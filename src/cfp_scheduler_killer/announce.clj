(ns cfp-scheduler-killer.announce
  "Organizer-side speaker announcement workflow.

   The public roster remains cfp-scheduler-killer.public-catalog/public-speakers;
   these verbs only append identity and roster facts that make that projection
   light up."
  (:require
   [cfp-scheduler-killer.people :as people]
   [cfp-scheduler-killer.portal :as portal]
   [cfp-scheduler-killer.public-catalog :as public-catalog]
   [cfp-scheduler-killer.store :as store]
   [clojure.string :as str]
   [com.fulcrologic.guardrails.core :refer [=> >defn ?]]
   [taoensso.timbre :as log]))

(defn- not-blank [s]
  (when-not (str/blank? s) s))

(defn speaker-form-values
  "The marquee speaker form, read into program vocabulary. Lives here, not in a
   handler, because BOTH handlers that can receive an inline program edit read
   the same shape (see program-edit?)."
  [params]
  {:name (str/trim (str (or (:name params) "")))
   :email (people/normalize-email (:email params))
   :org (not-blank (:org params))
   :title (not-blank (:title params))
   :headshot-url (not-blank (:headshot-url params))
   :website-url (not-blank (:website-url params))
   :bio (not-blank (:bio params))
   :announce? (some? (:announce params))})

(defn speaker-form-errors
  ([values] (speaker-form-errors values false))
  ([{:keys [name email headshot-url website-url]} require-email?]
   (not-empty
     (merge
       (when (str/blank? name) {:name ["Name is required."]})
       (when (and require-email?
                  (not (re-matches people/email-pattern (str email))))
         {:email ["A valid email address is required — it is how this speaker signs in."]})
       (portal/profile-errors {:headshot-url headshot-url
                               :website-url website-url})))))

(defn program-edit?
  "Is this POST the Announce panel's PROGRAM edit rather than the roster's
   event-local edit?

   Both forms post to POST /api/events/:slug/speakers/:person-id. The router
   used to tell them apart by asking whether the person was on the speaker
   roster — true only for CFP speakers back when a marquee speaker was on no
   roster at all. Now that every created speaker is a roster member (SPK-01),
   the FORM SHAPE is the honest discriminator: the marquee posts :org and the
   publish toggle, the roster posts :organization/:location/:notes."
  [params]
  (or (contains? params :org) (contains? params :announce)))

(defn- announced-entry [event-id speaker-name]
  (let [needle (public-catalog/norm-name speaker-name)]
    (some #(when (= needle (public-catalog/norm-name (:name %))) %)
          (get-in (store/get-event-by-id event-id)
                  [:settings :announced-speakers]))))

(defn- profile-params [{:keys [org title headshot-url website-url bio]}]
  {:org org
   :tagline title
   :headshot-url headshot-url
   :website-url website-url
   :bio bio})

(defn- mint-person!
  [name email details actor]
  (let [profile (profile-params details)]
    (when-let [errors (portal/profile-errors profile)]
      (throw (ex-info "Invalid speaker profile"
                      {:type :invalid-speaker-profile :errors errors})))
    (let [[person created?] (people/find-or-new email name)]
      (when created?
        (store/append! {:type "person.created"
                        :actor actor
                        :payload person}))
      (people/ensure-slug! (:id person) actor)
      (portal/update-profile! (:id person) profile actor)
      (store/person-by-id (:id person)))))

(defn- record-participation!
  "Put a newly minted marquee speaker on the CANONICAL event roster.

   domain.speakers/roster is built from :speaker-participations (plus CFP
   submissions), so a person who only ever received a program entry is a
   person the organizer's roster, its search, and its status filter cannot
   see — SPK-01. An organizer who creates or adopts a speaker on this event
   has INVITED them, which is exactly the fact the roster's own add form
   appends. One person, one event roster, however they arrived."
  [event-id person-id actor]
  (store/append! {:type "speaker.added-to-event"
                  :actor actor
                  :event-id event-id
                  :payload {:event-id event-id
                            :person-id person-id
                            :status "Invited"
                            :added-at (store/now-iso)}}))

