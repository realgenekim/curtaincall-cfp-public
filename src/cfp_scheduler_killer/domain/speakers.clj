(ns cfp-scheduler-killer.domain.speakers
  "Pure decisions and projections for event-scoped speaker participation.

   A person is a global identity. A submission carries the immutable speaker
   snapshot received with that talk. A participation is the organizer's
   event-local operational view. Keeping those three ideas separate prevents
  one conference from silently rewriting another conference's speaker data."
  (:require
   [cfp-scheduler-killer.domain.speaker-tasks :as speaker-tasks]
   [cfp-scheduler-killer.folds :as folds]
   [clojure.string :as str]))

(def statuses
  "Small operational vocabulary. Submission decisions remain on submissions."
  #{"Invited" "Confirmed" "Submitted" "Withdrawn"})

(def speaker-workspaces
  "The speaker lifecycle is data: navigation, copy, defaults, and visible state
   filters all come from this vector. Adding a workspace or state does not add
   another handler/view branch."
  [{:id :decide
    :label "Decide"
    :href "/board"
    :description "Choose which proposals to accept, waitlist, or decline."
    :default-filter "Pending"
    :filters [{:label "Needs decision" :value "Pending"}
              {:label "Accepted" :value "Accepted"}
              {:label "Waitlisted" :value "Waitlisted"}
              {:label "Declined" :value "Declined"}
              {:label "All" :value ""}]}
   {:id :inform
    :label "Inform"
    :href "/inform"
    :description "Communicate each recorded decision to the speaker."
    :default-filter "Needs notification"
    :filters [{:label "Needs informing" :value "Needs notification"}
              {:label "Informed" :value "Informed"}
              {:label "All" :value ""}]}
   {:id :manage
    :label "Manage"
    :href "/speakers?view=manage"
    :description "Manage the active speaker roster, portal access, and public visibility."
    :default-filter "All"
    :filters [{:label "Active" :value "Active"}
              {:label "Invited" :value "Invited"}
              {:label "Confirmed" :value "Confirmed"}
              {:label "Withdrawn" :value "Withdrawn"}
              {:label "All" :value "All"}]}])

(defn speaker-workspace [id]
  (some #(when (= id (:id %)) %) speaker-workspaces))

(def editable-fields
  "Event-local operational details. Canonical identity/profile fields remain
   speaker-managed and must not be shadowed by participation facts."
  #{:title :organization :location :notes})

(defn normalize-email [email]
  (some-> email str str/trim str/lower-case not-empty))

(def ^:private email-pattern
  "The same canonical address shape enforced by submissions and committees."
  #"^[^@\s]+@[^@\s]+\.[^@\s]+$")

(defn normalize-name
  "The house name-identity convention: trim, lower-case, collapse internal
   whitespace — the same shape public-catalog/norm-name uses to decide that two
   speaker names are the same name. Restated here deliberately: a domain
   namespace must never reach into a read projection for a helper."
  [name]
  (-> (or name "") str str/trim str/lower-case (str/replace #"\s+" " ")))

(defn- rejected [reason message]
  {:rejected {:reason reason :message message}})

(defn- event-exists? [state event-id]
  (boolean (some #(= event-id (:id %)) (vals (:events state)))))

(defn- participation [state event-id person-id]
  (get-in state [:speaker-participations [event-id person-id]]))

(defn person-by-email
  "The existing global identity for this email, if any. decide-add below reuses
   it instead of minting a second person, which is why a same-email create can
   never produce a duplicate contact."
  [state email]
  (let [wanted (normalize-email email)]
    (some (fn [[_ person]]
            (when (= wanted (normalize-email (:email person))) person))
          (:people state))))

(defn- title-of [submission]
  (or (get-in submission [:answers :talk-title])
      (get-in submission [:answers "talk-title"])
      (:title submission)
      "Untitled session"))

(defn- submissions-for-event [state event-id]
  (->> (vals (:submissions state))
       (filter #(= event-id (:event-id %)))
       (map #(folds/effective-submission-speakers state %))))

(defn- submission-speaker-rows [state event-id]
  (for [submission (submissions-for-event state event-id)
        speaker (:speakers submission)
        :when (:person-id speaker)]
    {:person-id (:person-id speaker)
     :speaker speaker
     :submission submission}))

(defn- derived-status [rows]
  (if (some (fn [{:keys [submission]}]
              (and (= "accepted" (some-> (:status submission) name str/lower-case))
                   (:notified-at submission)))
            rows)
    "Confirmed"
    "Submitted"))

(defn normalize-custom-values
  "Keep event-scoped custom-value lookups stable across JSONL serialization,
   whose nested object keys are read back as keywords."
  [values]
  (into {} (map (fn [[id value]] [(name id) value])) (or values {})))

(defn missing-profile-fields
  "Ordered profile requirements shared by organizer and speaker surfaces."
  [profile]
  (cond-> []
    (str/blank? (:bio profile)) (conj "bio")
    (str/blank? (:headshot-url profile)) (conj "headshot")))

(def ^:private speaker-lifecycle-fact-types
  #{"speaker.added-to-event"
    "speaker.status-changed"
    "speaker.details-updated"})

(def ^:private submission-lifecycle-fact-types
  #{"submission.created"
    "submission.speaker-assigned"
    "submission.speaker-unassigned"
    "submission.status-changed"
    "submission.notified"})

(def ^:private task-lifecycle-fact-types
  #{"task.installed"
    "task.completed"
    "task.chase-recorded"})

(defn- fact-at [{:keys [payload]}]
  (some payload [:at :created-at :added-at :changed-at :completed-at]))

(defn- fact-in-event? [event-id fact]
  (let [fact-event-id (or (:event-id fact) (get-in fact [:payload :event-id]))]
    (or (nil? fact-event-id) (= event-id fact-event-id))))

(defn- lifecycle-fact?
  [event-id person-id submission-ids {:keys [type payload] :as fact}]
  (and
    (fact-in-event? event-id fact)
    (cond
      (contains? speaker-lifecycle-fact-types type)
      (= person-id (:person-id payload))

      (contains? submission-lifecycle-fact-types type)
      (contains? submission-ids (or (:submission-id payload) (:id payload)))

      (contains? task-lifecycle-fact-types type)
      (contains? submission-ids (:submission-id payload))

      (= "email.sent" type)
      (and (= "speaker-chase" (:kind payload))
           (contains? submission-ids (:submission-id payload)))

      :else false)))

(defn- lifecycle-history-entry
  [state {:keys [type actor payload] :as fact}]
  (let [submission-id (or (:submission-id payload) (:id payload))
        task-key (or (:task-key payload) (:key payload))
        task (get-in state [:tasks [submission-id task-key]])
        submission (get-in state [:submissions submission-id])]
    (cond-> {:type type
             :actor actor
             :at (fact-at fact)}
      submission-id (assoc :submission-id submission-id)
      task-key (assoc :task-key task-key)
      (or (:label payload) (:label task)) (assoc :label (or (:label payload) (:label task)))
      submission (assoc :talk-title (title-of submission))
      (or (:status payload) (:to payload) (:status-at-notify payload))
      (assoc :status (or (:status payload) (:to payload) (:status-at-notify payload))))))

(defn- lifecycle-read
  "One event-scoped speaker read assembled entirely from the folded state and
   its append-only fact log. No lifecycle state is written independently."
  [state event-id person-id rows status]
  (let [submission-ids (into #{} (map (comp :id :submission)) rows)
        pending-tasks (->> (:tasks state)
                           vals
                           (filter #(contains? submission-ids (:submission-id %)))
                           (remove speaker-tasks/task-done?)
                           (map #(-> (select-keys % [:submission-id :key :label :due-on
                                                    :last-chased-at])
                                     (assoc :chase-count (count (:chases %)))))
                           (sort-by (juxt #(if (:due-on %) 0 1)
                                          #(some-> (:due-on %) str)
                                          :label
                                          :key))
                           vec)
        history (->> (:log state)
                     (filter #(lifecycle-fact? event-id person-id submission-ids %))
                     (mapv #(lifecycle-history-entry state %)))]
    {:status status
     :pending-tasks pending-tasks
     :history history}))

(defn roster
  "One row per person participating in an event, whether they arrived through
   a submission or an explicit organizer invitation."
  [state event-id]
  (let [submission-rows (group-by :person-id
                                  (submission-speaker-rows state event-id))
        participation-rows (into {}
                                 (keep (fn [[[eid person-id] row]]
                                         (when (= event-id eid)
                                           [person-id row])))
                                 (:speaker-participations state))
        person-ids (into (set (keys submission-rows))
                         (keys participation-rows))]
    (->> person-ids
         (map (fn [person-id]
                (let [rows (get submission-rows person-id)
                      submitted (some-> rows first :speaker)
                      person (get-in state [:people person-id])
                      local (get participation-rows person-id)
                      profile (:profile person)
                      profile-missing (missing-profile-fields profile)]
                  (merge
                    {:person-id person-id
                     :name (or (:name person) (:name submitted) "Unknown speaker")
                     :email (or (:email person) (:email submitted))
                     :tagline (:tagline profile)
                     :title (:tagline profile)
                     :profile-organization (:org profile)
                     :event-organization (:organization local)
                     ;; The current person profile wins over the immutable
                     ;; submission snapshot, including for invited speakers who
                     ;; never submitted. An event-local organizer override is
                     ;; merged over this projection below.
                     :organization (or (:org profile)
                                       (:organization submitted) (:org submitted))
                     :bio (:bio profile)
                     :headshot-url (:headshot-url profile)
                     :linkedin-url (:linkedin-url profile)
                     :website-url (:website-url profile)
                     :status (derived-status rows)
                     :talks (->> rows (map (comp title-of :submission)) distinct vec)
                     :profile-missing profile-missing
                     :profile-complete? (empty? profile-missing)
                     :lifecycle (lifecycle-read
                                  state event-id person-id rows
                                  (or (:status local) (derived-status rows)))}
                    (assoc (select-keys local (conj editable-fields :status))
                      :custom-values
                      (normalize-custom-values (:custom-values local)))))))
         (sort-by (comp str/lower-case str :name))
         vec)))

(defn- roster-row [state event-id person-id]
  (some #(when (= person-id (:person-id %)) %) (roster state event-id)))

(defn decide-add
  [state {:keys [event-id person-id email name status actor at]}]
  (let [email (normalize-email email)
        status (or status "Invited")
        found (person-by-email state email)
        actual-person-id (or (:id found) person-id)]
    (cond
      (not (event-exists? state event-id))
      (rejected :event-not-found "The event does not exist.")

      (nil? email)
      (rejected :email-required "A speaker email is required.")

      (not (re-matches email-pattern email))
      (rejected :invalid-email "Enter a complete speaker email address.")

      (str/blank? (str name))
      (rejected :name-required "A speaker name is required.")

      (not (contains? statuses status))
      (rejected :invalid-status "Choose a known speaker status.")

      (str/blank? (str actor))
      (rejected :actor-required "A named organizer must make this change.")

      (str/blank? (str actual-person-id))
      (rejected :person-id-required "A new speaker needs an id.")

      (roster-row state event-id actual-person-id)
      {:facts [] :existing? true :person-id actual-person-id}

      :else
      {:person-id actual-person-id
       :facts (cond-> []
                (nil? found)
                (conj {:type "person.created"
                       :actor actor
                       :event-id event-id
                       :payload {:id actual-person-id
                                 :email email
                                 :name (str/trim (str name))
                                 :profile {}
                                 :created-at at}})

                true
                (conj {:type "speaker.added-to-event"
                       :actor actor
                       :event-id event-id
                       :payload {:event-id event-id
                                 :person-id actual-person-id
                                 :status status
                                 :added-at at}}))})))

(defn decide-status
  [state {:keys [event-id person-id status actor at]}]
  (let [current (roster-row state event-id person-id)]
    (cond
      (not (event-exists? state event-id))
      (rejected :event-not-found "The event does not exist.")

      (not (contains? statuses status))
      (rejected :invalid-status "Choose a known speaker status.")

      (nil? current)
      (rejected :speaker-not-found "That speaker does not belong to this event.")

      (str/blank? (str actor))
      (rejected :actor-required "A named organizer must make this change.")

      (= status (:status current))
      {:facts [] :unchanged? true}

      :else
      {:facts [{:type "speaker.status-changed"
                :actor actor
                :event-id event-id
                :payload {:event-id event-id
                          :person-id person-id
                          :before (:status current)
                          :status status
                          :changed-at at}}]})))

(defn decide-edit
  [state {:keys [event-id person-id changes actor at]}]
  (let [current (roster-row state event-id person-id)
        changes (->> (select-keys changes editable-fields)
                     (map (fn [[k v]] [k (some-> v str str/trim not-empty)]))
                     (remove (fn [[k v]] (= v (get current k))))
                     (into {}))]
    (cond
      (not (event-exists? state event-id))
      (rejected :event-not-found "The event does not exist.")

      (nil? current)
      (rejected :speaker-not-found "That speaker does not belong to this event.")

      (str/blank? (str actor))
      (rejected :actor-required "A named organizer must make this change.")

      (empty? changes)
      {:facts [] :unchanged? true}

      :else
      {:facts [{:type "speaker.details-updated"
                :actor actor
                :event-id event-id
                :payload {:event-id event-id
                          :person-id person-id
                          :before (select-keys current (keys changes))
                          :changes changes
                          :changed-at at}}]})))

(defn decide-rename
  "Correct a roster member's canonical display name. The existing program
   speaker fact is also the durable person-identity correction understood by
   the fold, whether or not this roster member has a published session yet."
  [state {:keys [event-id person-id name actor at]}]
  (let [current (roster-row state event-id person-id)
        person (get-in state [:people person-id])
        display-name (-> (or name "") str str/trim (str/replace #"\s+" " "))]
    (cond
      (not (event-exists? state event-id))
      (rejected :event-not-found "The event does not exist.")

      (nil? current)
      (rejected :speaker-not-found "That speaker does not belong to this event.")

      (str/blank? display-name)
      (rejected :name-required "A speaker name is required.")

      (str/blank? (str actor))
      (rejected :actor-required "A named organizer must make this change.")

      (= display-name (:name person))
      {:facts [] :unchanged? true}

      :else
      {:facts [{:type "event.program-speaker-updated"
                :actor actor
                :event-id event-id
                :payload {:event-id event-id
                          :person-id person-id
                          :entry {:name display-name}
                          :profile {}
                          :before {:name (:name person)}
                          :at at}}]})))
