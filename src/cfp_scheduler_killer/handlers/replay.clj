(ns cfp-scheduler-killer.handlers.replay
  "Safe replay-simulator page and control handlers."
  (:require
   [cfp-scheduler-killer.auth :as auth]
   [cfp-scheduler-killer.events :as events]
   [cfp-scheduler-killer.replay :as replay]
   [cfp-scheduler-killer.review-policy :as review-policy]
   [cfp-scheduler-killer.reviews :as reviews]
   [cfp-scheduler-killer.sse :as sse]
   [cfp-scheduler-killer.views.replay :as view-replay]
   [cfp-scheduler-killer.views.review :as view-review]
   [cfp-scheduler-killer.views.shell :as view-shell]
   [cfp-scheduler-killer.web.event :as event-web]
   [cfp-scheduler-killer.web.http :as http]))

(defn- replay-model [req event]
  (let [run (replay/state-for (:id event))]
    {:person (auth/current-person req)
     :progress (replay/progress (:id event))
     :corpus-available? (replay/corpus-available?)
     :speeds replay/speeds
     :speed (or (:speed run) replay/default-speed)
     :running? (= :playing (:status run))}))

(defn handle-replay-page [req]
  (let [slug (get-in req [:path-params :slug])]
    (if-let [event (events/event-by-slug slug)]
      (http/html-response (view-replay/replay-page event (replay-model req event)))
      (event-web/not-found-page slug))))

(defn handle-replay-start-demo
  "Create a fresh event to replay into; never touch an existing event.
   A `then` section param (the newcomer ghost sidebar, Gene ratified Option B
   2026-08-11) fast-forwards the whole corpus first, so the clicked section
   lands populated — never a blank board."
  [req]
  (let [person (auth/current-person req)
        event (replay/create-demo-event! (or (:email person) "replay") person)
        slug (:slug event)
        then (get-in req [:params :then])
        dest (cond
               (= "agenda" then) (str "/agenda/" slug)
               (contains? #{"details" "form" "committee" "board"
                            "inform" "schedule" "exports"
                            "comms" "log" "settings"} then)
               (str "/events/" slug "/" then))]
    (when dest (replay/skip-to-end! event))
    (http/see-other (or dest (str "/events/" slug "/replay")))))

(defn- push-replay! [event]
  (sse/push-fragment! (:id event) "#replay-progress"
                      (fn [] (view-replay/replay-progress-bar
                              event (replay/progress (:id event)))))
  (sse/push-fragment! (:id event) "#coverage-bar"
                      (fn [] (view-review/coverage-bar
                              event (reviews/coverage
                                     (:id event)
                                     (review-policy/coverage-target-for (:id event)))))))

(defn- with-replay [req f]
  (let [slug (get-in req [:path-params :slug])]
    (if-let [event (events/event-by-slug slug)]
      (try
        (f event)
        (http/see-other (str "/events/" slug "/replay"))
        (catch clojure.lang.ExceptionInfo e
          (if (= :not-a-replay-event (:type (ex-data e)))
            {:status 409
             :headers {"Content-Type" "text/html; charset=utf-8"}
             :body (view-shell/page-shell
                    "Not a replay event"
                    [:div.ui.warning.message
                     [:div.header "This event is not a replay target"]
                     [:p "The simulator only plays into an event it created, so a "
                      "demo can never pollute a real conference's data."]]
                    [:a.ui.basic.button {:href (str "/events/" slug)} "Back to the event"])}
            (throw e))))
      (event-web/not-found-page slug))))

(defn handle-replay-play [req]
  (with-replay req
    (fn [event]
      (let [speed (or (try (Integer/parseInt (str (get-in req [:params :speed])))
                           (catch Exception _ nil))
                      replay/default-speed)]
        (replay/start! event speed #(push-replay! event))))))

(defn handle-replay-pause [req]
  (with-replay req (fn [event] (replay/pause! event))))

(defn handle-replay-skip [req]
  (with-replay req
    (fn [event]
      (replay/skip-to-end! event)
      (push-replay! event))))
