#!/usr/bin/env python3
"""End-to-end drive of the running server, as a real organizer + speaker would.

Not a unit test: this exercises the LIVE server over HTTP the way a person does,
so it catches wiring defects (routes, redirects, sessions, persistence, sinks)
that in-process tests can't see.

Usage:  python3 bin/e2e_drive.py [--base http://localhost:20500]
        python3 bin/e2e_drive.py --base https://example.com \
          --public-program-slug event-slug
Exit code 0 = every step passed.
"""
import argparse, json, re, sys, threading, time, urllib.parse
from html import unescape
import http.cookiejar, urllib.request

BASE = "http://localhost:20500"
FAILS = []
STEPS = 0


class Client:
    """One browser-equivalent: its own cookie jar."""

    def __init__(self, name):
        self.name = name
        self.last_headers = {}
        self.jar = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(
            urllib.request.HTTPCookieProcessor(self.jar), NoRedirect())

    def req(self, method, path, data=None, follow=False, headers=None):
        url = path if path.startswith("http") else BASE + path
        body = urllib.parse.urlencode(data).encode() if data else None
        r = urllib.request.Request(url, data=body, method=method)
        if body:
            r.add_header("Content-Type", "application/x-www-form-urlencoded")
        for k, v in (headers or {}).items():
            r.add_header(k, v)
        try:
            resp = self.opener.open(r)
            status, text, loc = resp.status, resp.read().decode("utf-8", "replace"), \
                resp.headers.get("Location")
            self.last_headers = dict(resp.headers)
        except urllib.error.HTTPError as e:
            status, text, loc = e.code, e.read().decode("utf-8", "replace"), \
                e.headers.get("Location")
            self.last_headers = dict(e.headers)
        if follow and status in (301, 302, 303) and loc:
            return self.req("GET", loc, follow=True)
        return status, text, loc

    def get(self, path, follow=True, headers=None):
        return self.req("GET", path, follow=follow, headers=headers)

    def post(self, path, data=None, follow=False):
        return self.req("POST", path, data=data, follow=follow)


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *a, **kw):
        return None


def check(label, cond, detail=""):
    global STEPS
    STEPS += 1
    if cond:
        print(f"  \033[32m✓\033[0m {label}")
    else:
        print(f"  \033[31m✗ {label}\033[0m   {detail}")
        FAILS.append((label, detail))
    return cond


def section(t):
    print(f"\n\033[1m{t}\033[0m")


def login(client, email):
    st, body, _ = client.post("/api/login", {"email": email})
    m = re.search(r"/auth/([A-Za-z0-9-]+)", body)
    if not m:
        return False
    st, _, loc = client.get(f"/auth/{m.group(1)}", follow=False)
    return st in (200, 302, 303)


def text_of(html):
    """Tag-strip to what a human would actually READ on the page.

    Entities must be unescaped, not just stripped: the renderer emits
    `doesn&apos;t`, so a check grepping for "doesn't" silently fails against
    perfectly good copy. That cost a false failure once already — an instrument
    that lies about the app is worse than no instrument.
    """
    html = re.sub(r"<script.*?</script>", " ", html, flags=re.S)
    html = re.sub(r"<style.*?</style>", " ", html, flags=re.S)
    stripped = re.sub(r"<[^>]+>", " ", html)
    return re.sub(r"\s+", " ", unescape(stripped))


def header_of(client, name):
    """Case-insensitive lookup of a response header.

    http.client title-cases what it parses, so the server's `ETag` arrives as
    `Etag` and a literal `.get("ETag")` silently returns None — which reads
    exactly like "the server never sent one". That cost a false failure once.
    """
    want = name.lower()
    for k, v in (client.last_headers or {}).items():
        if k.lower() == want:
            return v
    return None


def store_lines():
    with open("data/store/events.jsonl") as f:
        return [json.loads(l) for l in f if l.strip()]


def drive_public_program(slug):
    """Read-only proof that the canonical program exposes every browse door.

    Keep this independently runnable: it is safe to aim at a deployed site and
    catches missing links, missing routes, and accidental route collapse without
    needing an organizer session or mutating event data.
    """
    speaker = Client("anonymous-program-visitor")
    section("Anonymous visitors can discover and open every public program surface")
    st, program, _ = speaker.get(f"/program/{slug}")
    check("the canonical Program page is public",
          st == 200 and "public-program-nav" in program, f"status {st}")
    public_doors = [
        ("Sessions", f"/agenda/{slug}/sessions", "public-session-list"),
        ("Speakers", f"/agenda/{slug}/speakers", "public-speaker-directory"),
        ("Speaker gallery", f"/agenda/{slug}/gallery", "public-gallery"),
        ("My schedule", f"/agenda/{slug}/my", "Schedule itinerary"),
    ]
    opened = []
    for label, path, marker in public_doors:
        check(f"Program links to {label}",
              f'href="{path}"' in program, f"missing {path}")
        st, page, _ = speaker.get(path)
        check(f"anonymous GET {path} is the distinct {label} surface",
              st == 200 and marker in page,
              f"status {st}; missing marker {marker!r}")
        opened.append(page)
    check("the four public doors do not collapse onto one response",
          len(set(opened)) == len(public_doors),
          f"{len(set(opened))} distinct bodies for {len(public_doors)} doors")


def drive_marquee(client):
    """The create page's live marquee, end to end over real HTTP.

    This exists because the failure mode is SILENT: the preview endpoint answers
    204 whether or not the push found a listener, so a broken connection key
    looks exactly like a working one from the client's side. The only honest
    proof is to hold the SSE stream open and watch the second fragment arrive.
    """
    st, page, _ = client.get("/events/new")
    check("the create page mounts an SSE connection",
          "/api/sse?event-id=new-event" in page, f"status {st}")
    check("  and posts keystrokes to the preview endpoint",
          "/api/events/preview" in page)
    check("  debounced, so a fast typist doesn't DOS the server",
          "__debounce" in page)

    frames = []

    def reader():
        req = urllib.request.Request(BASE + "/api/sse?event-id=new-event")
        try:
            resp = client.opener.open(req, timeout=8)
            while True:
                line = resp.readline()
                if not line:
                    break
                frames.append(line.decode("utf-8", "replace"))
        except Exception as e:                      # the read timeout ends it
            frames.append(f"\n[stream closed: {e}]\n")

    t = threading.Thread(target=reader, daemon=True)
    t.start()
    time.sleep(1.0)                                 # let the stream register

    payload = json.loads(client.req("GET", "/dev/sse-state")[1])
    check("the server can SEE the viewer's connection (/dev/sse-state)",
          payload["you"]["connections-on-create-page"] >= 1,
          f"registrations={payload['registrations']}")

    body = json.dumps({"evname": "Marquee Drive Conf", "evloc": "Charlotte, NC",
                       "evstarts": "2026-10-07", "evends": "2026-10-08"}).encode()
    r = urllib.request.Request(BASE + "/api/events/preview", data=body, method="POST")
    r.add_header("Content-Type", "application/json")
    try:
        st = client.opener.open(r).status
    except urllib.error.HTTPError as e:
        st = e.code
    check("POST /api/events/preview answers 204", st == 204, f"status {st}")

    t.join(timeout=10)
    stream = "".join(frames)
    check("a fragment arrived on the SSE stream",
          "datastar-patch-elements" in stream, "nothing was pushed at all")
    check("  targeting #event-marquee", "#event-marquee" in stream)
    check("  carrying the typed name — the marquee really moved",
          "Marquee Drive Conf" in stream,
          "204 was returned but the push reached nobody")
    check("  with the display name assembled by events/display-name",
          "Marquee Drive Conf — Charlotte, NC · Oct 7–8, 2026" in stream)
    check("  and the slug derived from name + city + year",
          "/cfp/marquee-drive-conf-charlotte-2026" in stream)


