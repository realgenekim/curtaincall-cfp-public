(ns cfp-scheduler-killer.views.portal
  "Speaker portal, talk editing, profile editing, and tasks."
  (:require
   [cfp-scheduler-killer.domain.speakers :as speaker-domain]
   [cfp-scheduler-killer.portal :as portal]
   [cfp-scheduler-killer.schedule :as schedule]
   [cfp-scheduler-killer.submissions :as submissions]
   [cfp-scheduler-killer.views.form-controls :as form-controls]
   [cfp-scheduler-killer.views.format :as format]
   [cfp-scheduler-killer.views.live-drafts :as live-drafts]
   [cfp-scheduler-killer.views.shell :as shell]
   [clojure.string :as str]
   [datastar-kit.ds :as ds]))

(defn- edit-form [{:keys [submission]} errors values]
  (let [snapshot (:form-snapshot submission)
        answers (:answers submission)]
    [:div.portal-sub {:style "border-color:#2185d0;"}
     [:h3 "Editing: " (get-in submission [:answers :talk-title])]
     [:div.field-hint {:style "margin-bottom:0.8em;"}
      "You can edit right up to the event — being accepted doesn't lock your talk. "
      "These are the questions exactly as you answered them; if the organizers have "
      "since changed the form, your talk keeps the version you saw."]
     (when (:_ errors) [:div.ui.negative.message (str/join " " (:_ errors))])
     ;; Same live lane as the public page: novalidate (the server validates),
     ;; a debounced whole-form POST, and a hidden `dscope` so the server knows
     ;; WHICH open form these keystrokes belong to.
     [:form.ui.form {:method "post" :action (str "/api/submissions/" (:id submission) "/answers")
                     :novalidate "novalidate"
                     :data-star-on:input__debounce.300ms
                     "@post('/portal/draft', {contentType: 'form'})"}
      [:input {:type "hidden" :name "dscope" :value (:id submission)}]
      (live-drafts/portal-draft-status (:id submission) false)
      ;; Empty note landing pads. Nothing to say on first paint — these fill in
      ;; from the stream the moment the speaker types (CLAUDE.md #9: the target
      ;; must exist before the push).
      (for [f (submissions/session-fields snapshot)]
        (list (form-controls/answer-input f
                                          (merge (into {} (map (fn [[k v]]
                                                                 [(keyword (str "answer-" (name k))) v]))
                                                       answers)
                                                 values)
                                          errors)
              (live-drafts/cfp-note (keyword (str "answer-" (name (:id f)))) nil)))
      [:button.ui.primary.button {:type "submit"} "Save changes"]
      [:a.ui.basic.button {:href "/portal" :style "margin-left:0.5em;"} "Cancel"]]]))

(defn- profile-form [person headshot-upload? errors values newcomer?]
  (let [profile (:profile person)
        missing (speaker-domain/missing-profile-fields profile)
        v #(if (contains? values %)
             (get values %)
             (get profile % ""))]
    [:div.portal-sub
     [:h3 "Your profile"]
     [:div.field-hint {:style "margin-bottom:0.8em;"}
      "This is what gets printed and published. It also prefills your next "
      "submission, so you never type your bio twice."]
     (if (seq missing)
       [:div.ui.warning.message
        (str "Profile incomplete — missing " (str/join " and " missing))]
       [:div.ui.positive.message
        "Profile complete — your bio and headshot are ready for organizers."])
     (when-let [headshot-url (some-> (:headshot-url profile) str/trim not-empty)]
       [:div.portal-headshot-preview {:style "margin-bottom:1em;"}
        [:img {:src headshot-url
               :alt (str (:name person) " headshot")
               :style "display:block;width:120px;height:120px;object-fit:cover;border-radius:6px;"}]
        [:div.field-hint "Current published headshot"]])
     [:form.ui.form {:method "post" :action "/api/profile"
                     :enctype "multipart/form-data"
                     :novalidate "novalidate"
                     :data-star-on:input__debounce.300ms
                     "@post('/portal/draft', {contentType: 'form'})"}
      [:input {:type "hidden" :name "dscope" :value "profile"}]
      (live-drafts/portal-draft-status "profile" false)
      [:div.field
       [:label "Name"]
       [:input {:type "text" :value (:name person) :disabled true}]
       [:div.field-hint "Ask the organizers if your name needs changing."]]
      (for [{:keys [key label type help]} portal/profile-fields]
        (list
          [:div.field {:class (when (get errors key) "error")}
           [:label label]
           (if (= "textarea" type)
             [:textarea {:name (name key) :rows 5} (v key)]
             [:input {:type type :name (name key) :value (v key)}])
           (when help [:div.field-hint help])
           (form-controls/field-error errors key)]
          (live-drafts/cfp-note key nil)))
      (when headshot-upload?
        [:div.field
         [:label "Upload a new headshot"]
         [:input {:type "file" :name "file"
                  :accept "image/png,image/jpeg"}]
         [:div.field-hint
          "Optional · PNG or JPEG · 25 MB maximum · saving keeps every previous version."]])
      [:button.ui.primary.button {:type "submit"}
       (if newcomer? "Save profile → continue" "Save profile")]]]))

