(ns cfp-scheduler-killer.views.my-schedule
  "Public, server-rendered attendee schedule selection."
  (:require
   [cfp-scheduler-killer.events :as events]
   [cfp-scheduler-killer.views.personal-schedule :as personal-schedule]
   [cfp-scheduler-killer.views.shell :as shell]
   [clojure.string :as str]))

(defn- session-row
  ([event session selected?]
   (session-row event session selected? true))
  ([event session selected? selectable?]
   [:div.ui.segment
    [:h3.ui.header
     [:a {:href (str "/agenda/" (:slug event) "/sessions/" (:id session))}
      (:title session)]]
    (if (or (seq (:day session)) (seq (:time session)))
      [:p.public-session-meta
       (str/join " · " (remove str/blank? [(:day session) (:time session) (:room session)]))]
      [:p.public-session-meta "Schedule to come"])
    (when-not (str/blank? (:description session))
      [:p.public-session-description (:description session)])
    (when (seq (:speakers session))
      [:div.public-session-speakers
       (for [{:keys [name tagline company]} (:speakers session)]
         [:p
          [:strong name]
          (when-not (str/blank? tagline) (str " — " tagline))
          (when-not (str/blank? company) (str ", " company))])])
    [:div.public-chips
     (when-not (str/blank? (:format session))
       [:span.public-chip (str "Format: " (:format session))])
     (when-not (str/blank? (:track session))
       [:span.public-chip (str "Track: " (:track session))])]
    (when selectable?
      (personal-schedule/toggle-control event (:id session) selected?))]))

(defn- itinerary-sections
  ([event sessions]
   (itinerary-sections event sessions true))
  ([event sessions selectable?]
   (for [day-sessions (partition-by #(or (not-empty (:day %)) "Schedule to come")
                                    sessions)
         :let [day-label (or (not-empty (:day (first day-sessions)))
                             "Schedule to come")]]
     [:section.public-itinerary-day {:data-day day-label}
      [:h3 day-label]
      (for [session day-sessions]
        (session-row event session false selectable?))])))

(defn public-itinerary-page [event sessions selected-ids]
  (shell/page-shell
    (str (events/display-name event) " — Itinerary")
    [:main.ui.container.public-widget-page
     (personal-schedule/browser-state event selected-ids)
     [:div.cfp-masthead
      [:h1.ui.header (events/display-name event)]
      [:div.cfp-meta "Public itinerary"]]
     (shell/public-event-nav (:slug event) :itinerary)
     [:h2 "Public itinerary"]
     [:p "Published sessions are grouped by event day and listed in chronological order."]
     (if (seq sessions)
       (for [day-sessions (partition-by #(or (not-empty (:day %)) "Schedule to come") sessions)
             :let [day-label (or (not-empty (:day (first day-sessions))) "Schedule to come")]]
         [:section.public-itinerary-day {:data-day day-label}
          [:h3 day-label]
          (for [session day-sessions]
            (session-row event session (contains? selected-ids (:id session)) true))])
       [:div.ui.message "The public itinerary will be published soon."])
     (personal-schedule/browser-script)]))

(defn my-schedule-page [event sessions selected-ids]
  (let [selected (filterv #(contains? selected-ids (:id %)) sessions)]
    (shell/page-shell
      (str (events/display-name event) " — My schedule")
      [:main.ui.container.public-widget-page
       (personal-schedule/browser-state event selected-ids)
        [:div.cfp-masthead
         [:h1.ui.header (events/display-name event)]
         [:div.cfp-meta "My schedule"]]
        (shell/public-event-nav (:slug event) :my)

        [:h2 "My schedule"]
       (if (empty? selected)
         [:div.ui.message
          [:div.header "No sessions selected yet"]
          [:p "Star sessions on the public agenda. Your picks stay in this browser only."]
          [:a.ui.button {:href (str "/program/" (:slug event))} "Browse the agenda"]]
         (list
           [:p "Your picks are stored only in this browser. Curtain Call renders this list from the public program."]
           [:form.my-schedule-export {:method "post" :action (str "/agenda/" (:slug event) "/my.ics")
                                      :data-my-schedule-export ""}
            [:input {:type "hidden" :name "session-ids"
                     :value (personal-schedule/selection-value selected-ids)}]
            [:button.ui.primary.button {:type "submit"} "Export My Schedule (.ics)"]]
           (for [session selected] (session-row event session true))))]
      (personal-schedule/browser-script))))
