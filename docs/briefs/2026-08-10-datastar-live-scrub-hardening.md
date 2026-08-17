# Brief: harden datastar-kit against the Live Scrub gotchas

**For:** a coding agent with this repo checked out. **From:** the main-loop
session, 2026-08-10 ~00:40. **Why now:** we shipped the "Datastar Live"
scrub tonight and hit every sharp edge on the way; the helpers must bake
those edges in so they cannot recur. Skill doc is already updated
(`~/.claude/skills/datastar-sse/SKILL.md`, "THE LIVE SCRUB" section at top) —
this brief is the CODE half.

## Context (read first, 5 min)

- `~/.claude/skills/datastar-sse/SKILL.md` — the new "THE LIVE SCRUB"
  section states the four rules. The helper you're building enforces them.
- `src/datastar_kit/ds.clj` — the helper library. `sse-mount` (~line 242)
  is the precedent: a field-learned gotcha promoted into the ONLY way to do
  the thing, with the war story in its docstring.
- `src/cfp_scheduler_killer/views.clj` `time-travel-bar` — the current
  hand-written live-scrub attrs (the thing to replace).
- `src/cfp_scheduler_killer/server.clj` `sse-fragment-response` — the
  server-side one-shot.

## Deliverables

1. **`ds/live-scrub` in `src/datastar_kit/ds.clj`** (place beside
   `sse-mount`):

   ```clojure
   (defn live-scrub
     "Attrs for a CONTINUOUS control (slider/scrubber) that live-patches a
      region through a one-shot SSE @get — 'Datastar Live' (Gene, 2026-08-09).
      Bakes in the field-learned gotchas:
      - SINGLE-WORD signal, asserted (Datastar camelCases hyphens: $at-index
        silently becomes $atIndex and the expression breaks);
      - __throttle, never __debounce (debounce waits for the hand to PAUSE;
        throttle repaints WHILE it moves);
      - NO legacy handlers: never merge :onchange/:oninput onto the same
        element — they race the patch and a form submit reloads over it."
     ([signal url-prefix] (live-scrub signal url-prefix 150))
     ([signal url-prefix throttle-ms]
      (let [nm (name signal)]
        (assert (not (clojure.string/includes? nm "-"))
                (str "live-scrub signal must be a single word: " nm))
        {(keyword (str "data-star-bind:" nm)) ""
         (keyword (str "data-star-on:input__throttle." throttle-ms "ms"))
         (str "@get('" url-prefix "' + $" nm ")")})))
   ```

2. **Refactor `views/time-travel-bar`** to use it — delete the hand-written
   `:data-star-bind:atidx` / `:data-star-on:input__throttle.150ms` pair and
   merge `(ds/live-scrub :atidx (str fragment-path "?at-index="))` instead.
   Do NOT reintroduce any `onchange` — its deletion was tonight's bug fix.

3. **Sync the canonical copy**: add `live-scrub` (and `sse-mount` if absent)
   to `~/.claude/skills/datastar-sse/ds-reference.clj`, and update the
   SKILL.md Live Scrub code sample to call `ds/live-scrub` rather than
   hand-writing attrs.

4. **A unit test** in `test/datastar_kit/` (or nearest test ns): asserts the
   generated attrs exactly, and that a hyphenated signal name throws.

## Verify (must run, must be green)

- `clj-nrepl-eval -p $(cat .nrepl-port) "(require 'datastar-kit.ds 'cfp-scheduler-killer.views :reload) :ok"`
- `make runtests-once` in background — `polish-test/scrub-slider-wiring-test`
  must stay green (it asserts throttle present + onchange ABSENT).
- Live: fresh board tab, drag the dev-strip slider — board repaints during
  the drag, URL never changes, no page reload.

## Do not

- Do not touch the board table markup (another agent's active lane).
- Do not add a persistent SSE connection for the scrub — the one-shot
  response IS the design (see skill doc for why it's safer).
- Do not commit files with hunks that aren't yours (`git diff` each file
  first — CLAUDE.md tree discipline).
