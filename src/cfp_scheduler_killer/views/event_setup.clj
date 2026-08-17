(ns cfp-scheduler-killer.views.event-setup
  "Event listing, creation, and editing views."
  (:require
   [cfp-scheduler-killer.demo :as demo]
   [cfp-scheduler-killer.events :as events]
   [cfp-scheduler-killer.submissions :as submissions]
   [cfp-scheduler-killer.views.form-controls :as form-controls]
   [cfp-scheduler-killer.views.format :as format]
   [cfp-scheduler-killer.views.organizer-layout :as organizer-layout]
   [clojure.string :as str]
   [datastar-kit.ds :as ds]))

;; House defaults for a brand-new create page. Ghosted text can carry a real
;; example (Tab accepts it — see resources/public/js/ghost-fill.js), but a date
;; input has no placeholder to ghost, so the two dates are prefilled outright
;; and the marquee shows them on the FIRST paint rather than after a keystroke.
;; Nothing here is forced: they are ordinary editable values, and a POST always
;; carries whatever the browser actually holds.
(defn event-date-defaults
  "Moving house defaults for a fresh create page: two months from `today`,
   with a one-day event span."
  [^java.time.LocalDate today]
  (let [start (.plusMonths today 2)]
    {:starts-on (str start)
     :ends-on (str (.plusDays start 1))}))

(defn speaker-event-overview-page
  "A speaker-safe event room: public event facts plus doors into this person's
   own participation. Committee workflow and private event activity never enter
   this projection."
  [event person submission]
  (let [slug (:slug event)
        submission-id (:id submission)]
    (organizer-layout/organizer-shell
      (str (:name event) " — My participation")
      {:event event :active :dashboard :person person}
      (organizer-layout/header
        (:name event)
        (str/join " · "
                  (remove str/blank?
                          [(events/display-dates (:starts-on event) (:ends-on event))
                           (str (:location event))])))
      [:div.ui.segment
       [:h2.ui.header "You’re part of the program"]
       [:p "Everything here is scoped to your own proposal and public event information."]
       [:div.ui.buttons
        [:a.ui.primary.button {:href (str "/portal#proposal-" submission-id)}
         "Open my proposal"]
        [:a.ui.basic.button {:href (str "/agenda/" slug)} "See the public agenda"]]])))

