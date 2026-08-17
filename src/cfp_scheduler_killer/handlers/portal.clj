(ns cfp-scheduler-killer.handlers.portal
  (:require
   [cfp-scheduler-killer.auth :as auth]
   [cfp-scheduler-killer.events :as events]
   [cfp-scheduler-killer.files :as files]
   [cfp-scheduler-killer.live-validation :as live-validation]
   [cfp-scheduler-killer.portal :as portal]
   [cfp-scheduler-killer.speaker-custom-fields :as speaker-custom-fields]
   [cfp-scheduler-killer.sse :as sse]
   [cfp-scheduler-killer.store :as store]
   [cfp-scheduler-killer.submissions :as submissions]
   [cfp-scheduler-killer.views.live-drafts :as live-drafts]
   [cfp-scheduler-killer.views.portal :as view-portal]
   [cfp-scheduler-killer.web.event :as web-event]
   [cfp-scheduler-killer.web.http :as http]
   [clojure.string :as str]
   [taoensso.timbre :as log]))

(defonce ^{:doc "Per-person portal drafts: {person-id {scope {param value}}}.

  `scope` is \"profile\" or a submission id, because the portal can have two
  forms open at once and a bio must never leak into a talk. Same contract as the
  public page's `cfp-drafts`: a refresh repaints what was being typed. The
  portal's viewer always HAS an account, so this keys on person-id — which is
  strictly better than a cookie, since it survives a new browser."}
  portal-drafts (atom {}))

(defonce ^{:doc "Last portal note pushed, per person: {person-id {param note}}."}
  portal-notes-sent (atom {}))

(defn portal-channel
  "The SSE key for one speaker's portal. Per PERSON, not per event — a speaker's
   portal spans every conference they submitted to, and two speakers must never
   land on the same key."
  [person-id]
  (str "portal-" person-id))

(defn portal-draft-for
  "What this person has typed into one portal form, as {param value}."
  [person scope]
  (get-in @portal-drafts [(:id person) scope] {}))

(defn stash-portal-draft! [person scope params]
  (when (and person scope)
    (swap! portal-drafts update-in [(:id person) scope] merge
           (into {} (filter (fn [[_ v]] (string? v))) (dissoc params :dscope)))))

(defn clear-portal-draft! [person scope]
  (when (and person scope)
    (swap! portal-drafts update (:id person) dissoc scope)
    (swap! portal-notes-sent dissoc (:id person))))

(defn render-portal
  ([req] (render-portal req {} 200))
  ([req extra status]
   (let [person (auth/current-person req)
         submissions (portal/my-submissions (:id person))
         submission-event-ids (into #{} (map (comp :id :event)) submissions)
         _ (doseq [event-id submission-event-ids]
             (portal/record-visit! event-id
                                   (:id person)
                                   (or (:email person) (str (:id person)))))
         participations (->> (portal/my-participations (:id person))
                             (remove #(contains? submission-event-ids
                                                 (get-in % [:event :id])))
                             vec)
         requested-id (or (http/clean-id (:editing-id extra))
                          (http/clean-id (get-in req [:params :edit])))
         editing-id (some (fn [{:keys [submission editable?]}]
                            (when (and editable? (= requested-id (:id submission)))
                              requested-id))
                          submissions)
         uploaded (some-> (get-in req [:params :uploaded]) str str/trim not-empty)]
     (http/html-response
       status
       (view-portal/portal-page
         (merge {:person person
                 :submissions submissions
                 :participations participations
                 :editing-id editing-id
                 :message (cond
                            uploaded (str uploaded " uploaded; this task is now complete.")
                            (get-in req [:params :saved]) "Saved.")
                 :upload-error (get-in req [:params :upload-error])
                 :profile-values (merge (portal-draft-for person "profile")
                                        (:profile-values extra))
                 :values (merge (when editing-id (portal-draft-for person editing-id))
                                (:values extra))}
                (dissoc extra :profile-values :values :editing-id)))))))

(defn handle-portal [req] (render-portal req))

(defn- own-submission [req submission-id]
  (let [person (auth/current-person req)
        row (when submission-id (store/submission-by-id submission-id))]
    (when (and person row
               (some #(= (:id person) (:person-id %)) (:speakers row)))
      row)))

(defn handle-portal-answers [req]
  (let [sid (http/clean-id (get-in req [:path-params :submission-id]))]
    (if-let [row (own-submission req sid)]
      (let [person (auth/current-person req)
            result (portal/update-answers! (:id row) (:params req) (:email person))]
        (if (:ok result)
          (do (clear-portal-draft! person (:id row))
              (http/see-other "/portal?saved=1"))
          (do (stash-portal-draft! person (:id row) (:params req))
              (render-portal req {:editing-id (:id row)
                                  :errors (:errors result)
                                  :values (:params req)}
                             422))))
      (web-event/not-found-page "that submission"))))

(defn- upload-param [req]
  (let [upload (get-in req [:params :file])]
    (when (and (map? upload) (:tempfile upload)) upload)))

(defn- profile-event-id [person-id]
  (or (some-> (portal/my-submissions person-id) first :event :id)
      (some-> (portal/my-participations person-id) first :event :id)))

(defn- upload-error-message [error]
  (let [details (seq (vals (:errors (ex-data error))))]
    (str "Headshot could not be saved: "
         (if details
           (str/join " " details)
           (or (.getMessage error) "Check the image and try again.")))))

(defn handle-portal-profile [req]
  (let [person (auth/current-person req)
        actor (:email person)
        upload (upload-param req)
        profile-params (dissoc (:params req) :file :dscope)
        parsed-profile (portal/parse-profile profile-params)
        validation-errors (portal/profile-errors parsed-profile)]
    (if validation-errors
      (do (stash-portal-draft! person "profile" profile-params)
          (render-portal req {:profile-errors validation-errors
                              :profile-values parsed-profile}
                         422))
      (try
        (let [event-id (profile-event-id (:id person))
              headshot-url
              (when upload
                (when-not event-id
                  (throw (ex-info "Join an event before uploading a headshot."
                                  {:type :speaker-event-required})))
                (let [file (files/upload! {:source (:tempfile upload)
                                           :filename (:filename upload)
                                           :content-type (:content-type upload)
                                           :size (:size upload)
                                           :event-id event-id
                                           :person-id (:id person)
                                           :kind "Headshot"
                                           :actor actor})]
                  (str (http/request-host req) "/headshots/" (:id file))))
              result (portal/update-profile!
                       (:id person)
                       (cond-> profile-params headshot-url (assoc :headshot-url headshot-url))
                       actor)]
          (if (:ok result)
            (do
              (when headshot-url
                (doseq [{:keys [submission]} (portal/my-submissions (:id person))]
                  (portal/complete-task! (:id submission) "headshot" headshot-url actor)))
              (clear-portal-draft! person "profile")
              (http/see-other (str "/portal?saved=1"
                                   (when headshot-url "&headshot=1"))))
            (do (stash-portal-draft! person "profile" profile-params)
                (render-portal req {:profile-errors (:errors result)
                                    :profile-values (:profile result)}
                               422))))
        (catch clojure.lang.ExceptionInfo error
          (stash-portal-draft! person "profile" profile-params)
          (render-portal req {:profile-values parsed-profile
                              :upload-error (upload-error-message error)}
                         422))))))

(defn handle-portal-custom-values [req]
  (let [slug (get-in req [:path-params :slug])
        event (events/event-by-slug slug)
        person (auth/current-person req)]
    (if-not event
      (web-event/not-found-page slug)
      (let [fields (speaker-custom-fields/fields-for-event (:id event))
            values (speaker-custom-fields/parse-values fields (:params req))
            result (speaker-custom-fields/update-values!
                     (:id event) (:id person) values (:email person))]
        (if-let [{:keys [reason message errors]} (:rejected result)]
          (if (= :speaker-not-found reason)
            (web-event/not-found-page "that event speaker")
            (render-portal req
                           {:message message
                            :custom-errors {(:id event) errors}
                            :custom-value-overrides {(:id event) values}}
                           422))
          (http/see-other "/portal?saved=1"))))))

(defn- with-attempted-task-value
  [submissions submission-id task-key value errors]
  (mapv (fn [entry]
          (if (= submission-id (get-in entry [:submission :id]))
            (update entry :tasks
                    #(mapv (fn [task]
                             (if (= task-key (:key task))
                               (cond-> (assoc task :value value)
                                 errors (assoc :form-errors errors))
                               task))
                           %))
            entry))
        submissions))

(defn- task-form-errors
  "Reuse the CFP field-definition schema without coupling task recipes to the
   form builder's event lifecycle. Only task field errors leave this seam."
  [task answers]
  (let [valid-sentinel {:name "Task response"
                        :email "task-response@example.com"
                        :title "Speaker"
                        :org "Event"
                        :bio "Task response validation sentinel."}
        field-ids (mapv (comp keyword name :id) (:fields task))]
    (not-empty
      (select-keys
        (submissions/validation-errors (:fields task) answers valid-sentinel)
        field-ids))))

(defn handle-portal-task [req]
  (let [sid (http/clean-id (get-in req [:path-params :submission-id]))]
    (if-let [row (own-submission req sid)]
      (let [person (auth/current-person req)
            task-key (get-in req [:params :key])
            task (get-in (store/snapshot) [:tasks [(:id row) task-key]])
            form-task? (= "form" (:task-type task))
            value (if form-task?
                    (submissions/parse-answers (:fields task) (:params req))
                    (get-in req [:params :value]))
            errors (when form-task? (task-form-errors task value))]
        (if errors
          (render-portal
            req
            {:message "Complete the required form fields."
             :submissions
             (with-attempted-task-value
               (portal/my-submissions (:id person)) (:id row) task-key value errors)}
            422)
          (try
            (portal/complete-task! (:id row) task-key value (:email person))
            (http/see-other "/portal")
            (catch clojure.lang.ExceptionInfo error
              (if (#{:value-required :invalid-url :file-upload-required
                     :form-response-required}
                   (:type (ex-data error)))
                (render-portal
                  req
                  {:message (.getMessage error)
                   :submissions
                   (with-attempted-task-value
                     (portal/my-submissions (:id person)) (:id row) task-key value nil)}
                  422)
                (throw error))))))
      (web-event/not-found-page "that submission"))))

