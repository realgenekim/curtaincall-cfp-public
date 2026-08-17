(ns cfp-scheduler-killer.submissions
  "The public CFP's output: talk submissions.

   The form is DATA (a vector of field defs, docs/design/form-builder.md), and
   this namespace derives everything from it — the malli schema, the answer
   parsing, the required-field messages. Add a field to the seed form and the
   validation follows automatically; there is no second list to keep in sync.

   Three rules from the design doc that this code enforces:
     1. **Snapshot the field defs with each submission.** A mid-CFP form edit
        must never change how an existing submission renders or validates, so
        the whole field vector is stored on the submission.
     2. **Answers are keyed by FIELD ID, never by label.** Labels rename freely.
     3. **Private fields are PC-only.** `:private true` fields are collected from
        the speaker but never leave the committee."
  (:require
   [cfp-scheduler-killer.forms :as forms]
   [cfp-scheduler-killer.mail :as mail]
   [cfp-scheduler-killer.people :as people]
   [cfp-scheduler-killer.sessionize-import :as sessionize-import]
   [cfp-scheduler-killer.store :as store]
   [clojure.string :as str]
   [closed-record.core :as cr]
   [com.fulcrologic.guardrails.core :refer [=> >defn ?]]
   [malli.core :as m]
   [malli.error :as me]
   [taoensso.timbre :as log])
  (:import
   (java.time Instant)))

(def default-status
  "New submissions land in the middle of the 7-valued vocabulary — neither
   accepted nor declined, and explicitly NOT a draft."
  "Pending")

(def content-statuses ["Draft" "In review" "Approved"])
(def default-content-status "Draft")
(def ^:private content-status-set (set content-statuses))

(defn content-status [submission]
  (or (:content-status submission) default-content-status))

(>defn set-content-status!
       "Set the editorial readiness of a submission, independently of its decision.
   Repeating the current value is a no-op; every actual transition is a fact."
       [submission-id status actor]
       [string? string? string? => map?]
       (when-not (contains? content-status-set status)
         (throw (ex-info (str "Invalid content status: " status)
                         {:type :invalid-content-status
                          :allowed content-statuses
                          :value status})))
       (let [submission (store/submission-by-id submission-id)]
         (when-not submission
           (throw (ex-info (str "No such submission: " submission-id)
                           {:type :no-such-submission :submission-id submission-id})))
         (let [before (content-status submission)]
           (when-not (= before status)
             (store/append! {:type "submission.content-status-changed"
                             :actor actor
                             :event-id (:event-id submission)
                             :payload {:event-id (:event-id submission)
                                       :submission-id submission-id
                                       :from before
                                       :to status
                                       :at (store/now-iso)}})
             (log/info :submission-content-status-changed
                       :submission-id submission-id :from before :to status))
           (store/submission-by-id submission-id))))

;; --- Field-def helpers ------------------------------------------------------

