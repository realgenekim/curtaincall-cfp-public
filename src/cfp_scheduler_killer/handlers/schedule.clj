(ns cfp-scheduler-killer.handlers.schedule
  "Organizer schedule-grid, room, block, lock, and publish handlers."
  (:require
   [cfp-scheduler-killer.auth :as auth]
   [cfp-scheduler-killer.events :as events]
   [cfp-scheduler-killer.exports :as exports]
   [cfp-scheduler-killer.schedule :as schedule]
   [cfp-scheduler-killer.schedule-suggestions :as schedule-suggestions]
   [cfp-scheduler-killer.sse :as sse]
   [cfp-scheduler-killer.store :as store]
   [cfp-scheduler-killer.tracks :as tracks]
   [cfp-scheduler-killer.views.schedule :as view-schedule]
   [cfp-scheduler-killer.views.shell :as view-shell]
   [cfp-scheduler-killer.web.event :as event-web]
   [cfp-scheduler-killer.web.http :as http]))

(defn- occupied-minutes
  "Union known occupied intervals so conflicts never double-count room time."
  [items day room-id day-start day-end]
  (->> items
       (keep (fn [item]
               (when (and (= day (:day item)) (= room-id (:room-id item)))
                 (let [start (max day-start (:start item))
                       end (min day-end (:end item))]
                   (when (< start end) [start end])))))
       (sort-by first)
       (reduce (fn [[total occupied-through] [start end]]
                 (if (> end occupied-through)
                   [(+ total (- end (max start occupied-through))) end]
                   [total occupied-through]))
               [0 day-start])
       first))

(defn- room-time-math
  [event rooms placed blocks]
  (let [{:keys [day-start day-end]} (events/day-hours event)
        day-start (schedule/parse-time day-start)
        day-end (schedule/parse-time day-end)
        room-ids (mapv :id rooms)
        items (concat placed blocks)
        per-day (mapv
                  (fn [day]
                    (let [capacity (* (- day-end day-start) (count room-ids))
                          filled (reduce + (map #(occupied-minutes
                                                  items day % day-start day-end)
                                               room-ids))]
                      {:day day
                       :capacity capacity
                       :filled filled
                       :open (max 0 (- capacity filled))}))
                  (schedule/event-days event))]
    {:capacity (reduce + (map :capacity per-day))
     :filled (reduce + (map :filled per-day))
     :open (reduce + (map :open per-day))
     :per-day per-day}))

(defn- schedule-model [req event]
  (let [days (schedule/event-days event)
        day (or (http/not-blank (get-in req [:params :day])) (first days))
        person (auth/current-person req)
        rooms (store/rooms-for-event (:id event))
        placed (schedule/placements (:id event))
        blocks (store/blocks-for-event (:id event))]
    {:day day
     :stats (assoc (schedule/stats event)
                   :room-time (room-time-math event rooms placed blocks))
     :conflicts (schedule/conflicts event)
     :conflicted-ids (schedule/conflicted-submission-ids event)
     :withheld-count (let [conflicted (schedule/conflicted-submission-ids event)]
                       (count (filter #(and (contains? conflicted (:id %))
                                            (exports/published? %))
                                      (store/submissions-for-event (:id event)))))
     :rooms rooms
     :tracks (tracks/tracks-for-event event)
     :placed placed
     :blocks blocks
     :trayed (schedule/tray (:id event))
     :locked? (schedule/locked? event)
     :lock-version (schedule/lock-label event)
     :published-at (schedule/published-at event)
     :editable? (auth/event-manager? person (:id event))
     :person person}))

(defn handle-schedule [req]
  (let [slug (get-in req [:path-params :slug])]
    (if-let [event (events/event-by-slug slug)]
      (http/html-response (view-schedule/schedule-page event (schedule-model req event)))
      (event-web/not-found-page slug))))

(defn push-schedule!
  "Repaint the grid and the status bar for everyone else watching this event."
  [req event]
  (let [model (schedule-model req event)]
    (sse/push-fragment! (:id event) "#schedule-status"
                        (fn [] (view-schedule/schedule-status-bar (:stats model))))))

