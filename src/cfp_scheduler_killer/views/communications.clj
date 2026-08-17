(ns cfp-scheduler-killer.views.communications
  "Decision-letter previews and communication history."
  (:require
   [cfp-scheduler-killer.ds :as ds]
   [cfp-scheduler-killer.views.format :as format]
   [cfp-scheduler-killer.views.organizer-layout :as organizer-layout]
   [cfp-scheduler-killer.views.pipeline :as pipeline]
   [clojure.data.json :as json]
   [clojure.string :as str]
   [datastar-kit.ds :as datastar-ds]))

(defn- recipient-fragment-path
  [event lane recipient]
  (str "/events/" (:slug event) "/comms?fragment=person-detail&lane=" (name lane)
       "&recipient="
       (java.net.URLEncoder/encode (str (:id recipient)) "UTF-8")))

(defn- path-choice-expression [paths]
  (if (seq paths)
    (str "("
         (str/join ":"
                   (map-indexed
                     (fn [idx path]
                       (str "$commsIdx===" idx "?" (json/write-str path)))
                     paths))
         ":null)")
    "null"))

(defn- navigation-action
  [delta waiting-paths emailed-paths]
  (str "$commsIdx=Math.max(0,Math.min(($commsIdx)+" delta ","
       "($commsLane==='waiting'?$commsWaitingMax:$commsEmailedMax)));"
       "@get($commsLane==='waiting'?"
       (path-choice-expression waiting-paths) ":"
       (path-choice-expression emailed-paths) ")"))

(defn- people-select-action
  [lane idx path]
  (str "$commsLane='" (name lane) "';"
       "$commsIdx=" idx ";"
       "@get(" (json/write-str path) ")"))

(defn- communication-status
  [communication]
  (or (some-> (:state communication) name)
      (case (:type communication)
        "comms.sent" "sent"
        "email.sent" "sent"
        "comms.failed" "failed"
        "email.failed" "failed"
        "email.discarded" "discarded"
        "email.approved" "approved"
        "email.queued" "queued"
        "comms.rendered" "would send"
        "queued")))

(defn- communication-status-class
  [status]
  (case status
    "sent" "green"
    "failed" "red"
    "discarded" "grey"
    "approved" "blue"
    "queued" "yellow"
    nil))

(defn- people-list
  [event title lane recipients]
  [:section.ui.segment {:data-comms-lane (name lane)}
   [:h4.ui.header title " (" (count recipients) ")"]
   (if (seq recipients)
     [:div.ui.relaxed.divided.list
      (for [[idx recipient] (map-indexed vector recipients)
            :let [path (recipient-fragment-path event lane recipient)]]
        [:button.item
         {:type "button"
          :data-star-on:click (people-select-action lane idx path)
          :data-star-show
          (str "$commsFilter===''||"
               (json/write-str
                 (str/lower-case (str (:name recipient) " " (:email recipient))))
               ".includes($commsFilter.toLowerCase())")
          :data-comms-person ""
          :data-comms-idx idx
          :data-comms-path path
          :data-star-class:active
          (str "$commsLane==='" (name lane) "'&&$commsIdx===" idx)
          :data-star-attr:aria-current
          (str "$commsLane==='" (name lane) "'&&$commsIdx===" idx
               "?'true':null")
          :style "background:transparent;border:0;cursor:pointer;text-align:left;width:100%;"}
         [:div.content
          [:div.header (:name recipient)]
          [:div.description (:email recipient)]
          [:div.description
           (:count recipient) (if (= 1 (:count recipient)) " message" " messages")]]])]
     [:div.empty-state "Nobody here yet."])])

(defn comms-person-detail
  [event {:keys [selected-person selected-communications]}]
  [:section#comms-person-detail.ui.segment
   (if selected-person
     [:div
      [:div {:style "display:flex;justify-content:space-between;gap:1rem;align-items:start;"}
       [:div
        [:h3.ui.header {:style "margin-bottom:.2rem;"} (:name selected-person)]
        [:div.sub-meta (:email selected-person)]]
       [:div.sub-meta "J / K to move"]]
      [:div.ui.divider]
      (if (seq selected-communications)
        (for [communication selected-communications
              :let [status (communication-status communication)]]
          [:div.letter {:data-email-id (:email-id communication)}
           [:div.letter-subject (:subject communication)]
           [:div.letter-to
            (or (format/fmt-when (or (:when communication) (:at communication))
                                 (:tz event))
                (:when communication)
                (:at communication))
            " · "
            [:span.ui.mini.label
             {:class (communication-status-class status)}
             status]]
           (when (:kind communication)
             [:div.sub-meta (:kind communication)])
           (when (:error communication)
             [:div.ui.tiny.negative.message (:error communication)])
           [:div.letter-body (:body communication)]])
        [:div.empty-state "No communications are recorded for this person."])]
     [:div.empty-state "No queued or sent communications yet."])])

