// ---------------------------------------------------------------------------
// datastar-kit.js — Shared JS runtime for Datastar game engine projects
// ---------------------------------------------------------------------------
// This file provides the runtime functions that ds.clj-generated expressions
// call. Load this BEFORE your app.js.
//
// Companion: src/datastar_kit/ds.clj (Clojure expression builders)
//
// == Tier 1: ds.clj contract ==
//   postJSON(url, body)           — fetch wrapper for game engine POSTs
//   showNotification(msg, err?)   — overlay notification (upper right)
//   logAction(type, data)         — log user action to server
//
// == Tier 2: Game engine keyboard ==
//   encodeKey(e)                  — normalize keydown event -> "alt+l" string
//   initServerKeymap(url)         — fetch keymap from server, populate globals
//   Prefix chord support built into the dispatcher
//
// Projects using the server keymap pattern get the full keyboard dispatcher
// for free. Projects using only ds.clj expressions need Tier 1 only.
// ---------------------------------------------------------------------------

// ===== TIER 1: ds.clj contract ==============================================

/**
 * Unified fetch helper for POST + JSON body.
 * Every ds/post-action* call compiles to postJSON(url, body).
 * Game engine pattern: POST and forget, server pushes result via SSE.
 */
function postJSON(url, body) {
  return fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body)
  });
}

/**
 * Overlay notification (upper right, 3s auto-hide).
 * ds.clj clipboard helpers call showNotification('Copied!').
 * Prefers a server-rendered #notification element (project-specific CSS).
 * Falls back to a dynamically created overlay (works in any project).
 */
let _notifyTimer = null;
function showNotification(msg, isError) {
  let el = document.getElementById('notification');
  if (el) {
    clearTimeout(_notifyTimer);
    el.textContent = msg;
    el.className = 'notification show' + (isError ? ' error' : '');
    _notifyTimer = setTimeout(() => { el.className = 'notification'; }, 3000);
    return;
  }
  // Fallback: create floating notification
  el = document.getElementById('ds-notify');
  if (!el) {
    el = document.createElement('div');
    el.id = 'ds-notify';
    el.style.cssText = 'position:fixed;top:12px;right:12px;z-index:10000;max-width:50vw;padding:10px 18px;border-radius:6px;font-size:13px;font-weight:600;pointer-events:none;opacity:0;transition:opacity 0.3s;';
    document.body.appendChild(el);
  }
  clearTimeout(_notifyTimer);
  el.textContent = msg;
  el.style.background = isError ? '#e74c3c' : '#2ecc71';
  el.style.color = '#fff';
  el.style.opacity = '1';
  _notifyTimer = setTimeout(() => { el.style.opacity = '0'; }, 3000);
}

/**
 * Log user action to server (for telemetry / event log).
 * Fire-and-forget — errors are silently swallowed.
 */
function logAction(type, data) {
  postJSON('/api/log-action', Object.assign({ type: type }, data || {})).catch(() => {});
}

/**
 * Copy newly minted material from its one-time response. Historical plaintext
 * keys still use the organizer-scoped copy endpoint until they are rotated.
 */
async function copyApiKey(el) {
  try {
    if (el.dataset.apiKeyMaterial) {
      await navigator.clipboard.writeText(el.dataset.apiKeyMaterial);
      showNotification('API key copied');
      return;
    }
    const response = await fetch(el.dataset.copyApiKey, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(el.dataset.apiKeyId ? { id: el.dataset.apiKeyId } : {})
    });
    if (!response.ok) throw new Error('API key copy failed');
    const result = await response.json();
    await navigator.clipboard.writeText(result.key);
    showNotification('API key copied');
  } catch (_) {
    showNotification('Could not copy API key', true);
  }
}

// ===== TIER 2: Game engine keyboard =========================================

/**
 * Normalize a keydown event into a canonical string.
 * Mac-safe: Alt+key uses e.code (physical key) instead of e.key (which
 * produces dead characters like ¬ for Alt+L, ç for Alt+C on macOS).
 *
 * Examples:
 *   Ctrl+1     -> "ctrl+1"
 *   Alt+L      -> "alt+l"      (not "alt+¬")
 *   Shift+Tab  -> "shift+Tab"
 *   j          -> "j"
 *   Meta+\\    -> "meta+\\"
 */
function encodeKey(e) {
  const mods = [];
  if (e.ctrlKey && !e.metaKey) mods.push('ctrl');
  if (e.metaKey) mods.push('meta');
  if (e.shiftKey) mods.push('shift');
  if (e.altKey) mods.push('alt');
  // On Mac, Alt produces special chars. Use e.code for physical key.
  const keyPart = e.altKey && e.code && e.code.startsWith('Key')
                    ? e.code.slice(3).toLowerCase()
                  : e.altKey && e.code && e.code.startsWith('Digit')
                    ? e.code.slice(5)
                  : e.key;
  return mods.length ? mods.join('+') + '+' + keyPart : keyPart;
}

/**
 * Fetch the server keymap and populate global keyboard dispatch state.
 * Call once on page load. The server returns:
 *   { keys: ["alt+l", "j", ...],
 *     passthroughInText: ["j", "k", ...],
 *     prefixKeys: {"shift+M": "M"} }
 *
 * After init, the globals are:
 *   window._serverKeys         — Set of keys the server handles
 *   window._serverPassthrough  — Set of keys that yield to browser in text fields
 *   window._prefixKeys         — Map of prefix key -> prefix string
 *   window._flushKeys          — Set of keys that need textarea flush before POST
 *
 * @param {string} url - Keymap endpoint (default: '/api/keymap')
 */
