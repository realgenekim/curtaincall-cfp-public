(ns cfp-scheduler-killer.handlers.integrations
  "Organizer settings, API keys, Slack, webhooks, and Airtable handlers."
  (:require
   [cfp-scheduler-killer.auth :as auth]
   [cfp-scheduler-killer.events :as events]
   [cfp-scheduler-killer.exports :as exports]
   [cfp-scheduler-killer.notices :as notices]
   [cfp-scheduler-killer.sinks :as sinks]
   [cfp-scheduler-killer.store :as store]
   [cfp-scheduler-killer.views.integrations :as view-integrations]
   [cfp-scheduler-killer.web.event :as event-web]
   [cfp-scheduler-killer.web.http :as http]
   [clojure.string :as str]
   [taoensso.timbre :as log]))

(defn- settings-response
  "`extra` carries the one-shot state of a just-POSTed control — the freshly
   minted key's identity, or which key is asking to be confirmed. It is passed
   through the render and never stored, because neither fact outlives the
   response."
  ([req event] (settings-response req event 200 nil))
  ([req event status] (settings-response req event status nil))
  ([req event status extra]
   (let [person (auth/current-person req)]
     (http/html-response
       status
       (view-integrations/settings-page
         (http/request-host req)
         event
         (merge {:person person
                 :webhooks (exports/webhooks-for event)
                 :api-keys (exports/api-keys-for event)
                 :deliveries (filterv #(= (:id event) (:event-id %))
                                      @store/deliveries)
                 :slack-groups sinks/slack-event-groups
                 :notice (notices/notice-for (:id event) (:id person))}
                extra))))))

(defn handle-settings [req]
  (let [slug (get-in req [:path-params :slug])]
    (if-let [event (events/event-by-slug slug)]
      (settings-response req event)
      (event-web/not-found-page slug))))

(defn handle-api-key-create
  "Mint a key and re-render Settings with its masked identifier and copy action."
  [req]
  (let [slug (get-in req [:path-params :slug])]
    (if-let [event (events/event-by-slug slug)]
      (let [person (auth/current-person req)
            scope (exports/api-key-scope (get-in req [:params :scope]))]
        (if scope
          (let [row (exports/create-api-key! event
                                             (get-in req [:params :label])
                                             scope
                                             (:id person)
                                             (:email person))]
            (assoc-in (settings-response req (events/event-by-slug slug) 200
                                         {:new-key row})
                      [:headers "Cache-Control"] "no-store"))
          (settings-response req event 422
                             {:api-key-error "Choose a valid API key scope."})))
      (event-web/not-found-page slug))))

(defn handle-api-key-copy
  "Return one event-scoped credential only after the organizer deliberately asks
   to copy it. Omitting `id` selects the legacy event token."
  [req]
  (let [slug (get-in req [:path-params :slug])
        requested-id (get-in req [:params :id])
        key-id (http/clean-id requested-id)]
    (if-let [event (events/event-by-slug slug)]
      (if-let [key (if (some? requested-id)
                     (when key-id
                       (get-in event [:settings :api-keys key-id :key]))
                     (get-in event [:settings :api-token]))]
        (assoc-in (http/json-response {"key" key})
                  [:headers "Cache-Control"] "no-store")
        (http/json-response 404 {"error" "no such API key"}))
      (http/json-response 404 {"error" "no such event"}))))