(defn- comms-tabs [event active-tab]
  [:div.ui.top.attached.tabular.menu
   [:a {:class (str "item" (when (= :history active-tab) " active"))
        :href (str "/events/" (:slug event) "/comms")}
    "History"]
   [:a {:class (str "item" (when (= :send active-tab) " active"))
        :href (str "/events/" (:slug event) "/comms?tab=send")}
    "Send Emails"]])

(defn- queued-emails-view [event queued-emails]
  [:section.ui.bottom.attached.segment
   [:div {:style "display:flex;align-items:start;justify-content:space-between;gap:1rem;"}
   [:h3.ui.header
    (str "Queued emails (" (count queued-emails) ")")
    [:div.sub.header "Review each message, then send it individually."]]
    [:button.ui.button {:type "button"
                        :disabled true
                        :data-send-all-disabled "true"
                        :title "Send queued emails individually for now"}
     "Send All"]]
   (if (seq queued-emails)
     [:table.ui.celled.table
      [:thead
       [:tr
        [:th "ID"]
        [:th "Recipient"]
        [:th "Message"]
        [:th "Queued"]
        [:th ""]]]
      [:tbody
       (for [{:keys [email-id to subject kind at]} queued-emails]
         [:tr {:data-email-id email-id}
          [:td [:code email-id]]
          [:td to]
          [:td
           [:strong subject]
           (when kind [:div.sub-meta kind])]
          [:td (or (format/fmt-when at (:tz event)) at)]
          [:td
           [:form {:method "post"
                   :action (str "/api/events/" (:slug event) "/comms/"
                                email-id "/approve")}
            [:button.ui.small.primary.button {:type "submit"} "Send"]]]])]]
     [:div.empty-state "No emails are waiting to be sent."])])

