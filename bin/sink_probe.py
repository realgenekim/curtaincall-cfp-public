#!/usr/bin/env python3
"""Prove the sink fan-out actually fires on real submissions and judging actions.

Starts a throwaway HTTP listener, registers it as a webhook on a live event,
then drives a submission + a rating + a status change through the real server
and reports which events arrived. This is the local stand-in for "does Airtable
/ Slack / Zapier actually get told" — same registry, same append! fan-out.

Usage: python3 bin/sink_probe.py <event-slug>
"""
import http.server, json, re, sys, threading, time, urllib.parse
import urllib.request, http.cookiejar

BASE = "http://localhost:20500"
PORT = 20599
RECEIVED = []


class Handler(http.server.BaseHTTPRequestHandler):
    def do_POST(self):
        n = int(self.headers.get("Content-Length", 0))
        raw = self.rfile.read(n).decode("utf-8", "replace")
        try:
            RECEIVED.append(json.loads(raw))
        except Exception:
            RECEIVED.append({"raw": raw[:200]})
        self.send_response(200)
        self.end_headers()
        self.wfile.write(b"ok")

    def log_message(self, *a):
        pass


class Client:
    def __init__(self):
        self.jar = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(
            urllib.request.HTTPCookieProcessor(self.jar), NoRedirect())

    def req(self, method, path, data=None):
        body = urllib.parse.urlencode(data).encode() if data else None
        r = urllib.request.Request(BASE + path, data=body, method=method)
        if body:
            r.add_header("Content-Type", "application/x-www-form-urlencoded")
        try:
            resp = self.opener.open(r)
            return resp.status, resp.read().decode("utf-8", "replace"), \
                resp.headers.get("Location")
        except urllib.error.HTTPError as e:
            return e.code, e.read().decode("utf-8", "replace"), \
                e.headers.get("Location")


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *a, **kw):
        return None


def main():
    slug = sys.argv[1]
    srv = http.server.HTTPServer(("127.0.0.1", PORT), Handler)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    print(f"listener on :{PORT}")

    gene = Client()
    st, body, _ = gene.req("POST", "/api/login", {"email": "genek@itrevolution.net"})
    tok = re.search(r"/auth/([A-Za-z0-9-]+)", body).group(1)
    gene.req("GET", f"/auth/{tok}")

    st, _, _ = gene.req("POST", f"/api/events/{slug}/webhooks/add",
                        {"url": f"http://127.0.0.1:{PORT}/hook", "types": ""})
    print(f"register webhook -> {st}")

    speaker = Client()
    stamp = str(int(time.time()))[-6:]
    st, _, loc = speaker.req("POST", f"/api/cfp/{slug}/submit", {
        "speaker-name": "Priya Raman",
        "speaker-email": f"priya{stamp}@example.com",
        "speaker-org": "Continental Mutual",
        "speaker-title": "Director of Platform Engineering",
        "speaker-bio": "Fifteen years in insurance systems.",
        "answer-talk-title": "The Model Was Fine. Our Change Process Wasn't.",
        "answer-abstract": ("We shipped a well-behaved model into a change process "
                            "designed for quarterly releases, and spent nine months "
                            "discovering which of our controls were load-bearing."),
        "answer-session-format": "Experience Report",
        "answer-org-size": ">10,000",
        "answer-industry": "Insurance",
        "answer-ai-transformation-history": "Started 2024, three systems in production.",
        "answer-measurable-outcomes": "Change lead time 34d -> 6d.",
        "answer-advice-to-peer": "Fix the process before you blame the model.",
        "answer-notes-to-committee": "Can also run this as a panel.",
    })
    print(f"submit -> {st}")
    sid = re.search(r"/submitted/([^/?]+)", loc).group(1) if loc else None

    if sid:
        print(f"rate -> {gene.req('POST', f'/api/submissions/{sid}/rate', {'stars': '5'})[0]}")
        print(f"comment -> {gene.req('POST', f'/api/submissions/{sid}/comment', {'body': 'Yes. The controls story is the talk.'})[0]}")
        print(f"status -> {gene.req('POST', f'/api/submissions/{sid}/status', {'status': 'accepted'})[0]}")

    time.sleep(6)
    srv.shutdown()

    types = [r.get("type") for r in RECEIVED]
    print(f"\n{len(RECEIVED)} deliveries: {types}")
    for want in ("submission.created", "rating.set", "comment.added",
                 "submission.status-changed"):
        mark = "\033[32m✓\033[0m" if want in types else "\033[31m✗\033[0m"
        print(f"  {mark} {want}")
    if RECEIVED:
        print("\nfirst payload keys:", sorted(RECEIVED[0].keys()))
        print(json.dumps(RECEIVED[0], indent=1)[:700])


if __name__ == "__main__":
    main()
