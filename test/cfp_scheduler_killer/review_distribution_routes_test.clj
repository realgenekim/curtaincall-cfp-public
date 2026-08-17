(ns cfp-scheduler-killer.review-distribution-routes-test
  (:require
   [cfp-scheduler-killer.auth :as auth]
   [cfp-scheduler-killer.committees :as committees]
   [cfp-scheduler-killer.events :as events]
   [cfp-scheduler-killer.review-assignments :as assignments]
   [cfp-scheduler-killer.server :as server]
   [cfp-scheduler-killer.store :as store]
   [cfp-scheduler-killer.submissions :as submissions]
   [cfp-scheduler-killer.test-helpers :refer [with-temp-store]]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [ring.mock.request :as mock])
  (:import
   (org.jsoup Jsoup)))

(use-fixtures :each with-temp-store)

(defn- signed-in-app [email]
  (let [handler (server/create-app)
        token (auth/issue-token! email)
        response (handler (mock/request :get (str "/auth/" token)))
        cookie (first (str/split (first (get-in response [:headers "Set-Cookie"])) #";"))]
    (fn [request]
      (handler (mock/header request "cookie" cookie)))))

(defn- make-event! [_handler slug]
  (let [event (events/create-eais-event!
                {:name "Distribution Summit" :slug slug :tz "UTC"}
                "kaocha")
        committee-id (:id (first (events/committees-for-event (:id event))))]
    (committees/add-member! committee-id
                            {:name "Organizer" :email "organizer@example.com"
                             :role "chair"}
                            "kaocha")
    event))

(defn- submit! [event title track]
  (let [fields (:fields (events/form-for-event (:id event)))
        params {:answer-talk-title title
                :answer-abstract "Abstract."
                :answer-session-format "Experience Report"
                :answer-track track
                :answer-org-size ">10,000"
                :answer-industry "Insurance"
                :answer-ai-transformation-history "2023."
                :answer-measurable-outcomes "Numbers."
                :speaker-name (str title " Speaker")
                :speaker-email (str (random-uuid) "@example.com")
                :speaker-title "VP"
                :speaker-org "BigCo"
                :speaker-bio "Bio."}]
    (submissions/create-submission!
      event
      (submissions/parse-answers fields params)
      (submissions/parse-speaker params)
      "form"
      "kaocha")))

(defn- assignment-facts [event-id]
  (->> (store/log-for-event event-id)
       (filter #(= "reviewer.assigned" (:type %)))
       vec))

(defn- confirmation-params [body]
  (let [form (.selectFirst (Jsoup/parse body)
                           "form[action$='/reviewers/distribute']")]
    (reduce (fn [params input]
              (let [field (.attr input "name")
                    value (.attr input "value")]
                (if (= "reviewer-id" field)
                  (update params field (fnil conj []) value)
                  (assoc params field value))))
            {}
            (.select form "input[name]"))))

(deftest preview-confirm-cap-track-tenancy-route-test
  (let [chair-app (signed-in-app "organizer@example.com")
        slug (str "distribution-" (events/random-suffix 8))
        event (make-event! chair-app slug)
        committee-id (:id (first (events/committees-for-event (:id event))))
        reviewer-a (committees/add-member!
                     committee-id
                     {:name "Reviewer A" :email "a@example.com" :role "reviewer"}
                     "kaocha")
        reviewer-b (committees/add-member!
                     committee-id
                     {:name "Reviewer B" :email "b@example.com" :role "reviewer"}
                     "kaocha")
        talk-a (submit! event "AI One" "AI Models")
        talk-b (submit! event "AI Two" "AI Models")
        talk-other (submit! event "Architecture One" "Architecture")
        params {"track" "AI Models"
                "cap" "1"
                "reviewer-id" [(:person-id reviewer-a) (:person-id reviewer-b)]}
        preview-path (str "/api/events/" slug "/reviewers/distribute/preview")
        confirm-path (str "/api/events/" slug "/reviewers/distribute")
        before-preview (assignment-facts (:id event))
        preview-response (chair-app (mock/request :post preview-path params))
        pinned-params (confirmation-params (:body preview-response))]
    (testing "chair submission detail exposes the actual assignment command"
      (let [path (str "/events/" slug "/submissions/" (:id talk-other))
            detail (chair-app (mock/request :get path))
            body (:body detail)]
        (is (= 200 (:status detail)))
        (is (str/includes? body "Assign reviewers"))
        (is (str/includes? body "Assign · Reviewer A"))
        (is (str/includes? body
                           (str "/api/submissions/" (:id talk-other)
                                "/reviewers/" (:person-id reviewer-a) "/assign")))))
    (testing "preview appends nothing and shows the track-filtered capped plan"
      (is (= 200 (:status preview-response)))
      (is (= before-preview (assignment-facts (:id event))))
      (is (str/includes? (:body preview-response) "Confirm bulk distribution"))
      (is (str/includes? (:body preview-response) "role=\"status\""))
      (is (str/includes? (:body preview-response) "Preview ready"))
      (is (str/includes? (:body preview-response)
                         "2 exact assignments across 2 reviewers"))
      (is (str/includes? (:body preview-response)
                         "Track: AI Models · Per-reviewer cap: 1"))
      (is (str/includes? (:body preview-response) "Nothing recorded yet"))
      (is (str/includes? (:body preview-response) "AI One"))
      (is (str/includes? (:body preview-response) "AI Two"))
      (is (not (str/includes? (:body preview-response) "Architecture One")))
      (is (= "AI Models" (get pinned-params "track")))
      (is (= "1" (get pinned-params "cap")))
      (is (< 2 (count (get pinned-params "reviewer-id")))
          "confirmation carries an opaque receipt in addition to reviewers"))
    (testing "confirmation rejects cap, track, and event tenancy changes"
      (let [other-slug (str "distribution-other-" (events/random-suffix 8))
            other-event (make-event! chair-app other-slug)
            other-confirm-path (str "/api/events/" other-slug "/reviewers/distribute")]
        (is (= 422 (:status (chair-app
                              (mock/request :post confirm-path
                                            (assoc pinned-params "cap" "2"))))))
        (is (= 422 (:status (chair-app
                              (mock/request :post confirm-path
                                            (assoc pinned-params "track" "Architecture"))))))
        (is (= 422 (:status (chair-app
                              (mock/request :post other-confirm-path pinned-params)))))
        (is (empty? (assignment-facts (:id event))))
        (is (empty? (assignment-facts (:id other-event))))))
    (testing "confirmation records exactly the previewed facts and retry is idempotent"
      (let [response (chair-app (mock/request :post confirm-path pinned-params))]
        (is (= 200 (:status response)))
        (is (str/includes? (:body response) "Bulk reviewer distribution complete"))
        (is (str/includes? (:body response) "role=\"status\""))
        (is (str/includes? (:body response) "Distribution confirmed"))
        (is (str/includes? (:body response)
                           "2 assignments recorded across 2 reviewers"))
        (is (str/includes? (:body response)
                           "Track: AI Models · Per-reviewer cap: 1"))
        (is (= #{(:id talk-a) (:id talk-b)}
               (set (map #(get-in % [:payload :submission-id])
                         (assignment-facts (:id event))))))
        (is (= #{(:person-id reviewer-a) (:person-id reviewer-b)}
               (set (map #(get-in % [:payload :person-id])
                         (assignment-facts (:id event))))))
        (is (every? #(= (:id event) (:event-id %))
                    (assignment-facts (:id event))))
        (is (empty? (assignments/assignments-for-submission (:id talk-other))))
        (is (str/includes? (:body response) "Current assignment counts"))
        (let [after-confirm (assignment-facts (:id event))
              _ (submit! event "AI Three" "AI Models")
              retry (chair-app (mock/request :post confirm-path pinned-params))
              retry-document (Jsoup/parse (:body retry))]
          (is (= 200 (:status retry)))
          (is (= after-confirm (assignment-facts (:id event))))
          (is (str/includes? (:body retry) "0 new assignments"))
          (is (= "1" (.attr (.selectFirst retry-document
                                          (str "[data-reviewer-id='"
                                               (:person-id reviewer-a) "']"))
                            "data-assignment-count")))
          (is (= "1" (.attr (.selectFirst retry-document
                                          (str "[data-reviewer-id='"
                                               (:person-id reviewer-b) "']"))
                            "data-assignment-count"))))))
    (testing "chair opens the durable per-reviewer completion dashboard on its own page"
      (let [board (chair-app (mock/request :get (str "/events/" slug "/board")))
            progress-path (str "/events/" slug "/reviewer-progress")
            progress-page (chair-app (mock/request :get progress-path))
            body (:body progress-page)]
        (is (= 200 (:status board)))
        (is (not (str/includes? (:body board) "reviewer-progress-panel")))
        (is (str/includes? (:body board) progress-path))
        (is (< (str/index-of (:body board) "Review Board (")
               (str/index-of (:body board) "sb-policy-link")
               (str/index-of (:body board) "Reviewer Progress")))
        (is (= 200 (:status progress-page)))
        (is (str/includes? body "Reviewer progress"))
        (is (str/includes? body "Reviews"))
        (is (str/includes? body "Assigned"))
        (is (str/includes? body "Remaining"))
        (is (str/includes? body "Reviewer A"))
        (is (str/includes? body "Reviewer B"))
        (is (str/includes? body "Draft nudges"))))
    (testing "reviewer can discover their exact persisted assignment queue"
      (let [reviewer-app (signed-in-app "a@example.com")
            board (reviewer-app (mock/request :get (str "/events/" slug "/board")))
            queue-path (str "/events/" slug "/board?assigned=1")
            queue (reviewer-app (mock/request :get queue-path))]
        (is (= 200 (:status board)))
        (is (str/includes? (:body board) "Assigned to you (1 remaining)"))
        (is (str/includes? (:body board) queue-path))
        (is (= 200 (:status queue)))
        (is (str/includes? (:body queue) "0 of 1 reviews complete"))
        (is (= 1 (count (filter #(str/includes? (:body queue) %)
                                ["AI One" "AI Two"]))))
        (is (not (str/includes? (:body queue) "Architecture One")))))
    (testing "invalid caps and non-chairs fail closed"
      (is (= 422 (:status (chair-app
                            (mock/request :post preview-path (assoc params "cap" "0"))))))
      (let [reviewer-app (signed-in-app "a@example.com")]
        (is (= 403 (:status (reviewer-app (mock/request :post preview-path params)))))
        (is (= 403 (:status (reviewer-app (mock/request :post confirm-path params)))))))))
