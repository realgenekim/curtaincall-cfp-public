---
name: light-up-tech-tree
description: Update the canonical Curtain Call technology tree (docs/tech-tree.md on main) — flip node statuses, add nodes, append the changelog — in the SAME batch as the work being mapped. Fire whenever a lane starts, ships, discovers, or meter-verifies anything; whenever an agent is launched or lands; or when Gene asks "update the tech tree" / "where does X fit".
---

# Light Up Tech Tree

## The one file

**Canonical: `/Users/genekim/src.local/sessionize-sched-killer/docs/tech-tree.md`**
(branch `main`; edit by absolute path from ANY worktree — staging lanes included).
`curtain-call-staging/docs/TECH-TREE.md` is a pointer stub, never edited.
Two trees forked once (2026-08-11) and merging them cost a session detour:
**never fork, merge on write.** Gene views it in Mothership
(`bin/mothership-open docs/tech-tree.md`) — pushes to main make it durable.

## The update protocol (mirrors the doc's own header — keep them in sync)

1. **Same batch as the work.** Launch an agent → its node goes 🧪 with the
   lane's name. Agent lands + you independently verified → ✅. Filed a bead →
   🔓 node citing the bead id. Never batch tree updates for "later".
2. **Statuses:** `✅ shipped` · `🧪 in flight (named lane)` · `🔓 unlocked` ·
   `🔒 locked (edge names the prerequisite)`.
3. **✅ only when verified at the meter** — a live URL probed, a green suite
   YOU re-ran, a real rehearsal. Code existing ≠ shipped. Deployed-to-prod is
   its own ✅ line when it happens (with the deploy sha).
4. **Node format:** `**Name** (`bead-id`) — one-line what · *unlocks: …*`.
   ERA II uses the chain style (✅/🧪/🔓 lines with │ ▼ arrows) — match
   whichever grammar the surrounding era uses.
5. **One line per change appended to the Changelog:**
   `- YYYY-MM-DD HH:MM <seat> — moved X to ✅ / added Y (unlocked by Z)`.
6. **Never delete a node** — strike through with the reason.
7. Eras gate eras: SUBMISSION (P0) > current research ⭐ > THE SHOW >
   PLATFORM HEALTH > AFTER THE HORN. New work slots into the era matching its
   deadline value, not its interestingness.

## Mechanics

- Small anchored Edits (the file is shared across lanes and Gene reads it
  live); re-read before editing — agents update it concurrently.
- After editing: `git add docs/tech-tree.md && git commit -m "docs: tech tree — <what moved>" && git push origin main`.
  Tree commits on main are cheap and safe; push so other seats' pulls see it.
- When SPAWNING an agent, put the tree update IN its brief (flip its own node
  on completion, ✅ only per rule 3) — and still audit its edit when it lands.
- Bead ids come from `bd` (e.g. `5z0`, `a3b`, `92x`) — cite them; the tree is
  the map, beads carry the detail.
