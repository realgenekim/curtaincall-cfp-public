(ns analysis
  "Tiny REPL façade for the production telemetry analysis namespace."
  (:require
   [cfp-scheduler-killer.analysis :as telemetry]))

(def read-events telemetry/read-events)
(def filter-date telemetry/filter-date)
(def event-breakdown telemetry/event-breakdown)
(def session-phases telemetry/session-phases)
(def journeys telemetry/journeys)
(def journey-summary telemetry/journey-summary)
(def export-jsonl! telemetry/export-jsonl!)
