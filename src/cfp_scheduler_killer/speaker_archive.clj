(ns cfp-scheduler-killer.speaker-archive
  "Public, browsable cards for accepted and informed event speakers."
  (:require
   [cfp-scheduler-killer.events :as events]
   [cfp-scheduler-killer.views.shell :as shell]
   [clojure.string :as str]))

(defn accepted-speakers
  "Keep speakers backed by at least one published accepted session."
  [speakers]
  (filterv #(seq (:sessions %)) speakers))

(defn- speaker-handle
  [speaker]
  (or (:slug speaker) (:id speaker)))

(defn- initials
  [name]
  (->> (str/split (str/trim (or name "")) #"\s+")
       (keep first)
       (take 2)
       (apply str)
       str/upper-case))

(defn- headshot
  [speaker]
  (if (str/blank? (:headshot speaker))
    [:div.public-headshot.public-headshot-fallback
     {:role "img" :aria-label (str "Photo unavailable for " (:name speaker))}
     [:span (initials (:name speaker))]
     [:small "Photo unavailable"]]
    [:img.public-headshot
     {:src (:headshot speaker)
      :alt (str (:name speaker) " headshot")
      :loading "lazy"}]))

(defn- speaker-card
  [event speaker]
  (let [profile-path (str "/agenda/" (:slug event) "/speakers/"
                          (speaker-handle speaker))]
    [:article.cfp-featured-card.speaker-archive-card
     [:a {:href profile-path} (headshot speaker)]
     [:div.cfp-featured-name [:a {:href profile-path} (:name speaker)]]
     (when-not (str/blank? (:company speaker))
       [:div.cfp-featured-org (:company speaker)])
     [:div.speaker-archive-talks
      (for [session (:sessions speaker)]
        [:a.speaker-archive-talk
         {:href (str "/agenda/" (:slug event) "/sessions/" (:id session))}
         (:title session)])]
     [:a.speaker-archive-bio {:href profile-path} "Read bio →"]]))

(defn page
  [event all-speakers speakers q]
  (shell/page-shell
   (str (events/display-name event) " — Speaker Gallery")
   [:main.ui.container.public-widget-page
    [:div.cfp-masthead
     [:h1.ui.header (events/display-name event)]
     [:div.cfp-meta "Speaker Gallery"]
     [:div.cfp-limit
      [:a {:href (str "/program/" (:slug event))} "← The program"]]]
    [:form.public-filter-bar
     {:method "get" :action (str "/agenda/" (:slug event) "/gallery")}
     [:input {:type "search" :name "q" :value (or q "")
              :placeholder "Search speakers by name"}]
     [:button.ui.button {:type "submit"} "Search"]]
    [:div.public-result-count
     (str "Speakers " (count speakers) " of " (count all-speakers))]
    (if (seq speakers)
      [:div.public-speaker-grid.public-gallery.speaker-archive
       (for [speaker speakers] (speaker-card event speaker))]
      [:div.ui.segment.empty-state "No speakers match that search."])]))
