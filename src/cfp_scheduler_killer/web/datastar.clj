(ns cfp-scheduler-killer.web.datastar
  "Datastar request parsing shared by server-rendered interaction handlers."
  (:require
   [clojure.data.json :as json]
   [clojure.java.io :as io]
   [starfederation.datastar.clojure.adapter.http-kit :as hk]
   [starfederation.datastar.clojure.api :as d*]
   [taoensso.timbre :as log]))

(defn signals
  "Read Datastar signals from the request's JSON body, returning an empty map
   when the body is absent or malformed."
  [req]
  (try
    (let [sigs (d*/get-signals req)]
      (cond
        (nil? sigs) {}
        (string? sigs) (json/read-str sigs :key-fn keyword)
        :else (with-open [r (io/reader sigs)]
                (json/read r :key-fn keyword))))
    (catch Exception e
      (log/warn :preview-unparseable-signals :msg (.getMessage e))
      {})))

(defn sse-fragment-response
  "A one-shot Datastar response: patch one region, then close.

   Used by the live time-travel scrub. It cannot go over the shared board SSE
   connection, because that broadcasts to everyone watching the event — one
   organizer dragging a slider must not move anybody else's screen."
  [req selector html]
  (hk/->sse-response
    req
    {hk/on-open
     (fn [sse]
       (d*/with-open-sse sse
         (d*/patch-elements! sse html
                             {d*/selector selector
                              d*/patch-mode d*/pm-outer})))}))
