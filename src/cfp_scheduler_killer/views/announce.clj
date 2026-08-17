(ns cfp-scheduler-killer.views.announce
  "Organizer marquee and invited-speaker creation views."
  (:require
   [cfp-scheduler-killer.views.form-controls :as form-controls]
   [cfp-scheduler-killer.views.organizer-layout :as organizer-layout]
   [cfp-scheduler-killer.views.pipeline :as pipeline]
   [cfp-scheduler-killer.views.public-widgets :as public-widgets]
   [clojure.string :as str]))

(defn- coverage [lit total]
  (let [pct (if (pos? total) (* 100.0 (/ lit total)) 0.0)]
    [:div.coverage
     [:div.coverage-headline lit " of " total " lit"]
     [:div.coverage-track
      [:div.coverage-fill {:style (str "width:" (format "%.1f" pct) "%;")}]]
     [:div.coverage-note (format "%.0f%% live" pct)]]))

(defn- ready-row [event submission]
  (let [speaker (first (:speakers submission))]
    [:div.announce-admin-row.announce-ready-row
     [:div.announce-bulb.dark "◌"]
     [:div.announce-person
      [:strong (:name speaker)]
      [:span (:talk-title (:answers submission))]]
     [:div.announce-readiness "told ✗"]
     [:div.announce-actions
      [:a.ui.small.basic.button
       {:href (str "/events/" (:slug event) "/inform")}
       "Inform them →"]]]))

(defn- announce-path [event speaker]
  (str "/agenda/" (:slug event) "/speakers/"
       (or (:slug speaker) (:id speaker)) "/announce"))

(defn- lit-row [event base-url speaker edit]
  (let [path (announce-path event speaker)
        page-url (public-widgets/announce-page-url event base-url speaker)
        post-text (public-widgets/announce-post-text event speaker page-url)
        published? (:published? speaker)
        edit-id (str "edit-" (:id speaker))
        editing? (= (str (:id speaker)) (str (:person-id edit)))
        values (when editing? (:values edit))
        errors (when editing? (:errors edit))
        value (fn [key fallback]
                (if (and values (contains? values key))
                  (or (get values key) "")
                  fallback))]
    [:div.announce-admin-row {:class (if published? "lit" "dark")}
     [:div.announce-bulb {:class (if published? "lit" "dark")}
      (if published? "✦" "◌")]
     [:div.announce-person
      [:strong (:name speaker)]
      (when-not (str/blank? (:company speaker)) [:span (:company speaker)])
      (if published?
        [:code path]
        [:span "Not published"])]
     [:div.announce-readiness
      (if (:manual? speaker)
        (str "published " (if published? "✓" "✗"))
        (str "told " (if (:told? speaker) "✓" "✗")))
      " · headshot " (if (str/blank? (:headshot speaker)) "✗" "✓")
      " · bio " (if (str/blank? (:bio speaker)) "✗" "✓")]
     [:div.announce-actions
      (when published?
        [:a.ui.small.basic.button
         {:href path :target "_blank" :rel "noopener"} "View →"])
      [:a.ui.small.basic.button {:href (str "#" edit-id)} "Edit details ▾"]
      (when published?
        [:button.ui.small.basic.button
         {:type "button" :data-copy page-url :onclick "copyShare(this)"}
         "Copy speaker brag page link"])
      (when published?
        [:button.ui.small.basic.button
         {:type "button" :data-copy post-text :onclick "copyShare(this)"}
         "Copy post text"])]
     [:div.announce-edit-panel {:id edit-id}
      [:div.announce-edit-head
       [:div
        [:div.announce-edit-kicker "PROGRAM DETAILS"]
        [:strong "Edit " (:name speaker)]]
       [:a.announce-edit-close {:href "#" :title "Close"} "✕"]]
      [:div.announce-edit-grid
       [:form.ui.small.form
        {:method "post"
         :action (str "/api/events/" (:slug event)
                      "/speakers/" (:id speaker))}
        [:div.announce-edit-subhead "Speaker"]
        [:div.two.fields
         [:div.field {:class (when (:name errors) "error")}
          [:label "Name"]
          [:input {:name "name" :required true
                   :value (value :name (:name speaker))}]
          (form-controls/field-errors errors :name)]
         [:div.field
          [:label "Job title / tagline"]
          [:input {:name "title" :value (value :title (:tagline speaker))}]]]
        [:div.two.fields
         [:div.field
          [:label "Organization"]
          [:input {:name "org" :value (value :org (:company speaker))}]]
         [:div.field {:class (when (:headshot-url errors) "error")}
          [:label "Headshot URL"]
          [:input {:name "headshot-url" :type "url"
                   :value (value :headshot-url (:headshot speaker))
                   :placeholder "https://…"}]
          (form-controls/field-errors errors :headshot-url)]]
        [:div.field
         [:label "Bio"]
         [:textarea {:name "bio" :rows "5"}
          (value :bio (:bio speaker))]]
        (when (:manual? speaker)
          [:div.field
           [:div.ui.checkbox
            [:input (cond-> {:type "checkbox" :name "announce" :value "1"}
                      published? (assoc :checked true))]
            [:label "Publish as speaker"]]])
        [:button.ui.small.primary.button {:type "submit"} "Save speaker"]]
       [:div.ui.small.form
        [:div.announce-edit-subhead "Talks"]
        (if (seq (:sessions speaker))
          (for [session (:sessions speaker)]
            [:div.announce-edit-talk
             [:div.field
              [:label "Talk title"]
              [:input {:name "talk-title" :value (:title session)}]]
             [:button.ui.small.primary.disabled.button
              {:type "button" :disabled true} "Save title"]])
          [:div.field-hint "No talk attached yet."])]]]]))

