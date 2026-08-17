(ns cfp-scheduler-killer.portal-task-actor-variants-contract-test
  (:require
   [cfp-scheduler-killer.auth :as auth]
   [cfp-scheduler-killer.events :as events]
   [cfp-scheduler-killer.inform :as inform]
   [cfp-scheduler-killer.reviews :as reviews]
   [cfp-scheduler-killer.server :as server]
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

(deftest portal-url-task-completion-records-authenticated-speaker-test
  (let [speaker-email "url-speaker@example.com"
        event (events/create-event!
               {:name "Task Actor Variant Summit"
                :slug "task-actor-variant"
                :tz "America/New_York"
                :support-email "support@example.com"
                :location "Charlotte, NC"
                :starts-on (java.time.LocalDate/of 2026 10 14)
                :ends-on (java.time.LocalDate/of 2026 10 15)}
               "test")
        fields (:fields (events/form-for-event (:id event)))
        params {:answer-talk-title "URL task"
                :answer-abstract "Actor identity must survive URL completion."
                :answer-session-format "Experience Report"
                :answer-track "Developer Practices"
                :answer-org-size ">10,000"
                :answer-industry "Insurance"
                :answer-ai-transformation-history "2026."
                :answer-measurable-outcomes "Named actor."
                :speaker-name "URL Speaker"
                :speaker-email speaker-email
                :speaker-title "VP"
                :speaker-org "Actor Labs"
                :speaker-bio "A URL-task fixture."}
        submission (submissions/create-submission!
                     event
                     (submissions/parse-answers fields params)
                     (submissions/parse-speaker params)
                     "form" "actor-variant")
        _ (reviews/set-status! (:id submission) "Accepted" "organizer@example.com")
        _ (inform/inform! event (store/submission-by-id (:id submission))
                          "organizer@example.com")
        handler (server/create-app)
        cookie (login-cookie handler speaker-email)
        response (handler
                  (mock/header
                   (mock/request :post
                                 (str "/api/submissions/" (:id submission) "/task")
                                 {:key "slides-url"
                                  :value "https://slides.example.com/actor-variant"})
                   "cookie" cookie))
        completion (last (filter #(and (= "task.completed" (:type %))
                                       (= (:id submission)
                                          (get-in % [:payload :submission-id])))
                                 (store/read-events)))]
    (is (= 303 (:status response)))
    (is (= speaker-email (:actor completion)))
    (is (= "slides-url" (get-in completion [:payload :key])))
    (is (= "https://slides.example.com/actor-variant"
           (get-in completion [:payload :value])))))
