(ns cfp-scheduler-killer.speaker-portal-canonical-identity-contract-test
  (:require
   [cfp-scheduler-killer.auth :as auth]
   [cfp-scheduler-killer.events :as events]
   [cfp-scheduler-killer.server :as server]
   [cfp-scheduler-killer.speakers :as speakers]
   [cfp-scheduler-killer.store :as store]
   [cfp-scheduler-killer.submissions :as submissions]
   [cfp-scheduler-killer.test-helpers :refer [with-temp-store]]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [ring.mock.request :as mock]))

(use-fixtures :each with-temp-store)

(defn- signed-in [handler email]
  (let [token (auth/issue-token! email)
        response (handler (mock/request :get (str "/auth/" token)))
        cookie (first (str/split (first (get-in response [:headers "Set-Cookie"])) #";"))]
    (fn [request]
      (handler (mock/header request "cookie" cookie)))))

(defn- create-submission! [event]
  (let [fields (:fields (events/form-for-event (:id event)))
        params {:answer-talk-title "Names after submission"
                :answer-abstract "A practical experience report."
                :answer-session-format "Experience Report"
                :answer-track "Developer Practices"
                :answer-org-size ">10,000"
                :answer-industry "Technology"
                :answer-ai-transformation-history "2025."
                :answer-measurable-outcomes "Measured outcomes."
                :speaker-name "Primary Speaker"
                :speaker-email "primary-portal@example.com"
                :speaker-title "VP Engineering"
                :speaker-org "Portal Labs"
                :speaker-bio "An experienced primary speaker."}
        primary (assoc (submissions/parse-speaker params) :role "Primary speaker")
        partner {:name "Partner Snapshot"
                 :email "partner-portal@example.com"
                 :title "VP Product"
                 :org "Portal Labs"
                 :bio "An experienced partner speaker."
                 :role "Co-speaker"}]
    (submissions/create-submission!
      event
      (submissions/parse-answers fields params)
      [primary partner]
      "form"
      "kaocha")))

(deftest speaker-portal-follows-canonical-participant-names
  (let [handler (server/create-app)
        organizer (signed-in handler "organizer@example.com")
        _ (organizer
            (mock/request :post "/api/events/create"
                          {"name" "Portal Identity Summit"
                           "slug" "portal-identity"
                           "starts-on" "2026-10-14"
                           "ends-on" "2026-10-15"
                           "presenter-visibility-mode" "visible"}))
        event (events/event-by-slug "portal-identity")
        submission (create-submission! event)
        partner (store/person-by-email "partner-portal@example.com")
        primary-portal (signed-in handler "primary-portal@example.com")]
    (is (str/includes? (:body (primary-portal (mock/request :get "/portal")))
                       "Partner Snapshot — Co-speaker"))

    (speakers/rename! (:id event) (:id partner)
                      "Partner Canonical" "organizer@example.com")

    (let [submission-after (store/submission-by-id (:id submission))
          body (:body (primary-portal (mock/request :get "/portal")))]
      (testing "the submission remains the historical snapshot"
        (is (= "Partner Snapshot"
               (get-in submission-after [:speakers 1 :name]))))
      (testing "the live co-speaker list follows the canonical identity"
        (is (str/includes? body "Partner Canonical — Co-speaker"))
        (is (not (str/includes? body "Partner Snapshot")))))))
