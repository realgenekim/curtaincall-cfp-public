(ns cfp-scheduler-killer.io-architecture-test
  (:require
   [clojure.data.json :as json]
   [clojure.java.io :as io]
   [clojure.java.shell :as shell]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

(def provider-prefixes
  ["cfp-scheduler-killer.io.email.smtp"
   "cfp-scheduler-killer.io.email.aws-ses"
   "cfp-scheduler-killer.io.email.resend"
   "cfp-scheduler-killer.io.email.cloudflare"
   "cfp-scheduler-killer.io.blob.local"
   "cfp-scheduler-killer.io.blob.gcs"])

(def pure-prefixes
  ["cfp-scheduler-killer.views"
   "cfp-scheduler-killer.folds"
   "cfp-scheduler-killer.domain"])

(defn- namespace-usages []
  (let [source-files (->> (file-seq (io/file "src/cfp_scheduler_killer"))
                          (filter #(.isFile %))
                          (map str)
                          (filter #(str/ends-with? % ".clj"))
                          sort)
        {:keys [out err]} (apply shell/sh
                                 (concat ["clj-kondo" "--lint"]
                                         source-files
                                         ["--config"
                                          "{:analysis {:namespace-usages true} :output {:format :json}}"]))
        result (json/read-str out :key-fn keyword)
        usages (get-in result [:analysis :namespace-usages])]
    (is (seq usages)
        (str "clj-kondo must emit dependency evidence: " err))
    usages))

(defn- starts-with-any? [value prefixes]
  (some #(str/starts-with? (str value) %) prefixes))

(defn- source-files-containing [needle]
  (->> (file-seq (io/file "src/cfp_scheduler_killer"))
       (filter #(.isFile %))
       (filter #(str/ends-with? (.getName %) ".clj"))
       (filter #(str/includes? (slurp %) needle))
       (map #(.getPath %))
       set))

(deftest only-io-port-namespaces-may-select-provider-adapters
  (let [usages (namespace-usages)
        allowed-selectors ["cfp-scheduler-killer.io.email"
                           "cfp-scheduler-killer.io.blob"]
        forbidden-edges (->> usages
                             (filter #(starts-with-any? (:to %) provider-prefixes))
                             (remove #(starts-with-any? (:from %) allowed-selectors))
                             (map (juxt :from :to))
                             set)]
    (is (empty? forbidden-edges)
        (str "concrete providers may be selected only inside application-owned "
             "io.email* or io.blob* port namespaces: " (pr-str forbidden-edges)))))

(deftest handlers-choose-application-ports-not-provider-adapters
  (let [usages (namespace-usages)
        forbidden-edges (->> usages
                             (filter #(str/starts-with?
                                       (str (:from %))
                                       "cfp-scheduler-killer.handlers."))
                             (filter #(starts-with-any? (:to %) provider-prefixes))
                             (map (juxt :from :to))
                             set)]
    (is (empty? forbidden-edges)
        (str "handlers must call application-owned ports and cannot choose concrete "
             "email or blob providers: " (pr-str forbidden-edges)))))

(deftest application-adapters-cannot-select-the-postgres-implementation
  (let [usages (namespace-usages)
        protected-prefixes ["cfp-scheduler-killer.domain."
                            "cfp-scheduler-killer.views."
                            "cfp-scheduler-killer.handlers."
                            "cfp-scheduler-killer.web."
                            "cfp-scheduler-killer.middleware"
                            "cfp-scheduler-killer.agent."]
        forbidden-edges (->> usages
                             (filter #(= "cfp-scheduler-killer.store-pg"
                                         (str (:to %))))
                             (filter #(starts-with-any? (:from %) protected-prefixes))
                             (map (juxt :from :to))
                             set)]
    (is (empty? forbidden-edges)
        (str "application and transport adapters must use the store abstraction and "
             "cannot select the PostgreSQL implementation: "
             (pr-str forbidden-edges)))))

(deftest provider-adapters-cannot-reach-back-into-application-layers
  (let [usages (namespace-usages)
        application-prefixes ["cfp-scheduler-killer.handlers."
                              "cfp-scheduler-killer.views."
                              "cfp-scheduler-killer.server"
                              "cfp-scheduler-killer.store"
                              "cfp-scheduler-killer.middleware"
                              "cfp-scheduler-killer.sse"]
        forbidden-edges (->> usages
                             (filter #(starts-with-any? (:from %) provider-prefixes))
                             (filter #(starts-with-any? (:to %) application-prefixes))
                             (map (juxt :from :to))
                             set)]
    (is (empty? forbidden-edges)
        (str "provider adapters must stay at the infrastructure edge and cannot "
             "reach back into application layers: " (pr-str forbidden-edges)))))

(deftest providers-stay-behind-application-owned-ports
  (let [usages (namespace-usages)
        pure-provider-edges (->> usages
                                 (filter #(starts-with-any? (:from %) pure-prefixes))
                                 (filter #(starts-with-any? (:to %) provider-prefixes))
                                 (map (juxt :from :to))
                                 set)
        gcp-users (->> usages
                       (filter #(= "gcp-secrets.main" (:to %)))
                       (map :from)
                       set)]
    (testing "views, folds, and pure decisions never choose a provider"
      (is (empty? pure-provider-edges)
          (str "provider dependencies escaped their ports: "
               (pr-str pure-provider-edges))))
    (testing "Postal belongs only to the SMTP adapter"
      (is (= #{"src/cfp_scheduler_killer/io/email/smtp.clj"}
             (source-files-containing "postal.core"))))
    (testing "the GCP library remains at the two approved infrastructure edges"
      (is (= #{"cfp-scheduler-killer.io.blob.gcs"
               "cfp-scheduler-killer.secrets"}
             gcp-users)))
    (testing "GCS access-token acquisition belongs only to the blob adapter"
      (is (= #{"src/cfp_scheduler_killer/io/blob/gcs.clj"}
             (source-files-containing "gcp/get-token"))))))
