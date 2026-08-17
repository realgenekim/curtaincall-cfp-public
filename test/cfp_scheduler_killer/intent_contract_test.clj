(ns cfp-scheduler-killer.intent-contract-test
  "LINKED INTENT DEVELOPMENT — the contract that turns a silent intent
   regression into a NAMED, mechanical CI red.

   Two witnesses, deliberately independent:

   (a) TRACEABILITY. docs/intent/registry.edn is the append-only index of the
       product's load-bearing intent (EARS one-liners with forever-stable IDs).
       Every :active id must be tagged in BOTH code (\";; INTENT: <ID>\") and a
       test (\";; INTENT-TEST: <ID>\"). A tag naming an id the registry does not
       know also fails. So intent can never rot silently: drop the affordance,
       drop the tag, or drop the registry row and this test goes red.

   (b) BEHAVIOR. The actual rendered app must SATISFY each active intent — the
       organizer sidebar hrefs render AND the routes answer 200; the review-board
       redaction actually holds. The expected values here are a HAND-WRITTEN
       oracle (nav-oracle + the literal secret strings below) — never derived
       from the runtime code they check, so check and test stay separate
       witnesses and the assertion can never go tautological.

   Origin: Jess's LID talk (Aug-12 AI Forum) + bd sessionize-sched-killer-cbgk.
   The app already hand-rolls LID in domain/review_plan.clj
   (presenter-visibility-policy-definition = one data source → enforcement AND
   explanation); this generalizes the pattern into a checkable index."
  (:require
   [cfp-scheduler-killer.auth :as auth]
   [cfp-scheduler-killer.committees :as committees]
   [cfp-scheduler-killer.events :as events]
   [cfp-scheduler-killer.server :as server]
   [cfp-scheduler-killer.submissions :as submissions]
   [cfp-scheduler-killer.test-helpers :refer [with-temp-store]]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [ring.mock.request :as mock]))

(use-fixtures :each with-temp-store)

;; ---------------------------------------------------------------------------
;; The registry
;; ---------------------------------------------------------------------------

(def ^:private registry-path "docs/intent/registry.edn")

(defn- load-registry []
  (edn/read-string (slurp (io/file registry-path))))

(defn- all-intents [] (:intents (load-registry)))
(defn- active-intents [] (filter #(= :active (:status %)) (all-intents)))
(defn- intent-id-str [intent] (name (:id intent)))

;; ---------------------------------------------------------------------------
;; (a) Traceability — grep the tree for intent tags
;; ---------------------------------------------------------------------------
;;
;; Code tags look like  ";; INTENT: NAV-003"   (harvested from src/**)
;; Test tags look like  ";; INTENT-TEST: NAV-003"  (harvested from test/**)
;; The two regexes are disjoint: "INTENT:" requires a colon immediately after
;; INTENT, so it can never match "INTENT-TEST:" (a hyphen follows INTENT there).

(def ^:private code-tag-re #"(?m);;\s*INTENT:\s*([A-Za-z][A-Za-z0-9_-]*)")
(def ^:private test-tag-re #"(?m);;\s*INTENT-TEST:\s*([A-Za-z][A-Za-z0-9_-]*)")

(defn- clj-source? [^java.io.File f]
  (and (.isFile f) (boolean (re-find #"\.clj[cs]?$" (.getName f)))))

(defn- harvest
  "Every id named by `re` across all Clojure source files under `dir`."
  [dir re]
  (into #{}
        (comp (filter clj-source?)
              (map slurp)
              (mapcat #(map second (re-seq re %))))
        (file-seq (io/file dir))))

(deftest every-active-intent-is-traceable
  ;; INTENT-TEST: NAV-003
  ;; INTENT-TEST: NAV-004
  ;; INTENT-TEST: NAV-005
  ;; INTENT-TEST: REV-VIS-001
  (let [known-ids  (into #{} (map intent-id-str) (all-intents))
        active-ids (into #{} (map intent-id-str) (active-intents))
        code-tags  (harvest "src" code-tag-re)
        test-tags  (harvest "test" test-tag-re)]
    (testing "every active intent id is tagged in CODE"
      (doseq [id (sort active-ids)]
        (is (contains? code-tags id)
            (str id ": active intent has no \";; INTENT: " id
                 "\" tag anywhere in src/ — the code that satisfies it is "
                 "untraceable (this is exactly how the lost-nav regression hid)."))))
    (testing "every active intent id is tagged in a TEST"
      (doseq [id (sort active-ids)]
        (is (contains? test-tags id)
            (str id ": active intent has no \";; INTENT-TEST: " id
                 "\" tag anywhere in test/ — nothing witnesses it."))))
    (testing "no tag names an unknown intent id (typo / stale reference)"
      (doseq [id (sort (remove known-ids code-tags))]
        (is false
            (str "code tag \";; INTENT: " id "\" names an id absent from "
                 registry-path " — register it or fix the typo.")))
      (doseq [id (sort (remove known-ids test-tags))]
        (is false
            (str "test tag \";; INTENT-TEST: " id "\" names an id absent from "
                 registry-path " — register it or fix the typo."))))))

;; ---------------------------------------------------------------------------
;; Shared HTTP fixtures for the behavioral witnesses
;; ---------------------------------------------------------------------------

(defn- person-app
  "A ring handler that carries `email`'s session on every request."
  [email]
  (let [raw    (server/create-app)
        token  (auth/issue-token! email)
        cookie (-> (raw (mock/request :get (str "/auth/" token)))
                   (get-in [:headers "Set-Cookie"])
                   first
                   (str/split #";")
                   first)]
    (fn [req] (raw (mock/header req "cookie" cookie)))))

(defn- make-event! [handler slug name]
  (handler (mock/request :post "/api/events/create"
                         {"name"          name
                          "slug"          slug
                          "tz"            "America/New_York"
                          "cfp-opens-at"  "2020-01-01T00:00"
                          "cfp-closes-at" "2099-01-01T00:00"
                          "presenter-visibility-mode" "visible"}))
  (events/event-by-slug slug))

;; ---------------------------------------------------------------------------
;; (b) Behavior — NAV-003/004/005: the sidebar affordance must render AND resolve
;; ---------------------------------------------------------------------------
;;
;; nav-oracle is the INDEPENDENT witness: these literal path templates are
;; hand-written here, NOT read from the registry EARS and NOT computed by the
;; sidebar code under test. If organizer_layout.clj drops a link (the lost-nav
;; refactor) OR mislinks it, this disagrees and fails, naming the intent id.

(def ^:private nav-oracle
  {:NAV-003 "/events/%s/speakers?view=manage"
   :NAV-006 "/people"
   :NAV-004 "/events/%s/deliverables"
   :NAV-005 "/events/%s/files"})

;; INTENT-TEST: NAV-003
;; INTENT-TEST: NAV-004
;; INTENT-TEST: NAV-005
(deftest nav-sidebar-affordances-render-and-resolve
  (let [app   (person-app "organizer@example.com")
        slug  (str "intent-nav-" (events/random-suffix 8))
        _     (make-event! app slug "Intent Nav Summit")
        dash  (app (mock/request :get (str "/events/" slug)))
        body  (:body dash)]
    (is (= 200 (:status dash)) "the organizer dashboard must render")
    (doseq [intent (->> (active-intents)
                        (filter #(str/starts-with? (intent-id-str %) "NAV-"))
                        (sort-by intent-id-str))]
      (let [id   (intent-id-str intent)
            tmpl (get nav-oracle (:id intent))]
        (is (some? tmpl)
            (str id ": active NAV intent has no hand-written oracle href — "
                 "add it to nav-oracle so the witness stays honest."))
        (when tmpl
          (let [href (format tmpl slug)]
            (is (str/includes? body
                               (str "href=\"" (str/replace href "&" "&amp;") "\""))
                (str id ": expected href=\"" href "\" in the organizer sidebar, "
                     "but it was absent — the route survives yet the affordance "
                     "was dropped (the lost-nav regression)."))
            (is (= 200 (:status (app (mock/request :get href))))
                (str id ": GET " href " must return 200 (the link must actually resolve)."))))))))

;; ---------------------------------------------------------------------------
;; (b) Behavior — REV-VIS-001: reveal-after-vote redaction must actually hold
;; ---------------------------------------------------------------------------
;;
;; Independent oracle: distinctive secret strings are planted in the submission
;; and asserted ABSENT from the reviewer's rendered board. Non-vacuity is proven
;; by the chair-exemption witness — the chair's detail page DOES contain the
;; name, so the data really carries it and redaction is doing real work.

(def ^:private secret-name  "Zelda Confidential")
(def ^:private secret-org   "Undisclosed Labs")
(def ^:private secret-email "zelda@undisclosed.example")

;; INTENT-TEST: REV-VIS-001
(deftest rev-vis-001-redaction-holds-before-vote
  (let [chair-app    (person-app "organizer@example.com")
        slug         (str "intent-rev-" (events/random-suffix 8))
        event        (make-event! chair-app slug "Intent Review Summit")
        committee-id (:id (first (events/committees-for-event (:id event))))
        _reviewer    (committees/add-member!
                      committee-id
                      {:name "Sam Reviewer" :email "reviewer@example.com" :role "reviewer"}
                      "organizer@example.com")
        fields       (:fields (events/form-for-event (:id event)))
        params       {:answer-talk-title     "Redaction under test"
                      :answer-abstract       "Identity must stay hidden until the reviewer rates."
                      :answer-session-format "Experience Report"
                      :answer-org-size       ">10,000"
                      :answer-industry       "Technology"
                      :answer-ai-transformation-history "2026."
                      :answer-measurable-outcomes "Safer review."
                      :answer-notes-to-committee "Private note."
                      :speaker-name  secret-name
                      :speaker-email secret-email
                      :speaker-title "VP"
                      :speaker-org   secret-org
                      :speaker-bio   "Secret biography"}
        submission   (submissions/create-submission!
                      event
                      (submissions/parse-answers fields params)
                      (submissions/parse-speaker params)
                      "form" "intent-test")
        ;; Flip the event to reveal-after-vote through the REAL handler.
        _            (chair-app (mock/request
                                 :post
                                 (str "/api/events/" slug "/review-plan/presenter-visibility")
                                 {"mode" "reveal-after-vote" "expected-version" "0"}))
        reviewer-app (person-app "reviewer@example.com")
        board        (reviewer-app (mock/request :get (str "/events/" slug "/board")))
        board-body   (:body board)
        chair-detail (chair-app (mock/request
                                 :get
                                 (str "/events/" slug "/submissions/" (:id submission))))]
    (testing "the reviewer has not yet rated — presenter identity is redacted"
      (is (= 200 (:status board)) "reviewer board must render")
      (is (str/includes? board-body "Anonymous speaker")
          "REV-VIS-001: the reviewer board must show the anonymous projection.")
      (doseq [secret [secret-name secret-org secret-email]]
        (is (not (str/includes? board-body secret))
            (str "REV-VIS-001: presenter identity <" secret "> was disclosed to a "
                 "reviewer BEFORE they voted, under mode=reveal-after-vote."))))
    (testing "chair-exemption witness — the identity IS in the data (redaction is not vacuous)"
      (is (str/includes? (:body chair-detail) secret-name)
          (str "REV-VIS-001 witness: the chair must see the real presenter name <"
               secret-name ">. If this fails, the redaction assertion above is "
               "vacuously true and proves nothing.")))))

;; ---------------------------------------------------------------------------
;; (b) Behavior — EMB-001: one Speakers product, compatibility URL preserved
;; ---------------------------------------------------------------------------

;; INTENT-TEST: EMB-001
(deftest emb-001-public-routes-render-unified-speakers
  (let [organizer (person-app "organizer@example.com")
        slug      (str "intent-emb-" (events/random-suffix 8))
        _         (make-event! organizer slug "Intent Embed Summit")
        expected  [["sessions" "Sessions"]
                   ["speakers" "Speakers"]
                   ["gallery" "Speakers"]]
        responses (mapv (fn [[suffix heading]]
                          [suffix heading
                           (organizer
                            (mock/request :get (str "/agenda/" slug "/" suffix)))])
                        expected)
        bodies    (mapv (comp :body #(nth % 2)) responses)]
    (doseq [[suffix heading response] responses]
      (is (= 200 (:status response))
          (str "EMB-001: /agenda/{slug}/" suffix " must resolve publicly."))
      (is (str/includes? (:body response) heading)
          (str "EMB-001: /agenda/{slug}/" suffix
               " must render the " heading " surface.")))
    (is (not= (first bodies) (second bodies))
        "EMB-001: sessions and speakers remain distinct products.")
    (doseq [body [(second bodies) (nth bodies 2)]]
      (is (str/includes? body "aria-label=\"Filter speakers\"")
          "EMB-001: both Speakers entrances render the shared Speakers filter.")
      (is (str/includes? body (str "/agenda/" slug "/speakers/filter"))
          "EMB-001: both Speakers entrances use the shared live-filter endpoint.")
      (is (not (str/includes? body "Speaker Gallery"))
          "EMB-001: the retired Gallery product name cannot leak through either entrance."))))

;; INTENT-TEST: EMB-002
(deftest emb-002-public-surfaces-render-inline-html
  (let [organizer (person-app "organizer@example.com")
        slug      (str "intent-emb-inline-" (events/random-suffix 8))
        _         (make-event! organizer slug "Intent Inline Embed Summit")]
    (doseq [suffix ["sessions" "speakers" "gallery"]]
      (let [response (organizer
                      (mock/request :get (str "/agenda/" slug "/" suffix)))
            headers  (:headers response)]
        (is (= 200 (:status response))
            (str "EMB-002: /agenda/{slug}/" suffix " must render, not redirect."))
        (is (= "text/html; charset=utf-8" (get headers "Content-Type"))
            (str "EMB-002: /agenda/{slug}/" suffix " must be inline HTML."))
        (is (nil? (get headers "Content-Disposition"))
            (str "EMB-002: /agenda/{slug}/" suffix " must not be a download."))))))
