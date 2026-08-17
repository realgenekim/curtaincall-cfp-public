(ns cfp-scheduler-killer.view-architecture-test
  "Permanent dependency guards for the decomposed view namespaces."
  (:require
   [clojure.data.json :as json]
   [clojure.java.io :as io]
   [clojure.java.shell :as shell]
   [clojure.set :as set]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

(def ^:private view-prefix "cfp-scheduler-killer.views.")

(def ^:private expected-view-namespaces
  (set (map #(str view-prefix %)
            ["announce" "auth" "avatar" "committee" "communications" "dashboard"
             "event-setup" "form-builder" "form-controls" "format" "homepage-copy"
             "crm" "embed" "files" "integrations" "live-drafts" "log" "manifesto"
             "my-schedule" "organizer-layout" "people" "personal-schedule" "pipeline" "portal"
             "policy" "public-cfp" "public-cfps" "public-widgets" "replay" "review" "review-assignment"
             "reviewer-progress" "reviewer-queue" "schedule" "shell"
             ;; review-board rebuild 2026-08-16, Mayor-swept, pending GENEDEV
             ;; ratification: the one-page board's row renderer was extracted
             ;; out of views.review into its own namespace.
             "compact-person" "resource-pages" "speaker-tasks" "speakers" "submission-content" "submission-row" "zoo"])))

(def ^:private foundation-namespaces
  (set (map #(str view-prefix %)
            ["avatar" "form-controls" "format" "homepage-copy" "live-drafts"
             "organizer-layout" "shell"])))

(def ^:private public-namespaces
  (set (map #(str view-prefix %)
            ["auth" "portal" "public-cfp" "public-cfps" "public-widgets"])))

(defn- clj-kondo-analysis []
  ;; Pass the .clj files EXPLICITLY, never the directory: clj-kondo v2023's
  ;; directory walk silently returns 0 files in some checkouts (bit us in a
  ;; git worktree 2026-08-10; mothership's analysis.clj documents the same
  ;; bug). A glob is immune, and the empty-glob guard below makes a silent
  ;; no-files run impossible either way.
  (let [files (->> (file-seq (io/file "src/cfp_scheduler_killer/views"))
                   (filter #(str/ends-with? (.getName %) ".clj"))
                   (map #(.getPath %))
                   sort)
        _ (is (seq files) "found no view files to lint — wrong working directory?")
        {:keys [out err]}
        (apply shell/sh
               (concat ["clj-kondo" "--lint"]
                       files
                       ["--config"
                        "{:analysis {:namespace-usages true :namespace-definitions true} :output {:format :json}}"]))
        result (json/read-str out :key-fn keyword)]
    (is (zero? (get-in result [:summary :error]))
        (str "clj-kondo must resolve the view graph without errors: " err))
    result))

(defn- acyclic? [edges]
  (loop [remaining (set (mapcat identity edges))
         edges (set edges)]
    (if (empty? remaining)
      true
      (let [targets (set (map second edges))
            roots (set/difference remaining targets)]
        (if (empty? roots)
          false
          (recur (set/difference remaining roots)
                 (set (remove (comp roots first) edges))))))))

(defn- source-forms [file]
  (with-open [reader (java.io.PushbackReader. (io/reader file))]
    (doall
      (take-while #(not= ::eof %)
                  (repeatedly #(read {:eof ::eof} reader))))))

(defn- hiccup-key-count [file]
  (->> (source-forms file)
       (mapcat #(tree-seq coll? seq %))
       (filter #(and (vector? %)
                     (map? (second %))
                     (contains? (second %) :key)))
       count))

(deftest views-cannot-acquire-request-or-streaming-infrastructure
  (let [usages (get-in (clj-kondo-analysis) [:analysis :namespace-usages])
        infrastructure-prefixes ["cfp-scheduler-killer.handlers."
                                 "cfp-scheduler-killer.middleware"
                                 "cfp-scheduler-killer.sse"]
        forbidden-edges
        (->> usages
             (filter #(str/starts-with? (:from %) view-prefix))
             (filter (fn [{:keys [to]}]
                       (some #(str/starts-with? (str to) %)
                             infrastructure-prefixes)))
             (map (juxt :from :to))
             set)]
    (is (empty? forbidden-edges)
        (str "views must render data passed inward and cannot acquire handlers, "
             "middleware, or SSE infrastructure: " (pr-str forbidden-edges)))))

(deftest server-rendered-hiccup-never-emits-react-keys-test
  (let [offenders (->> (file-seq (io/file "src/cfp_scheduler_killer/views"))
                       (filter #(str/ends-with? (.getName %) ".clj"))
                       (keep (fn [file]
                               (let [n (hiccup-key-count file)]
                                 (when (pos? n)
                                   [(.getPath file) n]))))
                       (into (sorted-map)))]
    (is (empty? offenders)
        (str "Hiccup attribute maps must not contain React-only :key entries: "
             offenders))))

(deftest public-speaker-consumers-never-read-the-raw-announced-ledger-test
  (doseq [file ["src/cfp_scheduler_killer/server.clj"
                "src/cfp_scheduler_killer/exports.clj"
                "src/cfp_scheduler_killer/handlers/public_widgets.clj"
                "src/cfp_scheduler_killer/views/public_cfp.clj"
                "src/cfp_scheduler_killer/views/public_widgets.clj"]]
    (is (not (str/includes? (slurp file) "[:settings :announced-speakers]"))
        (str file " must use the named speaker projection"))))

(deftest view-namespace-architecture-test
  (let [analysis (:analysis (clj-kondo-analysis))
        definitions (get analysis :namespace-definitions)
        usages (get analysis :namespace-usages)
        view-namespaces (->> definitions
                             (map :name)
                             (filter #(str/starts-with? % view-prefix))
                             set)
        view-edges (->> usages
                        (filter #(and (str/starts-with? (:from %) view-prefix)
                                      (str/starts-with? (:to %) view-prefix)))
                        (map (juxt :from :to))
                        set)
        allowed-view-targets (conj foundation-namespaces
                                   (str view-prefix "review")
                                   ;; shared featured-card renderer (2026-08-11):
                                   ;; views.public-cfp -> views.public-widgets and
                                   ;; views.auth -> views.public-widgets
                                   (str view-prefix "public-widgets")
                                   ;; pipeline strip + cascade (4d3, 2026-08-11):
                                   ;; board/inform/announce all render it
                                   (str view-prefix "pipeline")
                                   ;; Extracted, reusable view components. They
                                   ;; remain acyclic and own no product shell.
                                   (str view-prefix "personal-schedule")
                                   (str view-prefix "policy")
                                   (str view-prefix "review-assignment")
                                   (str view-prefix "review-plan")
                                   (str view-prefix "reviewer-progress")
                                   (str view-prefix "scorecard")
                                   (str view-prefix "submission-content")
                                   ;; review-board rebuild 2026-08-16,
                                   ;; Mayor-swept, pending GENEDEV ratification:
                                   ;; the one-page board's row renderer, shared
                                   ;; by views.review, views.log, and
                                   ;; views.people. It depends only on
                                   ;; foundations + reviewer-progress +
                                   ;; scorecard, so the graph stays acyclic and
                                   ;; it owns no product shell.
                                   (str view-prefix "submission-row")
                                   ;; Dense organizer ledgers share one small
                                   ;; face + name identity renderer.
                                   (str view-prefix "compact-person"))]
    (testing "the monolith cannot grow back"
      (is (not (.exists (io/file "src/cfp_scheduler_killer/views.clj"))))
      (is (= expected-view-namespaces view-namespaces)))
    (testing "product surfaces depend only on foundations or review"
      (is (every? (comp allowed-view-targets second) view-edges)))
    (testing "public surfaces never acquire organizer chrome"
      (is (not-any? (fn [[from to]]
                      (and (public-namespaces from)
                           (= (str view-prefix "organizer-layout") to)))
                    view-edges)))
    (testing "view dependencies never point at the server"
      (is (not-any? #(and (str/starts-with? (:from %) view-prefix)
                          (= "cfp-scheduler-killer.server" (:to %)))
                    usages)))
    (testing "the resolved view graph has no cycles"
      (is (acyclic? view-edges)))))

(deftest presenter-policy-markup-has-one-view-owner-test
  (let [view-root (io/file "src/cfp_scheduler_killer/views")
        owners (->> (file-seq view-root)
                    (filter #(.isFile %))
                    (filter #(str/ends-with? (.getName %) ".clj"))
                    (filter #(str/includes? (slurp %)
                                            ":aside.ui.info.message.presenter-visibility-policy"))
                    (map #(.getPath %))
                    set)]
    (is (= #{"src/cfp_scheduler_killer/views/policy.clj"} owners))))
