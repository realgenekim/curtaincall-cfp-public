(ns cfp-scheduler-killer.domain.review-work-test
  (:require
   [cfp-scheduler-killer.domain.review-work :as work]
   [cfp-scheduler-killer.folds :as folds]
   [clojure.test :refer [deftest is testing]]))

(def base-state
  (assoc folds/empty-state
         :events {"event" {:id "event-1" :slug "event" :name "Event"}}
         :committees {"committee-1" {:id "committee-1" :event-id "event-1"}}
         :people {"chair" {:id "chair" :name "Chair"}
                  "reviewer" {:id "reviewer" :name "Reviewer"}
                  "outsider" {:id "outsider" :name "Outsider"}}
         :memberships {"m-chair" {:id "m-chair" :committee-id "committee-1"
                                  :person-id "chair" :role "chair"}
                       "m-reviewer" {:id "m-reviewer" :committee-id "committee-1"
                                     :person-id "reviewer" :role "reviewer"}}
         :submissions {"s-1" {:id "s-1" :event-id "event-1"}
                       "s-2" {:id "s-2" :event-id "event-1"}}
         :review-assignments {["s-1" "reviewer"] {:submission-id "s-1" :person-id "reviewer"}
                              ["s-2" "reviewer"] {:submission-id "s-2" :person-id "reviewer"}}
         :ratings {["s-1" "reviewer"] {:submission-id "s-1" :person-id "reviewer" :stars 4.0}}))

(def recuse-command
  {:submission-id "s-2" :person-id "reviewer" :reason "I work with the speaker."
   :actor "reviewer@example.com" :at "2026-08-10T12:00:00Z"})

(deftest recusal-is-algebraic-idempotent-and-reversible
  (let [decision (work/decide-recuse base-state recuse-command)
        state-after (reduce folds/fold-event base-state (:facts decision))
        repeated (work/decide-recuse state-after recuse-command)
        undo (work/decide-unrecuse state-after
                                   {:submission-id "s-2" :person-id "reviewer"
                                    :actor "reviewer@example.com"
                                    :at "2026-08-10T12:01:00Z"})
        restored (reduce folds/fold-event state-after (:facts undo))]
    (is (= ["reviewer.recused"] (mapv :type (:facts decision))))
    (is (work/recused? state-after "s-2" "reviewer"))
    (is (= {:assigned 1 :completed 1 :remaining 0}
           (select-keys (work/progress-for-reviewer state-after "event-1" "reviewer")
                        [:assigned :completed :remaining])))
    (is (empty? (:facts repeated)))
    (is (= ["reviewer.unrecused"] (mapv :type (:facts undo))))
    (is (not (work/recused? restored "s-2" "reviewer")))
    (is (= {:assigned 2 :completed 1 :remaining 1}
           (select-keys (work/progress-for-reviewer restored "event-1" "reviewer")
                        [:assigned :completed :remaining])))))

(deftest recusal-rejections-are-values
  (testing "reason and tenancy are enforced before a fact exists"
    (is (= :missing-recusal-reason
           (get-in (work/decide-recuse base-state (assoc recuse-command :reason " "))
                   [:rejection :type])))
    (is (= :not-event-reviewer
           (get-in (work/decide-recuse base-state (assoc recuse-command :person-id "outsider"))
                   [:rejection :type])))))

(deftest nudge-recording-is-chair-only-human-authored-and-idempotent
  (let [command {:event-id "event-1" :person-id "reviewer"
                 :actor-person-id "chair" :actor "chair@example.com"
                 :at "2026-08-10T12:02:00Z" :nudge-id "nudge-1"
                 :body "Please finish the remaining review."}
        decision (work/decide-record-nudge base-state command)
        state-after (reduce folds/fold-event base-state (:facts decision))]
    (is (= ["reviewer.nudge-recorded"] (mapv :type (:facts decision))))
    (is (= {:assigned 2 :completed 1 :remaining 1}
           (select-keys (get-in decision [:result :progress])
                        [:assigned :completed :remaining])))
    (is (empty? (:facts (work/decide-record-nudge state-after command))))
    (is (= :chair-required
           (get-in (work/decide-record-nudge
                     base-state (assoc command :actor-person-id "reviewer"))
                   [:rejection :type])))
    (is (= :missing-nudge-body
           (get-in (work/decide-record-nudge base-state (assoc command :body ""))
                   [:rejection :type])))))
