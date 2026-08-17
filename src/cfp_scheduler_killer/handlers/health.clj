(ns cfp-scheduler-killer.handlers.health)

(defn handle-ping
  "Lightweight, mutation-free liveness contract for Cloud Run keepalives."
  [_req]
  {:status 200
   :headers {"Content-Type" "text/plain; charset=utf-8"}
   :body "pong"})
