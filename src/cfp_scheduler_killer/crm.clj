(ns cfp-scheduler-killer.crm
  "Application service for the cross-event people workspace."
  (:require
   [cfp-scheduler-killer.domain.crm :as domain]
   [cfp-scheduler-killer.speakers :as speakers]
   [cfp-scheduler-killer.store :as store]
   [taoensso.timbre :as log]))

(defn directory-for [viewer-id filters]
  (domain/directory (store/snapshot) viewer-id filters))

(defn detail-for [viewer-id person-id]
  (domain/contact-detail (store/snapshot) viewer-id person-id))

;; INTENT: SPK-002
(defn duplicate-name-candidates
  "Advisory only: visible contacts sharing a normalized name with a hand-entered
   speaker but carrying a different email. Reads nothing the viewer cannot
   already see, and never blocks the write."
  [viewer-id name email]
  (domain/duplicate-name-candidates (store/snapshot) viewer-id
                                    {:name name :email email}))

(defn- append-decision! [decision log-key fields]
  (if-let [rejection (:rejected decision)]
    {:rejected rejection}
    (do
      (when (seq (:facts decision))
        (store/append-all! (:facts decision))
        (log/info log-key (apply hash-map fields)))
      decision)))

(defn add-note! [viewer-id person-id {:keys [event-id body actor]}]
  (append-decision!
    (domain/decide-add-note
      (store/snapshot)
      {:viewer-id viewer-id :person-id person-id :event-id event-id
       :note-id (store/new-id) :body body :actor actor :at (store/now-iso)})
    :crm-note-added
    [:viewer-id viewer-id :person-id person-id :event-id event-id]))

(defn add-tag! [viewer-id person-id {:keys [event-id tag actor]}]
  (append-decision!
    (domain/decide-add-tag
      (store/snapshot)
      {:viewer-id viewer-id :person-id person-id :event-id event-id
       :tag tag :actor actor :at (store/now-iso)})
    :crm-tag-added
    [:viewer-id viewer-id :person-id person-id :event-id event-id :tag tag]))

(defn remove-tag! [viewer-id person-id {:keys [event-id tag actor]}]
  (append-decision!
    (domain/decide-remove-tag
      (store/snapshot)
      {:viewer-id viewer-id :person-id person-id :event-id event-id
       :tag tag :actor actor :at (store/now-iso)})
    :crm-tag-removed
    [:viewer-id viewer-id :person-id person-id :event-id event-id :tag tag]))

(defn import-to-event! [viewer-id event-id parsed actor]
  (let [state (store/snapshot)]
    (if (some #(= event-id (:id %)) (domain/accessible-events state viewer-id))
      (speakers/import! event-id parsed actor)
      {:rejected {:reason :forbidden
                  :message "That event is outside your organizing scope."}})))

(defn save-segment! [viewer-id event-id name filters actor]
  (append-decision!
    (domain/decide-save-segment
      (store/snapshot)
      {:viewer-id viewer-id :event-id event-id :segment-id (store/new-id)
       :name name :filters filters :actor actor :at (store/now-iso)})
    :crm-segment-saved
    [:viewer-id viewer-id :event-id event-id :name name]))

(defn remove-segment! [viewer-id segment-id actor]
  (append-decision!
    (domain/decide-remove-segment
      (store/snapshot)
      {:viewer-id viewer-id :segment-id segment-id
       :actor actor :at (store/now-iso)})
    :crm-segment-removed
    [:viewer-id viewer-id :segment-id segment-id]))

(defn outreach-preview [viewer-id command]
  (domain/prepare-outreach (store/snapshot) (assoc command :viewer-id viewer-id)))

(defn record-outreach! [viewer-id command]
  (append-decision!
    (domain/decide-record-outreach
      (store/snapshot)
      (assoc command :viewer-id viewer-id :draft-id (store/new-id)
             :at (store/now-iso)))
    :crm-outreach-drafted
    [:viewer-id viewer-id :event-id (:event-id command)
     :recipient-count (count (:recipient-ids command))]))

(defn push-to-event!
  "Add an existing contact to an accessible event's invited-speaker lane.
   Existing speaker participation is idempotent and never downgraded."
  [viewer-id person-id event-id actor]
  (let [state (store/snapshot)
        detail (domain/contact-detail state viewer-id person-id)
        contact (:contact detail)
        target (some #(when (= event-id (:id %)) %) (:events detail))
        existing (some #(when (and (= event-id (:event-id %)) (:speaker? %)) %)
                       (:connections contact))]
    (cond
      (or (nil? contact) (nil? target))
      {:rejected {:reason :forbidden
                  :message "That contact or event is outside your organizing scope."}}
      existing {:facts [] :unchanged? true :person-id person-id}
      :else (speakers/add! event-id {:email (:email contact)
                                     :name (:name contact)
                                     :status "Invited"
                                     :actor actor}))))
