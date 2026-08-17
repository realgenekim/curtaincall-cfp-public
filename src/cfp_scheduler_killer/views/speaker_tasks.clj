(ns cfp-scheduler-killer.views.speaker-tasks
  (:require
   [cfp-scheduler-killer.views.organizer-layout :as organizer-layout]
   [clojure.string :as str]))

(defn- status-label [{:keys [status days-overdue done?]}]
  (if done?
    "Uploaded / complete"
    (case status
      :overdue (str days-overdue "d overdue")
      :due-today "Due today"
      :due-soon "Due soon"
      :upcoming "Upcoming"
      :unscheduled "Needs event date"
      "Open")))

(defn- obligation-row [event task]
  (let [submission-id (:submission-id task)
        task-key (:key task)
        versions (some-> task :file :versions)
        latest-version (peek versions)]
    [:tr
     [:td
      (cond
        (:done? task)
        [:span.ui.tiny.green.label "Complete"]

        (str/blank? (:speaker-email task))
        [:span.ui.tiny.basic.label "No email"]

        :else
        [:input {:type "checkbox"
                 :name "selected"
                 :value (str submission-id "|" task-key
                             (when-let [speaker-key (:speaker-key task)]
                               (str "|" speaker-key)))
                 :form "chase-batch"
                 :checked (when (:scheduled? task) true)
                 :aria-label (str "Select " (:speaker-name task) " for follow-up")}])
      [:strong (:speaker-name task)]
      [:div.field-hint (:talk-title task)]]
     [:td
      [:strong (:label task)]
      [:div.field-hint (status-label task)]
      (when latest-version
        [:div.field-hint.file-upload-state
         "Uploaded " (:filename latest-version)
         " · v" (:number latest-version) " current"
         " · " (count versions) " version"
         (when (not= 1 (count versions)) "s")
         " · "
         [:a {:href (str "/events/" (:slug event) "/files#file-" (get-in task [:file :id]))}
          "Open in central files library"]])]
     [:td (or (some-> (:due-on task) str) "—")]
     [:td
      (if-let [last-touch (:last-chased-at task)]
        (list (str last-touch)
              [:div.field-hint (:chase-count task) " follow-up"
               (when (not= 1 (:chase-count task)) "s")])
        [:span.field-hint "Not contacted"])]
     [:td
      (if (:done? task)
        [:span.field-hint "No reminder needed"]
        [:form {:method "post"
                :action (str "/api/events/" (:slug event)
                             "/deliverables/" submission-id "/" task-key "/chase")}
         [:input {:type "hidden"
                  :name "chase-id"
                  :value (str submission-id ":" task-key ":"
                              (or (:speaker-key task) "speaker") ":"
                              (inc (or (:chase-count task) 0)))}]
         [:div.ui.action.input
          [:select.ui.compact.dropdown {:name "medium"}
           [:option {:value "email"} "Email"]
           [:option {:value "text"} "Text"]
           [:option {:value "call"} "Call"]
           [:option {:value "other"} "Other"]]
          [:input {:type "text"
                   :name "note"
                   :required true
                   :placeholder "What did you ask the speaker?"
                   :data-ghost-fill ""}]
          [:button.ui.small.button {:type "submit"} "Record follow-up"]]])]]))

(defn- filter-panel [event {:keys [q status due]} visible total]
  [:div.ui.secondary.segment
   [:h3.ui.header "Filter deliverables"]
   [:form.ui.form {:method "get"
                   :action (str "/events/" (:slug event) "/deliverables")}
    [:div.three.fields
     [:div.field
      [:label "Search"]
      [:input {:type "search" :name "q" :value (or q "")
               :placeholder "Speaker, session, or task"
               :data-ghost-fill ""}]]
     [:div.field
      [:label "Completion"]
      [:select {:name "status"}
       (for [[value label] [["all" "All tasks"] ["open" "Incomplete"]
                            ["complete" "Uploaded / complete"]]]
         [:option {:value value :selected (= value status)} label])]]
     [:div.field
      [:label "Due status"]
      [:select {:name "due"}
       (for [[value label] [["all" "Any due status"] ["overdue" "Overdue"]
                            ["due-today" "Due today"] ["due-soon" "Due soon"]
                            ["upcoming" "Upcoming"] ["unscheduled" "No due date"]]]
         [:option {:value value :selected (= value due)} label])]]]
    [:button.ui.primary.button {:type "submit"} "Apply filters"]
    [:a.ui.basic.button {:href (str "/events/" (:slug event) "/deliverables")}
     "Clear"]]
   [:div.field-hint (str "Showing " visible " of " total " tasks")]])

