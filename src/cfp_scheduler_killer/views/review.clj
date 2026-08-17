(ns cfp-scheduler-killer.views.review
  "Proposal review board, detail, decision controls, and organizer capture."
  (:require
   [cfp-scheduler-killer.committees :as committees]
   [cfp-scheduler-killer.domain.review-plan :as domain-review-plan]
   [cfp-scheduler-killer.events :as events]
   [cfp-scheduler-killer.forms :as forms]
   [cfp-scheduler-killer.inform :as inform]
   [cfp-scheduler-killer.review-plan :as review-plan]
   [cfp-scheduler-killer.review-work :as review-work]
   [cfp-scheduler-killer.reviews :as reviews]
   [cfp-scheduler-killer.store :as store]
   [cfp-scheduler-killer.submissions :as submissions]
   [cfp-scheduler-killer.views.avatar :as avatar]
   [cfp-scheduler-killer.views.form-controls :as form-controls]
   [cfp-scheduler-killer.views.format :as format]
   [cfp-scheduler-killer.views.organizer-layout :as organizer-layout]
   [cfp-scheduler-killer.views.policy :as view-policy]
   [cfp-scheduler-killer.views.review-assignment :as review-assignment]
   [cfp-scheduler-killer.views.reviewer-progress :as reviewer-progress]
   [cfp-scheduler-killer.views.submission-content :as submission-content-view]
   [cfp-scheduler-killer.views.submission-row :as submission-row]
   [cfp-scheduler-killer.voting-policy :as voting-policy]
   [clojure.data.json :as json]
   [clojure.string :as str]
   [datastar-kit.ds :as ds]))

;; The crown jewel, and the whole doctrine in one screen: every score and every
;; comment visible inline, no clicks, no assignments, no rounds. Two visual rows
;; per submission — facts on top, opinions underneath — because the fulcro app
;; proved that reviewers compare talks by reading each other, not by drilling in.
;; Every control is a plain <form>. POST → mutate → SSE push to everyone else →
;; 303 back for you. Datastar only receives; it never decides.

(defn- board-qs
  "The board's WHOLE view state as a query string — the URL is the state
   (Gene, 2026-08-10: 'give it to you and you can replicate it'). The optional
   trailing `show-all?` keeps the 'show all tracks' escape sticky across sorts
   and searches; without it a re-sort would snap a reviewer back to their rooms."
  ([sort-key q status track] (board-qs sort-key q status track false))
  ([sort-key q status track show-all?]
   (str "?sort=" sort-key
        (when (format/not-blank q) (str "&q=" (java.net.URLEncoder/encode q "UTF-8")))
        (when (format/not-blank status)
          (str "&status=" (java.net.URLEncoder/encode status "UTF-8")))
        (when (format/not-blank track)
          (str "&track=" (java.net.URLEncoder/encode track "UTF-8")))
        (when show-all? "&all=1"))))

