(ns cfp-scheduler-killer.telemetry-test
  (:require
   [cfp-scheduler-killer.analysis :as analysis]
   [cfp-scheduler-killer.server :as server]
   [cfp-scheduler-killer.telemetry :as telemetry]
   [cfp-scheduler-killer.views.organizer-layout :as organizer-layout]
   [cfp-scheduler-killer.views.shell :as shell]
   [clojure.data.json :as json]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [ring.mock.request :as mock])
  (:import
   (java.io ByteArrayInputStream)))

(use-fixtures :each
  (fn [f]
    (telemetry/stop!)
    (try (f) (finally (telemetry/stop!)))))

(defn- body [value]
  (ByteArrayInputStream. (.getBytes (json/write-str value) "UTF-8")))

(deftest bounded-queue-and-batch-test
  (let [written (atom [])]
    (telemetry/start! {:enabled? true
                       :start-thread? false
                       :capacity 2
                       :batch-size 10
                       :write-batch-fn #(swap! written conj %)})
    (is (telemetry/enqueue! {:type "one"}))
    (is (telemetry/enqueue! {:type "two"}))
    (is (false? (telemetry/enqueue! {:type "overflow"})))
    (is (telemetry/flush-once!))
    (is (= [[{:type "one"} {:type "two"}]] @written))
    (is (= {:enqueued 2 :dropped 1 :written 2 :batches 1 :write-failures 0}
           (telemetry/metrics-snapshot)))
    (is (= "INSERT INTO telemetry_events (line) VALUES (?),(?),(?)"
           (telemetry/insert-statement 3)))))

(deftest failed-batch-is-retried-and-shutdown-is-bounded-test
  (let [fail? (atom true)
        written (atom [])]
    (telemetry/start! {:enabled? true
                       :start-thread? false
                       :shutdown-timeout-ms 50
                       :write-batch-fn (fn [rows]
                                         (if (compare-and-set! fail? true false)
                                           (throw (ex-info "not now" {}))
                                           (swap! written into rows)))})
    (telemetry/enqueue! {:type "kept"})
    (is (false? (telemetry/flush-once!)))
    (is (= 1 (telemetry/remaining-count)) "the failed row remains pending")
    (let [result (telemetry/stop!)]
      (is (:flushed? result))
      (is (= [{:type "kept"}] @written))
      (is (= 1 (get-in result [:metrics :write-failures]))))))

(deftest request-middleware-sanitizes-before-enqueue-test
  (let [written (atom [])
        raw-session "raw-session-secret-12345"
        handler (telemetry/wrap-telemetry (fn [_] {:status 201 :body "ok"}))]
    (telemetry/start! {:enabled? true :start-thread? false
                       :write-batch-fn #(reset! written %)})
    (is (= 201
           (:status
             (handler {:request-method :get
                       :uri "/events/secret-event/board"
                       :reitit.core/match {:template "/events/:slug/board"}
                       :path-params {:slug "secret-event"}
                       :params {:sort "score" :q "alice@example.com private words"
                                :at-index "130" :body "never"}
                       :session {:session-id raw-session}
                       :headers {"user-agent" "Mozilla/5.0"}}))))
    (telemetry/flush-once!)
    (let [event (first @written)
          encoded (pr-str event)]
      (is (= "/events/:slug/board" (:route event)))
      (is (= "secret-event" (:event-slug event)))
      (is (= {:sort "score" :at-index 130 :q-present true :q-length 31}
             (:params event)))
      (is (= "browser" (:ua-class event)))
      (is (string? (:session-hash event)))
      (is (not (str/includes? encoded raw-session)))
      (is (not (str/includes? encoded "alice@example.com")))
      (is (not (str/includes? encoded "never"))))))

(deftest beacon-validation-and-privacy-test
  (let [written (atom [])
        payload {:app "sessionize-sched-killer"
                 :type "cta_click"
                 :path "/cfp/demo?q=private"
                 :ts "2026-08-10T12:00:00Z"
                 :sid "session_12345678"
                 :vid "visitor_12345678"
                 :data {:label "submit-talk"
                        :text "alice@example.com"
                        :answer "my private proposal"}}
        request {:body (body payload) :headers {"user-agent" "Mozilla/5.0"}}]
    (telemetry/start! {:enabled? true :start-thread? false
                       :write-batch-fn #(reset! written %)})
    (is (= 204 (:status (telemetry/accept-beacon! request))))
    (telemetry/flush-once!)
    (let [event (first @written)
          encoded (pr-str event)]
      (is (= "beacon" (:source event)))
      (is (= "/cfp/:slug" (:path event)) "query text and raw slugs are discarded")
      (is (= "demo" (:event-slug event)))
      (is (= {:label "submit-talk"} (:data event)))
      (is (not (str/includes? encoded "session_12345678")))
      (is (not (str/includes? encoded "visitor_12345678")))
      (is (not (str/includes? encoded "alice@example.com")))
      (is (not (str/includes? encoded "private proposal"))))

    (testing "unknown types and oversized bodies are rejected without enqueueing"
      (is (= 422
             (:status (telemetry/accept-beacon!
                        {:body (body (assoc payload :type "form_body"))}))))
      (is (= 413
             (:status (telemetry/accept-beacon!
                        {:body (ByteArrayInputStream.
                                 (.getBytes (apply str (repeat 5000 "x")) "UTF-8"))})))))))

(deftest beacon-route-is-public-and-site-wide-test
  (let [written (atom [])
        _ (telemetry/start! {:enabled? true :start-thread? false
                             :write-batch-fn #(reset! written %)})
        handler (server/create-app)
        payload {:app "sessionize-sched-killer" :type "page_view"
                 :path "/cfp/demo" :ts "2026-08-10T12:00:00Z"
                 :sid "session_12345678" :vid "visitor_12345678" :data {}}
        response (handler (-> (mock/request :post "/api/telemetry/beacon")
                              (mock/body (json/write-str payload))
                              (mock/content-type "text/plain")))
        generic (shell/page-shell "Public" [:p "hello"])
        organizer (organizer-layout/organizer-shell
                    "Organizer" {:active :events :person nil} [:p "hello"])
        javascript (slurp "resources/public/js/telemetry-beacon.js")]
    (telemetry/flush-once!)
    (is (= 204 (:status response)) "anonymous browsers can send the narrow beacon")
    (is (some #(and (= "request" (:type %))
                    (= "/api/telemetry/beacon" (:route %)))
              @written)
        "the global request lane receives the matched route template")
    (is (str/includes? generic "/js/telemetry-beacon.js"))
    (is (str/includes? organizer "/js/telemetry-beacon.js"))
    (is (str/includes? javascript "navigator.sendBeacon"))
    (is (str/includes? javascript "keepalive: true"))
    (is (str/includes? javascript "globalPrivacyControl"))
    (is (str/includes? javascript "data-telemetry-opt-out"))
    (is (str/includes? javascript "window.siteTrack"))
    (is (not (str/includes? javascript "textContent")) "click text is never collected")))

(deftest analysis-lineage-test
  (let [events [{:at "2026-08-10T10:00:00Z" :type "request" :route "/cfp/:slug"
                 :session-hash "abc"}
                {:at "2026-08-10T10:10:00Z" :type "scroll_depth" :path "/cfp/demo"
                 :session-hash "abc"}
                {:at "2026-08-10T11:00:00Z" :type "request" :route "/portal"
                 :session-hash "abc"}]
        journeys (analysis/journeys events)]
    (is (= 2 (count journeys)) "a 30-minute gap starts a new phase")
    (is (= [{:type "request" :count 2} {:type "scroll_depth" :count 1}]
           (analysis/event-breakdown events)))
    (is (= 3 (count (analysis/filter-date events "2026-08-10"))))
    (is (= {"request" 1 "scroll_depth" 1}
           (:types (analysis/journey-summary (first journeys)))))
    (testing "the REPL export path produces the familiar JSONL stream"
      (let [file (java.io.File/createTempFile "telemetry-export-" ".jsonl")]
        (try
          (analysis/export-jsonl! (.getAbsolutePath file) events)
          (is (= events
                 (mapv #(json/read-str % :key-fn keyword)
                       (str/split-lines (slurp file)))))
          (finally (.delete file)))))))
