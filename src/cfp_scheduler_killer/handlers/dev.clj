(ns cfp-scheduler-killer.handlers.dev
  "Web server with http-kit, reitit routing, and dev auto-reload.

   Handler convention (CLAUDE.md): every handler is a named `defn handle-*`
   referenced as `#'var` in the route table, so REPL redefinition takes effect
   without a restart."
  (:require
   [cfp-scheduler-killer.auth :as auth]
   [cfp-scheduler-killer.events :as events]
   [cfp-scheduler-killer.sse :as sse]
   [cfp-scheduler-killer.telemetry :as telemetry]
   [cfp-scheduler-killer.views.event-setup :as view-event-setup]
   [cfp-scheduler-killer.web.http :as web-http]
   [clojure.data.json :as json]
   [hiccup2.core :as h])
  (:gen-class))

(defn handle-reload-check [req]
  (if-let [handler (requiring-resolve 'browser-reload.core/reload-check-handler)]
    (handler req)
    {:status 200 :body "no-reload"}))

(defn handle-telemetry-beacon [req]
  (telemetry/accept-beacon! req))

(defn- visible-sse-registrations
  "Only registrations this organizer is authorized to diagnose.

   Real event streams follow committee membership. The create-page pseudo-
   event is per-person, so expose only the caller's own connection."
  [person]
  (let [event-ids (into #{} (map :id) (events/events-for-person (:id person)))]
    (filterv (fn [{:keys [event-id person-id]}]
               (or (contains? event-ids event-id)
                   (and (= sse/new-event-channel event-id)
                        (= (:id person) person-id))))
             (sse/registrations))))

(defn handle-sse-state
  "GET /dev/sse-state — who is listening, and what would be pushed.

   Read-only, organizer-gated, no secrets. It exists because \"SSE isn't
   updating\" is otherwise a twenty-minute bisect: this turns it into one
   request that says whether anyone is registered, under which keys, and what
   the fragment for a given set of params would actually look like."
  [req]
  (let [person (auth/current-person req)
        registrations (visible-sse-registrations person)
        p (:params req)
        typed {:name (:name p) :location (:location p)
               :starts-on (:starts-on p) :ends-on (:ends-on p)}]
    {:status 200
     :headers {"Content-Type" "application/json; charset=utf-8"}
     :body (json/write-str
             {:subscribers (count registrations)
              :registrations registrations
              :you {:person-id (:id person)
                    :new-event-channel sse/new-event-channel
                    :connections-on-create-page
                    (sse/person-connection-count sse/new-event-channel (:id person))}
              :display-name (events/display-name typed)
              :derived-slug (events/derive-slug typed)
              :marquee (str (h/html (view-event-setup/event-marquee (web-http/request-host req) typed
                                                                    (events/derive-slug typed)
                                                                    nil true)))})}))
