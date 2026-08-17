/* Tab accepts the example text.
 *
 * An input carrying `data-ghost-fill` shows a REAL example value as its
 * placeholder ("Enterprise AI Summit", "Charlotte, NC"). Pressing Tab while the
 * field is still empty accepts that example and moves on, the way shell
 * completion and browser autofill already behave. Typing anything first turns
 * the enhancement off for that field, because the placeholder is no longer what
 * you meant.
 *
 * WHY THIS IS ALLOWED TO EXIST (global CLAUDE.md, the Datastar NEVERs):
 * this is form autofill — a browser-native affordance, in the same family as
 * clipboard, cursor and drag, which is the one carve-out for client JS. It sets
 * the value of the field the user is standing in and then gets out of the way.
 * It holds no state, reads no server state, decides nothing about what to
 * render, and never touches an element other than the focused input.
 *
 * The dispatched `input` event is the load-bearing part: it is a real bubbling
 * event, so Datastar's binding updates the signal and the debounced preview
 * POST fires exactly as if the characters had been typed. The marquee therefore
 * comes back FROM THE SERVER, rendered by the same code that renders every
 * other keystroke — no second rendering path, and no DOM the server didn't
 * write.
 *
 * Do not extend this to anything else. The moment it starts filling a field
 * from something other than that field's own placeholder, it is client-side
 * state and it belongs on the server.
 */
(function () {
  "use strict";

  document.addEventListener("keydown", function (e) {
    if (e.key !== "Tab" || e.shiftKey || e.altKey || e.metaKey || e.ctrlKey) return;

    var el = e.target;
    if (!el || !el.matches ||
        !el.matches("input[data-ghost-fill], textarea[data-ghost-fill]")) return;
    if (el.value !== "" || !el.placeholder) return;

    // Accept the ghost, then let Tab do its ordinary job — focus still
    // advances, because we never call preventDefault.
    el.value = el.placeholder;
    el.dispatchEvent(new Event("input", { bubbles: true }));
  });
})();
