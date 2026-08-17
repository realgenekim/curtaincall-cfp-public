(ns cfp-scheduler-killer.validation-test
  "bd sessionize-sched-killer-xmf — a bad VALUE must never look like a success.

   The bug these cover: POST /api/submissions/:id/{status,rate,comment} answered
   303 while appending nothing whenever the value was unrecognised. Unknown IDs
   404'd correctly; only bad values vanished. It lost writes for anyone driving
   the REST API, and it manufactured a FALSE PASS in bin/e2e_drive.py.

   So every test below asserts BOTH halves: the status code the caller sees, and
   that the log did not grow. Either one alone is what let this through the
   first time."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [ring.mock.request :as mock]
            [cfp-scheduler-killer.auth :as auth]
            [cfp-scheduler-killer.committees :as committees]
            [cfp-scheduler-killer.events :as events]
            [cfp-scheduler-killer.notices :as notices]
            [cfp-scheduler-killer.reviews :as reviews]
            [cfp-scheduler-killer.server :as server]
            [cfp-scheduler-killer.sse :as sse]
            [cfp-scheduler-killer.store :as store]
            [cfp-scheduler-killer.submissions :as sub]
            [cfp-scheduler-killer.test-helpers :refer [with-temp-store]])
  (:import (java.time LocalDateTime)))

(use-fixtures :each
  with-temp-store
  (fn [f] (reset! auth/tokens {}) (reset! sse/subscribers {}) (notices/clear-all!) (f)))

(defn- setup!
  "One event, one committee member, one submission."
  []
  (let [event (events/create-event!
               {:name "Validation Summit" :slug "validation-test" :tz "America/New_York"
                :cfp-opens-at (LocalDateTime/of 2020 1 1 0 0)
                :cfp-closes-at (LocalDateTime/of 2099 1 1 0 0)}
               "kaocha")
        cid (:id (first (events/committees-for-event (:id event))))
        gene (committees/add-member! cid {:name "Gene Kim" :email "gene@example.com"
                                          :role "chair"} "kaocha")
        ff (:fields (events/form-for-event (:id event)))
        params {:answer-talk-title "A talk about controls"
                :answer-abstract "Abstract."
                :answer-session-format "Experience Report"
                :answer-org-size ">10,000"
                :answer-industry "Insurance"
                :answer-ai-transformation-history "2023."
                :answer-measurable-outcomes "Numbers."
                :answer-notes-to-committee "PC only."
                :speaker-name "Ann Speaker" :speaker-email "ann@example.com"
                :speaker-title "VP" :speaker-org "BigCo" :speaker-bio "Bio."}
        s (sub/create-submission! event (sub/parse-answers ff params)
                                  (sub/parse-speaker params) "form" "kaocha")]
    {:event event :gene gene :submission s}))

