(ns cfp-scheduler-killer.analysis
  "REPL-friendly telemetry reads and session analysis, descended from
   social-media-writer/writer.analysis."
  (:require
   [cfp-scheduler-killer.db :as db]
   [clojure.data.json :as json]
   [clojure.string :as str]
   [next.jdbc :as jdbc]
   [next.jdbc.result-set :as rs]))

(defn decode-row [{:keys [seq created-at line]}]
  (assoc (json/read-str line :key-fn keyword)
         :seq seq
         :stored-at (some-> created-at str)))

(defn read-events
  "Read the newest telemetry rows through the application pool. Read-only; no
   psql and no schema changes."
  ([] (read-events 10000))
  ([limit]
   (->> (jdbc/execute! (db/start-pool!)
                       ["SELECT seq, created_at, line
                         FROM telemetry_events
                         ORDER BY seq DESC LIMIT ?" limit]
                       {:builder-fn rs/as-unqualified-lower-maps})
        reverse
        (mapv decode-row))))

(defn filter-date [events date-str]
  (filter #(str/starts-with? (or (:at %) "") date-str) events))

(defn event-breakdown [events]
  (->> events
       (map :type)
       frequencies
       (sort-by val >)
       (mapv (fn [[type count]] {:type type :count count}))))

(defn- parse-at [event]
  (try (some-> (:at event) java.time.Instant/parse)
       (catch Exception _ nil)))

(defn session-phases
  "Split one already time-ordered session into phases at idle gaps."
  [events & {:keys [gap-minutes] :or {gap-minutes 30}}]
  (let [gap-seconds (* gap-minutes 60)]
    (loop [remaining events current [] phases []]
      (if-let [event (first remaining)]
        (let [previous-at (some-> current last parse-at)
              current-at (parse-at event)
              gap? (and previous-at current-at
                        (> (- (.getEpochSecond current-at)
                              (.getEpochSecond previous-at))
                           gap-seconds))]
          (if gap?
            (recur (rest remaining) [event] (conj phases current))
            (recur (rest remaining) (conj current event) phases)))
        (cond-> phases (seq current) (conj current))))))

(defn journeys
  "All anonymous/authenticated journeys, keyed only by pseudonymous session."
  [events & {:keys [gap-minutes] :or {gap-minutes 30}}]
  (->> events
       (filter :session-hash)
       (group-by :session-hash)
       (mapcat (fn [[session rows]]
                 (map (fn [phase] {:session-hash session :events phase})
                      (session-phases (sort-by :at rows) :gap-minutes gap-minutes))))
       vec))

(defn journey-summary [{:keys [session-hash events]}]
  {:session-hash session-hash
   :start (:at (first events))
   :end (:at (last events))
   :event-count (count events)
   :types (frequencies (map :type events))
   :routes (frequencies (keep #(or (:route %) (:path %)) events))})

(defn export-jsonl!
  "Export decoded telemetry to a laptop JSONL file for ordinary REPL analysis."
  [path events]
  (spit path (str (str/join "\n" (map json/write-str events)) "\n"))
  path)
