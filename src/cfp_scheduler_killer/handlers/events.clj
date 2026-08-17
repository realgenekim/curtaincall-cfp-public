(ns cfp-scheduler-killer.handlers.events
  "Web server with http-kit, reitit routing, and dev auto-reload.

   Handler convention (CLAUDE.md): every handler is a named `defn handle-*`
   referenced as `#'var` in the route table, so REPL redefinition takes effect
   without a restart."
  (:require
   [cfp-scheduler-killer.auth :as auth]
   [cfp-scheduler-killer.committees :as committees]
   [cfp-scheduler-killer.demo :as demo]
   [cfp-scheduler-killer.events :as events]
   [cfp-scheduler-killer.people :as people]
   [cfp-scheduler-killer.public-catalog :as public-catalog]
   [cfp-scheduler-killer.review-plan :as review-plan]
   [cfp-scheduler-killer.sse :as sse]
   [cfp-scheduler-killer.store :as store]
   [cfp-scheduler-killer.submissions :as submissions]
   [cfp-scheduler-killer.views.auth :as view-auth]
   [cfp-scheduler-killer.views.event-setup :as view-event-setup]
   [cfp-scheduler-killer.web.datastar :as datastar]
   [cfp-scheduler-killer.web.event :as web-event]
   [cfp-scheduler-killer.web.http :as web-http]
   [clojure.string :as str]
   [taoensso.timbre :as log])
  (:gen-class))

(defn- add-creator-as-chair!
  "Seed a brand-new conference's committee with the one person who made it.

   Load-bearing since authorization went per-event: a conference whose roster is
   empty is a conference nobody can open. Idempotent about :already-member so a
   double-submit can't 500."
  [event person]
  (when person
    (when-let [committee (first (events/committees-for-event (:id event)))]
      (try
        (committees/add-member! (:id committee)
                                {:name (:name person)
                                 :email (:email person)
                                 :role "chair"}
                                (:email person))
        (catch clojure.lang.ExceptionInfo e
          (when-not (= :already-member (:type (ex-data e))) (throw e)))))))

(defn- archive-target
  "Resolve the event a j/k/x keystroke acted on. The client posts the focused
   INDEX; the server maps it over ITS OWN active-list order — the same
   derivation the page rendered — so no server state ever rides in the POST."
  [req]
  (let [person (auth/current-person req)
        active (vec (remove :archived-at
                            (if person (events/events-for-person (:id person))
                                (events/list-events))))
        idx (try (Long/parseLong (str (get-in req [:params :idx])))
                 (catch Exception _ nil))]
    (when (and idx (< -1 idx (count active)))
      (nth active idx))))

