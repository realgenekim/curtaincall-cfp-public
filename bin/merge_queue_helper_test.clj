(ns merge-queue-helper-test
  (:require
   [babashka.fs :as fs]
   [clojure.edn :as edn]
   [clojure.string :as str]
   [clojure.test :refer [deftest is run-tests testing]]
   [merge-queue-helper :as sut])
  (:import
   (java.nio.file Files LinkOption Path)
   (java.security MessageDigest)))

(def verified-output
  (str "schema=merge-queue-verdict.v1\n"
       "verdict=:verified\n"
       "command_count=1\n"
       "executed_command_count=1\n"
       "skipped_command_count=0\n"
       "failed_command_index=0\n"
       "failed_rc=0\n"
       "error_class=:none\n"))

(def rejected-output
  (str "schema=merge-queue-verdict.v1\n"
       "verdict=:rejected\n"
       "command_count=2\n"
       "executed_command_count=1\n"
       "skipped_command_count=1\n"
       "failed_command_index=1\n"
       "failed_rc=7\n"
       "error_class=:command-failure\n"))

(def audited-incoherent-output
  (str "schema=merge-queue-verdict.v1\n"
       "stage=verify\n"
       "verdict=:rejected\n"
       "command_count=2\n"
       "executed_command_count=1\n"
       "skipped_command_count=1\n"
       "failed_command_index=2\n"
       "failed_command=merger\\ full\\ suite\n"
       "failed_rc=0\n"
       "error_class=:command-failure\n"))

