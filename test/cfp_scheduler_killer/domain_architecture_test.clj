(ns cfp-scheduler-killer.domain-architecture-test
  (:require
   [clojure.data.json :as json]
   [clojure.java.shell :as shell]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

(def ^:private domain-prefix "cfp-scheduler-killer.domain.")

(defn- clj-kondo-analysis
  []
  (let [{:keys [out err]}
        (shell/sh "clj-kondo" "--lint" "src/cfp_scheduler_killer/domain"
                  "--config"
                  "{:analysis {:namespace-usages true} :output {:format :json}}")
        result (json/read-str out :key-fn keyword)]
    (is (zero? (get-in result [:summary :error]))
        (str "clj-kondo must resolve domain.* without errors: " err))
    result))

(defn- effect-namespace?
  [namespace-name]
  (or (= namespace-name "cfp-scheduler-killer.store")
      (= namespace-name "cfp-scheduler-killer.server")
      (= namespace-name "cfp-scheduler-killer.middleware")
      (str/starts-with? namespace-name "cfp-scheduler-killer.handlers.")
      (str/starts-with? namespace-name "cfp-scheduler-killer.views.")
      (str/starts-with? namespace-name "cfp-scheduler-killer.io.")))

(deftest domain-namespaces-have-no-effect-dependencies-test
  (let [usages (get-in (clj-kondo-analysis) [:analysis :namespace-usages])
        forbidden-edges (->> usages
                             (filter #(str/starts-with? (:from %) domain-prefix))
                             (filter #(effect-namespace? (:to %)))
                             (map (juxt :from :to))
                             set)]
    (testing "pure decisions cannot acquire store, transport, view, or provider dependencies"
      (is (empty? forbidden-edges)
          (str "effect dependencies from domain.*: " (pr-str forbidden-edges))))))

(deftest domain-namespaces-cannot-bypass-ports-with-external-io
  (let [usages (get-in (clj-kondo-analysis) [:analysis :namespace-usages])
        effect-prefixes ["clojure.java.io"
                         "clojure.java.shell"
                         "next.jdbc"
                         "hato."
                         "org.httpkit."
                         "postal."
                         "com.cognitect.aws."
                         "gcp-secrets."]
        forbidden-edges
        (->> usages
             (filter #(str/starts-with? (:from %) domain-prefix))
             (filter (fn [{:keys [to]}]
                       (some #(str/starts-with? (str to) %) effect-prefixes)))
             (map (juxt :from :to))
             set)]
    (is (empty? forbidden-edges)
        (str "domain.* must express decisions as data and use application ports, "
             "not filesystem, shell, HTTP, JDBC, mail, or cloud SDKs directly: "
             (pr-str forbidden-edges)))))

(deftest folds-projection-cannot-acquire-external-io
  (let [usages (get-in (clj-kondo-analysis) [:analysis :namespace-usages])
        effect-prefixes ["clojure.java.io"
                         "clojure.java.shell"
                         "next.jdbc"
                         "hato."
                         "org.httpkit."
                         "postal."
                         "com.cognitect.aws."
                         "gcp-secrets."]
        forbidden-targets
        (->> usages
             (filter #(= "cfp-scheduler-killer.folds" (:from %)))
             (map :to)
             (filter (fn [to]
                       (some #(str/starts-with? (str to) %) effect-prefixes)))
             set)]
    (is (empty? forbidden-targets)
        (str "folds must remain a pure projection and cannot acquire external I/O: "
             (pr-str forbidden-targets)))))
