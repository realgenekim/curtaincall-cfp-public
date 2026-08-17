(ns cfp-scheduler-killer.views.submission-row
  "Proposal review board, detail, decision controls, and organizer capture."
  (:require
   [cfp-scheduler-killer.committees :as committees]
   [cfp-scheduler-killer.events :as events]
   [cfp-scheduler-killer.inform :as inform]
   [cfp-scheduler-killer.review-plan :as review-plan]
   [cfp-scheduler-killer.review-work :as review-work]
   [cfp-scheduler-killer.reviews :as reviews]
   [cfp-scheduler-killer.views.avatar :as avatar]
   [cfp-scheduler-killer.views.compact-person :as compact-person]
   [cfp-scheduler-killer.views.format :as format]
   [cfp-scheduler-killer.views.reviewer-progress :as reviewer-progress]
   [cfp-scheduler-killer.voting-policy :as voting-policy]
   [clojure.string :as str]))

(defn chair-on-event?
  "Chairs decide; reviewers rate and argue (Gene, 2026-08-09). The status
   control is a chair's act — this is the view-side half; the endpoint
   enforces the same rule server-side."
  [event person]
  (boolean
    (when person
      (let [committee (first (events/committees-for-event (:id event)))]
        (some #(and (= (:person-id %) (:id person)) (= "chair" (:role %)))
              (when committee
                (committees/members-for-committee (:id committee))))))))

(defn fmt-mean [m] (when m (format "%.1f" (double m))))

(defn fmt-stars [s]
  (when s (let [d (double s)]
            (if (== d (Math/floor d)) (str (int d)) (format "%.1f" d)))))

