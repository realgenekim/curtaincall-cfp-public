(ns cfp-scheduler-killer.speaker-organizer-profile-org-contract-test
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

(deftest organizer-and-speaker-share-canonical-profile-organization
  (let [handler (server/create-app)
        organizer-cookie (session-cookie handler "organizer@example.com")
        organizer (partial as organizer-cookie)
        _ (handler
            (organizer
              (mock/request :post "/api/events/create"
                            {"name" "Profile Organization Summit"
                             "slug" "profile-organization"
                             "starts-on" "2026-10-14"
                             "ends-on" "2026-10-15"
                             "presenter-visibility-mode" "visible"})))
        event (events/event-by-slug "profile-organization")
        added (speakers/add! (:id event)
                             {:name "Ada Organization"
                              :email "ada-organization@example.com"
                              :status "Invited"
                              :actor "organizer@example.com"})
        person-id (:person-id added)
        roster-path "/events/profile-organization/speakers"
        speaker-path (str roster-path "/" person-id)
        edit-path (str "/api/events/profile-organization/speakers/" person-id)]
    (is (= person-id
           (:person-id (first (speakers/roster-for-event (:id event))))))
    (testing "the organizer form distinguishes shared organization from its event override"
      (let [body (:body (handler (organizer (mock/request :get speaker-path))))]
        (is (str/includes? body "name=\"org\""))
        (is (str/includes? body "Event organization override"))))

    (testing "an organizer edit writes the same canonical profile the portal reads"
      (is (= 303
             (:status
               (handler
                 (organizer
                   (mock/request :post edit-path
                                 {"profile-edit" "true"
                                  "name" "Ada Organization"
                                  "org" "Canonical Labs"
                                  "organization" ""}))))))
      (let [person (store/person-by-id person-id)
            roster-speaker (first (speakers/roster-for-event (:id event)))
            speaker-cookie (session-cookie handler "ada-organization@example.com")
            portal-body (:body
                          (handler
                            (as speaker-cookie (mock/request :get "/portal"))))]
        (is (= "Canonical Labs" (get-in person [:profile :org])))
        (is (= "Canonical Labs" (:profile-organization roster-speaker)))
        (is (= "Canonical Labs" (:organization roster-speaker)))
        (is (str/includes? portal-body
                           "name=\"org\" type=\"text\" value=\"Canonical Labs\""))))

    (testing "a later portal edit returns to the organizer's shared-profile field"
      (let [speaker-cookie (session-cookie handler "ada-organization@example.com")]
        (is (= 303
               (:status
                 (handler
                   (as speaker-cookie
                       (mock/request :post "/api/profile"
                                     {"org" "Speaker Updated Labs"})))))))
      (let [roster-speaker (first (speakers/roster-for-event (:id event)))
            organizer-body (:body
                             (handler
                               (organizer (mock/request :get speaker-path))))]
        (is (= "Speaker Updated Labs" (:profile-organization roster-speaker)))
        (is (= "Speaker Updated Labs" (:organization roster-speaker)))
        (is (str/includes? organizer-body
                           "name=\"org\" value=\"Speaker Updated Labs\""))))))
