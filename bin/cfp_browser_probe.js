#!/usr/bin/env node --experimental-websocket
/*
 * The one claim curl cannot make: that a REAL BROWSER fires the CFP page's
 * debounced draft POST and repaints from the fragment the server pushes back.
 *
 *   node --experimental-websocket bin/cfp_browser_probe.js [base] [slug]
 *
 * bin/cfp_draft_probe.sh proves the server side end to end — two anonymous
 * sessions, real streams, no cross-talk — but it SIMULATES the browser with
 * curl. It would still pass if Datastar never fired the POST at all, which is
 * exactly the class of lie this repo keeps rediscovering. This script closes
 * that gap: it drives headless Chrome over raw CDP (no npm dependency, no
 * extension), dispatches a genuine bubbling `input` event, and then asks three
 * questions that can only be answered yes if the whole loop ran:
 *
 *   1. did the live status line in the DOM change (an SSE fragment landed)?
 *   2. did the per-field note appear (server-side validation, pushed)?
 *   3. does the SERVER now hold the draft (fetch the page again, same cookie)?
 *
 * It launches and kills its own Chrome with a throwaway profile, so it never
 * touches the operator's browser session.
 */
const { spawn } = require('child_process');
const fs = require('fs');
const os = require('os');
const path = require('path');

const BASE = process.argv[2] || 'http://localhost:20500';
const SLUG = process.argv[3] || 'enterprise-ai-summit-charlotte-2026';
const PORT = 9333 + (process.pid % 300);
const CHROME = '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';

const fails = [];
const chk = (label, ok, detail) => {
  console.log(ok ? `OK: ${label}` : `FAIL: ${label}${detail ? '   ' + detail : ''}`);
  if (!ok) fails.push(label);
};

const sleep = ms => new Promise(r => setTimeout(r, ms));

async function connect() {
  for (let i = 0; i < 40; i++) {
    try {
      const list = await fetch(`http://127.0.0.1:${PORT}/json/list`).then(r => r.json());
      const page = list.find(t => t.type === 'page');
      if (page) return page.webSocketDebuggerUrl;
    } catch (_) { /* Chrome is still coming up */ }
    await sleep(250);
  }
  throw new Error('headless Chrome never opened its debugging port');
}

(async () => {
  if (!fs.existsSync(CHROME)) {
    console.log('SKIP: Google Chrome is not installed at the expected path');
    process.exit(0);
  }
  const profile = fs.mkdtempSync(path.join(os.tmpdir(), 'cfp-probe-'));
  const chrome = spawn(CHROME, [
    '--headless=new', `--remote-debugging-port=${PORT}`,
    `--user-data-dir=${profile}`, '--no-first-run', '--no-default-browser-check',
    'about:blank'], { stdio: 'ignore' });

  const cleanup = () => {
    try { chrome.kill(); } catch (_) {}
    try { fs.rmSync(profile, { recursive: true, force: true }); } catch (_) {}
  };

  try {
    const ws = new WebSocket(await connect());
    await new Promise(r => ws.addEventListener('open', r));
    let id = 0; const pending = new Map();
    ws.addEventListener('message', e => {
      const m = JSON.parse(e.data);
      if (m.id && pending.has(m.id)) { pending.get(m.id)(m); pending.delete(m.id); }
    });
    const send = (method, params = {}) => {
      const i = ++id;
      ws.send(JSON.stringify({ id: i, method, params }));
      return new Promise(r => pending.set(i, r));
    };
    const evaluate = async expr => {
      const r = await send('Runtime.evaluate',
        { expression: expr, awaitPromise: true, returnByValue: true });
      if (r.result?.exceptionDetails) throw new Error(JSON.stringify(r.result.exceptionDetails));
      return r.result?.result?.value;
    };

    await send('Page.enable');
    await send('Runtime.enable');
    await send('Page.navigate', { url: `${BASE}/cfp/${SLUG}` });
    await sleep(2500);

    const out = await evaluate(`(async () => {
      // Wait for the form rather than guessing at a sleep: in dev the page can
      // be mid-reload (the browser-reload watcher fires on any file save).
      let inp = null;
      for (let i = 0; i < 40 && !inp; i++) {
        inp = document.querySelector('input[name="answer-talk-title"]');
        if (!inp) await new Promise(r => setTimeout(r, 250));
      }
      // Now pin the page down: a save by anyone (another agent, an editor)
      // mid-probe would reload it and look like a failure that isn't one.
      window.location.reload = function () {};
      if (!inp) return { error: 'no talk-title input on the page' };
      const f = inp.form;
      const before = (document.getElementById('cfp-draft-status') || {}).textContent;
      inp.value = 'HEADLESS BROWSER TITLE';
      inp.dispatchEvent(new Event('input', { bubbles: true }));
      const bad = document.querySelector('input[name="answer-prior-talk-video"]');
      if (bad) { bad.value = 'notaurl'; bad.dispatchEvent(new Event('input', { bubbles: true })); }
      await new Promise(r => setTimeout(r, 1800));
      const page2 = await fetch(location.href, { cache: 'no-store' }).then(r => r.text());
      return {
        expr: f.getAttribute('data-star-on:input__debounce.300ms'),
        novalidate: f.hasAttribute('novalidate'),
        // Without this span the 15s heartbeat logs PatchElementsNoTargetsFound
        // four times a minute and trains everyone to ignore the console.
        heartbeatTarget: !!document.getElementById('sse-heartbeat'),
        before,
        after: (document.getElementById('cfp-draft-status') || {}).textContent,
        note: (document.getElementById('cfp-note-answer-prior-talk-video') || {}).textContent,
        restored: page2.includes('HEADLESS BROWSER TITLE'),
        banner: page2.includes('Picked up where you left off')
      };
    })()`);

    if (out.error) { chk(out.error, false); }
    else {
      chk('the form carries the debounced draft POST',
        /@post\(.+draft.+contentType/.test(out.expr || ''), out.expr);
      chk('  and novalidate, so the browser does not nag mid-keystroke', !!out.novalidate);
      chk('the live status line was repainted BY THE SERVER',
        out.after !== out.before && /Saved/.test(out.after || ''),
        `before=${JSON.stringify(out.before)} after=${JSON.stringify(out.after)}`);
      chk('  and it counts what has been answered',
        /answered/.test(out.after || ''), out.after);
      chk('  and the heartbeat has a target, so the console stays quiet',
        !!out.heartbeatTarget);
      chk('a bad link is called out live, in the DOM',
        /full link/.test(out.note || ''), out.note);
      chk('the SERVER is holding the draft a moment later', !!out.restored);
      chk('  and says so when the page is reopened', !!out.banner);
    }
    ws.close();
  } catch (e) {
    chk(`probe error: ${e.message}`, false);
  }
  cleanup();
  console.log(fails.length ? 'BROWSER PROBE FAIL' : 'BROWSER PROBE PASS');
  process.exit(fails.length ? 1 : 0);
})();