(defn- login! [handler email]
  (let [token (auth/issue-token! email)
        resp (handler (mock/request :get (str "/auth/" token)))]
    (first (str/split (first (get-in resp [:headers "Set-Cookie"])) #";"))))

(defn- log-size
  "How many events have ACTUALLY been written — draining async sink deliveries
   first.

   Without the drain this is a race, and it bit: the `pc-push` sink fires
   asynchronously off the `submission.created` in `setup!` and appends its own
   `comms.rendered`. Land that between a baseline and its assertion and the test
   reports 10 where it expected 9 — indicting the write we just refused for a
   line it never wrote. The refusal was correct every time; the ruler was moving.

   Draining here rather than at each call site means every 'nothing was appended'
   assertion in this namespace is measuring a settled log."
  []
  (store/await-sinks!)
  (count (store/read-events)))

(defn- api-post
  "An integrator's POST: no Accept: text/html, so the answer is JSON."
  [handler cookie path params]
  (-> (mock/request :post path params)
      (mock/header "cookie" cookie)
      (mock/header "accept" "*/*")
      handler))

(defn- browser-post
  "A board POST from a browser: Accept says text/html."
  [handler cookie path params]
  (-> (mock/request :post path params)
      (mock/header "cookie" cookie)
      (mock/header "accept" "text/html,application/xhtml+xml")
      handler))

;; --- status -----------------------------------------------------------------

(deftest canonical-status-is-case-insensitive-test
  (let [statuses ["Accepted" "Accept Queue" "Pending" "Declined"]]
    (testing "the event's OWN spelling comes back, whatever case arrived"
      (is (= "Accepted" (reviews/canonical-status statuses "accepted")))
      (is (= "Accepted" (reviews/canonical-status statuses "ACCEPTED")))
      (is (= "Accepted" (reviews/canonical-status statuses "  Accepted  ")))
      (is (= "Accept Queue" (reviews/canonical-status statuses "accept queue"))))
    (testing "and a value the event never heard of is nil, not a guess"
      (is (nil? (reviews/canonical-status statuses "Bogus")))
      (is (nil? (reviews/canonical-status statuses "accept")))
      (is (nil? (reviews/canonical-status statuses "")))
      (is (nil? (reviews/canonical-status statuses nil))))))

(deftest lowercase-status-is-accepted-and-stored-canonically-test
  (let [handler (server/create-app)
        {:keys [submission]} (setup!)
        cookie (login! handler "gene@example.com")
        resp (api-post handler cookie
                       (str "/api/submissions/" (:id submission) "/status")
                       {"status" "accepted"})]
    (testing "a lowercase status is a write, not a loss"
      (is (= 303 (:status resp)))
      (is (= "Accepted" (:status (store/submission-by-id (:id submission))))))
    (testing "and the LOG carries the canonical spelling, so history never forks"
      (let [ev (last (filter #(= "submission.status-changed" (:type %)) (store/read-events)))]
        (is (= "Accepted" (get-in ev [:payload :to])))))))

(deftest unknown-status-is-422-and-appends-nothing-test
  (let [handler (server/create-app)
        {:keys [submission]} (setup!)
        cookie (login! handler "gene@example.com")
        before (log-size)
        resp (api-post handler cookie
                       (str "/api/submissions/" (:id submission) "/status")
                       {"status" "Bogus"})]
    (testing "422, never the 303 that told an integrator it worked"
      (is (= 422 (:status resp))))
    (testing "the body names the value and lists the whole vocabulary"
      (let [body (json/read-str (:body resp))]
        (is (str/includes? (get body "error") "Bogus"))
        (is (some #{"Accepted"} (get body "allowed")))
        (is (some #{"Decline Queue"} (get body "allowed")))))
    (testing "and nothing whatsoever was written"
      (is (= before (log-size)))
      (is (= "Pending" (:status (store/submission-by-id (:id submission))))))))

(deftest empty-status-is-422-test
  (let [handler (server/create-app)
        {:keys [submission]} (setup!)
        cookie (login! handler "gene@example.com")
        before (log-size)]
    (is (= 422 (:status (api-post handler cookie
                                  (str "/api/submissions/" (:id submission) "/status")
                                  {"status" "   "}))))
    (is (= before (log-size)))))

;; --- rate -------------------------------------------------------------------

(deftest bad-star-values-are-422-and-append-nothing-test
  (let [handler (server/create-app)
        {:keys [submission]} (setup!)
        cookie (login! handler "gene@example.com")]
    (doseq [[label raw] [["out of range" "99"]
                         ["negative" "-3"]
                         ["not a number" "abc"]
                         ["not on the half-step scale" "4.3"]
                         ["empty" ""]
                         ["whitespace" "  "]]]
      (let [before (log-size)
            resp (api-post handler cookie
                           (str "/api/submissions/" (:id submission) "/rate")
                           {"stars" raw})]
        (testing (str "stars=" (pr-str raw) " (" label ")")
          (is (= 422 (:status resp)))
          (is (= before (log-size)) "nothing was appended")
          (is (empty? (store/ratings-for-submission (:id submission)))))))

    (testing "the refusal names the scale, so the caller can fix it"
      (let [body (json/read-str
                  (:body (api-post handler cookie
                                   (str "/api/submissions/" (:id submission) "/rate")
                                   {"stars" "99"})))]
        (is (str/includes? (get body "error") "1 to 5"))
        (is (= 9 (count (get body "allowed"))) "1 … 5 in halves")))

    (testing "EMPTY means 'pick one', not 'clear my rating' — the documented decision"
      (let [body (json/read-str
                  (:body (api-post handler cookie
                                   (str "/api/submissions/" (:id submission) "/rate")
                                   {"stars" ""})))]
        (is (str/includes? (get body "error") "Pick a rating"))
        (is (str/includes? (get body "error") "Clearing a rating"))))

    (testing "and a legal value still works"
      (is (= 303 (:status (api-post handler cookie
                                    (str "/api/submissions/" (:id submission) "/rate")
                                    {"stars" "4.5"}))))
      (is (= 1 (count (store/ratings-for-submission (:id submission))))))))

;; --- comment ----------------------------------------------------------------

(deftest empty-comment-is-422-and-appends-nothing-test
  (let [handler (server/create-app)
        {:keys [submission]} (setup!)
        cookie (login! handler "gene@example.com")]
    (doseq [raw ["" "   " "\n\t "]]
      (let [before (log-size)
            resp (api-post handler cookie
                           (str "/api/submissions/" (:id submission) "/comment")
                           {"body" raw})]
        (testing (str "body=" (pr-str raw))
          (is (= 422 (:status resp)))
          (is (str/includes? (get (json/read-str (:body resp)) "error") "Say something"))
          (is (= before (log-size)))
          (is (empty? (store/comments-for-submission (:id submission)))))))

    (testing "a comment with content is trimmed and kept"
      (is (= 303 (:status (api-post handler cookie
                                    (str "/api/submissions/" (:id submission) "/comment")
                                    {"body" "  Worth a slot.  "}))))
      (is (= ["Worth a slot."]
             (mapv :body (store/comments-for-submission (:id submission))))))))

;; --- The Datastar error surface ---------------------------------------------

(deftest refusal-renders-a-server-side-notice-test
  (let [handler (server/create-app)
        {:keys [event submission gene]} (setup!)
        cookie (login! handler "gene@example.com")
        board #(:body (-> (mock/request :get (str "/events/" (:slug event) "/board"))
                          (mock/header "cookie" cookie)
                          handler))]

    (testing "the notice region exists BEFORE anything goes wrong — an SSE patch
              needs its target to already be on the page"
      (is (str/includes? (board) "id=\"validation-notice\"")))

    (testing "a browser gets 422 carrying the board it was on, message included"
      (let [resp (browser-post handler cookie
                               (str "/api/submissions/" (:id submission) "/status")
                               {"status" "Bogus"})]
        (is (= 422 (:status resp)))
        (is (str/includes? (:body resp) "isn&apos;t a status on this event"))
        (is (str/includes? (:body resp) "A talk about controls")
            "and it is the real board, not an error page")))

    (testing "the message is SERVER state, so it survives into the next page load"
      (is (str/includes? (board) "isn&apos;t a status on this event"))
      (is (some? (notices/notice-for (:id event) (:person-id gene)))))

    (testing "a SUCCESSFUL action clears it — the server decides when it goes,
              never a timer in the browser"
      (browser-post handler cookie
                    (str "/api/submissions/" (:id submission) "/status")
                    {"status" "Accepted"})
      (is (nil? (notices/notice-for (:id event) (:person-id gene))))
      (is (not (str/includes? (board) "isn&apos;t a status on this event"))))

    (testing "and Dismiss is a plain POST, no JavaScript anywhere"
      (browser-post handler cookie
                    (str "/api/submissions/" (:id submission) "/rate")
                    {"stars" "99"})
      (is (some? (notices/notice-for (:id event) (:person-id gene))))
      (let [resp (browser-post handler cookie
                               (str "/api/events/" (:slug event) "/notice/dismiss") {})]
        (is (= 303 (:status resp)))
        (is (nil? (notices/notice-for (:id event) (:person-id gene))))))))

(deftest a-notice-belongs-to-one-person-test
  (let [handler (server/create-app)
        {:keys [event submission gene]} (setup!)
        cid (:id (first (events/committees-for-event (:id event))))
        ann (committees/add-member! cid {:name "Ann Perry" :email "ann@example.com"
                                         :role "member"} "kaocha")
        gene-cookie (login! handler "gene@example.com")]
    (browser-post handler gene-cookie
                  (str "/api/submissions/" (:id submission) "/rate")
                  {"stars" "abc"})
    (testing "Gene's mistake is Gene's business"
      (is (some? (notices/notice-for (:id event) (:person-id gene))))
      (is (nil? (notices/notice-for (:id event) (:person-id ann)))))
    (testing "and Ann's board says nothing about it"
      (let [ann-cookie (login! handler "ann@example.com")
            body (:body (-> (mock/request :get (str "/events/" (:slug event) "/board"))
                            (mock/header "cookie" ann-cookie)
                            handler))]
        (is (str/includes? body "id=\"validation-notice\""))
        (is (not (str/includes? body "isn&apos;t a rating")))))))

(deftest the-api-does-not-get-worse-so-the-ui-can-get-nicer-test
  (let [handler (server/create-app)
        {:keys [submission]} (setup!)
        cookie (login! handler "gene@example.com")
        resp (api-post handler cookie
                       (str "/api/submissions/" (:id submission) "/rate")
                       {"stars" "99"})]
    (testing "a non-browser caller gets JSON and an honest status"
      (is (= 422 (:status resp)))
      (is (str/includes? (get-in resp [:headers "Content-Type"]) "application/json"))
      (is (map? (json/read-str (:body resp)))))
    (testing "and a refusal is never cached — you have to be able to retry"
      (is (= "no-store" (get-in resp [:headers "Cache-Control"]))))))

(deftest an-unknown-submission-is-refused-test
  ;; This used to assert 404. Since authorization went per-event (2026-08-09),
  ;; the gate has to answer "which conference is this about?" BEFORE the handler
  ;; runs — and an id we've never seen belongs to no conference, so there is no
  ;; roster to check it against. A write is therefore refused outright rather
  ;; than routed. That is also the better answer: 404-vs-403 on a guessed id is
  ;; exactly how you enumerate which submissions exist.
  (let [handler (server/create-app)
        _ (setup!)
        cookie (login! handler "gene@example.com")]
    (testing "a write against an id that belongs to no event is refused, not routed"
      (is (= 403 (:status (api-post handler cookie
                                    "/api/submissions/2a1e0b2c-0000-4000-8000-000000000000/rate"
                                    {"stars" "5"})))))))
