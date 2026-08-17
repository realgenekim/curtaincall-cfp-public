(ns cfp-scheduler-killer.acceptance-notify-test
  "Acceptance NOTIFICATIONS — Gene's ask: 'whenever a new acceptance comes in,
   I want to be notified every time.'

   Three channels fire when a submission becomes Accepted:
     - Slack   (the existing :slack sink, on the 'decision' group)
     - Airtable (the existing :airtable sink, a row upsert)
     - Email    (the NEW :acceptance-email sink, one letter per committee member)

   No network anywhere: Slack's poster and Airtable's HTTP call are rebound to
   capture the body, and the mailer runs in render mode (no SMTP), so committee
   letters are recorded — never really sent — exactly as the firewall requires."
  (:require
   [cfp-scheduler-killer.auth :as auth]
   [cfp-scheduler-killer.committees :as committees]
   [cfp-scheduler-killer.events :as events]
   [cfp-scheduler-killer.mail :as mail]
   [cfp-scheduler-killer.reviews :as reviews]
   [cfp-scheduler-killer.server :as server]
   [cfp-scheduler-killer.sinks :as sinks]
   [cfp-scheduler-killer.store :as store]
   [cfp-scheduler-killer.submissions :as sub]
   [cfp-scheduler-killer.test-helpers :refer [with-temp-store]]
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
  (let [event (events/create-event!
                {:name "Accept Test Summit" :slug "accept-test" :tz "America/New_York"
                 :support-email "annp@example.com"
                 :cfp-opens-at (LocalDateTime/of 2020 1 1 0 0)
                 :cfp-closes-at (LocalDateTime/of 2099 1 1 0 0)}
                "kaocha")
        cid (:id (first (events/committees-for-event (:id event))))]
    (doseq [m [{:name "Gene Kim" :email "gene@example.com" :role "chair"}
               {:name "Ann Perry" :email "ann@example.com" :role "member"}
               {:name "Alex B-F" :email "alex@example.com" :role "member"}]]
      (committees/add-member! cid m "kaocha"))
    (events/event-by-slug "accept-test")))

(defn- submit! [event]
  (let [ff (:fields (events/form-for-event (:id event)))
        params {:answer-talk-title "Rebuilding underwriting"
                :answer-abstract "How we did it, and what broke."
                :answer-session-format "Experience Report"
                :answer-org-size ">10,000"
                :answer-industry "Insurance"
                :answer-notes-to-committee private-note
                :speaker-name "Priya Raghavan" :speaker-email "priya@example.com"
                :speaker-title "VP Underwriting" :speaker-org "Meridian Mutual"
                :speaker-bio "Priya runs underwriting technology."}]
    (sub/create-submission! event (sub/parse-answers ff params)
                            (sub/parse-speaker params) "form" "speaker")))

(defn- notes [event-id]
  (filterv #(= "acceptance-notify" (:kind %)) (mail/history event-id)))

;; --- pure payload builders --------------------------------------------------

(deftest accepted-transition-recognises-only-acceptance-test
  (testing "a move TO Accepted is an acceptance"
    (is (sinks/accepted-transition?
          {:type "submission.status-changed" :payload {:to "Accepted" :from "Pending"}})))
  (testing "case and whitespace don't matter — an integrator may type 'accepted'"
    (is (sinks/accepted-transition?
          {:type "submission.status-changed" :payload {:to "  accepted "}})))
  (testing "any OTHER status change is NOT an acceptance"
    (is (not (sinks/accepted-transition?
               {:type "submission.status-changed" :payload {:to "Declined"}})))
    (is (not (sinks/accepted-transition?
               {:type "submission.status-changed" :payload {:to "Waitlisted"}}))))
  (testing "and a non-status event never is"
    (is (not (sinks/accepted-transition?
               {:type "submission.created" :payload {:to "Accepted"}}))))
  (testing "a no-op re-accept (already Accepted -> Accepted) is NOT a transition"
    (is (not (sinks/accepted-transition?
               {:type "submission.status-changed" :payload {:from "Accepted" :to "Accepted"}})))
    (is (not (sinks/accepted-transition?
               {:type "submission.status-changed" :payload {:from "  accepted " :to "ACCEPTED"}})))))

(deftest acceptance-email-body-names-the-talk-and-speaker-test
  (let [event (setup!)
        submission (submit! event)
        body (sinks/acceptance-email-body
               event (store/submission-by-id (:id submission))
               "https://cfp.example.com/events/accept-test/submissions/x")]
    (testing "it announces the acceptance and names the confirmed session"
      (is (str/includes? body "ACCEPTED"))
      (is (str/includes? body "Rebuilding underwriting"))
      (is (str/includes? body "Priya Raghavan"))
      (is (str/includes? body "Meridian Mutual")))
    (testing "one link, to the row"
      (is (str/includes? body "https://cfp.example.com/events/accept-test/submissions/x")))
    (testing "and it says why they received it"
      (is (str/includes? body "programming committee")))))

;; --- the sink: email fires on acceptance, once per committee member ----------

(deftest committee-is-emailed-on-acceptance-test
  (let [event (setup!)
        submission (submit! event)]
    (Thread/sleep 400)
    (testing "no acceptance mail before any decision"
      (is (empty? (notes (:id event)))))

    (reviews/set-status! (:id submission) "Accepted" "gene@example.com")
    (store/await-sinks!)
    (Thread/sleep 200)

    (testing "every committee member gets exactly one letter, and only them"
      (let [ns* (notes (:id event))]
        (is (= 3 (count ns*)))
        (is (= #{"gene@example.com" "ann@example.com" "alex@example.com"}
               (set (map :to ns*))))
        (is (not-any? #(= "priya@example.com" (:to %)) ns*)
            "the speaker is not on the committee mail")))

    (testing "the subject names the event and the talk"
      (is (= "[Accept Test Summit] Accepted: Rebuilding underwriting"
             (:subject (first (notes (:id event)))))))

    (testing "reply-to reaches the organizers"
      (is (= "annp@example.com" (:reply-to (first (notes (:id event)))))))))

;; --- no-op re-accept must not re-email the committee -------------------------

(deftest re-accepting-an-accepted-talk-does-not-re-email-test
  (let [event (setup!)
        submission (submit! event)]
    (Thread/sleep 400)

    (testing "the first Pending -> Accepted fires the committee email once"
      (reviews/set-status! (:id submission) "Accepted" "gene@example.com")
      (store/await-sinks!)
      (Thread/sleep 200)
      (is (= 3 (count (notes (:id event))))))

    (testing "re-setting the already-Accepted talk to Accepted fires NOTHING more"
      (reviews/set-status! (:id submission) "Accepted" "gene@example.com")
      (store/await-sinks!)
      (Thread/sleep 200)
      (is (= 3 (count (notes (:id event))))
          "a no-op re-accept must not re-email the whole committee"))

    (testing "a status cycle never duplicates an acceptance notice"
      (reviews/set-status! (:id submission) "Waitlisted" "gene@example.com")
      (reviews/set-status! (:id submission) "Accepted" "gene@example.com")
      (store/await-sinks!)
      (Thread/sleep 200)
      (is (= 3 (count (notes (:id event))))
          "each committee recipient gets at most one acceptance notice per talk"))))

;; --- firewall: renders, never claims to send, without SMTP -------------------

(deftest acceptance-mail-respects-the-simulation-firewall-test
  (let [event (setup!)
        submission (submit! event)]
    (reviews/set-status! (:id submission) "Accepted" "gene@example.com")
    (store/await-sinks!)
    (testing "acceptance notices wait in the human-approved outbox"
      (let [ns* (notes (:id event))]
        (is (seq ns*))
        (is (every? #(= "email.queued" (:type %)) ns*))
        (is (not-any? :sent? ns*)
            "nothing claims a letter went out before approval")))))

;; --- only on acceptance, not on other decisions ------------------------------

(deftest only-acceptance-triggers-the-mail-test
  (let [event (setup!)
        submission (submit! event)]
    (Thread/sleep 400)
    (testing "a move to Waitlisted is a decision but NOT an acceptance"
      (reviews/set-status! (:id submission) "Waitlisted" "gene@example.com")
      (store/await-sinks!)
      (Thread/sleep 200)
      (is (empty? (notes (:id event)))))
    (testing "the move to Accepted then fires it"
      (reviews/set-status! (:id submission) "Accepted" "gene@example.com")
      (store/await-sinks!)
      (Thread/sleep 200)
      (is (= 3 (count (notes (:id event))))))))

;; --- opt-out ----------------------------------------------------------------

(deftest acceptance-mail-can-be-turned-off-test
  (let [event (setup!)]
    (testing "on by default"
      (is (sinks/acceptance-email-enabled? event)))
    (testing "off when the event says so"
      (is (not (sinks/acceptance-email-enabled?
                 (assoc-in event [:settings :acceptance-email-enabled] false)))))))

;; --- all three channels fire together on acceptance --------------------------

(defn- slack-set!
  [handler as slug params]
  (handler (as (mock/request :post (str "/api/events/" slug "/slack/set") params))))

(deftest acceptance-fires-slack-airtable-and-email-together-test
  (let [event (setup!)
        handler (server/create-app)
        cookie (let [t (auth/issue-token! "gene@example.com")
                     r (handler (mock/request :get (str "/auth/" t)))]
                 (first (str/split (first (get-in r [:headers "Set-Cookie"])) #";")))
        as #(mock/header % "cookie" cookie)
        slack-posts (atom [])
        air-posts (atom [])]
    ;; Slack on the 'decision' group so a status change posts.
    (slack-set! handler as "accept-test"
                {"webhook-url" "https://hooks.slack.com/services/T0/B0/xyz"
                 "groups" "decision"})
    ;; Airtable configured the way the Settings form does.
    (store/append! {:type "event.updated" :actor "kaocha" :event-id (:id event)
                    :payload {:id (:id event) :slug "accept-test"
                              :changed ["airtable"] :before {}
                              :changes {:settings (assoc (:settings (events/event-by-slug "accept-test"))
                                                         :airtable
                                                         {:base-id "appTEST"
                                                          :table "Submissions"
                                                          :token "pat_secret"})}}})
    (let [submission (submit! event)]
      (Thread/sleep 400)
      (binding [sinks/*slack-post* (fn [url body]
                                     (swap! slack-posts conj {:url url :body body})
                                     {:status 200 :body "ok"})
                sinks/*http-post* (fn [url token body]
                                    (swap! air-posts conj {:url url :token token :body body})
                                    {:status 200 :body "{}"})]
        (reset! slack-posts [])
        (reset! air-posts [])
        (reviews/set-status! (:id submission) "Accepted" "gene@example.com")
        (store/await-sinks!)
        (Thread/sleep 200)

        (testing "Slack posts the decision"
          (is (= 1 (count @slack-posts)))
          (is (str/includes? (get-in (first @slack-posts) [:body "text"]) "Accepted")))

        (testing "Airtable upserts the row with Status Accepted"
          (is (= 1 (count @air-posts)))
          (is (= "Accepted"
                 (get-in (first @air-posts) [:body "records" 0 "fields" "Status"]))))

        (testing "and the committee gets its email"
          (is (= 3 (count (notes (:id event))))))

        (testing "no private note leaks to Slack or Airtable"
          (is (not (str/includes? (pr-str @slack-posts) private-note)))
          (is (not (str/includes? (pr-str @air-posts) private-note))))))))

;; --- a sink failure never breaks acceptance ----------------------------------

(deftest a-sink-failure-does-not-break-acceptance-test
  (let [event (setup!)
        handler (server/create-app)
        cookie (let [t (auth/issue-token! "gene@example.com")
                     r (handler (mock/request :get (str "/auth/" t)))]
                 (first (str/split (first (get-in r [:headers "Set-Cookie"])) #";")))
        as #(mock/header % "cookie" cookie)]
    (slack-set! handler as "accept-test"
                {"webhook-url" "https://hooks.slack.com/services/T0/B0/dead"
                 "groups" "decision"})
    (let [submission (submit! event)]
      (Thread/sleep 400)
      (binding [sinks/*slack-post* (fn [_ _] {:status 500 :body "boom"})]
        (testing "a status change to Accepted still succeeds even if Slack 500s"
          (reviews/set-status! (:id submission) "Accepted" "gene@example.com")
          (store/await-sinks!)
          (Thread/sleep 200)
          (is (= "Accepted" (:status (store/submission-by-id (:id submission))))))
        (testing "the committee email still went out (a different sink)"
          (is (= 3 (count (notes (:id event))))))
        (testing "and the Slack failure is recorded, not swallowed"
          (is (some #(and (= :slack (:sink-type %)) (false? (:ok %)))
                    @store/deliveries)))))))
