(ns cfp-scheduler-killer.auth
  "Magic-link-lite identity for committee members.

   The rule that shapes this: **no account wall, ever, for speakers.** The
   public CFP page never asks who you are. Identity exists only so the board can
   say *whose* 4.5 that is — reviewers are named, which is the entire point of a
   conversation among trusted peers.

   Who may sign in is DERIVED, not administered: if your email is on any
   committee of any event, you can get a link. There is no user table to
   maintain, no invite to accept, no role to grant — the roster IS the
   permission (docs/design/domain-model.md).

   Tokens and sessions are SESSION STATE, not facts about the world, so they
   live in atoms and are deliberately NOT appended to the store. Restarting the
   server signs everyone out; that is correct — a login is not history."
  (:require
   [cfp-scheduler-killer.mail :as mail]
   [cfp-scheduler-killer.people :as people]
   [cfp-scheduler-killer.portal :as portal]
   [cfp-scheduler-killer.store :as store]
   [cfp-scheduler-killer.views.organizer-layout :as organizer-layout]
   [clojure.string :as str]
   [taoensso.timbre :as log]))

(def token-ttl-ms (* 24 60 60 1000))

(defonce ^{:doc "{token -> {:person-id .. :expires-at ..}}. Ephemeral by design."}
  tokens (atom {}))

(defn dev? [] (= "dev" (System/getenv "ENV")))

;; --- Who may sign in --------------------------------------------------------

(defn committee-member?
  "True when this email sits on ANY committee of ANY event."
  [email]
  (let [email (people/normalize-email email)]
    (boolean
      (when-let [person (people/by-email email)]
        (some #(= (:id person) (:person-id %))
              (vals (:memberships (store/snapshot))))))))

(defn speaker?
  "True when this email has submitted at least one talk.

   Speakers get in on the strength of having submitted — no invitation, no
   account creation, nothing to accept. They already proved who they are by
   putting work into the form."
  [email]
  (boolean
    (when-let [person (people/by-email (people/normalize-email email))]
      (portal/speaker? (:id person)))))

(defn may-sign-in?
  [email]
  (or (committee-member? email) (speaker? email)))

(defn first-run?
  "True on a brand-new instance: NO conferences exist yet.

   This is the narrow bootstrap, and the narrowing is the point. It used to key
   off 'no memberships anywhere', which had two ugly consequences: on a populated
   instance, removing the last reviewer promoted *everyone who could sign in* to
   full organizer; and a speaker on such an instance could create events. Keying
   off 'no events' can only ever be true once, at the very beginning, and it
   cannot be re-entered by deleting anything — the log always still has the
   events.

   Without SOME bootstrap a fresh install is unusable by design: organizer pages
   need a reviewer, reviewers are only added from an organizer page, so nobody
   could create the first conference. The first person through the door creates
   the first event and is auto-added as its chair (server/handle-create-event),
   which closes the window behind them."
  []
  (empty? (:events (store/snapshot))))

(defn reviewer-somewhere?
  "Does this person sit on any committee, of any conference?"
  [person]
  (boolean
    (and person
         (some #(= (:id person) (:person-id %))
               (vals (:memberships (store/snapshot)))))))

(defn organizer?
  "May this person see the organizer side AT ALL?

   Reviewer on ANY conference, OR the first-run window above. This answers 'is
   this person an organizer somewhere', which is the right question for /events
   and for CREATING a conference — and nothing else. It is emphatically NOT the
   question 'may they touch THIS conference' — see `member-of-event?`. Treating
   the two as one is the hole this file used to have: a reviewer on one event
   could read another's settings page (API token and all) and flip its
   decisions."
  [person]
  (boolean (or (reviewer-somewhere? person)
               (and person (first-run?)))))

(defn chair-somewhere?
  "Does this person chair at least one conference?"
  [person]
  (boolean
    (and person
         (some #(and (= (:id person) (:person-id %))
                     (= "chair" (some-> (:role %) name str/lower-case)))
               (vals (:memberships (store/snapshot)))))))

(defn may-create-events?
  "Conference creation is a chair act, except for the first-run bootstrap."
  [person]
  (boolean (or (chair-somewhere? person)
               (and person (first-run?)))))

