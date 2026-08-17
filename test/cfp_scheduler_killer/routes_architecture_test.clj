(ns cfp-scheduler-killer.routes-architecture-test
  (:require
   [cfp-scheduler-killer.folds :as folds]
   [cfp-scheduler-killer.server :as server]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

(def ^:private server-file
  (io/file "src/cfp_scheduler_killer/server.clj"))

(def ^:private handler-dir
  (io/file "src/cfp_scheduler_killer/handlers"))

(def ^:private expected-handler-namespaces
  '#{cfp-scheduler-killer.handlers.auth
     cfp-scheduler-killer.handlers.agent
     cfp-scheduler-killer.handlers.announce
     cfp-scheduler-killer.handlers.board
     cfp-scheduler-killer.handlers.crm
     cfp-scheduler-killer.handlers.communications
     cfp-scheduler-killer.handlers.dashboard
     cfp-scheduler-killer.handlers.dev
     cfp-scheduler-killer.handlers.events
     cfp-scheduler-killer.handlers.exports
     cfp-scheduler-killer.handlers.files
     cfp-scheduler-killer.handlers.forms
     cfp-scheduler-killer.handlers.health
     cfp-scheduler-killer.handlers.integrations
     cfp-scheduler-killer.handlers.manifesto
     cfp-scheduler-killer.handlers.portal
     cfp-scheduler-killer.handlers.public-api
     cfp-scheduler-killer.handlers.public-cfp
     cfp-scheduler-killer.handlers.public-widgets
     cfp-scheduler-killer.handlers.resource-pages
     cfp-scheduler-killer.handlers.replay
     cfp-scheduler-killer.handlers.review-plan
     cfp-scheduler-killer.handlers.schedule
     cfp-scheduler-killer.handlers.speaker-tasks cfp-scheduler-killer.handlers.speakers
     cfp-scheduler-killer.handlers.zoo})

(defn- source [file]
  (slurp file))

(defn- ns-name-in [file]
  (with-open [reader (java.io.PushbackReader. (io/reader file))]
    (second (read reader))))

(defn- ns-requires-in [file]
  (with-open [reader (java.io.PushbackReader. (io/reader file))]
    (->> (read reader)
         (tree-seq coll? seq)
         (filter vector?)
         (keep first)
         (filter symbol?)
         set)))

(defn- handler-files []
  (->> (.listFiles handler-dir)
       (filter #(str/ends-with? (.getName %) ".clj"))
       (sort-by #(.getName %))))

(defn- route-handler-vars [x]
  (cond
    (map? x) (concat (keep (fn [[k v]] (when (= :handler k) v)) x)
                     (mapcat route-handler-vars (vals x)))
    (sequential? x) (mapcat route-handler-vars x)
    :else []))

(deftest composition-root-stays-small-and-body-free
  (let [server-source (source server-file)]
    (testing "server is only the composition root"
      (is (<= (count (str/split-lines server-source)) 400))
      (is (not (re-find #"\(defn-?\s+handle-" server-source)))
      (is (= expected-handler-namespaces
             (set (map ns-name-in (handler-files))))))
    (testing "every route points at a resolved Var"
      (let [handlers (route-handler-vars (server/make-routes))]
        (is (seq handlers))
        (is (every? var? handlers))
        (is (every? bound? handlers))))))

(deftest handlers-point-inward-not-sideways-or-backward
  (doseq [file (handler-files)]
    (let [handler-source (source file)
          requires (ns-requires-in file)
          label (.getPath file)]
      (testing label
        (is (not (contains? requires 'cfp-scheduler-killer.server)))
        (is (empty? (filter #(str/starts-with?
                               (str %) "cfp-scheduler-killer.handlers.")
                            requires)))
        (is (empty? (filter #(str/starts-with? (str %) "reitit")
                            requires)))
        (is (not (re-find #"\(defn-?\s+make-routes" handler-source)))))))

(deftest web-helpers-never-become-a-second-composition-root
  (let [web-dir (io/file "src/cfp_scheduler_killer/web")
        forbidden-prefixes ["cfp-scheduler-killer.handlers."
                            "cfp-scheduler-killer.io."]
        forbidden-exact '#{cfp-scheduler-killer.server
                           cfp-scheduler-killer.middleware
                           cfp-scheduler-killer.sse}
        offenders
        (->> (.listFiles web-dir)
             (filter #(str/ends-with? (.getName %) ".clj"))
             (mapcat (fn [file]
                       (for [required (ns-requires-in file)
                             :when (or (forbidden-exact required)
                                       (some #(str/starts-with? (str required) %)
                                             forbidden-prefixes))]
                         [(.getPath file) required])))
             set)]
    (is (empty? offenders)
        (str "web.* helpers must remain inward-facing response/event utilities, "
             "not a second composition root: " (pr-str offenders)))))

(deftest middleware-cannot-acquire-endpoint-or-provider-layers
  (let [requires (ns-requires-in
                   (io/file "src/cfp_scheduler_killer/middleware.clj"))
        forbidden-prefixes ["cfp-scheduler-killer.handlers."
                            "cfp-scheduler-killer.views."
                            "cfp-scheduler-killer.io."
                            "cfp-scheduler-killer.sse"]
        forbidden-targets (->> requires
                               (filter (fn [target]
                                         (some #(str/starts-with? (str target) %)
                                               forbidden-prefixes)))
                               set)]
    (is (empty? forbidden-targets)
        (str "middleware owns cross-cutting request concerns and cannot acquire "
             "handlers, views, concrete I/O adapters, or SSE: "
             (pr-str forbidden-targets)))))

(deftest only-the-process-entrypoint-may-require-the-server
  (let [src-dir (io/file "src/cfp_scheduler_killer")
        offenders
        (->> (file-seq src-dir)
             (filter #(.isFile %))
             (filter #(str/ends-with? (.getName %) ".clj"))
             (remove #(= "core.clj" (.getName %)))
             (filter #(contains? (ns-requires-in %)
                                 'cfp-scheduler-killer.server))
             (map #(.getPath %))
             set)]
    (is (empty? offenders)
        (str "only the process entrypoint may require the server composition root; "
             "all other namespaces must depend inward: " (pr-str offenders)))))

(deftest folds-own-the-pure-projection
  (let [folds-source (source (io/file "src/cfp_scheduler_killer/folds.clj"))
        store-source (source (io/file "src/cfp_scheduler_killer/store.clj"))
        actual-methods (set (keys (methods folds/fold-event)))
        required-methods
        #{:default
          "agenda.published" "api-key.created" "api-key.revoked"
          "auth.session-ended" "auth.session-started"
          "block.added" "block.removed" "comment.added" "committee.created"
          "comms.failed" "comms.rendered" "comms.sent"
          "event.archived" "event.created" "event.unarchived" "event.updated"
          "form.installed" "form.reviewed" "form.updated"
          "member.added" "member.removed"
          "person.created" "person.default-event-set" "person.profile-updated"
          "rating.set" "replay.marked"
          "review-round.created"
          "review-round.updated"
          "review-round.pool-set"
          "review-round.scorecard-set"
          "review-round.activated"
          "review-round.advanced"
          "review-round.retired"
          "review.blind-mode-set"
          "review.presenter-visibility-set"
          "reviewer.assigned" "reviewer.nudge-recorded" "reviewer.recused" "reviewer.unassigned" "reviewer.unrecused"
          "room.added" "room.removed" "room.renamed"
          "schedule.locked" "schedule.unlocked"
          "scorecard.criterion-added" "scorecard.criterion-retired"
          "scorecard.criterion-updated" "scorecard.value-set"
          "sink.registered" "sink.removed"
          "slot.assigned" "slot.cleared"
          "submission.answers-updated" "submission.content-status-changed"
          "submission.created" "submission.notified"
          "submission.priority-toggled" "submission.status-changed"
          "task.completed" "task.installed"
          "file.version-added" "file.comment-added"}]
    (testing "folds retains every required projection method and no engine edge"
      (is (every? actual-methods required-methods))
      (is (not (str/includes? folds-source "cfp-scheduler-killer.store")))
      (is (not (str/includes? folds-source "clojure.java.io")))
      (is (not (str/includes? folds-source "append!"))))
    (testing "store depends one-way on folds and owns no projection methods"
      (is (str/includes? store-source
                         "[cfp-scheduler-killer.folds :as folds]"))
      (is (not (str/includes? store-source "(defmulti fold-event")))
      (is (not (str/includes? store-source "(defmethod fold-event"))))))
