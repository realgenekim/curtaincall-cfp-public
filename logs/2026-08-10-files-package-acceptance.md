# Files package acceptance — 2026-08-10

Bead: `sessionize-sched-killer-7nr`

## Outcome

The application now owns the complete conference-file loop:

1. an organizer creates a typed file request with instructions and an explicit due date;
2. the speaker uploads through the existing magic-link portal;
3. identical retries are no-ops, while changed bytes append immutable `v2`, `v3`, and later versions;
4. organizer and speaker share one append-only comment thread;
5. the organizer sees every file in one event-scoped library and can export the latest set as ZIP;
6. a speaker can upload a headshot, whose versioned file becomes the canonical profile URL.

This is deliberately not a mutable folder abstraction. The domain is the algebra
`request -> file aggregate -> immutable versions + comments`; local disk and GCS
are replaceable byte-store effects outside the decision and fold namespaces.

## Facts and folds

- `task.installed` now carries optional `:due-on`, `:instructions`, and `:file-kind` fields.
- `file.version-added` creates or advances one stable file aggregate under `:files`.
- `file.comment-added` accumulates a two-sided file conversation.
- Uploading against a task appends `file.version-added` and `task.completed` in one batch.
- `:due-on`, `:uploaded-at`, and comment `:at` are serialized as ISO strings and restored to domain time by the pure fold.

## Provider boundary

`cfp-scheduler-killer.io.blob` is the provider-neutral byte boundary. Tests bind
recording in-memory functions and perform no network I/O. Development defaults to
local disk. A configured `GCS_UPLOAD_BUCKET` (or the existing snapshot bucket)
uses the already proven GCS JSON copy path. The broader provider extraction remains
tracked by `sessionize-sched-killer-973.1`; no domain, fold, handler view, or test
fixture knows GCS credentials.

## Safety and compatibility

- Speaker-owned upload/comment/download routes pass through the default-deny auth gate and then re-check exact submission ownership.
- Organizer routes remain event-scoped and reject cross-event files.
- Only Headshot aggregates are reachable through the anonymous public image route.
- Uploads accept PDF, PowerPoint, Keynote, PNG, or JPEG and reject empty, unsupported, and over-25-MB files.
- Existing `slides-url` and `headshot` task facts retain their historical URL semantics, while the portal presents their upgraded upload controls. New explicit file requests use `task-type=file`.
- No file deletion exists. A replacement is another version.

## Verification

- Focused multipart/auth/version/comment/headshot route story: 1 test, 25 assertions, 0 failures.
- Focused file/request algebra: 2 tests, 5 assertions, 0 failures in the final focused slice; the full suite contains the remaining algebra assertions.
- Architecture + complete GET route contract: 5 tests, 328 assertions, 0 failures.
- `make runtests-once`: 325 tests, 3,270 assertions, 0 failures.
- Full non-fail-fast `bin/kaocha unit`: 325 tests, 3,270 assertions, 0 failures, exit 0.
- Cold JSONL server plus live HTTP driver: 263/263 checks, including `/events/<slug>/files`.
- Route topology: 158 routes (65 GET, 93 POST), SHA-256 `2e479c299edeb5a301f4954762007721c7a62a047bc88c89880c52d76c65230b`.

## Literal evaluator-visible strings

- `Request a file`
- `Deliverable type`
- `Due date`
- `Upload file`
- `Upload a new version`
- `show history`
- `Conversation`
- `Download all as ZIP`
- `PDF, PowerPoint, Keynote, PNG, or JPEG · 25 MB maximum`

## Operator work not performed

- No DDL, PostgreSQL mutation, Cloud Run deploy, GCS provisioning, secret change, or production verification was performed.
- Production must provide a writable GCS bucket configuration before uploaded bytes can survive instance recycling.
