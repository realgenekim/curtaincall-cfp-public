(ns datastar-kit.ds-test
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [datastar-kit.ds :as ds]))

(def ^:private app-lifetime-options
  "{openWhenHidden:false,retry:'always',retryMaxCount:1000000}")

(deftest persistent-sse-mounts-own-the-lifecycle-policy
  (testing "an arbitrary stream URL gets the safe app-lifetime policy"
    (is (= {:data-star-init
            (str "@get('/portal/stream'," app-lifetime-options ")")}
           (ds/sse-mount-url "/portal/stream"))))
  (testing "the organizer convenience helper uses the same constructor"
    (is (= (ds/sse-mount-url "/api/sse?event-id=event-123")
           (ds/sse-mount "event-123")))))

(deftest live-scrub-owns-continuous-one-shot-wiring
  (is (= {:data-star-bind:atidx ""
          :data-star-on:input__throttle.150ms
          "@get('/events/demo/board/fragment?at-index=' + $atidx)"}
         (ds/live-scrub :atidx "/events/demo/board/fragment?at-index=")))
  (is (= {:data-star-bind:scrub ""
          :data-star-on:input__throttle.75ms
          "@get('/fragment?at=' + $scrub)"}
         (ds/live-scrub :scrub "/fragment?at=" 75)))
  (is (thrown? AssertionError
               (ds/live-scrub :at-index "/fragment?at="))))

(deftest views-cannot-hand-spell-persistent-sse-mounts
  (let [offenders
        (->> (file-seq (io/file "src"))
             (filter #(.isFile %))
             (filter #(str/ends-with? (.getName %) ".clj"))
             (remove #(= "src/datastar_kit/ds.clj" (.getPath %)))
             (keep (fn [file]
                     (when (str/includes? (slurp file) ":data-star-init")
                       (.getPath file))))
             vec)]
    (is (empty? offenders)
        (str "Persistent SSE mounts must go through datastar-kit.ds: " offenders))))

(deftest initially-hidden-tabs-do-not-open-a-stream
  (let [bundle (slurp (io/resource "public/vendor/datastar-aliased.js"))]
    (is (re-find #"h\|\|!document\.hidden" bundle))))
