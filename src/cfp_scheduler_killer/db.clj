(ns cfp-scheduler-killer.db
  "PostgreSQL connection pool, JSONB plumbing, and the tiny migration runner.

   == CONNECTION MODES (env-detected, see `resolve-config`) ==

   1. LOCAL DEV (default on this laptop) — plain local Postgres, no password:
        jdbc:postgresql://localhost:5432/cfp_dev
      Override with DATABASE_URL, or secrets/db.edn {:jdbc-url \"...\"}.

   2. CLOUD SQL IAM (later, on Cloud Run) — set CLOUD_SQL_INSTANCE (and
      optionally DB_IAM_USER / DB_NAME). Uses the Cloud SQL socket factory with
      IAM auth: no passwords, no proxy. Requires the socket-factory dep:
        com.google.cloud.sql/postgres-socket-factory {:mvn/version \"1.15.2\"}
      and ADC locally (`gcloud auth application-default login`).

   == USE ==
     (require '[cfp-scheduler-killer.db :as db])
     (db/start!)      ; pool + migrations
     (db/q [\"SELECT 1 AS test\"])"
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.data.json :as json]
            [next.jdbc :as jdbc]
            [next.jdbc.prepare :as prepare]
            [next.jdbc.result-set :as rs]
            [honey.sql :as sql]
            [taoensso.timbre :as log])
  (:import (com.zaxxer.hikari HikariConfig HikariDataSource)
           (java.sql PreparedStatement)
           (org.postgresql.util PGobject)))

;; --- JSONB <-> Clojure data -------------------------------------------------
;;
;; Store data in its most original form; transform on the way OUT. We hand
;; Postgres a PGobject of type jsonb on write, and parse it back to Clojure
;; data (keyword keys) on read.

(defn ->jsonb
  "Wrap a Clojure value as a Postgres `jsonb` parameter."
  ^PGobject [v]
  (doto (PGobject.)
    (.setType "jsonb")
    (.setValue (json/write-str v))))

(defn <-jsonb
  "Parse a PGobject (json/jsonb) into Clojure data with keyword keys.
   Non-PGobject values pass through untouched."
  [v]
  (if (instance? PGobject v)
    (let [t (.getType ^PGobject v)]
      (if (#{"json" "jsonb"} t)
        (some-> (.getValue ^PGobject v) (json/read-str :key-fn keyword))
        v))
    v))

;; Reading: turn jsonb columns into Clojure data automatically.
(extend-protocol rs/ReadableColumn
  PGobject
  (read-column-by-label [v _] (<-jsonb v))
  (read-column-by-index [v _ _] (<-jsonb v)))

;; Writing: let raw Clojure maps/vectors be passed as jsonb params.
(extend-protocol prepare/SettableParameter
  clojure.lang.IPersistentMap
  (set-parameter [m ^PreparedStatement s i] (.setObject s i (->jsonb m)))
  clojure.lang.IPersistentVector
  (set-parameter [v ^PreparedStatement s i] (.setObject s i (->jsonb v))))

;; --- Connection pool --------------------------------------------------------

(defonce ^:private ds-atom (atom nil))

(def default-local-jdbc-url
  "Local dev database (brew Postgres, current OS user, no password)."
  "jdbc:postgresql://localhost:5432/cfp_dev")

(defn resolve-config
  "Decide how to connect, from the environment.

   Returns either {:mode :cloud-sql-iam :database .. :iam-user .. :instance ..}
   or {:mode :jdbc-url :jdbc-url \"jdbc:postgresql://...\"}.

   Cloud SQL IAM wins when CLOUD_SQL_INSTANCE is set; otherwise we use
   DATABASE_URL > secrets/db.edn > the local default."
  []
  (if-let [instance (System/getenv "CLOUD_SQL_INSTANCE")]
    {:mode     :cloud-sql-iam
     :instance instance
     :database (or (System/getenv "DB_NAME") "cfp_scheduler_killer")
     :iam-user (or (System/getenv "DB_IAM_USER") "genek@itrevolution.net")}
    {:mode     :jdbc-url
     :jdbc-url (or (System/getenv "DATABASE_URL")
                   (try (:jdbc-url (read-string (slurp "secrets/db.edn")))
                        (catch Exception _ nil))
                   default-local-jdbc-url)}))

(defn- make-iam-pool
  "HikariCP pool via the Cloud SQL socket factory with IAM auth (no password).
   Local dev uses ADC; Cloud Run uses workload identity."
  ^HikariDataSource [{:keys [database iam-user instance]}]
  (HikariDataSource.
   (doto (HikariConfig.)
     (.setJdbcUrl (str "jdbc:postgresql:///" database))
     (.setUsername iam-user)
     (.setPassword "ignored")            ; must be non-empty (driver quirk)
     (.setMaximumPoolSize 10)
     (.setMinimumIdle 1)
     (.setConnectionTimeout 60000)
     (.setIdleTimeout 300000)
     (.setKeepaliveTime 60000)
     (.setConnectionTestQuery "SELECT 1")
     (.addDataSourceProperty "socketFactory"
                             "com.google.cloud.sql.postgres.SocketFactory")
     (.addDataSourceProperty "cloudSqlInstance" instance)
     (.addDataSourceProperty "enableIamAuth" "true")
     (.addDataSourceProperty "cloudSqlRefreshStrategy" "lazy")
     (.setPoolName "pg-pool"))))

(defn- make-url-pool
  "HikariCP pool from a plain JDBC URL. Local dev needs no password at all;
   if one is required, put it in the URL or DATABASE_URL."
  ^HikariDataSource [jdbc-url]
  (HikariDataSource.
   (doto (HikariConfig.)
     (.setJdbcUrl jdbc-url)
     (.setMaximumPoolSize 10)
     (.setMinimumIdle 1)
     (.setConnectionTimeout 30000)
     (.setIdleTimeout 300000)
     (.setMaxLifetime 600000)
     (.setConnectionTestQuery "SELECT 1")
     (.setPoolName "pg-pool"))))

(defn- make-pool
  [{:keys [mode jdbc-url] :as config}]
  (case mode
    :cloud-sql-iam (make-iam-pool config)
    :jdbc-url      (make-url-pool jdbc-url)))

;; --- Migrations -------------------------------------------------------------
;;
;; Simplest possible: numbered .sql files on the classpath, every statement
;; written to be idempotent (CREATE ... IF NOT EXISTS). We just run them all,
;; in order, at startup. No version table, no framework.

(def migration-files
  "Ordered list of migration resources (classpath paths)."
  ["migrations/001-init.sql"
   "migrations/002-people.sql"
   "migrations/003-event-location.sql"
   "migrations/004-submissions.sql"])

(defn- split-statements
  "Split a .sql file into individual statements.

   Strips whole-line `--` comments FIRST (so a semicolon inside a comment can't
   split a statement), then splits on ';'. Migrations must not contain ';'
   inside string literals or function bodies — keep them simple, that is the
   whole point of having no framework."
  [sql-text]
  (->> (str/split-lines sql-text)
       (remove #(str/starts-with? (str/trim %) "--"))
       (str/join "\n")
       (#(str/split % #";"))
       (map str/trim)
       (remove str/blank?)))

(defn migrate!
  "Apply every migration file idempotently against `datasource`.
   Safe to call on every startup."
  [datasource]
  (doseq [path migration-files]
    (if-let [res (io/resource path)]
      (let [statements (split-statements (slurp res))]
        (doseq [stmt statements]
          (jdbc/execute-one! datasource [stmt]))
        (log/info :migration-applied :file path :statements (count statements)))
      (log/error :migration-missing :file path))))

;; --- Lifecycle --------------------------------------------------------------

(defn start-pool!
  "Open (and verify) the connection pool WITHOUT running `migration-files`.
   Safe to call repeatedly.

   This is what the event store uses. The 001-004 migrations describe the
   abandoned relational slices; the event store owns exactly one table and
   creates it itself (store-pg/ensure-schema!). Running the old migrations
   against the event-store database would leave four tables nothing reads —
   schema is a claim about what the system believes, so we don't make claims we
   don't mean.

   No-arg: resolves the config from the environment (see `resolve-config`).
   1-arg:  a JDBC URL string, or a config map as returned by `resolve-config`."
  ([] (start-pool! (resolve-config)))
  ([config]
   (if @ds-atom
     @ds-atom
     (let [config (if (string? config) {:mode :jdbc-url :jdbc-url config} config)
           pool   (make-pool config)]
       (jdbc/execute-one! pool ["SELECT 1 AS test"])
       (reset! ds-atom pool)
       (log/info :postgres-pool-started
                 :mode (:mode config)
                 ;; never log credentials: strip password/user params from the URL
                 :target (some-> (or (:jdbc-url config) (:instance config))
                                 (str/replace #"(?i)(password|user)=[^&]*" "$1=REDACTED")))
       pool))))

(defn start!
  "Initialize the connection pool and apply the legacy relational migrations.
   Safe to call repeatedly. See `start-pool!` for the event-store path."
  ([] (start! (resolve-config)))
  ([config]
   (if @ds-atom
     (do (log/info :pool-already-started) @ds-atom)
     (let [pool (start-pool! config)]
       (migrate! pool)
       pool))))

(defn stop!
  "Shut down the connection pool."
  []
  (when-let [ds @ds-atom]
    (.close ^HikariDataSource ds)
    (reset! ds-atom nil)
    (log/info :postgres-pool-stopped)))

(defn ds
  "Get the datasource. Starts the pool (and runs migrations) if needed."
  []
  (or @ds-atom (start!)))

;; --- Query helpers ----------------------------------------------------------

(def ^:private default-opts
  {:builder-fn rs/as-unqualified-kebab-maps})

(defn- ->sql-vec [query]
  (if (map? query) (sql/format query) query))

(defn q
  "Execute a query (a [sql & params] vector or a HoneySQL map).
   Returns a vector of maps with unqualified kebab-case keys."
  [query]
  (jdbc/execute! (ds) (->sql-vec query) default-opts))

(defn q1
  "Execute a query and return the first row (or nil)."
  [query]
  (jdbc/execute-one! (ds) (->sql-vec query) default-opts))

(defn exec!
  "Execute a statement (INSERT/UPDATE/DELETE). Returns the update-count map."
  [query]
  (jdbc/execute-one! (ds) (->sql-vec query)))

(defn exec-returning!
  "Execute a statement with a RETURNING clause; returns the first row."
  [query]
  (jdbc/execute-one! (ds) (->sql-vec query) default-opts))

(defn unique-violation?
  "True when `e` (or anything it wraps) is a Postgres unique-constraint
   violation, SQLSTATE 23505. Use it to turn an index collision into a typed
   domain error instead of leaking a JDBC exception to a handler."
  [^Exception e]
  (boolean
   (some (fn [t] (and (instance? java.sql.SQLException t)
                      (= "23505" (.getSQLState ^java.sql.SQLException t))))
         (take-while some? (take 8 (iterate #(.getCause ^Throwable %) e))))))

(defn with-tx
  "Run (f tx) inside a transaction. `tx` is a next.jdbc connectable."
  [f]
  (jdbc/with-transaction [tx (ds)]
    (f tx)))

(defn tx-exec-returning!
  "Like exec-returning! but on an explicit transaction/connectable."
  [tx query]
  (jdbc/execute-one! tx (->sql-vec query) default-opts))

(comment
  ;; --- REPL playground ---
  (resolve-config)
  (start!)
  (q ["SELECT 1 AS answer"])
  (q ["SELECT table_name FROM information_schema.tables WHERE table_schema='public'"])
  (stop!))
