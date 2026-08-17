(ns cfp-scheduler-killer.store-pg
  "The Postgres backend for the append-only event log.

   THE ABSTRACTION IS UNCHANGED. The JSONL store is 'a file of ordered lines';
   this is 'a table of ordered lines'. One row per event, the JSON line stored
   VERBATIM in a TEXT column, ordered by a BIGSERIAL. Read = SELECT line ORDER
   BY seq; write = INSERT. Nothing else. Every property the JSONL store buys —
   replay, time-travel, `state-as-of`, the security tests that assert the log
   only ever GROWS — survives unchanged because the shape survives unchanged.

   WHY TEXT AND NOT JSONB: house doctrine — store data in its most ORIGINAL
   form and transform on the way OUT. The line we wrote is the line we read
   back, byte for byte, so `canonicalize` still holds and a jsonb round-trip can
   never reorder keys, coerce a number, or lose a duplicate. (A jsonb PROJECTION
   can be added later as a generated column for querying; it would be derived,
   which is the only honest way to have one.)

   APPEND-ONLY IS ENFORCED IN THE DATABASE, not just by convention:
   prevent_update()/prevent_delete() triggers RAISE EXCEPTION. 'Classify, don't
   discard' — there is nothing to classify here, so there is nothing to change.

   CONNECTION: cfp-scheduler-killer.db. Locally a JDBC URL (DATABASE_URL, e.g.
   through cloud_sql_proxy); on Cloud Run the Cloud SQL socket factory with IAM
   auth, so no password secret exists at all. See docs/postgres-store.md."
  (:require [clojure.data.json :as json]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [cfp-scheduler-killer.db :as db]
            [taoensso.timbre :as log]))

;; --- Schema -----------------------------------------------------------------
;;
;; Idempotent DDL, applied at start!. Deliberately NOT part of db/migration-files
;; (001-004 describe the abandoned relational slices) — the event store owns its
;; own one table and nothing else.

(def default-table "store_events")

