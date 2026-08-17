(ns cfp-scheduler-killer.agent.mcp
  "Small, stateless MCP data layer over the shared command registry.

   The HTTP transport owns authentication and event scope. This namespace owns
   only JSON-RPC/MCP messages, which keeps protocol tests fast and algebraic."
  (:require
   [cfp-scheduler-killer.agent.commands :as commands]
   [clojure.data.json :as json]))

(def protocol-version "2025-06-18")
(def server-name "cfp-scheduler-killer")
(def server-version "1.0.0")

(defn- result-response
  [id result]
  {"jsonrpc" "2.0" "id" id "result" result})

(defn- error-response
  ([id code message]
   (error-response id code message nil))
  ([id code message data]
   {"jsonrpc" "2.0"
    "id" id
    "error" (cond-> {"code" code "message" message}
              data (assoc "data" (commands/json-safe data)))}))

(defn- server-capabilities
  []
  {"protocolVersion" protocol-version
   "capabilities" {"tools" {"listChanged" false}}
   "serverInfo" {"name" server-name "version" server-version}
   "instructions" (str "Tools are scoped to the event in the endpoint URL. "
                       "Public tools expose published program data; operational tools "
                       "require event membership or an event API key. Mutations require "
                       "a signed-in human and explicit confirm=true.")})

(defn- tool-result
  [value]
  {"content" [{"type" "text" "text" (json/write-str value :escape-slash false)}]
   "structuredContent" value
   "isError" false})

(defn- tool-error
  [e]
  (let [value {"ok" false
               "error" {"type" (some-> (ex-data e) :type name)
                        "message" (.getMessage e)
                        "data" (commands/json-safe (dissoc (ex-data e) :type))}}]
    {"content" [{"type" "text" "text" (json/write-str value :escape-slash false)}]
     "structuredContent" value
     "isError" true}))

(defn handle-message
  "Handle one decoded JSON-RPC message. Returns nil for notifications."
  [context message]
  (let [id (:id message)
        method (:method message)]
    (case method
      "initialize"
      (result-response id (server-capabilities))

      "server/discover"
      (result-response id (server-capabilities))

      "notifications/initialized"
      nil

      "ping"
      (result-response id {})

      "tools/list"
      (result-response id {"tools" (commands/tool-definitions)})

      "tools/call"
      (let [tool-name (get-in message [:params :name])
            arguments (get-in message [:params :arguments] {})]
        (if-not (some #{tool-name} (commands/command-names))
          (error-response id -32602 (str "Unknown tool: " tool-name)
                          {:available (commands/command-names)})
          (try
            (result-response id (tool-result (commands/invoke! context tool-name arguments)))
            (catch Exception e
              (result-response id (tool-error e))))))

      (error-response id -32601 (str "Method not found: " method)))))

(defn handle-payload
  "Handle a single MCP message or a JSON-RPC batch. Notifications disappear."
  [context payload]
  (if (vector? payload)
    (->> payload (keep #(handle-message context %)) vec)
    (handle-message context payload)))
