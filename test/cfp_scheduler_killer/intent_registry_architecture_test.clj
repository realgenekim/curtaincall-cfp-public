(ns cfp-scheduler-killer.intent-registry-architecture-test
  (:require
   [clojure.data.json :as json]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.java.shell :as shell]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

(def ^:private allowed-statuses
  #{:active :proposed :retired :superseded})

(defn- registry []
  (edn/read-string (slurp "docs/intent/registry.edn")))

(defn- discovered-test-names []
  (->> (file-seq (io/file "test"))
       (filter #(.isFile %))
       (filter #(str/ends-with? (.getName %) ".clj"))
       (mapcat #(re-seq #"\(deftest\s+([^\s\)]+)" (slurp %)))
       (map (comp keyword second))
       set))

(def ^:private tagged-deftest-re
  #"(?ms)((?:[ \t]*;;[ \t]*INTENT-TEST:[ \t]*[A-Za-z][A-Za-z0-9_-]*[^\n]*\n)+)[ \t]*\(deftest[ \t]+([^\s\)]+)")

(def ^:private intent-test-tag-re
  #"INTENT-TEST:[ \t]*([A-Za-z][A-Za-z0-9_-]*)")

(defn- tagged-intents-by-test []
  (->> (file-seq (io/file "test"))
       (filter #(and (.isFile ^java.io.File %)
                     (re-find #"_test\.clj[cs]?$" (.getName ^java.io.File %))))
       (mapcat #(re-seq tagged-deftest-re (slurp %)))
       (reduce (fn [index [_ tag-block test-name]]
                 (reduce (fn [m [_ id]]
                           (update m (keyword test-name) (fnil conj #{}) (keyword id)))
                         index
                         (re-seq intent-test-tag-re tag-block)))
               {})))

(deftest intent-registry-is-an-append-only-executable-index-test
  (let [{:keys [schema-version intents]} (registry)
        ids (mapv :id intents)
        active (filter #(= :active (:status %)) intents)
        test-names (discovered-test-names)]
    (testing "the ledger has one stable identity per atomic requirement"
      (is (= 1 schema-version))
      (is (seq intents))
      (is (every? keyword? ids))
      (is (= (count ids) (count (distinct ids)))
          (str "intent IDs are forever and cannot be reused: " (pr-str ids))))
    (testing "every row follows the declared registry schema"
      (doseq [{:keys [id ears status pins]} intents]
        (is (contains? allowed-statuses status) (str id " has invalid status " status))
        (is (and (string? ears) (not (str/blank? ears)) (not (str/includes? ears "\n")))
            (str id " must have one atomic EARS line"))
        (is (and (string? pins) (not (str/blank? pins)))
            (str id " must explain the regression or decision it pins"))))
    (testing "every active row names at least one test that really exists"
      (doseq [{:keys [id tests]} active]
        (is (seq tests) (str id " is active but names no behavioral witness"))
        (doseq [test-name tests]
          (is (contains? test-names test-name)
              (str id " names missing test " test-name)))))))

(deftest active-intents-tag-their-declared-behavioral-witnesses-test
  (let [active (filter #(= :active (:status %)) (:intents (registry)))
        tagged-by-test (tagged-intents-by-test)]
    (doseq [{:keys [id tests]} active
            test-name tests]
      (is (contains? (get tagged-by-test test-name #{}) id)
          (str id " declares " test-name
               " as its witness, but that deftest is not immediately preceded by "
               ";; INTENT-TEST: " (name id)
               ". A tag elsewhere cannot prove the declared test guards this intent.")))))
