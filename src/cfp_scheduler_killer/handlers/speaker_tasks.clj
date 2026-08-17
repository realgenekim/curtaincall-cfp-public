(ns cfp-scheduler-killer.handlers.speaker-tasks
  (:require
   [cfp-scheduler-killer.auth :as auth]
   [cfp-scheduler-killer.committees :as committees]
   [cfp-scheduler-killer.domain.files :as file-decisions]
   [cfp-scheduler-killer.domain.speaker-tasks :as decisions]
   [cfp-scheduler-killer.events :as events]
   [cfp-scheduler-killer.mail :as mail]
   [cfp-scheduler-killer.speaker-tasks :as speaker-tasks]
   [cfp-scheduler-killer.sse :as sse]
   [cfp-scheduler-killer.store :as store]
   [cfp-scheduler-killer.views.speaker-tasks :as view-speaker-tasks]
   [cfp-scheduler-killer.web.event :as web-event]
   [cfp-scheduler-killer.web.http :as http]
   [clojure.string :as str]
   [taoensso.timbre :as log])
  (:import
   (java.time LocalDate)
   (java.time.format DateTimeParseException)))

(defn- param-values [value]
  (cond
    (nil? value) []
    (sequential? value) value
    :else [value]))

(defn- task-assignees [event]
  (->> (store/submissions-for-event (:id event))
       (keep (fn [submission]
               (let [speaker-names (->> (:speakers submission)
                                        (keep (fn [speaker]
                                                (let [person (some-> (:person-id speaker)
                                                                     store/person-by-id)]
                                                  (or (not-empty (:name person))
                                                      (not-empty (:name speaker))))))
                                        distinct
                                        vec)]
                 (when (seq speaker-names)
                   {:submission-id (:id submission)
                    :speaker-name (str/join ", " speaker-names)
                    :talk-title (or (get-in submission [:answers :talk-title])
                                    "Untitled session")}))))
       (sort-by (juxt (comp str/lower-case :speaker-name)
                      (comp str/lower-case :talk-title)))
       vec))

