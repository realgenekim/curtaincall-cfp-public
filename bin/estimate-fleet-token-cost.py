#!/usr/bin/env python3
"""Estimate API retail-equivalent cost for Codex fleet rollouts.

The Codex thread database stores one cumulative ``tokens_used`` value per
thread.  The corresponding rollout JSONL preserves the breakdown required for
pricing: input, cached input, and output tokens.  This program reconciles the
two sources before estimating cost.

Prices are standard API rates per million tokens from the official OpenAI
pricing page, checked 2026-08-16:
https://developers.openai.com/api/docs/pricing

GPT-5.3 Codex Spark is a subscription model without a separately published API
price.  Its model-matched estimate uses the published GPT-5.3 Codex API rate as
an explicitly named proxy.  The report also supplies an all-GPT-5.6-Sol
equivalent so the proxy never masquerades as an exact price.
"""

from __future__ import annotations

import argparse
import json
import sqlite3
from collections import defaultdict
from pathlib import Path


MILLION = 1_000_000

PRICES = {
    "gpt-5.6-sol": {
        "input": 5.00,
        "cached_input": 0.50,
        "output": 30.00,
        "basis": "published gpt-5.6-sol standard API rate",
    },
    "gpt-5.6-codex-sol": {
        "input": 5.00,
        "cached_input": 0.50,
        "output": 30.00,
        "basis": "gpt-5.6-sol alias rate",
    },
    "gpt-5.3-codex-spark": {
        "input": 1.75,
        "cached_input": 0.175,
        "output": 14.00,
        "basis": "published gpt-5.3-codex standard API rate used as proxy",
    },
    "gpt-5.6-luna": {
        "input": 0.20,
        "cached_input": 0.02,
        "output": 1.20,
        "basis": "published gpt-5.6-luna standard API rate",
    },
}


def last_total_usage(rollout_path: Path) -> dict[str, int] | None:
    """Return the final cumulative token-usage record in one rollout."""
    last = None
    try:
        with rollout_path.open(errors="replace") as stream:
            for line in stream:
                if '"token_count"' not in line:
                    continue
                try:
                    event = json.loads(line)
                except json.JSONDecodeError:
                    continue
                payload = event.get("payload") or {}
                if event.get("type") != "event_msg" or payload.get("type") != "token_count":
                    continue
                usage = ((payload.get("info") or {}).get("total_token_usage") or {})
                if usage:
                    last = usage
    except OSError:
        return None
    return last


def empty_tokens() -> dict[str, int]:
    return {
        "input_tokens": 0,
        "cached_input_tokens": 0,
        "cache_write_input_tokens": 0,
        "output_tokens": 0,
        "reasoning_output_tokens": 0,
        "total_tokens": 0,
    }


def add_tokens(target: dict[str, int], usage: dict[str, int]) -> None:
    for key in target:
        target[key] += int(usage.get(key, 0) or 0)


def scan_home(codex_home: Path) -> dict:
    db_path = codex_home / "state_5.sqlite"
    result = {
        "home": str(codex_home),
        "threads": 0,
        "db_tokens": 0,
        "rollout_tokens": 0,
        "missing_rollouts": [],
        "mismatched_threads": [],
        "by_model": {},
    }
    if not db_path.is_file():
        result["error"] = "state_5.sqlite missing"
        return result

    connection = sqlite3.connect(f"file:{db_path}?mode=ro", uri=True)
    rows = connection.execute(
        "SELECT id, rollout_path, tokens_used, COALESCE(model, '(unknown)') FROM threads"
    ).fetchall()
    connection.close()
    result["threads"] = len(rows)

    by_model = defaultdict(empty_tokens)
    for thread_id, rollout_raw, db_tokens, model in rows:
        db_tokens = int(db_tokens or 0)
        result["db_tokens"] += db_tokens
        usage = last_total_usage(Path(rollout_raw))
        if usage is None:
            if db_tokens:
                result["missing_rollouts"].append(
                    {"thread_id": thread_id, "tokens": db_tokens, "path": rollout_raw}
                )
            continue
        rollout_tokens = int(usage.get("total_tokens", 0) or 0)
        result["rollout_tokens"] += rollout_tokens
        add_tokens(by_model[model], usage)
        if rollout_tokens != db_tokens:
            result["mismatched_threads"].append(
                {"thread_id": thread_id, "db_tokens": db_tokens, "rollout_tokens": rollout_tokens}
            )

    result["by_model"] = dict(by_model)
    return result


def cost_for(tokens: dict[str, int], prices: dict[str, float]) -> float:
    cached = tokens["cached_input_tokens"]
    uncached = max(0, tokens["input_tokens"] - cached)
    return (
        uncached * prices["input"]
        + cached * prices["cached_input"]
        + tokens["output_tokens"] * prices["output"]
    ) / MILLION


def build_report(homes: list[Path]) -> dict:
    scans = [scan_home(home) for home in homes]
    fleet = empty_tokens()
    by_model = defaultdict(empty_tokens)
    for scan in scans:
        for model, usage in scan.get("by_model", {}).items():
            add_tokens(by_model[model], usage)
            add_tokens(fleet, usage)

    priced = []
    unpriced = []
    model_matched_cost = 0.0
    for model, usage in sorted(by_model.items()):
        prices = PRICES.get(model)
        row = {"model": model, "tokens": usage}
        if prices:
            row["prices_per_million_usd"] = {
                key: prices[key] for key in ("input", "cached_input", "output")
            }
            row["pricing_basis"] = prices["basis"]
            row["retail_equivalent_usd"] = round(cost_for(usage, prices), 2)
            model_matched_cost += row["retail_equivalent_usd"]
            priced.append(row)
        else:
            unpriced.append(row)

    sol_prices = PRICES["gpt-5.6-sol"]
    missing_tokens = sum(
        item["tokens"] for scan in scans for item in scan.get("missing_rollouts", [])
    )
    mismatches = sum(len(scan.get("mismatched_threads", [])) for scan in scans)
    return {
        "schema": "fleet-token-retail-estimate.v1",
        "pricing_source": "https://developers.openai.com/api/docs/pricing",
        "pricing_checked_on": "2026-08-16",
        "scope": {"codex_homes": [str(home) for home in homes]},
        "reconciliation": {
            "state": "complete" if not missing_tokens and not mismatches else "incomplete",
            "database_tokens": sum(scan.get("db_tokens", 0) for scan in scans),
            "rollout_tokens": fleet["total_tokens"],
            "missing_rollout_tokens": missing_tokens,
            "mismatched_threads": mismatches,
        },
        "fleet_tokens": fleet,
        "by_model": priced,
        "unpriced_models": unpriced,
        "retail_equivalent_usd": {
            "model_matched_with_named_spark_proxy": round(model_matched_cost, 2),
            "all_tokens_as_gpt_5_6_sol": round(cost_for(fleet, sol_prices), 2),
        },
        "does_not_prove": [
            "historical invoice amount",
            "a separately published GPT-5.3 Codex Spark API price",
            "Claude Code token usage or API-equivalent value",
            "tokens from Codex homes outside the enumerated scope",
        ],
        "homes": scans,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--codex-home",
        action="append",
        required=True,
        type=Path,
        help="Codex state directory; repeat for each seat",
    )
    parser.add_argument("--pretty", action="store_true")
    args = parser.parse_args()
    report = build_report(args.codex_home)
    print(json.dumps(report, indent=2 if args.pretty else None, sort_keys=True))
    return 0 if report["reconciliation"]["state"] == "complete" else 2


if __name__ == "__main__":
    raise SystemExit(main())
