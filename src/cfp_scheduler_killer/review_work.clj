(ns cfp-scheduler-killer.review-work
  "Thin event-store shell around pure reviewer-work decisions."
  (:require
   [cfp-scheduler-killer.committees :as committees]
   [cfp-scheduler-killer.domain.review-assignments :as assignment-decisions]
   [cfp-scheduler-killer.domain.review-work :as decisions]
   [cfp-scheduler-killer.mail :as mail]
   [cfp-scheduler-killer.review-policy :as review-policy]
   [cfp-scheduler-killer.reviews :as reviews]
   [cfp-scheduler-killer.store :as store]
   [clojure.string :as str]
   [taoensso.timbre :as log]))

(defn- accepted! [decision]
  (if-let [rejection (:rejection decision)]
    (throw (ex-info (:message rejection) rejection))
    (do
      (when (seq (:facts decision))
        (store/append-all! (:facts decision)))
      (:result decision))))

(defn- accepted-together!
  "Validate related decisions before appending all of their facts atomically."
  [decisions]
  (doseq [decision decisions]
    (accepted! (assoc decision :facts [])))
  (let [facts (mapcat :facts decisions)]
    (when (seq facts)
      (store/append-all! facts))
    (:result (first decisions))))

(defn recusal-for [submission-id person-id]
  (get-in (store/snapshot) [:review-recusals [submission-id person-id]]))

(defn recused? [submission-id person-id]
  (boolean (recusal-for submission-id person-id)))

(defn progress-for-reviewer [event-id person-id]
  (decisions/progress-for-reviewer (store/snapshot) event-id person-id))

(defn latest-nudge [event-id person-id]
  (->> (:reviewer-nudges (store/snapshot))
       vals
       (filter #(and (= event-id (:event-id %)) (= person-id (:person-id %))))
       (sort-by :recorded-at)
       last))

(defn progress-for-event
  "Every committee member's current review obligation, with last-touch data."
  ([event-id]
   (progress-for-event event-id (reviews/enriched-for-event event-id)))
  ([event-id rows]
   (let [state (store/snapshot)
         target (review-policy/coverage-target-for event-id)
         committee-ids (->> (:committees state) vals
                            (filter #(= event-id (:event-id %)))
                            (map :id) set)]
     (->> (:memberships state)
          vals
          (filter #(contains? committee-ids (:committee-id %)))
          (map (fn [membership]
                 (let [person (get-in state [:people (:person-id membership)])
                       scope (committees/member-scope membership)
                       scope-tracks (when (set? scope) scope)
                       scope-rows (reviews/filter-board rows {:tracks scope-tracks})]
                   (merge (decisions/progress-for-reviewer state event-id (:person-id membership))
                          {:name (:name person)
                           :email (:email person)
                           :role (:role membership)
                           :scope scope
                           :scope-label (if (= :all scope)
                                          "All tracks"
                                          (str/join ", " (sort scope)))
                           :scope-coverage (reviews/coverage-for-rows scope-rows target)
                           :review-count (:rated-count
                                           (reviews/reviewer-summary
                                             event-id (:person-id membership)))
                           :last-nudge (latest-nudge event-id (:person-id membership))}))))
          (sort-by (juxt :remaining :review-count :name) #(compare %2 %1))
          vec))))

(defn nudge-body
  [event-name {:keys [name completed assigned remaining]}]
  (str "Hi " (or (first (str/split (or name "there") #"\\s+")) "there") ",\n\n"
       "You have " remaining " assigned review" (when (not= 1 remaining) "s")
       " remaining for " event-name " (" completed " of " assigned " complete). "
       "Could you take a look when you have a moment?\n\nThank you."))

(defn recuse! [submission-id person-id reason actor]
  (let [state (store/snapshot)
        at (store/now-iso)
        decision (decisions/decide-recuse
                   state
                   {:submission-id submission-id :person-id person-id
                    :reason reason :actor actor :at at})
        unassignment (when (seq (:facts decision))
                       (assignment-decisions/decide-unassign
                         state
                         {:submission-id submission-id :person-id person-id
                          :actor actor :at at}))
        retired? (seq (:facts unassignment))
        decision (cond-> decision
                   retired? (assoc-in [:facts 0 :payload :assignment-retired?] true))
        result (accepted-together! (cond-> [decision]
                                     unassignment (conj unassignment)))]
    (when (seq (:facts decision))
      (log/info :reviewer-recused :submission-id submission-id :person-id person-id))
    (or (recusal-for submission-id person-id) result)))

(defn unrecuse! [submission-id person-id actor]
  (let [state (store/snapshot)
        recusal (get-in state [:review-recusals [submission-id person-id]])
        at (store/now-iso)
        decision (decisions/decide-unrecuse
                   state
                   {:submission-id submission-id :person-id person-id
                    :actor actor :at at})
        assignment (when (and (seq (:facts decision))
                              (:assignment-retired? recusal))
                     (assignment-decisions/decide-assign
                       state
                       {:submission-id submission-id :person-id person-id
                        :actor actor :at at}))
        result (accepted-together! (cond-> [decision]
                                     assignment (conj assignment)))]
    (when (seq (:facts decision))
      (log/info :reviewer-unrecused :submission-id submission-id :person-id person-id))
    result))

(defn record-nudges!
  "Validate the whole human-edited batch before appending any nudge facts."
  [event-id drafts actor-person-id actor]
  (let [state (store/snapshot)
        at (store/now-iso)
        commands (mapv (fn [{:keys [person-id body]}]
                         {:event-id event-id :person-id person-id
                          :actor-person-id actor-person-id :actor actor
                          :at at :nudge-id (store/new-id) :body body})
                       drafts)
        decisions (mapv #(decisions/decide-record-nudge state %) commands)]
    (doseq [decision decisions] (accepted! (assoc decision :facts [])))
    (let [facts (mapcat :facts decisions)]
      (when (seq facts) (store/append-all! facts))
      (doseq [fact facts]
        (log/info :reviewer-nudge-recorded
                  :event-id event-id :person-id (get-in fact [:payload :person-id])))
      (mapv #(get-in (store/snapshot) [:reviewer-nudges (:nudge-id %)]) commands))))

(defn queue-nudge-emails!
  "Queue one already-reviewed reminder per recorded nudge.

   The record step validates and appends the whole batch before this function
   creates any email. `mail/send!` is the application's human-approval gate:
   it records `email.queued`; it never bypasses the outbox or calls a provider."
  [event-id nudges actor base-url]
  (let [state (store/snapshot)
        event (some #(when (= event-id (:id %)) %) (vals (:events state)))
        queue-url (str base-url "/events/" (:slug event) "/board?assigned=1")]
    (mapv
      (fn [{:keys [person-id body]}]
        (let [person (get-in state [:people person-id])]
          (mail/send!
            {:to (:email person)
             :reply-to (:support-email event)
             :subject (str "Review reminder — " (:name event))
             :body (str body "\n\nOpen your assigned review queue: " queue-url)}
            {:event-id event-id
             :kind "reviewer.nudge"
             :person-id person-id
             :actor actor})))
      nudges)))
