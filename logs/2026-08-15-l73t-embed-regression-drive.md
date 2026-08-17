# l73t embed/widgets regression drive

Date: 2026-08-15 UTC

## Scored-window investigation

- EMB 75.0 build: `1df449d`
- EMB 63.2 build: `7733d730`
- The only public-widgets change inside that window was `6442bf3`, which fixed
  duplicated expanded copy and allowed public-but-unplaced talks in My Schedule.
- Suspects `93a91d0` and `0d38c006` landed after both scored builds and did not
  change public widget behavior.
- The durable evaluator finding was discoverability: the canonical Program page
  did not link the already-working Sessions, Speakers, Gallery, or My Schedule
  routes. That was fixed forward by `f0da3f5` (`PB-163`). Reverting a commit from
  the scored window would remove fixes and would not restore discoverability.

Decision: **FIX-FORWARD, not revert.** The correct product fix is `f0da3f5`,
which is already in staging and production. This change adds the missing live
HTTP ratchet; it does not claim a restored rubric score without a new immutable
evaluation.

## Production drive

Driven anonymously against `https://curtaincallcfp.com` with event slug
`enterprise-ai-summit-charlotte-2026`:

- `/program/<slug>`: HTTP 200; contains the `Explore the program` navigation and
  visible links for Sessions, Speakers, Speaker gallery, and My schedule.
- `/agenda/<slug>/sessions`: HTTP 200; distinct Sessions page with populated
  session-list markup.
- `/agenda/<slug>/speakers`: HTTP 200; distinct searchable speaker directory.
- `/agenda/<slug>/gallery`: HTTP 200; distinct speaker-photo gallery.
- `/agenda/<slug>/my`: HTTP 200; distinct My Schedule itinerary.

Repeatable command:

```sh
python3 bin/e2e_drive.py \
  --base https://curtaincallcfp.com \
  --public-program-slug enterprise-ai-summit-charlotte-2026
```

Result: **10 checks, 0 failures** on 2026-08-15 UTC.

## Ratchet

`bin/e2e_drive.py` now begins at the canonical Program page as an anonymous
browser, verifies every public browse door is present, follows all four URLs,
asserts a surface-specific marker, and proves the responses do not collapse to
one body. `--public-program-slug` runs this read-only proof independently, so it
can safely verify a deployed event without organizer credentials or mutations.
This catches the deployment/wiring/discoverability failure that an in-process
green suite cannot.