(defn capture-page
  "Ten seconds from 'a talk arrived in my DMs' to a row on the board.

   Deliberately parses NOTHING. Gene's actual failure mode is that a good talk
   arrives as an email or a LinkedIn message and dies in the inbox because
   getting it into the tool costs more than it is worth. So: paste it, name it
   if you can be bothered, done. The committee can tidy it later — a messy row
   on the board beats a perfect one that was never created."
  [event {:keys [person values errors]}]
  (let [v #(get values % "")]
    (organizer-layout/organizer-shell
      (str "Capture a submission — " (:name event))
      {:event event :active :submissions :person person :crumb "Submissions"}
      (organizer-layout/header "Add a submission on someone's behalf"
                               "Paste what you got. Nothing else is required."
                               [:a.ui.basic.button {:href (str "/events/" (:slug event) "/submissions")}
                                "All submissions"])

      (when (:_ errors) [:div.ui.negative.message (str/join " " (:_ errors))])

      [:form.ui.form {:method "post" :action (str "/api/events/" (:slug event) "/capture")}
       [:div.field {:class (when (:captured-text errors) "error")}
        [:label "Paste the email or DM"]
        [:textarea {:name "captured-text" :rows 10 :autofocus true
                    :placeholder "Hi Gene — I'd love to talk about how we rebuilt…"}
         (v :captured-text)]
        [:div.field-hint "Stored verbatim. We don't parse it — you or the committee "
         "can fill the real fields in later."]
        (form-controls/field-error errors :captured-text)]

       [:div.three.fields
        [:div.field
         [:label "Talk title " [:span.optional "(optional)"]]
         [:input {:type "text" :name "title" :value (v :title)}]]
        [:div.field
         [:label "Speaker name " [:span.optional "(optional)"]]
         [:input {:type "text" :name "speaker-name" :value (v :speaker-name)}]]
        [:div.field {:class (when (:speaker-email errors) "error")}
         [:label "Speaker email " [:span.optional "(optional)"]]
         [:input {:type "email" :name "speaker-email" :value (v :speaker-email)}]
         (form-controls/field-error errors :speaker-email)]]

       [:div.two.fields
        [:div.field
         [:label "Organization " [:span.optional "(optional)"]]
         [:input {:type "text" :name "speaker-org" :value (v :speaker-org)}]]
        [:div.field
         [:label "Where did this come from?"]
         [:select {:name "source"}
          (for [[val label] [["email" "Email"] ["linkedin-dm" "LinkedIn DM"]
                             ["other" "Somewhere else"]]]
            [:option (cond-> {:value val}
                       (= val (v :source)) (assoc :selected true))
             label])]]]

       [:button.ui.primary.button {:type "submit"} "Capture it"]
       [:div.field-hint {:style "margin-top:0.6em;"}
        "It lands on the review board as Pending, flagged as captured on someone's "
        "behalf. No email is sent to the speaker — this is your note, not their "
        "submission."]])))

(defn coverage-bar
  "The headline number. Wrapped in a stable id so SSE can repaint just this."
  [event coverage]
  (let [{:keys [covered total target pct]} coverage]
    [:div#coverage-bar.coverage
     [:div.coverage-headline (format "%.0f%%" pct) " reviewed"]
     [:div.coverage-track
      [:div.coverage-fill {:style (str "width:" (format "%.1f" pct) "%;")}]]
     [:div.coverage-note
      (str covered "/" total " have ≥" target " review" (when (not= 1 target) "s"))]]))

(defn inform-banner
  "The Sessionize warning, adopted with pride: a decision nobody has been told
   about is not a decision anyone can act on."
  [event n]
  (when (pos? n)
    [:div.ui.warning.message
     [:div.header n " decision" (when (not= 1 n) "s") " not yet communicated"]
     [:p "Speakers are not automatically informed. Until you tell them, they see "
      [:strong "Under review"] " — whatever the committee decided."]
     [:a.ui.small.orange.button {:href (str "/events/" (:slug event) "/inform")}
      "Inform speakers"]]))

(defn notice-region
  "What the server said when it would not do what you asked — or how something
   it did on your behalf turned out.

   Three things about this region are deliberate:

     1. It is ALWAYS rendered, empty when there is nothing to say. A Datastar
        patch needs its target to exist already (global CLAUDE.md, NEVER #9); an
        element conjured only when there is an error is an element the push
        cannot find.
     2. Nothing about it lives in the browser. The text, the tone and the moment
        it disappears are server state (`notices`), so there is no timer, no
        `classList.toggle`, and no response body for the client to read.
     3. Dismiss is a plain form POST like every other control on this page. It
        works with JavaScript switched off, which is the same promise the public
        CFP form makes."
  [event notice]
  [:div#validation-notice
   (when notice
     [:div.ui.message {:class (if (= :ok (:kind notice)) "positive" "warning")
                       :style "margin-bottom:1em;"}
      [:div.header (if (= :ok (:kind notice)) "Done" "That didn't go through")]
      [:p {:style "margin:0.4em 0 0.6em 0;"} (:message notice)]
      (when (:detail notice)
        [:div.field-hint {:style "margin-top:0;"} (:detail notice)])
      [:form {:method "post"
              :action (str "/api/events/" (:slug event) "/notice/dismiss")}
       [:button.ui.mini.basic.button {:type "submit"} "Dismiss"]]])])

(defn decision-notice
  "Build a one-request confirmation from a decision redirect.

   The status must belong to this event and the result must be one emitted by
   the status handler, so a hand-edited URL cannot manufacture arbitrary copy."
  [event result status]
  (when-let [status (reviews/canonical-status (get-in event [:settings :statuses]) status)]
    (case result
      "saved" {:kind :ok :message (str "Decision saved: " status ".")}
      "unchanged" {:kind :ok
                   :message (str "Decision already " status
                                 "; no new change was recorded.")}
      nil)))

(defn- work-queue-link
  [active-key preset slug q status track show-all? coverage]
  (let [key (:key preset)
        coverage? (= key "needs-reviews")
        active? (= active-key key)
        qs (board-qs key q status track show-all?)]
    [:a.ui.segment.column
     {:class (when active? "raised")
      :href (str "/events/" slug "/board" qs)
      :title (:help preset)
      :aria-current (when active? "page")
      :onclick (str "postJSON('/api/events/" slug "/board/sort',"
                    (json/write-str {:sort key :q (or q "")
                                     :status (or status "") :track (or track "")
                                     :all (if show-all? "1" "")})
                    ");history.replaceState(null,'','" qs "');return false")}
     [:div.ui.tiny.label (if coverage? "COVERAGE" "DECISION")]
     [:h3 {:style "margin:0.5em 0 0.2em;"}
      (if coverage? "Coverage worklist" "Decision queue")]
     [:div {:style "font-weight:700;"}
      (if coverage?
        "# ratings ascending"
        "Mean stars descending")]
     [:p.field-hint
      (if coverage?
        (str (:target coverage) "-review rule: rate the least-covered submissions first.")
        (str "Compare mean stars directly; Reviews shows confidence and warns below the "
             (:target coverage) "-review target."))]
     (when coverage? (coverage-bar nil coverage))]))

(defn- status-chip [current-status slug label count* sort-key q]
  (let [target (when (not= current-status label) label)
        qs (str "?sort=" sort-key
                (when (format/not-blank q) (str "&q=" (java.net.URLEncoder/encode q "UTF-8")))
                (when target (str "&status=" (java.net.URLEncoder/encode target "UTF-8"))))]
    [:a.chip {:class (when (= current-status label) "on")
              :href (str "/events/" slug "/board" qs)}
     label " " count*]))

(defn- status-filters [event current-status status-counts sort-key q]
  [:div.board-controls.board-status-controls
   [:span.field-hint {:style "margin:0;"} "Status:"]
   (for [s (get-in event [:settings :statuses])
         :let [c (get status-counts s 0)]
         :when (pos? c)]
     (status-chip current-status (:slug event) s c sort-key q))])

(defn submissions-sparkline
  "Submissions over the CFP's life as a tiny server-drawn SVG (Gene,
   2026-08-09: 'a sparkline of how many submissions we have over time').
   Cumulative count from the call's open (or the first submission) to its
   close (or now) — a rising line IS the momentum story. No JS: the server
  draws, the browser displays."
  [event rows coverage]
  (let [target (:target coverage)
        ats (sort (keep :created-at rows))
        review-evidence :ratings]
    (when (seq ats)
      (let [now (store/now-inst)
            t0-inst (or (:cfp-opens-at event) (first ats))
            closes-at (:cfp-closes-at event)
            t-end-inst (or closes-at now)
            t0 (inst-ms t0-inst)
            t-end (inst-ms t-end-inst)
            t-max (max t-end (inst-ms (last ats)))
            span (double (max 1 (- t-max t0)))
            w 240.0 h 34.0 pad 3.0
            n (count ats)
            x #(-> (- (inst-ms %) t0) (/ span) (* (- w (* 2 pad))) (+ pad))
            y #(- h pad (* (- h (* 2 pad)) (/ (double %) n)))
            xy (fn [at k] (str (format "%.1f" (x at)) "," (format "%.1f" (y k))))
            pts (map-indexed (fn [i at] (xy at (inc i))) ats)
            ;; carry the line flat to \"now\" so the reader sees where we ARE
            now-x (format "%.1f" (max (x (last ats)) (min (- w pad) (max pad (x now)))))
            path (str (format "%.1f" pad) "," (format "%.1f" (y 0)) " "
                      (str/join " " pts) " " now-x "," (format "%.1f" (y n)))
            ;; THE REVIEWED FILL (Gene, 2026-08-09; recolored 2026-08-10:
            ;; submissions RED, reviewed GREEN): when each submission reached
            ;; the coverage target — the instant its TARGET-th rating landed.
            ;; The wedge between red line and green fill IS the backlog.
            target (or target 2)
            reviewed-ats (sort (keep (fn [r]
                                       (let [rats (sort (keep :at (review-evidence r)))]
                                         (when (>= (count rats) target)
                                           (nth rats (dec target)))))
                                     rows))
            rn (count reviewed-ats)
            r-pts (map-indexed (fn [i at] (xy at (inc i))) reviewed-ats)
            fill (when (pos? rn)
                   (str (format "%.1f" pad) "," (format "%.1f" (y 0)) " "
                        (str/join " " r-pts) " "
                        now-x "," (format "%.1f" (y rn)) " "
                        now-x "," (format "%.1f" (y 0))))
            days-left (when closes-at
                        (let [d (.toDays (java.time.Duration/between
                                           now closes-at))]
                          (when (pos? d) d)))
            ;; the third series (Gene, 2026-08-10): cumulative RATINGS in
            ;; amber, scaled to its own max — reviewing effort overlaid on
            ;; arrivals, so the wedge between curves is the story
            rating-ats (sort (keep :at (mapcat review-evidence rows)))
            rk (count rating-ats)
            yr (fn [k] (- h pad (* (- h (* 2 pad)) (/ (double k) (max 1 rk)))))
            rate-path (when (pos? rk)
                        (str (format "%.1f" pad) "," (format "%.1f" (yr 0)) " "
                             (str/join " " (map-indexed
                                             (fn [i at]
                                               (str (format "%.1f" (x at)) ","
                                                    (format "%.1f" (yr (inc i)))))
                                             rating-ats)) " "
                             now-x "," (format "%.1f" (yr rk))))]
        ;; Emitted as an <img data:> URI, NOT inline SVG: Datastar's morph is
        ;; unreliable at patching inline-SVG attributes, which is why the
        ;; time-travel scrub moved the table but froze the sparkline (Gene,
        ;; 2026-08-10). A src attribute swap always repaints. Colors inline —
        ;; stylesheet rules cannot reach inside an <img>.
        [:div.spark-block
         [:img.spark
          {:width (int w) :height (int h) :alt ""
           :src (str "data:image/svg+xml;base64,"
                     (.encodeToString
                       (java.util.Base64/getEncoder)
                       (.getBytes
                         (str "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 "
                              (int w) " " (int h) "' preserveAspectRatio='none'>"
                              (when fill
                                (str "<polygon points='" fill
                                     "' fill='#1B7A4B' fill-opacity='0.22'/>"))
                              (when rate-path
                                (str "<polyline points='" rate-path
                                     "' fill='none' stroke='#D4880F' stroke-width='1.3'/>"))
                              "<polyline points='" path
                              "' fill='none' stroke='#B3261E' stroke-width='1.6'/>"
                              "<circle cx='" now-x "' cy='" (format "%.1f" (y n))
                              "' r='2.5' fill='#B3261E'/></svg>")
                         "UTF-8")))}]
         [:div.spark-label
          [:strong.spark-subs n] " submission" (when (not= 1 n) "s")
          [:span.sep "·"] [:strong.spark-rev rn] " fully reviewed"
          (when-let [pct (:pct coverage)]
            (str " (" (Math/round (double pct)) "%)"))
          (if closes-at
            (when days-left
              (list [:span.sep "·"] (str days-left " days left")))
            (list [:span.sep "·"] "no close date set"))]
         ;; The raw work ledger under the headline: every review and every
         ;; comment counted (Gene, 2026-08-10).
         (let [n-reviews (reduce + 0 (map (comp count review-evidence) rows))
               n-comments (reduce + 0 (map (comp count :comments) rows))]
           [:div.spark-label.spark-counts
            (str "(" n-reviews " review" (when (not= 1 n-reviews) "s")
                 " · " n-comments " comment" (when (not= 1 n-comments) "s") ")")])
         ;; The axis: when the line starts and when the call closes — the
         ;; two dates that give the shape its meaning.
         (let [zone (try (java.time.ZoneId/of (or (:tz event) "UTC"))
                         (catch Exception _ (java.time.ZoneId/of "UTC")))
               fmt (java.time.format.DateTimeFormatter/ofPattern "MMM d")
               day (fn [^java.time.Instant i] (.format fmt (.atZone i zone)))]
           [:div.spark-axis
            [:span (day t0-inst)]
            [:span (day t-end-inst)]])]))))

(defn- track-chip
  "One facet chip in the Track row (Gene ratified 2026-08-09: tracks FILTER,
   never gate — everyone sees everything; a chip just narrows). `label` nil
   renders the honest \"(no track)\" bucket for submissions whose form
   snapshot predates the field; its query value is \"(none)\"."
  [current-track slug label count* sort-key q status & [show-all?]]
  (let [value (or label "(none)")
        on? (= current-track value)
        qs (str "?sort=" sort-key
                (when (format/not-blank q) (str "&q=" (java.net.URLEncoder/encode q "UTF-8")))
                (when (format/not-blank status)
                  (str "&status=" (java.net.URLEncoder/encode status "UTF-8")))
                (when-not on? (str "&track=" (java.net.URLEncoder/encode value "UTF-8")))
                ;; Preserve the open-table escape when a facet is toggled OFF, so
                ;; clearing a chip returns to all tracks, not the committee rooms.
                (when (and on? show-all?) "&all=1"))]
    [:a.chip {:class (when on? "on")
              :href (str "/events/" slug "/board" qs)}
     (or label "no track") " " count*]))

