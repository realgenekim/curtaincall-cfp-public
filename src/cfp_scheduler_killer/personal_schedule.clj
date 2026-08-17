(ns cfp-scheduler-killer.personal-schedule
  "Pure projection of browser-owned attendee selections onto public sessions."
  (:require
   [cfp-scheduler-killer.exports :as exports]
   [cfp-scheduler-killer.store :as store]))

(defn selected-submissions
  "Project browser-owned IDs onto publishable sessions in itinerary order."
  [event selected-ids]
  (let [selected (set (map str selected-ids))]
    (->> (exports/publishable-sessions event)
         (filter #(contains? selected (:id %)))
         (sort-by (fn [submission]
                    (let [slot (store/slot-for (:id submission))
                          placed? (boolean (and slot (:day slot)))]
                      [(if placed? 0 1)
                       (if placed? (str (:day slot)) "")
                       (if placed? (or (:start slot) 0) 0)
                       (str (:id submission))])))
         vec)))
