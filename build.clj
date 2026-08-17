(ns build
  "Build script — uses thin-jar-build library.

     clojure -T:build thin-build    # Full thin build (clean + deps + jar)
     clojure -T:build clean         # Clean target/
     clojure -T:build copy-deps     # Copy dependency JARs to target/lib/
     clojure -T:build thin-jar      # Build thin app JAR only"
  (:require [thin-jar.build :as tb]))

(def config
  {:jar-file "target/cfp-scheduler-killer.jar"
   :ns-compile ['cfp-scheduler-killer.core]
   :src-dirs ["src" "resources"]})

(defn clean [_] (tb/clean config))
(defn copy-deps [_] (tb/copy-deps config))
(defn thin-jar [_] (tb/thin-jar config))
(defn thin-build [_] (tb/thin-build config))
