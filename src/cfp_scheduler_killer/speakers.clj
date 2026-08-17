(ns cfp-scheduler-killer.speakers
  "Thin append-only shell for event-scoped speaker participation."
  (:require
   [cfp-scheduler-killer.domain.speakers :as domain]
   [cfp-scheduler-killer.folds :as folds]
   [cfp-scheduler-killer.store :as store]
   [clojure.string :as str]
   [taoensso.timbre :as log]))

(defn roster-for-event [event-id]
  (domain/roster (store/snapshot) event-id))

(defn- append-decision! [decision log-key fields]
  (if-let [rejection (:rejected decision)]
    decision
    (do
      (when (seq (:facts decision))
        (store/append-all! (:facts decision))
        (log/info log-key (apply hash-map fields)))
      decision)))

(defn add!
  [event-id {:keys [email name status actor] :as command}]
  (let [decision (domain/decide-add
                   (store/snapshot)
                   (assoc command
                          :event-id event-id
                          :person-id (store/new-id)
                          :actor actor
                          :at (store/now-iso)))]
    (append-decision! decision :speaker-added
                      [:event-id event-id :email email :name name :status status])))

(defn change-status!
  [event-id person-id status actor]
  (append-decision!
    (domain/decide-status
      (store/snapshot)
      {:event-id event-id :person-id person-id :status status
       :actor actor :at (store/now-iso)})
    :speaker-status-changed
    [:event-id event-id :person-id person-id :status status]))

(defn edit!
  [event-id person-id changes actor]
  (append-decision!
    (domain/decide-edit
      (store/snapshot)
      {:event-id event-id :person-id person-id :changes changes
       :actor actor :at (store/now-iso)})
    :speaker-details-updated
    [:event-id event-id :person-id person-id :fields (mapv name (keys changes))]))

(defn- change-publication!
  [event-id person-id actor fact-type]
  (if-not (some #(= (str person-id) (str (:person-id %)))
                (get-in (store/get-event-by-id event-id)
                        [:settings :announced-speakers]))
    {:rejected {:reason :speaker-not-found
                :message "That manually managed speaker does not belong to this event."}}
    (let [at (store/now-iso)
          fact (store/append! {:type fact-type
                               :actor actor
                               :event-id event-id
                               :payload {:event-id event-id
                                         :person-id person-id
                                         :at at}})]
      (log/info (if (= "speaker.published" fact-type)
                  :speaker-published
                  :speaker-unpublished)
                :event-id event-id :person-id person-id)
      {:ok true :fact fact})))

(defn publish!
  "Publish an event speaker through one append-only lifecycle fact."
  [event-id person-id actor]
  (change-publication! event-id person-id actor "speaker.published"))

(defn unpublish!
  "Unpublish an event speaker through one append-only lifecycle fact."
  [event-id person-id actor]
  (change-publication! event-id person-id actor "speaker.unpublished"))

(defn rename!
  [event-id person-id name actor]
  (append-decision!
    (domain/decide-rename
      (store/snapshot)
      {:event-id event-id :person-id person-id :name name
       :actor actor :at (store/now-iso)})
    :speaker-name-corrected
    [:event-id event-id :person-id person-id]))

(def ^:private imported-profile-keys [:bio :headshot-url])

(defn- imported-profile-facts [state person-id values actor at]
  (let [profile (get-in state [:people person-id :profile])
        supplied (into {}
                       (keep (fn [[key value]]
                               (when-let [value (some-> value str str/trim not-empty)]
                                 [key value])))
                       (select-keys values imported-profile-keys))
        changes (into {} (remove (fn [[key value]]
                                   (= value (get profile key)))) supplied)]
    (if (empty? changes)
      []
      [{:type "person.profile-updated"
        :actor actor
        :payload {:person-id person-id
                  :changed (mapv name (keys changes))
                  :before (select-keys profile (keys changes))
                  :changes changes
                  :at at}}])))

(defn import!
  "Append every valid parsed CSV row as one atomic batch. The pure decisions
   fold into a private working state first, so duplicate/existing identities and
   retries behave exactly as they do one row at a time."
  [event-id parsed actor]
  (if-not (:valid? parsed)
    {:rejected {:reason :invalid-csv
                :message "Fix the CSV errors before importing."}}
    (let [{:keys [facts] :as result}
          (reduce
            (fn [{:keys [state facts]} {:keys [values]}]
              (let [at (store/now-iso)
                    base-command (assoc values
                                        :event-id event-id
                                        :person-id (store/new-id)
                                        :actor actor
                                        :at at)
                    add-decision (domain/decide-add state base-command)]
                (if-let [rejection (:rejected add-decision)]
                  (reduced {:state state :facts facts :rejected rejection})
                  (let [after-add (reduce folds/fold-event state (:facts add-decision))
                        actual-id (:person-id add-decision)
                        profile-facts (imported-profile-facts
                                        after-add actual-id values actor at)
                        after-profile (reduce folds/fold-event after-add profile-facts)
                        changes (select-keys values domain/editable-fields)
                        edit-decision (domain/decide-edit
                                        after-profile
                                        {:event-id event-id
                                         :person-id actual-id
                                         :changes changes
                                         :actor actor
                                         :at at})]
                    (if-let [rejection (:rejected edit-decision)]
                      (reduced {:state state :facts facts :rejected rejection})
                      {:state (reduce folds/fold-event after-profile (:facts edit-decision))
                       :facts (into facts (concat (:facts add-decision)
                                                  profile-facts
                                                  (:facts edit-decision)))})))))
            {:state (store/snapshot) :facts []}
            (:rows parsed))]
      (if-let [rejection (:rejected result)]
        {:rejected rejection}
        (do
          (when (seq facts)
            (store/append-all! facts)
            (log/info :speakers-imported :event-id event-id
                      :rows (count (:rows parsed)) :facts (count facts)))
          {:ok true :facts-count (count facts)
           :imported-count (count (:rows parsed))
           :unchanged? (empty? facts)})))))
