(ns cfp-scheduler-killer.telemetry
  "Privacy-bounded request and browser telemetry.

   Request threads only build a small map and offer it to a bounded queue. A
   daemon writes batches to the separate telemetry_events append-only table.
   Telemetry failure is observable, but it can never fail a product request."
  (:require
   [cfp-scheduler-killer.auth :as auth]
   [cfp-scheduler-killer.db :as db]
   [cfp-scheduler-killer.store :as store]
   [clojure.data.json :as json]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [next.jdbc :as jdbc]
   [taoensso.timbre :as log])
  (:import
   (java.net URI)
   (java.security MessageDigest)
   (java.time Instant)
   (java.util ArrayList)
   (java.util.concurrent ArrayBlockingQueue)))

(def table-name "telemetry_events")
(def default-capacity 10000)
(def default-batch-size 500)
(def default-flush-interval-ms 2000)
(def default-shutdown-timeout-ms 750)
(def max-beacon-bytes 4096)

(defonce ^:private runtime (atom nil))
(defonce ^:private metrics
  (atom {:enqueued 0 :dropped 0 :written 0 :batches 0 :write-failures 0}))

(defn reset-metrics! []
  (reset! metrics {:enqueued 0 :dropped 0 :written 0 :batches 0 :write-failures 0}))

(defn metrics-snapshot [] @metrics)

