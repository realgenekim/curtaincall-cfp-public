(ns cfp-scheduler-killer.domain.speaker-custom-fields
  "Pure decisions for organizer-defined, event-scoped speaker fields."
  (:require
   [cfp-scheduler-killer.domain.speakers :as speakers]
   [clojure.set :as set]
   [clojure.string :as str]))

(def field-types #{"text" "textarea"})

(defn field-id
  "Stable human-readable id minted from a label."
  [label]
  (let [id (-> (str label)
               str/lower-case
               (str/replace #"[^a-z0-9]+" "-")
               (str/replace #"(^-|-$)" ""))]
    (when-not (str/blank? id) id)))

(defn fields-for-event
  [state event-id]
  (->> (:speaker-custom-fields state)
       (keep (fn [[[eid _field-id] field]]
               (when (= event-id eid) field)))
       (sort-by (juxt :position :id))
       vec))

(defn values-for
  [state event-id person-id]
  (speakers/normalize-custom-values
    (get-in state [:speaker-participations [event-id person-id] :custom-values])))

(defn- event-exists?
  [state event-id]
  (some #(= event-id (:id %)) (vals (:events state))))

(defn- speaker-belongs?
  [state event-id person-id]
  (some #(= person-id (:person-id %)) (speakers/roster state event-id)))

(defn- rejected
  [reason message & [errors]]
  {:rejected (cond-> {:reason reason :message message}
               errors (assoc :errors errors))})

(defn decide-define
  [state {:keys [event-id label type required actor at]}]
  (let [label (some-> label str str/trim not-empty)
        type (or (some-> type str str/trim not-empty) "text")
        id (field-id label)
        fields (fields-for-event state event-id)
        existing (some #(when (= id (:id %)) %) fields)
        field {:id id
               :event-id event-id
               :label label
               :type type
               :required (boolean required)
               :position (count fields)
               :created-at at}]
    (cond
      (not (event-exists? state event-id))
      (rejected :event-not-found "The event does not exist.")

      (nil? label)
      (rejected :label-required "A custom field needs a label.")

      (not (contains? field-types type))
      (rejected :invalid-type "Choose a known custom field type.")

      (str/blank? (str actor))
      (rejected :actor-required "A named organizer must define this field.")

      (= (select-keys existing [:label :type :required])
         (select-keys field [:label :type :required]))
      {:facts [] :unchanged? true :field existing}

      existing
      (rejected :duplicate-field "A custom field with that label already exists.")

      :else
      {:field field
       :facts [{:type "speaker.custom-field-defined"
                :actor actor
                :event-id event-id
                :payload {:event-id event-id :field field}}]})))

(defn decide-update-values
  [state {:keys [event-id person-id values actor at]}]
  (let [fields (fields-for-event state event-id)
        by-id (into {} (map (juxt :id identity)) fields)
        values (into {}
                     (map (fn [[id value]]
                            [(name id) (some-> value str str/trim not-empty)]))
                     values)
        unknown (set/difference (set (keys values)) (set (keys by-id)))
        current (values-for state event-id person-id)
        proposed (merge current values)
        missing (->> fields
                     (keep (fn [{:keys [id label required]}]
                             (when (and required (str/blank? (str (get proposed id))))
                               [id [(str label " is required.")]])))
                     (into {}))
        changes (into {}
                      (remove (fn [[id value]] (= value (get current id))))
                      values)]
    (cond
      (not (event-exists? state event-id))
      (rejected :event-not-found "The event does not exist.")

      (not (speaker-belongs? state event-id person-id))
      (rejected :speaker-not-found "You are not a speaker for this event.")

      (str/blank? (str actor))
      (rejected :actor-required "A named speaker must update these values.")

      (seq unknown)
      (rejected :unknown-field "That custom field does not belong to this event.")

      (seq missing)
      (rejected :required-fields "Complete the required event details." missing)

      (empty? changes)
      {:facts [] :unchanged? true :values current}

      :else
      {:values proposed
       :facts [{:type "speaker.custom-values-updated"
                :actor actor
                :event-id event-id
                :payload {:event-id event-id
                          :person-id person-id
                          :before (select-keys current (keys changes))
                          :changes changes
                          :changed-at at}}]})))
