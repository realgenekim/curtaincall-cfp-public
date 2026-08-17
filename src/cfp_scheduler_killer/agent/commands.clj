(ns cfp-scheduler-killer.agent.commands
  "One event-scoped application-service surface for humans, CLIs, and agents.

   Adapters may speak JSON-RPC, HTTP, or `clj -X`; none of them are allowed to
   reimplement these use cases. Every command receives its event scope from the
   trusted adapter context, never from model-controlled arguments."
  (:require
   [cfp-scheduler-killer.auth :as auth]
   [cfp-scheduler-killer.domain.review-plan :as domain-review-plan]
   [cfp-scheduler-killer.events :as events]
   [cfp-scheduler-killer.exports :as exports]
   [cfp-scheduler-killer.review-plan :as review-plan]
   [cfp-scheduler-killer.review-work :as review-work]
   [cfp-scheduler-killer.reviews :as reviews]
   [cfp-scheduler-killer.schedule :as schedule]
   [cfp-scheduler-killer.speaker-tasks :as speaker-tasks]
   [cfp-scheduler-killer.store :as store]
   [clojure.string :as str]
   [taoensso.timbre :as log])
  (:import
   (java.time.temporal TemporalAccessor)
   (java.util UUID)))

(defn- refuse!
  [type message data]
  (throw (ex-info message (assoc data :type type))))

(defn json-safe
  "Turn domain values into the transport-neutral JSON data model."
  [x]
  (cond
    (map? x) (into {} (map (fn [[k v]]
                             [(cond
                                (keyword? k) (name k)
                                (string? k) k
                                :else (str k))
                              (json-safe v)])) x)
    (set? x) (mapv json-safe (sort-by pr-str x))
    (sequential? x) (mapv json-safe x)
    (keyword? x) (name x)
    (or (instance? TemporalAccessor x)
        (instance? UUID x)) (str x)
    :else x))

(def ^:private empty-input
  {"type" "object"
   "properties" {}
   "additionalProperties" false})

(defn- object-input
  [properties required]
  (cond-> {"type" "object"
           "properties" properties
           "additionalProperties" false}
    (seq required) (assoc "required" (vec required))))

(defn- public-context?
  [_event _context]
  true)

(defn- member-context?
  [event {:keys [person token]}]
  (boolean
    (or (auth/member-of-event? person (:id event))
        (and (not (str/blank? (str token)))
             (exports/valid-token? event token)))))

(defn- signed-member-context?
  [event {:keys [person]}]
  (auth/member-of-event? person (:id event)))

(defn- event-summary
  [event _args context]
  (exports/api-event event (:base-url context) (member-context? event context)))

(defn- session-list
  [event _args context]
  (exports/api-sessions event nil (member-context? event context)))

(defn- speaker-list
  [event _args _context]
  (exports/api-speakers event false))

(defn- schedule-view
  [event _args context]
  (exports/api-schedule event (member-context? event context)))

