(ns cfp-scheduler-killer.board-test
  "Route-level tests for identity, the board, and SSE.

   The two assertions I care most about here are NEGATIVE ones: the organizer
   pages must refuse an anonymous visitor, and the private
   'Notes to the Planning Committee' answer must never appear on a public page."
  (:require
   [cfp-scheduler-killer.auth :as auth]
   [cfp-scheduler-killer.committees :as committees]
   [cfp-scheduler-killer.events :as events]
   [cfp-scheduler-killer.handlers.board :as board-handlers]
   [cfp-scheduler-killer.review-assignments :as review-assignments]
   [cfp-scheduler-killer.review-plan :as review-plan]
   [cfp-scheduler-killer.review-updates :as review-updates]
   [cfp-scheduler-killer.review-work :as review-work]
   [cfp-scheduler-killer.reviews :as reviews]
   [cfp-scheduler-killer.server :as server]
   [cfp-scheduler-killer.sse :as sse]
   [cfp-scheduler-killer.store :as store]
   [cfp-scheduler-killer.submission-content :as submission-content]
   [cfp-scheduler-killer.submissions :as sub]
   [cfp-scheduler-killer.test-helpers :refer [with-temp-store]]
   [cfp-scheduler-killer.views.review :as view-review]
   [cfp-scheduler-killer.views.submission-row :as submission-row]
   [cfp-scheduler-killer.voting-policy :as voting-policy]
   [clojure.data.json :as json]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [hiccup2.core :as h]
   [ring.mock.request :as mock])
  (:import
   (java.time LocalDateTime)))

(use-fixtures :each
  with-temp-store
  (fn [f] (reset! auth/tokens {}) (reset! sse/subscribers {}) (f)))

(defn- app [] (server/create-app))

(def ^:private private-answer "Please do not schedule me opposite the keynote.")

(defn- setup!
  "An event with a committee of one and two submissions, one of them rated."
  []
  (let [event (events/create-eais-event!
                {:name "Board Test Summit" :slug "board-test" :tz "America/New_York"
                 :cfp-opens-at (LocalDateTime/of 2020 1 1 0 0)
                 :cfp-closes-at (LocalDateTime/of 2099 1 1 0 0)
                 :presenter-visibility-mode "visible"}
                "kaocha")
        cid (:id (first (events/committees-for-event (:id event))))
        gene (committees/add-member! cid {:name "Gene Kim" :email "gene@example.com"
                                          :role "chair"} "kaocha")
        ff (:fields (events/form-for-event (:id event)))
        mk (fn [title email]
             (let [params {:answer-talk-title title
                           :answer-abstract "Abstract."
                           :answer-session-format "Experience Report"
                           :answer-track "Leadership & Organizational Change"
                           :answer-org-size ">10,000"
                           :answer-industry "Insurance"
                           :answer-ai-transformation-history "2023."
                           :answer-measurable-outcomes "Numbers."
                           :answer-notes-to-committee private-answer
                           :speaker-name "Ann Speaker" :speaker-email email
                           :speaker-title "VP" :speaker-org "BigCo" :speaker-bio "Bio."}]
               (sub/create-submission! event (sub/parse-answers ff params)
                                       (sub/parse-speaker params) "form" "kaocha")))
        a (mk "Rated talk" "a@example.com")
        b (mk "Unrated talk" "b@example.com")]
    (reviews/set-rating! (:id a) (:person-id gene) 4.5 "gene@example.com")
    (reviews/add-comment! (:id a) (:person-id gene) "Strong opener." "gene@example.com")
    {:event event :committee-id cid :gene gene :rated a :unrated b}))

