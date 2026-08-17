(ns cfp-scheduler-killer.public-projection-architecture-test
  (:require
   [clojure.data.json :as json]
   [clojure.java.shell :as shell]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

(def ^:private projection-namespaces
  #{"cfp-scheduler-killer.exports"
    "cfp-scheduler-killer.public-catalog"})

(def ^:private outward-prefixes
  ["cfp-scheduler-killer.handlers."
   "cfp-scheduler-killer.views."
   "cfp-scheduler-killer.web."
   "cfp-scheduler-killer.io."
   "cfp-scheduler-killer.middleware"
   "cfp-scheduler-killer.sse"
   "cfp-scheduler-killer.server"])

(defn- projection-usages []
  (let [{:keys [out err]}
        (shell/sh "clj-kondo" "--lint"
                  "src/cfp_scheduler_killer/exports.clj"
                  "src/cfp_scheduler_killer/public_catalog.clj"
                  "--config"
                  "{:analysis {:namespace-usages true} :output {:format :json}}")
        result (json/read-str out :key-fn keyword)]
    (is (zero? (get-in result [:summary :error]))
        (str "public projection sources must parse and resolve: " err))
    (let [usages (get-in result [:analysis :namespace-usages])]
      (is (seq usages) "clj-kondo must emit public projection dependency evidence")
      usages)))

(deftest public-projections-cannot-acquire-delivery-infrastructure-test
  (let [usages (projection-usages)
        analyzed (set (map :from usages))
        forbidden-edges
        (->> usages
             (filter #(projection-namespaces (:from %)))
             (filter (fn [{:keys [to]}]
                       (some #(str/starts-with? (str to) %) outward-prefixes)))
             (map (juxt :from :to))
             set)]
    (testing "both production projection namespaces were actually analyzed"
      (is (= projection-namespaces (set (filter projection-namespaces analyzed)))))
    (testing "public data computation remains reusable behind every delivery surface"
      (is (empty? forbidden-edges)
          (str "public projections must not depend on HTTP handlers, views, transport, "
               "streaming, the composition root, or concrete providers: "
               (pr-str forbidden-edges))))))
