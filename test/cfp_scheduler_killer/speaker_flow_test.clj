(ns cfp-scheduler-killer.speaker-flow-test
  "The speaker side's live lane: anonymous viewer identity, the session-bound
   draft stash, and the per-viewer feedback the server pushes.

   Its own namespace rather than an addition to server-test because the property
   under test is not 'the route resolves' — it is 'two strangers typing at the
   same time never see each other', which is a statement about KEYS. Every test
   here therefore drives at least two viewers.

   What these tests CANNOT prove is that a fragment reached a browser; a push to
   a key nobody registered under is indistinguishable from a working one from
   the caller's side. That claim belongs to `bin/cfp_draft_probe.sh`, which
   holds two real SSE streams open. Green here plus green there is the pair."
  (:require
   [cfp-scheduler-killer.auth :as auth]
   [cfp-scheduler-killer.events :as events]
   [cfp-scheduler-killer.forms :as forms]
   [cfp-scheduler-killer.handlers.public-cfp :as public-cfp-handlers]
   [cfp-scheduler-killer.live-validation :as live-validation]
   [cfp-scheduler-killer.server :as server]
   [cfp-scheduler-killer.sse :as sse]
   [cfp-scheduler-killer.submissions :as submissions]
   [cfp-scheduler-killer.test-helpers :refer [with-temp-store]]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [datastar-live.core :as live]
   [ring.mock.request :as mock]))

(use-fixtures :each with-temp-store)

