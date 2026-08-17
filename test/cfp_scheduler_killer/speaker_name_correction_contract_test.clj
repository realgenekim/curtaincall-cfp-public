(ns cfp-scheduler-killer.speaker-name-correction-contract-test
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

(deftest organizer-can-correct-the-canonical-name-the-portal-tells-speakers-to-request
  (let [handler (server/create-app)
        organizer-cookie (session-cookie handler "organizer@example.com")
        organizer (partial as organizer-cookie)
        _ (handler
            (organizer
              (mock/request :post "/api/events/create"
                            {"name" "Name Correction Summit"
                             "slug" "name-correction"
                             "starts-on" "2026-10-14"
                             "ends-on" "2026-10-15"
                             "presenter-visibility-mode" "visible"})))
        event (events/event-by-slug "name-correction")
        _ (handler
            (organizer
              (mock/request :post "/api/events/name-correction/speakers"
                            {"name" "Katherine Typo"
                             "email" "katherine-name@example.com"
                             "status" "Invited"})))
        person-id (:person-id (first (speakers/roster-for-event (:id event))))
        edit-path (str "/api/events/name-correction/speakers/" person-id)]
    (testing "the explicit shared-profile form owns the canonical name"
      (let [body (:body
                   (handler
                     (organizer
                       (mock/request
                         :get (str "/events/name-correction/speakers/" person-id)))))]
        (is (str/includes? body "Shared speaker profile"))
        (is (str/includes? body "name=\"name\""))
        (is (str/includes? body "value=\"Katherine Typo\""))))

    (testing "a blank correction is rejected before event details can append"
      (let [before (count (:log (store/snapshot)))
            response
            (handler
              (organizer
                (mock/request :post edit-path
                              {"profile-edit" "true"
                               "name" "   "
                               "title" "Must not partially save"
                               "bio" ""
                               "headshot-url" ""
                               "linkedin-url" ""})))
            speaker (first (speakers/roster-for-event (:id event)))]
        (is (= 422 (:status response)))
        (is (str/includes? (:body response) "A speaker name is required."))
        (is (= before (count (:log (store/snapshot)))))
        (is (nil? (:title speaker)))
        (is (= "Katherine Typo" (:name speaker)))))

    (testing "one save corrects the identity everywhere"
      (let [response
            (handler
              (organizer
                (mock/request :post edit-path
                              {"profile-edit" "true"
                               "name" "  Katherine   Keynote  "
                               "title" "Distinguished Engineer"
                               "bio" "Builds systems for humans."
                               "headshot-url" ""
                               "linkedin-url" ""})))
            person (store/person-by-id person-id)
            roster-body (:body
                          (handler
                            (organizer
                              (mock/request :get "/events/name-correction/speakers"))))]
        (is (= 303 (:status response)))
        (is (= "Katherine Keynote" (:name person)))
        (is (str/includes? roster-body "Katherine Keynote"))
        (is (not (str/includes? roster-body "Katherine Typo")))
        (is (some #(= "event.program-speaker-updated" (:type %))
                  (:log (store/snapshot))))))

    (testing "the speaker sees the corrected canonical identity"
      (let [speaker-cookie (session-cookie handler "katherine-name@example.com")
            body (:body (handler (as speaker-cookie (mock/request :get "/portal"))))]
        (is (str/includes? body "Katherine Keynote · katherine-name@example.com"))
        (is (str/includes? body "value=\"Katherine Keynote\""))))))