(defn comms-page
  "Two compact people queues and the complete communication record for one
   selected person. Selecting and navigating never dispatches mail."
  [event {:keys [person mail-status waiting-people emailed-people selected-person
                 selected-communications active-lane active-tab queued-emails
                 delivery sent-count]}]
  (let [waiting-paths (mapv #(recipient-fragment-path event :waiting %) waiting-people)
        emailed-paths (mapv #(recipient-fragment-path event :emailed %) emailed-people)
        active-people (if (= :emailed active-lane) emailed-people waiting-people)
        selected-idx (or (first
                           (keep-indexed
                             (fn [idx recipient]
                               (when (= (:email selected-person) (:email recipient)) idx))
                             active-people))
                         0)
        signals {:commsLane (name active-lane)
                 :commsIdx selected-idx
                 :commsFilter ""
                 :commsWaitingMax (max 0 (dec (count waiting-people)))
                 :commsEmailedMax (max 0 (dec (count emailed-people)))}
        navigable? (seq active-people)]
    (organizer-layout/organizer-shell
      (str "Comms — " (:name event))
      {:event event :active :comms :person person :crumb "Comms" :sse? true
       :body-attrs (datastar-ds/sse-mount (:id event))}
      (organizer-layout/header "Comms" mail-status)

      (when (= :sent delivery)
        [:div.toast {:role "status"} "Email sent"])
      (when (and (= :send active-tab) (= :failed delivery))
        [:div.ui.negative.message {:role "alert"} "Email failed"])

      (comms-tabs event active-tab)

      (if (= :send active-tab)
        (queued-emails-view event queued-emails)
      [:div
       (cond-> {:data-star-signals__ifmissing (json/write-str signals)}
         navigable?
         (assoc :data-star-on:keydown__window
                (ds/keydown-expr
                  []
                  [(ds/on-key "j" {} (navigation-action 1 waiting-paths emailed-paths))
                   (ds/on-key "k" {} (navigation-action -1 waiting-paths emailed-paths))])))

       [:div.ui.info.message
        [:div.header "Select one person to read their communications"]
        [:p "The lists stay compact; full subjects and bodies load only for the selected person. "
         "J and K move through the active list. Nothing on this page sends email."]]

       [:form.public-filter-bar {:data-star-on:submit "evt.preventDefault()"}
        [:input (merge {:id "comms-filter"
                        :type "search"
                        :name "q"
                        :value ""
                        :aria-label "Filter communication recipients"
                        :autofocus true
                        :placeholder "Filter people by name or email"}
                       (datastar-ds/bind :comms-filter))]
        [:button.ui.button {:type "submit"} "Search"]]

       (case delivery
         :sent
         [:div.ui.positive.message
          [:div.header
           (if (and sent-count (> sent-count 1))
             (str "Messages sent to " sent-count " speakers and recorded in history")
             "Message sent and recorded in history")]]

         :failed
         [:div.ui.negative.message
          [:div.header "Delivery failed, and the failure is recorded"]]

         nil)

       [:div
        {:style "display:grid;grid-template-columns:minmax(16rem,22rem) minmax(0,1fr);gap:1rem;align-items:start;"}
        [:div
         (people-list event "Waiting for approval" :waiting waiting-people)
         (people-list event "People emailed" :emailed emailed-people)]
        (comms-person-detail
          event
          {:selected-person selected-person
             :selected-communications selected-communications})]]))))

(defn- letter-block [_row letter]
  [:div.letter
   [:div.letter-subject (:subject letter)]
   [:div.letter-to "To: " (:to letter)
    " · reply-to comes from the event's speaker support address"]
   [:div.letter-body (:body letter)]])

(defn- mail-message
  "The delivery truth, always stated — claiming 'sent' with no mailer is the
   most damaging lie this tool could tell an organizer."
  [event mail-configured? mail-status]
  [:div.ui.info.message
   (if mail-configured?
     [:p [:strong "Letters will be emailed."] " " mail-status
      " · Reply-to is this event's speaker support address, so a reply reaches "
      "a person. Every send is recorded on the "
      [:a {:href (str "/events/" (:slug event) "/comms")} "Comms"] " page."]
     [:p [:strong "SMTP is not configured, so nothing is emailed."]
      " Informing still records the decision — the speaker sees their real "
      "status immediately — and the exact letter is kept on the "
      [:a {:href (str "/events/" (:slug event) "/comms")} "Comms"]
      " page so you can send it by hand."])])

(defn decision-message-preview-page
  "The server-rendered privacy and delivery gate for one decision message."
  [event {:keys [person submission mail-configured? mail-status error curated]}]
  (let [{:keys [letters feedback-ids chair-note]} curated]
    (organizer-layout/organizer-shell
      (str "Preview decision message — " (:name event))
      {:event event :active :inform :person person :crumb "Inform Speakers"}
      (organizer-layout/header
        "Preview decision message"
        (str (get-in submission [:answers :talk-title]) " · " (:status submission)))

      (when error
        [:div.ui.negative.message
         [:div.header "This message was not queued"]
         [:p error]])

      [:div.ui.warning.message
       [:div.header "Nothing has been sent or queued"]
       [:p "Read every recipient's complete copy below. Queueing records the decision, "
        "then the existing Outbox approval remains the final delivery gate."]]

      (mail-message event mail-configured? mail-status)

      [:div.ui.segment.decision-message-preview
       [:h3.ui.header "Exact recipient copies"]
       (for [letter letters]
         (letter-block submission letter))]

      [:div.ui.segment
       [:form.ui.form {:method "post"
                       :action (str "/api/submissions/" (:id submission) "/inform")}
        [:input {:type "hidden" :name "command" :value "send-previewed"}]
        [:input {:type "hidden" :name "previewed" :value "yes"}]
        (when chair-note
          [:input {:type "hidden" :name "chair-note" :value chair-note}])
        (for [feedback-id feedback-ids]
          [:input {:type "hidden" :name "feedback-ids" :value feedback-id}])
        [:button.ui.primary.button {:type "submit"}
         "Queue this decision notification"]
        [:a.ui.button {:href (str "/events/" (:slug event) "/inform")}
         "Back without queuing"]]])))

(defn inform-page
  "Queued decisions, grouped by the letter each will send, each shown IN FULL
   before anyone presses a button. You should never send a letter you haven't
   read."
  [event {:keys [groups informed notification-receipt person mail-configured? mail-status]}]
  (organizer-layout/organizer-shell
    (str "Inform Speakers — " (:name event))
    {:event event :active :inform :person person :crumb "Inform Speakers"
     :share? true}
    (organizer-layout/header "Inform Speakers"
                             (if (seq informed)
                               (str (count informed) " decision"
                                    (when (not= 1 (count informed)) "s")
                                    " already communicated. Read each remaining letter, then send it.")
                               "Nothing here has been sent. Read each letter, then send it."))

    (when notification-receipt
      (let [{:keys [status count recipients]} notification-receipt]
        (if (pos? count)
          [:div.ui.positive.message.decision-notification-receipt
           [:div.header
            status " notification" (when (not= 1 count) "s")
            " queued for " count " speaker" (when (not= 1 count) "s")]
           [:p "Recipient" (when (not= 1 count) "s") ": "
            (if (seq recipients)
              (str/join ", " recipients)
              "recorded in the decision history")
            ". Delivery state and the complete letter are available on the "
            [:a {:href (str "/events/" (:slug event) "/comms")} "Comms page"] "."]]
          [:div.ui.warning.message.decision-notification-receipt
           [:div.header "No new notification was queued"]
           [:p "That decision had already been communicated or was no longer eligible."]])))

    (if (empty? groups)
      ;; An empty queue is a HANDOFF, never a dead end (Gene, 2026-08-11):
      ;; show the whole pipeline with the ball's current owners instead of
      ;; a "nothing waiting" shrug.
     (pipeline/pipeline-cascade event {})

      (list
        (for [{:keys [status rows]} groups]
          [:div.inform-group {}
           [:div.inform-head
            [:h3.ui.header {:style "margin:0;"}
             status " — " (count rows) " speaker" (when (not= 1 (count rows)) "s")]
            [:form {:method "post" :action (str "/api/events/" (:slug event) "/inform-all")}
             [:input {:type "hidden" :name "status" :value status}]
             [:button.ui.small.primary.button {:type "submit"}
              "Inform all " (count rows) " — queue " status " notifications"]]]

           (for [{:keys [submission letter feedback-options]} rows]
             [:div {:id (str "decision-" (:id submission))
                    :style "margin-bottom:1.2em;"}
              [:div {:style "display:flex; align-items:baseline; gap:0.8em;"}
               [:div {:style "flex:1;"}
                [:strong (get-in submission [:answers :talk-title])]
                [:div.sub-meta (:name (first (:speakers submission)))
                 " · " (:org (first (:speakers submission)))]]]
              (letter-block submission letter)
              [:form.ui.form.curated-feedback-form
               {:method "post"
                :action (str "/api/submissions/" (:id submission) "/inform")}
               [:input {:type "hidden" :name "command" :value "preview"}]
               [:div.field
                [:label "Optional note from the program chair"]
                [:textarea {:name "chair-note" :rows 3
                            :placeholder "Could you clarify the concrete outcome and send us a revised abstract?"
                            :data-ghost-fill ""}]]
               [:div.grouped.fields
                [:label "Reviewer feedback to share"]
                [:p.field-hint
                 "Every reviewer comment starts excluded. Select only excerpts you have judged speaker-safe. "
                 "Private committee-only form answers are never available here."]
                (if (seq feedback-options)
                  (for [{:keys [id person-name body]} feedback-options]
                    [:div.field
                     [:div.ui.checkbox
                      [:input {:type "checkbox" :name "feedback-ids" :value id}]
                      [:label [:strong person-name] " — " body]]])
                  [:p.field-hint "No reviewer comments are available to curate."])]
               [:button.ui.mini.primary.button {:type "submit"}
                "Preview decision message"]]])])))

    ;; Receipts are redundant under the cascade (its Tell band lists them) —
    ;; only shown alongside a live queue.
    (when (and (seq groups) (seq informed))
      [:div {:style "margin-top:2.5em;"}
       [:h4.ui.header "Already informed (" (count informed) ")"]
       (for [s informed]
         [:div.sub-row {}
          [:div.sub-title (get-in s [:answers :talk-title])]
          [:div.sub-meta
           (:notified-status s) " · told "
           (or (format/fmt-when (:notified-at s) (:tz event)) "—")
           " · " (:email (first (:speakers s)))]])])))
