# 2026-08-10 — Production deploy (revision 00012-vhp)

**Asked:** Gene: "let's go ahead and do a production deploy."

**What shipped:** everything through commit `7c8a4ec` — the full review board
(ledger, quick-rate via postJSON+SSE, :target rate card), submission detail
(Basecamp thread + sticky speaker rail), tonight's sparkline recolor
(submissions red / reviewed green + raw ratings·comments counts) and the
histogram 1–5 axis + stars-left column (T1+T5).

**How:** built from a clean git worktree of HEAD, NOT the working tree — the
tree carried another lane's uncommitted mission-control work. Finding along
the way: **HEAD did not compile standalone** — views.clj at HEAD calls
`ds/live-scrub` but the fn lived only in the uncommitted ../datastar-helpers
extraction (callee-before-caller at commit granularity). Patched the ephemeral
worktree with the current datastar_kit source; the real fix is that lane
committing deps pin + deletion atomically (they've since committed c046820).

**Evidence (measured at the meter):**
- Cloud Run revision swyx-cfp-saas-killer-00012-vhp serving 100%
- https://swyx-cfp-saas-killer-109637679549.us-west1.run.app
- prod logs: `:store-loaded :backend :postgres` — 2,233 facts, 51 events, 208 people
- auth wall: / and /board 302→login; /login 200; /dev/sse-state gated
- served /css/app.css contains tonight's `hcol` histogram rules

**Still open:** swyx demo package on prod (other agent's lane); Ann's
rehearsal (bd -nuk); dashboard tile label bug filed as bd -z9e.

## Addendum — the landing ships, second deploy (00017-ssj)

Post-deploy, one hour of live steering produced: the Curtain Call landing
at / (bd -7e1, Zen hero + hate/proud mirror + duo cards + HISTORY.LOG tape),
Gene's second ruling that the landing is for EVERYONE (first cut redirected
signed-in users to /events — "this is terrible"), the Basecamp two-row
Recent feed on the dashboard, the sidebar claiming the top of the viewport
(whoami moved into the breadcrumb row), and the T1+T5 histogram work before
that. All rolled to prod: curtaincallcfp-00017-ssj, / serves the landing
publicly (verified 200 + tape present). Suite green: 235/2265/0.

Housekeeping: bd -u04 closed (target/classes guard in server-dev + nrepl —
the shadow bit three times in 24h). Scrubber-on-dashboard finding handed
off: dev-strip renders everywhere, but only board handlers pass
:time-travel, so the dashboard strip has no scrub bar. That lane owns it.
