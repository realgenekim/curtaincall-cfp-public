(ns cfp-scheduler-killer.embed-widget
  "Read-only accepted-session embed and its copy-paste handoff."
  (:require
   [cfp-scheduler-killer.events :as events]
   [cfp-scheduler-killer.schedule :as schedule]
   [clojure.string :as str]
   [datastar-kit.ds :as ds]
   [hiccup2.core :as h]))

(def mode "accepted-sessions")

(defn- widget-url
  [host event]
  (str host "/program/" (:slug event)))

(defn- iframe-snippet
  [host event]
  (str "<iframe src=\"" (widget-url host event)
       "\" title=\"Accepted sessions\" loading=\"lazy\" "
       "width=\"100%\" height=\"640\"></iframe>"))

(defn program-with-copy-paste
  "Add the embed handoff to an already-rendered canonical program page."
  [program-html host event]
  (let [url (widget-url host event)
        panel (str
                (h/html
                  [:section.accepted-sessions-embed
                   [:h2.public-program-heading "Embed accepted sessions"]
                   [:p "Paste this read-only agenda widget into a wiki or website."]
                   [:textarea.accepted-sessions-code
                    {:readonly "readonly"
                     :aria-label "Accepted sessions iframe snippet"
                     :rows 2}
                    (iframe-snippet host event)]
                   [:div.accepted-sessions-actions
                    [:button.ui.small.basic.button
                     {:type "button"
                      :data-star-on:click
                      (ds/copy-nearest-text ".accepted-sessions-embed"
                                            ".accepted-sessions-code"
                                            "Copied embed code to clipboard")}
                     "Copy to clipboard"]
                    [:a {:href url :target "_blank" :rel "noopener"}
                     "Preview the widget →"]]]))]
    (if (str/includes? program-html "</main>")
      (str/replace-first program-html "</main>" (str panel "</main>"))
      (str program-html panel))))

(defn- session-card
  [event item]
  [:article.accepted-session
   {:style "border:1px solid #ddd;border-radius:.4rem;padding:.8rem;margin:.6rem 0;"}
   [:div {:style "font-size:.9rem;color:#555;"}
    (schedule/time-range-display (:start item) (:end item))
    (when (:room item) (str " · " (:room item)))]
   [:h3 {:style "margin:.25rem 0;"}
    [:a {:href (str "/agenda/" (:slug event) "/sessions/" (:submission-id item))
         :target "_blank" :rel "noopener"}
     (:title item)]]
   (when (seq (:speakers item))
     [:div (str/join ", " (:speakers item))])])

(defn accepted-sessions-fragment
  "Render only placed, published sessions from schedule/agenda for iframe use."
  [event agenda]
  (let [days (keep (fn [day]
                     (let [sessions (filterv #(= :session (:kind %)) (:items day))]
                       (when (seq sessions) (assoc day :items sessions))))
                   agenda)]
    (str
      (h/html
        [:section.accepted-sessions-widget
         {:aria-label "Accepted sessions"
          :style "box-sizing:border-box;font-family:system-ui,sans-serif;max-width:64rem;padding:1rem;color:#222;"}
         [:header
          [:h1 {:style "margin:0;"} (events/display-name event)]
          [:p {:style "margin:.25rem 0 1rem;color:#555;"} "Accepted sessions"]]
         (if (seq days)
           (for [day days]
             [:section.accepted-sessions-day
              [:h2 {:style "margin:1.25rem 0 .5rem;"} (:label day)]
              (for [item (:items day)]
                (session-card event item))])
           [:p "The agenda will be published soon."])]))))
