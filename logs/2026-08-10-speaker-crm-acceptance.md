# Speaker CRM acceptance — grounded extra credit without mini-Salesforce

**Bead:** `sessionize-sched-killer-ncu`
**Date:** 2026-08-10
**Status:** accepted locally; all implementation, broad-suite, cold-boot, and
HTTP gates are green.

## Outcome

The organization-level `/people` surface now turns the existing event log into
a tenant-scoped contact directory. It does not introduce a second person
database or a parallel sales workflow. Committee membership determines which
events an organizer may see; canonical speaker identities, submissions, and
event facts remain authoritative.

The product-shaped spine follows the ethnographic evidence:

- find repeat speakers across authorized events;
- see their talks, roles, organizations, and event activity in one place;
- record internal notes and tags as facts;
- preview a CSV before importing it into one explicit event;
- reuse an email-matched canonical identity instead of creating a duplicate;
- push an existing person into another authorized event through the normal
  speaker verb;
- save useful directory filters;
- compose personalized outreach for a selected cohort, then record only the
  human-reviewed draft. The CRM never sends autonomously.

## Rubric map

| Item | Implemented proof | Product doctrine |
|---|---|---|
| CRM-01 | `/people` cross-event directory with canonical people and authorized event history | One projection over the event log, not a shadow CRM store |
| CRM-02 | Composable text, organization, role, event, and tag filters | Filters reflect observed diligence dimensions |
| CRM-03 | `/people/:person-id` detail with talks, events, roles, and internal activity | Foreign-event activity is absent, not merely hidden in HTML |
| CRM-04 | Add/remove tags through `crm.tag-added` and `crm.tag-removed` | Append-only facts; no destructive update |
| CRM-05 | CSV preview and explicit import into one target event | Preview appends nothing; invalid or inaccessible targets fail closed |
| CRM-06 | Case-insensitive email identity reuse during import and push | Duplicate prevention, not destructive person merging |
| CRM-07 | Derived Relationship → Invited → Submitted → Confirmed/Withdrawn pipeline | Derived from canonical event facts; no parallel funnel state |
| CRM-08 | The local rubric analysis contains no authoritative requirement text | Not claimed until the original evaluator specification is available |
| CRM-09 | Saved and removable named segments | Segment filters are facts; deleting a saved segment is a removal fact |
| CRM-10 | Push an existing person into another authorized event | Reuses the ordinary speaker-add domain verb and is idempotent |
| CRM-11 | Multi-recipient outreach composer with resolved `{name}`, `{email}`, and `{organization}` | Preview is pure; recording appends `crm.outreach-drafted`; never `comms.sent` |
| CRM-12 | Directory metrics for contacts, organizations, repeat speakers, and represented events | Metrics are projections over the same tenant-scoped result set |

## New fact types and folds

| Fact | Folded projection |
|---|---|
| `crm.note-added` | `:crm-notes` |
| `crm.tag-added` / `crm.tag-removed` | `:crm-tags` |
| `crm.segment-saved` / `crm.segment-removed` | `:crm-segments` |
| `crm.outreach-drafted` | `:crm-outreach-drafts` |

CSV import and push-to-event intentionally reuse the existing named speaker
verbs and folds. No direct store or database write was added.

## Verification receipts

- Focused pure-domain and Ring acceptance plus route architecture/contract:
  **14 tests, 454 assertions, 0 failures**.
- The route topology is locked at **183 routes**: 71 GET and 112 POST.
- Pure tests prove tenant intersection, composable filters, authorized history,
  event-sourced notes/tags, saved segments, template resolution, pure preview,
  and a single reviewed-draft decision.
- Ring tests prove signed-in rendering, foreign-person 404, named-fact writes,
  idempotent push, inaccessible-target 422 with no fact, read-only CSV preview,
  email reuse on import, pipeline/segment UI, and no `comms.sent` fact from the
  outreach composer.
- Repository fast full suite: **362 tests, 3,628 assertions, 0 failures**.
- Required non-fail-fast `bin/kaocha unit`: **362 tests, 3,628 assertions,
  0 failures**.
- Fresh isolated JSONL boot loaded **0 events** before seeding and registered
  the CRM folds through ordinary namespace loading.
- General cold HTTP drive: **263/263 checks passed**.
- Dedicated `bin/crm_drive.py`: **25/25 checks passed** against the cold
  process, including `/people`, contact detail, email search, composer copy,
  pure preview, and reviewed-draft recording with no `comms.sent` fact.
- Targeted clj-kondo over the CRM implementation/tests and architecture guard:
  **0 errors, 0 warnings** after removing one stale test alias.

The isolated sibling sandbox and its fictional event log were removed after
the drive. The shared JSONL log and PostgreSQL database were untouched.

## Manual acceptance path

1. Sign in as an organizer who belongs to at least two events and open
   `/people`.
2. Confirm the counts and repeat-speaker metric, then combine organization,
   role, event, tag, and free-text filters.
3. Open a contact and verify only authorized event history appears.
4. Add a note and tag, reload, then remove the tag. Confirm the history remains
   append-only.
5. Save a useful filter as a segment, reopen it, then remove it.
6. Preview a CSV containing one existing email and one new email. Confirm the
   preview appends nothing; import and verify exactly one new identity.
7. Push an existing person into another authorized event twice. Confirm the
   second operation is a no-op and the target roster has one person.
8. Select contacts for outreach, preview a personalized message, edit it, and
   choose **Record human-reviewed draft**. Confirm nothing is sent and no
   contacted clock advances.
9. As a different organizer, request the first organizer's foreign contact
   URL and target-event mutations. Expect 404/422 and no fact.