(defn- login!
  "Run the REAL login flow against `handler` and return the session cookie.

   Injecting {:session {...}} onto a mock request does not work: wrap-session
   reads the cookie and replaces :session wholesale, so a hand-set session is
   silently discarded. Doing the round trip also means these tests exercise the
   actual token + cookie path rather than a convenient fiction."
  [handler email]
  (let [token (auth/issue-token! email)
        resp (handler (mock/request :get (str "/auth/" token)))
        set-cookie (first (get-in resp [:headers "Set-Cookie"]))]
    (assert set-cookie "login did not set a session cookie")
    (first (str/split set-cookie #";"))))

(defn- as
  "Attach a session cookie to a request."
  [req cookie]
  (mock/header req "cookie" cookie))

;; --- Identity ---------------------------------------------------------------

(deftest login-gate-test
  (let [handler (app)
        {:keys [event]} (setup!)]

    (testing "organizer pages redirect an anonymous visitor to the landing page"
      ;; Gene, 2026-08-11: strangers land on "/" — the page that sells, not a
      ;; login dead-end (open sign-up model, docs/open-signup.md). The
      ;; landing's own sign-in CTAs carry their own fixed ?next= targets; the
      ;; gate itself no longer encodes the originally requested path.
      (doseq [path [(str "/events")
                    (str "/events/" (:slug event))
                    (str "/events/" (:slug event) "/board")
                    (str "/events/" (:slug event) "/submissions")
                    (str "/events/" (:slug event) "/log")]]
        (let [resp (handler (mock/request :get path))]
          (is (= 302 (:status resp)) (str path " must be gated"))
          (is (= "/" (get-in resp [:headers "Location"]))
              (str path " must redirect to the landing page")))))

    (testing "the landing page itself carries the sign-in CTAs, next= baked in"
      (let [body (:body (handler (mock/request :get "/")))]
        (is (str/includes? body "/login?next=%2Fevents"))))

    (testing "PUBLIC pages are never gated — no account wall for speakers"
      (doseq [path [(str "/cfp/" (:slug event)) "/login"]]
        (is (= 200 (:status (handler (mock/request :get path))))
            (str path " must stay public"))))

    (testing "a signed-in committee member gets through"
      (let [session (login! handler "gene@example.com")
            resp (handler (as (mock/request :get (str "/events/" (:slug event) "/board")) session))]
        (is (= 200 (:status resp)))
        (is (str/includes? (:body resp) "signed in as"))
        (is (str/includes? (:body resp) "Gene Kim"))))))

(deftest magic-link-test
  (let [handler (app)
        {:keys [event]} (setup!)]

    (testing "a committee member's email produces a token"
      ;; The PAGE says nothing confirming membership — these tests run with ENV
      ;; unset (production), where confirming would leak who is on a committee.
      ;; The token is the evidence; the page is deliberately neutral.
      (let [resp (handler (mock/request :post "/api/login" {"email" "gene@example.com"}))]
        (is (= 200 (:status resp)))
        (is (not (re-find #"/auth/[0-9a-f-]{36}" (str (:body resp))))
            "and never prints the credential in production")
        (is (some? (auth/issue-token! "gene@example.com")))))

    (testing "an unknown email gets a NEUTRAL answer — no committee enumeration"
      (let [resp (handler (mock/request :post "/api/login" {"email" "stranger@example.com"}))]
        (is (= 200 (:status resp)))
        (is (str/includes? (:body resp) "is on a committee, a link is on its way"))
        (is (not (str/includes? (:body resp) "/auth/")))))

    (testing "the token signs you in and lands you on the events list"
      (let [token (auth/issue-token! "gene@example.com")
            resp (handler (mock/request :get (str "/auth/" token)))]
        (is (= 303 (:status resp)))
        (is (= "/events" (get-in resp [:headers "Location"])))
        ;; wrap-session turns :session into a Set-Cookie and removes the key,
        ;; so through the full stack the cookie IS the evidence.
        (is (some? (get-in resp [:headers "Set-Cookie"])))))

    (testing "a token is SINGLE USE — a link leaked into Slack can't be replayed"
      (let [token (auth/issue-token! "gene@example.com")]
        (is (= 303 (:status (handler (mock/request :get (str "/auth/" token))))))
        (is (= 400 (:status (handler (mock/request :get (str "/auth/" token))))))))

    (testing "an expired token is refused"
      (let [token (auth/issue-token! "gene@example.com")]
        (swap! auth/tokens update token assoc :expires-at 1)
        (is (= 400 (:status (handler (mock/request :get (str "/auth/" token))))))))

    (testing "`next` carries you to the page you were trying to reach"
      (let [token (auth/issue-token! "gene@example.com")
            target (str "/events/" (:slug event) "/board")
            resp (handler (mock/request :get (str "/auth/" token "?next="
                                                  (java.net.URLEncoder/encode target "UTF-8"))))]
        (is (= target (get-in resp [:headers "Location"])))))

    (testing "logging out sends you to /login and the old cookie stops working"
      (let [session (login! handler "gene@example.com")]
        (is (= 200 (:status (handler (as (mock/request :get "/events") session)))))
        (handler (as (mock/request :post "/logout") session))
        (is (= 302 (:status (handler (as (mock/request :get "/events") session))))
            "the session was destroyed server-side")))))

;; --- The private field must never leak --------------------------------------

(deftest private-field-never-public-test
  (let [handler (app)
        {:keys [event]} (setup!)
        session (login! handler "gene@example.com")]

    (testing "the PC-only answer is NOT on the public CFP page"
      (let [body (:body (handler (mock/request :get (str "/cfp/" (:slug event)))))]
        (is (not (str/includes? body private-answer)))))

    (testing "it IS on the board, badged, for the committee"
      (let [body (:body (handler (as (mock/request :get (str "/events/" (:slug event) "/board")) session)))]
        (is (str/includes? body private-answer))
        (is (str/includes? body "PC ONLY"))))

    (testing "an anonymous visitor cannot reach the board at all"
      (is (= 302 (:status (handler (mock/request
                                     :get (str "/events/" (:slug event) "/board")))))))

    (testing "and it is absent from the speaker's own confirmation page"
      (let [ff (:fields (events/form-for-event (:id event)))
            params {"answer-talk-title" "Fresh talk" "answer-abstract" "A"
                    "answer-session-format" "Panel"
                    "answer-track" "Developer Practices"
                    "answer-org-size" ">10,000"
                    "answer-industry" "Retail" "answer-ai-transformation-history" "x"
                    "answer-measurable-outcomes" "y"
                    "answer-notes-to-committee" private-answer
                    "speaker-name" "New Person" "speaker-email" "new@example.com"
                    "speaker-title" "VP" "speaker-org" "Co" "speaker-bio" "Bio"}
            resp (handler (mock/request :post (str "/api/cfp/" (:slug event) "/submit") params))
            location (get-in resp [:headers "Location"])
            page (:body (handler (mock/request :get location)))]
        (is (= 303 (:status resp)))
        (is (not (str/includes? page private-answer)))
        (is (some? ff))))))

;; --- The board --------------------------------------------------------------

(deftest sparkline-without-close-date-test
  (let [{:keys [event]} (setup!)
        hiccup (view-review/submissions-sparkline
                 (assoc event :cfp-closes-at nil)
                 [{:created-at (store/now-inst) :ratings [] :comments []}]
                 {:target 2})
        rendered (pr-str hiccup)]
    (is (str/includes? rendered "no close date set"))
    (is (not (str/includes? rendered "days left")))
    (is (not (str/includes? rendered "26441")))))

(deftest board-page-test
  (let [handler (app)
        {:keys [event gene rated]} (setup!)
        session (login! handler "gene@example.com")
        get-board (fn [qs]
                    (:body (handler (as (mock/request
                                          :get (str "/events/" (:slug event) "/board" qs))
                                        session))))]

    (testing "the all-submissions momentum sparkline sits right of Coverage Worklist"
      (let [body (get-board "")
            coverage-index (str/index-of body "Coverage worklist")
            spark-index (str/index-of body "spark-block")]
        (is (str/includes? body "board-work-queue-row"))
        (is (some? coverage-index))
        (is (some? spark-index))
        (is (< coverage-index spark-index))
        (is (str/includes? body "fully reviewed"))
        (is (str/includes? body "submissions"))))

    (testing "review workflow guidance precedes board state and the lifecycle graph is gone"
      (let [body (get-board "")
            guidance-index (str/index-of body "How Curtain Call reviews")
            status-index (str/index-of body "Status:")]
        (is (some? guidance-index))
        (is (some? status-index))
        (is (< guidance-index status-index))
        (is (not (str/includes? body "pipeline-strip")))
        (is (str/includes? body "Every submission"))
        (is (str/includes? body "no pagination, no page reloads"))
        (is (= 1 (count (re-seq #"How Curtain Call reviews" body))))))

    (testing "the board search spans the same available width as its rows"
      (let [body (get-board "")]
        (is (str/includes? body "style=\"width:100%;\""))
        (is (str/includes? body "flex:1 1 auto; min-width:0;"))))

    (testing "bulk reviewer distribution controls are absent"
      (let [body (get-board "")]
        (is (not (str/includes? body "Bulk reviewer distribution / auto-distribute")))
        (is (not (str/includes? body "Per-reviewer cap")))
        (is (not (str/includes? body "Preview bulk distribution")))))

    (testing "a board load materializes the enriched submission set only once"
      (let [calls (atom 0)
            enriched-for-event reviews/enriched-for-event
            body (with-redefs [reviews/enriched-for-event
                               (fn [event-id]
                                 (swap! calls inc)
                                 (enriched-for-event event-id))]
                   (get-board ""))]
        (is (str/includes? body "Rated talk"))
        (is (= 1 @calls)
            "one projection supplies board state and the all-time sparkline")))

    (testing "a live sort fragment materializes the enriched set only once"
      (let [calls (atom 0)
            enriched-for-event reviews/enriched-for-event
            body (with-redefs [reviews/enriched-for-event
                               (fn [event-id]
                                 (swap! calls inc)
                                 (enriched-for-event event-id))]
                   (board-handlers/board-fragment-html
                     (as (mock/request :get (str "/events/" (:slug event)
                                                 "/board?sort=ready-to-decide"))
                         session)
                     event))]
        (is (str/includes? body "Rated talk"))
        (is (= 1 @calls)
            "one projection supplies state, privacy, and the sparkline")))

    (testing "every opinion is inline — no clicking to see who said what"
      (let [body (get-board "")]
        (is (str/includes? body "Gene Kim"))
        (is (str/includes? body "★4.5"))
        (is (str/includes? body "Strong opener."))))

    (testing "each submission is one patchable tbody"
      (let [body (get-board "")]
        (is (str/includes? body (str "id=\"sub-" (:id rated) "\"")))))

    (testing "quick-rate controls are lazy and opened through URL state"
      (let [body (get-board "")]
        (is (not (str/includes? body "class=\"star-rating"))
            "an unopened board must ship no rating forms")
        (is (str/includes? body
                           (str "/board?open=" (:id rated) "#sub-" (:id rated))))
        (is (not (str/includes? body "data-star-on:click")))))

    (testing "the coverage worklist remains the board work queue"
      (let [body (get-board "")]
        (is (str/includes? body "Coverage worklist"))
        (is (str/includes? body "# ratings ascending"))
        (is (str/includes? body "2-review rule"))
        (is (str/includes? body "id=\"coverage-bar\""))
        (is (str/includes? body "0/2 have ≥2 reviews"))
        (is (not (str/includes? body "Decision queue")))
        (is (not (str/includes? body "Mean stars descending")))
        (is (str/includes? body ">Reviews<"))
        (is (str/includes? body "2 talks need coverage before deciding"))
        (is (= 2 (count (re-seq #"Needs another review" body)))
            "every under-covered row carries a visible warning")
        (is (str/includes? body "aria-current=\"page\""))
        (is (str/includes? body "href=\"/events/board-test/board?sort=needs-reviews\""))
        (is (str/includes? body "postJSON(&apos;/api/events/board-test/board/sort&apos;"))))

    (testing "'needs reviews' puts the unrated talk first"
      (let [body (get-board "?sort=needs-reviews")]
        (is (< (str/index-of body "Unrated talk") (str/index-of body "Rated talk")))))

    (testing "'ready to decide' puts the covered, well-rated talk first"
      (let [body (get-board "?sort=ready-to-decide")]
        (is (< (str/index-of body "Rated talk") (str/index-of body "Unrated talk")))))

    (testing "search narrows the board server-side"
      (let [body (get-board "?q=Unrated")]
        (is (str/includes? body "Unrated talk"))
        (is (not (str/includes? body ">Rated talk<")))
        (is (str/includes? body "1 of 2 shown"))))

    (testing "the status chips carry their counts"
      (is (str/includes? (get-board "") "Pending 2")))

    (testing "the board opens an SSE connection and nothing else"
      (let [body (get-board "")]
        (is (str/includes? body "data-star-init"))
        (is (str/includes? body (str "/api/sse?event-id=" (:id event))))
        (is (str/includes? body "datastar-aliased.js"))
        (is (str/includes? body "id=\"sse-heartbeat\""))))

    (testing "pages that don't stream do NOT ship the SSE client"
      (let [body (:body (handler (as (mock/request :get "/events") session)))]
        (is (not (str/includes? body "datastar-aliased.js")))))))

;; INTENT-TEST: REV-BOARD-001
(deftest large-board-response-has-a-byte-per-source-row-budget
  ;; INTENT-TEST: REV-BOARD-001
  (let [handler (app)
        {:keys [event]} (setup!)
        session (login! handler "gene@example.com")
        base-row (first (reviews/enriched-for-event (:id event)))
        source-count 501
        large-rows (mapv (fn [i]
                           (-> base-row
                               (assoc :id (format "budget-submission-%03d" i))
                               (assoc-in [:answers :talk-title]
                                         (format "Budget talk %03d" i))))
                         (range source-count))
        get-board (fn [suffix]
                    (with-redefs [reviews/enriched-for-event (fn [_] large-rows)]
                      (:body
                        (handler
                          (as (mock/request
                                :get
                                (str "/events/" (:slug event) "/board" suffix))
                              session)))))
        full-board (get-board "?sort=needs-reviews")
        legacy-page-query (get-board "?sort=needs-reviews&page=2")
        response-bytes (alength (.getBytes full-board "UTF-8"))]
    (testing "the shared board renders every submission without pagination"
      (is (= source-count (count (re-seq #"class=\"ledger-row\"" full-board))))
      (is (= source-count (count (re-seq #"class=\"ledger-row\"" legacy-page-query))))
      (is (not (str/includes? full-board "Page 1 of")))
      (is (not (str/includes? full-board "page=2")))
      (is (str/includes? full-board "data-star-on:input__debounce.50ms"))
      (is (str/includes? full-board "/api/events/board-test/board/sort"))
      (is (str/includes? full-board "Budget talk 000"))
      (is (str/includes? full-board "Budget talk 500"))
      (is (not (str/includes? full-board "class=\"star-rating"))))
    (testing "five hundred plus rows stay under three KiB per rendered row"
      (is (< response-bytes (* source-count 3072))
          (str response-bytes " bytes exceeded the byte-per-row budget")))))

(deftest quick-rate-controls-render-only-for-the-url-selected-row
  (let [handler (app)
        {:keys [event committee-id gene rated]} (setup!)
        _maya (committees/add-member!
                committee-id
                {:name "Maya Reviewer" :email "maya@example.com" :role "reviewer"}
                "kaocha")
        session (login! handler "gene@example.com")
        maya-session (login! handler "maya@example.com")
        response (handler
                   (as (mock/request
                         :get
                         (str "/events/" (:slug event) "/board?open=" (:id rated)))
                       session))
        body (:body response)
        maya-body (:body
                    (handler
                      (as (mock/request :get (str "/events/" (:slug event) "/board"))
                          maya-session)))]
    (is (= 200 (:status response)))
    (is (= 1 (count (re-seq #"class=\"star-rating" body))))
    (is (str/includes? body (str "id=\"sub-" (:id rated) "\"")))
    (is (str/includes? body "aria-label=\"Rate 3.0 stars\""))
    (is (zero? (count (re-seq #"class=\"star-rating" maya-body)))
        "Gene's request-local open row never appears in Maya's board")))

(deftest submission-status-controls-board-visibility-and-rating-test
  (let [{:keys [event gene unrated]} (setup!)
        handler (app)
        session (login! handler (:email gene))
        person (store/person-by-id (:person-id gene))
        board-path (str "/events/" (:slug event) "/board")]
    (testing "only active review queues expose rating actions"
      (doseq [status ["Pending" "Accept Queue" "Decline Queue"]]
        (reviews/set-status! (:id unrated) status "gene@example.com")
        (let [row (reviews/enrich (store/submission-by-id (:id unrated)))
              body (str (h/html (submission-row/board-row event row person)))]
          (is (str/includes? body "Read &amp; rate →") status)
          (is (str/includes? body "Quick rate ▾") status))))

    (testing "closed states remain readable but reject ratings"
      (doseq [status ["Waitlisted" "Accepted" "Declined" "Withdrawn"]]
        (reviews/set-status! (:id unrated) status "gene@example.com")
        (let [row (reviews/enrich (store/submission-by-id (:id unrated)))
              body (str (h/html (submission-row/board-row event row person)))
              error (try
                      (reviews/set-rating! (:id unrated) (:person-id gene) 4.0 "gene@example.com")
                      nil
                      (catch clojure.lang.ExceptionInfo e e))]
          (is (str/includes? body "Read →") status)
          (is (not (str/includes? body "Quick rate ▾")) status)
          (is (= :submission-not-rateable (:type (ex-data error))) status))))

    (testing "Draft disappears from the rendered board"
      (reviews/set-status! (:id unrated) "Draft" "gene@example.com")
      (let [body (:body (handler (as (mock/request :get board-path) session)))]
        (is (not (str/includes? body "Unrated talk")))
        (is (not (str/includes? body ">Draft<")))))))

(deftest presenter-visibility-has-one-authority-and-never-blinds-the-chair-test
  (let [handler (app)
        {:keys [event committee-id gene rated]} (setup!)
        reviewer (committees/add-member!
                   committee-id
                   {:name "Maya Reviewer" :email "maya@example.com" :role "reviewer"}
                   "kaocha")
        _ (review-plan/set-presenter-visibility!
            (:id event) "hidden" 0 (:person-id gene) "gene@example.com")
        chair-session (login! handler "gene@example.com")
        reviewer-session (login! handler "maya@example.com")
        board-url (str "/events/" (:slug event) "/board")
        detail-url (str "/events/" (:slug event) "/submissions/" (:id rated))
        fetch-body (fn [url session]
                     (:body (handler (as (mock/request :get url) session))))
        chair-board (fetch-body board-url chair-session)
        chair-detail (fetch-body detail-url chair-session)
        reviewer-board (fetch-body board-url reviewer-session)
        reviewer-detail (fetch-body detail-url reviewer-session)]
    (testing "the durable review-plan policy overrides stale legacy event settings"
      (is (str/includes? reviewer-board
                         "Blind review · Presenter identity is hidden throughout review."))
      (is (str/includes? reviewer-board "Anonymous speaker"))
      (is (not (str/includes? reviewer-board "Ann Speaker"))))
    (testing "the chair remains unredacted on both board and submission detail"
      (is (str/includes? chair-board "Ann Speaker"))
      (is (not (str/includes? chair-board "Anonymous speaker")))
      (is (str/includes? chair-detail "Ann Speaker"))
      (is (not (str/includes? chair-detail "Anonymous speaker"))))
    (testing "a reviewer remains redacted on submission detail"
      (is (str/includes? reviewer-detail "Anonymous speaker"))
      (is (not (str/includes? reviewer-detail "Ann Speaker"))))
    (is (:person-id reviewer))))

(deftest revealed-mark-signals-deliberate-reveal-test
  ;; bd-cm3h, treatment #05 "dashed halo": when a BLIND policy would hide the
  ;; presenter but a viewer is nonetheless seeing the identity because they are
  ;; EXEMPT (a chair), the avatar carries `revealed-mark` so the reveal reads as
  ;; deliberate — never a leak.
  (let [handler (app)
        {:keys [event gene rated committee-id]} (setup!)
        reviewer (committees/add-member!
                   committee-id
                   {:name "Maya Reviewer" :email "maya@example.com" :role "reviewer"}
                   "kaocha")
        _ (review-plan/set-presenter-visibility!
            (:id event) "hidden" 0 (:person-id gene) "gene@example.com")
        chair-session (login! handler "gene@example.com")
        reviewer-session (login! handler "maya@example.com")
        fetch-body (fn [url session]
                     (:body (handler (as (mock/request :get url) session))))
        board-url (str "/events/" (:slug event) "/board")
        detail-url (str "/events/" (:slug event) "/submissions/" (:id rated))]
    (testing "chair under a HIDDEN policy gets the revealed-mark on board and detail"
      (is (str/includes? (fetch-body board-url chair-session) "revealed-mark")
          "the chair's board avatar is marked as a deliberate reveal")
      (is (str/includes? (fetch-body detail-url chair-session) "revealed-mark")
          "the chair's submission-detail avatar is marked too"))
    (testing "a still-blinded plain reviewer gets NO mark (they see no identity)"
      (is (not (str/includes? (fetch-body board-url reviewer-session) "revealed-mark"))))
    (is (:person-id reviewer))))

(deftest revealed-mark-absent-under-visible-policy-test
  ;; bd-cm3h: under a VISIBLE (open-table) policy the identity is shown to
  ;; everyone, so there is nothing to reassure — NO mark, even for a chair.
  (let [handler (app)
        {:keys [event rated]} (setup!)
        chair-session (login! handler "gene@example.com")
        board (:body (handler (as (mock/request
                                    :get (str "/events/" (:slug event) "/board"))
                                  chair-session)))
        detail (:body (handler (as (mock/request
                                     :get (str "/events/" (:slug event)
                                               "/submissions/" (:id rated)))
                                   chair-session)))]
    (testing "chair under a VISIBLE policy gets NO mark (identity is open to all)"
      (is (str/includes? board "Ann Speaker") "sanity: identity is openly shown")
      (is (not (str/includes? board "revealed-mark")))
      (is (not (str/includes? detail "revealed-mark"))))))

(deftest submission-detail-obeys-canonical-visible-policy-test
  (let [handler (app)
        {:keys [event committee-id rated]} (setup!)
        _ (committees/add-member!
            committee-id
            {:name "Maya Reviewer" :email "maya@example.com" :role "reviewer"}
            "kaocha")
        reviewer-session (login! handler "maya@example.com")
        body (:body (handler (as (mock/request
                                   :get (str "/events/" (:slug event)
                                             "/submissions/" (:id rated)))
                                 reviewer-session)))]
    (testing "stale legacy blinding cannot override the canonical visible policy"
      (is (str/includes? body "Ann Speaker"))
      (is (not (str/includes? body "Anonymous speaker"))))))

(deftest blind-review-redacts-co-speakers-on-submission-detail-test
  ;; Regression: the multi-speaker "Speakers and roles" block on the submission
  ;; detail page rendered every co-speaker's name, email, and org UNGATED —
  ;; leaking presenter identity to reviewers even under a hidden policy while
  ;; the primary-speaker rail was correctly redacted.
  (let [handler (app)
        {:keys [event committee-id gene]} (setup!)
        _ (committees/add-member!
            committee-id
            {:name "Maya Reviewer" :email "maya@example.com" :role "reviewer"}
            "kaocha")
        ff (:fields (events/form-for-event (:id event)))
        params {:answer-talk-title "Duo talk"
                :answer-abstract "Abstract."
                :answer-session-format "Experience Report"
                :answer-track "Leadership & Organizational Change"
                :answer-org-size ">10,000"
                :answer-industry "Insurance"
                :answer-ai-transformation-history "2023."
                :answer-measurable-outcomes "Numbers."
                :answer-notes-to-committee private-answer
                :speaker-name "Ann Primary" :speaker-email "ann-primary@example.com"
                :speaker-title "VP" :speaker-org "BigCo" :speaker-bio "Bio."
                :speaker-2-name "Bob Cospeaker"
                :speaker-2-email "bob-cospeaker@example.com"
                :speaker-2-title "Director" :speaker-2-org "SecretOrg"
                :speaker-2-role "Co-speaker"}
        duo (sub/create-submission! event (sub/parse-answers ff params)
                                    (sub/parse-speakers params) "form" "kaocha")
        ;; Canonical authority: hide presenter identity from reviewers.
        _ (review-plan/set-presenter-visibility!
            (:id event) "hidden" 0 (:person-id gene) "gene@example.com")
        detail-url (str "/events/" (:slug event) "/submissions/" (:id duo))
        fetch-body (fn [session]
                     (:body (handler (as (mock/request :get detail-url) session))))
        reviewer-detail (fetch-body (login! handler "maya@example.com"))
        chair-detail (fetch-body (login! handler "gene@example.com"))]
    (testing "a reviewer never sees any co-speaker's name / email / org"
      (is (not (str/includes? reviewer-detail "Bob Cospeaker")))
      (is (not (str/includes? reviewer-detail "bob-cospeaker@example.com")))
      (is (not (str/includes? reviewer-detail "SecretOrg")))
      (is (not (str/includes? reviewer-detail "Ann Primary")))
      ;; The block still renders, redacted, so the reviewer knows co-speakers
      ;; exist but their identities are withheld.
      (is (str/includes? reviewer-detail voting-policy/hidden-marker)))
    (testing "the chair still sees co-speaker identities on the same page"
      (is (str/includes? chair-detail "Bob Cospeaker"))
      (is (str/includes? chair-detail "SecretOrg")))))

(deftest quick-rate-sse-obeys-canonical-visible-policy-test
  (let [handler (app)
        {:keys [event committee-id rated]} (setup!)
        reviewer (committees/add-member!
                   committee-id
                   {:name "Maya Reviewer" :email "maya@example.com" :role "reviewer"}
                   "kaocha")
        reviewer-session (login! handler "maya@example.com")
        row-html (promise)
        fake-gen (reify Object)]
    (with-redefs [starfederation.datastar.clojure.api/patch-elements!
                  (fn [_ html _]
                    (when (str/includes? html (str "sub-" (:id rated)))
                      (deliver row-html html))
                    true)]
      (sse/add-subscriber! fake-gen (:id event) (:person-id reviewer))
      (try
        (let [resp (handler (as (mock/request
                                  :post
                                  (str "/api/submissions/" (:id rated) "/comment")
                                  {"body" "Visible in the first response."})
                                reviewer-session))
              _ (sse/await-pushes!)
              html (when (realized? row-html) @row-html)]
          (is (= 303 (:status resp)))
          (is (some? html) "the quick-rate POST repaints the changed row")
          (is (str/includes? (or html "") "Ann Speaker"))
          (is (not (str/includes? (or html "") "Anonymous speaker"))))
        (finally
          (sse/remove-subscriber! fake-gen))))))

(deftest submission-detail-comment-returns-a-fresh-projection-test
  (let [handler (app)
        {:keys [event committee-id rated]} (setup!)
        reviewer (committees/add-member!
                   committee-id
                   {:name "Maya Reviewer" :email "maya@example.com" :role "reviewer"}
                   "kaocha")
        _assignment (review-assignments/assign!
                      (:id rated) (:person-id reviewer) "kaocha")
        session (login! handler "maya@example.com")
        detail-path (str "/events/" (:slug event) "/submissions/" (:id rated))
        detail-request (-> (mock/request
                             :post
                             (str "/api/submissions/" (:id rated) "/comment")
                             {"body" "Both are visible immediately." "stars" "4"})
                           (mock/header "referer" detail-path)
                           (as session))
        response (handler detail-request)
        fresh-body (:body (handler (as (mock/request :get detail-path) session)))]
    (is (= 303 (:status response)))
    (is (= (str detail-path "#sub-" (:id rated))
           (get-in response [:headers "Location"])))
    (is (str/includes? fresh-body "Both are visible immediately."))
    (is (str/includes? fresh-body "rated ★ 4.0"))
    (is (str/includes? fresh-body "Saved ✓"))
    (is (not (str/includes? fresh-body "onsubmit=\"event.preventDefault")))))

(deftest review-scores-csv-export-test
  (let [handler (app)
        {:keys [event committee-id gene rated unrated]} (setup!)
        session (login! handler "gene@example.com")
        path (str "/events/" (:slug event) "/board/review-scores.csv")
        recused (committees/add-member!
                  committee-id
                  {:name "Recused Reviewer" :email "recused@example.com"
                   :role "reviewer"}
                  "gene@example.com")
        _ (reviews/set-rating! (:id rated) (:person-id recused) 1.0
                               "recused@example.com")
        _ (review-work/recuse! (:id rated) (:person-id recused)
                               "Conflict of interest" "recused@example.com")
        response (handler (as (mock/request :get path) session))
        body (:body response)
        other (events/create-event!
                {:name "Other Event" :slug "other-event" :tz "America/New_York"}
                "kaocha")
        other-committee (:id (first (events/committees-for-event (:id other))))
        _ (committees/add-member! other-committee
                                  {:name "Other Reviewer" :email "other@example.com"
                                   :role "reviewer"}
                                  "kaocha")
        fields (:fields (events/form-for-event (:id other)))
        other-params {:answer-talk-title "Other event secret"
                      :answer-abstract "Private to the other event."
                      :answer-session-format "Experience Report"
                      :answer-org-size ">10,000"
                      :answer-industry "Retail"
                      :answer-ai-transformation-history "History"
                      :answer-measurable-outcomes "Outcomes"
                      :speaker-name "Other Speaker" :speaker-email "speaker@other.example"
                      :speaker-title "VP" :speaker-org "Other Co" :speaker-bio "Bio"}
        _ (sub/create-submission! other (sub/parse-answers fields other-params)
                                  (sub/parse-speaker other-params) "form" "kaocha")
        other-session (login! handler "other@example.com")]
    (testing "an organizer gets a deterministic download with board scores"
      (is (= 200 (:status response)))
      (is (= "text/csv; charset=utf-8" (get-in response [:headers "Content-Type"])))
      (is (= "attachment; filename=\"board-test-review-scores.csv\""
             (get-in response [:headers "Content-Disposition"])))
      (is (str/starts-with?
            body
            (str "submission_id,title,speaker_name,speaker_email,decision_status,"
                 "content_status,priority,review_count,mean_stars,"
                 "reviewer_name,reviewer_id,stars,rated_at\r\n")))
      (is (str/includes? body "Rated talk"))
      (is (str/includes? body "Ann Speaker,a@example.com"))
      (is (str/includes? body "Ann Speaker,b@example.com"))
      (is (str/includes? body "Gene Kim"))
      (is (str/includes? body ",4.5,"))
      (is (not (str/includes? body "weighted_score")))
      (is (not (str/includes? body "criterion_values_json")))
      (is (not (str/includes? body "Recused Reviewer")))
      (is (= body (:body (handler (as (mock/request :get path) session))))))
    (testing "the board routes organizers to exports without downloading inline"
      (let [board-body (:body (handler (as (mock/request :get "/events/board-test/board")
                                           session)))
            settings-body (:body (handler (as (mock/request :get "/events/board-test/settings")
                                              session)))]
        (is (str/includes? board-body "Export review results"))
        (is (str/includes? board-body "href=\"/events/board-test/exports\""))
        (is (not (str/includes? board-body "review-results.csv")))
        (is (not (str/includes? board-body "Prepare export")))
        (is (not (str/includes? board-body "Download review scores CSV")))
        (is (str/includes? settings-body "Review data export"))
        (is (not (str/includes? settings-body "Prepare export")))
        (is (str/includes? settings-body "Download review scores CSV"))
        (is (str/includes? settings-body "download=\"board-test-review-scores.csv\""))
        (is (str/includes? settings-body
                           "Download review scores CSV as board-test-review-scores.csv"))))
    (testing "a stranger and a reviewer from another event cannot download it"
      (is (= 302 (:status (handler (mock/request :get path)))))
      (is (= 403 (:status (handler (as (mock/request :get path) other-session))))))
    (testing "another event's submission cannot appear"
      (is (not (str/includes? body "Other event secret"))))))

(deftest board-mutations-test
  (let [handler (app)
        {:keys [event gene unrated]} (setup!)
        session (login! handler "gene@example.com")
        post (fn [path params]
               (handler (as (mock/request :post path params) session)))]

    (testing "rating from the board records it and sends you back"
      (let [resp (post (str "/api/submissions/" (:id unrated) "/rate") {"stars" "3.5"})]
        (is (= 303 (:status resp)))
        (is (= 3.5 (:stars (store/rating-by (:id unrated) (:person-id gene)))))))

    (testing "an illegal star value is REFUSED — 422, never a 303 that did nothing"
      (let [resp (post (str "/api/submissions/" (:id unrated) "/rate") {"stars" "4.3"})]
        (is (= 422 (:status resp)) "a 303 here is the bug in bd -xmf")
        (is (= 3.5 (:stars (store/rating-by (:id unrated) (:person-id gene))))
            "the old rating stands")))

    (testing "commenting works from the board"
      (post (str "/api/submissions/" (:id unrated) "/comment") {"body" "Worth a slot."})
      (is (= ["Worth a slot."] (mapv :body (store/comments-for-submission (:id unrated))))))

    (testing "posting a comment carries the selected rating in the same action"
      (let [row-html (promise)
            fake-gen (reify Object)]
        (with-redefs [starfederation.datastar.clojure.api/patch-elements!
                      (fn [_ html _]
                        (when (str/includes? html (str "sub-" (:id unrated)))
                          (deliver row-html html))
                        true)]
          (sse/add-subscriber! fake-gen (:id event) (:person-id gene))
          (let [resp (post (str "/api/submissions/" (:id unrated) "/comment")
                           {"body" "Five, with evidence." "stars" "5"})]
            (sse/await-pushes!)
            (let [html (when (realized? row-html) @row-html)]
              (is (= 303 (:status resp)))
              (is (= 5.0 (:stars (store/rating-by (:id unrated) (:person-id gene)))))
              (is (some? html) "the first live response includes the changed row")
              (is (str/includes? (or html "") "★5"))
              (is (str/includes? (or html "") "Five, with evidence."))))
          (sse/remove-subscriber! fake-gen))))

    (testing "status changes from the board"
      (post (str "/api/submissions/" (:id unrated) "/status") {"status" "Accept Queue"})
      (is (= "Accept Queue" (:status (store/submission-by-id (:id unrated))))))

    (testing "an invalid status is REFUSED, and says what the choices are"
      (let [resp (post (str "/api/submissions/" (:id unrated) "/status") {"status" "Nope"})]
        (is (= 422 (:status resp)))
        (is (= "Accept Queue" (:status (store/submission-by-id (:id unrated)))))))

    (testing "a CASE VARIANT of a real status succeeds — be liberal in what you accept"
      (let [resp (post (str "/api/submissions/" (:id unrated) "/status") {"status" "accepted"})]
        (is (= 303 (:status resp)))
        (is (= "Accepted" (:status (store/submission-by-id (:id unrated))))
            "and the event's OWN spelling is what gets stored")))

    (testing "priority toggles"
      (post (str "/api/submissions/" (:id unrated) "/priority") {})
      (is (true? (:priority (store/submission-by-id (:id unrated))))))

    (testing "an ANONYMOUS visitor cannot rate — the gate is on the API too"
      (let [resp (handler (mock/request :post (str "/api/submissions/" (:id unrated) "/rate")
                                        {"stars" "5"}))]
        (is (= 302 (:status resp)))
        (is (= 5.0 (:stars (store/rating-by (:id unrated) (:person-id gene))))
            "nothing changed")))

    (testing "the coverage number moved because real work happened"
      (is (= 2 (:covered (reviews/coverage (:id event) 1)))))))

(deftest datastar-rating-and-comment-return-the-fresh-durable-row-test
  (let [handler (app)
        {:keys [event gene unrated]} (setup!)
        session (login! handler "gene@example.com")
        received (atom [])
        fake-gen (reify Object)
        json-post (fn [path body]
                    (handler
                      (-> (mock/request :post path)
                          (mock/content-type "application/json")
                          (mock/body (json/write-str body))
                          (as session))))
        rate-path (str "/api/submissions/" (:id unrated) "/rate")
        comment-path (str "/api/submissions/" (:id unrated) "/comment")]
    (with-redefs [starfederation.datastar.clojure.api/patch-elements!
                  (fn [_ html _]
                    (swap! received conj html)
                    true)]
      (sse/add-subscriber! fake-gen (:id event) (:person-id gene))
      (try
        (testing "the browser's numeric JSON rating click is durable"
          (is (= 204 (:status (json-post rate-path {:stars 5}))))
          (sse/await-pushes!)
          (is (= 5.0 (:stars (store/rating-by (:id unrated) (:person-id gene))))))

        (testing "the JSON comment action's first pushed row contains both writes"
          (reset! received [])
          ;; `stars` is a string here because the browser obtains it from
          ;; FormData immediately before posting the comment.
          (is (= 204 (:status (json-post comment-path
                                         {:body "Five, with browser evidence."
                                          :stars "5"}))))
          (sse/await-pushes!)
          (let [row (last (filter #(str/includes? % (str "sub-" (:id unrated)))
                                  @received))]
            (is (= 5.0 (:stars (store/rating-by (:id unrated) (:person-id gene)))))
            (is (= ["Five, with browser evidence."]
                   (mapv :body (store/comments-for-submission (:id unrated)))))
            (is (some? row) "the comment action pushes the personalized row")
            (is (str/includes? row "★5") "the pushed row is not stale")
            (is (str/includes? row "Five, with browser evidence."))))
        (finally
          (sse/remove-subscriber! fake-gen))))))

(deftest recused-reviewer-cannot-contribute-review-evidence-test
  (let [handler (app)
        {:keys [event gene unrated]} (setup!)
        session (login! handler "gene@example.com")
        post (fn [path params]
               (handler (as (mock/request :post path params) session)))
        submission-path (str "/api/submissions/" (:id unrated))]
    (is (= 303 (:status (post (str submission-path "/recuse")
                              {"reason" "Conflict of interest"}))))
    (testing "direct rating and comment posts are truthfully refused"
      (is (= 422 (:status (post (str submission-path "/rate") {"stars" "5"}))))
      (is (nil? (store/rating-by (:id unrated) (:person-id gene))))
      (is (= 422 (:status (post (str submission-path "/comment")
                                {"body" "This must not be recorded."}))))
      (is (empty? (store/comments-for-submission (:id unrated)))))
    (testing "the board shows the recusal instead of active review controls"
      (let [body (:body (handler (as (mock/request
                                       :get (str "/events/" (:slug event) "/board"))
                                     session)))]
        (is (str/includes? body "Recused from this review"))
        (is (str/includes? body (str submission-path "/unrecuse")))
        (is (not (str/includes? body (str submission-path "/rate"))))))))

;; --- Submission detail ------------------------------------------------------

(deftest submission-detail-test
  (let [handler (app)
        {:keys [event gene rated]} (setup!)
        session (login! handler "gene@example.com")
        body (:body (handler (as (mock/request
                                   :get (str "/events/" (:slug event)
                                             "/submissions/" (:id rated)))
                                 session)))]
    (testing "the full proposal renders under its snapshot labels"
      (is (str/includes? body "Rated talk"))
      (is (str/includes? body "Organization size"))
      (is (str/includes? body "Notes to the Planning Committee"))
      (is (str/includes? body private-answer))
      (is (str/includes? body "PC ONLY")))

    (testing "the speaker block is there"
      (is (str/includes? body "Ann Speaker"))
      (is (str/includes? body "BigCo")))

    ;; CHANGED 2026-08-16 for the ratified NATIVE QUICK RATE round trip
    ;; (review-board rebuild; Mayor-swept, pending GENEDEV ratification). The
    ;; submission detail page renders submission-row/row-controls* with
    ;; zero-navigation? FALSE, so the rating control is now nine plain
    ;; <button type="submit" name="stars"> inside one <form method="post"
    ;; action=".../rate">, not radio inputs driven by postJSON. The INTENT is
    ;; unchanged and is re-asserted below in the new markup's own terms: the
    ;; control is present, keyboard-operable and labelled, the reviewer's
    ;; current rating is announced, and it is wired to the persist endpoint.
    (testing "the committee's thread and keyboard-accessible rating controls are there
              (Treatment B: ratings render as 'rated ★ N' bubbles)"
      (is (str/includes? body "rated ★ 4.5"))
      (is (str/includes? body "Strong opener."))
      (is (str/includes? body "the conversation so far"))
      ;; Wired to the persist endpoint — natively, as the form's own action.
      (is (str/includes? body (str "action=\"/api/submissions/" (:id rated) "/rate\"")))
      (is (str/includes? body "class=\"star-rating\""))
      (is (str/includes? body "aria-label=\"Your rating for Rated talk\""))
      ;; Nine steps, 1.0–5.0 in halves. Native submit buttons are focusable and
      ;; Enter/Space-activated, so keyboard access needs no JS at all.
      (is (= 9 (count (re-seq #"aria-label=\"Rate [0-9.]+ stars\"" body))))
      (is (= 9 (count (re-seq #"name=\"stars\" type=\"submit\"" body))))
      ;; The reviewer's own rating is ANNOUNCED, not merely coloured.
      (is (= 1 (count (re-seq #"class=\"star-btn mine\"" body))))
      (is (str/includes? body "aria-label=\"Rate 4.5 stars\" aria-pressed="))
      ;; The comment box is a real form on this page, not an input adopted into
      ;; a remote form by a `form=` attribute (that was the zero-navigation
      ;; board-row shape, which this page no longer renders).
      (is (str/includes? body (str "id=\"comment-form-" (:id rated) "\"")))
      ;; Native round trip: the detail page must NOT reach for the client-side
      ;; rating path. A postJSON rate call here would mean the server stopped
      ;; owning the response.
      (is (not (str/includes? body (str "postJSON('/api/submissions/" (:id rated) "/rate'"))))
      (is (not (str/includes? body "style=\"display:none;\""))))

    (testing "an unknown submission 404s"
      (is (= 404 (:status (handler (as (mock/request
                                         :get (str "/events/" (:slug event)
                                                   "/submissions/" (store/new-id)))
                                       session))))))))

(deftest organizer-session-content-history-test
  (let [handler (app)
        {:keys [event committee-id rated]} (setup!)
        _ (reviews/set-status! (:id rated) "Accepted" "gene@example.com")
        reviewer (committees/add-member!
                   committee-id
                   {:name "Review Only" :email "reviewer@example.com"
                    :role "reviewer"}
                   "kaocha")
        chair-session (login! handler "gene@example.com")
        reviewer-session (login! handler (:email reviewer))
        detail-path (str "/events/" (:slug event) "/submissions/" (:id rated))
        edit-path (str "/api/events/" (:slug event) "/submissions/"
                       (:id rated) "/content")]
    (testing "the chair sees one canonical edit surface and append-only history"
      (let [body (:body (handler (as (mock/request :get detail-path)
                                     chair-session)))]
        (is (str/includes? body "Session editing"))
        (is (str/includes? body "Save session changes"))
        (is (str/includes? body "History"))
        (is (str/includes? body "No session edits yet."))))

    (testing "an accepted session can be edited through the real route"
      (let [response (handler
                       (as (-> (mock/request :post edit-path)
                               (mock/body
                                 {:answer-talk-title "Organizer title"}))
                           chair-session))]
        (is (= 303 (:status response)))
        (is (= "Organizer title"
               (get-in (store/submission-by-id (:id rated))
                       [:answers :talk-title]))))
      (let [body (:body (handler (as (mock/request :get detail-path)
                                     chair-session)))]
        (is (str/includes? body "Restore this version"))
        (is (str/includes? body "talk-title"))
        (is (str/includes? body " · Gene Kim · gene@example.com</div>")
            "content history attributes the edit to the organizer's visible name")
        (is (not (str/includes? body "class=\"header\"> · gene@example.com"))
            "content-history entries render their event-local timestamp")))

    (testing "restore appends another fact and returns to the prior version"
      (let [revision (first (submission-content/revision-history (:id rated)))
            restore-path (str "/api/events/" (:slug event) "/submissions/"
                              (:id rated) "/history/" (:log-index revision)
                              "/restore")
            response (handler (as (mock/request :post restore-path)
                                  chair-session))]
        (is (= 303 (:status response)))
        (is (= "Rated talk"
               (get-in (store/submission-by-id (:id rated))
                       [:answers :talk-title])))
        (is (= (:log-index revision)
               (get-in (last (filter
                               #(= "submission.answers-updated" (:type %))
                               (store/read-events)))
                       [:payload :restored-from-log-index])))))

    (testing "reviewers and mismatched event URLs cannot mutate content"
      (is (= 403
             (:status
               (handler
                 (as (-> (mock/request :post edit-path)
                         (mock/body
                           {:answer-talk-title "Reviewer overwrite"}))
                     reviewer-session)))))
      (is (= 403
             (:status
               (handler
                 (as (-> (mock/request
                           :post
                           (str "/api/events/wrong-event/submissions/"
                                (:id rated) "/content"))
                         (mock/body
                           {:answer-talk-title "Cross-event overwrite"}))
                     chair-session)))))
      (is (= "Rated talk"
             (get-in (store/submission-by-id (:id rated))
                     [:answers :talk-title]))))))

;; --- Person page pivot ------------------------------------------------------

(deftest person-page-reviews-test
  (let [handler (app)
        {:keys [event gene rated]} (setup!)
        session (login! handler "gene@example.com")
        body (:body (handler (as (mock/request
                                   :get (str "/events/" (:slug event)
                                             "/people/" (:person-id gene)))
                                 session)))]
    (testing "their ratings appear, linked to the talk"
      (is (str/includes? body "Rated talk"))
      (is (str/includes? body (str "/submissions/" (:id rated))))
      (is (str/includes? body "★4.5")))

    (testing "their mean is shown against the committee mean on the same talks"
      (is (str/includes? body "Their mean"))
      (is (str/includes? body "committee mean on the same talks")))

    (testing "their comments appear"
      (is (str/includes? body "Strong opener.")))))

;; --- SSE --------------------------------------------------------------------

(deftest sse-smoke-test
  (let [{:keys [event gene rated]} (setup!)
        received (atom [])
        ;; A stand-in subscriber: the push path only needs something
        ;; patch-elements! can write to, so we record what it would have sent.
        fake-gen (reify Object)]
    (with-redefs [starfederation.datastar.clojure.api/patch-elements!
                  (fn [_gen html _opts] (swap! received conj html) true)]
      (sse/add-subscriber! fake-gen (:id event) (:person-id gene))

      (testing "a rating pushes the affected row AND the coverage bar"
        (review-updates/push-board-updates! event (:id rated))
        (sse/await-pushes!)
        (is (= 2 (count @received)))
        (let [row (first (filter #(str/includes? % (str "sub-" (:id rated))) @received))
              bar (first (filter #(str/includes? % "coverage-bar") @received))]
          (is (some? row) "the submission row was pushed")
          (is (some? bar) "the coverage bar was pushed")
          (is (str/includes? row "Rated talk"))
          (is (str/includes? row "★4.5"))))

      (testing "the pushed row is rendered FOR THE VIEWER — it shows them their own rating"
        (reset! received [])
        (review-updates/push-board-updates! event (:id rated) (:person-id gene))
        (sse/await-pushes!)
        (let [row (first (filter #(str/includes? % (str "sub-" (:id rated))) @received))]
          (is (str/includes? row "star-btn mine")
              "Gene rated this 4.5, so his own button is marked")))

      (testing "post-write rating and comment are both in the immediate projection"
        (reset! received [])
        (reviews/set-rating! (:id rated) (:person-id gene) 5.0 "gene@example.com")
        (reviews/add-comment! (:id rated) (:person-id gene)
                              "Fresh projection comment." "gene@example.com")
        (review-updates/push-board-updates! event (:id rated))
        (sse/await-pushes!)
        (let [row (last (filter #(str/includes? % (str "sub-" (:id rated))) @received))]
          (is (str/includes? row "★5"))
          (is (str/includes? row "Fresh projection comment."))))

      (testing "a subscriber watching a DIFFERENT event hears nothing"
        (reset! received [])
        (sse/remove-subscriber! fake-gen)
        (sse/add-subscriber! fake-gen "some-other-event-id" (:person-id gene))
        (review-updates/push-board-updates! event (:id rated))
        (sse/await-pushes!)
        (is (empty? @received)))

      (testing "subscribers are tracked and released"
        (is (= 1 (sse/subscriber-count)))
        (sse/remove-subscriber! fake-gen)
        (is (zero? (sse/subscriber-count)))))))

(deftest decision-status-is-idempotent-and-truthfully-confirmed-test
  (let [handler (app)
        {:keys [event unrated]} (setup!)
        session (login! handler "gene@example.com")
        path (str "/api/submissions/" (:id unrated) "/status")
        board-path (str "/events/" (:slug event) "/board")
        post-status (fn
                      ([status]
                       (handler (as (mock/request :post path {"status" status}) session)))
                      ([status referer]
                       (handler (as (-> (mock/request :post path {"status" status})
                                        (mock/header "referer" referer))
                                    session))))
        follow-redirect (fn [response]
                          (let [location (get-in response [:headers "Location"])
                                target (first (str/split location #"#" 2))]
                            (:body (handler (as (mock/request :get target) session)))))
        status-facts (fn []
                       (filter #(= "submission.status-changed" (:type %))
                               (store/log-for-event (:id event))))
        before (count (status-facts))]
    (testing "the fast decision control also opens a submission-scoped changes request"
      (let [body (:body
                   (handler
                     (as (mock/request
                           :get
                           (str "/events/" (:slug event) "/submissions/" (:id unrated)))
                         session)))]
        (is (str/includes? body
                           (str "/events/" (:slug event) "/comms?submission-id="
                                (:id unrated) "&amp;template=request-changes")))
        (is (str/includes? body "Ask speaker for changes"))))
    (testing "a real decision appends once and confirms its canonical status"
      (let [response (post-status "accepted")
            body (follow-redirect response)
            detail (:body
                     (handler
                       (as (mock/request
                             :get
                             (str "/events/" (:slug event) "/submissions/" (:id unrated)))
                           session)))]
        (is (= 303 (:status response)))
        (is (= (inc before) (count (status-facts))))
        (is (str/includes? body "Decision saved: Accepted."))
        (is (str/includes? detail "Review decision message"))))
    (testing "repeating the current decision is a truthful no-op"
      (let [response (post-status
                       "Accepted"
                       (str board-path
                            "?decision-result=saved&decision-status=Waitlisted"))]
        (is (= 303 (:status response)))
        (is (= (inc before) (count (status-facts))))
        (is (= 1 (count (re-seq #"decision-result="
                                (get-in response [:headers "Location"])))))
        (is (str/includes? (follow-redirect response)
                           "Decision already Accepted; no new change was recorded."))))
    (testing "an invalid decision remains rejected without a fact"
      (let [response (post-status "Definitely not a status")]
        (is (= 422 (:status response)))
        (is (= (inc before) (count (status-facts))))
        (is (str/includes? (:body response) "allowed"))
        (is (str/includes? (:body response) "Accepted"))))))
