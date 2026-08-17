(ns cfp-scheduler-killer.handlers.communications
  (:require
   [cfp-scheduler-killer.auth :as auth]
   [cfp-scheduler-killer.events :as events]
   [cfp-scheduler-killer.inform :as inform]
   [cfp-scheduler-killer.mail :as mail]
   [cfp-scheduler-killer.people :as people]
   [cfp-scheduler-killer.review-updates :as review-updates]
   [cfp-scheduler-killer.reviews :as reviews]
   [cfp-scheduler-killer.speakers :as speakers]
   [cfp-scheduler-killer.store :as store]
   [cfp-scheduler-killer.views.communications :as view-communications]
   [cfp-scheduler-killer.web.datastar :as web-datastar]
   [cfp-scheduler-killer.web.event :as web-event]
   [cfp-scheduler-killer.web.http :as http]
   [clojure.string :as str]
   [hiccup2.core :as h]
   [taoensso.timbre :as log]))

(defn- request-param [req k]
  (or (get-in req [:params k])
      (get-in req [:params (name k)])))

(defn- recipient-options [event]
  (let [submission-by-person
        (into {}
              (mapcat (fn [submission]
                        (for [speaker (:speakers submission)
                              :when (:person-id speaker)]
                          [(:person-id speaker) submission])))
              (store/submissions-for-event (:id event)))]
    (->> (speakers/roster-for-event (:id event))
         (keep (fn [speaker]
                 (when-not (str/blank? (:email speaker))
                   (let [submission (get submission-by-person (:person-id speaker))]
                     {:submission-id (:id submission)
                      :person-id (:person-id speaker)
                      :email (:email speaker)
                      :label (str (:name speaker)
                                  (when submission
                                    (str " — " (get-in submission [:answers :talk-title]))))}))))
         vec)))

