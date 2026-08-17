(ns cfp-scheduler-killer.web.event
  "Event-scoped web context shared by otherwise independent handler slices."
  (:require
   [cfp-scheduler-killer.events :as events]
   [cfp-scheduler-killer.store :as store]
   [cfp-scheduler-killer.views.shell :as view-shell]
   [cfp-scheduler-killer.web.http :as http]))

(defn not-found-page [slug]
  (http/html-response 404 (view-shell/page-shell "Not found"
                                                 [:div.ui.warning.message
                                                  [:div.header "No such event"]
                                                  [:p (str "Nothing is published at /events/" slug ".")]]
                                                 [:a.ui.basic.button {:href "/events"} "All events"])))

(defn with-event [req f]
  (let [slug (get-in req [:path-params :slug])]
    (if-let [event (events/event-by-slug slug)]
      (f event)
      (http/json-response 404 {"error" "no such event" "slug" slug}))))

(defn- event-log-entries [event-id]
  (store/indexed-log-for-event event-id))

(defn time-travel-context
  "Return the selected as-of instant and scrubber bounds for an event page."
  [req event base-path]
  (let [entries (event-log-entries (:id event))
        ats (mapv (comp :at :event) entries)
        total (count entries)
        raw (http/not-blank (get-in req [:params :at-index]))
        idx (when raw (try (Integer/parseInt raw) (catch Exception _ nil)))
        idx (when (and idx (< idx (dec total)) (>= idx 0)) idx)
        selected (when idx (nth entries idx))
        cutoff (get-in selected [:event :at])]
    {:as-of (when selected
              {:log-index (:log-index selected)
               :at cutoff})
     :cutoff cutoff
     :fragment-path (str base-path "/fragment")
     :bounds (when (seq ats) [(first ats) (last ats)])
     :index (or idx (max 0 (dec total)))
     :total total
     :base-path base-path}))

(defmacro with-as-of
  "Render body against an exact log prefix (or legacy timestamp), else now."
  [selection & body]
  `(if-let [selection# ~selection]
     (let [cutoff# (if (map? selection#) (:at selection#) selection#)
           state# (if (map? selection#)
                    (store/state-at-log-index (:log-index selection#))
                    (store/state-as-of selection#))]
       (binding [store/*as-of-state* state#
                 store/*as-of-cutoff* cutoff#]
         ~@body))
     (do ~@body)))
