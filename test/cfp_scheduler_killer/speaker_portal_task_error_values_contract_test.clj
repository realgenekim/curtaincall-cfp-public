(ns cfp-scheduler-killer.speaker-portal-task-error-values-contract-test
  (:require
   [cfp-scheduler-killer.auth :as auth]
   [cfp-scheduler-killer.events :as events]
   [cfp-scheduler-killer.inform :as inform]
   [cfp-scheduler-killer.portal :as portal]
   [cfp-scheduler-killer.reviews :as reviews]
   [cfp-scheduler-killer.server :as server]
   [cfp-scheduler-killer.speaker-tasks :as speaker-tasks]
   [cfp-scheduler-killer.store :as store]
   [cfp-scheduler-killer.submissions :as submissions]
   [cfp-scheduler-killer.test-helpers :refer [with-temp-store]]
   [clojure.string :as str]
   [clojure.test :refer [deftest is use-fixtures]]
   [ring.mock.request :as mock]))

(use-fixtures :each with-temp-store)

(defn- login-cookie [handler email]
  (let [token (auth/issue-token! email)
        response (handler (mock/request :get (str "/auth/" token)))]
    (first (str/split (first (get-in response [:headers "Set-Cookie"])) #";"))))

(deftest rejected-url-task-edit-rerenders-the-speakers-attempt-test
  (let [speaker-email "task-correction@example.com"
        event (events/create-event!
                {:name "Task Correction Summit"
                 :slug "task-correction"
                 :tz "America/New_York"
                 :support-email "support@example.com"
                 :location "Charlotte, NC"
                 :starts-on (java.time.LocalDate/of 2026 10 14)
                 :ends-on (java.time.LocalDate/of 2026 10 15)}
                "test")
        fields (:fields (events/form-for-event (:id event)))
        params {:answer-talk-title "Correcting an onboarding URL"
                :answer-abstract "A rejected edit must remain visible for correction."
                :answer-session-format "Experience Report"
                :answer-track "Developer Practices"
                :answer-org-size ">10,000"
                :answer-industry "Insurance"
                :answer-ai-transformation-history "2026."
                :answer-measurable-outcomes "A truthful correction form."
                :speaker-name "Task Speaker"
                :speaker-email speaker-email
                :speaker-title "VP"
                :speaker-org "Correction Labs"
                :speaker-bio "A URL-task fixture."}
        submission (submissions/create-submission!
                     event
                     (submissions/parse-answers fields params)
                     (submissions/parse-speaker params)
                     "form" "test")
        _ (reviews/set-status! (:id submission) "Accepted" "organizer@example.com")
        _ (inform/inform! event (store/submission-by-id (:id submission))
                          "organizer@example.com")
        _ (speaker-tasks/install! (:id submission)
                                  {:key "recording-url"
                                   :label "Recording URL"
                                   :task-type "url"
                                   :required? true}
                                  "organizer@example.com")
        stored-url "https://video.example.com/original"
        attempted-url "javascript:alert(1)"
        _ (portal/complete-task! (:id submission) "recording-url" stored-url speaker-email)
        handler (server/create-app)
        cookie (login-cookie handler speaker-email)
        response (handler
                   (mock/header
                     (mock/request :post
                                   (str "/api/submissions/" (:id submission) "/task")
                                   {:key "recording-url" :value attempted-url})
                     "cookie" cookie))
        body (:body response)
        task (first (filter #(= "recording-url" (:key %))
                            (portal/tasks-for (:id submission))))]
    (is (= 422 (:status response)))
    (is (str/includes? body "Enter a complete http:// or https:// URL."))
    (is (str/includes? body (str "value=\"" attempted-url "\""))
        "the rejected value remains in the form so the speaker can correct it")
    (is (not (str/includes? body (str "value=\"" stored-url "\"")))
        "the response must not silently replace the attempt with stale durable data")
    (is (= stored-url (:value task))
        "a rejected edit must not alter the last valid durable value")))
