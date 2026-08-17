# Captain's Log — the Honest Row night (2026-08-17, ~03:00–10:00 PT)

*Mayor seat (Fable), Gene present and rapid-fire until ~10:00. The night the
eval finally landed an honest row, the sidebar got redesigned three times in
ninety minutes, and the dev-box conveyor became repeatable.*

## 1. The row (top P0, "whatever it takes")

Run `20260817T102915Z` (pin 6e3ff751) had died in `finalize_failed_no_row`
after two truncated-URL capture refusals quarantined 7 of 18 scenarios. Gene's
order: finish the mission — but Mayor's constraint held all night: **typed
named zeros, never synthetic evidence** (cfp5 on spark-low drifted toward
"build synthetic evidence" and was course-corrected: honest row or no row).

Row #1 landed 11:53Z: **69.6 · coverage 15/18 captured, 11 judged, 7 named
zeros · flag insufficient** — with per-area receipts. Real signal: ai-agenda
100, content-management 96.8, public-widgets 57.4, abstract-management 46.3;
CFP + SPK zeroed by coverage, not by the product. The judge quoted our own
refusal copy ("Curtain Call does not use weighted scorecards") back as a
defect — the rubric disagreeing with the product, exactly as doctrine
predicts.

Run #2 (pin 59afd5b, terra workers, fail-forward ratchets) hit the SAME
refusal class as a **false positive** — the validator's broad U+2026 check
matched the UI's own "(truncated)" text beside a complete URL. cfp5 fixed the
validator (inspect only the matched URL slug, efc1fa7) and sealed run #2 at
11 captured / 7 typed zeros; judges running at close of log. Ruling: two rows
tonight — never kill a running eval — then a scoped CFP+SPK top-up
(`FLEET_SCENARIOS` supports subsets) with the fixed validator.

Ratchet chain from the saga: fail-forward (refusal costs the scenario, never
the slot), typed zeros as first-class fold input, malformed judge receipts
become reported refusals not run-killers, and refusal messages must quote the
exact bytes they matched (rule 20: the verifier binds to its subject).

## 2. The sidebar odyssey (three ratified designs in ~90 minutes)

25a1016 (move Decide & Tell under Admin) → rendered broken because `sb-group`
is FLAT (nesting emits an empty header) → Gene: "still wrong" → root-caused,
fixed → cfp3 relayed a deletion ratification (generic Speakers + Add Speaker
links die; Announce/Manage owns the roster) which Mayor confirmed with Gene
per rule 7 → flat nine-link Admin shipped (90be025) → Gene: "bring back
Decide and Tell; try again" → Mayor ascii, ratified verbatim → **Mayor took
the sidebar fence, committed 505f1d6 himself** → later v3: Inform + Announce
collapse into ONE link, "**Manage Submissions and Speakers**" →
/speakers?view=manage (15eb2e1 + 7dce37a oracle re-pin), the page's own
[Decide]→[Inform]→[Manage] stage strip carrying the workflows (cfp3's tabs,
1fa9234 — tabs are DATA in domain.speakers/roster-tabs).

Casualty and lesson: Mayor's full-file server_test copy displaced cfp6's
unknown-event fix (the exact stale-copy hazard the parallel-stream rule
names); owned, restored in 5ba664f.

## 3. The 66-red wave — one default, one projection

cfp3's b1d8c29 (Manage lifecycle) defaulted the roster to status=Active,
hiding every Invited/Submitted fixture across 19 contract namespaces — and
made "All" unselectable in the live UI (the chip linked to bare ?view=manage
which reapplied Active). Plus a "swap" that wasn't: program-only speaker
lookup excluded manual/imported identities. cfp4's receipt forensics + cfp6's
root-cause isolation converged; cfp3 fixed both in b6764cf **with a >defn
typed-refusal ratchet** so a real slug/person swap now refuses at the call
site. 66 reds, two causes, zero pin-chasing.

Also: the dev-server ns-tracker WEDGED after repeated failed hot-reload passes
(three simultaneous callee-before-caller saves) — touch-healing stopped
working even though nREPL proved every namespace loaded in dependency order.
Remedy: kill by exact PID, `make server-dev`, prove the reload token.

## 4. Features ratified and specced tonight (the riff harvest)

- **9v0f track-scoped reviewers** (SHIPPED: domain 12b79bf, Committee chips
  d35b51b, Board scope edbd5bc): committee = roster + scope filter made real;
  "a filter, not a wall" explainer is Gene's ratified copy.
