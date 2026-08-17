(ns cfp-scheduler-killer.views.speakers
  (:require
   [cfp-scheduler-killer.domain.speakers :as speaker-domain]
   [cfp-scheduler-killer.public-catalog :as public-catalog]
   [cfp-scheduler-killer.speaker-csv :as speaker-csv]
   [cfp-scheduler-killer.views.avatar :as avatar]
   [cfp-scheduler-killer.views.compact-person :as compact-person]
   [cfp-scheduler-killer.views.organizer-layout :as organizer-layout]
   [cfp-scheduler-killer.views.pipeline :as pipeline]
   [cfp-scheduler-killer.views.public-widgets :as public-widgets]
   [clojure.data.json :as json]
   [clojure.string :as str]
   [datastar-kit.ds :as ds]))

(defn- status-select [name selected]
  [:select.ui.dropdown {:name name}
   (for [status (sort speaker-domain/statuses)]
     [:option {:value status :selected (= status selected)} status])])

(defn- return-fields [query status profile]
  (list
    [:input {:type "hidden" :name "return-q" :value (or query "")}]
    [:input {:type "hidden" :name "return-status" :value (or status "")}]
    [:input {:type "hidden" :name "return-profile" :value (or profile "")}]))

(defn- lifecycle-history-label [{:keys [type status label talk-title]}]
  (case type
    "speaker.added-to-event" (str "Added to event" (when status (str " as " status)))
    "speaker.status-changed" (str "Speaker status changed" (when status (str " to " status)))
    "speaker.details-updated" "Organizer details updated"
    "submission.created" (str "Submission received" (when talk-title (str ": " talk-title)))
    "submission.speaker-assigned" (str "Assigned to session" (when talk-title (str ": " talk-title)))
    "submission.speaker-unassigned" (str "Removed from session" (when talk-title (str ": " talk-title)))
    "submission.status-changed" (str "Submission moved" (when status (str " to " status)))
    "submission.notified" (str "Speaker informed" (when status (str ": " status)))
    "task.installed" (str "Task assigned" (when label (str ": " label)))
    "task.completed" (str "Task completed" (when label (str ": " label)))
    "task.chase-recorded" (str "Follow-up recorded" (when label (str ": " label)))
    "email.sent" (str "Follow-up delivered" (when label (str ": " label)))
    type))

(defn- lifecycle-panel [{:keys [status pending-tasks history]}]
  [:div.speaker-lifecycle
   [:span.ui.tiny.label (or status "Unknown")]
   [:span.speaker-lifecycle-tasks
    (if (seq pending-tasks)
      (str (count pending-tasks) " pending task" (when (> (count pending-tasks) 1) "s"))
      "No pending tasks")
    (when (seq pending-tasks)
      [:span.speaker-lifecycle-task-list
       (for [{:keys [submission-id key label due-on last-chased-at chase-count]} pending-tasks]
         [:span {:data-submission-id submission-id :data-task-key key}
          " · " (or label key)
          (when due-on [:span.field-hint (str " · due " due-on)])
          (when last-chased-at
            [:span.field-hint
             (str " · last follow-up " last-chased-at
                  " · " chase-count " delivered")])])])]
   (when (seq history)
     [:span.speaker-lifecycle-history
      [:strong "Updates: "]
      (interpose
        " · "
        (for [{:keys [type at actor] :as entry} history]
          [:span.speaker-lifecycle-update
           {:data-lifecycle-type type
            :title (str (when at (str at))
                        (when (and at actor) " · ")
                        (when actor actor))}
           (lifecycle-history-label entry)]))])])

