(ns cfp-scheduler-killer.handlers.forms
  "Organizer CFP form-builder and live-preview handlers."
  (:require
   [cfp-scheduler-killer.auth :as auth]
   [cfp-scheduler-killer.events :as events]
   [cfp-scheduler-killer.forms :as forms]
   [cfp-scheduler-killer.sse :as sse]
   [cfp-scheduler-killer.submissions :as submissions]
   [cfp-scheduler-killer.views.form-builder :as view-form-builder]
   [cfp-scheduler-killer.views.shell :as view-shell]
   [cfp-scheduler-killer.web.datastar :as datastar]
   [cfp-scheduler-killer.web.event :as event-web]
   [cfp-scheduler-killer.web.http :as http]
   [taoensso.timbre :as log]))

(defn- form-model
  "Everything `view-form-builder/form-builder-page` needs. `extra` carries the state of a
   rejected add/edit so a bad field comes back filled in with its messages."
  ([req event] (form-model req event nil))
  ([req event extra]
   (let [fields (forms/fields-for-event (:id event))
         person (auth/current-person req)
         form (forms/form-for-event (:id event))]
     (merge {:fields fields
             :person person
             :editing (when-let [e (http/not-blank (get-in req [:params :edit]))]
                        (forms/find-field fields e))
             :confirming (forms/confirming-field (:id person) (:id form))
             :reviewed? (forms/reviewed? (:id event))
             :submission-count (submissions/count-for-event (:id event))}
            extra))))

