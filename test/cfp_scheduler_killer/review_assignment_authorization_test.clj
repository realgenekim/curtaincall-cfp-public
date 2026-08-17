(ns cfp-scheduler-killer.review-assignment-authorization-test
  (:require
   [cfp-scheduler-killer.auth :as auth]
   [cfp-scheduler-killer.committees :as committees]
   [cfp-scheduler-killer.events :as events]
   [cfp-scheduler-killer.notices :as notices]
   [cfp-scheduler-killer.review-assignments :as assignments]
   [cfp-scheduler-killer.review-authorization :as review-authorization]
   [cfp-scheduler-killer.reviews :as reviews]
   [cfp-scheduler-killer.server :as server]
   [cfp-scheduler-killer.sse :as sse]
   [cfp-scheduler-killer.store :as store]
   [cfp-scheduler-killer.submissions :as submissions]
   [cfp-scheduler-killer.test-helpers :refer [with-temp-store]]
   [clojure.data.json :as json]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [ring.mock.request :as mock])
  (:import
   (java.time LocalDateTime)))

(use-fixtures :each
  with-temp-store
  (fn [f]
    (reset! auth/tokens {})
    (reset! sse/subscribers {})
    (notices/clear-all!)
    (f)))

(defn- setup! []
  (let [event (events/create-event!
                {:name "Assignment Authorization Summit"
                 :slug "assignment-authorization"
                 :tz "UTC"
                 :cfp-opens-at (LocalDateTime/of 2020 1 1 0 0)
                 :cfp-closes-at (LocalDateTime/of 2099 1 1 0 0)}
                "chair@example.com")
        committee-id (:id (first (events/committees-for-event (:id event))))
        chair-membership
        (committees/add-member! committee-id
                                {:name "Casey Chair"
                                 :email "chair@example.com"
                                 :role "chair"}
                                "chair@example.com")
        reviewer-membership
        (committees/add-member! committee-id
                                {:name "Riley Reviewer"
                                 :email "reviewer@example.com"
                                 :role "reviewer"}
                                "chair@example.com")
        fields (:fields (events/form-for-event (:id event)))
        submit! (fn [title email]
                  (let [params {:answer-talk-title title
                                :answer-abstract "Authorization is evidence-bound."
                                :answer-session-format "Experience Report"
                                :answer-org-size ">10,000"
                                :answer-industry "Technology"
                                :answer-ai-transformation-history "2026."
                                :answer-measurable-outcomes "No stray scores."
                                :answer-notes-to-committee "Private note."
                                :speaker-name "Sam Speaker"
                                :speaker-email email
                                :speaker-title "Engineer"
                                :speaker-org "ExampleCo"
                                :speaker-bio "Bio."}]
                    (submissions/create-submission!
                      event
                      (submissions/parse-answers fields params)
                      (submissions/parse-speaker params)
                      "form"
                      "test")))
        assigned (submit! "Assigned proposal" "assigned@example.com")
        unassigned (submit! "Unassigned proposal" "unassigned@example.com")]
    (assignments/assign! (:id assigned) (:person-id reviewer-membership)
                         "chair@example.com")
    {:event event
     :chair-id (:person-id chair-membership)
     :reviewer-id (:person-id reviewer-membership)
     :assigned assigned
     :unassigned unassigned}))

(defn- session-app [email]
  (let [app (server/create-app)
        token (auth/issue-token! email)
        response (app (mock/request :get (str "/auth/" token)))
        cookie (first (str/split (first (get-in response [:headers "Set-Cookie"])) #";"))]
    (fn [request]
      (app (mock/header request "cookie" cookie)))))

(defn- post-json [handler path body]
  (handler (-> (mock/request :post path (json/write-str body))
               (mock/header "content-type" "application/json")
               (mock/header "accept" "application/json"))))

;; INTENT-TEST: AUTHZ-002
(deftest unassigned-committee-reviewer-post-succeeds-and-non-member-is-refused-test
  (let [{:keys [reviewer-id unassigned]} (setup!)
        reviewer-app (session-app "reviewer@example.com")
        outsider-app (session-app "unassigned@example.com")
        path (str "/api/submissions/" (:id unassigned) "/rate")]
    (testing "a committee reviewer may rate work outside their assigned queue"
      (is (= 204 (:status (post-json reviewer-app path {:stars 5}))))
      (is (= 5.0 (:stars (store/rating-by (:id unassigned) reviewer-id)))))
    (testing "a non-member still cannot append review evidence"
      (let [before (count (filter #(= "rating.set" (:type %))
                                  (store/read-events)))
            response (post-json outsider-app path {:stars 4})]
        ;; Event membership is refused before the review-write handler.
        (is (= 403 (:status response)))
        (is (= before
               (count (filter #(= "rating.set" (:type %))
                              (store/read-events)))))))))

(deftest chair-exemption-is-role-specific-test
  (let [{:keys [chair-id reviewer-id unassigned]} (setup!)
        chair-app (session-app "chair@example.com")]
    (is (= 204 (:status (post-json chair-app
                                   (str "/api/submissions/" (:id unassigned) "/rate")
                                   {:stars 3.5}))))
    (is (= 3.5 (:stars (store/rating-by (:id unassigned) chair-id))))
    (is (nil? (store/rating-by (:id unassigned) reviewer-id)))))

(deftest revoked-assignment-does-not-revoke-committee-write-proof-test
  (let [{:keys [reviewer-id assigned]} (setup!)
        proof (review-authorization/require-write-proof!
                (store/snapshot) (:id assigned) reviewer-id)]
    (assignments/unassign! (:id assigned) reviewer-id "chair@example.com")
    (reviews/rate! proof "5" "reviewer@example.com")
    (is (= 5.0 (:stars (store/rating-by (:id assigned) reviewer-id))))))
