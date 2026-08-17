(ns cfp-scheduler-killer.handlers.public-api
  "Public API handlers. Reads are public or token-widened; writes require scoped keys."
  (:require
   [cfp-scheduler-killer.api-reviews :as api-reviews]
   [cfp-scheduler-killer.auth :as auth]
   [cfp-scheduler-killer.domain.review-plan :as domain-review-plan]
   [cfp-scheduler-killer.events :as events]
   [cfp-scheduler-killer.exports :as exports]
   [cfp-scheduler-killer.review-plan :as review-plan]
   [cfp-scheduler-killer.speakers :as speakers]
   [cfp-scheduler-killer.store :as store]
   [cfp-scheduler-killer.views.integrations :as view-integrations]
   [cfp-scheduler-killer.web.event :as event-web]
   [cfp-scheduler-killer.web.http :as http]
   [clojure.data.json :as json]
   [clojure.java.io :as io]
   [clojure.string :as str]))

(defn- api-params [req]
  (if (some-> (get-in req [:headers "content-type"])
              (str/includes? "application/json"))
    (merge (:params req)
           (try (json/read (io/reader (:body req)) :key-fn keyword)
                (catch Exception _ {})))
    (:params req)))

(defn- review-policy-resource [event]
  {"event" {"id" (:id event) "slug" (:slug event)}
   "definition" domain-review-plan/presenter-visibility-policy-definition
   "policy" (review-plan/presenter-visibility-policy (:id event))})

(defn- blind-public? [event authed?]
  (and (not authed?) (review-plan/blind-review? (:id event))))

(defn- redact-session-resource [session]
  (assoc session
         "speakerIds" []
         "speakers" [{"name" "Anonymous speaker"
                      "org" ""
                      "title" ""}]))

