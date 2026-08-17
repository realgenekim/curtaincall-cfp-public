(ns cfp-scheduler-killer.handlers.speakers
  (:require
   [cfp-scheduler-killer.announce :as announce]
   [cfp-scheduler-killer.auth :as auth]
   [cfp-scheduler-killer.crm :as crm]
   [cfp-scheduler-killer.domain.speakers :as speaker-domain]
   [cfp-scheduler-killer.events :as events]
   [cfp-scheduler-killer.inform :as inform]
   [cfp-scheduler-killer.portal :as portal]
   [cfp-scheduler-killer.public-catalog :as public-catalog]
   [cfp-scheduler-killer.speaker-csv :as speaker-csv]
   [cfp-scheduler-killer.speaker-custom-fields :as speaker-custom-fields]
   [cfp-scheduler-killer.speakers :as speakers]
   [cfp-scheduler-killer.store :as store]
   [cfp-scheduler-killer.submissions :as submissions]
   [cfp-scheduler-killer.views.announce :as view-announce]
   [cfp-scheduler-killer.views.speakers :as view-speakers]
   [cfp-scheduler-killer.web.datastar :as datastar]
   [cfp-scheduler-killer.web.event :as web-event]
   [cfp-scheduler-killer.web.http :as http]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [com.fulcrologic.guardrails.core :refer [=> >defn ?]]
   [hiccup2.core :as h]))

(def max-csv-bytes (* 1024 1024))