(defn- sha256 [^bytes bytes]
  (let [digest (.digest (MessageDigest/getInstance "SHA-256") bytes)]
    (apply str (map #(format "%02x" (bit-and 0xff %)) digest))))

(defn- source-state [path]
  (let [p (Path/of (str path) (make-array String 0))
        attributes (Files/readAttributes p "unix:dev,ino,size,lastModifiedTime"
                                         (make-array LinkOption 0))
        bytes (Files/readAllBytes p)]
    {:dev (get attributes "dev")
     :ino (get attributes "ino")
     :size (get attributes "size")
     :last-modified (str (get attributes "lastModifiedTime"))
     :sha256 (sha256 bytes)}))

(defn- decoded [fact]
  (.decode (java.util.Base64/getDecoder) ^String (:payload fact)))

(defn- run-command [argv]
  (let [process (.start (ProcessBuilder. (into-array String argv)))
        stdout (future (.readAllBytes (.getInputStream process)))
        stderr (future (.readAllBytes (.getErrorStream process)))
        exit (.waitFor process)]
    {:exit exit :stdout @stdout :stderr @stderr}))

(defn- write-helper! [path]
  (spit (str path)
        (str "#!/usr/bin/env bash\n"
             "set -euo pipefail\n"
             "source_sha=$(sha256sum \"$3/source.txt\" | awk '{print $1}')\n"
             "printf 'source-sha=%s\\n' \"$source_sha\" >&2\n"
             "case \"$1\" in\n"
             "  pass) printf '%s' '" (str/replace verified-output "'" "'\\''") "'; exit 0 ;;\n"
             "  reject) printf '%s' '" (str/replace rejected-output "'" "'\\''") "'; exit 1 ;;\n"
             "  *) printf 'unexpected mode\\n' >&2; exit 9 ;;\n"
             "esac\n"))
  (.setExecutable (java.io.File. (str path)) true true))

(deftest exact-argv-exit-streams-and-source-identity-are-facts
  (let [root (fs/create-temp-dir {:prefix "merge-helper-capture-"})
        helper (fs/path root "helper.sh")
        source (fs/path root "source.txt")
        log-path (fs/path root "verify log.txt")]
    (try
      (spit (str source) "authoritative source bytes\n")
      (write-helper! helper)
      (let [before (source-state source)
            argv [(str helper) "reject" "verify" (str root) (str log-path)]
            facts (sut/capture! argv)
            verified-facts (sut/capture! (assoc argv 1 "pass"))
            executable (str (fs/parent *file*) "/merge_queue_helper.clj")
            cli (run-command (into [executable "--"] argv))
            cli-response (edn/read-string (String. ^bytes (:stdout cli) "UTF-8"))
            after (source-state source)
            expected-stderr (str "source-sha=" (:sha256 before) "\n")]
        (is (= before after) "capture and helper must not mutate the source")
        (is (= argv (:argv facts)))
        (is (= 1 (:exit facts)) "the real helper exit is captured without shell inversion")
        (is (= rejected-output (String. (decoded (:stdout facts)) "UTF-8")))
        (is (= expected-stderr (String. (decoded (:stderr facts)) "UTF-8")))
        (is (= (count (.getBytes rejected-output "UTF-8"))
               (get-in facts [:stdout :byte-count])))
        (is (= (sha256 (.getBytes rejected-output "UTF-8"))
               (get-in facts [:stdout :sha256])))
        (is (= :rejected (sut/verdict facts)))
        (is (= 0 (:exit verified-facts)))
        (is (= verified-output (String. (decoded (:stdout verified-facts)) "UTF-8")))
        (is (= :verified (sut/verdict verified-facts)))
        (is (= 1 (:exit cli)) "the executable returns the pure rejected verdict")
        (is (= 0 (alength ^bytes (:stderr cli))))
        (is (= :rejected (:verdict cli-response)))
        (is (= 1 (get-in cli-response [:facts :exit])))
        (is (= argv (get-in cli-response [:facts :argv]))))
      (finally (fs/delete-tree root)))))

(deftest if-bang-command-status-cannot-convert-rejection-to-green
  (let [facts {:schema "merge-queue-helper-facts.v1"
               :capture-state :complete
               :argv ["/fixture/helper" "reject"]
               :cwd "/fixture"
               :observed-at {:started "2026-08-16T08:00:00Z"
                             :finished "2026-08-16T08:00:01Z"}
               :duration-ns 1
               :exit 1
               :stdout (sut/byte-fact (.getBytes rejected-output "UTF-8"))
               :stderr (sut/byte-fact (byte-array 0))}]
    (is (= :rejected (sut/verdict facts)))
    (testing "the exact `if ! command; rc=$?` inversion becomes incoherent"
      (is (= :unverified (sut/verdict (assoc facts :exit 0))))
      (is (not= :verified (sut/verdict (assoc facts :exit 0)))))
    (testing "the audited rejected claim with failed_rc=0 is also incoherent"
      (let [audited (assoc facts :exit 0
                           :stdout (sut/byte-fact
                                     (.getBytes audited-incoherent-output "UTF-8")))]
        (is (= :unverified (sut/verdict audited)))
        (is (not (.contains (pr-str (sut/response audited)) "already-landed")))))))

(deftest verified-requires-coherent-exit-and-exact-bytes
  (let [facts {:schema "merge-queue-helper-facts.v1"
               :capture-state :complete
               :argv ["/fixture/helper" "pass"]
               :cwd "/fixture"
               :observed-at {:started "2026-08-16T08:00:00Z"
                             :finished "2026-08-16T08:00:01Z"}
               :duration-ns 1
               :exit 0
               :stdout (sut/byte-fact (.getBytes verified-output "UTF-8"))
               :stderr (sut/byte-fact (byte-array 0))}]
    (is (= :verified (sut/verdict facts)))
    (is (= :unverified (sut/verdict (assoc facts :exit 1))))
    (is (= :unverified
           (sut/verdict (assoc-in facts [:stdout :sha256] (apply str (repeat 64 "0"))))))))

(deftest harness-errors-are-only-unverified
  (let [facts (sut/capture! ["/definitely/absent/merge-queue-helper"])]
    (is (= :error (:capture-state facts)))
    (is (= :helper-start-failed (get-in facts [:harness-error :code])))
    (is (= :unverified (sut/verdict facts)))
    (is (not= :rejected (sut/verdict facts)))
    (is (not= :verified (sut/verdict facts)))))

(let [{:keys [fail error]} (run-tests)]
  (when (pos? (+ fail error))
    (System/exit 1)))
