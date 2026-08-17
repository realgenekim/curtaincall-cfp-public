(ns cfp-scheduler-killer.portal
  "The speaker's side of the house.

   Two rules shape everything here:

   1. **The inform gate.** A speaker sees `Under review` until their decision has
      been deliberately communicated (see cfp-scheduler-killer.inform). This
      namespace never reads `:status` for display — it reads `:notified-status`,
      which only exists once someone told them.

   2. **No lock after acceptance.** swyx was explicit: accepted speakers can
      still edit. A talk is a living thing until it is delivered, and a tool that
      freezes it just moves the edits into email. Edits validate against the
      submission's own form SNAPSHOT, so the questions never shift underneath
  an answer."
  (:require
   [cfp-scheduler-killer.domain.speaker-tasks :as task-decisions]
   [cfp-scheduler-killer.speaker-custom-fields :as speaker-custom-fields]
   [cfp-scheduler-killer.speaker-tasks :as speaker-tasks]
   [cfp-scheduler-killer.speakers :as speakers]
   [cfp-scheduler-killer.store :as store]
   [cfp-scheduler-killer.submission-content :as content]
   [cfp-scheduler-killer.submissions :as submissions]
   [clojure.string :as str]
   [com.fulcrologic.guardrails.core :refer [=> >defn ?]]
   [taoensso.timbre :as log]))

(def under-review-label "Under review")

;; --- What the speaker is allowed to know ------------------------------------

;; INTENT: CFP-001 — committee status is not speaker-visible until the separate
;; notification fact records that the decision was communicated.
(>defn visible-status
       "The status AS THE SPEAKER MAY SEE IT.

   This is the single most important function in the speaker-facing app: it is
   the difference between a committee's working note and a promise. Until
   `notified-at` exists, everything reads 'Under review' — including a talk the
   committee accepted an hour ago."
       [submission]
       [map? => string?]
       (if (:notified-at submission)
         (or (:notified-status submission) (:status submission))
         under-review-label))

(defn informed? [submission] (some? (:notified-at submission)))

(defn accepted-and-informed? [submission]
  (and (informed? submission) (= "Accepted" (visible-status submission))))

;; --- Editing ----------------------------------------------------------------