(defn- rooms-toggle
  "The 'filter, not wall' line: a track-scoped reviewer's board opens filtered
   to their tracks, but one click reveals the whole table — and one click back
   restores their default queue. Renders nothing for an all-tracks reviewer.

   `scoped-tracks` is the set actively narrowing the view (nil once escaped);
   `my-tracks` is the reviewer's scope regardless of the current escape. A plain
   GET link — the escape is navigation, not a mutation, and the URL stays the
   whole state (board-qs)."
  [slug {:keys [scoped-tracks my-tracks sort-key q status]}]
  (when (seq my-tracks)
    (let [base (board-qs sort-key q status nil)
          rooms (str/join ", " (sort my-tracks))]
      [:div.board-controls.rooms-toggle
       (if (seq scoped-tracks)
         (list
           [:span.field-hint {:style "margin:0;"}
            (str "Showing: your tracks — " rooms ".")]
           [:a.chip {:href (str "/events/" slug "/board" base
                                (if (str/includes? base "?") "&" "?") "all=1")}
            "Show all tracks"])
         (list
           [:span.field-hint {:style "margin:0;"}
            "Showing every track — the open table."]
           [:a.chip {:href (str "/events/" slug "/board" base)}
            "Back to your tracks"]))])))

(defn- sort-click
  "One sorting <a>: click POSTs the sort (server re-renders and pushes
   #board-region down THIS viewer's SSE stream — no reload) and stamps the
   full state into the URL with replaceState (browser-owned URL bar; every
   value baked server-side at render). href is the no-JS fallback: the same
   state as a plain GET. Plain onclick, not data-star-on — these live inside
   the morphed region and must cost nothing per push."
  [label next-key arrow slug q status track & [show-all?]]
  (let [qs (board-qs next-key q status track (boolean show-all?))]
    [:a {:href (str "/events/" slug "/board" qs)
         :onclick (str "postJSON('/api/events/" slug "/board/sort',"
                       (json/write-str {:sort next-key :q (or q "")
                                        :status (or status "") :track (or track "")
                                        :all (if show-all? "1" "")})
                       ");history.replaceState(null,'','" qs "');return false")}
     label arrow]))

