(ns cfp-scheduler-killer.speaker-custom-fields
  "Event-store adapter for custom speaker field decisions."
  (:require
   [cfp-scheduler-killer.domain.speaker-custom-fields :as domain]
   [cfp-scheduler-killer.store :as store]
   [com.fulcrologic.guardrails.core :refer [=> >defn]]
   [taoensso.timbre :as log]))

(def field-types domain/field-types)

(defn fields-for-event
  [event-id]
  (domain/fields-for-event (store/snapshot) event-id))

(defn values-for
  [event-id person-id]
  (domain/values-for (store/snapshot) event-id person-id))

(defn parse-values
  "Read only configured custom field params; unknown request keys never enter a command."
  [fields params]
  (into {}
        (map (fn [{:keys [id]}]
               [id (or (get params (keyword (str "custom-" id)))
                       (get params (str "custom-" id)))]))
        fields))

(defn- append-decision!
  [decision log-key context]
  (if (:rejected decision)
    decision
    (do
      (when (seq (:facts decision))
        (store/append-all! (:facts decision))
        (log/info log-key context))
      decision)))

(>defn define!
       [event-id attrs actor]
       [string? map? string? => map?]
       (append-decision!
         (domain/decide-define
           (store/snapshot)
           (merge attrs {:event-id event-id :actor actor :at (store/now-iso)}))
         :speaker-custom-field-defined
         {:event-id event-id :label (:label attrs)}))

(>defn update-values!
       [event-id person-id values actor]
       [string? string? map? string? => map?]
       (append-decision!
         (domain/decide-update-values
           (store/snapshot)
           {:event-id event-id :person-id person-id :values values
            :actor actor :at (store/now-iso)})
         :speaker-custom-values-updated
         {:event-id event-id :person-id person-id :fields (vec (keys values))}))
