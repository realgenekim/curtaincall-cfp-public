#!/usr/bin/env python3
"""Fail-closed HTTP acceptance drive for the event-scoped MCP interface."""

import argparse
import json
import sys
import urllib.error
import urllib.parse
import urllib.request


FORBIDDEN_NAME_PARTS = ("sql", "eval", "exec", "append_fact", "delete")


def post(endpoint, request_id, method, params=None):
    payload = {"jsonrpc": "2.0", "id": request_id, "method": method}
    if params is not None:
        payload["params"] = params
    request = urllib.request.Request(
        endpoint,
        data=json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=10) as response:
            body = json.load(response)
    except (urllib.error.URLError, TimeoutError, json.JSONDecodeError) as error:
        raise AssertionError(f"MCP request failed: {method}: {error}") from error
    assert body.get("jsonrpc") == "2.0", body
    assert body.get("id") == request_id, body
    assert "error" not in body, body
    return body["result"]


def tool_call(endpoint, request_id, name, arguments=None):
    return post(
        endpoint,
        request_id,
        "tools/call",
        {"name": name, "arguments": arguments or {}},
    )


def drive(base_url, event_slug):
    encoded_slug = urllib.parse.quote(event_slug, safe="")
    endpoint = f"{base_url.rstrip('/')}/events/{encoded_slug}/mcp"

    initialized = post(
        endpoint,
        1,
        "initialize",
        {
            "protocolVersion": "2025-06-18",
            "capabilities": {},
            "clientInfo": {"name": "agent-drive", "version": "1"},
        },
    )
    assert initialized["serverInfo"]["name"] == "cfp-scheduler-killer", initialized

    discovered = post(endpoint, 2, "tools/list", {})
    names = [tool["name"] for tool in discovered["tools"]]
    assert len(names) >= 5, names
    assert "get_event" in names, names
    assert "review_coverage" in names, names
    assert not any(
        forbidden in name for name in names for forbidden in FORBIDDEN_NAME_PARTS
    ), names

    public = tool_call(endpoint, 3, "get_event")
    assert public["isError"] is False, public
    assert public["structuredContent"]["event"]["slug"] == event_slug, public

    private = tool_call(endpoint, 4, "review_coverage")
    assert private["isError"] is True, private
    assert private["structuredContent"]["error"]["type"] == "forbidden", private

    return {
        "endpoint": endpoint,
        "server": initialized["serverInfo"],
        "toolCount": len(names),
        "publicCall": "ok",
        "anonymousPrivateCall": "rejected",
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", default="http://localhost:20501")
    parser.add_argument(
        "--event", default="enterprise-ai-summit-charlotte-2026"
    )
    args = parser.parse_args()
    try:
        result = drive(args.base, args.event)
    except AssertionError as error:
        print(f"AGENT DRIVE FAILED: {error}", file=sys.stderr)
        return 1
    print(json.dumps(result, sort_keys=True))
    print("AGENT DRIVE PASSED")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
