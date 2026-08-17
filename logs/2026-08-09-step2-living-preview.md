# 2026-08-09 — Step 2 form builder: living preview, retired counts, Warm Paper

*Parallel-agent session executing `plans/2026-08-09-step2-cfp-form-plan.md`,
scoped to the form-builder surface only (the main session was concurrently in
auth-google/login/sidebar/routes). Suite split into :unit/:e2e lanes happened
first, in the main session's stream (4bcd2dc).*

## What shipped (commits, in order)

- **c448276** — counts line under the header ("11 active + the speaker block ·
  0 retired · 1 private (committee-only)"); retired questions collapse into a
  "Show N retired questions" `<details>` with their Restore buttons; retired
  rows lose their ordinal. Also CLAUDE.md rule: compile checks via nREPL, never
  `clojure -M` (~40s JVM boot vs instant) — Gene's fastest-feedback-loop call.
- **0471887** — the living preview. Edit-panel inputs bound as `fbe*` signals,
  add-panel as `fba*` (distinct prefixes because both forms coexist — shared
  names would mirror each other's typing). Each form fires one
  `data-star-on:input__debounce.300ms` POST to the new stateless
  `/api/events/:slug/form/preview` route. `handle-form-preview` parses the
  signals through the SAME `forms/parse-field-params` a real save uses, overlays
  onto the saved fields (edit keeps the field's birth type; `?mode=add` renders
  a ghost row), pushes `#form-preview` to THIS viewer's stream only via
  `sse/push-to-person!`, logs `:form-preview-push-no-subscriber` loudly when
  nobody listens, answers 204. Saves still broadcast via `push-form-updates!`.
  **`bin/form_preview_probe.sh`** is the permanent proof (adapted from
  sse_probe.sh).
- **c973e06** — Warm Paper: `.fb-card` on the three builder segments (unlayered
  shell rule, same trick as `.launch-strip`, to beat the CDN's `.ui.segment`);
  the entire fb-* CSS block now references tokens only.

## Live evidence

- **Batch A (curl walk):** retire `prior-talk-video` → header "10 active ·
  1 retired"; `<details>` renders with Restore; restore → "11 active ·
  0 retired". Snapshot-safety banner confirmed present in source, gated on
  `submission-count > 0` (plan item 5 — no change needed).
- **Batch B+C (SSE probe, PASS):** on a real `/api/sse?event-id=<event>`
  stream: edit POST produced a `#form-preview` fragment carrying the typed
  label + help; add POST produced the ghost row (`fb-ghost` + typed label).
  2 patch events observed. 204s were not trusted; fragments were.
- **Batch D (Chrome):** screenshot of `/events/.../form?edit=advice-to-peer` —
  Warm Paper cards, counts line, open editor, preview pane all render.
- **Suite:** 206 tests / 1951 assertions green after B+C (background run,
  exit 0); post-D run backgrounded (CSS + class-only change).

## Defects / surprises found while driving

1. **Server restart window:** mid-session curls returned 000 — the main session
   had restarted the app JVM (auth work). Lesson: a dead curl during parallel
   sessions is not necessarily your bug; check `:server-started` in the log.
2. **`.nrepl-port` points at a different JVM than the live server** — a
   `:reload` there does NOT affect :20500 (wrap-reload does that on request).
   Fine for compile checks, useless for hot state.
3. **Chrome extension flake (OPEN):** mid-drive, every action started failing
   with "Cannot access a chrome-extension:// URL of different extension", then
   "No tab available"; viewport also shrank to 572×358 so a coordinate click
   typed into nothing. **Unverified as a result: the client-side debounce
   actually firing from a real browser keystroke.** The server side is fully
   probed; the binding + debounce attributes are confirmed present in the
   served HTML. Next session: one manual keystroke in the edit panel while
   watching `GET /dev/sse-state` / the log's `:form-preview-pushed`.

## Still open

- Manual browser keystroke test (above) — the last unverified link.
- `bd` issue sessionize-sched-killer-6ss closed with this log.

## Addendum (same day): the working-event sidebar (a3a6606)

Gene's ruling while driving prod vs local: **the rail is never a list of
events** — always exactly ONE event's full spine. Prod's loved one-event
behavior was already the shipped rule; the multi-event "switcher" branch was
the gap. Implemented: `events/working-event` (last-visited via a router-level
middleware recording signed-in GETs of /events/:slug*, cold-start = nearest
upcoming), the switcher deleted, a name header tops any DERIVED spine, CSS
`.sb-event-name`. Live-verified both directions (cold /events → EAIS; after
visiting Charlotte → Charlotte; in-event pages unchanged). 5 new derivation
tests in events-test. Spec + 12-scenario table: docs/design/nav-elements.md.
Leftover: ghost spine on a zero-event hall (noted on bead d9y).

## Addendum 2: resume-setup landing (d178f8d) + conflicts leave publics (7508ff9)

Two live-drive rulings from Gene, both shipped and verified same-session:
1. **Clicking an event resumes setup** — `event-resume-path` (form until
   reviewed, committee until 2nd reviewer, dashboard once done) drives the
   events-table name link, the Open/"Resume setup →" button, and the derived
   spine header. Verified: Charlotte → /form; EAIS-2026 → #committee (its form
   is genuinely marked reviewed in state).
2. **Withhold, loudly (bd 31b closed)** — `exports/publishable-sessions` gates
   all 5 public surfaces off known conflicts; organizer pools stay complete;
   schedule page counts what's held. Seeded-demo verified (Marcus 2→0 public,
   "2 sessions held back" renders); regression test covers withhold +
   organizer completeness + republish-on-resolve. Both suites green.

## Evening polish session — the form builder page is DONE (18:15)

**What was driven:** the full visual tightening of `/events/<slug>/form`, live in
Chrome with Gene steering from screenshots.

**Ratified design (Gene, 2026-08-09 evening):**
- Left column = question cards: always-on white surface, shadow-only
  (`box-shadow`, no border) so they stay quieter than the bordered preview
  cards they annotate. Arrived at via three live treatments (flat margin-notes,
  flat + connector arrow, hover-reveal card) — Gene ratified the hover surface
  as the permanent resting state.
- A ⟶ connector points each question at its preview (Gene's sketch).
- The preview keeps the border — only the real artifact gets the full card.
- Header: green "Next: create the review committee →" sits ABOVE "View public
  page" in the right-hand stack; same green button repeats at page bottom.
- Title "Create CFP Form"; subtitle "N active questions plus the speaker
  profile · N retired · N committee-only".

**Defect found — the invisible CSS enemy:** `target/classes/public/{css,js,vendor}`
(stale copies from the 16:26 uberjar build) shadowed `resources/public/` on some
classpath orderings, which is why hours of CSS edits "served but didn't render."
Deleted. OPEN GUARD: `make server-dev` (or the jar build) should clean
`target/classes/public` so a build can never haunt the dev server again.

**Cache-busting audit (Gene's ask):** all six local asset refs go through
`versioned` = `System/currentTimeMillis` per render — nothing can cache stale.
CDN libs are version-pinned URLs.

**Outage during session:** parallel agent's `views/api-docs-page` reference
landed in server.clj before the views half → wrap-reload 500'd every request
until their fix + Gene's restart. Sessions are in-memory, so each restart logs
everyone out — worth remembering when "the server won't load" is really a
stale session cookie.

**Final ratifications (18:15–18:20):**
- Question card: always-on white surface (hover treatment promoted to resting
  state per Gene's "we always show the white box").
- Type chip ("One line", "Long answer") always on its own line under the
  question label, left-justified.
- Sidebar wizard: done steps = green ✓ with NO "done" note (the check says it);
  future steps = no marker, no roster-count note (read as noise); an invisible
  spacer keeps the marker column so labels align.
- Header stack gap widened to 1em between the green Next button and View
  public page.

## Public CFP page study + polish (18:30–18:45, committed c542952)

Gene's drive of the public page found the import UX gap and my page study
found the copy leaks. All fixed:
- Sessionize import accepts a bare username ("realgenekim") or full URL —
  three layers had to agree: input type=text (browser url validation was
  rejecting handles client-side), normalize-profile-url expands handles,
  live-notes validate with the normalizer. Verified live: bare handle fetched
  Gene's real profile (name/tagline/bio/headshot/LinkedIn).
- Speaker-voice scrub: dev-slice/roadmap copy removed from the public page,
  dead "+ add co-speaker" button removed, organizer-doctrine hints reworded
  (seed + live demo event via forms/update-field! — simulated on the real row
  first, per the write-simulation rule), Submit talk now the green btn-go,
  help text renders once (was placeholder AND caption).
- Stale red "needs a full link" note over a successful import: root cause was
  the url-pattern validation on the sessionize field; now uses the normalizer.

## Addendum 3: Event details page + the front-door 500 (595fe2a, 3db7602, 3d2324a)

Gene, driving: "I should be able to click Create Event... add something to
sell the conference at the top of the banner." While wiring it, found the
public CFP pages 500ing live — a lane had added the masthead's (:cfp-intro
event) read without adding the key to the closed record's event-keys.
Closed-record caught it exactly as designed, just as a 500. HOTFIX 595fe2a.
Then the feature: /events/:slug/details (pitch textarea + location/website/
support-email; present-with-nil clears), reachable from both rail modes —
wizard step 1 is now "✓ Create / edit event" (check mark = done, no note),
relaxed mode leads "The call" with Event details. Roundtrip live-verified:
save → 303 → pitch renders in p.cfp-intro on the public masthead. Suite green.

## The no-reload import + the selling hero (18:45–19:15, and Gene's FIRST REAL TALK SUBMITTED)

- **No-reload Sessionize import, pure game-engine**: the profile input joined
  the main form, so its value is already in the per-viewer draft stash when
  Import is pressed — the @post carries NOTHING; the server reads its own
  state, imports, and morphs #cfp-about-you down that one viewer's stream.
  Proven on a real SSE stream (fragment observed with value="Gene Kim"),
  not trusted from the 204. Enter in the box imports instead of submitting.
  Anonymous-safe: everything keys off the session viewer-id.
- **The hero sells**: kicker → marquee title → organizer's :cfp-intro rendered
  as markdown (markdown-clj after next JVM restart, md-lite fallback until
  then; source entity-escaped either way — found and fixed an escape bypass
  where hiccup read md-inline's vector as an element) → one facts line
  (close date, per-person limit, "Official event website ↗").
- **Tab autofill everywhere**: ghost-fill.js extended to textareas; every
  placeholder is now a REAL example (instructional placeholders rewritten),
  so tabbing through the empty form fills it with plausible data — the same
  affordance as the create page.
- Both live events synced via forms/update-field!; placeholder became an
  editable field attribute (editable-keys + the update-field! patch, which
  silently dropped unknown keys — the second silent-drop bug that fn has
  grown; watch it).

## Addendum 4 (2026-08-10, late): the one-database cutover

Gene, driving the deployed URLs, hit the three-worlds confusion (local JSONL /
demo baked-seed / prod empty) and re-ruled the whole storage strategy live:
**dev IS production** — one Cloud SQL database, VL-2026 precedent, "with good
backups and event sourcing we can do this very safely." My split-brain verdict
lost on re-litigation and he was right: the log is multi-writer-safe; the one
real defect (stale folds) fell to a 20-line honest-mark fix.

Executed, all meter-verified: GCS write path proven then parked (CPU-throttling
+ baked-seed findings preserved in sinks.clj/tests); relic row backed up +
cleared; 620/620 byte-identical REPL replay; service flipped to Postgres
(IAM, max-instances 1); dev server restarted onto the same DB. New operator
CLI (admin.clj: backup-db / promote-local, the VLAdmin clj -X pattern),
server-jsonl sandbox for agents/e2e, secrets/db.edn socket-factory connection
(never proxy, never psql). Strategy + mitigation stack: docs/one-database.md.
Also tonight: Event details page (+ every create fact editable, Cmd-S via
ds/on-meta, save toasts standardized), the prose standard, the front-door 500
hotfix, and Gene submitted the first real talk through the tool.

Open next: retire one of the two Cloud Run services (naming), cron backups,
e2e drives must target server-jsonl from now on.

## Addendum 5: j/k/x archive + the db-interface rule (bcfa87b)

The two-screenshot mystery (old sidebar on curtaincall, junk events) resolved:
stale image on the second service + e2e fixtures created AS GENE, migrated in.
Both fixed by one feature: j/k focus ring + x = event.archived appended fact,
Archived shelf with Restore, forward-compat fold (old images ignore the new
type). 48 junk events bulk-archived via the domain verb — after the meter rule
caught pass #1 landing in the jsonl sandbox (nREPL defaults :jsonl). CLAUDE.md
gains the database-interface rule (no direct PG writes; domain verbs append
facts; psql banned). Both services now on tonight's image. Backups: 3 taken.

## The flood + the board (19:30–20:45)

- seed-flood ran: 51 submissions on charlotte-2026, ratings/comments
  scattered mid-flight (33% coverage, 17/51 at threshold). The board came
  ALIVE — and revealed the time-travel scrubber already prototyped on it.
- Plan converged with Gene (4 decisions ratified): scrubber → Replay theater
  + dev drawer; reviewer switching → demo-login identity dropdown (dev only);
  swyx experiences it as himself on a seeded prod demo event; flood speakers
  stay fictitious (AI faces filed as -ek8).
- Dev drawer shipped (79ea7b1): DEV-badged slate panel at the rail's foot,
  identity switcher proven end-to-end (switched to Ann via endpoint).
- Board Warm Paper CSS pass (e1ef9d9). False-alarm P0 worth remembering:
  the board "wouldn't scroll" under automation — my own file edits kept
  firing dev auto-reload which reset scroll between wheel and screenshot.
  window.scrollTo proved the page fine. The instrument, not the app.
- docs/demo-script.md: the five-minute walkthrough for showing off tonight.

## Addendum 6: faces, the person-first board, URL-as-state sorting (29edb2d…7105d1c)

The face pool shipped end to end in one evening stretch: 48 AI-generated
people (~70¢, one lighting spec), deterministic hash binding (same person =
same face, zero data changes), wired into board/detail/person/roster and
deployed. Then Gene redesigned the board live over screenshots: line 1 =
face·name·role·org, line 2 = the talk full-width ("nobody sorts by title");
sortable headers now POST → per-viewer SSE push of #board-region (no reload)
while replaceState stamps the COMPLETE view state into the URL ("give me the
URL and you can replicate the state"). SSE-probe-verified. Also: make nrepl
defaults to postgres — REPL, dev, prod all in the one database. Landing-page
treatments (6 tellings of the true story) mocked, riffed, and beaded (7e1);
faces/companies/logos riff captured in 9ot.
