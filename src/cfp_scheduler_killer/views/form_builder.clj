(ns cfp-scheduler-killer.views.form-builder
  "CFP schema builder and its server-pushable regions."
  (:require
   [cfp-scheduler-killer.forms :as forms]
   [cfp-scheduler-killer.submissions :as submissions]
   [cfp-scheduler-killer.views.form-controls :as form-controls]
   [cfp-scheduler-killer.views.organizer-layout :as organizer-layout]
   [clojure.string :as str]
   [datastar-kit.ds :as ds]))

(defn- fb-post
  "One-button form POST. Every action on this page is one of these — a button
   that changes server state and forgets, which is the whole client contract."
  [action label params & [{:keys [class title]}]]
  [:form {:method "post" :action action}
   (for [[k v] params]
     [:input {:type "hidden" :name (name k) :value (str v)}])
   [:button.ui.mini.basic.button
    {:type "submit" :class class :title title} label]])

(defn- finish-cfp-bar
  "The wizard's forward act — the same green button top and bottom of the form
   page (Gene, 2026-08-09), always visible whether or not the form was already
   marked reviewed. Not-yet-reviewed → a POST that records the review and lands
   on step 3; already reviewed → a plain link to step 3. `pos` is :top or
   :bottom, only for a class hook."
  [event reviewed? pos]
  [:div.fb-next {:class (name pos)}
   (if reviewed?
     [:a.btn-go {:href (str "/events/" (:slug event) "/committee")}
      "Next: create the review committee →"]
     [:form {:method "post" :action (str "/api/events/" (:slug event) "/form/reviewed")}
      [:button.btn-go {:type "submit"}
       "The form looks right — create the review committee →"]])])

(defn form-preview-region
  "The public CFP page's OWN renderer, run over the live field defs. Not a
   mock-up of the form — literally `answer-input`, the function the speaker's
   browser gets, so a preview that looks right cannot be lying. `ghost` is the
   would-be field from the add panel, shown appended before it exists."
  ([event fields] (form-preview-region event fields nil))
  ([event fields {:keys [ghost]}]
   [:div#form-preview
    [:div.ui.form.fb-preview
     [:div.cfp-section-title {:style "margin-top:0;"} "Your talk"]
     ;; Numbered to MATCH the builder rows on the left (Gene, 2026-08-09): the
     ;; number is the correspondence key, so the two columns line up by eye
     ;; without any brittle height-sync. The number wraps answer-input rather
     ;; than living inside it — answer-input is the SAME fn the public page
     ;; uses, and speakers must never see question numbers.
     (map-indexed
       (fn [i f]
         [:div.pv-item {}
          [:span.pv-num (inc i)]
          [:div.pv-field (form-controls/answer-input f {} {})]])
       (submissions/session-fields (forms/active-fields fields)))
     (when ghost
       [:div.fb-ghost {}
        (form-controls/answer-input ghost {} {})
        [:div.field-hint "Not added yet — appears here when you press Add question."]])
     [:div.cfp-section-title "About you"]
     [:div.field-hint
      "The repeatable speaker block — name, email, title, organization, bio, "
      "headshot, LinkedIn — renders here on the real page."]]
    [:div.field-hint {:style "margin-top:0.7em;"}
     "This is the public page's renderer, not a mock-up. "
     [:a {:href (str "/cfp/" (:slug event)) :target "_blank" :rel "noopener"}
      "Open the real thing →"]]]))

