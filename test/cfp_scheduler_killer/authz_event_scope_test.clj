(ns cfp-scheduler-killer.authz-event-scope-test
  "Authorization is PER CONFERENCE, not per instance.

   The hole these tests close (found 2026-08-09 in the audit for bd
   sessionize-sched-killer-x9j): `auth/organizer?` asked one global question —
   'is this person on ANY committee of ANY event?' — and every organizer route
   was satisfied by a yes. One reviewer added to one event's committee could
   therefore open every OTHER event's settings page (which prints its API
   token), point its Slack webhook at their own channel, register a webhook,
   lock its schedule, flip its acceptances, and add themselves to its committee.
   Nothing in the app said no, and the log recorded it as ordinary organizer
   work.

   The rule now: a reviewer's reach stops at the conferences whose roster they
   are on. Two properties are asserted throughout, and the second is the one
   that matters most:

     1. The STATUS is a refusal (403 — for reads as well as writes; a page that
        renders another event's API key is as much a leak as a write).
     2. The LOG did not grow. A 403 that still appended is not a refusal, and
        only the append-only log can tell us which happened."
  (:require
   [cfp-scheduler-killer.auth :as auth]
   [cfp-scheduler-killer.committees :as committees]
   [cfp-scheduler-killer.events :as events]
   [cfp-scheduler-killer.exports :as exports]
   [cfp-scheduler-killer.review-assignments :as review-assignments]
   [cfp-scheduler-killer.server :as server]
   [cfp-scheduler-killer.sse :as sse]
   [cfp-scheduler-killer.store :as store]
   [cfp-scheduler-killer.submissions :as sub]
   [cfp-scheduler-killer.test-helpers :refer [with-temp-store]]
   [clojure.data.json :as json]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [ring.mock.request :as mock])
  (:import
   (java.time LocalDateTime)))

(use-fixtures :each
  with-temp-store
  (fn [f] (reset! auth/tokens {}) (f)))

;; --- The world: two conferences that share nothing but a server -------------

(defn- make-event! [slug name reviewer-email reviewer-name]
  (let [event (events/create-event!
                {:name name :slug slug :tz "America/New_York"
                 :cfp-opens-at (LocalDateTime/of 2020 1 1 0 0)
                 :cfp-closes-at (LocalDateTime/of 2099 1 1 0 0)}
                "kaocha")
        cid (:id (first (events/committees-for-event (:id event))))]
    {:event event
     :committee-id cid
     :reviewer (committees/add-member! cid {:name reviewer-name
                                            :email reviewer-email
                                            :role "chair"} "kaocha")}))

(defn- submit! [event title speaker-email]
  (let [ff (:fields (events/form-for-event (:id event)))]
    (sub/create-submission!
      event
      (sub/parse-answers ff {:answer-talk-title title
                             :answer-abstract "Abstract."
                             :answer-session-format "Experience Report"
                             :answer-org-size ">10,000"
                             :answer-industry "Insurance"
                             :answer-ai-transformation-history "2023."
                             :answer-measurable-outcomes "Numbers."})
      (sub/parse-speaker {:speaker-name "Dana Speaker"
                          :speaker-email speaker-email
                          :speaker-title "VP" :speaker-org "BigCo"
                          :speaker-bio "Bio."})
      "form" "kaocha")))

(defn- setup!
  "Alpha (Ann reviews) and Beta (Bob reviews), plus a talk submitted to each.
   Dana speaks at Beta and is on nobody's committee."
  []
  (let [a (make-event! "alpha" "Alpha Summit" "ann@example.com" "Ann Perry")
        b (make-event! "beta" "Beta Summit" "bob@example.com" "Bob Ross")]
    {:alpha (:event a) :beta (:event b)
     :alpha-committee (:committee-id a) :beta-committee (:committee-id b)
     :ann (:reviewer a) :bob (:reviewer b)
     :alpha-talk (submit! (:event a) "A talk at Alpha" "dana@example.com")
     :beta-talk (submit! (:event b) "A talk at Beta" "dana@example.com")}))