(defn- private-note-block
  "The 'Notes to the Planning Committee' answer, badged. Collected on the public
   form, shown ONLY here — the BusyConf split, restored."
  [row]
  (let [field (first (filter #(:private %) (:form-snapshot row)))
        answer (when field (get (:answers row) (keyword (name (:id field)))))]
    (when answer
      [:div.pc-only
       [:span.pc-badge "PC ONLY"]
       [:span {:style "color:#666;"} (:label field) ": "] answer])))

(defn reviewed-by?
  [row person]
  (boolean (and person
                (some #(= (:id person) (:person-id %))
                      (:ratings row)))))

(defn score-for-person [scores person]
  (when person
    (:stars (first (filter #(= (:id person) (:person-id %)) scores)))))

(defn- star-form
  "1.0–5.0 in halves. URL-opened board rows use a native POST button; SSE-
   opened rows retain the zero-navigation update. The server owns both paths."
  [row person mine comment-form-id zero-navigation?]
  [:form.star-rating {:method "post"
                      :action (str "/api/submissions/" (:id row) "/rate")
                      :role (when zero-navigation? "radiogroup")
                      :aria-label (str "Your rating for " (get-in row [:answers :talk-title]))}
   [:span.mine-label "you:"]
   (if zero-navigation?
     (for [s reviews/star-steps]
       [:label.star-btn {:class (when (and mine (== (double s) (double mine))) "mine")}
        [:input.star-radio {:type "radio" :name "stars" :value (str s)
                            :aria-label (str s " stars")
                            :form comment-form-id
                            :checked (when (and mine (== (double s) (double mine))) true)
                            :onchange (str "postJSON('/api/submissions/" (:id row)
                                           "/rate', {stars: " s "})")}]
        (fmt-stars s)])
     (for [s reviews/star-steps]
       [:button.star-btn {:type "submit"
                          :name "stars"
                          :value (str s)
                          :aria-label (str "Rate " s " stars")
                          :aria-pressed (boolean (and mine (== (double s) (double mine))))
                          :class (when (and mine (== (double s) (double mine))) "mine")}
        (fmt-stars s)]))])

(defn star-histogram
  "The distribution as a BAR chart — five buckets (1★…5★ left to right,
   halves folding down), bar height = ratings in the bucket. Histograms have
   bars; the bullet points belong on the comments (Gene, 2026-08-10, second
   ruling — the first draft had it backwards). Hover names every rater.
   T1 (third ruling, same night): a numeral 1–5 under each bucket — without
   the axis the bars read as meaningless dots."
  [ratings]
  (let [counts (frequencies (map #(int (Math/floor (double (:stars %)))) ratings))]
    [:span.histo {:title (str/join " · " (map #(str (:person-name %) " ★"
                                                    (fmt-stars (:stars %)))
                                              ratings))}
     (for [b (range 1 6)]
       (let [n (get counts b 0)]
         [:span.hcol {}
          [:span.hbar {:class (when (zero? n) "empty")
                       :style (str "height:" (if (pos? n)
                                               (+ 3 (* 4 (min n 6)))
                                               2) "px")}]
          [:span.hnum b]]))]))

(defn- reviewer-input-controls
  [event row person mine zero-navigation?]
  (let [comment-form-id (str "comment-form-" (:id row))]
    (list
      (star-form row person mine comment-form-id zero-navigation?)
      ;; Board rows use zero-navigation postJSON + SSE. Submission detail uses
      ;; the native POST/303 contract so its first response is a fresh canonical
      ;; detail projection, never a board-shaped fragment or a cleared fiction.
      [:form (cond-> {:id comment-form-id
                      :method "post"
                      :action (str "/api/submissions/" (:id row) "/comment")}
               zero-navigation?
               (assoc :onsubmit
                      (str "event.preventDefault();"
                           "if(this.body.value.trim()){"
                           "const data=new FormData(this);"
                           "postJSON('/api/submissions/" (:id row)
                           "/comment', {body: this.body.value, stars: data.get('stars')});"
                           "this.body.value='';}")))
       [:input.comment-input {:type "text" :name "body" :placeholder "Add a comment…"}]
       [:button.ui.mini.basic.button {:type "submit"} "Post comment"]])))

(defn- opinions-block
  "Every reviewer score and every comment, inline — the anti-Sessionize move: opinions
   are never collapsed into a number you have to click to expand.

   The shape (Gene ratified T2, 2026-08-10): a tiny histogram of the score
   distribution, quote-lines carrying each comment WITH its author's score,
   and an 'also rated' line so reviewers who didn't comment stay visible —
   every score has a name somewhere on the row."
  [row]
  (let [ratings (:ratings row)
        stars-for (fn [person-id]
                    (some #(when (= person-id (:person-id %)) (:stars %)) ratings))
        commenter-ids (set (map :person-id (:comments row)))
        silent (remove #(commenter-ids (:person-id %)) ratings)]
    [:div.opinions-t2
     (if (seq ratings)
       (star-histogram ratings)
       [:span.op-none "no ratings yet"])
     [:div.quote-lines
      ;; A person's stars ride only their FIRST comment (Gene, 2026-08-10) —
      ;; repeating them on every line reads as re-voting.
      (let [first-comment-id (into {} (map (fn [[pid cs]] [pid (:id (first cs))])
                                           (group-by :person-id (:comments row))))]
        (for [c (:comments row)]
          ;; T5 (Gene, 2026-08-10): stars sit LEFT of the name, in a
          ;; fixed-width slot so scores read as a scannable column even on
          ;; lines that carry none (second comments, unrated commenters).
          [:div.quote-line {}
           [:span.op-slot
            (when (= (:id c) (get first-comment-id (:person-id c)))
              (when-let [st (stars-for (:person-id c))]
                [:span.op-stars "★" (fmt-stars st)]))]
           [:span.who (:person-name c)]
           " — " (:body c)]))
      (when (seq silent)
        [:div.quote-line.silent
         [:span.who "also rated/scored: "]
         (interpose " · "
                    (for [r silent]
                      [:span {}
                       (:person-name r) " "
                       [:span.op-stars "★" (fmt-stars (:stars r))]]))])]]))

(defn row-controls*
  [event row person mine chair? zero-navigation?]
  [:div.row-controls
   (if-not (reviews/rateable-status? row)
     [:div.ui.info.message.submission-state-read-only
      [:strong (:status row) " · Read-only"]
      [:p "Ratings are available only while a submission is Pending, in Accept Queue, or in Decline Queue."]]
     (reviewer-input-controls event row person mine zero-navigation?))
   (when chair?
     (list
       [:form {:method "post" :action (str "/api/submissions/" (:id row) "/status")}
        [:select {:name "status" :style "font-size:0.85em; padding:0.2em;"}
         (for [s (get-in event [:settings :statuses])]
           [:option (cond-> {:value s} (= s (:status row)) (assoc :selected true)) s])]
        [:button.ui.mini.basic.button {:type "submit"} "Set submission status"]]
       (let [terminal? (contains? (set inform/informable-statuses) (:status row))]
         [:a.ui.mini.basic.button
          {:href (if terminal?
                   (str "/events/" (:slug event) "/inform#decision-" (:id row))
                   (str "/events/" (:slug event) "/comms?submission-id=" (:id row)
                        "&template=request-changes"))}
          (if terminal? "Review decision message" "Ask speaker for changes")])))])

(defn- row-controls
  ([event row person mine]
   (row-controls event row person mine (chair-on-event? event person)))
  ([event row person mine chair?]
   (row-controls* event row person mine chair? true)))

(defn board-row
  "THE LEDGER (Gene ratified treatment A, 2026-08-09): ONE line per
   submission under sortable headings, the conversation as a sub-row where
   it exists, and controls included only in the acted row's server push.
   All <tr>s share one <tbody> so a single patch replaces the submission."
  ([event row person] (board-row event row person nil nil))
  ([event row person chair?*] (board-row event row person chair?* nil))
  ([event row person chair?* visibility-context]
   (board-row event row person chair?* visibility-context true))
  ([event row person chair?* visibility-context controls-open?]
   (let [sp (first (:speakers row))
         id (:id row)
         chair? (if (nil? chair?*) (chair-on-event? event person) chair?*)
         mine (score-for-person (:ratings row) person)
         reviewed? (reviewed-by? row person)
         recusal (when person (review-work/recusal-for id (:id person)))
         ;; BLIND REVIEW seam: does THIS reviewer see the presenter's identity
         ;; on this row?
         sp-visible? (or chair?
                         (if visibility-context
                           (voting-policy/presenter-visible-in-context?
                             (cond-> visibility-context
                               reviewed? (update :rated-submission-ids (fnil conj #{}) id))
                             row)
                           (voting-policy/presenter-visible? event row person reviewed?)))
         ;; REVEALED-MARK seam (bd-cm3h): the identity is shown to a viewer for
         ;; whom the policy WOULD otherwise hide it — a chair, or reveal-after-vote
         ;; and they've voted. The DURABLE policy (via visibility-policy-event, the
         ;; same authority the board handler and pill use) is the "would hide" fact;
         ;; the raw event's legacy :settings can be stale. With sp-visible? it means
         ;; "revealed on purpose, not leaked". Visible policy → hide off → no mark.
         revealed? (and sp-visible?
                        (voting-policy/hide-presenter-info?
                          (review-plan/visibility-policy-event event)))
         sp-name (if sp-visible?
                   (:name sp)
                   (str "Anonymous speaker · " (voting-policy/hidden-presenter-label row)))
         sp-title (when sp-visible? (format/not-blank (:title sp)))
         sp-org (when sp-visible? (format/not-blank (:org sp)))
         has-conversation? (or (seq (:ratings row))
                               (seq (:comments row)))]
     [:tbody.ledger {:id (str "sub-" id)}
      [:tr.ledger-row
       [:td.lg-flag
        [:form {:method "post" :action (str "/api/submissions/" id "/priority")}
         [:button.sub-flag {:class (when (:priority row) "on")
                            :type "submit" :title "Flag for discussion"} "🔥"]]]
       ;; Line 1 is the PERSON (Gene, 2026-08-10): face · name · role · org.
       ;; The whole person block is a SYNONYM for Read & rate (Gene,
       ;; 2026-08-10): photo, name, meta — one anchor to the detail page.
       [:td.lg-person
        (compact-person/person-link
          {:href (str "/events/" (:slug event) "/submissions/" id)
           :link-class (when-not sp-visible? "blind-person-link")
           ;; Blind means no human face at all: even a generated face implies
           ;; an identity and makes the reviewer wonder whether it is evidence.
           :image-src (when sp-visible?
                        (or (format/not-blank (:headshot-url sp))
                            (avatar/pool-face (or (:person-id sp) (:name sp)))))
           :image-class (when revealed? "revealed-mark")
           :image-alt sp-name
           :name sp-name
           :name-class (when-not sp-visible? "blind-presenter-label")
           :secondary (when revealed? [:div.revealed-note "revealed to you"])})]
       ;; Size LEFT of format (Gene, 2026-08-10).
       [:td.lg-size (get-in row [:answers :org-size])]
       [:td.lg-format
        (when-let [fmt (format/not-blank (get-in row [:answers :session-format]))]
          [:span.fmt-chip fmt])]
       [:td.lg-mean (or (fmt-mean (:mean row)) "—")
        (when (:split? row) [:span.b-split " SPLIT"])]
       [:td.lg-n
        (:n row)
        (when (< (or (:n row) 0)
                 (or (:coverage-target visibility-context) 2))
          [:span.coverage-warning
           {:title (str "Needs another review to reach the "
                        (or (:coverage-target visibility-context) 2)
                        "-review rule")
            :aria-label "Under review-count target"}
           " ⚠"])]
       [:td.lg-you (if mine (fmt-mean mine) "–")]
       ;; TWO verbs, always visible (Gene, 2026-08-10): act HERE, or go read
       ;; the whole submission. The quiet one asks the server to push the
       ;; inline card for just this row.
       ;; Primary verb FIRST and gold: Read & rate is the default act; Quick
       ;; rate is the shortcut and stays quiet (Gene, 2026-08-10, option 1).
       [:td.lg-acts
        [:a.act-read {:href (str "/events/" (:slug event) "/submissions/" id)}
         (if (reviews/rateable-status? row) "Read & rate →" "Read →")]
        [:div.act-status-line
         [:span.ui.mini.label (:status row)]
         (when (reviews/rateable-status? row)
           [:a.act-rate
            {:href (str "/events/" (:slug event) "/board?open=" id "#sub-" id)}
            (if recusal "Review recused ▾" "Quick rate ▾")])]]]
      ;; Speaker role and organization need the same horizontal room as the
      ;; proposal title. Keeping them in the narrow identity cell made long,
      ;; useful titles wrap into an unreadable vertical stack.
      (when (or sp-title sp-org)
        [:tr.ledger-title-row
         [:td.lg-spacer]
         [:td.lg-title-cell {:colspan 8}
          [:div.lg-title-indent
           (when sp-title [:span.b-facts sp-title])
           (when (and sp-title sp-org) [:span.b-facts " · "])
           (when sp-org [:span.b-facts sp-org])]]])
      ;; Line 2 is the TALK — the FULL table width; vertical space is precious.
      [:tr.ledger-title-row
       [:td.lg-spacer]
       [:td.lg-title-cell {:colspan 8}
        ;; The indent lives on an INNER wrapper: td padding rules fight each
        ;; other across layers/importants; a div has no competitors. It puts
        ;; the title on the same LEFT RAIL as the name — the photo is an
        ;; ornament column and text never aligns to it (Gene, 2026-08-10).
        ;; No chevron (Gene, 2026-08-10) — the focus affordance lives on the
        ;; YOU cell instead: click your own rating to rate.
        [:div.lg-title-indent
         [:a.lg-title-link {:href (str "/events/" (:slug event) "/submissions/" id)}
          (get-in row [:answers :talk-title])]
         " "
         (when-let [t (get-in row [:answers :track])]
           [:span.b-facts {:style "margin-left:0.7em;"} t])
         (when (> (count (:speakers row)) 1)
           [:span.b-facts {:style "margin-left:0.7em;"}
            "Speakers: "
            (interpose " · "
                       (for [speaker (:speakers row)]
                         [:span {}
                          (if sp-visible? (:name speaker) voting-policy/hidden-marker)
                          " (" (or (:role speaker) "Speaker") ")"]))])]]]
      (when has-conversation?
        [:tr.ledger-sub
         [:td.lg-spacer]
         [:td {:colspan 8}
          (opinions-block row)
          (private-note-block row)]])
      (when (and person (reviews/rateable-status? row) (or controls-open? recusal))
        [:tr.ledger-controls
         [:td.lg-spacer]
         [:td {:colspan 8}
          ;; The open card sits INSIDE the submission's white envelope (the
          ;; whole tbody surfaces on :target) and names itself with a title
          ;; bar (Gene, 2026-08-10). href \"#\" clears the :target — ✕ tucks
          ;; the card away.
          (reviewer-progress/recusal-control row person recusal)
          (when-not recusal
            [:div.rate-card
             [:div.rate-card-head
              [:span.rate-card-title "Quick rate"
               ;; A persistent truth, not a toast: your rating IS saved.
               (when reviewed? [:span.rate-card-saved "Saved ✓"])]
              [:span.card-close-group
               [:kbd.esc-hint "esc"]
               [:a.card-close {:href "#" :title "Close (esc)"} "✕"]]]
             (row-controls* event row person mine chair? false)])]])])))