(defn- with-schedule
  "Resolve the event, refuse locked mutations, act, push, and redirect."
  [req f]
  (let [slug (get-in req [:path-params :slug])]
    (if-let [event (events/event-by-slug slug)]
      (try
        (f event)
        (push-schedule! req event)
        (http/see-other (str "/events/" slug "/schedule"
                             (when-let [d (http/not-blank (get-in req [:params :day]))]
                               (str "?day=" d))))
        (catch clojure.lang.ExceptionInfo e
          (case (:type (ex-data e))
            :schedule-locked
            {:status 409
             :headers {"Content-Type" "text/html; charset=utf-8"}
             :body (view-shell/page-shell
                     "Schedule locked"
                     [:div.ui.warning.message
                      [:div.header "The schedule is locked"]
                      [:p "It was frozen as " (or (:version (ex-data e)) "a version")
                       ". Unlock it to keep editing."]]
                     [:a.ui.basic.button {:href (str "/events/" slug "/schedule")}
                      "Back to the schedule"])}

            :invalid-track
            {:status 422
             :headers {"Content-Type" "text/html; charset=utf-8"}
             :body (view-shell/page-shell
                     "Track not changed"
                     [:div.ui.warning.message
                      [:div.header "Track not changed"]
                      [:p (.getMessage e)]]
                     [:a.ui.basic.button {:href (str "/events/" slug "/schedule")}
                      "Back to the schedule"])}

            :submission-not-in-event
            ;; Fail closed without echoing a globally valid foreign id or any
            ;; of its proposal/presenter data into this event's response.
            (event-web/not-found-page slug)

            (throw e))))
      (event-web/not-found-page slug))))

(defn handle-schedule-place [req]
  (with-schedule req
    (fn [event]
      (schedule/place! event
                       (get-in req [:params :submission-id])
                       {:day (get-in req [:params :day])
                        :start (get-in req [:params :start])
                        :duration (get-in req [:params :duration])
                        :room-id (http/not-blank (get-in req [:params :room-id]))}
                       (:email (auth/current-person req))))))

(defn handle-schedule-clear [req]
  (with-schedule req
    (fn [event]
      (schedule/clear-slot! event (get-in req [:params :submission-id])
                            (:email (auth/current-person req))))))

(defn handle-room-add [req]
  (with-schedule req
    (fn [event]
      (schedule/add-room! event (get-in req [:params :name])
                          (:email (auth/current-person req))))))

(defn handle-room-remove [req]
  (with-schedule req
    (fn [event]
      (schedule/remove-room! event (get-in req [:params :room-id])
                             (:email (auth/current-person req))))))

(defn handle-track-add [req]
  (with-schedule req
    (fn [event]
      (tracks/add! event (get-in req [:params :label])
                   (:email (auth/current-person req))))))

(defn handle-track-rename [req]
  (with-schedule req
    (fn [event]
      (tracks/rename! event
                      (get-in req [:params :old-label])
                      (get-in req [:params :new-label])
                      (:email (auth/current-person req))))))

(defn handle-track-retire [req]
  (with-schedule req
    (fn [event]
      (tracks/retire! event (get-in req [:params :label])
                      (:email (auth/current-person req))))))

(defn handle-block-add [req]
  (with-schedule req
    (fn [event]
      (schedule/add-block! event
                           {:day (get-in req [:params :day])
                            :start (get-in req [:params :start])
                            :duration (get-in req [:params :duration])
                            :room-id (http/not-blank (get-in req [:params :room-id]))
                            :label (get-in req [:params :label])}
                           (:email (auth/current-person req))))))

(defn handle-block-remove [req]
  (with-schedule req
    (fn [event]
      (schedule/remove-block! event (get-in req [:params :block-id])
                              (:email (auth/current-person req))))))

(defn handle-schedule-lock [req]
  (let [slug (get-in req [:path-params :slug])]
    (if-let [event (events/event-by-slug slug)]
      (do (schedule/lock! event (:email (auth/current-person req)))
          (http/see-other (str "/events/" slug "/schedule")))
      (event-web/not-found-page slug))))

(defn handle-schedule-unlock [req]
  (let [slug (get-in req [:path-params :slug])]
    (if-let [event (events/event-by-slug slug)]
      (do (schedule/unlock! event (:email (auth/current-person req)))
          (http/see-other (str "/events/" slug "/schedule")))
      (event-web/not-found-page slug))))

(defn handle-schedule-publish [req]
  (let [slug (get-in req [:path-params :slug])]
    (if-let [event (events/event-by-slug slug)]
      (do (schedule/publish! event (:email (auth/current-person req)))
          (http/see-other (str "/events/" slug "/schedule")))
      (event-web/not-found-page slug))))

(defn handle-schedule-suggest
  [req]
  (with-schedule
    req
    (fn [event]
      (schedule-suggestions/apply!
        event (:email (auth/current-person req))))))