- **6fb6 AI Reviewers v1** (specced end-to-end): love/hate taste prompts
  (ghost-filled 2-para examples), Comms-style [Configure][Evaluate] tabs,
  Apply = demo engine drawing from a 1000-comment star-banded pool (shipped
  resources/ai-reviewer-comments.json, codex-generated, 80f0560), AI posts a
  DISTINCT fact type `ai-review.recorded` (bot-in-human-math unrepresentable),
  LATEST generation wins display, Quick Rate reveals after commit, API brag
  copy with honest-boundary guard. Evaluate tab = the board row component
  sorted by AI rating, header-sortable, star-only cell.
- **vbo7 REST API v1 writes** (spec: docs/design/rest-api-v1-writes.md):
  read/watch/act verbs over the event-sourced store; POST reviews with
  idempotency keys (scope selects fact type); analytics = traffic joined to
  outcomes (the endpoint GA can't build); Admin → API documentation page
  rendered from the same contract data as the index. cfp4 shipped item 1
  (key scopes) focused-green.
- **nx6f ABS recovery**: ABS-13 review-results export SHIPPED (6f98d09,
  Exports page + board button per Gene's ratified shape); ABS-09 evidence fix
  merged via Mayor lane (c21073b).
- **qbxj**: surface the OG/Twitter unfurl capability visibly (share-card
  preview box) — head metadata scores zero if no agent can see it.
- **seva** (parked for morning): extra rating criteria as data, feature-flagged,
  ?criteria=true, config.edn default, no reporting views ("that's what APIs
  are for").
- **Rubric gap map** (bottom of gene-final-punchlist.md): headline = the
  never-run speaker-crm area (w10) with ~7/12 items already built (`mz96`).

## 5. Fuel economy (the 35% night)

One genekkanban account, per-model pools. Judges stay spark×5 (series
sanctity — a 5→3 cut was made and reverted within the hour). Workers moved
sol→terra. All coder seats (cfp3/4/6, dev-a/b/c) flipped to terra high when
sol hit 35%. cfp5 to luna high. Mayor stays Fable (meter showed no pressure);
heavy rewrites route through free `codex exec -m gpt-5.6-sol`. dev-c's codex
login turned out to be a separate, exhausted account (limit until Aug 22) —
needs Gene's re-login.

## 6. The repeatable dev-box conveyor ("we've made it repeatable; huzzah")

The night's infrastructure wins, each earned by a failure:

- **merger@anvil is the only account with GitHub access** → it now owns
  /tmp/curtaincall-staging.git tracking GitHub staging directly, refreshed by
  cron every 2 minutes, world-readable — dev seats always have current base,
  zero credentials on anvil. (Replaced the slow scp/bundle path; bundle
  fallback preserved in bin/refresh-anvil-staging-mirror.)
- **bin/dev-seat-watch** + persistent Monitor: stuck dev seats (BLOCKED /
  fuel-dead / stalled / frozen) page the Mayor, not Gene. First alert fired
  within a minute (dev-c fuel-dead) — the watcher proved itself at birth.
- tmux windows renamed dev-a/dev-b; pane bindings in Gene's registry;
  `agent-bridge ensure` resurrects dead seats (rebuilt dev-c as p45).
- **Merge doctrine**: script the hands, keep the eyes. Dev-box patches merge
  ONLY via Mayor review (diff read + doctrine check + independent tests);
  codex sol = free second skeptic on subtle diffs; no model ever says
  "merged" on the Mayor's behalf.
- Model switching via tmux: /model menu answers NUMBER keys; footers verified
  gpt-5.6-terra high on all three seats.

## 7. Losses and their ratchets

- Mayor's uncommitted Write-created docs (API spec, fuel log) were wiped by
  an untracked-file clean in the shared tree → recreated and committed within
  minutes; new rule: **Mayor docs commit immediately on write**.
- Five+ agent-bridge dispatches stalled at enter-sent → every one recovered
  via observe-dispatch; the recovery command in the refusal is the ceremony.

## 8. Production tonight

Promotions #5–#6+: bfbdb53 → production (sidebar/tabs/dashboard stack), then
the auto-promote v2 loop (scans last-10 for newest green, halts loud on
failure) carried d7bc63c and 59afd5b. Overnight standing order: staging green
→ production, dev-box output review-first, wake Gene only for data loss or
production outage.
