(ns cfp-scheduler-killer.speaker-profile-management-contract-test
  (:require
   [cfp-scheduler-killer.auth :as auth]
   [cfp-scheduler-killer.events :as events]
   [cfp-scheduler-killer.server :as server]
   [cfp-scheduler-killer.speakers :as speakers]
   [cfp-scheduler-killer.store :as store]
   [cfp-scheduler-killer.test-helpers :refer [with-temp-store]]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [ring.mock.request :as mock]))

(use-fixtures :each with-temp-store)

(defn- session-cookie [handler email]
  (let [token (auth/issue-token! email)
        response (handler (mock/request :get (str "/auth/" token)))]
    (first (str/split (first (get-in response [:headers "Set-Cookie"])) #";"))))

(defn- as [cookie request]
  (mock/header request "cookie" cookie))

(deftest organizer-can-explicitly-maintain-the-canonical-speaker-profile
  (let [handler (server/create-app)
        organizer-cookie (session-cookie handler "organizer@example.com")
        organizer (partial as organizer-cookie)
        _ (handler
            (organizer
              (mock/request :post "/api/events/create"
                            {"name" "Speaker Profile Summit"
                             "slug" "speaker-profile"
                             "starts-on" "2026-10-14"
                             "ends-on" "2026-10-15"
                             "presenter-visibility-mode" "visible"})))
        event (events/event-by-slug "speaker-profile")
        _ (handler
            (organizer
              (mock/request :post "/api/events/speaker-profile/speakers"
                            {"name" "Priya Profile"
                             "email" "priya-profile@example.com"
                             "status" "Invited"})))
        speaker (first (speakers/roster-for-event (:id event)))
        person-id (:person-id speaker)
        edit-path (str "/api/events/speaker-profile/speakers/" person-id)]
    (testing "the roster links to one dedicated, always-open speaker form"
      (let [detail-path (str "/events/speaker-profile/speakers/" person-id)
            roster-body (:body
                          (handler
                            (organizer
                              (mock/request :get "/events/speaker-profile/speakers?status="))))
            detail-body (:body (handler (organizer (mock/request :get detail-path))))]
        (is (str/includes? roster-body "Edit Speaker Details"))
        (is (str/includes? roster-body (str "href=\"" detail-path "\"")))
        (is (not (str/includes? roster-body "name=\"profile-edit\"")))
        (is (str/includes? detail-body "Edit Speaker Details"))
        (is (str/includes? detail-body "name=\"profile-edit\""))
        (is (str/includes? detail-body "name=\"status\""))
        (is (str/includes? detail-body "name=\"bio\""))
        (is (str/includes? detail-body "name=\"headshot-url\""))
        (is (str/includes? detail-body "name=\"linkedin-url\""))
        (is (not (str/includes? detail-body "<summary>Edit Speaker Details")))))

    (testing "invalid profile data cannot partially save event details"
      (let [response
            (handler
              (organizer
                (mock/request :post edit-path
                              {"profile-edit" "true"
                               "title" "Premature title"
                               "bio" "A bio that must not save yet."
                               "headshot-url" "not-a-url"})))
            current (first (speakers/roster-for-event (:id event)))]
        (is (= 422 (:status response)))
        (is (str/includes? (:body response) "Enter a full URL starting with"))
        (is (nil? (:title current)))
        (is (nil? (:bio current)))))

    (testing "one explicit save updates the canonical profile and event fields"
      (let [response
            (handler
              (organizer
                (mock/request :post edit-path
                              {"profile-edit" "true"
                               "status" "Confirmed"
                               "title" "VP Engineering"
                               "organization" "Event Program Org"
                               "notes" "Green room at 08:30"
                               "bio" "Priya builds reliable platforms."
                               "headshot-url" "https://images.example.test/priya-profile.png"
                               "linkedin-url" "https://linkedin.example.test/priya"})))
            person (store/person-by-id person-id)
            current (first (speakers/roster-for-event (:id event)))]
        (is (= 303 (:status response)))
        (is (= "Confirmed" (:status current)))
        (is (= "VP Engineering" (:title current)))
        (is (= "Event Program Org" (:organization current)))
        (is (= "Green room at 08:30" (:notes current)))
        (is (= "Priya builds reliable platforms." (:bio current)))
        (is (= "https://images.example.test/priya-profile.png"
               (:headshot-url current)))
        (is (= "https://linkedin.example.test/priya"
               (get-in person [:profile :linkedin-url])))))

    (testing "the organizer save is immediately visible in the speaker portal"
      (let [speaker-cookie (session-cookie handler "priya-profile@example.com")
            body (:body (handler (as speaker-cookie (mock/request :get "/portal"))))]
        (is (str/includes? body "Priya builds reliable platforms."))
        (is (str/includes? body
                           "value=\"https://images.example.test/priya-profile.png\""))
        (is (str/includes? body
                           "value=\"https://linkedin.example.test/priya\""))))))
