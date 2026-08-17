(ns cfp-scheduler-killer.test-suite-architecture-test
  (:require
   [clojure.data.json :as json]
   [clojure.java.shell :as shell]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

(defn- test-definitions []
  (let [{:keys [out err]}
        (shell/sh "clj-kondo" "--lint" "test"
                  "--config"
                  "{:analysis {:var-definitions true} :output {:format :json}}")
        result (json/read-str out :key-fn keyword)]
    (is (zero? (get-in result [:summary :error]))
        (str "clj-kondo must resolve the test suite: " err))
    (->> (get-in result [:analysis :var-definitions])
         (filter :test))))

(deftest every-test-var-has-one-definition-test
  (let [definitions (test-definitions)
        duplicates (->> definitions
                        (group-by (juxt :ns :name))
                        (keep (fn [[test-var sites]]
                                (when (< 1 (count sites))
                                  [test-var
                                   (mapv #(select-keys % [:filename :row]) sites)])))
                        (into (sorted-map)))]
    (testing "the analyzer found the real regression suite"
      (is (< 100 (count definitions))))
    (testing "a later deftest cannot silently replace an earlier regression pin"
      (is (empty? duplicates)
          (str "duplicate test vars shadow earlier coverage: "
               (pr-str duplicates))))))

(defn- test-analysis []
  (let [{:keys [out err]}
        (shell/sh "clj-kondo" "--lint" "test"
                  "--config"
                  "{:analysis {:var-definitions true :var-usages true} :output {:format :json}}")
        result (json/read-str out :key-fn keyword)]
    (is (zero? (get-in result [:summary :error]))
        (str "clj-kondo must analyze the test suite: " err))
    (:analysis result)))

(deftest every-deftest-contains-an-assertion-test
  (let [{:keys [var-definitions var-usages]} (test-analysis)
        definitions (filter #(= "clojure.test/deftest" (:defined-by %))
                            var-definitions)
        assertion-sites (->> var-usages
                             (filter #(and (= "clojure.test" (:to %))
                                           (#{"is" "are"} (:name %)))))
        assertions-by-file (group-by :filename assertion-sites)
        empty-tests
        (->> definitions
             (remove
               (fn [{:keys [filename row end-row]}]
                 (some #(<= row (:row %) end-row)
                       (get assertions-by-file filename))))
             (map #(select-keys % [:filename :row :name]))
             vec)]
    (testing "the analyzer found every deftest, not an empty sample"
      (is (< 100 (count definitions))))
    (testing "a regression test cannot silently pass without an assertion"
      (is (empty? empty-tests)
          (str "deftest forms without clojure.test/is or are: "
               (pr-str empty-tests))))))

(deftest committed-tests-cannot-disable-or-focus-the-regression-net-test
  (let [definitions (test-definitions)
        forbidden-meta #{:focus :skip :kaocha/focus :kaocha/skip}
        disabled-tests
        (->> definitions
             (keep
               (fn [definition]
                 (let [metadata (meta (find-var (symbol (str (:ns definition))
                                                  (str (:name definition)))))
                       flags (set (filter metadata forbidden-meta))]
                   (when (seq flags)
                     [(select-keys definition [:filename :row :ns :name]) flags]))))
             vec)]
    (testing "the check covers the real discovered suite"
      (is (< 100 (count definitions))))
    (testing "no committed test may focus CI or opt out of the regression net"
      (is (empty? disabled-tests)
          (str "focused/skipped test vars silently narrow coverage: "
               (pr-str disabled-tests))))))

(defn- test-namespace-definitions []
  (let [{:keys [out]}
        (shell/sh "clj-kondo" "--lint" "test"
                  "--config"
                  "{:analysis {:namespace-definitions true} :output {:format :json}}")
        result (json/read-str out :key-fn keyword)]
    (get-in result [:analysis :namespace-definitions])))

(defn- expected-test-namespace [filename]
  (-> filename
      (str/replace #"^test/" "")
      (str/replace #"\.clj[cs]?$" "")
      (str/replace "_" "-")
      (str/replace "/" ".")))

(deftest ^:ci every-test-file-has-one-path-matching-namespace-test
  (let [definitions (test-namespace-definitions)
        by-file (group-by :filename definitions)
        test-files (->> (file-seq (java.io.File. "test"))
                        (filter #(.isFile %))
                        (map #(.getPath %))
                        (filter #(re-find #"\.clj[cs]?$" %))
                        sort)
        violations
        (->> test-files
             (keep
               (fn [filename]
                 (let [found (mapv :name (get by-file filename))
                       expected (expected-test-namespace filename)]
                   (when (not= [expected] found)
                     {:filename filename :expected expected :found found}))))
             vec)]
    (testing "the analyzer and filesystem both cover the real suite"
      (is (< 50 (count test-files)))
      (is (= (count test-files) (count definitions))))
    (testing "each test file owns exactly one namespace matching its path"
      (is (empty? violations)
          (str "test namespaces can be silently skipped or collide: "
               (pr-str violations))))))

(deftest every-test-suffixed-file-defines-a-discovered-test-test
  (let [definitions (test-definitions)
        files-with-tests (into #{} (map :filename) definitions)
        test-suffixed-files
        (->> (file-seq (java.io.File. "test"))
             (filter #(.isFile %))
             (map #(.getPath %))
             (filter #(re-find #"_test\.clj[cs]?$" %))
             set)
        empty-test-files (sort (remove files-with-tests test-suffixed-files))]
    (testing "the naming convention covers the real regression suite"
      (is (< 50 (count test-suffixed-files))))
    (testing "a file advertised as a test cannot silently contribute zero tests"
      (is (empty? empty-test-files)
          (str "*_test files with no analyzer-discovered deftest: "
               (pr-str empty-test-files))))))
