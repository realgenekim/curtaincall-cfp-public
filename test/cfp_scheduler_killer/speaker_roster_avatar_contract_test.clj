(ns cfp-scheduler-killer.speaker-roster-avatar-contract-test
  (:require
   [cfp-scheduler-killer.auth :as auth]
   [cfp-scheduler-killer.events :as events]
   [cfp-scheduler-killer.portal :as portal]
   [cfp-scheduler-killer.server :as server]
   [cfp-scheduler-killer.speakers :as speakers]
   [cfp-scheduler-killer.test-helpers :refer [with-temp-store]]
   [cfp-scheduler-killer.views.avatar :as avatar]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [ring.mock.request :as mock]))

(use-fixtures :each with-temp-store)

(defn- session-cookie [handler email]
  (let [token (auth/issue-token! email)
        response (handler (mock/request :get (str "/auth/" token)))]
    (first (str/split (first (get-in response [:headers "Set-Cookie"])) #";"))))

(deftest organizer-roster-always-has-a-speaker-avatar
  (let [handler (server/create-app)
        cookie (session-cookie handler "organizer@example.com")
        as-organizer #(mock/header % "cookie" cookie)
        _ (handler
            (as-organizer
              (mock/request :post "/api/events/create"
                            {"name" "Roster Avatar Summit"
                             "slug" "roster-avatars"
                             "starts-on" "2026-10-14"
                             "ends-on" "2026-10-15"
                             "presenter-visibility-mode" "visible"})))
        event (events/event-by-slug "roster-avatars")
        missing (speakers/add! (:id event)
                               {:name "Ada Noheadshot"
                                :email "ada-noheadshot@example.com"
                                :status "Invited"
                                :actor "organizer@example.com"})
        uploaded (speakers/add! (:id event)
                                {:name "Grace Uploaded"
                                 :email "grace-uploaded@example.com"
                                 :status "Invited"
                                 :actor "organizer@example.com"})
        uploaded-url "https://images.example.test/grace.png"
        _ (portal/update-profile! (:person-id uploaded)
                                  {:headshot-url uploaded-url}
                                  "grace-uploaded@example.com")
        fallback #(str "/test-avatar/" % ".jpg")
        body (with-redefs [avatar/pool-face fallback]
               (:body (handler
                        (as-organizer
                          (mock/request :get "/events/roster-avatars/speakers")))))]
    (testing "a speaker without a headshot gets the established fallback"
      (is (str/includes? body (fallback (:person-id missing))))
      (is (str/includes? body "alt=\"Ada Noheadshot headshot\"")))

    (testing "a canonical headshot remains authoritative"
      (is (str/includes? body uploaded-url))
      (is (not (str/includes? body (fallback (:person-id uploaded))))))))
