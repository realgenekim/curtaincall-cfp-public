(ns cfp-scheduler-killer.views.replay
  "Replay controls and pushable progress fragment."
  (:require
   [cfp-scheduler-killer.views.organizer-layout :as organizer-layout]
   [datastar-kit.ds :as ds]))

(defn replay-progress-bar
  "Pushed over SSE on every tick. Its own id so the rest of the page stays put."
  [_event {:keys [status day days idx total submissions total-submissions reviews pct]}]
  [:div#replay-progress
   [:div.replay-progress
    [:div.replay-fill {:style (str "width:" (format "%.1f" (double (or pct 0))) "%;")}]]
   [:div.field-hint
    (case status
      :playing "▶ playing · "
      :paused "⏸ paused · "
      :done "finished · "
      "")
    "day " day " of " days
    " · " submissions "/" total-submissions " submissions"
    " · " reviews " review events"
    " · " idx "/" total " total"]])

(defn replay-page
  [event {:keys [person progress corpus-available? speeds speed running?]}]
  (organizer-layout/organizer-shell
    (str "Replay — " (:name event))
    {:event event :active :replay :person person :crumb "Replay" :sse? true
     :body-attrs (ds/sse-mount (:id event))}

    (organizer-layout/header "Replay simulator"
                             "Three weeks of a real CFP, compressed. Played through the actual mutations — nothing here is faked.")

    (if-not corpus-available?
      [:div.ui.warning.message
       [:div.header "The corpus isn't installed"]
       [:p "Drop the scripted corpus at " [:code "resources/replay/aie-corpus.json"]
        " and reload. Without it there is nothing to play — and rather than "
        "invent filler, this page says so."]]

      [:div.replay-bar
       (replay-progress-bar event progress)
       [:div.replay-controls {:style "margin-top:0.6em;"}
        (if running?
          [:form {:method "post" :action (str "/api/events/" (:slug event) "/replay/pause")}
           [:button.ui.button {:type "submit"} "⏸ Pause"]]
          [:form {:method "post" :action (str "/api/events/" (:slug event) "/replay/play")}
           [:select {:name "speed"}
            (for [sp speeds]
              [:option (cond-> {:value (:key sp)}
                         (= (:seconds sp) speed) (assoc :selected true))
               (:label sp)])]
           [:button.ui.primary.button {:type "submit"} "▶ Play"]])
        [:form {:method "post" :action (str "/api/events/" (:slug event) "/replay/skip")}
         [:button.ui.button {:type "submit"} "Skip to end"]]
        [:a.ui.basic.button {:href (str "/events/" (:slug event) "/board")}
         "Watch the board"]
        [:a.ui.basic.button {:href (str "/events/" (:slug event) "/log")} "Watch the log"]]])

    [:div.ui.info.message {:style "margin-top:1.2em;"}
     [:p "Open the "
      [:a {:href (str "/events/" (:slug event) "/board")} "review board"]
      " in another tab while this plays. Rows appear, ratings land, coverage climbs — "
      "live, over the same SSE the app already uses. Afterwards, drag the slider on "
      "the board or the log to replay it at your own pace."]
     [:p.field-hint
      "Every entry goes through the same functions a real submission does. If the "
      "replay worked, the app works."]]))
