#!/usr/bin/env bb
(ns merge-queue-helper
  "Capture an exact helper process result, then project a fail-closed verdict."
  (:require
   [clojure.string :as str])
  (:import
   (java.io File)
   (java.nio ByteBuffer)
   (java.nio.charset CharacterCodingException CodingErrorAction StandardCharsets)
   (java.security MessageDigest)
   (java.time Instant)
   (java.util Base64)
   (java.util.concurrent TimeUnit)))

(def ^:private default-timeout-ms 30000)
(def ^:private sha256-pattern #"[0-9a-f]{64}")
(def ^:private required-claim-keys
  #{"schema" "verdict" "command_count" "executed_command_count"
    "skipped_command_count" "failed_command_index" "failed_rc" "error_class"})

(defn- sha256 [^bytes bytes]
  (let [digest (.digest (MessageDigest/getInstance "SHA-256") bytes)]
    (apply str (map #(format "%02x" (bit-and 0xff %)) digest))))

(defn byte-fact [^bytes bytes]
  {:encoding "base64"
   :byte-count (alength bytes)
   :sha256 (sha256 bytes)
   :payload (.encodeToString (Base64/getEncoder) bytes)})

(defn- decode-byte-fact [fact]
  (try
    (when (and (map? fact)
               (= "base64" (:encoding fact))
               (nat-int? (:byte-count fact))
               (string? (:sha256 fact))
               (re-matches sha256-pattern (:sha256 fact))
               (string? (:payload fact)))
      (let [bytes (.decode (Base64/getDecoder) ^String (:payload fact))]
        (when (and (= (:byte-count fact) (alength bytes))
                   (= (:sha256 fact) (sha256 bytes)))
          bytes)))
    (catch Exception _ nil)))

(defn- error-facts [argv started-at started-ns code error]
  {:schema "merge-queue-helper-facts.v1"
   :capture-state :error
   :argv (vec argv)
   :cwd (.getCanonicalPath (File. "."))
   :observed-at {:started (str started-at) :finished (str (Instant/now))}
   :duration-ns (- (System/nanoTime) started-ns)
   :stdout (byte-fact (byte-array 0))
   :stderr (byte-fact (byte-array 0))
   :harness-error {:code code
                   :class (.getName (class error))
                   :message (or (.getMessage ^Throwable error) "")}})

(defn capture!
  "Execute argv directly, without a shell, and return exact process facts."
  ([argv] (capture! argv default-timeout-ms))
  ([argv timeout-ms]
   (let [argv (vec argv)
         started-at (Instant/now)
         started-ns (System/nanoTime)]
     (if-not (and (seq argv) (every? string? argv)
                  (pos-int? timeout-ms))
       (error-facts argv started-at started-ns :invalid-harness-request
                    (ex-info "argv or timeout is invalid" {}))
       (try
         (let [process (.start (ProcessBuilder. (into-array String argv)))
               stdout-future (future (.readAllBytes (.getInputStream process)))
               stderr-future (future (.readAllBytes (.getErrorStream process)))
               finished? (.waitFor process (long timeout-ms) TimeUnit/MILLISECONDS)]
           (when-not finished?
             (.destroyForcibly process)
             (.waitFor process))
           (let [stdout @stdout-future
                 stderr @stderr-future
                 common {:schema "merge-queue-helper-facts.v1"
                         :capture-state (if finished? :complete :error)
                         :argv argv
                         :cwd (.getCanonicalPath (File. "."))
                         :observed-at {:started (str started-at)
                                       :finished (str (Instant/now))}
                         :duration-ns (- (System/nanoTime) started-ns)
                         :exit (.exitValue process)
                         :stdout (byte-fact stdout)
                         :stderr (byte-fact stderr)}]
             (if finished?
               common
               (assoc common :harness-error
                      {:code :helper-timeout
                       :class "java.util.concurrent.TimeoutException"
                       :message "helper exceeded the fixed deadline"}))))
         (catch Exception error
           (error-facts argv started-at started-ns :helper-start-failed error)))))))

(defn- strict-utf8 [^bytes bytes]
  (try
    (str (.decode (doto (.newDecoder StandardCharsets/UTF_8)
                    (.onMalformedInput CodingErrorAction/REPORT)
                    (.onUnmappableCharacter CodingErrorAction/REPORT))
                  (ByteBuffer/wrap bytes)))
    (catch CharacterCodingException _ nil)))

(defn- parse-unsigned-long [value]
  (try
    (when (and (string? value) (re-matches #"[0-9]+" value))
      (Long/parseLong value))
    (catch Exception _ nil)))

(defn- parse-claim [text]
  (let [lines (remove str/blank? (str/split-lines text))
        pairs (mapv (fn [line]
                      (let [index (.indexOf ^String line "=")]
                        (when (pos? index)
                          [(subs line 0 index) (subs line (inc index))])))
                    lines)]
    (when (and (seq pairs)
               (every? some? pairs)
               (= (count pairs) (count (set (map first pairs)))))
      (into {} pairs))))

(defn- coherent-facts? [facts stdout-bytes]
  (and (= "merge-queue-helper-facts.v1" (:schema facts))
       (= :complete (:capture-state facts))
       (vector? (:argv facts))
       (seq (:argv facts))
       (every? string? (:argv facts))
       (integer? (:exit facts))
       (<= 0 (:exit facts) 255)
       (nat-int? (:duration-ns facts))
       (map? (:observed-at facts))
       (try
         (Instant/parse (get-in facts [:observed-at :started]))
         (Instant/parse (get-in facts [:observed-at :finished]))
         true
         (catch Exception _ false))
       stdout-bytes
       (decode-byte-fact (:stderr facts))))

(defn verdict
  "Purely project exact process facts. Any harness/fact incoherence is unverified."
  [facts]
  (try
    (let [stdout-bytes (decode-byte-fact (:stdout facts))
          stdout (some-> stdout-bytes strict-utf8)
          claim (some-> stdout parse-claim)
          command-count (parse-unsigned-long (get claim "command_count"))
          executed-count (parse-unsigned-long (get claim "executed_command_count"))
          skipped-count (parse-unsigned-long (get claim "skipped_command_count"))
          failed-index (parse-unsigned-long (get claim "failed_command_index"))
          failed-rc (parse-unsigned-long (get claim "failed_rc"))
          claim-verdict (get claim "verdict")
          counts-coherent? (and command-count executed-count skipped-count
                                (= command-count (+ executed-count skipped-count)))
          claim-coherent? (and (coherent-facts? facts stdout-bytes)
                               claim
                               (every? #(contains? claim %) required-claim-keys)
                               (= "merge-queue-verdict.v1" (get claim "schema"))
                               counts-coherent?
                               failed-index failed-rc)]
      (cond
        (not claim-coherent?) :unverified

        (and (= ":verified" claim-verdict)
             (zero? (:exit facts))
             (zero? failed-index)
             (zero? failed-rc)
             (= ":none" (get claim "error_class")))
        :verified

        (and (= ":rejected" claim-verdict)
             (= 1 (:exit facts))
             (pos? command-count)
             (<= 1 failed-index command-count)
             (pos? failed-rc)
             (not= ":none" (get claim "error_class")))
        :rejected

        (and (= ":unverified" claim-verdict)
             (= 2 (:exit facts)))
        :unverified

        :else :unverified))
    (catch Exception _ :unverified)))

(defn response [facts]
  {:schema "merge-queue-helper-response.v1"
   :facts facts
   :verdict (verdict facts)})

(defn -main [& arguments]
  (let [argv (when (= "--" (first arguments)) (vec (rest arguments)))
        facts (capture! (or argv []))
        result (response facts)]
    (prn result)
    (System/exit (case (:verdict result) :verified 0 :rejected 1 2))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
