(ns cfp-scheduler-killer.domain.committees
  "Pure programming-committee projection queries.")

(defn role-on-event
  "A person's committee role for one event in an explicit projection, or nil."
  [state event-id person-id]
  (let [committee-ids (->> (:committees state)
                           vals
                           (filter #(= event-id (:event-id %)))
                           (map :id)
                           set)]
    (some (fn [membership]
            (when (and (= person-id (:person-id membership))
                       (contains? committee-ids (:committee-id membership)))
              (:role membership)))
          (vals (:memberships state)))))