(defn handle-portal-stream [req]
  (if-let [person (auth/current-person req)]
    (sse/handle-sse (assoc-in req [:params :event-id] (portal-channel (:id person))))
    {:status 403 :headers {"Content-Type" "text/plain"} :body "sign in first"}))

(defn handle-portal-draft [req]
  (let [person (auth/current-person req)
        scope (http/not-blank (get-in req [:params :dscope]))]
    (if-not (and person scope)
      {:status 204 :headers {} :body ""}
      (let [submission (when (not= "profile" scope) (own-submission req scope))]
        (if (and (not= "profile" scope) (nil? submission))
          {:status 403 :headers {"Content-Type" "text/plain"} :body "not your talk"}
          (let [chan (portal-channel (:id person))
                _ (stash-portal-draft! person scope (:params req))
                values (portal-draft-for person scope)
                notes (live-validation/portal-live-notes scope values submission)
                was (get @portal-notes-sent (:id person) {})
                reach (sse/person-connection-count chan (:id person))
                changed (remove #(= (get was %) (get notes %))
                                (distinct (concat (keys was) (keys notes))))]
            (swap! portal-notes-sent assoc (:id person) notes)
            (when (zero? reach)
              (log/warn :portal-draft-push-no-subscriber
                        :event-id chan :person-id (:id person)
                        :registrations (sse/registrations)
                        :msg "nothing is listening on this key — the portal cannot move"))
            (doseq [param changed]
              (sse/push-to-person! chan (:id person) (str "#cfp-note-" (name param))
                                   #(live-drafts/cfp-note param (get notes param))))
            (sse/push-to-person! chan (:id person) (str "#portal-status-" scope)
                                 #(live-drafts/portal-draft-status scope true))
            (log/debug :portal-draft-stashed :person-id (:id person) :scope scope
                       :reach reach :fields (count values) :notes (count notes))
            {:status 204 :headers {} :body ""}))))))