(defn event-details-page
  "Every fact from Create event, revisitable (Gene, 2026-08-09: 'it shouldn't
   be a subset'). Each fact is either editable or VISIBLY locked with its
   reason: the slug never changes (permalinks + calendar UIDs), and the call's
   open/close is a deliberate act with its own controls on the dashboard. The
   pitch is the headline: it renders atop the public CFP masthead."
  [event {:keys [person notice errors values editable?]}]
  (let [hours (events/day-hours event)
        editable? (not= false editable?)
        ;; hours BEFORE event: the event record is a closed record and throws
        ;; on :day-start/:day-end (they live in settings, read via day-hours).
        v (fn [k] (str (or (get values k) (get hours k) (get event k) "")))
        err (fn [k] (when-let [e (get errors k)]
                      [:div.field-hint.slug-bad e]))]
    (organizer-layout/organizer-shell
      (str "Event details — " (:name event))
      {:event event :active :details :person person :crumb "Event details"}
      (organizer-layout/header "Event details"
                               (if editable?
                                 "Everything you said at create, revisitable — editable anytime."
                                 "The conference facts and current review policy — view only."))
      (when (and editable? notice)
        ;; The save toast — THE standard save confirmation for every edit form
        ;; (Gene, 2026-08-09): server-rendered on the post-save reload,
        ;; CSS-only lifecycle. Reuse .toast wherever a form 303s with ?saved=1.
        [:div.toast {} notice])
      [:div.ui.segment.fb-card
       [:form.ui.form {:id "details-form"
                       :method "post"
                       :action (str "/api/events/" (:slug event) "/details")
                       ;; Cmd-S / Ctrl-S saves (Gene, 2026-08-09; fixed
                       ;; 2026-08-11: `el` is undefined in window-scoped
                       ;; expressions in this build — silent no-op). Explicit
                       ;; id + requestSubmit = the same browser-owned submit
                       ;; the button does.
                       :data-star-on:keydown__window
                       (ds/on-meta "s" "document.getElementById('details-form').requestSubmit()")}
        [:fieldset.read-only-fields {:disabled (not editable?)}
         [:div.field
          [:label "Sell the conference "
           [:span.optional "(shown at the top of the public CFP page)"]]
          [:textarea {:name "cfp-intro" :rows 6 :class "prose-deep"
                      :placeholder (str "Two days with the leaders actually rewiring "
                                        "their enterprises with AI — real numbers, "
                                        "real scars, no vendor decks.")}
           (v :cfp-intro)]]
         [:div.field {:class (when (:name errors) "error")}
          [:label "Event name" (form-controls/req-mark true)]
          [:input {:type "text" :name "name" :value (v :name)}]
          (err :name)]
         [:div.two.fields
          [:div.field {:class (when (:starts-on errors) "error")}
           [:label "Starts"]
           [:input {:type "date" :name "starts-on" :value (v :starts-on)}]
           (err :starts-on)]
          [:div.field {:class (when (:ends-on errors) "error")}
           [:label "Ends"]
           [:input {:type "date" :name "ends-on" :value (v :ends-on)}]
           (err :ends-on)]]
         ;; Programming-day bounds (Gene ratified 2026-08-11): a property of the
         ;; day — powers the agenda frame, slot math, and empty-day rendering.
         [:div.two.fields
          [:div.field {:class (when (:day-start errors) "error")}
           [:label "Programming day starts"]
           [:input {:type "time" :name "day-start" :value (v :day-start)}]
           (err :day-start)]
          [:div.field {:class (when (:day-end errors) "error")}
           [:label "Programming day ends"]
           [:input {:type "time" :name "day-end" :value (v :day-end)}]
           (err :day-end)
           [:div.field-hint "The frame each agenda day renders inside — 9:00 to 5:00 unless you say otherwise."]]]
         [:div.two.fields
          [:div.field
           [:label "Location"]
           [:input {:type "text" :name "location" :value (v :location)}]]
          [:div.field {:class (when (:tz errors) "error")}
           [:label "Time zone"]
           [:select {:name "tz"}
            (for [z events/common-timezones]
              [:option (cond-> {:value z}
                         (= z (or (get values :tz) (:tz event))) (assoc :selected true))
               z])]
           (err :tz)
           [:div.field-hint "Changing the zone reinterprets every stored "
            "wall-clock time — fine before submissions, careful after."]]]
         [:div.two.fields
          [:div.field
           [:label "Event website"]
           [:input {:type "url" :name "website-url" :value (v :website-url)}]]
          [:div.field
           [:label "Speaker support email"]
           [:input {:type "email" :name "support-email" :value (v :support-email)}]]]
         ;; UNLISTED (Gene, 2026-08-11): an unlisted event is indistinguishable
         ;; from no event — the public CFP and program pages 404 for everyone
         ;; but the committee. Rides the main save, so Cmd-S covers it.
         [:div.field
          [:div.ui.checkbox
           [:input {:type "checkbox" :name "unlisted" :value "1"
                    :checked (boolean (events/unlisted? event))}]
           [:label "Unlisted — the public CFP and program pages return 404"]]
          [:div.field-hint "Committee members still see the pages while signed in, "
           "so you can proof before listing — open a private window to see what "
           "the public sees."]]
         [:div.field
          [:label "Public address " [:span.optional "(permanent)"]]
          [:div.field-hint [:span.cfp-url "/cfp/" (:slug event)]
           " — permalinks and calendar UIDs are woven into it, so it never changes."]]]
        (when editable?
          [:button.ui.small.primary.button {:type "submit"} "Save details"])
        [:a.ui.small.basic.button {:href (str "/cfp/" (:slug event))
                                   :target "_blank" :rel "noopener"}
         "See the public page →"]]]
      [:div.field-hint
       "The call's open/close controls live on the "
       [:a {:href (str "/events/" (:slug event))} "dashboard"] "."])))