(defn- make-event!
  "An organizer creates a conference with an open call, and we get its slug."
  []
  (let [slug (str "speaker-flow-" (events/random-suffix 10))
        raw (server/create-app)
        token (auth/issue-token! "organizer@example.com")
        cookie (-> (raw (mock/request :get (str "/auth/" token)))
                   (get-in [:headers "Set-Cookie"]) first (str/split #";") first)]
    (raw (-> (mock/request :post "/api/events/create"
                           {"name" "Speaker Flow Summit" "slug" slug
                            "tz" "America/New_York"
                            "starts-on" "2027-04-01" "ends-on" "2027-04-02"
                            "presenter-visibility-mode" "visible"})
             (mock/header "cookie" cookie)))
    slug))

(defn- session-cookie [resp]
  (some-> (get-in resp [:headers "Set-Cookie"]) first (str/split #";") first))

(defn- fields-of [slug]
  (forms/active-fields (:fields (events/form-for-event (:id (events/event-by-slug slug))))))

;; --- The identity a speaker has before they have an account -----------------

(deftest anonymous-viewers-are-told-apart-test
  (let [slug (make-event!)
        app (server/create-app)
        a (app (mock/request :get (str "/cfp/" slug)))
        b (app (mock/request :get (str "/cfp/" slug)))]
    (testing "every anonymous visitor leaves with a session of their own"
      (is (= 200 (:status a)))
      (is (some? (session-cookie a)))
      (is (some? (session-cookie b)))
      (is (not= (session-cookie a) (session-cookie b))
          "two strangers sharing one session is how drafts cross"))

    (testing "the page opens a PUBLIC stream, not the organizer-only one"
      (is (str/includes? (:body a) (str "/api/cfp/" slug "/stream")))
      (is (not (str/includes? (:body a) "/api/sse?event-id="))
          "/api/sse is default-denied to a stranger — mounting it is a dead stream"))

    (testing "and it mounts with the only attribute this build understands"
      (is (str/includes? (:body a) "data-star-init")))))

(deftest viewer-key-prefers-the-person-test
  (testing "a signed-in person still keys on their person-id (nothing changed)"
    (is (= "p-1" (sse/viewer-key {:session {:person-id "p-1" :viewer-id "v-9"}}))))
  (testing "an anonymous session falls back to its viewer-id"
    (is (= "v-9" (sse/viewer-key {:session {:viewer-id "v-9"}}))))
  (testing "and a request with neither is honestly nobody"
    (is (nil? (sse/viewer-key {:session {}})))))

;; --- What the server says about what is being typed -------------------------

(deftest live-notes-speak-only-when-there-is-something-to-say-test
  (let [slug (make-event!)
        fields (fields-of slug)]
    (testing "an empty form says nothing at all"
      (is (empty? (live-validation/cfp-live-notes fields {}))))

    (testing "a normal answer says nothing either — mid-sentence is not an error"
      (is (empty? (live-validation/cfp-live-notes fields {:answer-talk-title "A good title"}))))

    (testing "past the cap it names the count the speaker actually typed"
      (let [note (get (live-validation/cfp-live-notes fields {:answer-talk-title (apply str (repeat 400 "x"))})
                      :answer-talk-title)]
        (is (= :warn (:level note)))
        (is (str/includes? (:text note) "400 characters"))))

    (testing "a link that is not a link is called out, on both kinds of field"
      (is (= :warn (:level (get (live-validation/cfp-live-notes fields {:answer-prior-talk-video "nope"})
                                :answer-prior-talk-video))))
      (is (= :warn (:level (get (live-validation/cfp-live-notes fields {:speaker-linkedin "nope"})
                                :speaker-linkedin)))))

    (testing "a real link is silent"
      (is (empty? (live-validation/cfp-live-notes fields {:speaker-linkedin "https://example.com/x"}))))

    (testing "progress counts answered questions, not typed characters"
      (let [p (public-cfp-handlers/cfp-progress fields {:answer-talk-title "x" :answer-abstract "  "})]
        (is (= 1 (:answered p)))
        (is (pos? (:total p)))))))

;; --- The draft stash --------------------------------------------------------

(deftest drafts-are-per-viewer-and-survive-the-tab-test
  (let [slug (make-event!)
        app (server/create-app)
        first-a (app (mock/request :get (str "/cfp/" slug)))
        first-b (app (mock/request :get (str "/cfp/" slug)))
        ca (session-cookie first-a)
        cb (session-cookie first-b)
        type! (fn [cookie params]
                (app (-> (mock/request :post (str "/api/cfp/" slug "/draft") params)
                         (mock/header "cookie" cookie))))
        page (fn [cookie]
               (:body (app (-> (mock/request :get (str "/cfp/" slug))
                               (mock/header "cookie" cookie)))))]

    (testing "a keystroke answers 204 — the screen is moved by the stream"
      (is (= 204 (:status (type! ca {"answer-talk-title" "ALPHA TALK"
                                     "speaker-bio" "Alpha's bio"})))))
    (is (= 204 (:status (type! cb {"answer-talk-title" "BRAVO TALK"}))))

    (testing "reopening the page repaints what THIS viewer typed"
      (is (str/includes? (page ca) "ALPHA TALK"))
      (is (str/includes? (page ca) "Picked up where you left off")))

    (testing "and never what somebody else typed"
      (is (not (str/includes? (page ca) "BRAVO TALK")))
      (is (not (str/includes? (page cb) "ALPHA TALK")))
      (is (not (str/includes? (page cb) "Alpha's bio"))))

    (testing "the viewer can explicitly reset every saved field"
      (let [before (page ca)
            response (app (-> (mock/request :post (str "/api/cfp/" slug "/draft/reset"))
                              (mock/header "cookie" ca)))
            after (page ca)]
        (is (str/includes? before "Picked up where you left off"))
        (is (str/includes? before "Reset saved data"))
        (is (= 303 (:status response)))
        (is (= (str "/cfp/" slug) (get-in response [:headers "Location"])))
        (is (not (str/includes? after "ALPHA TALK")))
        (is (not (str/includes? after "Alpha's bio")))
        (is (not (str/includes? after "Picked up where you left off")))))

    (testing "a stranger with no cookie at all gets a clean form"
      (let [fresh (:body (app (mock/request :get (str "/cfp/" slug))))]
        (is (not (str/includes? fresh "ALPHA TALK")))
        (is (not (str/includes? fresh "Picked up where you left off")))))))

(deftest live-draft-preview-preserves-typing-and-submit-still-commits-test
  (let [slug (make-event!)
        app (server/create-app)
        event (events/event-by-slug slug)
        first-page (app (mock/request :get (str "/cfp/" slug)))
        cookie (session-cookie first-page)
        refreshed (atom nil)
        fields (fields-of slug)
        answers (into {} (for [f fields
                               :when (:required f)]
                           [(str "answer-" (name (:id f)))
                            (if (seq (:options f)) (first (:options f)) "A real answer")]))
        payload (merge answers
                       {"speaker-name" "Lee Faus"
                        "speaker-email" "lee@example.com"
                        "speaker-title" "Engineering Leader"
                        "speaker-org" "ExampleCo"
                        "speaker-bio" "Builds systems that respect user input."})
        draft-response
        (with-redefs [live/refresh!
                      (fn [view scope]
                        (reset! refreshed {:view view :scope scope})
                        1)]
          (app (-> (mock/request :post (str "/api/cfp/" slug "/draft")
                                 {"answer-talk-title" "Typing must survive"})
                   (mock/header "cookie" cookie))))]
    (testing "the form binds user input without overwriting an existing signal"
      (is (str/includes? (:body first-page) "data-star-signals__ifmissing"))
      (is (str/includes? (:body first-page) "data-star-bind:cfpanswertalktitle")))
    (testing "a keystroke refreshes only the sanctioned local view"
      (is (= 204 (:status draft-response)))
      (is (identical? public-cfp-handlers/public-cfp-live-view
                      (:view @refreshed)))
      (is (= slug (second (:scope @refreshed)))))
    (testing "the same public form POST commits and redirects"
      (let [response (app (-> (mock/request :post (str "/api/cfp/" slug "/submit") payload)
                              (mock/header "cookie" cookie)))]
        (is (= 303 (:status response)))
        (is (str/starts-with? (get-in response [:headers "Location"])
                              (str "/cfp/" slug "/submitted/")))
        (is (= 1 (count (submissions/for-event (:id event)))))))))

(deftest submitting-clears-the-answers-and-keeps-the-speaker-test
  (let [slug (make-event!)
        app (server/create-app)
        cookie (session-cookie (app (mock/request :get (str "/cfp/" slug))))
        fields (fields-of slug)
        ;; A complete submission built from the live form, so this test does not
        ;; go stale the day the seed form gains a required question.
        answers (into {} (for [f fields
                               :when (:required f)]
                           [(str "answer-" (name (:id f)))
                            (if (seq (:options f)) (first (:options f)) "A real answer")]))
        payload (merge answers
                       {"speaker-name" "Dana Whitfield"
                        "speaker-email" "dana@example.com"
                        "speaker-title" "VP Engineering"
                        "speaker-org" "Northwind Freight"
                        "speaker-bio" "Runs the platform group."})
        resp (app (-> (mock/request :post (str "/api/cfp/" slug "/submit") payload)
                      (mock/header "cookie" cookie)))
        success (:body (app (-> (mock/request :get (get-in resp [:headers "Location"]))
                                (mock/header "cookie" cookie))))
        after (:body (app (-> (mock/request :get (str "/cfp/" slug))
                              (mock/header "cookie" cookie))))]
    (testing "the submission is taken"
      (is (= 303 (:status resp)) (str "body: " (subs (str (:body resp)) 0 (min 400 (count (str (:body resp))))))))

    (testing "the handoff truthfully explains the organizer-triggered portal link"
      (is (str/includes? success "private one-time link"))
      (is (not (str/includes? success "Not yet wired up"))))

    (testing "the answers are gone — a second talk starts blank"
      (is (not (str/includes? after "A real answer")))
      (is (not (str/includes? after "Picked up where you left off"))))

    (testing "but they never type their bio twice"
      (is (str/includes? after "Runs the platform group."))
      (is (str/includes? after "Dana Whitfield")))))

(deftest a-refusal-keeps-the-typing-test
  (let [slug (make-event!)
        app (server/create-app)
        cookie (session-cookie (app (mock/request :get (str "/cfp/" slug))))
        resp (app (-> (mock/request :post (str "/api/cfp/" slug "/submit")
                                    {"answer-talk-title" "Half A Talk"
                                     "speaker-name" "Dana Whitfield"})
                      (mock/header "cookie" cookie)))]
    (testing "an incomplete submission is refused, and says so as a refusal"
      (is (= 422 (:status resp))))
    (testing "the 422 page still holds what was typed"
      (is (str/includes? (:body resp) "Half A Talk")))
    (testing "and so does a refresh AFTER the refusal"
      (let [again (:body (app (-> (mock/request :get (str "/cfp/" slug))
                                  (mock/header "cookie" cookie))))]
        (is (str/includes? again "Half A Talk"))))))

;; --- The portal, same grade -------------------------------------------------
;;
;; These live here rather than in portal-test because portal-test covers the
;; portal NAMESPACE (pure: visible status, task progress, profile parsing) and
;; what is under test here is the ROUTE-level draft lane in server.clj.

(defn- submit-and-sign-in!
  "Do the whole speaker journey up to 'I have an account': submit a talk, then
   sign in with the email that submitting created. Returns [cookie slug]."
  [slug app]
  (let [fields (fields-of slug)
        answers (into {} (for [f fields :when (:required f)]
                           [(str "answer-" (name (:id f)))
                            (if (seq (:options f)) (first (:options f)) "A real answer")]))]
    (app (mock/request :post (str "/api/cfp/" slug "/submit")
                       (merge answers
                              {"speaker-name" "Dana Whitfield"
                               "speaker-email" "dana@example.com"
                               "speaker-title" "VP Engineering"
                               "speaker-org" "Northwind Freight"
                               "speaker-bio" "Runs the platform group."})))
    (let [token (auth/issue-token! "dana@example.com")]
      (session-cookie (app (mock/request :get (str "/auth/" token)))))))

(deftest submitted-speaker-can-leave-and-return-without-saving-the-handoff-link-test
  (let [slug (make-event!)
        app (server/create-app)
        anonymous-cookie (session-cookie
                           (app (mock/request :get (str "/cfp/" slug))))
        fields (fields-of slug)
        answers (into {} (for [field fields :when (:required field)]
                           [(str "answer-" (name (:id field)))
                            (if (seq (:options field))
                              (first (:options field))
                              "A real answer")]))
        submitted (app (-> (mock/request
                             :post (str "/api/cfp/" slug "/submit")
                             (merge answers
                                    {"speaker-name" "Riley Returner"
                                     "speaker-email" "riley-returner@example.com"
                                     "speaker-title" "Staff Engineer"
                                     "speaker-org" "Return Path"
                                     "speaker-bio" "Builds recoverable journeys."
                                     "answer-talk-title" "The Way Back"}))
                           (mock/header "cookie" anonymous-cookie)))
        speaker-cookie (session-cookie submitted)
        portal-token (second (re-find #"portal-token=([^&]+)"
                                      (get-in submitted [:headers "Location"])))
        as-speaker #(app (mock/header % "cookie" speaker-cookie))]
    (testing "submitting establishes a durable browser session"
      (is (= 303 (:status submitted)))
      (is (some? speaker-cookie)))

    (testing "the speaker can navigate away and return without the token URL"
      (is (= 200 (:status (as-speaker (mock/request :get "/")))))
      (let [portal-response (as-speaker (mock/request :get "/portal"))]
        (is (= 200 (:status portal-response)))
        (is (str/includes? (:body portal-response) "The Way Back"))))

    (testing "the session is scoped to that browser and the one-time backup remains"
      (is (= 302 (:status (app (mock/request :get "/portal")))))
      (let [token-path (str "/auth/" portal-token "?next=%2Fportal")]
        (is (= 303 (:status (app (mock/request :get token-path)))))
        (is (= 400 (:status (app (mock/request :get token-path)))))))))

(deftest portal-drafts-are-scoped-and-restored-test
  (let [slug (make-event!)
        app (server/create-app)
        cookie (submit-and-sign-in! slug app)
        as-dana (fn [req] (app (mock/header req "cookie" cookie)))
        portal (fn [] (:body (as-dana (mock/request :get "/portal"))))]

    (testing "the portal streams on a route a speaker may actually reach"
      (let [body (portal)]
        (is (str/includes? body "/portal/stream"))
        (is (str/includes? body "/portal/draft"))
        (is (str/includes? body "data-star-init"))))

    (testing "a portal keystroke answers 204 and is remembered"
      (is (= 204 (:status (as-dana (mock/request :post "/portal/draft"
                                                 {"dscope" "profile"
                                                  "bio" "HALF TYPED BIO"})))))
      (is (str/includes? (portal) "HALF TYPED BIO")))

    (testing "saving it for real clears the draft"
      (as-dana (mock/request :post "/api/profile"
                             {"tagline" "VP Engineering" "bio" "The saved bio."
                              "headshot-url" "" "linkedin-url" "" "website-url" ""}))
      (let [body (portal)]
        (is (str/includes? body "The saved bio."))
        (is (not (str/includes? body "HALF TYPED BIO"))
            "a stale draft still competing with a saved value")))

    (testing "a draft for somebody else's talk is refused, not stashed"
      (is (= 403 (:status (as-dana (mock/request :post "/portal/draft"
                                                 {"dscope" "not-my-submission-id"
                                                  "answer-talk-title" "sneaky"}))))))

    (testing "and an unsigned-in stranger cannot open the portal stream at all"
      (is (#{302 303} (:status (app (mock/request :get "/portal/stream"))))))))

;; --- The gate, from the speaker's side --------------------------------------

(deftest the-live-lane-is-reachable-by-the-people-it-is-for-test
  (let [slug (make-event!)
        app (server/create-app)]
    (testing "a stranger can POST a draft without signing in"
      (is (= 204 (:status (app (mock/request :post (str "/api/cfp/" slug "/draft")
                                             {"answer-talk-title" "x"}))))))
    (testing "a draft for a conference that does not exist is a 404, not a 500"
      (is (= 404 (:status (app (mock/request :post "/api/cfp/no-such-event/draft" {}))))))
    (testing "and neither is the organizer-only stream"
      (let [resp (app (mock/request :get "/api/sse?event-id=new-event"))]
        (is (#{302 303} (:status resp))
            "if this ever answers 200 anonymously, the gate has a hole")))))