(defn handle-api-key-revoke
  "Two steps, both server-rendered. The first POST carries no `confirm` and only
   re-renders the page with that row asking; the second does the work. No
   confirm() — a modal dialog blocks the SSE stream (global CLAUDE.md, NEVER #2)
   and, more simply, is state living in the browser."
  [req]
  (let [slug (get-in req [:path-params :slug])
        key-id (http/clean-id (get-in req [:params :id]))]
    (if-let [event (events/event-by-slug slug)]
      (cond
        (nil? (get-in event [:settings :api-keys key-id]))
        (settings-response req event 404)

        (http/not-blank (get-in req [:params :confirm]))
        (do (exports/revoke-api-key! event key-id (:email (auth/current-person req)))
            (http/see-other (str "/events/" slug "/settings")))

        :else
        (settings-response req event 200 {:confirming-key key-id}))
      (event-web/not-found-page slug))))

(defn- update-event-settings!
  "Append one event.updated carrying the whole new settings map."
  [event actor changed f]
  (store/append!
    {:type "event.updated" :actor actor :event-id (:id event)
     :payload {:id (:id event) :slug (:slug event)
               :changed [changed] :before {}
               :changes {:settings (f (:settings event))}}}))

(defn handle-slack-set [req]
  (let [slug (get-in req [:path-params :slug])]
    (if-let [event (events/event-by-slug slug)]
      (let [person (auth/current-person req)
            url (http/not-blank (get-in req [:params :webhook-url]))
            raw (get-in req [:params :groups])
            groups (vec (cond (nil? raw) []
                              (sequential? raw) (map str raw)
                              :else [(str raw)]))]
        (cond
          (nil? url)
          (do (notices/set-notice!
                (:id event) (:id person)
                {:kind :error
                 :message "Paste the incoming-webhook URL Slack gave you (it starts https://hooks.slack.com/services/)."})
              (settings-response req event 422))

          (empty? groups)
          (do (notices/set-notice!
                (:id event) (:id person)
                {:kind :error
                 :message "Pick at least one moment to post about — a Slack hook that posts about nothing is just a stored secret."})
              (settings-response req event 422))

          :else
          (do (update-event-settings!
                event (:email person) "slack"
                (fn [settings]
                  (assoc settings :slack {:webhook-url url
                                          :groups groups
                                          :events (sinks/types-for-groups groups)})))
              (notices/set-notice!
                (:id event) (:id person)
                {:kind :ok :message "Slack saved. Send a test message to be sure."})
              (http/see-other (str "/events/" slug "/settings")))))
      (event-web/not-found-page slug))))

(defn handle-slack-remove [req]
  (let [slug (get-in req [:path-params :slug])]
    (if-let [event (events/event-by-slug slug)]
      (let [person (auth/current-person req)]
        (update-event-settings! event (:email person) "slack" #(dissoc % :slack))
        (notices/clear-notice! (:id event) (:id person))
        (http/see-other (str "/events/" slug "/settings")))
      (event-web/not-found-page slug))))

(defn handle-slack-test
  "Post a real message to the real channel and say what came back."
  [req]
  (let [slug (get-in req [:path-params :slug])]
    (if-let [event (events/event-by-slug slug)]
      (let [person (auth/current-person req)
            url (get-in event [:settings :slack :webhook-url])
            result (sinks/send-test-message! event url (or (:name person) (:email person)))]
        (notices/set-notice! (:id event) (:id person)
                             {:kind (if (:ok result) :ok :error)
                              :message (:message result)})
        (log/info :slack-test :slug slug :ok (:ok result))
        (if (:ok result)
          (http/see-other (str "/events/" slug "/settings"))
          (settings-response req event 422)))
      (event-web/not-found-page slug))))

(defn handle-webhook-add [req]
  (let [slug (get-in req [:path-params :slug])]
    (if-let [event (events/event-by-slug slug)]
      (do
        (exports/register-webhook! event
                                   (get-in req [:params :url])
                                   (map str/trim (str/split (str (get-in req [:params :types])) #","))
                                   (:email (auth/current-person req)))
        (http/see-other (str "/events/" slug "/settings")))
      (event-web/not-found-page slug))))

(defn handle-airtable-set [req]
  (let [slug (get-in req [:path-params :slug])]
    (if-let [event (events/event-by-slug slug)]
      (do
        (when-let [base-id (http/not-blank (get-in req [:params :base-id]))]
          (store/append!
            {:type "event.updated" :actor (:email (auth/current-person req))
             :event-id (:id event)
             :payload {:id (:id event) :slug slug
                       :changed ["airtable"]
                       :before {}
                       :changes {:settings
                                 (assoc (:settings event)
                                        :airtable
                                        {:base-id base-id
                                         :table (or (http/not-blank (get-in req [:params :table]))
                                                    "Submissions")
                                         :token (get-in req [:params :token])})}}}))
        (http/see-other (str "/events/" slug "/settings")))
      (event-web/not-found-page slug))))

(defn handle-airtable-remove [req]
  (let [slug (get-in req [:path-params :slug])]
    (if-let [event (events/event-by-slug slug)]
      (do (store/append!
            {:type "event.updated" :actor (:email (auth/current-person req))
             :event-id (:id event)
             :payload {:id (:id event) :slug slug
                       :changed ["airtable"]
                       :before {}
                       :changes {:settings (dissoc (:settings event) :airtable)}}})
          (http/see-other (str "/events/" slug "/settings")))
      (event-web/not-found-page slug))))

(defn handle-webhook-remove [req]
  (let [slug (get-in req [:path-params :slug])]
    (if-let [event (events/event-by-slug slug)]
      (do (exports/remove-webhook! event (get-in req [:params :id])
                                   (:email (auth/current-person req)))
          (http/see-other (str "/events/" slug "/settings")))
      (event-web/not-found-page slug))))
