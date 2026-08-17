(ns cfp-scheduler-killer.views.files
  (:require
   [cfp-scheduler-killer.domain.files :as domain]
   [cfp-scheduler-killer.views.format :as format]
   [cfp-scheduler-killer.views.organizer-layout :as organizer-layout]
   [clojure.string :as str]))

(def upload-constraints
  "PDF, PowerPoint, Keynote, PNG, or JPEG · 25 MB maximum · every replacement keeps its history")

(defn- version-row [event file version latest?]
  [:tr
   [:td (str "v" (:number version)) (when latest? " · latest")]
   [:td (:filename version)]
   [:td (:content-type version)]
   [:td (str (:size version) " bytes")]
   [:td (or (format/fmt-when (:uploaded-at version) (:tz event)) "Unknown")]
   [:td (or (:uploaded-by version) "Unknown")]
   [:td
    [:a {:href (str "/events/" (:slug event) "/files/" (:id file)
                    "/download?version-id=" (:id version))}
     "Download"]]])

(defn- comment-thread [event action file]
  [:div
   [:h5.ui.header "Shared file conversation"
    [:div.sub.header "Speaker and organizer replies stay together with author and time."]]
   (if (seq (:comments file))
     [:div.ui.comments
      (for [{:keys [id actor body at]} (:comments file)]
        [:div.comment {:id id}
         [:div.content
          [:span.author actor]
          [:div.metadata (or (format/fmt-when at (:tz event)) "Unknown time")]
          [:div.text body]]])]
     [:div.field-hint "No comments yet."])
   [:form.ui.reply.form {:method "post" :action action}
    [:input {:type "hidden" :name "comment-id" :value (str (random-uuid))}]
    [:div.field
     [:textarea {:name "body" :rows 2 :maxlength 2000 :required true
                 :placeholder "Write a note for the speaker…"}]]
    [:button.ui.small.basic.button {:type "submit"} "Reply to speaker"]]])

(defn- file-card [event file]
  (let [latest (domain/latest-version file)]
    [:div.ui.segment {:id (str "file-" (:id file))}
     [:h3.ui.header
      (:filename latest)
      [:div.sub.header
       (:kind file) " · v" (:number latest)
       (when-let [owner (:owner-name file)] (str " · " owner))
       (when-let [session (:session-title file)] (str " · " session))
       (when-let [speakers (:speaker-names file)] (str " · " speakers))
       (when-let [uploaded-at (:uploaded-at latest)]
         [:span.file-latest-upload
          " · Latest upload " (format/fmt-when uploaded-at (:tz event))
          (when-let [uploader (:uploaded-by latest)]
            (str " by " uploader))])]]
     (when (< 1 (count (:versions file)))
       [:div.ui.tiny.positive.message.file-version-state
        (count (:versions file)) " versions retained · v" (:number latest)
        " is current · previous versions remain downloadable below"])
     [:details (cond-> {}
                 (< 1 (count (:versions file))) (assoc :open true))
      [:summary (str (count (:versions file)) " version"
                     (when (not= 1 (count (:versions file))) "s") " · show history")]
      [:table.ui.celled.compact.table
       [:thead [:tr [:th "Version"] [:th "Name"] [:th "Type"]
                [:th "Size"] [:th "Uploaded"] [:th "Uploaded by"] [:th "File"]]]
       [:tbody
        (for [version (reverse (:versions file))]
          (version-row event file version (= (:id version) (:id latest))))]]]
     (comment-thread
       event
       (str "/api/events/" (:slug event) "/files/" (:id file) "/comment")
       file)]))

(defn- file-library-index [event files]
  (when (seq files)
    (let [version-count (reduce + (map (comp count :versions) files))]
      [:div.ui.segment.central-file-library
       [:h3.ui.header "Central files library"
        [:div.sub.header
         (count files) " uploaded file" (when (not= 1 (count files)) "s")
         " · " version-count " immutable version" (when (not= 1 version-count) "s")
         " across every session and speaker profile"]]
       [:table.ui.celled.compact.table.file-library-index
        [:thead
         [:tr [:th "File"] [:th "Session"] [:th "Speaker"]
          [:th "Latest upload"] [:th "Versions"] [:th "Actions"]]]
        [:tbody
         (for [file files
               :let [latest (domain/latest-version file)
                     versions (count (:versions file))]]
           [:tr
            [:td
             [:strong (:filename latest)]
             [:div.field-hint (:kind file)]]
            [:td (or (:session-title file) "Speaker profile")]
            [:td (or (:speaker-names file) (:owner-name file) "Unknown speaker")]
            [:td
             (or (format/fmt-when (:uploaded-at latest) (:tz event)) "Unknown")
             [:div.field-hint "Uploaded by " (or (:uploaded-by latest) "Unknown")]]
            [:td
             [:strong versions " version" (when (not= 1 versions) "s")]
             [:div.field-hint "v" (:number latest) " current"]]
            [:td
             [:a {:href (str "/events/" (:slug event) "/files/" (:id file) "/download")
                  :download true}
              "Download current"]
             " · "
             [:a {:href (str "#file-" (:id file))} "Open file details"]]])]]])))

