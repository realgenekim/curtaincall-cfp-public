(ns cfp-scheduler-killer.review-work-routes-test
  (:require
   [cfp-scheduler-killer.auth :as auth]
   [cfp-scheduler-killer.committees :as committees]
   [cfp-scheduler-killer.events :as events]
   [cfp-scheduler-killer.review-assignments :as assignments]
   [cfp-scheduler-killer.reviews :as reviews]
   [cfp-scheduler-killer.server :as server]
   [cfp-scheduler-killer.submissions :as submissions]
   [cfp-scheduler-killer.test-helpers :refer [with-temp-store]]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [ring.mock.request :as mock]))

(use-fixtures :each with-temp-store)

(defn- signed-in-app [email]
  (let [handler (server/create-app)
        token (auth/issue-token! email)
        response (handler (mock/request :get (str "/auth/" token)))
        cookie (first (str/split (first (get-in response [:headers "Set-Cookie"])) #";"))]
    (fn [request]
      (handler (mock/header request "cookie" cookie)))))

(defn- organizer-app []
  (signed-in-app "organizer@example.com"))

(defn- make-event! [handler slug]
  (handler
    (mock/request :post "/api/events/create"
                  {"name" "Review Work Summit"
                   "slug" slug
                   "starts-on" "2026-10-14"
                   "ends-on" "2026-10-15"
                   "presenter-visibility-mode" "visible"}))
  (events/event-by-slug slug))

(defn- submit! [event]
  (let [fields (:fields (events/form-for-event (:id event)))
        params {:answer-talk-title "A conflicted proposal"
                :answer-abstract "Abstract."
                :answer-session-format "Experience Report"
                :answer-org-size ">10,000"
                :answer-industry "Insurance"
                :answer-ai-transformation-history "2023."
                :answer-measurable-outcomes "Numbers."
                :answer-notes-to-committee "Private."
                :speaker-name "Speaker Person"
                :speaker-email "review-work-speaker@example.com"
                :speaker-title "VP" :speaker-org "BigCo" :speaker-bio "Bio."
                :speaker-2-role "Co-speaker"
                :speaker-2-name "Partner Person"
                :speaker-2-email "review-work-partner@example.com"
                :speaker-2-title "CTO"
                :speaker-2-org "PartnerCo"
                :speaker-2-bio "Partner bio."}]
    (submissions/create-submission!
      event (submissions/parse-answers fields params)
      (submissions/parse-speakers params) "form" "kaocha")))

(deftest organizer-progress-dashboard-shows-live-assigned-completion-test
  (let [handler (organizer-app)
        slug (str "review-progress-" (events/random-suffix 8))
        event (make-event! handler slug)
        committee-id (:id (first (events/committees-for-event (:id event))))
        reviewer-membership (committees/add-member!
                              committee-id
                              {:name "Sam Reviewer"
                               :email "sam-progress@example.com"
                               :role "reviewer"}
                              "kaocha")
        reviewer-id (:person-id reviewer-membership)
        assigned-submissions [(submit! event) (submit! event)]
        progress-path (str "/events/" slug "/reviewer-progress")
        progress-body (fn [] (:body (handler (mock/request :get progress-path))))]
    (doseq [submission assigned-submissions]
      (assignments/assign! (:id submission) reviewer-id "organizer@example.com"))
    (testing "the organizer sees exact outstanding progress before reviews"
      (let [body (progress-body)]
        (is (str/includes? body "Review coverage: 0 of 2 submissions have at least 2 reviews (0%)"))
        (is (str/includes? body "0 signed reviews recorded"))
        (is (str/includes? body "2 outstanding assignments"))))
    (doseq [submission assigned-submissions]
      (reviews/set-rating! (:id submission) reviewer-id 4.0 "sam-progress@example.com"))
    (testing "the same dashboard reflects both completed reviews"
      (let [body (progress-body)]
        (is (str/includes? body "2 signed reviews recorded"))
        (is (str/includes? body "0 outstanding assignments"))
        (is (str/includes?
              body
              "Sam Reviewer</strong> — All tracks · 0/2 covered · 2 reviews · 2 assigned · 0 remaining"))))))
