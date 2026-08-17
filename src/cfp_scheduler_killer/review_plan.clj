(ns cfp-scheduler-killer.review-plan
  "Thin effectful shell around presenter-visibility policy."
  (:require
   [cfp-scheduler-killer.domain.review-plan :as decisions]
   [cfp-scheduler-killer.store :as store]
   [taoensso.timbre :as log]))

(defn blind-review? [event-id]
  (decisions/blind-review? (store/snapshot) event-id))

(defn presenter-visibility-policy
  [event-id]
  (decisions/presenter-visibility-policy (store/snapshot) event-id))

(defn default-presenter-visibility-policy
  [event-id]
  (decisions/default-presenter-visibility-policy (store/snapshot) event-id))

(defn visibility-policy-event
  "Adapt the canonical durable presenter policy to the legacy voting-policy seam."
  [event]
  (let [mode (:mode (presenter-visibility-policy (:id event)))]
    (-> event
        (assoc-in [:settings :hide-presenter-info] (not= "visible" mode))
        (assoc-in [:settings :reveal-after-vote] (= "reveal-after-vote" mode)))))

(defn project-submission [event-id person-id row]
  (decisions/project-submission (store/snapshot) event-id person-id row))

(defn project-submissions [event-id person-id rows]
  (let [state (store/snapshot)]
    (mapv #(decisions/project-submission state event-id person-id %) rows)))

(defn- accepted! [{:keys [facts result rejection]}]
  (when rejection
    (throw (ex-info (:message rejection) rejection)))
  (when (seq facts)
    (store/append-all! facts))
  result)

(defn- command [event-id actor-person-id actor attrs]
  (merge attrs
         {:event-id event-id
          :actor-person-id actor-person-id
          :actor actor
          :at (store/now-iso)}))

(defn set-presenter-visibility!
  [event-id mode expected-version actor-person-id actor]
  (let [decision (decisions/decide-set-presenter-visibility
                   (store/snapshot)
                   (command event-id actor-person-id actor
                            {:mode mode :expected-version expected-version}))
        result (accepted! decision)]
    (when (seq (:facts decision))
      (log/info :review-presenter-visibility-set
                :event-id event-id
                :mode mode
                :version (get-in result [:policy :version])))
    (or (get-in (store/snapshot) [:review-plans event-id :presenter-visibility])
        (:policy result))))
