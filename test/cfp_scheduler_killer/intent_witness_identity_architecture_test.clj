(ns cfp-scheduler-killer.intent-witness-identity-architecture-test
  (:require
   [clojure.data.json :as json]
   [clojure.edn :as edn]
   [clojure.java.shell :as shell]
   [clojure.test :refer [deftest is]]))

(defn- registered-test-names []
  (->> (:intents (edn/read-string (slurp "docs/intent/registry.edn")))
       (mapcat :tests)
       set))

(defn- discovered-test-definition-counts []
  (let [{:keys [out]}
        (shell/sh "clj-kondo" "--lint" "test"
                  "--config"
                  "{:analysis {:var-definitions true} :output {:format :json}}")
        result (json/read-str out :key-fn keyword)]
    (->> (get-in result [:analysis :var-definitions])
         (filter :test)
         (map (comp keyword :name))
         frequencies)))

(deftest every-registered-witness-name-has-one-definition-test
  (let [definition-counts (discovered-test-definition-counts)]
    (doseq [test-name (sort (registered-test-names))]
      (is (= 1 (get definition-counts test-name 0))
          (str "registered witness " test-name " has "
               (get definition-counts test-name 0)
               " deftest definitions; unqualified registry names require exactly one "
               "definition or they can resolve to the wrong behavioral witness.")))))