(defn event-marquee
  "`draft` is {:name :location :starts-on :ends-on} of raw typed strings.
   `slug` is what the URL will be. `suggestion` is an optional
   events/trim-suggestion. `cfp-open?` colours one line of the caption."
  [host draft slug suggestion cfp-open?]
  [:div#event-marquee.marquee
   (if-let [display (events/display-name draft)]
     [:h1.marquee-title [:span.marquee-mark display]]
     [:h1.marquee-title.ghost [:span "Your event…"]])
   [:div.marquee-caption
    "↳ how your event appears everywhere: public page · invites · emails"]
   ;; Never a degenerate address. With no name there is no slug, and the line
   ;; says so in the same ghost idiom as the headline rather than inventing
   ;; something like /cfp/2026 out of the dates.
   (if-let [s (format/not-blank slug)]
     [:div.marquee-url
      [:span.url-text (str host "/cfp/" s)]
      ;; Clipboard is one of the few things the browser owns (global CLAUDE.md)
      ;; — this is the sanctioned inline-JS shape, via the ds helper.
      [:button.copy-url {:type "button" :title "Copy the public CFP URL"
                         :data-star-on:click
                         (ds/copy-nearest-text ".marquee-url" ".url-text"
                                               "Copied to clipboard")}
       "⧉ copy"]]
     [:div.marquee-url.ghost host "/cfp/" [:span "your-event-name…"]])
   [:div.marquee-caption
    (if cfp-open?
      "The call for speakers opens the moment you create it."
      "The call for speakers will stay closed until you open it.")]
   (when suggestion
     ;; One quiet line, never a modal, and never automatic. The button posts
     ;; back to the same preview endpoint with ?apply-trim=1 — the SERVER does
     ;; the trimming and pushes the corrected name down as a signal patch, so
     ;; no JavaScript anywhere touches the input's value.
     [:div.marquee-assist
      "Your name seems to include dates/location — we add those automatically. "
      [:button {:type "button"
                :data-star-on:click "@post('/api/events/preview?apply-trim=1')"}
       (str "Use “" (:trimmed suggestion) "”")]])])

(defn- sims-shelf
  "Replay demos are SIMULATIONS, not events you run (Gene, 2026-08-11) —
   they collapse into this quiet shelf, never the main table."
  [sims]
  (when (seq sims)
    [:details.ev-sims {}
     [:summary (str "Simulated events (" (count sims)
                    ") — scratch events from the replay demo; nothing in them is real")]
     (for [e sims]
       [:div.ev-archived-row {}
        [:span.ev-archived-name (:name e)
         [:span.field-hint {:style "display:inline; margin-left:0.6em;"} (:slug e)]]
        [:a.ui.mini.basic.button {:href (str "/events/" (:slug e) "/board")} "Open →"]])]))

(defn judge-sandbox-orientation [person]
  (when (:demo? person)
    (case (or (:persona-role person)
              (demo/persona-role (:email person)))
      "organizer"
      [:aside.wh-sandbox-orientation
       [:div.wh-sandbox-k "Judge Sandbox"]
       [:p [:strong "Hello, swyx (you are an amazing conference organizer). "]
        "This tool is designed to make it easier for you and your team to run "
        "a great process for submission reviews (especially for the reviewers "
        "and chair), speaker onboarding, and, to a certain extent, scheduling."]
       [:p "I've loved organizing conferences for 12 years. For your Judge "
        "Sandbox, I'm giving you a slightly adjusted copy of the upcoming "
        "October Enterprise AI Summit: a real event with real speakers and "
        "a large set of fake submissions."]
       [:p "I don't run a blind review process, but this sandbox starts blind "
        "so you can inspect that workflow. You can also disable blind review "
        "from Event details. (See "
        [:a {:href "/manifesto"} "my manifesto"]
        " of how I want the review process to feel for everyone.)"]
       [:p [:strong "Start with The call → Event details "]
        "in the left rail to see how it all works."]
       [:p "Return to Sign in whenever you want to switch to Maya (reviewer) "
        "or Amara (speaker) and see the other side. Everything here is "
        "isolated; no real email is sent."]]

      "reviewer"
      [:aside.wh-sandbox-orientation
       [:div.wh-sandbox-k "Judge Sandbox"]
       [:p [:strong "Hello, Maya (you are reviewing Enterprise AI Summit). "]
        "You are a member of its programming committee, helping turn 500 "
        "proposals into a conference program."]
       [:p "This is a slightly adjusted copy of a real upcoming event, with "
        "real event details and speakers, plus a large set of fake submissions. "
        "The sandbox starts blind, so presenter identities stay hidden while "
        "you read, rate, comment, and join the committee conversation."]
       [:p [:strong "Start with Review CFP proposals → Review Board "]
        "in the left rail. Event details, the committee, and the public CFP "
        "give you context; The show lets you inspect what the audience will see."]
       [:p "Organizer controls are not yours for this event, so you will not "
        "see CFP editing, decisions, speaker operations, or administration. "
        "Return to Sign in to switch to swyx (organizer) or Amara (speaker). "
        "Everything here is isolated; no real email is sent."]]

      "speaker"
      [:aside.wh-sandbox-orientation
       [:div.wh-sandbox-k "Judge Sandbox"]
       [:p [:strong "Hello, Amara (you are speaking at Enterprise AI Summit). "]
        "This view follows your real proposal, speaker profile, onboarding work, "
        "and published program context — never an invented demo identity."]
       [:p "The event switcher includes every conference where you organize, "
        "review, or speak. Organizer and committee controls stay out of sight "
        "and are refused at their direct URLs too."]
       [:p [:strong "Start with My participation → My proposal "]
        "in the left rail. From there you can maintain your proposal and profile, "
        "complete onboarding tasks, and follow your session into the public show."]
       [:p "Return to Sign in to switch to swyx (organizer) or Maya (reviewer). "
        "Everything here is isolated; no real email is sent."]]

      nil)))

