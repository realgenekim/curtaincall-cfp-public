(ns cfp-scheduler-killer.domain.review-assignments-test
  (:require
   [cfp-scheduler-killer.domain.review-assignments :as decisions]
   [cfp-scheduler-killer.folds :as folds]
   [clojure.test :refer [deftest is testing]]))

(defn- assignment-world
  [event-id submission-id person-id]
  (assoc folds/empty-state
         :submissions {submission-id {:id submission-id :event-id event-id}}
         :committees {"committee-1" {:id "committee-1" :event-id event-id}}
         :memberships {"membership-1" {:id "membership-1"
                                       :committee-id "committee-1"
                                       :person-id person-id
                                       :role "reviewer"}}))

(defn- assign-command
  [submission-id person-id at]
  {:submission-id submission-id
   :person-id person-id
   :actor "chair@example.com"
   :at at})

(deftest assignment-decisions-are-algebraic-test
  (testing "eight identities obey the same deterministic assign/invert laws"
    (doseq [n (range 1 9)]
      (let [event-id (str "event-" n)
            submission-id (str "submission-" n)
            person-id (str "reviewer-" n)
            at (str "2026-08-10T12:00:0" n "Z")
            later-at (str "2026-08-10T13:00:0" n "Z")
            state (assignment-world event-id submission-id person-id)
            command (assign-command submission-id person-id at)
            assignment {:submission-id submission-id
                        :person-id person-id
                        :assigned-at at
                        :assigned-by "chair@example.com"}
            assign-fact {:type "reviewer.assigned"
                         :at at
                         :actor "chair@example.com"
                         :event-id event-id
                         :payload assignment}
            assign-decision (decisions/decide-assign state command)
            assigned-state (folds/fold-event state assign-fact)
            folded-assignment (get-in assigned-state
                                      [:review-assignments [submission-id person-id]])
            unassign-command (assoc command :at later-at)
            unassign-fact {:type "reviewer.unassigned"
                           :at later-at
                           :actor "chair@example.com"
                           :event-id event-id
                           :payload {:submission-id submission-id
                                     :person-id person-id
                                     :unassigned-at later-at}}
            unassign-decision (decisions/decide-unassign assigned-state unassign-command)
            restored-state (folds/fold-event assigned-state unassign-fact)]
        (is (= {:facts [assign-fact] :result assignment} assign-decision))
        (is (= assigned-state (folds/fold-event state (first (:facts assign-decision)))))
        (is (= {:facts [] :result folded-assignment}
               (decisions/decide-assign assigned-state command)))
        (is (= {:facts [unassign-fact] :result folded-assignment} unassign-decision))
        (is (empty? (:review-assignments restored-state)))
        (is (= {:facts [] :result {}}
               (decisions/decide-unassign restored-state unassign-command)))))))

(deftest assignment-rejections-are-data-test
  (let [state (assignment-world "event-a" "submission-a" "reviewer-a")]
    (testing "missing submissions are rejected without effects"
      (is (= {:rejection {:type :no-such-submission
                          :message "No such submission: missing"
                          :submission-id "missing"}}
             (decisions/decide-assign
               state (assign-command "missing" "reviewer-a" "fixed-time")))))
    (testing "a reviewer from another event cannot cross the tenancy boundary"
      (is (= {:rejection {:type :not-event-reviewer
                          :message "The assignee must be a committee reviewer for this event."
                          :event-id "event-a"
                          :submission-id "submission-a"
                          :person-id "reviewer-b"}}
             (decisions/decide-assign
               state (assign-command "submission-a" "reviewer-b" "fixed-time")))))))

(deftest capped-bulk-distribution-is-deterministic-and-idempotent
  (let [state (assoc folds/empty-state
                     :events {"event-a" {:id "event-a"}
                              "event-b" {:id "event-b"}}
                     :submissions (into {"existing" {:id "existing" :event-id "event-a"}
                                         "foreign" {:id "foreign" :event-id "event-b"}}
                                        (map (fn [n]
                                               [(str "submission-" n)
                                                {:id (str "submission-" n)
                                                 :event-id "event-a"}])
                                             (range 1 5)))
                     :committees {"committee-a" {:id "committee-a" :event-id "event-a"}
                                  "committee-b" {:id "committee-b" :event-id "event-b"}}
                     :memberships {"r1" {:id "r1" :committee-id "committee-a"
                                         :person-id "reviewer-1" :role "reviewer"}
                                   "r2" {:id "r2" :committee-id "committee-a"
                                         :person-id "reviewer-2" :role "reviewer"}
                                   "r3" {:id "r3" :committee-id "committee-b"
                                         :person-id "reviewer-3" :role "reviewer"}}
                     :review-assignments {["existing" "reviewer-1"]
                                          {:submission-id "existing"
                                           :person-id "reviewer-1"}})
        command {:event-id "event-a"
                 :submission-ids (mapv #(str "submission-" %) (range 1 5))
                 :person-ids ["reviewer-1" "reviewer-2"]
                 :cap 2
                 :actor "chair@example.com"
                 :at "2026-08-10T14:00:00Z"}
        decision (decisions/decide-distribute state command)
        assigned-state (reduce folds/fold-event state (:facts decision))]
    (testing "the lightest queue wins, ties preserve reviewer order, and caps are total"
      (is (= [{:submission-id "submission-1" :person-id "reviewer-2"}
              {:submission-id "submission-2" :person-id "reviewer-1"}
              {:submission-id "submission-3" :person-id "reviewer-2"}]
             (get-in decision [:result :assignments])))
      (is (= [{:submission-id "submission-4" :reason :cap-reached}]
             (get-in decision [:result :unassigned])))
      (is (= {"reviewer-1" 2 "reviewer-2" 2}
             (get-in decision [:result :loads-after])))
      (is (= 3 (count (:facts decision))))
      (is (every? #(= "reviewer.assigned" (:type %)) (:facts decision))))
    (testing "the exact retry appends nothing"
      (is (empty? (:facts (decisions/decide-distribute assigned-state command)))))
    (testing "event scope, reviewer scope, and cap errors are data"
      (is (= :submission-outside-event
             (get-in (decisions/decide-distribute
                       state (assoc command :submission-ids ["foreign"]))
                     [:rejection :type])))
      (is (= :not-event-reviewer
             (get-in (decisions/decide-distribute
                       state (assoc command :person-ids ["reviewer-3"]))
                     [:rejection :type])))
      (is (= :invalid-cap
             (get-in (decisions/decide-distribute state (assoc command :cap 0))
                     [:rejection :type]))))))