(defn- onboarding-by-speaker [event]
  (let [event-id (:id event)
        portal-visitors (->> (store/log-for-event event-id)
                             (filter #(= "portal.visited" (:type %)))
                             (keep #(get-in % [:payload :person-id]))
                             set)]
    (->> (store/submissions-for-event event-id)
         (mapcat
           (fn [submission]
             (let [tasks (speaker-tasks/tasks-for-submission (:id submission))
                   talk-title (or (get-in submission [:answers :talk-title])
                                  "Untitled session")]
               (for [speaker (:speakers submission)
                     :when (:person-id speaker)]
                 {:person-id (:person-id speaker)
                  :speaker-name (:name speaker)
                  :speaker-email (:email speaker)
                  :talk-title talk-title
                  :accepted? (= "Accepted" (:status submission))
                  :notified-at (:notified-at submission)
                  :slot-assigned? (boolean (store/slot-for (:id submission)))
                  :tasks tasks}))))
         (group-by :person-id)
         (map (fn [[person-id rows]]
                (let [tasks (mapcat :tasks rows)
                      person (store/person-by-id person-id)
                      speaker-email (or (:email person) (:speaker-email (first rows)))
                      delivery-history (mail/history-for-person event-id speaker-email)]
                  {:person-id person-id
                   :speaker-name (or (:name person) (:speaker-name (first rows)))
                   :speaker-email speaker-email
                   :talks (->> rows (map :talk-title) distinct vec)
                   :accepted? (boolean (some :accepted? rows))
                   :notified? (boolean (some :notified-at rows))
                   :portal-visited? (contains? portal-visitors person-id)
                   :slot-assigned? (boolean (some :slot-assigned? rows))
                   :ics-sent? (boolean (some #(and (:sent? %) (:has-ics? %))
                                             delivery-history))
                   :done (count (filter :done? tasks))
                   :total (count tasks)
                   :overdue (count (filter #(and (not (:done? %))
                                                 (= :overdue (:status %)))
                                           tasks))})))
         (sort-by (comp str/lower-case str :speaker-name))
         vec)))

(defn- onboarding-funnel [speaker-progress]
  {:accepted (count (filter :accepted? speaker-progress))
   :notified (count (filter #(and (:accepted? %) (:notified? %)) speaker-progress))
   :portal-visited (count (filter #(and (:accepted? %) (:portal-visited? %))
                                  speaker-progress))
   :tasks-complete (count (filter #(and (:accepted? %)
                                        (pos? (:total %))
                                        (= (:done %) (:total %)))
                                  speaker-progress))})

(defn- current-speaker-contacts [obligation]
  (let [submission (store/submission-by-id (:submission-id obligation))
        contacts (map-indexed
                   (fn [idx speaker]
                     (let [person (some-> (:person-id speaker) store/person-by-id)]
                       (assoc obligation
                              :speaker-key (or (:person-id speaker) (str idx))
                              :speaker-name (or (not-empty (:name person))
                                                (:name speaker)
                                                "Unknown speaker")
                              :speaker-email (or (not-empty (:email person))
                                                 (:email speaker)
                                                 ""))))
                   (:speakers submission))]
    (if (seq contacts) contacts [obligation])))

(defn push-deliverables-update! [submission-id]
  (when-let [submission (store/submission-by-id submission-id)]
    (when-let [event (events/event-by-id (:event-id submission))]
      (let [event-id (:id event)
            speaker-progress (onboarding-by-speaker event)
            funnel (onboarding-funnel speaker-progress)
            obligations (->> (speaker-tasks/for-event event-id)
                             (mapcat current-speaker-contacts)
                             vec
                             (speaker-tasks/apply-reminder-schedule event))]
        (doseq [{:keys [person-id role]} (committees/members-for-event event-id)
                :when (contains? #{"chair" "admin"} role)]
          (let [reach (sse/person-connection-count event-id person-id)]
            (if (pos? reach)
              (sse/push-to-person!
                event-id person-id "#deliverables-live"
                #(view-speaker-tasks/deliverables-live-region
                   funnel speaker-progress obligations))
              (log/warn :deliverables-push-no-subscriber
                        :event-id event-id
                        :person-id person-id
                        :submission-id submission-id))))))))

(speaker-tasks/register-completion-listener!
  ::deliverables-live-update
  #'push-deliverables-update!)

(defn- deliverable-filters [req]
  (let [status (get-in req [:params :status])
        due (get-in req [:params :due])]
    {:q (some-> (get-in req [:params :q]) str str/trim not-empty)
     :status (if (contains? #{"all" "open" "complete"} status) status "all")
     :due (if (contains? #{"all" "overdue" "due-today" "due-soon" "upcoming" "unscheduled"}
                         due)
            due
            "all")}))

(defn- matches-deliverable-filters? [{:keys [q status due]} task]
  (let [haystack (str/lower-case
                   (str (:speaker-name task) " " (:speaker-email task) " "
                        (:talk-title task) " " (:label task) " "
                        (:instructions task)))
        due-status (name (:status task))]
    (and (or (nil? q) (str/includes? haystack (str/lower-case q)))
         (case status
           "open" (not (:done? task))
           "complete" (:done? task)
           true)
         (or (= "all" due) (= due due-status)))))

(defn handle-deliverables [req]
  (let [slug (get-in req [:path-params :slug])]
    (if-let [event (events/event-by-slug slug)]
      (let [filters (deliverable-filters req)
            speaker-progress (onboarding-by-speaker event)
            all-obligations (vec
                              (mapcat current-speaker-contacts
                                      (speaker-tasks/for-event (:id event))))
            obligations (filterv #(matches-deliverable-filters? filters %)
                                 all-obligations)]
        (http/html-response
          (view-speaker-tasks/deliverables-page
            event
            {:person (auth/current-person req)
             :reminder-schedule (speaker-tasks/reminder-schedule event)
             :obligations (speaker-tasks/apply-reminder-schedule event obligations)
             :total-obligations (count all-obligations)
             :filters filters
             :task-assignees (task-assignees event)
             :speaker-progress speaker-progress
             :funnel (onboarding-funnel speaker-progress)})))
      (web-event/not-found-page slug))))

(defn- parse-due-on [value]
  (try
    (some-> value str/trim not-empty LocalDate/parse)
    (catch DateTimeParseException _ nil)))

(defn- inferred-file-kind
  "Recognize the upload requests organizers already express in plain language.
   The explicit task-kind control wins; this compatibility seam repairs tasks
   created by the pre-control form and by old clients using the same endpoint."
  [task-name]
  (let [label (some-> task-name str/lower-case)]
    (when (str/starts-with? (or label "") "upload ")
      (cond
        (re-find #"\b(headshot|portrait|photo)\b" label) "Headshot"
        (re-find #"\b(poster)\b" label) "Poster"
        (re-find #"\b(handout)\b" label) "Handout"
        (re-find #"\b(presentation|slides?|deck)\b" label) "Presentation"))))

(defn- requested-file-kind [req task-name]
  (let [requested (some-> (get-in req [:params :task-kind]) str str/trim not-empty)]
    (cond
      (= "check" requested) nil
      (contains? file-decisions/file-kinds requested) requested
      requested ::invalid
      :else (inferred-file-kind task-name))))

(defn- create-general-task! [req event actor]
  (let [submission-ids (->> (param-values (get-in req [:params :submission-ids]))
                            (map str)
                            distinct
                            vec)
        submissions (mapv store/submission-by-id submission-ids)
        task-id (some-> (get-in req [:params :task-id]) str str/trim)
        task-name (some-> (get-in req [:params :task-name]) str str/trim not-empty)
        due-on (parse-due-on (get-in req [:params :due-on]))
        instructions (some-> (get-in req [:params :instructions]) str str/trim not-empty)
        file-kind (requested-file-kind req task-name)]
    (cond
      (empty? submission-ids)
      {:status 422 :headers {"Content-Type" "text/plain; charset=utf-8"}
       :body "Choose at least one speaker."}

      (or (str/blank? task-id)
          (not (re-matches #"[A-Za-z0-9_-]{1,80}" task-id)))
      {:status 422 :headers {"Content-Type" "text/plain; charset=utf-8"}
       :body "Task identity is invalid. Refresh the page and try again."}

      (or (nil? task-name) (nil? due-on))
      {:status 422 :headers {"Content-Type" "text/plain; charset=utf-8"}
       :body "Enter a task name and due date."}

      (= ::invalid file-kind)
      {:status 422 :headers {"Content-Type" "text/plain; charset=utf-8"}
       :body "Choose a checklist or a supported upload type."}

      (and file-kind (nil? instructions))
      {:status 422 :headers {"Content-Type" "text/plain; charset=utf-8"}
       :body "Tell the speaker what file to upload."}

      (or (not= (count submission-ids) (count (remove nil? submissions)))
          (not-every? #(= (:id event) (:event-id %)) submissions))
      (web-event/not-found-page "that speaker")

      :else
      (let [task {:key (str "general-" task-id)
                  :label task-name
                  ;; Explicit organizer requests remain independent from the
                  ;; default checklist: each owns its deadline, file history,
                  ;; conversation, and completion evidence.
                  :task-type (if file-kind "file" "check")
                  :required? true
                  :due-on due-on
                  :instructions instructions
                  :file-kind file-kind}]
        (doseq [submission-id submission-ids]
          (speaker-tasks/install! submission-id task actor))
        (http/see-other (str "/events/" (:slug event) "/deliverables"))))))

(defn handle-reminder-schedule [req]
  (let [slug (get-in req [:path-params :slug])]
    (if-let [event (events/event-by-slug slug)]
      (let [actor (:email (auth/current-person req))]
        (if (= "create-general-task" (get-in req [:params :intent]))
          (create-general-task! req event actor)
          (try
            (speaker-tasks/configure-reminder-schedule!
              event
              {:enabled? (= "true" (get-in req [:params :enabled?]))
               :days-before (get-in req [:params :days-before])}
              actor)
            (http/see-other (str "/events/" slug "/deliverables"))
            (catch clojure.lang.ExceptionInfo e
              (if (= :invalid-reminder-schedule (:type (ex-data e)))
                {:status 422
                 :headers {"Content-Type" "text/plain; charset=utf-8"}
                 :body (.getMessage e)}
                (throw e))))))
      (web-event/not-found-page slug))))

(defn handle-record-chase [req]
  (let [slug (get-in req [:path-params :slug])
        submission-id (http/clean-id (get-in req [:path-params :submission-id]))
        task-key (get-in req [:path-params :task-key])
        event (events/event-by-slug slug)
        submission (when submission-id (store/submission-by-id submission-id))]
    (if-not (and event submission (= (:id event) (:event-id submission)))
      (web-event/not-found-page "that speaker obligation")
      (do
        (speaker-tasks/record-chase!
          submission-id
          task-key
          (get-in req [:params :chase-id])
          (get-in req [:params :note])
          (:email (auth/current-person req))
          {:medium (get-in req [:params :medium])})
        (http/see-other (str "/events/" slug "/deliverables"))))))

(defn- selected-values [req]
  (let [selected (get-in req [:params :selected])]
    (cond
      (nil? selected) []
      (sequential? selected) selected
      :else [selected])))

(defn- selection-key [value]
  (let [[submission-id task-key speaker-key] (str/split (str value) #"\|" 3)]
    [(http/clean-id submission-id) task-key speaker-key]))

(defn handle-draft-chases [req]
  (let [slug (get-in req [:path-params :slug])
        event (events/event-by-slug slug)]
    (if-not event
      (web-event/not-found-page slug)
      (let [person (auth/current-person req)
            obligations (mapcat current-speaker-contacts
                                (speaker-tasks/outstanding-for-event (:id event)))
            by-key (reduce
                     (fn [lookup task]
                       (let [exact [(:submission-id task) (:key task) (:speaker-key task)]
                             legacy [(:submission-id task) (:key task) nil]]
                         (cond-> (assoc lookup exact task)
                           (not (contains? lookup legacy)) (assoc legacy task))))
                     {}
                     obligations)
            selected (->> (selected-values req)
                          (map selection-key)
                          distinct
                          (keep by-key)
                          vec)
            error (cond
                    (empty? selected) "Select at least one speaker with an email address."
                    :else nil)
            drafts (when-not error
                     (mapv #(assoc (decisions/chase-draft event % person)
                                   :chase-id (str (:submission-id %) ":" (:key %) ":"
                                                  (or (:speaker-key %) "speaker") ":"
                                                  (inc (or (:chase-count %) 0))))
                           selected))]
        (http/html-response
          (if error 422 200)
          (view-speaker-tasks/chase-drafts-page
            event {:person person :drafts drafts :error error}))))))

(defn- reviewed-letter [params idx]
  (let [suffix (str idx)]
    {:chase-id (get params (keyword (str "chase-id-" suffix)))
     :submission-id (http/clean-id (get params (keyword (str "submission-id-" suffix))))
     :task-key (get params (keyword (str "task-key-" suffix)))
     :from (get params (keyword (str "from-" suffix)))
     :to (get params (keyword (str "to-" suffix)))
     :subject (get params (keyword (str "subject-" suffix)))
     :body (get params (keyword (str "body-" suffix)))}))

(defn handle-send-chases [req]
  (let [slug (get-in req [:path-params :slug])
        event (events/event-by-slug slug)]
    (if-not event
      (web-event/not-found-page slug)
      (let [person (auth/current-person req)
            count-value (parse-long (str (get-in req [:params :count])))
            count-value (when (and count-value (<= 1 count-value 50)) count-value)
            letters (if count-value
                      (mapv #(reviewed-letter (:params req) %) (range count-value))
                      [])
            results (speaker-tasks/send-chases! (:id event) letters (:email person))]
        (http/html-response
          (view-speaker-tasks/chase-results-page
            event {:person person :results results}))))))
