(ns cfp-scheduler-killer.review-assignments-test
  "Optional reviewer scoping: assignments narrow a reviewer's work queue without
   hiding the shared board or changing the default-open committee doctrine."
  (:require
   [cfp-scheduler-killer.committees :as committees]
   [cfp-scheduler-killer.events :as events]
   [cfp-scheduler-killer.review-assignments :as assignments]
   [cfp-scheduler-killer.review-work :as review-work]
   [cfp-scheduler-killer.reviews :as reviews]
   [cfp-scheduler-killer.store :as store]
   [cfp-scheduler-killer.submissions :as submissions]
   [cfp-scheduler-killer.test-helpers :refer [with-temp-store]]
   [clojure.test :refer [deftest is testing use-fixtures]])
  (:import
   (java.time LocalDateTime)))

(use-fixtures :each with-temp-store)

(defn- make-event! [name slug]
  (events/create-eais-event!
    {:name name
     :slug slug
     :tz "America/New_York"
     :cfp-opens-at (LocalDateTime/of 2020 1 1 0 0)
     :cfp-closes-at (LocalDateTime/of 2099 1 1 0 0)}
    "kaocha"))

(defn- add-reviewer! [event name email role]
  (let [committee-id (:id (first (events/committees-for-event (:id event))))]
    (committees/add-member! committee-id {:name name :email email :role role} "kaocha")))

(defn- submit!
  ([event title] (submit! event title nil))
  ([event title track]
   (let [fields (:fields (events/form-for-event (:id event)))
         params (cond-> {:answer-talk-title title
                         :answer-abstract "Abstract."
                         :answer-session-format "Experience Report"
                         :answer-org-size ">10,000"
                         :answer-industry "Insurance"
                         :answer-ai-transformation-history "2023."
                         :answer-measurable-outcomes "Numbers."
                         :answer-notes-to-committee "Private note."
                         :speaker-name "Ann Speaker"
                         :speaker-email (str (random-uuid) "@example.com")
                         :speaker-title "VP"
                         :speaker-org "BigCo"
                         :speaker-bio "Bio."}
                  track (assoc :answer-track track))]
     (submissions/create-submission!
       event
       (submissions/parse-answers fields params)
       (submissions/parse-speaker params)
       "form"
       "kaocha"))))

(deftest assignment-roundtrip-test
  (let [event (make-event! "Assignment Summit" "assignment-summit")
        reviewer (add-reviewer! event "Sam Reviewer" "sam@example.com" "reviewer")
        talk-a (submit! event "Talk A")
        talk-b (submit! event "Talk B")
        assignment (assignments/assign! (:id talk-a) (:person-id reviewer)
                                        "chair@example.com")]

    (testing "the shell returns the canonical folded value and stays idempotent"
      (is (instance? java.time.Instant (:assigned-at assignment)))
      (is (= assignment
             (assignments/assign! (:id talk-a) (:person-id reviewer)
                                  "chair@example.com")))
      (is (= 1 (->> (store/log-for-event (:id event))
                    (filter #(= "reviewer.assigned" (:type %)))
                    count))))

    (testing "an assignment is folded into the reviewer's explicit queue"
      (is (assignments/assigned? (:id talk-a) (:person-id reviewer)))
      (is (= [(:id talk-a)]
             (mapv :id (assignments/submissions-for-reviewer
                         (:id event) (:person-id reviewer))))))

    (testing "the shared board remains the default-open scope"
      (is (= #{(:id talk-a) (:id talk-b)}
             (set (map :id (assignments/scoped-submissions
                             (:id event) (:person-id reviewer) :all)))))
      (is (= [(:id talk-a)]
             (mapv :id (assignments/scoped-submissions
                         (:id event) (:person-id reviewer) :assigned)))))

    (testing "unassigning appends a fact and removes the relationship"
      (assignments/unassign! (:id talk-a) (:person-id reviewer) "chair@example.com")
      (is (false? (assignments/assigned? (:id talk-a) (:person-id reviewer))))
      (is (= ["reviewer.assigned" "reviewer.unassigned"]
             (->> (store/log-for-event (:id event))
                  (map :type)
                  (filter #(contains? #{"reviewer.assigned" "reviewer.unassigned"} %))
                  vec))))))

(deftest assignment-tenancy-test
  (let [event-a (make-event! "Event A" "event-a")
        event-b (make-event! "Event B" "event-b")
        reviewer-b (add-reviewer! event-b "Other Reviewer" "other@example.com" "reviewer")
        talk-a (submit! event-a "Talk A")]
    (testing "a reviewer from another event cannot be assigned"
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #"committee reviewer"
            (assignments/assign! (:id talk-a) (:person-id reviewer-b) "chair@example.com"))))
    (is (empty? (assignments/assignments-for-submission (:id talk-a))))))

