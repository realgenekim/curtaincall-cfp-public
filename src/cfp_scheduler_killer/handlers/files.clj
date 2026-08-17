(ns cfp-scheduler-killer.handlers.files
  (:require
   [cfp-scheduler-killer.auth :as auth]
   [cfp-scheduler-killer.domain.files :as domain]
   [cfp-scheduler-killer.domain.speaker-tasks :as task-decisions]
   [cfp-scheduler-killer.events :as events]
   [cfp-scheduler-killer.files :as files]
   [cfp-scheduler-killer.portal :as portal]
   [cfp-scheduler-killer.speaker-tasks :as speaker-tasks]
   [cfp-scheduler-killer.store :as store]
   [cfp-scheduler-killer.views.files :as view-files]
   [cfp-scheduler-killer.web.event :as web-event]
   [cfp-scheduler-killer.web.http :as http]
   [clojure.string :as str])
  (:import
   (java.net URLEncoder)
   (java.nio.charset StandardCharsets)
   (java.time LocalDate)))

(defn- own-submission [req submission-id]
  (let [person (auth/current-person req)
        submission (when submission-id (store/submission-by-id submission-id))]
    (when (and person submission
               (some #(= (:id person) (:person-id %)) (:speakers submission)))
      submission)))

(defn- upload-param [req]
  (let [upload (get-in req [:params :file])]
    (when (and (map? upload) (:tempfile upload)) upload)))

(defn- upload-error-message [e]
  (let [details (seq (vals (:errors (ex-data e))))]
    (str "Upload could not be saved: "
         (if details
           (str/join " " details)
           (or (.getMessage e) "Check the file and try again.")))))

(defn- upload-response [f redirect]
  (try
    (f)
    (http/see-other redirect)
    (catch clojure.lang.ExceptionInfo e
      (http/see-other
        (str (first (str/split redirect #"\?" 2)) "?upload-error="
             (URLEncoder/encode (upload-error-message e)
                                StandardCharsets/UTF_8))))))

(defn- parse-date [s]
  (try
    (some-> (http/not-blank s) LocalDate/parse)
    (catch Exception _ nil)))

(defn- param-values [value]
  (cond
    (nil? value) []
    (sequential? value) value
    :else [value]))

(defn- selected-submission-ids [req]
  (->> (or (seq (param-values (get-in req [:params :submission-ids])))
           (param-values (get-in req [:params :submission-id])))
       (keep http/clean-id)
       distinct
       vec))

(defn- prepared-zip [req event-files]
  (when (= "true" (get-in req [:params :prepare-zip]))
    (let [requested (->> (param-values (get-in req [:params :file-ids]))
                         (keep http/clean-id)
                         distinct
                         vec)
          file-by-id (into {} (map (juxt :id identity)) event-files)
          selected-files (if (seq requested)
                           (into [] (keep file-by-id) requested)
                           event-files)]
      {:grouping (if (= "flat" (get-in req [:params :grouping]))
                   "flat"
                   "by-session")
       :files (mapv (fn [file]
                      {:id (:id file)
                       :filename (:filename (domain/latest-version file))})
                    selected-files)})))

(defn- dashboard-filters [req]
  (let [status (get-in req [:params :status])
        kind (get-in req [:params :kind])
        sort-key (get-in req [:params :sort])
        file-sort (get-in req [:params :file-sort])]
    {:q (some-> (get-in req [:params :q]) str str/trim not-empty)
     :status (if (#{"all" "pending" "received"} status) status "all")
     :kind (if (or (= "all" kind) (domain/file-kinds kind)) kind "all")
     :sort (if (#{"due-asc" "due-desc" "speaker-asc"} sort-key)
             sort-key
             "due-asc")
     :file-sort (if (#{"uploaded-newest" "uploaded-oldest"
                       "speaker-asc" "filename-asc"} file-sort)
                  file-sort
                  "uploaded-newest")}))

(defn- contains-query? [q values]
  (or (nil? q)
      (str/includes? (str/lower-case (str/join " " (remove nil? values)))
                     (str/lower-case q))))

(defn- request-matches? [{:keys [done? file-kind] :as request}
                         {:keys [q status kind]}]
  (and (case status
         "pending" (not done?)
         "received" done?
         true)
       (or (= "all" kind) (= kind file-kind))
       (contains-query? q [(:label request) (:session-title request)
                           (:speaker-name request) (:speaker-email request)
                           (:instructions request)])))

(defn- request-sort-key [sort-key request]
  (let [due-on (:due-on request)
        due-day (some-> ^LocalDate due-on .toEpochDay)
        speaker (str/lower-case (str (:speaker-name request)))]
    (case sort-key
      "due-desc" [(if due-on 0 1) (if due-day (- due-day) 0) speaker]
      "speaker-asc" [speaker (if due-on 0 1) (or due-day 0)]
      [(if due-on 0 1) (or due-day 0) speaker])))

(defn- file-matches? [file {:keys [q kind status]}]
  (let [versions (:versions file)]
    (and (not= "pending" status)
         (or (= "all" kind) (= kind (:kind file)))
         (contains-query?
           q
           (concat [(:kind file) (:owner-name file) (:search-owner-name file)
                    (:session-title file)]
                   (mapcat (juxt :filename :uploaded-by) versions))))))

(defn- sort-files [sort-key files]
  (let [latest #(peek (:versions %))
        uploaded-at #(or (:uploaded-at (latest %)) "")
        speaker #(str/lower-case
                   (str (or (not-empty (:speaker-names %)) (:owner-name %))))
        filename #(str/lower-case (str (:filename (latest %))))]
    (->> files
         (sort-by
           (case sort-key
             "uploaded-oldest" #(or (:uploaded-at (latest %)) "9999")
             "speaker-asc" (juxt speaker uploaded-at :id)
             "filename-asc" (juxt filename uploaded-at :id)
             uploaded-at)
           (if (= "uploaded-newest" sort-key)
             #(compare %2 %1)
             compare))
         vec)))

(defn- speaker-projection [submission]
  (let [speakers (:speakers submission)
        names (str/join ", " (keep :name speakers))
        emails (str/join " " (keep :email speakers))]
    {:speaker-name names
     :speaker-email emails
     :speaker-search (str names " " emails)}))

(defn- current-speaker-identity [speaker]
  (let [person (some-> (:person-id speaker) store/person-by-id)]
    (cond-> speaker
      (seq (:name person)) (assoc :name (:name person))
      (seq (:email person)) (assoc :email (:email person)))))

(defn- current-submission-speakers [submission]
  (update submission :speakers #(mapv current-speaker-identity %)))

(defn handle-files [req]
  (let [slug (get-in req [:path-params :slug])]
    (if-let [event (events/event-by-slug slug)]
      (http/html-response
        (let [submissions (mapv current-submission-speakers
                                (store/submissions-for-event (:id event)))
              submission-by-id (into {} (map (juxt :id identity)) submissions)
              title-by-id (into {}
                                (map (juxt :id #(or (get-in % [:answers :talk-title])
                                                    "Untitled session")))
                                submissions)
              filters (dashboard-filters req)
              requests (->> submissions
                            (mapcat (fn [submission]
                                      (let [speakers (speaker-projection submission)]
                                        (->> (speaker-tasks/tasks-for-submission (:id submission))
                                             (filter #(= "file" (:task-type %)))
                                             (map #(assoc %
                                                          :session-title (get title-by-id (:id submission))
                                                          :speaker-name (:speaker-name speakers)
                                                          :speaker-email (:speaker-email speakers)))))))
                            (filter #(request-matches? % filters))
                            (sort-by #(request-sort-key (:sort filters) %))
                            vec)
              all-event-files (->> (files/for-event (:id event))
                                   (mapv (fn [file]
                                           (let [submission (get submission-by-id
                                                                 (:submission-id file))
                                                 speakers (some-> submission
                                                                  speaker-projection)]
                                             (cond->
                                               (assoc file
                                                      :owner-name
                                                      (some-> (:person-id file)
                                                              store/person-by-id :name)
                                                      :session-title
                                                      (get title-by-id (:submission-id file)))
                                               submission
                                               (assoc :speaker-names
                                                      (:speaker-name speakers)
                                                      :search-owner-name
                                                      (:speaker-search speakers)))))))
              event-files (->> all-event-files
                               (filter #(file-matches? % filters))
                               (sort-files (:file-sort filters)))]
          (view-files/files-page
            event {:person (auth/current-person req)
                   :submissions submissions
                   :requests requests
                   :files event-files
                   :prepared-zip (prepared-zip req all-event-files)
                   :filters filters
                   :message (get-in req [:params :message])})))
      (web-event/not-found-page slug))))

(defn handle-request-file [req]
  (let [slug (get-in req [:path-params :slug])
        event (events/event-by-slug slug)
        submission-ids (selected-submission-ids req)
        submissions (mapv store/submission-by-id submission-ids)
        raw-request-id (http/not-blank (get-in req [:params :request-id]))
        request-id (if raw-request-id
                     (http/clean-id raw-request-id)
                     (store/new-id))
        kind (get-in req [:params :file-kind])
        request-name (http/not-blank (get-in req [:params :request-name]))
        due-on (parse-date (get-in req [:params :due-on]))
        actor (:email (auth/current-person req))]
    (if (and raw-request-id (nil? request-id))
      (http/text-response 422 "text/plain; charset=utf-8"
                          "Request identity is invalid. Refresh the page and try again.")
      (if (empty? submission-ids)
        (http/text-response 422 "text/plain; charset=utf-8"
                            "Choose at least one session.")
        (if-not (and event
                     (= (count submission-ids) (count (remove nil? submissions)))
                     (every? #(= (:id event) (:event-id %)) submissions))
          (web-event/not-found-page "that session")
          (if-not (and due-on (domain/file-kinds kind)
                       (http/not-blank (get-in req [:params :instructions])))
            (http/text-response 422 "text/plain; charset=utf-8"
                                "Choose a deliverable type, due date, and instructions.")
            (let [task-key (str "file-" request-id)
                  task {:key task-key
                        ;; Older/API clients may omit the newly exposed human name;
                        ;; keep their established generic label while the organizer
                        ;; UI always sends the explicit request name.
                        :label (or request-name (str "Upload " (str/lower-case kind)))
                        :task-type "file" :required? true :due-on due-on
                        :file-kind kind :instructions (get-in req [:params :instructions])}
                  state (store/snapshot)
                  at (store/now-iso)
                  decisions (mapv #(task-decisions/decide-install
                                     state
                                     (merge task {:submission-id % :actor actor :at at}))
                                  submission-ids)]
              (if-let [rejection (some :rejected decisions)]
                (http/text-response
                  409 "text/plain; charset=utf-8"
                  (str "Request identity conflicts with existing work: "
                       (:message rejection)))
                (do
                  (doseq [submission-id submission-ids]
                    (speaker-tasks/install! submission-id task actor))
                  (http/see-other
                    (str "/events/" slug "/files?message="
                         (URLEncoder/encode
                           (if (every? (comp empty? :facts) decisions)
                             (str "Request already exists for " (count submission-ids)
                                  " sessions")
                             (str "Request created for " (count submission-ids)
                                  " sessions"))
                           StandardCharsets/UTF_8))))))))))))

(defn handle-speaker-upload [req]
  (let [submission-id (http/clean-id (get-in req [:path-params :submission-id]))
        task-key (get-in req [:path-params :task-key])
        submission (own-submission req submission-id)
        task (get-in (store/snapshot) [:tasks [submission-id task-key]])
        person (auth/current-person req)
        kind (or (:file-kind task)
                 (if (= "headshot" task-key) "Headshot" "Presentation"))
        upload (upload-param req)]
    (if-not (and submission task upload)
      (web-event/not-found-page "that file request")
      (upload-response
        #(let [file (files/upload! {:source (:tempfile upload)
                                    :filename (:filename upload)
                                    :content-type (:content-type upload)
                                    :size (:size upload)
                                    :event-id (:event-id submission)
                                    :submission-id submission-id :task-key task-key
                                    :kind kind
                                    :force-version? true
                                    :actor (:email person)})]
           (when (= "Headshot" kind)
             (portal/update-profile!
               (:id person)
               {:headshot-url (str (http/request-host req) "/headshots/" (:id file))}
               (:email person)))
           file)
        (str "/portal?uploaded="
             (URLEncoder/encode (:filename upload) StandardCharsets/UTF_8))))))

(defn handle-profile-headshot [req]
  (let [submission-id (http/clean-id (get-in req [:path-params :submission-id]))
        submission (own-submission req submission-id)
        person (auth/current-person req)
        upload (upload-param req)]
    (if-not (and submission person upload)
      (web-event/not-found-page "that speaker profile")
      (upload-response
        #(let [file (files/upload! {:source (:tempfile upload)
                                    :filename (:filename upload)
                                    :content-type (:content-type upload)
                                    :size (:size upload)
                                    :event-id (:event-id submission)
                                    :person-id (:id person) :kind "Headshot"
                                    :force-version? true
                                    :actor (:email person)})
               headshot-url (str (http/request-host req) "/headshots/" (:id file))]
           (portal/update-profile!
             (:id person)
             {:headshot-url headshot-url}
             (:email person))
           ;; The default checklist predates file tasks and records headshot as
           ;; a URL obligation. Saving the profile is the evidence that fulfils
           ;; it; without this bridge the organizer keeps chasing completed work.
           (portal/complete-task! submission-id "headshot" headshot-url (:email person))
           file)
        "/portal"))))

(def ^:private comment-validation-types
  #{:actor-required :comment-id-required :comment-required :comment-too-long})

(defn- comment-actor [req]
  (let [{:keys [name email]} (auth/current-person req)]
    (if (str/blank? name)
      email
      (str name " <" email ">"))))

(defn- comment-response [add-comment redirect]
  (try
    (add-comment)
    (http/see-other redirect)
    (catch clojure.lang.ExceptionInfo e
      (if (comment-validation-types (:type (ex-data e)))
        (assoc-in
          (http/text-response
            422
            "text/plain; charset=utf-8"
            (str "Comment could not be saved: " (.getMessage e)))
          [:headers "Cache-Control"] "no-store")
        (throw e)))))

(defn handle-speaker-comment [req]
  (let [submission-id (http/clean-id (get-in req [:path-params :submission-id]))
        file-id (http/clean-id (get-in req [:path-params :file-id]))
        submission (own-submission req submission-id)
        file (files/by-id file-id)]
    (if-not (and submission file (= submission-id (:submission-id file)))
      (web-event/not-found-page "that file")
      (comment-response
        #(files/add-comment! file-id (get-in req [:params :comment-id])
                             (get-in req [:params :body])
                             (comment-actor req))
        (str "/portal#onboarding-" submission-id)))))

(defn handle-organizer-comment [req]
  (let [slug (get-in req [:path-params :slug])
        event (events/event-by-slug slug)
        file-id (http/clean-id (get-in req [:path-params :file-id]))
        file (files/by-id file-id)]
    (if-not (and event file (= (:id event) (:event-id file)))
      (web-event/not-found-page "that file")
      (comment-response
        #(files/add-comment! file-id (get-in req [:params :comment-id])
                             (get-in req [:params :body])
                             (comment-actor req))
        (str "/events/" slug "/files#file-" file-id)))))

(defn- unavailable-response []
  (assoc-in
    (http/text-response 503 "text/plain; charset=utf-8"
                        "File bytes are temporarily unavailable. Please try again.")
    [:headers "Cache-Control"] "no-store"))

(defn- with-file-bytes [read-bytes respond]
  (try
    (if-some [bytes (read-bytes)]
      (respond bytes)
      (unavailable-response))
    (catch Exception _
      (unavailable-response))))

(defn- download-response [file version-id]
  (if-let [version (if version-id
                     (some #(when (= version-id (:id %)) %) (:versions file))
                     (domain/latest-version file))]
    (with-file-bytes
      #(if version-id
         (files/version-bytes! (:id file) version-id)
         (files/latest-bytes! (:id file)))
      (fn [bytes]
        {:status 200
         :headers {"Content-Type" (:content-type version)
                   ;; These bytes are permission-gated speaker deliverables. They
                   ;; must not survive logout or role revocation in a shared cache,
                   ;; and their uploader-supplied media type must never be sniffed
                   ;; into active browser content. Public headshots use their own
                   ;; explicitly cacheable route below.
                   "Cache-Control" "private, no-store"
                   "Pragma" "no-cache"
                   "Expires" "0"
                   "X-Content-Type-Options" "nosniff"
                   "Content-Disposition" (str "attachment; filename=\""
                                              (str/replace (:filename version) #"[\"\r\n]" "_")
                                              "\"")}
         :body bytes}))
    (web-event/not-found-page "that file version")))

(defn handle-speaker-download [req]
  (let [submission-id (http/clean-id (get-in req [:path-params :submission-id]))
        file-id (http/clean-id (get-in req [:path-params :file-id]))
        submission (own-submission req submission-id)
        file (files/by-id file-id)]
    (if (and submission file (= submission-id (:submission-id file)))
      (download-response file (get-in req [:params :version-id]))
      (web-event/not-found-page "that file"))))

(defn handle-organizer-download [req]
  (let [slug (get-in req [:path-params :slug])
        event (events/event-by-slug slug)
        file (files/by-id (http/clean-id (get-in req [:path-params :file-id])))]
    (if (and event file (= (:id event) (:event-id file)))
      (download-response file (get-in req [:params :version-id]))
      (web-event/not-found-page "that file"))))

(defn handle-headshot [req]
  (let [file-id (or (get-in req [:path-params :file-id])
                    (get-in req [:path-params :id]))
        file (files/by-id (http/clean-id file-id))]
    (if-let [version (when (= "Headshot" (:kind file)) (domain/latest-version file))]
      (with-file-bytes
        #(files/latest-bytes! (:id file))
        (fn [bytes]
          {:status 200
           :headers {"Content-Type" (:content-type version)
                     ;; The public URL names the stable file aggregate, not this
                     ;; immutable version. Replacements therefore require a fresh
                     ;; lookup instead of a year-long cached response.
                     "Cache-Control" "public, max-age=0, must-revalidate"}
           :body bytes}))
      (web-event/not-found-page "that headshot"))))

(defn handle-files-zip [req]
  (let [slug (get-in req [:path-params :slug])
        event (events/event-by-slug slug)
        selected-file-ids (->> (param-values (get-in req [:params :file-ids]))
                               (keep http/clean-id)
                               distinct
                               vec)
        grouping (if (= "flat" (get-in req [:params :grouping]))
                   "flat"
                   "by-session")]
    (if event
      (with-file-bytes
        #(if (or (seq selected-file-ids)
                 (= "true" (get-in req [:params :latest])))
           (files/event-latest-zip-bytes! (:id event) selected-file-ids grouping)
           (files/event-zip-bytes! (:id event)))
        (fn [bytes]
          {:status 200
           :headers {"Content-Type" "application/zip"
                     "Content-Disposition" (str "attachment; filename=\"" slug "-files.zip\"")
                     "Cache-Control" "private, no-store"
                     "Pragma" "no-cache"
                     "Expires" "0"
                     "X-Content-Type-Options" "nosniff"}
           :body bytes}))
      (web-event/not-found-page slug))))
