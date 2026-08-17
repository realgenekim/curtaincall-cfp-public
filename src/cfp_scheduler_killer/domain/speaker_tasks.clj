(ns cfp-scheduler-killer.domain.speaker-tasks
  "Pure decisions and projections for speaker obligations.

   Deadlines are always derived from the current event date. A moved event
   therefore changes every open obligation without rewriting historical facts."
  (:require
   [clojure.string :as str])
  (:import
   (java.net URI)
   (java.time Instant LocalDate ZoneId)
   (java.time.temporal ChronoUnit)))

(defn- rejection [type message data]
  {:rejected (merge {:type type :message message} data)})

(defn task-done?
  [{:keys [completed-at type task-type value]}]
  (or (some? completed-at)
      (and (= "url" (or type task-type))
           (not (str/blank? value)))))

(defn http-url?
  "True only for an absolute HTTP(S) URL with a host. Browser input types are
   hints; obligation completion enforces the same contract for every caller."
  [value]
  (try
    (let [uri (URI. (str value))
          scheme (some-> (.getScheme uri) str/lower-case)]
      (boolean (and (#{"http" "https"} scheme)
                    (not (str/blank? (.getHost uri))))))
    (catch Exception _ false)))

(def legacy-due-offset-days
  "Evidence-based defaults for task facts created before offsets were explicit."
  {"confirm-bio" -30
   "headshot" -30
   "slides-url" -21})

(defn due-date
  "Derive an obligation deadline from the event date and its signed offset."
  [event-starts-on {:keys [due-on due-offset-days key]}]
  (or (when (instance? LocalDate due-on) due-on)
      (let [offset (or due-offset-days (get legacy-due-offset-days key))]
        (when (and (instance? LocalDate event-starts-on)
                   (number? offset))
          (.plusDays ^LocalDate event-starts-on (long offset))))))

(defn- event-by-id [state event-id]
  (some #(when (= event-id (:id %)) %) (vals (:events state))))

(defn- submission-title [submission]
  (or (get-in submission [:answers :talk-title])
      (:title submission)
      "Untitled session"))

(defn- primary-speaker-name [submission]
  (or (-> submission :speakers first :name)
      "Unknown speaker"))

(defn- primary-speaker-email [submission]
  (or (some-> submission :speakers first :email str/trim not-empty)
      ""))

(defn chase-draft
  "Create one editable, individually addressed follow-up letter. Pure: callers
   may preview and revise it without sending or recording anything."
  [event obligation organizer]
  (let [event-name (or (:name event) (:title event) "the event")
        speaker-name (:speaker-name obligation)
        label (or (:label obligation) "requested item")
        due-on (:due-on obligation)
        overdue (:days-overdue obligation)
        instructions (some-> (:instructions obligation) str/trim not-empty)
        due-sentence (cond
                       (pos? (or overdue 0))
                       (str "It was due on " due-on " (" overdue " days ago).")

                       due-on
                       (str "It is due on " due-on ".")

                       :else
                       "There is no fixed due date, but it is still outstanding.")]
    {:submission-id (:submission-id obligation)
     :task-key (:key obligation)
     :from (:email organizer)
     :to (:speaker-email obligation)
     :subject (str "Quick follow-up: " label " for " event-name)
     :body (str "Hi " speaker-name ",\n\n"
                "I'm following up about " label " for \"" (:talk-title obligation)
                "\" at " event-name ". " due-sentence
                (when instructions (str "\n\nDetails: " instructions))
                "\n\nCould you send or update it when you can?"
                "\n\nThanks,\n" (or (:name organizer) "The program team"))}))

(defn- obligation-status [today due-on]
  (cond
    (nil? due-on) :unscheduled
    (.isBefore ^LocalDate due-on ^LocalDate today) :overdue
    (= due-on today) :due-today
    (<= (.between ChronoUnit/DAYS today due-on) 14) :due-soon
    :else :upcoming))

(defn- days-overdue [today due-on]
  (when (and due-on (.isBefore ^LocalDate due-on ^LocalDate today))
    (.between ChronoUnit/DAYS due-on today)))

(defn- ->instant [value]
  (cond
    (instance? Instant value) value
    (string? value) (try (Instant/parse value) (catch Exception _ nil))
    :else nil))

(defn- days-outstanding [today event requested-at]
  (when-let [requested-at (->instant requested-at)]
    (let [zone (try
                 (ZoneId/of (or (:tz event) "UTC"))
                 (catch Exception _ (ZoneId/of "UTC")))
          requested-on (.toLocalDate (.atZone ^Instant requested-at zone))]
      (max 0 (.between ChronoUnit/DAYS requested-on today)))))

(defn project-obligation [today event submission task]
  (let [due-on (due-date (:starts-on event) task)]
    (assoc task
           :due-on due-on
           :status (obligation-status today due-on)
           :days-overdue (days-overdue today due-on)
           :requested-at (:at task)
           :days-outstanding (days-outstanding today event (:at task))
           :speaker-name (primary-speaker-name submission)
           :speaker-email (primary-speaker-email submission)
           :talk-title (submission-title submission))))

(defn outstanding-obligations
  "Project one event's open obligations, most overdue first.

   This is the shared read model for the organizer ledger and speaker portal."
  [state event-id today]
  (let [event (event-by-id state event-id)
        submissions (:submissions state)]
    (->> (:tasks state)
         vals
         (keep (fn [task]
                 (let [submission (get submissions (:submission-id task))]
                   (when (and submission
                              (= event-id (:event-id submission))
                              (not (task-done? task)))
                     (project-obligation today event submission task)))))
         (sort-by (juxt #(if (:due-on %) 0 1)
                        #(some-> (:due-on %) str)
                        :speaker-name
                        :key))
         vec)))

(defn materials-chase-list
  "Pure organizer projection of accepted speakers' open material requests.

   `task.installed` is the append-only request, `task.completed` is its receipt,
   and rqh9's delivery-bound chase fold supplies `:last-chased-at`. No second
   spreadsheet or mutable status record participates in this answer."
  [state event-id today]
  (->> (outstanding-obligations state event-id today)
       (filterv (fn [{:keys [submission-id]}]
                  (= "Accepted" (get-in state [:submissions submission-id :status]))))))

(defn decide-install
  "Install one explicit organizer-created obligation. Existing keys are
   immutable: an exact retry is a no-op and a conflicting reuse is rejected."
  [state {:keys [submission-id key label task-type required? due-on
                 due-offset-days instructions file-kind actor at]}]
  (let [submission (get-in state [:submissions submission-id])
        existing (get-in state [:tasks [submission-id key]])
        event (when submission (event-by-id state (:event-id submission)))
        payload {:submission-id submission-id :key key :label label
                 :task-type task-type :required? required? :due-on (some-> due-on str)
                 :due-offset-days due-offset-days :instructions instructions
                 :file-kind file-kind :position (count (filter #(= submission-id (:submission-id %))
                                                               (vals (:tasks state))))
                 :value nil :completed-at nil :at at}]
    (cond
      (nil? submission)
      (rejection :submission-not-found "Submission does not exist."
                 {:submission-id submission-id})

      (nil? event)
      (rejection :event-not-found "Submission event does not exist."
                 {:submission-id submission-id})

      (str/blank? actor)
      (rejection :actor-required "A human actor is required."
                 {:submission-id submission-id})

      (or (str/blank? key) (str/blank? label))
      (rejection :task-required "A task key and label are required."
                 {:submission-id submission-id})

      (and (= "file" task-type) (str/blank? file-kind))
      (rejection :file-kind-required "A file request needs a deliverable type."
                 {:submission-id submission-id :key key})

      (and (= "file" task-type) (str/blank? instructions))
      (rejection :instructions-required "A file request needs instructions for the speaker."
                 {:submission-id submission-id :key key})

      (and (= "file" task-type) (nil? due-on) (nil? due-offset-days))
      (rejection :due-date-required "A file request needs a due date."
                 {:submission-id submission-id :key key})

      (= (update (select-keys existing [:submission-id :key :label :task-type :required?
                                        :due-on :due-offset-days :instructions :file-kind])
                 :due-on #(some-> % str))
         (select-keys payload [:submission-id :key :label :task-type :required?
                               :due-on :due-offset-days :instructions :file-kind]))
      {:facts []}

      existing
      (rejection :task-key-conflict "That task key is already in use."
                 {:submission-id submission-id :key key})

      :else
      {:facts [{:type "task.installed" :actor actor :event-id (:id event)
                :payload payload}]})))

(defn decide-complete
  "Return a task.completed fact or a rejection value. Repeating the same
   completion is a successful no-op; a new URL remains a legitimate revision."
  [state {:keys [submission-id task-key value actor at]}]
  (let [task (get-in state [:tasks [submission-id task-key]])
        submission (get-in state [:submissions submission-id])
        task-type (or (:type task) (:task-type task))
        uploaded-file (get-in state [:files value])
        matching-upload? (and uploaded-file
                              (= submission-id (:submission-id uploaded-file))
                              (= task-key (:task-key uploaded-file))
                              (seq (:versions uploaded-file)))]
    (cond
      (nil? task)
      (rejection :task-not-found "Speaker task does not exist."
                 {:submission-id submission-id :task-key task-key})

      (nil? submission)
      (rejection :submission-not-found "Submission does not exist."
                 {:submission-id submission-id})

      (str/blank? actor)
      (rejection :actor-required "A human actor is required."
                 {:submission-id submission-id :task-key task-key})

      (and (= "file" task-type) (not matching-upload?))
      (rejection :file-upload-required
                 "Upload a file to complete this deliverable."
                 {:submission-id submission-id :task-key task-key})

      (and (= "url" task-type) (str/blank? value))
      (rejection :value-required "This task requires a URL."
                 {:submission-id submission-id :task-key task-key})

      (and (= "url" task-type)
           (not matching-upload?)
           (not (http-url? value)))
      (rejection :invalid-url "Enter a complete http:// or https:// URL."
                 {:submission-id submission-id :task-key task-key})

      (and (= "form" task-type) (not (map? value)))
      (rejection :form-response-required "Complete the required form fields."
                 {:submission-id submission-id :task-key task-key})

      (and (task-done? task) (= value (:value task)))
      {:facts []}

      :else
      {:facts [{:type "task.completed"
                :actor actor
                :event-id (:event-id submission)
                :payload {:submission-id submission-id
                          :key task-key
                          :value value
                          :at at}}]})))

(defn decide-record-chase
  "Return a human-authored task.chase-recorded fact or a rejection value.
   The caller supplies a stable chase-id so retries are algebraically safe."
  [state {:keys [submission-id task-key chase-id note actor at
                 medium subject body delivery-mode]}]
  (let [task (get-in state [:tasks [submission-id task-key]])
        submission (get-in state [:submissions submission-id])]
    (cond
      (nil? task)
      (rejection :task-not-found "Speaker task does not exist."
                 {:submission-id submission-id :task-key task-key})

      (nil? submission)
      (rejection :submission-not-found "Submission does not exist."
                 {:submission-id submission-id})

      (task-done? task)
      (rejection :task-complete "Completed tasks do not need a follow-up."
                 {:submission-id submission-id :task-key task-key})

      (str/blank? actor)
      (rejection :human-author-required "A human author must record the follow-up."
                 {:submission-id submission-id :task-key task-key})

      (str/blank? note)
      (rejection :note-required "Describe the human follow-up."
                 {:submission-id submission-id :task-key task-key})

      (str/blank? chase-id)
      (rejection :chase-id-required "A stable follow-up id is required."
                 {:submission-id submission-id :task-key task-key})

      (some #(= chase-id (:chase-id %)) (:chases task))
      {:facts []}

      :else
      {:facts [{:type "task.chase-recorded"
                :actor actor
                :event-id (:event-id submission)
                :payload (cond-> {:submission-id submission-id
                                  :key task-key
                                  :chase-id chase-id
                                  :note note
                                  :actor actor
                                  :at at}
                           medium (assoc :medium medium)
                           subject (assoc :subject subject)
                           body (assoc :body body)
                           delivery-mode (assoc :delivery-mode delivery-mode))}]})))