(defn- status-pill [visible-status]
  (let [cls (case visible-status
              "Accepted" "accepted"
              "Waitlisted" "waitlisted"
              "Declined" "declined"
              "review")]
    [:span.status-pill {:class cls} visible-status]))

(defn- edit-lock-copy [event visible-status message]
  (cond
    (= message "The call closed — editing is locked.")
    (str "The call closed "
         (format/fmt-instant-day (:cfp-closes-at event) (:tz event))
         " — editing is locked.")

    (and (= "Under review" visible-status)
         (= "This submission can no longer be edited (it is Declined)." message))
    "This submission can no longer be edited."

    :else message))

(defn- file-version-row [event submission-id file version latest?]
  [:tr
   [:td (str "v" (:number version)) (when latest? " · latest / current")]
   [:td
    [:a {:href (str "/api/submissions/" submission-id "/files/" (:id file)
                    "/download?version-id=" (:id version))}
     (:filename version)]]
   [:td (or (format/fmt-when (:uploaded-at version) (:tz event)) "Unknown time")]
   [:td (or (:uploaded-by version) "Unknown")]])

(defn- file-thread [event submission-id file]
  (let [latest (some-> file :versions peek)]
    [:div
     (when latest
       [:div.field-hint
        [:a {:href (str "/api/submissions/" submission-id "/files/" (:id file) "/download")}
         (:filename latest)]
        " · v" (:number latest) " · latest / current · " (:kind file)])
     (when (< 1 (count (:versions file)))
       [:div.ui.tiny.positive.message.file-version-state
        (count (:versions file)) " versions retained · v" (:number latest)
        " is current · previous versions remain downloadable below"])
     (when latest
       [:details (cond-> {}
                   (< 1 (count (:versions file))) (assoc :open true))
        [:summary (str (count (:versions file)) " version"
                       (when (not= 1 (count (:versions file))) "s")
                       " · show history")]
        [:table.ui.celled.compact.table
         [:thead
          [:tr [:th "Version"] [:th "File"] [:th "Uploaded"] [:th "Uploaded by"]]]
         [:tbody
          (for [version (reverse (:versions file))]
            (file-version-row event submission-id file version
                              (= (:id version) (:id latest))))]]])
     (when file
       [:h6.ui.header "Shared file conversation"
        [:div.sub.header "Your comments and organizer replies stay with this file."]])
     (for [{:keys [id actor body at]} (:comments file)]
       [:div.ui.message {:id id}
        [:strong actor] " · " (or (format/fmt-when at (:tz event)) "Unknown time")
        [:div body]])
     (when file
       [:form.task-form {:method "post"
                         :action (str "/api/submissions/" submission-id
                                      "/files/" (:id file) "/comment")}
        [:input {:type "hidden" :name "comment-id" :value (str (random-uuid))}]
        [:input {:type "text" :name "body" :required true :maxlength 2000
                 :placeholder "Reply to the organizers…"}]
        [:button.ui.mini.basic.button {:type "submit"} "Post comment to organizers"]])]))

