(ns cfp-scheduler-killer.handlers.public-cfp
  "Web server with http-kit, reitit routing, and dev auto-reload.

   Handler convention (CLAUDE.md): every handler is a named `defn handle-*`
   referenced as `#'var` in the route table, so REPL redefinition takes effect
   without a restart."
  (:require
   [cfp-scheduler-killer.auth :as auth]
   [cfp-scheduler-killer.domain.review-plan :as domain-review-plan]
   [cfp-scheduler-killer.events :as events]
   [cfp-scheduler-killer.forms :as forms]
   [cfp-scheduler-killer.live-validation :as live-validation]
   [cfp-scheduler-killer.mail :as mail]
   [cfp-scheduler-killer.people :as people]
   [cfp-scheduler-killer.portal :as portal]
   [cfp-scheduler-killer.public-catalog :as public-catalog]
   [cfp-scheduler-killer.review-plan :as review-plan]
   [cfp-scheduler-killer.sessionize-import :as sessionize-import]
   [cfp-scheduler-killer.sse :as sse]
   [cfp-scheduler-killer.submissions :as submissions]
   [cfp-scheduler-killer.views.form-controls :as form-controls]
   [cfp-scheduler-killer.views.format :as view-format]
   [cfp-scheduler-killer.views.live-drafts :as live-drafts]
   [cfp-scheduler-killer.views.public-cfp :as view-public-cfp]
   [cfp-scheduler-killer.views.shell :as view-shell]
   [cfp-scheduler-killer.web.http :as web-http]
   [clojure.data.json :as json]
   [clojure.string :as str]
   [datastar-live.core :as live]
   [taoensso.timbre :as log])
  (:gen-class))

(defn cfp-channel
  "The SSE key for one event's public page. A string, and every real event-id is
   a uuid, so a CFP viewer can never land in the board's fan-out (the board
   pushes #board-region to everyone watching the raw event-id)."
  [event-id]
  (str "cfp-" event-id))

