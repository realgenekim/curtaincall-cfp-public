(ns cfp-scheduler-killer.store-pg-test
  "Postgres backend tests — these hit a REAL Cloud SQL database over the
   network, so they are OFF by default and the normal suite never needs a
   connection.

     PG_TESTS=true DATABASE_URL='jdbc:postgresql://127.0.0.1:5433/cfp_scheduler_killer?user=genek&password=…' bin/kaocha

   (or CLOUD_SQL_INSTANCE=… for the IAM socket-factory path — same tests, no
   password anywhere.)

   TWO THINGS ARE BEING PROVEN, and only these:
     1. The seam is faithful — append N lines, read them back IN ORDER, fold
        them, and get the same state the JSONL backend would produce.
     2. The log is append-only IN THE DATABASE — UPDATE and DELETE raise, so a
        stray `psql` cannot quietly rewrite history.

   WHY A SEPARATE TABLE: the tests run against the real database, but they write
   to `store_events_test`, not `store_events`. An append-only log has no undo —
   isolation has to come from writing somewhere ELSE, not from cleaning up
   afterwards. Same instance, same database, same DDL, same triggers, same
   network; just not the real log."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [clojure.data.json :as json]
            [next.jdbc :as jdbc]
            [cfp-scheduler-killer.db :as db]
            [cfp-scheduler-killer.store :as store]
            [cfp-scheduler-killer.store-pg :as pg]))

(def enabled?
  (= "true" (some-> (System/getenv "PG_TESTS") str/trim str/lower-case)))

(def test-table "store_events_test")

(defn with-pg
  "Point the store at Postgres and at the scratch table, fresh each run."
  [f]
  (binding [pg/*table* test-table]
    (let [ds (pg/start!)]
      ;; A previous run's rows would make the ordering assertions meaningless,
      ;; and DELETE is (correctly) forbidden — so the scratch table is dropped
      ;; and rebuilt. Dropping a SCRATCH table is not discarding a log.
      (jdbc/execute-one! ds [(str "DROP TABLE IF EXISTS " test-table)])
      (pg/ensure-schema! ds test-table)
      (store/set-backend! :postgres)
      (reset! store/state store/empty-state)
      (try (f)
           (finally
             (store/await-sinks!)
             (store/set-backend! :jsonl)
             (reset! store/state store/empty-state))))))

(when enabled?
  (use-fixtures :each with-pg))

(def sample-events
  [{:at "2026-08-01T10:00:00Z" :type "event.created" :actor "gene"
    :payload {:id "e1" :slug "pgtest" :name "PG Test Summit"
              :starts-on "2026-10-14" :ends-on "2026-10-15"
              :settings {:review-visibility "open"}
              :created-at "2026-08-01T10:00:00Z"}}
   {:at "2026-08-01T10:00:01Z" :type "committee.created" :actor "gene"
    :payload {:id "c1" :event-id "e1" :name "Program Committee"
              :scope {:all true} :created-at "2026-08-01T10:00:01Z"}}
   {:at "2026-08-02T09:00:00Z" :type "person.created" :actor "gene"
    :payload {:id "p1" :email "ann@example.com" :name "Ann Perry" :profile {}
              :created-at "2026-08-02T09:00:00Z"}}])

(when enabled?

  (deftest pg-round-trip-test
    (testing "appends land in the table, in order, and read back verbatim"
      (doseq [e sample-events] (store/append! e))
      (is (= 3 (pg/count-lines)))
      (let [lines (pg/read-lines)]
        (is (= 3 (count lines)))
        (is (every? string? lines) "the JSON line is stored VERBATIM as text")
        (is (= ["event.created" "committee.created" "person.created"]
               (mapv #(:type (json/read-str % :key-fn keyword)) lines))
            "seq preserves the order they were appended in")))

    (testing "read-events parses them back in the same order"
      (is (= ["event.created" "committee.created" "person.created"]
             (mapv :type (store/read-events)))))

    (testing "the fold over the Postgres log is the SAME state as over a JSONL log"
      (let [from-pg (store/fold (store/read-events))]
        (is (= 1 (count (:events from-pg))))
        (is (= "PG Test Summit" (get-in from-pg [:events "pgtest" :name])))
        (is (= (java.time.LocalDate/of 2026 10 14)
               (get-in from-pg [:events "pgtest" :starts-on]))
            "ISO strings still become domain values — the wire format is unchanged")
        (is (= 1 (count (:committees from-pg))))
        (is (= 1 (count (:people from-pg))))
        (is (= 3 (count (:log from-pg)))))))

  (deftest pg-live-projection-matches-reload-test
    (testing "the in-memory projection equals a fresh replay from the database"
      (doseq [e sample-events] (store/append! e))
      (let [live @store/state
            reloaded (store/fold (store/read-events))]
        (is (= (:events live) (:events reloaded)))
        (is (= (:committees live) (:committees reloaded)))
        (is (= (:people live) (:people reloaded)))
        (is (= (:log live) (:log reloaded))
            "canonicalize still holds: we fold exactly what we wrote"))))

  (deftest pg-load-refolds-from-the-database-test
    (testing "load! rebuilds state from Postgres alone"
      (doseq [e sample-events] (store/append! e))
      (reset! store/state store/empty-state)
      (store/load!)
      (is (= "PG Test Summit" (get-in @store/state [:events "pgtest" :name])))
      (is (= 3 (count (:log @store/state))))))

  (deftest append-only-is-enforced-by-the-database-test
    (store/append! (first sample-events))
    (let [ds (db/ds)]
      (testing "UPDATE raises — history cannot be rewritten"
        (let [e (is (thrown? Exception
                             (jdbc/execute-one!
                              ds [(str "UPDATE " test-table " SET line = 'tampered'")])))]
          (is (re-find #"UPDATE not allowed" (str (ex-message e) (ex-cause e)))
              "and it says why")))

      (testing "DELETE raises — nothing is ever removed"
        (let [e (is (thrown? Exception
                             (jdbc/execute-one!
                              ds [(str "DELETE FROM " test-table)])))]
          (is (re-find #"DELETE not allowed" (str (ex-message e) (ex-cause e))))))

      (testing "and the row is still there, untouched"
        (is (= 1 (pg/count-lines)))
        (is (= "event.created" (:type (first (store/read-events)))))))))
