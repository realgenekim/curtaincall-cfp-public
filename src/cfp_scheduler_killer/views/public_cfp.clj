(ns cfp-scheduler-killer.views.public-cfp
  "Public CFP, speaker inputs, safe mini-Markdown, and submission success."
  (:require
   [cfp-scheduler-killer.events :as events]
   [cfp-scheduler-killer.submissions :as submissions]
   [cfp-scheduler-killer.views.form-controls :as form-controls]
   [cfp-scheduler-killer.views.format :as format]
   [cfp-scheduler-killer.views.live-drafts :as live-drafts]
   [cfp-scheduler-killer.views.policy :as view-policy]
   [cfp-scheduler-killer.views.public-widgets :as view-public-widgets]
   [cfp-scheduler-killer.views.shell :as shell]
   [clojure.data.json :as json]
   [clojure.string :as str]
   [clojure.walk :as walk]
   [datastar-kit.ds :as ds]))

(defn- cfp-signal-name [param]
  (str "cfp" (str/replace (name param) #"[^A-Za-z0-9]" "")))

(defn- bind-form-controls
  "Bind public-CFP controls to stable client signals. A morph may re-run this
   wiring, so the form initializes signals with __ifmissing and the value the
   speaker is actively editing remains authoritative."
  [hiccup]
  (walk/postwalk
    (fn [node]
      (if (and (vector? node)
               (#{:input :select :textarea} (first node))
               (map? (second node))
               (:name (second node)))
        (update node 1 merge (ds/bind (keyword (cfp-signal-name (:name (second node))))))
        node))
    hiccup))

(defn cfp-signal-values
  "Translate raw public-CFP params into the signals bound to their controls.

   This is also the server-push boundary for profile imports: once ABOUT YOU
   controls are bound, a fragment alone cannot replace an existing browser
   signal, so the handler follows the fragment with these authoritative values."
  [values]
  (into {}
        (keep (fn [[param value]]
                (when (and (or (keyword? param) (string? param))
                           (or (str/starts-with? (name param) "answer-")
                               (str/starts-with? (name param) "speaker-"))
                           (string? value))
                  [(cfp-signal-name param) value])))
        values))

(defn cfp-closed-notice
  "What a speaker sees when they can't submit. Both messages are warm and both
   name a fact — never a bare 'closed'.

   :not-open-yet covers two situations that read the same to a speaker: an
   organizer who created the event with the call deliberately shut, and (for
   older events) a call scheduled to open later. Only the second one can name a
   date, so only the second one does."
  [event state]
  (case state
    :not-open-yet
    (let [opens (format/fmt-instant (:cfp-opens-at event) (:tz event))
          later? (and (:cfp-opens-at event)
                      (.isAfter ^java.time.Instant (:cfp-opens-at event)
                                (java.time.Instant/now)))]
      [:div.ui.info.message
       [:div.header "The call for speakers isn't open yet"]
       [:p (if (and later? opens)
             (str "It opens " opens ". Check back then.")
             "Check back soon — the organizers haven't opened it yet.")]])
    :closed
    [:div.ui.warning.message
     [:div.header "The call for speakers has closed"]
     [:p "Submissions closed "
      (or (format/fmt-instant (:cfp-closes-at event) (:tz event)) "recently")
      ". Thank you to everyone who submitted."]]
    nil))

(defn cfp-success-page
  "Honest confirmation of the saved submission and human invite policy."
  ([event submission]
   (cfp-success-page event submission nil))
  ([event submission portal-link]
   (cfp-success-page event submission portal-link true))
  ([event submission portal-link invite-queued?]
   (shell/page-shell
     (str "Submitted — " (:name event))
     [:div.cfp-masthead
      [:h1.ui.header "Thanks — your talk is in."]]
     [:div.ui.success.message
      [:div.header (get-in submission [:answers :talk-title])]
      [:p "Submitted to " (:name event) "."]]
     [:div.ui.segment
      [:h4.ui.header "Your private portal link — save it"]
      (if portal-link
        [:a {:href portal-link} portal-link]
        [:p (if invite-queued?
              "Use the private portal link in your queued confirmation email."
              "The organizers can resend portal access from Curtain Call.")])
      [:p.field-hint
       (if invite-queued?
         "This private one-time link opens your speaker portal. Your confirmation email is queued for organizer approval."
         "Your submission is saved, but its confirmation email could not be queued. Save the link above; the organizers can resend access later.")]]
     [:div.ui.segment
      [:h4.ui.header "What happens next"]
      [:ul
       [:li "The Program Committee reads every submission — real people, not a filter."]
       [:li "You can submit another talk from the "
        [:a {:href (str "/cfp/" (:slug event))} "call for speakers page"] "."]]
      [:div.field-hint {:style "margin-top:1em;"}
       "Your submission is saved. Use your private portal link whenever you need to edit it."]])))

(defn cfp-live-note
  "One stable, non-editable landing pad for a public speaker-field note."
  [param note]
  (live-drafts/cfp-note param note {:reactive? true}))

(defn- speaker-input
  "One ABOUT YOU input. `field-key` is the bare speaker attribute; errors for it
   are keyed :speaker-<field-key> so they can't collide with a form field."
  [{:keys [field-key label param error-key input-type required help placeholder]}
   values errors notes]
  (let [k (or error-key (keyword (str "speaker-" field-key)))
        v (get values param "")
        max-length (get submissions/speaker-max-lengths (keyword field-key))]
    [:div.field (merge {:class (when (get errors k) "error")}
                       (form-controls/reactive-field-attrs k))
     [:label label (form-controls/req-mark required)]
     [:input {:type (or input-type "text") :name (str (symbol param)) :value v
              :placeholder placeholder
              :maxlength (some-> max-length str)
              :data-ghost-fill (when placeholder "")}]
     (cfp-live-note param (get notes param))
     (when help [:div.field-hint help])
     (form-controls/field-error errors k {:reactive? true})]))

(def ^:private speaker-inputs
  [{:field-key "name"  :param :speaker-name  :label "Name" :required true
    :placeholder "Grace Hopper"}
   {:field-key "email" :param :speaker-email :label "Email" :input-type "email" :required true
    :help "How we reach you. Your speaker portal is tied to this address."
    :placeholder "you@company.com"}
   {:field-key "title" :param :speaker-title :label "Title / tagline" :required true
    :placeholder "SVP Engineering"}
   {:field-key "org"   :param :speaker-org   :label "Organization" :required true
    :placeholder "Acme Insurance"}])

(def ^:private speaker-validation-error-keys
  [:speaker-name :speaker-email :speaker-title :speaker-org :speaker-bio
   :speaker-headshot-url :speaker-linkedin-url :speaker-sessionize-url
   :speaker-2-role :speaker-2-name :speaker-2-email :speaker-2-title
   :speaker-2-org :speaker-2-bio :speaker-2-headshot-url
   :speaker-2-linkedin-url])

(defn cfp-validation-error-keys [form-fields]
  (into speaker-validation-error-keys
        (map (comp keyword name :id))
        (submissions/session-fields form-fields)))

(def speaker-live-note-params
  [:speaker-sessionize-url
   :speaker-name :speaker-email :speaker-title :speaker-org :speaker-bio
   :speaker-headshot-url :speaker-linkedin
   :speaker-2-name :speaker-2-email :speaker-2-title :speaker-2-org :speaker-2-bio
   :speaker-2-headshot-url :speaker-2-linkedin])

(defn cfp-live-note-params [form-fields]
  (concat
    (map (comp #(keyword (str "answer-" %)) name :id) form-fields)
    speaker-live-note-params))

(def ^:private additional-speaker-inputs
  [{:field-key "name" :param :speaker-2-name :error-key :speaker-2-name
    :label "Name" :required true :placeholder "Katherine Johnson"}
   {:field-key "email" :param :speaker-2-email :error-key :speaker-2-email
    :label "Email" :input-type "email" :required true
    :placeholder "co-speaker@company.com"}
   {:field-key "title" :param :speaker-2-title :error-key :speaker-2-title
    :label "Title / tagline" :required true :placeholder "Chief Architect"}
   {:field-key "org" :param :speaker-2-org :error-key :speaker-2-org
    :label "Organization" :required true :placeholder "PartnerCo"}])

(defn- additional-speaker-block [values errors notes]
  (let [v #(get values % "")
        note #(cfp-live-note % (get notes %))
        open? (or (some #(seq (str (v %)))
                        [:speaker-2-name :speaker-2-email :speaker-2-title
                         :speaker-2-org :speaker-2-bio
                         :speaker-2-headshot-url :speaker-2-linkedin])
                  (some #(str/starts-with? (name %) "speaker-2-") (keys errors)))]
    [:details.ui.segment.additional-speaker {:open (when open? true)}
     [:summary [:strong "+ Add another speaker"]]
     [:p.field-hint
      "Add a co-speaker, panelist, or moderator. Each person receives their own identity and portal access."]
     [:div.field (merge {:class (when (:speaker-2-role errors) "error")}
                        (form-controls/reactive-field-attrs :speaker-2-role))
      [:label "Speaker role"]
      [:select {:name "speaker-2-role"}
       (for [role submissions/speaker-role-options]
         [:option {:value role :selected (= role (or (v :speaker-2-role) "Co-speaker"))}
          role])]
      (form-controls/field-error errors :speaker-2-role {:reactive? true})]
     [:div.two.fields
      (speaker-input (nth additional-speaker-inputs 0) values errors notes)
      (speaker-input (nth additional-speaker-inputs 1) values errors notes)]
     [:div.two.fields
      (speaker-input (nth additional-speaker-inputs 2) values errors notes)
      (speaker-input (nth additional-speaker-inputs 3) values errors notes)]
     [:div.field (merge {:class (when (:speaker-2-bio errors) "error")}
                        (form-controls/reactive-field-attrs :speaker-2-bio))
      [:label "Bio" (form-controls/req-mark true)]
      [:textarea {:name "speaker-2-bio" :rows 5 :class "prose-deep"
                  :maxlength (str (:bio submissions/speaker-max-lengths))
                  :placeholder "A short biography for the program."}
       (v :speaker-2-bio)]
      (note :speaker-2-bio)
      (form-controls/field-error errors :speaker-2-bio {:reactive? true})]
     [:div.two.fields
      [:div.field (merge {:class (when (:speaker-2-headshot-url errors) "error")}
                         (form-controls/reactive-field-attrs :speaker-2-headshot-url))
       [:label "Headshot URL " [:span.optional "(optional)"]]
       [:input {:type "url" :name "speaker-2-headshot-url"
                :value (v :speaker-2-headshot-url)}]
       (note :speaker-2-headshot-url)
       (form-controls/field-error errors :speaker-2-headshot-url {:reactive? true})]
      [:div.field (merge {:class (when (:speaker-2-linkedin-url errors) "error")}
                         (form-controls/reactive-field-attrs :speaker-2-linkedin-url))
       [:label "LinkedIn " [:span.optional "(optional)"]]
       [:input {:type "url" :name "speaker-2-linkedin"
                :value (v :speaker-2-linkedin)}]
       (note :speaker-2-linkedin)
       (form-controls/field-error errors :speaker-2-linkedin-url {:reactive? true})]]]))

(defn cfp-about-you
  "The ABOUT YOU block, addressable as #cfp-about-you so the Sessionize import
   can morph it over SSE — filled fields appear in place, no page reload
   (Gene, 2026-08-09). Everything per-viewer: the import handler pushes only
   down the requesting viewer's stream, so two anonymous speakers on the same
   page never see each other's profile.

   The import button posts the current MAIN form so a click cannot outrun the
   debounced draft save; the server folds that snapshot into the same
   per-viewer draft before importing."
  [event values errors notes import-message]
  (let [v #(get values % "")
        note #(cfp-live-note % (get notes %))
        slug (:slug event)
        import-post (str "@post('/api/cfp/" slug
                         "/import-live', {contentType: 'form'})")]
    (bind-form-controls
      [:div#cfp-about-you
       [:div.import-box
        [:div.title "Have a Sessionize profile?"]
        [:div.ui.action.input.fluid
         ;; type=text, NOT url — the browser's native url validation would
         ;; reject a bare handle ("realgenekim") before the server sees it.
         ;; Enter imports rather than submitting the whole talk.
         [:input {:type "text" :name "speaker-sessionize-url"
                  :value (v :speaker-sessionize-url)
                  :placeholder "realgenekim"
                  :data-star-on:keydown
                  (str "if(evt.key==='Enter'){evt.preventDefault();"
                       import-post "}")}]
         [:button.ui.button {:type "button"
                             :data-star-on:click import-post}
          "Import"]]
        (note :speaker-sessionize-url)
        [:div.field-hint "Fills in your bio, photo and links below — you confirm "
         "before submitting. We never post anything or log in as you."]
        (when import-message
          [:div.ui.small.message {:style "margin-top:0.6em;"} import-message])]

       [:div.cfp-section-title "Primary speaker"]
       [:input {:type "hidden" :name "speaker-role" :value "Primary speaker"}]
       [:div.two.fields
        (speaker-input (nth speaker-inputs 0) values errors notes)
        (speaker-input (nth speaker-inputs 1) values errors notes)]
       [:div.two.fields
        (speaker-input (nth speaker-inputs 2) values errors notes)
        (speaker-input (nth speaker-inputs 3) values errors notes)]

       [:div.field (merge {:class (when (:speaker-bio errors) "error")}
                          (form-controls/reactive-field-attrs :speaker-bio))
        [:label "Bio" (form-controls/req-mark true)]
        [:textarea {:name "speaker-bio" :rows 5 :class "prose-deep"
                    :maxlength (str (:bio submissions/speaker-max-lengths))
                    :data-ghost-fill ""
                    :placeholder (str "Maria Chen leads platform engineering at "
                                      "Acme Insurance, where her team of 120 "
                                      "ships underwriting and claims systems. "
                                      "Her AI-assisted delivery program won "
                                      "Acme's 2025 Chairman's Award.")}
         (v :speaker-bio)]
        (note :speaker-bio)
        (form-controls/field-error errors :speaker-bio {:reactive? true})]

       [:div.two.fields
        [:div.field (merge {:class (when (:speaker-headshot-url errors) "error")}
                           (form-controls/reactive-field-attrs :speaker-headshot-url))
         [:label "Headshot URL " [:span.optional "(optional)"]]
         [:input {:type "url" :name "speaker-headshot-url" :value (v :speaker-headshot-url)}]
         (note :speaker-headshot-url)
         [:div.field-hint "Paste a link to a photo — imports fill this in for you."]
         (form-controls/field-error errors :speaker-headshot-url {:reactive? true})]
        [:div.field (merge {:class (when (:speaker-linkedin-url errors) "error")}
                           (form-controls/reactive-field-attrs :speaker-linkedin-url))
         [:label "LinkedIn " [:span.optional "(optional)"]]
         [:input {:type "url" :name "speaker-linkedin" :value (v :speaker-linkedin)}]
         (note :speaker-linkedin)
         (form-controls/field-error errors :speaker-linkedin-url {:reactive? true})]]

       ;; Carry the pasted profile URL through a normal submit too, so it is
       ;; recorded on the submission even if they never pressed Import.
       [:input {:type "hidden" :name "speaker-sessionize-url-carry"
                :value (v :speaker-sessionize-url)}]

       (additional-speaker-block values errors notes)])))

(defn cfp-live-preview
  "The only fragment a draft keystroke may morph. It reports saved progress and
   live validation without containing any user-editable controls."
  [form-fields values notes progress saved?]
  (let [answers (submissions/parse-answers form-fields values)]
    [:div#cfp-live-preview
     (live-drafts/cfp-draft-status progress saved?)
     (for [field (submissions/visible-session-fields form-fields answers)
           :let [param (keyword (str "answer-" (name (:id field))))
                 note (get notes param)]
           :when note]
       [:div.cfp-live-note
        [:strong (str (:label field) ": ")]
        (live-drafts/cfp-note param note)])]))

(defn- conditional-answer-input
  "Render conditional questions once and let the controlling answer's existing
   Datastar signal toggle them. Server state remains authoritative for restored
   visibility; no inline display rule competes with the signal."
  [field values errors answers]
  (let [control (form-controls/answer-input (assoc field :reactive? true) values errors)]
    (if-let [{:keys [field-id equals]} (:show-when field)]
      (update control 1 merge
              {:data-star-show
               (str "$" (cfp-signal-name
                          (keyword (str "answer-" field-id)))
                    " === " (json/write-str equals))
               :data-show-when-field field-id
               :data-show-when-value equals
               :data-server-visible (str (submissions/field-visible? field answers))})
      control)))

(defn cfp-session-question
  "Render exactly one 'YOUR TALK' field, preserving signal wiring."
  [field values errors answers]
  [:div {:id (str "pv-" (name (:id field)))}
   (bind-form-controls
     (conditional-answer-input field values errors answers))])

(defn cfp-session-question-selector
  "Stable target for a single question fragment update."
  [field-id]
  (str "#pv-" (name field-id)))

(defn cfp-session-fields
  "The YOUR TALK controls. The scoped live region owns only non-editable
   projection HTML; browser-owned form controls remain outside every morph."
  ([form-fields values errors notes progress saved?]
   (cfp-session-fields form-fields values errors notes progress saved? nil))
  ([form-fields values errors notes progress saved? live-region]
   (let [answers (submissions/parse-answers form-fields values)]
     (bind-form-controls
       [:div#cfp-session-fields
        [:div.cfp-section-title "Your talk"]
        (or live-region
            (cfp-live-preview form-fields values notes progress saved?))
        (for [f (submissions/session-fields form-fields)]
          (cfp-session-question f values errors answers))]))))

(defn cfp-page
  "The public submission page (docs/design/submission-page-wireframe.md).

   `opts` = {:state :open|:not-open-yet|:closed
             :form-fields [...] :values {..} :errors {..}
             :message <string> :import-message <string> :cap n
             :notes {param note} :progress {:answered n :total n}
             :restored? truthy-when-a-draft-was-found}"
  [event {:keys [state form-fields values errors message import-message cap notes progress restored? organizer base-url speakers presenter-visibility presenter-visibility-definition live-region]}]
  (let [open? (= :open state)
        slug (:slug event)]
    (shell/share-page-shell
      ;; `events/display-name` is the ONE place that knows how an event is
      ;; spelled — same string the organizer watched write itself on the create
      ;; page, same string on the agenda, same string in the tab title.
      (str (events/display-name event) " — Call for Speakers")
      (view-public-widgets/event-og-meta
        event base-url (str "/cfp/" slug) "Call for Speakers")

        ;; The Sessionize import still uses the legacy targeted stream. Ordinary
        ;; draft validation is isolated in the scoped local view nested below.
      (when open? [:div#cfp-import-live
                   (ds/sse-mount-url (str "/api/cfp/" slug "/stream"))])
      (when open? (shell/datastar-script))

      ;; The hero SELLS the event with every fact we hold (Gene, 2026-08-09):
      ;; kicker, the full marquee (name — city · dates), the organizer's pitch
      ;; rendered as markdown, then the practical facts in one quiet line.
      [:div.cfp-masthead
       (when-let [img (get-in event [:settings :hero-image-url])]
         [:img.cfp-hero {:src img :alt (events/display-name event)}])
       [:div.cfp-kicker "Call for Speakers"]
       ;; Plain title — the highlighter mark read as "a weird yellow bar" out
       ;; here (Gene, 2026-08-09); the gesture belongs on the create page only.
       [:h1.masthead-title (events/display-name event)]
       ;; T3 two-column editorial masthead (Gene converged, 2026-08-11):
       ;; serif thesis + bold-sans ask on the left; a labeled facts rail on the
       ;; right (Organizer / Submissions / Links). The event date lives ONLY in
       ;; the H1 — events/display-name is the one canonical spelling.
       ;; No intro copy (e.g. a fresh event): the rail runs horizontal under
       ;; the title instead of flanking an empty column (the demo-CFP void,
       ;; Gene 2026-08-11).
       [:div.cfp-two-col
        {:class (when-not (format/not-blank (:cfp-intro event)) "cfp-rail-only")}
        [:div.cfp-intro-col
         (when-let [intro (format/not-blank (:cfp-intro event))]
           [:div.cfp-intro (format/render-markdown intro)])]
        [:div.cfp-rail
         (when organizer
           [:div.rl
            [:span.rk "Organizer"]
            [:a.cfp-organizer-chip {:href (:url organizer)}
             (when-let [img (:headshot-url organizer)]
               [:img.cfp-organizer-photo {:src img :alt (:name organizer) :loading "lazy"}])
             [:span.cfp-organizer-name (:name organizer)]]
            (when-let [t (:tagline organizer)]
              [:div.cfp-rail-attr t])])
         (when (and (= :open state) (:cfp-closes-at event))
           (when-let [c (format/fmt-instant (:cfp-closes-at event) (:tz event))]
             [:div.rl [:span.rk "Submissions"] "Close " c]))
         [:div.rl [:span.rk "Program"]
          [:a {:href (str "/program/" slug)} "Speakers & agenda so far ↗"]]
         (when (:website-url event)
           [:div.rl [:span.rk "Links"]
            [:a {:href (:website-url event) :target "_blank" :rel "noopener"}
             "Official event website ↗"]])]]]

      (cfp-closed-notice event state)

      (view-policy/presenter-visibility-summary presenter-visibility presenter-visibility-definition)

      (when message [:div.ui.negative.message message])

      (when (and open? restored?)
        [:div.cfp-restored
         [:div
          "Picked up where you left off — everything you typed on this device is "
          "still here."]
         [:form {:method "post" :action (str "/api/cfp/" slug "/draft/reset")}
          [:button.ui.tiny.basic.button {:type "submit"} "Reset saved data"]]])

      (when open?
        ;; Every debounced keystroke posts the WHOLE form to the draft endpoint.
        ;; contentType 'form' (this Datastar build serializes the enclosing form
        ;; as urlencoded) means the draft params are spelled exactly like a real
        ;; submit — one vocabulary for the stash, the prefill and the 422.
        ;;
        ;; :novalidate is REQUIRED, not cosmetic: Datastar's form path calls
        ;; checkValidity() and would pop reportValidity() on every keystroke into
        ;; a half-typed type="url" field. Validation is the server's job here
        ;; anyway — it is the same code the 422 uses, pushed down the stream.
        [:form.ui.form {:id (str "cfp-form-" slug)
                        :method "post" :action (str "/api/cfp/" slug "/submit")
                        :novalidate "novalidate"
                        :data-star-signals__ifmissing
                        (json/write-str
                          (merge (cfp-signal-values values)
                                 (form-controls/validation-signal-values
                                   (cfp-validation-error-keys form-fields)
                                   errors)
                                 (live-drafts/note-signal-values
                                   (cfp-live-note-params form-fields)
                                   notes)))
                        :data-star-on:input__debounce.300ms
                        (str "@post('/api/cfp/" slug "/draft', {contentType: 'form'})")}
         (cfp-session-fields form-fields values errors notes progress false live-region)

         [:div.cfp-section-title "About you"]

         ;; The import box + speaker fields live in an addressable block the
         ;; Sessionize import morphs over SSE — see `cfp-about-you`.
         (cfp-about-you event values errors notes import-message)

         ;; No disabled ghost buttons and no roadmap talk at the moment of
         ;; maximum trust (Gene, 2026-08-09) — one green act, one promise we
         ;; keep on the very next page.
         [:div {:style "margin-top:1.4em;"}
          (when cap
            [:div.cfp-cap-note (str "Up to " cap " talk"
                                    (when (not= 1 cap) "s") " per person.")])
          [:button.btn-go {:type "submit"} "Submit talk"]
          [:div.field-hint {:style "margin-top:0.6em;"}
           "You'll land on a confirmation page with a link to your speaker "
           "portal, where you can edit this submission anytime."]]])

      (when-let [announced (seq speakers)]
        [:div.cfp-featured
         [:div.cfp-section-title "Featured speakers — already on the program"]
         [:div.cfp-featured-grid
          (for [sp announced]
            (view-public-widgets/announced-card sp))]])
      [:div {:style "margin-top:3em; color:#aaa; font-size:0.85em;"}
       (when (:support-email event)
         (list "Questions? "
               [:a {:href (str "mailto:" (:support-email event))} (:support-email event)]))])))
