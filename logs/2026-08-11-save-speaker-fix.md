# Save Speaker persistence fix — 2026-08-11

Bead: `sessionize-sched-killer-g9v`

## Failure

The inline announce-page editor rendered populated speaker fields, but `Save
speaker` was explicitly disabled and no POST route existed.

## Implementation

- Converted the speaker fields to a server-rendered POST form.
- Added the event-scoped `/api/events/:slug/speakers/:person-id` route.
- Added one `event.program-speaker-updated` fact that updates the durable person
  identity/profile and any invited-roster entry atomically.
- Made the shared public-speaker projection prefer canonical person name,
  organization, tagline, bio, and headshot values so every public surface stays
  DRY.
- Preserved submitted values and field errors on invalid input without writing.

## Verification

- `make runtests-once`: 305 tests, 2,976 assertions, 0 failures.
- Browser: submitted Art Smalley's existing values from the exact supplied edit
  anchor, received the same edit-anchor redirect, hard-reloaded, and confirmed
  every field persisted and the button remained enabled. No public speaker
  content was changed during this proof.