(defonce ^{:doc "Per-person create-page drafts: {person-id {field-str value}}.
  Every draft carries a page token. Preview requests may update only the token
  that GET /events/new issued, so a late request from a successfully submitted
  page cannot resurrect stale values. Deliberately an atom, not the event log —
  an abandoned draft is not history."}
  create-drafts (atom {}))

(def ^:private create-draft-fields
  [:name :location :starts-on :ends-on :slug :cfp-state :website-url
   :cfp-closes-on :tz :support-email])

(defn- matching-create-draft
  [person-id draft-token]
  (let [draft (get @create-drafts person-id)]
    (when (and draft-token (= draft-token (:draft-token draft)))
      draft)))

(defn- ensure-create-draft!
  [person-id]
  (let [fresh-token (str (java.util.UUID/randomUUID))]
    (get (swap! create-drafts update person-id
                (fn [draft]
                  (cond-> (or draft {})
                    (nil? (:draft-token draft)) (assoc :draft-token fresh-token))))
         person-id)))

(defn- recover-create-draft
  [params draft]
  (reduce (fn [[recovered recovered-fields] field]
            (let [submitted (web-http/not-blank (get recovered field))
                  remembered (web-http/not-blank (get draft field))]
              (if (and (nil? submitted) remembered)
                [(assoc recovered field remembered) (conj recovered-fields field)]
                [recovered recovered-fields])))
          [params []]
          create-draft-fields))

(defn- create-field-state
  [params]
  (into {}
        (map (fn [field]
               [field {:present? (contains? params field)
                       :chars (count (str (or (get params field) "")))}]))
        create-draft-fields))

(defn handle-cfp-close
  "POST /api/events/:slug/cfp/close — stops new submissions immediately."
  [req]
  (let [slug (get-in req [:path-params :slug])]
    (if-let [event (events/event-by-slug slug)]
      (let [actor (or (:email (auth/current-person req)) "organizer")]
        (events/close-cfp! event actor)
        (log/info :cfp-closed :slug slug :actor actor)
        (web-http/see-other (str "/events/" slug)))
      (web-event/not-found-page slug))))

(defn handle-cfp-close-date
  "POST /api/events/:slug/cfp/close-date — the scheduled half: set or clear the
   date the call shuts. Blank clears it and the call stays open."
  [req]
  (let [slug (get-in req [:path-params :slug])]
    (if-let [event (events/event-by-slug slug)]
      (let [actor (or (:email (auth/current-person req)) "organizer")]
        (try
          (events/set-cfp-close-date! event (get-in req [:params :cfp-closes-on]) actor)
          (log/info :cfp-close-date-set :slug slug
                    :date (get-in req [:params :cfp-closes-on]))
          (catch clojure.lang.ExceptionInfo e
            (if (= :invalid-close-date (:type (ex-data e)))
              (log/info :cfp-close-date-rejected :slug slug :value (:value (ex-data e)))
              (throw e))))
        (web-http/see-other (str "/events/" slug "/settings")))
      (web-event/not-found-page slug))))

(defn handle-cfp-open
  "POST /api/events/:slug/cfp/open — a deliberate act, by a named person."
  [req]
  (let [slug (get-in req [:path-params :slug])]
    (if-let [event (events/event-by-slug slug)]
      (let [actor (or (:email (auth/current-person req)) "organizer")]
        (events/open-cfp! event actor)
        (log/info :cfp-opened :slug slug :actor actor)
        (web-http/see-other (str "/events/" slug)))
      (web-event/not-found-page slug))))

(defn handle-event-details
  "GET /events/:slug/details — the step-1 page you can COME BACK to (Gene,
   2026-08-09: 'Create and Edit Event'). Writes the pitch the public CFP
   masthead displays."
  [req]
  (let [slug (get-in req [:path-params :slug])]
    (if-let [event (events/event-by-slug slug)]
      (let [person (auth/current-person req)]
        (web-http/html-response
          (view-event-setup/event-details-page
            event {:person person
                   :editable? (auth/event-manager? person (:id event))
                   :notice (when (get-in req [:params :saved]) "Saved.")})))
      (web-event/not-found-page slug))))

