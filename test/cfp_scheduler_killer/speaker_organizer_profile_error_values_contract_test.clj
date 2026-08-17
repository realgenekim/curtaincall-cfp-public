(ns cfp-scheduler-killer.speaker-organizer-profile-error-values-contract-test
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

(deftest rejected-organizer-profile-edit-preserves-the-attempted-row
  (let [handler (server/create-app)
        cookie (session-cookie handler "organizer@example.com")
        organizer (partial as cookie)
        _ (handler
            (organizer
              (mock/request :post "/api/events/create"
                            {"name" "Organizer Profile Error Summit"
                             "slug" "organizer-profile-error"
                             "starts-on" "2026-10-14"
                             "ends-on" "2026-10-15"
                             "presenter-visibility-mode" "visible"})))
        event (events/event-by-slug "organizer-profile-error")
        added (speakers/add!
                (:id event)
                {:name "Ada Stored"
                 :email "ada-profile-error@example.com"
                 :status "Invited"
                 :actor "organizer@example.com"})
        person-id (:person-id added)
        _ (portal/update-profile!
            person-id
            {:tagline "Stored tagline"
             :org "Stored Shared Org"
             :bio "Stored biography"
             :linkedin-url "https://www.linkedin.com/in/ada-stored"}
            "organizer@example.com")
        _ (speakers/edit!
            (:id event) person-id
            {:title "Stored event title"
             :organization "Stored Event Org"
             :notes "Stored notes"}
            "organizer@example.com")
        edit-path (str "/api/events/organizer-profile-error/speakers/" person-id)
        response (handler
                   (organizer
                     (mock/request :post edit-path
                                   {"profile-edit" "true"
                                    "name" "Ada Attempted"
                                    "tagline" "Attempted tagline"
                                    "org" "Attempted Shared Org"
                                    "bio" ""
                                    "headshot-url" "https://images.example.com/ada-attempted.png"
                                    "linkedin-url" "not-a-url"
                                    "website-url" "https://ada-attempted.example.com"
                                    "title" "Attempted event title"
                                    "organization" "Attempted Event Org"
                                    "notes" "Attempted notes"})))
        body (:body response)
        person (:profile (store/person-by-id person-id))
        roster-speaker (first (speakers/roster-for-event (:id event)))]
    (testing "the rejected row shows every value the organizer needs to correct"
      (is (= 422 (:status response)))
      (is (str/includes? body "Enter a full URL starting with http:// or https://"))
      (is (str/includes? body "name=\"name\" required=\"required\" value=\"Ada Attempted\""))
      (is (str/includes? body "name=\"tagline\" value=\"Attempted tagline\""))
      (is (str/includes? body "name=\"org\" value=\"Attempted Shared Org\""))
      (is (str/includes? body "<textarea name=\"bio\" rows=\"4\"></textarea>"))
      (is (not (str/includes? body "Stored biography")))
      (is (str/includes? body "name=\"linkedin-url\" type=\"url\" value=\"not-a-url\""))
      (is (str/includes? body "name=\"title\" value=\"Attempted event title\""))
      (is (str/includes? body "name=\"organization\" value=\"Attempted Event Org\""))
      (is (str/includes? body ">Attempted notes</textarea>")))

    (testing "validation remains atomic until the organizer submits a valid row"
      (is (= "Ada Stored" (:name (store/person-by-id person-id))))
      (is (= "Stored tagline" (:tagline person)))
      (is (= "Stored Shared Org" (:org person)))
      (is (= "Stored biography" (:bio person)))
      (is (= "Stored event title" (:title roster-speaker)))
      (is (= "Stored Event Org" (:event-organization roster-speaker)))
      (is (= "Stored notes" (:notes roster-speaker))))))