(defn session-fields
  "The non-group fields — everything in the YOUR TALK part of the page."
  [form-fields]
  (remove #(= "group" (name (:type %))) form-fields))

(defn field-visible?
  "True when `field` has no condition or its controlling answer equals the
   configured value. This predicate is the shared authority for rendering,
   parsing, progress, live feedback, and validation."
  [field answers]
  (if-let [{:keys [field-id equals]} (:show-when field)]
    (= (str (get answers (keyword (name field-id)))) (str equals))
    true))

(defn visible-session-fields
  "Session fields whose show-when predicates hold for canonical `answers`.

   The fold exposes only answers from preceding visible fields. That gives
   chained conditions ordinary left-to-right semantics and prevents a stale
   answer from a hidden controller from revealing anything downstream."
  [form-fields answers]
  (first
    (reduce (fn [[visible visible-answers] field]
              (if (field-visible? field visible-answers)
                (let [id (keyword (name (:id field)))]
                  [(conj visible field)
                   (assoc visible-answers id (get answers id))])
                [visible visible-answers]))
            [[] {}]
            (session-fields form-fields))))

(defn speaker-group
  "The repeatable speaker block (ABOUT YOU)."
  [form-fields]
  (first (filter #(= "group" (name (:type %))) form-fields)))

(defn public-fields
  "Fields safe to show outside the committee (exports, public agenda)."
  [form-fields]
  (remove :private (session-fields form-fields)))

(defn field-by-id [form-fields id]
  (first (filter #(= (name (:id %)) (name id)) form-fields)))

;; --- Parsing ----------------------------------------------------------------

(defn- blank->nil [s]
  (when (string? s) (let [t (str/trim s)] (when-not (str/blank? t) t))))

(defn- canonical-sessionize-url [value]
  (when-let [raw (blank->nil value)]
    (or (sessionize-import/normalize-profile-url raw) raw)))

(defn parse-answers
  "Pull one visible answer per session field out of raw params, keyed by field id.

   Every candidate is parsed first so show-when predicates can inspect their
   controlling answers. Hidden answers are then discarded: stale browser input
   can never smuggle a value for a question the speaker cannot see."
  [form-fields params]
  (let [all-answers
        (reduce (fn [acc field]
                  (let [id (keyword (name (:id field)))
                        value (blank->nil
                                (get params (keyword (str "answer-" (name id)))))]
                    (assoc acc id value)))
                {}
                (session-fields form-fields))
        visible-ids (map (comp keyword name :id)
                         (visible-session-fields form-fields all-answers))]
    (select-keys all-answers visible-ids)))

(defn parse-speaker
  "The owning speaker's details from the ABOUT YOU block."
  [params]
  {:name (blank->nil (:speaker-name params))
   :email (people/normalize-email (:speaker-email params))
   :title (blank->nil (:speaker-title params))
   :org (blank->nil (:speaker-org params))
   :bio (blank->nil (:speaker-bio params))
   :headshot-url (blank->nil (:speaker-headshot-url params))
   :linkedin-url (blank->nil (:speaker-linkedin params))
   :sessionize-url (canonical-sessionize-url (:speaker-sessionize-url params))})

(def speaker-role-options
  ["Co-speaker" "Panelist" "Moderator"])

(defn- parse-additional-speaker [params position]
  (let [prefix (str "speaker-" (inc position) "-")
        value #(get params (keyword (str prefix %)))
        speaker {:name (blank->nil (value "name"))
                 :email (people/normalize-email (value "email"))
                 :title (blank->nil (value "title"))
                 :org (blank->nil (value "org"))
                 :bio (blank->nil (value "bio"))
                 :headshot-url (blank->nil (value "headshot-url"))
                 :linkedin-url (blank->nil (value "linkedin"))
                 :sessionize-url nil
                 :role (or (blank->nil (value "role")) "Co-speaker")}]
    (when (some identity (vals (dissoc speaker :role)))
      speaker)))

(defn parse-speakers
  "Parse the primary speaker plus the optional add-another-speaker block.
   Every returned identity carries the explicit role shown on the form."
  [params]
  (cond-> [(assoc (parse-speaker params) :role "Primary speaker")]
    (parse-additional-speaker params 1)
    (conj (parse-additional-speaker params 1))))

;; --- Validation (derived from the field defs) -------------------------------

(def url-pattern
  #"^https?://(?:localhost(?::\d+)?(?:/[^\s]*)?|[^\s/$.?#][^\s]*\.[^\s]*)$")

(defn valid-headshot-url?
  "A speaker photo may be externally hosted or served by Curtain Call itself."
  [url]
  (boolean (or (re-matches url-pattern (or url ""))
               (str/starts-with? (or url "") "/images/"))))

(defn- field-schema
  "One malli entry for one field def. Required-ness, max length and the option
   list all come from the DATA, so the form and its validation can never drift."
  [{:keys [id type label required max-length options]}]
  (let [k (keyword (name id))
        t (name type)
        base (cond
               ;; :enum takes its values SPLICED, not as a nested vector — a
               ;; wrapped vector silently matches nothing.
               (= t "select")
               (into [:enum {:error/message (str label ": pick one of the listed options.")}]
                     options)

               (= t "url")
               [:re {:error/message (str label ": enter a full URL starting with http:// or https://")}
                url-pattern]

               (= t "email")
               [:re {:error/message (str label ": that doesn't look like an email address.")}
                people/email-pattern]

               ;; Length is NOT enforced here: `max-length-errors` reports it
               ;; with the count the speaker actually typed, which is the
               ;; difference between a useful message and a scolding.
               :else
               [:string {:min 1 :error/message (str label " is required.")}])]
    (if required
      [k base]
      [k {:optional true} [:maybe base]])))

(defn answers-schema
  "A malli map schema for a form's session fields."
  [form-fields]
  (into [:map {:closed false}]
        (map field-schema)
        (session-fields form-fields)))

(def speaker-max-lengths
  "The public speaker fields with bounded submit-time lengths. Public controls
   and live validation read this map so they cannot drift from the schema."
  {:name 120 :title 180 :org 180 :bio 2000})

(defn valid-speaker-email?
  "True when a nonblank public speaker email satisfies the submit-time rule."
  [email]
  (boolean (re-matches people/email-pattern (or email ""))))

(def SpeakerDraft
  "The ABOUT YOU block. Locked core fields are required; the rest are optional
   but validated when present."
  [:map {:closed false}
   [:name [:string {:min 1 :max (:name speaker-max-lengths)
                    :error/message "Your name is required."}]]
   [:email [:re {:error/message "A valid email address is required — it's how we reach you."}
            people/email-pattern]]
   [:title [:string {:min 1 :max (:title speaker-max-lengths)
                     :error/message "Your title or tagline is required."}]]
   [:org [:string {:min 1 :max (:org speaker-max-lengths)
                   :error/message "Your organization is required."}]]
   [:bio [:string {:min 1 :max (:bio speaker-max-lengths)
                   :error/message "A short bio is required."}]]
   [:headshot-url {:optional true}
    [:maybe [:fn {:error/message "Headshot must be a full URL or a Curtain Call image."}
             valid-headshot-url?]]]
   [:linkedin-url {:optional true}
    [:maybe [:re {:error/message "LinkedIn must be a full URL."} url-pattern]]]
   [:sessionize-url {:optional true}
    [:maybe [:fn {:error/message "Sessionize profile must be a Sessionize URL or username."}
             #(boolean (sessionize-import/normalize-profile-url %))]]]])

(defn max-length-errors
  "Length checks reported per field, because malli's :max message can't name the
   count the speaker actually typed."
  [form-fields answers]
  (into {}
        (keep (fn [{:keys [id label] :as f}]
                ;; The SHAPE supplies an invisible cap when the field has no
                ;; explicit one (forms/default-cap) — generous, anti-runaway,
                ;; and named only here, at the moment it is actually exceeded.
                (let [k (keyword (name id))
                      v (get answers k)
                      cap (forms/effective-cap f)]
                  (when (and cap v (> (count v) cap))
                    [k [(str label " is " (count v) " characters — we have room "
                             "for " cap ". Trim it a little.")]]))))
        (session-fields form-fields)))

(defn validation-errors
  "All server-side validation for one submission: {field [messages]} or nil.

   Answer errors are keyed by field id; speaker errors by `:speaker-<field>` so
   a form field called `bio` and the speaker's `bio` never collide. Hidden
   conditional fields contribute neither required nor shape errors."
  [form-fields answers speaker]
  (let [visible-fields (visible-session-fields form-fields answers)
        answer-errs (when-not (m/validate (answers-schema visible-fields) answers)
                      (me/humanize (m/explain (answers-schema visible-fields) answers)))
        speaker-errs (when-not (m/validate SpeakerDraft speaker)
                       (me/humanize (m/explain SpeakerDraft speaker)))
        all (merge-with into
                        (or answer-errs {})
                        (max-length-errors visible-fields answers)
                        (into {} (map (fn [[k v]] [(keyword (str "speaker-" (name k))) v]))
                              (or speaker-errs {})))]
    (when (seq all) all)))

(defn validation-errors-for-speakers
  "Validate the talk and every supplied speaker. Additional-speaker errors use
   their exact public parameter names so a 422 points at the right person."
  [form-fields answers speakers]
  (let [primary-errors (validation-errors form-fields answers (first speakers))
        additional-errors
        (->> (rest speakers)
             (map-indexed
               (fn [index speaker]
                 (let [position (+ index 2)
                       schema-errors (when-not (m/validate SpeakerDraft speaker)
                                       (me/humanize (m/explain SpeakerDraft speaker)))
                       role-errors (when-not (some #{(:role speaker)} speaker-role-options)
                                     {:role ["Choose a valid speaker role."]})]
                   (into {}
                         (map (fn [[k v]]
                                [(keyword (str "speaker-" position "-" (name k))) v]))
                         (merge schema-errors role-errors)))))
             (apply merge-with into {}))
        emails (keep :email speakers)
        duplicate-email (when (not= (count emails) (count (distinct emails)))
                          {:speaker-2-email ["Each speaker must use a different email address."]})
        all (merge-with into (or primary-errors {}) additional-errors duplicate-email)]
    (when (seq all) all)))

;; --- CFP window + cap -------------------------------------------------------

(defn cfp-state
  "Is this CFP :not-open-yet, :open, or :closed? A missing window means open —
   an organizer who didn't set dates did not mean 'reject everyone'.

   The middle clause is how \"stays closed for now\" is read back. Creating an
   event with that box ticked records a call that closes the SAME INSTANT it
   opens (events/apply-create-defaults) — no extra flag, no sentinel date, just
   a window of zero width. A window that never had any width was never open, so
   the honest word for it is :not-open-yet, and the public page says 'isn't open
   yet' rather than 'has closed'. A close date that merely fell in the past is a
   different story and still reads :closed."
  ([event] (cfp-state event (Instant/now)))
  ([event now]
   (let [{:keys [cfp-opens-at cfp-closes-at]} event]
     (cond
       (and cfp-opens-at (.isBefore now ^Instant cfp-opens-at)) :not-open-yet
       (and cfp-opens-at cfp-closes-at (= cfp-opens-at cfp-closes-at)) :not-open-yet
       (and cfp-closes-at (.isAfter now ^Instant cfp-closes-at)) :closed
       :else :open))))

(defn accepting?
  ([event] (= :open (cfp-state event)))
  ([event now] (= :open (cfp-state event now))))

(defn- demo-submission-cap [event]
  (when (and (= "on" (System/getenv "DEMO_PERSONAS"))
             (= "enterprise-ai-summit-charlotte-2026" (:slug event)))
    (when-let [raw (not-empty (str/trim (or (System/getenv "DEMO_SUBMISSION_CAP") "")))]
      (try
        (let [cap (Long/parseLong raw)]
          (when (pos? cap) cap))
        (catch NumberFormatException _
          (log/warn :invalid-demo-submission-cap :value raw)
          nil)))))

(defn submission-cap
  "Per-person cap; an explicit judge-demo value may override event settings."
  [event]
  (or (demo-submission-cap event)
      (get-in event [:settings :submissions-per-person-cap])))

(defn submission-count-for-email
  "How many talks this person has already submitted to this event."
  [event-id email]
  (let [email (people/normalize-email email)]
    (count (filter (fn [s]
                     (some #(= email (people/normalize-email (:email %)))
                           (:speakers s)))
                   (store/submissions-for-event event-id)))))

(defn cap-reached?
  [event email]
  (when-let [cap (submission-cap event)]
    (>= (submission-count-for-email (:id event) email) cap)))

;; --- Row projection ---------------------------------------------------------

(def ^:private submission-keys
  [:id :event-id :form-snapshot :answers :speakers :status :priority
   :content-status :notified-at :source :created-at])

(defn row->submission [row]
  (when row
    (cr/closed-record
      (reduce (fn [m k] (if (contains? m k) m (assoc m k nil)))
              (select-keys row submission-keys)
              submission-keys))))

(defn title-of
  "The talk title, for lists and Slack messages."
  [submission]
  (or (get-in submission [:answers :talk-title]) "(untitled)"))

(defn- event-speaker?
  [state event-id person-id]
  (or (contains? (:speaker-participations state) [event-id person-id])
      (some (fn [submission]
              (and (= event-id (:event-id submission))
                   (some #(= person-id (:person-id %))
                         (:speakers submission))))
            (vals (:submissions state)))))

(defn- assignment-speaker-snapshot
  [person position]
  {:person-id (:id person)
   :name (:name person)
   :email (:email person)
   :role "Co-speaker"
   :position position})

(defn- next-speaker-position
  [speakers]
  (inc (reduce max -1
               (map-indexed (fn [index speaker]
                              (or (:position speaker) index))
                            speakers))))

(>defn assign-speaker!
  "Assign an existing event-roster person to a session. The submitted speaker
   blocks stay frozen; a named fact drives the live assignment projection."
  [event-id submission-id person-id actor]
  [string? string? string? string? => map?]
  (let [state (store/snapshot)
        submission (get-in state [:submissions submission-id])
        effective (store/submission-by-id submission-id)
        person (get-in state [:people person-id])]
    (cond
      (nil? submission)
      (throw (ex-info "The session does not exist."
                      {:type :submission-not-found
                       :submission-id submission-id}))

      (not= event-id (:event-id submission))
      (throw (ex-info "The session does not belong to this event."
                      {:type :submission-event-mismatch
                       :event-id event-id
                       :submission-id submission-id}))

      (or (nil? person) (not (event-speaker? state event-id person-id)))
      (throw (ex-info "Choose a speaker from this event roster."
                      {:type :speaker-not-on-event-roster
                       :event-id event-id
                       :person-id person-id}))

      (some #(= person-id (:person-id %)) (:speakers effective))
      {:submission-id submission-id :person-id person-id :existing? true}

      :else
      (let [speaker (assignment-speaker-snapshot
                      person (next-speaker-position (:speakers effective)))
            fact {:type "submission.speaker-assigned"
                  :actor actor
                  :event-id event-id
                  :payload {:event-id event-id
                            :submission-id submission-id
                            :person-id person-id
                            :speaker speaker
                            :assigned-at (store/now-iso)}}]
        (store/append! fact)
        (log/info :submission-speaker-assigned
                  :event-id event-id
                  :submission-id submission-id
                  :person-id person-id
                  :actor actor)
        {:submission-id submission-id :person-id person-id :assigned? true}))))

(>defn unassign-speaker!
  "Remove a co-speaker from the live session projection. The primary submitted
   speaker cannot be removed through this workflow."
  [event-id submission-id person-id actor]
  [string? string? string? string? => map?]
  (let [state (store/snapshot)
        submission (get-in state [:submissions submission-id])
        effective (store/submission-by-id submission-id)
        speakers (:speakers effective)
        speaker (some #(when (= person-id (:person-id %)) %) speakers)]
    (cond
      (nil? submission)
      (throw (ex-info "The session does not exist."
                      {:type :submission-not-found
                       :submission-id submission-id}))

      (not= event-id (:event-id submission))
      (throw (ex-info "The session does not belong to this event."
                      {:type :submission-event-mismatch
                       :event-id event-id
                       :submission-id submission-id}))

      (nil? speaker)
      {:submission-id submission-id :person-id person-id :existing? false}

      (= person-id (:person-id (first speakers)))
      (throw (ex-info "The primary submitted speaker cannot be removed."
                      {:type :primary-speaker-required
                       :submission-id submission-id
                       :person-id person-id}))

      :else
      (let [fact {:type "submission.speaker-unassigned"
                  :actor actor
                  :event-id event-id
                  :payload {:event-id event-id
                            :submission-id submission-id
                            :person-id person-id
                            :speaker speaker
                            :unassigned-at (store/now-iso)}}]
        (store/append! fact)
        (log/info :submission-speaker-unassigned
                  :event-id event-id
                  :submission-id submission-id
                  :person-id person-id
                  :actor actor)
        {:submission-id submission-id :person-id person-id :assigned? false}))))

;; --- Organizer stewardship of an accepted talk ------------------------------
;;
;; The speaker's own edit path (portal/update-answers!) is gated on the CFP
;; window: once the call closes the speaker can no longer touch their talk. The
;; ORGANIZER managing an accepted speaker is the opposite case — this is exactly
;; the work that happens AFTER the call closes, when the program is being firmed
;; up. So this verb does NOT consult `editable?`; membership on the event is the
;; permission (enforced at the route by auth/member-of-event?), and the title
;; rides the SAME `submission.answers-updated` fact + fold as a speaker edit, so
;; the log tells one continuous story of how the title reached its final form.

(>defn update-session-title!
       "Organizer edits the SESSION TITLE of a submission. Appends
   `submission.answers-updated` — the identical fact a speaker's own edit uses,
   so there is one fold and one history — recording the before and after title.
   Returns {:ok true :submission ..} or {:ok false :errors {..}}; a blank title
   is refused (every talk needs a name) and an unchanged title is a no-op."
       ([submission-id title] [string? (? string?) => map?]
                              (update-session-title! submission-id title "organizer"))
       ([submission-id title actor]
        [string? (? string?) string? => map?]
        (let [submission (store/submission-by-id submission-id)
              trimmed (some-> title str/trim)]
          (cond
            (nil? submission)
            {:ok false :errors {:talk-title ["That submission no longer exists."]}}

            (str/blank? (str trimmed))
            {:ok false :errors {:talk-title ["A session title is required."]}}

            :else
            (let [before (get-in submission [:answers :talk-title])]
              (if (= before trimmed)
                {:ok true :submission submission :unchanged? true}
                (do
                  (store/append!
                    {:type "submission.answers-updated" :actor actor
                     :event-id (:event-id submission)
                     :payload {:submission-id submission-id
                               :changed ["talk-title"]
                               :before {:talk-title before}
                               :changes {:talk-title trimmed}
                               :at (store/now-iso)}})
                  (log/info :session-title-updated :submission-id submission-id :actor actor)
                  {:ok true :submission (store/submission-by-id submission-id)})))))))

;; --- Mutations --------------------------------------------------------------

(defn speaker-input? [value]
  (or (map? value)
      (and (vector? value) (seq value) (every? map? value))))

(defn- submitted-speaker-profile
  "The reusable person fields supplied in one CFP speaker block. Blank optional
   inputs are absent, not profile-clearing commands: a form that did not load a
   maintained value must never erase it when the talk is submitted."
  [speaker]
  (into {}
        (keep (fn [[key value]]
                (when-let [value (some-> value str str/trim not-empty)]
                  [key value])))
        {:tagline (:title speaker)
         :org (:org speaker)
         :bio (:bio speaker)
         :headshot-url (:headshot-url speaker)
         :linkedin-url (:linkedin-url speaker)}))

(defn- submitted-speaker-profile-facts
  "Seed a new identity or merge a returning speaker's supplied CFP identity into
   the same authoritative profile edited in the portal. The immutable speaker
   block on submission.created remains the historical statement."
  [event-id speaker person created? actor]
  (let [submitted-profile (submitted-speaker-profile speaker)]
    (if created?
      [{:type "person.created" :actor actor
        :event-id event-id
        :payload (assoc person :profile submitted-profile)}]
      (let [current-profile (:profile person)
            changes (into {}
                          (remove (fn [[key value]]
                                    (= value (get current-profile key))))
                          submitted-profile)]
        (if (empty? changes)
          []
          [{:type "person.profile-updated" :actor actor
            :event-id event-id
            :payload {:person-id (:id person)
                      :changed (mapv name (keys changes))
                      :before (select-keys current-profile (keys changes))
                      :changes changes
                      :at (store/now-iso)}}])))))

(>defn create-submission!
       "Record a talk submission. Seeds a new speaker's authoritative profile or
   merges a returning speaker's supplied, nonblank identity into it, then
   appends `submission.created`, whose payload carries the COMPLETE record: the
   form snapshot, answers, ordered role-labeled speaker blocks, and source.
   Sinks fire on submission.created.

   Throws ex-info {:type :cfp-closed} or {:type :cap-reached}."
       ([event answers speaker-or-speakers] [map? map? speaker-input? => map?]
                                            (create-submission! event answers speaker-or-speakers "form" "speaker"))
       ([event answers speaker-or-speakers source actor]
        [map? map? speaker-input? string? string? => map?]
        (let [speakers (if (map? speaker-or-speakers)
                         [speaker-or-speakers]
                         (vec speaker-or-speakers))
              primary (first speakers)
              form (last (store/forms-for-event (:id event)))
              form-fields (:fields form)
              email (people/normalize-email (:email primary))
              emails (mapv (comp people/normalize-email :email) speakers)]
          (when-not (accepting? event)
            (throw (ex-info "The call for speakers is not open."
                            {:type :cfp-closed :state (cfp-state event)})))
          (when (not= (count emails) (count (distinct emails)))
            (throw (ex-info "Each speaker must use a different email address."
                            {:type :duplicate-speaker-email :emails emails})))
          (when-let [capped-email (some #(when (and % (cap-reached? event %)) %)
                                        emails)]
            (throw (ex-info (str "Submission limit reached for " capped-email)
                            {:type :cap-reached :cap (submission-cap event)
                             :email capped-email})))
          (let [speaker-people (mapv (fn [speaker]
                                       [speaker
                                        (people/find-or-new
                                          (people/normalize-email (:email speaker))
                                          (:name speaker))])
                                     speakers)
                submitted-speakers
                (mapv (fn [position [speaker [person _created?]]]
                        (assoc speaker
                               :person-id (:id person)
                               :position position))
                      (range)
                      speaker-people)
                submission {:id (store/new-id)
                            :event-id (:id event)
                            ;; Rule 1: the defs travel WITH the submission, forever.
                            :form-snapshot form-fields
                            :answers answers
                            ;; The speaker block AS SUBMITTED — a snapshot, so a later
                            ;; profile edit never rewrites history on an accepted talk.
                            :speakers submitted-speakers
                            :status default-status
                            :priority false
                            :notified-at nil
                            :source source
                            :created-at (store/now-iso)}]
            (store/append-all!
              (conj
                (->> speaker-people
                     (mapcat (fn [[speaker [person created?]]]
                               (submitted-speaker-profile-facts
                                (:id event) speaker person created? actor)))
                     vec)
                {:type "submission.created" :actor actor
                 :event-id (:id event) :payload submission}))
            (let [persisted (row->submission (store/submission-by-id (:id submission)))]
              ;; Never acknowledge a write the durable event projection cannot
              ;; immediately read back. A loud failure is recoverable; a success
              ;; redirect for a missing talk is silent data loss.
              (when-not (and persisted
                             (= (:id submission) (:id persisted))
                             (= (:id event) (:event-id persisted)))
                (throw (ex-info "Submission persistence could not be confirmed."
                                {:type :submission-not-persisted
                                 :submission-id (:id submission)
                                 :event-id (:id event)})))
              ;; The confirmation a speaker actually wants: proof it arrived, the
              ;; title we recorded, and a reply-to that reaches a person.
              ;; The talk is already durable and readable. An unavailable email
              ;; outbox must not turn that committed success into a 500 that
              ;; invites the speaker to submit the same talk again.
              (try
                (mail/send!
                  {:to email
                   :subject (str "We received your talk for " (:name event))
                   :reply-to (:support-email event)
                   :body (str "Hi " (or (first (str/split (str (:name primary)) #"\s+")) "there") ",\n\n"
                              "We have your submission for " (:name event) ":\n\n"
                              "    \"" (get answers :talk-title) "\"\n\n"
                              "The programming committee reads every submission — real "
                              "people, not a filter — and you will hear from us either "
                              "way. You do not need to chase us.\n\n"
                              "If you need to change anything, reply to this note.\n\n"
                              (or (:support-email event) ""))}
                  {:event-id (:id event) :kind "submission-confirmation" :actor actor
                   :submission-id (:id submission)})
                (catch Exception e
                  (log/error e :submission-confirmation-queue-failed
                             :event-id (:id event)
                             :submission-id (:id submission)
                             :speaker-email email)))
              (log/info :submission-created :event (:slug event) :email email
                        :title (get answers :talk-title)
                        :speaker-count (count submitted-speakers)
                        :people-created (count (filter (comp second second) speaker-people)))
              persisted)))))

;; --- Queries ----------------------------------------------------------------

(defn for-event
  "Every submission for an event, newest first."
  [event-id]
  (->> (store/submissions-for-event event-id)
       reverse
       (mapv row->submission)))

(defn by-id [submission-id]
  (row->submission (store/submission-by-id submission-id)))

(defn count-for-event [event-id]
  (count (store/submissions-for-event event-id)))

(defn unique-speaker-count
  "Every distinct submitted speaker, including collaborators in any position."
  [event-id]
  (->> (store/submissions-for-event event-id)
       (mapcat :speakers)
       (keep :email)
       distinct
       count))

(comment
  (store/load!)
  (let [e (store/get-event-by-slug "enterprise-ai-summit-charlotte")]
    (cfp-state e)
    (for-event (:id e))))

;; --- Quick capture (bd d9o) -------------------------------------------------
;;
;; A good talk arrives as an email or a LinkedIn DM and dies in the inbox,
;; because getting it into the tool costs more than it is worth. So capture
;; parses NOTHING: paste it, name it if convenient, done. A messy row on the
;; board beats a perfect one that was never created.

(def captured-text-field
  "A synthetic field def so the raw paste is a first-class answer — snapshot,
   rendering and export machinery all work on it without special cases. It is
   :private, so a captured DM never appears on a public page."
  {:id :captured-text
   :type :textarea
   :label "As received"
   :private true
   :help "The message exactly as it arrived."})

(defn placeholder-email
  "When we have no email, invent a stable local one so the submission still has
   an owning identity. Never routable: nothing must ever try to mail it."
  [event]
  (str "capture+" (subs (str (random-uuid)) 0 8) "@" (:slug event) ".invalid"))

(>defn capture!
       "Create a submission on someone's behalf. Bypasses the CFP window and the
   per-person cap on purpose: an organizer capturing an email is not a speaker
   submitting, and the rules that protect the public form would only get in the
   way here."
       [event {:keys [captured-text title speaker-name speaker-email speaker-org source]} actor]
       [map? map? string? => (? map?)]
       (let [text (some-> captured-text str/trim not-empty)]
         (when text
           (let [form (last (store/forms-for-event (:id event)))
                 ;; The snapshot gains the synthetic field, so the detail view can
                 ;; render "As received" from the submission's own defs.
                 snapshot (vec (cons captured-text-field (:fields form)))
                 email (or (people/normalize-email speaker-email) (placeholder-email event))
                 nm (or (some-> speaker-name str/trim not-empty) "Unknown speaker")
                 [person created?] (people/find-or-new email nm)
                 submission {:id (store/new-id)
                             :event-id (:id event)
                             :form-snapshot snapshot
                             :answers {:captured-text text
                                       :talk-title (or (some-> title str/trim not-empty)
                                                       (str "Captured from " nm))}
                             :speakers [{:name nm
                                         :email email
                                         :org (some-> speaker-org str/trim not-empty)
                                         :person-id (:id person)
                                         :position 0}]
                             :status default-status
                             :priority false
                             :notified-at nil
                             :source (str "on-behalf-of:" (or (some-> source str/trim not-empty)
                                                              "other"))
                             :created-at (store/now-iso)}]
             (store/append-all!
               (cond-> []
                 created? (conj {:type "person.created" :actor actor
                                 :event-id (:id event) :payload person})
                 true (conj {:type "submission.created" :actor actor
                             :event-id (:id event) :payload submission})))
             (log/info :submission-captured :event (:slug event) :by actor :source source)
             ;; NO confirmation email: this is the organizer's note, not the
             ;; speaker's submission, and the speaker may not know it exists yet.
             (row->submission (store/submission-by-id (:id submission)))))))

(defn captured?
  [submission]
  (str/starts-with? (str (:source submission)) "on-behalf-of"))