function initServerKeymap(url) {
  const endpoint = url || '/api/keymap';
  fetch(endpoint)
    .then(r => r.json())
    .then(data => {
      window._serverKeys = new Set(data.keys || []);
      window._serverPassthrough = new Set(data.passthroughInText || []);
      window._flushKeys = new Set(data.flushKeys || []);
      window._prefixKeys = data.prefixKeys || {};
    })
    .catch(() => {
      // Fallback: empty sets — keyboard dispatch degrades to no-op
      window._serverKeys = new Set();
      window._serverPassthrough = new Set();
      window._flushKeys = new Set();
      window._prefixKeys = {};
    });
}

/**
 * Check if the active element is a text input (textarea, input, contenteditable).
 * Used by keyboard dispatchers to decide passthrough vs server dispatch.
 */
function isTextField() {
  const tag = document.activeElement?.tagName;
  return tag === 'TEXTAREA' || tag === 'INPUT' || document.activeElement?.isContentEditable;
}

/**
 * Server keymap keyboard dispatcher.
 * Call from your keydown listener after handling browser-owned shortcuts.
 * Returns true if the key was dispatched to the server, false otherwise.
 *
 * Features:
 *   - Prefix chord support (vim-style: shift+M then t -> "M+t")
 *   - Passthrough: keys with passthrough-in-text yield to browser in text fields
 *   - Flush: keys with flush-draft trigger a callback before POST
 *
 * @param {KeyboardEvent} e - The keydown event
 * @param {Object} opts - Optional callbacks
 * @param {Function} opts.onFlush - Called before POST for flush-draft keys
 * @returns {boolean} true if key was handled (preventDefault already called)
 *
 * Usage in your keydown listener:
 *   document.addEventListener('keydown', (e) => {
 *     if (handleBrowserOwned(e)) return;
 *     if (dispatchServerKey(e, { onFlush: () => flushDraftSync() })) return;
 *     // ... project-specific fallback handlers
 *   });
 */
function dispatchServerKey(e, opts) {
  const encoded = encodeKey(e);
  opts = opts || {};

  // --- Prefix chord handling ---
  if (window._pendingPrefix) {
    e.preventDefault();
    const chord = window._pendingPrefix + '+' + e.key;
    window._pendingPrefix = null;
    clearTimeout(window._prefixTimer);
    postJSON('/api/keydown', { key: chord });
    return true;
  }

  // Check if this keystroke starts a prefix chord
  if (window._prefixKeys && window._prefixKeys[encoded]) {
    const shouldPassthrough = window._serverPassthrough && window._serverPassthrough.has(encoded);
    if (shouldPassthrough && isTextField()) {
      return false; // Let browser handle — user is typing
    }
    e.preventDefault();
    window._pendingPrefix = window._prefixKeys[encoded];
    window._prefixTimer = setTimeout(() => {
      window._pendingPrefix = null;
    }, 2000);
    return true;
  }

  // --- Normal key dispatch ---
  if (!window._serverKeys || !window._serverKeys.has(encoded)) return false;

  // Passthrough: yield to browser in text fields
  if (window._serverPassthrough && window._serverPassthrough.has(encoded) && isTextField()) {
    return false;
  }

  e.preventDefault();

  // Flush callback for keys that need textarea sync before server acts
  if (window._flushKeys && window._flushKeys.has(encoded) && opts.onFlush) {
    opts.onFlush();
  }

  postJSON('/api/keydown', { key: encoded });
  return true;
}

/* Scroll preservation for the board's inline rate cards (Gene, 2026-08-10).
 *
 * The "rate inline" affordance is a plain #sub-<id> anchor so CSS :target can
 * open the card with zero server state — but native anchor navigation also
 * scrolls the target to the viewport top, yanking the reader away from where
 * they were. This listener lets the anchor fire (so :target applies), then
 * restores the scroll position on the same frame: the card opens IN PLACE and
 * rows below simply shift down.
 *
 * WHY THIS IS ALLOWED (global CLAUDE.md, the Datastar NEVERs): scroll
 * preservation is a browser-owned operation, explicitly in the sanctioned
 * carve-out with clipboard and cursor. It holds no state, renders nothing,
 * and decides nothing about what the screen says.
 */
document.addEventListener("click", function (e) {
  var a = e.target.closest && e.target.closest('a[href^="#sub-"], a.card-close');
  if (!a) return;
  var x = window.scrollX, y = window.scrollY;
  requestAnimationFrame(function () { window.scrollTo(x, y); });
});

/* Escape closes the quick-rate card (Gene, 2026-08-10) — the keyboard synonym
 * for its ✕. Same sanctioned category as the scroll preserver above: URL
 * fragment navigation + scroll restoration, both browser-owned. Navigating to
 * '#' clears the :target (no element has an empty id) which is exactly what
 * the ✕'s href="#" does; the rAF restores the scroll the same way.
 */
document.addEventListener("keydown", function (e) {
  if (e.key !== "Escape") return;
  if (!location.hash || location.hash.indexOf("#sub-") !== 0) return;
  var x = window.scrollX, y = window.scrollY;
  location.hash = "#";
  requestAnimationFrame(function () { window.scrollTo(x, y); });
});
