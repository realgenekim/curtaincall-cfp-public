(ns cfp-scheduler-killer.store-checkpoint
  "Local, validated snapshot of the raw Postgres event log."
  (:require
   [cfp-scheduler-killer.store-pg :as store-pg]
   [clojure.data.json :as json]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [taoensso.timbre :as log])
  (:import
   (java.math BigInteger)
   (java.nio.charset StandardCharsets)
   (java.nio.file CopyOption Files StandardCopyOption)
   (java.nio.file.attribute FileAttribute)
   (java.security MessageDigest)))

(def checkpoint-path "./cache/store-checkpoint.json")

(defn- sha256-lines [rows]
  (let [bytes (.getBytes (str/join "\n" (map second rows)) StandardCharsets/UTF_8)
        digest (.digest (MessageDigest/getInstance "SHA-256") bytes)]
    (format "%064x" (BigInteger. 1 digest))))

(defn- frontier [rows]
  (reduce max 0 (map first rows)))

(defn- valid-rows? [rows]
  (and (vector? rows)
       (every? (fn [row]
                 (and (vector? row)
                      (= 2 (count row))
                      (integer? (first row))
                      (pos? (first row))
                      (string? (second row))))
               rows)))

(defn- monotonic-seqs? [rows]
  (let [seqs (map first rows)]
    (every? true? (map < seqs (rest seqs)))))

(defn- reject [path reason]
  (log/warn :store-checkpoint-rejected :path (str path) :reason reason)
  nil)

(defn- parse-events [rows]
  (into []
        (keep (fn [[_ line]]
                (try
                  (json/read-str line :key-fn keyword)
                  (catch Exception e
                    (log/error :unparseable-log-row :msg (.getMessage e)
                               :line (subs line 0 (min 120 (count line))))
                    nil))))
        rows))

(defn hydrate!
  "Validate and hydrate a checkpoint, or fail closed to nil."
  [path]
  (let [file (io/file path)]
    (if-not (.isFile file)
      (reject path :missing)
      (try
        (let [{:keys [schema-version backend table row-count sha256 rows]
               checkpoint-frontier :frontier}
              (json/read-str (slurp file :encoding "UTF-8") :key-fn keyword)]
          (cond
            (not= 1 schema-version)
            (reject path :schema-version-mismatch)

            (not= "postgres" backend)
            (reject path :backend-mismatch)

            (not= store-pg/default-table table)
            (reject path :table-mismatch)

            (not (valid-rows? rows))
            (reject path :invalid-rows)

            (not= row-count (count rows))
            (reject path :row-count-mismatch)

            (not= sha256 (sha256-lines rows))
            (reject path :sha256-mismatch)

            (not (monotonic-seqs? rows))
            (reject path :non-monotonic-seqs)

            (not= checkpoint-frontier (frontier rows))
            (reject path :frontier-mismatch)

            :else
            {:events (parse-events rows)
             :frontier checkpoint-frontier}))
        (catch Exception e
          (log/warn :store-checkpoint-rejected :path (str path)
                    :reason :unparsable-json :msg (.getMessage e))
          nil)))))

(defn write-checkpoint!
  "Read the complete raw Postgres log once and atomically replace the cache."
  ([] (write-checkpoint! checkpoint-path))
  ([path]
   (let [started (System/nanoTime)
         rows (store-pg/read-lines-with-seq)
         sha256 (sha256-lines rows)
         checkpoint {:schema-version 1
                     :backend "postgres"
                     :table store-pg/default-table
                     :frontier (frontier rows)
                     :row-count (count rows)
                     :sha256 sha256
                     :rows rows}
         payload (json/write-str checkpoint)
         bytes (.getBytes payload StandardCharsets/UTF_8)
         target (.toPath (io/file path))
         parent (.getParent (.toAbsolutePath target))]
     (Files/createDirectories parent (into-array FileAttribute []))
     (let [temp (Files/createTempFile parent "store-checkpoint-" ".tmp"
                                      (into-array FileAttribute []))]
       (try
         (spit (.toFile temp) payload :encoding "UTF-8")
         (Files/move temp target
                     (into-array CopyOption
                                 [StandardCopyOption/ATOMIC_MOVE
                                  StandardCopyOption/REPLACE_EXISTING]))
         {:rows (count rows)
          :bytes (alength bytes)
          :sha256 sha256
          :frontier (:frontier checkpoint)
          :elapsed-ms (long (/ (- (System/nanoTime) started) 1000000))}
         (finally
           (Files/deleteIfExists temp)))))))

(defn -main [& _]
  (try
    (let [{:keys [rows bytes sha256 frontier elapsed-ms]} (write-checkpoint!)]
      (println (format "rows=%d bytes=%d sha256=%s frontier=%d elapsed-ms=%d"
                       rows bytes (subs sha256 0 12) frontier elapsed-ms)))
    (catch Exception e
      (log/error :store-checkpoint-write-failed :msg (.getMessage e) :error e)
      (System/exit 1))))