(defn- speaker-row [event speaker query status profile]
  (let [base (str "/api/events/" (:slug event) "/speakers/" (:person-id speaker))
        title (some-> (:title speaker) str str/trim not-empty)
        organization (or (some-> (:event-organization speaker) str str/trim not-empty)
                         (some-> (:profile-organization speaker) str str/trim not-empty))
        edit-url (str "/events/" (:slug event) "/speakers/" (:person-id speaker))
        talks (:talks speaker)]
    [:tbody.speaker-ledger {:id (str "speaker-" (:person-id speaker))}
     [:tr.ledger-row.speaker-ledger-row
      [:td.lg-person
       (compact-person/person-link
         {:href edit-url
          :image-src (or (some-> (:headshot-url speaker) str/trim not-empty)
                         (avatar/pool-face (:person-id speaker)))
          :image-alt (str (:name speaker) " headshot")
          :name (:name speaker)
          :secondary [:div.speaker-ledger-email (:email speaker)]})]
      [:td.speaker-ledger-sessions
       (if (seq talks)
         [:strong (str (count talks) " session" (when (> (count talks) 1) "s"))]
         [:span.field-hint "None yet"])]
      [:td.speaker-ledger-status
       [:span.ui.tiny.label (:status speaker)]]
      [:td.speaker-ledger-profile
       (if (:profile-complete? speaker)
         [:span.ui.tiny.label "Complete ✓"]
         [:span.ui.tiny.orange.label
          (if (seq (:profile-missing speaker))
            (str "Missing " (str/join " + " (:profile-missing speaker)))
            "Incomplete")])]
      [:td.lg-acts.speaker-ledger-actions
       [:form {:method "post" :action (str base "/portal-invite")}
        (return-fields query status profile)
        [:button.act-read.speaker-invite-action {:type "submit"} "Send portal invite"]]
       (when-let [submission-id (:pending-submission-id speaker)]
         [:a.act-read
          {:href (str "/events/" (:slug event) "/inform#decision-" submission-id)}
          "Review message"])
       [:a.act-read {:href edit-url} "Edit Speaker Details"]]]
     (when (or title organization)
       [:tr.ledger-title-row.speaker-ledger-title-row
        [:td.lg-title-cell {:colspan 5}
         [:div.lg-title-indent
          (when title [:span.b-facts title])
          (when (and title organization) [:span.b-facts " · "])
          (when organization [:span.b-facts organization])]]])
     (for [talk talks]
       [:tr.ledger-title-row.speaker-ledger-talk-row
        [:td.lg-title-cell {:colspan 5}
         [:div.lg-title-indent
          [:span.lg-title-link talk]]]])
     [:tr.speaker-ledger-details
      [:td {:colspan 5}
       (lifecycle-panel (:lifecycle speaker))]]]))

