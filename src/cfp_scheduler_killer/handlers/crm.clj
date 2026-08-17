(ns cfp-scheduler-killer.handlers.crm
  (:require
   [cfp-scheduler-killer.auth :as auth]
   [cfp-scheduler-killer.crm :as crm]
   [cfp-scheduler-killer.speaker-csv :as speaker-csv]
   [cfp-scheduler-killer.views.crm :as view-crm]
   [cfp-scheduler-killer.views.shell :as view-shell]
   [cfp-scheduler-killer.web.http :as http]
   [clojure.java.io :as io]
   [clojure.string :as str]))

(def max-csv-bytes (* 2 1024 1024))

(defn- viewer [req]
  (auth/current-person req))

(defn- contact-id [req]
  (http/clean-id (get-in req [:path-params :person-id])))

(defn- filters [req]
  (select-keys (:params req) [:q :organization :role :event :tag]))

(defn- notice [req]
  (case (get-in req [:params :notice])
    "note-added" "Internal note recorded in the event log."
    "tag-added" "Contact tag added."
    "tag-removed" "Contact tag removed."
    "pushed" "Contact added to the event's invited-speaker lane."
    "imported" "Contacts imported into the selected event."
    "segment-saved" "Contact segment saved."
    "segment-removed" "Contact segment removed."
    "outreach-recorded" "Human-reviewed outreach draft recorded. Nothing was sent."
    "already-there" "That contact is already a speaker in the selected event."
    nil))

(defn- render-people [req status extra]
  (let [person (viewer req)]
    (http/html-response
      status
      (view-crm/people-page
        (merge (crm/directory-for (:id person) (filters req))
               {:viewer person :message (notice req)}
               extra)))))

(defn handle-people [req]
  (render-people req 200 {}))

(defn- csv-text [req]
  (let [pasted (get-in req [:params :csv-text])
        upload (get-in req [:params :csv-file])]
    (cond
      (not (str/blank? pasted)) pasted
      (and (map? upload) (:tempfile upload)
           (<= (or (:size upload) 0) max-csv-bytes))
      (slurp (io/file (:tempfile upload)))
      :else nil)))

(defn handle-import-preview [req]
  (let [text (csv-text req)
        event-id (get-in req [:params :event-id])
        preview (speaker-csv/parse text)]
    (render-people req 200 {:csv-text text :import-event-id event-id
                            :import-preview preview})))

(defn handle-import [req]
  (let [person (viewer req)
        text (csv-text req)
        event-id (get-in req [:params :event-id])
        parsed (speaker-csv/parse text)
        outcome (crm/import-to-event! (:id person) event-id parsed (:email person))]
    (if-let [rejection (:rejected outcome)]
      (render-people req 422 {:error (:message rejection)
                              :csv-text text :import-event-id event-id
                              :import-preview parsed})
      (http/see-other "/people?notice=imported"))))

(defn handle-segment-save [req]
  (let [person (viewer req)
        outcome (crm/save-segment! (:id person)
                                   (get-in req [:params :event-id])
                                   (get-in req [:params :name])
                                   (filters req)
                                   (:email person))]
    (if-let [rejection (:rejected outcome)]
      (render-people req 422 {:error (:message rejection)})
      (http/see-other "/people?notice=segment-saved"))))

(defn handle-segment-remove [req]
  (let [person (viewer req)
        segment-id (http/clean-id (get-in req [:path-params :segment-id]))
        outcome (crm/remove-segment! (:id person) segment-id (:email person))]
    (if-let [rejection (:rejected outcome)]
      (render-people req 422 {:error (:message rejection)})
      (http/see-other "/people?notice=segment-removed"))))

(defn- many [value]
  (cond
    (nil? value) []
    (sequential? value) (vec value)
    :else [value]))

(defn- outreach-command [req]
  {:event-id (get-in req [:params :event-id])
   :recipient-ids (many (get-in req [:params :person-id]))
   :subject (get-in req [:params :subject])
   :body (get-in req [:params :body])})

(defn- render-outreach [req status extra]
  (let [person (viewer req)
        directory (crm/directory-for (:id person) {})
        command (outreach-command req)]
    (http/html-response
      status
      (view-crm/outreach-page
        (merge {:viewer person
                :contacts (:all-contacts directory)
                :events (:events directory)
                :selected-person-ids (:recipient-ids command)
                :event-id (or (:event-id command) (some-> directory :events first :id))
                :subject (:subject command)
                :body (:body command)
                :message (notice req)}
               extra)))))

(defn handle-outreach [req]
  (render-outreach req 200 {}))

(defn handle-outreach-preview [req]
  (let [person (viewer req)
        command (outreach-command req)
        outcome (crm/outreach-preview (:id person) command)]
    (if-let [rejection (:rejected outcome)]
      (render-outreach req 422 {:error (:message rejection)})
      (render-outreach req 200 {:preview (:preview outcome)}))))

(defn handle-outreach-record [req]
  (let [person (viewer req)
        outcome (crm/record-outreach! (:id person)
                                      (assoc (outreach-command req) :actor (:email person)))]
    (if-let [rejection (:rejected outcome)]
      (render-outreach req 422 {:error (:message rejection)})
      (http/see-other "/people/outreach?notice=outreach-recorded"))))

(defn- not-found []
  (http/html-response
    404
    (view-shell/page-shell
      "Contact not found"
      [:div.ui.warning.message
       [:div.header "No such contact"]
       [:p "This person is not connected to an event you organize."]]
      [:a.ui.basic.button {:href "/people"} "Back to People"])))

(defn- render-person
  ([req] (render-person req 200 {}))
  ([req status extra]
   (let [person (viewer req)
         person-id (contact-id req)]
     (if-let [detail (and person-id (crm/detail-for (:id person) person-id))]
       (http/html-response
         status
         (view-crm/person-page
           (merge detail {:viewer person :message (notice req)} extra)))
       (not-found)))))

(defn handle-person [req]
  (render-person req))

(defn- outcome-response [req outcome success-notice]
  (if-let [rejection (:rejected outcome)]
    (render-person req 422 {:error (:message rejection)})
    (http/see-other
      (str "/people/" (contact-id req) "?notice="
           (if (:unchanged? outcome) "already-there" success-notice)))))

(defn handle-note-add [req]
  (let [person (viewer req)
        outcome (crm/add-note! (:id person) (contact-id req)
                               {:event-id (get-in req [:params :event-id])
                                :body (get-in req [:params :body])
                                :actor (:email person)})]
    (outcome-response req outcome "note-added")))

(defn handle-tag-add [req]
  (let [person (viewer req)
        outcome (crm/add-tag! (:id person) (contact-id req)
                              {:event-id (get-in req [:params :event-id])
                               :tag (get-in req [:params :tag])
                               :actor (:email person)})]
    (outcome-response req outcome "tag-added")))

(defn handle-tag-remove [req]
  (let [person (viewer req)
        outcome (crm/remove-tag! (:id person) (contact-id req)
                                 {:event-id (get-in req [:params :event-id])
                                  :tag (get-in req [:params :tag])
                                  :actor (:email person)})]
    (outcome-response req outcome "tag-removed")))

(defn handle-push-to-event [req]
  (let [person (viewer req)
        outcome (crm/push-to-event! (:id person)
                                    (contact-id req)
                                    (get-in req [:params :event-id])
                                    (:email person))]
    (outcome-response req outcome "pushed")))
