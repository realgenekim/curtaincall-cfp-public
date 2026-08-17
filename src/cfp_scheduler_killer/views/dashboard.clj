(ns cfp-scheduler-killer.views.dashboard
  "Event mission-control page and live fragment."
  (:require
   [cfp-scheduler-killer.store :as store]
   [cfp-scheduler-killer.views.avatar :as avatar]
   [cfp-scheduler-killer.views.format :as format]
   [cfp-scheduler-killer.views.organizer-layout :as organizer-layout]
   [cfp-scheduler-killer.views.review :as review]
   [datastar-kit.ds :as ds]))

(defn alert-rows-partial
  "The 'Also check' rows. Count first — the number a human would panic about
   leads the sentence. Rows with a zero count are not rendered at all: a
   dashboard of zeroes teaches people to stop reading it."
  [rows]
  (when (seq rows)
    [:div.alerts
     (for [r rows]
       [:div.alert-row {:class (when (:urgent? r) "urgent")}
        [:div.alert-count (:count r)]
        [:div.alert-text (:text r)]
        [:a.alert-go {:href (:href r)} (:link r) " →"]])]))

(defn- checklist-item [marker done? label]
  [:li {:data-setup-marker (name marker)
        :data-complete (str (boolean done?))}
   [:span.box {:class (when done? "done")} (if done? "✓" "☐")]
   [:span label]])

(defn- dash-days-left
  "Days until the call closes — honest when there is no close date (the
   '26441 days left' class of nonsense, bead 5nr, dies here)."
  [event cfp-state]
  (let [closes (:cfp-closes-at event)]
    (cond
      (= :closed cfp-state) {:n "—" :hint "call closed"}
      (nil? closes) {:n "—" :hint "no close date set"}
      :else
      (let [d (.between java.time.temporal.ChronoUnit/DAYS
                        (-> (store/now-inst)
                            (.atZone (java.time.ZoneId/of "UTC"))
                            (.toLocalDate))
                        (java.time.LocalDate/parse (subs (str closes) 0 10)))]
        (cond
          (neg? d) {:n "0" :hint "past the close date"}
          ;; a demo/far-future close date is "no deadline", not "26,441 days"
          (> d 365) {:n "—" :hint "no deadline pressure"}
          :else {:n (str d) :hint (str "closes " (subs (str closes) 0 10))})))))

(defn- dash-feed-talk
  "WHAT the action landed on — the review-board row's identity, compact:
   the speaker's face, the talk title (a door to the submission), and
   name · org (Gene, 2026-08-10: 'we need to know what they commented on')."
  [slug submission-of e]
  (let [p (:payload e)
        sid (or (:submission-id p) (:id p))
        s (when (and sid submission-of) (submission-of sid))]
    (when s
      (let [sp (first (:speakers s))
            title (get-in s [:answers :talk-title])]
        [:span.dash-feed-talk
         [:img.dash-feed-face
          {:src (or (format/not-blank (:headshot-url sp))
                    (avatar/pool-face (or (:person-id sp) (:name sp))))
           :alt (or (:name sp) "")}]
         [:a.dash-feed-title {:href (str "/events/" slug "/submissions/" (:id s))}
          (str "“" title "”")]
         [:span.dash-feed-who
          (str (:name sp) (when (format/not-blank (:org sp)) (str " · " (:org sp))))]]))))