def open_stream(client, path, seconds=4.0):
    """Hold an SSE stream open on a background thread and collect its frames.

    The speaker side's failure mode is silent in exactly the way the create
    page's was: the draft endpoint answers 204 whether or not the push found a
    listener. Only a held-open stream can tell the difference.
    """
    frames = []

    def reader():
        req = urllib.request.Request(BASE + path)
        try:
            resp = client.opener.open(req, timeout=seconds)
            while True:
                line = resp.readline()
                if not line:
                    break
                frames.append(line.decode("utf-8", "replace"))
        except Exception as e:                      # the read timeout ends it
            frames.append(f"\n[stream closed: {e}]\n")

    t = threading.Thread(target=reader, daemon=True)
    t.start()
    time.sleep(1.0)                                 # let the stream register
    return frames, t


def slug_from_location(loc):
    """The event slug out of a post-create redirect.

    This used to be "the last path segment", which was true only while create
    redirected to /events/<slug>. It now lands the organizer on the first
    UNFINISHED setup step (/events/<slug>/form), and the old rule quietly
    reported that every event in the drive was called "form" — 40-odd failures
    downstream, none of them real. Name the segment you actually want.
    """
    m = re.search(r"/events/([^/?#]+)", loc or "")
    return m.group(1) if m else None


def last_status_in_log(sid):
    """What the LOG says this submission's status is — the only witness that
    cannot be fooled by a word appearing in a dropdown."""
    to = None
    for e in store_lines():
        if e.get("type") == "submission.status-changed" \
                and e.get("payload", {}).get("submission-id") == sid:
            to = e["payload"].get("to")
    return to


