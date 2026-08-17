(ns cfp-scheduler-killer.views.public-cfps
  "Public discovery page for listed events accepting CFP submissions."
  (:require
   [cfp-scheduler-killer.events :as events]
   [cfp-scheduler-killer.views.format :as format]
   [cfp-scheduler-killer.views.public-widgets :as public-widgets]
   [cfp-scheduler-killer.views.shell :as shell]))

(defn- date-bracket [event]
  (when-let [start (format/->local-date (:starts-on event))]
    (let [end (or (format/->local-date (:ends-on event)) start)
          same-month? (and (= (.getYear start) (.getYear end))
                           (= (.getMonth start) (.getMonth end)))]
      {:month (subs (format/fmt-date start) 0 3)
       :days (cond
               (= start end) (str (.getDayOfMonth start))
               same-month? (str (.getDayOfMonth start) "–" (.getDayOfMonth end))
               :else (str (.getDayOfMonth start) "–"
                          (subs (format/fmt-date end) 0 3) " "
                          (.getDayOfMonth end)))
       :year (.getYear start)})))

(defn- featured-speaker-card [event speaker]
  (public-widgets/featured-card
    {:name (:name speaker)
     :org (:company speaker)
     :title (:tagline speaker)
     :headshot (:headshot speaker)
     :detail-url (when-let [handle (or (:slug speaker) (:id speaker))]
                   (str "/agenda/" (:slug event) "/speakers/" handle))}))

(defn- event-card [{:keys [event organizer speakers]}]
  (let [date (date-bracket event)
        speaker-count (count speakers)]
    [:article.cfps-event {:data-state "open"}
     (when date
       [:div.cfps-date
        [:span.mo (:month date)]
        [:span.dd (:days date)]
        [:span.yr (:year date)]])
     [:div.cfps-card-head
      [:h2 [:a {:href (str "/cfp/" (:slug event))}
            (:name event)]]
      [:span.cfps-state.cfps-state-open "Open for proposals"]]
     [:p.cfps-event-meta
      (when (:location event)
        [:span (:location event)])
      (when-let [dates (events/display-dates (:starts-on event) (:ends-on event))]
        [:span dates])
      (when-let [closes (format/fmt-instant (:cfp-closes-at event) (:tz event))]
        [:span.deadline
         [:span.deadline-label "Submit by"]
         [:time {:datetime (str (:cfp-closes-at event))} closes]])]
     [:div.cfps-foot
      (when organizer
        [:div.cfps-organizer
         [:span.rk "Organized by"]
         [:a {:class (str "cfp-organizer-chip"
                          (when-not (:headshot-url organizer) " no-photo"))
              :href (:url organizer)}
          (when-let [img (:headshot-url organizer)]
            [:img.cfp-organizer-photo
             {:src img :alt (:name organizer) :loading "lazy"}])
          [:span.cfp-organizer-name (:name organizer)]]])
      [:a.masthead-cfp-link {:href (str "/cfp/" (:slug event))}
       "Submit to this CFP →"]]
     (when (pos? speaker-count)
       [:div.cfps-gallery
        [:div.cfps-gallery-kicker
         (str "Already on this program — " speaker-count " speakers")]
        [:div.cfp-featured-grid
         (map (partial featured-speaker-card event) (take 8 speakers))]
        [:p.cfps-gallery-more
         [:a {:href (str "/program/" (:slug event))}
          (str "See all " speaker-count " speakers and the program →")]]])]))

(defn cfps-page [open-events]
  (shell/page-shell
    "CFPs open now"
    [:main.ui.container.cfps-page
     [:div.cfp-kicker "Find Your Next Conference Speaking Engagement!"]
     [:h1.masthead-title "CFPs open now"]
     [:p.cfps-intro
      "Calls that are accepting proposals right now — one clear path from an idea to the program."]
     (if (seq open-events)
       [:div.cfps-list
        [:div.cfps-rule
         (str (count open-events) " open "
              (if (= 1 (count open-events)) "call" "calls"))]
        (map event-card open-events)]
       [:div.ui.message.cfps-empty
        [:div.header "No open calls right now"]
        [:p "The next stage is taking shape. Check back soon."]])]))
