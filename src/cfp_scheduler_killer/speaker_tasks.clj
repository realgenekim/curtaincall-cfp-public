(ns cfp-scheduler-killer.speaker-tasks
  "Thin append-only shell around the pure speaker-obligation algebra."
  (:require
   [cfp-scheduler-killer.domain.speaker-tasks :as decisions]
   [cfp-scheduler-killer.files :as files]
   [cfp-scheduler-killer.mail :as mail]
   [cfp-scheduler-killer.store :as store]
   [clojure.string :as str]
   [taoensso.timbre :as log])
  (:import
   (java.time LocalDate ZoneId)))

(defn- accepted! [decision]
  (if-let [rejection (:rejected decision)]
    (throw (ex-info (:message rejection) rejection))
    (do
      (when (seq (:facts decision))
        (store/append-all! (:facts decision)))
      decision)))

(defn today-for
  "The event-local calendar day used by due-date projections."
  [event]
  (LocalDate/now (ZoneId/of (or (:tz event) "UTC"))))

(def default-reminder-schedule
  {:enabled? false
   :days-before 7})

(defn reminder-schedule [event]
  (merge default-reminder-schedule
         (get-in event [:settings :speaker-reminder-schedule])))

(defn- parse-days-before [value]
  (cond
    (integer? value) value
    (string? value) (try
                      (Integer/parseInt (str/trim value))
                      (catch NumberFormatException _ nil))
    :else nil))

(defn configure-reminder-schedule!
  "Configure when open obligations are preselected for draft review.
   This fact never sends mail; send-chases! remains the only delivery gate."
  [event {:keys [enabled? days-before]} actor]
  (let [days (parse-days-before days-before)]
    (when-not (and days (<= 1 days 90))
      (throw (ex-info "Days before must be between 1 and 90"
                      {:type :invalid-reminder-schedule
                       :days-before days-before})))
    (store/append!
      {:type "speaker.reminder-schedule-configured"
       :actor actor
       :event-id (:id event)
       :payload {:event-id (:id event)
                 :slug (:slug event)
                 :enabled? (true? enabled?)
                 :days-before days
                 :at (store/now-iso)}})
    (store/get-event-by-id (:id event))))

(defn apply-reminder-schedule
  "Mark due obligations for preselection on the human-reviewed draft form."
  [event obligations]
  (let [{:keys [enabled? days-before]} (reminder-schedule event)
        threshold (.plusDays (today-for event) days-before)]
    (mapv (fn [obligation]
            (assoc obligation :scheduled?
                   (boolean
                     (and enabled?
                          (not (:done? obligation))
                          (:due-on obligation)
                          (not (.isAfter ^LocalDate (:due-on obligation) threshold))))))
          obligations)))

(defn tasks-for-submission
  "Every installed task for the speaker portal, decorated by the same pure
   projection used by the organizer ledger."
  [submission-id]
  (let [state (store/snapshot)
        submission (get-in state [:submissions submission-id])
        event (some #(when (= (:event-id submission) (:id %)) %)
                    (vals (:events state)))
        today (today-for event)]
    (->> (store/tasks-for-submission submission-id)
         (mapv #(assoc (decisions/project-obligation today event submission %)
                       :done? (decisions/task-done? %)
                       :file (files/for-task submission-id (:key %)))))))

