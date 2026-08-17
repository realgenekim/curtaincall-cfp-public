(ns cfp-scheduler-killer.domain.review-work
  "Pure decisions and projections for review obligations, conflicts, and nudges."
  (:require
   [cfp-scheduler-killer.domain.committees :as committees]
   [clojure.string :as str]))

(defn- rejection [type message data]
  {:rejection (assoc data :type type :message message)})

(defn recused?
  [state submission-id person-id]
  (boolean (get-in state [:review-recusals [submission-id person-id]])))

(defn review-complete?
  "A current star rating completes an assigned review."
  [state _event-id submission-id person-id]
  (some? (get-in state [:ratings [submission-id person-id] :stars])))

(defn progress-for-reviewer
  "Derive one reviewer's active obligation counts from assignments and
   recusals. Recused assignments leave the denominator; a current star rating
   completes work."
  [state event-id person-id]
  (let [assigned-ids (->> (:review-assignments state)
                          keys
                          (keep (fn [[submission-id assigned-person-id]]
                                  (let [submission (get-in state [:submissions submission-id])]
                                    (when (and (= person-id assigned-person-id)
                                               (= event-id (:event-id submission))
                                               (not (recused? state submission-id person-id)))
                                      submission-id))))
                          set)
        completed (count (filter #(review-complete? state event-id % person-id)
                                 assigned-ids))]
    {:event-id event-id
     :person-id person-id
     :assigned (count assigned-ids)
     :completed completed
     :remaining (- (count assigned-ids) completed)}))

(defn decide-recuse
  [state {:keys [submission-id person-id reason actor at]}]
  (let [submission (get-in state [:submissions submission-id])
        event-id (:event-id submission)
        existing (get-in state [:review-recusals [submission-id person-id]])
        reason (some-> reason str/trim)]
    (cond
      (nil? submission)
      (rejection :no-such-submission (str "No such submission: " submission-id)
                 {:submission-id submission-id})

      (nil? (committees/role-on-event state event-id person-id))
      (rejection :not-event-reviewer
                 "Only a committee member for this event can recuse from its reviews."
                 {:event-id event-id :submission-id submission-id :person-id person-id})

      (str/blank? reason)
      (rejection :missing-recusal-reason
                 "Say why you are recusing so the chair can plan coverage."
                 {:event-id event-id :submission-id submission-id :person-id person-id})

      existing
      {:facts [] :result existing}

      :else
      (let [recusal {:submission-id submission-id
                     :person-id person-id
                     :reason reason
                     :recused-at at
                     :recused-by actor}]
        {:facts [{:type "reviewer.recused"
                  :at at :actor actor :event-id event-id :payload recusal}]
         :result recusal}))))

(defn decide-unrecuse
  [state {:keys [submission-id person-id actor at]}]
  (let [submission (get-in state [:submissions submission-id])
        event-id (:event-id submission)
        existing (get-in state [:review-recusals [submission-id person-id]])]
    (cond
      (nil? submission)
      (rejection :no-such-submission (str "No such submission: " submission-id)
                 {:submission-id submission-id})

      (nil? (committees/role-on-event state event-id person-id))
      (rejection :not-event-reviewer
                 "Only a committee member for this event can restore their review."
                 {:event-id event-id :submission-id submission-id :person-id person-id})

      (nil? existing)
      {:facts [] :result {}}

      :else
      {:facts [{:type "reviewer.unrecused"
                :at at :actor actor :event-id event-id
                :payload {:submission-id submission-id
                          :person-id person-id
                          :unrecused-at at}}]
       :result existing})))

(defn decide-record-nudge
  [state {:keys [event-id person-id actor-person-id actor at nudge-id body]}]
  (let [progress (progress-for-reviewer state event-id person-id)
        actor-role (committees/role-on-event state event-id actor-person-id)
        recipient-role (committees/role-on-event state event-id person-id)
        existing (get-in state [:reviewer-nudges nudge-id])
        body (some-> body str/trim)]
    (cond
      (not= "chair" (some-> actor-role name str/lower-case))
      (rejection :chair-required "Only an event chair can record reviewer nudges."
                 {:event-id event-id :actor-person-id actor-person-id})

      (nil? recipient-role)
      (rejection :not-event-reviewer "That reviewer is not on this event."
                 {:event-id event-id :person-id person-id})

      (str/blank? body)
      (rejection :missing-nudge-body "Write a message before recording the nudge."
                 {:event-id event-id :person-id person-id})

      (not (pos? (:remaining progress)))
      (rejection :no-review-work-remaining
                 "That reviewer has no assigned reviews remaining."
                 {:event-id event-id :person-id person-id :progress progress})

      existing
      {:facts [] :result existing}

      :else
      (let [nudge {:id nudge-id
                   :event-id event-id
                   :person-id person-id
                   :body body
                   :progress progress
                   :recorded-at at
                   :recorded-by actor}]
        {:facts [{:type "reviewer.nudge-recorded"
                  :at at :actor actor :event-id event-id :payload nudge}]
         :result nudge}))))
