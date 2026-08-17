(ns cfp-scheduler-killer.speaker-task-canonical-identity-contract-test
  (:require
   [cfp-scheduler-killer.auth :as auth]
   [cfp-scheduler-killer.events :as events]
   [cfp-scheduler-killer.server :as server]
   [cfp-scheduler-killer.speaker-tasks :as speaker-tasks]
   [cfp-scheduler-killer.speakers :as speakers]
   [cfp-scheduler-killer.store :as store]
   [cfp-scheduler-killer.submissions :as submissions]
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

(defn- create-submission! [event]
  (let [fields (:fields (events/form-for-event (:id event)))
        params {:answer-talk-title "Identity after submission"
                :answer-abstract "A practical experience report."
                :answer-session-format "Experience Report"
                :answer-track "Developer Practices"
                :answer-org-size ">10,000"
                :answer-industry "Technology"
                :answer-ai-transformation-history "2025."
                :answer-measurable-outcomes "Measured outcomes."
                :speaker-name "Katherine Snapshot"
                :speaker-email "katherine-tasks@example.com"
                :speaker-title "VP Engineering"
                :speaker-org "Identity Labs"
                :speaker-bio "An experienced speaker."}]
    (submissions/create-submission!
      event
      (submissions/parse-answers fields params)
      (submissions/parse-speaker params)
      "form"
      "kaocha")))

(deftest onboarding-completion-follows-the-canonical-speaker-name
  (let [handler (server/create-app)
        organizer-cookie (session-cookie handler "organizer@example.com")
        organizer (partial as organizer-cookie)
        _ (handler
            (organizer
              (mock/request :post "/api/events/create"
                            {"name" "Task Identity Summit"
                             "slug" "task-identity"
                             "starts-on" "2026-10-14"
                             "ends-on" "2026-10-15"
                             "presenter-visibility-mode" "visible"})))
        event (events/event-by-slug "task-identity")
        submission (create-submission! event)
        person (store/person-by-email "katherine-tasks@example.com")
        _ (speaker-tasks/install!
            (:id submission)
            {:key "confirm-bio"
             :label "Confirm biography"
             :task-type "check"
             :required? true
             :due-offset-days -30}
            "organizer@example.com")
        path "/events/task-identity/deliverables"]
    (is (str/includes? (:body (handler (organizer (mock/request :get path))))
                       "Katherine Snapshot"))

    (speakers/rename! (:id event) (:id person)
                      "Katherine Canonical" "organizer@example.com")

    (let [submission-after (store/submission-by-id (:id submission))
          body (:body (handler (organizer (mock/request :get path))))]
      (testing "the submission remains the historical snapshot"
        (is (= "Katherine Snapshot"
               (get-in submission-after [:speakers 0 :name]))))
      (testing "the live completion row follows the canonical person identity"
        (is (str/includes? body "Katherine Canonical"))
        (is (not (str/includes? body "Katherine Snapshot")))))))
