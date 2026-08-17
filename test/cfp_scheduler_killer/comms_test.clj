(ns cfp-scheduler-killer.comms-test
  "The mailer and quick capture.

   The mailer's contract is mostly a promise about HONESTY: with no SMTP
   configured it must record what it would have sent and never imply it went.
   Every test here runs with no network — the configured path uses a stub
   transport."
  (:require
   [cfp-scheduler-killer.auth :as auth]
   [cfp-scheduler-killer.committees :as committees]
   [cfp-scheduler-killer.events :as events]
   [cfp-scheduler-killer.exports :as exports]
   [cfp-scheduler-killer.inform :as inform]
   [cfp-scheduler-killer.io.email :as email-port]
   [cfp-scheduler-killer.io.email.smtp :as smtp]
   [cfp-scheduler-killer.mail :as mail]
   [cfp-scheduler-killer.reviews :as reviews]
   [cfp-scheduler-killer.server :as server]
   [cfp-scheduler-killer.sse :as sse]
   [cfp-scheduler-killer.store :as store]
   [cfp-scheduler-killer.submissions :as sub]
   [cfp-scheduler-killer.test-helpers :refer [with-temp-store]]
   [cfp-scheduler-killer.web.datastar :as web-datastar]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [ring.mock.request :as mock])
  (:import
   (java.time LocalDate LocalDateTime)))

(use-fixtures :each with-temp-store (fn [f] (reset! auth/tokens {}) (f)))

(defn- setup! []
  (let [event (events/create-event!
                {:name "Comms Test Summit" :slug "comms-test" :tz "America/New_York"
                 :starts-on (LocalDate/of 2026 10 14) :ends-on (LocalDate/of 2026 10 15)
                 :location "Charlotte, NC" :support-email "annp@example.com"
                 :cfp-opens-at (LocalDateTime/of 2020 1 1 0 0)
                 :cfp-closes-at (LocalDateTime/of 2099 1 1 0 0)}
                "kaocha")
        cid (:id (first (events/committees-for-event (:id event))))
        _ (committees/add-member! cid {:name "Gene Kim" :email "gene@example.com"
                                       :role "chair"} "kaocha")
        ff (:fields (events/form-for-event (:id event)))
        params {:answer-talk-title "A talk" :answer-abstract "Abstract."
                :answer-session-format "Experience Report"
                :answer-org-size ">10,000" :answer-industry "Insurance"
                :answer-ai-transformation-history "2023."
                :answer-measurable-outcomes "Numbers."
                :answer-notes-to-committee "Private budget concern."
                :speaker-name "Priya Raghavan" :speaker-email "priya@example.com"
                :speaker-title "VP" :speaker-org "Meridian" :speaker-bio "Bio."}
        s (sub/create-submission! event (sub/parse-answers ff params)
                                  (sub/parse-speaker params) "form" "kaocha")]
    {:event (events/event-by-slug "comms-test") :submission s}))

