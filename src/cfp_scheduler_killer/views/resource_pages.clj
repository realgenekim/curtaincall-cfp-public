(ns cfp-scheduler-killer.views.resource-pages
  (:require
   [cfp-scheduler-killer.events :as events]
   [cfp-scheduler-killer.views.organizer-layout :as organizer-layout]
   [cfp-scheduler-killer.views.shell :as shell]
   [clojure.string :as str]
   [hiccup2.core :as h]))

(defn- lines [body]
  (remove str/blank? (str/split-lines (or body ""))))

(defn public-index-page [event pages]
  (shell/page-shell
   (str (events/display-name event) " — Resources")
   [:main.ui.container.public-widget-page
    [:div.cfp-masthead
     [:h1.ui.header (events/display-name event)]
     [:div.cfp-meta "Resources"]]
    [:h2 "Resources"]
    (if (seq pages)
      [:div.ui.relaxed.divided.list
       (for [{:keys [title slug body]} pages]
         [:div.item
          [:div.content
           [:a.header {:href (str "/program/" (:slug event) "/resources/" slug)} title]
           [:div.description (first (lines body))]]])]
      [:p "Resources will be published soon."])]))

(defn public-page [event page embed?]
  (let [content [:article.resource-page
                 {:data-resource-slug (:slug page)}
                 [:h1 (:title page)]
                 (for [line (lines (:body page))]
                   [:p line])]]
    (if embed?
      (str (h/html content))
      (shell/page-shell
       (str (:title page) " — " (events/display-name event))
       [:main.ui.container.public-widget-page
        [:a {:href (str "/program/" (:slug event) "/resources")} "← Resources"]
        content]))))

(defn organizer-page [host event person pages selected error saved?]
  (let [selected (or selected {:published? false})
        public-url (when (:slug selected)
                     (str host "/program/" (:slug event) "/resources/" (:slug selected)))
        snippet (when public-url
                  (str "<iframe src=\"" public-url "?embed=1\" title=\""
                       (:title selected) "\" loading=\"lazy\"></iframe>"))]
    (organizer-layout/organizer-shell
     (str "Resources — " (:name event))
     {:event event :active :resources :person person :crumb "Resources"}
     (organizer-layout/header "Resources" "Create public wiki pages from the event fact log.")
     [:div.ui.stackable.two.column.grid
      [:aside.five.wide.column
       [:h2.ui.small.header "Pages"]
       [:div.ui.selection.list
        (for [{:keys [id title published?]} pages]
          [:a.item {:href (str "/events/" (:slug event) "/resources?page=" id)}
           [:div.content
            [:div.header title]
            [:div.description (if published? "Published" "Draft")]]])]
       [:a.ui.basic.button {:href (str "/events/" (:slug event) "/resources")} "New page"]]
      [:section.eleven.wide.column
       [:h2.ui.header (if (:id selected) "Edit resource page" "New resource page")]
       (when error [:div.ui.negative.message error])
       (when saved? [:div.ui.positive.message "Resource page saved as an event fact."])
       [:form.ui.form {:method "post" :action (str "/api/events/" (:slug event) "/resources")}
        [:input {:type "hidden" :name "id" :value (or (:id selected) "")}]
        [:div.field
         [:label "Title"]
         [:input {:type "text" :name "title" :value (or (:title selected) "")
                  :placeholder "e.g. Venue guide" :data-ghost-fill ""}]]
        [:div.field
         [:label "URL slug"]
         [:input {:type "text" :name "slug" :value (or (:slug selected) "")
                  :placeholder "e.g. venue-guide" :data-ghost-fill ""}]]
        [:div.field
         [:label "Page body"]
         [:textarea {:name "body" :rows 12 :placeholder "Helpful details for attendees."
                     :data-ghost-fill ""} (:body selected)]]
        [:div.field
         [:div.ui.checkbox
          [:input {:type "checkbox" :name "published" :value "true"
                   :checked (:published? selected)}]
          [:label "Publish this page to the public program"]]]
        [:button.ui.primary.button {:type "submit"} "Save resource page"]]
       (when snippet
         [:div.ui.segment
          [:h3 "HTML embed"]
          [:p "This read-only iframe always renders the published resource page."]
          [:textarea {:readonly "readonly" :rows 3 :aria-label "Resource page iframe snippet"} snippet]
          [:a.ui.basic.button {:href public-url :target "_blank" :rel "noopener"}
           "Preview public page"]])]])))
