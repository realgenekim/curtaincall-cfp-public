(ns cfp-scheduler-killer.admin
  "Operator CLI — the video-publisher/VLAdmin pattern (Gene, 2026-08-09):
   every task is a `clj -X` entry function wired to a Makefile one-liner.
   The fn takes ONE arg map with :or defaults, fires up its own
   infrastructure (no running server needed), does the task, prints a
   count-first summary, and returns data. Docstrings carry the exact
   invocations.

   Backups honor the house data rule: the event log is copied VERBATIM —
   raw lines in log order, never re-encoded. A backup that transforms is a
   corrupted witness."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [taoensso.timbre :as log]
            [cfp-scheduler-killer.store-pg :as store-pg])
  (:import (java.time ZonedDateTime ZoneOffset)
           (java.time.format DateTimeFormatter)))

(def ^:private stamp-fmt (DateTimeFormatter/ofPattern "yyyy-MM-dd-HHmmss"))

(defn- utc-stamp []
  (.format (ZonedDateTime/now ZoneOffset/UTC) stamp-fmt))

(defn backup-db
  "Back up the Postgres event log to a local ./backups/ file, verbatim.

   clj -X cfp-scheduler-killer.admin/backup-db
   clj -X cfp-scheduler-killer.admin/backup-db :dest '\"backups\"'

   Connects with the same runtime-loaded credentials as the app (Cloud SQL
   IAM: ADC on the laptop — no secret exists). Read-only: SELECTs the log,
   writes <dest>/<utc-stamp>-events-pg.jsonl, prints the count first."
  [{:keys [dest] :or {dest "backups"}}]
  (log/info :admin/backup-db :entering :dest dest)
  (store-pg/start!)
  (let [lines (store-pg/read-lines)
        f (io/file dest (str (utc-stamp) "-events-pg.jsonl"))]
    (io/make-parents f)
    (with-open [w (io/writer f)]
      (doseq [^String l lines]
        (.write w l)
        (.write w "\n")))
    (println)
    (println (format "%,d events -> %s (%,d bytes)"
                     (count lines) (.getPath f) (.length f)))
    (store-pg/stop!)
    {:events (count lines) :file (.getPath f)}))

(defn promote-local
  "Replay the LOCAL dev event log into the Postgres store, verbatim, in order.

   clj -X cfp-scheduler-killer.admin/promote-local
   clj -X cfp-scheduler-killer.admin/promote-local :force true

   The one-database migration move (first run 2026-08-10, via REPL: 620/620
   byte-identical). Refuses a non-empty destination unless :force true — and
   even forced it only APPENDS after the existing log (the table's triggers
   forbid anything else). Verifies after write: the destination tail must be
   byte-identical to the source."
  [{:keys [src force] :or {src "data/store/events.jsonl"}}]
  (log/info :admin/promote-local :entering :src src)
  (store-pg/start!)
  (let [lines (with-open [r (io/reader src)]
                (into [] (remove clojure.string/blank?) (line-seq r)))
        before (count (store-pg/read-lines))]
    (if (and (pos? before) (not force))
      (do (println (format "REFUSED: destination already has %,d events. :force true appends after them." before))
          (store-pg/stop!)
          {:refused true :existing before})
      (do (doseq [^String l lines] (store-pg/append-line! l))
          (let [after (store-pg/read-lines)
                verbatim? (= (subvec after before) lines)]
            (println)
            (println (format "%,d local events appended after %,d existing -> %,d total"
                             (count lines) before (count after)))
            (println (if verbatim?
                       "VERIFIED: destination tail is byte-identical to the source."
                       "MISMATCH: destination tail differs from the source — do not trust this run."))
            (store-pg/stop!)
            {:appended (count lines) :before before :verbatim verbatim?})))))