(def ^{:dynamic true
       :doc "The log table. Rebound ONLY by the Postgres tests, which run
             against the real cloud database and must not append test events to
             the real log — an append-only log has no undo, so isolation has to
             come from writing somewhere else, not from cleaning up after."}
  *table* default-table)

(defn ddl-statements
  "Idempotent DDL for one log table."
  [table]
  [(str "CREATE TABLE IF NOT EXISTS " table " (
      seq        BIGSERIAL PRIMARY KEY,
      line       TEXT NOT NULL,
      created_at TIMESTAMPTZ NOT NULL DEFAULT now())")

   ;; Append-only enforcement, in the DATABASE. Both functions are per-database;
   ;; CREATE OR REPLACE keeps this idempotent.
   "CREATE OR REPLACE FUNCTION prevent_delete()
      RETURNS TRIGGER AS $fn$
      BEGIN
        RAISE EXCEPTION 'DELETE not allowed on %. The log is append-only.', TG_TABLE_NAME;
      END;
      $fn$ LANGUAGE plpgsql"

   "CREATE OR REPLACE FUNCTION prevent_update()
      RETURNS TRIGGER AS $fn$
      BEGIN
        RAISE EXCEPTION 'UPDATE not allowed on %. The log is append-only.', TG_TABLE_NAME;
      END;
      $fn$ LANGUAGE plpgsql"

   ;; CREATE TRIGGER has no portable IF NOT EXISTS, so drop-then-create.
   ;; Dropping a TRIGGER is not dropping DATA — the rows are untouched, and the
   ;; window is a few milliseconds at boot before the app serves anything.
   (str "DROP TRIGGER IF EXISTS no_delete_" table " ON " table)
   (str "CREATE TRIGGER no_delete_" table
        " BEFORE DELETE ON " table " FOR EACH ROW EXECUTE FUNCTION prevent_delete()")

   (str "DROP TRIGGER IF EXISTS no_update_" table " ON " table)
   (str "CREATE TRIGGER no_update_" table
        " BEFORE UPDATE ON " table " FOR EACH ROW EXECUTE FUNCTION prevent_update()")])

(defn- table-exists? [datasource table]
  (some? (jdbc/execute-one! datasource
                            ["SELECT 1 FROM information_schema.tables WHERE table_name = ?"
                             table])))

(defn ensure-schema!
  "Apply the DDL — but only when the table is missing. The Cloud Run role has
   SELECT/INSERT and deliberately NO CREATE on schema public (least
   privilege), so on a provisioned database this must probe-and-skip rather
   than run DDL it isn't allowed to run. First prod boot failed exactly there
   (2026-08-09). Creating the table remains the owner's job (docs/postgres-store.md)."
  ([datasource] (ensure-schema! datasource *table*))
  ([datasource table]
   (if (table-exists? datasource table)
     (log/info :store-pg-schema-present :table table)
     (let [stmts (ddl-statements table)]
       (doseq [stmt stmts]
         (jdbc/execute-one! datasource [stmt]))
       (log/info :store-pg-schema-created :table table :statements (count stmts))))
   nil))

;; --- Lifecycle --------------------------------------------------------------

(defonce ^:private started? (atom false))

(defn start!
  "Open the pool (WITHOUT the legacy 001-004 migrations) and ensure the schema.
   Idempotent."
  []
  (let [ds (db/start-pool!)]
    (when-not @started?
      (ensure-schema! ds)
      (reset! started? true))
    ds))

(defn stop! []
  (reset! started? false)
  (db/stop!))

(defn- ds [] (if @started? (db/ds) (start!)))

;; --- The two operations the store seam needs --------------------------------

(def ^:private opts {:builder-fn rs/as-unqualified-kebab-maps})

(defn append-line!
  "INSERT one already-encoded JSON line. Returns its seq.

   Pure INSERT: no upsert, no ON CONFLICT, no dedupe. A repeated fact is a
   repeated FACT — the log's job is to record that it happened twice."
  [^String line]
  (:seq (jdbc/execute-one!
         (ds)
         [(str "INSERT INTO " *table* " (line) VALUES (?) RETURNING seq") line]
         opts)))

(defn read-lines
  "Every line, in log order."
  []
  (into []
        (map :line)
        (jdbc/execute! (ds)
                       [(str "SELECT line FROM " *table* " ORDER BY seq")]
                       opts)))

(defn read-lines-with-seq
  "Every raw [seq line] pair, in log order, from one consistent query."
  []
  (into []
        (map (juxt :seq :line))
        (jdbc/execute! (ds)
                       [(str "SELECT seq, line FROM " *table* " ORDER BY seq")]
                       opts)))

(defn read-events
  "Every event, parsed, in log order. A malformed row is reported and skipped —
   same contract as the JSONL reader: one bad line must not cost the other
   10,000."
  []
  (into []
        (keep (fn [line]
                (try (json/read-str line :key-fn keyword)
                     (catch Exception e
                       (log/error :unparseable-log-row :msg (.getMessage e)
                                  :line (subs (str line) 0 (min 120 (count (str line)))))
                       nil))))
        (read-lines)))

(defn max-seq
  "Highest seq in the table, or 0. Used as the boot value of the store's change
   mark (see store/current-mark)."
  []
  (or (:max (jdbc/execute-one!
             (ds)
             [(str "SELECT COALESCE(MAX(seq), 0) AS max FROM " *table*)] opts))
      0))

(defn count-lines []
  (:n (jdbc/execute-one! (ds) [(str "SELECT COUNT(*) AS n FROM " *table*)] opts)))

(comment
  ;; --- REPL playground (needs DATABASE_URL or CLOUD_SQL_INSTANCE) ---
  (start!)
  (count-lines)
  (max-seq)
  (append-line! (json/write-str {:at "2026-08-09T00:00:00Z" :type "ping" :payload {}}))
  (take 3 (read-events))
  (stop!))

(defn read-events-since
  "Events with seq > mark, parsed, in log order, plus the highest seq seen —
   the tail an incremental refresh folds onto state it already holds. Same
   skip-bad-rows contract as read-events."
  [mark]
  (let [rows (jdbc/execute! (ds)
                            [(str "SELECT seq, line FROM " *table*
                                  " WHERE seq > ? ORDER BY seq") mark]
                            opts)]
    {:max-seq (or (:seq (peek rows)) mark)
     :events (into []
                   (keep (fn [{:keys [line]}]
                           (try (json/read-str line :key-fn keyword)
                                (catch Exception e
                                  (log/error :unparseable-log-row :msg (.getMessage e)
                                             :line (subs (str line) 0 (min 120 (count (str line)))))
                                  nil))))
                   rows)}))