(defn- completion-row [{:keys [speaker-name speaker-email talks done total overdue
                               portal-visited? slot-assigned? ics-sent?]}]
  (let [open (- total done)
        status (cond
                 (zero? total) "Not started"
                 (zero? open) [:span.ui.tiny.green.label "All tasks complete"]
                 :else
                 (list
                   [:span.ui.tiny.orange.label (str open " open")]
                   (when (pos? overdue)
                     [:span.ui.tiny.red.label (str overdue " overdue")])))]
    [:tr
     [:td
      [:strong speaker-name]
      [:div.field-hint speaker-email]]
     [:td (str/join ", " talks)]
     [:td
      (if (pos? total)
        (list
          [:div (str done " of " total " complete")]
          [:progress {:value done
                      :max total
                      :aria-label (str speaker-name " onboarding completion")}])
        [:span.field-hint "No tasks assigned"])]
     [:td status]
     [:td (if portal-visited?
            "Visited"
            [:span.ui.tiny.orange.label "Never visited"])]
     [:td (if slot-assigned? "Assigned" "Not assigned")]
     [:td (if ics-sent? "Sent" "Not sent")]]))

(defn- completion-table [speaker-progress]
  [:div.ui.segment
   [:h3.ui.header "Onboarding completion by speaker"
    [:div.sub.header
     "Completed work stays visible, so mixed progress is clear without opening each portal."]]
   (if (seq speaker-progress)
     [:table.ui.celled.compact.table
      [:thead
       [:tr
        [:th "Speaker"]
        [:th "Sessions"]
        [:th "Progress"]
        [:th "Status"]
        [:th "Portal"]
        [:th "Slot assigned"]
        [:th {:title "derived from delivery history"} ".ics sent"]]]
      [:tbody (for [progress speaker-progress] (completion-row progress))]]
     [:div.empty-state "No speaker checklists have been assigned yet."])])

(defn- funnel-strip [{:keys [accepted notified portal-visited tasks-complete]}]
  [:div.ui.four.mini.statistics.deliverables-funnel
   [:div.statistic [:div.value (or accepted 0)] [:div.label "Accepted"]]
   [:div.statistic [:div.value (or notified 0)] [:div.label "Notified"]]
   [:div.statistic [:div.value (or portal-visited "—")] [:div.label "Portal visited"]]
   [:div.statistic [:div.value (or tasks-complete 0)] [:div.label "Tasks complete"]]])

