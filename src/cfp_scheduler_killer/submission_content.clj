(ns cfp-scheduler-killer.submission-content
  "Canonical, append-only changes to a submission's session content.

   Authorization is deliberately outside this namespace: speakers and
   organizers have different policies, but both must validate and emit the
   same fact. History is reconstructed from those facts; restore is another
   forward write, never a rewind."
  (:require
   [cfp-scheduler-killer.store :as store]
   [cfp-scheduler-killer.submissions :as submissions]
   [taoensso.timbre :as log]))

(defn- submitted-fields
  [snapshot params]
  (keep (fn [field]
          (let [k (keyword (name (:id field)))]
            (when (contains? params (keyword (str "answer-" (name k))))
              k)))
        (submissions/session-fields snapshot)))

(defn- speaker-draft
  [submission]
  (select-keys (first (:speakers submission))
               [:name :email :title :org :bio :headshot-url :linkedin-url]))

(defn- validate-answers
  [submission answers]
  (submissions/validation-errors (:form-snapshot submission)
                                 answers
                                 (speaker-draft submission)))

(defn- append-change!
  [submission desired actor extra-payload]
  (let [current (:answers submission)
        changes (into {}
                      (filter (fn [[k v]] (not= v (get current k))))
                      desired)]
    (if (empty? changes)
      {:ok true :submission submission :unchanged? true}
      (let [before (select-keys current (keys changes))]
        (store/append!
          {:type "submission.answers-updated"
           :actor actor
           :event-id (:event-id submission)
           :payload (merge {:submission-id (:id submission)
                            :changed (mapv name (keys changes))
                            :before before
                            :changes changes
                            :at (store/now-iso)}
                           extra-payload)})
        (when (= "Approved" (submissions/content-status submission))
          (submissions/set-content-status! (:id submission) "In review" actor))
        (log/info :submission-content-updated
                  :submission-id (:id submission)
                  :actor actor
                  :fields (mapv name (keys changes)))
        {:ok true :submission (store/submission-by-id (:id submission))}))))

(defn update-answers!
  "Validate fields carried by `params`, then append one canonical content fact.
   This function makes no authorization decision; the invoking adapter owns it."
  [submission-id params actor]
  (if-let [submission (store/submission-by-id submission-id)]
    (let [snapshot (:form-snapshot submission)
          fields (submitted-fields snapshot params)
          submitted (select-keys (submissions/parse-answers snapshot params) fields)
          answers (merge (:answers submission) submitted)
          errors (validate-answers submission answers)]
      (if errors
        {:ok false :errors errors :answers answers}
        (append-change! submission submitted actor {})))
    {:ok false :errors {:_ ["That submission no longer exists."]}}))

(defn revision-history
  "Return chronological, complete before/after versions for content edits and
   editorial status transitions.

   Each revision is named by its exact log index. Walking backward from current
   state makes the projection independent of timestamps and robust to old facts
   that changed only a subset of fields."
  [submission-id]
  (when-let [submission (store/submission-by-id submission-id)]
    (let [revisions (->> (store/indexed-log-for-event (:event-id submission))
                         (filter (fn [{:keys [event]}]
                                   (and (contains? #{"submission.answers-updated"
                                                     "submission.content-status-changed"}
                                                   (:type event))
                                        (= submission-id
                                           (get-in event [:payload :submission-id])))))
                         vec)]
      (->> revisions
           reverse
           (reduce (fn [{:keys [answers rows]} {:keys [log-index event]}]
                     (case (:type event)
                       "submission.answers-updated"
                       (let [prior (merge answers (get-in event [:payload :before]))]
                         {:answers prior
                          :rows (conj rows
                                      {:kind :answers
                                       :log-index log-index
                                       :at (:at event)
                                       :actor (:actor event)
                                       :changed (get-in event [:payload :changed])
                                       :before prior
                                       :after answers
                                       :restored-from-log-index
                                       (get-in event [:payload :restored-from-log-index])})})

                       "submission.content-status-changed"
                       {:answers answers
                        :rows (conj rows
                                    {:kind :content-status
                                     :log-index log-index
                                     :at (:at event)
                                     :actor (:actor event)
                                     :before-status (get-in event [:payload :from])
                                     :after-status (get-in event [:payload :to])})}))
                   {:answers (:answers submission) :rows []})
           :rows
           reverse
           vec))))

(defn restore!
  "Restore the content or editorial status immediately before `log-index`.
   The result is a new forward fact; existing history is never rewritten."
  [submission-id log-index actor]
  (if-let [submission (store/submission-by-id submission-id)]
    (if-let [revision (first (filter #(= log-index (:log-index %))
                                     (revision-history submission-id)))]
      (if (= :content-status (:kind revision))
        (let [desired (:before-status revision)
              unchanged? (= desired (submissions/content-status submission))]
          {:ok true
           :submission (submissions/set-content-status! submission-id desired actor)
           :unchanged? unchanged?})
        (let [desired (:before revision)
              errors (validate-answers submission desired)]
          (if errors
            {:ok false :errors errors :answers desired}
            (append-change! submission desired actor
                            {:restored-from-log-index log-index}))))
      {:ok false :errors {:_ ["That session revision no longer exists."]}})
    {:ok false :errors {:_ ["That submission no longer exists."]}}))
