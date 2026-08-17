(ns cfp-scheduler-killer.speaker-portal-profile-completion-contract-test
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

(deftest speaker-portal-names-the-current-profile-completion-state
  (let [event (events/create-event!
                {:name "Profile Truth Summit"
                 :slug "profile-truth"
                 :starts-on "2026-10-14"
                 :ends-on "2026-10-15"}
                "organizer@example.com")
        _ (speakers/add! (:id event)
                         {:name "Ada Speaker"
                          :email "ada-speaker@example.com"
                          :status "Invited"
                          :actor "organizer@example.com"})
        handler (server/create-app)
        cookie (session-cookie handler "ada-speaker@example.com")
        speaker (partial as cookie)
        portal-body #(-> (handler (speaker (mock/request :get "/portal"))) :body)]
    (testing "the empty profile names every outstanding requirement"
      (is (str/includes? (portal-body)
                         "Profile incomplete — missing bio and headshot")))

    (testing "the status advances as the speaker supplies profile fields"
      (is (= 303
             (:status
               (handler
                 (speaker
                   (mock/request :post "/api/profile"
                                 {"bio" "Ada builds dependable systems."}))))))
      (is (str/includes? (portal-body)
                         "Profile incomplete — missing headshot")))

    (testing "the portal confirms when the organizer requirements are met"
      (is (= 303
             (:status
               (handler
                 (speaker
                   (mock/request
                     :post "/api/profile"
                     {"headshot-url" "https://images.example.test/ada.png"}))))))
      (let [body (portal-body)]
        (is (str/includes? body "Profile complete"))
        (is (not (str/includes? body "Profile incomplete")))))))
