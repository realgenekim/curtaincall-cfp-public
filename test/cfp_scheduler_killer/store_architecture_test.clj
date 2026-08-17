(ns cfp-scheduler-killer.store-architecture-test
  (:require
   [clojure.data.json :as json]
   [clojure.java.shell :as shell]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

(def ^:private store-namespaces
  #{"cfp-scheduler-killer.store"
    "cfp-scheduler-killer.store-pg"})

(def ^:private outward-prefixes
  ["cfp-scheduler-killer.handlers."
   "cfp-scheduler-killer.views."
   "cfp-scheduler-killer.web."
   "cfp-scheduler-killer.io."
   "cfp-scheduler-killer.agent."
   "cfp-scheduler-killer.middleware"
   "cfp-scheduler-killer.sse"
   "cfp-scheduler-killer.server"])

(def ^:private read-events-caller-allowlist
  #{["cfp-scheduler-killer.store" "read-events"]
    ["cfp-scheduler-killer.store" "load!"]
    ["cfp-scheduler-killer.store" "state-as-of"]})

(defn- store-usages []
  (let [{:keys [out err]}
        (shell/sh "clj-kondo" "--lint"
                  "src/cfp_scheduler_killer/store.clj"
                  "src/cfp_scheduler_killer/store_pg.clj"
                  "--config"
                  "{:analysis {:namespace-usages true} :output {:format :json}}")
        result (json/read-str out :key-fn keyword)
        usages (get-in result [:analysis :namespace-usages])]
    (is (zero? (get-in result [:summary :error]))
        (str "clj-kondo must resolve both store implementations: " err))
    (is (seq usages) "clj-kondo must emit store dependency evidence")
    usages))

(defn- read-events-callers []
  (let [{:keys [out]}
        (shell/sh "clj-kondo" "--lint" "src"
                  "--config"
                  "{:analysis {:var-usages true} :output {:format :json}}")
        result (json/read-str out :key-fn keyword)
        usages (get-in result [:analysis :var-usages])]
    (is (seq usages) "clj-kondo must emit production Var usage evidence")
    (->> usages
         (filter #(and (= "cfp-scheduler-killer.store" (:to %))
                       (= "read-events" (:name %))))
         (map (juxt :from :from-var))
         set)))

(deftest stores-cannot-acquire-delivery-or-application-adapters-test
  (let [usages (store-usages)
        analyzed (set (map :from usages))
        forbidden-edges
        (->> usages
             (filter #(store-namespaces (:from %)))
             (filter (fn [{:keys [to]}]
                       (some #(str/starts-with? (str to) %) outward-prefixes)))
             (map (juxt :from :to))
             set)]
    (testing "both production store implementations were actually analyzed"
      (is (= store-namespaces (set (filter store-namespaces analyzed)))))
    (testing "persistence remains below application and delivery adapters"
      (is (empty? forbidden-edges)
          (str "stores cannot depend on handlers, views, web transport, agent adapters, "
               "middleware, SSE, the server, or concrete provider selectors: "
               (pr-str forbidden-edges))))))

(deftest durable-event-replay-has-a-named-caller-allowlist-test
  (is (= read-events-caller-allowlist (read-events-callers))
      "wire-level replay is reserved for store startup, time travel, and store internals"))
