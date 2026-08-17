(ns cfp-scheduler-killer.domain.crm
  "Pure cross-event contact projections and CRM decisions.

   A contact is not a second identity model. People remain canonical global
   identities; this namespace projects the committee and speaker relationships
   visible through the organizing viewer's event memberships."
  (:require
   [cfp-scheduler-killer.domain.speakers :as speakers]
   [clojure.string :as str]))

(def max-note-length 2000)
(def max-tag-length 40)
(def max-segment-name-length 80)
(def max-outreach-subject-length 200)
(def max-outreach-body-length 10000)
(def filter-keys [:q :organization :role :event :tag])

(defn- rejected [reason message]
  {:rejected {:reason reason :message message}})

(defn accessible-events
  "All events whose committee includes viewer-id. This is the CRM tenancy
   boundary: cross-event means across the organizer's events, never across
   another organization's event world."
  [state viewer-id]
  (let [committee-ids (into #{}
                            (comp (filter #(= viewer-id (:person-id %)))
                                  (map :committee-id))
                            (vals (:memberships state)))
        event-ids (into #{}
                        (comp (filter #(contains? committee-ids (:id %)))
                              (map :event-id))
                        (vals (:committees state)))]
    (->> (vals (:events state))
         (filter #(contains? event-ids (:id %)))
         (sort-by (juxt :starts-on :name :id))
         vec)))

(defn- committee-relationships [state event-id]
  (let [committee-ids (into #{}
                            (comp (filter #(= event-id (:event-id %)))
                                  (map :id))
                            (vals (:committees state)))]
    (->> (vals (:memberships state))
         (filter #(contains? committee-ids (:committee-id %)))
         (group-by :person-id))))

(defn event-connections
  "One relationship row per person visible inside event."
  [state event]
  (let [event-id (:id event)
        committee-by-person (committee-relationships state event-id)
        speakers-by-person (into {} (map (juxt :person-id identity))
                                 (speakers/roster state event-id))
        person-ids (into (set (keys committee-by-person))
                         (keys speakers-by-person))]
    (->> person-ids
         (keep (fn [person-id]
                 (when-let [person (get-in state [:people person-id])]
                   (let [speaker (get speakers-by-person person-id)
                         memberships (get committee-by-person person-id)
                         roles (cond-> (into #{} (map :role) memberships)
                                 speaker (conj "speaker"))]
                     {:person-id person-id
                      :name (or (:name person) (:name speaker) "Unknown person")
                      :email (or (:email person) (:email speaker))
                      :event-id event-id
                      :event-slug (:slug event)
                      :event-name (:name event)
                      :event-starts-on (:starts-on event)
                      :roles roles
                      :speaker? (boolean speaker)
                      :speaker-status (:status speaker)
                      :organization (:organization speaker)
                      :talks (vec (:talks speaker))}))))
         (sort-by (comp str/lower-case str :name))
         vec)))

(defn- active-tag-records [state event-ids person-id]
  (->> (vals (:crm-tags state))
       (filter #(and (= person-id (:person-id %))
                     (contains? event-ids (:event-id %))))
       (sort-by (juxt :tag :event-id))
       vec))

(defn contacts
  "Canonical contacts visible to viewer-id, with cross-event connections."
  [state viewer-id]
  (let [events (accessible-events state viewer-id)
        event-ids (into #{} (map :id) events)]
    (->> events
         (mapcat #(event-connections state %))
         (group-by :person-id)
         (map (fn [[person-id connections]]
                (let [person (get-in state [:people person-id])
                      organizations (->> connections (keep :organization)
                                         (remove str/blank?) distinct sort vec)
                      roles (->> connections (mapcat :roles) distinct sort vec)
                      talks (->> connections (mapcat :talks) distinct vec)
                      tag-records (active-tag-records state event-ids person-id)]
                  {:person-id person-id
                   :name (or (:name person) (:name (first connections)) "Unknown person")
                   :email (or (:email person) (:email (first connections)))
                   :profile (:profile person)
                   :organization (first organizations)
                   :organizations organizations
                   :roles roles
                   :connections (vec (sort-by (juxt :event-starts-on :event-name) connections))
                   :event-count (count connections)
                   :talks talks
                   :talk-count (count talks)
                   :repeat? (> (count connections) 1)
                   :tag-records tag-records
                   :tags (->> tag-records (map :tag) distinct sort vec)})))
         (sort-by (comp str/lower-case str :name))
         vec)))

(defn duplicate-name-candidates
  "ADVISORY duplicate detection for a hand-entered contact. Returns the contacts
   ALREADY VISIBLE to viewer-id whose normalized name matches, but whose email
   differs — never a rejection, never a reason to refuse the write.

   Two deliberate silences:

   - Scope is `contacts state viewer-id`, so a match can only ever name someone
     the viewer can already open. Another organizer's same-name contact is not a
     candidate and is never rendered.
   - An email that already resolves to a person yields nothing at all:
     speakers/decide-add reuses that identity rather than minting a second
     person, so no duplicate is possible and a warning would only put friction
     in front of the legitimate add-a-known-speaker-to-a-second-event flow."
  [state viewer-id {:keys [name email]}]
  (let [wanted-name (speakers/normalize-name name)
        wanted-email (speakers/normalize-email email)]
    (if (or (str/blank? wanted-name)
            (nil? wanted-email)
            (some? (speakers/person-by-email state wanted-email)))
      []
      (filterv (fn [contact]
                 (and (= wanted-name (speakers/normalize-name (:name contact)))
                      (not= wanted-email (speakers/normalize-email (:email contact)))))
               (contacts state viewer-id)))))

(defn- searchable-text [contact]
  (->> [(:name contact) (:email contact)
        (str/join " " (:organizations contact))
        (str/join " " (:roles contact))
        (str/join " " (:tags contact))
        (str/join " " (:talks contact))
        (str/join " " (map :event-name (:connections contact)))]
       (str/join " ")
       str/lower-case))

(defn filter-contacts [contacts* {:keys [q organization role event tag]}]
  (let [needle (some-> q str/trim str/lower-case not-empty)]
    (->> contacts*
         (filter (fn [contact]
                   (and (or (nil? needle)
                            (str/includes? (searchable-text contact) needle))
                        (or (str/blank? organization)
                            (some #{organization} (:organizations contact)))
                        (or (str/blank? role)
                            (some #{role} (:roles contact)))
                        (or (str/blank? event)
                            (some #(or (= event (:event-id %))
                                       (= event (:event-slug %)))
                                  (:connections contact)))
                        (or (str/blank? tag)
                            (some #{tag} (:tags contact))))))
         vec)))

(defn directory [state viewer-id filters]
  (let [all (contacts state viewer-id)
        visible (filter-contacts all filters)
        events (accessible-events state viewer-id)
        event-ids (into #{} (map :id) events)
        stage-of (fn [contact]
                   (let [statuses (set (keep :speaker-status (:connections contact)))]
                     (cond
                       (contains? statuses "Confirmed") :confirmed
                       (contains? statuses "Submitted") :submitted
                       (contains? statuses "Invited") :invited
                       (contains? statuses "Withdrawn") :withdrawn
                       :else :relationship)))]
    {:contacts visible
     :all-contacts all
     :events events
     :filters filters
     :organizations (->> all (mapcat :organizations) distinct sort vec)
     :roles (->> all (mapcat :roles) distinct sort vec)
     :tags (->> all (mapcat :tags) distinct sort vec)
     :segments (->> (vals (:crm-segments state))
                    (filter #(and (= viewer-id (:owner-id %))
                                  (contains? event-ids (:event-id %))))
                    (sort-by (juxt :name :created-at))
                    vec)
     :pipeline (->> all (group-by stage-of)
                    (map (fn [[stage rows]] [stage (vec rows)]))
                    (into {}))
     :stats {:contacts (count all)
             :repeat-contacts (count (filter :repeat? all))
             :organizations (count (distinct (mapcat :organizations all)))
             :events (count events)}}))

(defn- fact-event-id [fact]
  (or (:event-id fact) (get-in fact [:payload :event-id])))

(defn- contains-identity? [value person-id email]
  (cond
    (map? value) (or (= person-id (:person-id value))
                     (= person-id (:id value))
                     (= email (:email value))
                     (some #(contains-identity? % person-id email) (vals value)))
    (sequential? value) (some #(contains-identity? % person-id email) value)
    (set? value) (or (contains? value person-id) (contains? value email))
    :else (or (= value person-id) (= value email))))

(defn- activity-for [state event-ids events-by-id person]
  (->> (:log state)
       (map-indexed vector)
       (keep (fn [[index fact]]
               (let [event-id (fact-event-id fact)]
                 (when (and (contains? event-ids event-id)
                            (or (= (:email person) (:actor fact))
                                (contains-identity? (:payload fact)
                                                    (:id person)
                                                    (:email person))))
                   {:log-index index
                    :type (:type fact)
                    :at (:at fact)
                    :actor (:actor fact)
                    :event-id event-id
                    :event-name (get-in events-by-id [event-id :name])}))))
       reverse
       (take 100)
       vec))

(defn contact-detail [state viewer-id person-id]
  (let [events (accessible-events state viewer-id)
        event-ids (into #{} (map :id) events)
        events-by-id (into {} (map (juxt :id identity)) events)]
    (when-let [contact (some #(when (= person-id (:person-id %)) %)
                             (contacts state viewer-id))]
      (let [person (get-in state [:people person-id])]
        {:contact contact
         :events events
         :notes (->> (vals (:crm-notes state))
                     (filter #(and (= person-id (:person-id %))
                                   (contains? event-ids (:event-id %))))
                     (sort-by :at #(compare %2 %1))
                     vec)
         :activity (activity-for state event-ids events-by-id person)}))))

(defn- valid-context? [state viewer-id event-id person-id]
  (and (some #(= event-id (:id %)) (accessible-events state viewer-id))
       (some #(= person-id (:person-id %)) (contacts state viewer-id))))

(defn decide-add-note
  [state {:keys [viewer-id event-id person-id note-id body actor at]}]
  (let [body (some-> body str str/trim)]
    (cond
      (not (valid-context? state viewer-id event-id person-id))
      (rejected :forbidden "That contact or event is outside your organizing scope.")
      (str/blank? body) (rejected :note-required "Write a note first.")
      (> (count body) max-note-length)
      (rejected :note-too-long (str "Notes are limited to " max-note-length " characters."))
      (str/blank? (str actor)) (rejected :actor-required "A named organizer must add the note.")
      :else {:facts [{:type "crm.note-added" :event-id event-id :actor actor :at at
                      :payload {:id note-id :event-id event-id :person-id person-id
                                :body body :actor actor :at at}}]})))

(defn normalize-tag [tag]
  (some-> tag str str/trim str/lower-case not-empty))

(defn decide-add-tag
  [state {:keys [viewer-id event-id person-id tag actor at]}]
  (let [tag (normalize-tag tag)
        k [event-id person-id tag]]
    (cond
      (not (valid-context? state viewer-id event-id person-id))
      (rejected :forbidden "That contact or event is outside your organizing scope.")
      (nil? tag) (rejected :tag-required "Write a tag first.")
      (> (count tag) max-tag-length)
      (rejected :tag-too-long (str "Tags are limited to " max-tag-length " characters."))
      (str/blank? (str actor)) (rejected :actor-required "A named organizer must add the tag.")
      (get-in state [:crm-tags k]) {:facts [] :unchanged? true}
      :else {:facts [{:type "crm.tag-added" :event-id event-id :actor actor :at at
                      :payload {:event-id event-id :person-id person-id
                                :tag tag :actor actor :at at}}]})))

(defn decide-remove-tag
  [state {:keys [viewer-id event-id person-id tag actor at]}]
  (let [tag (normalize-tag tag)
        k [event-id person-id tag]]
    (cond
      (not (valid-context? state viewer-id event-id person-id))
      (rejected :forbidden "That contact or event is outside your organizing scope.")
      (nil? tag) (rejected :tag-required "Choose a tag to remove.")
      (nil? (get-in state [:crm-tags k])) {:facts [] :unchanged? true}
      :else {:facts [{:type "crm.tag-removed" :event-id event-id :actor actor :at at
                      :payload {:event-id event-id :person-id person-id
                                :tag tag :actor actor :at at}}]})))

(defn- normalized-filters [filters]
  (into {}
        (keep (fn [k]
                (when-let [value (some-> (get filters k) str str/trim not-empty)]
                  [k value])))
        filter-keys))

(defn decide-save-segment
  [state {:keys [viewer-id event-id segment-id name filters actor at]}]
  (let [name (some-> name str str/trim)]
    (cond
      (not (some #(= event-id (:id %)) (accessible-events state viewer-id)))
      (rejected :forbidden "That event is outside your organizing scope.")
      (str/blank? name) (rejected :segment-name-required "Name this segment first.")
      (> (count name) max-segment-name-length)
      (rejected :segment-name-too-long
                (str "Segment names are limited to " max-segment-name-length " characters."))
      :else {:facts [{:type "crm.segment-saved" :event-id event-id :actor actor :at at
                      :payload {:id segment-id :event-id event-id :owner-id viewer-id
                                :name name :filters (normalized-filters filters)
                                :created-at at :actor actor}}]})))

(defn decide-remove-segment
  [state {:keys [viewer-id segment-id actor at]}]
  (let [segment (get-in state [:crm-segments segment-id])
        event-id (:event-id segment)]
    (cond
      (nil? segment) {:facts [] :unchanged? true}
      (or (not= viewer-id (:owner-id segment))
          (not (some #(= event-id (:id %)) (accessible-events state viewer-id))))
      (rejected :forbidden "That saved segment is outside your organizing scope.")
      :else {:facts [{:type "crm.segment-removed" :event-id event-id :actor actor :at at
                      :payload {:segment-id segment-id :event-id event-id
                                :owner-id viewer-id :removed-at at}}]})))

(defn- normalize-recipient-ids [recipient-ids]
  (->> recipient-ids (map str) (remove str/blank?) distinct vec))

(defn- resolve-template [template contact]
  (-> (str template)
      (str/replace "{name}" (str (:name contact)))
      (str/replace "{email}" (str (:email contact)))
      (str/replace "{organization}" (str (or (:organization contact) "")))))

(defn prepare-outreach
  [state {:keys [viewer-id event-id recipient-ids subject body]}]
  (let [contacts* (contacts state viewer-id)
        contacts-by-id (into {} (map (juxt :person-id identity)) contacts*)
        recipient-ids (normalize-recipient-ids recipient-ids)
        recipients (mapv contacts-by-id recipient-ids)]
    (cond
      (not (some #(= event-id (:id %)) (accessible-events state viewer-id)))
      (rejected :forbidden "That event is outside your organizing scope.")
      (empty? recipient-ids) (rejected :recipients-required "Select at least one contact.")
      (some nil? recipients) (rejected :forbidden "One or more contacts are outside your organizing scope.")
      (str/blank? (str subject)) (rejected :subject-required "Write a subject first.")
      (> (count (str subject)) max-outreach-subject-length)
      (rejected :subject-too-long "The outreach subject is too long.")
      (str/blank? (str body)) (rejected :body-required "Write the message first.")
      (> (count (str body)) max-outreach-body-length)
      (rejected :body-too-long "The outreach message is too long.")
      :else {:event-id event-id
             :recipient-ids recipient-ids
             :subject (str subject)
             :body (str body)
             :preview (mapv (fn [contact]
                              {:person-id (:person-id contact)
                               :name (:name contact)
                               :email (:email contact)
                               :subject (resolve-template subject contact)
                               :body (resolve-template body contact)})
                            recipients)})))

(defn decide-record-outreach
  [state {:keys [viewer-id draft-id actor at] :as command}]
  (let [prepared (prepare-outreach state command)]
    (if-let [rejection (:rejected prepared)]
      {:rejected rejection}
      {:preview (:preview prepared)
       :facts [{:type "crm.outreach-drafted" :event-id (:event-id prepared)
                :actor actor :at at
                :payload {:id draft-id :event-id (:event-id prepared)
                          :owner-id viewer-id :recipient-ids (:recipient-ids prepared)
                          :subject (:subject prepared) :body (:body prepared)
                          :actor actor :created-at at}}]})))