(defn- hash-id [value]
  (when (seq (str value))
    (let [digest (.digest (MessageDigest/getInstance "SHA-256")
                          (.getBytes (str "cfp-telemetry-v1:" value) "UTF-8"))]
      (->> digest
           (map #(format "%02x" (bit-and 0xff %)))
           (apply str)
           (take 24)
           (apply str)))))

(defn- instance-id []
  (or (not-empty (System/getenv "K_REVISION"))
      (not-empty (System/getenv "HOSTNAME"))
      "local"))

(defn ua-class [user-agent]
  (let [ua (str/lower-case (str user-agent))]
    (cond
      (re-find #"bot|spider|crawler|slurp|preview" ua) "bot"
      (re-find #"mozilla|chrome|safari|firefox|edge" ua) "browser"
      (re-find #"curl|wget|python|java|hato|httpclient" ua) "http-client"
      (str/blank? ua) "unknown"
      :else "other")))

(defn- safe-label [value]
  (let [s (some-> value str str/trim)]
    (when (and (seq s)
               (<= (count s) 80)
               (re-matches #"[\p{L}\p{N} _./:&-]+" s))
      s)))

(defn- safe-token [value]
  (let [s (some-> value str str/trim)]
    (when (and (seq s)
               (<= (count s) 64)
               (re-matches #"[A-Za-z0-9_.:/-]+" s))
      s)))

(defn- safe-host [value]
  (let [s (some-> value str str/lower-case str/trim)]
    (when (and (seq s)
               (<= (count s) 253)
               (re-matches #"[a-z0-9.-]+" s))
      s)))

(defn- safe-long [value low high]
  (try
    (let [n (Long/parseLong (str value))]
      (when (<= low n high) n))
    (catch Exception _ nil)))

(defn behavioral-params
  "The query behavior worth studying, without storing search text or arbitrary
   attacker-controlled parameters."
  [params]
  (let [value #(or (get params %) (get params (name %)))
        q (some-> (value :q) str)]
    (cond-> {}
      (safe-label (value :sort)) (assoc :sort (safe-label (value :sort)))
      (safe-label (value :status)) (assoc :status (safe-label (value :status)))
      (safe-label (value :track)) (assoc :track (safe-label (value :track)))
      (safe-label (value :filter)) (assoc :filter (safe-label (value :filter)))
      (safe-long (value :at-index) 0 1000000000)
      (assoc :at-index (safe-long (value :at-index) 0 1000000000))
      (seq q) (assoc :q-present true :q-length (min 500 (count q))))))

(defn- path-parts [path]
  (vec (remove str/blank? (str/split (str path) #"/"))))

(defn- event-slug-from-path [path]
  (let [parts (path-parts path)]
    (cond
      (contains? #{"cfp" "agenda" "events"} (first parts)) (second parts)
      (and (= "api" (first parts)) (= "cfp" (second parts))) (nth parts 2 nil)
      (and (= "api" (first parts)) (= "events" (second parts)))
      (let [candidate (nth parts 2 nil)]
        (when-not (contains? #{"create" "demo" "preview" "archive" "draft-pref"} candidate)
          candidate))
      :else nil)))

(defn- normalize-path [path]
  (let [parts (path-parts path)
        uuid? #(boolean (re-matches #"(?i)[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}" %))]
    (str "/"
         (str/join
           "/"
           (map-indexed
             (fn [index part]
               (cond
                 (and (= index 1) (contains? #{"cfp" "agenda" "events"} (first parts))) ":slug"
                 (and (= index 2) (= "api" (first parts))
                      (contains? #{"cfp" "events"} (second parts))) ":slug"
                 (uuid? part) ":id"
                 (or (str/includes? part "@") (> (count part) 80)) ":redacted"
                 :else part))
             parts)))))

(defn- request-route [req]
  (or (get-in req [:reitit.core/match :template])
      (some-> (:uri req) normalize-path)
      "unknown"))

(defn- request-event [req route status duration-ms]
  (let [person (auth/current-person req)
        ua (ua-class (get-in req [:headers "user-agent"]))]
    {:at (store/now-iso)
     :source "server"
     :type "request"
     :route route
     :method (some-> (:request-method req) name str/upper-case)
     :status status
     :duration-ms duration-ms
     :person-id (:id person)
     :session-hash (hash-id (get-in req [:session :session-id]))
     :event-slug (or (get-in req [:path-params :slug])
                     (get-in req [:path-params "slug"])
                     (event-slug-from-path (:uri req)))
     :params (behavioral-params (:params req))
     :actor-class (if person "authenticated" "anonymous")
     :ua-class ua
     :traffic-class (if (= "bot" ua) "bot" (if person "internal" "external"))
     :instance-id (instance-id)}))

(defn insert-statement [row-count]
  (str "INSERT INTO " table-name " (line) VALUES "
       (str/join "," (repeat row-count "(?)"))))

(defn write-batch!
  "One SQL statement for the whole batch. Opens only the existing pool seam;
   it never creates tables or runs migrations."
  [rows]
  (when (seq rows)
    (jdbc/execute! (db/start-pool!)
                   (into [(insert-statement (count rows))]
                         (map json/write-str rows)))))

(defn remaining-count []
  (if-let [{:keys [^ArrayBlockingQueue queue pending]} @runtime]
    (+ (.size queue) (count @pending))
    0))

(defn enqueue!
  "Non-blocking. Returns false when disabled or full; never throws."
  [event]
  (if-let [{:keys [^ArrayBlockingQueue queue]} @runtime]
    (try
      (if (.offer queue event)
        (do (swap! metrics update :enqueued inc) true)
        (do (swap! metrics update :dropped inc)
            (log/warn :telemetry-queue-full :capacity (.remainingCapacity queue))
            false))
      (catch Throwable t
        (swap! metrics update :dropped inc)
        (log/warn :telemetry-enqueue-failed :error (.getMessage t))
        false))
    false))

(defn flush-once!
  "Write one pending batch. A failed batch stays in memory for the next retry."
  []
  (if-let [{:keys [^ArrayBlockingQueue queue pending batch-size write-batch! flush-lock]}
           @runtime]
    (locking flush-lock
      (let [batch (if (seq @pending)
                    @pending
                    (let [items (ArrayList.)]
                      (.drainTo queue items batch-size)
                      (vec items)))]
        (if (empty? batch)
          true
          (do
            (reset! pending batch)
            (try
              (write-batch! batch)
              (reset! pending [])
              (swap! metrics #(-> %
                                  (update :written + (count batch))
                                  (update :batches inc)))
              true
              (catch Throwable t
                (swap! metrics update :write-failures inc)
                (log/warn :telemetry-batch-failed
                          :rows (count batch) :error (.getMessage t))
                false))))))
    true))

(defn- enabled-by-environment? []
  (and (store/postgres?)
       (= "on" (some-> (System/getenv "TELEMETRY_ENABLED") str/trim str/lower-case))))

(defn start!
  "Start the daemon writer. Tests inject a writer and may disable the thread."
  ([] (start! {}))
  ([{:keys [enabled? capacity batch-size flush-interval-ms shutdown-timeout-ms
            write-batch-fn start-thread?]
     :or {capacity default-capacity
          batch-size default-batch-size
          flush-interval-ms default-flush-interval-ms
          shutdown-timeout-ms default-shutdown-timeout-ms
          write-batch-fn write-batch!
          start-thread? true}}]
   (if @runtime
     @runtime
     (let [enabled? (if (nil? enabled?) (enabled-by-environment?) enabled?)]
       (if-not enabled?
         (do (log/info :telemetry-disabled :postgres (store/postgres?)) nil)
         (let [running? (atom true)
               config {:queue (ArrayBlockingQueue. capacity)
                       :pending (atom [])
                       :running? running?
                       :batch-size batch-size
                       :flush-interval-ms flush-interval-ms
                       :shutdown-timeout-ms shutdown-timeout-ms
                       :write-batch! write-batch-fn
                       :flush-lock (Object.)}
               thread (when start-thread?
                        (doto
                          (Thread.
                            ^Runnable
                            (fn []
                              (while @running?
                                (try
                                  (Thread/sleep flush-interval-ms)
                                  (when @running? (flush-once!))
                                  (catch InterruptedException _)
                                  (catch Throwable t
                                    (log/warn :telemetry-flusher-error
                                              :error (.getMessage t)))))))
                          (.setName "telemetry-flusher")
                          (.setDaemon true)))]
           (reset-metrics!)
           (reset! runtime (assoc config :thread thread))
           (when thread (.start ^Thread thread))
           (log/info :telemetry-started :capacity capacity :batch-size batch-size)
           @runtime))))))

(defn stop!
  "Stop accepting rows and spend at most shutdown-timeout-ms flushing."
  []
  (if-let [{:keys [running? ^Thread thread shutdown-timeout-ms]} @runtime]
    (do
      (reset! running? false)
      (when thread
        (.interrupt thread)
        (.join thread (long (min 250 shutdown-timeout-ms))))
      (let [deadline (+ (System/nanoTime) (* 1000000 shutdown-timeout-ms))]
        (loop []
          (when (and (pos? (remaining-count))
                     (< (System/nanoTime) deadline)
                     (flush-once!))
            (recur))))
      (let [result {:flushed? (zero? (remaining-count))
                    :remaining (remaining-count)
                    :metrics @metrics}]
        (when-not (:flushed? result)
          (log/warn :telemetry-shutdown-incomplete :remaining (:remaining result)))
        (reset! runtime nil)
        (log/info :telemetry-stopped :remaining (:remaining result))
        result))
    {:flushed? true :remaining 0 :metrics @metrics}))

(defn wrap-telemetry [handler]
  (fn [req]
    (let [started (System/nanoTime)]
      (try
        (let [response (handler req)
              duration (long (/ (- (System/nanoTime) started) 1000000))
              route (or (::route-template response) (request-route req))
              event (request-event req route (or (:status response) 200) duration)]
          (log/info :req
                    :route (:route event) :method (:method event)
                    :status (:status event) :duration-ms duration
                    :event-slug (:event-slug event) :ua-class (:ua-class event))
          (enqueue! event)
          (dissoc response ::route-template))
        (catch Throwable t
          (let [duration (long (/ (- (System/nanoTime) started) 1000000))
                event (request-event req (request-route req) 500 duration)]
            (log/info :req
                      :route (:route event) :method (:method event)
                      :status 500 :duration-ms duration
                      :event-slug (:event-slug event) :ua-class (:ua-class event))
            (enqueue! event))
          (throw t))))))

(defn wrap-route-template
  "Attach Reitit's matched template to the response for the outer telemetry
   middleware, then let that middleware remove the private key."
  [handler]
  (fn [req]
    (assoc (handler req) ::route-template (request-route req))))

(def ^:private beacon-types
  #{"page_view" "scroll_depth" "time_on_page" "cta_click" "outbound_click" "app_event"})

(defn- bounded-body [body]
  (with-open [reader (io/reader body)]
    (let [buffer (char-array 1024)
          out (StringBuilder.)]
      (loop [total 0]
        (let [remaining (- (inc max-beacon-bytes) total)
              n (if (pos? remaining)
                  (.read reader buffer 0 (min (alength buffer) remaining))
                  -1)]
          (cond
            (= -1 n) (str out)
            (> (+ total n) max-beacon-bytes)
            (throw (ex-info "Beacon is too large." {:status 413 :type :beacon-too-large}))
            :else (do (.append out buffer 0 n)
                      (recur (+ total n)))))))))

(defn- valid-id? [value]
  (boolean (and (string? value) (re-matches #"[A-Za-z0-9_-]{8,80}" value))))

(defn- safe-path [value]
  (try
    (let [path (.getPath (URI. (str value)))]
      (when (and (str/starts-with? path "/") (<= (count path) 300)) path))
    (catch Exception _ nil)))

(defn- safe-client-at [value]
  (try
    (when value (str (Instant/parse (str value))))
    (catch Exception _ nil)))

(defn- clean-beacon-data [type data]
  (let [data (if (map? data) data {})]
    (case type
      "page_view" (cond-> {}
                    (safe-host (:referrer-host data))
                    (assoc :referrer-host (safe-host (:referrer-host data))))
      "scroll_depth" (if-let [pct (safe-long (:pct data) 0 100)] {:pct pct} {})
      "time_on_page" (if-let [seconds (safe-long (:seconds data) 0 86400)]
                       {:seconds seconds} {})
      "cta_click" (if-let [label (safe-token (:label data))] {:label label} {})
      "outbound_click" (if-let [host (safe-host (:host data))] {:host host} {})
      "app_event" (if-let [event-name (safe-token (:name data))] {:name event-name} {})
      {})))

(defn parse-beacon
  "Validate and irreversibly sanitize one browser envelope."
  [req]
  (let [payload (try
                  (json/read-str (bounded-body (:body req)) :key-fn keyword)
                  (catch clojure.lang.ExceptionInfo e (throw e))
                  (catch Exception _
                    (throw (ex-info "Beacon is not valid JSON."
                                    {:status 400 :type :invalid-beacon-json}))))
        {:keys [app type path ts sid vid data]} payload
        path (safe-path path)]
    (when-not (= "sessionize-sched-killer" app)
      (throw (ex-info "Unknown beacon app." {:status 422 :type :invalid-beacon-app})))
    (when-not (contains? beacon-types type)
      (throw (ex-info "Unknown beacon type." {:status 422 :type :invalid-beacon-type})))
    (when-not (and (valid-id? sid) (valid-id? vid) path)
      (throw (ex-info "Invalid beacon identifiers or path."
                      {:status 422 :type :invalid-beacon-envelope})))
    (let [person (auth/current-person req)
          ua (ua-class (get-in req [:headers "user-agent"]))]
      {:at (store/now-iso)
       :client-at (safe-client-at ts)
       :source "beacon"
       :type type
       :path (normalize-path path)
       :person-id (:id person)
       :session-hash (hash-id sid)
       :visitor-hash (hash-id vid)
       :event-slug (event-slug-from-path path)
       :data (clean-beacon-data type data)
       :actor-class (if person "authenticated" "anonymous")
       :ua-class ua
       :traffic-class (if (= "bot" ua) "bot" (if person "internal" "external"))
       :instance-id (instance-id)})))

(defn accept-beacon! [req]
  (try
    (let [event (parse-beacon req)]
      (enqueue! event)
      {:status 204 :headers {} :body ""})
    (catch clojure.lang.ExceptionInfo e
      (let [{:keys [status type]} (ex-data e)]
        (log/warn :telemetry-beacon-refused :type type :status status)
        {:status (or status 422)
         :headers {"Content-Type" "text/plain; charset=utf-8"}
         :body "Telemetry event refused.\n"}))
    (catch Throwable t
      (log/warn :telemetry-beacon-failed :error (.getMessage t))
      {:status 400
       :headers {"Content-Type" "text/plain; charset=utf-8"}
       :body "Telemetry event refused.\n"})))