(defn- dark-row [event speaker]
  [:div.announce-admin-row.dark
   [:div.announce-bulb.dark "◌"]
   [:div.announce-person
    [:strong (:name speaker)]
    (when-not (str/blank? (:company speaker)) [:span (:company speaker)])]
   [:div.announce-readiness
    "no identity yet (wall name)"]
   [:div.announce-actions
    [:form {:method "post"
            :action (str "/api/events/" (:slug event) "/speakers/adopt")}
     [:input {:type "hidden" :name "name" :value (:name speaker)}]
     [:button.ui.small.primary.button {:type "submit"} "✦ Light their page"]]]])

(defn announce-page
  [event {:keys [person roster ready base-url stats edit]}]
  (let [{:keys [lit total]} stats
        dark (filterv (comp not :id) roster)]
    (organizer-layout/organizer-shell
      (str "Announce Speakers — " (:name event))
      {:event event :active :announce :person person :crumb "Announce Speakers"
       :share? true}
      (organizer-layout/header
        "Announce Speakers"
        "Light every speaker's shareable page, then give them the words to post it.")
      (pipeline/stage-strip event :announce)
      [:div.announce-admin
       (coverage lit total)

       (when (seq ready)
         [:section.announce-admin-section
          [:h2.announce-admin-kicker "READY TO ANNOUNCE"]
          (for [submission ready]
            (ready-row event submission))])

       [:section.announce-admin-section
        [:div.announce-admin-section-head
         [:h2.announce-admin-kicker "THE MARQUEE"]
         (when (>= (count dark) 2)
           [:form {:method "post"
                   :action (str "/api/events/" (:slug event) "/speakers/adopt-all")}
            [:button.ui.small.primary.button {:type "submit"}
             "✦ Light all " (count dark)]])]
        (if (seq roster)
          (for [speaker roster]
            (if (:id speaker)
              (lit-row event base-url speaker edit)
              (dark-row event speaker)))
          [:div.empty-state
           [:p [:strong "The marquee is empty."]]
           [:p "Create an invited speaker or accept and inform a CFP submission."]])]

       [:div.announce-admin-footer
        [:a.ui.primary.button {:href (str "/events/" (:slug event) "/speakers/new")}
         "+ Create Speaker"]]])))

