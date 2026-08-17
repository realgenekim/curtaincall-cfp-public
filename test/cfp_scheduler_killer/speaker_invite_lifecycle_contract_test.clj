(ns cfp-scheduler-killer.speaker-invite-lifecycle-contract-test
  (:require
   [cfp-scheduler-killer.auth :as auth]
   [cfp-scheduler-killer.committees :as committees]
   [cfp-scheduler-killer.events :as events]
   [cfp-scheduler-killer.server :as server]
   [cfp-scheduler-killer.speaker-custom-fields :as speaker-custom-fields]
   [cfp-scheduler-killer.speakers :as speakers]
   [cfp-scheduler-killer.store :as store]
   [cfp-scheduler-killer.test-helpers :refer [with-temp-store]]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [ring.mock.request :as mock]))

(use-fixtures :each with-temp-store)

(defn- as [cookie request]
  (mock/header request "cookie" cookie))

(defn- session-cookie [response]
  (some-> (get-in response [:headers "Set-Cookie"])
          first
          (str/split #";")
          first))

(deftest invited-speaker-without-a-submission-has-a-usable-event-aware-portal
  (let [event (events/create-event!
                {:name "Invited Speaker Summit"
                 :slug "invited-speaker"
                 :tz "America/New_York"}
                "kaocha")
        committee-id (:id (first (events/committees-for-event (:id event))))
        _ (committees/add-member!
            committee-id
            {:name "Olivia Organizer"
             :email "organizer@example.com"
             :role "chair"}
            "kaocha")
        added (speakers/add!
                (:id event)
                {:name "Katherine Keynote"
                 :email "katherine@example.com"
                 :status "Invited"
                 :actor "organizer@example.com"})
        person (store/person-by-email "katherine@example.com")
        _ (speaker-custom-fields/define!
            (:id event)
            {:label "Pronouns" :type "text" :required true}
            "organizer@example.com")
        handler (server/create-app)
        issued-token (auth/issue-token! "katherine@example.com")
        ;; Keep driving the SUT on the red baseline even when the production
        ;; eligibility check refuses the organizer's explicitly invited person.
        token (or issued-token
                  (auth/mint-token! "katherine@example.com" person))
        login-response (handler (mock/request :get (str "/auth/" token)))
        cookie (session-cookie login-response)]
    (testing "the organizer's portal invite can actually be redeemed"
      (is (= (:person-id added) (:id person)))
      (is (some? issued-token))
      (is (= 303 (:status login-response)))
      (is (= "/portal" (get-in login-response [:headers "Location"]))))

    (testing "the empty-talk portal still explains the event invitation"
      (let [body (:body (handler (as cookie (mock/request :get "/portal"))))]
        (is (str/includes? body "Invited to speak at Invited Speaker Summit"))
        (is (str/includes? body "No session is attached yet"))
        (is (str/includes? body "Event details — Invited Speaker Summit"))
        (is (str/includes? body "Pronouns"))))

    (testing "profile and event-specific edits round-trip to the organizer"
      (is (= 303
             (:status
               (handler
                 (as cookie
                     (mock/request :post "/api/profile"
                                   {"bio" "Katherine builds humane systems."
                                    "org" "Lifecycle Labs"}))))))
      (is (= 303
             (:status
               (handler
                 (as cookie
                     (mock/request
                       :post
                       "/api/events/invited-speaker/speaker-custom-values"
                       {"custom-pronouns" "she/her"}))))))
      (let [speaker (first (speakers/roster-for-event (:id event)))
            portal-body (:body (handler (as cookie (mock/request :get "/portal"))))]
        (is (= "Katherine builds humane systems." (:bio speaker)))
        (is (= "Lifecycle Labs" (:organization speaker)))
        (is (= "she/her" (get-in speaker [:custom-values "pronouns"])))
        (is (str/includes? portal-body "value=\"she/her\""))))))
