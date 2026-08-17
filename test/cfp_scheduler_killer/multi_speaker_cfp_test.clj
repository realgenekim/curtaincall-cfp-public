(ns cfp-scheduler-killer.multi-speaker-cfp-test
  (:require
   [cfp-scheduler-killer.auth :as auth]
   [cfp-scheduler-killer.events :as events]
   [cfp-scheduler-killer.server :as server]
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

(defn- create-event! [chair slug]
  (chair
    (mock/request :post "/api/events/create"
                  {"name" "Multi-speaker Summit"
                   "slug" slug
                   "starts-on" "2026-10-14"
                   "ends-on" "2026-10-15"
                   "presenter-visibility-mode" "visible"}))
  (events/event-by-slug slug))

(def submission-params
  {"answer-talk-title" "Two Humans, One Transformation"
   "answer-abstract" "A joint technical and business account."
   "answer-audience-level" "Advanced"
   "answer-session-format" "Panel"
   "answer-session-length" "45 minutes"
   "speaker-name" "Priya Primary"
   "speaker-email" "priya@example.com"
   "speaker-title" "VP Engineering"
   "speaker-org" "BigCo"
   "speaker-bio" "Priya leads engineering."
   "speaker-2-role" "Co-speaker"
   "speaker-2-name" "Rae Partner"
   "speaker-2-email" "rae@example.com"
   "speaker-2-title" "VP Claims"
   "speaker-2-org" "BigCo"
   "speaker-2-bio" "Rae leads the business transformation."})

(deftest public-multi-speaker-role-roundtrip-test
  (let [handler (server/create-app)
        chair (signed-in handler "organizer@example.com")
        slug (str "multi-speaker-" (events/random-suffix 8))
        event (create-event! chair slug)
        page-path (str "/cfp/" slug)
        submit-path (str "/api/cfp/" slug "/submit")]
    (testing "the public form exposes a real no-JavaScript add-another control"
      (let [body (:body (handler (mock/request :get page-path)))]
        (is (str/includes? body "Primary speaker"))
        (is (str/includes? body "+ Add another speaker"))
        (is (str/includes? body "Speaker role"))
        (is (str/includes? body "name=\"speaker-2-email\""))))
    (testing "an incomplete second identity fails at its own field and appends nothing"
      (let [response (handler
                       (mock/request :post submit-path
                                     (dissoc submission-params "speaker-2-bio")))]
        (is (= 422 (:status response)))
        (is (str/includes? (:body response) "Rae Partner"))
        (is (str/includes? (:body response) "A short bio is required"))
        (is (zero? (submissions/count-for-event (:id event))))))
    (testing "the same email cannot masquerade as two speaker identities"
      (let [response (handler
                       (mock/request :post submit-path
                                     (assoc submission-params
                                            "speaker-2-email" "PRIYA@example.com")))]
        (is (= 422 (:status response)))
        (is (str/includes? (:body response)
                           "Each speaker must use a different email address"))
        (is (zero? (submissions/count-for-event (:id event))))))
    (testing "invalid optional co-speaker URLs show their exact errors"
      (let [response (handler
                       (mock/request :post submit-path
                                     (assoc submission-params
                                            "speaker-2-headshot-url" "not-a-url"
                                            "speaker-2-linkedin" "also-not-a-url")))]
        (is (= 422 (:status response)))
        (is (str/includes? (:body response)
                           "Headshot must be a full URL or a Curtain Call image"))
        (is (str/includes? (:body response) "LinkedIn must be a full URL"))
        (is (zero? (submissions/count-for-event (:id event))))))
    (testing "both identities and roles land in one submission fact"
      (let [response (handler (mock/request :post submit-path submission-params))
            submission (first (submissions/for-event (:id event)))]
        (is (= 303 (:status response)))
        (is (= ["Priya Primary" "Rae Partner"]
               (mapv :name (:speakers submission))))
        (is (= ["Primary speaker" "Co-speaker"]
               (mapv :role (:speakers submission))))
        (testing "each new identity receives its submitted reusable profile"
          (is (= {:tagline "VP Engineering" :org "BigCo"
                  :bio "Priya leads engineering."}
                 (select-keys (:profile (store/person-by-email "priya@example.com"))
                              [:tagline :org :bio])))
          (is (= {:tagline "VP Claims" :org "BigCo"
                  :bio "Rae leads the business transformation."}
                 (select-keys (:profile (store/person-by-email "rae@example.com"))
                              [:tagline :org :bio]))))
        (is (= [0 1] (mapv :position (:speakers submission))))
        (is (every? :person-id (:speakers submission)))
        (is (= 1 (->> (store/log-for-event (:id event))
                      (filter #(= "submission.created" (:type %)))
                      count)))))
    ;; sessionize-sched-killer-5w0h: exercise the same decision transition and
    ;; the two read journeys a judge uses.  A unit assertion on the creation
    ;; fact alone missed the accepted-session projection that dropped this row.
    (testing "acceptance keeps both submitted identities and roles"
      (let [submission (first (submissions/for-event (:id event)))
            response (chair
                       (mock/request :post
                                     (str "/api/submissions/" (:id submission)
                                          "/status")
                                     {"status" "Accepted"}))
            accepted (store/submission-by-id (:id submission))]
        (is (= 303 (:status response)))
        (is (= "Accepted" (:status accepted)))
        (is (= ["Priya Primary" "Rae Partner"]
               (mapv :name (:speakers accepted))))
        (is (= ["Primary speaker" "Co-speaker"]
               (mapv :role (:speakers accepted))))))
    (testing "organizer proposal detail names both people and their roles after acceptance"
      (let [submission (first (submissions/for-event (:id event)))
            body (:body (chair
                          (mock/request :get
                                        (str "/events/" slug "/submissions/"
                                             (:id submission)))))]
        (is (str/includes? body "Speakers and roles"))
        (is (str/includes? body "Priya Primary"))
        (is (str/includes? body "Primary speaker"))
        (is (str/includes? body "Rae Partner"))
        (is (str/includes? body "Co-speaker"))))
    (testing "organizer co-speaker controls project accepted sessions before notification"
      (let [submission (first (submissions/for-event (:id event)))
            body (:body (chair (mock/request :get (str "/events/" slug "/speakers"))))]
        (is (str/includes? body (str "id=\"session-" (:id submission) "\"")))
        (is (str/includes? body "Two Humans, One Transformation"))
        (is (str/includes? body "Priya Primary"))
        (is (str/includes? body "Primary speaker"))
        (is (str/includes? body "Rae Partner"))
        (is (str/includes? body "Co-speaker"))
        (is (str/includes? body "Remove co-speaker"))))
    (testing "both speakers retain portal access to the accepted shared talk"
      (doseq [email ["priya@example.com" "rae@example.com"]]
        (let [speaker (signed-in handler email)
              response (speaker (mock/request :get "/portal"))
              body (:body response)]
          (is (= 200 (:status response)))
          (is (str/includes? body "Two Humans, One Transformation"))
          (is (str/includes? body "Speakers and roles"))
          (is (str/includes? body "Priya Primary — Primary speaker"))
          (is (str/includes? body "Rae Partner — Co-speaker"))
          (is (not (str/includes?
                     body
                     (if (= email "priya@example.com")
                       "rae@example.com"
                       "priya@example.com")))))))))