(defn- submission-list
  [event _args {:keys [person]}]
  (let [resource (exports/api-sessions event :all true)]
    (if (and person
             (not (auth/event-manager? person (:id event))))
      (let [rows (review-plan/project-submissions
                   (:id event)
                   (:id person)
                   (store/submissions-for-event (:id event)))]
        (assoc resource "sessions" (mapv #(exports/api-session event %) rows)))
      resource)))

(defn- review-coverage
  [event _args _context]
  {"event" {"id" (:id event) "slug" (:slug event) "name" (:name event)}
   "reviewers" (review-work/progress-for-event (:id event))})

(defn- review-policy
  [event _args _context]
  {"event" {"id" (:id event) "slug" (:slug event)}
   "definition" (json-safe domain-review-plan/presenter-visibility-policy-definition)
   "policy" (json-safe (review-plan/presenter-visibility-policy (:id event)))})

(defn- schedule-conflicts
  [event _args _context]
  {"event" {"id" (:id event) "slug" (:slug event) "name" (:name event)}
   "stats" (schedule/stats event)
   "conflicts" (schedule/conflicts event)})

(defn- speaker-obligations
  [event _args _context]
  {"event" {"id" (:id event) "slug" (:slug event) "name" (:name event)}
   "obligations" (speaker-tasks/outstanding-for-event (:id event))})

(defn- event-history
  [event {:keys [since]} _context]
  (exports/api-changes event (or since 0)))

(defn- event-export
  [event {:keys [format]} {:keys [base-url]}]
  (case format
    "sessions" (exports/sessions-json-data event)
    "speakers" (exports/speakers-json-data event)
    "calendar" {"format" "text/calendar" "body" (exports/calendar-ics event)}
    "llms" {"format" "text/markdown" "body" (exports/llms-txt event base-url)}
    (refuse! :invalid-argument
             (str "Unknown export format: " format)
             {:argument :format :allowed ["sessions" "speakers" "calendar" "llms"]})))

(defn- status-change
  [event {:keys [submissionId status confirm]} {:keys [actor]}]
  (let [submission (store/submission-by-id submissionId)]
    (when-not submission
      (refuse! :not-found "No such submission in this event."
               {:submission-id submissionId}))
    (when-not (= (:id event) (:event-id submission))
      (refuse! :cross-event
               "That submission belongs to a different event."
               {:submission-id submissionId :event-id (:id event)}))
    (if-not confirm
      {:data {"dryRun" true
              "changed" false
              "submissionId" submissionId
              "from" (:status submission)
              "to" status
              "message" "No fact appended. Repeat with confirm=true to apply."}
       :mutation {:changed false}}
      (let [before-count (count (store/log-for-event (:id event)))
            updated (reviews/set-status! submissionId status actor)
            log (store/log-for-event (:id event))
            matching-facts
            (keep-indexed
              (fn [offset fact]
                (when (and (= "submission.status-changed" (:type fact))
                           (= submissionId (get-in fact [:payload :submission-id])))
                  {:fact fact :log-index (+ before-count offset 1)}))
              (drop before-count log))
            {:keys [fact log-index]} (first matching-facts)]
        (when-not (= 1 (count matching-facts))
          (refuse! :mutation-proof-failed
                   "The status changed, but its appended status fact could not be proven."
                   {:before before-count
                    :after (count log)
                    :matching-facts (count matching-facts)}))
        {:data {"dryRun" false
                "changed" true
                "submissionId" submissionId
                "from" (:status submission)
                "to" (:status updated)}
         :mutation {:changed true
                    :fact-log-index log-index
                    :fact {:type (:type fact)
                           :at (:at fact)
                           :submission-id (get-in fact [:payload :submission-id])}}}))))

(def command-definitions
  [{:name "get_event"
    :title "Get event"
    :description "Get event identity, dates, CFP state, counts, and canonical links."
    :access :public
    :input-schema empty-input
    :handler event-summary}
   {:name "list_sessions"
    :title "List sessions"
    :description "List the published program with stable session and speaker IDs."
    :access :public
    :input-schema empty-input
    :handler session-list}
   {:name "list_speakers"
    :title "List speakers"
    :description "List published speakers and their stable session relationships."
    :access :public
    :input-schema empty-input
    :handler speaker-list}
   {:name "get_schedule"
    :title "Get schedule"
    :description "Get days, rooms, placements, blocks, and unscheduled sessions."
    :access :public
    :input-schema empty-input
    :handler schedule-view}
   {:name "export_event"
    :title "Export event"
    :description "Export authoritative sessions, speakers, calendar, or llms data."
    :access :public
    :input-schema (object-input
                    {"format" {"type" "string"
                               "enum" ["sessions" "speakers" "calendar" "llms"]}}
                    ["format"])
    :handler event-export}
   {:name "get_review_policy"
    :title "Explain review identity policy"
    :description "Get the effective presenter-visibility mode, version, allowed modes, guarantees, limitations, and audience explanations."
    :access :public
    :input-schema empty-input
    :handler review-policy}
   {:name "list_submissions"
    :title "List submissions"
    :description "List the complete event funnel, including unpublished decisions."
    :access :member
    :input-schema empty-input
    :handler submission-list}
   {:name "review_coverage"
    :title "Review coverage"
    :description "Show assigned, completed, and remaining reviews for every reviewer."
    :access :member
    :input-schema empty-input
    :handler review-coverage}
   {:name "schedule_conflicts"
    :title "Schedule conflicts"
    :description "Show schedule coverage plus room, speaker, and date collisions."
    :access :member
    :input-schema empty-input
    :handler schedule-conflicts}
   {:name "speaker_obligations"
    :title "Speaker obligations"
    :description "Show outstanding speaker deliverables, most overdue first."
    :access :member
    :input-schema empty-input
    :handler speaker-obligations}
   {:name "event_history"
    :title "Event history"
    :description "Read the monotonic, IDs-only event change feed."
    :access :member
    :input-schema (object-input
                    {"since" {"type" "integer" "minimum" 0}}
                    [])
    :handler event-history}
   {:name "set_submission_status"
    :title "Set submission status"
    :description (str "Preview or append the existing submission.status-changed domain fact. "
                      "confirm=false is always a dry run; confirm=true requires a signed-in event member.")
    :access :member
    :mutation? true
    :input-schema (object-input
                    {"submissionId" {"type" "string" "minLength" 1}
                     "status" {"type" "string" "minLength" 1}
                     "confirm" {"type" "boolean"}}
                    ["submissionId" "status" "confirm"])
    :handler status-change}])

(def ^:private command-by-name
  (into {} (map (juxt :name identity)) command-definitions))

(defn command-names
  []
  (mapv :name command-definitions))

(defn tool-definitions
  "The MCP-facing projection of the same registry the CLI executes."
  []
  (mapv (fn [{:keys [name title description access mutation? input-schema]}]
          {"name" name
           "title" title
           "description" (str description " Access: " (clojure.core/name access) ".")
           "inputSchema" input-schema
           "annotations" {"readOnlyHint" (not mutation?)
                          "destructiveHint" false
                          "idempotentHint" (not mutation?)}})
        command-definitions))

(defn- normalize-args
  [args]
  (into {} (map (fn [[k v]] [(if (keyword? k) k (keyword (str k))) v])) (or args {})))

(defn- validate-type!
  [argument schema value]
  (let [type (get schema "type")]
    (when (and (= "string" type) (not (string? value)))
      (refuse! :invalid-argument (str argument " must be a string.") {:argument argument}))
    (when (and (= "boolean" type) (not (instance? Boolean value)))
      (refuse! :invalid-argument (str argument " must be a boolean.") {:argument argument}))
    (when (and (= "integer" type) (not (integer? value)))
      (refuse! :invalid-argument (str argument " must be an integer.") {:argument argument}))
    (when-let [minimum (and (number? value) (get schema "minimum"))]
      (when (< value minimum)
        (refuse! :invalid-argument
                 (str argument " must be at least " minimum ".")
                 {:argument argument :minimum minimum})))
    (when-let [minimum-length (and (string? value) (get schema "minLength"))]
      (when (< (count value) minimum-length)
        (refuse! :invalid-argument
                 (str argument " must contain at least " minimum-length " character(s).")
                 {:argument argument :min-length minimum-length})))
    (when-let [allowed (seq (get schema "enum"))]
      (when-not (some #{value} allowed)
        (refuse! :invalid-argument
                 (str argument " must be one of: " (str/join ", " allowed) ".")
                 {:argument argument :allowed allowed})))))

(defn- validate-args!
  [{:keys [input-schema]} args]
  (let [properties (get input-schema "properties")
        allowed (set (map keyword (keys properties)))
        required (set (map keyword (get input-schema "required" [])))
        supplied (set (keys args))
        unknown (seq (sort (remove allowed supplied)))
        missing (seq (sort (remove supplied required)))]
    (when unknown
      (refuse! :invalid-argument
               (str "Unknown argument(s): " (str/join ", " (map name unknown)) ".")
               {:unknown unknown :allowed allowed}))
    (when missing
      (refuse! :invalid-argument
               (str "Missing required argument(s): " (str/join ", " (map name missing)) ".")
               {:missing missing}))
    (doseq [[k v] args]
      (validate-type! k (get properties (name k)) v))
    args))

(defn- authorize!
  [{:keys [access mutation?]} event context]
  (when-not ((case access
               :public public-context?
               :member member-context?)
             event context)
    (refuse! :forbidden
             "This command requires membership in the scoped event or a valid event API key."
             {:event-slug (:slug event) :access access}))
  (when (and mutation? (not (signed-member-context? event context)))
    (refuse! :confirmation-required
             "Mutations require a signed-in human member of this event; API keys are read-only."
             {:event-slug (:slug event)})))

(defn invoke!
  "Execute one named command inside a trusted event scope.

   Context keys: :event-slug, optional :person/:token, :actor, :base-url, and
   :source. Model-controlled args can never replace :event-slug or :actor."
  [{:keys [event-slug actor source] :as context} command-name raw-args]
  (let [command (get command-by-name (name command-name))
        event (events/event-by-slug (str event-slug))
        actor (or actor (some-> (:person context) :email) "anonymous")
        context (assoc context :actor actor)]
    (when-not command
      (refuse! :unknown-command (str "Unknown command: " command-name)
               {:command command-name :available (command-names)}))
    (when-not event
      (refuse! :event-not-found (str "No event has slug " event-slug ".")
               {:event-slug event-slug}))
    (try
      (authorize! command event context)
      (let [args (validate-args! command (normalize-args raw-args))
            raw ((:handler command) event args context)
            mutation (or (:mutation raw) {:changed false})
            data (if (contains? raw :data) (:data raw) raw)
            audit {"actor" actor
                   "eventId" (:id event)
                   "eventSlug" (:slug event)
                   "command" (:name command)
                   "source" (name (or source :application))
                   "access" (name (:access command))
                   "mutation" (boolean (:mutation? command))
                   "changed" (boolean (:changed mutation))
                   "factLogIndex" (:fact-log-index mutation)}
            response (json-safe
                       {"ok" true
                        "command" (:name command)
                        "event" {"id" (:id event) "slug" (:slug event)}
                        "data" data
                        "audit" audit
                        "fact" (:fact mutation)})]
        (log/info :agent-command
                  :actor actor :event-id (:id event) :event-slug (:slug event)
                  :command (:name command) :source source
                  :changed (:changed mutation) :fact-log-index (:fact-log-index mutation))
        response)
      (catch Exception e
        (log/warn :agent-command-refused
                  :actor actor :event-slug event-slug :command command-name
                  :source source :error-type (:type (ex-data e)) :msg (.getMessage e))
        (throw e)))))