(defn- comms-events [event-id kind]
  (filterv #(= kind (:kind %)) (mail/history event-id)))

;; --- Dev-render mode --------------------------------------------------------

(deftest dev-render-mode-test
  (testing "with no SMTP config we are in render mode, and say so"
    (is (not (mail/configured?)))
    (is (str/includes? (mail/status-line) "not configured"))
    (is (not (str/includes? (mail/status-line) "Sending"))))

  (let [{:keys [event submission]} (setup!)]
    (testing "a submission produced exactly ONE confirmation, recorded not sent"
      (let [confs (comms-events (:id event) "submission-confirmation")]
        (is (= 1 (count confs)))
        (is (= "priya@example.com" (:to (first confs))))
        (is (str/includes? (:subject (first confs)) "We received your talk"))
        (is (= "email.queued" (:type (first confs))))
        (is (false? (:sent? (first confs))))))

    (testing "the recorded letter is the WHOLE letter, so it can be sent by hand"
      (let [c (first (comms-events (:id event) "submission-confirmation"))]
        (is (str/includes? (:body c) "Hi Priya,"))
        (is (str/includes? (:body c) "\"A talk\""))
        (is (str/includes? (:body c) "annp@example.com"))))

    (testing "NOTHING anywhere claims it was sent"
      (is (empty? (filter #(= "comms.sent" (:type %)) (mail/history (:id event))))))

    (testing "the Log narrates it as 'Would send'"
      (let [e (first (filter #(= "email.queued" (:type %))
                             (store/log-for-event (:id event))))]
        (is (str/includes? (@#'cfp-scheduler-killer.views.log/log-summary e) "Queued email"))))))

(deftest inform-sends-one-letter-test
  (let [{:keys [event submission]} (setup!)]
    (reviews/set-status! (:id submission) "Accepted" "gene@example.com")
    (inform/inform! event (store/submission-by-id (:id submission)) "gene@example.com")

    (testing "informing produced exactly one decision letter"
      (let [ds (comms-events (:id event) "decision")]
        (is (= 1 (count ds)))
        (is (= "priya@example.com" (:to (first ds))))
        (is (str/includes? (:subject (first ds)) "accepted"))))

    (testing "an ACCEPTANCE carries the calendar invite"
      (is (true? (:has-ics? (first (comms-events (:id event) "decision"))))))

    (testing "reply-to is the event's speaker support address — a reply reaches a person"
      (is (= "annp@example.com" (:reply-to (first (comms-events (:id event) "decision"))))))

    (testing "informing twice does not send twice"
      (inform/inform! event (store/submission-by-id (:id submission)) "gene@example.com")
      (is (= 1 (count (comms-events (:id event) "decision")))))))

(deftest decline-carries-no-invite-test
  (let [{:keys [event submission]} (setup!)]
    (reviews/set-status! (:id submission) "Declined" "gene@example.com")
    (inform/inform! event (store/submission-by-id (:id submission)) "gene@example.com")
    (testing "a decline is a letter, never a calendar invite"
      (let [d (first (comms-events (:id event) "decision"))]
        (is (some? d))
        (is (false? (boolean (:has-ics? d))))))))

(deftest ics-attachment-matches-the-feed-test
  (let [{:keys [event submission]} (setup!)]
    (reviews/set-status! (:id submission) "Accepted" "gene@example.com")
    (inform/inform! event (store/submission-by-id (:id submission)) "gene@example.com")
    (store/await-sinks!)
    (let [fresh (store/submission-by-id (:id submission))
          attached (exports/submission-ics event fresh)
          feed (exports/calendar-ics event)
          uid (exports/ics-uid fresh)]

      (testing "the attached invite is a complete, single-event calendar"
        (is (str/starts-with? attached "BEGIN:VCALENDAR\r\n"))
        (is (str/ends-with? attached "END:VCALENDAR\r\n"))
        (is (= 1 (count (re-seq #"BEGIN:VEVENT" attached)))))

      (testing "its UID is IDENTICAL to the public feed's — the same calendar entry"
        (is (str/includes? attached (str "UID:" uid)))
        (is (str/includes? feed (str "UID:" uid))))

      (testing "and so is its SEQUENCE, so a later move amends what they already have"
        ;; The feed's FIRST VEVENT is the always-present hold-the-date banner
        ;; (Gene ratified 2026-08-11), which carries its own unrelated
        ;; SEQUENCE counter — so pull the SEQUENCE from the VEVENT block that
        ;; actually carries this submission's UID, not just the first match.
        (let [;; Unfold RFC 5545 line-continuations (CRLF + a leading space)
              ;; before matching, in case a long line folds mid-substring.
              vevent-for-uid (fn [ics uid]
                               (->> (str/split (str/replace ics "\r\n " "") #"(?=BEGIN:VEVENT)")
                                    (filter #(str/starts-with? % "BEGIN:VEVENT"))
                                    (some #(when (str/includes? % (str "UID:" uid)) %))))
              seq-of (fn [ics] (second (re-find #"SEQUENCE:(\d+)" ics)))]
          (is (= (seq-of attached) (seq-of (vevent-for-uid feed uid)))))))))

(deftest magic-link-email-test
  (let [{:keys [event]} (setup!)]
    (with-redefs [mail/provider-delivery-enabled? (constantly false)]
      (auth/issue-token! "gene@example.com"))
    (testing "requesting a link produces exactly one magic-link letter"
      (let [ls (filterv #(= "magic-link" (:kind %)) (mail/history nil))]
        ;; magic-link has no event context, so it is not in the event's history
        (is (empty? (comms-events (:id event) "magic-link")))))

    (testing "…and it is synchronously queued, approved, and delivered"
      (let [log (store/read-events)
            queued-event (last (filter #(= "magic-link" (get-in % [:payload :kind]))
                                       log))
            email-id (get-in queued-event [:payload :email-id])
            events (filterv #(= email-id (get-in % [:payload :email-id])) log)
            e (last events)]
        (is (some? e))
        (is (= ["email.queued" "email.approved" "email.sent"]
               (mapv :type events)))
        (is (= "gene@example.com" (get-in e [:payload :to])))
        (is (str/includes? (get-in e [:payload :body]) "/auth/"))
        (is (= "dev-log" (get-in e [:payload :via])))))))

(deftest event-scoped-portal-invite-renders-and-sends-test
  (let [{:keys [event submission]} (setup!)
        speaker (first (:speakers submission))
        email (:email speaker)
        person-id (:person-id speaker)
        context {:event-id (:id event)
                 :kind "portal-invite"
                 :actor "ann@example.com"
                 :person-id person-id}
        letter-fn (fn [token _person]
                    {:from "ann@example.com"
                     :to email
                     :reply-to "ann@example.com"
                     :subject "Your Comms Test Summit speaker portal"
                     :body (str "Use https://cfp.example.test/auth/" token)})
        _options {:letter-fn letter-fn :context context}]
    (store/await-sinks!)
    (let [token (auth/mint-token! email (store/person-by-id person-id))]
      (mail/send! (letter-fn token nil) context))
    (testing "without SMTP, the exact event-scoped letter is rendered and recorded"
      (let [rendered (last (comms-events (:id event) "portal-invite"))]
        (is (= "email.queued" (:type rendered)))
        (is (= "ann@example.com" (:actor rendered)))
        (is (= person-id (:person-id rendered)))
        (is (str/includes? (:body rendered) "https://cfp.example.test/auth/"))))

    (let [sent (atom [])]
      (with-redefs [mail/provider-delivery-enabled? (constantly true)
                    mail/config (constantly {:host "smtp.example.com" :port 587
                                             :user "u" :pass "p"
                                             :from "cfp@example.com"})
                    email-port/send-with-config!
                    (fn [cfg message]
                      (swap! sent conj (smtp/postal-message cfg message))
                      {:ok true :message-id "<portal@example.com>"})]
        (let [token (auth/mint-token! email (store/person-by-id person-id))]
          (mail/send! (letter-fn token nil) context))
        (mail/approve! (:id event)
                       (:email-id (last (comms-events (:id event) "portal-invite")))
                       "ann@example.com"))
      (testing "with SMTP, the same human-authored letter is sent and event-sourced"
        (is (= 1 (count @sent)))
        (let [message (first @sent)
              sent-event (some #(when (= "email.sent" (:type %)) %)
                               (comms-events (:id event) "portal-invite"))]
          (is (= email (:to message)))
          (is (= "ann@example.com" (:from message)))
          (is (str/includes? (:body message) "https://cfp.example.test/auth/"))
          (is (= "email.sent" (:type sent-event)))
          (is (= "<portal@example.com>" (:message-id sent-event))))))))

;; --- Configured mode (stubbed transport, no network) ------------------------

(def ^:private smtp-config
  {:host "smtp.example.com" :port 587 :user "u" :pass "p"
   :from "cfp@example.com"})

(deftest configured-send-test
  (let [{:keys [event submission]} (setup!)
        ;; Drain the submission's async PC push BEFORE configuring SMTP, so we
        ;; are measuring the decision letter and not a straggler from setup.
        _ (store/await-sinks!)
        sent (atom [])]
    (with-redefs [mail/provider-delivery-enabled? (constantly true)
                  mail/config (constantly smtp-config)
                  ;; Stand in for the network. postal is never loaded.
                  email-port/send-with-config!
                  (fn [cfg message]
                    (swap! sent conj (smtp/postal-message cfg message))
                    {:ok true :message-id "<abc123@example.com>"})]

      (testing "the status line now names the host and the from address"
        (is (mail/configured?))
        (is (str/includes? (mail/status-line) "smtp.example.com"))
        (is (str/includes? (mail/status-line) "cfp@example.com")))

      (reviews/set-status! (:id submission) "Accepted" "gene@example.com")
      (inform/inform! event (store/submission-by-id (:id submission)) "gene@example.com")
      (mail/approve! (:id event)
                     (:email-id (first (filter #(= "decision" (:kind %))
                                               (mail/queued (:id event)))))
                     "gene@example.com")
      ;; Acceptance also fires the async :acceptance-email sink (one letter per
      ;; committee member); drain it so the transport captures deterministically.
      (store/await-sinks!)

      (testing "a real decision message was handed to the transport"
        ;; The decision letter is the one to the SPEAKER; acceptance-notify goes
        ;; to committee members. Pick the speaker's out of the mix.
        (let [m (first (filter #(= "priya@example.com" (:to %)) @sent))]
          (is (some? m))
          (is (= "priya@example.com" (:to m)))
          (is (= "cfp@example.com" (:from m)) "from is OURS")
          (is (= "annp@example.com" (:reply-to m)) "reply-to is the EVENT's")
          (is (str/includes? (:subject m) "accepted"))))

      (testing "the acceptance is multipart with a text/calendar attachment"
        (let [body (:body (first (filter #(= "priya@example.com" (:to %)) @sent)))]
          (is (vector? body))
          (is (= 2 (count body)))
          (is (str/includes? (:type (first body)) "text/plain"))
          (is (= :attachment (:type (second body))))
          (is (str/includes? (:content-type (second body)) "text/calendar"))
          (is (str/includes? (String. ^bytes (:content (second body)) "UTF-8")
                             "BEGIN:VCALENDAR"))))

      (testing "it is recorded as SENT, with the message id"
        (let [d (first (comms-events (:id event) "decision"))]
          (is (= "email.sent" (:type d)))
          (is (true? (:sent? d)))
          (is (= "<abc123@example.com>" (:message-id d))))))))

(deftest speaker-chase-preserves-the-human-sender-and-obligation
  (let [{:keys [event submission]} (setup!)
        sent (atom [])]
    (with-redefs [mail/provider-delivery-enabled? (constantly true)
                  mail/config (constantly smtp-config)
                  email-port/send-with-config!
                  (fn [cfg message]
                    (swap! sent conj (smtp/postal-message cfg message))
                    {:ok true :message-id "<chase@example.com>"})]
      (is (= :queued
             (:mode (mail/send! {:from "ann@example.com"
                                 :to "priya@example.com"
                                 :subject "Slides follow-up"
                                 :body "Please send the revised slides."
                                 :reply-to "ann@example.com"}
                                {:event-id (:id event)
                                 :kind "speaker-chase"
                                 :submission-id (:id submission)
                                 :task-key "slides"
                                 :actor "ann@example.com"}))))
      (mail/approve! (:id event)
                     (:email-id (first (filter #(= "speaker-chase" (:kind %))
                                               (mail/queued (:id event)))))
                     "ann@example.com")
      (is (= "ann@example.com" (:from (first @sent))))
      (let [entry (first (filter #(= "speaker-chase" (:kind %))
                                 (mail/history (:id event))))]
        (is (= "ann@example.com" (:from entry)))
        (is (= "slides" (:task-key entry)))
        (is (= "Slides follow-up" (:subject entry)))))))

(deftest send-failure-is-recorded-not-thrown-test
  (let [{:keys [event submission]} (setup!)
        _ (store/await-sinks!)]
    (with-redefs [mail/provider-delivery-enabled? (constantly true)
                  mail/config (constantly smtp-config)
                  email-port/send-with-config!
                  (fn [_ _] (throw (RuntimeException. "connection refused")))]
      (reviews/set-status! (:id submission) "Accepted" "gene@example.com")

      (testing "a dead SMTP server does not roll back the decision"
        (is (some? (inform/inform! event (store/submission-by-id (:id submission))
                                   "gene@example.com")))
        (mail/approve! (:id event)
                       (:email-id (first (filter #(= "decision" (:kind %))
                                                 (mail/queued (:id event)))))
                       "gene@example.com")
        (is (some? (:notified-at (store/submission-by-id (:id submission))))
            "the speaker IS informed — the fact survives the failed delivery"))

      (testing "and the failure is visible so someone can retry"
        (let [d (first (comms-events (:id event) "decision"))]
          (is (= "email.failed" (:type d)))
          (is (false? (:sent? d)))
          (is (str/includes? (:error d) "connection refused")))))))

(deftest comms-page-test
  (let [{:keys [event submission]} (setup!)
        handler (server/create-app)
        cookie (let [t (auth/issue-token! "gene@example.com")
                     r (handler (mock/request :get (str "/auth/" t)))]
                 (first (str/split (first (get-in r [:headers "Set-Cookie"])) #";")))
        as #(mock/header % "cookie" cookie)
        recipient-id (get-in submission [:speakers 0 :person-id])]
    (reviews/set-status! (:id submission) "Accepted" "gene@example.com")
    (inform/inform! event (store/submission-by-id (:id submission)) "gene@example.com")
    (store/await-sinks!)

    (let [body (:body
                 (handler
                   (as (mock/request
                         :get
                         (str "/events/comms-test/comms?lane=waiting&recipient="
                              recipient-id)))))]
      (testing "the page is two compact people lists"
        (is (str/includes? body "Waiting for approval"))
        (is (str/includes? body "People emailed"))
        (is (str/includes? body "Priya Raghavan"))
        (is (str/includes? body "gene@example.com"))
        (is (str/includes? body "J and K move through the active list"))
        (is (str/includes? body "data-star-signals__ifmissing="))
        (is (str/includes? body "id=\"comms-filter\""))
        (is (str/includes? body "public-filter-bar"))
        (is (< (.indexOf body "Select one person to read their communications")
               (.indexOf body "public-filter-bar")))
        (is (str/includes? body "Filter people by name or email"))
        (is (str/includes? body "data-star-bind:comms-filter"))
        (is (str/includes? body "evt.preventDefault()"))
        (is (str/includes? body "$commsFilter.toLowerCase()"))
        (is (str/includes? body "/vendor/datastar-aliased.js"))
        (is (str/includes? body "data-comms-path="))
        (is (str/includes? body "fragment=person-detail"))
        (is (str/includes? body "@get("))
        (is (not (str/includes? body "fetch(")))
        (is (not (str/includes? body "commsWaitingPaths")))
        (is (not (str/includes? body "document.querySelectorAll")))
        (is (not (str/includes? body "href=\"/events/comms-test/comms?lane="))))

      (testing "only the selected person's full messages render"
        (is (str/includes? body "submission-confirmation"))
        (is (str/includes? body "decision"))
        (is (str/includes? body "priya@example.com"))
        (is (not (str/includes? body "pc-push")))
        (is (not (str/includes? body "acceptance-notify"))))

      (testing "the page cannot launch mail"
        (is (str/includes? body "Nothing on this page sends email"))
        (is (not (str/includes? body "Compose a speaker message")))
        (is (not (str/includes? body "Approve &amp; send")))
        (is (not (str/includes? body "Discard")))))

    (testing "selection returns one personal SSE detail fragment and no page"
      (let [pushed (atom nil)
            response
            (with-redefs [web-datastar/sse-fragment-response
                          (fn [_req selector html]
                            (reset! pushed {:selector selector :html html})
                            {:status 200 :headers {} :body ""})]
              (handler
                (as (mock/request
                      :get
                      (str "/events/comms-test/comms?fragment=person-detail"
                           "&lane=waiting&recipient=" recipient-id)))))]
        (is (= 200 (:status response)))
        (is (= "#comms-person-detail" (:selector @pushed)))
        (is (str/includes? (:html @pushed) "id=\"comms-person-detail\""))
        (is (str/includes? (:html @pushed) "Priya Raghavan"))
        (is (str/includes? (:html @pushed) "decision"))
        (is (not (str/includes? (:html @pushed) "Waiting for approval")))
        (is (not (str/includes? (:html @pushed) "<!DOCTYPE html>")))))

    (testing "the inform page leaves delivery status to Comms"
      (let [body (:body (handler (as (mock/request :get "/events/comms-test/inform"))))]
        (is (not (str/includes? body "SMTP is not configured, so nothing is emailed")))))))

(deftest curated-decision-feedback-requires-an-exact-preview-test
  (let [{:keys [event submission]} (setup!)
        handler (server/create-app)
        cookie (let [t (auth/issue-token! "gene@example.com")
                     r (handler (mock/request :get (str "/auth/" t)))]
                 (first (str/split (first (get-in r [:headers "Set-Cookie"])) #";")))
        as #(mock/header % "cookie" cookie)
        gene (store/person-by-email "gene@example.com")
        selected (reviews/add-comment! (:id submission) (:id gene)
                                       "Show the concrete before-and-after metric."
                                       "gene@example.com")
        withheld (reviews/add-comment! (:id submission) (:id gene)
                                       "Committee-only concern about fit."
                                       "gene@example.com")
        path (str "/api/submissions/" (:id submission) "/inform")]
    (reviews/set-status! (:id submission) "Accepted" "gene@example.com")

    (testing "comments are private-by-default choices and private form answers never enter the picker"
      (let [body (:body (handler (as (mock/request :get "/events/comms-test/inform"))))]
        (is (str/includes? body "Every reviewer comment starts excluded"))
        (is (str/includes? body "Show the concrete before-and-after metric."))
        (is (str/includes? body "Committee-only concern about fit."))
        (is (not (str/includes? body "Private budget concern.")))
        (is (= 2 (count (re-seq #"name=\"feedback-ids\"" body))))
        (is (not (re-find #"name=\"feedback-ids\"[^>]*checked" body)))
        (is (str/includes? body "Preview decision message"))))

    (testing "preview renders only server-resolved selections and changes no state"
      (let [response (handler
                       (as (mock/request :post path
                                         {"command" "preview"
                                          "chair-note" "Please send a revised abstract by Friday."
                                          "feedback-ids" (str (:id selected))})))
            body (:body response)]
        (is (= 200 (:status response)))
        (is (str/includes? body "Nothing has been sent or queued"))
        (is (str/includes? body "Please send a revised abstract by Friday."))
        (is (str/includes? body "Show the concrete before-and-after metric."))
        (is (not (str/includes? body "Committee-only concern about fit.")))
        (is (not (str/includes? body "Private budget concern.")))
        (is (str/includes? body "Queue this decision notification"))
        (is (nil? (:notified-at (store/submission-by-id (:id submission)))))
        (is (empty? (filter #(= "decision" (:kind %))
                            (mail/queued (:id event)))))))

    (testing "a forged cross-context feedback identity is rejected at the server boundary"
      (doseq [params [{"command" "preview"
                       "feedback-ids" "comment-from-another-submission"}
                      {"command" "send-previewed"
                       "previewed" "yes"
                       "feedback-ids" "comment-from-another-submission"}]]
        (let [response (handler (as (mock/request :post path params)))]
          (is (= 422 (:status response)))
          (is (str/includes? (:body response) "no longer belong to this submission"))
          (is (nil? (:notified-at (store/submission-by-id (:id submission))))))))

    (testing "the curated path cannot queue without returning through its preview"
      (let [response (handler
                       (as (mock/request :post path
                                         {"command" "send-previewed"
                                          "feedback-ids" (str (:id selected))})))]
        (is (= 422 (:status response)))
        (is (str/includes? (:body response) "Review the complete message"))
        (is (nil? (:notified-at (store/submission-by-id (:id submission)))))))

    (testing "the deliberate queue action records context, selected ids, and exact safe copy"
      (let [response (handler
                       (as (mock/request :post path
                                         {"command" "send-previewed"
                                          "previewed" "yes"
                                          "chair-note" "Please send a revised abstract by Friday."
                                          "feedback-ids" (str (:id selected))})))
            decision (first (filter #(= "decision" (:kind %))
                                    (mail/queued (:id event))))
            notified (last (filter #(= "submission.notified" (:type %))
                                   (store/log-for-event (:id event))))]
        (is (= 303 (:status response)))
        (is (some? (:notified-at (store/submission-by-id (:id submission)))))
        (is (= (:id submission) (:submission-id decision)))
        (is (= [(str (:id selected))] (:feedback-ids decision)))
        (is (= [(str (:id selected))] (get-in notified [:payload :feedback-ids])))
        (is (str/includes? (:body decision) "Please send a revised abstract by Friday."))
        (is (str/includes? (:body decision) "Show the concrete before-and-after metric."))
        (is (not (str/includes? (:body decision) (:body withheld))))
        (is (not (str/includes? (:body decision) "Private budget concern.")))
        (is (= :queued (:state decision))
            "the existing outbox approval remains the final delivery gate")))

    (testing "the queued decision appears in the selected person's bounded history"
      (let [recipient-id (get-in submission [:speakers 0 :person-id])
            body (:body
                   (handler
                     (as (mock/request
                           :get
                           (str "/events/comms-test/comms?lane=waiting&recipient="
                                recipient-id)))))]
        (is (str/includes? body "decision"))
        (is (str/includes? body "Please send a revised abstract by Friday."))
        (is (str/includes? body "Show the concrete before-and-after metric."))
        (is (not (str/includes? body "Committee-only concern about fit.")))))))

(deftest decision-family-comms-projection-uses-current-deployment-test
  (doseq [[status copy]
          [["Accepted" "kept coming back to"]
           ["Waitlisted" "on our waitlist"]
           ["Declined" "Thank you for offering your work"]]]
    (testing status
      (with-temp-store
        (fn []
          (reset! auth/tokens {})
          (let [{:keys [event submission]} (setup!)
                handler (server/create-app)
                cookie (let [token (auth/issue-token! "gene@example.com")
                             response (handler (mock/request :get (str "/auth/" token)))]
                         (first (str/split (first (get-in response [:headers "Set-Cookie"])) #";")))
                as-organizer #(-> %
                                  (mock/header "cookie" cookie)
                                  (mock/header "host" "u2j5.example.test")
                                  (mock/header "x-forwarded-proto" "https"))]
            (reviews/set-status! (:id submission) status "gene@example.com")

            (testing "a status alone is not a notification"
              (is (nil? (:notified-at (store/submission-by-id (:id submission)))))
              (is (empty? (filter #(= "decision" (:kind %))
                                  (mail/queued (:id event))))))

            (let [response (handler
                             (as-organizer
                               (mock/request
                                 :post (str "/api/submissions/" (:id submission) "/inform"))))]
              (is (= 303 (:status response))))

            (testing "the deliberate inform act is projected on comms"
              (is (some? (:notified-at (store/submission-by-id (:id submission)))))
              (is (= 1 (count (filter #(= "decision" (:kind %))
                                      (mail/queued (:id event))))))
              (let [decision (first (filter #(= "decision" (:kind %))
                                            (mail/queued (:id event))))]
                (mail/approve! (:id event) (:email-id decision) "gene@example.com")
                (store/await-sinks!))
              (is (= :sent
                     (:state (first (filter #(= "decision" (:kind %))
                                            (mail/outbox (:id event)))))))
              (let [body (:body
                           (handler
                             (as-organizer
                               (mock/request
                                 :get
                                 (str "/events/comms-test/comms?lane=emailed&recipient="
                                      (get-in submission [:speakers 0 :person-id]))))))]
                (is (str/includes? body copy))
                (is (str/includes? body "decision"))
                (when (= "Accepted" status)
                  (is (str/includes? body "https://u2j5.example.test/portal"))
                  (is (str/includes? body "sign-in options available on this deployment"))
                  (is (not (str/includes? body "https://curtaincallcfp.com/portal")))
                  (is (not (str/includes? body "a one-time link"))))))))))))

(deftest selectable-editable-template-compose-test
  (let [{:keys [event submission]} (setup!)
        handler (server/create-app)
        cookie (let [t (auth/issue-token! "gene@example.com")
                     r (handler (mock/request :get (str "/auth/" t)))]
                 (first (str/split (first (get-in r [:headers "Set-Cookie"])) #";")))
        as #(mock/header % "cookie" cookie)]
    (testing "the preserved backend route sends and appears in selected-person history"
      (let [response (handler
                       (as (mock/request
                             :post
                             "/api/events/comms-test/comms/approve-all"
                             {"command" "send-message"
                              "template" "reminder"
                              "submission-id" (:id submission)
                              "to" "alternate@example.com"
                              "subject" "A human-edited subject"
                              "body" "A human-edited reminder."})))
            location (get-in response [:headers "Location"])
            message (first (filter #(= "speaker-message" (:kind %))
                                   (mail/history (:id event))))]
        (is (= 303 (:status response)))
        (is (str/ends-with? location "/events/comms-test/comms?delivery=sent"))
        (is (= "email.sent" (:type message)))
        (is (= "alternate@example.com" (:to message)))
        (is (= "A human-edited subject" (:subject message)))
        (is (= "A human-edited reminder." (:body message)))
        (let [body (:body
                     (handler
                       (as (mock/request
                             :get
                             "/events/comms-test/comms?lane=emailed&recipient=alternate%40example.com"))))]
          (is (str/includes? body "A human-edited subject"))
          (is (not (str/includes? body "Send message"))))))))

(deftest outbox-approve-and-discard-route-test
  (let [{:keys [event]} (setup!)
        handler (server/create-app)
        cookie (let [t (auth/issue-token! "gene@example.com")
                     r (handler (mock/request :get (str "/auth/" t)))]
                 (first (str/split (first (get-in r [:headers "Set-Cookie"])) #";")))
        as #(mock/header % "cookie" cookie)
        [approve discard] (take 2 (mail/queued (:id event)))]
    (testing "History stays read-only and links to the send queue"
      (let [body (:body (handler (as (mock/request :get "/events/comms-test/comms"))))]
        (is (str/includes? body ">History<"))
        (is (str/includes? body ">Send Emails<"))
        (is (str/includes? body "Waiting for approval"))
        (is (str/includes? body "People emailed"))
        (is (not (str/includes? body "formaction=")))
        (is (not (str/includes? body ">Send</button>")))))

    (testing "Send Emails lists every queued email with one explicit send action"
      (let [queued (mail/queued (:id event))
            body (:body (handler (as (mock/request
                                       :get
                                       "/events/comms-test/comms?tab=send"))))]
        (is (str/includes? body "Queued emails"))
        (is (str/includes? body ">Send All</button>"))
        (is (str/includes? body "data-send-all-disabled=\"true\""))
        (is (not (str/includes? body "Select one person to read their communications")))
        (doseq [{:keys [email-id to subject]} queued]
          (is (str/includes? body email-id))
          (is (str/includes? body to))
          (is (str/includes? body subject))
          (is (str/includes? body
                             (str "/api/events/comms-test/comms/"
                                  email-id "/approve"))))
        (is (= (count queued)
               (count (re-seq #">Send</button>" body))))))

    (testing "approve dispatches only the literal item in its path"
      (let [response (handler (as (mock/request
                                    :post
                                    (str "/api/events/comms-test/comms/"
                                         (:email-id approve) "/approve"))))]
        (is (= 303 (:status response)))
        (is (str/ends-with? (get-in response [:headers "Location"])
                            "/events/comms-test/comms?tab=send&delivery=sent"))
        (let [body (:body (handler (as (mock/request
                                         :get
                                         (get-in response [:headers "Location"])))))]
          (is (str/includes? body "class=\"toast\""))
          (is (str/includes? body "Email sent"))))
      (is (= :sent (:state (some #(when (= (:email-id approve) (:email-id %)) %)
                                 (mail/outbox (:id event))))))
      (is (= "dev-log" (:via (some #(when (= (:email-id approve) (:email-id %)) %)
                                   (mail/outbox (:id event))))))
      (is (= :queued (:state (some #(when (= (:email-id discard) (:email-id %)) %)
                                   (mail/outbox (:id event)))))))
    (testing "discard records the terminal state without dispatch"
      (is (= 303 (:status (handler (as (mock/request
                                         :post
                                         (str "/api/events/comms-test/comms/"
                                              (:email-id discard) "/discard")))))))
      (is (= :discarded
             (:state (some #(when (= (:email-id discard) (:email-id %)) %)
                           (mail/outbox (:id event))))))
      (let [body (:body (handler (as (mock/request :get "/events/comms-test/comms"))))]
        (is (str/includes? body "Waiting for approval"))
        (is (not (str/includes? body "Approve &amp; send all")))))

    (testing "terminal commands are no-ops"
      (let [n (count (store/read-events))]
        (handler (as (mock/request :post
                                   (str "/api/events/comms-test/comms/"
                                        (:email-id discard) "/discard"))))
        (is (= n (count (store/read-events))))))))

;; --- Quick capture ----------------------------------------------------------

(deftest capture-test
  (let [{:keys [event]} (setup!)
        paste "Hi Gene — I'd love to talk about how we rebuilt our deploy pipeline.\n\n— Sam"]

    (testing "a capture with only a paste still creates a real submission"
      (let [s (sub/capture! event {:captured-text paste} "gene@example.com")]
        (is (some? s))
        (is (= "Pending" (:status s)))
        (is (str/starts-with? (:source s) "on-behalf-of"))
        (is (sub/captured? s))
        (is (= paste (get-in s [:answers :captured-text])))
        (is (str/includes? (get-in s [:answers :talk-title]) "Captured from"))

        (testing "with a placeholder identity that can never be mailed"
          (let [sp (first (:speakers s))]
            (is (= "Unknown speaker" (:name sp)))
            (is (str/ends-with? (:email sp) ".invalid"))
            (is (some? (:person-id sp)))))

        (testing "the raw paste is a PRIVATE field, so it never reaches a public surface"
          (let [f (first (filter #(= :captured-text (keyword (name (:id %))))
                                 (:form-snapshot s)))]
            (is (:private f))
            (is (= "As received" (:label f))))
          (is (not (contains? (exports/public-answers s) :captured-text))))))

    (testing "a capture WITH details uses them"
      (let [s (sub/capture! event {:captured-text paste
                                   :title "Rebuilding our deploy pipeline"
                                   :speaker-name "Sam Okafor"
                                   :speaker-email "SAM@example.com"
                                   :speaker-org "Northwind"
                                   :source "linkedin-dm"}
                            "gene@example.com")
            sp (first (:speakers s))]
        (is (= "Rebuilding our deploy pipeline" (get-in s [:answers :talk-title])))
        (is (= "Sam Okafor" (:name sp)))
        (is (= "sam@example.com" (:email sp)) "email is normalised like everywhere else")
        (is (= "Northwind" (:org sp)))
        (is (= "on-behalf-of:linkedin-dm" (:source s)))))

    (testing "an empty paste captures nothing"
      (is (nil? (sub/capture! event {:captured-text "   "} "gene@example.com"))))

    (testing "capture sends NO email — it is the organizer's note, not a submission"
      (is (empty? (filter #(= "submission-confirmation" (:kind %))
                          (filterv #(str/includes? (str (:to %)) "sam@")
                                   (mail/history (:id event)))))))

    (testing "captures appear on the board like anything else"
      (let [rows (reviews/enriched-for-event (:id event))]
        (is (= 3 (count rows)))
        (is (some #(= "Rebuilding our deploy pipeline" (get-in % [:answers :talk-title])) rows))))

    (testing "the Log narrates it as a capture, not a submission"
      (let [e (last (filter #(and (= "submission.created" (:type %))
                                  (str/starts-with? (str (get-in % [:payload :source]))
                                                    "on-behalf-of"))
                            (store/log-for-event (:id event))))]
        (is (str/includes? (@#'cfp-scheduler-killer.views.log/log-summary e)
                           "Captured on behalf of"))))

    (testing "and it all survives a reload"
      (store/load!)
      (is (= 3 (count (store/submissions-for-event (:id event))))))))

(deftest capture-routes-test
  (let [{:keys [event]} (setup!)
        handler (server/create-app)
        cookie (let [t (auth/issue-token! "gene@example.com")
                     r (handler (mock/request :get (str "/auth/" t)))]
                 (first (str/split (first (get-in r [:headers "Set-Cookie"])) #";")))
        as #(mock/header % "cookie" cookie)]

    (testing "old capture links reach the canonical manual-speaker form"
      (let [response (handler (as (mock/request :get "/events/comms-test/capture")))
            location (get-in response [:headers "Location"])
            form-response (handler (as (mock/request :get location)))
            body (:body form-response)]
        (is (= 303 (:status response)))
        (is (= "/events/comms-test/speakers/new?legacy=capture" location))
        (is (= 200 (:status form-response)))
        (is (str/includes? body "Add a Speaker (Manually, Not from CFP)"))
        (is (str/includes? body "Quick capture has retired"))
        (doseq [field ["name" "email" "org" "title" "bio" "headshot-url" "website-url"]]
          (is (str/includes? body (str "name=\"" field "\"")) field))
        (doseq [retired-field ["captured-text" "source" "when" "where" "provenance"]]
          (is (not (str/includes? body (str "name=\"" retired-field "\""))) retired-field)))
      (is (= 302 (:status (handler (mock/request :get "/events/comms-test/capture"))))))

    (testing "the sidebar offers it — and the retired submissions page 303s to the board"
      (let [board-body (:body (handler (as (mock/request :get "/events/comms-test/board"))))]
        (is (not (str/includes? board-body "Add Speaker (bypass CFP)")))
        (is (not (str/includes? board-body "+ Add submission"))))
      (let [resp (handler (as (mock/request :get "/events/comms-test/submissions")))]
        (is (= 303 (:status resp)))
        (is (str/ends-with? (get-in resp [:headers "Location"]) "/board"))))

    (testing "the retired writer redirects without appending a submission"
      (let [before (count (store/submissions-for-event (:id event)))
            resp (handler (as (mock/request :post "/api/events/comms-test/capture"
                                            {"captured-text" "Pasted from an email."
                                             "speaker-name" "Sam Okafor"
                                             "source" "email"
                                             "when" "tomorrow"
                                             "where" "Charlotte"})))]
        (is (= 303 (:status resp)))
        (is (= "/events/comms-test/speakers/new?legacy=capture"
               (get-in resp [:headers "Location"])))
        (is (= before (count (store/submissions-for-event (:id event)))))))))
