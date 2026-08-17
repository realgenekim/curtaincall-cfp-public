(ns user
  "Development namespace for REPL utilities.
  This namespace is automatically loaded when you start a REPL."
  (:require [clojure.tools.namespace.repl :refer [refresh]]
            [cfp-scheduler-killer.core :as core]))

(defn reset
  "Reload all changed namespaces."
  []
  (refresh))

(comment
  ;; Quick test of core functionality
  (core/greet "REPL")

  ;; Reload changed code
  (reset)
  )

(println "Welcome to the project REPL!")
(println "Useful commands:")
(println "  (reset)         - Reload all changed namespaces")
(println "  (core/greet x)  - Try the greet function")