(>defn adopt-announced-speaker!
       "Give a legacy announced-wall entry a person identity. Returns the person,
   or nil when no normalized-name match exists. Repeated adoption is a no-op."
       [event-id speaker-name actor]
       [string? string? string? => (? map?)]
       (if-let [entry (announced-entry event-id speaker-name)]
         (if-let [person-id (:person-id entry)]
           (store/person-by-id person-id)
           (let [person (mint-person! (:name entry) nil entry actor)]
             (record-participation! event-id (:id person) actor)
             (store/append! {:type "event.announced-speaker-adopted"
                             :actor actor
                             :event-id event-id
                             :payload {:event-id event-id
                                       :name (:name entry)
                                       :person-id (:id person)
                                       :at (store/now-iso)}})
             (log/info :announced-speaker-adopted
                       :event-id event-id :person-id (:id person))
             person))
         (do
           (log/warn :announced-speaker-adopt-missed
                     :event-id event-id :speaker-name speaker-name)
           nil)))

(>defn create-announced-speaker!
       "Create an invited speaker identity and its event-scoped program entry.
   Publication is explicit: an unchecked speaker remains manageable by the
   organizer but does not enter any public program projection."
       [event-id {:keys [name email org title headshot-url bio announce?] :as details} actor]
       [string? map? string? => map?]
       (let [email (people/normalize-email email)]
         (when-not (re-matches people/email-pattern (str email))
           (throw (ex-info "A valid speaker email address is required."
                           {:type :invalid-speaker-email :email email})))
         (let [person (mint-person! name email details actor)]
           (record-participation! event-id (:id person) actor)
           (store/append! {:type "event.announced-speaker-added"
                           :actor actor
                           :event-id event-id
                           :payload {:event-id event-id
                                     :entry {:name name
                                             :org org
                                             :title title
                                             :headshot-url headshot-url
                                             :person-id (:id person)
                                             :published? (boolean announce?)}
                                     :at (store/now-iso)}})
           (log/info :announced-speaker-created
                     :event-id event-id :person-id (:id person)
                     :announced? (boolean announce?))
           (store/person-by-id (:id person)))))

(>defn update-program-speaker!
       "Persist an organizer's inline edit to a speaker on this event's public
   program. The optional announced roster entry and durable person profile move
   through one event-sourced fact."
       [event-id person-id {:keys [name org title headshot-url bio announce?] :as details} actor]
       [string? string? map? string? => map?]
       (let [event (store/get-event-by-id event-id)
             speaker (public-catalog/program-speaker-by-id event person-id)
             person (store/person-by-id person-id)
             profile (profile-params details)]
         (cond
           (or (nil? event) (nil? speaker) (nil? person))
           {:ok false :not-found? true}

           :else
           (do
             (store/append!
               {:type "event.program-speaker-updated"
                :actor actor
                :event-id event-id
                :payload {:event-id event-id
                          :person-id person-id
                          :entry (cond-> {:name name
                                          :org org
                                          :title title
                                          :headshot-url headshot-url}
                                   (:manual? speaker)
                                   (assoc :published? (boolean announce?)))
                          :profile profile
                          :at (store/now-iso)}})
             (log/info :program-speaker-updated
                       :event-id event-id :person-id person-id)
             {:ok true :person (store/person-by-id person-id)}))))

(>defn adopt-all!
       "Adopt every still-dark announced-wall entry. Returns the minted people."
       [event-id actor]
       [string? string? => vector?]
       (->> (get-in (store/get-event-by-id event-id)
                    [:settings :announced-speakers])
            (remove :person-id)
            (keep #(adopt-announced-speaker! event-id (:name %) actor))
            vec))

(>defn ready-to-announce
       "Accepted submissions whose decision has not yet been communicated."
       [event-id]
       [string? => vector?]
       (->> (store/submissions-for-event event-id)
            (filter #(and (= "Accepted" (:status %))
                          (nil? (:notified-at %))))
            (sort-by (juxt #(get-in % [:speakers 0 :name])
                           #(get-in % [:answers :talk-title])
                           :id))
            vec))