(defn- accepted-sessions
  "Private organizer workset for co-speaker assignment.  Notification and
   public-content gates are deliberately downstream: an organizer must be able
   to verify or repair the accepted proposal's speaker list before telling the
   speakers or publishing it."
  [event-id]
  (->> (store/submissions-for-event event-id)
       (filter #(= "Accepted" (:status %)))
       (sort-by :created-at)
       vec))

(defn- speaker-workflow [value]
  (or (when (= "roster" value) :roster)
      (some (fn [{:keys [id]}]
              (when (and (#{:inform :manage} id)
                         (= value (name id)))
                id))
            speaker-domain/speaker-workspaces)
      :manage))

(defn- workflow-default-status [workflow]
  (:default-filter (speaker-domain/speaker-workspace workflow)))

(defn- pending-submissions-by-person [event]
  (reduce
    (fn [by-person submission]
      (reduce
        (fn [result speaker]
          (if-let [person-id (:person-id speaker)]
            (assoc result (str person-id) (:id submission))
            result))
        by-person
        (:speakers submission)))
    {}
    (inform/pending-decisions (:id event))))

(defn- program-roster [event]
  (let [roster-by-person (into {}
                               (map (juxt (comp str :person-id) identity))
                               (speakers/roster-for-event (:id event)))]
    (mapv
      (fn [program-speaker]
        (let [person-id (str (:id program-speaker))
              person (store/person-by-id person-id)
              profile (:profile person)
              profile-missing (speaker-domain/missing-profile-fields profile)
              roster-row (get roster-by-person person-id)
              status (or (:status roster-row) "Invited")]
          (merge
            {:person-id person-id
             :name (:name program-speaker)
             :email (:email person)
             :tagline (:tagline program-speaker)
             :title (:tagline program-speaker)
             :profile-organization (:company program-speaker)
             :organization (:company program-speaker)
             :bio (:bio program-speaker)
             :headshot-url (:headshot program-speaker)
             :status status
             :talks (mapv :title (:sessions program-speaker))
             :profile-missing profile-missing
             :profile-complete? (empty? profile-missing)
             :lifecycle {:status status :pending-tasks [] :history []}}
            roster-row
            {:person-id person-id
             :manual? (:manual? program-speaker)
             :published? (:published? program-speaker)
             :program-sessions (:sessions program-speaker)})))
      (public-catalog/program-speakers event))))

(defn- filtered-roster [event workflow query status profile]
  (let [needle (some-> query str/trim str/lower-case not-empty)
        pending-by-person (pending-submissions-by-person event)
        informed-people (into #{}
                              (comp (mapcat :speakers)
                                    (keep (comp str :person-id)))
                              (inform/informed (:id event)))
        base-roster (if (= :manage workflow)
                      (program-roster event)
                      (speakers/roster-for-event (:id event)))]
    (->> base-roster
         (map #(assoc % :pending-submission-id
                      (get pending-by-person (str (:person-id %)))))
         (filter (fn [speaker]
                   (and (case status
                          "Needs notification" (:pending-submission-id speaker)
                          "Informed" (contains? informed-people (str (:person-id speaker)))
                          "Active" (#{"Invited" "Confirmed"} (:status speaker))
                          "All" true
                          nil true
                          "" true
                          (= status (:status speaker)))
                        (case profile
                          "complete" (:profile-complete? speaker)
                          "incomplete" (not (:profile-complete? speaker))
                          true)
                        (or (nil? needle)
                            (str/includes?
                              (str/lower-case
                                (str (:name speaker) " " (:email speaker) " "
                                     (:organization speaker) " " (str/join " " (:talks speaker))))
                              needle)))))
         vec)))

(defn- render-speakers
  ([req event extra] (render-speakers req event extra 200))
  ([req event extra response-status]
   (let [query (get-in req [:params :q])
         requested-workflow (get-in req [:params :view])
         workflow (speaker-workflow requested-workflow)
         roster-workflow (if requested-workflow workflow :roster)
         status (or (get-in req [:params :status])
                    (workflow-default-status workflow))
         profile (get-in req [:params :profile])
         notice (get-in req [:params :notice])]
     (http/html-response
       response-status
       (view-speakers/speakers-page
         event
         (merge {:person (auth/current-person req)
                 :query query
                 :workflow workflow
               :roster-workflow roster-workflow
                 :status status
                 :profile profile
                 :message (when (= "portal-invite" notice)
                            "Portal invite prepared and recorded in Comms.")
               :speakers (filtered-roster event roster-workflow query status profile)
               :roster (filtered-roster event roster-workflow query status profile)
                 :sessions (accepted-sessions (:id event))
                 :custom-fields (speaker-custom-fields/fields-for-event (:id event))}
                extra))))))

(defn- event-for [req]
  (events/event-by-slug (get-in req [:path-params :slug])))

(defn- actor [req]
  (:email (auth/current-person req)))

(defn- roster-return-location [event person-id params]
  (let [query (http/not-blank (:return-q params))
        status (http/not-blank (:return-status params))
        profile (http/not-blank (:return-profile params))
        notice (http/not-blank (:return-notice params))
        query-string (str/join "&"
                               (cond-> []
                                 query (conj (str "q=" (java.net.URLEncoder/encode query "UTF-8")))
                                 status (conj (str "status=" (java.net.URLEncoder/encode status "UTF-8")))
                                 profile (conj (str "profile=" (java.net.URLEncoder/encode profile "UTF-8")))
                                 notice (conj (str "notice=" (java.net.URLEncoder/encode notice "UTF-8")))))]
    (str "/events/" (:slug event) "/speakers"
         (when (seq query-string) (str "?" query-string))
         "#speaker-" person-id)))

(defn- roster-return-request [req]
  (assoc req :params
         (assoc (:params req)
                :q (get-in req [:params :return-q])
                :status (get-in req [:params :return-status])
                :profile (get-in req [:params :return-profile]))))

(def ^:private roster-form-projection
  [[:name :name]
   [:tagline :tagline]
   [:profile-organization :org]
   [:bio :bio]
   [:headshot-url :headshot-url]
   [:linkedin-url :linkedin-url]
   [:website-url :website-url]
   [:status :status]
   [:title :title]
   [:event-organization :organization]
   [:notes :notes]])

(defn- attempted-roster [event person-id params]
  (mapv
    (fn [speaker]
      (if (= person-id (:person-id speaker))
        (reduce
          (fn [row [row-key param-key]]
            (if (contains? params param-key)
              (assoc row row-key (get params param-key))
              row))
          speaker
          roster-form-projection)
        speaker))
    (filtered-roster event
                    :roster
                     (:return-q params)
                     (:return-status params)
                     (:return-profile params))))

(defn- attempted-speaker [event person-id params]
  (some #(when (= person-id (:person-id %)) %)
        (attempted-roster event person-id params)))

(defn- profile-edit-error [params]
  (when (contains? params :profile-edit)
    (let [profile-messages (some->> (portal/profile-errors
                                      (portal/parse-profile params))
                                    vals
                                    (mapcat identity))
          name-message (when (and (contains? params :name)
                                  (str/blank? (:name params)))
                         "A speaker name is required.")
          status-message (when (and (contains? params :status)
                                    (not (speaker-domain/statuses (:status params))))
                           "Choose a valid speaker status.")]
      (not-empty (str/join " " (cond-> (vec profile-messages)
                                 name-message (conj name-message)
                                 status-message (conj status-message)))))))

(defn- csv-text [req]
  (let [pasted (get-in req [:params :csv-text])
        upload (get-in req [:params :csv-file])]
    (cond
      (not (str/blank? pasted)) pasted
      (and (map? upload) (:tempfile upload)
           (<= (or (:size upload) 0) max-csv-bytes))
      (slurp (io/file (:tempfile upload)))
      :else nil)))

(defn handle-speakers [req]
  (if-let [event (event-for req)]
    (render-speakers req event {})
    (web-event/not-found-page (get-in req [:path-params :slug]))))

(defn handle-speakers-filter [req]
  (if-let [event (event-for req)]
    (let [{:keys [speakerq speakerstatus speakerprofile speakerworkflow]}
          (datastar/signals req)
          workflow (speaker-workflow speakerworkflow)
          roster (filtered-roster event workflow speakerq speakerstatus speakerprofile)]
      (datastar/sse-fragment-response
        req "#speaker-roster-results"
        (str (h/html
               (view-speakers/speakers-region
                 event roster speakerq speakerstatus speakerprofile)))))
    (web-event/not-found-page (get-in req [:path-params :slug]))))

(>defn ^:private speaker-for-event [event person-id]
       [map? string? => (? map?)]
       (some #(when (= person-id (str (:person-id %))) %)
        (concat (program-roster event)
                (speakers/roster-for-event (:id event)))))

(defn- render-speaker-detail
  ([req event speaker error]
   (render-speaker-detail req event speaker error 200))
  ([req event speaker error response-status]
   (http/html-response
     response-status
     (view-speakers/speaker-page
       event
       {:person (auth/current-person req)
        :speaker speaker
        :custom-fields (speaker-custom-fields/fields-for-event (:id event))
        :error error}))))

(defn handle-speaker-detail [req]
  (if-let [event (event-for req)]
    (let [person-id (http/clean-id (get-in req [:path-params :person-id]))]
      (if-let [speaker (speaker-for-event event person-id)]
        (render-speaker-detail req event speaker nil)
        (web-event/not-found-page "that event speaker")))
    (web-event/not-found-page (get-in req [:path-params :slug]))))

(defn- assignment-person-id
  [req]
  (or (http/not-blank (get-in req [:params :person-id]))
      (some-> (get-in req [:params :speaker-email])
              str str/trim str/lower-case http/not-blank
              store/person-by-email :id)))

(defn- assignment-location
  [event submission-id]
  (str "/events/" (:slug event) "/speakers#session-" submission-id))

(defn handle-assign-session-speaker
  [req]
  (if-let [event (event-for req)]
    (try
      (let [submission-id (get-in req [:path-params :submission-id])
            person-id (or (assignment-person-id req)
                          (throw (ex-info "Choose a speaker from this event roster."
                                          {:type :speaker-required})))]
        (submissions/assign-speaker!
          (:id event) submission-id person-id (actor req))
        (http/see-other (assignment-location event submission-id)))
      (catch clojure.lang.ExceptionInfo e
        (render-speakers req event {:message (.getMessage e)} 422)))
    (web-event/not-found-page (get-in req [:path-params :slug]))))

(defn handle-unassign-session-speaker
  [req]
  (if-let [event (event-for req)]
    (try
      (let [submission-id (get-in req [:path-params :submission-id])
            person-id (get-in req [:path-params :person-id])]
        (submissions/unassign-speaker!
          (:id event) submission-id person-id (actor req))
        (http/see-other (assignment-location event submission-id)))
      (catch clojure.lang.ExceptionInfo e
        (render-speakers req event {:message (.getMessage e)} 422)))
    (web-event/not-found-page (get-in req [:path-params :slug]))))

(defn- confirmed-duplicate? [req]
  (not (str/blank? (str (get-in req [:params :confirm-duplicate])))))

(defn- duplicate-candidates
  "Advisory same-name matches, scoped to what this viewer can already see.
   Skipped once the organizer has confirmed — the warning must be passable in
   one click, so it can never become a second gate on the same submission."
  [req values]
  (when-not (confirmed-duplicate? req)
    (seq (crm/duplicate-name-candidates
           (:id (auth/current-person req)) (:name values) (:email values)))))

(defn handle-add-speaker [req]
  (if-let [event (event-for req)]
    (let [values {:name (get-in req [:params :name])
                  :email (get-in req [:params :email])
                  :status (get-in req [:params :status])}]
      (if-let [matches (duplicate-candidates req values)]
        ;; Nothing is written on this pass. The organizer either edits the form
        ;; or re-posts this same endpoint with confirm-duplicate set.
        (render-speakers req event
                         {:duplicate-warning {:values values :matches (vec matches)}})
        (let [result (speakers/add! (:id event) (assoc values :actor (actor req)))]
          (if-let [message (get-in result [:rejected :message])]
            (assoc (render-speakers req event {:message message :values values}) :status 422)
            (http/see-other
              (if (:existing? result)
                (str "/events/" (:slug event) "/speakers#speaker-" (:person-id result))
                (str "/events/" (:slug event) "/speakers")))))))
    (web-event/not-found-page (get-in req [:path-params :slug]))))

(defn handle-define-custom-field [req]
  (if-let [event (event-for req)]
    (let [params (:params req)
          result (speaker-custom-fields/define!
                   (:id event)
                   {:label (:label params)
                    :type (:type params)
                    :required (some? (:required params))}
                   (actor req))]
      (if-let [rejection (:rejected result)]
        (render-speakers req event {:message (:message rejection)} 422)
        (http/see-other (str "/events/" (:slug event) "/speakers"))))
    (web-event/not-found-page (get-in req [:path-params :slug]))))

(defn handle-speaker-status [req]
  (if-let [event (event-for req)]
    (let [person-id (http/clean-id (get-in req [:path-params :person-id]))
          result (speakers/change-status!
                   (:id event) person-id (get-in req [:params :status]) (actor req))]
      (if-let [{:keys [reason message]} (:rejected result)]
        (if (= :speaker-not-found reason)
          (web-event/not-found-page "that event speaker")
          (render-speakers (roster-return-request req) event
                           {:row-errors {person-id message}} 422))
        (http/see-other (roster-return-location event person-id (:params req)))))
    (web-event/not-found-page (get-in req [:path-params :slug]))))

(defn- handle-program-edit
  "The Announce marquee's inline panel posts its PROGRAM edit to this same
   route (see announce/program-edit?). A program edit belongs to the person's
   durable profile and the event's program entry — including the publish
   toggle — not to the event-local participation fields the roster owns."
  [req event person-id]
  (let [values (announce/speaker-form-values (:params req))
        errors (announce/speaker-form-errors values)]
    (cond
      errors
      (http/html-response
        422
        (view-announce/announce-page
          event
          {:person (auth/current-person req)
           :roster (public-catalog/program-speakers event)
           :ready (announce/ready-to-announce (:id event))
           :base-url (http/request-host req)
           :stats (public-catalog/announce-stats event)
           :edit {:person-id person-id :values values :errors errors}}))

      (:not-found? (announce/update-program-speaker!
                     (:id event) person-id values (actor req)))
      (web-event/not-found-page person-id)

      :else
      (http/see-other
        (str "/events/" (:slug event) "/announce#edit-" person-id)))))

(defn handle-edit-speaker [req]
  (if-let [event (event-for req)]
    (let [person-id (http/clean-id (get-in req [:path-params :person-id]))
          params (:params req)
          program-speaker (public-catalog/program-speaker-by-id event person-id)
          participant? (some #(= (str person-id) (str (:person-id %)))
                             (speakers/roster-for-event (:id event)))]
      (if (and (not (contains? params :profile-edit))
               (announce/program-edit? params))
        (handle-program-edit req event person-id)
        (if-let [profile-error (profile-edit-error params)]
          (render-speaker-detail
            req event (attempted-speaker event person-id params) profile-error 422)
          (let [custom-fields (when (contains? params :profile-edit)
                                (speaker-custom-fields/fields-for-event (:id event)))
                custom-values (when (seq custom-fields)
                                (speaker-custom-fields/parse-values custom-fields params))
                custom-result (when (seq custom-fields)
                                (speaker-custom-fields/update-values!
                                  (:id event) person-id custom-values (actor req)))]
            (if-let [rejection (:rejected custom-result)]
              (render-speaker-detail
                req event (attempted-speaker event person-id params) (:message rejection) 422)
              (let [changes (select-keys params [:title :organization :location :notes])
                    result (if participant?
                             (speakers/edit! (:id event) person-id changes (actor req))
                             {:ok true})]
                (if (:rejected result)
                  (web-event/not-found-page "that event speaker")
                  (do
                    (when (contains? params :profile-edit)
                      (when (and participant? (contains? params :name))
                        (speakers/rename! (:id event) person-id (:name params) (actor req)))
                      (portal/update-profile!
                        person-id
                        (select-keys params [:tagline :org :bio :headshot-url
                                             :linkedin-url :website-url])
                        (actor req))
                      (when (:manual? program-speaker)
                        (announce/update-program-speaker!
                          (:id event)
                          person-id
                          {:name (:name params)
                           :org (:org params)
                           :title (:tagline params)
                           :headshot-url (:headshot-url params)
                           :bio (:bio params)
                           :announce? (contains? params :announce)}
                          (actor req)))
                      (when (and participant? (contains? params :status))
                        (speakers/change-status!
                          (:id event) person-id (:status params) (actor req))))
                    (http/see-other
                      (str "/events/" (:slug event) "/speakers/" person-id))))))))))
    (web-event/not-found-page (get-in req [:path-params :slug]))))

(defn handle-portal-invite [req]
  (if-let [event (event-for req)]
    (let [person-id (http/clean-id (get-in req [:path-params :person-id]))
          speaker (some #(when (= person-id (:person-id %)) %)
                        (speakers/roster-for-event (:id event)))
          actor-email (actor req)]
      (if-not speaker
        (web-event/not-found-page "that event speaker")
        (let [token
              (auth/issue-token!
                (:email speaker)
                {:letter-fn
                 (fn [token _person]
                   {:from actor-email
                    :to (:email speaker)
                    :reply-to actor-email
                    :subject (str "Your " (:name event) " speaker portal")
                    :body (str "Hi " (:name speaker) ",\n\n"
                               (if (seq (:talks speaker))
                                 (str "Your submission to " (:name event) " is in. "
                                      "Use this private one-time link to review your talks, "
                                      "profile, tasks, and files:\n\n")
                                 (str "You're invited to speak at " (:name event) ". "
                                      "Use this private one-time link to set up your speaker profile:\n\n"))
                               (http/request-host req) "/auth/" token
                               "\n\nThe link expires in 24 hours. If you did not expect "
                               "this message, reply to the organizer.\n")})
                 :context {:event-id (:id event)
                           :kind "portal-invite"
                           :actor actor-email
                           :person-id person-id}})]
          (if token
            (http/see-other
              (roster-return-location
                event person-id
                (assoc (:params req) :return-notice "portal-invite")))
            (web-event/not-found-page "that event speaker")))))
    (web-event/not-found-page (get-in req [:path-params :slug]))))

(defn handle-import-preview [req]
  (if-let [event (event-for req)]
    (let [text (csv-text req)
          preview (speaker-csv/parse text)]
      (render-speakers req event {:csv-text text :import-preview preview}))
    (web-event/not-found-page (get-in req [:path-params :slug]))))

(defn handle-import-speakers [req]
  (if-let [event (event-for req)]
    (let [text (csv-text req)
          parsed (speaker-csv/parse text)
          result (speakers/import! (:id event) parsed (actor req))]
      (if-let [message (get-in result [:rejected :message])]
        (assoc (render-speakers req event {:message message
                                           :csv-text text
                                           :import-preview parsed})
               :status 422)
        (http/see-other (str "/events/" (:slug event) "/speakers"))))
    (web-event/not-found-page (get-in req [:path-params :slug]))))
