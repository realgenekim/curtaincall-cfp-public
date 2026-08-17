(ns cfp-scheduler-killer.views.embed
  "Organizer-facing handoff builder for already-public agenda surfaces."
  (:require
   [cfp-scheduler-killer.embedding :as embedding]
   [cfp-scheduler-killer.views.organizer-layout :as organizer-layout]))

(defn- option
  [selected {:keys [id label]}]
  [:option {:value id :selected (= selected id)} label])

(defn embed-builder-page
  [host event person params]
  (let [{:keys [widget format config public-url handoff-url value] :as spec}
        (embedding/build host event (:widget params) (:format params) params)
        {:keys [accent theme track session-format room width height fields]} config]
    (organizer-layout/organizer-shell
      (str "Embed builder — " (:name event))
      {:event event :active :embed :person person :crumb "Embed builder"}
      (organizer-layout/header
        "Embed builder"
        "Choose a public widget and a handoff format; every generated URL works without login.")

      [:div.ui.info.message
       [:div.header "Social unfurls"]
       [:p "These public URLs also carry Open Graph and Twitter card metadata:"]
       [:ul
        [:li [:a {:href (str "/program/" (:slug event)) :target "_blank"} "Public program"]
         " — event title, description, and program card image."]
        [:li [:a {:href (str "/agenda/" (:slug event) "/speakers") :target "_blank"} "Speaker profiles"]
         " — speaker title, bio summary, and generated speaker card image."]
        [:li "Speaker announcement pages — the speaker's announcement copy and card image."]]
       [:p "Each unfurls as a rich card on Twitter/LinkedIn/Slack."]]

      [:div.ui.segment
       [:form.ui.form {:method "get"
                       :action (str "/events/" (:slug event) "/embed")}
        [:input {:type "hidden" :name "customize" :value "true"}]
        [:div.two.fields
         [:div.field
          [:label "Widget type"]
          [:select {:name "widget"}
           (for [item embedding/widget-options]
             (option widget item))]]
         [:div.field
          [:label "Format"]
          [:select {:name "format"}
           (for [item embedding/format-options]
             (option format item))]]]
        [:h3.ui.dividing.header "Branding and content"]
        [:div.three.fields
         [:div.field
          [:label "Accent color"]
          [:input {:type "color" :name "accent" :value accent}]]
         [:div.field
          [:label "Layout density"]
          [:select {:name "theme"}
           [:option {:value "standard" :selected (= "standard" theme)} "Standard"]
           [:option {:value "compact" :selected (= "compact" theme)} "Compact"]]]
         [:div.field
          [:label "Track filter"]
          [:input {:type "text" :name "track" :value (or track "")
                   :placeholder "e.g. Developer Practices"
                   :data-ghost-fill ""}]]]
        [:div.two.fields
         [:div.field
          [:label "Format filter"]
          [:input {:type "text" :name "session-format"
                   :value (or session-format "")
                   :placeholder "e.g. Experience Report"
                   :data-ghost-fill ""}]]
         [:div.field
          [:label "Location filter"]
          [:input {:type "text" :name "room" :value (or room "")
                   :placeholder "e.g. Main Stage"
                   :data-ghost-fill ""}]]]
        [:div.two.fields
         [:div.field
          [:label "Frame width"]
          [:input {:type "text" :name "width" :value width
                   :inputmode "numeric" :placeholder "100% or 800"}]]
         [:div.field
          [:label "Frame height"]
          [:input {:type "number" :name "height" :value height
                   :min "300" :max "1600" :step "10"}]]]
        [:div.grouped.fields
         [:label "Fields to show"]
         (for [{:keys [id label]} embedding/field-options]
           [:div.field
            [:div.ui.checkbox
             [:input {:type "checkbox" :name "fields" :value id
                      :checked (contains? (set fields) id)}]
             [:label label]]])]
        [:button.ui.primary.button {:type "submit"} "Generate embed"]]]

      [:div.ui.segment
       [:h2 "Copyable snippet"]
       [:p.field-hint
        "Paste this into a website, CMS, calendar client, or integration. The source remains the published event log projection."]
       [:textarea {:readonly "readonly" :rows 6 :data-widget widget
                   :data-format format}
        value]
       [:div.ui.positive.message
        [:strong "Configuration applied. "]
        "This URL reads the live public projection. Organizer edits appear when the embed reloads; no regeneration or republishing is required."]
       [:p [:a.ui.basic.button {:href handoff-url :target "_blank"
                                :rel "noopener"}
            "Open generated handoff"]]]

      (when (= "iframe" (:format spec))
        [:div.ui.segment
         [:h2 "Working preview"]
         [:iframe {:src public-url :title "Embed preview"
                   :loading "lazy" :width width :height height}]]))))