(def editable-statuses
  "Who may still edit. A withdrawn or declined talk is closed — editing it would
   imply it is still in play, which would be unkind rather than permissive."
  #{"Pending" "Accept Queue" "Accepted" "Waitlisted" "Decline Queue" "Draft"})

(defn edit-lock-message
  "Why a speaker cannot edit, or nil while editing is allowed."
  [event submission]
  (cond
    (not (contains? editable-statuses (:status submission)))
    (str "This submission can no longer be edited (it is " (:status submission) ").")

    (= :closed (submissions/cfp-state event))
    "The call closed — editing is locked."

    (= :not-open-yet (submissions/cfp-state event))
    "The call is not open — editing is locked."

    :else nil))

(defn editable?
  [event submission]
  (nil? (edit-lock-message event submission)))

(>defn update-answers!
       "Apply a speaker's edits to their own submission.

   The speaker policy lives here; canonical validation and event creation live
   in submission-content so organizer edits cannot drift into a second fact
   shape. Returns {:ok true :submission ..} or {:ok false :errors {..}}."
       ([submission-id params] [string? map? => map?]
                               (update-answers! submission-id params "speaker"))
       ([submission-id params actor]
        [string? map? string? => map?]
        (let [submission (store/submission-by-id submission-id)
              event (when submission
                      (store/get-event-by-id (:event-id submission)))]
          (cond
            (nil? submission)
            {:ok false :errors {:_ ["That submission no longer exists."]}}

            (not (editable? event submission))
            {:ok false :errors {:_ [(edit-lock-message event submission)]}}

            :else
            (content/update-answers! submission-id params actor)))))

;; --- Profile ----------------------------------------------------------------

(def profile-fields
  "What a speaker maintains about themselves. Kept small on purpose: every field
   here is one a conference actually reprints."
  [{:key :tagline :label "Tagline" :type "text"
    :help "One line, as it appears under your name in the program."}
   {:key :org :label "Organization" :type "text"}
   {:key :bio :label "Bio" :type "textarea"}
   {:key :headshot-url :label "Headshot URL" :type "url"}
   {:key :linkedin-url :label "LinkedIn" :type "url"}
   {:key :website-url :label "Website" :type "url"}])

(def ^:private url-keys #{:headshot-url :linkedin-url :website-url})

(defn parse-profile [params]
  (into {}
        (keep (fn [{:keys [key]}]
                (let [v (get params key)]
                  (when (some? v)
                    [key (when-not (str/blank? v) (str/trim v))]))))
        profile-fields))

(defn profile-errors
  [profile]
  (let [errs (into {}
                   (keep (fn [[k v]]
                           (when (and (contains? url-keys k) v
                                      (not (if (= :headshot-url k)
                                             (submissions/valid-headshot-url? v)
                                             (re-matches submissions/url-pattern v))))
                             [k ["Enter a full URL starting with http:// or https://"]])))
                   profile)]
    (when (seq errs) errs)))

(>defn update-profile!
       "A speaker editing their own description of themselves. Merges the keys they
   sent; a field they left blank is CLEARED (they meant to remove it), a field
   the form didn't include is untouched."
       ([person-id params] [string? map? => map?] (update-profile! person-id params "speaker"))
       ([person-id params actor]
        [string? map? string? => map?]
        (let [person (store/person-by-id person-id)
              profile (parse-profile params)
              errors (profile-errors profile)]
          (cond
            (nil? person) {:ok false :errors {:_ ["We don't know who you are."]}}
            errors {:ok false :errors errors :profile profile}
            :else
            (let [before (select-keys (:profile person) (keys profile))
                  changed (into {} (remove (fn [[k v]] (= v (get before k)))) profile)]
              (if (empty? changed)
                {:ok true :person person :unchanged? true}
                (do
                  (store/append!
                    {:type "person.profile-updated" :actor actor
                     :payload {:person-id person-id
                               :changed (mapv name (keys changed))
                               :before before
                               :changes changed
                               :at (store/now-iso)}})
                  (log/info :profile-updated :person-id person-id
                            :fields (mapv name (keys changed)))
                  {:ok true :person (store/person-by-id person-id)})))))))

(defn prefill-from-profile
  "Speaker-block defaults for a NEW submission, from what they already told us.
   Their profile is the reason they shouldn't have to type their bio twice."
  [person]
  (let [p (:profile person)]
    (into {}
          (remove (comp str/blank? str val))
          {:speaker-name (:name person)
           :speaker-email (:email person)
           :speaker-title (:tagline p)
           :speaker-bio (:bio p)
           :speaker-headshot-url (:headshot-url p)
           :speaker-linkedin (:linkedin-url p)})))

;; --- Tasks ------------------------------------------------------------------

(defn task-response?
  [value]
  (or (nil? value) (string? value) (map? value)))

(>defn complete-task!
       "Tick a checkbox or save a URL through the shared obligation algebra.

   Preserve the historical adapter contract: an uninstalled task is a harmless
   no-op. The pure decision still reports :task-not-found to trusted callers."
       ([submission-id task-key value]
        [string? string? task-response? => (? map?)]
        (complete-task! submission-id task-key value "speaker"))
       ([submission-id task-key value actor]
        [string? string? task-response? string? => (? map?)]
        (let [task (get-in (store/snapshot) [:tasks [submission-id task-key]])]
          (when task
            (speaker-tasks/complete! submission-id task-key value actor)))))

(defn task-done?
  [task]
  (task-decisions/task-done? task))

(defn tasks-for
  [submission-id]
  (speaker-tasks/tasks-for-submission submission-id))

(defn task-progress
  [submission-id]
  (let [ts (tasks-for submission-id)]
    {:total (count ts) :done (count (filter :done? ts))}))

;; --- The portal view model --------------------------------------------------

(defn my-participations
  "Every event where this person is on the speaker roster, including explicit
   organizer invitations that do not have a submission yet."
  [person-id]
  (->> (store/all-events)
       (keep (fn [event]
               (when-let [speaker
                          (some #(when (= person-id (:person-id %)) %)
                                (speakers/roster-for-event (:id event)))]
                 {:event event
                  :speaker speaker
                  :custom-fields (speaker-custom-fields/fields-for-event (:id event))
                  :custom-values (speaker-custom-fields/values-for (:id event) person-id)})))
       (sort-by (comp str/lower-case str :name :event))
       vec))

(defn- current-speaker-identity [speaker]
  (let [person (some-> (:person-id speaker) store/person-by-id)]
    (cond-> speaker
      (seq (:name person)) (assoc :name (:name person))
      (seq (:email person)) (assoc :email (:email person)))))

(defn- current-submission-speakers [submission]
  (update submission :speakers #(mapv current-speaker-identity %)))

(defn record-visit!
  "Append the first portal visit for one person and event. Repeated portal GETs
   are reads after the first visit, not an ever-growing stream of duplicate facts."
  [event-id person-id actor]
  (when (and event-id
             person-id
             (not-any? #(and (= "portal.visited" (:type %))
                             (= person-id (get-in % [:payload :person-id])))
                       (store/log-for-event event-id)))
    (store/append! {:type "portal.visited"
                    :actor (or actor "speaker")
                    :event-id event-id
                    :payload {:person-id person-id}})))

(defn my-submissions
  "Everything this person submitted, with the speaker-safe status, schedule,
   and tasks. One shape, so the view never decides what a speaker may see."
  [person-id]
  (mapv (fn [s]
          (let [event (store/get-event-by-id (:event-id s))
                slot (store/slot-for (:id s))
                room (when-let [room-id (:room-id slot)]
                       (first (filter #(= room-id (:id %))
                                      (store/rooms-for-event (:id event)))))]
            {:submission (current-submission-speakers s)
             :event event
             :visible-status (visible-status s)
             :informed? (informed? s)
             :editable? (editable? event s)
             :edit-lock-message (edit-lock-message event s)
             :schedule (when slot
                         {:day (:day slot)
                          :start (:start slot)
                          :end (:end slot)
                          :room (:name room)})
             :tasks (tasks-for (:id s))
             :progress (task-progress (:id s))
             :custom-fields (speaker-custom-fields/fields-for-event (:id event))
             :custom-values (speaker-custom-fields/values-for (:id event) person-id)}))
        (store/submissions-for-person person-id)))

(defn submission-for-event
  "This person's real submission for an event, if any. Co-speakers count: the
   ownership relationship is the person-id recorded on the submission."
  [person-id event-id]
  (some #(when (= event-id (:event-id %)) %)
        (store/submissions-for-person person-id)))

(defn speaker?
  "Is this person on any event's speaker roster, by submission or invitation?"
  [person-id]
  (boolean (seq (my-participations person-id))))

(comment
  (store/load!)
  (let [p (store/person-by-email "priya.raghavan@meridianmutual.example.com")]
    (my-submissions (:id p))))