(defn- speaker-edit-form [event speaker custom-fields error]
  (let [base (str "/api/events/" (:slug event) "/speakers/" (:person-id speaker))]
    [:form.ui.form.speaker-edit-form {:method "post" :action base}
     [:input {:type "hidden" :name "profile-edit" :value "true"}]
     (when error
       [:div.ui.negative.message {:role "alert"} error])
     [:div.announce-edit-kicker "PROGRAM DETAILS"]
     [:h4.ui.header "Shared speaker profile"
      [:div.sub.header "Updates the same profile the speaker sees in their portal."]]
     [:div.two.fields
      [:div.required.field
       [:label "Name"]
       [:input {:name "name" :required true :value (:name speaker)}]]
      [:div.field
       [:label "Email"]
       [:input {:type "email" :value (:email speaker) :readonly true}]
       [:div.field-hint "The speaker's permanent sign-in identity."]]]
     [:div.two.fields
      [:div.field
       [:label "Tagline"]
       [:input {:name "tagline" :value (or (:tagline speaker) "")}]
       [:div.field-hint "Shared across events and shown under the speaker's name."]]
      [:div.field
       [:label "Organization"]
       [:input {:name "org" :value (or (:profile-organization speaker) "")}]
       [:div.field-hint "Shared across events and maintained in the speaker portal."]]]
     [:div.field
      [:label "Bio"]
      [:textarea {:name "bio" :rows 4} (or (:bio speaker) "")]]
     [:div.three.fields
      [:div.field
       [:label "Headshot URL"]
       [:input {:name "headshot-url" :type "url"
                :value (or (:headshot-url speaker) "")}]]
      [:div.field
       [:label "LinkedIn URL"]
       [:input {:name "linkedin-url" :type "url"
                :value (or (:linkedin-url speaker) "")}]]
      [:div.field
       [:label "Website URL"]
       [:input {:name "website-url" :type "url"
                :value (or (:website-url speaker) "")}]]]
     [:h4.ui.header "Event details"
      [:div.sub.header "Used only for this event."]]
     [:div.three.fields
      [:div.field
       [:label "Status"]
       (status-select "status" (:status speaker))]
      [:div.field
       [:label "Job title"]
       [:input {:name "title" :value (or (:title speaker) "")}]]
      [:div.field
       [:label "Event organization override"]
       [:input {:name "organization"
                :value (or (:event-organization speaker) "")}]
       [:div.field-hint "Leave blank to use the shared profile organization."]]]
     [:div.field
      [:label "Event notes"]
      [:textarea {:name "notes" :rows 2} (or (:notes speaker) "")]]
     [:div.field
      [:div.ui.checkbox
       [:input (cond-> {:type "checkbox" :name "announce" :value "1"}
                 (:published? speaker) (assoc :checked true)
                 (not (:manual? speaker)) (assoc :disabled true))]
       [:label "Publish as speaker"]]
      (when-not (:manual? speaker)
        [:div.field-hint
         "This CFP speaker is public through their accepted, informed session."])]
     (when (seq custom-fields)
       [:div.speaker-custom-fields
        [:h4.ui.header "Travel and logistics"
         [:div.sub.header
          "Event-specific custom fields. Organizer and speaker portal edits stay in sync."]]
        (for [{:keys [id label type required]} custom-fields]
          [:div.field {:class (when required "required")}
           [:label label]
           (if (= "textarea" type)
             [:textarea {:name (str "custom-" id) :rows 4}
              (get-in speaker [:custom-values id] "")]
             [:input {:name (str "custom-" id)
                      :type "text"
                      :value (get-in speaker [:custom-values id] "")}])])])
     [:h4.ui.header "Talks"]
     (let [sessions (:program-sessions speaker)
           talks (:talks speaker)]
       (cond
         (seq sessions)
         (for [session sessions]
           [:div.field
            [:label "Talk title"]
            [:input {:value (:title session) :readonly true}]])

         (seq talks)
         (for [talk talks]
           [:div.field
            [:label "Talk title"]
            [:input {:value talk :readonly true}]])

         :else
         [:div.field-hint "No talk attached yet."]))
     [:button.ui.primary.button {:type "submit"} "Save Speaker Details"]]))

(defn- social-share-card [event speaker]
  (let [public-speaker (some #(when (= (:person-id speaker) (:id %)) %)
                             (public-catalog/public-speakers event))]
    [:section.ui.segment
     [:h3.ui.header "Social share card"]
     (if public-speaker
       (let [{:keys [title description url image]}
             (public-widgets/speaker-social-metadata event "" public-speaker)]
         [:div
          [:article {:style "border:1px solid #d6dbe0; border-radius:8px; overflow:hidden; max-width:560px; background:#fff;"}
           [:img {:src image :alt (str "Social preview for " (:name public-speaker))
                  :style "display:block; width:100%; aspect-ratio:1.91; object-fit:cover; background:#eef2f5;"}]
           [:div {:style "padding:0.9em 1em;"}
            [:div {:style "font-size:0.75em; color:#667085; text-transform:uppercase; letter-spacing:0.06em;"}
             "Curtain Call"]
            [:strong title]
            [:p {:style "margin:0.35em 0 0; color:#475467;"} description]]]
          [:p.field-hint {:style "margin-top:0.8em;"}
           "This public profile unfurls as a rich card on Twitter/LinkedIn/Slack."]
          [:div {:style "display:flex; gap:0.6em; align-items:center;"}
           [:span.cfp-url url]
           [:button.ui.small.basic.button
            {:type "button" :data-copy-link url :onclick "copyLink(this)"}
            "Copy link"]]])
       [:p.field-hint
        "This card appears when the speaker's public profile is published."])]))

