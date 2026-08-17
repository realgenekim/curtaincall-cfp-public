(ns cfp-scheduler-killer.views.schedule
  "Organizer schedule and public agenda views."
  (:require
   [cfp-scheduler-killer.events :as events]
   [cfp-scheduler-killer.schedule :as schedule]
   [cfp-scheduler-killer.views.organizer-layout :as organizer-layout]
   [cfp-scheduler-killer.views.personal-schedule :as personal-schedule]
   [clojure.string :as str]
   [datastar-kit.ds :as ds]))

(defn agenda-days
  "The published agenda as framed day sections (Gene ratified 2026-08-11):
   every event day renders inside its programming-hours frame; a day without
   sessions says so instead of vanishing; an all-empty agenda offers the
   hold-the-dates calendar subscription."
  [event days active-day & [selected-ids]]
  (let [selected-ids (or selected-ids #{})
        all-days? (= :all active-day)
        selected-day (or (some #(when (= active-day (:day %)) (:day %)) days)
                         (:day (first days)))
        selected-index (first (keep-indexed #(when (= selected-day (:day %2)) %1) days))
        day-link (fn [day]
                   (str "/program/" (:slug event) "?day=" (:day day) "#agenda"))
        ampm (fn [hhmm]
               (let [[h m] (map #(Integer/parseInt %) (str/split hhmm #":"))
                     h12 (cond (zero? h) 12 (> h 12) (- h 12) :else h)]
                 (str h12 (when (pos? m) (format ":%02d" m))
                      (if (< h 12) "am" "pm"))))
        hours (events/day-hours event)
        frame (str "Programming " (ampm (:day-start hours))
                   " – " (ampm (:day-end hours)))
        any-items? (boolean (some #(seq (:items %)) days))]
    (list
      ;; The frame states itself ONCE while every day shares the same hours
      ;; (Gene, 2026-08-11: "why listed twice?") — per-day only on divergence,
      ;; when per-day overrides exist.
      [:div.agenda-frame-note frame
       (when (> (count days) 1) (str ", " (if (= 2 (count days)) "both days" "every day")))]
      (when (and (not all-days?) (> (count days) 1))
        [:nav.agenda-day-controls {:aria-label "Agenda days"}
         (when (pos? selected-index)
           [:a.ui.button.agenda-day-previous {:href (day-link (nth days (dec selected-index)))}
            "← Previous day"])
         [:div.agenda-day-tabs {:role "tablist" :aria-label "Agenda days"}
          (for [d days]
            [:a.agenda-day-tab {:href (day-link d)
                                :role "tab"
                                :aria-current (when (= selected-day (:day d)) "page")
                                :class (when (= selected-day (:day d)) "active")}
             (:label d)])]
         (when (< selected-index (dec (count days)))
           [:a.ui.button.agenda-day-next {:href (day-link (nth days (inc selected-index)))}
            "Next day →"])])
      (when-not any-items?
        [:p.agenda-soon "The agenda will be published soon. "
         [:a {:href (str "/events/" (:slug event) "/exports/calendar.ics")}
          "Hold the dates →"]])
      (for [d (if all-days? days (filter #(= selected-day (:day %)) days))]
        [:div {:id (str "agenda-day-" (:day d))
               :class "agenda-day-section"}
         [:h3.ui.header (:label d)]
         (if (empty? (:items d))
           [:div.agenda-day-empty
            "Sessions coming — the "
            [:a {:href (str "/cfp/" (:slug event))} "call for speakers"]
            " is open."]
           (for [item (:items d)]
             [:div.agenda-item {}
              [:div.agenda-time (schedule/time-range-display (:start item) (:end item))]
              [:div.agenda-body
               (if (= :block (:kind item))
                 [:div.agenda-block (:title item)]
                 (list
                   [:div.agenda-title {}
                    [:a {:href (str "/agenda/" (:slug event) "/sessions/"
                                    (:submission-id item) "?from=agenda&day=" (:day d))}
                     (:title item)]]
                   (when (seq (:speakers item))
                     [:div.agenda-who {} (str/join ", " (:speakers item))])
                   (when-not (str/blank? (:abstract item))
                     [:details.public-show-more
                      [:summary
                       [:span.public-disclosure-more "Show more"]
                       [:span.public-disclosure-less "Show less"]]
                      [:p (:abstract item)]])
                   [:div.public-chips
                    (when-not (str/blank? (:format item))
                      [:span.public-chip (str "Format: " (:format item))])
                    (when-not (str/blank? (:track item))
                      [:span.public-chip (str "Track: " (:track item))])]
                   (personal-schedule/toggle-control event (:submission-id item)
                     (contains? selected-ids (:submission-id item)))))
               (when (:room item) [:div.agenda-room (:room item)])]]))]))))

(defn agenda-export-hint [event]
  [:div.field-hint {:style "margin-top:2.5em;"}
   "Times are local to the event (" (:tz event) "). "
   [:a {:href (str "/events/" (:slug event) "/exports/calendar.ics")} "Subscribe by calendar"]
   " · "
   [:a {:href (str "/events/" (:slug event) "/exports/sessions.json")} "Session data"]])

(defn- block-card [event editable? b]
  [:div.sched-card.block
   [:div (:label b)]
   (when editable?
     [:div.acts
      [:form {:method "post" :action (str "/api/events/" (:slug event) "/schedule/block-remove")}
       [:input {:type "hidden" :name "block-id" :value (:id b)}]
       [:button {:type "submit"} "Remove"]]])])

(defn conflict-chips
  "Named collisions with BOTH fixes offered, because the tool doesn't know which
   side should move."
  [event conflicts]
  (when (seq conflicts)
    [:div {:style "margin-bottom:1em;"}
     (for [c conflicts]
       [:div.conflict-chip {}
        (:message c)
        [:span.fixes
         (when-let [a (:a c)]
           [:form {:method "post"
                   :action (str "/api/events/" (:slug event) "/schedule/clear")}
            [:input {:type "hidden" :name "submission-id" :value (:submission-id a)}]
            [:button.ui.mini.basic.button {:type "submit"}
             "move \"" (let [t (str (:title a))]
                         (if (> (count t) 28) (str (subs t 0 28) "…") t)) "\""]])
         (when-let [b (:b c)]
           [:form {:method "post"
                   :action (str "/api/events/" (:slug event) "/schedule/clear")}
            [:input {:type "hidden" :name "submission-id" :value (:submission-id b)}]
            [:button.ui.mini.basic.button {:type "submit"}
             "move \"" (let [t (str (:title b))]
                         (if (> (count t) 28) (str (subs t 0 28) "…") t)) "\""]])]])]))

(defn- day-tab [event day active-day]
  [:a.chip {:class (when (= day active-day) "on")
            :href (str "/events/" (:slug event) "/schedule?day=" day)}
   (or (schedule/day-label event day) day)])

(defn- room-options [rooms selected]
  (list
    [:option {:value "" :selected (nil? selected)} "no room yet"]
    (for [r rooms]
      [:option (cond-> {:value (:id r)}
                 (= (:id r) selected) (assoc :selected true))
       (:name r)])))

(defn- duration-label
  [minutes]
  (let [hours (quot minutes 60)
        minutes (mod minutes 60)]
    (str (when (pos? hours) (str hours "h"))
         (when (and (pos? hours) (pos? minutes)) " ")
         (when (or (zero? hours) (pos? minutes)) (str minutes "m")))))

(defn schedule-status-bar
  [stats]
  [:div#schedule-status.status-bar
   [:div [:span.n (:placed stats)] "/" (:accepted stats) " "
    [:span.lbl "accepted placed"]]
   [:span.status-sep "·"]
   [:div [:span.n (:unplaced stats)] " " [:span.lbl "in the tray"]]
   ;; Only ever shown when it is non-zero: a standing zero trains people to
   ;; stop reading the bar (inform/alert-rows house rule). Non-zero, it is the
   ;; one number explaining why the tray is shorter than the accept count.
   (when (pos? (or (:awaiting-inform stats) 0))
     (list [:span.status-sep {} "·"]
           [:div {} [:span.n (:awaiting-inform stats)] " "
            [:span.lbl "accepted, not yet informed"]]))
   [:span.status-sep "·"]
   [:div [:span.n (:unroomed stats)] " " [:span.lbl "unroomed"]]
   [:span.status-sep "·"]
   [:div [:span.n {:style (when (pos? (:conflicts stats)) "color:#f2711c;")}
          (:conflicts stats)] " " [:span.lbl "conflicts"]]
   (when (pos? (get-in stats [:room-time :capacity] 0))
     (list [:span.status-sep {} "·"]
           [:div.slot-math
            [:span.n (duration-label (get-in stats [:room-time :filled]))]
            " / "
            [:span.n (duration-label (get-in stats [:room-time :capacity]))]
            " " [:span.lbl "room time filled"]
            ", "
            [:span.n (duration-label (get-in stats [:room-time :open]))]
            " " [:span.lbl "open"]]))
   (for [d (:per-day stats)
         :let [room-day (some #(when (= (:day d) (:day %)) %)
                              (get-in stats [:room-time :per-day]))]]
     (list [:span.status-sep {} "·"]
           [:div {}
            [:span.lbl (:label d) ": "]
            [:span.n (:sessions d)] [:span.lbl " sessions"]
            (when (pos? (:blocks d))
              (list " " [:span.n (:blocks d)] [:span.lbl " blocks"]))
            (when (pos? (or (:capacity room-day) 0))
              (list ", " [:span.n (duration-label (:open room-day))]
                    [:span.lbl " room time open"]))]))])

(defn- place-form
  [event submission day rooms]
  [:form.place-form {:method "post" :action (str "/api/events/" (:slug event) "/schedule/place")}
   [:input {:type "hidden" :name "submission-id" :value (:id submission)}]
   [:select {:name "day"}
    (for [d (schedule/event-days event)]
      [:option (cond-> {:value d} (= d day) (assoc :selected true))
       (schedule/day-label event d)])]
   [:input {:type "time" :name "start" :step "60" :value "09:00" :required true}]
   [:input {:type "number" :name "duration" :min "5" :step "5"
            :value (schedule/duration-for event submission) :style "width:5em;"}]
   [:select {:name "room-id"} (room-options rooms nil)]
   [:button.ui.mini.primary.button {:type "submit"} "Place"]])

(defn- placed-card
  [event editable? p rooms conflicted?]
  [:div.sched-card {:class (when conflicted? "conflicted")}
   [:div [:a {:href (str "/events/" (:slug event) "/submissions/" (:submission-id p))}
          (:title p)]]
   [:div.who (str/join ", " (map :name (:speakers p)))]
   (when editable?
     [:div.acts]
     ;; Quick room re-assign: the late-room-assignment workflow, one control.
     [:form {:method "post" :action (str "/api/events/" (:slug event) "/schedule/place")}
      [:input {:type "hidden" :name "submission-id" :value (:submission-id p)}]
      [:input {:type "hidden" :name "day" :value (:day p)}]
      [:input {:type "hidden" :name "start" :value (schedule/minutes->hhmm (:start p))}]
      [:input {:type "hidden" :name "duration" :value (- (:end p) (:start p))}]
      [:select {:name "room-id" :onchange "this.form.submit()"}
       (room-options rooms (:room-id p))]]
     [:form {:method "post" :action (str "/api/events/" (:slug event) "/schedule/clear")}
      [:input {:type "hidden" :name "submission-id" :value (:submission-id p)}
       [:button {:type "submit"} "Clear"]]])])

(defn schedule-grid
  "Rooms as columns plus an Unroomed column; one row per occupied start time.
   Compressed to what is actually there — an empty 15-minute lattice is a lot of
   scrolling to say nothing."
  [event day {:keys [rooms placed blocks conflicted-ids editable?]}]
  (let [day-placed (filter #(= day (:day %)) placed)
        day-blocks (filter #(= day (:day %)) blocks)
        starts (sort (distinct (concat (map :start day-placed) (map :start day-blocks))))
        cols (concat (map (fn [r] {:id (:id r) :name (:name r)}) rooms)
                     [{:id nil :name "Unroomed"}])]
    (if (empty? starts)
      [:div.empty-state
       "Nothing placed on this day yet. Use " [:strong "Place"] " on a tray card below."]
      [:table.sched-grid
       [:thead
        [:tr [:th.sched-time "Time"]
         (for [c cols] [:th {} (:name c)])]]
       [:tbody
        (for [t starts]
          [:tr {}
           [:td.sched-time (schedule/minutes->display t)]
           (for [c cols]
             [:td {:class (when (nil? (:id c)) "unroomed-col")}
              (for [p day-placed
                    :when (and (= t (:start p)) (= (:room-id p) (:id c)))]
                (placed-card event editable? p rooms (contains? conflicted-ids (:submission-id p))))
              (for [b day-blocks
                    :when (and (= t (:start b)) (= (:room-id b) (:id c)))]
                (block-card event editable? b))])])]])))

(defn schedule-page
  [event {:keys [day stats conflicts rooms tracks placed blocks trayed conflicted-ids
                 person locked? lock-version withheld-count published-at editable?]}]
  (let [editable? (not= false editable?)
        {:keys [day-start day-end]} (events/day-hours event)]
    (organizer-layout/organizer-shell
      (str "Schedule — " (:name event))
      {:event event :active :schedule :person person :crumb "Schedule" :sse? true
       :body-attrs (ds/sse-mount (:id event))}

      (organizer-layout/header "Schedule"
                               (if editable?
                                 "Draft-first. Place things half-decided; the arithmetic and the clashes keep up."
                                 "The working program, rooms, and conflicts — view only."))

      (when locked?
        [:div.locked-banner
         [:div [:strong "Locked — " (or lock-version "v1")]
          [:div.field-hint "The draft is frozen. Unlock to keep moving things."]]
         (when editable?
           [:form {:method "post" :action (str "/api/events/" (:slug event) "/schedule/unlock")}
            [:button.ui.small.button {:type "submit"} "Unlock"]])])

      (schedule-status-bar stats)
      (list
        (conflict-chips event conflicts)
        (when (pos? (or withheld-count 0))
          ;; The loud half of "withhold, loudly": conflicted sessions leave the
          ;; public agenda/exports, and this line is why nothing vanishes silently.
          [:div.withheld-note {}
           [:strong (str withheld-count " session" (when (not= 1 withheld-count) "s"))]
           " held back from the public agenda and exports until the conflict is
       resolved — attendees never see a schedule we know is wrong."]))

      (if (empty? (schedule/event-days event))
        [:div.ui.warning.message
         [:div.header "This event has no dates yet"]
         [:p "Set start and end dates and the day tabs will appear."]]

        (list
          [:div.day-tabs {}
           (for [d (schedule/event-days event)] (day-tab event d day))]

          [:div {} (schedule-grid event day {:rooms rooms :placed placed
                                             :blocks blocks
                                             :conflicted-ids conflicted-ids
                                             :editable? editable?
                                             :locked? locked?})]

          (when (and editable? (not locked?) (seq trayed) (seq rooms)) [:div.ui.info.message [:div.header "Suggest schedule"] [:p (str "Fill open " day-start "–" day-end " room slots without moving anything already placed. ") "The organizer remains in control and can adjust every result."] [:form {:method "post" :action (str "/api/events/" (:slug event) "/schedule/suggest")} [:button.ui.button {:type "submit"} "Suggest schedule"]]])

          (when (and editable? (not locked?))
            [:div.tray {}
             [:h4.ui.header {:style "margin-bottom:0.3em;"}
              "Accepted (unscheduled): " (count trayed)]
             [:div.field-hint {:style "margin-bottom:0.6em;"}
              "Only accepted speakers who have been informed appear here — the agenda "
              "is downstream of the promise, never ahead of it."]
             (if (empty? trayed)
                 ;; An empty tray has two very different causes and only one of
                 ;; them is good news. Saying "everything accepted has a place"
                 ;; to an organizer who has accepted ten talks and informed none
                 ;; of them is a lie that dead-ends the whole downstream walk —
                 ;; nothing schedules, so nothing publishes, so every public
                 ;; surface is empty for a reason the page refused to name.
                 (let [awaiting (or (:awaiting-inform stats) 0)
                       many? (not= 1 awaiting)]
                   (if (pos? awaiting)
                     [:div.empty-state
                      [:strong (str awaiting " accepted talk" (when many? "s")
                                    " cannot be scheduled yet")]
                      (str " — the speaker" (when many? "s") " ha" (if many? "ve" "s")
                           " not been told. Accepting is the decision; informing is "
                           "the promise, and the agenda follows the promise. ")
                      [:a {:href (str "/events/" (:slug event) "/inform")}
                       "Inform speakers →"]
                      " and they land in this tray."]
                     [:div.empty-state "Everything accepted has a place. "
                      (when (pos? (:unroomed stats))
                        (str (:unroomed stats) " still need a room, which is fine."))]))
                 (for [sub trayed]
                   [:div.tray-card {}
                    [:div [:strong (get-in sub [:answers :talk-title])]]
                    [:div.sub-meta (:name (first (:speakers sub)))
                     " · " (get-in sub [:answers :session-format])]
                    (place-form event sub day rooms)]))])

          (when (and editable? (not locked?))
            [:div.ui.segment {}
             [:h4.ui.header "Rooms & blocks"]
             [:div {:style "display:flex; gap:2em; flex-wrap:wrap;"}
              [:div {:style "flex:1; min-width:16em;"}
               [:h5.ui.header "Rooms"]
               (if (seq rooms)
                 [:div.member-list
                  (for [r rooms]
                    [:div.member-row {}
                     [:div.member-who [:span.member-name (:name r)]]
                     [:form {:method "post"
                             :action (str "/api/events/" (:slug event) "/schedule/room-remove")}
                      [:input {:type "hidden" :name "room-id" :value (:id r)}]
                      [:button.ui.mini.basic.button {:type "submit"} "Remove"]]])]
                 [:p.field-hint "No rooms yet — sessions can still be placed unroomed."])
               [:form.place-form {:method "post"
                                  :action (str "/api/events/" (:slug event) "/schedule/room-add")}
                [:input {:type "text" :name "name" :placeholder "Main Stage" :required true}]
                [:button.ui.mini.button {:type "submit"} "Add room"]]]

              [:div {:style "flex:1; min-width:20em;"}
               [:h5.ui.header "Add a block"]
               [:div.field-hint {:style "margin-bottom:0.4em;"}
                "Lunch, Keynote TBD, Break — placeholders that hold space for "
                "something not yet decided."]
               [:form.place-form {:method "post"
                                  :action (str "/api/events/" (:slug event) "/schedule/block-add")}
                [:input {:type "text" :name "label" :placeholder "Lunch" :required true}]
                [:select {:name "day"}
                 (for [d (schedule/event-days event)]
                   [:option (cond-> {:value d} (= d day) (assoc :selected true))
                    (schedule/day-label event d)])]
                [:input {:type "time" :name "start" :step "60" :value "12:00" :required true}]
                [:input {:type "number" :name "duration" :min "5" :step "5" :value "60"
                         :style "width:5em;"}]
                [:select {:name "room-id"} (room-options rooms nil)]
                [:button.ui.mini.button {:type "submit"} "Add block"]]]

              [:div {:style "flex:1; min-width:20em;"}
               [:h5.ui.header "Track management"]
               [:div.field-hint {:style "margin-bottom:0.4em;"}
                "One canonical track list feeds the CFP, review filters, schedule, and public facets."]
               (if (seq tracks)
                 [:div.member-list
                  (for [track tracks]
                    [:div.member-row {}
                     [:form.place-form {:method "post"
                                        :action (str "/api/events/" (:slug event) "/schedule/track-rename")}
                      [:input {:type "hidden" :name "old-label" :value track}]
                      [:input {:type "text" :name "new-label" :value track :required true}]
                      [:button.ui.mini.basic.button {:type "submit"} "Rename"]]
                     [:form {:method "post"
                             :action (str "/api/events/" (:slug event) "/schedule/track-retire")}
                      [:input {:type "hidden" :name "label" :value track}]
                      [:button.ui.mini.basic.button {:type "submit"} "Retire"]]])]
                 [:p.field-hint "No active tracks."])
               [:form.place-form {:method "post"
                                  :action (str "/api/events/" (:slug event) "/schedule/track-add")}
                [:input {:type "text" :name "label" :placeholder "Platform Engineering" :required true}]
                [:button.ui.mini.button {:type "submit"} "Add track"]]]]])

          (when (and editable? (not locked?))
            [:div {:style "margin-top:1.5em;"}
             [:form {:method "post" :action (str "/api/events/" (:slug event) "/schedule/lock")}
              [:button.ui.button {:type "submit"} "Lock schedule"]]
             [:div.field-hint {:style "margin-top:0.4em;"}
              "Locking freezes the draft and stamps a version. The "
              [:a {:href (str "/events/" (:slug event) "/log")} "Log"] " narrates every change."]])

          [:div.ui.segment {}
           [:h4.ui.header "Public agenda"]
           (if published-at
             [:div.ui.positive.message "Published ✓"]
             [:div.field-hint "Only accepted speakers who have been informed will appear."])
           (when editable?
             [:form {:method "post"
                     :action (str "/api/events/" (:slug event) "/schedule/publish")}
              [:button.ui.primary.button {:type "submit"} "Publish"]])
           [:a.ui.basic.button {:href (str "/agenda/" (:slug event)) :target "_blank"}
            "View the public agenda"]])))))
