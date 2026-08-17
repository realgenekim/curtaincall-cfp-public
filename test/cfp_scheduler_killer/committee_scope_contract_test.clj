(ns cfp-scheduler-killer.committee-scope-contract-test
  "Organizer-to-reviewer contract for committees as rosters plus board lenses."
  (:require
   [cfp-scheduler-killer.auth :as auth]
   [cfp-scheduler-killer.committees :as committees]
   [cfp-scheduler-killer.events :as events]
   [cfp-scheduler-killer.review-assignments :as review-assignments]
   [cfp-scheduler-killer.server :as server]
   [cfp-scheduler-killer.store :as store]
   [cfp-scheduler-killer.submissions :as submissions]
   [cfp-scheduler-killer.test-helpers :refer [with-temp-store]]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [ring.mock.request :as mock])
  (:import
   (java.time LocalDateTime)))

(use-fixtures :each with-temp-store)

(def track-a "AI Models")
(def track-b "Architecture")

(defn- make-event! []
  (events/create-eais-event!
   {:name "Committee Scope Summit"
    :slug "committee-scope"
    :tz "UTC"
    :cfp-opens-at (LocalDateTime/of 2020 1 1 0 0)
    :cfp-closes-at (LocalDateTime/of 2099 1 1 0 0)}
   "kaocha"))

(defn- submit! [event title track]
  (let [fields (:fields (events/form-for-event (:id event)))
        params {:answer-talk-title title
                :answer-abstract "Abstract."
                :answer-session-format "Experience Report"
                :answer-track track
                :answer-org-size ">10,000"
                :answer-industry "Technology"
                :answer-ai-transformation-history "2024"
                :answer-measurable-outcomes "Measured."
                :answer-notes-to-committee "Private."
                :speaker-name "Sam Speaker"
                :speaker-email (str (random-uuid) "@example.com")
                :speaker-title "VP"
                :speaker-org "ExampleCo"
                :speaker-bio "Bio."}]
    (submissions/create-submission!
     event
     (submissions/parse-answers fields params)
     (submissions/parse-speaker params)
     "form"
     "kaocha")))

(defn- login-cookie [handler email]
  (let [token (auth/issue-token! email)
        response (handler (mock/request :get (str "/auth/" token)))
        set-cookie (first (get-in response [:headers "Set-Cookie"]))]
    (assert set-cookie "login did not set a session cookie")
    (first (str/split set-cookie #";"))))

(defn- as [request cookie]
  (mock/header request "cookie" cookie))

(deftest committee-roster-and-scope-drive-reviewer-board-test
  (reset! auth/tokens {})
  (let [event (make-event!)
        committee-id (:id (first (events/committees-for-event (:id event))))
        chair (committees/add-member!
               committee-id
               {:name "Casey Chair" :email "chair@example.com" :role "chair"}
               "kaocha")
        in-track (submit! event "In-scope talk" track-a)
        out-of-track (submit! event "Open-table talk" track-b)
        handler (server/create-app)
        chair-cookie (login-cookie handler "chair@example.com")
        add-response
        (handler
         (as (mock/request
              :post
              (str "/api/committees/" committee-id "/members/add")
              {"name" "Riley Reviewer"
               "email" "reviewer@example.com"
               "role" "reviewer"})
             chair-cookie))]
    (testing "the organizer can add a reviewer through the real roster action"
      (is (= 303 (:status add-response)))
      (is (= #{"chair@example.com" "reviewer@example.com"}
             (set (map :email (committees/members-for-committee committee-id)))))
      (is (= "chair" (:role chair))))

    (let [reviewer-cookie (login-cookie handler "reviewer@example.com")
          committee-page
          (handler
           (as (mock/request :get "/events/committee-scope/committee")
               chair-cookie))]
      (testing "the committee page replaces the retired committee-wide editor with member chips"
        (is (= 200 (:status committee-page)))
        (is (not (str/includes? (:body committee-page) "Default board scope")))
        (is (str/includes? (:body committee-page) "Default work queue:"))
        (is (str/includes? (:body committee-page) "filter, not a wall")))

      (testing "a member scope fact stores the reviewer's default queue"
        (let [reviewer-id (:id (committees/person-by-email "reviewer@example.com"))]
          (committees/set-member-scope! committee-id reviewer-id #{track-a} "chair@example.com")
          (is (= #{track-a}
                 (committees/tracks-for-person-on-event (:id event) reviewer-id)))))

      (testing "the reviewer board defaults to the committee lens"
        (let [response
              (handler
               (as (mock/request :get "/events/committee-scope/board")
                   reviewer-cookie))]
          (is (= 200 (:status response)))
          (is (str/includes? (:body response) "In-scope talk"))
          (is (not (str/includes? (:body response) "Open-table talk")))
          (is (str/includes? (:body response) "Show all tracks"))))

      (testing "scope is not authorization: assigned out-of-scope work still works"
        (let [reviewer-id (:id (committees/person-by-email "reviewer@example.com"))
              _assignment (review-assignments/assign!
                           (:id out-of-track) reviewer-id "chair@example.com")
              open-board
              (handler
               (as (mock/request :get "/events/committee-scope/board?all=1")
                   reviewer-cookie))
              rate-response
              (handler
               (as (mock/request
                    :post
                    (str "/api/submissions/" (:id out-of-track) "/rate")
                    {"stars" "4.5"})
                   reviewer-cookie))]
          (is (= 200 (:status open-board)))
          (is (str/includes? (:body open-board) "In-scope talk"))
          (is (str/includes? (:body open-board) "Open-table talk"))
          (is (#{200 204 302 303} (:status rate-response)))
          (is (= 4.5 (:stars (store/rating-by (:id out-of-track) reviewer-id))))))

      (testing "clearing the scope restores the open-table default"
        (let [response
              (handler
               (as (mock/request
                    :post
                    "/api/events/committee-scope/details"
                    {"intent" "committee-scope"
                     "committee-id" committee-id
                     "tracks" "   "})
                   chair-cookie))]
          (is (= 303 (:status response)))
          (is (= {:all true} (:scope (committees/committee-by-id committee-id))))))

      (testing "removing a reviewer removes only the roster membership"
        (let [reviewer (first (filter #(= "reviewer@example.com" (:email %))
                                      (committees/members-for-committee committee-id)))
              response
              (handler
               (as (mock/request
                    :post
                    (str "/api/memberships/" (:membership-id reviewer) "/remove"))
                   chair-cookie))]
          (is (= 303 (:status response)))
          (is (= ["chair@example.com"]
                 (mapv :email (committees/members-for-committee committee-id))))
          (is (some? (committees/person-by-email "reviewer@example.com")))
          (is (= (:id in-track) (:id (store/submission-by-id (:id in-track))))))))))
