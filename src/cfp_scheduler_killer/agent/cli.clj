(ns cfp-scheduler-killer.agent.cli
  "`clj -X:agent` adapter over the same event command registry MCP exposes."
  (:require
   [cfp-scheduler-killer.agent.commands :as commands]
   [cfp-scheduler-killer.people :as people]
   [cfp-scheduler-killer.sinks :as sinks]
   [cfp-scheduler-killer.store :as store]
   [clojure.data.json :as json]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]))

(def help-text
  (str "cfp-scheduler-killer agent CLI\n\n"
       "One command:\n"
       "  clj -X:agent :event '\"my-event\"' :command :get_event\n"
       "  clj -X:agent :event '\"my-event\"' :command :review_coverage "
       ":actor '\"organizer@example.com\"'\n"
       "  clj -X:agent :event '\"my-event\"' :command :set_submission_status "
       ":actor '\"organizer@example.com\"' "
       ":args '{:submissionId \"id\" :status \"Accepted\" :confirm false}'\n\n"
       "Remote MCP:\n"
       "  clj -X:agent :event '\"my-event\"' :command :get_event "
       ":base-url '\"https://service.example\"' :token '\"event-api-key\"'\n\n"
       "Scenario EDN (dry-run by default; add :apply true to honor confirms):\n"
       "  clj -X:agent :scenario '\"scenario.edn\"'\n\n"
       "Output: :output :json (default) or :output :human.\n"
       "Commands: " (str/join ", " (commands/command-names))))

(defn- local-context
  [{:keys [event actor]}]
  (store/load!)
  (let [person (when actor (people/by-email (str actor)))]
    {:event-slug (str event)
     :person person
     :actor (or (:email person) (some-> actor str) "anonymous")
     :base-url (sinks/public-base-url)
     :source :cli}))

(defn- remote-call
  [{:keys [base-url event command args token]}]
  (let [post (requiring-resolve 'hato.client/post)
        endpoint (str (str/replace (str base-url) #"/$" "")
                      "/events/" event "/mcp")
        request {"jsonrpc" "2.0" "id" 1 "method" "tools/call"
                 "params" {"name" (name command) "arguments" (or args {})}}
        response (post endpoint
                       {:headers (cond-> {"content-type" "application/json"
                                          "accept" "application/json"}
                                   token (assoc "authorization" (str "Bearer " token)))
                        :body (json/write-str request :escape-slash false)
                        :as :string
                        :throw-exceptions false
                        :timeout 10000})]
    (when-not (= 200 (:status response))
      (throw (ex-info (str "Remote MCP returned HTTP " (:status response))
                      {:type :remote-http-error :status (:status response)
                       :body (:body response)})))
    (let [decoded (json/read-str (:body response) :key-fn keyword)]
      (if-let [error (:error decoded)]
        (throw (ex-info (get error :message "Remote MCP error")
                        {:type :remote-mcp-error :error error}))
        (get-in decoded [:result :structuredContent])))))

(defn execute
  "Return one transport-neutral command result without printing."
  [{:keys [base-url event command args] :as options}]
  (when-not (and event command)
    (throw (ex-info "Both :event and :command are required."
                    {:type :cli-usage})))
  (if base-url
    (remote-call options)
    (commands/invoke! (local-context options) (name command) (or args {}))))

(defn- scenario-options
  [defaults step apply?]
  (let [args (or (:args step) {})
        mutation? (= "set_submission_status" (name (:command step)))]
    (merge defaults
           {:command (:command step)
            :args (if (and mutation? (not apply?))
                    (assoc args :confirm false)
                    args)})))

(defn execute-scenario
  "Compile ordered scenario EDN into ordinary command invocations.

   Dry-run is the default. `:apply true` does not invent confirmation: it only
   honors the explicit :confirm values already present in the scenario."
  [{:keys [scenario apply] :as options}]
  (let [data (edn/read-string (slurp (io/file (str scenario))))
        defaults {:event (or (:event data) (:event options))
                  :actor (or (:actor data) (:actor options))
                  :base-url (:base-url options)
                  :token (:token options)}]
    (when-not (vector? (:commands data))
      (throw (ex-info "Scenario EDN must contain a :commands vector."
                      {:type :invalid-scenario})))
    (let [steps (mapv #(scenario-options defaults % (true? apply))
                      (:commands data))
          results (if (:base-url defaults)
                    (mapv execute steps)
                    (let [context (local-context defaults)]
                      (mapv #(commands/invoke! context
                                               (name (:command %))
                                               (:args %))
                            steps)))]
      {"scenario" (str scenario)
       "dryRun" (not (true? apply))
       "results" results})))

(defn- print-result!
  [output result]
  (case output
    :human (println (str (get result "command" "scenario") " — "
                         (if (or (get result "ok") (contains? result "results"))
                           "ok" "failed")
                         (when-let [slug (get-in result ["event" "slug"])]
                           (str " — " slug))))
    (println (json/write-str result :escape-slash false))))

(defn run
  [{:keys [help scenario output] :as options}]
  (if help
    (println help-text)
    (print-result! (or output :json)
                   (if scenario
                     (execute-scenario options)
                     (execute options)))))
