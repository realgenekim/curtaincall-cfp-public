(ns cfp-scheduler-killer.polish-test
  "Live-scrub time travel, clickable public URLs, and the one timestamp format."
  (:require
   [cfp-scheduler-killer.auth :as auth]
   [cfp-scheduler-killer.committees :as committees]
   [cfp-scheduler-killer.events :as events]
   [cfp-scheduler-killer.inform :as inform]
   [cfp-scheduler-killer.review-plan :as review-plan]
   [cfp-scheduler-killer.reviews :as reviews]
   [cfp-scheduler-killer.server :as server]
   [cfp-scheduler-killer.store :as store]
   [cfp-scheduler-killer.submissions :as sub]
   [cfp-scheduler-killer.test-helpers :refer [with-temp-store]]
   [cfp-scheduler-killer.views.avatar :as avatar]
   [cfp-scheduler-killer.views.dashboard :as view-dashboard]
   [cfp-scheduler-killer.views.format :as view-format]
   [cfp-scheduler-killer.views.organizer-layout :as organizer-layout]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [hiccup2.core :as h]
   [ring.mock.request :as mock])
  (:import
   (java.time Instant LocalDateTime)))

(use-fixtures :each with-temp-store (fn [f] (reset! auth/tokens {}) (f)))

(deftest render-markdown-safety-test
  (testing "ordinary inline Markdown survives the sanitizer"
    (let [html (str (h/html (view-format/render-markdown
                              "**Bold** and [safe](https://example.com).")))]
      (is (str/includes? html "<strong>Bold</strong>"))
      (is (str/includes? html "href=\"https://example.com\""))))

  (testing "quote-bearing link syntax cannot inject event attributes"
    (let [html (str (h/html (view-format/render-markdown
                              "[click](https://example.com/' autofocus onfocus='alert(1))")))]
      (is (not (str/includes? html "autofocus")))
      (is (not (str/includes? html "onfocus"))))))

;; --- The one timestamp format -----------------------------------------------

(deftest fmt-when-test
  (let [now (Instant/parse "2026-08-09T15:00:00Z")
        ny "America/New_York"]

    (testing "absolute time, in the EVENT's zone, in English"
      ;; 15:00Z is 11:00 EDT — the whole point of passing the zone.
      (is (= "Jun 1, 11:00 AM" (view-format/fmt-when "2026-06-01T15:00:00Z" ny
                                                     (Instant/parse "2026-09-01T00:00:00Z"))))
      (is (= "Jun 1, 5:00 AM" (view-format/fmt-when "2026-06-01T09:00:00Z" ny
                                                    (Instant/parse "2026-09-01T00:00:00Z")))))

    (testing "a relative hint while it is still recent"
      (is (= "Aug 9, 10:59 AM · just now" (view-format/fmt-when "2026-08-09T14:59:30Z" ny now)))
      (is (= "Aug 9, 10:20 AM · 40m ago" (view-format/fmt-when "2026-08-09T14:20:00Z" ny now)))
      (is (= "Aug 9, 7:15 AM · 3h ago" (view-format/fmt-when "2026-08-09T11:15:00Z" ny now)))
      (is (= "Aug 6, 11:00 AM · 3d ago" (view-format/fmt-when "2026-08-06T15:00:00Z" ny now))))

    (testing "and no hint once it stops being useful"
      (is (= "Jul 1, 11:00 AM" (view-format/fmt-when "2026-07-01T15:00:00Z" ny now))))

    (testing "future timestamps never receive a past-tense relative hint"
      (is (= "Sep 30, 5:00 AM"
             (view-format/fmt-when "2026-09-30T09:00:00Z" ny now)))
      (let [random (java.util.Random. 45)]
        (doseq [future (cons (.plusNanos now 1)
                             (repeatedly 100
                                         #(.plusSeconds now
                                                        (inc (.nextInt random
                                                                       (* 10 365 86400))))))]
          (is (nil? (view-format/relative-when future now))
              (str "future instant must not read as past: " future)))))

    (testing "the zone really is honoured"
      (is (= "Aug 9, 4:00 PM" (view-format/fmt-when "2026-08-09T15:00:00Z" "Europe/London"
                                                    (Instant/parse "2026-09-01T00:00:00Z"))))
      (is (= "Aug 9, 3:00 PM" (view-format/fmt-when "2026-08-09T15:00:00Z" "UTC"
                                                    (Instant/parse "2026-09-01T00:00:00Z")))))

    (testing "an unusable value renders as nothing, never as an exception"
      (is (nil? (view-format/fmt-when nil ny now)))
      (is (nil? (view-format/fmt-when "not-a-time" ny now)))
      (is (nil? (view-format/fmt-when "" ny now))))

    (testing "it accepts an Instant as readily as a string"
      (is (= (view-format/fmt-when "2026-08-09T14:20:00Z" ny now)
             (view-format/fmt-when (Instant/parse "2026-08-09T14:20:00Z") ny now))))

    (testing "an unknown zone falls back to UTC rather than throwing"
      (is (= "Aug 9, 3:00 PM" (view-format/fmt-when "2026-08-09T15:00:00Z" "Mars/Olympus"
                                                    (Instant/parse "2026-09-01T00:00:00Z")))))))

