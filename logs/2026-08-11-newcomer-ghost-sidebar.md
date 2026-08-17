# 2026-08-11 — The newcomer ghost sidebar (Option B): the map is the tour

## What was asked

Gene, looking at the /events page as a newcomer would see it: "The sidebar.
Look at how awesome it is when you are an organizer. Why don't we show that?
… look at what I would normally see … as similar to this as possible."
He ratified **Option B**: every ghost item is clickable — a click starts the
replay demo AND lands on that exact section.

Also in the same message: prove hot reloading actually works before the next
production push.

## What was built

1. **`handle-replay-start-demo` grew a `then` param**
   (`handlers/replay.clj`): `POST /api/replay/start-demo?then=board` creates
   the demo event, calls `replay/skip-to-end!` (the corpus fast-forward —
   confirmed self-initializing, no prior `start!` needed), then 303s into
   `/events/<slug>/board`. Whitelisted sections: details, form, committee,
   board, inform, schedule, exports, + `agenda` → `/agenda/<slug>`. No
   `then` = today's behavior (replay console, unplayed).
2. **Third sidebar branch** in `views/organizer_layout.clj`: guarded
   `(and person (not birth?) (nil? event))` — a signed-in person with no
   events. Mirrors the real journey spine: ghost event card ("Your event
   will live here / Create it →"), "Create CFP — 3 steps" (step 1 = real
   link to /events/new; steps 2–3 = demo doors), Review CFP proposals
   (Review Board), Decide & tell (Inform Speakers), The show (Schedule /
   Public agenda / Exports & API), "▶ Tour it live" footer, Comms·Log·
   Settings quiet row. Every ghost row is a plain server-rendered
   `<form method=post>` — zero JS, Datastar-clean.
3. **CSS** appended to `resources/public/css/app.css`: `button.sb-item`
   reset, `.sb-ghost` (muted like sb-step-done but pointer + hover-brighten),
   `.sb-ghost-card` (dashed border), `.sb-tour`.

Both Clojure edits went through one clj-surgeon `apply_clojure_changes`
transaction (kondo clean, hash-guarded, receipt retained). This was also the
session where the correct `inspect_clojure` request schema was learned: a
`requests` array of `{id, operation, file, forms|…, expect}` objects — the
top-level `forms` field I first guessed is refused as unknown-fields.

## Evidence (live drive on 20501, jsonl sandbox)

- Fresh email → dev magic link → `/events`: ghost sidebar renders — all 7
  `then` doors + ghost card + tour button present in the HTML.
- **Hot reload proven**: the server compiled both edited files on request —
  no restart, no `touch`, launched via `make server-jsonl` (the -A:dev
  lesson from last night holding).
- `POST /api/replay/start-demo?then=board` → 303 →
  `/events/aie-replay-c8dnff/board` in **1.27s total**, landing on
  **Review Board (40)** — populated, never blank.
- After the click the person owns an event, so the sidebar graduates to the
  real working-event spine automatically — the ghost map is self-retiring.

## Open

- Suite run in background at write time; commit + prod push (with 8c9beea
  welcome sprint) once green and Gene has eyeballed it.
- Gene can see it: incognito → http://localhost:20501/login → any fresh
  email → click the echoed link → /events.

## Iteration 2 (same night): the three hats made explicit

Gene, on the first render: "it's just not clear what the three roles are…
make it really obvious that as a speaker, you can update your profile and
perhaps look at the submissions you've done."

Shipped (ASCII mockup approved in-flight):
- Thesis line under the greeting: "One account, three hats — organizer,
  speaker, reviewer. There's no role to pick: just walk through a door."
- Speaker hat: two concrete verbs — "Update your speaker profile →" and
  "Your submissions & their statuses →" (both /portal).
- NEW reviewer hat: honest (organizers invite you; one click from the
  email) + a live door — "See a live review board →" is a form POST to
  /api/replay/start-demo?then=board.
