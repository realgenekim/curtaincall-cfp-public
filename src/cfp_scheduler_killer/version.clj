(ns cfp-scheduler-killer.version
  "Build/version stamp — renders 'ver <sha> (deployed Xm ago)' in your footer.
   Pattern origin: joe-payne-app (esr-dashboard); also used by video-library-admin.
   Full write-up: docs/version-stamp-pattern.md

   Works OUT OF THE BOX in dev (falls back to live `git rev-parse` + now).
   To make deployed builds self-identify, UNCOMMENT these two lines at the top
   of your Makefile `build` target (and gitignore the two files):

       # @git rev-parse --short HEAD > resources/build-sha.txt
       # @date -u +%Y-%m-%dT%H:%M:%SZ > resources/build-time.txt

   Then render (version-str) somewhere subtle, e.g.:
       [:span {:style \"font-size:11px;color:#bbb\"} (version/version-str)]"
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]))

(def git-sha
  (or (try (some-> (io/resource "build-sha.txt") slurp str/trim) (catch Exception _ nil))
      (try (str/trim (slurp (.getInputStream (.exec (Runtime/getRuntime)
                                                    (into-array ["git" "rev-parse" "--short" "HEAD"])))))
           (catch Exception _ nil))
      "dev"))

(def build-time
  (or (try (some-> (io/resource "build-time.txt") slurp str/trim java.time.Instant/parse)
           (catch Exception _ nil))
      (java.time.Instant/now)))

(def ^:private build-time-formatter
  (-> (java.time.format.DateTimeFormatter/ofPattern
        "MMM d, uuuu 'at' HH:mm:ss 'UTC'" java.util.Locale/ENGLISH)
      (.withZone java.time.ZoneOffset/UTC)))

(defn build-time-str []
  (.format build-time-formatter build-time))

(defn time-ago [^java.time.Instant inst]
  (let [mins (.toMinutes (java.time.Duration/between inst (java.time.Instant/now)))]
    (cond
      (< mins 1)    "just now"
      (< mins 60)   (str mins "m ago")
      (< mins 1440) (str (quot mins 60) "h " (mod mins 60) "m ago")
      :else         (str (quot mins 1440) "d ago"))))

(defn version-str []
  (str "ver " git-sha " (deployed " (time-ago build-time) ")"))
