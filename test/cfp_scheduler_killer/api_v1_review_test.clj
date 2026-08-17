(ns cfp-scheduler-killer.api-v1-review-test
  (:require
   [cfp-scheduler-killer.committees :as committees]
   [cfp-scheduler-killer.events :as events]
   [cfp-scheduler-killer.exports :as exports]
   [cfp-scheduler-killer.review-assignments :as review-assignments]
   [cfp-scheduler-killer.reviews :as reviews]
   [cfp-scheduler-killer.server :as server]
   [cfp-scheduler-killer.store :as store]
   [cfp-scheduler-killer.submissions :as submissions]
   [cfp-scheduler-killer.test-helpers :refer [with-temp-store]]
   [clojure.data.json :as json]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [ring.mock.request :as mock])
  (:import
   (java.time LocalDateTime)))

(use-fixtures :each with-temp-store)

(defn- setup! []
  (let [event (events/create-event!
                {:name "API Review Summit"
                 :slug "api-review"
                 :tz "America/New_York"
                 :cfp-opens-at (LocalDateTime/of 2020 1 1 0 0)
                 :cfp-closes-at (LocalDateTime/of 2099 1 1 0 0)}
                "kaocha")
        committee-id (:id (first (events/committees-for-event (:id event))))
        reviewer (committees/add-member! committee-id
                                         {:name "Human Reviewer"
                                          :email "reviewer@example.com"
                                          :role "reviewer"}
                                         "kaocha")
        fields (:fields (events/form-for-event (:id event)))
        submission (submissions/create-submission!
                     event
                     (submissions/parse-answers
                       fields
                       {:answer-talk-title "An API-reviewed talk"
                        :answer-abstract "Evidence and outcomes."
                        :answer-session-format "Experience Report"
                        :answer-org-size ">10,000"
                        :answer-industry "Technology"
                        :answer-ai-transformation-history "2024."
                        :answer-measurable-outcomes "Numbers."})
                     (submissions/parse-speaker
                       {:speaker-name "API Speaker"
                        :speaker-email "speaker@example.com"
                        :speaker-title "VP Platform"
                        :speaker-org "ExampleCo"
                        :speaker-bio "Bio."})
                     "form"
                     "kaocha")]
    (review-assignments/assign! (:id submission) (:person-id reviewer) "kaocha")
    {:event (events/event-by-slug "api-review")
     :reviewer reviewer
     :submission submission}))

(defn- post-review [handler submission-id secret body]
  (handler
    (-> (mock/request :post
                      (str "/api/v1/events/api-review/submissions/"
                           submission-id "/reviews"))
        (mock/header "authorization" (str "Bearer " secret))
        (mock/header "content-type" "application/json")
        (mock/body (json/write-str body)))))

(defn- body-data [response]
  (json/read-str (:body response)))