(defn push-form-updates!
  "Repaint the field list and the preview for everyone else on this page.

   The list goes out PER VIEWER because the two-step retire prompt is one
   person's business — broadcasting one render would ask the whole committee to
   confirm somebody else's decision. The preview is impersonal: it is the public
   page, and the public page looks the same to everyone."
  [event]
  (let [fields (forms/fields-for-event (:id event))
        form-id (:id (forms/form-for-event (:id event)))]
    ;; One interleaved region now (#fb-grid) — questions and their preview are
    ;; the same grid, so both update together. Per-viewer for the retire-prompt.
    (sse/push-personal-fragment!
      (:id event) "#fb-grid"
      (fn [person-id]
        (view-form-builder/form-grid-region event {:fields fields
                                                   :confirming (forms/confirming-field person-id form-id)})))))

(defn handle-form-builder [req]
  (let [slug (get-in req [:path-params :slug])]
    (if-let [event (events/event-by-slug slug)]
      (http/html-response
        (view-form-builder/form-builder-page
          event (form-model req event
                            {:saved-toast
                             (cond (get-in req [:params :saved]) "Saved."
                                   (get-in req [:params :added]) "Question added.")})))
      (event-web/not-found-page slug))))

(defn- with-form
  "Resolve the event, run `f`, push, and send the organizer back to the editor.
   `f` may return a response to short-circuit (a rejected form re-render)."
  [req f]
  (let [slug (get-in req [:path-params :slug])]
    (if-let [event (events/event-by-slug slug)]
      (let [resp (try (f event)
                      (catch clojure.lang.ExceptionInfo e
                        (if (= :locked-field (:type (ex-data e)))
                          {:status 409
                           :headers {"Content-Type" "text/html; charset=utf-8"}
                           :body (view-shell/page-shell
                                   "Locked field"
                                   [:div.ui.warning.message
                                    [:div.header "That field can't be removed"]
                                    [:p "Talk title, abstract and the speaker block are the "
                                     "spine of a call for speakers — a CFP without them "
                                     "can't describe a talk."]]
                                   [:a.ui.basic.button {:href (str "/events/" slug "/form")}
                                    "Back to the form"])}
                          (throw e))))]
        (push-form-updates! event)
        (or resp (http/see-other (str "/events/" slug "/form"))))
      (event-web/not-found-page slug))))

(defn- field-values
  "What the organizer typed, for a re-render. Parsed attrs carry canonical
   values; condition inputs stay raw so an incomplete rejected pair is not lost."
  [req attrs]
  (assoc attrs
         :max-length (get-in req [:params :max-length])
         :show-when-field-id (get-in req [:params :show-when-field-id])
         :show-when-value (get-in req [:params :show-when-value])))

(defn handle-form-add [req]
  (let [slug (get-in req [:path-params :slug])]
    (if-let [event (events/event-by-slug slug)]
      (let [attrs (forms/parse-field-params (:params req))
            source-ids (->> (forms/condition-source-fields
                              (forms/fields-for-event (:id event)) nil)
                            (map forms/field-id)
                            set)
            errors (forms/field-validation-errors
                     attrs {:new? true :available-source-ids source-ids})]
        (if errors
          (do (log/info :form-field-rejected :slug slug :fields (vec (keys errors)))
              (http/html-response
                422
                (view-form-builder/form-builder-page
                  event (form-model req event
                                    {:add-form {:values (field-values req attrs)
                                                :errors errors}}))))
          (do (forms/add-field! event attrs (:email (auth/current-person req)))
              (push-form-updates! event)
              (http/see-other (str "/events/" slug "/form?added=1")))))
      (event-web/not-found-page slug))))

(defn handle-form-update [req]
  (let [slug (get-in req [:path-params :slug])]
    (if-let [event (events/event-by-slug slug)]
      (let [field-id (http/not-blank (get-in req [:params :field-id]))
            existing (when field-id
                       (forms/find-field (forms/fields-for-event (:id event)) field-id))]
        (if-not existing
          (http/see-other (str "/events/" slug "/form?saved=1"))
          ;; The field's OWN type drives validation — an edit never posts one,
          ;; because a type is fixed at birth.
          (let [attrs (assoc (forms/parse-field-params (:params req))
                             :type (forms/field-type existing))
                source-ids (->> (forms/condition-source-fields
                                  (forms/fields-for-event (:id event)) field-id)
                                (map forms/field-id)
                                set)
                errors (forms/field-validation-errors
                         attrs {:available-source-ids source-ids})]
            (if errors
              (http/html-response
                422
                (view-form-builder/form-builder-page
                  event (form-model req event
                                    {:editing existing
                                     :edit-form {:values (field-values req attrs)
                                                 :errors errors}})))
              (do (forms/update-field! event field-id attrs
                                       (:email (auth/current-person req)))
                  (push-form-updates! event)
                  (http/see-other (str "/events/" slug "/form?saved=1")))))))
      (event-web/not-found-page slug))))

(defn handle-form-move [req]
  (with-form req
    (fn [event]
      (when-let [field-id (http/not-blank (get-in req [:params :field-id]))]
        (forms/move-field! event field-id
                           (if (= "up" (get-in req [:params :direction])) "up" "down")
                           (:email (auth/current-person req))))
      nil)))

(defn handle-form-retire-ask [req]
  (with-form req
    (fn [event]
      (forms/ask-confirm-retire! (:id (auth/current-person req))
                                 (:id (forms/form-for-event (:id event)))
                                 (http/not-blank (get-in req [:params :field-id])))
      nil)))

(defn handle-form-retire-cancel [req]
  (with-form req
    (fn [event]
      (forms/clear-confirm-retire! (:id (auth/current-person req))
                                   (:id (forms/form-for-event (:id event))))
      nil)))

(defn handle-form-retire [req]
  (with-form req
    (fn [event]
      (let [form-id (:id (forms/form-for-event (:id event)))]
        (try
          (when-let [field-id (http/not-blank (get-in req [:params :field-id]))]
            (forms/retire-field! event field-id (:email (auth/current-person req))))
          (finally
            ;; The question has been answered either way — never leave a stale
            ;; prompt on a screen.
            (forms/clear-confirm-retire! (:id (auth/current-person req)) form-id))))
      nil)))

(defn handle-form-restore [req]
  (with-form req
    (fn [event]
      (when-let [field-id (http/not-blank (get-in req [:params :field-id]))]
        (forms/restore-field! event field-id (:email (auth/current-person req))))
      nil)))

(defn handle-form-preview
  "POST /api/events/:slug/form/preview — the living 'What speakers see' pane.

   Stateless: reads the bound edit/add signals, renders the public form with
   the in-progress typing applied on top of the saved fields, and pushes
   #form-preview down THIS viewer's SSE stream only — half-typed edits are one
   person's business (saves broadcast to everyone via push-form-updates!). The
   POST answers 204; the stream is the answer."
  [req]
  (let [slug (get-in req [:path-params :slug])]
    (if-let [event (events/event-by-slug slug)]
      (let [person (auth/current-person req)
            sigs (datastar/signals req)
            field-id (http/not-blank (get-in req [:params :field-id]))
            add? (some? (get-in req [:params :mode]))
            fields (forms/fields-for-event (:id event))
            typed (fn [pfx]
                    (let [sig (fn [suffix] (get sigs (keyword (str pfx suffix))))
                          attrs (forms/parse-field-params
                                  {:label (sig "label")
                                   :type (sig "type")
                                   :help (sig "help")
                                   :max-length (str (or (sig "max") ""))
                                   :required (when (true? (sig "req")) "on")
                                   :private (when (true? (sig "priv")) "on")
                                   :widget (when (true? (sig "widget")) "radio")
                                   :options (sig "opts")})]
                      (cond-> attrs
                        (keyword? (:max-length attrs)) (assoc :max-length nil))))]
        (if-not person
          (log/warn :form-preview-skipped :why :no-session)
          (let [existing (when field-id (forms/find-field fields field-id))
                attrs (cond
                        existing (assoc (typed "fbe") :type (forms/field-type existing))
                        add? (typed "fba"))
                ghost (when (and add? (:label attrs))
                        (assoc attrs :id "ghost-preview"))
                fields' (if existing
                          (mapv #(if (= (forms/field-id %) field-id) (merge % attrs) %)
                                fields)
                          fields)
                reach (sse/person-connection-count (:id event) (:id person))]
            (when (zero? reach)
              (log/warn :form-preview-push-no-subscriber
                        :event-id (:id event) :person-id (:id person)
                        :msg "nothing is listening — the preview cannot move"))
            (sse/push-to-person! (:id event) (:id person) "#fb-grid"
                                 #(view-form-builder/form-grid-region event {:fields fields'
                                                                             :confirming nil
                                                                             :ghost ghost}))
            (log/debug :form-preview-pushed :reach reach :edit field-id :add add?)))
        {:status 204 :headers {} :body ""})
      (event-web/not-found-page slug))))

(defn handle-form-reviewed [req]
  (with-form req
    (fn [event]
      (forms/mark-reviewed! event (:email (auth/current-person req)))
      (http/see-other (str "/events/" (:slug event) "/committee")))))
