(ns cfp-scheduler-killer.domain.review-assignments
  "Pure reviewer-assignment decisions: state + command -> facts or rejection."
  (:require
   [cfp-scheduler-killer.domain.committees :as committees]))

(defn- rejection
  [type message data]
  {:rejection (assoc data :type type :message message)})

(defn- recused-assignment?
  [state submission-id person-id]
  (boolean (get-in state [:review-recusals [submission-id person-id]])))

(defn decide-assign
  "Return assignment facts/result or a structured rejection."
  [state {:keys [submission-id person-id actor at]}]
  (let [submission (get-in state [:submissions submission-id])
        event-id (:event-id submission)
        existing (get-in state [:review-assignments [submission-id person-id]])]
    (cond
      (nil? submission)
      (rejection :no-such-submission
                 (str "No such submission: " submission-id)
                 {:submission-id submission-id})

      (nil? (committees/role-on-event state event-id person-id))
      (rejection :not-event-reviewer
                 "The assignee must be a committee reviewer for this event."
                 {:event-id event-id
                  :submission-id submission-id
                  :person-id person-id})

      existing
      {:facts [] :result existing}

      :else
      (let [assignment {:submission-id submission-id
                        :person-id person-id
                        :assigned-at at
                        :assigned-by actor}]
        {:facts [{:type "reviewer.assigned"
                  :at at
                  :actor actor
                  :event-id event-id
                  :payload assignment}]
         :result assignment}))))

(defn decide-unassign
  "Return unassignment facts/result or a structured rejection."
  [state {:keys [submission-id person-id actor at]}]
  (let [submission (get-in state [:submissions submission-id])
        existing (get-in state [:review-assignments [submission-id person-id]])]
    (cond
      (nil? submission)
      (rejection :no-such-submission
                 (str "No such submission: " submission-id)
                 {:submission-id submission-id})

      (nil? existing)
      {:facts [] :result {}}

      :else
      {:facts [{:type "reviewer.unassigned"
                :at at
                :actor actor
                :event-id (:event-id submission)
                :payload {:submission-id submission-id
                          :person-id person-id
                          :unassigned-at at}}]
       :result existing})))

(defn decide-distribute
  "Pure, deterministic bulk assignment plan. Assign at most one selected
   reviewer to each selected submission, always choosing the currently
   lightest reviewer below the explicit total-assignment cap. Existing work
   counts toward the cap; repeating the command is an idempotent no-op."
  [state {:keys [event-id submission-ids person-ids cap actor at]}]
  (let [submission-ids (vec (distinct submission-ids))
        person-ids (vec (distinct person-ids))
        cap (when (number? cap) (long cap))
        bad-submission (some (fn [submission-id]
                               (let [submission (get-in state [:submissions submission-id])]
                                 (when (or (nil? submission)
                                           (not= event-id (:event-id submission)))
                                   submission-id)))
                             submission-ids)
        bad-reviewer (some #(when-not (committees/role-on-event state event-id %) %)
                           person-ids)]
    (cond
      (not-any? #(= event-id (:id %)) (vals (:events state)))
      (rejection :no-such-event "No such event." {:event-id event-id})

      (empty? submission-ids)
      (rejection :no-submissions "Select at least one submission."
                 {:event-id event-id})

      bad-submission
      (rejection :submission-outside-event
                 "Every selected submission must belong to this event."
                 {:event-id event-id :submission-id bad-submission})

      (empty? person-ids)
      (rejection :no-reviewers "Select at least one reviewer."
                 {:event-id event-id})

      bad-reviewer
      (rejection :not-event-reviewer
                 "Every selected assignee must be a committee reviewer for this event."
                 {:event-id event-id :person-id bad-reviewer})

      (or (nil? cap) (not (pos? cap)) (> cap 500))
      (rejection :invalid-cap "Per-reviewer cap must be between 1 and 500."
                 {:event-id event-id :cap cap})

      :else
      (let [positions (zipmap person-ids (range))
            initial-loads
            (into {}
                  (map (fn [person-id]
                         [person-id
                          (->> (:review-assignments state)
                               keys
                               (filter (fn [[submission-id assigned-person-id]]
                                         (and (= person-id assigned-person-id)
                                              (= event-id
                                                 (get-in state
                                                         [:submissions submission-id :event-id]))
                                              (not (recused-assignment?
                                                     state submission-id person-id)))))
                               count)]))
                  person-ids)]
        (loop [remaining submission-ids
               loads initial-loads
               assignments []
               unassigned []]
          (if-let [submission-id (first remaining)]
            (let [already? (some #(and (get-in state
                                               [:review-assignments [submission-id %]])
                                       (not (recused-assignment?
                                              state submission-id %)))
                                 person-ids)
                  eligible (when-not already?
                             (->> person-ids
                                  (remove #(recused-assignment?
                                             state submission-id %))
                                  (filter #(< (get loads %) cap))
                                  (sort-by (juxt #(get loads %) #(get positions %)))))
                  person-id (first eligible)]
              (cond
                already?
                (recur (next remaining) loads assignments
                       (conj unassigned {:submission-id submission-id
                                         :reason :already-assigned}))

                person-id
                (recur (next remaining)
                       (update loads person-id inc)
                       (conj assignments {:submission-id submission-id
                                          :person-id person-id})
                       unassigned)

                :else
                (recur (next remaining) loads assignments
                       (conj unassigned {:submission-id submission-id
                                         :reason :cap-reached}))))
            {:facts (mapv (fn [{:keys [submission-id person-id]}]
                            {:type "reviewer.assigned"
                             :at at
                             :actor actor
                             :event-id event-id
                             :payload {:submission-id submission-id
                                       :person-id person-id
                                       :assigned-at at
                                       :assigned-by actor}})
                          assignments)
             :result {:event-id event-id
                      :cap cap
                      :loads-before initial-loads
                      :loads-after loads
                      :assignments assignments
                      :unassigned unassigned}}))))))
