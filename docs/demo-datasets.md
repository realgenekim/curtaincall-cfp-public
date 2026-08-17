# The demo datasets — which event is canonical, and why

*Written 2026-08-11 ~01:00, after a REPL survey of the production store
uncovered what "we're using the wrong event" meant. Update this file when a
dataset is superseded — an archived spike-event with no explanation is a trap
for the next agent.*

## The four demo events in production (plus the replay corpus)

One shared production Postgres serves both Cloud Run services. Surveyed via
REPL (`store/load!` on the `:postgres` backend, reads through the domain
getters only):

| event | slug | subs | submission spread | status |
|---|---|---|---|---|
| AI Engineer Code Summit | `ai-engineer-code-summit` | 502 | **41 distinct days, Jul 1 → Aug 10** | **live — THE canonical demo dataset** |
| AI Engineer Code Summit (superseded demo) | `ai-engineer-code-summit-2026` | 500 | 1 day (all 2026-08-10) | archived — the spike problem |
| Enterprise AI Summit | `enterprise-ai-summit-charlotte-2026` | 81 | 1 day (all 2026-08-10) | live — Gene's corrected event; content spread, timestamps NOT |
| Enterprise AI Summit Charlotte | `enterprise-ai-summit-charlotte` | 8 | 1 day | archived — Gene's original seed |

The story (Gene, 2026-08-11): it started as one event for Gene + one for
swyx. All submissions landed on the same wall-clock day — every time-based
view (the scrub bar, "31 days left", the submissions sparkline) showed a
single vertical spike instead of a CFP's real life. So a new pair was
written with submissions **spread across the call window**; the old pair was
archived with "(superseded)" in the name. `ai-engineer-code-summit` is the
one that got the full treatment — 41 days of submissions with reviews
following. Anything that demonstrates time (scrubber, sparkline, board
progress) must be built on spread-timestamp data, never the spike events.

## The replay corpus is a FIFTH dataset — and it currently bursts

`resources/replay/aie-corpus.json` (built by `build_corpus.py`, checked by
`selfcheck.py`): 190 timeline entries — 40 submissions, 71 ratings, 72
comments, 7 status changes — each with `offset-secs` from CFP open across a
21-day `window-secs`. This is what `/api/replay/start-demo` plays into each
fresh `aie-replay-*` event.

**The trap:** `play-entry!` drives the real domain verbs, which stamp facts
via `store/now-iso` — so no matter what `offset-secs` says, every replayed
fact is recorded at wall-clock play time. A `skip-to-end!` demo therefore
has a ~60-second recorded history (the scrub bar Gene saw spanned
07:36:47 → 07:37:49), reproducing exactly the spike problem the corrected
events were written to kill.

**The seam that fixes it:** `store/*clock*` (store.clj) — bound to a
simulated instant, `now-iso` returns it, so "fabricated demo facts can carry
the moment they pretend to have happened while still walking through the
ordinary domain verbs." This is how the corrected events' spread was staged
on 2026-08-10. The replay path (`replay/tick!`) must bind it per entry to
`cfp-opens-at + offset-secs`. (Fix in flight the night this doc was born.)

## Who uses which dataset

- **Welcome showcase** (`welcome-showcase`, server.clj): picks the most
  submission-rich live event → in prod that is `ai-engineer-code-summit`
  (502). Correct by construction; in a fresh dev sandbox it falls back to
  whatever is seeded.
- **Ghost sidebar / "See a live review board" / Start AIE replay demo**:
  creates a fresh per-user `aie-replay-*` event from the replay corpus.
  User-owned, so newcomers get full membership without touching the four
  shared events.
- **Demo-mode personas** (`/api/demo-login`, swyx demo service): seeded
  people on the AIE demo event.

## How to re-survey (REPL, read-only)

```clojure
(require '[cfp-scheduler-killer.store :as store]
         '[cfp-scheduler-killer.events :as events]
         '[cfp-scheduler-killer.submissions :as submissions])
(store/postgres?)   ;; VERIFY the backend before trusting anything
(store/load!)
(doseq [slug ["ai-engineer-code-summit" "enterprise-ai-summit-charlotte-2026"]]
  (let [e (events/event-by-slug slug)
        ss (submissions/for-event (:id e))]
    (println slug :subs (count ss)
             :days (count (distinct (map #(subs (str (:created-at %)) 0 10) ss))))))
```

Reads only, through the domain getters — the database interface rule
(CLAUDE.md) applies to surveys too.
