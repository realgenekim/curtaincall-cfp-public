# g785 SSE race class and k1tx authorization drive

## g785: the actual race

The randomized board failure was a time-of-check/time-of-use race in SSE fanout.
The push functions chose whether to render from the subscriber registry at
dispatch time, queued work onto the agent, and then re-read the live registry
when that work eventually ran.  A subscriber that registered between those two
moments could therefore receive a stale push for which no per-subscriber HTML
had been rendered.  The resulting `nil` write failed and the broad error path
reaped the newly healthy stream.  That is how a harmless ordering difference
could look like a flaky promise timeout and, in the integration path, silently
lose downstream work.

This is a class, not a point bug: asynchronous fanout must carry an immutable
target/payload snapshot across the queue boundary, and failure cleanup may only
reap a subscriber that belonged to that snapshot.  A registry re-read after an
async handoff is a new authorization/identity decision and is unsafe unless it
is explicitly reconciled.  The g785 ratchet exercises all 64 combinations of
the relevant before/after registration ordering so future fanout code cannot
quietly reintroduce the same assumption.

## k1tx: current-staging persona drive

Base under test: `e14b4030d0b10938981cc1d1706478f41adf3a36`.

The immutable Judge Sandbox fixture was restored and served from that exact
checkout on isolated port 20531.  Its boot receipt proved all three demo
personas had valid independent sessions before the drive began.

The earlier `k1tx-02` patch was a regression witness over an already-shipped
boundary; it added no competing authorization mechanism.  On the unmodified
base, the speaker drive found:

- `/events/new` redirected to `/portal`, while the organizer received 200.
- The speaker received 403 from announce, board, capture, committee, comms,
  deliverables, embed, the exports index, files, form, inform, log, replay,
  review, schedule, settings, speakers, speaker creation, and submissions.
- The organizer received a successful page (200, or the intentional submissions
  redirect to the board) from the same organizer rail.
- The speaker intentionally retained 200 access to their event participation
  dashboard and details, plus the public JSON/ICS/llms.txt exports.

The remaining defect was structural.  A chair reached every new event-scoped
GET by default, and the router had no finite declaration saying whether that
route was public, speaker, reviewer, or organizer-only.  The old point checks
could prove today's pages but could not make tomorrow's new organizer page fail
closed.

The fix adds a second server boundary at the matched-route layer.  All 36 live
`/events/new` and `/events/:slug...` method/template pairs have an explicit
capability; a missing or unknown declaration returns 403 even for a chair.  An
independent persona-by-surface oracle matches that manifest to the real router,
so adding a route changes the inventory and fails the test until its audience
is deliberately declared.

After the fix, the isolated HTTP drive repeated the 26 representative surfaces
in both directions: all organizer outcomes remained successful, every
organizer-only speaker outcome remained 403, `/events/new` remained a 302 to
`/portal`, and the intended speaker/public reads remained 200.  No demo persona
screen regressed.

Verification also forced one intentional exception into the declaration:
`POST /events/:slug/mcp` is public at the transport boundary so anonymous MCP
initialize/discovery and public commands work; command authorization remains
inside the MCP protocol handler.  The first full-suite run caught an incorrect
organizer-only declaration there.  With that corrected, the complete fast suite
passed 717 tests and 7,597 assertions.  After rebasing onto staging `e238f6b4`
(including the disjoint pvva.5 and esva ships), the exact publication candidate
passed 718 tests and 7,638 assertions.
