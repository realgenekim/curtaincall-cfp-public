# Nav elements — rails, breadcrumbs, and the product's surface area

*Written 2026-08-09 (Gene's ask, driving the live app: "the /events page needs
the sidebar we used on the step-1 screen — propose it, and write down the nav
elements so we can see the surface area"). This is the spec; the sidebar/
organizer-shell fns are the auth/sidebar work-stream's files today.*

## The altitude model

Every screen sits at one of three altitudes, and the altitude decides its
chrome. One rail component (`views/sidebar`) serves both signed-in altitudes —
it already branches on "is there an event in context"; we enrich the account
branch rather than fork a second sidebar.

| Altitude | Who | Chrome |
|---|---|---|
| **L0 — Public** | speakers, attendees | No rail, no crumbs. Event masthead is the identity. |
| **L1 — The hall** (`/events`, `/events/new`) | signed-in organizer, pan-event | **Account rail** (this proposal) |
| **L2 — The room** (`/events/:slug/**`) | organizer inside ONE event | **Workspace rail** (exists: step hub + groups) |

## Surface-area inventory

| Route | Page | Altitude | Rail | Breadcrumb |
|---|---|---|---|---|
| `/events` | All events | L1 | account | none (root) |
| `/events/new` | Create event | L1 | account | Events › New event |
| `/events/:slug` | Dashboard | L2 | workspace | Events › {event} |
| `…/form` | Create CFP form | L2 | workspace | Events › {event} › Form |
| `…/board` | Review Board | L2 | workspace | Events › {event} › Board |
| `…/submissions`, `…/submissions/:id` | Submissions / detail | L2 | workspace | … › Submissions |
| `…/people/:pid` | Person | L2 | workspace | … › People › {name} |
| `…/inform` | Inform Speakers | L2 | workspace | … › Inform |
| `…/schedule` | Schedule | L2 | workspace | … › Schedule |
| `…/exports` | Exports & API | L2 | workspace | … › Exports |
| `…/comms`, `…/log`, `…/settings` | quiet pages | L2 | workspace | … › {page} |
| `…/capture`, `…/replay` | operator tools | L2 | workspace | … › {page} |
| `/cfp/:slug` (+ `/submitted`) | Public CFP | L0 | none | none |
| `/agenda/:slug` | Public agenda | L0 | none | none |
| `/portal` | Speaker portal | L0 (speaker hat) | none | none |
| `/login` | Sign in | L0 | none | none |

## The rail is ONE event's spine — never a list of events (ratified 2026-08-09)

Gene, looking at prod (curtaincallcfp, one event): **"I love what's there"** —
and, on seeing every list-flavored alternative: **"I don't think I ever want a
list of events in the sidebar."** The invariant his instinct protects: **the
rail never grows with event count.** It is always the full spine of exactly one
event — the map of one room. Switching events is the MAIN surface's job (the
table's links); the rail's only way out is `All events`.

The one open question — whose spine when the URL names no event — is answered
by the **working event**: per-person, server-side (last event visited, stored
like `create-drafts`; fallback = the nearest upcoming event with an open call).
With one event on the instance this degenerates to exactly today's shipped,
loved behavior.

The scenario table (exhaustive):

| # | Situation | Page | Rail shows |
|---|---|---|---|
| 1 | Zero events | `/events` | Top strip + **ghost spine** (muted lifecycle map, same treatment as birth mode). |
| 2 | Zero events | `/events/new` | **Birth mode** (shipped): step 1 "you are here", ghost groups. |
| 3 | One event | `/events` | That event's full live spine (shipped in prod — unchanged). |
| 4 | One event | inside the event | Same spine, active page highlighted (shipped). |
| 5 | Two+ events | `/events` | The **working event's** spine, topped with the event's NAME as the spine header (→ its dashboard) so ownership is unambiguous. No list. |
| 6 | Two+ events | inside event B | B's spine only — sibling events appear nowhere in the rail. |
| 7 | Two+ events | `/events/new` | Birth mode for the event being born; existing events nowhere. |
| 8 | Ten events (replay demos pile up) | `/events` | Identical to #5 — the rail is O(1) forever; scale is the table's problem (filters live in the table, never the rail). |
| 9 | Just ran the replay demo | `/events` | Demo event is last-touched → its spine. Correct: it IS the working event right now. |
| 10 | Only event is past/closed | `/events` | Its spine still renders (an archive is still a room); the heuristic prefers upcoming-open over finished. |
| 11 | Signed out / public pages | `/cfp`, `/agenda` | No rail, ever. |
| 12 | Speaker hat only | `/portal` | No rail — speakers have no hall. |

The invariants:

1. **One spine, always.** The sidebar is the map of a room, never a directory
   of buildings. Rail size is O(1) in event count.
2. **Switching is a main-surface act.** The events table is the switcher; a
   multi-event spine header names its event to kill ambiguity.
3. **Working event is server state per person** — last-visited with a
   deterministic fallback; no JS, no client memory, nothing for a morph to
   clobber.
4. **The duplication question dissolves**: the rail shows one event's *pages*;
   the table shows all events' *facts*. No echo at any scale.

## Breadcrumb rules

- **L1 root (`/events`)**: no crumbs — it IS the root.
- **L2**: `Events › {event name — location · dates} › {Page}`. The event crumb
  always goes to the dashboard; the last crumb is plain text (already the
  `breadcrumb` fn's behavior — keep it).
- **L0**: never. A speaker has no hall to climb back into.

## Ownership note

`sidebar` / `organizer-shell` / `events-list-page` belong to the auth+sidebar
work-stream (2026-08-09 parallel-session boundary). This doc is the spec;
implementation is tracked in beads (see issue created alongside this doc).