(defn- session-for
  "Sign in as this email and return a handler that carries the cookie."
  [handler email]
  (let [token (auth/issue-token! email)
        resp (handler (mock/request :get (str "/auth/" token)))
        cookie (first (str/split (first (get-in resp [:headers "Set-Cookie"])) #";"))]
    (fn [req] (handler (mock/header req "cookie" cookie)))))

(defn- log-types
  "Every event type currently in the log, with async sink deliveries drained
   first — the ground truth about what actually happened."
  []
  (store/await-sinks!)
  (frequencies (map :type (store/read-events))))

;; INTENT-TEST: AUTHZ-003
(deftest speaker-and-non-chair-reviewer-never-inherit-organizer-authority-test
  (let [{:keys [alpha alpha-committee]} (setup!)
        reviewer (committees/add-member!
                   alpha-committee
                   {:name "Riley Reviewer" :email "riley@example.com" :role "reviewer"}
                   "ann@example.com")
        raw (server/create-app)
        speaker (session-for raw "dana@example.com")
        riley (session-for raw "riley@example.com")
        board (:body (riley (mock/request :get "/events/alpha/board")))]
    (testing "a speaker is redirected to their portal, never event creation"
      (is (= "/portal"
             (get-in (speaker (mock/request :get "/events/new"))
                     [:headers "Location"]))))
    (testing "a speaker denied an organizer page gets a truthful way home"
      (let [response (speaker (mock/request :get "/events/beta/submissions"))
            body (str (:body response))]
        (is (= 403 (:status response)))
        (is (= "text/html; charset=utf-8"
               (get-in response [:headers "Content-Type"])))
        (is (str/includes? body "<!DOCTYPE html>"))
        (is (str/includes? body "signed in as a speaker for this event"))
        (is (not (str/includes? body "You review for another conference")))
        (is (str/includes? body "href=\"/portal\">Back to Speaker Portal"))))
    (testing "a reviewer can review but cannot create or load organizer workspaces"
      (is (= "/events"
             (get-in (riley (mock/request :get "/events/new"))
                     [:headers "Location"])))
      (is (= 403 (:status (riley (mock/request :get "/events/alpha/settings")))))
      (is (= 403 (:status (riley (mock/request :get "/events/alpha/schedule")))))
      (is (= 403 (:status (riley (mock/request :post "/api/events/create")))))
      (is (= "reviewer" (:role reviewer)))
      (is (= (:id alpha) (:event-id (first (events/committees-for-event (:id alpha)))))))
    (testing "blind-review policy is a chair-only server mutation"
      (let [before (count (store/read-events))
            requests [(mock/request :post "/api/events/alpha/blind-review"
                                    {"hide-presenter-info" "on"})
                      (mock/request :post "/api/events/alpha/review-plan/blind"
                                    {"blind" "true"})
                      (-> (mock/request :put "/api/v1/events/alpha/review-policy"
                                        (json/write-str {"mode" "hidden"
                                                         "expectedVersion" 0}))
                          (mock/header "content-type" "application/json"))]]
        (doseq [request requests]
          (is (= 403 (:status (riley request))) (:uri request)))
        (is (= before (count (store/read-events)))
            "a refused live-input request must append no policy fact")))
    (testing "the review board uses the review-only rail"
      (doseq [allowed ["Event overview" "Review Board"]]
        (is (str/includes? board allowed) allowed))
      (doseq [organizer-only ["+ New event" "Settings" "Blind Review Process"
                              "Create Speaker (Bypass CFP)" "Exports &amp; API"]]
        (is (not (str/includes? board organizer-only)) organizer-only))
      (is (not (str/includes? board "href=\"/events/alpha/schedule\""))
          "reviewer navigation must not advertise the chair-only schedule workspace"))))

;; --- (a) reads ---------------------------------------------------------------

(deftest a-reviewer-cannot-read-another-events-pages-test
  (let [_ (setup!)
        raw (server/create-app)
        ann (session-for raw "ann@example.com")]
    (doseq [path ["" "/submissions" "/board" "/settings" "/comms" "/log"
                  "/inform" "/form" "/schedule" "/replay" "/capture"
                  ;; the HTML export index — the RAW files one segment deeper
                  ;; are public on purpose, this page is not
                  "/exports"]]
      (testing (str "GET /events/beta" path " is refused for a reviewer of Alpha")
        (let [resp (ann (mock/request :get (str "/events/beta" path)))]
          (is (= 403 (:status resp))
              (str "/events/beta" path " answered " (:status resp)
                   " — another conference's organizer page must be refused, not rendered"))
          (is (str/includes? (str (:body resp)) "reviewer")
              "the refusal says, in English, what is missing"))))

    (testing "the settings page's API token never reaches a foreign reviewer"
      (let [token (get-in (events/event-by-slug "beta") [:settings :api-token])
            body (str (:body (ann (mock/request :get "/events/beta/settings"))))]
        (is (not (str/includes? body token))
            "the refusal body must not contain the very secret the page renders")))

    (testing "and the event's own reviewer is not affected"
      (let [bob (session-for raw "bob@example.com")]
        (is (= 200 (:status (bob (mock/request :get "/events/beta/settings")))))))))

(deftest sse-subscriptions-and-diagnostics-are-event-scoped-test
  (let [{:keys [alpha beta]} (setup!)
        raw (server/create-app)
        ann (session-for raw "ann@example.com")
        ann-person (store/person-by-email "ann@example.com")
        bob-person (store/person-by-email "bob@example.com")
        gate-status (fn [event-id]
                      (:status
                        (auth/gate (constantly {:status 204})
                                   {:uri "/api/sse"
                                    :request-method :get
                                    :params (cond-> {} event-id (assoc :event-id event-id))
                                    :session {:person-id (:id ann-person)}})))]
    (testing "an organizer cannot subscribe to another event by query parameter"
      (is (= 403 (gate-status (:id beta)))))

    (testing "missing and invented event ids are refused before a stream opens"
      (is (= 403 (gate-status nil)))
      (is (= 403 (gate-status "not-an-event"))))

    (testing "the organizer's own event and create-page pseudo-channel remain reachable"
      (doseq [event-id [(:id alpha) sse/new-event-channel]]
        (is (= 204 (gate-status event-id)))))

    (testing "the diagnostic endpoint reveals only this organizer's registrations"
      (with-redefs [sse/registrations
                    (constantly [{:event-id (:id alpha) :person-id (:id ann-person)}
                                 {:event-id (:id beta) :person-id (:id bob-person)}
                                 {:event-id sse/new-event-channel :person-id (:id ann-person)}
                                 {:event-id sse/new-event-channel :person-id (:id bob-person)}])]
        (let [payload (json/read-str
                        (:body (ann (mock/request :get "/dev/sse-state")))
                        :key-fn keyword)]
          (is (= 2 (:subscribers payload)))
          (is (= #{{:event-id (:id alpha) :person-id (:id ann-person)}
                   {:event-id sse/new-event-channel :person-id (:id ann-person)}}
                 (set (:registrations payload)))))))))

;; --- (b) writes --------------------------------------------------------------

(deftest a-reviewer-cannot-write-to-another-event-test
  (let [{:keys [beta-talk beta-committee bob]} (setup!)
        raw (server/create-app)
        ann (session-for raw "ann@example.com")
        sid (:id beta-talk)
        before (log-types)
        attempts
        [["decide another event's talk"
          (-> (mock/request :post (str "/api/submissions/" sid "/status"))
              (mock/body {"status" "Declined"}))              "submission.status-changed"]
         ["rate another event's talk"
          (-> (mock/request :post (str "/api/submissions/" sid "/rate"))
              (mock/body {"stars" "1"}))                      "rating.set"]
         ["comment on another event's talk"
          (-> (mock/request :post (str "/api/submissions/" sid "/comment"))
              (mock/body {"body" "Let me in"}))               "comment.added"]
         ["inform another event's speaker"
          (mock/request :post (str "/api/submissions/" sid "/inform"))
          "submission.notified"]
         ["repoint another event's Slack"
          (-> (mock/request :post "/api/events/beta/slack/set")
              (mock/body {"webhook-url" "https://hooks.slack.com/services/EVIL"
                          "groups" "submissions"}))           "event.updated"]
         ["connect their Airtable to my base"
          (-> (mock/request :post "/api/events/beta/airtable/set")
              (mock/body {"base-id" "appEVIL" "table" "Submissions"
                          "token" "patEVIL"}))                "event.updated"]
         ["register a webhook on their event"
          (-> (mock/request :post "/api/events/beta/webhooks/add")
              (mock/body {"url" "http://evil.example/x"}))     "sink.registered"]
         ["mint an API key for their event"
          (-> (mock/request :post "/api/events/beta/api-keys/create")
              (mock/body {"label" "mine now" "scope" "read"})) "api-key.created"]
         ["lock their schedule"
          (mock/request :post "/api/events/beta/schedule/lock")  "schedule.locked"]
         ["add a room to their schedule"
          (-> (mock/request :post "/api/events/beta/schedule/room-add")
              (mock/body {"name" "Ballroom"}))                "room.added"]
         ["edit their CFP form"
          (-> (mock/request :post "/api/events/beta/form/add")
              (mock/body {"label" "Your home address?" "type" "text"}))
          "form.updated"]
         ;; The two that would make the breach permanent: writing themselves
         ;; onto the roster, or writing the real reviewer off it.
         ["add themselves to their committee"
          (-> (mock/request :post (str "/api/committees/" beta-committee "/members/add"))
              (mock/body {"name" "Ann Perry" "email" "ann@example.com"
                          "role" "chair"}))                   "member.added"]
         ["remove their reviewer"
          (mock/request :post (str "/api/memberships/" (:membership-id bob) "/remove"))
          "member.removed"]]]
    (doseq [[what req evt] attempts]
      (testing (str "a reviewer of Alpha may not " what)
        (let [resp (ann req)]
          (is (= 403 (:status resp))
              (str what " answered " (:status resp)
                   " — a cross-event write must be refused, and a refusal must not"
                   " look like a 303 success"))
          (is (= (get before evt 0) (get (log-types) evt 0))
              (str what " appended a " evt " to the log despite being refused")))))

    (testing "the whole run appended NOTHING at all"
      (is (= before (log-types))
          "an event log that grew during a series of refusals is a log of writes that happened"))

    (testing "and Beta's roster is untouched"
      (is (= ["bob@example.com"]
             (mapv :email (committees/members-for-committee beta-committee)))))))

;; --- (c) the reviewer's own event still works -------------------------------

(deftest a-reviewer-keeps-their-own-event-test
  (let [{:keys [alpha-talk alpha-committee]} (setup!)
        raw (server/create-app)
        ann (session-for raw "ann@example.com")]
    (testing "every organizer page of their own event opens"
      (doseq [path ["" "/board" "/settings" "/form" "/schedule"]]
        (is (= 200 (:status (ann (mock/request :get (str "/events/alpha" path)))))
            (str "/events/alpha" path)))
      ;; /submissions is retired (2026-08-10): their own event still ANSWERS —
      ;; with the redirect to the board, not a login bounce.
      (let [resp (ann (mock/request :get "/events/alpha/submissions"))]
        (is (= 303 (:status resp)))
        (is (str/ends-with? (get-in resp [:headers "Location"]) "/board"))))

    (testing "rating a talk on their own event lands in the log"
      (let [before (get (log-types) "rating.set" 0)
            resp (ann (-> (mock/request :post (str "/api/submissions/" (:id alpha-talk) "/rate"))
                          (mock/body {"stars" "4"})))]
        (is (#{200 204 302 303} (:status resp)))
        (is (= (inc before) (get (log-types) "rating.set" 0)))))

    (testing "and so does adding a reviewer to their own committee"
      (let [resp (ann (-> (mock/request :post (str "/api/committees/" alpha-committee "/members/add"))
                          (mock/body {"name" "Alex B-F" "email" "alex@example.com"
                                      "role" "reviewer"})))]
        (is (#{200 302 303} (:status resp)))
        (is (= #{"ann@example.com" "alex@example.com"}
               (set (map :email (committees/members-for-committee alpha-committee)))))))))

;; --- (d) speakers are unaffected --------------------------------------------

(deftest the-speakers-own-surfaces-are-unchanged-test
  (let [{:keys [beta-talk]} (setup!)
        raw (server/create-app)
        dana (session-for raw "dana@example.com")]
    (testing "the portal opens for someone on no committee at all"
      (is (= 200 (:status (dana (mock/request :get "/portal"))))))

    (testing "they may still edit their own submission"
      (is (not= 403 (:status (dana (-> (mock/request :post (str "/api/submissions/"
                                                                (:id beta-talk) "/answers"))
                                       (mock/body {"answer-talk-title" "A better title"})))))))

    (testing "they may still tick off their own tasks and edit their profile"
      (is (not= 403 (:status (dana (-> (mock/request :post (str "/api/submissions/"
                                                                (:id beta-talk) "/task"))
                                       (mock/body {"key" "confirm-bio" "done" "true"}))))))
      (is (not= 403 (:status (dana (-> (mock/request :post "/api/profile")
                                       (mock/body {"bio" "A new bio."})))))))

    (testing "a speaker who wanders onto another event's organizer page is refused
              (open sign-up 2026-08-10: the wall is per-conference membership,
              not organizer-somewhere; the honest answer is the wrong-event 403)"
      (let [resp (dana (mock/request :get "/events/beta/board"))]
        (is (= 403 (:status resp)))))

    (testing "the public CFP and program are still open to everyone"
      ;; /agenda/:slug 302-redirects onto /program/:slug (Gene ratified
      ;; 2026-08-11: "/program is canon") — still un-gated, just relocated.
      (let [anon (server/create-app)]
        (is (= 200 (:status (anon (mock/request :get "/cfp/beta")))))
        (is (= 302 (:status (anon (mock/request :get "/agenda/beta")))))
        (is (= 200 (:status (anon (mock/request :get "/program/beta")))))))))

;; --- (e) routes nobody has written yet --------------------------------------

(deftest the-gate-is-default-deny-across-events-test
  (let [_ (setup!)
        raw (server/create-app)
        ann (session-for raw "ann@example.com")
        dana (session-for raw "dana@example.com")]
    (testing "a verb that does not exist yet is refused cross-event"
      ;; This URL has no route. What matters is that the GATE refuses it before
      ;; routing gets a say — so an /api/events/:slug/ verb written next month
      ;; is event-scoped on the day it is written, without anyone remembering.
      (is (= 403 (:status (ann (mock/request :post "/api/events/beta/some-future-verb")))))
      (is (= 403 (:status (dana (mock/request :post "/api/events/beta/some-future-verb"))))))

    (testing "and so is a conference-shaped URL that names no conference"
      (is (= 403 (:status (ann (mock/request :post "/api/events/no-such-event/webhooks/add")))))
      (is (= 403 (:status (ann (mock/request
                                 :post (str "/api/submissions/"
                                            "2a1e0b2c-0000-4000-8000-000000000000/status")))))))

    (testing "an unknown slug still 404s on a READ — a typo is not a permissions problem"
      (is (= 404 (:status (ann (mock/request :get "/events/no-such-event"))))))

    (testing "their own event's future verb reaches the router, which 404s honestly"
      (is (= 404 (:status (ann (mock/request :post "/api/events/alpha/some-future-verb"))))))))

;; --- (f) API keys ------------------------------------------------------------

(deftest api-key-create-and-revoke-round-trip-test
  (let [_ (setup!)
        raw (server/create-app)
        ann (session-for raw "ann@example.com")
        sessions (fn [query]
                   (raw (mock/request :get (str "/api/v1/events/alpha/sessions" query))))]

    (testing "before any key exists, filtering by status needs one"
      (is (= 401 (:status (sessions "?status=all")))))

    (testing "an unknown scope is refused before a key fact is appended"
      (let [before (get (log-types) "api-key.created" 0)
            resp (ann (-> (mock/request :post "/api/events/alpha/api-keys/create")
                          (mock/body {"label" "Typo" "scope" "admin"})))]
        (is (= 422 (:status resp)))
        (is (str/includes? (:body resp) "Choose a valid API key scope"))
        (is (= before (get (log-types) "api-key.created" 0)))))

    (let [secret "0123456789abcdefghijklmnopqrstuv"
          resp (with-redefs [exports/new-key-material (constantly secret)]
                 (ann (-> (mock/request :post "/api/events/alpha/api-keys/create")
                          (mock/body {"label" "Review bot" "scope" "review-bot"}))))
          key-row (first (exports/api-keys-for (events/event-by-slug "alpha")))
          context (exports/api-key-context (events/event-by-slug "alpha") secret)
          created-fact (first (filter #(= "api-key.created" (:type %))
                                      (store/log-for-event (:id (events/event-by-slug "alpha")))))]

      (testing "creating a key returns the material in the no-store mint response only"
        (is (= 200 (:status resp)))
        (is (str/includes? (str (:body resp)) secret))
        (is (= "no-store" (get-in resp [:headers "Cache-Control"])))
        (is (str/includes? (str (:body resp)) (exports/key-prefix secret)))
        (is (str/includes? (str (:body resp)) "Copy API key"))
        (is (str/includes? (str (:body resp)) "Review bot"))
        (is (str/includes? (str (:body resp)) "review-bot")))

      (testing "the append-only fact and projection retain a hash and prefix, never the secret"
        (is (nil? (get-in created-fact [:payload :key])))
        (is (string? (get-in created-fact [:payload :key-hash])))
        (is (= (subs secret 0 8) (get-in created-fact [:payload :prefix])))
        (is (nil? (:key key-row)))
        (is (= (subs secret 0 8) (:prefix key-row)))
        (is (= "review-bot" (:scope key-row)))
        (is (string? (:person-id key-row)))
        (is (= (:id key-row) (:id context)))
        (is (= :review-bot (:scope context)))
        (is (= (:person-id key-row) (:person-id context)))
        (is (= (str "api:review-bot:" (subs secret 0 8))
               (exports/api-key-actor context))))

      (testing "the key is 32 url-safe characters and not a UUID"
        (is (= 32 (count secret)))
        (is (re-matches #"[A-Za-z0-9_-]{32}" secret)))

      (testing "and every later view shows only the label and last four characters"
        (let [body (str (:body (ann (mock/request :get "/events/alpha/settings"))))]
          (is (not (str/includes? body secret))
              "the settings page must never print the key again")
          (is (str/includes? body (exports/key-prefix secret)))
          (is (str/includes? body "Review bot"))
          (is (str/includes? body "review-bot"))))

      (testing "the hash-only projection cannot return the material later"
        (let [copied (ann (-> (mock/request :post "/api/events/alpha/api-keys/copy")
                              (mock/body {"id" (:id key-row)})))]
          (is (= 404 (:status copied)))
          (is (not (str/includes? (str (:body copied)) secret)))))

      (testing "an anonymous copy request is rejected before key material is read"
        (let [copied (raw (-> (mock/request :post "/api/events/alpha/api-keys/copy")
                              (mock/body {"id" (:id key-row)})))]
          (is (= 302 (:status copied)))
          (is (not (str/includes? (str (:body copied)) secret)))))

      (testing "omitting the id copies the event's legacy token"
        (let [copied (ann (-> (mock/request :post "/api/events/alpha/api-keys/copy")
                              (mock/body {})))
              payload (json/read-str (:body copied))]
          (is (= (get-in (events/event-by-slug "alpha") [:settings :api-token])
                 (get payload "key")))))

      (testing "a missing key is refused without falling back to the legacy token"
        (is (= 404 (:status (ann (-> (mock/request :post "/api/events/alpha/api-keys/copy")
                                     (mock/body {"id" "no-such-key"})))))))

      (testing "the key widens the API exactly like the legacy token"
        (let [r (sessions (str "?status=all&token=" secret))]
          (is (= 200 (:status r)))
          (is (map? (json/read-str (:body r)))))
        (is (= 200 (:status (raw (-> (mock/request :get "/api/v1/events/alpha/sessions?status=all")
                                     (mock/header "authorization" (str "Bearer " secret))))))))

      (testing "a key from one conference does not open another's"
        (is (= 401 (:status (raw (mock/request
                                   :get (str "/api/v1/events/beta/sessions?status=all&token="
                                             secret)))))))

      (testing "a review-bot key cannot cross into organizer writes"
        (let [response (raw (-> (mock/request :post
                                              "/api/v1/events/alpha/speakers/no-such/publish")
                                (mock/header "authorization" (str "Bearer " secret))))]
          (is (= 403 (:status response)))
          (is (str/includes? (:body response) "organizer scope"))))

      (testing "the legacy per-event token still works — no event loses its integrations"
        (let [legacy (get-in (events/event-by-slug "alpha") [:settings :api-token])]
          (is (= 200 (:status (sessions (str "?status=all&token=" legacy)))))))

      (testing "revoking is two server-rendered steps, and the first one does nothing"
        (let [asked (ann (-> (mock/request :post "/api/events/alpha/api-keys/revoke")
                             (mock/body {"id" (:id key-row)})))]
          (is (= 200 (:status asked)))
          (is (str/includes? (str (:body asked)) "Revoke it?")
              "the confirmation is rendered by the server, not a confirm() dialog")
          (is (= 200 (:status (sessions (str "?status=all&token=" secret))))
              "asking to revoke must not revoke")))

      (testing "confirming revokes it, and the key stops working immediately"
        (let [done (ann (-> (mock/request :post "/api/events/alpha/api-keys/revoke")
                            (mock/body {"id" (:id key-row) "confirm" "yes"})))]
          (is (#{302 303} (:status done)))
          (is (= 401 (:status (sessions (str "?status=all&token=" secret)))))
          (is (empty? (exports/api-keys-for (events/event-by-slug "alpha"))))))

      (testing "and the log tells the whole story — grant and revocation both"
        (is (= 1 (get (log-types) "api-key.created" 0)))
        (is (= 1 (get (log-types) "api-key.revoked" 0))))

      (testing "the key survives a re-fold, and so does its revocation"
        (store/load!)
        (is (empty? (exports/api-keys-for (events/event-by-slug "alpha"))))
        (is (not (exports/valid-token? (events/event-by-slug "alpha") secret)))))))

;; --- The prefix allowlist ----------------------------------------------------

(deftest public-prefixes-match-at-segment-boundaries-test
  (testing "a public prefix covers itself and what is below it"
    (doseq [uri ["/cfp" "/cfp/alpha" "/cfp/alpha/submitted/x"
                 "/agenda/alpha" "/login" "/api/login" "/auth/abc"
                 "/js/keyboard.js" "/vendor/datastar.js"]]
      (is (auth/public-path? uri) (str uri " must stay public"))))

  (testing "but NOT a path that merely starts with the same letters"
    ;; "/cfp" matched "/cfpanything" as a plain string prefix, which on an
    ;; allowlist means open: any future route whose name began with a public
    ;; one would have been reachable with no session at all.
    (doseq [uri ["/cfpanything" "/cfp-admin" "/agendas" "/logins"
                 "/authorize" "/api/logins" "/portalx"]]
      (is (not (auth/public-path? uri)) (str uri " must NOT be public"))))

  (testing "the same rule guards the speaker allowlist"
    (is (auth/speaker-path? "/portal"))
    (is (auth/speaker-path? "/portal/anything"))
    (is (not (auth/speaker-path? "/portalx")))
    (is (not (auth/speaker-path? "/api/profiles-of-everyone")))))

(deftest an-unauthenticated-lookalike-path-is-not-a-public-route-test
  (let [_ (setup!)
        anon (server/create-app)]
    ;; A nonexistent path is an honest 404. This does not weaken the segment-
    ;; boundary allowlist above: if a handler is later declared here, router-
    ;; level default-deny auth wraps it automatically.
    (testing "a public-prefix lookalike remains nonexistent for a stranger"
      (let [resp (anon (mock/request :get "/cfpanything"))]
        (is (= 404 (:status resp)))
        (is (str/starts-with? (get-in resp [:headers "Content-Type"] "")
                              "text/html"))))))

;; --- The narrowed first-run window ------------------------------------------

(deftest only-reviewers-can-create-conferences-test
  (testing "on a brand-new instance the first person through the door may create one"
    (is (auth/first-run?) "no events yet")
    (let [raw (server/create-app)
          founder (session-for raw "founder@example.com")
          resp (founder (-> (mock/request :post "/api/events/create")
                            (mock/body {"name" "First Conference" "tz" "America/New_York"
                                        "cfp-opens-at" "2020-01-01T00:00"
                                        "cfp-closes-at" "2099-01-01T00:00"
                                        "presenter-visibility-mode" "visible"})))]
      (is (#{302 303} (:status resp)))
      (is (not (auth/first-run?)) "the window closes the moment an event exists")

      (testing "and the founder is auto-added as chair, so they are not locked out"
        (let [event (events/event-by-slug "first-conference")
              cid (:id (first (events/committees-for-event (:id event))))]
          (is (= ["founder@example.com"]
                 (mapv :email (committees/members-for-committee cid))))
          (is (= 200 (:status (founder (mock/request :get "/events/first-conference"))))))))))

(deftest conference-creation-requires-chair-standing-test
  ;; A session created by submitting a proposal is speaker standing, not
  ;; organizer authority. First-run bootstrap remains covered above; after it
  ;; closes, only an existing chair may create another event.
  (let [_ (setup!)
        raw (server/create-app)
        dana (session-for raw "dana@example.com")]
    (testing "a speaker cannot create a conference"
      (is (= 403 (:status (dana (-> (mock/request :post "/api/events/create")
                                    (mock/body {"name" "Dana's Own Conference"
                                                "tz" "America/New_York"
                                                "cfp-opens-at" "2020-01-01T00:00"
                                                "cfp-closes-at" "2099-01-01T00:00"
                                                "presenter-visibility-mode" "visible"})))))))

    (testing "and the events list is theirs to browse"
      (is (= 200 (:status (dana (mock/request :get "/events"))))))

    (testing "an existing chair may create another event"
      (let [ann (session-for raw "ann@example.com")]
        (is (#{302 303} (:status (ann (-> (mock/request :post "/api/events/create")
                                          (mock/body {"name" "Ann's Other Conference"
                                                      "tz" "America/New_York"
                                                      "cfp-opens-at" "2020-01-01T00:00"
                                                      "cfp-closes-at" "2099-01-01T00:00"
                                                      "presenter-visibility-mode" "visible"}))))))))))

(deftest removing-the-last-reviewer-is-refused-over-http-test
  (let [{:keys [alpha-committee ann]} (setup!)
        raw (server/create-app)
        ann-session (session-for raw "ann@example.com")
        before (log-types)]
    (testing "the only reviewer cannot remove themselves — that would seal the event"
      (let [resp (ann-session (mock/request :post (str "/api/memberships/"
                                                       (:membership-id ann) "/remove")))]
        (is (= 422 (:status resp)))
        (is (str/includes? (str (:body resp)) "last reviewer"))
        (is (= before (log-types)))
        (is (= 1 (count (committees/members-for-committee alpha-committee))))))

    (testing "with a second reviewer on the roster it goes through"
      (ann-session (-> (mock/request :post (str "/api/committees/" alpha-committee "/members/add"))
                       (mock/body {"name" "Alex B-F" "email" "alex@example.com"
                                   "role" "reviewer"})))
      (let [resp (ann-session (mock/request :post (str "/api/memberships/"
                                                       (:membership-id ann) "/remove")))]
        (is (#{302 303} (:status resp)))
        (is (= ["alex@example.com"]
               (mapv :email (committees/members-for-committee alpha-committee))))))))