(defn- committee-scope-from-params
  "Blank means the open table; otherwise preserve the organizer's track order
   while trimming and deduplicating the comma-separated list."
  [params]
  (let [tracks (->> (str/split (str (:tracks params)) #",")
                    (map str/trim)
                    (remove str/blank?)
                    distinct
                    vec)]
    (if (seq tracks)
      {:field :track :in tracks}
      {:all true})))

(defn- handle-committee-scope-save [req event]
  (let [committee-id (web-http/clean-id (get-in req [:params :committee-id]))
        committee (when committee-id (committees/committee-by-id committee-id))]
    (if-not (and committee (= (:id event) (:event-id committee)))
      (web-event/not-found-page (str "committee " (get-in req [:params :committee-id])))
      (let [actor (or (:email (auth/current-person req)) "organizer")]
        (committees/set-scope! committee-id
                               (committee-scope-from-params (:params req))
                               actor)
        (web-http/see-other (str "/events/" (:slug event) "/committee?scope=saved"))))))

(defn handle-event-details-save [req]
  (let [slug (get-in req [:path-params :slug])]
    (if-let [event (events/event-by-slug slug)]
      (if (= "committee-scope" (get-in req [:params :intent]))
        (handle-committee-scope-save req event)
        (let [p (:params req)
              nm (web-http/not-blank (:name p))
              starts (events/parse-date (:starts-on p))
              ends (events/parse-date (:ends-on p))
              tz (web-http/not-blank (:tz p))
              day-start (web-http/not-blank (:day-start p))
              day-end (web-http/not-blank (:day-end p))
              date? #(instance? java.time.LocalDate %)
              time-minutes (fn [value]
                             (when (and value (re-matches #"\d{2}:\d{2}" value))
                               (try
                                 (let [time (java.time.LocalTime/parse value)]
                                   (+ (* 60 (.getHour time)) (.getMinute time)))
                                 (catch Exception _ nil))))
              start-minute (time-minutes day-start)
              end-minute (time-minutes day-end)
              errors (cond-> {}
                       (nil? nm)
                       (assoc :name "The event needs a name — it is load-bearing everywhere.")
                       (keyword? starts) (assoc :starts-on "Not a readable date.")
                       (keyword? ends) (assoc :ends-on "Not a readable date.")
                       (and (date? starts) (date? ends)
                            (.isAfter ^java.time.LocalDate starts ^java.time.LocalDate ends))
                       (assoc :ends-on "The event ends before it starts.")
                       (and tz (not (events/valid-timezone? tz)))
                       (assoc :tz "Unknown time zone.")
                       (nil? start-minute)
                       (assoc :day-start "Enter a start time as HH:mm.")
                       (nil? end-minute)
                       (assoc :day-end "Enter an end time as HH:mm.")
                       (and start-minute end-minute (>= start-minute end-minute))
                       (assoc :day-end "The programming day must end after it starts."))]
          (if (seq errors)
            (web-http/html-response 422 (view-event-setup/event-details-page
                                          event {:person (auth/current-person req)
                                                 :errors errors :values p}))
            (let [actor (or (:email (auth/current-person req)) "organizer")]
              (events/update-event-details!
                (:id event)
                ;; Every field of the form is sent every time, so a cleared box
                ;; is a deliberate clear — present-with-nil erases. Dates ride
                ;; as ISO strings (the store's event.updated fold re-parses).
                {:name nm
                 :starts-on (some-> starts str)
                 :ends-on (some-> ends str)
                 :tz tz
                 :cfp-intro (web-http/not-blank (:cfp-intro p))
                 :location (web-http/not-blank (:location p))
                 :website-url (web-http/not-blank (:website-url p))
                 :support-email (web-http/not-blank (:support-email p))}
                actor)
              (events/set-day-hours! event day-start day-end actor)
              (web-http/see-other (str "/events/" slug "/details?saved=1"))))))
      (web-event/not-found-page slug))))

(defn handle-event-unarchive
  "POST /api/events/:slug/unarchive — Restore from the archived section."
  [req]
  (let [slug (get-in req [:path-params :slug])]
    (when-let [e (events/event-by-slug slug)]
      (events/unarchive-event! e (or (:email (auth/current-person req)) "organizer")))
    (web-http/see-other "/events")))

(defn handle-events-list [req]
  (let [person (auth/current-person req)]
    ;; Your events, not everyone's — the tenancy boundary (Gene, 2026-08-09).
    ;; The gate already refuses cross-event ACTIONS; this stops the names of
    ;; other organizers' conferences leaking into the list and the sidebar.
    (web-http/html-response (view-event-setup/events-list-page
                              (if person (events/events-for-person (:id person))
                                  (events/list-events))
                              person))))

(defn handle-default-event
  "POST /api/events/:slug/default — move this person's persistent event star."
  [req]
  (let [person (auth/current-person req)
        slug (get-in req [:path-params :slug])
        event (events/event-by-slug slug)
        visible-event-ids (when person
                            (into #{} (map :id) (events/events-for-person (:id person))))]
    (if (and person event (contains? visible-event-ids (:id event)))
      (do
        (people/set-default-event! (:id person) (:id event) (:email person))
        (events/remember-working-event! (:id person) (:id event))
        (web-http/see-other "/events"))
      (web-event/not-found-page slug))))

(defn handle-home
  "The front door (bd -7e1): the landing page, for EVERYONE — signed-in
   people are not redirected away from their own homepage (Gene, 2026-08-10,
   second ruling; the first draft shunted them to /events and that was
   'terrible'). The speaker card's 'see a live call' link is pinned HERE to
   the Enterprise AI Summit while its call is open, so unrelated test events
   can never become the homepage's judge-facing invitation."
  [req]
  (let [flagship (events/event-by-slug "enterprise-ai-summit-charlotte-2026")
        live (when (and flagship
                        (not (:archived-at flagship))
                        (= :open (submissions/cfp-state flagship)))
               flagship)]
    (web-http/html-response
      (view-auth/landing-page (when live (select-keys live [:slug :name]))
                              (auth/current-person req)
                              (demo/personas?)
                              (->> (events/event-by-slug
                                     "enterprise-ai-summit-charlotte-2026")
                                   public-catalog/public-speakers
                                   (filterv :announced?))))))

(defn- event-host [event]
  (or (when-let [committee (first (events/committees-for-event (:id event)))]
        (some->> (committees/members-for-committee (:id committee))
                 (filter #(= "chair" (:role %))) first
                 (#(select-keys % [:name :email]))))
      (let [actor (:actor (first (filter #(= "event.created" (:type %))
                                         (store/log-for-event (:id event)))))
            person (when (and actor (re-find #"@" actor)) (people/by-email actor))]
        (when person (select-keys person [:name :email])))
      {:name (some-> (:support-email event) (str/split #"@") second) :email nil}))

(defn- welcome-live-events []
  (->> (events/list-events)
       (remove :archived-at)
       (remove #(get-in % [:settings :replay?]))
       (keep (fn [event]
               (let [n (submissions/count-for-event (:id event))]
                 (when (pos? n)
                   {:name (:name event) :agenda-url (str "/agenda/" (:slug event))
                    :host (:name (event-host event))
                    :host-url (str "/organizers/" (:slug event)) :sub-count n}))))
       (sort-by :sub-count >) (take 3) vec not-empty))

(defn handle-organizer-page [req]
  (let [slug (get-in req [:path-params :slug]) event (events/event-by-slug slug)]
    (if-not event
      (web-event/not-found-page slug)
      (let [host (event-host event)
            person (when (:email host) (people/by-email (:email host)))
            row (fn [e] {:name (:name e)
                         :sub-count (submissions/count-for-event (:id e))
                         :agenda-url (str "/agenda/" (:slug e))
                         :cfp-url (when (= :open (submissions/cfp-state e))
                                    (str "/cfp/" (:slug e)))})
            hosted (if (:email host)
                     (->> (events/list-events) (remove :archived-at)
                          (remove #(get-in % [:settings :replay?]))
                          (filter #(= (:email host) (:email (event-host %)))) (mapv row))
                     [(row event)])
            current (auth/current-person req)
            self? (and current (:email host) (= (:email current) (:email host)))]
        (web-http/html-response
          (view-auth/organizer-page
            {:host (:name host) :profile (or (:profile person) {}) :events hosted
             :self? self? :self-nudge? (and self? (empty? (:profile person)))}))))))

(defn handle-welcome [req]
  (let [person (auth/current-person req)
        event-list (if person (events/events-for-person (:id person)) [])
        simulations (vec (remove :archived-at
                                 (filter #(get-in % [:settings :replay?]) event-list)))]
    (web-http/html-response
      (view-event-setup/welcome-page person (welcome-live-events) simulations))))

(defn handle-create-demo-event
  "The judge's one-click event. Same rule as a real create: whoever pressed the
   button chairs it, or nobody could get back into it."
  [req]
  (let [event (events/create-demo-event!)]
    (add-creator-as-chair! event (auth/current-person req))
    (log/info :demo-event-created :slug (:slug event))
    (web-http/see-other (str "/events/" (:slug event)))))

(defn handle-event-archive
  "POST /api/events/archive {idx} — x on the events page. Appends a fact."
  [req]
  (when-let [e (archive-target req)]
    (events/archive-event! e (or (:email (auth/current-person req)) "organizer")))
  (web-http/see-other "/events"))

(defn handle-create-event
  "Plain form POST. Valid → 303 to the new event's organizer dashboard.
   Invalid → 422 re-render of the form with server-side messages (no client JS).

   The form is three visible fields now, so the DEFAULTS matter as much as the
  input: the call's opening moment, whether it opens at all, and the support
   address all come from `events/apply-create-defaults`. They only fill blanks,
   which is why the full-field POST the drive and the older tests send still
  behaves exactly as it always did."
  [req]
  (let [submitted-params (:params req)
        person (auth/current-person req)
        draft-token (web-http/not-blank (:draft-token submitted-params))
        remembered (matching-create-draft (:id person) draft-token)
        [params recovered-fields] (recover-create-draft submitted-params remembered)
        draft (events/apply-create-defaults (events/parse-form params)
                                            (:email person))
        errors (events/validation-errors draft)]
    (when (seq recovered-fields)
      (log/warn :event-create-draft-recovered
                :fields recovered-fields
                :submitted-field-state (create-field-state submitted-params)
                :draft-field-state (create-field-state remembered)))
    (if errors
      (do (log/info :event-create-rejected
                    :fields (vec (keys errors))
                    :draft-token-current? (some? remembered)
                    :recovered-fields recovered-fields
                    :submitted-field-state (create-field-state submitted-params)
                    :draft-field-state (create-field-state remembered))
          (web-http/html-response 422 (view-event-setup/new-event-page (web-http/request-host req)
                                        ;; keep what they typed, but show the slug we derived
                                        (assoc params :slug (or (:slug draft) (:slug params)))
                                        errors
                                        (auth/current-person req))))
      (try
        (let [event (events/create-event! draft (or (:email person) "organizer"))]
          ;; Whoever creates a conference joins its Program Committee as chair —
          ;; the roster starts at exactly one person, and every other reviewer is
          ;; added deliberately, on this event only. This is also what keeps a
          ;; first-run organizer from locking themselves out: the first-run
          ;; window closes the moment an event exists, so the creator has to be
          ;; inside that first membership before it shuts.
          (add-creator-as-chair! event person)
          ;; The draft became a real event; forget the scratch copy.
          (swap! create-drafts dissoc (:id person))
          ;; Creation already installs the seed form, opens the call, and assigns
          ;; the creator as chair. Land in Mission Control, where those completed
          ;; facts and the next action (share the public link) are visible.
          (web-http/see-other (str "/events/" (:slug event))))
        (catch clojure.lang.ExceptionInfo e
          (if (= :duplicate-slug (:type (ex-data e)))
            (web-http/html-response 422 (view-event-setup/new-event-page
                                          (web-http/request-host req)
                                          (assoc params :slug (:slug draft))
                                          {:slug [(str "That URL was taken a moment ago by "
                                                       (or (events/display-name
                                                             (store/get-event-by-slug (:slug draft)))
                                                           (:slug draft))
                                                       ". Pick a different URL in More options.")]}
                                          (auth/current-person req)))
            (throw e)))))))

(defn handle-draft-pref
  "POST /api/events/draft-pref — tiny per-person create-page preferences.

   Today exactly one: whether More Options is expanded. The <details> element
   stays native (the browser owns the click), but it REPORTS its state here so
   a refresh re-renders the draft with the panel the way it was left."
  [req]
  (when-let [person (auth/current-person req)]
    (let [{:keys [more-open evdraft]} (datastar/signals req)]
      (when (matching-create-draft (:id person) (web-http/not-blank evdraft))
        (swap! create-drafts update (:id person) merge
               {:more-open? (boolean more-open)}))))
  {:status 204 :headers {} :body ""})

(defn handle-events-preview
  "POST /api/events/preview — the marquee, re-rendered from what is being typed.

   Writes nothing, not even a draft; its only store read is the slug-owner
   lookup that powers the live available/taken line. Every
   keystroke on /events/new arrives here debounced, and the answer is the same
   `view-event-setup/event-marquee` the page was first painted with, pushed down THIS
   viewer's SSE stream and nobody else's. The POST itself answers 204 — the
   server decides what the screen shows, and it says so on the stream, never in
   a response body the client would have to interpret.

   `?apply-trim=1` is the assist button: the server recomputes the same
   suggestion, applies it, pushes the corrected marquee, and then patches the
   `evname` SIGNAL so the input updates itself. No client JavaScript touches a
   value — that is the whole reason the trim is a round trip instead of a
   one-line DOM write."
  [req]
  (let [person (auth/current-person req)
        sigs   (datastar/signals req)
        trim?  (some? (get-in req [:params :apply-trim]))
        ;; The `ev*` names are the signal names the form binds (single words on
        ;; purpose — Datastar camelCases hyphens). The plain aliases are for
        ;; curl: a probe that spells them the obvious way gets a real answer
        ;; instead of a ghost marquee and a puzzling 204.
        typed  {:name      (or (:evname sigs) (:name sigs))
                :location  (or (:evloc sigs) (:location sigs))
                :starts-on (or (:evstarts sigs) (:starts-on sigs) (:starts sigs))
                :ends-on   (or (:evends sigs) (:ends-on sigs) (:ends sigs))}
        draft-token (web-http/not-blank (:evdraft sigs))
        found  (events/trim-suggestion (:name typed) (:location typed))
        typed  (cond-> typed (and trim? found) (assoc :name (:trimmed found)))
        slug   (or (web-http/not-blank (:evslug sigs)) (events/derive-slug typed))
        open?  (not= "closed" (web-http/not-blank (:evcfp sigs)))
        reach  (when person
                 (sse/person-connection-count sse/new-event-channel (:id person)))]
    (if-not person
      (log/warn :preview-push-skipped :why :no-session)
      (do
        ;; Remember what is being typed only when this preview belongs to the
        ;; currently rendered create page. A debounced request may complete
        ;; after create has redirected; its old token must not resurrect the
        ;; finished draft.
        (if (matching-create-draft (:id person) draft-token)
          (swap! create-drafts update (:id person) merge
                 (into {} (filter (comp some? val))
                       {:name (:name typed) :location (:location typed)
                        :starts-on (:starts-on typed) :ends-on (:ends-on typed)
                        :slug (web-http/not-blank (:evslug sigs))
                        :cfp-state (web-http/not-blank (:evcfp sigs))
                        :website-url (web-http/not-blank (:evweb sigs))
                        :cfp-closes-on (web-http/not-blank (:evcloses sigs))
                        :tz (web-http/not-blank (:evtz sigs))
                        :support-email (web-http/not-blank (:evsupport sigs))}))
          (log/debug :preview-draft-skipped :why :stale-or-missing-token))
        ;; A 204 that pushed to nobody is the instrument lying. Say so.
        (when (zero? reach)
          (log/warn :preview-push-no-subscriber
                    :event-id sse/new-event-channel :person-id (:id person)
                    :registrations (sse/registrations)
                    :msg "nothing is listening on this key — the marquee cannot move"))
        ;; Elements first, signals second (CLAUDE.md #9) — a signal patch that
        ;; lands before its target exists fires an effect against nothing.
        (sse/push-to-person! sse/new-event-channel (:id person) "#event-marquee"
                             #(view-event-setup/event-marquee (web-http/request-host req) typed slug
                                                              (when-not trim? found) open?))
        ;; The URL field's own line answers "is this address free?" live, from
        ;; the same derivation the marquee just used and the same owner lookup
        ;; the create-time refusal uses.
        (sse/push-to-person! sse/new-event-channel (:id person) "#slug-status"
                             #(view-event-setup/slug-status slug (events/slug-owner-display slug)))
        ;; Signals AFTER elements (CLAUDE.md #9): the ghost in the URL box
        ;; follows the derivation, so it never disagrees with the green line.
        (sse/push-signals-to-person! sse/new-event-channel (:id person)
                                     {:slugghost (or slug "eais-charlotte")})
        (when (and trim? found)
          (sse/push-signals-to-person! sse/new-event-channel (:id person)
                                       {:evname (:name typed)}))
        (log/debug :preview-pushed :reach reach :name (:name typed) :slug slug)))
    {:status 204 :headers {} :body ""}))

(defn handle-new-event [req]
  (let [person (auth/current-person req)
        draft  (ensure-create-draft! (:id person))]
    (web-http/html-response (view-event-setup/new-event-page (web-http/request-host req) draft nil person))))
