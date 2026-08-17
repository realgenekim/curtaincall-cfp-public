(ns cfp-scheduler-killer.views.personal-schedule
  "Server-rendered controls for a browser-owned attendee selection."
  (:require
   [cfp-scheduler-killer.views.shell :as shell]
   [clojure.string :as str]))

(defn selection-value [selected-ids]
  (str/join "," (sort (map str selected-ids))))

(defn browser-state [event selected-ids]
  {:data-my-schedule-root ""
   :data-my-schedule-event-id (:id event)
   :data-my-schedule-selected (selection-value selected-ids)})

(defn toggle-control
  [_event submission-id selected?]
  [:button.ui.tiny.basic.button.personal-schedule-toggle
   {:type "button" :data-my-schedule-toggle "" :data-session-id submission-id
    :aria-pressed (if selected? "true" "false")
    :aria-label (str (if selected? "Remove from" "Add to") " My schedule")}
   (if selected? "★ In My schedule" "☆ Add to My schedule")])

(defn links
  [event selection-count my-schedule?]
  [:div.personal-schedule-links
   [:a {:href (str "/agenda/" (:slug event) "/my") :data-my-schedule-link ""}
    "My schedule" (when (pos? selection-count) (str " (" selection-count ")"))]
   (when my-schedule?
     (list " · " "Export selected sessions below"))])

(defn browser-script []
  [:script {:src (shell/versioned "/js/my-schedule.js") :defer true}])
