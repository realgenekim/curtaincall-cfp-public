(ns cfp-scheduler-killer.views.auth
  "Unauthenticated landing and sign-in views."
  (:require
   [cfp-scheduler-killer.views.homepage-copy :as homepage-copy]
   [cfp-scheduler-killer.views.public-widgets :as view-public-widgets]
   [cfp-scheduler-killer.views.shell :as shell]
   [clojure.string :as str]))

(defn landing-page
  "The public front door (bd -7e1, Gene ratified 2026-08-10): Zen Paper hero
   + the hate/proud mirror + organizer/speaker duo cards + the Ledger tape as
   the trust section. Served signed-OUT only — signed-in / goes to /events.
   `live-cfp` is {:slug .. :name ..} for an open call chosen by the server,
   or nil (the speaker link hides rather than 404s). Everyone sees this page
   — signed-in or not (Gene, 2026-08-10: / IS the landing, no exceptions);
   `person` only changes the top-right door; `demo?` adds judge sign-in guidance."
  [live-cfp person demo? featured]
  (shell/share-page-shell
    "Curtain Call — calls for papers, without the paperwork"
    (shell/social-meta shell/homepage-social-metadata)
    [:div.landing
     [:div.landing-top
      [:span.landing-brand "Curtain Call"]
      [:div.landing-utilities
       (when demo?
         [:a.landing-demo-link {:href "/login"}
          "Judge demo: choose a persona →"])
       (if person
         [:a.landing-signin {:href "/events"} "Your events →"]
         [:a.landing-signin {:href "/login"} "Sign in →"])]]
     nil
     [:div.kicker "Curtain Call · calls for papers, without the paperwork"]
     [:h1 "The CFP tool organizers have dreamed of for fifteen years." [:br]
      [:b "We were the ones complaining."]]
     [:p.confess
      "24 conferences over 12 years, on five of some of the worst tools on the "
      "planet. We know exactly what conference organizers hate — we have "
      "personally gnashed our teeth about these problems for over a decade."]
     [:div.mirror
      [:div.hate
       [:h3 "Everything we hate about CFP tools"]
       [:ul
        [:li "Reviewers can't actually talk to each other — opinions go to die in silos"]
        [:li "Committee members forced to log in to yet another tool just to leave a comment"]
        [:li "Form-filling, forever. It turns the best part of running a conference into a chore"]
        [:li "Deciding and notifying smeared into one terrifying button"]
        [:li "Your program, trapped — export means copy-paste"]
        [:li.ex "Exhibit A of our desperation: Trello boards, Zapier glue, one Google Sheet "
         "running the schedule for ten straight years, and an entire front-end we built "
         "by scraping our own CFP tool"]]]
      [:div.proud
       [:h3 "What we're proud of in this one"]
       [:ul
        [:li "Review is a conversation — every score and comment on one shared page"]
        [:li "Committee members click one link. Speakers never make an account at all"]
        [:li "Zero to open CFP in ten minutes — the acceptance test is a stopwatch"]
        [:li "Decide quietly, then tell everyone deliberately — and it remembers who's been told"]
        [:li "sessions.json, speakers.json, calendar.ics, a real API — your site drinks directly"]
        [:li "Append-only ledger: nothing is ever deleted, everything can be rewound"]]]]
     [:div.duo
      [:div.card.org
       [:div.aud "For organizers — you choose the tool"]
       [:h2 "Run the whole call on one calm page."]
       [:p "Built from 20,000 committee Slack messages' worth of scar tissue, "
        "after swyx dared the internet to kill his SaaS bill."]
       [:a {:href "/login?next=%2Fevents"} "Open your call for papers →"]]
      [:div.card.spk
       [:div.aud "For speakers — the people it must not lose"]
       [:h2 "The easiest submission you'll ever make."]
       [:p "One page. No account until you press submit. A half-typed abstract "
        "that survives anything. Edit until the call closes."]
       (when live-cfp
         [:a {:href (str "/cfp/" (:slug live-cfp))}
          "See a live event CFP! Gene Kim's Enterprise AI Summit (Oct 7-8, 2026) →"])]]
     (list
       [:a.cta {:href "/login?next=%2Fevents%2Fnew"} "Create your event — live in ten minutes"]
       [:span.quiet "win or lose, the $10K prize goes to STEM charity"]
       (homepage-copy/section)
       [:div.landing-ledger
        [:div
         [:h2 "Fifteen years, five tools, one lesson: " [:span "never lose the work."]]
         [:p.sub
          "Curtain Call is an append-only ledger wearing a friendly face. The story "
          "of how it came to exist is best told the way the tool itself would record "
          "it — and everything on this tape really happened."]]
        [:div.tape
         [:div.h "HISTORY.LOG — THE CONFERENCE DESK, 2011→"]
         [:div "2016  eventpower.adopted   review=thumbs-tally"]
         [:div.dead "2017  eventpower.abandoned  → cvent.adopted"]
         [:div.dead "2017  committee.refers-to-speakers-by-sheets-row"]
         [:div.love "2018  busyconf.adopted      \"the beloved era\""]
         [:div.dead "2023  busyconf.died         cause=heroku-repricing"]
         [:div "2021  sessionize.adopted + scraper.written (self-defense)"]
         [:div "2014-24  workarounds.written  trello, zapier, the-Sheet"]
         [:div "2026-08-09  swyx.dares-internet  \"kill my SaaS\""]
         [:div "2026-08-09  slack.messages.reviewed  n=20,000"]
         [:div.hot "2026-08-10  curtaincall.created  — you are here"]
         [:div.foot "append-only · nothing above can be edited or deleted · that's the product"]]])
     [:div.landing-story
      [:h2 "Fifteen years, five tools I've been so frustrated with: "
       [:span "thanks to vibe coding, I can finally build the tool I want."]]
      [:p "On August 9th, "
       [:a {:href "https://twitter.com/swyx" :target "_blank" :rel "noopener"} "swyx"]
       " — curator of the AI Engineer conferences — dared the internet to kill "
       "his SaaS: write, in one weekend, the CFP and agenda tool he pays for "
       "every year. A $10,000 prize — which I'd donate to charity — judged "
       "on a single question: " [:em "would his team actually use it?"]]
      [:p "I couldn't resist. IT Revolution has run 24 conferences over 12 "
       "years on five tools we mostly hated — 20,000 committee Slack messages "
       "of scar tissue, a Google Sheet we still need for some parts of our "
       "process, and a wish list nobody would ever build. When someone finally "
       "said “build it in a weekend,” I already knew exactly what "
       [:em "it"] " was."]
      [:p "So we pointed our AI agents at fifteen years of frustration, and "
       "Curtain Call existed by Monday — "
       [:a {:href "https://gist.github.com/realgenekim/863f20b8ea515ed8858a298f8e470e9d"
            :target "_blank" :rel "noopener"}
        "the build log tells the whole story, agents and all"]
       ". After five tools, the lesson was never about their features: the "
       "only fix for a tool you hate is to finally have the one you want."]
      [:img.ls-photo {:src "/images/eais-charlotte-hero.jpg"
                      :alt "Enterprise AI Summit — the room in Charlotte"}]
      [:p "Our own call for speakers is live on it "
       "right now: the "
       [:a {:href "/cfp/enterprise-ai-summit-charlotte-2026"}
        "Enterprise AI Summit — Charlotte, October 7–8"]
       " is accepting proposals. We have an amazing program planned and just "
       "a few open slots — if you have an amazing story to share, submit one."]
      (when (seq featured)
        [:div.cfp-featured
         [:div.cfp-section-title "Featured speakers — already on the program"]
         [:div.cfp-featured-grid
          (for [sp featured]
            (view-public-widgets/announced-card sp))]])
      (list)]
     (list)]))