(defn- sort-th
  "A sortable ledger heading: ascending, click again for descending."
  [label col current slug q status track & [show-all?]]
  (let [desc (str col "-desc")
        next-key (if (= current col) desc col)
        arrow (cond (= current col) " ↑"
                    (= current desc) " ↓"
                    :else "")]
    [:th {:class (str "th-" col (when (str/starts-with? (str current) col) " on"))}
     (sort-click label next-key arrow slug q status track show-all?)]))

(defn- presenter-visibility-policy-data
  "Resolve the effective presenter policy without rendering it."
  [event review-plan]
  {:policy (or (:presenter-visibility review-plan)
               {:mode (voting-policy/presenter-visibility-mode event)})
   :definition (or (:presenter-visibility-definition review-plan)
                   domain-review-plan/presenter-visibility-policy-definition)})

(defn- content-status-control [event row person]
  [:div.ui.segment
   [:div [:strong "Content status"] " · " (submissions/content-status row)]
   [:div.field-hint
    "Public program gate: Approved content appears once the session is Accepted, "
    "the speaker has been Notified, and the session is scheduled. Draft and In review stay private. "
    [:a {:href (str "/program/" (:slug event)) :target "_blank" :rel "noopener"}
     "Preview public program ↗"]]
   (when (submission-row/chair-on-event? event person)
     [:form.ui.form {:method "post"
                     :action (str "/api/submissions/" (:id row) "/content-status")}
      [:select {:name "status"}
       (for [status submissions/content-statuses]
         [:option (cond-> {:value status}
                    (= status (submissions/content-status row)) (assoc :selected true))
          status])]
      [:button.ui.small.button {:type "submit"} "Save content status"]])])

