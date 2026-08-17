(ns cfp-scheduler-killer.mail
  "Human-approved, event-sourced outbound email. Sending always starts by
   appending email.queued; only an explicit approval reaches a provider."
  (:require
   [cfp-scheduler-killer.io.email :as email-port]
   [cfp-scheduler-killer.ses :as ses]
   [cfp-scheduler-killer.store :as store]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [taoensso.timbre :as log]))

;; --- Configuration ----------------------------------------------------------

(defn- env [k] (some-> (System/getenv k) str/trim not-empty))

(defn config
  "SMTP settings from secrets/smtp.edn or the environment. nil when unconfigured
   — which is a supported state, not a failure."
  []
  (let [file (try (let [f (io/file "secrets/smtp.edn")]
                    (when (.exists f) (read-string (slurp f))))
                  (catch Exception e
                    (log/warn :smtp-edn-unreadable :msg (.getMessage e))
                    nil))
        from-env (when (env "SMTP_HOST")
                   {:host (env "SMTP_HOST")
                    :port (some-> (env "SMTP_PORT") Integer/parseInt)
                    :user (env "SMTP_USER")
                    :pass (env "SMTP_PASS")
                    :from (env "SMTP_FROM")})
        cfg (or file from-env)]
    (when (and cfg (not (str/blank? (str (:host cfg)))))
      (merge {:port 587} cfg))))

(defn provider-delivery-enabled?
  "Only Cloud Run may reach a real provider. Development approvals use the
   event-sourced log provider even if a developer has mail credentials nearby."
  []
  (boolean (System/getenv "K_SERVICE")))

(defn configured? []
  (and (provider-delivery-enabled?) (or (some? (config)) (ses/enabled?))))

(defn status-line
  "What the UI says about delivery. Never optimistic."
  []
  (let [delivery? (provider-delivery-enabled?)]
    (if-let [c (when delivery? (config))]
      (str "Sending via " (:host c) " as " (or (:from c) (:user c)))
      (if (and delivery? (ses/enabled?))
        "Sending via AWS SES as notifications@itrevolution.com"
        "Mail not configured — approvals use the safe development log provider; no external email is sent"))))

;; --- Sending ----------------------------------------------------------------

(defn- ->provider-message
  [cfg {:keys [to subject body reply-to ics ics-filename] :as letter}]
  (cond-> {:from (or (:from letter) (:from cfg) (:user cfg))
           :to to
           :subject subject}
    reply-to (assoc :reply-to reply-to)
    ;; A plain body when there's no attachment; multipart when there is. Some
    ;; clients render a single-part multipart badly, so we don't wrap needlessly.
    (nil? ics) (assoc :body body)
    ics (assoc :body [{:type "text/plain; charset=utf-8" :content body}
                      {:type :attachment
                       :content-type "text/calendar; method=PUBLISH; charset=utf-8"
                       :file-name (or ics-filename "invite.ics")
                       :content (.getBytes ^String ics "UTF-8")}])))

(defn- dispatch!
  "Dispatch an approved letter through the configured provider.

   `letter` = {:to :subject :body :reply-to :ics :ics-filename}.
   `context` = {:event-id :kind :submission-id :person-id :task-key :chase-id}
   — whatever makes the entry findable later. A speaker chase's stable id is
   load-bearing delivery evidence, so it must survive queue -> approval -> sent.

   Returns {:mode :sent|:rendered|:failed …}. NEVER throws: a mail failure must
   not roll back the domain act that triggered it. Informing a speaker is a fact
   even if the SMTP server was down; the send log records the failure so someone
   can retry, which is exactly what Ann does by hand today."
  [letter context]
  (let [;; SIMULATION FIREWALL (2026-08-11, the 4:30am flood): a replay-marked
        ;; event's letters NEVER really send — the demo plays real domain
        ;; verbs, and the night SES went live, one replay click emailed the
        ;; demo committee ~160 real letters each. Render, always.
        event* (when-let [eid (:event-id context)]
                 (some #(when (= eid (:id %)) %)
                       (vals (:events (cfp-scheduler-killer.store/snapshot)))))
        replay-event? (boolean (get-in event* [:settings :replay?]))
        ;; Per-event mail switch (Gene, 2026-08-11): explicit false = render.
        muted? (false? (get-in event* [:settings :email-notifications?]))
        suppress? (or replay-event? muted? (not (provider-delivery-enabled?)))
        cfg (when-not suppress? (config))
        base (cond-> {:email-id (:email-id context)
                      :from (:from letter)
                      :to (:to letter)
                      :subject (:subject letter)
                      :body (:body letter)
                      :reply-to (:reply-to letter)
                      :has-ics? (some? (:ics letter))
                      :kind (:kind context)
                      :submission-id (:submission-id context)
                      :person-id (:person-id context)
                      :task-key (:task-key context)
                      :feedback-ids (:feedback-ids context)
                      :at (store/now-iso)}
               (:chase-id context) (assoc :chase-id (:chase-id context)))]
    (cond
      ;; SES transport (2026-08-11, ported from does email/ses): text-only —
      ;; letters carrying an .ics still RENDER (attachments need raw MIME;
      ;; honest degradation beats a silently stripped invite).
      (and (nil? cfg) (not suppress?) (ses/enabled?) (nil? (:ics letter)))
      (let [{:keys [ok? message-id error]}
            (ses/send-email! {:to (:to letter) :subject (:subject letter)
                              :body (:body letter) :reply-to (:reply-to letter)})]
        (if ok?
          (do (store/append! {:type "email.sent" :actor (or (:actor context) "system")
                              :event-id (:event-id context)
                              :payload (assoc base :message-id message-id :via "ses")})
              (log/info :comms-sent :via :ses :to (:to letter) :kind (:kind context))
              {:mode :sent :message-id message-id})
          (do (store/append! {:type "email.failed" :actor (or (:actor context) "system")
                              :event-id (:event-id context)
                              :payload (assoc base :error error :via "ses")})
              (log/warn :comms-failed :via :ses :to (:to letter) :error error)
              {:mode :failed :error error})))

      (nil? cfg)
      (do
        (store/append! {:type "email.sent" :actor (or (:actor context) "system")
                        :event-id (:event-id context)
                        :payload (assoc base :message-id (str "dev-log/" (:email-id context))
                                        :via "dev-log")})
        (log/info :email-sent :via :dev-log :to (:to letter) :kind (:kind context))
        {:mode :sent :message-id (str "dev-log/" (:email-id context))})
      :else
      (try
        (let [result (email-port/send-with-config! cfg (->provider-message cfg letter))]
          (if (:ok result)
            (do
              (store/append! {:type "email.sent" :actor (or (:actor context) "system")
                              :event-id (:event-id context)
                              :payload (assoc base :message-id
                                              (:message-id result))})
              (log/info :comms-sent :to (:to letter) :kind (:kind context))
              {:mode :sent :result result})
            (do
              (store/append! {:type "email.failed" :actor (or (:actor context) "system")
                              :event-id (:event-id context)
                              :payload (assoc base :error (str (:error result)))})
              (log/warn :comms-failed :to (:to letter) :error (:error result))
              {:mode :failed :result result})))
        (catch Exception e
          (store/append! {:type "email.failed" :actor (or (:actor context) "system")
                          :event-id (:event-id context)
                          :payload (assoc base :error (.getMessage e))})
          (log/error :comms-error :to (:to letter) :msg (.getMessage e))
          {:mode :failed :error (.getMessage e)})))))

