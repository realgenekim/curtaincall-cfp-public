(ns cfp-scheduler-killer.speaker-portal-blank-profile-edit-contract-test
  (:require
   [cfp-scheduler-killer.auth :as auth]
   [cfp-scheduler-killer.events :as events]
   [cfp-scheduler-killer.portal :as portal]
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

(deftest rejected-profile-edit-preserves-an-explicitly-cleared-field
  (let [event (events/create-event!
                {:name "Profile Draft Summit"
                 :slug "profile-draft"
                 :tz "UTC"}
                "organizer@example.com")
        added (speakers/add!
                (:id event)
                {:name "Ada Draft"
                 :email "ada-draft@example.com"
                 :status "Invited"
                 :actor "organizer@example.com"})
        person-id (:person-id added)
        original-bio "This biography should stay stored until a valid save."
        original-website "https://ada.example.com"
        _ (portal/update-profile!
            person-id
            {:bio original-bio :website-url original-website}
            "ada-draft@example.com")
        handler (server/create-app)
        cookie (session-cookie handler "ada-draft@example.com")
        response (handler
                   (as cookie
                       (mock/request :post "/api/profile"
                                     {"bio" ""
                                      "website-url" "not-a-url"})))
        body (:body response)
        stored-profile (:profile (store/person-by-id person-id))]
    (testing "the rejected form shows exactly what the speaker submitted"
      (is (= 422 (:status response)))
      (is (str/includes? body "Enter a full URL starting with http:// or https://"))
      (is (str/includes? body "name=\"website-url\" type=\"url\" value=\"not-a-url\""))
      (is (str/includes? body "<textarea name=\"bio\" rows=\"5\"></textarea>"))
      (is (not (str/includes? body original-bio))))

    (testing "validation remains atomic until the speaker submits a valid edit"
      (is (= original-bio (:bio stored-profile)))
      (is (= original-website (:website-url stored-profile))))))