(defn- mention-control
  "\"Ask a colleague to look\" — a plain form (no client JS): pick any committee
   person on the event (CROSS-ROOM: the picker is the whole-event roster, never
   this reviewer's own committee) plus an optional note, and POST a mention. An
   invitation, not an assignment — the copy says so."
  [event row person]
  (let [people (->> (reviews/mentionable-people (:id event) (:id person))
                    (remove #(review-work/recused? (:id row) (:person-id %))))]
    (when (seq people)
      [:div.mention-control
       [:div.cfp-section-title {:style "margin-top:0.9em;"} "Ask a colleague to look"]
       [:form {:method "post"
               :action (str "/api/submissions/" (:id row) "/mention")}
        [:select {:name "to-person-id" :aria-label "Colleague to ask"}
         [:option {:value ""} "Pick a colleague…"]
         (for [m people]
           [:option {:value (:person-id m)}
            (str (:name m) (when (:email m) (str " · " (:email m))))])]
        [:input.mention-note {:type "text" :name "note"
                              :placeholder "Optional: why them? (\"you know this space\")"}]
        [:button.ui.mini.basic.button {:type "submit"} "Ask them to look"]]
       [:div.field-hint {:style "margin-top:0.3em;"}
        "A soft nudge — it lands on their board, no obligation."]])))

(defn mentions-shelf
  "\"You were asked to look at these\" — the recipient's soft shelf, shown on
   their board. Cross-room by nature: a talk from any track appears here if
   someone tapped this person about it. Renders nothing when the shelf is empty,
   so a reviewer with no mentions sees exactly the board they always saw."
  [event shelf]
  (when (seq shelf)
    [:div.mentions-shelf {:id "mentions-shelf"}
     [:div.cfp-section-title "You were asked to look at these"]
     [:ul.mention-list
      (for [m shelf]
        [:li.mention-item {}
         [:a {:href (str "/events/" (:slug event) "/submissions/" (:submission-id m))}
          (or (format/not-blank (:title m)) "a submission")]
         [:span.mention-from " — " (:from-name m) " asked"]
         (when (format/not-blank (:note m))
           [:span.mention-why " · \"" (:note m) "\""])])]
     [:div.field-hint {:style "margin-top:0.3em;"}
      "Invitations, not assignments — look if you can, ignore if you can't."]]))

(defn- detail-row-controls
  [event row person mine]
  (submission-row/row-controls*
    event row person mine (submission-row/chair-on-event? event person) false))

(defn submission-detail-page
  "One talk, in full — every answer under its own snapshot label, the speaker
   block, and the same inline controls as the board so you never have to go
   back to act on what you just read."
  [event row {:keys [person coverage-target notice recusal chair?
                     content-history edit-errors edit-values]}]
  (let [sp (first (:speakers row))
        mine (submission-row/score-for-person (:ratings row) person)
        reviewed? (submission-row/reviewed-by? row person)
        ;; BLIND REVIEW seam: same per-reviewer decision as the board row.
        sp-visible? (or chair?
                        (voting-policy/presenter-visible? event row person reviewed?))
        ;; REVEALED-MARK seam (bd-cm3h): shown to a viewer the policy WOULD hide
        ;; (chair, or reveal-after-vote and voted). Durable policy via
        ;; visibility-policy-event (idempotent — detail already adapts the event).
        ;; Off under a visible policy.
        revealed? (and sp-visible?
                       (voting-policy/hide-presenter-info?
                         (review-plan/visibility-policy-event event)))]
    (organizer-layout/organizer-shell
      (str (get-in row [:answers :talk-title]) " — " (:name event))
      {:event event :active :board :person person :crumb "Review Board"}
      (notice-region event notice)
      (organizer-layout/header (get-in row [:answers :talk-title])
                               (if sp-visible?
                                 (str (:name sp) (when (:org sp) (str " · " (:org sp)))
                                      (when (submissions/captured? row)
                                        (str " · captured on their behalf ("
                                             (str/replace (str (:source row)) #"^on-behalf-of:" "") ")")))
                                 (voting-policy/hidden-presenter-label row))
                               [:a.ui.basic.button {:href (str "/events/" (:slug event) "/board")}
                                "← Board"]
                               (when (= "Accepted" (:status row))
                                 [:a.ui.button {:href (str "/events/" (:slug event)
                                                           "/submissions/" (:id row) "/manage")}
                                  "Manage speaker"]))

      (list
        (content-status-control event row person)
        (when chair?
          (review-assignment/assignment-control event row))
        (when chair?
          (submission-content-view/organizer-controls
            event row {:history content-history
                       :errors edit-errors
                       :values edit-values})))

      ;; TREATMENT B, "Basecamp" (Gene ratified 2026-08-10): the proposal at a
      ;; comfortable reading measure with the speaker in the rail, and the
      ;; committee as a full-width THREAD at the page's foot — you walk through
      ;; the talk before you reach the room's opinions. The geometry is the
      ;; anti-anchoring.
      (list [:div.b-facts {:style "margin:-0.6em 0 1.2em;"} (get-in row [:answers :session-format]) (when-let [t (get-in row [:answers :track])] (str " · " t)) (when-let [o (get-in row [:answers :org-size])] (str " · " o)) (when-let [i (get-in row [:answers :industry])] (str " · " i))] (when (> (count (:speakers row)) 1) [:div.ui.segment.submission-speakers [:strong "Speakers and roles"] [:div.ui.relaxed.list (for [speaker (:speakers row)] [:div.item [:div.header (if sp-visible? (:name speaker) voting-policy/hidden-marker)] [:div.description (if sp-visible? (str (or (:role speaker) "Speaker") " · " (:email speaker) (when-let [org (:org speaker)] (str " · " org))) (or (:role speaker) "Speaker"))]])]]))

      [:div.sd-layout
       [:div.sd-main
        [:div.cfp-section-title "The proposal"]
        ;; Rendered from the SNAPSHOT, so this reads exactly as it did the day
        ;; it was submitted even if the live form has moved on.
        [:dl.facts.sd-prose
         (for [f (submissions/session-fields (:form-snapshot row))
               :let [a (get (:answers row) (keyword (name (:id f))))
                     captured? (= :captured-text (keyword (name (:id f))))]
               :when a]
           (list [:dt {}
                  (:label f)
                  (when (:private f) [:span.pc-badge {:style "margin-left:0.5em;"} "PC ONLY"])]
                 [:dd {:class (when (:private f) "private-note")
                       :style (if captured?
                                ;; The raw paste, verbatim and visibly so.
                                "font-weight:400; white-space:pre-wrap; font-family:ui-monospace,Menlo,monospace; font-size:0.86em; background:#fafafa; padding:0.6em 0.8em; border-radius:4px;"
                                "font-weight:400; white-space:pre-wrap;")}
                  a]))]]

       ;; EVERYTHING we hold about the speaker, labeled (Gene, 2026-08-10:
       ;; "all the speaker information — especially LinkedIn").
       [:div.sd-rail
        (if-not sp-visible?
          ;; Blinded: the entire speaker rail is presenter identity. Show the
          ;; marker so the reviewer knows the info exists but is withheld.
          [:div.roster-card
           [:span.sd-photo.blind-avatar {:aria-hidden "true"} "?"]
           [:div.sp-name.blind-presenter-label
            (str "Anonymous speaker · " (voting-policy/hidden-presenter-label row))]
           [:div.sp-meta "Blind review"]]
          [:div.roster-card
           [:img.sd-photo {:src (or (format/not-blank (:headshot-url sp))
                                    (avatar/pool-face (or (:person-id sp) (:name sp))))
                           :class (when revealed? "revealed-mark")
                           :alt (:name sp)}]
           [:div.sp-name (:name sp)]
           (when revealed? [:div.revealed-note "revealed to you"])
           (when-let [t (format/not-blank (:title sp))] [:div.sp-meta t])
           (when-let [o (format/not-blank (:org sp))] [:div.sp-meta o])
           [:dl.facts.sp-facts
            [:dt "Email"]
            [:dd [:a {:href (str "mailto:" (:email sp))} (:email sp)]]
            (when-let [u (format/not-blank (:linkedin-url sp))]
              (list [:dt {} "LinkedIn"]
                    [:dd {}
                     [:a {:href u :target "_blank" :rel "noopener"}
                      (str/replace u #"^https?://(www\.)?" "")]]))
            (when-let [u (format/not-blank (:sessionize-url sp))]
              (list [:dt {} "Sessionize"]
                    [:dd {}
                     [:a {:href u :target "_blank" :rel "noopener"}
                      (str/replace u #"^https?://(www\.)?" "")]]))
            (when-let [u (format/not-blank (:website-url sp))]
              (list [:dt {} "Website"]
                    [:dd {}
                     [:a {:href u :target "_blank" :rel "noopener"}
                      (str/replace u #"^https?://(www\.)?" "")]]))
            (when-let [u (format/not-blank (:twitter-url sp))]
              (list [:dt {} "Twitter / X"]
                    [:dd {}
                     [:a {:href u :target "_blank" :rel "noopener"}
                      (str/replace u #"^https?://(www\.)?" "")]]))]
           (when (format/not-blank (:bio sp))
             (list [:div.cfp-section-title {:style "margin-top:0.9em;"} "Bio"]
                   [:div.sp-bio {} (:bio sp)]))])]]

      ;; The conversation, at the artifact's foot.
      [:div.sd-thread {:id (str "sub-" (:id row))}
       [:div.cfp-section-title "The committee — the conversation so far"]
       [:div.verdict-line
        [:span.avg {:title "Mean signed Stars"}
         (or (submission-row/fmt-mean (:mean row)) "—")]
        [:span (:n row) " vote" (when (not= 1 (:n row)) "s")]
        [:span.sep "·"]
        [:span (count (:comments row)) " comment"
         (when (not= 1 (count (:comments row))) "s")]
        (when (:split? row) [:span.b-split "SPLIT"])]
       (let [items (sort-by :at
                            (concat (map #(assoc % :kind :rating) (:ratings row))
                                    (map #(assoc % :kind :comment) (:comments row))))]
         (if (empty? items)
           [:p.field-hint "Nobody has weighed in yet — you're first."]
           (for [it items]
             [:div.bubble {}
              [:span.sd-avatar (avatar/initials (:person-name it))]
              [:div.balloon
               [:span.who (:person-name it)]
               [:span.when (format/fmt-instant (:at it) (:tz event))]
               (if (= :comment (:kind it))
                 [:div.balloon-body (:body it)]
                 [:span.stars-inline
                  " rated ★ "
                  (submission-row/fmt-mean (:stars it))])]])))
       (when (and person recusal)
         [:div.ui.info.message.review-recusal-current-state {:role "status"}
          [:div.header "Current status: Recused"]
          [:p "This submission is not in your actionable review queue."]])
       (when person
         [:div.rate-strip
          [:span.lbl "Your take" (when reviewed? [:span.rate-card-saved " · Saved ✓"])]
          (reviewer-progress/recusal-control row person recusal)
          (when-not recusal (detail-row-controls event row person mine))])
       (when (and person (reviews/rateable-status? row) (not recusal))
         (mention-control event row person))
       [:div.field-hint {:style "margin-top:0.8em;"}
        "Coverage target for this event is " (or coverage-target 2)
        " review" (when (not= 1 coverage-target) "s") "."]])))

;; --- Manage speaker (organizer stewardship of an accepted talk) -------------
;;
;; Where the organizer engages an ACCEPTED speaker: the one screen for firming
;; up what the program prints — the session TITLE (on the submission) and the
;; speaker's BIO and HEADSHOT (on the person, shared across their talks). All
;; save through append-only domain paths; this view only shows the current
;; values and a plain <form> that POSTs them back. No client JS; the server decides
;; everything and re-renders.
(defn manage-speaker-page
  "Organizer edit screen for an accepted submission's session title, speaker bio,
   and headshot photo.
   `values` carries the values to SHOW (the folded current values, or the
   rejected input on a 422 so the organizer's typing survives)."
  [event row {:keys [person notice values files]}]
  (let [sp (first (:speakers row))
        title (get values :talk-title (get-in row [:answers :talk-title]))
        bio (get values :bio (or (:bio values) ""))
        headshot-url (format/not-blank (:headshot-url values))
        portal-comments (->> files
                             (mapcat (fn [file]
                                       (map #(assoc % :file-kind (:kind file))
                                            (:comments file))))
                             (sort-by :at))]
    (organizer-layout/organizer-shell
      (str "Manage " (:name sp) " — " (:name event))
      {:event event :active :board :person person :crumb "Review Board"}
      (notice-region event notice)
      (organizer-layout/header
        (str "Manage " (:name sp))
        (str "Accepted · " (get-in row [:answers :talk-title]))
        [:a.ui.basic.button
         {:href (str "/events/" (:slug event) "/submissions/" (:id row))}
         "← Back to talk"])

      [:div.sd-layout
       [:div.sd-main
        [:form.ui.form {:method "post" :enctype "multipart/form-data"
                        :action (str "/api/events/" (:slug event)
                                     "/submissions/" (:id row) "/manage")}
         [:div.field
          [:label "Session title"]
          [:input {:type "text" :name "talk-title" :value (or title "")
                   :maxlength "255"}]]
         [:div.field
          [:label "Speaker bio"]
          [:textarea {:name "bio" :rows "8"
                      :placeholder "The bio the program prints, as this speaker's identity across their talks."}
           (or bio "")]
          [:div.field-hint
           "The bio lives on " (:name sp)
           "'s profile — editing it here updates it everywhere they speak."]]
         [:div.field
          [:label "Upload a new headshot"]
          [:input {:type "file" :name "file" :accept "image/png,image/jpeg"}]
          [:div.field-hint
           "PNG or JPEG · 25 MB maximum · replacing a photo keeps its file history. "
           "Leave this empty to keep the current headshot."]]
         [:button.ui.primary.button {:type "submit"} "Save changes"]]]

       (when (seq portal-comments)
         [:div.ui.segment.portal-comments
          [:h3.ui.header "Speaker portal comments"
           [:div.sub.header "File conversations from the speaker portal, newest last."]]
          [:div.ui.comments
           (for [{:keys [id actor body at file-kind]} portal-comments]
             [:div.comment {:id id}
              [:div.content
               [:span.author actor]
               [:div.metadata (str at) (when file-kind (str " · " file-kind))]
               [:div.text body]]])]
          [:a.ui.small.basic.button
           {:href (str "/events/" (:slug event) "/files")}
           "Open Files to reply"]])

       [:div.sd-rail
        [:div.roster-card
         (when headshot-url
           [:img.sd-photo {:src headshot-url :alt (str (:name sp) " headshot")}])
         [:div.sp-name (:name sp)]
         (when-let [t (format/not-blank (:title sp))] [:div.sp-meta t])
         (when-let [o (format/not-blank (:org sp))] [:div.sp-meta o])
         [:dl.facts.sp-facts
          [:dt "Email"]
          [:dd [:a {:href (str "mailto:" (:email sp))} (:email sp)]]]]]])))

(defn board-region
  "Everything the time-travel slider repaints — and NOT the slider itself.
   Rendered whole on page load and again per scrub tick; one id, one patch."
  [event {:keys [rows coverage needs-coverage sort-key active-work-queue q status status-counts person sort-presets total
                 filtered-total sparkline-rows
                 uncommunicated notice track track-counts
                 scoped-tracks my-tracks show-all? visibility-context mentions-to-me
                 reviewer-progress chair? open-submission-id] :as opts}]
  [:div#board-region
   {:data-star-signals__ifmissing (json/write-str {:q (or q "")})}
   (notice-region event notice)
   (mentions-shelf event mentions-to-me)
   (when chair? (inform-banner event (or uncommunicated 0)))
   ;; The sparkline stays the all-submissions weather report; filters live
   ;; together immediately above their Status and Track facets below.
   [:div.board-work-queue-row
    [:div.ui.one.column.stackable.grid.board-coverage-grid
     (for [p sort-presets
           :when (= "needs-reviews" (:key p))]
       (work-queue-link active-work-queue p (:slug event) q status track show-all? coverage))]
    ;; ALL submissions over the call's life, never the filtered view (a
    ;; filter is a lens, the sparkline is the weather).
    (submissions-sparkline event
                           sparkline-rows
                           coverage)]

   [:div.ui.message.coverage-summary
    {:class (when (pos? (or needs-coverage 0)) "warning")}
    (str (or needs-coverage 0) " talk" (when (not= 1 needs-coverage) "s")
         (if (= 1 needs-coverage) " needs" " need")
         " coverage before deciding · target " (:target coverage) " reviews each")]

   [:form.board-controls {:method "get"
                          :action (str "/events/" (:slug event) "/board")
                          :style "width:100%;"}
    [:input {:type "hidden" :name "sort" :value sort-key}]
    (when (format/not-blank status) [:input {:type "hidden" :name "status" :value status}])
    (when (format/not-blank track) [:input {:type "hidden" :name "track" :value track}])
    (when show-all? [:input {:type "hidden" :name "all" :value "1"}])
    [:input (merge {:id "review-board-filter"
                    :type "search" :name "q" :value (or q "")
                    :aria-label "Filter review submissions"
                    :autofocus true
                    :placeholder "Filter any submission"
                    :data-star-on:input__debounce.50ms
                    (str "@post('/api/events/" (:slug event) "/board/sort')")
                    :style "padding:0.35em 0.6em; flex:1 1 auto; min-width:0;"}
                   (ds/bind :q))]
    (when (format/not-blank q)
      [:a.chip {:href (str "/events/" (:slug event) "/board?sort=" sort-key)} "clear"])]

   (when-not chair?
     (status-filters event status status-counts sort-key q))

   ;; The "room, not cubicle" escape: your rooms by default, the whole table one
   ;; click away (renders only for a track-scoped reviewer).
   (rooms-toggle (:slug event) opts)

   ;; The track facet row — options in the FORM's own order, then the honest
   ;; untracked bucket. Only renders once the form has a track field.
   (let [track-opts (some #(when (= "track" (name (:id %))) (:options %))
                          (forms/active-fields (forms/fields-for-event (:id event))))]
     (when (seq track-opts)
       [:div.board-controls
        [:span.field-hint {:style "margin:0;"} "Track:"]
        (for [t track-opts
              :let [c (get track-counts t 0)]
              :when (pos? c)]
          (track-chip track (:slug event) t c sort-key q status show-all?))
        (when-let [c (get track-counts nil)]
          (track-chip track (:slug event) nil c sort-key q status show-all?))]))

   (if (empty? rows)
     [:div.ui.segment
      [:div.empty-state
       (if (pos? total)
         "No submissions match that search."
         "No submissions yet — the board fills as talks arrive.")]]
     (let [chair? (submission-row/chair-on-event? event person)
           slug (:slug event)]
       [:table.board-table.ledger-table
        (when (#{"avg" "avg-desc"} sort-key)
          [:caption.field-hint {:aria-live "polite"}
           (str "Review results sorted by mean Stars · "
                (if (str/ends-with? sort-key "-desc") "highest first" "lowest first"))])
        [:thead
         [:tr
          [:th]
          [:th.lg-th-person {:class (if (if (some? visibility-context)
                                          (:hide-presenter-info? visibility-context)
                                          (voting-policy/hide-presenter-info? event))
                                      "blind-submission-header"
                                      (when (or (str/starts-with? (str sort-key) "speaker")
                                                (str/starts-with? (str sort-key) "org")) "on"))}
           (if (if (some? visibility-context)
                 (:hide-presenter-info? visibility-context)
                 (voting-policy/hide-presenter-info? event))
             (sort-click "Submission"
                         (if (= sort-key "submission") "submission-desc" "submission")
                         (cond (= sort-key "submission") " ↑"
                               (= sort-key "submission-desc") " ↓"
                               :else "")
                         slug q status track show-all?)
             (let [ctl (fn [label col]
                         (sort-click label
                                     (if (= sort-key col) (str col "-desc") col)
                                     (cond (= sort-key col) " ↑"
                                           (= sort-key (str col "-desc")) " ↓"
                                           :else "")
                                     slug q status track show-all?))]
               (list (ctl "Speaker Fname" "speaker-first")
                     [:span.dot " · "]
                     (ctl "Lname" "speaker-last")
                     [:span.dot " · "]
                     (ctl "Org" "org"))))]
          ;; Size LEFT of format (Gene, 2026-08-10).
          (sort-th "Size" "org-size" sort-key slug q status track show-all?)
          [:th.th-format "Format"]
          (sort-th "Stars" "avg" sort-key slug q status track show-all?)
          (sort-th "Reviews" "voted" sort-key slug q status track show-all?)
          [:th "You"]
          [:th]]]
        (for [row rows]
          (submission-row/board-row
            event row person chair? visibility-context
            (= (:id row) open-submission-id)))]))

   [:div.field-hint {:style "margin-top:1.2em;"}
    (str (count rows) " of " total " shown"
         (when (not= (or filtered-total total) total)
           (str " · " (or filtered-total 0) " matching · " total " total")))
    " · Ratings are 1–5 with halves; SPLIT marks a spread of "
    reviews/split-threshold " stars or more — the rows worth arguing about on the call."
    (when-not person " · Sign in to rate and comment.")]])

(defn board-page
  "`opts` = {:rows [enriched] :coverage {..} :sort-key :q :status :status-counts
             :person :sort-presets :total :time-travel}"
  [event {:keys [person time-travel chair? my-review-progress
                 status status-counts sort-key q] :as opts}]
  (let [{:keys [policy]} (presenter-visibility-policy-data event (:review-plan opts))]
    (organizer-layout/organizer-shell
      (str "Review Board — " (:name event))
      {:event event :active :board :person person :crumb "Review Board" :sse? true
       :presenter-visibility-policy
       (assoc policy :summary (view-policy/presenter-visibility-summary-text policy))
       ;; The scrubber rides the DEV STRIP at the viewport's foot (Gene,
       ;; 2026-08-09), not the working surface.
       :time-travel time-travel
       ;; The board is the one page that streams. data-star-init opens the SSE
       ;; connection; everything after that is the server pushing HTML.
       :body-attrs (ds/sse-mount (:id event))}

      (organizer-layout/header "Review Board"
                               "Every score and every comment, on one page. The shared board is the default; assigned queues help reviewers focus."
                               (when (and (not chair?)
                                          (pos? (or (:assigned my-review-progress) 0)))
                                 [:a.ui.primary.button
                                  {:href (str "/events/" (:slug event) "/board?assigned=1")}
                                  (str "Assigned to you (" (:remaining my-review-progress)
                                       " remaining)")])
                               [:a.ui.basic.button
                                {:href (str "/events/" (:slug event) "/exports")}
                                "Export review results"])

      (when chair? (view-policy/review-workflow-positioning))
      (when chair?
        (status-filters event status status-counts sort-key q))
      (board-region event opts))))
