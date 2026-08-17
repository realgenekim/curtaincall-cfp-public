(ns cfp-scheduler-killer.handlers.announce
  "Organizer handlers for the speaker marquee and invited-speaker path."
  (:require
   [cfp-scheduler-killer.announce :as announce]
   [cfp-scheduler-killer.auth :as auth]
   [cfp-scheduler-killer.crm :as crm]
   [cfp-scheduler-killer.events :as events]
   [cfp-scheduler-killer.public-catalog :as public-catalog]
   [cfp-scheduler-killer.views.announce :as view-announce]
   [cfp-scheduler-killer.web.event :as web-event]
   [cfp-scheduler-killer.web.http :as http]
   [clojure.string :as str]))

(defn- actor [req]
  (or (:email (auth/current-person req)) "organizer"))

(defn- event-at [req]
  (events/event-by-slug (get-in req [:path-params :slug])))

(defn handle-announce-page [req]
  (if-let [event (event-at req)]
    (http/html-response
      (view-announce/announce-page
        event
        {:person (auth/current-person req)
         :roster (public-catalog/program-speakers event)
         :ready (announce/ready-to-announce (:id event))
         :base-url (http/request-host req)
         :stats (public-catalog/announce-stats event)}))
    (web-event/not-found-page (get-in req [:path-params :slug]))))

(defn handle-create-speaker-page [req]
  (if-let [event (event-at req)]
    (http/html-response
      (view-announce/create-speaker-page
        event {:person (auth/current-person req)
               :values {:announce? true}
               :legacy-capture? (= "capture" (get-in req [:query-params "legacy"]))}))
    (web-event/not-found-page (get-in req [:path-params :slug]))))

(defn- confirmed-duplicate? [req]
  (not (str/blank? (str (get-in req [:params :confirm-duplicate])))))

(defn- duplicate-candidates [req values]
  (when-not (confirmed-duplicate? req)
    (seq (crm/duplicate-name-candidates
           (:id (auth/current-person req)) (:name values) (:email values)))))

(defn handle-create-speaker [req]
  (if-let [event (event-at req)]
    (let [values (assoc (announce/speaker-form-values (:params req)) :announce? true)
          errors (announce/speaker-form-errors values true)
          matches (when-not errors (duplicate-candidates req values))]
      (cond
        errors
        (http/html-response
          422
          (view-announce/create-speaker-page
            event {:person (auth/current-person req)
                   :values values
                   :errors errors}))

        matches
        (http/html-response
          (view-announce/create-speaker-page
            event {:person (auth/current-person req)
                   :values values
                   :duplicate-warning {:matches (vec matches)}}))

        :else
        (do
          (announce/create-announced-speaker! (:id event) values (actor req))
          (http/see-other (str "/events/" (:slug event) "/announce")))))
    (web-event/not-found-page (get-in req [:path-params :slug]))))

(defn handle-update-speaker [req]
  (if-let [event (event-at req)]
    (let [person-id (get-in req [:path-params :person-id])
          values (announce/speaker-form-values (:params req))
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
          (str "/events/" (:slug event) "/announce#edit-" person-id))))
    (web-event/not-found-page (get-in req [:path-params :slug]))))

(defn handle-adopt-speaker [req]
  (if-let [event (event-at req)]
    (do
      (announce/adopt-announced-speaker!
        (:id event) (str (or (get-in req [:params :name]) "")) (actor req))
      (http/see-other (str "/events/" (:slug event) "/announce")))
    (web-event/not-found-page (get-in req [:path-params :slug]))))

(defn handle-adopt-all [req]
  (if-let [event (event-at req)]
    (do
      (announce/adopt-all! (:id event) (actor req))
      (http/see-other (str "/events/" (:slug event) "/announce")))
    (web-event/not-found-page (get-in req [:path-params :slug]))))
