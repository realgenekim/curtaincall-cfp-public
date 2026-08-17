(ns cfp-scheduler-killer.authz-security-test
  "Privilege separation between a SPEAKER and the programming committee.

   These exist because of a real hole, found 2026-08-09 by driving the running
   server as a speaker (bd sessionize-sched-killer-53i). Anyone who submits a
   talk gets an account; the gate matched organizer pages by URL prefix
   (\"/events\"), so every mutation under /api/ was guarded by nothing but
   'is signed in'. A speaker rated proposals, flipped a talk from Accepted to
   DECLINED, and locked the schedule — each request answering 303, which is
   what success looks like.

   Two lessons are baked into the assertions below:

     1. Assert the LOG, not just the status code. The original probe saw 303 and
        believed it. Only the append-only log knew the truth.
     2. Assert that an UNKNOWN route is refused too. The gate is default-deny;
        a test that only checks today's endpoints would pass again the next time
        someone adds one and forgets."
  (:require
   [cfp-scheduler-killer.auth :as auth]
   [cfp-scheduler-killer.committees :as committees]
   [cfp-scheduler-killer.events :as events]
   [cfp-scheduler-killer.server :as server]
   [cfp-scheduler-killer.store :as store]
   [cfp-scheduler-killer.submissions :as sub]
   [cfp-scheduler-killer.test-helpers :refer [with-temp-store]]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [ring.mock.request :as mock])
  (:import
   (java.time LocalDateTime)))

(use-fixtures :each
  with-temp-store
  (fn [f] (reset! auth/tokens {}) (f)))

(defn- setup!
  "An event whose committee is Gene alone, plus a talk from an outside speaker.

   The committee must be non-empty: on a store with no memberships at all,
   auth/bootstrap-mode? deliberately makes the first visitor an organizer, and
   every assertion here would be meaningless."
  []
  (let [event (events/create-event!
                {:name "Authz Summit" :slug "authz" :tz "America/New_York"
                 :cfp-opens-at (LocalDateTime/of 2020 1 1 0 0)
                 :cfp-closes-at (LocalDateTime/of 2099 1 1 0 0)}
                "kaocha")
        cid (:id (first (events/committees-for-event (:id event))))
        gene (committees/add-member! cid {:name "Gene Kim"
                                          :email "gene@example.com"
                                          :role "chair"} "kaocha")
        ff (:fields (events/form-for-event (:id event)))
        talk (sub/create-submission!
               event
               (sub/parse-answers ff {:answer-talk-title "A talk by an outsider"
                                      :answer-abstract "Abstract."
                                      :answer-session-format "Experience Report"
                                      :answer-org-size ">10,000"
                                      :answer-industry "Insurance"
                                      :answer-ai-transformation-history "2023."
                                      :answer-measurable-outcomes "Numbers."})
               (sub/parse-speaker {:speaker-name "Dana Speaker"
                                   :speaker-email "dana@example.com"
                                   :speaker-title "VP" :speaker-org "BigCo"
                                   :speaker-bio "Bio."})
               "form" "kaocha")]
    {:event event :gene gene :talk talk}))

(defn- session-for
  "Sign in as this email and return a handler that carries the cookie."
  [handler email]
  (let [token (auth/issue-token! email)
        resp (handler (mock/request :get (str "/auth/" token)))
        cookie (first (str/split (first (get-in resp [:headers "Set-Cookie"])) #";"))]
    (fn [req] (handler (mock/header req "cookie" cookie)))))

(defn- log-types
  "Every event type currently in the log — the ground truth about what happened."
  []
  (frequencies (map :type (store/read-events))))

(deftest speaker-cannot-act-as-the-committee-test
  (let [{:keys [talk event]} (setup!)
        raw (server/create-app)
        speaker (session-for raw "dana@example.com")
        sid (:id talk)
        slug (:slug event)
        before (log-types)
        attempts
        [["rate a proposal"        (-> (mock/request :post (str "/api/submissions/" sid "/rate"))
                                       (mock/body {"stars" "1"}))          "rating.set"]
         ["decide a talk"          (-> (mock/request :post (str "/api/submissions/" sid "/status"))
                                       (mock/body {"status" "Declined"}))  "submission.status-changed"]
         ["flag a talk"            (mock/request :post (str "/api/submissions/" sid "/priority"))
          "submission.priority-toggled"]
         ["comment to committee"   (-> (mock/request :post (str "/api/submissions/" sid "/comment"))
                                       (mock/body {"body" "Let me in"}))   "comment.added"]
         ["inform a speaker"       (mock/request :post (str "/api/submissions/" sid "/inform"))
          "submission.notified"]
         ["lock the schedule"      (mock/request :post (str "/api/events/" slug "/schedule/lock"))
          "schedule.locked"]
         ["add a room"             (-> (mock/request :post (str "/api/events/" slug "/schedule/room-add"))
                                       (mock/body {"name" "Ballroom"}))    "room.added"]
         ["register a webhook"     (-> (mock/request :post (str "/api/events/" slug "/webhooks/add"))
                                       (mock/body {"url" "http://evil.example/x"}))
          "sink.registered"]]]
    (doseq [[what req evt] attempts]
      (testing (str "a speaker may not " what)
        (let [resp (speaker req)]
          (is (= 403 (:status resp))
              (str what " answered " (:status resp)
                   " — a speaker must be refused, and a refusal must not look like a 303 success"))
          (is (= (get before evt 0) (get (log-types) evt 0))
              (str what " appended a " evt " to the log despite being refused")))))))

(deftest speaker-event-shell-does-not-open-the-committees-work-test
  (let [{:keys [event]} (setup!)
        raw (server/create-app)
        speaker (session-for raw "dana@example.com")
        slug (:slug event)]
    (testing "their event overview and read-only facts are relationship-readable"
      (doseq [path ["" "/details"]]
        (let [resp (speaker (mock/request :get (str "/events/" slug path)))]
          (is (= 200 (:status resp)))
          (is (not (str/includes? (:body resp) "Review Board"))))))
    (doseq [path ["/submissions" "/board" "/settings" "/comms" "/log" "/inform"]]
      (testing (str "GET /events/<slug>" path " is refused — not on THIS committee")
        (let [resp (speaker (mock/request :get (str "/events/" slug path)))]
          (is (= 403 (:status resp))
              "committee work must remain refused without a seat"))))))

(deftest the-gate-is-default-deny-test
  (testing "a route nobody thought to protect is protected anyway"
    (let [_ (setup!)
          raw (server/create-app)
          speaker (session-for raw "dana@example.com")]
      ;; This URL does not exist. What matters is that the GATE refuses it
      ;; before routing gets a say — proving a NEW organizer endpoint is
      ;; closed on the day it is written, without anyone remembering to add it
      ;; to a list. The old prefix gate would have let this through to the
      ;; handler.
      (let [resp (speaker (mock/request :post "/api/events/authz/some-future-verb"))]
        (is (= 403 (:status resp))
            "an unrecognised organizer-side POST must be refused by default")))))

(deftest speaker-keeps-their-own-surfaces-test
  (let [{:keys [event talk]} (setup!)
        raw (server/create-app)
        speaker (session-for raw "dana@example.com")]
    (testing "the portal still opens"
      (is (= 200 (:status (speaker (mock/request :get "/portal"))))))

    (testing "a speaker may still edit their own talk"
      (let [resp (speaker (-> (mock/request :post (str "/api/submissions/" (:id talk) "/answers"))
                              (mock/body {"answer-talk-title" "A better title"})))]
        (is (not= 403 (:status resp))
            "locking down the committee verbs must not lock speakers out of their own submission")))

    (testing "a speaker may still complete the tasks we gave them"
      (let [resp (speaker (-> (mock/request :post (str "/api/submissions/" (:id talk) "/task"))
                              (mock/body {"key" "confirm-bio" "done" "true"})))]
        (is (not= 403 (:status resp)))))

    (testing "a speaker may update their event-scoped custom values"
      (let [resp (speaker (mock/request :post
                                        (str "/api/events/" (:slug event)
                                             "/speaker-custom-values")))]
        (is (not= 403 (:status resp)))))

    (testing "a speaker may still update their profile"
      (let [resp (speaker (-> (mock/request :post "/api/profile")
                              (mock/body {"bio" "A new bio."})))]
        (is (not= 403 (:status resp)))))))

(deftest committee-member-still-works-test
  (let [{:keys [talk]} (setup!)
        raw (server/create-app)
        gene (session-for raw "gene@example.com")
        before (get (log-types) "rating.set" 0)]
    (testing "the committee can still do its job"
      (let [resp (gene (-> (mock/request :post (str "/api/submissions/" (:id talk) "/rate"))
                           (mock/body {"stars" "4"})))]
        (is (#{200 204 302 303} (:status resp)))
        (is (= (inc before) (get (log-types) "rating.set" 0))
            "the organizer's rating must actually land")))))

(deftest events-preview-gate-test
  "The create page's live marquee endpoint belongs to NO conference.

   /api/events/preview is spelled exactly like /api/events/:slug/… so without an
   entry in auth/unscoped-paths the gate resolves it to a conference named
   \"preview\", finds none, and refuses the write — and the marquee simply stops
   moving, which is indistinguishable from a broken SSE connection. It is still
   an organizer surface: a speaker gets nothing."
  (let [_ (setup!)
        raw (server/create-app)
        gene (session-for raw "gene@example.com")
        speaker (session-for raw "dana@example.com")
        preview (fn [handler]
                  (handler (-> (mock/request :post "/api/events/preview")
                               (mock/content-type "application/json")
                               (mock/body "{\"evname\":\"Preview Gate Summit\"}"))))]
    (testing "an organizer may POST it"
      (is (= 204 (:status (preview gene)))))

    (testing "speaker standing does not grant access to the creation preview"
      (is (= 403 (:status (preview speaker)))))

    (testing "and it is STATELESS — previewing appends nothing, ever"
      (let [before (log-types)]
        (preview gene)
        (preview gene)
        (is (= before (log-types))
            "the marquee is a rendering, not a draft")))))
