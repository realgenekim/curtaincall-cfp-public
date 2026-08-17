# Domain model v1: events, committees, submissions

*2026-08-08. Build sequence per Gene: (1) event creation → (2) programming
committee → (3) submissions. Match Gene's flow as the zero-config default;
leave the door open for sophisticated flows (multiple committees per
track/topic) without building bureaucracy now.*

## The core design move: committee = roster + scope filter, NOT a permission fortress

The incumbents (Sessionize evaluation plans, Sessionboard committees/rounds)
model committees as access control — who may see what. That produces the
isolation troughs. We invert it:

```
event ──1─┬─* committee        {name, roster(memberships), scope, coverage-target}
          │                      scope = FILTER over submissions:
          │                        {:all true}                        ← Gene's world (default)
          │                        {:field :track :in ["AI Infra"]}   ← multi-track world
          ├─* submission        (form-snapshot, answers JSONB, status, notified?, source)
          │      ├─* rating     (person × submission, stars)   ← attach to SUBMISSION,
          │      └─* comment    (person × submission, text)      never to committee
          └─1 form              (field-def vector; see form-builder.md)
```

A committee DOES: (1) default its members' board view to its slice; (2) define
the coverage denominator (Gallimore's 2-review rule computed per scope);
(3) route the push emails for its slice.
A committee does NOT: gate visibility or rating rights. **Review visibility is
collective and event-wide** (`:review-visibility :open`, per-event setting) —
if AIE's ethos answer is "blind," that's a toggle (`:blind-until-rated` /
`:blind`), not a remodel.

**Gene's flow = the degenerate case, zero extra UI**: event creation
auto-spawns one committee ("Program Committee", scope `:all`). Multi-track
later = add committees with filters; boards open pre-filtered, coverage per
slice, cross-committee reading/commenting still open. If assignments are ever
truly needed: an `assignment` table beside ratings — core untouched, because
ratings were always person×submission.

## Entities (hybrid-JSONB house style + append-only events_log)

- `events` — name, slug, dates, tz, settings JSONB (statuses vocabulary,
  review-visibility, submission cap, ingest-email address)
- `people` — email-keyed identity; profile JSONB (bio, headshot, tagline,
  socials, pronouns); magic-link auth
- `committees` — event_id, name, scope JSONB, coverage_target (default 2)
- `memberships` — committee_id × person_id, role (member | chair)
- `forms` — event_id, field-def vector JSONB (see form-builder.md)
- `submissions` — event_id, form_snapshot JSONB, answers JSONB, status
  (7-valued: Accepted/Accept Queue/Pending/Decline Queue/Declined/Withdrawn/
  Draft), notified_at, priority-flag (🔥), source (form | on-behalf-of |
  email-forward), speakers (person refs + per-submission role)
- `ratings` — submission_id × person_id, stars (1–5, halves)
- `comments` — submission_id × person_id, body, visibility (committee | with-speaker)
- `events_log` — append-only, every mutation (house rule)

Later phases (already designed, not in steps 1-3): tasks, schedule
(blocking-sheet model), comms log, exports/API.

## Build sequence

1. **Event creation** — event + auto-committee + seed form (EAIS Charlotte
   template) + public slug + statuses. Acceptance test = the 10-minute timer
   (bd 2tv).
2. **Programming committee** — magic-link invites → people + memberships;
   board renders (honest empty state); push-email wiring. Roster
   derived-friendly: raters/commenters count as active.
3. **Submissions** — public form rendering from field defs + quick-capture
   on-behalf-of (bd d9o); form-def snapshot per submission; push email fires.

## Open decisions (awaiting Gene)

a. Rating scale: keep fulcro heritage 1–5 stars with halves?
b. Committee roles: model a chair/decider (Gene-first-among-equals), or leave
   decision authority social?
c. One person on multiple committees: proposed YES (membership = rows).

## Storage pivot (Gene, 2026-08-08 evening): JSONL event sourcing first

Store = append-only `data/store/events.jsonl`; state = in-memory atom derived by
folding events at boot (reference: arkana-cfo "HL7 and Coding Tool"
hl7_recode/store.py — "Hickey-style"; + joe-payne events_log semantics, which we
already emit). **Zero-setup goal: clone → make server-dev → make seed-demo — no
database.** Mutation fns keep their signatures; the log IS the store; the old
events_log rows are now literally the storage format. Postgres returns later as
a swappable backend behind the same protocol — bd 3s1 (Gene one-shot; migration
= fold the JSONL into Postgres, by construction). Cloud Run deploy wrinkle
(ephemeral disk → single instance + GCS snapshot, or the Postgres backend)
decided in 3s1. Sink registry rides on append!: generic webhook (Zapier),

Mutation design is specified in
[Algebraic domain decisions](algebraic-domain-decisions.md): pure
`state + command -> facts | rejection` decisions inside thin `append!` shells.
Slack incoming-webhook now; Airtable (4u2) and BusyConf-style push email (8ch)
drop in behind the same registry.