(defn speaker-page [event {:keys [person speaker custom-fields error]}]
  (organizer-layout/organizer-shell
    (str "Edit " (:name speaker) " · " (:name event))
    {:event event :person person :active :speakers}
    [:div
     (organizer-layout/header
       "Edit Speaker Details"
       (:name speaker)
       [:a.ui.basic.button
        {:href (str "/events/" (:slug event) "/speakers#speaker-" (:person-id speaker))}
        "Back to Speakers"])
     [:div.ui.segment.speaker-edit-page
      (speaker-edit-form event speaker custom-fields error)]
     (social-share-card event speaker)]))

(defn- duplicate-match-line [match]
  [:div.item
   [:strong (:name match)]
   [:span " · " (or (:email match) "no email on file")]
   (when-not (str/blank? (str (:organization match)))
     [:span " · " (:organization match)])
   (when (seq (:connections match))
     [:div.field-hint
      (str "Already on: "
           (str/join ", " (distinct (map :event-name (:connections match)))))])])

(defn- duplicate-warning-panel
  "ADVISORY. Nothing has been written when this renders, and the primary button
   writes the new person anyway — one click, no extra step."
  [event {:keys [values matches]}]
  [:div.ui.segment {:id "duplicate-warning"}
   [:div.ui.warning.message
    [:div.header "Possible duplicate — nothing has been saved yet"]
    [:p
     (str "You already have "
          (if (= 1 (count matches)) "a contact" (str (count matches) " contacts"))
          " named " (str (:name values))
          ", with a different email address. This is only a heads-up: two real "
          "people can share a name.")]
    [:div.ui.relaxed.list
     (for [match matches] (duplicate-match-line match))]]
   [:form.ui.form {:method "post"
                   :action (str "/api/events/" (:slug event) "/speakers")}
    [:input {:type "hidden" :name "name" :value (or (:name values) "")}]
    [:input {:type "hidden" :name "email" :value (or (:email values) "")}]
    [:input {:type "hidden" :name "status" :value (or (:status values) "Invited")}]
    [:input {:type "hidden" :name "confirm-duplicate" :value "yes"}]
    [:button.ui.primary.button {:type "submit"}
     (str "Add " (str (:name values)) " (" (str (:email values))
          ") as a separate person")]]
   [:p.field-hint
    "Or correct the name or email in "
    [:a {:href "#add-speaker"} "Add a speaker"]
    " below and submit again. Existing contacts are never changed either way."]])

