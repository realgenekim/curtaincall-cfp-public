(ns cfp-scheduler-killer.views.crm
  (:require
   [cfp-scheduler-killer.views.format :as format]
   [cfp-scheduler-killer.views.organizer-layout :as organizer-layout]
   [clojure.string :as str]))

(defn- title-case [value]
  (->> (str/split (str value) #"[-_]")
       (map str/capitalize)
       (str/join " ")))

(defn- selected-option [value selected label]
  [:option {:value value :selected (= (str value) (str selected))} label])

(defn- metric [value label]
  [:div.statistic
   [:div.value value]
   [:div.label label]])

(defn- contact-row [contact]
  [:tr
   [:td
    [:a {:href (str "/people/" (:person-id contact))}
     [:strong (:name contact)]]
    (when (:repeat? contact)
      [:div.ui.tiny.teal.label "Repeat relationship"])]
   [:td [:a {:href (str "mailto:" (:email contact))} (:email contact)]]
   [:td (or (:organization contact) [:span.field-hint "—"])]
   [:td (for [role (:roles contact)]
          [:span.ui.tiny.basic.label (title-case role)])]
   [:td (:event-count contact)]
   [:td (:talk-count contact)]
   [:td [:a.ui.tiny.basic.button {:href (str "/people/" (:person-id contact))}
         "History"]]])

(defn- import-preview-table [{:keys [rows missing-headers valid-count error-count]}]
  [:div.ui.segment
   [:h3.ui.header "Import preview"]
   (when (seq missing-headers)
     [:div.ui.negative.message
      (str "Missing required columns: " (str/join ", " missing-headers))])
   [:p (str valid-count " valid · " error-count " with errors")]
   [:table.ui.celled.compact.table
    [:thead [:tr [:th "Row"] [:th "Name"] [:th "Email"] [:th "Status"] [:th "Result"]]]
    [:tbody
     (for [{:keys [row-number values errors]} rows]
       [:tr {:class (when (seq errors) "negative")}
        [:td row-number]
        [:td (:name values)]
        [:td (:email values)]
        [:td (:status values)]
        [:td (if (seq errors) (str/join "; " (vals errors)) "Ready")]])]]])

(def pipeline-stages
  [[:relationship "Relationship"] [:invited "Invited"] [:submitted "Submitted"]
   [:confirmed "Confirmed"] [:withdrawn "Withdrawn"]])

(defn- pipeline-strip [pipeline]
  [:div.ui.five.cards
   (for [[stage label] pipeline-stages]
     [:div.card
      [:div.content
       [:div.header label]
       [:div.meta (str (count (get pipeline stage)) " contacts")]
       [:div.description
        (if-let [names (seq (map :name (take 3 (get pipeline stage))))]
          (str/join ", " names)
          "None")]]])])

(defn- filter-query [filters]
  (->> [:q :organization :role :event :tag]
       (keep (fn [key]
               (when-let [value (some-> (get filters key) str not-empty)]
                 (str (name key) "="
                      (java.net.URLEncoder/encode value "UTF-8")))))
       (str/join "&")))

(defn- saved-segments [segments events filters]
  [:div.ui.segment
   [:h2.ui.header "Saved segments"
    [:div.sub.header "Keep a useful relationship query without creating a second contact list."]]
   (when (seq segments)
     [:div.ui.labels
      (for [segment segments]
        [:div.ui.basic.label
         [:a {:href (str "/people?" (filter-query (:filters segment)))} (:name segment)]
         [:form.ui.mini.form {:method "post"
                              :action (str "/api/people/segments/" (:id segment) "/remove")}
          [:button.ui.mini.basic.button {:type "submit"} "×"]]])])
   (when-let [event (first events)]
     [:form.ui.form {:method "post" :action "/api/people/segments"}
      [:input {:type "hidden" :name "event-id" :value (:id event)}]
      (for [[key value] filters :when (seq (str value))]
        [:input {:type "hidden" :name (name key) :value value}])
      [:div.two.fields
       [:div.field [:label "Segment name"]
        [:input {:name "name" :required true :maxlength 80
                 :placeholder "Prior enterprise-AI speakers"}]]
       [:div.field [:label "Current filter"]
        [:input {:value (or (not-empty (filter-query filters)) "All contacts")
                 :readonly true}]]]
      [:button.ui.small.button {:type "submit"} "Save current segment"]])])

(defn people-page
  [{:keys [viewer contacts all-contacts events filters organizations roles tags stats
           segments pipeline message error csv-text import-event-id import-preview]}]
  (organizer-layout/organizer-shell
    "People · CFP Scheduler Killer"
    {:active :people :person viewer}
    (organizer-layout/header
      "People"
      "One canonical relationship history across every event you organize."
      [:a.ui.basic.button {:href "/events"} "All events"])
    (when message [:div.ui.positive.message message])
    (when error [:div.ui.negative.message error])
    [:div.ui.four.mini.statistics
     (metric (:contacts stats) "Contacts")
     (metric (:repeat-contacts stats) "Repeat contacts")
     (metric (:organizations stats) "Organizations")
     (metric (:events stats) "Events")]
    [:div.ui.segment
     [:h2.ui.header "Sourcing pipeline"
      [:div.sub.header "A cross-event projection of relationship and speaker status; no shadow workflow."]]
     (pipeline-strip pipeline)]
    (saved-segments segments events filters)
    [:div.ui.segment
     [:form.ui.form {:method "get" :action "/people"}
      [:div.fields
       [:div.five.wide.field
        [:label "Search"]
        [:input {:type "search" :name "q" :value (or (:q filters) "")
                 :placeholder "Name, email, organization, talk, or event"}]]
       [:div.three.wide.field
        [:label "Organization"]
        [:select.ui.dropdown {:name "organization"}
         (selected-option "" (:organization filters) "All organizations")
         (for [organization organizations]
           (selected-option organization (:organization filters) organization))]]
       [:div.three.wide.field
        [:label "Relationship"]
        [:select.ui.dropdown {:name "role"}
         (selected-option "" (:role filters) "All relationships")
         (for [role roles]
           (selected-option role (:role filters) (title-case role)))]]
       [:div.three.wide.field
        [:label "Event"]
        [:select.ui.dropdown {:name "event"}
         (selected-option "" (:event filters) "All events")
         (for [event events]
           (selected-option (:id event) (:event filters) (:name event)))]]
       [:div.two.wide.field
        [:label "Tag"]
        [:select.ui.dropdown {:name "tag"}
         (selected-option "" (:tag filters) "All tags")
         (for [tag tags]
           (selected-option tag (:tag filters) tag))]]]
      [:button.ui.primary.button {:type "submit"} "Filter"]
      [:a.ui.basic.button {:href "/people"} "Clear"]]]
    [:div.ui.segment
     [:h2.ui.header (str "Contacts (" (count contacts) ")")
      [:div.sub.header
       (if (= (count contacts) (count all-contacts))
         "Everyone connected to an event in your organizing world."
         (str "Filtered from " (count all-contacts) " contacts."))]]
     (if (seq contacts)
       [:table.ui.celled.compact.table
        [:thead [:tr [:th "Name"] [:th "Email"] [:th "Organization"]
                 [:th "Relationship"] [:th "Events"] [:th "Talks"] [:th ""]]]
        [:tbody (for [contact contacts] (contact-row contact))]]
       [:div.ui.info.message
        [:div.header "No matching contacts"]
        [:p "Clear a filter or search for another name, email, organization, talk, or event."]])]
    [:div.ui.segment
     [:h2.ui.header "Import contacts CSV"
      [:div.sub.header
       "Choose the event that owns this import. Existing emails reuse the canonical person; they never create duplicates."]]
     [:form.ui.form {:method "post" :enctype "multipart/form-data"
                     :action "/api/people/import/preview"}
      [:div.two.fields
       [:div.field [:label "Target event"]
        [:select.ui.dropdown {:name "event-id" :required true}
         (for [event events]
           [:option {:value (:id event) :selected (= (:id event) import-event-id)}
            (:name event)])]]
       [:div.field [:label "CSV file"]
        [:input {:type "file" :name "csv-file" :accept ".csv,text/csv" :required true}]]]
      [:button.ui.button {:type "submit"} "Preview CSV import"]]
     (when import-preview
       (list
         (import-preview-table import-preview)
         [:form.ui.form {:method "post" :action "/api/people/import"}
          [:input {:type "hidden" :name "event-id" :value import-event-id}]
          [:input {:type "hidden" :name "csv-text" :value csv-text}]
          [:button.ui.primary.button
           {:type "submit" :disabled (not (:valid? import-preview))}
           "Import contacts into event"]]))]
    [:div.ui.segment
     [:h2.ui.header "Human-reviewed outreach"
      [:div.sub.header "Select people, resolve every recipient's preview, then record the draft. Nothing sends automatically."]]
     [:a.ui.primary.button {:href "/people/outreach"} "Compose outreach"]]))

(defn- event-option [event]
  [:option {:value (:id event)} (:name event)])

(defn- connection-row [connection]
  [:tr
   [:td [:a {:href (str "/events/" (:event-slug connection))}
         (:event-name connection)]]
   [:td (for [role (sort (:roles connection))]
          [:span.ui.tiny.basic.label (title-case role)])]
   [:td (or (:speaker-status connection) [:span.field-hint "—"])]
   [:td (if (seq (:talks connection))
          [:ul.ui.list (for [talk (:talks connection)] [:li talk])]
          [:span.field-hint "—"])]])

(defn- tag-chip [person-id tag-record]
  [:form.ui.tiny.form {:method "post"
                       :action (str "/api/people/" person-id "/tags/remove")}
   [:input {:type "hidden" :name "event-id" :value (:event-id tag-record)}]
   [:input {:type "hidden" :name "tag" :value (:tag tag-record)}]
   [:button.ui.tiny.basic.label {:type "submit"
                                 :title "Remove this event-scoped tag"}
    (:tag tag-record) " ×"]])

(defn person-page
  [{:keys [viewer contact events notes activity message error]}]
  (let [person-id (:person-id contact)
        profile (:profile contact)]
    (organizer-layout/organizer-shell
      (str (:name contact) " · People")
      {:active :people :person viewer}
      (organizer-layout/header
        (:name contact)
        (or (:tagline profile) "Cross-event relationship history")
        [:a.ui.basic.button {:href "/people"} "← People"])
      (when message [:div.ui.positive.message message])
      (when error [:div.ui.negative.message error])
      [:div.ui.two.column.stackable.grid
       [:div.column
        [:div.ui.segment
         [:h2.ui.header "Contact"]
         [:div.ui.relaxed.list
          [:div.item [:strong "Email" [:div.description]
                      [:a {:href (str "mailto:" (:email contact))} (:email contact)]]]
          [:div.item [:strong "Organization" [:div.description]
                      (or (:organization contact) "Not recorded")]]
          [:div.item [:strong "Events"] [:div.description (:event-count contact)]]
          [:div.item [:strong "Talks"] [:div.description (:talk-count contact)]]]
         (when (seq (:bio profile))
           [:div.ui.message [:div.header "Bio"] [:p (:bio profile)]])]
        [:div.ui.segment
         [:h2.ui.header "Tags"
          [:div.sub.header "Event-scoped organizer labels; never speaker-visible."]]
         (if (seq (:tag-records contact))
           [:div.ui.labels
            (for [tag-record (:tag-records contact)]
              (tag-chip person-id tag-record))]
           [:p.field-hint "No tags yet."])
         [:form.ui.form {:method "post"
                         :action (str "/api/people/" person-id "/tags/add")}
          [:div.two.fields
           [:div.field [:label "Event"]
            [:select.ui.dropdown {:name "event-id" :required true}
             (for [event events] (event-option event))]]
           [:div.field [:label "Tag"]
            [:input {:name "tag" :required true :maxlength 40
                     :placeholder "approval-risk, prior-speaker…"}]]]
          [:button.ui.small.button {:type "submit"} "Add tag"]]]]
       [:div.column
        [:div.ui.segment
         [:h2.ui.header "Push into event"
          [:div.sub.header "Reuse this identity in an invited-speaker lane; email matches never duplicate people."]]
         [:form.ui.form {:method "post"
                         :action (str "/api/people/" person-id "/events/add")}
          [:div.field [:label "Target event"]
           [:select.ui.dropdown {:name "event-id" :required true}
            (for [event events] (event-option event))]]
          [:button.ui.primary.button {:type "submit"} "Push contact into event"]]]
        [:div.ui.segment
         [:h2.ui.header "Internal notes"
          [:div.sub.header "Human diligence, attached to one event and visible only to its organizers."]]
         [:form.ui.form {:method "post"
                         :action (str "/api/people/" person-id "/notes")}
          [:div.field [:label "Event"]
           [:select.ui.dropdown {:name "event-id" :required true}
            (for [event events] (event-option event))]]
          [:div.field [:label "Note"]
           [:textarea {:name "body" :required true :maxlength 2000 :rows 3
                       :placeholder "What should the next organizer know?"}]]
          [:button.ui.small.button {:type "submit"} "Add internal note"]]
         (if (seq notes)
           [:div.ui.divided.relaxed.list
            (for [note notes]
              [:div.item
               [:div.content
                [:div.header (:body note)]
                [:div.description
                 (str (:actor note) " · " (format/fmt-when (:at note) "UTC"))]]])]
           [:p.field-hint "No internal notes yet."])]]]
      [:div.ui.segment
       [:h2.ui.header "Events and roles"]
       [:table.ui.celled.compact.table
        [:thead [:tr [:th "Event"] [:th "Relationship"] [:th "Speaker status"] [:th "Talks"]]]
        [:tbody (for [connection (:connections contact)]
                  (connection-row connection))]]]
      [:div.ui.segment
       [:h2.ui.header "Activity history"
        [:div.sub.header "Facts from only the events you are authorized to organize."]]
       (if (seq activity)
         [:table.ui.compact.very.basic.table
          [:thead [:tr [:th "When"] [:th "Event"] [:th "Fact"] [:th "Actor"]]]
          [:tbody
           (for [item activity]
             [:tr
              [:td (format/fmt-when (:at item) "UTC")]
              [:td (:event-name item)]
              [:td [:code (:type item)]]
              [:td (:actor item)]])]]
         [:p.field-hint "No matching activity facts yet."])])))

(defn outreach-page
  [{:keys [viewer contacts events selected-person-ids event-id subject body preview message error]}]
  (let [selected (set selected-person-ids)]
    (organizer-layout/organizer-shell
      "Outreach composer · People"
      {:active :people :person viewer}
      (organizer-layout/header
        "Outreach composer"
        "A human-reviewed draft workspace. This screen never sends mail automatically."
        [:a.ui.basic.button {:href "/people"} "← People"])
      (when message [:div.ui.positive.message message])
      (when error [:div.ui.negative.message error])
      [:form.ui.form {:method "post" :action "/api/people/outreach/preview"}
       [:div.field [:label "Owning event"]
        [:select.ui.dropdown {:name "event-id" :required true}
         (for [event events]
           [:option {:value (:id event) :selected (= (:id event) event-id)}
            (:name event)])]]
       [:div.field [:label "Subject template"]
        [:input {:name "subject" :required true :maxlength 200 :value (or subject "")
                 :placeholder "Invitation for {name}"}]]
       [:div.field [:label "Message template"]
        [:textarea {:name "body" :required true :maxlength 10000 :rows 6
                    :placeholder "Hello {name}…"}
         (or body "")]
        [:div.field-hint "Tokens: {name}, {email}, {organization}"]]
       [:div.field
        [:label "Recipients"]
        [:table.ui.celled.compact.table
         [:thead [:tr [:th "Select"] [:th "Name"] [:th "Email"] [:th "Organization"]]]
         [:tbody
          (for [contact contacts]
            [:tr
             [:td [:input {:type "checkbox" :name "person-id"
                           :value (:person-id contact)
                           :checked (contains? selected (:person-id contact))}]]
             [:td (:name contact)]
             [:td (:email contact)]
             [:td (or (:organization contact) "—")]])]]]
       [:button.ui.primary.button {:type "submit"} "Preview every recipient"]]
      (when (seq preview)
        [:div.ui.segment
         [:h2.ui.header "Resolved recipient previews"
          [:div.sub.header "Read these before recording the draft. There is still no send action."]]
         [:table.ui.celled.table
          [:thead [:tr [:th "Recipient"] [:th "Subject"] [:th "Resolved message"]]]
          [:tbody
           (for [item preview]
             [:tr
              [:td [:strong (:name item)] [:div.field-hint (:email item)]]
              [:td (:subject item)]
              [:td [:pre (:body item)]]])]]
         [:form.ui.form {:method "post" :action "/api/people/outreach/record"}
          [:input {:type "hidden" :name "event-id" :value event-id}]
          [:input {:type "hidden" :name "subject" :value subject}]
          [:input {:type "hidden" :name "body" :value body}]
          (for [person-id selected-person-ids]
            [:input {:type "hidden" :name "person-id" :value person-id}])
          [:button.ui.positive.button {:type "submit"} "Record human-reviewed draft"]]]))))