;; --- Fixtures ---------------------------------------------------------------

(defn- setup!
  "An event with two submissions and a rating, so there is a history to scrub."
  []
  (let [event (events/create-event!
                {:name "Polish Summit" :slug "polish" :tz "America/New_York"
                 :support-email "annp@example.com"
                 :starts-on (java.time.LocalDate/of 2026 10 14)
                 :ends-on (java.time.LocalDate/of 2026 10 15)
                 :cfp-opens-at (LocalDateTime/of 2020 1 1 0 0)
                 :cfp-closes-at (LocalDateTime/of 2099 1 1 0 0)
                 :presenter-visibility-mode "visible"}
                "kaocha")
        cid (:id (first (events/committees-for-event (:id event))))
        gene (committees/add-member! cid {:name "Gene Kim" :email "gene@example.com"
                                          :role "chair"} "kaocha")
        reviewer (committees/add-member! cid {:name "Maya Reviewer" :email "maya@example.com"
                                              :role "reviewer"} "kaocha")
        ff (:fields (events/form-for-event (:id event)))
        mk (fn [title email speaker-name speaker-org headshot-url]
             (let [params {:answer-talk-title title :answer-abstract "A."
                           :answer-session-format "Experience Report"
                           :answer-org-size ">10,000" :answer-industry "Insurance"
                           :answer-ai-transformation-history "x"
                           :answer-measurable-outcomes "y"
                           :speaker-name speaker-name :speaker-email email
                           :speaker-title "Temporal VP" :speaker-org speaker-org
                           :speaker-bio "Secret temporal biography"
                           :speaker-headshot-url headshot-url}]
               (sub/create-submission! event (sub/parse-answers ff params)
                                       (sub/parse-speaker params) "form" "kaocha")))
        a (mk "First talk" "a@example.com" "Temporal Alice"
              "Secret Alpha Corp" "https://secret.example/alice.jpg")
        _ (Thread/sleep 5)
        b (mk "Second talk" "b@example.com" "Temporal Bob"
              "Secret Beta Corp" "https://secret.example/bob.jpg")]
    (store/await-sinks!)
    (reviews/set-rating! (:id a) (:person-id gene) 5.0 "gene@example.com")
    {:event (events/event-by-slug "polish") :a a :b b :gene gene :reviewer reviewer}))

