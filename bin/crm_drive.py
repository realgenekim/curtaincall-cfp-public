#!/usr/bin/env python3
"""Fail-closed HTTP acceptance drive for the organization-level Speaker CRM.

This is intentionally smaller than e2e_drive.py. It proves that a cold running
server—not merely an in-process Ring handler—has the CRM routes, session
plumbing, tenant-scoped projections, and human-reviewed/no-send outreach path.

Usage:
  python3 bin/crm_drive.py --base http://localhost:20501 \
      --log data/store/events.jsonl
"""

import argparse
import http.cookiejar
import json
import re
import sys
import urllib.error
import urllib.parse
import urllib.request


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


class Client:
    def __init__(self, base):
        self.base = base.rstrip("/")
        self.opener = urllib.request.build_opener(
            urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()),
            NoRedirect(),
        )

    def request(self, method, path, data=None):
        body = urllib.parse.urlencode(data, doseq=True).encode() if data else None
        request = urllib.request.Request(self.base + path, data=body, method=method)
        if body:
            request.add_header("Content-Type", "application/x-www-form-urlencoded")
        try:
            response = self.opener.open(request)
            return response.status, response.read().decode("utf-8", "replace"), response.headers.get("Location")
        except urllib.error.HTTPError as error:
            return error.code, error.read().decode("utf-8", "replace"), error.headers.get("Location")

    def get(self, path):
        return self.request("GET", path)

    def post(self, path, data):
        return self.request("POST", path, data)


class Drive:
    def __init__(self):
        self.checks = 0
        self.failures = []

    def check(self, label, condition, detail=""):
        self.checks += 1
        if condition:
            print(f"  \033[32m✓\033[0m {label}")
        else:
            print(f"  \033[31m✗ {label}\033[0m   {detail}")
            self.failures.append((label, detail))


def login(client, email):
    status, body, _ = client.post("/api/login", {"email": email})
    token = re.search(r"/auth/([A-Za-z0-9-]+)", body)
    if status != 200 or not token:
        return False
    status, _, _ = client.get(f"/auth/{token.group(1)}")
    return status in (200, 302, 303)


def fact_types(path):
    with open(path, encoding="utf-8") as stream:
        return [json.loads(line)["type"] for line in stream if line.strip()]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", default="http://localhost:20501")
    parser.add_argument("--email", default="genek@itrevolution.net")
    parser.add_argument("--log", default="data/store/events.jsonl")
    args = parser.parse_args()

    drive = Drive()
    client = Client(args.base)

    print("\n\033[1m1. Sign in and open the organization-level directory\033[0m")
    drive.check("organizer signs in", login(client, args.email))
    status, directory, _ = client.get("/people")
    drive.check("GET /people returns 200", status == 200, f"status {status}")
    for literal in (
        "One canonical relationship history",
        "All organizations",
        "All relationships",
        "All events",
        "Sourcing pipeline",
        "Saved segments",
        "Human-reviewed outreach",
    ):
        drive.check(f"directory renders {literal!r}", literal in directory)

    person = re.search(r'href="/people/([0-9a-f-]{36})"', directory)
    event = re.search(r'name="event-id"[^>]*value="([0-9a-f-]{36})"', directory)
    if not event:
        event = re.search(r'<option value="([0-9a-f-]{36})"', directory)
    drive.check("directory links at least one canonical contact", person is not None)
    drive.check("directory exposes an authorized target event", event is not None)

    if person:
        print("\n\033[1m2. Contact detail and search are wired\033[0m")
        person_id = person.group(1)
        status, detail, _ = client.get(f"/people/{person_id}")
        drive.check("contact detail returns 200", status == 200, f"status {status}")
        drive.check("detail renders Events and roles", "Events and roles" in detail)
        drive.check("detail renders Activity history", "Activity history" in detail)
        drive.check("detail offers Push contact into event", "Push contact into event" in detail)
        email = re.search(r"[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}", detail)
        if email:
            status, filtered, _ = client.get("/people?q=" + urllib.parse.quote(email.group(0)))
            drive.check("email search returns the contact", status == 200 and person_id in filtered)

    print("\n\033[1m3. Outreach is previewed and recorded, never sent\033[0m")
    status, composer, _ = client.get("/people/outreach")
    drive.check("outreach composer returns 200", status == 200, f"status {status}")
    drive.check("composer says it never sends automatically", "never sends mail automatically" in composer)
    drive.check("composer offers recipient preview", "Preview every recipient" in composer)

    if person and event:
        params = {
            "event-id": event.group(1),
            "person-id": person.group(1),
            "subject": "Invitation for {name}",
            "body": "Hello {name} at {organization} ({email})",
        }
        before = fact_types(args.log)
        status, preview, _ = client.post("/api/people/outreach/preview", params)
        after_preview = fact_types(args.log)
        drive.check("preview returns 200", status == 200, f"status {status}")
        drive.check("preview resolves the recipient", "Resolved recipient previews" in preview)
        drive.check("preview appends zero facts", after_preview == before)
        status, _, _ = client.post("/api/people/outreach/record", params)
        after_record = fact_types(args.log)
        drive.check("record returns a redirect", status == 303, f"status {status}")
        drive.check("record appends crm.outreach-drafted", after_record[-1:] == ["crm.outreach-drafted"])
        drive.check("CRM drive appends no comms.sent", "comms.sent" not in after_record[len(before):])

    if drive.failures:
        print(f"\n\033[31m{len(drive.failures)} of {drive.checks} checks failed\033[0m")
        return 1
    print(f"\n\033[1m{drive.checks}/{drive.checks} CRM checks passed\033[0m")
    return 0


if __name__ == "__main__":
    sys.exit(main())
