(ns cfp-scheduler-killer.review-policy
  "Event-scoped review coverage policy shared by board and replay projections."
  (:require
   [cfp-scheduler-killer.store :as store]))

(defn coverage-target-for [event-id]
  (or (:coverage-target (first (store/committees-for-event event-id))) 2))
