(ns cfp-scheduler-killer.sinks-test
  "The PC push email, the Airtable mirror, and the durability snapshot.

   No network anywhere: the mailer is in render mode, Airtable's HTTP call is
   rebound, and the 'bucket' is a temp directory."
  (:require
   [cfp-scheduler-killer.auth :as auth]
   [cfp-scheduler-killer.committees :as committees]
   [cfp-scheduler-killer.events :as events]
   [cfp-scheduler-killer.exports :as exports]
   [cfp-scheduler-killer.inform :as inform]
   [cfp-scheduler-killer.io.blob :as blob]
   [cfp-scheduler-killer.io.blob.gcs :as gcs]
   [cfp-scheduler-killer.mail :as mail]
   [cfp-scheduler-killer.reviews :as reviews]
   [cfp-scheduler-killer.server :as server]
   [cfp-scheduler-killer.sinks :as sinks]
   [cfp-scheduler-killer.store :as store]
   [cfp-scheduler-killer.submissions :as sub]
   [cfp-scheduler-killer.test-helpers :refer [with-temp-store]]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [ring.mock.request :as mock])
  (:import
   (java.time LocalDateTime)))

(use-fixtures :each with-temp-store (fn [f] (reset! auth/tokens {}) (f)))

(def ^:private private-note "PC only: my co-presenter may drop out.")

(defn- setup!
  "An event with a three-person committee, ready to receive a submission."
  []
  (let [event (events/create-eais-event!
                {:name "Sink Test Summit" :slug "sink-test" :tz "America/New_York"
                 :support-email "annp@example.com"
                 :cfp-opens-at (LocalDateTime/of 2020 1 1 0 0)
                 :cfp-closes-at (LocalDateTime/of 2099 1 1 0 0)}
                "kaocha")
        cid (:id (first (events/committees-for-event (:id event))))]
    (doseq [m [{:name "Gene Kim" :email "gene@example.com" :role "chair"}
               {:name "Ann Perry" :email "ann@example.com" :role "member"}
               {:name "Alex B-F" :email "alex@example.com" :role "member"}]]
      (committees/add-member! cid m "kaocha"))
    (events/event-by-slug "sink-test")))

(defn- submit! [event]
  (let [ff (:fields (events/form-for-event (:id event)))
        params {:answer-talk-title "Rebuilding underwriting"
                :answer-abstract "How we did it, and what broke."
                :answer-session-format "Experience Report"
                :answer-org-size ">10,000"
                :answer-industry "Insurance"
                :answer-ai-transformation-history "Began mid-2023."
                :answer-measurable-outcomes "Quote turnaround 6.2 days to 1.4."
                :answer-notes-to-committee private-note
                :speaker-name "Priya Raghavan" :speaker-email "priya@example.com"
                :speaker-title "VP Underwriting" :speaker-org "Meridian Mutual"
                :speaker-bio "Priya runs underwriting technology."}]
    (sub/create-submission! event (sub/parse-answers ff params)
                            (sub/parse-speaker params) "form" "speaker")))

