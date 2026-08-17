(ns cfp-scheduler-killer.review-plan-routes-test
  (:require
   [cfp-scheduler-killer.agent.commands :as agent-commands]
   [cfp-scheduler-killer.auth :as auth]
   [cfp-scheduler-killer.committees :as committees]
   [cfp-scheduler-killer.events :as events]
   [cfp-scheduler-killer.inform :as inform]
   [cfp-scheduler-killer.review-assignments :as review-assignments]
   [cfp-scheduler-killer.review-authorization :as review-authorization]
   [cfp-scheduler-killer.review-plan :as review-plan]
   [cfp-scheduler-killer.reviews :as reviews]
   [cfp-scheduler-killer.server :as server]
   [cfp-scheduler-killer.store :as store]
   [cfp-scheduler-killer.submissions :as submissions]
   [cfp-scheduler-killer.test-helpers :refer [with-temp-store]]
   [cfp-scheduler-killer.web.datastar :as datastar]
   [clojure.data.json :as json]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [ring.mock.request :as mock]))

(use-fixtures :each with-temp-store)

(defn- person-app [email]
  (let [raw (server/create-app)
        token (auth/issue-token! email)
        response (raw (mock/request :get (str "/auth/" token)))
        cookie (first (str/split (first (get-in response [:headers "Set-Cookie"])) #";"))]
    (fn [request]
      (raw (mock/header request "cookie" cookie)))))

(defn- make-event! [handler slug name]
  (handler (mock/request :post "/api/events/create"
                         {"name" name
                          "slug" slug
                          "tz" "America/New_York"
                          "cfp-opens-at" "2020-01-01T00:00"
                          "cfp-closes-at" "2099-01-01T00:00"
                          "presenter-visibility-mode" "visible"}))
  (events/event-by-slug slug))

(defn- add-reviewer! [event]
  (let [committee-id (:id (first (events/committees-for-event (:id event))))]
    (committees/add-member! committee-id
                            {:name "Sam Reviewer"
                             :email "reviewer@example.com"
                             :role "reviewer"}
                            "organizer@example.com")))

(defn- submit! [event]
  (let [fields (:fields (events/form-for-event (:id event)))
        params {:answer-talk-title "Blind route talk"
                :answer-abstract "Identity must not leak."
                :answer-session-format "Experience Report"
                :answer-org-size ">10,000"
                :answer-industry "Technology"
                :answer-ai-transformation-history "2026."
                :answer-measurable-outcomes "Safer review."
                :answer-notes-to-committee "Private note."
                :speaker-name "Ann Secret Speaker"
                :speaker-email "ann-secret@example.com"
                :speaker-title "VP"
                :speaker-org "Secret Company"
                :speaker-bio "Secret biography"
                :speaker-headshot-url "https://secret.example/headshot.jpg"}]
    (submissions/create-submission!
      event (submissions/parse-answers fields params) (submissions/parse-speaker params)
      "form" "route-test")))

(deftest blind-review-routes-project-before-render-and-search
  (let [chair-app (person-app "organizer@example.com")
        slug (str "blind-route-" (events/random-suffix 8))
        event (make-event! chair-app slug "Blind Route Summit")
        _ (add-reviewer! event)
        reviewer-app (person-app "reviewer@example.com")
        submission (submit! event)
        _ (chair-app
            (mock/request :post
                          (str "/api/events/" slug "/review-plan/presenter-visibility")
                          {"mode" "hidden" "expected-version" "0"}))
        detail-path (str "/events/" slug "/submissions/" (:id submission))
        reviewer-detail (reviewer-app (mock/request :get detail-path))
        reviewer-board (reviewer-app (mock/request :get (str "/events/" slug "/board")))
        identity-search (reviewer-app
                          (mock/request :get
                                        (str "/events/" slug "/board?q=Secret+Company")))
        chair-detail (chair-app (mock/request :get detail-path))]
    (testing "reviewer board and detail render the anonymous projection"
      (is (= 200 (:status reviewer-detail)))
      (is (= 200 (:status reviewer-board)))
      (doseq [body [(:body reviewer-detail) (:body reviewer-board)]]
        (is (str/includes? body "Anonymous speaker"))
        (is (str/includes? body "Blind review"))
        (doseq [secret ["Ann Secret Speaker" "ann-secret@example.com"
                        "Secret Company" "Secret biography"]]
          (is (not (str/includes? body secret)) secret))))
    (testing "projection happens before filtering, so identity cannot be inferred by search"
      (is (= 200 (:status identity-search)))
      (is (str/includes? (:body identity-search) "No submissions match that search"))
      (is (not (str/includes? (:body identity-search) "Blind route talk"))))
    (testing "the chair exception retains the complete identity"
      (doseq [literal ["Ann Secret Speaker" "ann-secret@example.com"
                       "Secret Company" "Secret biography"]]
        (is (str/includes? (:body chair-detail) literal) literal)))))

(deftest blind-review-server-boundaries-reject-historical-and-export-bypasses
  (let [chair-app (person-app "organizer@example.com")
        slug (str "blind-boundary-" (events/random-suffix 8))
        event (make-event! chair-app slug "Blind Boundary Summit")
        reviewer (add-reviewer! event)
        reviewer-app (person-app "reviewer@example.com")
        submission (submit! event)
        historical-index (dec (count (store/log-for-event (:id event))))
        _ (chair-app
            (mock/request :post
                          (str "/api/events/" slug "/review-plan/presenter-visibility")
                          {"mode" "hidden" "expected-version" "0"}))
        _ (reviews/add-comment! (:id submission) (:person-id reviewer)
                                "The evidence is strong." "reviewer@example.com")
        historical-board
        (reviewer-app
          (mock/request :get
                        (str "/events/" slug "/board?at-index=" historical-index)))
        identity-sort
        (reviewer-app
          (mock/request :get (str "/events/" slug "/board?sort=speaker&q=Secret")))
        score-export
        (reviewer-app
          (mock/request :get (str "/events/" slug "/board/review-scores.csv")))]
    (testing "today's blind policy governs a historical full-page render"
      (is (= 200 (:status historical-board)))
      (is (str/includes? (:body historical-board) "Anonymous speaker"))
      (doseq [secret ["Ann Secret Speaker" "ann-secret@example.com"
                      "Secret Company" "Secret biography"]]
        (is (not (str/includes? (:body historical-board) secret)) secret)))
    (testing "comments and identity-based sort/search cannot reveal identity"
      (is (= 200 (:status identity-sort)))
      (is (str/includes? (:body identity-sort) "No submissions match that search"))
      (is (not (str/includes? (:body identity-sort) "Ann Secret Speaker"))))
    (testing "a reviewer cannot bypass the board projection through its CSV URL"
      (is (= 403 (:status score-export)))
      (doseq [secret ["Ann Secret Speaker" "ann-secret@example.com"
                      "Secret Company"]]
        (is (not (str/includes? (:body score-export) secret)) secret)))))

(deftest blind-review-adversarial-oracle-covers-every-server-surface
  (let [chair-app (person-app "organizer@example.com")
        slug (str "blind-oracle-" (events/random-suffix 8))
        event (make-event! chair-app slug "Blind Oracle Summit")
        reviewer (add-reviewer! event)
        reviewer-person (store/person-by-id (:person-id reviewer))
        reviewer-app (person-app "reviewer@example.com")
        submission (submit! event)
        historical-index (dec (count (store/log-for-event (:id event))))
        _ (chair-app
            (mock/request :post
                          (str "/api/events/" slug "/review-plan/presenter-visibility")
                          {"mode" "hidden" "expected-version" "0"}))
        _ (reviews/add-comment! (:id submission) (:person-id reviewer)
                                "Evidence without identity." "reviewer@example.com")
        _ (reviews/set-status! (:id submission) "Accepted" "organizer@example.com")
        _ (inform/inform! event (store/submission-by-id (:id submission))
                          "organizer@example.com")
        app (server/create-app)
        reviewer-paths [(str "/events/" slug "/board")
                        (str "/events/" slug "/submissions/" (:id submission))
                        (str "/events/" slug "/board?sort=speaker-first&q=Secret")
                        (str "/events/" slug "/board?at-index=" historical-index)]
        reviewer-pages (mapv #(reviewer-app (mock/request :get %)) reviewer-paths)
        forbidden-pages [(reviewer-app
                           (mock/request :get
                                         (str "/events/" slug "/board/review-scores.csv")))
                         (reviewer-app (mock/request :get (str "/events/" slug "/log")))
                         (reviewer-app
                           (mock/request :get (str "/events/" slug "/log/fragment")))]
        public-pages [(app (mock/request
                             :get
                             (str "/api/v1/events/" slug "/submissions/"
                                  (:id submission))))
                      (app (mock/request :get (str "/api/v1/events/" slug "/sessions")))
                      (app (mock/request :get (str "/api/v1/events/" slug "/speakers")))]
        member-api (agent-commands/invoke!
                     {:event-slug slug
                      :person reviewer-person
                      :actor (:email reviewer-person)
                      :base-url "https://oracle.example"
                      :source :test}
                     "list_submissions" {})
        secrets ["Ann Secret Speaker" "ann-secret@example.com" "Secret Company"
                 "Secret biography" "https://secret.example/headshot.jpg"]]
    (testing "board, detail, comment projection, sort/search, and time travel stay blind"
      (doseq [response reviewer-pages]
        (is (= 200 (:status response)))
        (doseq [secret secrets]
          (is (not (str/includes? (:body response) secret)) secret)))
      (is (str/includes? (:body (first reviewer-pages)) "Evidence without identity.")))
    (testing "identity-bearing exports and logs are denied at the HTTP boundary"
      (doseq [response forbidden-pages]
        (is (= 403 (:status response)))
        (doseq [secret secrets]
          (is (not (str/includes? (:body response) secret)) secret))))
    (testing "anonymous and member APIs receive only blind projections"
      (doseq [response public-pages]
        (is (= 200 (:status response)))
        (doseq [secret secrets]
          (is (not (str/includes? (:body response) secret)) secret)))
      (let [payload (pr-str member-api)]
        (is (str/includes? payload "Anonymous speaker"))
        (doseq [secret secrets]
          (is (not (str/includes? payload secret)) secret))))))

;; INTENT-TEST: REV-VIS-002
(deftest legacy-hide-identity-redacts-reviewer-and-anonymous-api-surfaces
  (let [chair-app (person-app "organizer@example.com")
        slug (str "blind-public-" (events/random-suffix 8))
        event (make-event! chair-app slug "Blind Public Summit")
        _ (add-reviewer! event)
        reviewer-app (person-app "reviewer@example.com")
        submission (submit! event)
        policy-path (str "/api/events/" slug "/review-plan/presenter-visibility")
        _ (chair-app (mock/request :post policy-path
                                   {"mode" "hidden" "expected-version" "0"}))
        _ (chair-app (mock/request :post policy-path
                                   {"mode" "visible" "expected-version" "1"}))
        _ (chair-app (mock/request :post
                                   (str "/api/events/" slug "/review-plan/presenter-visibility")
                                   {"mode" "hidden" "expected-version" "2"}))
        _ (reviews/set-status! (:id submission) "Accepted" "organizer@example.com")
        _ (inform/inform! event (store/submission-by-id (:id submission))
                          "organizer@example.com")
        app (server/create-app)
        reviewer-detail (reviewer-app
                          (mock/request :get
                                        (str "/events/" slug "/submissions/"
                                             (:id submission))))
        public-session (app (mock/request :get
                                          (str "/api/v1/events/" slug "/submissions/"
                                               (:id submission))))
        public-sessions (app (mock/request :get
                                           (str "/api/v1/events/" slug "/sessions")))
        public-speakers (app (mock/request :get
                                           (str "/api/v1/events/" slug "/speakers")))
        secrets ["Ann Secret Speaker" "ann-secret@example.com" "Secret Company"
                 "Secret biography" "https://secret.example/headshot.jpg"]]
    (is (= {:mode "hidden" :version 3}
           (select-keys (review-plan/presenter-visibility-policy (:id event))
                        [:mode :version])))
    (doseq [response [reviewer-detail public-session public-sessions public-speakers]]
      (is (= 200 (:status response)))
      (doseq [secret secrets]
        (is (not (str/includes? (:body response) secret)) secret)))
    (is (str/includes? (:body reviewer-detail) "Anonymous speaker"))
    (is (str/includes? (:body public-session) "Anonymous speaker"))
    (is (str/includes? (:body public-sessions) "Anonymous speaker"))
    (is (= [] (get (json/read-str (:body public-speakers)) "speakers")))))

(deftest presenter-visibility-policy-renders-on-board-and-cfp-test
  (let [chair-app (person-app "organizer@example.com")
        slug (str "policy-pane-" (events/random-suffix 8))
        event (make-event! chair-app slug "Policy Pane Summit")
        path (str "/api/events/" slug "/review-plan/presenter-visibility")]
    (is (= 303 (:status (chair-app (mock/request :post path
                                                 {"mode" "reveal-after-vote"
                                                  "expected-version" "0"})))))
    (doseq [body [(:body (chair-app (mock/request :get (str "/events/" slug "/board"))))
                  (:body ((server/create-app) (mock/request :get (str "/cfp/" slug))))]]
      (is (str/includes? body
                         "Blind until rated · Presenter identity appears after a reviewer submits their first rating."))
      (is (not (str/includes? body "Policy version"))))
    (is (= 1 (:version (review-plan/presenter-visibility-policy (:id event)))))))

(deftest organizer-policy-summary-stays-brief-with-unredacted-submission-test
  (let [chair-app (person-app "organizer@example.com")
        slug (str "organizer-policy-pane-" (events/random-suffix 8))
        event (make-event! chair-app slug "Organizer Policy Pane Summit")
        submission (submit! event)
        path (str "/api/events/" slug "/review-plan/presenter-visibility")]
    (is (= 303 (:status (chair-app (mock/request :post path
                                                 {"mode" "hidden"
                                                  "expected-version" "0"})))))
    (is (= 303 (:status (chair-app (mock/request :post path
                                                 {"mode" "reveal-after-vote"
                                                  "expected-version" "1"})))))
    (let [body (:body (chair-app
                        (mock/request :get (str "/events/" slug "/board"))))]
      (is (str/includes? body
                         "Blind until rated · Presenter identity appears after a reviewer submits their first rating."))
      (is (not (str/includes? body "Policy version")))
      (is (str/includes? body "Ann Secret Speaker"))
      (is (not (str/includes? body "Anonymous speaker")))
      (is (str/includes? body "data-policy-mode=\"reveal-after-vote\""))
      (is (str/includes? body "data-policy-version=\"2\""))
      (is (str/includes? body "sb-policy-summary"))
      (is (not (str/includes? body "presenter-visibility-policy")))
      (is (not (str/includes? body "name=\"expected-version\"")))
      (is (str/includes? body "How Curtain Call reviews")))
    (is (= {:mode "reveal-after-vote" :version 2}
           (select-keys (review-plan/presenter-visibility-policy (:id event))
                        [:mode :version])))
    (is (= (:id event) (:event-id submission)))))

(deftest committee-presenter-visibility-editor-updates-authoritative-policy-test
  (let [chair-app (person-app "organizer@example.com")
        slug (str "settings-blind-" (events/random-suffix 8))
        event (make-event! chair-app slug "Settings Blind Summit")
        _ (add-reviewer! event)
        _ (submit! event)
        reviewer-app (person-app "reviewer@example.com")
        response (chair-app
                   (mock/request
                     :post
                     (str "/api/events/" slug "/review-plan/presenter-visibility")
                     {"mode" "hidden" "expected-version" "0"}))
        policy (review-plan/presenter-visibility-policy (:id event))
        reviewer-board (:body
                         (reviewer-app
                           (mock/request :get (str "/events/" slug "/board"))))]
    (is (= 303 (:status response)))
    (is (= (str "/events/" slug "/committee#presenter-visibility")
           (get-in response [:headers "Location"])))
    (is (= {:mode "hidden" :version 1}
           (select-keys policy [:mode :version])))
    (is (str/includes? reviewer-board
                       "Blind review · Presenter identity is hidden throughout review."))
    (is (str/includes? reviewer-board "Anonymous speaker"))
    (doseq [secret ["Ann Secret Speaker" "ann-secret@example.com"
                    "Secret Company" "Secret biography"]]
      (is (not (str/includes? reviewer-board secret)) secret))))

(deftest review-policy-json-resource-is-self-describing-and-versioned-test
  (let [chair-app (person-app "organizer@example.com")
        slug (str "policy-api-" (events/random-suffix 8))
        event (make-event! chair-app slug "Policy API Summit")
        raw-app (server/create-app)
        path (str "/api/v1/events/" slug "/review-policy")
        get-json #(json/read-str (:body (raw-app (mock/request :get path))))]
    (is (= "visible" (get-in (get-json) ["policy" "mode"])))
    (is (= 3 (count (get-in (get-json) ["definition" "allowed-modes"]))))
    (let [put-request (-> (mock/request :put path
                                        (json/write-str {"mode" "hidden"
                                                         "expectedVersion" 0}))
                          (mock/header "content-type" "application/json"))
          response (chair-app put-request)]
      (is (= 200 (:status response)))
      (is (= "hidden" (get-in (json/read-str (:body response)) ["policy" "mode"]))))
    (let [stale (-> (mock/request :put path
                                  (json/write-str {"mode" "visible"
                                                   "expectedVersion" 0}))
                    (mock/header "content-type" "application/json"))]
      (is (= 409 (:status (chair-app stale)))))
    (is (= 1 (:version (review-plan/presenter-visibility-policy (:id event)))))))

(deftest adversarial-two-reviewer-reveal-meter-test
  (let [chair-app (person-app "organizer@example.com")
        slug (str "two-reviewer-meter-" (events/random-suffix 8))
        event (make-event! chair-app slug "Two Reviewer Meter")
        committee-id (:id (first (events/committees-for-event (:id event))))
        reviewer-a (committees/add-member!
                     committee-id {:name "Reviewer A" :email "reviewer-a@example.com"
                                   :role "reviewer"} "organizer@example.com")
        reviewer-b (committees/add-member!
                     committee-id {:name "Reviewer B" :email "reviewer-b@example.com"
                                   :role "reviewer"} "organizer@example.com")
        app-a (person-app "reviewer-a@example.com")
        app-b (person-app "reviewer-b@example.com")
        submission (submit! event)
        _assignment (review-assignments/assign!
                      (:id submission) (:person-id reviewer-a)
                      "organizer@example.com")
        authoritative-before (store/submission-by-id (:id submission))
        detail-path (str "/events/" slug "/submissions/" (:id submission))
        _ (chair-app
            (mock/request :post
                          (str "/api/events/" slug "/review-plan/presenter-visibility")
                          {"mode" "reveal-after-vote" "expected-version" "0"}))
        anonymous? #(and (str/includes? (:body %) "Anonymous speaker")
                         (not (str/includes? (:body %) "Ann Secret Speaker")))]
    (testing "both reviewers start from the same anonymous projection"
      (is (anonymous? (app-a (mock/request :get detail-path))))
      (is (anonymous? (app-b (mock/request :get detail-path)))))
    (testing "A's first persisted rating reveals only A's projection"
      (is (= 303 (:status
                   (app-a (mock/request :post
                                        (str "/api/submissions/" (:id submission) "/rate")
                                        {"stars" "4"})))))
      (is (str/includes? (:body (app-a (mock/request :get detail-path)))
                         "Ann Secret Speaker"))
      (is (anonymous? (app-b (mock/request :get detail-path))))
      (is (str/includes? (:body (chair-app (mock/request :get detail-path)))
                         "Ann Secret Speaker")))
    (testing "the reveal is keyed to the persisted reviewer rating"
      (is (contains? (:ratings (store/snapshot))
                     [(:id submission) (:person-id reviewer-a)]))
      (is (not (contains? (:ratings (store/snapshot))
                          [(:id submission) (:person-id reviewer-b)]))))
    (testing "projection never mutates the authoritative submission"
      (is (= authoritative-before (store/submission-by-id (:id submission)))))))