(defn- dash-recent-line
  "One log fact as one human sentence — nil for facts nobody scans for."
  [person-name-of e]
  (let [p (:payload e)
        who #(or (person-name-of (:person-id p)) "Someone")]
    (case (:type e)
      "submission.created" "New submission"
      "rating.set" (str (who) " rated ★" (:stars p))
      "comment.added" (str (who) " commented")
      "submission.status-changed" (str "Status changed → " (or (:to p) (:status p)))
      "submission.answers-updated" "A speaker revised their submission"
      "committee.member-added" (str (or (:name p) (:email p) "A reviewer")
                                    " joined the committee")
      "event.cfp-opened" "The call for speakers opened"
      "event.cfp-closed" "The call for speakers closed"
      nil)))

(defn- dash-rel-time
  "2m / 3h / 4d ago, from whatever timestamp the fact carries."
  [e]
  (when-let [ts (or (:at e) (:created-at e) (get-in e [:payload :at]))]
    (try
      (let [then (java.time.Instant/parse (str ts))
            mins (.toMinutes (java.time.Duration/between then (store/now-inst)))]
        (cond (< mins 1) "now"
              (< mins 60) (str mins "m")
              (< mins 1440) (str (quot mins 60) "h")
              :else (str (quot mins 1440) "d")))
      (catch Exception _ nil))))

(defn- days-label [n noun]
  (when (number? n)
    (str n " day" (when (not= 1 n) "s") " " noun)))

(defn- speaker-material-row
  [event {:keys [submission-id key speaker-name talk-title label due-on status
                 days-overdue days-outstanding last-chased-at chase-count]}]
  [:tr {:data-material (str submission-id ":" key)}
   [:td [:strong speaker-name] [:div.field-hint talk-title]]
   [:td [:strong label] [:div.field-hint (or (some-> due-on str) "No due date")]]
   [:td
    [:strong (or (days-label days-outstanding "outstanding") "Age unknown")]
    [:div.field-hint
     (case status
       :overdue (days-label days-overdue "overdue")
       :due-today "Due today"
       :due-soon "Due soon"
       :upcoming "Upcoming"
       :unscheduled "Needs event date"
       "Open")]]
   [:td
    (if last-chased-at
      (list "Last contacted " (format/fmt-instant-day last-chased-at (:tz event))
            [:div.field-hint chase-count " delivered follow-up"
             (when (not= 1 chase-count) "s")])
      [:span.field-hint "Not contacted"])]])

(defn speaker-materials-ledger
  "The dashboard's compact chase list. The full ledger remains one click away."
  [event rows]
  (when (seq rows)
    [:div.dash-card
     [:h4.ui.header "Speaker materials"
      [:div.sub.header "Who owes what, most overdue first."]]
     [:table.ui.very.basic.compact.table
      [:thead
       [:tr [:th "Speaker"] [:th "Owes"] [:th "Outstanding"] [:th "Last touch"]]]
      [:tbody (map #(speaker-material-row event %) (take 5 rows))]]
     [:div.field-hint
      [:a {:href (str "/events/" (:slug event) "/deliverables")}
       (str "Open all " (count rows) " outstanding item"
            (when (not= 1 (count rows)) "s") " →")]]]))

(defn event-dashboard-region
  "The replaceable facts inside Mission Control. The DEV strip and its live
   scrubber deliberately live outside this region so a patch cannot interrupt
   the slider gesture."
  [host event {:keys [member-count chair-assigned? sub-count cfp-state alerts
                      uncommunicated form-configured? form-field-count enriched
                      coverage recent person-name-of submission-of
                      speaker-materials editable?]}]
  (let [{:keys [slug tz]} event
        editable? (not= false editable?)
        n-subs (or sub-count 0)
        reviewed-pct (if (pos? (or (:total coverage) 0))
                       (int (* 100 (/ (:covered coverage) (:total coverage))))
                       0)
        needs-2nd (count (filter #(< (:n %) (or (:target coverage) 2)) enriched))
        days (dash-days-left event cfp-state)
        board-url (str "/events/" slug "/board")
        setup-incomplete? (or (not= :open cfp-state) (zero? n-subs))]
    [:div#dashboard-region
     ;; The launch strip stays: when the call is not open, the dashboard's
     ;; job is to walk you to launch — loudly.
     (when (and editable? (= :not-open-yet cfp-state))
       [:div.launch-strip
        [:div.launch-lead "Your call for speakers isn't open yet. Three steps to launch:"]
        [:div.launch-steps
         [:a.launch-step {:href (str "/events/" slug "/form")}
          [:span.n "1"] "Review the form"
          [:span.launch-note (if form-configured? "seed form ready ✓"
                                 (str (or form-field-count 11) " seed questions ready"))]]
         [:a.launch-step {:href (str "/events/" slug "/committee")}
          [:span.n "2"] "Add reviewers"
          [:span.launch-note (if chair-assigned?
                               "chair assigned ✓"
                               (str (or member-count 0) " on the roster"))]]
         [:form.launch-step-form {:method "post"
                                  :action (str "/api/events/" slug "/cfp/open")}
          [:button.launch-step.go {:type "submit"}
           [:span.n "3"] "Open the call →"]]]])

     (when editable?
       (list
         (review/inform-banner event (or uncommunicated 0))
         (alert-rows-partial alerts)))

     ;; The stat tiles — the five-second answer to "how is my call doing?"
     [:div.dash-tiles
      [:div.dash-tile
       [:div.dash-n (str n-subs)]
       [:div.dash-l (str "submission" (when (not= 1 n-subs) "s"))]
       (when (pos? n-subs)
         [:div.dash-spark (review/submissions-sparkline event enriched coverage)])]
      [:div.dash-tile
       [:div.dash-n (:n days)]
       [:div.dash-l "days left"]
       [:div.dash-h (:hint days)]]
      [:div.dash-tile
       [:div.dash-n (str reviewed-pct "%")]
       [:div.dash-l "fully reviewed"]
       [:div.dash-h (str "≥" (or (:target coverage) 2) " reviews each")]]
      (let [n-ratings (reduce + (map :n enriched))
            n-comments (reduce + (map (comp count :comments) enriched))]
        [:div.dash-tile
         [:div.dash-n (str n-ratings)]
         [:div.dash-l (str "rating" (when (not= 1 n-ratings) "s"))]
         [:div.dash-h (str n-comments " comment" (when (not= 1 n-comments) "s"))]])]

     ;; The setup checklist, ONLY while it still has work to do.
     (when (and editable? setup-incomplete?)
       [:div.dash-card
        [:h4.ui.header "Get the call open"]
        [:ul.checklist
         (checklist-item :event true "Create event")
         (checklist-item :cfp-form (boolean form-configured?)
                         [:span "Create CFP form — review the "
                          (or form-field-count 11) " seed questions: "
                          [:a {:href (str "/events/" slug "/form")} "open the form editor"]])
         (checklist-item :review-committee (boolean chair-assigned?)
                         [:span "Create review committee — "
                          [:a {:href (str "/events/" slug "/committee")} "invite reviewers"]])
         (checklist-item :public-cfp (= :open cfp-state)
                         (case cfp-state
                           :open (str "Call for speakers is open"
                                      (when-let [o (format/fmt-instant (:cfp-opens-at event) tz)]
                                        (str " — since " o)))
                           :closed "Call for speakers is closed"
                           "Open the call for speakers"))
         (checklist-item :first-submission (pos? n-subs) "First submission arrives")]
        [:div.field-hint {:style "margin-top:0.8em;"}
         "Every row tracks real state — nothing here is decorative."]])

     ;; What needs you — each row is a work queue with a door.
     [:div.dash-card
      [:h4.ui.header "What needs you"]
      (let [rows (remove nil?
                         [(when (and editable? (zero? n-subs))
                            [:div.dash-need
                             [:span "Share the public link — this is the URL speakers need: "]
                             [:a.cfp-url {:href (str "/cfp/" slug) :target "_blank" :rel "noopener"}
                              (format/cfp-public-url host slug)]
                             [:button.copy-url {:type "button" :title "Copy the public CFP URL"
                                                :data-star-on:click
                                                (ds/copy-nearest-text "div" ".cfp-url"
                                                                      "Copied to clipboard")}
                              "⧉ copy"]])
                          (when (pos? needs-2nd)
                            [:a.dash-need {:href (str board-url "?sort=needs-reviews")}
                             (str needs-2nd " submission" (when (not= 1 needs-2nd) "s")
                                  " still below " (or (:target coverage) 2) " reviews")
                             [:span.dash-go "coverage queue →"]])
                          (when (and editable? (pos? (or uncommunicated 0)))
                            [:a.dash-need {:href (str "/events/" slug "/inform")}
                             (str uncommunicated " decision" (when (not= 1 uncommunicated) "s")
                                  " made but not yet told")
                             [:span.dash-go "inform speakers →"]])
                          (when (and editable? (seq speaker-materials))
                            [:a.dash-need {:href (str "/events/" slug "/deliverables")}
                             (str (count speaker-materials) " speaker material"
                                  (when (not= 1 (count speaker-materials)) "s")
                                  " outstanding")
                             [:span.dash-go "open chase list →"]])])]
        (if (seq rows)
          rows
          [:div.field-hint "The queues are clear — nothing is waiting on you."]))]

     (when editable?
       (speaker-materials-ledger event speaker-materials))

     ;; Recent — the call breathing.
     (when (and editable? (seq recent))
       [:div.dash-card
        [:h4.ui.header "Recent"]
        ;; Basecamp shape (Gene, 2026-08-10: 'the winning ticket'): row one
        ;; is WHO did WHAT, row two — slightly indented — is what it landed
        ;; on: the talk, then its speaker · org.
        (let [lines (->> recent
                         (keep (fn [e]
                                 (when-let [line (dash-recent-line person-name-of e)]
                                   [:div.dash-recent-entry
                                    [:div.dash-recent-act
                                     [:span.dash-recent-what line]
                                     (when-let [t (dash-rel-time e)]
                                       [:span.dash-recent-when t])]
                                    (when-let [talk (dash-feed-talk slug submission-of e)]
                                      [:div.dash-recent-on talk])])))
                         (take 6)
                         seq)]
          (or lines [:div.field-hint "Nothing yet."]))
        [:div.field-hint {:style "margin-top:0.6em;"}
         [:a {:href (str "/events/" slug "/log")} "open the full log →"]]])

     ;; The call's open/close stays reachable — a deliberate act by a named
     ;; person, one quiet line instead of a whole card.
     ;; The public URL stays grabbable here always — organizers hand this
     ;; link out constantly, not only before the first submission.
     [:div.dash-call-line
      [:span (case cfp-state
               :open (str "The call is open"
                          (when-let [c (format/fmt-instant (:cfp-closes-at event) tz)]
                            (str " until " c)))
               :not-open-yet "The call is not open yet"
               :closed "The call is closed"
               "The call is open")]
      [:a.cfp-url {:href (str "/cfp/" slug) :target "_blank" :rel "noopener"}
       (format/cfp-public-url host slug)]
      [:button.copy-url {:type "button" :title "Copy the public CFP URL"
                         :data-star-on:click
                         (ds/copy-nearest-text "div" ".cfp-url" "Copied to clipboard")}
       "⧉ copy"]
      (when editable?
        [:form.cfp-act {:method "post"
                        :action (str "/api/events/" slug "/cfp/"
                                     (if (= :open cfp-state) "close" "open"))}
         [:button.ui.tiny.basic.button {:type "submit"}
          (if (= :open cfp-state) "Close the call" "Open the call")]])]]))

(defn event-dashboard-page
  "Mission control (Gene ratified 2026-08-10, over ASCII mockups): the call
   at a glance — stat tiles with the momentum sparkline, a what-needs-you
   queue, and the recent activity feed. The setup checklist appears only
   while setup is incomplete, then vanishes. No duplication: Event details
   owns the metadata, Committee owns the roster, the board owns the list."
  [host event {:keys [person time-travel]
               :as opts}]
  (when-not (and (map? time-travel)
                 (string? (:base-path time-travel))
                 (string? (:fragment-path time-travel)))
    (throw (ex-info "event-dashboard-page requires a complete :time-travel contract"
                    {:required [:base-path :fragment-path]
                     :received time-travel})))
  (let [{:keys [name starts-on ends-on]} event]
    (organizer-layout/organizer-shell
      (str name " — CFP Scheduler Killer")
      {:event event
       :active :dashboard
       :person person
       :time-travel time-travel
       :datastar? true}
      (organizer-layout/header name
                               (let [dates (or (format/fmt-date-range starts-on ends-on) "Dates not set yet")]
                                 (if (:location event) (str dates " · " (:location event)) dates))
                               [:a.ui.primary.button {:href (str "/events/" (:slug event) "/board")}
                                "Go to the review board →"])
      (event-dashboard-region host event opts))))