(defn welcome-page
  "THE GREETING ROOM (Gene, 2026-08-11: welcome and the events table are two
   different rooms) — /welcome carries the doors, the live ticker and the
   promises; /events is ALWAYS the table now. Newcomers land here after
   sign-in; the ghost rail renders beside it for people with no real events."
  [person live sims]
  (organizer-layout/organizer-shell
    "Welcome — Curtain Call"
    {:active :events :person person}
    [:div.welcome-hero
     [:h2.wh-greeting (str "Welcome, "
                           (or (some-> (:name person) (str/split #" ") first)
                               "friend")
                           " 👋")]
     (list
       [:p.wh-thesis "Whether you're an organizer, a speaker, or a reviewer — we're glad you're here."]
       (judge-sandbox-orientation person))
     [:div.wh-hat
      [:div.wh-hat-label "Running an event?"]
      [:p "Create it here and you get a Program Committee, a seeded CFP "
       "form, and a public call-for-speakers URL — live in minutes, "
       "not days."]
      [:a.ui.primary.button {:href "/events/new"} "Create your first event →"]]
     (list
       [:div.wh-hat
        [:div.wh-hat-label "Speaking at one?"]
        [:p "One profile, every conference — your bio, headshot, and links "
         "follow you, so you never type them twice."]
        [:div.wh-doors
         [:a {:href "/portal"} "Update your speaker profile →"]
         [:a {:href "/portal"} "Your submissions & their statuses →"]]]
       [:div.wh-hat
        [:div.wh-hat-label "Reviewing for a committee?"]
        [:p "Organizers invite you — one click from the invite email lands "
         "you on the shared board: every score and comment in one "
         "conversation. Want to feel it first?"]
        [:form.wh-demo-door {:method "post"
                             :action "/api/replay/start-demo?then=board"}
         [:button.wh-linklike {:type "submit"} "See a live review board →"]]])
     (when (seq live)
       (list
         [:div.wh-live-k "Live on Curtain Call right now"]
         [:div.wh-ticker
          (for [ev live]
            [:div.wh-trow
             [:b (:name ev)]
             (when (:host ev)
               [:a.wh-host {:href (:host-url ev)} (:host ev)])
             [:span.wh-dots]
             [:span.wh-n (str (:sub-count ev) " submissions")]
             [:a {:href (:agenda-url ev)} "agenda →"]])]))
     (list
       [:div.wh-promise
        [:div.wh-promise-k "Why we built this"]
        [:p "24 conferences, 12 years, five tools we hated. This is the "
         "one we always wanted. "
         [:a {:href "/manifesto"} "Read the whole story →"]]]
       [:div.wh-promise
        [:div.wh-promise-k "Our promise to you"]
        [:p "Nothing you write here is ever lost, and nothing is ever "
         "deleted. The whole story of your call, kept."]])
     (sims-shelf sims)]))

(defn events-list-page
  "ALWAYS the table (Gene, 2026-08-11) — the greeting moved to /welcome.
   j/k walk a focus ring down the ACTIVE rows (Datastar signals — 0ms, no
   round trip); x archives the focused event — an appended FACT, never a
   deletion (Gene, 2026-08-10). Simulations and archived events collapse
   into shelves at the bottom."
  [evts person]
  (let [sims (vec (remove :archived-at (filter #(get-in % [:settings :replay?]) evts)))
        real-evts (vec (remove #(get-in % [:settings :replay?]) evts))
        active (vec (remove :archived-at real-evts))
        working-event-id (some-> (events/working-event (some-> person :id)
                                                       (:default-event-id person)
                                                       active)
                                 :id)
        archived (vec (filter :archived-at real-evts))
        manages-any? (boolean (some #(organizer-layout/event-manager? % person) active))
        max-idx (max 0 (dec (count active)))
        ;; x posts the focused INDEX; the server maps it over its own list
        ;; order. The reload after the write is delivery, not rendering — the
        ;; server decides everything on the repaint (/events has no SSE mount).
        x-act (str (ds/post-action* "/api/events/archive" {:idx (ds/js "$evIdx")})
                   ".then(()=>location.reload())")]
    (organizer-layout/organizer-shell
      "Events — CFP Scheduler Killer"
      {:active :events :person person}
      ;; No header buttons (Gene, 2026-08-11: "get rid of the AIE replay demo
      ;; and the create event") — the rail carries every act.
      (organizer-layout/header "Events" "Every event you're part of. Create one whenever you're ready to run your own.")
      [:div {:data-star-signals__ifmissing "{evIdx: 0}"
             :data-star-signals (str "{evMax: " max-idx "}")
             :data-star-on:keydown__window
             (ds/keydown-expr
               []
               (cond-> [(ds/on-key "j" {} (ds/signal-inc "$evIdx" max-idx))
                        (ds/on-key "k" {} (ds/signal-dec "$evIdx"))]
                 manages-any? (conj (ds/on-key "x" {} x-act))))}
       [:table.ui.celled.table
        [:thead
         [:tr [:th "Event"] [:th "Dates"] [:th "Call for speakers"] [:th "Time zone"] [:th ""]]]
        [:tbody
         (if (empty? active)
           [:tr [:td {:colspan 5}
                 [:span.field-hint "No events yet — "]
                 [:a {:href "/events/new"} "create your first →"]]]
           (for [[i e] (map-indexed vector active)]
             (let [resume (organizer-layout/event-resume-path e)
                   done? (organizer-layout/event-setup-done? e)
                   working? (= working-event-id (:id e))
                   default? (= (:default-event-id person) (:id e))]
               [:tr {:data-star-class:ev-focused (str "$evIdx === " i)}
                ;; The NAME goes to Event details (Gene, 2026-08-11); the
                ;; Open/Resume button keeps the dashboard/resume door.
                [:td
                 [:div.event-name-cell
                  (when person
                    [:form.event-default-form
                     {:method "post"
                      :action (str "/api/events/" (:slug e) "/default")}
                     [:button.event-default-star
                      {:type "submit"
                       :class (when working? "active")
                       :aria-label (if default?
                                     (str (:name e) " is your default event")
                                     (str "Make " (:name e) " your default event"))
                       :title (if default? "Your default event" "Make default")}
                      (if working? "★" "☆")]])
                  [:div
                   [:a {:href (str "/events/" (:slug e) "/details")} (:name e)]
                   [:div.field-hint (:slug e)]]]]
                [:td (or (format/fmt-date-range (:starts-on e) (:ends-on e))
                         [:span.field-hint "not set"])]
                [:td
                 (case (submissions/cfp-state e)
                   :open [:span.sb-opt-state.open.cfp-state-cell "CFP open"]
                   :closed [:span.sb-opt-state.cfp-state-cell "closed"]
                   :not-open-yet [:span.sb-opt-state.cfp-state-cell "not open yet"]
                   nil)
                 (or (format/fmt-cfp-window e) [:span.field-hint "not scheduled"])]
                [:td (:tz e)]
                [:td [:a.ui.tiny.basic.button {:href resume}
                      (if done? "Open" "Resume setup →")]]])))]]
       (when manages-any?
         [:div.field-hint {:style "margin-top:0.4em;"}
          [:kbd "j"] "/" [:kbd "k"] " move · " [:kbd "x"] " archive the focused event — "
          "archiving is a recorded fact, never a deletion; restore below."])]
      (sims-shelf sims)
      (when (seq archived)
        [:details.ev-archived {}
         [:summary (str "Archived (" (count archived) ")")]
         (for [e archived]
           [:div.ev-archived-row {}
            [:span.ev-archived-name (:name e)
             [:span.field-hint {:style "display:inline; margin-left:0.6em;"}
              (:slug e)]]
            [:form {:method "post"
                    :action (str "/api/events/" (:slug e) "/unarchive")}
             [:button.ui.mini.basic.button {:type "submit"} "Restore"]]])]))))

(def ^:private example-location "Charlotte, NC")

(def ^:private example-name "Enterprise AI Summit")

(def ^:private example-website "https://events.itrevolution.com/2026-charlotte/")

(defn slug-status
  "The line under the Public CFP URL field, and the reason it needs no prose:
   it ANSWERS the only question the field raises. Green when the address is
   free, red naming the event that owns it when it is not, ghost guidance when
   there is no address yet. Re-pushed by handle-events-preview on every
   keystroke, same as the marquee.

   `owner-display` is (events/slug-owner-display slug) — nil when available."
  [slug owner-display]
  (cond
    (str/blank? slug)
    [:div#slug-status.field-hint
     "Derived from the name, the city and the year — or type your own. "
     "Slugs are permanent."]

    owner-display
    [:div#slug-status.field-hint.slug-bad
     (str "✗ Taken by " owner-display " — pick another address.")]

    :else
    [:div#slug-status.field-hint.slug-ok
     (str "✓ /cfp/" slug " is available.")]))

(defn new-event-page
  "Create an event. One page, three visible fields, and a headline that writes
   itself (docs/design/domain-model.md: zero-to-open-CFP in ten minutes).

   `values` are the raw submitted strings so a rejected form comes back filled
   in; `errors` is {field [messages]} from events/validation-errors."
  ([host] (new-event-page host {} nil nil))
  ([host values errors] (new-event-page host values errors nil))
  ([host values errors person]
   (let [;; A FRESH page gets the house defaults; a re-render after a rejected
         ;; submit gets exactly what was typed, blanks included. `values` is
         ;; empty only on the first paint, which is the whole distinction.
         fresh? (empty? (dissoc values :draft-token :more-open?))
         v      #(get values % "")
         date-defaults (when fresh?
                         (event-date-defaults (java.time.LocalDate/now)))
         starts (if fresh? (:starts-on date-defaults) (v :starts-on))
         ends   (if fresh? (:ends-on date-defaults) (v :ends-on))
         err?   (seq errors)
         draft  {:name (v :name) :location (v :location)
                 :starts-on starts :ends-on ends}
         derived (events/derive-slug draft)
         slug   (or (format/not-blank (v :slug)) derived)
         open?  (not= "closed" (format/not-blank (v :cfp-state)))]
     (organizer-layout/organizer-shell
       "Create event — CFP Scheduler Killer"
       {:active :new-event :person person
        ;; The create page streams: every keystroke is answered by a re-rendered
        ;; marquee on this connection. Same hookup as the board, on the pseudo
        ;; event that belongs to nobody (sse/new-event-channel).
        :sse? true
        :body-attrs (ds/sse-mount "new-event")}
       [:div.create-page
        (when err?
          [:div.ui.negative.message
           [:div.header "We couldn't create the event"]
           [:p "Fix the fields marked below and try again."]])

        [:div.rise (event-marquee host draft slug nil open?)]

        ;; input bubbles, so ONE debounced handler on the form covers every
        ;; field in it — and Datastar posts the whole signal set, which is
        ;; exactly what the marquee needs to re-render.
        [:form.ui.form.rise.rise-1
         {:method "post" :action "/api/events/create"
          :data-star-on:input__debounce.300ms "@post('/api/events/preview')"}

          ;; Fence the ephemeral server draft to this rendered page. A preview
          ;; request may finish after a successful submit; its old token cannot
          ;; repopulate the next event's form.
         [:input (merge {:type "hidden" :name "draft-token"
                         :value (v :draft-token)}
                        (ds/bind :evdraft))]

         [:div.field {:class (when (:name errors) "error")}
          [:label "Event name"]
          [:input (merge {:type "text" :name "name" :value (v :name)
                          :placeholder example-name :autofocus true
                          :data-ghost-fill ""}
                         (ds/bind :evname))]
          [:div.field-hint "Tab accepts the example text."]
          (form-controls/field-errors errors :name)]

         [:div.two.fields
          [:div.field {:class (when (:starts-on errors) "error")}
           [:label "Event starts"]
           [:input (merge {:type "date" :name "starts-on" :value starts}
                          (ds/bind :evstarts))]
           (form-controls/field-errors errors :starts-on)]
          [:div.field {:class (when (:ends-on errors) "error")}
           [:label "Event ends"]
           [:input (merge {:type "date" :name "ends-on" :value ends}
                          (ds/bind :evends))]
           (form-controls/field-errors errors :ends-on)]]

         ;; The call-for-speakers decision is one sentence with two radios, not a
         ;; section with two timestamps. Nobody opening a CFP wants to schedule
         ;; it; they want it live, or deliberately not yet.
         [:div.cfp-choice
          [:span.lead "The call for speakers"]
          ;; Both radios bind the SAME signal — that is how Datastar reads a radio
          ;; group — so the marquee's last caption line follows the choice.
          [:label
           [:input (merge (cond-> {:type "radio" :name "cfp-state" :value "open"}
                            open? (assoc :checked true))
                          (ds/bind :evcfp))]
           "opens right away"]
          [:label
           [:input (merge (cond-> {:type "radio" :name "cfp-state" :value "closed"}
                            (not open?) (assoc :checked true))
                          (ds/bind :evcfp))]
           "stays closed for now"]
          [:div.cfp-until
           [:span "…open until"]
           [:input (merge {:type "date" :name "cfp-closes-on" :value (v :cfp-closes-on)}
                          (ds/bind :evcloses))]
           [:span "(optional — end of that day, your time zone)"]]
          (form-controls/field-errors errors :cfp-closes-at)]

         [:div.field {:class (when (:presenter-visibility-mode errors) "error")}
          [:label "Presenter identity during review"]
          [:div.ui.info.message
           [:div.header "We prefer non-blind review"]
           [:p "We prefer visible review because review is a conversation among trusted peers "
            "over a shared table. "
            [:a {:href "/manifesto"} "Read the manifesto."]]
           [:p "Evaluators and AI agents often assume blind review is the norm. Identity disclosure "
            "cannot be undone, so we require an explicit choice and silently choose neither."]]
          [:div.grouped.fields
           [:div.field
            [:div.ui.radio.checkbox
             [:input (merge (cond-> {:type "radio"
                                     :name "presenter-visibility-mode"
                                     :value "hidden"}
                              (= "hidden" (v :presenter-visibility-mode))
                              (assoc :checked true))
                            (ds/bind :evpresentervisibility))]
             [:label "Blind review — hide presenter identity throughout review"]]]
           [:div.field
            [:div.ui.radio.checkbox
             [:input (merge (cond-> {:type "radio"
                                     :name "presenter-visibility-mode"
                                     :value "visible"}
                              (= "visible" (v :presenter-visibility-mode))
                              (assoc :checked true))
                            (ds/bind :evpresentervisibility))]
             [:label "Visible review — intentionally show presenter identity to reviewers"]]]]
          (form-controls/field-errors errors :presenter-visibility-mode)]

          ;; Native <details>. Zero JavaScript for the toggle itself, keyboard-
          ;; accessible for free, and it survives an SSE morph because the
          ;; browser owns the open/closed state — a signal-driven accordion would
          ;; not. It REPORTS each toggle to the draft stash (fire-and-forget), so
          ;; a refresh re-renders the panel the way it was left.
         [:details.create-details
          (cond-> {:data-star-on:toggle
                   (ds/post-action* "/api/events/draft-pref"
                                    {:more-open (ds/js "evt.target.open")})}
            (:more-open? values) (assoc :open true))
          [:summary "More options — location, website, URL, time zone, support email"]

          [:div.two.fields
           [:div.field {:class (when (:location errors) "error")}
            [:label "Location " [:span.optional "(optional)"]]
            [:input (merge {:type "text" :name "location" :value (v :location)
                            :placeholder example-location :data-ghost-fill ""}
                           (ds/bind :evloc))]
            [:div.field-hint "Appears in the headline above, on the public pages, "
             "and as the calendar-invite location."]
            (form-controls/field-errors errors :location)]
           [:div.field {:class (when (:website-url errors) "error")}
            [:label "Event website " [:span.optional "(optional)"]]
            [:input (merge {:type "url" :name "website-url" :value (v :website-url)
                            :placeholder example-website :data-ghost-fill ""}
                           (ds/bind :evweb))]
            (form-controls/field-errors errors :website-url)]]

          [:div.field {:class (when (:slug errors) "error")}
           [:label "Public CFP URL"]
           [:div.ui.labeled.input
            [:div.ui.label.slug-prefix (str host "/cfp/")]
            ;; The placeholder is the DERIVED slug, and it follows the typing:
            ;; bound to a signal the preview handler patches after each
            ;; keystroke, so the ghost in the box can never disagree with the
            ;; green line under it. Tab still accepts it (ghost-fill).
            [:input (merge {:type "text" :name "slug" :value (v :slug)
                            :placeholder (or derived "eais-charlotte")
                            :data-ghost-fill ""
                            :data-star-signals__ifmissing
                            (str "{slugghost: '" (or derived "eais-charlotte") "'}")
                            ;; `|| fallback` because the attr binding can evaluate
                            ;; BEFORE the same element's signal registers — without
                            ;; it, the first evaluation wipes the placeholder to
                            ;; "" (found live, 2026-08-09).
                            :data-star-attr:placeholder
                            (str "$slugghost || '" (or derived "eais-charlotte") "'")}
                           (ds/bind :evslug))]
            ;; Second copy spot (Gene: "it should be in two places") — same
            ;; clipboard gesture, sourcing the full URL from the marquee's own
            ;; text so the two can never disagree.
            [:button.copy-url {:type "button" :title "Copy the public CFP URL"
                               :data-star-on:click
                               (ds/copy-nearest-text "body" ".marquee-url .url-text"
                                                     "Copied to clipboard")}
             "⧉ copy"]]
           (slug-status slug (events/slug-owner-display slug))
           (form-controls/field-errors errors :slug)]

          [:div.field {:class (when (:tz errors) "error")}
           [:label "Time zone"]
           [:select (merge {:name "tz"} (ds/bind :evtz))
            (let [selected (or (format/not-blank (v :tz)) events/default-timezone)]
              (for [tz events/common-timezones]
                [:option (cond-> {:value tz} (= tz selected) (assoc :selected true)) tz]))]
           [:div.field-hint "It's a pain to change later. Daylight savings applies "
            "automatically."]
           (form-controls/field-errors errors :tz)]

          [:div.field {:class (when (:support-email errors) "error")}
           [:label "Speaker support email"]
           [:input (merge {:type "email" :name "support-email" :value (v :support-email)
                           :placeholder "annp@itrevolution.net"
                           :data-ghost-fill ""}
                          (ds/bind :evsupport))]
           [:div.field-hint "Reply-to on every email a speaker receives. "
            "Leave blank and we use your address."]
           (form-controls/field-errors errors :support-email)]]

         [:button.btn-go {:type "submit"} "Create event & open CFP →"]]

        [:div.create-footer.rise.rise-2
         [:p [:strong "On create:"] " a Program Committee is spawned (invite people next) · "
          "the seed form is installed (edit it anytime) · your public CFP URL goes live, and "
          "you land there immediately."]
         [:p "Target: under ten minutes from here to the first submission being possible."]
         [:div.box
          [:form {:method "post" :action "/api/events/demo"}
           [:button.btn-quiet {:type "submit"} "Create demo event"]]
          [:div.field-hint.demo-note
           "Creates \"Demo Conference\" with a Program Committee and the seed form installed, "
           "so no screen is ever empty. Fake submissions arrive in a later slice — this button "
           "does not generate any yet."]]]]))))