(defn- duplicate-warning-panel [event values matches]
  [:div.ui.warning.message {:id "duplicate-warning"}
   [:div.header "Possible duplicate — nothing has been saved yet"]
   [:p
    (str "You already have "
         (if (= 1 (count matches)) "a contact" (str (count matches) " contacts"))
         " named " (:name values)
         ", with a different email address. This is only a heads-up: two real "
         "people can share a name.")]
   [:div.ui.relaxed.list
    (for [match matches]
      [:div.item
       [:strong (:name match)]
       [:span " · " (or (:email match) "no email on file")]
       (when-not (str/blank? (str (:organization match)))
         [:span " · " (:organization match)])])]
   [:form {:method "post"
           :action (str "/api/events/" (:slug event) "/speakers/create")}
    (for [field [:name :email :org :title :headshot-url :bio]]
      [:input {:type "hidden" :name (name field)
               :value (str (or (get values field) ""))}])
    (when (:announce? values)
      [:input {:type "hidden" :name "announce" :value "1"}])
    [:input {:type "hidden" :name "confirm-duplicate" :value "yes"}]
    [:button.ui.primary.button {:type "submit"}
     (str "Create " (:name values) " (" (:email values)
          ") as a separate person")]]
   [:p.field-hint
    "Or correct the name or email below and submit again. Existing contacts are never changed either way."]])

(defn create-speaker-page
  [event {:keys [person values errors duplicate-warning legacy-capture?]}]
  (let [v #(str (or (get values %) ""))]
    (organizer-layout/organizer-shell
      (str "Add a Speaker (Manually, Not from CFP) — " (:name event))
      {:event event :active :create-speaker :person person
       :crumb "Add a Speaker (Manually, Not from CFP)"}
      (organizer-layout/header
        "Add a Speaker (Manually, Not from CFP)"
        "Create their private identity and public speaker card without a CFP submission.")
      [:div.ui.segment.announce-create
       (when legacy-capture?
         [:div.ui.info.message
          "Quick capture has retired. Add the speaker details here; pasted messages, provenance, time, and location are not stored."])
       (when duplicate-warning
         (duplicate-warning-panel event values (:matches duplicate-warning)))
       [:form.ui.form
        {:method "post"
         :action (str "/api/events/" (:slug event) "/speakers/create")}
        [:input {:type "hidden" :name "announce" :value "1"}]
        [:div.field {:class (when (:name errors) "error")}
         [:label "Name" (form-controls/req-mark true)]
         [:input {:type "text" :name "name" :required true
                  :value (v :name) :placeholder "Ann Perry" :data-ghost-fill ""}]
         (form-controls/field-errors errors :name)]
        [:div.field {:class (when (:email errors) "error")}
         [:label "Private email" (form-controls/req-mark true)]
         [:input {:type "email" :name "email" :required true
                  :value (v :email) :placeholder "speaker@example.com"
                  :autocomplete "email"}]
         [:div.field-hint "Their private speaker portal and invitations use this address."]
         (form-controls/field-errors errors :email)]
        [:div.two.fields
         [:div.field
          [:label "Organization"]
          [:input {:type "text" :name "org" :value (v :org)
                   :placeholder "IT Revolution" :data-ghost-fill ""}]]
         [:div.field
          [:label "Title / tagline"]
          [:input {:type "text" :name "title" :value (v :title)
                   :placeholder "Conference Chair" :data-ghost-fill ""}]]]
        [:div.field {:class (when (:headshot-url errors) "error")}
         [:label "Headshot URL"]
         [:input {:type "url" :name "headshot-url" :value (v :headshot-url)
                  :placeholder "https://…"}]
         (form-controls/field-errors errors :headshot-url)]
        [:div.field {:class (when (:website-url errors) "error")}
         [:label "Profile / archive URL"]
         [:input {:type "url" :name "website-url" :value (v :website-url)
                  :placeholder "https://…"}]
         (form-controls/field-errors errors :website-url)]
        [:div.field
         [:label "Bio"]
         [:textarea {:name "bio" :rows 6
                     :placeholder "Ann builds gatherings where practitioners teach each other."
                     :data-ghost-fill ""}
          (v :bio)]]
        [:button.ui.primary.button {:type "submit"} "Add Speaker"]]])))
