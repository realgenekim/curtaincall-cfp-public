(ns cfp-scheduler-killer.core
  (:require
   [cfp-scheduler-killer.middleware :as middleware]
   [cfp-scheduler-killer.seed-demo :as seed-demo]
   [cfp-scheduler-killer.server :as server]
   [cfp-scheduler-killer.store :as store]
   [logging.main :as glog]
   [taoensso.timbre :as log])
  (:gen-class))

;; Configure logging — glog sets up console output format;
;; we add a file appender so logs are available even when stdout is pipe-buffered.
(glog/configure-logging! glog/config)
(log/merge-config!
  {:appenders {:spit (taoensso.timbre/spit-appender {:fname "00SERVER-LOGS.txt"})}})

(defn greet
  "A simple greeting function."
  [name]
  (str "Hello, " name "!"))

(defn -main
  "Main entry point for the application."
  [& args]
  (when (some #{"--help" "-h"} args)
    (log/info :help-requested :args args)
    (log/info :usage :msg "clojure -M -m cfp-scheduler-killer.core [options]")
    (log/info :options :msg "--help, -h    Show this help message")
    (System/exit 0))
  (log/info :greeting :msg (greet "World"))
  ;; STORE_BACKEND=postgres: open the pool and ensure the one table BEFORE
  ;; anything folds the log. store-pg would start lazily on first use anyway;
  ;; doing it here makes a bad connection string fail at BOOT with one clear
  ;; error, instead of at the first request with a stack trace inside a render.
  ;; Loaded via requiring-resolve so the default JSONL path never touches JDBC.
  (when (store/postgres?)
    (log/info :store-backend :backend :postgres)
    ((requiring-resolve 'cfp-scheduler-killer.store-pg/start!)))
  ;; Demo instances seed themselves: on a fresh filesystem (first boot, or a
  ;; Cloud Run instance recycle while GCS persistence is deferred) the judge
  ;; lands in the Charlotte world, never an empty screen. seed! is idempotent —
  ;; an existing store is left alone, minus a submissions top-up.
  (when (middleware/demo-mode?)
    (log/info :demo-boot-seed :msg "DEMO_MODE — ensuring the demo world exists")
    (seed-demo/seed!))
  (log/info :startup :msg "Application started successfully") (server/start-server!) @(promise))

(comment
  ;; REPL experiments
  (greet "World")
  ; => "Hello, World!"

  (greet "Clojure")
  ; => "Hello, Clojure!"

  ;; Test main
  (-main)
  (-main "--help"))
