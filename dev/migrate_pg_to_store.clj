(ns migrate-pg-to-store
  "ONE-SHOT: fold the Postgres rows into the append-only JSONL store.

   Run once, on the day of the pivot:
     clojure -M:dev -i dev/migrate_pg_to_store.clj

   WHAT IT DOES: reads the current `events`, `committees`, `forms`, `people` and
   `memberships` rows and emits one COMPLETE store event per row, carrying that
   row's original created_at as the event's :at. The result is a log that folds
   back to exactly the state Postgres held.

   WHY IT DOESN'T REPLAY events_log: those rows were an AUDIT trail beside the
   authoritative tables, so their payloads are partial by design (a
   `committee.created` row recorded only the id, name and scope). Replaying them
   as store events would fold partial records over complete ones. The tables are
   the better witness of the end state, and the timestamps preserve the when.
   The cost, stated plainly: intermediate history (e.g. the separate
   `event.updated` that added location/website) collapses into the created
   event. That history stays readable in Postgres for as long as db.clj lives.

   Idempotency is the CALLER's job: this appends. Move data/store/events.jsonl
   aside before re-running, or you will double every record."
  (:require [clojure.java.io :as io]
            [cfp-scheduler-killer.db :as db]
            [cfp-scheduler-killer.store :as store]))

(defn- ->instant-str [ts]
  (store/->iso-instant
   (cond
     (nil? ts) nil
     (instance? java.sql.Timestamp ts) (.toInstant ^java.sql.Timestamp ts)
     :else ts)))

(defn- ->date-str [d]
  (store/->iso-date
   (cond
     (nil? d) nil
     (instance? java.sql.Date d) (.toLocalDate ^java.sql.Date d)
     :else d)))

(defn collect-events
  "Read Postgres and build the full event sequence, sorted by time."
  []
  (let [events (db/q {:select [:*] :from :events})
        committees (db/q {:select [:*] :from :committees})
        forms (db/q {:select [:*] :from :forms})
        people (db/q {:select [:*] :from :people})
        memberships (db/q {:select [:*] :from :memberships})
        person-by-id (into {} (map (juxt :id identity)) people)
        committee-event (into {} (map (juxt :id :event-id)) committees)]
    (->>
     (concat
      (for [e events]
        {:at (->instant-str (:created-at e))
         :type "event.created"
         :actor "pg-import"
         :payload {:id (str (:id e))
                   :slug (:slug e)
                   :name (:name e)
                   :starts-on (->date-str (:starts-on e))
                   :ends-on (->date-str (:ends-on e))
                   :tz (:tz e)
                   :cfp-opens-at (->instant-str (:cfp-opens-at e))
                   :cfp-closes-at (->instant-str (:cfp-closes-at e))
                   :support-email (:support-email e)
                   :location (:location e)
                   :website-url (:website-url e)
                   :settings (:settings e)
                   :created-at (->instant-str (:created-at e))}})
      (for [c committees]
        {:at (->instant-str (:created-at c))
         :type "committee.created"
         :actor "pg-import"
         :payload {:id (str (:id c))
                   :event-id (str (:event-id c))
                   :name (:name c)
                   :scope (:scope c)
                   :coverage-target (:coverage-target c)
                   :created-at (->instant-str (:created-at c))}})
      (for [f forms]
        {:at (->instant-str (:created-at f))
         :type "form.installed"
         :actor "pg-import"
         :payload {:id (str (:id f))
                   :event-id (str (:event-id f))
                   :template "eais-charlotte"
                   :fields (:fields f)
                   :created-at (->instant-str (:created-at f))}})
      (for [p people]
        {:at (->instant-str (:created-at p))
         :type "person.created"
         :actor "pg-import"
         :payload {:id (str (:id p))
                   :email (:email p)
                   :name (:name p)
                   :profile (or (:profile p) {})
                   :created-at (->instant-str (:created-at p))}})
      (for [m memberships]
        {:at (->instant-str (:created-at m))
         :type "member.added"
         :actor "pg-import"
         :payload {:id (str (:id m))
                   :committee-id (str (:committee-id m))
                   :event-id (str (get committee-event (:committee-id m)))
                   :person-id (str (:person-id m))
                   :email (:email (get person-by-id (:person-id m)))
                   :name (:name (get person-by-id (:person-id m)))
                   :role (:role m)
                   :created-at (->instant-str (:created-at m))}}))
     ;; A person must exist before their membership; sorting by time gives that
     ;; for free, and ties break in insertion order (people precede memberships
     ;; in the concat above).
     (sort-by :at)
     vec)))

(defn -main [& _]
  (db/start!)
  (let [events (collect-events)
        path @store/store-path]
    (when (.exists (io/file path))
      (println "REFUSING: " path " already exists. Move it aside first.")
      (System/exit 1))
    (println "Writing" (count events) "events to" path)
    (store/append-all! events)
    (db/stop!)
    (let [reloaded (store/load!)]
      (println "\nFolded state:")
      (println "  events     :" (count (:events reloaded)))
      (println "  committees :" (count (:committees reloaded)))
      (println "  forms      :" (count (:forms reloaded)))
      (println "  people     :" (count (:people reloaded)))
      (println "  memberships:" (count (:memberships reloaded)))
      (doseq [[slug e] (:events reloaded)]
        (println "  -" slug "|" (:name e) "|" (:location e))))))

(-main)
(System/exit 0)
