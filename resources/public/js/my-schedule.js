/* Browser-owned My Schedule selection; the server renders all session markup. */
(function () {
  "use strict";
  var uuidPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
  function normalized(values) { return Array.from(new Set((values || []).filter(function (v) { return typeof v === "string" && uuidPattern.test(v); }))).sort(); }
  function fromCsv(value) { return normalized((value || "").split(",")); }
  function read(key) { try { var value = JSON.parse(window.localStorage.getItem(key) || "[]"); return normalized(Array.isArray(value) ? value : []); } catch (_) { return []; } }
  function write(key, values) { try { window.localStorage.setItem(key, JSON.stringify(normalized(values))); } catch (_) {} }
  function same(left, right) { return left.length === right.length && left.every(function (id, i) { return id === right[i]; }); }
  function withSelection(url, selected) { if (selected.length) url.searchParams.set("picks", selected.join(",")); else url.searchParams.delete("picks"); return url; }
  document.addEventListener("DOMContentLoaded", function () {
    var root = document.querySelector("[data-my-schedule-root]");
    if (!root || !root.dataset.myScheduleEventId) return;
    var key = "curtain-call:my-schedule:" + root.dataset.myScheduleEventId;
    var selected = read(key), current = new URL(window.location.href), requested = fromCsv(current.searchParams.get("picks"));
    if (!same(selected, requested) && (selected.length || current.searchParams.has("picks"))) { window.location.replace(withSelection(current, selected).toString()); return; }
    document.addEventListener("click", function (event) {
      var toggle = event.target.closest && event.target.closest("[data-my-schedule-toggle]");
      if (toggle) {
        event.preventDefault(); var id = toggle.dataset.sessionId;
        var next = selected.filter(function (candidate) { return candidate !== id; });
        if (next.length === selected.length && uuidPattern.test(id)) next.push(id);
        selected = normalized(next); write(key, selected);
        window.location.assign(withSelection(new URL(window.location.href), selected).toString()); return;
      }
      var link = event.target.closest && event.target.closest("[data-my-schedule-link]");
      if (link) { event.preventDefault(); window.location.assign(withSelection(new URL(link.href, window.location.origin), selected).toString()); }
    });
    var form = document.querySelector("[data-my-schedule-export]");
    if (form) form.addEventListener("submit", function () { var input = form.querySelector('input[name="session-ids"]'); if (input) input.value = selected.join(","); });
  });
}());
