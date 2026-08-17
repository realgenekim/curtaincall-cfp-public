(ns cfp-scheduler-killer.speaker-profile-completeness-contract-test
  (:require
   [cfp-scheduler-killer.auth :as auth]
   [cfp-scheduler-killer.events :as events]
   [cfp-scheduler-killer.portal :as portal]
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

(deftest organizer-roster-names-the-missing-profile-fields
  (let [handler (server/create-app)
        organizer-cookie (session-cookie handler "organizer@example.com")
        organizer (partial as organizer-cookie)
        _ (handler
            (organizer
              (mock/request :post "/api/events/create"
                            {"name" "Profile Completion Summit"
                             "slug" "profile-completion"
                             "starts-on" "2026-10-14"
                             "ends-on" "2026-10-15"
                             "presenter-visibility-mode" "visible"})))
        event (events/event-by-slug "profile-completion")
        result (speakers/add! (:id event)
                              {:name "Ada Completion"
                               :email "ada-completion@example.com"
                               :status "Invited"
                               :actor "organizer@example.com"})
        person-id (:person-id result)
        page #(-> (handler
                    (organizer
                      (mock/request :get "/events/profile-completion/speakers")))
                  :body)]
    (testing "the empty profile names both outstanding fields"
      (is (str/includes? (page) "Missing bio + headshot")))

    (portal/update-profile! person-id {:bio "Ada builds humane systems."}
                            "ada-completion@example.com")

    (testing "partial completion identifies only the remaining field"
      (let [body (page)]
        (is (str/includes? body "Missing headshot"))
        (is (not (str/includes? body "Missing bio + headshot")))))

    (portal/update-profile!
      person-id {:headshot-url "https://images.example.test/ada.png"}
      "ada-completion@example.com")

    (testing "the warning clears once the existing completion policy is met"
      (is (str/includes? (page) "Complete ✓")))))