(defn outstanding-for-event
  "Open organizer obligations, sorted most overdue first."
  [event-id]
  (let [state (store/snapshot)
        event (some #(when (= event-id (:id %)) %) (vals (:events state)))]
    (decisions/outstanding-obligations state event-id (today-for event))))

(defn materials-chase-list-for-event
  "Accepted speakers' open materials, derived only from the append-only ledger."
  [event-id]
  (let [state (store/snapshot)
        event (some #(when (= event-id (:id %)) %) (vals (:events state)))]
    (decisions/materials-chase-list state event-id (today-for event))))

(defn for-event
  "Every organizer obligation, including completed uploads, in due-date order."
  [event-id]
  (let [state (store/snapshot)
        event (some #(when (= event-id (:id %)) %) (vals (:events state)))
        today (today-for event)]
    (->> (:tasks state)
         vals
         (keep (fn [task]
                 (let [submission (get-in state [:submissions (:submission-id task)])]
                   (when (and submission (= event-id (:event-id submission)))
                     (assoc (decisions/project-obligation today event submission task)
                            :done? (decisions/task-done? task)
                            :file (files/for-task (:submission-id task) (:key task)))))))
         (sort-by (juxt #(if (:due-on %) 0 1)
                        #(some-> (:due-on %) str)
                        :speaker-name
                        :key))
         vec)))

(defn install!
  [submission-id task actor]
  (let [decision (decisions/decide-install
                   (store/snapshot)
                   (merge task {:submission-id submission-id
                                :actor actor
                                :at (store/now-iso)}))]
    (accepted! decision)
    (when (seq (:facts decision))
      (log/info :speaker-task-installed :submission-id submission-id
                :task-key (:key task) :task-type (:task-type task)
                :actor actor))
    (get-in (store/snapshot) [:tasks [submission-id (:key task)]])))

(defonce ^:private completion-listeners (atom {}))

(defn register-completion-listener!
  [listener-key listener]
  (swap! completion-listeners assoc listener-key listener)
  listener-key)

(defn- notify-completion-listeners! [submission-id]
  (doseq [[listener-key listener] @completion-listeners]
    (try
      (listener submission-id)
      (catch Exception e
        (log/error :speaker-task-completion-listener-failed
                   :listener listener-key
                   :submission-id submission-id
                   :error e)))))

(defn complete!
  [submission-id task-key value actor]
  (let [value (if (string? value)
                (some-> value str/trim not-empty)
                value)
        decision (decisions/decide-complete
                   (store/snapshot)
                   {:submission-id submission-id
                    :task-key task-key
                    :value value
                    :actor actor
                    :at (store/now-iso)})]
    (accepted! decision)
    (when (seq (:facts decision))
      (log/info :speaker-task-completed
                :submission-id submission-id
                :task-key task-key
                :actor actor)
      (notify-completion-listeners! submission-id))
    (get-in (store/snapshot) [:tasks [submission-id task-key]])))

(defn record-chase!
  ([submission-id task-key chase-id note actor]
   (record-chase! submission-id task-key chase-id note actor {}))
  ([submission-id task-key chase-id note actor details]
   (let [decision (decisions/decide-record-chase
                    (store/snapshot)
                    (merge {:submission-id submission-id
                            :task-key task-key
                            :chase-id chase-id
                            :note note
                            :actor actor
                            :at (store/now-iso)}
                           details))]
     (accepted! decision)
     (when (seq (:facts decision))
       (log/info :speaker-task-chase-recorded
                 :submission-id submission-id
                 :task-key task-key
                 :chase-id chase-id
                 :medium (:medium details)
                 :actor actor))
     (get-in (store/snapshot) [:tasks [submission-id task-key]]))))

(defn- ensure-sendable! [letter]
  (doseq [field [:chase-id :submission-id :task-key :from :to :subject :body]]
    (when (str/blank? (str (get letter field)))
      (throw (ex-info (str "A reviewed chase requires " (name field) ".")
                      {:type :invalid-chase-letter
                       :field field
                       :letter (select-keys letter [:submission-id :task-key :to])}))))
  letter)

(defn send-chases!
  "Send a human-reviewed batch. All letters are preflighted before the first
   external effect, but the chase clock is folded only from the durable
   email.sent fact — never from this function's ephemeral adapter result."
  [event-id letters actor]
  (when (empty? letters)
    (throw (ex-info "Select at least one speaker to contact."
                    {:type :empty-chase-batch})))
  (let [state (store/snapshot)
        at (store/now-iso)
        prepared
        (mapv (fn [letter]
                (ensure-sendable! letter)
                (let [submission (get-in state [:submissions (:submission-id letter)])
                      task (get-in state [:tasks [(:submission-id letter)
                                                  (:task-key letter)]])
                      already-sent? (boolean
                                      (some #(= (:chase-id letter) (:chase-id %))
                                            (:chases task)))
                      validation (when-not already-sent?
                                   (decisions/decide-record-chase
                                     state
                                     {:submission-id (:submission-id letter)
                                      :task-key (:task-key letter)
                                      :chase-id (:chase-id letter)
                                      :note (str "Email sent to " (:to letter))
                                      :medium "email"
                                      :subject (:subject letter)
                                      :body (:body letter)
                                      :delivery-mode "sent"
                                      :actor actor
                                      :at at}))]
                  (when-let [rejection (:rejected validation)]
                    (throw (ex-info (:message rejection) rejection)))
                  (when-not (= event-id (:event-id submission))
                    (throw (ex-info "Speaker task does not belong to this event."
                                    {:type :cross-event-chase
                                     :event-id event-id
                                     :submission-id (:submission-id letter)})))
                  {:letter letter :already-sent? already-sent?}))
              letters)]
    (mapv (fn [{:keys [letter already-sent?]}]
            (let [result
                  (if already-sent?
                    {:mode :already-sent}
                    (let [queued-or-sent
                          (mail/send!
                            (select-keys letter [:from :to :subject :body :reply-to])
                            {:event-id event-id
                             :kind "speaker-chase"
                             :submission-id (:submission-id letter)
                             :task-key (:task-key letter)
                             :chase-id (:chase-id letter)
                             :actor actor})]
                      (if (= :queued (:mode queued-or-sent))
                        (or (mail/approve! event-id (:email-id queued-or-sent) actor)
                            queued-or-sent)
                        queued-or-sent)))]
              (assoc result
                     :submission-id (:submission-id letter)
                     :task-key (:task-key letter)
                     :to (:to letter))))
          prepared)))
