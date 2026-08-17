/* First-party engagement signals the Ring server cannot observe.
 *
 * Privacy boundary: no form values, search text, click text, full outbound
 * URLs, email, or raw identity. The server validates and hashes again.
 * Opt out globally with GPC/DNT or localStorage.telemetry_opt_out = "1";
 * opt out one page with <html data-telemetry-opt-out>.
 */
(function () {
  "use strict";

  try {
    var root = document.documentElement;
    var script = document.currentScript || {};
    var dataset = script.dataset || {};
    if (root.hasAttribute("data-telemetry-opt-out") ||
        dataset.disabled === "true" ||
        navigator.globalPrivacyControl === true ||
        navigator.doNotTrack === "1" ||
        window.doNotTrack === "1") return;

    try {
      if (localStorage.getItem("telemetry_opt_out") === "1") return;
    } catch (_) { /* storage denial is not a product failure */ }

    var APP = "sessionize-sched-killer";
    var ENDPOINT = dataset.endpoint || "/api/telemetry/beacon";
    var VISITOR_TTL_MS = 30 * 24 * 60 * 60 * 1000;

    function uid() {
      if (window.crypto && typeof window.crypto.randomUUID === "function") {
        return window.crypto.randomUUID();
      }
      return Date.now().toString(36) + "_" + Math.random().toString(36).slice(2, 14);
    }

    function sessionId() {
      try {
        var value = sessionStorage.getItem("cfp_telemetry_sid");
        if (!value) {
          value = uid();
          sessionStorage.setItem("cfp_telemetry_sid", value);
        }
        return value;
      } catch (_) {
        return uid();
      }
    }

    function visitorId() {
      try {
        var now = Date.now();
        var saved = JSON.parse(localStorage.getItem("cfp_telemetry_vid") || "null");
        if (saved && saved.id && saved.expiresAt > now) return saved.id;
        var value = uid();
        localStorage.setItem("cfp_telemetry_vid", JSON.stringify({
          id: value,
          expiresAt: now + VISITOR_TTL_MS
        }));
        return value;
      } catch (_) {
        return uid();
      }
    }

    var SID = sessionId();
    var VID = visitorId();

    function send(type, data) {
      try {
        var payload = JSON.stringify({
          app: APP,
          type: type,
          path: location.pathname,
          ts: new Date().toISOString(),
          sid: SID,
          vid: VID,
          data: data || {}
        });
        var body = new Blob([payload], { type: "text/plain" });
        var sent = typeof navigator.sendBeacon === "function" &&
          navigator.sendBeacon(ENDPOINT, body);
        if (!sent && typeof window.fetch === "function") {
          window.fetch(ENDPOINT, {
            method: "POST",
            body: payload,
            headers: { "Content-Type": "text/plain" },
            keepalive: true,
            credentials: "same-origin"
          }).catch(function () {});
        }
      } catch (_) {
        /* Analytics must never break the page. */
      }
    }

    var referrerHost = null;
    try { referrerHost = document.referrer ? new URL(document.referrer).hostname : null; }
    catch (_) { referrerHost = null; }
    send("page_view", referrerHost ? { "referrer-host": referrerHost } : {});

    var marks = [25, 50, 75, 100];
    var hit = {};
    window.addEventListener("scroll", function () {
      var html = document.documentElement;
      var body = document.body;
      var top = html.scrollTop || body.scrollTop;
      var max = Math.max(html.scrollHeight, body.scrollHeight) - html.clientHeight;
      var pct = max > 0 ? Math.round((top / max) * 100) : 100;
      marks.forEach(function (mark) {
        if (pct >= mark && !hit[mark]) {
          hit[mark] = true;
          send("scroll_depth", { pct: mark });
        }
      });
    }, { passive: true });

    var startedAt = Date.now();
    var timeSent = false;
    function timeOnPage() {
      if (timeSent) return;
      timeSent = true;
      send("time_on_page", { seconds: Math.round((Date.now() - startedAt) / 1000) });
    }
    document.addEventListener("visibilitychange", function () {
      if (document.visibilityState === "hidden") timeOnPage();
    });
    window.addEventListener("pagehide", timeOnPage);

    document.addEventListener("click", function (event) {
      var target = event.target;
      var tracked = target.closest && target.closest("[data-track]");
      if (tracked) send("cta_click", { label: tracked.getAttribute("data-track") });

      var link = target.closest && target.closest("a[href]");
      if (link && link.hostname && link.hostname !== location.hostname) {
        send("outbound_click", { host: link.hostname });
      }
    }, true);

    window.siteTrack = send;
  } catch (_) {
    /* Even initialization failure must be invisible to the product. */
  }
})();