(deftest reviewer-progress-test
  (let [event (make-event! "Progress Summit" "progress-summit")
        chair (add-reviewer! event "Casey Chair" "chair@example.com" "chair")
        reviewer (add-reviewer! event "Sam Reviewer" "sam@example.com" "reviewer")
        talk-a (submit! event "Talk A")
        talk-b (submit! event "Talk B")]
    (assignments/assign! (:id talk-a) (:person-id reviewer) "chair@example.com")
    (assignments/assign! (:id talk-b) (:person-id reviewer) "chair@example.com")
    (reviews/set-rating! (:id talk-a) (:person-id reviewer) 4.0 "sam@example.com")

    (is (= {:assigned 2 :completed 1 :remaining 1}
           (select-keys (assignments/progress-for-reviewer
                          (:id event) (:person-id reviewer))
                        [:assigned :completed :remaining])))
    (is (= 1
           (:review-count
             (some #(when (= (:person-id reviewer) (:person-id %)) %)
                   (review-work/progress-for-event (:id event))))))

    (testing "recusal leaves history but removes the conflict from active work"
      (review-work/recuse! (:id talk-b) (:person-id reviewer)
                           "I work with this speaker." "sam@example.com")
      (is (= {:assigned 1 :completed 1 :remaining 0}
             (select-keys (assignments/progress-for-reviewer
                            (:id event) (:person-id reviewer))
                          [:assigned :completed :remaining])))
      (is (= [(:id talk-a)]
             (mapv :id (assignments/submissions-for-reviewer
                         (:id event) (:person-id reviewer)))))
      (is (empty? (assignments/assignments-for-submission (:id talk-b))))
      (review-work/unrecuse! (:id talk-b) (:person-id reviewer) "sam@example.com")
      (is (= 1 (:remaining (assignments/progress-for-reviewer
                             (:id event) (:person-id reviewer)))))
      (is (= #{(:id talk-a) (:id talk-b)}
             (set (map :id (assignments/submissions-for-reviewer
                             (:id event) (:person-id reviewer))))))
      (is (= [(:person-id reviewer)]
             (mapv :person-id
                   (assignments/assignments-for-submission (:id talk-b))))))

    (testing "a chair records the reviewed draft without sending anything"
      (let [records (review-work/record-nudges!
                      (:id event)
                      [{:person-id (:person-id reviewer)
                        :body "Please finish Talk B."}]
                      (:person-id chair) "chair@example.com")]
        (is (= 1 (count records)))
        (is (= "Please finish Talk B." (:body (first records))))
        (is (= 1 (get-in (first records) [:progress :remaining])))
        (is (= "reviewer.nudge-recorded"
               (:type (last (store/log-for-event (:id event))))))))))

(deftest bulk-distribution-preview-confirm-and-track-test
  (let [event (make-event! "Bulk Assignment Summit" "bulk-assignment-summit")
        reviewer-a (add-reviewer! event "Reviewer A" "a@example.com" "reviewer")
        reviewer-b (add-reviewer! event "Reviewer B" "b@example.com" "reviewer")
        talk-a1 (submit! event "Applied One" "AI Models")
        talk-a2 (submit! event "Applied Two" "AI Models")
        talk-b (submit! event "Platform One" "Architecture")
        selected [(:person-id reviewer-a) (:person-id reviewer-b)]]
    (assignments/assign! (:id talk-a1) (:person-id reviewer-a) "chair@example.com")
    (let [before (->> (store/log-for-event (:id event))
                      (filter #(= "reviewer.assigned" (:type %)))
                      vec)
          preview (assignments/preview-distribution
                    (:id event) "AI Models" selected 2 "chair@example.com")]
      (testing "preview is pure, track-filtered, and accounts for existing load"
        (is (= before
               (->> (store/log-for-event (:id event))
                    (filter #(= "reviewer.assigned" (:type %)))
                    vec)))
        (is (= [(:id talk-a1) (:id talk-a2)]
               (assignments/eligible-submission-ids (:id event) "AI Models")))
        (is (= [{:submission-id (:id talk-a2)
                 :person-id (:person-id reviewer-b)}]
               (:assignments preview)))
        (is (= [{:submission-id (:id talk-a1) :reason :already-assigned}]
               (:unassigned preview)))
        (is (not (contains? (set (map :submission-id (:assignments preview)))
                            (:id talk-b)))))
      (testing "confirmation appends the previewed canonical fact exactly once"
        (is (= preview
               (assignments/distribute!
                 (:id event) "AI Models" selected 2 "chair@example.com")))
        (is (assignments/assigned? (:id talk-a2) (:person-id reviewer-b)))
        (is (= 2 (->> (store/log-for-event (:id event))
                      (filter #(= "reviewer.assigned" (:type %)))
                      count)))
        (let [after-confirm (->> (store/log-for-event (:id event))
                                 (filter #(= "reviewer.assigned" (:type %)))
                                 vec)
              retry (assignments/distribute!
                      (:id event) "AI Models" selected 2 "chair@example.com")]
          (is (empty? (:assignments retry)))
          (is (= after-confirm
                 (->> (store/log-for-event (:id event))
                      (filter #(= "reviewer.assigned" (:type %)))
                      vec))))))))

(deftest recused-assignments-do-not-consume-distribution-cap-test
  (let [event (make-event! "Recusal Distribution" "recusal-distribution")
        reviewer-a (add-reviewer! event "Reviewer A" "a@example.com" "reviewer")
        reviewer-b (add-reviewer! event "Reviewer B" "b@example.com" "reviewer")
        talk-a (submit! event "Conflicted assignment")
        talk-b (submit! event "Replacement work")
        selected [(:person-id reviewer-a) (:person-id reviewer-b)]]
    (assignments/assign! (:id talk-a) (:person-id reviewer-a) "chair@example.com")
    (review-work/recuse! (:id talk-a) (:person-id reviewer-a)
                         "Conflict of interest" "a@example.com")
    (let [preview (assignments/preview-distribution
                    (:id event) nil selected 1 "chair@example.com")]
      (is (= {(:person-id reviewer-a) 0 (:person-id reviewer-b) 0}
             (:loads-before preview)))
      (is (= #{{:submission-id (:id talk-a) :person-id (:person-id reviewer-b)}
               {:submission-id (:id talk-b) :person-id (:person-id reviewer-a)}}
             (set (:assignments preview))))
      (is (empty? (:unassigned preview)))
      (assignments/distribute! (:id event) nil (:confirmation-ids preview) 1
                               "chair@example.com")
      (is (assignments/assigned? (:id talk-a) (:person-id reviewer-b)))
      (is (assignments/assigned? (:id talk-b) (:person-id reviewer-a))))))