(defn- recipient-summary
  [rows]
  (->> rows
       (keep (fn [row]
               (when-let [email (people/normalize-email (:to row))]
                 (assoc row :recipient-email email))))
       (group-by :recipient-email)
       (map (fn [[email communications]]
              (let [latest (first (sort-by #(or (:when %) (:at %) "")
                                           #(compare %2 %1)
                                           communications))
                    person (or (some-> (:person-id latest) people/by-id)
                               (people/by-email email))]
                {:id (or (:id person) (:person-id latest) email)
                 :name (or (:name person) email)
                 :email email
                 :count (count communications)
                 :latest-at (or (:when latest) (:at latest))})))
       (sort-by #(or (:latest-at %) "") #(compare %2 %1))
       vec))

(defn- recipient-match?
  [requested recipient]
  (contains? #{(:id recipient) (:email recipient)} requested))

(def ^:private legacy-portal-copy
  #"Sign in with this email address \(([^)]+)\) and a one-time link\nwill land in your inbox — that's the whole account setup\.")

(defn- current-portal-copy [base-url email]
  (if (= "decision" (:kind email))
    (update email :body
            (fn [body]
              (some-> body
                      (str/replace "https://curtaincallcfp.com/portal"
                                   (str base-url "/portal"))
                      (str/replace legacy-portal-copy
                                   (str "Use this email address when you sign in: $1\n"
                                        "The portal will show the sign-in options available on this deployment.")))))
    email))

(defn- comms-model [req event]
  (let [snapshot (store/snapshot)]
    (binding [store/*as-of-state* snapshot]
      (let [base-url (http/request-host req)
            event-id (:id event)
            current (mail/outbox event-id)
            history (mail/history event-id)
            active-tab (if (= "send" (request-param req :tab)) :send :history)
            queued-emails (->> current
                               (filter #(= :queued (:state %)))
                               (sort-by #(or (:at %) "") #(compare %2 %1))
                               vec)
            waiting-people (recipient-summary (filter #(= :queued (:state %)) current))
            emailed-people (recipient-summary
                             (concat (filter #(= :sent (:state %)) current)
                                     (filter #(and (:sent? %) (nil? (:email-id %))) history)))
            requested (request-param req :recipient)
            requested-lane (if (= "emailed" (request-param req :lane))
                             :emailed
                             :waiting)
            preferred (if (= :emailed requested-lane) emailed-people waiting-people)
            alternate (if (= :emailed requested-lane) waiting-people emailed-people)
            selected (or (some #(when (recipient-match? requested %) %) preferred)
                         (some #(when (recipient-match? requested %) %) alternate)
                         (first preferred)
                         (first alternate))
            active-lane (if (some #(= (:id selected) (:id %)) preferred)
                          requested-lane
                          (if (= :emailed requested-lane) :waiting :emailed))
            selected-email (:email selected)
            selected-communications
            (->> (concat current (filter #(nil? (:email-id %)) history))
                 (filter #(= selected-email (people/normalize-email (:to %))))
                 (map #(current-portal-copy base-url %))
                 (sort-by #(or (:when %) (:at %) "") #(compare %2 %1))
                 vec)]
        {:person (auth/current-person req)
         :mail-configured? (mail/configured?)
         :mail-status (mail/status-line)
         :active-tab active-tab
         :queued-emails queued-emails
         :waiting-people waiting-people
         :emailed-people emailed-people
         :selected-person selected
         :selected-communications selected-communications
         :active-lane active-lane
         :delivery (some-> (request-param req :delivery) keyword)
         :sent-count (some-> (request-param req :sent-count) str parse-long)}))))

(defn- notification-receipt [req informed]
  (let [status (http/not-blank (request-param req :notification-status))
        count* (some-> (request-param req :notification-count) str parse-long)]
    (when (and status (some? count*))
      {:status status
       :count count*
       :recipients (->> informed
                        (filter #(= status (:notified-status %)))
                        (take count*)
                        (keep #(get-in % [:speakers 0 :email]))
                        vec)})))

(defn- inform-location [event status n]
  (str "/events/" (:slug event) "/inform?notification-status="
       (java.net.URLEncoder/encode (str status) "UTF-8")
       "&notification-count=" n))

(defn- param-values [value]
  (cond
    (nil? value) []
    (sequential? value) value
    :else [value]))

(defn- feedback-options [submission]
  (mapv (fn [{:keys [id person-id body]}]
          {:id (str id)
           :body body
           :person-name (or (:name (store/person-by-id person-id)) "Reviewer")})
        (:comments (reviews/enrich submission))))

(defn- curated-options [req]
  {:chair-note (request-param req :chair-note)
   :feedback-ids (param-values (request-param req :feedback-ids))})

(defn- preview-decision-message [req event submission & [error]]
  (try
    (let [curated (inform/curated-letters-for event submission
                                              (http/request-host req)
                                              (curated-options req))]
      (http/html-response
        (if error 422 200)
        (view-communications/decision-message-preview-page
          event {:person (auth/current-person req)
                 :submission submission
                 :mail-configured? (mail/configured?)
                 :mail-status (mail/status-line)
                 :error error
                 :curated curated})))
    (catch clojure.lang.ExceptionInfo e
      (if (= :invalid-feedback-selection (:type (ex-data e)))
        (http/html-response
          422
          (view-communications/decision-message-preview-page
            event {:person (auth/current-person req)
                   :submission submission
                   :mail-configured? (mail/configured?)
                   :mail-status (mail/status-line)
                   :error (:user-message (ex-data e))
                   :curated (inform/curated-letters-for event submission
                                                        (http/request-host req) {})}))
        (throw e)))))

(defn- selected-recipients [event req]
  (let [all (recipient-options event)
        ids (->> (request-param req :recipient-ids)
                 param-values
                 (keep http/clean-id)
                 set)
        legacy-submission-id (some-> (request-param req :submission-id) http/clean-id)
        legacy-to (some-> (request-param req :to) str/trim not-empty)]
    (if (seq ids)
      (filterv #(contains? ids (:person-id %)) all)
      (if legacy-to
        (mapv #(assoc % :email legacy-to)
              (filter #(= legacy-submission-id (:submission-id %)) all))
        []))))

(defn- render-comms
  ([req event]
   (render-comms req event 200 {}))
  ([req event status overrides]
   (http/html-response
     status
     (view-communications/comms-page event (merge (comms-model req event) overrides)))))

(defn- push-comms-person-detail [req event]
  (if (auth/current-person req)
    (web-datastar/sse-fragment-response
      req
      "#comms-person-detail"
      (str (h/html
             (view-communications/comms-person-detail
               event
               (comms-model req event)))))
    {:status 204 :headers {} :body ""}))

(defn handle-comms [req]
  (let [slug (get-in req [:path-params :slug])]
    (if-let [event (events/event-by-slug slug)]
      (if (= "person-detail" (request-param req :fragment))
        (push-comms-person-detail req event)
        (render-comms req event))
      (web-event/not-found-page slug))))

(defn- outbox-command [req command]
  (let [slug (get-in req [:path-params :slug])
        event (events/event-by-slug slug)
        email-id (some-> (get-in req [:path-params :email-id]) http/clean-id)
        actor (:email (auth/current-person req))]
    (if-not event
      (web-event/not-found-page slug)
      (let [result (command (:id event) email-id actor)
            delivery (when (#{:sent :failed} (:mode result))
                       (name (:mode result)))]
        (http/see-other (str "/events/" slug "/comms?tab=send"
                             (when delivery (str "&delivery=" delivery))))))))

(defn handle-email-approve [req]
  (outbox-command req mail/approve!))

(defn handle-email-discard [req]
  (outbox-command req mail/discard!))

(defn handle-email-approve-all [req]
  (let [slug (get-in req [:path-params :slug])
        event (events/event-by-slug slug)]
    (if-not event
      (web-event/not-found-page slug)
      (if (= "send-message" (request-param req :command))
        (let [recipients (selected-recipients event req)
              subject (some-> (request-param req :subject) str/trim)
              body (some-> (request-param req :body) str/trim)
              template-id (or (request-param req :template) "reminder")
              submission-id (some-> (request-param req :submission-id) http/clean-id)
              errors (cond-> {}
                       (empty? recipients) (assoc :recipients "Choose at least one speaker.")
                       (str/blank? subject) (assoc :subject "Write a subject.")
                       (str/blank? body) (assoc :body "Write a message."))]
          (if (seq errors)
            (render-comms req event 422
                          {:draft {:template template-id
                                   :submission-id submission-id
                                   :person-id (:person-id (first recipients))
                                   :subject subject
                                   :body body}
                           :errors errors})
            (let [actor (:email (auth/current-person req))
                  deliveries
                  (mapv
                    (fn [{:keys [email person-id submission-id]}]
                      (let [{:keys [email-id]}
                            (mail/send! {:from (or (:support-email event) actor)
                                         :to email
                                         :subject subject
                                         :body body
                                         :reply-to (or (:support-email event) actor)}
                                        {:event-id (:id event)
                                         :kind "speaker-message"
                                         :submission-id submission-id
                                         :person-id person-id
                                         :task-key template-id
                                         :actor actor})]
                        (mail/approve! (:id event) email-id actor)))
                    recipients)
                  sent? (every? #(= :sent (:mode %)) deliveries)]
              (http/see-other
                (str "/events/" slug "/comms?delivery="
                     (if sent? "sent" "failed")
                     (when (> (count recipients) 1)
                       (str "&sent-count=" (count recipients))))))))
        (do (mail/approve-all! (:id event) (:email (auth/current-person req)))
            (http/see-other (str "/events/" slug "/comms")))))))

(defn handle-capture-page [req]
  (let [slug (get-in req [:path-params :slug])]
    (if (events/event-by-slug slug)
      (http/see-other (str "/events/" slug "/speakers/new?legacy=capture"))
      (web-event/not-found-page slug))))

(defn handle-capture [req]
  (let [slug (get-in req [:path-params :slug])]
    (if (events/event-by-slug slug)
      (http/see-other (str "/events/" slug "/speakers/new?legacy=capture"))
      (web-event/not-found-page slug))))

(defn handle-inform-page [req]
  (let [slug (get-in req [:path-params :slug])]
    (if-let [event (events/event-by-slug slug)]
      (let [informed (inform/informed (:id event))]
        (http/html-response
          (view-communications/inform-page
            event
            {:groups (for [{:keys [status rows]} (inform/pending-by-status (:id event))]
                       {:status status
                        :rows (mapv (fn [r] {:submission r
                                             :letter (inform/letter-for event r)
                                             :feedback-options (feedback-options r)})
                                    rows)})
             :informed informed
             :notification-receipt (notification-receipt req informed)
             :person (auth/current-person req)
             :dev? (auth/dev?)
             :mail-configured? (mail/configured?)
             :mail-status (mail/status-line)})))
      (web-event/not-found-page slug))))

(defn handle-inform-one [req]
  (let [sid (http/clean-id (get-in req [:path-params :submission-id]))
        row (when sid (store/submission-by-id sid))
        event (when row (events/event-by-id (:event-id row)))]
    (if-not (and row event)
      (web-event/not-found-page "that submission")
      (case (request-param req :command)
        "preview"
        (preview-decision-message req event row)

        "send-previewed"
        (if-not (= "yes" (request-param req :previewed))
          (preview-decision-message
            req event row
            "Review the complete message before queuing this notification.")
          (let [result
                (try {:informed?
                      (boolean (inform/inform! event row
                                               (:email (auth/current-person req))
                                               (http/request-host req)
                                               (curated-options req)))}
                     (catch clojure.lang.ExceptionInfo e
                       (case (:type (ex-data e))
                         :not-informable
                         (do (log/warn :inform-rejected :status (:status row))
                             {:informed? false})

                         :invalid-feedback-selection
                         {:response (preview-decision-message
                                      req event row (:user-message (ex-data e)))}

                         (throw e))))]
            (if-let [response (:response result)]
              response
              (do
                (review-updates/push-board-updates! event sid)
                (http/see-other
                  (inform-location event (:status row)
                                   (if (:informed? result) 1 0)))))))

        ;; Existing API callers retain the original no-customization command.
        ;; The organizer UI never emits it: its curated path always previews.
        (let [informed?
              (try (boolean (inform/inform! event row (:email (auth/current-person req))
                                            (http/request-host req)))
                   (catch clojure.lang.ExceptionInfo e
                     (if (= :not-informable (:type (ex-data e)))
                       (do (log/warn :inform-rejected :status (:status row)) false)
                       (throw e))))]
          (review-updates/push-board-updates! event sid)
          (http/see-other (inform-location event (:status row) (if informed? 1 0))))))))

(defn handle-inform-all [req]
  (let [slug (get-in req [:path-params :slug])]
    (if-let [event (events/event-by-slug slug)]
      (let [status (http/not-blank (get-in req [:params :status]))
            n (inform/inform-all! event (:email (auth/current-person req)) status
                                  (http/request-host req))]
        (log/info :informed-batch :slug slug :status status :count n)
        (http/see-other (inform-location event (or status "All decisions") n)))
      (web-event/not-found-page slug))))
