(ns cfp-scheduler-killer.tracks
  "The event's canonical track taxonomy.

   Tracks are the options on the public CFP's `track` select field. Keeping
   one representation means form answers, review filters, schedule controls,
   and public facets cannot drift into competing taxonomies."
  (:require
   [cfp-scheduler-killer.forms :as forms]
   [cfp-scheduler-killer.schedule :as schedule]
   [clojure.string :as str]))

(def canonical-field-id "track")

(defn track-field [event]
  (forms/find-field (forms/fields-for-event (:id event)) canonical-field-id))

(defn tracks-for-event [event]
  (vec (:options (track-field event))))

(defn- invalid-track! [message data]
  (throw (ex-info message (assoc data :type :invalid-track))))

(defn- normalized-label [label]
  (some-> label str str/trim not-empty))

(defn- same-label? [a b]
  (= (some-> a str str/lower-case)
     (some-> b str str/lower-case)))

(defn- ensure-unlocked! [event]
  (when (schedule/locked? event)
    (throw (ex-info "The schedule is locked"
                    {:type :schedule-locked
                     :version (schedule/lock-label event)}))))

(defn- save-options! [event options actor]
  (ensure-unlocked! event)
  (if-let [field (track-field event)]
    (forms/update-field! event canonical-field-id
                         (assoc field :options (vec options)) actor)
    (invalid-track! "This event has no canonical track field"
                    {:field-id canonical-field-id})))

(defn add! [event label actor]
  (let [label (normalized-label label)
        options (tracks-for-event event)]
    (when-not label
      (invalid-track! "A track needs a name" {:label label}))
    (when (some #(same-label? % label) options)
      (invalid-track! "That track already exists" {:label label}))
    (save-options! event (conj options label) actor)))

(defn rename! [event old-label new-label actor]
  (let [new-label (normalized-label new-label)
        options (tracks-for-event event)]
    (when-not (some #(= % old-label) options)
      (invalid-track! "That track does not exist" {:label old-label}))
    (when-not new-label
      (invalid-track! "A track needs a name" {:label new-label}))
    (when (some #(and (not= % old-label) (same-label? % new-label)) options)
      (invalid-track! "That track already exists" {:label new-label}))
    (save-options! event
                   (mapv #(if (= % old-label) new-label %) options)
                   actor)))

(defn retire! [event label actor]
  (let [options (tracks-for-event event)]
    (when-not (some #(= % label) options)
      (invalid-track! "That track does not exist" {:label label}))
    (when (= 1 (count options))
      (invalid-track! "An event needs at least one active track" {:label label}))
    ;; Existing submissions keep their historical string answer. Retiring only
    ;; removes the choice from future CFP submissions and canonical facets.
    (save-options! event (filterv #(not= % label) options) actor)))
