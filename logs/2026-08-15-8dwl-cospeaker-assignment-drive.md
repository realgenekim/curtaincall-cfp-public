# 8dwl co-speaker assignment drive — 2026-08-15

## Baseline reproduction

- Exact code: detached staging `f9e2eb15378583f365d7cfbdba7fb31e5703d6c8`.
- Isolated Judge Sandbox: `make reset-jsonl-server PORT=20538`; golden fixture
  SHA-256 `d8ec652d8a7fe31fecb5c13da19de80cf1d9ec0c8fa3e554e4b8552e63371de6`.
- Organizer GET `/events/enterprise-ai-summit-charlotte-2026/speakers`: HTTP 200,
  one standalone “Add speaker” control, no session assignment workflow.
- Organizer GET `/events/enterprise-ai-summit-charlotte-2026/board`: HTTP 200,
  no session-speaker assignment action.
- Public GET `/api/v1/events/enterprise-ai-summit-charlotte-2026/sessions`: 30
  sessions; every `speakerIds` vector had cardinality 1.

## Ratchet result

- The new family contract was run against the baseline before the source fix.
  It failed on the absent literal “Assign or reassign co-speakers” organizer
  workflow (23 tests / 124 assertions at the randomized fail-fast boundary).
- The contract drives three sessions through non-sorted assignment ordering,
  retries, removals, shifted replacements, raw submitted-snapshot checks,
  export speaker-id checks, and full append-log replay equivalence.
- The first post-fix suite found one interaction: a full-roster datalist exposed
  a completed speaker's name on an “incomplete profiles” filtered page. The
  picker now follows the active roster filter, preserving that existing family
  contract.

## Patched Judge Sandbox drive

- Restarted the immutable sandbox on port 20538, proved all three personas,
  then proved the dev reload token changes after a CSS touch (`alive`).
- Organizer speakers page: HTTP 200 and exactly one visible “Assign or reassign
  co-speakers” panel covering the 30 public-program sessions.
- Selected session `2157600a-a5bb-432b-920f-98ab078a32ca` and existing roster
  person `a7fa8ac5-45cb-443d-9652-a61765eacf8b`.
- POST to the session-literal assignment path returned 303 to the same session
  anchor. The public session API speaker count changed `1 -> 2`.
- A final cold-source drive also counted the assigned session title on the
  speakers page: occurrences changed `2 -> 3` after assignment, proving the
  reused person's roster row gained that session as well as the assignment
  panel and public API changing.
- POST to the session-and-person-literal removal path returned 303 to the same
  anchor. The public session API speaker count changed `2 -> 1`.
- The speaker persona received HTTP 403 from the assignment POST. An organizer
  attempt with a person outside the event roster received HTTP 422 and the
  literal validation message “Choose a speaker from this event roster.” The
  public speaker count remained 1 after both refusals.

## Open

- No defect remains in the reproduced add/reuse/remove/reassign path. The UI is
  intentionally limited to accepted-and-informed public-program sessions; the
  immutable submitted speaker blocks remain history while named assignment facts
  drive the current projection.
