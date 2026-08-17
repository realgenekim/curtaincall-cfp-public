(ns cfp-scheduler-killer.speaker-roster-person-link-contract-test
  (:require
   [cfp-scheduler-killer.auth :as auth]
   [cfp-scheduler-killer.events :as events]
   [cfp-scheduler-killer.server :as server]
   [cfp-scheduler-killer.speakers :as speakers]
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

(deftest organizer-can-open-a-speaker-edit-page-from-the-roster
  (let [handler (server/create-app)
        cookie (session-cookie handler "organizer@example.com")
        organizer (partial as cookie)
        _ (handler
            (organizer
              (mock/request :post "/api/events/create"
                            {"name" "Speaker People Summit"
                             "slug" "speaker-people"
                             "starts-on" "2026-10-14"
                             "ends-on" "2026-10-15"
                             "presenter-visibility-mode" "visible"})))
        event (events/event-by-slug "speaker-people")
        added (speakers/add! (:id event)
                             {:name "Ada Person"
                              :email "ada-person@example.com"
                              :status "Invited"
                              :actor "organizer@example.com"})
        person-path (str "/events/speaker-people/speakers/" (:person-id added))
        roster-body (:body
                      (handler
                        (organizer
                          (mock/request :get "/events/speaker-people/speakers"))))]
    (testing "the roster exposes the dedicated speaker edit page"
      (is (str/includes? roster-body (str "href=\"" person-path "\"")))
      (is (str/includes? roster-body "Ada Person")))

    (testing "the linked edit page resolves the same canonical speaker"
      (let [response (handler (organizer (mock/request :get person-path)))
            body (:body response)]
        (is (= 200 (:status response)))
        (is (str/includes? body "Edit Speaker Details"))
        (is (str/includes? body "Ada Person"))
        (is (str/includes? body "ada-person@example.com"))))))
