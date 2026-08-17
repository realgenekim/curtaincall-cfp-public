(ns cfp-scheduler-killer.people
  "Person identity — email-keyed, event-independent, shared by every slice.

   The same human is a committee member on one event and a speaker on another;
   they get ONE record, found by lowercased email. Extracted here so the
   committee slice and the submission slice cannot drift into two different
   notions of who someone is.

   People are created by appending a `person.created` event; the callers own
   that append because a person is almost always created as part of a larger
   batch (a membership, a submission)."
  (:require
   [cfp-scheduler-killer.events :as events]
   [cfp-scheduler-killer.store :as store]
   [clojure.string :as str]
   [com.fulcrologic.guardrails.core :refer [=> >defn ?]]
   [taoensso.timbre :as log]))

(def email-pattern #"^[^@\s]+@[^@\s]+\.[^@\s]+$")

(defn normalize-email
  "Lowercase + trim. Email is an IDENTITY key; it must not be defeated by
   capitalisation."
  [email]
  (some-> email str/trim str/lower-case not-empty))

(defn by-email
  "The person with this email, or nil."
  [email]
  (store/person-by-email (normalize-email email)))

(defn by-id [person-id] (store/person-by-id person-id))

(>defn set-default-event!
       "Persist the one event this person wants URL-less organizer pages to open.
   Repeating the current choice is a no-op; moving the star appends a new fact."
       [person-id event-id actor]
       [string? string? string? => map?]
       (let [person (store/person-by-id person-id)]
         (when-not person
           (throw (ex-info "Cannot set a default event for an unknown person."
                           {:type :unknown-person :person-id person-id})))
         (when-not (events/event-by-id event-id)
           (throw (ex-info "Cannot set an unknown event as the default."
                           {:type :unknown-event :event-id event-id})))
         (when-not (= event-id (:default-event-id person))
           (store/append! {:type "person.default-event-set"
                           :actor actor
                           :payload {:person-id person-id
                                     :event-id event-id
                                     :at (store/now-iso)}}))
         (store/person-by-id person-id)))

(defn new-person
  "A person RECORD ready to be appended as a `person.created` payload.
   Pure — it does not write."
  [email name*]
  {:id (store/new-id)
   :email (normalize-email email)
   :name name*
   :profile {}
   :created-at (store/now-iso)})

(defn find-or-new
  "Returns [person created?]. An EXISTING person is returned untouched — we
   never rename someone because a third party typed their name differently."
  [email name*]
  (if-let [found (by-email email)]
    [found false]
    [(new-person email name*) true]))

;; --- Slugs: the person's public permalink -----------------------------------

(def ^:private max-slug-attempts
  "How far the -2, -3, … walk goes before we give up and keep the UUID URL.
   Deep enough for every real collision; bounded so a corrupt store cannot spin."
  200)

(defn- free-slug
  "The first of `base`, `base-2`, `base-3`, … that no OTHER person holds."
  [base person-id]
  (first
    (for [n (range 1 (inc max-slug-attempts))
          :let [candidate (if (= 1 n) base (str base "-" n))
                holder (store/person-by-slug candidate)]
          :when (or (nil? holder) (= person-id (:id holder)))]
      candidate)))

(>defn ensure-slug!
       "Mint the person's permanent URL slug from their name, once. Returns the
   slug. A person who already has one keeps it forever — renames never
   re-derive (slugs are permalinks, and the old one is already on somebody's
   LinkedIn post). Collisions get -2, -3, … (a GLOBAL namespace: people are
   cross-event, so one human owns one address everywhere).

   Returns nil, appending nothing, when there is no name to slugify or every
   candidate is taken — the UUID URL keeps working, which is the whole point of
   the UUID staying canonical."
       [person-id actor]
       [string? string? => (? string?)]
       (let [person (store/person-by-id person-id)]
         (or (:slug person)
             (when-let [base (not-empty (events/slugify (:name person)))]
               (when-let [slug (free-slug base person-id)]
                 (store/append! {:type "person.slug-set"
                                 :actor actor
                                 :payload {:person-id person-id
                                           :slug slug
                                           :at (store/now-iso)}})
                 (log/info :person-slug-minted :person-id person-id :slug slug)
                 slug)))))