(defn member-of-event?
  "Is this person on a committee OF THIS conference?

   The roster is still the permission (domain-model.md) — this only says WHICH
   roster. Committees remain 'a roster + a scope filter, never a permission
   fortress': every committee of an event has the same reach over that event.
   The boundary we draw is between conferences, not between committees."
  ([person event-id] (member-of-event? (store/snapshot) person event-id))
  ([snapshot person event-id]
   (boolean
     (when (and person event-id)
       (let [committee-ids (into #{}
                                 (comp (filter #(= event-id (:event-id %)))
                                       (map :id))
                                 (vals (:committees snapshot)))]
         (some (fn [m]
                 (and (contains? committee-ids (:committee-id m))
                      (= (:id person) (:person-id m))))
               (vals (:memberships snapshot))))))))

(defn event-manager?
  "Is this person a chair on this event? Chairs may mutate organizer surfaces."
  ([person event-id] (event-manager? (store/snapshot) person event-id))
  ([snapshot person event-id]
   (boolean
     (when (and person event-id)
       (let [committee-ids (into #{}
                                 (comp (filter #(= event-id (:event-id %)))
                                       (map :id))
                                 (vals (:committees snapshot)))]
         (some (fn [m]
                 (and (contains? committee-ids (:committee-id m))
                      (= (:id person) (:person-id m))
                      (= "chair" (:role m))))
               (vals (:memberships snapshot))))))))

(defn speaker-of-event?
  "Does this person own a submission on this event? Derived from the same
   submission speaker relationship that powers the portal."
  ([person event-id] (speaker-of-event? (store/snapshot) person event-id))
  ([snapshot person event-id]
   (boolean
     (when (and person event-id)
       (some (fn [submission]
               (and (= event-id (:event-id submission))
                    (some #(= (:id person) (:person-id %))
                          (:speakers submission))))
             (vals (:submissions snapshot)))))))

(defn- speaker-event-readable-path?
  "The private event shell a speaker may inspect for an event they speak at.
   Everything else remains default-deny; public CFP/program paths are handled
   by the public tier."
  [uri]
  (boolean
    (some #(re-matches % uri)
          [#"^/events/[^/]+$"
           #"^/events/[^/]+/details$"])))

(defn- reviewer-readable-path?
  "Event-scoped context a non-chair committee member may inspect."
  [uri]
  ;; INTENT: AUTHZ-003 — this allowlist is review context only. Organizer
  ;; workspaces such as scheduling remain in the chair branch of `gate`.
  (or (= uri "/api/sse")
      (boolean
        (some #(re-matches % uri)
              [#"^/events/[^/]+$"
               #"^/events/[^/]+/fragment$"
               #"^/events/[^/]+/details$"
               #"^/events/[^/]+/committee$"
               #"^/events/[^/]+/board$"
               #"^/events/[^/]+/board/fragment$"
               #"^/events/[^/]+/submissions/[^/]+$"
               #"^/events/[^/]+/people/[^/]+$"]))))

(defn- reviewer-write-path?
  "The event-scoped evidence and conflict actions available to reviewers."
  [uri]
  (boolean
    (some #(re-matches % uri)
          [#"^/api/events/[^/]+/board/sort$"
           #"^/api/submissions/[^/]+/(rate|comment)$"
           #"^/api/submissions/[^/]+/criteria/[^/]+/value$"
           #"^/api/submissions/[^/]+/(recuse|unrecuse)$"
           #"^/api/events/[^/]+/notice/dismiss$"])))

(defn home-path
  "Where this person belongs after signing in.

   A person can be BOTH — Gene submits talks to other people's events — so
   organizer wins: it is the view with a way to reach everything else.
   A brand-new person (open sign-up, no seats, no talks) is a POTENTIAL
   organizer per the RATIFIED model (docs/open-signup.md): they land on
   /events, whose empty state welcomes them and offers the first event —
   never the speaker-portal cul-de-sac (Gene, 2026-08-10 stranger test)."
  [person]
  (cond
    (organizer? person) "/events"
    (speaker? (:email person)) "/portal"
    ;; Brand-new person → the greeting room (Gene, 2026-08-11): /welcome
    ;; carries the doors; /events is always the table now.
    :else "/welcome"))

(defn- purge-expired! []
  (let [now (System/currentTimeMillis)]
    (swap! tokens #(into {} (remove (fn [[_ v]] (< (:expires-at v) now))) %))))

(defn- register-person!
  "Append a person for a first-run organizer. Their identity is a real fact, so
   it belongs in the log like any other."
  [email]
  (let [person (people/new-person email (first (str/split email #"@")))]
    (store/append! {:type "person.created" :actor "bootstrap" :payload person})
    person))

(defn mint-token!
  "Mint a sign-in token for an ALREADY-VETTED email — no letter, no gate.
   The caller owns the policy (a roster add IS the vetting)."
  [email person]
  (let [token (str (random-uuid))]
    (swap! tokens assoc token {:person-id (:id person)
                               :email email
                               :expires-at (+ (System/currentTimeMillis) token-ttl-ms)})
    token))

(defn send-committee-invite!
  "The invite letter (Gene, 2026-08-11): adding someone to a committee emails
   them ONE CLICK that signs them in and lands on the shared review board —
   the welcome page's promise, kept. Appends a comms fact on the event."
  [{:keys [email name event-id event-name event-slug origin invited-by]}]
  (when-let [email (people/normalize-email email)]
    (let [person (or (people/by-email email)
                     (let [p (people/new-person email (or name (first (str/split email #"@"))))]
                       (store/append! {:type "person.created"
                                       :actor "committee-invite" :payload p})
                       p))
          token (mint-token! email person)
          link (str origin "/auth/" token "?next=%2Fevents%2F" event-slug "%2Fboard")]
      (mail/send!
        {:to email
         :subject (str "You're invited to review for " event-name)
         :body (str "Hi" (when name (str " " name)) ",\n\n"
                    (or invited-by "An organizer") " added you to the review "
                    "committee for " event-name " on Curtain Call.\n\n"
                    "One click puts you on the shared review board \u2014 every "
                    "talk, every score, every comment on one page:\n\n"
                    "    " link "\n\n"
                    "The link signs you in directly. It works once and expires "
                    "in 24 hours \u2014 you can always request another at "
                    origin "/login.\n\n"
                    "\u2014 Curtain Call \u00b7 calls for papers, without the paperwork\n")}
        {:kind "committee-invite" :actor "system" :event-id event-id
         :person-id (:id person)})
      token)))

(defn issue-token!
  "Mint a login token for a committee member. Returns the token, or nil when the
   email isn't on any roster (except in first-run bootstrap, see above).

   Deliberately does NOT reveal which it was to the caller's UI — see the
   handler: an unknown email gets the same neutral response, so this endpoint
   can't be used to enumerate who is on a committee."
  ([email] (issue-token! email "https://curtaincallcfp.com"))
  ([email origin-or-options]
   (purge-expired!)
   (when-let [email (people/normalize-email email)]
     (let [{:keys [origin letter-fn context]}
           (if (map? origin-or-options)
             (merge {:origin "https://curtaincallcfp.com"} origin-or-options)
             {:origin origin-or-options})
           bootstrap? (and (first-run?) (not (may-sign-in? email)))]
       (when bootstrap?
         (log/warn :login-bootstrap-mode :email email
                   :msg "no committees exist yet — admitting the first organizer"))
       ;; dev? tier: parity with prod's OPEN Google sign-up (which dev lacks —
       ;; the OAuth redirect URI points at prod). Any email gets a person +
       ;; echoed link, so the stranger flow is walkable locally. Prod magic-link
       ;; stays roster-gated (open sign-up ships via Google only, 2026-08-10).
       (when (or bootstrap? (dev?) (may-sign-in? email))
         (let [person (or (people/by-email email)
                          (register-person! email))
               token (str (random-uuid))]
           (swap! tokens assoc token {:person-id (:id person)
                                      :email email
                                      :expires-at (+ (System/currentTimeMillis) token-ttl-ms)})
           ;; In prod the link arrives by mail; in dev it is ALSO shown on the
           ;; page, because a dev with no SMTP would otherwise be locked out of
           ;; their own app.
           (mail/send-now!
             (if letter-fn
               (letter-fn token person)
               {:to email
                :subject "Your Curtain Call sign-in link"
                :body (str "Hi,\n\n"
                           "Here is your sign-in link for Curtain Call — calls for "
                           "papers, without the paperwork:\n\n"
                           "    " origin "/auth/" token "\n\n"
                           "It works once and expires in 24 hours.\n\n"
                           "One account covers all three hats: run your own event "
                           "(a live CFP in under ten minutes), keep one speaker "
                           "profile across every conference, or review for a "
                           "committee on one shared board.\n\n"
                           "If you did not ask for this, you can ignore it — "
                           "nothing has changed.\n\n"
                           "— Curtain Call · https://curtaincallcfp.com\n")})
             (merge {:kind "magic-link" :actor "system" :person-id (:id person)}
                    context))
           (log/info :login-token-issued :email email :bootstrap bootstrap?)
           token))))))

(defn redeem-token!
  "Exchange a token for a person-id. Single use: redeeming removes it, so a link
   that leaks into a Slack channel can't be replayed."
  [token]
  (purge-expired!)
  (when-let [entry (get @tokens token)]
    (swap! tokens dissoc token)
    (when (>= (:expires-at entry) (System/currentTimeMillis))
      (log/info :login-token-redeemed :email (:email entry))
      (:person-id entry))))

;; --- Session ----------------------------------------------------------------

(defn current-person
  "The signed-in person, or nil. Signed cookies carrying a session id are
   accepted only while that event-sourced session remains active. Session maps
   without an id remain available to internal unit probes."
  [req]
  (let [{:keys [person-id session-id demo? persona-role]} (:session req)]
    (when (and person-id
               (or (nil? session-id)
                   (store/active-session? session-id person-id)))
      (cond-> (store/live-person-by-id person-id)
        demo? (assoc :demo? true :persona-role persona-role)))))

(defn signed-in? [req] (some? (current-person req)))

(defn start-session!
  "Append a revocable authenticated-session fact and return its id."
  [person-id]
  (let [session-id (store/new-id)
        person (store/person-by-id person-id)]
    (store/append! {:type "auth.session-started"
                    :actor (or (:email person) "auth")
                    :payload {:id session-id
                              :person-id person-id
                              :created-at (store/now-iso)}})
    session-id))

(defn sign-in
  "Start and attach a portable, revocable session."
  [resp person-id]
  (assoc resp :session {:person-id person-id
                        :session-id (start-session! person-id)}))

(defn sign-in-demo
  "Start a Judge Sandbox session whose demo identity survives every request."
  [resp person-id persona-role]
  (assoc resp :session {:person-id person-id
                        :session-id (start-session! person-id)
                        :demo? true
                        :persona-role persona-role}))

(defn end-session!
  "Append a revocation fact for a portable cookie session."
  [{:keys [person-id session-id]}]
  (when (and person-id session-id
             (store/active-session? session-id person-id))
    (store/append! {:type "auth.session-ended"
                    :actor (or (:email (store/person-by-id person-id)) "auth")
                    :payload {:session-id session-id
                              :person-id person-id
                              :ended-at (store/now-iso)}}))
  nil)

(defn sign-out [resp session]
  (end-session! session)
  (assoc resp :session nil))

;; --- The gate ---------------------------------------------------------------

(def public-prefixes
  "Everything a speaker or a stranger may reach without signing in. The CFP and
   its assets are public on purpose — an account wall at submission time is the
   incumbent behaviour we are replacing."
  ["/cfp" "/cfps" "/agenda" "/program" "/organizers" "/headshots" "/api/cfp" "/api/telemetry" "/api/v1/" "/login" "/api/login" "/api/demo-login" "/auth" "/logout" "/card.png" "/js/" "/vendor/" "/css/" "/images/" "/favicon.ico" "/dev/reload-check" "/manifesto"])

(defn- prefix-match?
  "Prefix matching AT A SEGMENT BOUNDARY.

   `str/starts-with?` was too generous, and on an allowlist too generous means
   open: \"/cfp\" matched \"/cfpanything\", so any future route whose name merely
   began with a public one would have been public too — reachable with no
   session at all. A prefix now covers itself and everything BELOW it, and
   nothing that merely shares its spelling.

   Prefixes written with a trailing slash (\"/js/\") are asset roots and are
   already boundary-safe, so they keep plain prefix semantics."
  [uri prefix]
  (if (str/ends-with? prefix "/")
    (str/starts-with? uri prefix)
    (or (= uri prefix)
        (str/starts-with? uri (str prefix "/")))))

(def speaker-prefixes
  "The signed-in surfaces a SPEAKER legitimately owns.

   This list is the ONLY thing standing between a speaker and the organizer
   side, because the gate below is default-deny: anything signed-in that is not
   named here requires committee membership. Adding a route does not quietly
   open it — you have to come here and say so."
  ["/portal" "/api/profile" "/logout"])

(def ^:private speaker-owned-pattern
  "A speaker acting on their OWN submission or event-scoped speaker record.
   Deliberately a pattern and not a prefix, because /api/submissions/:id/ ALSO
   carries committee verbs, while /api/events/:slug/ is otherwise entirely the
   organizer surface.

   Ownership of the specific submission or participation in the event is the
   handler's job; this only says 'a speaker may reach this exact verb at all'."
  #"^/(api/submissions/[^/]+/(answers|task|headshot|files/[^/]+/(upload|comment|download))|api/events/[^/]+/speaker-custom-values)$")

(defn speaker-path?
  "May a signed-in non-committee person reach this? Home is exact-match: as a
   prefix, \"/\" would match every URI on the site."
  [uri]
  (boolean (or (= uri "/")
               (some #(prefix-match? uri %) speaker-prefixes)
               (re-find speaker-owned-pattern uri))))

(def ^:private open-data-pattern
  "Static exports and the versioned REST API are PUBLIC surfaces. The exports
   describe only the published program (accepted + informed), and the API
   defaults to the same set — a token widens what you can read, it is not what
   makes the endpoint reachable."
  #"^/(events/[^/]+/(exports/|llms\.txt|mcp(?:/|$))|api/v1(?:/|$))")

(defn public-path?
  [uri]
  ;; \"/\" is EXACT-match public (bd -7e1: the landing page is the front
  ;; door). It must never join public-prefixes: as a trailing-slash prefix
  ;; it would match every URI on the site and open everything.
  ;; \"/llms.txt\" is the same front door for an AGENT (hg63: it redirected
  ;; home, which is the defect, not a missing feature). EXACT-match too — an
  ;; allowlist entry that is more generous than the one door it opens is a
  ;; security bug, and this door is exactly one path.
  (boolean (or (contains? #{"/" "/ping" "/llms.txt"} uri)
               (some #(prefix-match? uri %) public-prefixes)
               (re-find open-data-pattern uri))))

;; --- Which conference is this request about? --------------------------------
;;
;; Authorization needs a subject (the person) and an OBJECT. The object here is
;; always a conference, but the URL names it five different ways — by slug, or
;; through a submission, a committee or a membership id. Resolving that in ONE
;; place means a new route inherits the scoping instead of having to remember
;; it, which is the same property that makes the gate itself default-deny.

(def ^:private unscoped-paths
  "Organizer surfaces that belong to no single conference: the list of events,
   creating one, and diagnostics that filter themselves to the caller. Any
   organizer may reach these.

   An explicit set, not a pattern, because /api/events/create,
   /api/events/demo and /api/events/preview are spelled exactly like
   /api/events/:slug/… and would otherwise resolve to a conference named
   \"create\" — which does not exist, so `event-id-for-uri` would answer
   :unknown and the gate would refuse the write. The create page's live marquee
   would go silent in exactly the way a broken SSE connection looks."
  #{"/" "/events" "/events/new"
    "/api/events/create" "/api/events/demo" "/api/events/preview"
    "/api/events/draft-pref"
    "/api/replay/start-demo"
    "/dev/sse-state"
    "/portal" "/api/profile" "/logout"})

(def ^:private scope-patterns
  "[regex kind] — the first capture group names the thing, `kind` says how to
   turn it into a conference."
  [[#"^/events/([^/]+)(?:/|$)"        :slug]
   [#"^/agenda/([^/]+)(?:/|$)"        :slug]
   [#"^/cfp/([^/]+)(?:/|$)"           :slug]
   [#"^/api/cfp/([^/]+)(?:/|$)"       :slug]
   [#"^/api/v1/events/([^/]+)(?:/|$)" :slug]
   [#"^/api/events/([^/]+)(?:/|$)"    :slug]
   [#"^/api/submissions/([^/]+)(?:/|$)" :submission]
   [#"^/api/committees/([^/]+)(?:/|$)"  :committee]
   [#"^/api/memberships/([^/]+)(?:/|$)" :membership]])

(defn event-id-for-uri
  "Which conference does this URI act on?

     nil       — not conference-scoped (see `unscoped-paths`)
     <id>      — this conference
     :unknown  — it NAMES one, but no such slug/submission/committee/membership

   :unknown is deliberately a third answer rather than nil. Collapsing it into
   'unscoped' would mean a typo'd slug re-opened every route to any organizer;
   collapsing it into a refusal would turn every 404 into a 403 and make the
   organizer's mistyped URL look like a permissions problem."
  [snapshot uri]
  (when-not (contains? unscoped-paths uri)
    (letfn [(by-slug [slug] (get-in snapshot [:events slug]))
            (committee-event [cid] (get-in snapshot [:committees cid :event-id]))]
      (some (fn [[re kind]]
              (when-let [[_ id] (re-find re uri)]
                (or (case kind
                      :slug       (:id (by-slug id))
                      :submission (get-in snapshot [:submissions id :event-id])
                      :committee  (committee-event id)
                      :membership (some-> (get-in snapshot [:memberships id :committee-id])
                                          committee-event))
                    :unknown)))
            scope-patterns))))

(defn- event-id-for-request
  "Resolve the query-scoped board stream as strictly as path-scoped routes.

   The create page's `new-event` stream is deliberately unscoped: it is keyed
   again by the signed-in person, so one organizer never receives another's
   preview. Every real event stream must name an existing event and is then
   checked by `member-of-event?` in the same gate as every organizer page."
  [snapshot req]
  (if (= "/api/sse" (:uri req))
    (let [requested (get-in req [:params :event-id])]
      (cond
        (= "new-event" requested) nil
        (some #(= requested (:id %)) (vals (:events snapshot))) requested
        :else :unknown))
    (event-id-for-uri snapshot (:uri req))))

(def ^:private event-creation-paths
  #{"/events/new" "/api/events/create" "/api/events/demo"
    "/api/events/preview" "/api/events/draft-pref"})

(defn- redirect [location] {:status 302 :headers {"Location" location} :body ""})

(defn- forbidden
  "Refusing a WRITE must look nothing like performing it.

   The old gate matched organizer PAGES by URL prefix (\"/events\"), which left
   every mutation under /api/ guarded by nothing but 'is signed in'. A speaker —
   and everyone who submits a talk becomes one — could rate proposals, flip a
   talk from Accepted to Declined, and lock the schedule. Each one answered 303,
   indistinguishable from success.

   So a denied write says 403 and says why."
  ([]
   {:status 403
    :headers {"Content-Type" "text/plain; charset=utf-8"}
    :body (str "This action needs a program-committee reviewer of this event.\n"
               "If you should be one, ask an organizer to add you.\n")})
  ([req event]
   {:status 403
    :headers {"Content-Type" "text/html; charset=utf-8"}
    :body (organizer-layout/organizer-shell
            (str "Organizer or chair required — " (:name event))
            {:event event :person (current-person req) :crumb "Access required"}
            (organizer-layout/header
              "Organizer or chair required"
              "You are a reviewer of this event, but this page is reserved for its organizer or chair."
              [:a.ui.basic.button {:href (str "/events/" (:slug event) "/board")}
               "Back to Review Board"]))}))

(defn- wrong-event
  "Refusing an organizer page is a different sentence from refusing a write.

   A denied GET remains a hard 403, but renders as a real page that names the
   person's actual relationship to the event and gives them a useful way home.
   Writes retain the terse refusal and never look like success."
  [req event]
  (if (not= :get (:request-method req))
    (forbidden)
    (let [person (current-person req)
          speaker? (speaker-of-event? person (:id event))]
      {:status 403
       :headers {"Content-Type" "text/html; charset=utf-8"}
       :body (organizer-layout/organizer-shell
              (str "Access required — " (:name event))
              {:event event :person person :crumb "Access required"}
              (organizer-layout/header
               "Organizer access required"
               (if speaker?
                 "You are signed in as a speaker for this event. Your proposal, profile, and onboarding tasks are in the Speaker Portal."
                 "You are not a reviewer or organizer for this event. Your committee access belongs to a different event.")
               [:a.ui.primary.button
                {:href (if speaker? "/portal" "/events")}
                (if speaker? "Back to Speaker Portal" "Back to your events")]))})))

(declare gate)

(defn wrap-require-login
  "The gate, and it is DEFAULT-DENY — twice over.

   Three tiers, in order: public paths need no session; the speaker's own
   surfaces need only a session; and a conference-scoped URL requires
   membership **on that conference**. (A fourth 'organizer-somewhere' tier
   existed until 2026-08-10; open sign-up retired it — any session may see
   the events list and create an event.) Every route added after this was
   written inherits all of them.
   That ordering is the whole point. An allowlist of protected paths is how the
   original hole appeared: organizer pages sat under /events and were gated,
   while the mutations that actually change decisions sat under /api and were
   not. Naming what is OPEN cannot fail that way.

   The speaker tier is checked BEFORE the organizer tier because a person is
   frequently both — Gene chairs one conference and submits to another. Their
   portal and their own submission must not depend on which committees they sit
   on. Ownership of the specific submission is still the handler's job.

   The second default-deny is the conference: `event-id-for-uri` returns nil
   only for the handful of routes that genuinely belong to no conference, so a
   new /api/events/:slug/… verb is cross-event-refused on the day it is written.
   A URL that names a conference we don't have is refused for writes and passed
   through for reads, so the router can answer the honest 404.

   A speaker who follows a link to an organizer PAGE is not scolded — they are
   sent to their portal, which is where they meant to go. A speaker who POSTs at
   a committee verb is refused outright.

   ---

   The DECISION lives in `gate`, one var away, and that separation is not
   stylistic. `create-app` composes this middleware ONCE at boot, so the
   returned closure is frozen: a running dev server kept answering with the old
   cond for an hour after the new one was written, and the drive against it
   reported six false failures. Calling a var per request means redefining
   `gate` takes effect on the next request, like every route handler in this
   codebase (CLAUDE.md, the #'var convention). A security rule you cannot
   observe changing is a security rule you cannot verify."
  [handler]
  (fn [req] (gate handler req)))

(defn gate
  "One request against the four tiers. See `wrap-require-login` for the why."
  [handler req]
  (let [uri (:uri req)
        person (current-person req)
        get? (= :get (:request-method req))
        ;; Delay the fold for the overwhelmingly common public-path case. A
        ;; missing event-shaped GET must be classified before the stranger
        ;; redirect, though: nonexistent data cannot require authorization.
        snapshot (delay (store/snapshot))
        scope (delay (event-id-for-request @snapshot req))]
    (cond
      (public-path? uri) (handler req)

      ;; An event-shaped typo is not a protected page. Let the real handler
      ;; render its honest 404 for strangers and signed-in people alike. SSE
      ;; is the exception: an invented stream id is refused before allocation.
      (and get? (not= uri "/api/sse") (= :unknown @scope))
      (handler req)

      (nil? person)
      (redirect "/") ;; Strangers land on the LANDING (Gene, 2026-08-11:
      ;; "it should go here") — the page that sells, not a login dead-end.
      ;; The landing's own sign-in links carry ?next= where it matters.

      (speaker-path? uri) (handler req)

      (and (contains? event-creation-paths uri)
           (not (may-create-events? person)))
      (if get?
        (redirect (if (organizer? person) "/events" "/portal"))
        (forbidden))

      ;; There is deliberately NO "organizer-somewhere" tier here anymore.
      ;; Open sign-up (docs/open-signup.md RATIFIED model, Gene 2026-08-10):
      ;; every signed-in person may see the events list and create an event —
      ;; creating one seats them as its chair. The fortress this tier once
      ;; provided lives in the per-conference check below, which is the wall
      ;; that actually matters: membership ON THAT conference. (The stranger
      ;; test found the old tier bouncing newcomers /events → /portal forever.)

      :else
      (let [snapshot @snapshot
            scope @scope]
        (cond
          ;; Not about one conference (the events list, creating one, or the
          ;; per-person create-page SSE pseudo-channel). Open to any session.
          (nil? scope) (handler req)

          ;; Unlike ordinary GET pages there is no useful router 404 behind an
          ;; invalid SSE id: the handler would open a permanent stream under
          ;; nil or invented data. Refuse before allocating the connection.
          (and (= uri "/api/sse") (= :unknown scope)) (forbidden)

          (= :unknown scope)
          (if get?
            (handler req)               ; let the router answer 404 honestly
            (forbidden))

          (and (member-of-event? snapshot person scope)
               (or (event-manager? snapshot person scope)
                   (and get? (reviewer-readable-path? uri))
                   (and (not get?) (reviewer-write-path? uri))))
          (handler req)

          (member-of-event? snapshot person scope)
          (do (log/info :authz-event-capability-refused
                        :person-id (:id person) :uri uri
                        :method (:request-method req) :event-id scope)
              (forbidden req (some #(when (= scope (:id %)) %)
                                   (vals (:events snapshot)))))

          (and get?
               (speaker-of-event? snapshot person scope)
               (speaker-event-readable-path? uri))
          (handler req)

          :else
          (do (log/info :authz-cross-event-refused
                        :person-id (:id person) :uri uri
                        :method (:request-method req) :event-id scope)
              (wrong-event req (some #(when (= scope (:id %)) %)
                                     (vals (:events snapshot))))))))))