- Sidebar newcomer branch gained a "Speaking?" group → /portal.

Verified on 20501 with a fresh email (all seven new strings render; the
two containing "&" appear HTML-escaped as &amp; — grep accordingly).
Suite green: 262 tests / 2695 assertions / 0 failures.

## Iteration 3: the simulation gets an honest timeline (and a name)

Gene, from the demo board: the strip must SAY simulation and the scrub bar
must show the state — and "we're using the wrong event." REPL survey of prod
(docs/demo-datasets.md, written per Gene: "document the datasets") found the
four demo events: the corrected pair spreads submissions across the call
window (ai-engineer-code-summit: 502 subs over 41 days); the superseded pair
is single-day spikes. The replay path reproduced the spike: play-entry! goes
through real domain verbs, which stamp store/now-iso — wall-clock — so every
fresh demo had a ~60-second recorded history no matter what the corpus's
offset-secs said.

Fixes (one clj-surgeon transaction, kondo clean):
- tick! binds store/*clock* per entry to cfp-opens-at + offset-secs — the
  existing operator seam, built 2026-08-10 for exactly this, never wired in.
- create-demo-event! births the event ON the simulated timeline (8:00 PT,
  an hour before the sim CFP opens) so the story is continuous.
- dev-strip → dev/simulation strip: replay-marked events get the SIMULATION
  badge + scrubber in EVERY environment; DEV badge + identity switcher stay
  ENV=dev-gated. CSS: .sim-badge (green chip).

Proof (fresh email → ghost click → board on 20501): strip shows SIMULATION;
time-travel bounds 2026-07-21T15:00:00Z → 2026-08-11, 398 recorded events —
21 days of story where there was a 60-second burst. Beads filed: corpus
reshape to match the corrected dataset's trickle→spike; one stray fact still
stamped at wall-clock now (harmless, tracked).

## Iteration 4: dev IS prod (the environment ratification)

Two hours were lost to the jsonl sandbox making dev lie about what prod
shows (empty welcome, missing datasets, an aborted dev-only import). Gene
ended the debate: "because we have backups, let's make dev and prod the
same. only way to get the fast feedback loops we need." The dev server on
20501 now runs `make server-dev` (ENV=dev + STORE_BACKEND=postgres) against
the production database; verified genek@itrevolution.com sees AI Engineer
Code Summit (the swyx 502/41-day dataset) live on dev. CLAUDE.md gained a
top-level DEV ENVIRONMENT section so no session re-litigates this; the
stale "nREPL defaults to :jsonl" warning was corrected. Also: replay demos
no longer graduate the newcomer (ghost map stays until a real event
exists), and Buster lane B was unblocked with worktree direction (cc-czd
off sol-main). The 2,311-fact prod export (/tmp/swyx-502-facts.jsonl)
remains as reference only — nothing was appended anywhere.

## Iteration 5 (early morning): playbills, profiles, and the method ratified

- Host playbill shipped: /organizers/<slug> (public), host = committee
  CHAIR (REPL-verified: swyx chairs Code Summit, Gene chairs EAIS), speaker
  profile folded in, welcome ticker names chairs. Gene + swyx profiles
  patched via portal/update-profile! in the REPL (appended
  person.profile-updated facts); swyx's bio sourced from swyx.io.
  :org added to profile-fields (my over-move on Gene's data correction —
  kept, it's a program staple).
- Working method ratified by Gene: NATIVE writes (Edit + nREPL reload,
  sub-2s) for small anchored changes; clj-surgeon for STRUCTURE — outline/
  forms/match as the search tool ("never grep for a defn again"), guarded
  transactions for multi-file/ns surgery. Three surgeon findings filed
  upstream (clj-surgeon-dkj/tfh/elu: format-pass latency, comment-gap
  refusals, refusal diagnostics).
- bin/q born: one-line domain queries against the running nREPL.
- The warm watcher caught a cold-load-only breakage (handle-organizer-page
  referenced not-found-page defined 160 lines later; warm REPL said :ok) —
  fixed with a declare. The instrument Gene spent weeks wanting paid for
  itself within hours.
- Bead ahp: claim-your-account loop (submission info carried into account;
  notifications as the hook). Demo instance verdict: killed for the contest;
  personas dormant behind DEMO_PERSONAS=on.

## 03:00 — SHIPPED. Both services on tonight's build.

Deploy lane ran twice (Time Machine polish landed mid-lane):
swyx-cfp-saas-killer → 00026-cid at 100% (rollback point recorded);
curtaincallcfp → 00027-yuy at 100% (previous: 00024-ror). Tagged-URL
verification before each promote: landing/login/manifesto/playbill 200,
events gate 302, prod-mode login confirmed (grayed magic link, no
personas, Google door). Prod sanity walk green on both live URLs.

The night's milestone list: homepage ✓ login ✓ open sign-up ✓ manifesto ✓
DEPLOY ✓. DNS blocked on a discovery: curtaincallcfp.com is UNREGISTERED
(whois: No match) — Gene buys it (Cloudflare Registrar recommended; the
existing itrevolution.com token can't see other zones), then
docs/dns-changeover.md executes. Gene was already at the OAuth console
adding https://curtaincallcfp.com/auth/google/callback (confirmed correct).

Also born this hour: CFP Time Machine (Gene named it), the Exit simulation
button, host playbills with real chair profiles, bin/q, and the
native-writes/surgeon-searches doctrine that made the last three hours run
at 10x the first two.

## ~04:00 — THE CFP IS PUBLISHED. curtaincallcfp.com/cfp/enterprise-ai-summit-charlotte-2026

Gene: "it works!!" The complete artifact, live on the real domain: the EAIS
crowd-collage hero (from Gene's clipboard → app assets → event.hero-set
pointer fact; GCS migration beaded 4v9 pending one interactive gsutil),
Gene's new sell copy with markdown links (the gist credit to swyx's Kill My
SaaS), the 13-field form, no account wall. Final revisions:
swyx-cfp-saas-killer-00033 / curtaincallcfp-00031. En route we also caught
/images/ missing from the public allowlist (strangers got a 302'd hero) and
rode out a gcloud reauth window (Gene's one `gcloud auth login`).

The night, end to end: dev-is-prod ratified → welcome/three-hats/ghost rail
→ simulation timeline + red banner + CFP Time Machine → host playbills with
real chair profiles → T1 login → /welcome split → domain registered, mapped,
cert'd → both services shipped repeatedly → the published CFP with its hero.
Gene's own submission sits on swyx's 502-board. The swyx DM writes itself.

## ~04:50 — The email era, and its first fire

SES went live (SESv2 transport ported from does email/ses; secret copied
to this project, SAs granted): magic links deliver to any domain
(production access confirmed via realgenekim@me.com), sender "CurtainCall
CFP", selling letter with absolute origin links, committee invites wired
to roster add (one click from inbox to board — the welcome page's oldest
promise, kept; live-fired and inbox-verified).

Then the fire: replay demos play REAL domain verbs, so with SES live one
replay click emailed the demo committee for every simulated submission.
Damage (counted from the log): 809 real sends — 649 to four in-house
inboxes (genek 165 / me.com 164 / annp 160 / alex 160), 152 to
undeliverable example.com fakes, swyx ZERO (verified twice). Response in
~12 minutes: dev server killed, SIMULATION FIREWALL (replay events always
render) committed + emergency-deployed to both services, per-event
email-notifications switch added (fact + verb + mail enforcement; UI
toggle inb-5942d0), servers restarted on fixed code. Lessons: transports
going live change the blast radius of every existing verb — inventory
who sends before enabling; in-memory tokens split across JVMs (REPL-
minted invite unredeemable on the server) — durable tokens inb-623e84.