(defn send!
  "Queue a complete email for human approval. Every caller uses this one gate;
   provider delivery happens only through `approve!`."
  [letter context]
  (let [email-id (store/new-id)
        payload (merge (select-keys letter [:from :to :subject :body :reply-to
                                            :ics :ics-filename])
                       (select-keys context [:kind :submission-id :person-id :task-key
                                             :chase-id :feedback-ids])
                       {:email-id email-id
                        :has-ics? (some? (:ics letter))
                        :at (store/now-iso)})]
    (store/append! {:type "email.queued"
                    :actor (or (:actor context) "system")
                    :event-id (:event-id context)
                    :payload payload})
    (log/info :email-queued :email-id email-id :to (:to letter) :kind (:kind context))
    {:mode :queued :email-id email-id}))

(def email-types
  #{"email.queued" "email.approved" "email.sent" "email.failed" "email.discarded"})

(defn- email-events [snapshot event-id]
  (filter #(and (contains? email-types (:type %))
                (= event-id (:event-id %)))
          (:log snapshot)))

(defn outbox
  "Current email records for an event, newest first. State is folded from facts
   by email-id; the queued fact remains the immutable full preview."
  ([event-id]
   (outbox (store/snapshot) event-id))
  ([snapshot event-id]
   (->> (email-events snapshot event-id)
        (reduce (fn [by-id event]
                  (let [payload (:payload event)
                        id (:email-id payload)]
                    (update by-id id
                            (fn [row]
                              (merge row payload
                                     {:email-id id :type (:type event)
                                      :state (keyword (subs (:type event) 6))
                                      :actor (:actor event) :when (:at event)})))))
                {})
        vals
        (sort-by :at #(compare %2 %1))
        vec)))

(defn queued [event-id]
  (filterv #(= :queued (:state %)) (outbox event-id)))

(defn approve!
  "Approve one queued email, then dispatch it and record the provider outcome."
  [event-id email-id actor]
  (when-let [email (some #(when (= email-id (:email-id %)) %) (queued event-id))]
    (store/append! {:type "email.approved" :actor actor :event-id event-id
                    :payload {:email-id email-id :at (store/now-iso)}})
    (dispatch! (select-keys email [:from :to :subject :body :reply-to :ics :ics-filename])
               (merge (select-keys email [:kind :submission-id :person-id :task-key
                                          :chase-id :feedback-ids])
                      {:email-id email-id :event-id event-id :actor actor}))))

(defn send-now!
  "Queue, approve, and synchronously dispatch one email. Reserved for messages,
   such as sign-in links, that must bypass the human approval queue."
  [letter context]
  (let [{:keys [email-id]} (send! letter context)]
    (approve! (:event-id context)
              email-id
              (or (:actor context) "system"))))

(defn discard!
  "Discard one queued email. Repeating the command is a no-op."
  [event-id email-id actor]
  (when (some #(= email-id (:email-id %)) (queued event-id))
    (store/append! {:type "email.discarded" :actor actor :event-id event-id
                    :payload {:email-id email-id :at (store/now-iso)}})
    true))

(defn approve-all! [event-id actor]
  (mapv #(approve! event-id (:email-id %) actor) (queued event-id)))

;; --- The send log -----------------------------------------------------------

(def comms-types (into #{"comms.sent" "comms.rendered" "comms.failed"} email-types))

(defn history
  "Every letter for one event, newest first. The answer to 'did it go out?'"
  [event-id]
  (->> (store/log-for-event event-id)
       (filter #(contains? comms-types (:type %)))
       reverse
       (mapv (fn [e]
               (assoc (:payload e)
                      :type (:type e)
                      :sent? (contains? #{"comms.sent" "email.sent"} (:type e))
                      :actor (:actor e)
                      :when (:at e))))))

(defn history-for-person
  [event-id email]
  (filterv #(= (str/lower-case (str (:to %))) (str/lower-case (str email)))
           (history event-id)))