(defn- pushes [event-id]
  (filterv #(= "pc-push" (:kind %)) (mail/history event-id)))

;; --- :pc-push ---------------------------------------------------------------

(deftest pc-push-fires-on-submission-test
  (let [event (setup!)]
    (submit! event)
    (store/await-sinks!)

    (testing "one push per committee member, and only to them"
      (let [ps (pushes (:id event))]
        (is (= 3 (count ps)))
        (is (= #{"gene@example.com" "ann@example.com" "alex@example.com"}
               (set (map :to ps))))
        (is (not-any? #(= "priya@example.com" (:to %)) ps)
            "the speaker does not get the committee's mail")))

    (testing "the subject names the event and the talk"
      (let [p (first (pushes (:id event)))]
        (is (= "[Sink Test Summit] New submission: Rebuilding underwriting"
               (:subject p)))))

    (testing "reply-to reaches the organizers"
      (is (= "annp@example.com" (:reply-to (first (pushes (:id event)))))))

    (testing "it renders, never claims to send, without SMTP"
      (is (every? #(= "email.queued" (:type %)) (pushes (:id event))))
      (is (not-any? :sent? (pushes (:id event)))))))

(deftest pc-push-carries-the-whole-proposal-test
  (let [event (setup!)
        submission (submit! event)
        _ (Thread/sleep 400)
        body (:body (first (pushes (:id event))))]

    (testing "every answer appears under its own LABEL — the BusyConf anatomy"
      (doseq [[label answer] [["Talk title" "Rebuilding underwriting"]
                              ["Abstract" "How we did it, and what broke."]
                              ["Session format" "Experience Report"]
                              ["Organization size" ">10,000"]
                              ["Industry" "Insurance"]
                              ["What measurable outcomes can you share?"
                               "Quote turnaround 6.2 days to 1.4."]]]
        (is (str/includes? body label) (str "missing label: " label))
        (is (str/includes? body answer) (str "missing answer: " answer))))

    (testing "the PRIVATE note IS included — this mail's audience is the committee"
      (is (str/includes? body private-note))
      (is (str/includes? body "Notes to the Planning Committee"))
      (is (str/includes? body "[PC ONLY]") "and it is marked as such"))

    (testing "the speaker block is inline, so nobody has to click to judge"
      (is (str/includes? body "Priya Raghavan"))
      (is (str/includes? body "Meridian Mutual"))
      (is (str/includes? body "priya@example.com"))
      (is (str/includes? body "Priya runs underwriting technology.")))

    (testing "one link, to this submission's row"
      (is (str/includes? body (str (sinks/public-base-url)
                                   "/events/sink-test/submissions/" (:id submission))))
      (is (str/includes? body "Rate and comment:")))

    (testing "and it says why they received it"
      (is (str/includes? body "programming committee")))))

(deftest pc-push-only-on-creation-test
  (let [event (setup!)
        submission (submit! event)]
    (Thread/sleep 400)
    (let [before (count (pushes (:id event)))]
      (reviews/set-status! (:id submission) "Accepted" "gene@example.com")
      (inform/inform! event (store/submission-by-id (:id submission)) "gene@example.com")
      (Thread/sleep 400)
      (testing "a status change or an inform does NOT re-push the proposal"
        (is (= before (count (pushes (:id event)))))))))

(deftest pc-push-can-be-turned-off-test
  (let [event (setup!)]
    (testing "it is on by default"
      (is (sinks/pc-push-enabled? event)))
    (testing "and off when the event says so"
      (is (not (sinks/pc-push-enabled?
                 (assoc-in event [:settings :pc-push-enabled] false)))))))

;; --- :airtable --------------------------------------------------------------

(deftest airtable-payload-test
  (let [event (setup!)
        submission (submit! event)
        fields (sinks/airtable-fields event (store/submission-by-id (:id submission))
                                      "https://cfp.example.com")]

    (testing "the row carries what an automation needs"
      (is (= "Rebuilding underwriting" (get fields "Title")))
      (is (= "Priya Raghavan" (get fields "Speaker")))
      (is (= "Meridian Mutual" (get fields "Organization")))
      (is (= "Experience Report" (get fields "Format")))
      (is (= ">10,000" (get fields "Org Size")))
      (is (= "Pending" (get fields "Status")))
      (is (false? (get fields "Notified")))
      (is (= (str "https://cfp.example.com/events/sink-test/submissions/"
                  (:id submission))
             (get fields "URL")))
      (is (= (:id submission) (get fields "Submission ID"))))

    (testing "NO private field reaches a third-party base, by any path"
      (is (not (str/includes? (pr-str fields) private-note)))
      (is (not (contains? fields "Notes to the Planning Committee")))
      (is (not (str/includes? (pr-str fields) "notes-to-committee"))))

    (testing "the upsert merges on OUR id, so a status change updates one row"
      (let [payload (sinks/airtable-payload event (store/submission-by-id (:id submission))
                                            "https://cfp.example.com")]
        (is (= ["Submission ID"] (get-in payload ["performUpsert" "fieldsToMergeOn"])))
        (is (= 1 (count (get payload "records"))))))))

(deftest airtable-sink-fires-test
  (let [event (setup!)
        calls (atom [])]
    ;; Attach an Airtable config the way the Settings form does.
    (store/append! {:type "event.updated" :actor "kaocha" :event-id (:id event)
                    :payload {:id (:id event) :slug "sink-test"
                              :changed ["airtable"] :before {}
                              :changes {:settings (assoc (:settings event)
                                                         :airtable
                                                         {:base-id "appTEST"
                                                          :table "Submissions"
                                                          :token "pat_secret"})}}})
    (binding [sinks/*http-post* (fn [url token body]
                                  (swap! calls conj {:url url :token token :body body})
                                  {:status 200 :body "{}"})]
      (let [submission (submit! event)]
        (Thread/sleep 400)

        (testing "a submission mirrors one row"
          (is (= 1 (count @calls)))
          (let [c (first @calls)]
            (is (= "https://api.airtable.com/v0/appTEST/Submissions" (:url c)))
            (is (= "pat_secret" (:token c)))
            (is (= "Rebuilding underwriting"
                   (get-in (:body c) ["records" 0 "fields" "Title"])))
            (is (= (str (sinks/public-base-url)
                        "/events/sink-test/submissions/" (:id submission))
                   (get-in (:body c) ["records" 0 "fields" "URL"]))
                "the mirrored row has a browser-clickable absolute URL")))

        (testing "a status change mirrors again, upserting the same row"
          (reset! calls [])
          (reviews/set-status! (:id submission) "Accepted" "gene@example.com")
          (Thread/sleep 400)
          (is (= 1 (count @calls)))
          (is (= "Accepted" (get-in (first @calls) [:body "records" 0 "fields" "Status"])))
          (is (= (:id submission)
                 (get-in (first @calls) [:body "records" 0 "fields" "Submission ID"]))
              "same merge key, so Airtable updates rather than appends"))

        (testing "and the private note is absent from every call made"
          (is (not (str/includes? (pr-str @calls) private-note))))

        (testing "an unrelated event type does not call Airtable"
          (reset! calls [])
          (reviews/add-comment! (:id submission)
                                (:id (store/person-by-email "gene@example.com"))
                                "A comment." "gene@example.com")
          (Thread/sleep 400)
          (is (empty? @calls)))))))

(deftest airtable-failure-does-not-break-the-write-test
  (let [event (setup!)]
    (store/append! {:type "event.updated" :actor "kaocha" :event-id (:id event)
                    :payload {:id (:id event) :slug "sink-test"
                              :changed ["airtable"] :before {}
                              :changes {:settings (assoc (:settings event)
                                                         :airtable
                                                         {:base-id "appTEST" :table "T"
                                                          :token "x"})}}})
    (binding [sinks/*http-post* (fn [_ _ _] {:status 500 :body "boom"})]
      (testing "a failing Airtable does not fail the submission"
        (let [submission (submit! event)]
          (Thread/sleep 400)
          (is (some? submission))
          (is (= 1 (count (store/submissions-for-event (:id event)))))))
      (testing "and the failure is recorded for the Settings page"
        (is (some #(and (= :airtable (:sink-type %)) (false? (:ok %)))
                  @store/deliveries))))))

;; --- :slack -----------------------------------------------------------------

(defn- slack-set!
  "Configure Slack the way the Settings form does."
  [handler as slug params]
  (handler (as (mock/request :post (str "/api/events/" slug "/slack/set") params))))

(deftest slack-groups-are-organizer-words-not-our-event-types-test
  (testing "the four moments an organizer thinks in"
    (is (= ["arrival" "review" "decision" "notified"]
           (mapv :key sinks/slack-event-groups)))
    (is (= ["A talk arrives" "The committee acts" "A decision is made" "A speaker is told"]
           (mapv :label sinks/slack-event-groups))))

  (testing "arrival and decisions are on by default — the two moments someone
            outside the tool still needs to know about"
    (is (= ["arrival" "decision"] sinks/default-slack-groups)))

  (testing "a group flattens to the event types behind it"
    (is (= ["submission.created"] (sinks/types-for-groups ["arrival"])))
    (is (= ["rating.set" "comment.added"] (sinks/types-for-groups ["review"])))
    (is (= ["submission.created" "submission.status-changed" "submission.priority-toggled"]
           (sinks/types-for-groups ["arrival" "decision"])))
    (is (= [] (sinks/types-for-groups []))))

  (testing "an unknown key is ignored, because a checkbox list is user input"
    (is (= ["submission.created"] (sinks/types-for-groups ["arrival" "nonsense"]))))

  (testing "and the mapping inverts, so the form re-renders from what was stored"
    (is (= ["arrival" "decision"]
           (sinks/groups-from-types ["submission.created" "submission.status-changed"])))))

(deftest slack-message-shapes-test
  (let [event (setup!)
        submission (submit! event)
        gene (store/person-by-email "gene@example.com")
        base "https://cfp.example.com"
        url (str base "/events/sink-test/submissions/" (:id submission))
        msg (fn [ev] (sinks/slack-message base ev))]

    (testing "A TALK ARRIVES — the title links to the row, the speaker is named"
      (let [m (msg {:type "submission.created" :actor "speaker"
                    :payload (store/submission-by-id (:id submission))})]
        (is (str/includes? (get m "text") "New submission"))
        (is (str/includes? (get m "text") "Rebuilding underwriting"))
        (is (str/includes? (get m "text") "Priya Raghavan"))
        (let [blocks (get m "blocks")]
          (is (= 2 (count blocks)) "compact — this lands in a busy channel")
          (is (str/includes? (get-in blocks [0 "text" "text"])
                             (str "<" url "|Rebuilding underwriting>"))
              "one click to the submission")
          (is (str/includes? (get-in blocks [1 "elements" 0 "text"]) "Meridian Mutual"))
          (is (str/includes? (get-in blocks [1 "elements" 0 "text"]) "Experience Report"))
          (is (str/includes? (get-in blocks [1 "elements" 0 "text"]) "Sink Test Summit")))))

    (testing "A RATING — who rated what, and the new average"
      (reviews/set-rating! (:id submission) (:id gene) 4.0 "gene@example.com")
      (let [ev (last (filter #(= "rating.set" (:type %)) (store/read-events)))
            m (msg ev)]
        (is (str/includes? (get m "text") "Gene Kim"))
        (is (str/includes? (get m "text") "4"))
        (is (str/includes? (get m "text") "now averaging 4"))
        (is (str/includes? (get-in m ["blocks" 0 "text" "text"]) "Gene Kim"))
        (is (str/includes? (get-in m ["blocks" 1 "elements" 0 "text"])
                           "Now averaging *4* from 1 review"))))

    (testing "a re-rating says where it moved FROM — the interesting fact"
      (reviews/set-rating! (:id submission) (:id gene) 2.0 "gene@example.com")
      (let [ev (last (filter #(= "rating.set" (:type %)) (store/read-events)))]
        (is (str/includes? (get-in (msg ev) ["blocks" 0 "text" "text"]) "(was 4)"))))

    (testing "A COMMENT — the words themselves, quoted"
      (reviews/add-comment! (:id submission) (:id gene)
                            "The controls story is the talk." "gene@example.com")
      (let [ev (last (filter #(= "comment.added" (:type %)) (store/read-events)))
            m (msg ev)]
        (is (str/includes? (get m "text") "Gene Kim"))
        (is (str/includes? (get-in m ["blocks" 0 "text" "text"])
                           ">The controls story is the talk."))))

    (testing "A DECISION — what changed, from what to what, by whom"
      (reviews/set-status! (:id submission) "Accepted" "gene@example.com")
      (let [ev (last (filter #(= "submission.status-changed" (:type %)) (store/read-events)))
            m (msg ev)]
        (is (str/includes? (get m "text") "Pending → Accepted"))
        (is (str/includes? (get-in m ["blocks" 0 "text" "text"]) "*Pending* → *Accepted*"))
        (is (str/includes? (get-in m ["blocks" 0 "text" "text"]) url))
        (is (str/includes? (get-in m ["blocks" 1 "elements" 0 "text"]) "gene@example.com"))))

    (testing "A FLAG — the 🔥 that means 'talk about this one on the call'"
      (reviews/toggle-priority! (:id submission) "gene@example.com")
      (let [ev (last (filter #(= "submission.priority-toggled" (:type %)) (store/read-events)))]
        (is (str/includes? (get-in (msg ev) ["blocks" 0 "text" "text"]) "Flagged for the call"))))

    (testing "A SPEAKER IS TOLD"
      (inform/inform! event (store/submission-by-id (:id submission)) "gene@example.com")
      (let [ev (last (filter #(= "submission.notified" (:type %)) (store/read-events)))
            m (msg ev)]
        (is (str/includes? (get m "text") "Speaker told"))
        (is (str/includes? (get-in m ["blocks" 0 "text" "text"]) "Accepted"))
        (is (str/includes? (get-in m ["blocks" 1 "elements" 0 "text"]) "priya@example.com"))))

    (testing "and NO private answer reaches the channel, on any message"
      (doseq [t ["submission.created" "rating.set" "comment.added"
                 "submission.status-changed" "submission.notified"]]
        (let [ev (last (filter #(= t (:type %)) (store/read-events)))]
          (is (not (str/includes? (pr-str (msg ev)) private-note))
              (str t " leaked the notes-to-committee answer")))))))

(deftest slack-sink-fires-on-the-chosen-moments-only-test
  (let [event (setup!)
        handler (server/create-app)
        cookie (let [t (auth/issue-token! "gene@example.com")
                     r (handler (mock/request :get (str "/auth/" t)))]
                 (first (str/split (first (get-in r [:headers "Set-Cookie"])) #";")))
        as #(mock/header % "cookie" cookie)
        posts (atom [])]
    (slack-set! handler as "sink-test"
                {"webhook-url" "https://hooks.slack.com/services/T000/B000/xyz"
                 "groups" "arrival"})
    (binding [sinks/*slack-post* (fn [url body] (swap! posts conj {:url url :body body})
                                   {:status 200 :body "ok"})]
      (let [submission (submit! event)]
        (Thread/sleep 500)

        (testing "the arrival posts, to the configured hook"
          (is (= 1 (count @posts)))
          (is (= "https://hooks.slack.com/services/T000/B000/xyz" (:url (first @posts))))
          (is (str/includes? (get-in (first @posts) [:body "text"]) "New submission")))

        (testing "a rating does NOT — 'the committee acts' was not ticked"
          (reset! posts [])
          (reviews/set-rating! (:id submission)
                               (:id (store/person-by-email "gene@example.com"))
                               5.0 "gene@example.com")
          (Thread/sleep 400)
          (is (empty? @posts)))))))

(deftest failing-slack-never-fails-a-submission-test
  (let [event (setup!)
        handler (server/create-app)
        cookie (let [t (auth/issue-token! "gene@example.com")
                     r (handler (mock/request :get (str "/auth/" t)))]
                 (first (str/split (first (get-in r [:headers "Set-Cookie"])) #";")))
        as #(mock/header % "cookie" cookie)]
    (slack-set! handler as "sink-test"
                {"webhook-url" "https://hooks.slack.com/services/T0/B0/dead"
                 "groups" "arrival"})
    (binding [sinks/*slack-post* (fn [_ _] {:status 404 :body "no_service"})]
      (testing "a dead webhook does not cost the speaker their submission"
        (let [submission (submit! event)]
          (Thread/sleep 500)
          (is (some? submission))
          (is (= 1 (count (store/submissions-for-event (:id event)))))
          (is (= "Rebuilding underwriting"
                 (get-in (store/submission-by-id (:id submission)) [:answers :talk-title])))))
      (testing "and the failure is visible, not swallowed"
        (is (some #(and (= :slack (:sink-type %)) (false? (:ok %)))
                  @store/deliveries))))

    (binding [sinks/*slack-post* (fn [_ _] (throw (java.io.IOException. "connection refused")))]
      (testing "a network that is simply gone is the same story"
        (let [before (count (store/submissions-for-event (:id event)))]
          (submit! event)
          (Thread/sleep 500)
          (is (= (inc before) (count (store/submissions-for-event (:id event))))))))))

(deftest slack-settings-page-test
  (let [event (setup!)
        handler (server/create-app)
        cookie (let [t (auth/issue-token! "gene@example.com")
                     r (handler (mock/request :get (str "/auth/" t)))]
                 (first (str/split (first (get-in r [:headers "Set-Cookie"])) #";")))
        as #(mock/header % "cookie" cookie)
        page #(:body (handler (as (mock/request :get "/events/sink-test/settings"))))]

    (testing "the page offers Slack and says what will NOT be posted"
      (let [body (page)]
        (is (str/includes? body "Slack"))
        (is (str/includes? body "incoming-webhook"))
        (is (str/includes? body "Private answers are never posted"))
        (is (str/includes? body "A talk arrives"))
        (is (str/includes? body "A decision is made"))))

    (testing "a URL with no moments ticked is refused — that is just a stored secret"
      (let [r (slack-set! handler as "sink-test"
                          {"webhook-url" "https://hooks.slack.com/services/T1/B1/aaa"})]
        (is (= 422 (:status r)))
        (is (str/includes? (:body r) "at least one moment"))
        (is (nil? (get-in (events/event-by-slug "sink-test") [:settings :slack])))))

    (testing "moments with no URL is refused too"
      (is (= 422 (:status (slack-set! handler as "sink-test" {"groups" "arrival"})))))

    (testing "saving stores the hook and what it posts about"
      (slack-set! handler as "sink-test"
                  {"webhook-url" "https://hooks.slack.com/services/T1/B1/supersecret"
                   "groups" "arrival"})
      (let [slack (get-in (events/event-by-slug "sink-test") [:settings :slack])]
        (is (= ["arrival"] (:groups slack)))
        (is (= ["submission.created"] (:events slack))
            "the flattened type list the fan-out filters on")))

    (testing "and the page proves it without handing the credential back"
      (let [body (page)]
        (is (str/includes? body "Send a test message"))
        (is (str/includes? body "A talk arrives"))
        (is (not (str/includes? body "supersecret"))
            "a webhook URL is a credential — anyone holding it can post")
        (is (str/includes? body "•"))))

    (testing "the test button reports SUCCESS in plain language"
      (binding [sinks/*slack-post* (fn [_ _] {:status 200 :body "ok"})]
        (let [r (handler (as (mock/request :post "/api/events/sink-test/slack/test")))]
          (is (= 303 (:status r)))
          (is (str/includes? (page) "Check the channel")))))

    (testing "and reports the ACTUAL failure, in words, when Slack refuses"
      (binding [sinks/*slack-post* (fn [_ _] {:status 404 :body "no_service"})]
        (let [r (handler (as (mock/request :post "/api/events/sink-test/slack/test")))]
          (is (= 422 (:status r)))
          (is (str/includes? (:body r) "no_service"))
          (is (str/includes? (:body r) "deleted in Slack")))))

    (testing "a test message is shaped like a real one"
      (let [m (sinks/test-message (events/event-by-slug "sink-test") "Gene Kim")]
        (is (str/includes? (get m "text") "Sink Test Summit"))
        (is (= 2 (count (get m "blocks"))))
        (is (str/includes? (get-in m ["blocks" 1 "elements" 0 "text"]) "Gene Kim"))))

    (testing "removing it takes the hook away"
      (handler (as (mock/request :post "/api/events/sink-test/slack/remove")))
      (is (nil? (get-in (events/event-by-slug "sink-test") [:settings :slack])))
      (is (not (str/includes? (page) "Send a test message"))))

    (is (some? event))))

;; --- :gcs-snapshot ----------------------------------------------------------

(deftest snapshot-destination-test
  (testing "a bare bucket name and a gs:// URL both work"
    (is (= "gs://my-bucket/store/events.jsonl" (sinks/snapshot-destination "my-bucket")))
    (is (= "gs://my-bucket/store/events.jsonl" (sinks/snapshot-destination "gs://my-bucket"))))
  (testing "unconfigured is nil, not an error"
    (is (nil? (sinks/snapshot-destination nil)))
    (is (nil? (sinks/snapshot-destination "  ")))))

(deftest snapshot-copies-the-log-test
  (let [dir (str (java.nio.file.Files/createTempDirectory
                   "bucket" (into-array java.nio.file.attribute.FileAttribute [])))
        dest (str dir "/events.jsonl")
        event (setup!)]
    (binding [blob/*copy-fn* (fn [src d] (io/copy (io/file src) (io/file d)) {:ok true})]
      (submit! event)
      ;; The PC push fires asynchronously and appends its own comms events, so
      ;; let the log settle before snapshotting — otherwise we are comparing a
      ;; point-in-time copy against a file that is still growing.
      (Thread/sleep 500)
      (testing "the snapshot is a byte-for-byte copy of the log"
        (sinks/snapshot-now! dest @store/store-path)
        (is (.exists (io/file dest)))
        (is (= (slurp @store/store-path) (slurp dest))))

      (testing "and it folds back to the same world"
        (is (= (:submissions @store/state)
               (:submissions (store/fold (store/read-events dest)))))))))

(deftest snapshot-restore-test
  (let [dir (str (java.nio.file.Files/createTempDirectory
                   "bucket" (into-array java.nio.file.attribute.FileAttribute [])))
        dest (str dir "/events.jsonl")
        event (setup!)]
    (binding [blob/*copy-fn* (fn [src d] (io/copy (io/file src) (io/file d)) {:ok true})]
      (submit! event)
      (Thread/sleep 500)                      ; let the async PC push settle
      (sinks/snapshot-now! dest @store/store-path)
      (let [original (slurp @store/store-path)
            local @store/store-path]

        (testing "restore REFUSES to clobber an existing local log"
          (is (nil? (sinks/restore-from-snapshot! dest local)))
          (is (= original (slurp local))))

        (testing "but restores when the box is fresh"
          (io/delete-file (io/file local))
          (is (true? (sinks/restore-from-snapshot! dest local)))
          (is (= original (slurp local))))

        (testing "and the restored log folds to the same state"
          (store/load!)
          (is (= 1 (count (store/submissions-for-event (:id event))))))

        (testing "with no bucket configured it is simply a no-op"
          (is (nil? (sinks/restore-from-snapshot! nil local))))))))

(deftest snapshot-is-debounced-test
  (let [copies (atom 0)
        dir (str (java.nio.file.Files/createTempDirectory
                   "bucket" (into-array java.nio.file.attribute.FileAttribute [])))
        dest (str dir "/events.jsonl")]
    (binding [blob/*copy-fn* (fn [src d]
                               (swap! copies inc)
                               (io/copy (io/file src) (io/file d))
                               {:ok true})]
      (let [event (setup!)]
        ;; Ten appends in a burst, like a replay.
        (dotimes [_ 10]
          (store/deliver-sink! {:type :gcs-snapshot :dest dest :debounce-ms 150}
                               {:type "noop"}))
        (Thread/sleep 700)
        (testing "ten appends produce ONE copy, not ten"
          (is (= 1 @copies)))
        (is (some? event))))))

;; --- Settings page ----------------------------------------------------------

(deftest airtable-settings-page-test
  (let [event (setup!)
        handler (server/create-app)
        cookie (let [t (auth/issue-token! "gene@example.com")
                     r (handler (mock/request :get (str "/auth/" t)))]
                 (first (str/split (first (get-in r [:headers "Set-Cookie"])) #";")))
        as #(mock/header % "cookie" cookie)
        page #(:body (handler (as (mock/request :get "/events/sink-test/settings"))))]

    (testing "the page offers Airtable and explains the committee push"
      (let [body (page)]
        (is (str/includes? body "Connect Airtable"))
        (is (str/includes? body "Private fields are never sent"))
        (is (str/includes? body "Committee push email"))
        (is (str/includes? body "whole proposal inline"))
        (is (str/includes? body "Enabled for this event"))))

    (testing "connecting stores the config and shows it"
      (handler (as (mock/request :post "/api/events/sink-test/airtable/set"
                                 {"base-id" "appABC" "table" "Talks" "token" "pat_x"})))
      (let [body (page)]
        (is (str/includes? body "appABC"))
        (is (str/includes? body "Talks"))
        (is (str/includes? body "Disconnect"))
        (is (not (str/includes? body "pat_x")) "the token is never echoed back")))

    (testing "disconnecting removes it"
      (handler (as (mock/request :post "/api/events/sink-test/airtable/remove")))
      (is (str/includes? (page) "Connect Airtable")))))

;; --- Prod magic-link --------------------------------------------------------

(deftest prod-magic-link-never-leaks-test
  (let [event (setup!)
        handler (server/create-app)
        post-login #(handler (mock/request :post "/api/login" {"email" %}))]

    (testing "PROD with no SMTP: no link, and it says to ask an organizer"
      (with-redefs [auth/dev? (constantly false)
                    mail/configured? (constantly false)]
        (let [body (str (:body (post-login "gene@example.com")))]
          (is (not (re-find #"/auth/[0-9a-f-]{36}" body))
              "a working credential must never be printed in production")
          (is (str/includes? body "no mail configured"))
          (is (str/includes? body "Ask an organizer")))))

    (testing "PROD with SMTP: still no link on the page — it goes by mail"
      (with-redefs [auth/dev? (constantly false)
                    mail/configured? (constantly true)]
        (let [body (str (:body (post-login "gene@example.com")))]
          (is (not (re-find #"/auth/[0-9a-f-]{36}" body)))
          (is (str/includes? body "on its way")))))

    (testing "DEV: the link IS shown, because otherwise you cannot get in"
      (with-redefs [auth/dev? (constantly true)]
        (let [body (str (:body (post-login "gene@example.com")))]
          (is (re-find #"/auth/[0-9a-f-]{36}" body))
          (is (str/includes? body "Found you on a committee")))))

    (testing "an unknown email leaks nothing in PROD — the enumeration guard"
      (with-redefs [auth/dev? (constantly false)]
        (let [body (str (:body (post-login "stranger@example.com")))]
          (is (not (re-find #"/auth/[0-9a-f-]{36}" body))))))

    (testing "DEV admits an unknown email — parity with prod's open Google
              sign-up (2026-08-10), which dev lacks; the stranger flow must be
              walkable locally"
      (with-redefs [auth/dev? (constantly true)]
        (let [body (str (:body (post-login "fresh-stranger@example.com")))]
          (is (re-find #"/auth/[0-9a-f-]{36}" body)))))
    (is (some? event))))

(deftest gs-uri-parse-and-guard-test
  (testing "parse-gs-uri"
    (is (= {:bucket "b" :object "store/events.jsonl"}
           (gcs/parse-gs-uri "gs://b/store/events.jsonl")))
    (is (= {:bucket "curtaincall" :object "a/b/c.jsonl"}
           (gcs/parse-gs-uri "gs://curtaincall/a/b/c.jsonl")))
    (is (nil? (gcs/parse-gs-uri "/tmp/events.jsonl")))
    (is (nil? (gcs/parse-gs-uri "gs://bucket-with-no-object"))))
  (testing "rest-copy! refuses two local paths without touching credentials"
    (is (= false (:ok (gcs/rest-copy! "/tmp/nope-a" "/tmp/nope-b"))))))

(deftest restore-mode-test
  ;; Found at the meter (2026-08-10): the deployed image ships a baked seed,
  ;; so default refuse-to-clobber skipped restore on EVERY boot and judge
  ;; state never survived a recycle. prefer-snapshot inverts the rule.
  (let [tmp (java.io.File/createTempFile "restore" ".jsonl")
        calls (atom [])]
    (spit tmp "baked-seed-line\n")
    (binding [blob/*copy-fn* (fn [src dest] (swap! calls conj [src dest]) {:ok true})]
      (testing "default mode: existing local file wins, no download"
        (is (nil? (sinks/restore-from-snapshot! "gs://b/store/events.jsonl" (.getPath tmp) nil)))
        (is (empty? @calls)))
      (testing "prefer-snapshot: downloads over the baked seed"
        (is (true? (sinks/restore-from-snapshot! "gs://b/store/events.jsonl" (.getPath tmp)
                                                 "prefer-snapshot")))
        (is (= 1 (count @calls))))
      (testing "a failed download keeps the local file"
        (binding [blob/*copy-fn* (fn [_ _] {:ok false :error "404"})]
          (is (nil? (sinks/restore-from-snapshot! "gs://b/store/events.jsonl" (.getPath tmp)
                                                  "prefer-snapshot")))
          (is (= "baked-seed-line\n" (slurp tmp)) "baked seed untouched on failure"))))
    (.delete tmp)))