(defonce ^{:doc "Per-viewer CFP drafts: {viewer-key {event-id {param value}}}.

  Fed by every debounced keystroke on /cfp/<slug>, read back by the GET, cleared
  on a successful submit. This is why killing the tab mid-abstract costs
  nothing. Deliberately an atom and NOT the event log: an abandoned draft is not
  history, and a stranger who wandered off should leave no trace in the record."}
  cfp-drafts (atom {}))

(defonce ^{:doc "Validation errors for a submitted CFP draft, keyed exactly like cfp-drafts. Presence means corrected fields revalidate live."}
  cfp-validation-errors (atom {}))

(defn- cfp-not-found [slug]
  (web-http/html-response 404 (view-shell/page-shell
                                "No such call for speakers"
                                [:div.ui.warning.message
                                 [:div.header "Nothing here"]
                                 [:p (str "There's no call for speakers at /cfp/" slug ".")]])))

(defonce ^{:doc "The last note pushed per field, per viewer and event:
  {[viewer-key event-id] {param note}}.
  Kept so a keystroke pushes only the lines that CHANGED — a twenty-question
  form would otherwise repaint twenty fragments three times a second. The
  event id is a tenant boundary: a browser may have two CFPs open at once."}
  cfp-notes-sent (atom {}))

(defn cfp-progress
  "How far along this submission is — answered vs asked, over the fields the
   speaker can actually see."
  [form-fields values]
  (let [answers (submissions/parse-answers form-fields values)
        fields (submissions/visible-session-fields form-fields answers)
        answered (count (filter (fn [field]
                                  (not (str/blank?
                                         (get answers
                                              (keyword (name (:id field)))))))
                                fields))]
    {:answered answered :total (count fields)}))

(defn cfp-refusal-message
  "Plain English for a speaker whose submission we will not take, naming the
   date wherever there is one. Never the word 'error' — nothing they did was
   wrong; they arrived outside the window."
  [event]
  (case (submissions/cfp-state event)
    :closed (str "The call for speakers closed"
                 (when-let [c (view-format/fmt-instant (:cfp-closes-at event) (:tz event))]
                   (str " on " c))
                 ", so this wasn't submitted. Nothing was recorded — please "
                 "contact the organizers if you need an exception.")
    :not-open-yet (str "The call for speakers isn't open yet, so this wasn't "
                       "submitted. Nothing was recorded — please check back, "
                       "or contact the organizers.")
    "This event isn't accepting submissions right now, so nothing was recorded."))

(defn cfp-viewer
  "Who this request is, for draft + push purposes: the signed-in person if there
   is one, else the anonymous session id. Same rule as `sse/viewer-key`, so the
   key a fragment is pushed to is always the key the stream registered under."
  [req]
  (or (:id (auth/current-person req)) (get-in req [:session :viewer-id])))

(defn- cfp-state-key
  "Every ephemeral CFP projection is isolated by browser identity AND event."
  [req event]
  [(cfp-viewer req) (:id event)])

(def ^:private draft-param?
  "The params worth remembering — the speaker's own answers, nothing else."
  (fn [k] (let [n (name k)]
            (or (str/starts-with? n "answer-")
                (str/starts-with? n "speaker-")))))

(def ^:private reusable-primary-speaker-params
  #{:speaker-name :speaker-email :speaker-title :speaker-org :speaker-bio
    :speaker-headshot-url :speaker-linkedin :speaker-sessionize-url :speaker-role})

(defn- mint-viewer-id
  "The id that stands in for an account the speaker doesn't have yet."
  [req]
  (or (get-in req [:session :viewer-id]) (str (java.util.UUID/randomUUID))))

(defn- with-viewer-session
  "Put `vid` in the response's session so the next request carries it. Merges
   onto the REQUEST's session — dropping it would sign an organizer out just for
   looking at their own public page."
  [response req vid]
  (assoc response :session (assoc (:session req) :viewer-id vid)))

(defn handle-cfp-stream
  "GET /api/cfp/:slug/stream — the public page's own SSE connection.

   A separate route from /api/sse on purpose: the gate is default-deny and
   /api/sse is organizer-only, so a stranger opening it gets a 302 to /login and
   the page would sit there with a dead stream. /api/cfp/* is already a public
   prefix, so this one is reachable by exactly the people the page is for. It
   delegates to the same `sse/handle-sse`, which registers this connection under
   the viewer key."
  [req]
  (let [slug (get-in req [:path-params :slug])
        event (events/event-by-slug slug)]
    (if-not event
      {:status 404 :headers {"Content-Type" "text/plain"} :body "no such call"}
      (sse/handle-sse (assoc-in req [:params :event-id] (cfp-channel (:id event)))))))

(defn handle-cfp-submitted
  "The confirmation page, addressable so a refresh doesn't resubmit."
  [req]
  (let [slug (get-in req [:path-params :slug])
        submission-id (web-http/clean-id (get-in req [:path-params :submission-id]))
        event (events/event-by-slug slug)
        submission (when submission-id (submissions/by-id submission-id))]
    (if (and event submission (= (:id event) (:event-id submission)))
      (let [token (web-http/clean-id (get-in req [:params :portal-token]))
            invite-queued? (not= "unavailable" (get-in req [:params :invite]))
            portal-link (when token
                          (str (web-http/request-host req) "/auth/" token
                               "?next=%2Fportal"))]
        (web-http/html-response
          (view-public-cfp/cfp-success-page
            event submission portal-link invite-queued?)))
      (cfp-not-found slug))))

(defn cfp-draft-for
  "What this viewer has typed on this event, as {param value}."
  [req event]
  (get @cfp-drafts (cfp-state-key req event) {}))

(defn- cfp-live-scope [req event]
  [(cfp-viewer req) (:slug event)])

(defn- cfp-live-projection [[viewer slug]]
  (let [event (events/event-by-slug slug)
        form (events/form-for-event (:id event))
        fields (forms/active-fields (:fields form))
        state-key [viewer (:id event)]
        values (get @cfp-drafts state-key {})
        notes (live-validation/cfp-live-notes fields values)]
    (swap! cfp-notes-sent assoc state-key notes)
    {:event event
     :fields fields
     :values values
     :notes notes
     :progress (cfp-progress fields values)
     :errors (get @cfp-validation-errors state-key)}))

;; INTENT: CFP-003
(def public-cfp-live-view
  (live/local-view
    {:id ::public-cfp-validation
     :path "/api/cfp/live"
     :scope (fn [req]
              (when-let [event (events/event-by-slug
                                 (or (get-in req [:params :event])
                                     (get-in req [:query-params "event"])))]
                (cfp-live-scope req event)))
     :render (fn [scope]
               (let [{:keys [fields values notes progress]} (cfp-live-projection scope)]
                 (view-public-cfp/cfp-live-preview
                   fields values notes progress (boolean (seq values)))))
     :signals (fn [scope]
                (let [{:keys [fields notes errors]} (cfp-live-projection scope)]
                  (json/write-str
                    (merge
                      (form-controls/validation-signal-values
                        (view-public-cfp/cfp-validation-error-keys fields)
                        errors)
                      (live-drafts/note-signal-values
                        (view-public-cfp/cfp-live-note-params fields)
                        notes)))))}))

(def handle-cfp-live
  (get-in (live/route public-cfp-live-view) [1 :get :handler]))

(defn clear-cfp-draft!
  "The talk is in the log — its answers must go, or the next submission starts
   pre-filled with the last one.

   The ABOUT YOU block deliberately STAYS. Every event here has a per-person cap
   above one, so 'submit another talk' is a normal thing to do, and making
   someone retype their bio and headshot to do it is the exact behaviour this
   product exists to replace (docs: never type your bio twice). Their answers
   are gone; who they are is not."
  [req event]
  (when-let [key (cfp-viewer req)]
    (let [state-key (cfp-state-key req event)]
      (swap! cfp-drafts update state-key
             #(into {} (remove (fn [[k _]] (str/starts-with? (name k) "answer-"))) %))
      (swap! cfp-validation-errors dissoc state-key)
      (swap! cfp-notes-sent dissoc [key (:id event)]))))

(defn- retain-cfp-speaker-draft!
  "After a successful submit, keep only the primary speaker's reusable ABOUT
   YOU values in one draft rewrite. Numbered collaborators belong to the talk
   just submitted and must not silently follow the submitter into another one."
  [viewer event params]
  (when viewer
    (let [state-key [viewer (:id event)]
          speaker-params (into {}
                               (filter (fn [[k v]]
                                         (and (contains? reusable-primary-speaker-params
                                                         (keyword (name k)))
                                              (string? v))))
                               params)]
      (swap! cfp-drafts assoc state-key speaker-params)
      (swap! cfp-notes-sent dissoc [viewer (:id event)]))))

(defn reset-cfp-draft!
  "Forget every field this anonymous viewer stashed for this event.

   This is deliberately stronger than `clear-cfp-draft!`: after a successful
   submission we retain the reusable speaker profile, while an explicit Reset
   saved data act means exactly what it says. Drafts are ephemeral session
   state, so no domain fact is appended."
  [req event]
  (when-let [key (cfp-viewer req)]
    (let [state-key (cfp-state-key req event)]
      (swap! cfp-drafts dissoc state-key)
      (swap! cfp-validation-errors dissoc state-key)
      (swap! cfp-notes-sent dissoc [key (:id event)]))))

(defn handle-cfp-draft-reset [req]
  (let [slug (get-in req [:path-params :slug])]
    (if-let [event (events/event-by-slug slug)]
      (do
        (reset-cfp-draft! req event)
        (web-http/see-other (str "/cfp/" slug)))
      {:status 404 :headers {} :body ""})))

(defn stash-cfp-draft!
  "Remember what is being typed. Blank values are kept (clearing a field is a
   thing the speaker meant), unknown params are not."
  [req event params]
  (when-let [_key (cfp-viewer req)]
    (swap! cfp-drafts update (cfp-state-key req event) merge
           (into {} (filter (fn [[k v]] (and (draft-param? k) (string? v)))) params))))

(defn render-cfp
  "Render the public page for `event`. `extra` carries the state of a rejected
   submission (values + errors) or an import result, so a round-trip never
   costs the speaker their typing.

   Three sources of prefill, weakest first: what we already know about a
   signed-in speaker (never type your bio twice), what they have typed into the
   draft stash this session, and finally whatever this particular round trip is
   carrying (a 422's params, an import result). The strongest wins per field."
  ([req event] (render-cfp req event {} 200))
  ([req event extra status]
   (let [form (events/form-for-event (:id event))
         fields (forms/active-fields (:fields form))
         person (auth/current-person req)
         vid (mint-viewer-id req)
         live-req (assoc-in req [:session :viewer-id] vid)
         profile-values (when (and person
                                   (portal/submission-for-event (:id person) (:id event)))
                          (cond-> (portal/prefill-from-profile person)
                            (not (str/blank? (get-in person [:profile :org])))
                            (assoc :speaker-org (get-in person [:profile :org]))))
         values (merge profile-values
                       (cfp-draft-for req event)
                       (:values extra))
         notes (live-validation/cfp-live-notes fields values)
         progress (cfp-progress fields values)
         live-region (conj
                       (live/scoped-region
                         public-cfp-live-view
                         (cfp-live-scope live-req event)
                         {}
                         {"event" (:slug event)})
                       (view-public-cfp/cfp-live-preview
                         fields values notes progress false))]
     (-> (web-http/html-response
           status
           (view-public-cfp/cfp-page event
                                     (merge {:state (submissions/cfp-state event)
                                             ;; RETIRED fields never reach a speaker. The filter
                                             ;; lives at the one seam where the live form is
                                             ;; read, so rendering, parsing and validation can
                                             ;; never disagree about which questions exist.
                                             :form-fields fields
                                             :cap (submissions/submission-cap event)
                                             :speakers (->> (public-catalog/public-speakers event)
                                                            (filterv :announced?))
                                             :base-url (web-http/request-host req)
                                             :presenter-visibility
                                             (review-plan/presenter-visibility-policy (:id event))
                                             :presenter-visibility-definition
                                             domain-review-plan/presenter-visibility-policy-definition}
                                            extra
                                            {:values values
                                             :notes notes
                                             :progress progress
                                             :live-region live-region
                                             ;; Only ANSWERS count as "we saved your place".
                                             ;; The speaker block survives a submit on
                                             ;; purpose, and a fresh second talk should not
                                             ;; be greeted as a resumed one.
                                             :restored? (some #(str/starts-with? (name %) "answer-")
                                                              (keys (cfp-draft-for req event)))})))
         (with-viewer-session req vid)))))

(defn handle-cfp-draft
  "POST /api/cfp/:slug/draft — stash the browser-owned form values, revalidate
   corrected fields after a rejected submit, and refresh one scoped local view.
   The refresh owns only projection HTML and validation signals; it never morphs
   an editable control."
  [req]
  (let [slug (get-in req [:path-params :slug])
        event (events/event-by-slug slug)]
    (if-not event
      {:status 404 :headers {} :body ""}
      (let [vid (mint-viewer-id req)
            req (assoc-in req [:session :viewer-id] vid)
            state-key (cfp-state-key req event)
            form (events/form-for-event (:id event))
            fields (forms/active-fields (:fields form))
            _ (stash-cfp-draft! req event (:params req))
            values (cfp-draft-for req event)]
        (when (contains? @cfp-validation-errors state-key)
          (let [answers (submissions/parse-answers fields values)
                speakers (submissions/parse-speakers values)
                errors (submissions/validation-errors-for-speakers
                         fields answers speakers)]
            (swap! cfp-validation-errors assoc state-key errors)))
        (let [reach (live/refresh! public-cfp-live-view
                                   (cfp-live-scope req event))]
          (when (zero? reach)
            (log/warn :cfp-draft-refresh-no-subscriber
                      :slug slug :viewer (cfp-viewer req)))
          (log/debug :cfp-draft-stashed
                     :slug slug :viewer (cfp-viewer req)
                     :fields (count values) :reach reach))
        (-> {:status 204 :headers {} :body ""}
            (with-viewer-session req vid))))))

(defn handle-cfp-import-live
  "POST /api/cfp/:slug/import-live — the no-reload Sessionize import.

   The game-engine version of `handle-cfp-import`: the click posts the current
   main form, and the handler stashes that snapshot before reading its state.
   This closes the debounce race while preserving typed-wins merge semantics.
   It imports, merges, stashes, and morphs #cfp-about-you down this viewer's
   stream. 204 either way; failures speak through the same block."
  [req]
  (let [slug (get-in req [:path-params :slug])
        event (events/event-by-slug slug)]
    (if-not event
      {:status 404 :headers {} :body ""}
      (let [vid (mint-viewer-id req)
            req (assoc-in req [:session :viewer-id] vid)
            key (cfp-viewer req)
            chan (cfp-channel (:id event))
            form (events/form-for-event (:id event))
            fields (forms/active-fields (:fields form))
            _ (stash-cfp-draft! req event (:params req))
            draft (cfp-draft-for req event)
            url (:speaker-sessionize-url draft)
            result (sessionize-import/import-profile url)
            msg (if (:ok result)
                  (str "Imported from Sessionize: "
                       (get-in result [:speaker :name])
                       ". Check it over — you can edit anything below.")
                  (:message result))]
        (when (:ok result)
          (let [s (:speaker result)
                filled (merge {:speaker-name (:name s)
                               :speaker-title (:tagline s)
                               :speaker-bio (:bio s)
                               :speaker-headshot-url (:headshot-url s)
                               :speaker-linkedin (:linkedin-url s)
                               :speaker-sessionize-url (:url result)}
                              (into {} (remove (comp str/blank? val)) draft))]
            (log/info :cfp-import-live-ok :slug slug :name (:name s))
            (stash-cfp-draft! req event filled)))
        (let [values (cfp-draft-for req event)
              notes (live-validation/cfp-live-notes fields values)
              reach (sse/person-connection-count chan key)]
          ;; A 204 that pushed to nobody is the instrument lying. Say so.
          (when (zero? reach)
            (log/warn :cfp-import-live-push-no-subscriber
                      :event-id chan :viewer key
                      :registrations (sse/registrations)))
          (sse/push-to-person! chan key "#cfp-about-you"
                               #(view-public-cfp/cfp-about-you event values {} notes msg))
          ;; Elements first, signals second: every ABOUT YOU control is bound,
          ;; so the server must update the browser's existing signal values
          ;; after an import rather than reaching into the DOM client-side.
          (sse/push-signals-to-person!
            chan key (view-public-cfp/cfp-signal-values values)))
        (-> {:status 204 :headers {} :body ""}
            (with-viewer-session req vid))))))

(defn handle-cfp-import
  "Sessionize import: fetch + parse their public profile, then re-render the
   WHOLE page with the fields filled in. No client JS, no partial update — the
   server decides what the page says."
  [req]
  (let [slug (get-in req [:path-params :slug])
        event (events/event-by-slug slug)]
    (if-not event
      (cfp-not-found slug)
      (let [vid (mint-viewer-id req)
            req (assoc-in req [:session :viewer-id] vid)
            params (:params req)
            url (:speaker-sessionize-url params)
            _ (stash-cfp-draft! req event params)
            result (sessionize-import/import-profile url)]
        (if (:ok result)
          (let [s (:speaker result)
                ;; Imported values are DEFAULTS: anything already typed wins,
                ;; because the speaker is the authority on their own details.
                filled (merge {:speaker-name (:name s)
                               :speaker-title (:tagline s)
                               :speaker-bio (:bio s)
                               :speaker-headshot-url (:headshot-url s)
                               :speaker-linkedin (:linkedin-url s)
                               :speaker-sessionize-url (:url result)}
                              (into {} (remove (comp str/blank? val)) params))]
            (log/info :cfp-import-ok :slug slug :name (:name s))
            ;; An import IS typing, as far as the draft stash is concerned: a
            ;; refresh after importing must not throw the imported bio away.
            (stash-cfp-draft! req event filled)
            (render-cfp req event
                        {:values filled
                         :import-message (str "Imported from Sessionize: " (:name s)
                                              ". Check it over — you can edit anything below.")}
                        200))
          (do (log/info :cfp-import-failed :slug slug :error (:error result))
              (render-cfp req event
                          {:values params :import-message (:message result)}
                          200)))))))

(defn- queue-portal-invite
  "Mint and queue one submitted speaker's private portal handoff."
  [req event submission speaker]
  (let [token (try
                (auth/mint-token! (:email speaker)
                                  (people/by-id (:person-id speaker)))
                (catch Exception e
                  (log/error e :portal-invite-preparation-failed
                             :event-id (:id event)
                             :submission-id (:id submission)
                             :speaker-email (:email speaker))
                  nil))
        portal-link (when token
                      (str (web-http/request-host req) "/auth/" token
                           "?next=%2Fportal"))
        queued?
        (if-not token
          false
          (try
            (mail/send! {:to (:email speaker)
                         :reply-to (:support-email event)
                         :subject (str "Your " (:name event) " speaker portal")
                         :body (str "Hi " (:name speaker) ",\n\n"
                                    "Your private speaker portal link:\n"
                                    portal-link "\n\nKeep this link private.")}
                        {:event-id (:id event)
                         :submission-id (:id submission)
                         :person-id (:person-id speaker)
                         :kind "portal-invite"
                         :actor "speaker"})
            true
            (catch Exception e
              (log/error e :portal-invite-queue-failed
                         :event-id (:id event)
                         :submission-id (:id submission)
                         :speaker-email (:email speaker))
              false)))]
    {:token token :queued? queued?}))

(defn handle-cfp-submit
  "Plain form POST. Valid → 303 to the confirmation page.
   Invalid / closed / over cap → 422 re-render with server-side messages."
  [req]
  (let [slug (get-in req [:path-params :slug])
        event (events/event-by-slug slug)]
    (if-not event
      (cfp-not-found slug)
      (let [vid (mint-viewer-id req)
            req (assoc-in req [:session :viewer-id] vid)
            viewer (cfp-viewer req)
            ;; The debounced draft is the server's already-received copy of this
            ;; viewer's work. A sparse final request must not erase a fully
            ;; answered proposal (Datastar posts no value for an unbound/missing
            ;; control). Current request values are stronger, including explicit
            ;; blanks, so a last-moment correction or clear still wins.
            params (merge (get @cfp-drafts [viewer (:id event)] {})
                          (:params req))
            form (events/form-for-event (:id event))
            ;; Parse and validate against the SAME set the speaker was shown —
            ;; a retired question must not be collected or demanded.
            form-fields (forms/active-fields (:fields form))
            answers (submissions/parse-answers form-fields params)
            speakers (submissions/parse-speakers
                       ;; the pasted profile URL rides along on a normal submit
                       (update params :speaker-sessionize-url
                               #(or % (:speaker-sessionize-url-carry params))))
            errors (submissions/validation-errors-for-speakers form-fields answers speakers)
            reject (fn [errs message]
                     ;; A refusal is still typing worth keeping — stash before
                     ;; re-rendering so a refresh on the 422 page is safe too.
                     (stash-cfp-draft! req event params)
                     (when errs
                       (swap! cfp-validation-errors assoc
                              (cfp-state-key req event) errs))
                     (render-cfp req event
                                 {:values params :errors errs :message message}
                                 422))]
        (cond
          ;; A refusal must LOOK like a refusal. This answered 200 with the
          ;; closed page, which reads to a script — and to a speaker who
          ;; scrolled — as if the submission had been taken. It was not: nothing
          ;; was appended. 422 plus a plain sentence naming the close date is
          ;; the honest answer, and the log still grows by zero.
          (not (submissions/accepting? event))
          (reject nil (cfp-refusal-message event))

          errors
          (do (log/info :submission-rejected :slug slug :fields (vec (keys errors)))
              (reject errors "Almost there — a few fields need attention."))

          :else
          (try
            (let [sub (submissions/create-submission! event answers speakers "form" "speaker")
                  invites (mapv #(queue-portal-invite req event sub %)
                                (:speakers sub))
                  primary-invite (first invites)
                  token (:token primary-invite)
                  primary-person-id (get-in sub [:speakers 0 :person-id])
                  invite-queued? (:queued? primary-invite)]
              ;; The ABOUT YOU half is reusable. Keep it in one draft rewrite;
              ;; a second talk starts with blank answers and a filled-in speaker.
              (retain-cfp-speaker-draft! viewer event params)
              ;; The successful browser already receives the primary speaker's
              ;; private one-time handoff below. Preserve that same authority as
              ;; an ordinary revocable session so navigating away cannot strand
              ;; the speaker. The token remains unredeemed as the emailed backup;
              ;; no public route or authentication rule is weakened.
              (let [response
                    (-> (web-http/see-other
                          (str "/cfp/" slug "/submitted/" (:id sub)
                               "?portal-token=" token
                               (when-not invite-queued?
                                 "&invite=unavailable")))
                        (with-viewer-session req vid))]
                ;; A signed-in organizer or reviewer may inspect the public form
                ;; too; submitting must never replace their identity with the
                ;; named speaker. They already have a durable session.
                (if (auth/current-person req)
                  response
                  (-> (auth/sign-in response primary-person-id)
                      ;; Keep the anonymous draft/profile key alongside the
                      ;; authenticated identity: a second talk must still start
                      ;; with this speaker's reusable profile filled in.
                      (assoc-in [:session :viewer-id] vid)))))
            (catch clojure.lang.ExceptionInfo e
              (case (:type (ex-data e))
                :cap-reached
                (let [{:keys [cap email]} (ex-data e)]
                  (reject nil (str "The submission limit of " cap
                                   " talks has already been reached for "
                                   (or email "one of the submitted speakers") ". "
                                   "Nothing was recorded — contact the organizers "
                                   "if you need an exception.")))
                ;; The window shut between the check above and the append.
                :cfp-closed
                (reject nil (cfp-refusal-message event))
                (throw e)))))))))

(defn handle-public-cfp
  "The public call-for-speakers page. No login, no account wall.

   This GET is where an anonymous speaker acquires the only identity they will
   have until they submit: a viewer id in the ring session. Everything
   per-viewer downstream — the draft stash, the SSE registration, the live
   notes — hangs off it."
  [req]
  (let [slug (get-in req [:path-params :slug])]
    (if-let [event (events/event-by-slug slug)]
      (render-cfp req event)
      (cfp-not-found slug))))