(def ^:private demo-persona-copy
  {"organizer" {:body "Run the whole call: CFP, committee review, decisions, schedule, speaker operations, and publication."
                :action "Enter the organizer workspace →"}
   "reviewer" {:body "Review assigned proposals, rate and comment, and join the committee conversation."
               :action "Enter as Maya →"}
   "speaker" {:body "Manage her proposal, profile, tasks, files, and final schedule."
              :action "Enter as Amara →"}})

(defn login-page
  "T1 marquee (Gene picked it, 2026-08-11): the welcome line IS the headline
   and Google is the door. The magic-link button renders grayed-out outside
   dev — honest about the missing SMTP — while dev keeps its working
   echoed-link form (that is how dev signs in and how the drives test)."
  [{:keys [message link sent-to next dev? mail-live? prefill-email demo? google?
           demo-link? demo-personas]}]
  (shell/page-shell
    "Sign in — Curtain Call"
    [:div.login-marquee
     [:div.lm-brand "Curtain Call"]
     [:h1.lm-welcome "Whether you're an organizer, a speaker, or a reviewer — we're glad you're here."]
     [:p.lm-sub "One sign-in covers all three. No passwords, no forms."]
     (when message
       [:div.ui.info.message
        [:p message]
        (when link
          [:div {:style "margin-top:0.8em;"}
           [:div.field-hint
            (if demo-link?
              "Demo mode — SMTP is off; your link:"
              "Development mode — your sign-in link:")]
           [:a.ui.primary.button {:href link :style "margin-top:0.4em; word-break:break-all;"}
            "Sign in as " (or sent-to "yourself")]
           [:div.field-hint {:style "margin-top:0.5em; font-family:monospace; font-size:0.75em;
                                    word-break:break-all;"}
            link]])])
     ;; Google proves who you are, and that is the whole gate: any verified
     ;; identity gets an account (open sign-up, docs/open-signup.md).
     ;; One link, zero JavaScript — the whole flow is redirects.
     (when google?
       [:div.lm-act
        [:a.lm-google {:href "/auth/google"}
         [:span.lm-g
          [:svg {:viewBox "0 0 18 18" :width "18" :height "18"}
           [:path {:fill "#4285F4" :d "M17.64 9.2c0-.64-.06-1.25-.16-1.84H9v3.48h4.84a4.14 4.14 0 0 1-1.8 2.72v2.26h2.92a8.78 8.78 0 0 0 2.68-6.62z"}]
           [:path {:fill "#34A853" :d "M9 18c2.43 0 4.47-.8 5.96-2.18l-2.92-2.26c-.8.54-1.84.86-3.04.86-2.34 0-4.32-1.58-5.03-3.7H.96v2.33A9 9 0 0 0 9 18z"}]
           [:path {:fill "#FBBC05" :d "M3.97 10.72a5.41 5.41 0 0 1 0-3.44V4.95H.96a9 9 0 0 0 0 8.1l3-2.33z"}]
           [:path {:fill "#EA4335" :d "M9 3.58c1.32 0 2.5.45 3.44 1.35l2.58-2.58A9 9 0 0 0 .96 4.95l3 2.33C4.68 5.16 6.66 3.58 9 3.58z"}]]]
         "Continue with Google"]
        [:div.lm-hint "New or returning — any Google account works."]])
     ;; ONE magic-link unit (Gene, 2026-08-11: "that should all go in the same
     ;; thing") — same layout in every environment; dev enables it (the link
     ;; echoes on the page), prod renders it disabled until SMTP exists.
     (let [off (not (or dev? mail-live?))]
       [:form.lm-magic {:method "post" :action "/api/login"}
        (when next [:input {:type "hidden" :name "next" :value next}])
        [:span.lm-dev-tag
         (cond dev? "magic link — dev only: we will show your link right on this page"
               mail-live? "magic link — we will email you a sign-in link"
               :else "magic link — this feature is disabled right now")]
        [:input (cond-> {:type "email" :name "email"
                         :placeholder "you@example.com"
                         :value (or sent-to prefill-email "")}
                  off (assoc :disabled true))]
        [:button.lm-send (cond-> {:type "submit"}
                           off (assoc :disabled true))
         "Send link"]])

     [:div.lm-fine
      "Just submitting a talk? " [:strong "No sign-in needed"] " — the "
      "call-for-speakers page is public. Your account happens when you press "
      "submit."]
     ;; The real door stays first. Only an explicitly isolated judge service
     ;; adds this second path, and every card remains a plain POST underneath.
     (when demo?
       [:section.lm-sandbox
        [:div.lm-sandbox-head
         [:h2 "Judge Sandbox"]
         [:p "Start with the organizer responsible for making the conference happen. "
          "Then inspect the same work from either side."]
         [:p.lm-sandbox-safety "Isolated data · no real email · resets automatically"]]
        [:div.lm-personas
         (for [{:keys [role label enabled?]} demo-personas
               :let [{:keys [body action]} (get demo-persona-copy role)]]
           [:form {:method "post"
                   :action (str "/api/demo-login?role=" role)
                   :class (str "lm-persona lm-persona-" role
                               (when-not enabled? " disabled"))}
            [:button.lm-persona-card (cond-> {:type "submit"}
                                       (not enabled?) (assoc :disabled true))
             [:span.lm-persona-label label]
             [:span.lm-persona-body body]
             [:span.lm-persona-action (if enabled? action "Coming next")]]])]
        [:p.lm-sandbox-boundary
         "Sandbox identities cannot access our live Enterprise AI Summit."]])]))

(defn organizer-page
  "PUBLIC host playbill (Gene ratified round 2, 2026-08-11): the committee
   chair's name + their speaker profile (one profile, both hats) + every
   live event they chair. Graceful when the profile is empty — initials for
   the headshot, no bio block; a nudge renders only to the host themself."
  [{:keys [host profile events self? self-nudge?]}]
  (let [{:keys [tagline org bio headshot-url linkedin-url website-url]} profile
        initials (->> (str/split (or host "?") #"\s+")
                      (take 2) (keep first) (apply str) str/upper-case)]
    (shell/page-shell
      (str host " — Curtain Call")
      [:div.playbill
       [:div.pb-kick "Curtain Call · host"]
       (if-not (str/blank? (str headshot-url))
         [:img.pb-photo {:src headshot-url :alt host}]
         [:div.pb-ava initials])
       [:h1.pb-name host]
       (let [tagline-line (str/join " · " (remove str/blank? [(str tagline) (str org)]))]
         (when-not (str/blank? tagline-line)
           [:div.pb-tag tagline-line]))
       (when-not (str/blank? (str bio))
         [:p.pb-bio bio])
       (let [links (remove nil?
                           [(when-not (str/blank? (str website-url))
                              [:a {:href website-url} "website"])
                            (when-not (str/blank? (str linkedin-url))
                              [:a {:href linkedin-url} "linkedin"])])]
         (when (seq links) [:div.pb-links links]))
       [:div.pb-events
        [:div.pb-kick2 "Their events"]
        (for [e events]
          [:div.pb-trow
           [:b (:name e)]
           [:span.pb-dots]
           [:span.pb-n (str (:sub-count e) " submissions")]
           [:a {:href (:agenda-url e)} "agenda →"]
           (when (:cfp-url e)
             [:a.pb-open {:href (:cfp-url e)} "call open"])])]
       (when self-nudge?
         [:div.pb-nudge
          "This is your public host page — fill your speaker profile and "
          "your bio, headshot, and links appear here."])
       (when self?
         [:a.pb-edit {:href "/portal"} "Update your profile"])
       [:a.pb-back {:href "/"} "← Curtain Call"]])))
