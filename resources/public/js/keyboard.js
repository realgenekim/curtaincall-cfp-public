/**
 * Keyboard Accelerators — lightweight shortcut system.
 *
 * Usage (declarative via data attributes):
 *   <a href="/reload" data-shortcut="R">Reload</a>
 *   → Pressing R navigates to /reload. A <kbd>R</kbd> hint is auto-appended.
 *
 * Usage (programmatic):
 *   Keyboard.register('S', function() { document.querySelector('form').submit(); }, 'Save');
 *
 * Press ? to show all registered shortcuts.
 */
var Keyboard = (function() {
  var shortcuts = {};  // { key: { handler, description, element } }

  function register(key, handler, description) {
    shortcuts[key] = { handler: handler, description: description || key };
  }

  // Scan DOM for data-shortcut attributes and auto-register
  function scanDataAttributes() {
    document.querySelectorAll('[data-shortcut]').forEach(function(el) {
      var key = el.getAttribute('data-shortcut');
      var desc = el.getAttribute('data-shortcut-desc') || el.textContent.trim();
      var href = el.getAttribute('href');

      // Auto-append kbd hint unless opted out
      if (!el.querySelector('kbd') && !el.hasAttribute('data-shortcut-no-hint')) {
        var kbd = document.createElement('kbd');
        kbd.textContent = key;
        kbd.style.cssText = 'font-size:10px; color:#999; margin-left:4px; border:1px solid #ddd; border-radius:3px; padding:1px 4px; font-family:monospace;';
        el.appendChild(kbd);
      }

      if (href) {
        register(key, function() { window.location.href = href; }, desc);
      } else {
        register(key, function() { el.click(); }, desc);
      }
    });
  }

  // Show help overlay
  function showHelp() {
    var existing = document.getElementById('keyboard-help');
    if (existing) { existing.remove(); return; }

    var keys = Object.keys(shortcuts).sort();
    if (keys.length === 0) return;

    var overlay = document.createElement('div');
    overlay.id = 'keyboard-help';
    overlay.style.cssText = 'position:fixed; top:0; left:0; right:0; bottom:0; background:rgba(0,0,0,0.5); z-index:9999; display:flex; align-items:center; justify-content:center;';
    overlay.onclick = function(e) { if (e.target === overlay) overlay.remove(); };

    var box = document.createElement('div');
    box.style.cssText = 'background:white; border-radius:12px; padding:24px 32px; min-width:280px; max-width:400px; box-shadow:0 8px 32px rgba(0,0,0,0.2);';

    var title = document.createElement('h3');
    title.textContent = 'Keyboard Shortcuts';
    title.style.cssText = 'margin:0 0 16px; font-size:16px; color:#333;';
    box.appendChild(title);

    keys.forEach(function(k) {
      var row = document.createElement('div');
      row.style.cssText = 'display:flex; justify-content:space-between; padding:4px 0; font-size:14px;';
      var kbd = document.createElement('kbd');
      kbd.textContent = k;
      kbd.style.cssText = 'background:#f5f5f5; border:1px solid #ddd; border-radius:3px; padding:2px 8px; font-family:monospace; font-size:13px; min-width:24px; text-align:center;';
      var desc = document.createElement('span');
      desc.textContent = shortcuts[k].description;
      desc.style.cssText = 'color:#666; margin-left:16px;';
      row.appendChild(kbd);
      row.appendChild(desc);
      box.appendChild(row);
    });

    var hint = document.createElement('div');
    hint.textContent = 'Press ? or Esc to close';
    hint.style.cssText = 'margin-top:16px; font-size:11px; color:#999; text-align:center;';
    box.appendChild(hint);

    overlay.appendChild(box);
    document.body.appendChild(overlay);
  }

  // Global listener
  document.addEventListener('keydown', function(e) {
    // Skip when typing in inputs
    if (e.target.tagName === 'INPUT' || e.target.tagName === 'TEXTAREA' || e.target.tagName === 'SELECT') return;
    if (e.target.isContentEditable) return;
    // Allow browser shortcuts through
    if (e.ctrlKey || e.metaKey || e.altKey) return;

    // Dismiss help on Escape
    if (e.key === 'Escape') {
      var help = document.getElementById('keyboard-help');
      if (help) { help.remove(); return; }
    }

    // ? shows help
    if (e.key === '?') {
      e.preventDefault();
      showHelp();
      return;
    }

    // Check both cases: key as-is (for shifted keys) and uppercase
    var s = shortcuts[e.key] || shortcuts[e.key.toUpperCase()];
    if (s) {
      e.preventDefault();
      s.handler();
    }
  });

  // Auto-scan on DOMContentLoaded
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', scanDataAttributes);
  } else {
    scanDataAttributes();
  }

  return { register: register, showHelp: showHelp, shortcuts: shortcuts };
})();
