(ns cfp-scheduler-killer.handlers.agent
  "Streamable-HTTP-compatible MCP endpoint for one event."
  (:require
   [cfp-scheduler-killer.agent.mcp :as mcp]
   [cfp-scheduler-killer.auth :as auth]
   [cfp-scheduler-killer.exports :as exports]
   [cfp-scheduler-killer.web.http :as http]
   [clojure.data.json :as json]
   [taoensso.timbre :as log]))

(def max-request-bytes (* 64 1024))

(defn- read-json-body
  [req]
  (let [declared (some-> (get-in req [:headers "content-length"]) Long/parseLong)]
    (when (and declared (> declared max-request-bytes))
      (throw (ex-info "MCP request is too large."
                      {:type :request-too-large :max-bytes max-request-bytes})))
    (let [body (slurp (:body req))]
      (when (> (count (.getBytes body "UTF-8")) max-request-bytes)
        (throw (ex-info "MCP request is too large."
                        {:type :request-too-large :max-bytes max-request-bytes})))
      (json/read-str body :key-fn keyword))))

(defn- request-context
  [req]
  (let [person (auth/current-person req)
        token (exports/bearer-token req)]
    {:event-slug (get-in req [:path-params :slug])
     :person person
     :token token
     :actor (or (:email person) (when token "api-key") "anonymous")
     :base-url (http/request-host req)
     :source :mcp}))

(defn handle-mcp
  [req]
  (try
    (if-let [response (mcp/handle-payload (request-context req) (read-json-body req))]
      (http/json-response response)
      {:status 202 :headers {} :body ""})
    (catch Exception e
      (log/warn :mcp-request-failed :msg (.getMessage e) :error-type (:type (ex-data e)))
      (http/json-response
        (if (= :request-too-large (:type (ex-data e))) 413 400)
        {"jsonrpc" "2.0"
         "id" nil
         "error" {"code" -32700
                  "message" (.getMessage e)}}))))