(defn- bulk-export [event files prepared-zip]
  (let [selected-files (:files prepared-zip)
        selected-file-ids (into #{} (map :id) selected-files)
        download-url (when (seq selected-files)
                       (str "/events/" (:slug event) "/files.zip?latest=true&grouping="
                            (:grouping prepared-zip)
                            (apply str (map #(str "&file-ids=" (:id %)) selected-files))))]
    [:div.ui.secondary.segment
     [:h3.ui.header "Bulk download latest versions"
      [:div.sub.header
       "Select files below, choose their folder layout, then generate one ZIP."]]
     [:form#bulk-file-export.ui.form
      {:method "get" :action (str "/events/" (:slug event) "/files")}
      [:input {:type "hidden" :name "prepare-zip" :value "true"}]
      (when (seq files)
        [:fieldset.grouped.fields
         [:legend "Files to include"]
         [:div.field-hint
          "Choose any combination. Leave every box clear to prepare all uploaded files."]
         [:table.ui.very.basic.compact.table
          [:thead [:tr [:th "Include"] [:th "Session"] [:th "Speaker"] [:th "Current version"]]]
          [:tbody
           (for [file files
                 :let [latest (domain/latest-version file)
                       control-id (str "zip-select-" (:id file))]]
             [:tr
              [:td
               [:div.ui.checkbox
                [:input {:id control-id :type "checkbox" :name "file-ids"
                         :value (:id file)
                         :aria-label (str "Select " (:filename latest) " for ZIP")
                         :checked (when (contains? selected-file-ids (:id file)) true)}]
                [:label {:for control-id} (:filename latest)]]]
              [:td (or (:session-title file) "Speaker profile")]
              [:td (or (:speaker-names file) (:owner-name file) "Unknown speaker")]
              [:td (str "v" (:number latest) " · latest")]])]]])
      [:div.inline.fields
       [:div.field
        [:label "Grouping"]
        [:select {:name "grouping"}
         [:option (cond-> {:value "by-session"}
                    (= "by-session" (:grouping prepared-zip)) (assoc :selected true))
          "Group by session / speaker"]
         [:option (cond-> {:value "flat"}
                    (= "flat" (:grouping prepared-zip)) (assoc :selected true))
          "One flat folder"]]]
       [:button.ui.primary.button {:type "submit" :disabled (when (empty? files) true)}
        "Generate selected ZIP"]]
      [:div.field-hint
       "The archive contains the latest version of each selected file."]]
     (when prepared-zip
       (if download-url
         [:div.ui.positive.message {:role "status"}
          [:div.header "ZIP ready to download"]
          [:p (str (count selected-files) " latest file"
                   (when (not= 1 (count selected-files)) "s")
                   " · " (if (= "flat" (:grouping prepared-zip))
                           "one flat folder"
                           "grouped by session / speaker"))]
          [:div.ui.list
           (for [{:keys [id filename]} selected-files]
             [:div.item {:id (str "zip-file-" id)} filename])]
          [:a.ui.primary.button {:href download-url :download true}
           "Download prepared ZIP"]]
         [:div.ui.warning.message {:role "status"}
          [:div.header "No files selected"]
          [:p "Choose at least one current file and prepare the ZIP again."]]))]))

(defn- request-ledger [requests]
  [:div
   [:h3.ui.dividing.header "File requests"]
   (if (seq requests)
     [:table.ui.celled.compact.table
      [:thead [:tr [:th "Request"] [:th "Speaker"] [:th "Session"] [:th "Due"]
               [:th "Instructions"] [:th "Status"]]]
      [:tbody
       (for [{:keys [label speaker-name session-title due-on instructions done?]} requests]
         [:tr
          [:td label]
          [:td (or speaker-name "Unknown speaker")]
          [:td (or session-title "Unknown session")]
          [:td (some-> due-on str)]
          [:td instructions]
          [:td (if done? "Received" "Pending")]])]]
     [:div.empty-state "No file requests match these filters."])])

(defn- selected [actual expected]
  (when (= actual expected) true))

(defn- filter-panel [event {:keys [q status kind sort file-sort]}]
  [:div.ui.secondary.segment
   [:h3.ui.header "Filter files and requests"]
   [:form.ui.form {:method "get" :action (str "/events/" (:slug event) "/files")}
    [:div.five.fields
     [:div.field
      [:label "Search"]
      [:input {:type "search" :name "q" :value (or q "")
               :placeholder "Speaker, session, request, or filename"
               :data-ghost-fill ""}]]
     [:div.field
      [:label "Status"]
      [:select {:name "status"}
       (for [[value label] [["all" "All statuses"]
                            ["pending" "Pending"]
                            ["received" "Received"]]]
         [:option {:value value :selected (selected status value)} label])]]
     [:div.field
      [:label "Deliverable type"]
      [:select {:name "kind"}
       (for [value ["all" "Presentation" "Poster" "Handout" "Headshot" "Other"]]
         [:option {:value value :selected (selected kind value)}
          (if (= "all" value) "All types" value)])]]
     [:div.field
      [:label "Sort requests"]
      [:select {:name "sort"}
       (for [[value label] [["due-asc" "Due date · earliest"]
                            ["due-desc" "Due date · latest"]
                            ["speaker-asc" "Speaker · A–Z"]]]
         [:option {:value value :selected (selected sort value)} label])]]
     [:div.field
      [:label "Sort uploaded files"]
      [:select {:name "file-sort"}
       (for [[value label] [["uploaded-newest" "Uploaded · newest"]
                            ["uploaded-oldest" "Uploaded · oldest"]
                            ["speaker-asc" "Speaker · A–Z"]
                            ["filename-asc" "Filename · A–Z"]]]
         [:option {:value value :selected (selected file-sort value)} label])]]]
    [:button.ui.primary.button {:type "submit"} "Apply filters"]
    [:a.ui.basic.button {:href (str "/events/" (:slug event) "/files")} "Clear"]]])

(defn- count-label [count singular]
  (str count " " singular (when (not= 1 count) "s")))

(defn- filters-active? [{:keys [q status kind sort file-sort]}]
  (or (seq q)
      (not= "all" status)
      (not= "all" kind)
      (not= "due-asc" sort)
      (not= "uploaded-newest" file-sort)))

(defn- result-summary [requests files]
  [:p.field-hint
   (count-label (count requests) "file request")
   " · "
   (count-label (count files) "uploaded file")])

(defn files-page
  [event {:keys [person submissions requests files filters message prepared-zip]}]
  (organizer-layout/organizer-shell
    (str "Files · " (:name event))
    {:event event :person person :active :files}
    [:div.ui.segment
     [:h1.ui.header "Files"
      [:div.sub.header "Every deliverable stays attached to its session, with every version preserved."]]
     (when message [:div.ui.message message])
     [:a.ui.basic.button {:href (str "/events/" (:slug event) "/files.zip")}
      "Download complete version archive"]
     [:h3.ui.dividing.header "Request a file"]
     [:form.ui.form {:method "post" :action (str "/api/events/" (:slug event) "/files/requests")}
      [:input {:type "hidden" :name "request-id" :value (str (random-uuid))}]
      [:div.required.field
       [:label "Request name"]
       [:input {:type "text" :name "request-name" :required true :maxlength 120
                :placeholder "Final slides, speaker headshot, signed release…"}]]
      [:div.fields
       [:div.eight.wide.field
        [:label "Sessions / speakers"]
        [:div.grouped.fields {:role "group" :aria-label "Sessions / speakers"}
         (for [submission submissions
               :let [control-id (str "file-request-submission-" (:id submission))]]
           [:div.field
            [:div.ui.checkbox
             [:input {:id control-id :type "checkbox" :name "submission-ids"
                      :value (:id submission)}]
             [:label {:for control-id}
              (str (or (get-in submission [:answers :talk-title]) "Untitled session")
                   " — "
                   (str/join ", " (keep :name (:speakers submission))))]]])]
        [:div.field-hint "Choose one or more sessions. One request is assigned to every selected speaker."]]
       [:div.four.wide.field
        [:label "Deliverable type"]
        [:select {:name "file-kind" :required true}
         (for [kind ["Presentation" "Poster" "Handout" "Headshot" "Other"]]
           [:option {:value kind} kind])]]
       [:div.four.wide.field
        [:label "Due date"]
        [:input {:type "date" :name "due-on" :required true}]]]
      [:div.field
       [:label "Instructions"]
       [:textarea {:name "instructions" :rows 2 :required true
                   :placeholder "What should the speaker upload?"}]]
      [:button.ui.button {:type "submit"} "Create file request"]]
     (filter-panel event filters)
     (result-summary requests files)
     (request-ledger requests)
     [:div.ui.info.message upload-constraints]
     [:h3.ui.dividing.header "File library"]
     (file-library-index event files)
     (bulk-export event files prepared-zip)
     (cond
       (seq files)
       (for [file files]
         (file-card event file))

       (= "pending" (:status filters))
       [:div.empty-state
        [:p [:strong "Uploaded files are hidden in the Pending view."]]
        [:p "Pending requests remain in the ledger above. Choose All statuses or Received to see uploaded files."]]

       (filters-active? filters)
       [:div.empty-state
        [:p [:strong "No uploaded files match these filters."]]
        [:a {:href (str "/events/" (:slug event) "/files")} "Clear filters"]]

       :else
       [:div.empty-state
        [:p [:strong "No files uploaded yet."]]
        [:p "Create a request above; the upload appears here as soon as the speaker responds."]])]))