(defn- task-row [event submission-id t]
  [:li.task-row {:class (when (:done? t) "done")}
   [:span.task-tick (if (:done? t) "✓" "☐")]
   [:span.task-label
    (:label t)
    [:span.ui.tiny.label
     {:class (if (:done? t) "green" "orange")
      :style "margin-left:0.5em;"}
     (if (:done? t) "Complete" "Incomplete")]
    (when-let [due-on (:due-on t)]
      [:span.field-hint " · due " (str due-on)])
    (when-let [last-touch (:last-chased-at t)]
      [:span.field-hint " · last contacted " (str last-touch)])
    (when-let [instructions (:instructions t)]
      [:div.field-hint instructions])]
   (cond
     (or (= "file" (:task-type t)) (#{:headshot :slides-url} (keyword (:key t))))
     [:div
      (let [headshot? (= "Headshot" (:file-kind t))]
        [:form.task-form {:method "post" :enctype "multipart/form-data"
                          :action (str "/api/submissions/" submission-id
                                       "/files/" (:key t) "/upload")}
         [:input {:type "file" :name "file" :required true
                  :accept (if headshot?
                            ".png,.jpg,.jpeg"
                            ".pdf,.ppt,.pptx,.key,.png,.jpg,.jpeg")}]
         [:button.ui.mini.basic.button {:type "submit"}
          (if (:file t) "Upload a new version" "Upload file")]
         [:div.field-hint
          (if headshot?
            "Accepted file types: PNG or JPEG · Maximum file size: 25 MB"
            "Accepted file types: PDF, PowerPoint, Keynote, PNG, or JPEG · Maximum file size: 25 MB")]])
      (file-thread event submission-id (:file t))]

     (= "url" (:task-type t))
     [:form.task-form {:method "post"
                       :action (str "/api/submissions/" submission-id "/task")}
      [:input {:type "hidden" :name "key" :value (:key t)}]
      [:input {:type "url" :name "value" :value (or (:value t) "")
               :placeholder "https://…"}]
      [:button.ui.mini.basic.button {:type "submit"} "Save"]]

     (= "form" (:task-type t))
     (let [stored-values (into {}
                               (map (fn [[k v]]
                                      [(keyword (str "answer-" (name k))) v]))
                               (:value t))]
       [:form.ui.form.task-form {:method "post"
                                 :action (str "/api/submissions/" submission-id "/task")
                                 :novalidate "novalidate"}
        [:input {:type "hidden" :name "key" :value (:key t)}]
        (for [field (:fields t)]
          (form-controls/answer-input field stored-values (:form-errors t)))
        [:button.ui.mini.primary.button {:type "submit"}
         (if (:done? t) "Update answers" "Save form")]])

     :else
     (when-not (:done? t)
       [:form.task-form {:method "post"
                         :action (str "/api/submissions/" submission-id "/task")}
        [:input {:type "hidden" :name "key" :value (:key t)}]
        [:button.ui.mini.basic.button {:type "submit"} "Mark done"]]))])

(defn- custom-fields-form
  [{:keys [event custom-fields custom-values]} errors values]
  (when (seq custom-fields)
    (let [v (fn [id]
              (if (contains? values id)
                (get values id)
                (get custom-values id "")))]
      [:div.portal-sub
       [:h3 "Event details — " (:name event)]
       [:div.field-hint {:style "margin-bottom:0.8em;"}
        "Custom person fields requested by this event's organizers. These answers stay inside this event."]
       [:form.ui.form {:method "post"
                       :action (str "/api/events/" (:slug event) "/speaker-custom-values")}
        (for [{:keys [id label type required]} custom-fields]
          [:div.field {:class (when (get errors id) "error")}
           [:label label (form-controls/req-mark required)]
           (if (= "textarea" type)
             [:textarea {:name (str "custom-" id) :rows 4} (v id)]
             [:input {:type "text" :name (str "custom-" id) :value (v id)}])
           (form-controls/field-error errors id)])
        [:button.ui.primary.button {:type "submit"} "Save event details"]]])))

(defn- participation-card [{:keys [event speaker]}]
  [:div.portal-sub
   [:h3 "Invited to speak at " (:name event)]
   [:div.portal-event
    (when-let [d (format/fmt-date-range (:starts-on event) (:ends-on event))]
      d)]
   [:span.status-pill.review (or (:status speaker) "Invited")]
   [:p
    [:strong "No session is attached yet. "]
    "Your organizer invited you directly. Complete your shared profile and "
    "the event details below while the program takes shape."]])

(defn- portal-submission [{:keys [submission event visible-status informed? editable? edit-lock-message schedule tasks progress]} editing-id]
  (let [sid (:id submission)]
    [:div.portal-sub {:id (str "proposal-" sid)}
     [:h3 (get-in submission [:answers :talk-title])]
     [:div.portal-event (:name event)
      (when-let [d (format/fmt-date-range (:starts-on event) (:ends-on event))] (str " · " d))]

     (when (seq (:speakers submission))
       [:div.portal-participants {:style "margin-top:0.6em;"}
        [:div.field-hint [:strong "Speakers and roles"]]
        [:ul
         (for [{:keys [name role]} (:speakers submission)]
           [:li name " — " (or role "Speaker")])]])

     (status-pill visible-status)
     (when-not informed?
       [:span.field-hint {:style "margin-left:0.6em;"}
        "The committee will be in touch — you'll hear from us either way."])

     (when (and informed? (= "Accepted" visible-status))
       [:div.accepted-banner {:style "margin-top:0.8em;"}
        [:strong "You're in! 🎉"]
        [:div "We'd love to have this talk. A few small things below when you have a moment."]])

     (when (and informed? (= "Accepted" visible-status) schedule)
       [:div.ui.segment.portal-schedule {:style "margin-top:0.8em;"}
        [:div.ui.tiny.header "Your schedule"]
        [:div
         [:strong (schedule/day-label event (:day schedule))]
         " · " (schedule/time-range-display (:start schedule) (:end schedule))
         " · " (or (:room schedule) "Room TBA")]])

     (when (and informed? (= "Waitlisted" visible-status))
       [:div.ui.message {:style "margin-top:0.8em;"}
        [:strong "You're on the waitlist."]
        [:div "We had more talks we wanted than slots. If one opens we'll come straight to you."]])

     (when (and informed? (= "Declined" visible-status))
       [:div.ui.message {:style "margin-top:0.8em;"}
        [:div "We couldn't fit this one into the program this year. "
         "Thank you for offering it — we hope you'll submit again."]])

     (when edit-lock-message
       [:div.ui.warning.message (edit-lock-copy event visible-status edit-lock-message)])

     [:div {:id (str "onboarding-" sid) :style "margin-top:1em;"}
      [:h5.ui.header {:style "margin-bottom:0.2em;"}
       "Your checklist"
       (when (and informed? (= "Accepted" visible-status))
         [:span.field-hint {:style "margin-left:0.5em; font-weight:400;"}
          (:done progress) " of " (:total progress) " done"])]
      (cond
        (and informed? (= "Accepted" visible-status) (seq tasks))
        (list
          [:ul.task-list (for [t tasks] (task-row event sid t))]
          [:div.field-hint "Checklist forms and uploads stay attached to this talk."])

        (and informed? (= "Accepted" visible-status))
        [:div.field-hint "No onboarding tasks have been assigned yet."]

        :else
        [:div.field-hint "Onboarding tasks will appear here when your participation is confirmed."])]

     [:div {:style "margin-top:0.9em;"}
      (if (= editing-id sid)
        [:a.ui.mini.basic.button {:href "/portal"} "Cancel edit"]
        (when editable?
          [:a.ui.mini.basic.button {:href (str "/portal?edit=" sid)} "Edit this talk"]))]]))

(defn portal-page
  "`opts` = {:person :submissions [..] :participations [..] :editing-id
  :errors :values :message :upload-error}"
  [{:keys [person submissions participations editing-id errors values message profile-errors
           profile-values custom-errors custom-value-overrides upload-error]}]
  (shell/page-shell
    "Your speaker portal"
     ;; The portal streams too — same mount-inside-the-page trick as the CFP, so
     ;; the shared page-shell (and the auth lane's login page) stays untouched.
    [:div#portal-live (ds/sse-mount-url "/portal/stream")
     ;; The 15-second heartbeat patches #sse-heartbeat. A streaming page without
     ;; this span logs PatchElementsNoTargetsFound to the console four times a
     ;; minute — noise that trains everyone to ignore the console.
     [:span#sse-heartbeat]]
    (shell/datastar-script)
    [:div.ui.container {:style "max-width:720px; margin-top:2em;"}
     [:div.cfp-masthead
      [:h1.ui.header "Your speaker portal"]
      [:div.cfp-meta (:name person) " · " (:email person)
       [:form {:method "post" :action "/logout" :style "display:inline; margin-left:0.8em;"}
        [:button.ui.mini.basic.button {:type "submit"} "Log out"]]]]

     (when message [:div.toast message])
     (when upload-error
       [:div.ui.negative.message {:role "alert"} upload-error])

     (when (seq submissions)
       (list
         [:h4.ui.header {} "Your submissions"]
         (for [s submissions]
           (if (= editing-id (:id (:submission s)))
             (edit-form s errors values)
             (portal-submission s editing-id)))))

     (when (seq participations)
       (list
         [:h4.ui.header {} "Your speaking invitations"]
         (for [participation participations]
           (participation-card participation))))

     (when (and (empty? submissions) (empty? participations))
       [:div.ui.segment
        [:div.empty-state
         [:p [:strong "No submissions yet."]]
         [:p "When you submit a talk it will show up here, with its status and "
          "anything we need from you."]]])

     (for [[event-id event-submissions]
           (group-by (comp :id :event) submissions)
           :let [entry (first event-submissions)]
           :when (seq (:custom-fields entry))]
       (custom-fields-form entry
                           (get custom-errors event-id)
                           (get custom-value-overrides event-id)))

     (for [{:keys [event] :as participation} participations
           :let [event-id (:id event)]
           :when (seq (:custom-fields participation))]
       (custom-fields-form participation
                           (get custom-errors event-id)
                           (get custom-value-overrides event-id)))

     [:div#speaker-profile
      (profile-form person (boolean (or (seq submissions) (seq participations)))
                    profile-errors profile-values (empty? submissions))]

     [:div.field-hint {:style "margin-top:2em;"}
      "Questions about your talk? Reply to any email from the organizers — "
      "it reaches a real person."]]))