(defn deliverables-live-region [funnel speaker-progress obligations]
  (let [open-obligations (filterv (comp not :done?) obligations)
        overdue (count (filter #(= :overdue (:status %)) open-obligations))
        due-today (count (filter #(= :due-today (:status %)) open-obligations))]
    [:div#deliverables-live
     (funnel-strip funnel)
     (completion-table speaker-progress)
     [:div.ui.three.statistics
      [:div.statistic [:div.value (count open-obligations)] [:div.label "Open"]]
      [:div.statistic [:div.value overdue] [:div.label "Overdue"]]
      [:div.statistic [:div.value due-today] [:div.label "Due today"]]]]))

(defn- task-creation-panel [event task-assignees]
  [:div.ui.segment
   [:h3.ui.header "Create onboarding task"
    [:div.sub.header
     "Assign one dated action to one or more speakers. It appears in each selected speaker's portal checklist."]]
   [:form.ui.form
    {:method "post"
     :action (str "/api/events/" (:slug event) "/reminder-schedule")}
    [:input {:type "hidden" :name "intent" :value "create-general-task"}]
    [:input {:type "hidden" :name "task-id" :value (str (random-uuid))}]
    [:div.three.fields
     [:div.required.field
      [:label "Task name"]
      [:input {:type "text" :name "task-name" :maxlength 120 :required true
               :placeholder "Confirm travel details" :data-ghost-fill ""}]]
     [:div.required.field
      [:label "Due date"]
      [:input {:type "date" :name "due-on" :required true}]]
     [:div.required.field
      [:label "Speaker action"]
      [:select {:name "task-kind" :required true}
       [:option {:value "check"} "Confirm or mark done"]
       [:option {:value "Presentation"} "Upload presentation"]
       [:option {:value "Poster"} "Upload poster"]
       [:option {:value "Handout"} "Upload handout"]
       [:option {:value "Headshot"} "Upload headshot"]
       [:option {:value "Other"} "Upload another file"]]]]
    [:div.field
     [:label "Instructions"]
     [:textarea {:name "instructions" :rows 2
                 :placeholder "What does the speaker need to do?"
                 :data-ghost-fill ""}]]
    [:div.required.field
     [:label "Assign to speakers"]
     (if (seq task-assignees)
       [:div.grouped.fields {:role "group" :aria-label "Assign to speakers"}
        (for [{:keys [submission-id speaker-name talk-title]} task-assignees
              :let [control-id (str "general-task-" submission-id)]]
          [:div.field
           [:div.ui.checkbox
            [:input {:id control-id :type "checkbox" :name "submission-ids"
                     :value submission-id}]
            [:label {:for control-id}
             [:strong speaker-name]
             [:span.field-hint " — " talk-title]]]])]
       [:div.empty-state
        "Add a speaker submission before assigning onboarding tasks."])]
    [:button.ui.primary.button
     {:type "submit" :disabled (when-not (seq task-assignees) true)}
     "Create task"]]])

(defn deliverables-page
  [event {:keys [person obligations reminder-schedule speaker-progress funnel filters
                 task-assignees total-obligations]}]
  (let [open-obligations (filterv (comp not :done?) obligations)
        completed (count (filter :done? obligations))
        shown (count obligations)
        unfiltered? (and (contains? #{nil "all"} (:status filters))
                         (contains? #{nil "all"} (:due filters))
                         (str/blank? (:q filters)))]
    (organizer-layout/organizer-shell
      (str "Speaker deliverables · " (:name event))
      {:event event :person person :active :deliverables :sse? true}
      [:div.ui.segment
       [:h1.ui.header "Speaker deliverables"
        [:div.sub.header
         "Who owes what, sorted by the obligations needing attention first."]]
       (deliverables-live-region funnel speaker-progress obligations)
       [:div.ui.info.message
        "This page records human follow-up. It never sends an automatic reminder."]
       (when unfiltered?
         (task-creation-panel event task-assignees))
       (filter-panel event filters (count obligations) (or total-obligations (count obligations)))
       [:div.ui.message.deliverable-completion-summary {:role "status"}
        [:strong completed " uploaded / complete"]
        " of " shown " shown task" (when (not= 1 shown) "s")
        (when (pos? completed)
          (list " · "
                [:a {:href (str "/events/" (:slug event) "/files")}
                 "Review uploaded files →"]))]
       [:div.ui.segment
        [:h3.ui.header "Automated reminder schedule"
         [:div.sub.header
          "Automatically preselect due obligations for a reviewable draft batch."]]
        (let [{:keys [enabled? days-before]}
              (merge {:enabled? false :days-before 7} reminder-schedule)
              selected-count (count (filter :scheduled? open-obligations))]
          (list
            [:form.ui.form
             {:method "post"
              :action (str "/api/events/" (:slug event) "/reminder-schedule")}
             [:div.inline.fields
              [:div.field
               [:div.ui.checkbox
                [:input {:type "checkbox" :name "enabled?" :value "true"
                         :checked (when enabled? true)}]
                [:label "Enable scheduled draft selection"]]]
              [:div.field
               [:label "Days before due"]
               [:input {:type "number" :name "days-before" :min "1" :max "90"
                        :value days-before :required true}]]
              [:button.ui.primary.button {:type "submit"} "Save reminder schedule"]]]
            (if enabled?
              [:div.ui.positive.message
               selected-count " obligation" (when (not= 1 selected-count) "s")
               " preselected for draft review. Email is never sent automatically."]
              [:div.field-hint
               "Off by default. Nothing is preselected and no email is sent."])))]
       (if (seq obligations)
         [:div
          [:form#chase-batch
           {:method "post"
            :action (str "/api/events/" (:slug event) "/speaker-chases/draft")}]
          [:div.ui.secondary.segment
           [:strong "Batch a human follow-up"]
           [:span " — select any outstanding speakers and tasks, then review every letter."]
           [:button.ui.primary.button
            {:type "submit" :form "chase-batch"}
            "Draft selected emails"]]
          [:table.ui.celled.compact.table
           [:thead
            [:tr
             [:th "Speaker / session"]
             [:th "Deliverable"]
             [:th "Due"]
             [:th "Last touch"]
             [:th "Human follow-up"]]]
           [:tbody (for [task obligations] (obligation-row event task))]]]
         [:div.empty-state
          [:p [:strong "No deliverables match these filters."]]
          [:p "Clear the filters to see every incomplete and completed task."]])])))

(defn chase-drafts-page
  [event {:keys [person drafts error]}]
  (organizer-layout/organizer-shell
    (str "Review speaker follow-ups · " (:name event))
    {:event event :person person :active :deliverables}
    [:div.ui.segment
     [:h1.ui.header "Review speaker follow-ups"
      [:div.sub.header "Each message is individually addressed and fully editable."]]
     [:div.ui.warning.message
      [:strong "Human send gate. "]
      "Nothing is sent until you press “Send reviewed messages”."]
     (when error [:div.ui.negative.message error])
     (when (seq drafts)
       [:form.ui.form
        {:method "post"
         :action (str "/api/events/" (:slug event) "/speaker-chases/send")}
        [:input {:type "hidden" :name "count" :value (count drafts)}]
        (for [[idx draft] (map-indexed vector drafts)]
          [:div.ui.segment
           [:input {:type "hidden" :name (str "chase-id-" idx) :value (:chase-id draft)}]
           [:input {:type "hidden" :name (str "submission-id-" idx) :value (:submission-id draft)}]
           [:input {:type "hidden" :name (str "task-key-" idx) :value (:task-key draft)}]
           [:div.two.fields
            [:div.field
             [:label "From your address"]
             [:input {:type "email" :required true :name (str "from-" idx) :value (:from draft)}]]
            [:div.field
             [:label "To"]
             [:input {:type "email" :required true :name (str "to-" idx) :value (:to draft)}]]]
           [:div.field
            [:label "Subject"]
            [:input {:type "text" :required true :name (str "subject-" idx) :value (:subject draft)}]]
           [:div.field
            [:label "Message"]
            [:textarea {:required true :name (str "body-" idx) :rows 9} (:body draft)]]])
        [:button.ui.primary.button {:type "submit"} "Send reviewed messages"]
        [:a.ui.button {:href (str "/events/" (:slug event) "/deliverables")} "Cancel"]])]))

(defn chase-results-page
  [event {:keys [person results]}]
  (let [counts (frequencies (map :mode results))
        sent (get counts :sent 0)
        already-sent (get counts :already-sent 0)
        queued (get counts :queued 0)
        rendered (get counts :rendered 0)
        failed (get counts :failed 0)
        all-accounted? (and (seq results)
                            (= (+ sent already-sent) (count results)))]
    (organizer-layout/organizer-shell
      (str "Speaker follow-up results · " (:name event))
      {:event event :person person :active :deliverables}
      [:div.ui.segment
       [:h1.ui.header "Speaker follow-up results"]
       [:div {:class (if all-accounted? "ui positive message" "ui warning message")
              :role "status"}
        [:div.header
         (cond
           (and (pos? sent) (zero? already-sent))
           (str sent " reminder" (when (not= 1 sent) "s") " sent and recorded")

           (and (zero? sent) (pos? already-sent))
           (str already-sent " reminder" (when (not= 1 already-sent) "s")
                " already sent and recorded")

           (and (pos? sent) (pos? already-sent))
           (str sent " sent now · " already-sent " already sent and recorded")

           :else "No reminders were sent")]
        [:p (str (when (pos? already-sent) (str already-sent " already sent · "))
                 queued " queued · " rendered " rendered only · " failed " failed")]]
       [:div.ui.message
        "Only messages accepted by the mail provider reset their obligation clock."]
       [:div.ui.relaxed.divided.list
        (for [{:keys [to mode]} results]
          [:div.item
           [:div.content
            [:strong to]
            [:span " — " (case mode
                           :sent "Sent and recorded"
                           :already-sent "Already sent and recorded; no duplicate email sent"
                           :queued "Queued for delivery"
                           :rendered "Rendered only; not marked contacted"
                           :failed "Delivery failed; not marked contacted"
                           (name mode))]]])]
       [:a.ui.primary.button {:href (str "/events/" (:slug event) "/deliverables")}
        "Back to deliverables"]])))