(defn- redact-sessions-resource [resource]
  (update resource "sessions" #(mapv redact-session-resource %)))

(defn- redact-speakers-resource [resource]
  (assoc resource "total" 0 "speakers" []))

(defn- xml-escape [value]
  (str/escape (str value)
              {\& "&amp;" \< "&lt;" \> "&gt;" \" "&quot;" \' "&apos;"}))

(defn- xml-tag [value]
  (str/replace (name value) #"[^A-Za-z0-9_.-]" "-"))

(defn- singular-tag [tag]
  (cond
    (str/ends-with? tag "ies") (str (subs tag 0 (- (count tag) 3)) "y")
    (str/ends-with? tag "s") (subs tag 0 (dec (count tag)))
    :else "item"))

(defn- xml-node [tag value]
  (let [tag (xml-tag tag)]
    (cond
      (map? value)
      (str "<" tag ">"
           (apply str
                  (for [[child-tag child] (sort-by (comp str key) value)]
                    (xml-node child-tag child)))
           "</" tag ">")

      (sequential? value)
      (str "<" tag ">"
           (apply str (map #(xml-node (singular-tag tag) %) value))
           "</" tag ">")

      (nil? value) (str "<" tag "></" tag ">")
      :else (str "<" tag ">" (xml-escape value) "</" tag ">"))))

(defn- public-data-response [req root resource]
  (if (= "xml" (some-> (get-in req [:params :format]) str str/lower-case))
    (http/text-response
      "application/xml; charset=utf-8"
      (str "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
           (xml-node root resource)))
    (http/json-response resource)))

(defn handle-api-review-policy-get [req]
  (event-web/with-event req #(http/json-response (review-policy-resource %))))

(defn handle-api-review-policy-put [req]
  (event-web/with-event
    req
    (fn [event]
      (let [person (auth/current-person req)
            params (api-params req)
            mode (or (:mode params) (get params "mode"))
            raw-version (or (:expectedVersion params)
                            (:expected-version params)
                            (get params "expectedVersion"))
            expected-version (try (Long/parseLong (str raw-version))
                                  (catch Exception _ nil))]
        (cond
          (nil? person)
          (http/json-response 401 {"error" "sign in as an event chair"})

          (nil? expected-version)
          (http/json-response 422 {"error" "expectedVersion must be an integer"})

          :else
          (try
            (review-plan/set-presenter-visibility!
              (:id event) mode expected-version (:id person) (:email person))
            (http/json-response (review-policy-resource event))
            (catch clojure.lang.ExceptionInfo e
              (let [{:keys [type] :as data} (ex-data e)]
                (http/json-response
                  (case type
                    :chair-required 403
                    :stale-policy-version 409
                    422)
                  {"error" (.getMessage e) "type" (name type) "details" data})))))))))

(defn- api-context [req event]
  (exports/api-key-context event (exports/bearer-token req)))

(defn- api-authed? [req event]
  (boolean (api-context req event)))

(def ^:private token-hint
  "Authorization: Bearer <token> — mint one on the event's Settings page (API keys).")

(defn- needs-token [message]
  (http/json-response 401 {"error" message "hint" token-hint}))

(defn- api-actor [req event]
  (or (some-> (api-context req event) exports/api-key-actor)
      (:email (auth/current-person req))
      "api:unknown"))

(defn wrap-token-authenticated-no-store [handler]
  (fn [req]
    (let [response (handler req)
          event (when (str/starts-with? (:uri req) "/api/v1/events/")
                  (events/event-by-slug (get-in req [:path-params :slug])))]
      (if (and event (api-context req event))
        (assoc-in response [:headers "Cache-Control"] "private, no-store")
        response))))

(defn- needs-scope [scope]
  (http/json-response 403
                      {"error" (str "this write requires an API key with "
                                    (name scope) " scope")}))

(defn- api-review-error [e]
  (let [{:keys [type user-message]} (ex-data e)
        status (case type
                 :no-such-submission 404
                 :idempotency-conflict 409
                 :submission-not-rateable 409
                 :reviewer-recused 409
                 :not-on-review-committee 403
                 :reviewer-subject-required 403
                 :insufficient-scope 403
                 422)]
    (http/json-response status
                        {"error" (or user-message (.getMessage e))
                         "type" (some-> type name)})))

(defn handle-api-review-create [req]
  (event-web/with-event
    req
    (fn [event]
      (let [context (api-context req event)
            submission-id (http/clean-id (get-in req [:path-params :submission-id]))]
        (cond
          (nil? context)
          (needs-token "a scoped API key is required to record a review")

          (not (#{:reviewer :review-bot} (:scope context)))
          (http/json-response 403
                              {"error" "this endpoint requires reviewer or review-bot scope"})

          (nil? submission-id)
          (http/json-response 404 {"error" "no such submission on this event"})

          :else
          (try
            (let [params (api-params req)
                  params (assoc params :idempotency-key
                                (or (:idempotency-key params)
                                    (get-in req [:headers "idempotency-key"])))
                  result (api-reviews/record! (:id event) submission-id context params
                                              (exports/api-key-actor context))]
              (http/json-response (if (:created? result) 201 200)
                                  {"review-id" (str (:review-id result))
                                   "actor" (:actor result)}))
            (catch clojure.lang.ExceptionInfo e
              (api-review-error e))))))))

(defn- handle-api-speaker-publication [req verb]
  (event-web/with-event
    req
    (fn [event]
      (let [person-id (http/clean-id (get-in req [:path-params :person-id]))]
        (cond
          (not (api-authed? req event))
          (needs-token "a token is required to change speaker publication")

          (not= :organizer (:scope (api-context req event)))
          (needs-scope :organizer)

          (nil? person-id)
          (http/json-response 404 {"error" "no such speaker on this event"})

          :else
          (let [result (verb (:id event) person-id (api-actor req event))]
            (if (:rejected result)
              (http/json-response 404 {"error" "no such speaker on this event"})
              {:status 204 :headers {} :body ""})))))))

(defn handle-api-speaker-publish [req]
  (handle-api-speaker-publication req speakers/publish!))

(defn handle-api-speaker-unpublish [req]
  (handle-api-speaker-publication req speakers/unpublish!))

(defn handle-api-index [req]
  (http/with-etag req (http/json-response (exports/api-index (http/request-host req)))))

(defn handle-api-event [req]
  (http/with-etag
    req
    (event-web/with-event
      req
      (fn [event]
        (http/json-response
          (exports/api-event event (http/request-host req) (api-authed? req event)))))))

(defn handle-api-sessions [req]
  (http/with-etag
    req
    (event-web/with-event
      req
      (fn [event]
        (let [authed? (api-authed? req event)
              asked (http/not-blank (get-in req [:params :status]))]
          (if (and asked (not authed?))
            (needs-token "a token is required to filter by status")
            (let [resource (exports/api-sessions
                             event
                             (when authed?
                               (cond
                                 (nil? asked) nil
                                 (= "all" (str/lower-case asked)) :all
                                 :else asked))
                             authed?)]
              (public-data-response
                req
                "sessionsFeed"
                (if (blind-public? event authed?)
                  (redact-sessions-resource resource)
                  resource)))))))))

(defn handle-api-submissions [req]
  (http/with-etag
    req
    (event-web/with-event
      req
      (fn [event]
        (if-not (api-authed? req event)
          (needs-token "a token is required to read submissions")
          (let [asked (http/not-blank (get-in req [:params :status]))]
            (http/json-response
              (exports/api-sessions event
                                    (if (and asked (not= "all" (str/lower-case asked)))
                                      asked
                                      :all)
                                    true))))))))

(defn handle-api-speakers [req]
  (http/with-etag
    req
    (event-web/with-event
      req
      (fn [event]
        (let [authed? (api-authed? req event)
              asked (http/not-blank (get-in req [:params :status]))]
          (if (and asked (not authed?))
            (needs-token "a token is required to list speakers who are not on the program")
            (let [resource (exports/api-speakers event (boolean (and authed? asked)))]
              (public-data-response
                req
                "speakersFeed"
                (if (blind-public? event authed?)
                  (redact-speakers-resource resource)
                  resource)))))))))

(defn handle-api-speaker [req]
  (http/with-etag
    req
    (event-web/with-event
      req
      (fn [event]
        (let [pid (http/clean-id (get-in req [:path-params :person-id]))
              authed? (api-authed? req event)
              row (when pid (exports/api-speaker event pid authed?))]
          (if (and row (not (blind-public? event authed?)))
            (http/json-response row)
            (http/json-response 404 {"error" "no such speaker on this event"})))))))

(defn handle-api-schedule [req]
  (http/with-etag
    req
    (event-web/with-event
      req
      (fn [event]
        (http/json-response (exports/api-schedule event (api-authed? req event)))))))

(defn handle-api-rooms [req]
  (http/with-etag
    req
    (event-web/with-event
      req
      (fn [event] (http/json-response (exports/api-rooms event))))))

(defn handle-api-changes [req]
  (http/with-etag
    req
    (event-web/with-event
      req
      (fn [event]
        (if-not (api-authed? req event)
          (needs-token "a token is required for the change feed")
          (let [raw (http/not-blank (get-in req [:params :since]))
                since (when raw
                        (try (Long/parseLong (str raw))
                             (catch Exception _ ::invalid)))]
            (if (or (= ::invalid since) (and since (neg? since)))
              (http/json-response 422
                                  {"error" "since must be a non-negative integer"})
              (http/json-response (exports/api-changes event (or since 0))))))))))

(defn handle-api-docs [req]
  (let [slug (get-in req [:path-params :slug])]
    (if-let [event (events/event-by-slug slug)]
      (http/with-etag
        req
        (-> (http/html-response
              (view-integrations/api-docs-page (http/request-host req) event))
            (assoc-in [:headers "Access-Control-Allow-Origin"] "*")))
      (http/json-response 404 {"error" "no such event" "slug" slug}))))

(defn handle-api-submission [req]
  (http/with-etag
    req
    (event-web/with-event
      req
      (fn [event]
        (let [sid (http/clean-id (get-in req [:path-params :submission-id]))
              row (when sid (store/submission-by-id sid))
              authed? (api-authed? req event)]
          (cond
            (or (nil? row) (not= (:id event) (:event-id row)))
            (http/json-response 404 {"error" "no such submission"})

            (and (not (exports/published? row)) (not authed?))
            (needs-token "a token is required for unpublished submissions")

            :else
            (http/json-response
              (exports/api-session
                event
                (if (blind-public? event authed?)
                  (domain-review-plan/blind-submission row)
                  row)))))))))
