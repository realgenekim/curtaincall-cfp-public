(ns cfp-scheduler-killer.agent-test
  (:require
   [cfp-scheduler-killer.agent.cli :as cli]
   [cfp-scheduler-killer.agent.commands :as commands]
   [cfp-scheduler-killer.agent.mcp :as mcp]
   [cfp-scheduler-killer.auth :as auth]
   [cfp-scheduler-killer.committees :as committees]
   [cfp-scheduler-killer.events :as events]
   [cfp-scheduler-killer.exports :as exports]
   [cfp-scheduler-killer.inform :as inform]
   [cfp-scheduler-killer.review-plan :as review-plan]
   [cfp-scheduler-killer.reviews :as reviews]
   [cfp-scheduler-killer.server :as server]
   [cfp-scheduler-killer.sinks :as sinks]
   [cfp-scheduler-killer.store :as store]
   [cfp-scheduler-killer.submissions :as submissions]
   [cfp-scheduler-killer.test-helpers :refer [with-temp-store]]
   [clojure.data.json :as json]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [ring.mock.request :as mock])
  (:import
   (java.time LocalDate LocalDateTime)))

(use-fixtures :each with-temp-store)

(defn- create-event!
  [slug name chair-email]
  (let [event (events/create-event!
                {:name name :slug slug :tz "America/New_York"
                 :starts-on (LocalDate/of 2026 10 14)
                 :ends-on (LocalDate/of 2026 10 15)
                 :location "Charlotte, NC"
                 :website-url "https://example.com"
                 :support-email "support@example.com"
                 :cfp-opens-at (LocalDateTime/of 2020 1 1 0 0)
                 :cfp-closes-at (LocalDateTime/of 2099 1 1 0 0)}
                "kaocha")
        committee-id (:id (first (events/committees-for-event (:id event))))]
    (committees/add-member! committee-id
                            {:name (first (str/split chair-email #"@"))
                             :email chair-email
                             :role "chair"}
                            "kaocha")
    (events/event-by-slug slug)))

(defn- submit!
  [event title email]
  (let [fields (:fields (events/form-for-event (:id event)))
        params {:answer-talk-title title
                :answer-abstract (str "Abstract for " title)
                :answer-session-format "Experience Report"
                :answer-org-size "1,000-10,000"
                :answer-industry "Technology"
                :answer-ai-transformation-history "Started in 2024."
                :answer-measurable-outcomes "Lead time fell 40%."
                :speaker-name "Speaker One"
                :speaker-email email
                :speaker-title "VP Engineering"
                :speaker-org "Example Corp"
                :speaker-bio "A useful biography."}]
    (submissions/create-submission!
      event
      (submissions/parse-answers fields params)
      (submissions/parse-speaker params)
      "form"
      "kaocha")))

(defn- setup!
  []
  (let [event-a (create-event! "agent-a" "Agent Summit A" "gene@example.com")
        event-b (create-event! "agent-b" "Agent Summit B" "ann@example.com")
        published (submit! event-a "Published session" "speaker-a@example.com")
        pending (submit! event-a "Pending session" "speaker-b@example.com")
        foreign (submit! event-b "Foreign session" "speaker-c@example.com")]
    (reviews/set-status! (:id published) "Accepted" "gene@example.com")
    (inform/inform! event-a (store/submission-by-id (:id published)) "gene@example.com")
    {:event-a (events/event-by-slug "agent-a")
     :event-b (events/event-by-slug "agent-b")
     :published published
     :pending pending
     :foreign foreign
     :gene (store/person-by-email "gene@example.com")
     :ann (store/person-by-email "ann@example.com")}))

(defn- context
  [event person]
  {:event-slug (:slug event)
   :person person
   :actor (:email person)
   :base-url (sinks/public-base-url)
   :source :test})

(defn- rpc
  ([id method] (rpc id method {}))
  ([id method params]
   {:jsonrpc "2.0" :id id :method method :params params}))

(defn- login-cookie
  [handler email]
  (let [token (auth/issue-token! email)
        response (handler (mock/request :get (str "/auth/" token)))]
    (first (str/split (first (get-in response [:headers "Set-Cookie"])) #";"))))

(defn- mcp-post
  [handler slug message {:keys [cookie token]}]
  (let [request (cond-> (-> (mock/request :post (str "/events/" slug "/mcp")
                                          (json/write-str message))
                            (mock/header "content-type" "application/json")
                            (mock/header "accept" "application/json"))
                  cookie (mock/header "cookie" cookie)
                  token (mock/header "authorization" (str "Bearer " token)))
        response (handler request)]
    {:status (:status response)
     :body (when-not (str/blank? (str (:body response)))
             (json/read-str (:body response) :key-fn keyword))}))

(deftest command-registry-is-one-bounded-surface-test
  (testing "the registry exposes valuable workflows, not a generic shell"
    (is (= ["get_event" "list_sessions" "list_speakers" "get_schedule"
            "export_event" "get_review_policy" "list_submissions" "review_coverage"
            "schedule_conflicts" "speaker_obligations" "event_history"
            "set_submission_status"]
           (commands/command-names)))
    (is (= (commands/command-names)
           (mapv #(get % "name") (commands/tool-definitions))))
    (is (= (mapv :input-schema commands/command-definitions)
           (mapv #(get % "inputSchema") (commands/tool-definitions)))
        "MCP publishes the exact schemas the CLI registry executes")
    (is (not-any? (set (commands/command-names))
                  ["eval" "sql" "query" "append_fact" "delete"])))

  (testing "every tool is event-scoped by context, never by model arguments"
    (doseq [tool (commands/tool-definitions)]
      (is (false? (get-in tool ["inputSchema" "additionalProperties"])))
      (is (not (contains? (get-in tool ["inputSchema" "properties"])
                          "eventSlug"))))))

(deftest command-runtime-enforces-published-schema-bounds-test
  (let [{:keys [event-a gene]} (setup!)
        ctx (context event-a gene)]
    (doseq [[command args expected-data]
            [["event_history" {:since -1} {:argument :since :minimum 0}]
             ["set_submission_status"
              {:submissionId "" :status "Accepted" :confirm false}
              {:argument :submissionId :min-length 1}]]]
      (let [error (try
                    (commands/invoke! ctx command args)
                    (catch Exception e e))]
        (is (= :invalid-argument (:type (ex-data error))))
        (is (= expected-data (dissoc (ex-data error) :type)))))))

(deftest reads-respect-public-member-and-event-boundaries-test
  (let [{:keys [event-a event-b gene]} (setup!)
        anonymous {:event-slug "agent-a" :base-url "https://program.example.com"
                   :source :test}
        public (commands/invoke! anonymous "list_sessions" {})]
    (testing "anonymous reads receive only the published program"
      (is (= 1 (get-in public ["data" "total"])))
      (is (= "Published session"
             (get-in public ["data" "sessions" 0 "title"]))))

    (testing "the public policy command explains its own contract"
      (let [policy (commands/invoke! anonymous "get_review_policy" {})]
        (is (= "hidden" (get-in policy ["data" "policy" "mode"])))
        (is (= 0 (get-in policy ["data" "policy" "version"])))
        (is (= 3 (count (get-in policy ["data" "definition" "allowed-modes"]))))))

    (testing "operational reads reject anonymous callers"
      (is (= :forbidden
             (:type (ex-data
                      (try
                        (commands/invoke! anonymous "review_coverage" {})
                        (catch Exception e e)))))))

    (testing "membership in one event does not pierce another event"
      (is (= :forbidden
             (:type (ex-data
                      (try
                        (commands/invoke! (context event-b gene) "list_submissions" {})
                        (catch Exception e e))))))
      (is (= 2 (get-in (commands/invoke! (context event-a gene)
                                         "list_submissions" {})
                       ["data" "total"]))))))

(deftest reviewer-agent-submission-list-obeys-blind-review-policy-test
  (let [{:keys [event-a gene]} (setup!)
        committee-id (:id (first (events/committees-for-event (:id event-a))))
        reviewer (committees/add-member!
                   committee-id
                   {:name "Riley Reviewer"
                    :email "riley-reviewer@example.com"
                    :role "reviewer"}
                   "gene@example.com")
        _ (review-plan/set-presenter-visibility!
            (:id event-a) "hidden" 0 (:id gene) (:email gene))
        reviewer-result (commands/invoke!
                          (context event-a (store/person-by-id (:person-id reviewer)))
                          "list_submissions" {})
        chair-result (commands/invoke! (context event-a gene) "list_submissions" {})
        reviewer-payload (pr-str reviewer-result)
        chair-payload (pr-str chair-result)]
    (testing "the member API projects submissions for a reviewer"
      (is (= 2 (get-in reviewer-result ["data" "total"])))
      (is (str/includes? reviewer-payload "Anonymous speaker"))
      (doseq [secret ["Speaker One" "Example Corp" "speaker-a@example.com"]]
        (is (not (str/includes? reviewer-payload secret)) secret)))
    (testing "the organizer API retains complete identity"
      (is (str/includes? chair-payload "Speaker One")))))

(deftest mutation-is-dry-run-confirmed-named-verb-test
  (let [{:keys [event-a gene pending foreign]} (setup!)
        _ (store/await-sinks!)
        ctx (context event-a gene)
        before (count (store/log-for-event (:id event-a)))
        dry-run (commands/invoke! ctx "set_submission_status"
                                  {:submissionId (:id pending)
                                   :status "Accepted"
                                   :confirm false})]
    (testing "confirm=false proves the proposal without appending"
      (is (true? (get-in dry-run ["data" "dryRun"])))
      (is (false? (get-in dry-run ["audit" "changed"])))
      (is (= before (count (store/log-for-event (:id event-a)))))
      (is (= "Pending" (:status (store/submission-by-id (:id pending))))))

    (testing "confirm=true identifies the existing named domain fact among side effects"
      (let [result (commands/invoke! ctx "set_submission_status"
                                     {:submissionId (:id pending)
                                      :status "Accepted"
                                      :confirm true})
            after-log (store/log-for-event (:id event-a))
            fact-index (get-in result ["audit" "factLogIndex"])
            status-fact (nth after-log (dec fact-index))]
        (is (< before (count after-log)))
        (is (= "submission.status-changed" (get-in result ["fact" "type"])))
        (is (= (inc before) (get-in result ["audit" "factLogIndex"])))
        (is (= "submission.status-changed" (:type status-fact)))
        (is (= (:id pending) (get-in status-fact [:payload :submission-id])))
        (is (= "Accepted" (:status (store/submission-by-id (:id pending)))))))

    (testing "a foreign submission id is rejected before any fact append"
      (let [before-foreign (count (store/log-for-event (:id event-a)))
            error (try
                    (commands/invoke! ctx "set_submission_status"
                                      {:submissionId (:id foreign)
                                       :status "Accepted"
                                       :confirm true})
                    (catch Exception e e))]
        (is (= :cross-event (:type (ex-data error))))
        (is (= before-foreign (count (store/log-for-event (:id event-a)))))))))

(deftest mcp-initialize-discovery-calls-and-cli-parity-test
  (let [{:keys [event-a gene]} (setup!)
        _ (store/await-sinks!)
        ctx (context event-a gene)
        initialized (mcp/handle-message ctx (rpc 1 "initialize"))
        listed (mcp/handle-message ctx (rpc 2 "tools/list"))
        mcp-result (mcp/handle-message
                     ctx (rpc 3 "tools/call" {:name "get_event" :arguments {}}))
        cli-result (cli/execute {:event (:slug event-a)
                                 :command :get_event
                                 :actor "gene@example.com"})]
    (testing "initialize and deterministic discovery speak MCP"
      (is (= "2025-06-18" (get-in initialized ["result" "protocolVersion"])))
      (is (= (commands/command-names)
             (mapv #(get % "name") (get-in listed ["result" "tools"])))))

    (testing "MCP and CLI adapters return the same command data"
      (is (= (get cli-result "data")
             (get-in mcp-result ["result" "structuredContent" "data"])))
      (is (false? (get-in mcp-result ["result" "isError"]))))

    (testing "errors are stable across the shared registry and both adapters"
      (let [direct-error (try
                           (commands/invoke! ctx "no_such_command" {})
                           (catch Exception e e))
            cli-error (try
                        (cli/execute {:event (:slug event-a)
                                      :command :no_such_command})
                        (catch Exception e e))
            mcp-error (mcp/handle-message
                        ctx (rpc 4 "tools/call"
                                 {:name "no_such_command" :arguments {}}))]
        (is (= :unknown-command (:type (ex-data direct-error))))
        (is (= :unknown-command (:type (ex-data cli-error))))
        (is (= -32602 (get-in mcp-error ["error" "code"])))))

    (testing "CLI help is executable documentation"
      (is (str/includes? cli/help-text "clj -X:agent"))
      (is (str/includes? cli/help-text "confirm false")))))

(deftest mcp-protocol-layer-stays-transport-neutral-test
  (let [file (io/file "src/cfp_scheduler_killer/agent/mcp.clj")
        requires
        (with-open [reader (java.io.PushbackReader. (io/reader file))]
          (->> (read reader)
               (tree-seq coll? seq)
               (filter vector?)
               (keep first)
               (filter symbol?)
               set))]
    (is (= '#{cfp-scheduler-killer.agent.commands
              clojure.data.json}
           requires)
        (str "the MCP protocol layer may translate messages through the shared "
             "command registry, but must not acquire auth, store, HTTP, handlers, "
             "or views: " (pr-str requires)))))

(deftest agent-command-registry-never-depends-on-transport-adapters-test
  (let [file (io/file "src/cfp_scheduler_killer/agent/commands.clj")
        requires
        (with-open [reader (java.io.PushbackReader. (io/reader file))]
          (->> (read reader)
               (tree-seq coll? seq)
               (filter vector?)
               (keep first)
               (filter symbol?)
               set))
        outward-prefixes ["cfp-scheduler-killer.agent.cli"
                           "cfp-scheduler-killer.agent.mcp"
                           "cfp-scheduler-killer.handlers."
                           "cfp-scheduler-killer.server"
                           "cfp-scheduler-killer.middleware"
                           "cfp-scheduler-killer.web."
                           "cfp-scheduler-killer.views."]
        offenders (->> requires
                       (filter (fn [required]
                                 (some #(str/starts-with? (str required) %)
                                       outward-prefixes)))
                       set)]
    (is (empty? offenders)
        (str "the shared agent command registry is an application-service boundary "
             "and cannot depend outward on transport or presentation adapters: "
             (pr-str offenders)))))

(deftest agent-cli-does-not-acquire-http-or-presentation-layers-test
  (let [file (io/file "src/cfp_scheduler_killer/agent/cli.clj")
        requires
        (with-open [reader (java.io.PushbackReader. (io/reader file))]
          (->> (read reader)
               (tree-seq coll? seq)
               (filter vector?)
               (keep first)
               (filter symbol?)
               set))
        forbidden-prefixes ["cfp-scheduler-killer.handlers."
                             "cfp-scheduler-killer.server"
                             "cfp-scheduler-killer.middleware"
                             "cfp-scheduler-killer.web."
                             "cfp-scheduler-killer.views."]
        offenders (->> requires
                       (filter (fn [required]
                                 (some #(str/starts-with? (str required) %)
                                       forbidden-prefixes)))
                       set)]
    (is (empty? offenders)
        (str "the command-line agent adapter may orchestrate application services, "
             "but cannot acquire HTTP or presentation layers: "
             (pr-str offenders)))))

(deftest http-mcp-is-public-at-the-transport-and-private-in-the-command-test
  (let [{:keys [event-a event-b]} (setup!)
        handler (server/create-app)
        cookie (login-cookie handler "gene@example.com")
        api-key (:key (exports/create-api-key! event-a "agent test" "gene@example.com"))]
    (testing "the exact endpoint is public, not prefix lookalikes"
      (is (auth/public-path? "/events/agent-a/mcp"))
      (is (auth/public-path? "/events/agent-a/mcp/"))
      (is (not (auth/public-path? "/events/agent-a/mcpish"))))

    (testing "anonymous initialize, discovery, and a public call all work"
      (is (= 200 (:status (mcp-post handler "agent-a" (rpc 1 "initialize") {}))))
      (is (= 200 (:status (mcp-post handler "agent-a" (rpc 2 "tools/list") {}))))
      (let [response (mcp-post handler "agent-a"
                               (rpc 3 "tools/call" {:name "get_event" :arguments {}})
                               {})]
        (is (= 200 (:status response)))
        (is (false? (get-in response [:body :result :isError])))
        (is (nil? (get-in response [:body :result :structuredContent :data :counts
                                    :submissions])))))

    (testing "an operational call fails visibly for anonymous callers"
      (let [response (mcp-post handler "agent-a"
                               (rpc 4 "tools/call"
                                    {:name "review_coverage" :arguments {}})
                               {})]
        (is (= 200 (:status response)))
        (is (true? (get-in response [:body :result :isError])))
        (is (= "forbidden"
               (get-in response [:body :result :structuredContent :error :type])))))

    (testing "a signed-in event member and an event API key can read operations"
      (is (false? (get-in (mcp-post handler "agent-a"
                                    (rpc 5 "tools/call"
                                         {:name "review_coverage" :arguments {}})
                                    {:cookie cookie})
                          [:body :result :isError])))
      (is (false? (get-in (mcp-post handler "agent-a"
                                    (rpc 6 "tools/call"
                                         {:name "review_coverage" :arguments {}})
                                    {:token api-key})
                          [:body :result :isError]))))

    (testing "the same signed-in person is rejected on another event"
      (let [response (mcp-post handler (:slug event-b)
                               (rpc 7 "tools/call"
                                    {:name "list_submissions" :arguments {}})
                               {:cookie cookie})]
        (is (true? (get-in response [:body :result :isError])))
        (is (= "forbidden"
               (get-in response [:body :result :structuredContent :error :type])))))

    (testing "API keys are deliberately read-only even with confirm=true"
      (let [submission-id (:id (first (store/submissions-for-event (:id event-a))))
            response (mcp-post handler "agent-a"
                               (rpc 8 "tools/call"
                                    {:name "set_submission_status"
                                     :arguments {:submissionId submission-id
                                                 :status "Declined"
                                                 :confirm true}})
                               {:token api-key})]
        (is (true? (get-in response [:body :result :isError])))
        (is (= "confirmation-required"
               (get-in response [:body :result :structuredContent :error :type])))))))

(deftest scenario-runner-is-ordered-and-dry-by-default-test
  (let [{:keys [event-a pending]} (setup!)
        _ (store/await-sinks!)
        file (java.io.File/createTempFile "agent-scenario-" ".edn")]
    (try
      (spit file
            (pr-str {:event (:slug event-a)
                     :actor "gene@example.com"
                     :commands [{:command :get_event :args {}}
                                {:command :set_submission_status
                                 :args {:submissionId (:id pending)
                                        :status "Accepted"
                                        :confirm true}}]}))
      (let [before (count (store/log-for-event (:id event-a)))
            result (cli/execute-scenario {:scenario (.getAbsolutePath file)})]
        (is (true? (get result "dryRun")))
        (is (= ["get_event" "set_submission_status"]
               (mapv #(get % "command") (get result "results"))))
        (is (= before (count (store/log-for-event (:id event-a)))))
        (is (= "Pending" (:status (store/submission-by-id (:id pending))))))
      (finally
        (io/delete-file file true)))))