(defn- signed-in-handler []
  (let [handler (server/create-app)
        token (auth/issue-token! "gene@example.com")
        r (handler (mock/request :get (str "/auth/" token)))
        cookie (first (str/split (first (get-in r [:headers "Set-Cookie"])) #";"))]
    (fn [req] (handler (mock/header req "cookie" cookie)))))

(defn- fragment-html
  "The HTML a scrub tick would patch.

   Rendered through the handler's own function rather than the SSE socket: a
   one-shot Datastar response needs a live http-kit channel, which ring-mock
   does not provide. WHAT gets patched is the part worth asserting."
  [f slug at-index]
  (let [event (events/event-by-slug slug)
        req (cond-> {:params {} :path-params {:slug slug}}
              at-index (assoc :params {:at-index (str at-index)}))]
    (f req event)))

;; --- Live scrub -------------------------------------------------------------

(deftest scrub-slider-wiring-test
  ;; The scrubber rides the DEV STRIP (Gene, 2026-08-09), which renders only
  ;; under ENV=dev — so the wiring is asserted on the COMPONENT, and the page
  ;; is asserted to carry no slider inside the patchable region.
  (let [{:keys [event]} (setup!)
        bar (str (h/html
                   (organizer-layout/time-travel-bar
                     event
                     {:bounds ["2026-08-01T00:00:00Z" "2026-08-09T00:00:00Z"]
                      :index 3 :total 10
                      :base-path "/events/polish/board"
                      :fragment-path "/events/polish/board/fragment"})))
        handler (signed-in-handler)
        body (:body (handler (mock/request :get "/events/polish/board")))]

    (testing "the slider scrubs live WHILE dragging — throttled, not debounced"
      (is (str/includes? bar "data-star-on:input__throttle.150ms"))
      (is (str/includes? bar "/events/polish/board/fragment?at-index="))
      (is (str/includes? bar "data-star-bind:atidx")))

    (testing "releasing the handle does NOT reload the page — the scrub is
              pure SSE (Gene, 2026-08-09); the form is only the no-JS fallback"
      (is (not (str/includes? bar "onchange")))
      (is (str/includes? bar "action=\"/events/polish/board\"")))

    (testing "the signal is a single word — Datastar camelCases hyphens"
      (is (not (str/includes? bar "$at-index")))
      (is (str/includes? bar "$atidx")))

    (testing "the patchable region exists and carries NO slider — outside
              ENV=dev the board has no time control at all"
      (is (str/includes? body "id=\"board-region\""))
      (is (not (str/includes? body "type=\"range\""))))

    (is (some? event))))

(deftest dev-strip-never-overlays-controls-or-switches-persona-test
  (let [strip (with-redefs-fn {#'organizer-layout/dev-mode? (constantly true)}
                #(str (h/html (#'organizer-layout/dev-strip
                                {:event {:id "dev-strip-test"}}))))
        css (slurp (io/resource "public/css/app.css"))]
    (testing "identity switching exists only at the sandbox login door"
      (is (str/includes? strip "DEV"))
      (is (not (str/includes? strip "Review as")))
      (is (not (str/includes? strip "/api/demo-login")))
      (is (not (str/includes? strip "sb-dev-switch"))))

    (testing "the diagnostic strip participates in layout instead of covering it"
      (is (re-find #"(?s)\.dev-strip\s*\{[^}]*position:\s*static" css))
      (is (not (re-find #"(?s)\.dev-strip\s*\{[^}]*position:\s*fixed" css)))
      (is (not (str/includes? css "body:has(.dev-strip"))))))

(deftest dashboard-live-scrub-contract-test
  (let [{:keys [event]} (setup!)
        handler (signed-in-handler)]
    (testing "the page boundary fails loudly instead of silently hiding the scrubber"
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #"requires a complete :time-travel contract"
            (view-dashboard/event-dashboard-page "localhost" event {}))))

    (testing "the dashboard handler cannot silently omit its time-travel contract"
      (let [seen (atom nil)]
        (with-redefs [view-dashboard/event-dashboard-page
                      (fn [_host _event opts]
                        (reset! seen opts)
                        "dashboard")]
          (handler (mock/request :get "/events/polish")))
        (is (= "/events/polish" (get-in @seen [:time-travel :base-path])))
        (is (= "/events/polish/fragment"
               (get-in @seen [:time-travel :fragment-path])))))

    (testing "the dashboard loads one-shot Datastar without mounting persistent SSE"
      (let [body (:body (handler (mock/request :get "/events/polish")))]
        (is (str/includes? body "id=\"dashboard-region\""))
        (is (str/includes? body "datastar-aliased.js"))
        (is (not (str/includes? body "/api/sse?event-id=")))))

    (testing "the fragment patches only the dashboard region, never the scrubber"
      (let [html (fragment-html server/dashboard-fragment-html "polish" 2)]
        (is (str/starts-with? html "<div id=\"dashboard-region\""))
        (is (not (str/includes? html "type=\"range\"")))
        (is (not (str/includes? html "data-star-on:input")))))

    (testing "the dashboard fragment route resolves and an unknown event 404s"
      (is (not= 404 (:status (handler (mock/request
                                        :get "/events/polish/fragment?at-index=2")))))
      (is (= 404 (:status (handler (mock/request :get "/events/nope/fragment"))))))

    (is (some? event))))

(deftest dashboard-recent-age-follows-time-travel-clock-test
  (let [entry {:at "2026-08-09T14:58:00Z"}]
    (binding [store/*as-of-cutoff* "2026-08-09T15:00:00Z"]
      (is (= "2m" (#'view-dashboard/dash-rel-time entry))
          "historical activity age is relative to the selected frame"))))

(deftest archived-event-pages-carry-a-living-event-warning-test
  (let [{:keys [event gene]} (setup!)
        person (store/person-by-id (:person-id gene))
        living (events/create-event!
                 {:name "Living Summit" :slug "living" :tz "America/New_York"
                  :support-email "gene@example.com"}
                 "kaocha")
        committee-id (:id (first (events/committees-for-event (:id living))))
        _ (committees/add-member! committee-id
                                  {:name (:name gene) :email (:email gene) :role "chair"}
                                  "kaocha")
        _ (events/archive-event! event "kaocha")
        archived (events/event-by-slug "polish")
        html (organizer-layout/organizer-shell
               "Archived event"
               {:event archived :person person :active :dashboard}
               [:p "Recorded history"])]
    (is (str/includes? html "This event was archived"))
    (is (str/includes? html "You may be looking for"))
    (is (str/includes? html "Living Summit"))
    (is (str/includes? html "href=\"/events/living\""))
    (is (not (str/includes? html "href=\"/events/living/form\""))
        "a freshly created event already has a form, so resume at its dashboard")))

(deftest board-fragment-test
  (let [{:keys [event a b gene reviewer]} (setup!)
        handler (signed-in-handler)
        entries (store/log-for-event (:id event))
        ;; index of the event that created the SECOND talk
        idx-of-b (count (take-while #(not= (:id b) (get-in % [:payload :id])) entries))]

    (testing "the fragment renders the board region as of that moment"
      (let [html (fragment-html server/board-fragment-html "polish" (dec idx-of-b))]
        (is (str/includes? html "First talk"))
        (is (not (str/includes? html "Second talk"))
            "the second talk had not been submitted yet")))

    (testing "the fragment does NOT contain the slider — dragging must not be interrupted"
      (let [html (fragment-html server/board-fragment-html "polish" 2)]
        (is (not (str/includes? html "type=\"range\"")))
        ;; LOOSENED 2026-08-16, following the ratified Review Board rebuild.
        ;; This was a blanket `no "data-star-on:input"`, which is now too wide:
        ;; the ratified 50ms server-owned filter lives inside the region (see
        ;; the next block). The SAFETY PROPERTY it guarded is narrower and
        ;; still holds EXACTLY — the scrub slider's own THROTTLED @get handler
        ;; (ds/live-scrub -> data-star-on:input__throttle.150ms) must never
        ;; enter the region it patches, because morphing the element you are
        ;; mid-drag on cancels the gesture. See the "PURE SSE SCRUB" note in
        ;; views/organizer-layout.
        (is (not (str/includes? html "data-star-on:input__throttle")))
        (is (not (str/includes? html "Viewing as of")))
        (is (not (str/includes? html "Return to now")))))

    ;; TODO(morph-safety — review-board rebuild 2026-08-16, Mayor-swept,
    ;; pending GENEDEV ratification): the review-board filter input now sits
    ;; INSIDE its own morph target. Typing fires @post .../board/sort, whose
    ;; response morphs #board-region — the element being typed into. It works
    ;; today only because (a) the input carries a stable id, so idiomorph keeps
    ;; the node instead of replacing it, and Datastar beta.11 defaults
    ;; restoreFocus:true, so focus and selection survive; and (b)
    ;; handlers.board/handle-board-sort pushes with sse/push-to-person!, so no
    ;; OTHER committee member's action can repaint your filter (row pushes
    ;; target #sub-<id> and #coverage-bar, both strictly inside the region).
    ;; BUT Datastar calls Idiomorph.morph with NO options, so ignoreActiveValue
    ;; is falsy and the FOCUSED input's value IS overwritten from the server
    ;; echo: a character typed inside the 50ms debounce + round-trip window can
    ;; be silently reverted. That is latency-dependent — invisible locally,
    ;; real on Cloud Run. The durable fix is to move the filter OUTSIDE
    ;; #board-region, exactly as the scrubber already is. Do not weaken this
    ;; test further; fix the layout instead.
    (testing "exactly one live input handler rides inside the morphed region — the ratified 50ms filter"
      (let [html (fragment-html server/board-fragment-html "polish" 2)]
        (is (= 1 (count (re-seq #"data-star-on:input" html)))
            "a second live input handler inside the morph target is a keystroke-eating regression")
        (is (str/includes? html "data-star-on:input__debounce.50ms"))
        (is (str/includes? html "board/sort")
            "the filter is server-owned: it POSTs and the server re-renders")
        (is (not (str/includes? html ">Search</button>"))
            "the live filter needs no redundant submit button")
        (is (str/includes? html "id=\"review-board-filter\"")
            "a stable id is what lets idiomorph keep the node and restore focus")
        (is (< (str/index-of html "id=\"review-board-filter\"")
               (str/index-of html "Status:"))
            "Filter sits immediately before the Status and Track facets")))

    (testing "it IS the region, so the patch lands on the right element"
      (let [html (fragment-html server/board-fragment-html "polish" 2)]
        ;; Attribute ORDER is hiccup's business, not the contract's — the
        ;; region now also carries data-star-signals__ifmissing, which sorts
        ;; ahead of id. Assert the opening tag's IDENTITY instead (2026-08-16).
        (is (some? (re-find #"\A<div [^>]*id=\"board-region\"" html)))
        ;; Still a fragment, not a page: a fragment carrying the shell would
        ;; re-inject Datastar on every patch.
        (is (not (str/includes? html "datastar-aliased.js")))
        (is (not (str/includes? html "<script")))))

    (testing "with no index it renders NOW"
      (let [html (fragment-html server/board-fragment-html "polish" nil)]
        (is (str/includes? html "First talk"))
        (is (str/includes? html "Second talk"))))

    (testing "every historical index obeys the current blind policy"
      (review-plan/set-presenter-visibility!
        (:id event) "hidden" 0 (:person-id gene) "gene@example.com")
      (let [current-event (events/event-by-slug "polish")
            total (count (store/log-for-event (:id current-event)))
            forbidden ["Temporal Alice" "Temporal Bob"
                       "Secret Alpha Corp" "Secret Beta Corp" "Temporal VP"
                       "a@example.com" "b@example.com" "Secret temporal biography"
                       "https://secret.example/alice.jpg"
                       "https://secret.example/bob.jpg"]]
        (with-redefs [avatar/pool-face
                      (fn [& _]
                        (throw (ex-info "generated face entered blind render" {})))]
          (doseq [i (range total)]
            (let [html (fragment-html server/board-fragment-html "polish" i)]
              (doseq [token forbidden]
                (is (not (str/includes? html token))
                    (str "identity token leaked at scrub index " i ": " token)))
              (is (not (str/includes? html "<img class=\"b-face\""))
                  (str "speaker image leaked at scrub index " i)))))))

    (testing "an old scrub position uses the reviewer's current reveal entitlement"
      (review-plan/set-presenter-visibility!
        (:id event) "reveal-after-vote" 1 (:person-id gene) "gene@example.com")
      (reviews/set-rating! (:id a) (:person-id reviewer) 4.0 "maya@example.com")
      (let [reviewer-person {:id (:person-id reviewer)
                             :name (:name reviewer)
                             :email (:email reviewer)}
            html (with-redefs [auth/current-person (constantly reviewer-person)]
                   (fragment-html server/board-fragment-html "polish" (dec idx-of-b)))]
        (is (str/includes? html "Temporal Alice")
            "the reviewer rated this submission now, even though the old row predates the rating")
        (is (not (str/includes? html "Temporal Bob"))
            "the second submission did not exist at this historical position")))

    ;; The SSE UPGRADE itself is not assertable here: ->sse-response needs a live
    ;; http-kit async channel, which ring-mock does not provide. What the mock
    ;; CAN prove is that the route resolves to the fragment handler rather than
    ;; falling through to a 404 — the stream itself is verified against a real
    ;; server.
    (testing "the route resolves to the fragment handler"
      (is (not= 404 (:status (handler (mock/request
                                        :get "/events/polish/board/fragment?at-index=2"))))))

    (testing "the log page scrubs the same way"
      (let [page (:body (handler (mock/request :get "/events/polish/log")))]
        (is (str/includes? page "id=\"log-region\""))
        (is (str/includes? page "/events/polish/log/fragment?at-index=")))
      (let [html (fragment-html server/log-fragment-html "polish" 1)]
        (is (str/starts-with? html "<div id=\"log-region\""))
        (is (not (str/includes? html "type=\"range\"")))))

    (testing "an unknown event 404s rather than streaming nothing"
      (is (= 404 (:status (handler (mock/request :get "/events/nope/board/fragment"))))))))

(deftest scrubbing-cannot-mutate-test
  (let [{:keys [event a]} (setup!)
        handler (signed-in-handler)]
    (testing "scrubbing is a read — the world does not move"
      (let [before (count (store/read-events))]
        (dotimes [i 5]
          (handler (mock/request :get (str "/events/polish/board/fragment?at-index=" i))))
        (is (= before (count (store/read-events))))
        (is (= 2 (count (store/submissions-for-event (:id event)))))))

    (testing "and the write guard still refuses a write from inside the past"
      (binding [store/*as-of-state* (store/state-as-of "1970-01-01T00:00:00Z")]
        (is (= :read-only-as-of
               (:type (ex-data (try (store/append! {:type "x" :payload {}})
                                    nil
                                    (catch clojure.lang.ExceptionInfo e e))))))))
    (is (some? a))))

(deftest as-of-cache-test
  (let [{:keys [event]} (setup!)
        cutoff (:at (first (store/log-for-event (:id event))))]
    (testing "the same moment folds to the same state, cached or not"
      (let [a (store/state-as-of cutoff)
            b (store/state-as-of cutoff)]
        (is (= a b))))

    (testing "an append invalidates it — a stale past is worse than a slow one"
      (let [before (store/state-as-of cutoff)]
        (events/create-event! {:name "Another" :slug "another" :tz "UTC"} "kaocha")
        (let [after (store/state-as-of cutoff)]
          ;; the world AT that cutoff is unchanged…
          (is (= (:events before) (:events after))))))))

;; --- Clickable public URLs --------------------------------------------------

(deftest public-urls-are-links-test
  (let [{:keys [event]} (setup!)
        handler (signed-in-handler)
        body (:body (handler (mock/request :get "/events/polish")))]

    (testing "the CFP URL an organizer hands to speakers is clickable"
      (is (str/includes? body "<a class=\"cfp-url\" href=\"/cfp/polish\""))
      (is (str/includes? body "target=\"_blank\""))
      (is (str/includes? body "rel=\"noopener\"")))

    (testing "it still READS as an address"
      (is (str/includes? body "/cfp/polish</a>")))

    (testing "the agenda is reachable from the schedule page too"
      (let [sched (:body (handler (mock/request :get "/events/polish/schedule")))]
        (is (str/includes? sched "href=\"/agenda/polish\""))))

    (is (some? event))))

;; --- Comms table ------------------------------------------------------------

(deftest comms-table-test
  (let [{:keys [event a]} (setup!)
        handler (signed-in-handler)]
    (reviews/set-status! (:id a) "Accepted" "gene@example.com")
    (inform/inform! event (store/submission-by-id (:id a)) "gene@example.com")
    (store/await-sinks!)

    ;; 98e52b1 (Gene-accepted 2026-08-17): the fixed-column send-history
    ;; table is RETIRED — Comms renders per-recipient summaries with a
    ;; person-detail fragment. The surviving intents (humanized timestamps,
    ;; honest status copy) now bind to the recipient fragment.
    (let [body (:body (handler (mock/request :get "/events/polish/comms")))]
      (testing "the retired comms table is deliberately absent"
        (is (not (str/includes? body "comms-table")))
        (is (str/includes? body "message")))

      (testing "timestamps are English in the event's zone, not raw ISO"
        (is (re-find #"[A-Z][a-z]{2} \d{1,2}, \d{1,2}:\d{2} (AM|PM)" body))
        (is (not (re-find #"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}" body))
            "no raw ISO instant should reach the page"))

      (testing "the status label says what actually happened"
        (is (str/includes? body ">queued<"))
        (is (str/includes? body "Nothing on this page sends email."))
        (is (not (str/includes? body ">sent<")))))

    (testing "the Log page humanises its timestamps too"
      (let [body (:body (handler (mock/request :get "/events/polish/log")))]
        (is (re-find #"[A-Z][a-z]{2} \d{1,2}, \d{1,2}:\d{2} (AM|PM)" body))
        (is (not (re-find #"<div class=\"log-when\">\d{4}-\d{2}-\d{2}T" body)))))

    (testing "and so does the person page"
      (let [pid (:person-id (first (committees/members-for-committee
                                     (:id (first (events/committees-for-event (:id event)))))))
            body (:body (handler (mock/request :get (str "/events/polish/people/" pid))))]
        (is (str/includes? body "member since"))
        (is (re-find #"member since [A-Z][a-z]{2} \d{1,2}, \d{1,2}:\d{2} (AM|PM)" body))))))