(defn- type-label [f]
  (let [t (forms/field-type f)]
    (or (:label (first (filter #(= t (:value %)) forms/editable-types)))
        (case t
          "group" "Repeatable block"
          "file" "File upload"
          t))))

(defn- field-form-fields
  "The shared body of the add and edit forms. `f` is the field being edited, or
   nil when adding."
  [f values errors source-fields & [sig]]
  (let [v #(or (get values %) (when f (get f %)) "")
        checked? #(if (contains? values %)
                    (boolean (get values %))
                    (boolean (when f (get f %))))
        select? (= "select" (str (or (get values :type)
                                   (some-> f forms/field-type))))
        sig* (fn [suffix] (if sig (ds/bind (keyword (str sig suffix))) {}))
        condition (or (:show-when values) (:show-when f))
        source-id (or (:show-when-field-id values) (:field-id condition) "")
        expected (or (:show-when-value values) (:equals condition) "")]
    (list
      [:div.field {:class (when (:label errors) "error")}
       [:label "Question" (form-controls/req-mark true)]
       [:input (merge {:type "text" :name "label" :value (v :label)
                       :placeholder "What would you tell a peer CTO to do differently?"}
                      (sig* "label"))]
       (form-controls/field-errors errors :label)]

      (if f
        ;; The type is fixed at birth: changing it under stored answers is how a
        ;; form builder corrupts its own history.
        [:div.field {}
         [:label "Type"]
         [:div.field-hint (type-label f) " — a field's type is permanent, because "
          "answers are already stored in its shape. Retire it and add a new one "
          "if you need a different kind of answer."]]
        [:div.field {:class (when (:type errors) "error")}
         [:label "Type" (form-controls/req-mark true)]
         [:select (merge {:name "type"} (sig* "type"))
          (let [selected (str (or (get values :type) "text"))]
            (for [t forms/editable-types]
              [:option (cond-> {:value (:value t)}
                         (= (:value t) selected) (assoc :selected true))
               (:label t)]))]
         (form-controls/field-errors errors :type)])

      [:div.field {}
       [:label "Help text " [:span.optional "(optional)"]]
       [:input (merge {:type "text" :name "help" :value (v :help)
                       :placeholder "Specific numbers beat adjectives."}
                      (sig* "help"))]]

      ;; No character-limit box. The answer's SHAPE (its type) says how long in
      ;; human words; invisible server-side caps guard against abuse (Gene,
      ;; 2026-08-09: "we are not DBAs"). Numbers stay out of the UI entirely.
      [:div.field {}
       [:div.field
        [:label "Flags"]
        [:div.ui.checkbox {:style "margin-right:1.2em;"}
         [:input (cond-> (merge {:type "checkbox" :name "required"} (sig* "req"))
                   (checked? :required) (assoc :checked true))]
         [:label "Required"]]
        [:div.ui.checkbox
         [:input (cond-> (merge {:type "checkbox" :name "private"} (sig* "priv"))
                   (checked? :private) (assoc :checked true))]
         [:label "Private (committee only)"]]
        [:div.field-hint "A private answer is collected from the speaker and "
         "never appears in any export, the public API or the agenda."]]]

      ;; Only meaningful for "Choose one". On an EDIT the type is already known,
      ;; so a text field never shows an options box it would silently ignore.
      (when (or (nil? f) select?)
        [:div.field {:class (when (:options errors) "error")}
         [:label "Options " [:span.optional "(one per line — 'Choose one' only)"]]
         [:textarea (merge {:name "options" :rows 4
                            :placeholder "Experience Report\nSME talk\nPanel"}
                           (sig* "opts"))
          (let [o (or (get values :options) (when f (:options f)))]
            (if (sequential? o) (str/join "\n" o) (str (or o ""))))]
         (form-controls/field-errors errors :options)
         [:div.ui.checkbox {:style "margin-top:0.5em;"}
          [:input (cond-> (merge {:type "checkbox" :name "widget" :value "radio"} (sig* "widget"))
                    (= "radio" (str (or (get values :widget)
                                        (some-> f :widget name))))
                    (assoc :checked true))]
          [:label "Show as radio buttons instead of a dropdown"]]]) [:div.field {:class (when (:show-when errors) "error")}
                                                                     [:label "Show when " [:span.optional "(optional)"]]
                                                                     [:div.two.fields
                                                                      [:div.field
                                                                       [:select (merge {:name "show-when-field-id"} (sig* "conditionfield"))
                                                                        [:option {:value "" :selected (str/blank? (str source-id))}
                                                                         "Always show"]
                                                                        (for [source source-fields]
                                                                          (let [id (forms/field-id source)]
                                                                            [:option {:value id :selected (= id source-id)}
                                                                             (:label source)]))]]
                                                                      [:div.field
                                                                       [:input (merge {:type "text"
                                                                                       :name "show-when-value"
                                                                                       :value expected
                                                                                       :placeholder "Panel"
                                                                                       :data-ghost-fill ""}
                                                                                      (sig* "conditionvalue"))]]]
                                                                     (form-controls/field-errors errors :show-when)
                                                                     [:div.field-hint
                                                                      "Choose a previous question and the exact answer that reveals this one."]])))

(defn- field-row
  "One field def as a line. `confirming` is the field-id this viewer is being
   asked about — server state, rendered server-side, so there is no confirm()
   anywhere near the SSE stream."
  [event f {:keys [index last-session? confirming]}]
  (let [slug (:slug event)
        id (forms/field-id f)
        group? (forms/group? f)
        retired? (forms/retired? f)
        base (str "/api/events/" slug "/form/")]
    (list
      ;; A question is a full-width label with its actions UNDERNEATH (Gene,
      ;; 2026-08-09: a long label like #6 must not fight the buttons for the
      ;; row). Two calm lines of content, then a quiet button strip. The label
      ;; anchors to its preview twin (#pv-<id>) — click a row, the right pane
      ;; scrolls to that field. Plain HTML, zero JS.
      [:div.fb-row {:class (when retired? "retired")}
       ;; Lock sits to the LEFT of the number (Gene, 2026-08-09) — a locked
       ;; question reads as pinned before you even reach its number.
       [:div.fb-lock (when (forms/locked? f)
                       [:span {:title "Locked — the spine of the form"} "🔒"])]
       [:div.fb-ord (if (or group? retired?) "—" (inc index))]
       [:div.fb-body
        [:a.fb-label {:href (str "#pv-" id) :title "Show in the preview"}
         (:label f) (form-controls/req-mark (:required f))
         (when (:private f) [:span.fb-tag.priv {:title "Committee only"} "PC"])
         (when retired? [:span.fb-tag.gone {} "RETIRED"])]
        [:span.fb-shape
         (if group?
           ;; Plain human sentence, not "Repeatable block · structural" (Gene).
           (str "There are " (count (:fields f)) " speaker profile questions")
           (list (type-label f)
                 (when (seq (:options f)) (str " · " (count (:options f)) " options"))))]
        [:div.fb-acts
         (when-not (or group? retired?)
           (list
             (fb-post (str base "move") "↑" {:field-id id :direction "up"}
                      {:class (when (zero? index) "disabled") :title "Move up"})
             (fb-post (str base "move") "↓" {:field-id id :direction "down"}
                      {:class (when last-session? "disabled") :title "Move down"})))
         (when-not group?
           [:a.ui.mini.basic.button {:href (str "/events/" slug "/form?edit=" id)} "Edit"])
         (cond
           group? nil
           retired? (fb-post (str base "restore") "Restore" {:field-id id})
           (forms/locked? f) [:span.field-hint {:style "margin:0;"} "can't be removed"]
           :else (fb-post (str base "retire-ask") "Retire" {:field-id id}))]]]

      ;; Step two of the delete: server-rendered, never a modal dialog.
      (when (= confirming id)
        [:div.fb-confirm {}
         [:strong "Retire “" (:label f) "”?"]
         " It stops appearing on the public form. Nothing is erased — the field "
         "id " [:code.fb-id id] " is permanent, and every answer already given to "
         "it stays readable on its submission."
         [:div {:style "margin-top:0.5em;"}
          (fb-post (str base "retire") "Yes, retire it" {:field-id id})
          (fb-post (str base "retire-cancel") "Cancel" {})]]))))

(defn form-edit-panel
  "The open editor for ONE field as a MODAL (Gene, 2026-08-09) — you edit one
   question at a time, so it takes the foreground over a dimmed backdrop rather
   than pushing the list around. Still 100% server-rendered and server-owned
   (the `?edit=<id>` in the URL is the state); the backdrop is a real link that
   cancels, so there is no confirm()/JS anywhere near the SSE stream.

   Deliberately OUTSIDE #form-fields: an SSE push must never land on half-typed
   text."
  [event f {:keys [errors values]} source-fields]
  (when f
    (let [cancel-href (str "/events/" (:slug event) "/form")]
      [:div.fb-modal-backdrop
       ;; Click the dimmed area = cancel. A plain anchor, no listener.
       [:a.fb-modal-scrim {:href cancel-href :aria-label "Cancel"}]
       [:div#form-edit-panel.fb-modal.ui.segment.fb-card
        [:div.fb-modal-head
         [:h4.ui.header {:style "margin:0;"} "Edit question"
          [:span.fb-meta {:style "margin-left:0.6em; font-weight:400;"}
           [:code.fb-id (forms/field-id f)] " — permanent"]]
         [:a.fb-modal-x {:href cancel-href :title "Close" :aria-label "Close"} "×"]]
        [:form.ui.form {:method "post" :action (str "/api/events/" (:slug event) "/form/update")
                        :data-star-on:input__debounce.300ms
                        (str "@post('/api/events/" (:slug event) "/form/preview?field-id="
                             (forms/field-id f) "')")
                        ;; Cmd-S saves the open question — same idiom as the
                        ;; details page (ds/on-meta, browser-owned submit).
                        :data-star-on:keydown__window (ds/on-meta "s" "el.requestSubmit()")}
         [:input {:type "hidden" :name "field-id" :value (forms/field-id f)}]
         (field-form-fields f values errors source-fields "fbe")
         [:div.fb-modal-foot
          [:button.ui.small.primary.button {:type "submit"} "Save changes"]
          [:a.ui.small.basic.button {:href cancel-href} "Cancel"]]]]])))

(defn form-fields-region
  "The field list. Pushed per viewer over SSE. Retired fields collapse into a
   <details> at the bottom so the working list is exactly what speakers see."
  [event {:keys [fields confirming]}]
  (let [session (vec (remove forms/group? fields))
        live (vec (remove forms/retired? session))
        last-live-id (forms/field-id (last live))
        active (vec (remove forms/retired? fields))
        retired (vec (filter forms/retired? fields))]
    [:div#form-fields
     (if (empty? fields)
       [:div.empty-state "This event has no form — that shouldn't happen."]
       (list
         (map-indexed
           (fn [i f]
             (field-row event f {:index i
                                 :last-session? (= (forms/field-id f) last-live-id)
                                 :confirming confirming}))
           active)
         (when (seq retired)
           [:details.fb-retired {}
            [:summary (str "Show " (count retired) " retired question"
                           (when (not= 1 (count retired)) "s"))]
            (map-indexed
              (fn [i f]
                (field-row event f {:index i :confirming confirming}))
              retired)])))]))

(defn form-grid-region
  "Questions and their live preview INTERLEAVED into one CSS grid, so each
   question shares a grid ROW with its preview twin and the two can never drift
   out of vertical step (Gene, 2026-08-09: 'get q form and q preview
   valigned'). One pushable region (#fb-grid): a structural change or a live
   edit re-renders the whole grid. Cells are top-aligned, so a tall preview
   input (the Abstract textarea) makes the row tall and its builder row sits at
   the row's top, right beside it.

   `fields` may already carry an in-progress edit (the preview handler applies
   it before calling). `ghost` is the not-yet-added field from the add panel."
  [event {:keys [fields confirming ghost]}]
  (let [session (submissions/session-fields (forms/active-fields fields))
        last-live-id (forms/field-id (last session))
        group (first (filter forms/group? fields))
        retired (vec (filter forms/retired? fields))]
    [:div#fb-grid.fb-grid
     [:h4.ui.header.fb-col-head {} "Questions"]
     [:h4.ui.header.fb-col-head {} "What speakers see"]
     ;; Each session question: LEFT builder cell, then RIGHT preview cell —
     ;; grid auto-flow drops them onto the same row.
     (map-indexed
       (fn [i f]
         (list
           [:div.fb-gl {}
            (field-row event f {:index i
                                :last-session? (= (forms/field-id f) last-live-id)
                                :confirming confirming})]
           [:div.fb-gr {}
            [:span.pv-num (inc i)]
            [:div.pv-field.ui.form.fb-preview (form-controls/answer-input f {} {})]]))
       session)
     ;; The add-preview ghost, if any, as a trailing right cell.
     (when ghost
       (list [:div.fb-gl {}]
             [:div.fb-gr {}
              [:div.fb-ghost.ui.form.fb-preview (form-controls/answer-input ghost {} {})
               [:div.field-hint "Not added yet — appears when you press Add question."]]]))
     ;; The speaker block pairs with the "About you" section of the preview.
     (when group
       (list
         [:div.fb-gl {}
          (field-row event group {:index 0 :confirming confirming})]
         [:div.fb-gr {}
          [:div.ui.form.fb-preview
           [:div.cfp-section-title {:style "margin-top:0;"} "About you"]
           [:div.field-hint "Name, email, title, organization, bio, headshot & "
            "LinkedIn — filled in by each speaker (co-presenters welcome)."]]]))
     ;; Retired questions collapse full-width below the aligned pairs.
     (when (seq retired)
       [:details.fb-retired.fb-span {}
        [:summary (str "Show " (count retired) " retired question"
                       (when (not= 1 (count retired)) "s"))]
        (map-indexed
          (fn [i f] (field-row event f {:index i :confirming confirming}))
          retired)])]))

(defn form-builder-page
  "`opts` = {:fields [..] :person p :editing field-def :confirming field-id
             :add-form {:values .. :errors ..} :edit-form {:values .. :errors ..}
             :reviewed? bool :submission-count n}"
  [event {:keys [fields person editing add-form edit-form reviewed?
                 submission-count saved-toast]
          :as opts}]
  (organizer-layout/organizer-shell
    (str "Form — " (:name event))
    {:event event :active :form :person person :crumb "Form" :sse? true
     :body-attrs (ds/sse-mount (:id event))}

    (when saved-toast [:div.toast {} saved-toast])

    (organizer-layout/header "Create CFP Form"
                             (let [active (submissions/session-fields (forms/active-fields fields))
                                   retired (forms/retired-fields fields)
                                   private (filter :private active)]
                               (str (count active) " active questions plus the speaker profile"
                                    (when (pos? (count retired))
                                      (str " · " (count retired) " retired"))
                                    (when (pos? (count private))
                                      (str " · " (count private) " committee-only"))))
                             ;; The wizard's forward act sits ABOVE "View public page" in the
                             ;; header stack (Gene, 2026-08-09); the same button repeats at the
                             ;; bottom of the page via `finish-cfp-bar`.
                             [:div.fb-header-acts {}
                              (finish-cfp-bar event reviewed? :top)
                              [:a.ui.basic.button {:href (str "/cfp/" (:slug event))
                                                   :target "_blank" :rel "noopener"}
                               "View public page"]])

    ;; The wizard banner: this page IS step 2, and it says what the act is.
    (when-not reviewed?
      [:div.step-banner
       [:strong "Step 2 of 3."] " Your speakers will answer exactly these "
       "questions. The seed set is proven over 15 years of our CFPs — edit "
       "anything (the preview on the right is the real public page), or say "
       "the form looks right and move on. You can come back anytime, even after "
       "submissions arrive."])

    (when (pos? (or submission-count 0))
      [:div.ui.info.message
       [:div.header (str submission-count " submission"
                         (when (not= 1 submission-count) "s") " already exist"
                         (when (= 1 submission-count) "s"))]
       [:p "Editing the form now is safe: every submission carries its own "
        "snapshot of the questions it was answered against, so nothing you do "
        "here rewrites what a speaker already said."]])

    (form-edit-panel event editing edit-form
                     (forms/condition-source-fields fields
                                                    (some-> editing forms/field-id)))

    ;; The interleaved grid: each question and its live preview share a row and
    ;; stay vertically aligned (Gene, 2026-08-09). Column headers are the grid's
    ;; own top row, so they align too.
    (form-grid-region event opts)

    [:div.field-hint {:style "margin-top:0.8em;"}
     "Field ids are permanent — rename a label as often as you like, the id "
     "never moves, so answers keep their meaning. Removing is retiring: the "
     "question leaves the public form and its answers stay readable."]

    [:div.fb-add-block
     [:h4.ui.header "Add a question"]
     [:form.ui.form {:method "post" :action (str "/api/events/" (:slug event) "/form/add")
                     :data-star-on:input__debounce.300ms
                     (str "@post('/api/events/" (:slug event) "/form/preview?mode=add')")}
      (field-form-fields nil (:values add-form) (:errors add-form)
                         (forms/condition-source-fields fields nil) "fba")
      [:button.ui.small.primary.button {:type "submit"} "Add question"]]]

    (finish-cfp-bar event reviewed? :bottom)))
