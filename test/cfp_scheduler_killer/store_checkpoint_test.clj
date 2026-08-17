(ns cfp-scheduler-killer.store-checkpoint-test
  (:require
   [cfp-scheduler-killer.store :as store]
   [cfp-scheduler-killer.store-checkpoint :as checkpoint]
   [clojure.data.json :as json]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]])
  (:import
   (java.math BigInteger)
   (java.nio.charset StandardCharsets)
   (java.nio.file Files)
   (java.nio.file.attribute FileAttribute)
   (java.security MessageDigest)))

(def sample-events
  [{:at "2026-08-16T00:00:00Z" :type "checkpoint.test" :payload {:n 1}}
   {:at "2026-08-16T00:00:01Z" :type "checkpoint.test" :payload {:n 2}}
   {:at "2026-08-16T00:00:02Z" :type "checkpoint.test" :payload {:n 3}}])

(defn- sha256-lines [rows]
  (let [bytes (.getBytes (str/join "\n" (map second rows)) StandardCharsets/UTF_8)
        digest (.digest (MessageDigest/getInstance "SHA-256") bytes)]
    (format "%064x" (BigInteger. 1 digest))))

(defn- temp-checkpoint-path []
  (let [dir (Files/createTempDirectory
              "store-checkpoint-test"
              (into-array FileAttribute []))]
    (str (io/file (str dir) "store-checkpoint.json"))))

(defn- valid-checkpoint [rows]
  {:schema-version 1
   :backend "postgres"
   :table "store_events"
   :frontier (or (some-> rows peek first) 0)
   :row-count (count rows)
   :sha256 (sha256-lines rows)
   :rows rows})

(defn- write-json! [path value]
  (io/make-parents path)
  (spit path (json/write-str value) :encoding "UTF-8")
  path)

;; INTENT-TEST: STORE-CKPT-001
(deftest round-trip-test
  (let [rows (mapv vector [11 12 13] (mapv json/write-str sample-events))
        path (write-json! (temp-checkpoint-path) (valid-checkpoint rows))]
    (is (= {:events sample-events :frontier 13}
           (checkpoint/hydrate! path)))))

;; INTENT-TEST: STORE-CKPT-001
(deftest fail-closed-validation-test
  (testing "a missing file"
    (is (nil? (checkpoint/hydrate! (temp-checkpoint-path)))))
  (testing "corrupt JSON"
    (let [path (temp-checkpoint-path)]
      (spit path "{not json" :encoding "UTF-8")
      (is (nil? (checkpoint/hydrate! path)))))
  (let [rows [[1 (json/write-str (first sample-events))]]
        valid (valid-checkpoint rows)]
    (doseq [[label body]
            [["wrong schema version" (assoc valid :schema-version 2)]
             ["wrong backend" (assoc valid :backend "jsonl")]
             ["wrong table" (assoc valid :table "other_events")]
             ["sha256 mismatch" (assoc valid :sha256 (apply str (repeat 64 "0")))]
             ["row-count mismatch" (assoc valid :row-count 2)]
             ["frontier mismatch" (assoc valid :frontier 2)]
             ["non-monotonic seqs"
              (valid-checkpoint [[2 (json/write-str (first sample-events))]
                                 [1 (json/write-str (second sample-events))]])]]]
      (testing label
        (let [path (write-json! (temp-checkpoint-path) body)]
          (is (nil? (checkpoint/hydrate! path))))))))

;; INTENT-TEST: STORE-CKPT-001
(deftest bad-line-is-skipped-test
  (let [good-events [(first sample-events) (last sample-events)]
        rows [[1 (json/write-str (first good-events))]
              [2 "{not json"]
              [3 (json/write-str (second good-events))]]
        path (write-json! (temp-checkpoint-path) (valid-checkpoint rows))]
    (is (= {:events good-events :frontier 3}
           (checkpoint/hydrate! path)))))

;; INTENT-TEST: STORE-CKPT-001
(deftest checkpoint-prefix-plus-tail-equals-full-fold-test
  (let [lines (mapv json/write-str sample-events)
        prefix-rows (mapv vector [1 2] (take 2 lines))
        path (write-json! (temp-checkpoint-path) (valid-checkpoint prefix-rows))
        hydrated (checkpoint/hydrate! path)
        tail-events [(json/read-str (last lines) :key-fn keyword)]]
    (is (= (store/fold sample-events)
           (store/fold (store/fold (:events hydrated)) tail-events)))))