(def ^:private primary-preview-columns #{:name :email :status})

(defn- preview-value [present-columns key value]
  (cond
    (not (contains? present-columns key))
    [:span.field-hint "Not provided in CSV"]

    (str/blank? (str value))
    [:span.field-hint "Empty"]

    :else
    value))

(defn- import-details-cell [values present-columns]
  [:td
   (for [[key label] speaker-csv/accepted-columns
         :when (not (contains? primary-preview-columns key))]
     [:div
      [:strong (str label ": ")]
      (preview-value present-columns key (get values key))])])

(defn- import-preview-table
  [{:keys [rows present-columns missing-headers valid-count error-count]}]
  [:div.ui.segment
   [:h3.ui.header "Import preview"]
   (when (seq missing-headers)
     [:div.ui.negative.message
      (str "Missing required columns: " (str/join ", " missing-headers))])
   [:p (str valid-count " valid · " error-count " with errors")]
   [:table.ui.celled.compact.table
    [:thead
     [:tr [:th "Row"] [:th "Name"] [:th "Email"] [:th "Status"]
      [:th "Imported details"] [:th "Result"]]]
    [:tbody
     (for [{:keys [row-number values errors]} rows]
       [:tr {:class (when (seq errors) "negative")}
        [:td row-number]
        [:td (:name values)]
        [:td (:email values)]
        [:td
         (:status values)
         (when-not (contains? present-columns :status)
           [:div.field-hint "Default — Status column not provided"])]
        (import-details-cell values present-columns)
        [:td (if (seq errors)
               (str/join "; " (vals errors))
               "Ready")]])]]])

(defn- csv-import [event csv-text preview]
  [:div.ui.segment
   [:h2.ui.header "Import speakers.csv"
    [:div.sub.header "Preview first. Nothing is written until you confirm."]]
   [:form.ui.form {:method "post"
                   :enctype "multipart/form-data"
                   :action (str "/api/events/" (:slug event) "/speakers/import/preview")}
    [:div.field
     [:label "CSV file"]
     [:input {:type "file" :name "csv-file" :accept ".csv,text/csv"}]]
    [:div.field
     [:label "Or paste CSV"]
     [:textarea {:name "csv-text" :rows 5} (or csv-text "")]]
    [:button.ui.button {:type "submit"} "Preview import"]]
   (when preview
     [:div
      (import-preview-table preview)
      (when (:valid? preview)
        [:form {:method "post"
                :action (str "/api/events/" (:slug event) "/speakers/import")}
         [:textarea {:name "csv-text" :hidden true} csv-text]
         [:button.ui.primary.button {:type "submit"}
          (str "Import " (:valid-count preview) " speakers")]])])])

(defn- custom-fields-panel [event custom-fields]
  [:div.ui.segment
   [:h3.ui.header "Custom person fields"
    [:div.sub.header
     "Ask every speaker in this event for one event-specific detail without changing their global profile."]]
   (when (seq custom-fields)
     [:div.ui.relaxed.list
      (for [{:keys [id label type required]} custom-fields]
        [:div.item
         [:strong label]
         [:span.field-hint " · " type (when required " · Required") " · " id]])])
   [:form.ui.form {:method "post"
                   :action (str "/api/events/" (:slug event) "/speaker-fields")}
    [:div.three.fields
     [:div.field
      [:label "Field label"]
      [:input {:type "text" :name "label" :required true
               :placeholder "Dietary or accessibility needs"
               :data-ghost-fill ""}]]
     [:div.field
      [:label "Answer type"]
      [:select {:name "type"}
       [:option {:value "text"} "Short text"]
       [:option {:value "textarea"} "Long text"]]]
     [:div.field
      [:label "Required"]
      [:div.ui.checkbox
       [:input {:type "checkbox" :name "required"}]
       [:label "Speaker must answer"]]]]
    [:button.ui.small.primary.button {:type "submit"} "Add custom field"]]])

(defn- cospeaker-session-row
  [event session]
  (let [submission-id (:id session)]
    [:div.ui.segment {:id (str "session-" submission-id)}
     [:h3.ui.header (or (get-in session [:answers :talk-title]) "(untitled)")]
     [:div.ui.horizontal.list
      (for [[index speaker] (map-indexed vector (:speakers session))]
        [:div.item
         [:div.content
          [:span (or (:name speaker) "Unknown speaker")]
          [:span.ui.mini.label
           (or (:role speaker)
               (if (zero? index) "Primary speaker" "Co-speaker"))]
          (when (pos? index)
            [:form {:method "post"
                    :action (str "/events/" (:slug event)
                                 "/submissions/" submission-id
                                 "/speakers/" (:person-id speaker) "/remove")}
             [:button.ui.mini.basic.button {:type "submit"}
              "Remove co-speaker"]])]])]
     [:form.ui.form
      {:method "post"
       :action (str "/events/" (:slug event)
                    "/submissions/" submission-id "/speakers")}
      [:div.inline.fields
       [:div.twelve.wide.field
        [:label "Roster speaker email"]
        [:input {:type "email"
                 :name "speaker-email"
                 :list "event-roster-speakers"
                 :placeholder "speaker@example.com"
                 :required true}]]
       [:div.four.wide.field
        [:button.ui.primary.button {:type "submit"} "Assign co-speaker"]]]]]))

(defn- cospeaker-assignment-panel
  [event sessions roster]
  [:div.ui.segment {:id "session-speakers"}
   [:h2.ui.header "Assign or reassign co-speakers"
    [:div.sub.header
     "Reuse anyone already on this event roster. Remove a co-speaker, then assign their replacement."]]
   (if (seq sessions)
     [:div
      (for [session sessions]
        (cospeaker-session-row event session))]
     [:div.empty-state
      "Accepted sessions appear here so you can verify speakers before notifying them."])
   [:datalist {:id "event-roster-speakers"}
    (for [speaker roster]
      [:option {:value (:email speaker)} (:name speaker)])]])

(defn speakers-region
  "The server-owned roster result region. Filter controls live outside this
   element so an SSE morph never closes a native select while it is in use."
  [event speakers query status profile]
  [:div#speaker-roster-results
   (if (seq speakers)
     [:table.board-table.ledger-table.speaker-ledger-table
      [:thead
       [:tr
        [:th.lg-th-person "Speaker · Title · Org"]
        [:th "Sessions"]
        [:th "Status"]
        [:th "Public Profile"]
        [:th]]]
      (for [speaker speakers]
        (speaker-row event speaker query status profile))]
     [:div.empty-state "No speakers match this view."])])

(defn- speaker-state-filters [event workflow status query]
  (let [workspace (speaker-domain/speaker-workspace workflow)]
    [:div.board-controls.board-status-controls
     [:span.field-hint {:style "margin:0;"} "State:"]
     (for [{:keys [label value]} (:filters workspace)]
       [:a.chip
        {:class (when (= (or status "") value) "on")
         :href (str "/events/" (:slug event) "/speakers?view=" (name workflow)
                    (when (seq value)
                      (str "&status=" (java.net.URLEncoder/encode value "UTF-8")))
                    (when (seq query)
                      (str "&q=" (java.net.URLEncoder/encode query "UTF-8"))))}
        label])]))

(defn speakers-page
  [event {:keys [person speakers roster sessions query status profile message csv-text import-preview
                 custom-fields duplicate-warning values row-errors workflow roster-workflow]}]
  (let [workflow (or workflow :manage)
        workspace (speaker-domain/speaker-workspace workflow)
        page-title "Speakers"
        active (if (= :inform workflow) :inform :announce)]
    (organizer-layout/organizer-shell
      (str page-title " · " (:name event))
      {:event event :person person :active active :datastar? true}
      [:div
       (when duplicate-warning (duplicate-warning-panel event duplicate-warning))
       [:div.ui.segment
        {:data-star-signals__ifmissing
         (json/write-str {:speakerq (or query "")
                          :speakerstatus (or status "")
                          :speakerprofile (or profile "")
                          :speakerworkflow (name (or roster-workflow workflow))})}
        [:h1.ui.header page-title
         [:div.sub.header
          (:description workspace)]]
        (when message [:div.ui.message message])
        (when-let [error (some val row-errors)]
          [:div.ui.negative.message {:role "alert"} error])
        (pipeline/stage-strip event workflow)
        (speaker-state-filters event workflow status query)
        [:form.speaker-filter-form
         {:method "get" :action (str "/events/" (:slug event) "/speakers")}
         (when (not= :roster roster-workflow)
           [:input {:type "hidden" :name "view" :value (name workflow)}])
         [:input (merge {:type "hidden" :name "status" :value (or status "")}
                        (ds/bind :speakerstatus))]
         [:div.public-filter-bar
          [:input (merge {:type "search" :name "q" :value (or query "")
                          :aria-label "Filter speakers"
                          :placeholder "Search name, email, organization, or session"
                          :data-star-on:input__debounce.50ms
                          (str "@post('/events/" (:slug event) "/speakers/filter')")}
                         (ds/bind :speakerq))]]]
        (speakers-region event speakers query status profile)]
       (cospeaker-assignment-panel event sessions roster)
       (custom-fields-panel event custom-fields)
       (csv-import event csv-text import-preview)])))