(defn- facts-of-type [event-id type]
  (filterv #(= type (:type %)) (store/log-for-event event-id)))

(deftest review-bot-post-is-idempotent-and-structurally-separate-test
  (let [{:keys [event submission]} (setup!)
        key-row (exports/create-api-key! event "Program bot" :review-bot nil
                                         "gene@example.com")
        handler (server/create-app)
        request {"stars" 4
                 "comment" "matched love-pole: war story with production numbers"
                 "idempotency-key" "bot-run-7/sub-142"}
        first-response (post-review handler (:id submission) (:key key-row) request)
        first-data (body-data first-response)
        actor (str "api:review-bot:" (subs (:key key-row) 0 8))
        fact (first (facts-of-type (:id event) "ai-review.recorded"))]
    (testing "the scope selects the frozen AI fact and stable event identity"
      (is (= 201 (:status first-response)))
      (is (= actor (get first-data "actor")))
      (is (= #{:id :submission-id :generation-id :ai-reviewer-id :stars :comment
               :idempotency-key :api-key-id :at}
             (set (keys (:payload fact)))))
      (is (= (str "ai-reviewer:" (:id event))
             (get-in fact [:payload :ai-reviewer-id])))
      (is (str/starts-with? (get-in fact [:payload :generation-id]) "api:"))
      (is (= actor (:actor fact)))
      (is (= (get first-data "review-id") (get-in fact [:payload :id]))))

    (testing "AI facts never enter human rating or comment math"
      (is (= 0 (:n (reviews/submission-stats (:id submission)))))
      (is (empty? (store/comments-for-submission (:id submission)))))

    (testing "an identical retry returns the original receipt without append"
      (let [retry (post-review handler (:id submission) (:key key-row) request)]
        (is (= 200 (:status retry)))
        (is (= first-data (body-data retry)))
        (is (= 1 (count (facts-of-type (:id event) "ai-review.recorded"))))))

    (testing "reusing the key for a different body is a 409"
      (let [conflict (post-review handler (:id submission) (:key key-row)
                                  (assoc request "stars" 2))]
        (is (= 409 (:status conflict)))
        (is (str/includes? (:body conflict) "different review body"))
        (is (= 1 (count (facts-of-type (:id event) "ai-review.recorded"))))))))

(deftest reviewer-scope-reuses-human-review-verbs-and-dedupes-test
  (let [{:keys [event reviewer submission]} (setup!)
        key-row (exports/create-api-key! event "Reviewer's agent" :reviewer
                                         (:person-id reviewer) "gene@example.com")
        handler (server/create-app)
        request {"stars" 4.5
                 "comment" "Strong evidence; tighten the ending."
                 "idempotency-key" "human-run-1/sub-1"}
        first-response (post-review handler (:id submission) (:key key-row) request)
        first-data (body-data first-response)
        actor (str "api:reviewer:" (subs (:key key-row) 0 8))
        after-first (count (store/log-for-event (:id event)))]
    (testing "human scope writes ordinary signed rating and comment facts"
      (is (= 201 (:status first-response)))
      (is (= actor (get first-data "actor")))
      (is (= 4.5 (:stars (store/rating-by (:id submission) (:person-id reviewer)))))
      (is (= ["Strong evidence; tighten the ending."]
             (mapv :body (store/comments-for-submission (:id submission)))))
      (is (= 1 (:n (reviews/submission-stats (:id submission)))))
      (is (= 4.5 (:mean (reviews/submission-stats (:id submission)))))
      (is (every? #(= actor (:actor %))
                  (filter #(#{"rating.set" "comment.added"
                              "api-review.idempotency-recorded"} (:type %))
                          (store/log-for-event (:id event))))))

    (testing "an identical retry does not duplicate the comment or ledger facts"
      (let [retry (post-review handler (:id submission) (:key key-row) request)]
        (is (= 200 (:status retry)))
        (is (= first-data (body-data retry)))
        (is (= after-first (count (store/log-for-event (:id event)))))
        (is (= 1 (count (store/comments-for-submission (:id submission)))))))

    (testing "a different human review cannot reuse the receipt key"
      (is (= 409 (:status (post-review handler (:id submission) (:key key-row)
                                       (assoc request "comment" "Changed")))))
      (is (= after-first (count (store/log-for-event (:id event))))))))

(deftest review-post-refuses-wrong-scopes-and-invalid-input-before-append-test
  (let [{:keys [event submission]} (setup!)
        read-key (exports/create-api-key! event "Read only" :read nil "gene@example.com")
        organizer-key (exports/create-api-key! event "Organizer" :organizer nil "gene@example.com")
        bot-key (exports/create-api-key! event "Bot" :review-bot nil "gene@example.com")
        handler (server/create-app)
        path-request {"stars" 4 "idempotency-key" "refusal-1"}
        before (count (facts-of-type (:id event) "ai-review.recorded"))]
    (testing "missing or wrong scopes are explicit 401/403 refusals"
      (is (= 401 (:status (post-review handler (:id submission) "" path-request))))
      (is (= 403 (:status (post-review handler (:id submission) (:key read-key) path-request))))
      (is (= 403 (:status (post-review handler (:id submission) (:key organizer-key) path-request)))))

    (testing "invalid body and entity inputs append nothing"
      (is (= 422 (:status (post-review handler (:id submission) (:key bot-key)
                                       {"stars" 99 "idempotency-key" "bad-stars"}))))
      (is (= 422 (:status (post-review handler (:id submission) (:key bot-key)
                                       {"stars" 4}))))
      (is (= 404 (:status (post-review handler "no-such-submission" (:key bot-key)
                                       path-request))))
      (is (= before (count (facts-of-type (:id event) "ai-review.recorded")))))))