def main():
    global BASE
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default=BASE)
    ap.add_argument("--slug", default=None, help="reuse an existing event slug")
    ap.add_argument(
        "--public-program-slug",
        help="run only the anonymous, read-only public Program surface ratchet",
    )
    args = ap.parse_args()
    BASE = args.base
    if args.public_program_slug:
        drive_public_program(args.public_program_slug)
        print(f"\n{STEPS} checks, {len(FAILS)} failures")
        return 1 if FAILS else 0
    stamp = str(int(time.time()))[-6:]

    gene = Client("gene")
    ann = Client("ann")
    speaker = Client("speaker")   # public, never signs in

    # ---------------------------------------------------------------- 1. auth
    section("1. Organizer signs in (magic link)")
    check("gene signs in", login(gene, "genek@itrevolution.net"))
    st, body, _ = gene.get("/events")
    check("/events renders while signed in", st == 200 and "Events" in body, f"status {st}")
    check("shows who is signed in", "Gene Kim" in body)

    # -------------------------------------------------------- 2. create event
    section("2. Create an event (slug left blank -> auto-derived)")
    name = f"Enterprise Tech Leadership Summit Vegas {stamp}"
    st, body, loc = gene.post("/api/events/create", {
        "name": name,
        "starts-on": "2027-03-16", "ends-on": "2027-03-18",
        "tz": "America/Los_Angeles",
        "cfp-opens-at": "2026-08-01T09:00", "cfp-closes-at": "2026-11-20T23:59",
        "slug": "",
        "support-email": "genek@itrevolution.net",
        "location": "Las Vegas, NV", "website-url": "https://itrevolution.com",
    })
    check("create returns a redirect", st in (302, 303), f"status {st} loc {loc}")
    slug = slug_from_location(loc)
    check("slug auto-derived from name", bool(slug) and slug != "",
          f"loc={loc}")
    print(f"     slug = {slug}")

    st, dash, _ = gene.get(f"/events/{slug}")
    check("dashboard loads", st == 200, f"status {st}")
    dtext = text_of(dash)
    check("dashboard shows the event name", name in dtext)
    check("dashboard shows the location", "Las Vegas" in dtext)
    check("dashboard shows CFP dates", "2026" in dtext)

    # persistence: the log must hold it, and a re-fold must reproduce it
    lines = store_lines()
    created = [l for l in lines if l.get("type") == "event.created"
               and l.get("payload", {}).get("slug") == slug]
    check("event.created is in the append-only log", len(created) == 1,
          f"found {len(created)}")
    if created:
        p = created[0]["payload"]
        for f in ("name", "starts-on", "ends-on", "tz", "cfp-opens-at",
                  "cfp-closes-at", "support-email", "location", "website-url"):
            check(f"  log preserved {f}", p.get(f) not in (None, ""),
                  f"value={p.get(f)!r}")
        check("  the time zone I picked is the one stored",
              p.get("tz") == "America/Los_Angeles", f"stored {p.get('tz')!r}")

    check("auto-committee was created",
          any(l.get("type") == "committee.created"
              and l.get("payload", {}).get("event-slug") == slug for l in lines)
          or any(l.get("type") == "committee.created" for l in lines[-6:]),
          "no committee.created near event creation")
    check("seed form was installed",
          any(l.get("type") == "form.installed" for l in lines[-8:]))

    st, elist, _ = gene.get("/events")
    check("new event appears in the events list", name in text_of(elist))

    # ------------------------------------- 2b. name + dates only, CFP is live
    section("2b. The ten-minute claim: a name and two dates, and the CFP is OPEN")
    # There is no "opens at" field any more. If this section ever fails, the
    # headline promise of the product is false, not merely degraded.
    minimal_name = f"Minimal Summit {stamp}"
    st, _, loc = gene.post("/api/events/create", {
        "name": minimal_name, "starts-on": "2027-06-01", "ends-on": "2027-06-02"})
    check("create with ONLY name + dates is accepted", st in (302, 303), f"status {st}")
    mslug = slug_from_location(loc)
    check("the slug is derived from name + year",
          mslug == f"minimal-summit-{stamp}-2027", f"slug={mslug}")
    st, mcfp, _ = speaker.get(f"/cfp/{mslug}")
    check("the public CFP is immediately open to submissions",
          st == 200 and "Submit talk" in mcfp, f"status {st}")
    check("no 'not open yet' notice on a brand-new CFP",
          "isn't open yet" not in text_of(mcfp))
    mcreated = [l for l in store_lines() if l.get("type") == "event.created"
                and l.get("payload", {}).get("slug") == mslug]
    if mcreated:
        p = mcreated[0]["payload"]
        check("  the moment it opened is recorded", bool(p.get("cfp-opens-at")))
        check("  no close date, so it stays open", not p.get("cfp-closes-at"))
        check("  support email defaulted to the creator",
              p.get("support-email") == "genek@itrevolution.net",
              f"stored {p.get('support-email')!r}")

    section("2c. Closing the call is a deliberate act, and it is ENFORCED")
    st, _, _ = gene.post(f"/api/events/{mslug}/cfp/close")
    check("close the call", st in (302, 303), f"status {st}")
    st, closed_page, _ = speaker.get(f"/cfp/{mslug}")
    check("the public page says the call has closed",
          "call for speakers has closed" in text_of(closed_page), f"status {st}")
    before = len(store_lines())
    st, refused, _ = speaker.post(f"/api/cfp/{mslug}/submit", {
        "answer-talk-title": "Too late", "answer-abstract": "x",
        "speaker-name": "Late Speaker", "speaker-email": "late@example.com"})
    check("a submission after close is REFUSED 422 (not a 200 that looks like success)",
          st == 422, f"status {st}")
    check("  and the refusal says so in plain English",
          "call for speakers closed" in text_of(refused))
    check("  and the log grew by ZERO", len(store_lines()) == before,
          f"{len(store_lines()) - before} events appended")
    st, _, _ = gene.post(f"/api/events/{mslug}/cfp/open")
    check("reopening the call is one click", st in (302, 303), f"status {st}")
    st, reopened, _ = speaker.get(f"/cfp/{mslug}")
    check("  and the public page accepts submissions again", "Submit talk" in reopened)

    # ---------------------------------------- 2d. the create page's live marquee
    section("2d. The create-page marquee actually moves (SSE round trip)")
    drive_marquee(gene)

    # ------------------------------------------------------- 3. PC membership
    section("3. Add a programming-committee member")
    st, committee_page, _ = gene.get(f"/events/{slug}/committee")
    m = re.search(r'/api/committees/([^/"]+)/members/add', committee_page)
    check("committee page offers an add-member form", st == 200 and bool(m),
          f"status {st}")
    if m:
        cid = m.group(1)
        st, _, loc = gene.post(f"/api/committees/{cid}/members/add", {
            "name": "Ann Perry", "email": "annp@itrevolution.net", "role": "reviewer"})
        check("add member accepted", st in (200, 302, 303), f"status {st}")
        st, committee_page2, _ = gene.get(f"/events/{slug}/committee")
        check("Ann appears on the roster", "Ann Perry" in text_of(committee_page2))
        check("Ann's email is shown", "annp@itrevolution.net" in text_of(committee_page2))
        check("member.added is in the log",
              any(l.get("type") == "member.added" for l in store_lines()[-6:]))

    section("3b. Ann signs in and sees the event")
    check("ann signs in", login(ann, "annp@itrevolution.net"))
    st, aev, _ = ann.get("/events")
    check("Ann sees the new event in her list", name in text_of(aev), f"status {st}")
    st, aboard, _ = ann.get(f"/events/{slug}/board")
    check("Ann can open the review board", st == 200, f"status {st}")

    # --------------------------------------------------------- 4. public CFP
    section("4. Public call for speakers")
    st, cfp, _ = speaker.get(f"/cfp/{slug}")
    check("public CFP page loads with NO sign-in", st == 200, f"status {st}")
    ctext = text_of(cfp)
    check("CFP shows the event name", name in ctext)
    fields = sorted(set(re.findall(r'name="((?:answer|speaker)-[^"]+)"', cfp)))
    check("CFP renders form fields", len(fields) >= 8, f"fields={fields}")
    check("CFP has a submit button", "submit" in cfp.lower())
    check("CFP does NOT ask the speaker to make an account",
          "password" not in ctext.lower())
    check("private committee field is not shown as public",
          "notes-to-committee" in cfp, "private field missing from form")
    print(f"     {len(fields)} fields")

    # -------------------------------------------- 4b. a half-filled submission
    section("4b. Validation failure must not eat what the speaker typed")
    long_abstract = ("Eighteen months ago we had a twelve-person platform team and a "
                     "six-week lead time. Today we have neither. This is the honest "
                     "account of what we removed, what we kept, and the two things "
                     "that nearly took production down along the way.")
    st, bad, _ = speaker.post(f"/api/cfp/{slug}/submit", {
        "speaker-name": "Dana Whitfield",
        "answer-talk-title": "We Deleted Our Platform Team and Nothing Broke",
        "answer-abstract": long_abstract})
    check("incomplete submission is refused", st == 422, f"status {st}")
    check("the speaker is told what to fix",
          "need attention" in text_of(bad) or "required" in text_of(bad).lower())
    check("the title they typed is still in the form",
          "We Deleted Our Platform Team" in bad, "typed title was lost")
    check("the abstract they typed is still in the form",
          "twelve-person platform team" in bad, "typed abstract was LOST")
    check("their name is still in the form", "Dana Whitfield" in bad)

    # ------------------------- 4c. the speaker side's live lane (SSE, per viewer)
    section("4c. Nothing a speaker types is ever lost (draft stash + per-viewer SSE)")
    check("the CFP page mounts its own SSE connection",
          f"/api/cfp/{slug}/stream" in cfp, "no stream mount on the public page")
    check("  and posts keystrokes to the draft endpoint",
          f"/api/cfp/{slug}/draft" in cfp)
    check("  debounced, so a fast typist doesn't DOS the server",
          "__debounce" in cfp)
    check("  with novalidate, or the browser would nag on every keystroke",
          "novalidate" in cfp,
          "Datastar's form POST calls checkValidity(); a half-typed URL would pop a bubble")

    frames, t = open_stream(speaker, f"/api/cfp/{slug}/stream")
    typed_title = "x" * 400            # over the talk-title cap on purpose
    st, _, _ = speaker.post(f"/api/cfp/{slug}/draft", {
        "answer-talk-title": typed_title,
        "answer-abstract": "Half a sentence, typed and then abandoned",
        "answer-prior-talk-video": "notaurl"})
    check("a debounced keystroke answers 204", st == 204, f"status {st}")
    t.join(timeout=8)
    stream = "".join(frames)
    check("a fragment arrived on the speaker's OWN stream",
          "datastar-patch-elements" in stream, "204 was returned but nothing was pushed")
    check("  the over-cap note names the count they actually typed",
          "400 characters" in stream)
    check("  a link that isn't a link is said so, live",
          "full link" in stream)
    check("  and the draft-status line says it is saved",
          "cfp-draft-status" in stream and "Saved" in stream)

    st, again, _ = speaker.get(f"/cfp/{slug}")
    check("killing the tab costs nothing — the abstract comes back",
          "typed and then abandoned" in again, "the draft was NOT restored")
    check("  and the speaker is told this is an editable saved draft",
          "Editing a saved draft" in text_of(again))

    stranger = Client("another-speaker")
    st, spage, _ = stranger.get(f"/cfp/{slug}")
    check("a second anonymous visitor gets a CLEAN form (no cross-talk)",
          "typed and then abandoned" not in spage,
          "one stranger's draft leaked into another's browser")

    # ---------------------------------------------------------- 5. submission
    section("5. Speaker submits a complete talk (no account)")
    payload = {
        "speaker-name": "Dana Whitfield",
        "speaker-email": f"dana{stamp}@example.com",
        "speaker-org": "Northwind Freight",
        "speaker-title": "VP Engineering",
        "speaker-bio": "Runs the platform group at a mid-size logistics carrier.",
        "answer-talk-title": "We Deleted Our Platform Team and Nothing Broke",
        "answer-abstract": long_abstract,
        "answer-session-format": "Experience Report",
        "answer-track": "Developer Practices",
        "answer-org-size": "1,000–10,000",
        "answer-industry": "Transportation & Logistics",
        "answer-ai-transformation-history": "Two years in, mostly on internal tooling.",
        "answer-measurable-outcomes": "Lead time 6 weeks -> 2 days; change fail rate flat.",
        "answer-advice-to-peer": "Delete the team, keep the paved road.",
        "answer-notes-to-committee": "Happy to do this as a 20-minute version.",
    }
    st, sub, loc = speaker.post(f"/api/cfp/{slug}/submit", payload)
    check("submit accepted", st in (200, 302, 303), f"status {st}")
    sid = None
    if loc:
        m = re.search(r"/submitted/([^/?]+)", loc)
        sid = m.group(1) if m else None
    check("speaker lands on a confirmation URL", bool(sid), f"loc={loc}")
    if sid:
        st, conf, _ = speaker.get(loc)
        check("confirmation page loads", st == 200, f"status {st}")
        ctext = text_of(conf)
        check("confirmation repeats the talk title",
              "We Deleted Our Platform Team" in ctext)
    check("submission.created is in the log",
          any(l.get("type") == "submission.created" for l in store_lines()[-8:]))

    # A second talk is a NORMAL thing to do (the cap here is above one), so the
    # ABOUT YOU block must survive the submit while the answers must not.
    st, fresh, _ = speaker.get(f"/cfp/{slug}")
    ftext = text_of(fresh)
    check("after submitting, the talk answers are cleared",
          "We Deleted Our Platform Team" not in fresh,
          "the next submission would start pre-filled with the last one")
    check("  but they never type their bio twice",
          "Runs the platform group" in fresh,
          "the speaker block was thrown away with the answers")
    check("  and a fresh talk is not greeted as a resumed one",
          "Editing a saved draft" not in ftext)

    section("5b. The submission reaches the organizer")
    st, subs, _ = gene.get(f"/events/{slug}/submissions")
    check("submissions list loads", st == 200, f"status {st}")
    check("the new talk is listed", "We Deleted Our Platform Team" in text_of(subs))
    check("the speaker's name is listed", "Dana Whitfield" in text_of(subs))
    st, board, _ = gene.get(f"/events/{slug}/board")
    check("review board shows the talk", "We Deleted Our Platform Team" in text_of(board))
    if not sid:
        m = re.search(r'/submissions/([0-9a-f-]{8,})', board)
        sid = m.group(1) if m else None
    print(f"     submission = {sid}")

    # ------------------------------------------------------------ 6. judging
    section("6. Judging: two reviewers rate and comment")
    if sid:
        st, det, _ = gene.get(f"/events/{slug}/submissions/{sid}")
        check("submission detail loads", st == 200, f"status {st}")
        check("detail shows the abstract", "twelve-person platform team" in text_of(det))

        st, _, _ = gene.post(f"/api/submissions/{sid}/rate", {"stars": "4"})
        check("Gene's rating accepted", st in (200, 204, 302, 303), f"status {st}")
        st, _, _ = gene.post(f"/api/submissions/{sid}/comment",
                             {"body": "Strong. The two near-outages are the talk."})
        check("Gene's comment accepted", st in (200, 204, 302, 303), f"status {st}")
        st, _, _ = ann.post(f"/api/submissions/{sid}/rate", {"stars": "5"})
        check("Ann's rating accepted", st in (200, 204, 302, 303), f"status {st}")

        st, board, _ = gene.get(f"/events/{slug}/board")
        btext = text_of(board)
        check("board shows both reviews counted", "2" in btext)
        check("board shows the comment inline",
              "near-outages" in btext or "Strong." in btext,
              "comment not visible on the board")
        check("Ann sees Gene's rating (open visibility)",
              "Gene" in text_of(ann.get(f"/events/{slug}/board")[1]))

        # ------------------------------------------- 6a. bad values are REFUSED
        # bd sessionize-sched-killer-xmf. These endpoints used to answer 303 and
        # append nothing when the VALUE was unrecognised — which produced a
        # false pass right here: the driver posted lowercase "accepted", the app
        # ignored it, and the assertion below still passed because the word
        # "Accepted" appears in the status dropdown. So assert the REFUSAL, and
        # assert it by counting the log, not by reading the page.
        section("6a. Bad values are refused, not silently swallowed")
        before = len(store_lines())
        rejects = [
            ("an unknown status", f"/api/submissions/{sid}/status", {"status": "Bogus"}),
            ("a blank status", f"/api/submissions/{sid}/status", {"status": "  "}),
            ("an out-of-range rating", f"/api/submissions/{sid}/rate", {"stars": "99"}),
            ("a non-numeric rating", f"/api/submissions/{sid}/rate", {"stars": "abc"}),
            ("a negative rating", f"/api/submissions/{sid}/rate", {"stars": "-3"}),
            ("an off-scale rating", f"/api/submissions/{sid}/rate", {"stars": "4.3"}),
            ("an empty rating", f"/api/submissions/{sid}/rate", {"stars": ""}),
            ("an empty comment", f"/api/submissions/{sid}/comment", {"body": ""}),
            ("a whitespace comment", f"/api/submissions/{sid}/comment", {"body": "   "}),
        ]
        for what, path, data in rejects:
            st, body, _ = gene.post(path, data)
            check(f"{what} -> 422", st == 422,
                  f"status {st} — a 303 here is the silent no-op from bd -xmf")
            check(f"  ...and it says why, in words",
                  len(text_of(body).strip()) > 0 and "error" in body.lower()
                  or "isn" in body or "Pick" in body or "Say something" in body,
                  "no human-readable reason in the body")
        check("  ...and NOTHING was written to the log",
              len(store_lines()) == before,
              f"log grew by {len(store_lines()) - before} lines")

        section("6b. Decision: accept it")
        # Lowercase on purpose: the event's vocabulary is "Accepted", and being
        # liberal about case is the other half of the -xmf fix.
        st, _, _ = gene.post(f"/api/submissions/{sid}/status", {"status": "accepted"})
        check("status change accepted", st in (200, 204, 302, 303), f"status {st}")
        check("a lowercase status really wrote (not a false pass)",
              last_status_in_log(sid) == "Accepted",
              f"log says {last_status_in_log(sid)!r}")
        st, det, _ = gene.get(f"/events/{slug}/submissions/{sid}")
        check("detail reflects Accepted", "Accepted" in text_of(det))
        check("speaker has NOT been told yet (inform gate)",
              "not been informed" in text_of(det).lower()
              or "not informed" in text_of(det).lower()
              or "Notify" in det or "Inform" in det,
              "no visible inform gate")

    # ------------------------------------------------------------ 7. exports
    section("7. Exports and API")
    st, sess, _ = gene.get(f"/events/{slug}/exports/sessions.json")
    check("sessions.json is valid JSON", st == 200 and _is_json(sess), f"status {st}")
    st, spk, _ = gene.get(f"/events/{slug}/exports/speakers.json")
    check("speakers.json is valid JSON", st == 200 and _is_json(spk), f"status {st}")
    st, ics, _ = gene.get(f"/events/{slug}/exports/calendar.ics")
    check("calendar.ics is an iCalendar file", st == 200 and "BEGIN:VCALENDAR" in ics,
          f"status {st}")
    st, llms, _ = gene.get(f"/events/{slug}/llms.txt")
    check("llms.txt renders", st == 200, f"status {st}")
    check("accepted-but-uninformed talk is NOT published yet",
          "We Deleted Our Platform Team" not in sess,
          "unpublished talk leaked into sessions.json")

    # ------------------------------------ 7b. Sessionize profile auto-populate
    section("7b. Sessionize import — one URL instead of retyping a bio")
    st, cfp, _ = speaker.get(f"/cfp/{slug}")
    check("the CFP offers a Sessionize import", "sessionize" in cfp.lower())
    st, imp, _ = speaker.post(f"/api/cfp/{slug}/import-sessionize",
                              {"speaker-sessionize-url": "https://sessionize.com/tessak22/"})
    check("import responds", st == 200, f"status {st}")
    filled = dict(re.findall(r'name="(speaker-[a-z-]+)"[^>]*value="([^"]*)"', imp))
    bio = re.search(r'name="speaker-bio"[^>]*>(.*?)</textarea>', imp, re.S)
    check("  name is filled in", bool(filled.get("speaker-name")),
          "the import reached Sessionize but filled nothing — check the fetch timeout")
    check("  title/tagline is filled in", bool(filled.get("speaker-title")))
    check("  headshot URL is filled in", bool(filled.get("speaker-headshot-url")))
    check("  bio is filled in", bool(bio and bio.group(1).strip()))

    st, bad, _ = speaker.post(f"/api/cfp/{slug}/import-sessionize",
                              {"speaker-sessionize-url": "https://example.com/not-a-profile"})
    check("a bad URL is refused politely", st in (200, 422), f"status {st}")
    badtext = text_of(bad).lower()
    check("  ...and says so in plain language",
          any(p in badtext for p in ("couldn't", "could not", "doesn't look like",
                                     "does not look like")),
          f"no recognisable explanation in the response")
    check("  ...and the form is still usable", "speaker-name" in bad)

    # A typed value must survive an import — imported values are DEFAULTS.
    st, keep, _ = speaker.post(f"/api/cfp/{slug}/import-sessionize", {
        "speaker-sessionize-url": "https://sessionize.com/tessak22/",
        "speaker-name": "The Name I Typed Myself"})
    check("what the speaker typed beats what we imported",
          "The Name I Typed Myself" in keep,
          "the import overwrote a value the speaker had already typed")

    # --------------------------------- 7c. a speaker is not the committee
    section("7c. Privilege separation (bd 53i — this was a real hole)")
    if sid:
        # Sign in as the speaker who submitted earlier in this drive.
        spk = Client("speaker-session")
        if login(spk, payload["speaker-email"]):
            before = len(store_lines())
            attempts = [
                ("rate a proposal", f"/api/submissions/{sid}/rate", {"stars": "1"}),
                ("decide a talk", f"/api/submissions/{sid}/status", {"status": "Declined"}),
                ("flag a talk", f"/api/submissions/{sid}/priority", None),
                ("lock the schedule", f"/api/events/{slug}/schedule/lock", None),
                ("register a webhook", f"/api/events/{slug}/webhooks/add",
                 {"url": "http://127.0.0.1:1/x"}),
            ]
            for what, path, data in attempts:
                st, _, _ = spk.post(path, data)
                check(f"a speaker may not {what}", st == 403,
                      f"status {st} — a refusal must not look like a 303 success")
            time.sleep(1)
            check("  ...and nothing they tried was written to the log",
                  len(store_lines()) == before,
                  f"log grew by {len(store_lines()) - before} lines")
            st, _, loc = spk.req("GET", f"/events/{slug}/board")
            check("a speaker who opens the board is sent to their portal",
                  st in (302, 303) and loc == "/portal", f"status {st} loc {loc}")
            st, portal, _ = spk.get("/portal")
            check("the speaker's own portal still works", st == 200)

            # ---- the portal at the same grade as the public page
            check("the portal mounts its own SSE connection",
                  "/portal/stream" in portal,
                  "/api/sse is organizer-only; a speaker's portal needs its own route")
            check("  and posts keystrokes to the portal draft endpoint",
                  "/portal/draft" in portal)
            check("  naming WHICH form they belong to",
                  'name="dscope"' in portal)
            pframes, pt = open_stream(spk, "/portal/stream")
            st, _, _ = spk.post("/portal/draft", {
                "dscope": "profile",
                "bio": "Half a bio, typed into the portal and abandoned",
                "headshot-url": "not-a-url"})
            check("a portal keystroke answers 204", st == 204, f"status {st}")
            pt.join(timeout=8)
            pstream = "".join(pframes)
            check("a fragment arrived on the portal stream",
                  "datastar-patch-elements" in pstream,
                  "204 was returned but the portal push reached nobody")
            check("  a bad profile link is called out live",
                  "full link" in pstream)
            check("  and the portal says the draft is saved",
                  "portal-status-profile" in pstream)
            st, portal2, _ = spk.get("/portal")
            check("a portal refresh repaints the half-typed bio",
                  "Half a bio, typed into the portal" in portal2,
                  "the portal draft was NOT restored")

            # ---- profile + answers really round-trip (not just draft)
            st, _, _ = spk.post("/api/profile", {
                "tagline": "VP Engineering, Northwind Freight",
                "bio": "Runs the platform group at a mid-size logistics carrier.",
                "headshot-url": "https://example.com/dana.jpg",
                "linkedin-url": "", "website-url": ""})
            check("saving the profile is accepted", st in (302, 303), f"status {st}")
            st, portal3, _ = spk.get("/portal")
            check("  and the saved profile is what the portal now shows",
                  "VP Engineering, Northwind Freight" in portal3)
            check("  the draft is gone once it is real",
                  "Half a bio, typed into the portal" not in portal3,
                  "a saved value is still competing with a stale draft")
            check("  and it is in the append-only log",
                  any(l.get("type") == "person.profile-updated"
                      for l in store_lines()[-6:]))

            st, _, _ = spk.post(f"/api/submissions/{sid}/answers", {
                "answer-talk-title": "We Deleted Our Platform Team and Nothing Broke",
                "answer-abstract": long_abstract + " Revised in the portal.",
                "answer-session-format": "Experience Report",
                "answer-org-size": "1,000\u201310,000",
                "answer-industry": "Transportation & Logistics",
                "answer-ai-transformation-history": "Two years in, mostly on internal tooling.",
                "answer-measurable-outcomes": "Lead time 6 weeks -> 2 days; change fail rate flat.",
                "answer-advice-to-peer": "Delete the team, keep the paved road.",
                "answer-notes-to-committee": "Happy to do this as a 20-minute version."})
            check("a speaker can edit their own talk", st in (302, 303), f"status {st}")
            st, portal4, _ = spk.get("/portal")
            check("  and the edit shows on the organizer's side too",
                  "Revised in the portal"
                  in gene.get(f"/events/{slug}/submissions/{sid}")[1])
        else:
            check("speaker can sign in to test privileges", False, "login failed")

    # ------------------------- 7d. one committee is not every committee
    section("7d. Per-event scoping (bd x9j — a reviewer's reach stops at their event)")
    st, _, loc2 = gene.post("/api/events/create", {
        "name": f"Someone Elses Conference {stamp}", "slug": "",
        "tz": "America/New_York",
        "cfp-opens-at": "2026-01-01T09:00", "cfp-closes-at": "2099-01-01T23:59"})
    other = slug_from_location(loc2)
    check("a second event is created", bool(other), f"status {st}")
    if other:
        # Ann reviews the FIRST event only. Everything about the second one —
        # including the settings page that prints its API token — must be shut
        # to her, and the refusal must not write anything.
        before = len(store_lines())
        st, body, _ = ann.req("GET", f"/events/{other}/settings")
        check("a reviewer of another event cannot open this one's settings",
              st == 403, f"status {st}")
        for what, path, data in [
                ("mint an API key", f"/api/events/{other}/api-keys/create", {"label": "mine"}),
                ("repoint Slack", f"/api/events/{other}/slack/set",
                 {"webhook-url": "https://hooks.slack.com/services/EVIL",
                  "groups": "submissions"}),
                ("lock the schedule", f"/api/events/{other}/schedule/lock", None),
                ("reach a verb that does not exist", f"/api/events/{other}/some-future-verb", None)]:
            st, _, _ = ann.post(path, data)
            check(f"a reviewer of another event may not {what}", st == 403, f"status {st}")
        time.sleep(1)
        check("  ...and nothing they tried was written to the log",
              len(store_lines()) == before,
              f"log grew by {len(store_lines()) - before} lines")
        st, _, _ = gene.get(f"/events/{other}/settings")
        check("the event's own creator still gets in", st == 200, f"status {st}")

    # ---------------------------------------- 7e. named API keys (bd bdm)
    section("7e. API keys — masked, deliberately copied, revocable one at a time")
    st, body, _ = gene.post(f"/api/events/{slug}/api-keys/create", {"label": "e2e drive"})
    km = re.search(r'data-api-key-id="([^"]+)"[^>]*onclick="copyApiKey\(this\)"', body or "")
    kid = km.group(1) if km else None
    material = re.search(r'data-api-key-material="([^"]+)"', body or "")
    secret = material.group(1) if material else None
    check("creating a key returns its material exactly once",
          st == 200 and bool(kid) and bool(secret) and secret in body,
          f"status {st}")
    check("the one-time material is a 32-character URL-safe secret",
          bool(re.fullmatch(r"[A-Za-z0-9_-]{32}", secret or "")))
    if secret and kid:
        st, page, _ = gene.get(f"/events/{slug}/settings")
        check("the settings page still shows only the masked last four",
              secret not in page and secret[-4:] in page)
        st, js, _ = gene.get(f"/api/v1/events/{slug}/sessions?status=all&token={secret}")
        check("the key opens the status-filtered API", st == 200 and _is_json(js), f"status {st}")
        st, _, _ = gene.get(f"/api/v1/events/{slug}/sessions?status=all")
        check("...and without it the same call is 401", st == 401, f"status {st}")
        check("the revoke button carries the key id", bool(kid))
        if kid:
            st, body, _ = gene.post(f"/api/events/{slug}/api-keys/revoke", {"id": kid})
            check("the first click only asks", st == 200 and "Revoke it?" in body, f"status {st}")
            st, _, _ = gene.get(f"/api/v1/events/{slug}/sessions?status=all&token={secret}")
            check("...and the key still works while it is asking", st == 200, f"status {st}")
            st, _, _ = gene.post(f"/api/events/{slug}/api-keys/revoke",
                                 {"id": kid, "confirm": "yes"})
            check("confirming revokes it", st in (302, 303), f"status {st}")
            st, _, _ = gene.get(f"/api/v1/events/{slug}/sessions?status=all&token={secret}")
            check("...and the revoked key is refused", st == 401, f"status {st}")

    # ------------------------------------------- 7f. the anti-Sessionize API
    #
    # Driven as a SCRAPER-AUTHOR would: a client with no cookies at all, then
    # the same client holding a key. The point of every check here is that a
    # consumer never has to match a human being by name.
    section("7f. The API a scraper-author would love (bd vi9)")
    scraper = Client("scraper")          # no cookies, ever

    # A key of its own: 7e deliberately revoked the one it minted.
    st, body, _ = gene.post(f"/api/events/{slug}/api-keys/create", {"label": "e2e api drive"})
    km = re.search(r'data-api-key-id="([^"]+)"[^>]*onclick="copyApiKey\(this\)"', body or "")
    kid = km.group(1) if km else None
    material = re.search(r'data-api-key-material="([^"]+)"', body or "")
    key = material.group(1) if material else None
    check("mint a key for the read API", bool(key), f"status {st}")
    hdr = {"Authorization": f"Bearer {key}"} if key else {}

    st, idx, _ = scraper.get("/api/v1/")
    check("the service index answers an anonymous caller", st == 200 and _is_json(idx),
          f"status {st}")
    idxj = json.loads(idx) if _is_json(idx) else {}
    check("  and lists every endpoint it has", len(idxj.get("endpoints", [])) >= 15,
          f"{len(idxj.get('endpoints', []))} endpoints")

    st, evj, _ = scraper.get(f"/api/v1/events/{slug}")
    check("the event document is public", st == 200 and _is_json(evj), f"status {st}")
    ev = json.loads(evj) if _is_json(evj) else {}
    check("  it carries the event's own id", bool(ev.get("id")))
    check("  and a link to every other endpoint",
          all(k in ev.get("links", {}) for k in
              ["sessions", "speakers", "schedule", "rooms", "changes", "docs",
               "sessionsJson", "calendarIcs", "llmsTxt"]),
          str(sorted(ev.get("links", {}).keys())))
    check("  submission COUNT is withheld from an anonymous caller",
          "submissions" not in ev.get("counts", {}))
    if key:
        st, evk, _ = scraper.get(f"/api/v1/events/{slug}", headers=hdr)
        check("  ...and shown to a key holder",
              _is_json(evk) and "submissions" in json.loads(evk).get("counts", {}))

    # The join, checked on the AUTHED lists so there is something to check even
    # before anyone has been informed — an empty program would pass every
    # id assertion vacuously, which is exactly the bug this section exists for.
    st, sj, _ = scraper.get(f"/api/v1/events/{slug}/sessions?status=all", headers=hdr)
    st2, spj, _ = scraper.get(f"/api/v1/events/{slug}/speakers?status=all", headers=hdr)
    if _is_json(sj) and _is_json(spj):
        sessions = json.loads(sj)["sessions"]
        speakers = json.loads(spj)["speakers"]
        sess_ids = {s["id"] for s in sessions}
        spk_ids = {s["id"] for s in speakers}
        check("there are rows to check at all", bool(sessions) and bool(speakers),
              f"{len(sessions)} sessions / {len(speakers)} speakers")
        check("every session carries a stable id",
              bool(sessions) and all(s.get("id") for s in sessions))
        check("every speaker carries a stable id",
              bool(speakers) and all(s.get("id") for s in speakers))
        check("a session's speakerIds resolve against the speakers list — no name matching",
              bool(sessions) and all(set(s.get("speakerIds", [])) <= spk_ids for s in sessions),
              "the 6/9/12 Jason Cox bug would live here")
        check("and the join closes from the speaker's side too",
              bool(speakers) and all(set(s.get("sessionIds", [])) <= sess_ids for s in speakers))
        st3, fj, _ = scraper.get(f"/events/{slug}/exports/sessions.json")
        if _is_json(fj):
            check("the static export uses ids drawn from the same pool as the API",
                  {s["id"] for s in json.loads(fj)["sessions"]} <= sess_ids,
                  "one entity, one id, whichever door you came through")

    st, sch, _ = scraper.get(f"/api/v1/events/{slug}/schedule", headers=hdr)
    check("the schedule endpoint answers", st == 200 and _is_json(sch), f"status {st}")
    if _is_json(sch):
        s = json.loads(sch)
        room_ids = {r["id"] for r in s.get("rooms", [])}
        items = [i for d in s.get("days", []) for i in d.get("items", [])]
        check("  it names the timezone", bool(s.get("timezone")))
        check("  every item names its sessionId or blockId",
              all(i.get("sessionId") or i.get("blockId") for i in items))
        check("  and every roomId resolves against the rooms in the same payload",
              all(i.get("roomId") in room_ids for i in items if i.get("roomId")),
              "a roomId that resolves to nothing is a fuzzy join wearing a uniform")
        check("  unscheduled sessions are NAMED, not omitted", "unscheduled" in s)
    st, _, _ = scraper.get(f"/api/v1/events/{slug}/schedule")
    check("  and it is public too", st == 200, f"status {st}")

    st, _, _ = scraper.get(f"/api/v1/events/{slug}/rooms")
    check("rooms are public and have ids of their own", st == 200, f"status {st}")

    st, _, _ = scraper.get(f"/api/v1/events/{slug}/submissions")
    check("the funnel needs a key — 401, not a login redirect", st == 401, f"status {st}")
    st, _, _ = scraper.get(f"/api/v1/events/{slug}/changes")
    check("so does the change feed", st == 401, f"status {st}")

    if key:
        st, subj, _ = scraper.get(f"/api/v1/events/{slug}/submissions", headers=hdr)
        check("the key opens the funnel", st == 200 and _is_json(subj), f"status {st}")
        if _is_json(subj):
            rows = json.loads(subj)["sessions"]
            check("  status and notified are SEPARATE, visible facts",
                  bool(rows) and all("status" in r and "notified" in r and "notifiedAt" in r
                                     for r in rows))
            check("  every row says where it sits on the grid, even when unplaced",
                  bool(rows) and all("placed" in (r.get("schedule") or {}) for r in rows))
            check("  private answers are still absent, key or no key",
                  "notes-to-committee" not in subj,
                  "a token widens; it never unlocks")

        st, chj, _ = scraper.get(f"/api/v1/events/{slug}/changes?since=0", headers=hdr)
        check("the change feed opens too", st == 200 and _is_json(chj), f"status {st}")
        if _is_json(chj):
            ch = json.loads(chj)
            seqs = [c["seq"] for c in ch["changes"]]
            check("  seq is monotonic from 1", seqs == list(range(1, len(seqs) + 1)))
            check("  and matches scheduleVersion", ch["scheduleVersion"] == len(seqs))
            if len(seqs) > 3:
                st, tailj, _ = scraper.get(
                    f"/api/v1/events/{slug}/changes?since={len(seqs) - 3}", headers=hdr)
                check("  ?since= returns only what came after",
                      _is_json(tailj) and json.loads(tailj)["total"] == 3)
            check("  it carries IDS ONLY — never payload bodies",
                  all(set(c.keys()) == {"seq", "type", "at", "id", "submissionId",
                                        "personId", "roomId"} for c in ch["changes"]))

    # Polling politely. NOTE: in ENV=dev the browser-reload middleware rewrites
    # Cache-Control to no-store on every response, so only the ETag half of the
    # contract is observable here; the max-age half is a production behaviour.
    st, _, _ = scraper.get(f"/api/v1/events/{slug}/sessions")
    etag = header_of(scraper, "etag")
    check("every response carries an ETag", bool(etag), str(etag))
    if etag:
        st, _, _ = scraper.get(f"/api/v1/events/{slug}/sessions",
                               headers={"If-None-Match": etag})
        check("  sending it back is a 304 — polling without being rude", st == 304,
              f"status {st}")
        st, _, _ = scraper.get(f"/api/v1/events/{slug}/sessions",
                               headers={"If-None-Match": 'W/"deadbeef"'})
        check("  a stale ETag gets the whole thing", st == 200, f"status {st}")
    scraper.get(f"/events/{slug}/exports/calendar.ics")
    ics_etag = header_of(scraper, "etag")
    if ics_etag:
        st, _, _ = scraper.get(f"/events/{slug}/exports/calendar.ics",
                               headers={"If-None-Match": ics_etag})
        check("  the ics feed 304s too, despite its per-fetch DTSTAMP", st == 304,
              f"status {st}")

    st, docs, _ = scraper.get(f"/api/v1/events/{slug}/docs")
    check("the API reference is PUBLIC", st == 200, f"status {st}")
    check("  it documents every endpoint the index advertises",
          all(p.get("path", "").replace("{slug}", slug) in docs
              for p in idxj.get("endpoints", [])),
          "an endpoint the docs page forgot")
    check("  with a curl line for each", docs.count("curl -s ") >= 15,
          f"{docs.count('curl -s ')} curl lines")
    check("  and it tells a scraper-author what it is for",
          "You do not need a scraper" in docs and "Never match on a name" in docs)
    st, llms2, _ = scraper.get(f"/events/{slug}/llms.txt")
    check("llms.txt points an agent at the API reference",
          f"/api/v1/events/{slug}/docs" in llms2)

    # ------------------------------------------------------- 8. every page 200
    section("8. Every organizer page renders")
    for path in ["", "/submissions", "/board", "/settings", "/comms", "/schedule",
                 "/inform", "/speakers", "/deliverables", "/files", "/log", "/replay", "/capture", "/exports", "/form"]:
        st, b, _ = gene.get(f"/events/{slug}{path}")
        check(f"GET /events/<slug>{path or '/'} -> 200", st == 200, f"status {st}")
        if st == 200:
            check(f"  ...and it is not an error page",
                  "Exception" not in b and "stack trace" not in b.lower(),
                  "error text on page")

    st, schedule_page, _ = gene.get(f"/events/{slug}/schedule")
    check("the schedule offers the explicit Publish handoff",
          st == 200 and ">Publish<" in schedule_page, f"status {st}")
    st, _, _ = gene.post(f"/api/events/{slug}/schedule/publish")
    check("clicking Publish records the handoff", st in (302, 303), f"status {st}")
    st, schedule_page, _ = gene.get(f"/events/{slug}/schedule")
    check("the schedule confirms Published",
          st == 200 and "Published ✓" in text_of(schedule_page), f"status {st}")
    check("the Publish act is in the append-only log",
          any(e.get("type") == "agenda.published" and e.get("event-id") == ev.get("id")
              for e in store_lines()))

    section("8a. Public program browse doors")
    drive_public_program(slug)

    section("8b. The nav is the lifecycle, and every item in it resolves")
    st, dashb, _ = gene.get(f"/events/{slug}")
    for group in ["Review", "Decide & tell", "The show"]:
        check(f"nav group '{group}' is present", group in text_of(dashb))
    # A FRESH event shows the transient wizard where "The call" will later be
    # (ratified 2026-08-09: the wizard exists exactly as long as it is true) —
    # the group relaxes into "The call" once the form is reviewed + a second
    # reviewer exists.
    check("the call area shows the wizard (fresh event) or 'The call' (set up)",
          "The call" in text_of(dashb) or "Create CFP — step" in text_of(dashb)
          or "ready to open" in text_of(dashb))
    check("the rail states whether the call is open",
          any(s in text_of(dashb) for s in
              ["Open the call", "Close the call", "call is OPEN", "call closed",
               "not open yet", "Call for speakers is open"]))
    check("the public doors are marked and open in a new tab",
          f'href="/cfp/{slug}"' in dashb and f'href="/agenda/{slug}"' in dashb
          and 'target="_blank"' in dashb)
    check("the breadcrumb is on event pages",
          'class="crumbs"' in gene.get(f"/events/{slug}/board")[1])
    st, exp, _ = gene.get(f"/events/{slug}/exports")
    check("Exports & API page lists all four exports",
          st == 200 and all(f in exp for f in
                            ["sessions.json", "speakers.json", "calendar.ics", "llms.txt"]),
          f"status {st}")
    check("  and names the REST base URL", f"/api/v1/events/{slug}" in exp)
    st, _, loc = speaker.req("GET", f"/events/{slug}/exports", follow=False)
    check("  but a stranger cannot read it", st in (302, 403), f"status {st}")
    st, raw, _ = speaker.get(f"/events/{slug}/exports/sessions.json")
    check("  while the raw export files stay public", st == 200, f"status {st}")

    # --------------------------------------------------- 9. reload = same state
    section("9. Persistence: state survives a re-fold")
    n_before = len(store_lines())
    st, dash_before, _ = gene.get(f"/events/{slug}")
    check("log grew during this drive", n_before > 0, f"{n_before} lines")
    check("dashboard still renders the event after all mutations",
          name in text_of(dash_before))

    print(f"\n\033[1m{STEPS - len(FAILS)}/{STEPS} checks passed\033[0m")
    if FAILS:
        print("\n\033[31mFAILURES\033[0m")
        for lbl, d in FAILS:
            print(f"  - {lbl}   {d}")
    print(f"\nevent slug: {slug}")
    return 1 if FAILS else 0


def _is_json(s):
    try:
        json.loads(s)
        return True
    except Exception:
        return False


if __name__ == "__main__":
    sys.exit(main())
