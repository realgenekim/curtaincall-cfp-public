(ns cfp-scheduler-killer.dev-warmup
  (:require
   [clojure.string :as str]
   [taoensso.timbre :as log]))

(defonce load-state (atom :ready))

(defn start-async-load! [load-fn]
  (reset! load-state :loading)
  (future
    (try
      (load-fn)
      (reset! load-state :ready)
      (log/info :dev-async-load-ready)
      (catch Throwable t
        (reset! load-state t)
        (log/error :dev-async-load-failed :msg (.getMessage t) :error t)))))

(defn boot-load!
  "The one boot-time load decision: dev (without DEMO_MODE, whose seed path
   needs a folded store before bind) loads asynchronously behind the warming
   page; every other mode loads synchronously before bind, skipping the load
   entirely when the seed path already folded it."
  [dev? loaded? load-fn]
  (if (and dev? (nil? (System/getenv "DEMO_MODE")))
    (start-async-load! load-fn)
    (when-not (loaded?) (load-fn))))

(defn- escape-html [value]
  (str/escape (str value) {\& "&amp;" \< "&lt;" \> "&gt;" \" "&quot;" \' "&#39;"}))

(defn- warming-response []
  {:status 503
   :headers {"Content-Type" "text/html; charset=utf-8"}
   :body (str "<!doctype html><html><head><meta charset=\"utf-8\">"
              "<meta http-equiv=\"refresh\" content=\"2\">"
              "<title>Store warming</title></head><body>"
              "<h1>Store warming — this page reloads automatically</h1>"
              "</body></html>")})

(defn- failed-response [error]
  {:status 500
   :headers {"Content-Type" "text/html; charset=utf-8"}
   :body (str "<!doctype html><html><head><meta charset=\"utf-8\">"
              "<title>Store load failed</title></head><body>"
              "<h1>Store load failed</h1><p>"
              (escape-html (.getMessage error))
              "</p></body></html>")})

(defn wrap-warmup [handler]
  (fn [request]
    (if (#{"/ping" "/dev/reload-check"} (:uri request))
      (handler request)
      (let [state @load-state]
        (cond
          (= :loading state) (warming-response)
          (instance? Throwable state) (failed-response state)
          :else (handler request))))))
