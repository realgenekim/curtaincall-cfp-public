(ns cfp-scheduler-killer.cfp-prefill-test
  (:require
   [cfp-scheduler-killer.auth :as auth]
   [cfp-scheduler-killer.events :as events]
   [cfp-scheduler-killer.portal :as portal]
   [cfp-scheduler-killer.server :as server]
   [cfp-scheduler-killer.test-helpers :refer [with-temp-store]]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [ring.mock.request :as mock]))

(use-fixtures :each with-temp-store)

(defn- login-cookie! [handler email]
  (let [token (auth/issue-token! email)
        response (handler (mock/request :get (str "/auth/" token)))]
    (first (str/split (first (get-in response [:headers "Set-Cookie"])) #";"))))

(defn- request-as [handler cookie request]
  (handler (mock/header request "cookie" cookie)))

(defn- create-event! [handler cookie slug]
  (request-as
    handler cookie
    (mock/request :post "/api/events/create"
                  {"name" "Profile Prefill Summit"
                   "slug" slug
                   "tz" "America/New_York"
                   "cfp-opens-at" "2020-01-01T00:00"
                   "cfp-closes-at" "2099-01-01T00:00"
                   "presenter-visibility-mode" "visible"
                   "location" "Charlotte, NC"}))
  (events/event-by-slug slug))

(def ^:private valid-submission
  {"answer-talk-title" "A speaker, not an organizer"
   "answer-abstract" "Keep each person's profile attached to that person."
   "answer-audience-level" "Intermediate"
   "answer-session-format" "Talk"
   "answer-session-length" "45 minutes"
   "speaker-name" "Organizer Who Also Speaks"
   "speaker-email" "organizer@example.com"
   "speaker-title" "VP Engineering"
   "speaker-org" "BigCo"
   "speaker-bio" "Runs the conference and occasionally speaks."})

(deftest cfp-profile-prefill-requires-an-event-speaker-relationship-test
  (let [handler (server/create-app)
        cookie (login-cookie! handler "organizer@example.com")
        slug "profile-prefill-summit"
        _event (create-event! handler cookie slug)
        page #(request-as handler cookie (mock/request :get (str "/cfp/" slug)))
        sentinel "https://images.example.com/current-person.jpg"]
    (with-redefs [portal/prefill-from-profile
                  (constantly {:speaker-email "organizer@example.com"
                               :speaker-headshot-url sentinel})]
      (testing "an authenticated organizer who is not a speaker does not leak into the CFP"
        (let [body (:body (page))]
          (is (not (str/includes? body sentinel)))
          (is (not (str/includes? body
                                  "name=\"speaker-email\" value=\"organizer@example.com\"")))))

      (testing "the same person is prefilled after becoming a speaker for this event"
        (is (= 303
               (:status
                 (request-as
                   handler cookie
                   (mock/request :post (str "/api/cfp/" slug "/submit")
                                 valid-submission)))))
        (is (str/includes? (:body (page)) sentinel))))))
