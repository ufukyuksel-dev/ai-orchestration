#!/usr/bin/env python3
"""Pooled A/B comparison over several result directories (the acceptance numbers of a token-lever measurement).

Per agent and arm: all runs of all suites together, failures included.
  tokens per success = sum of all tokens / number of successful tasks     (the main number)
  first task         = mean tokens of position-1 tasks (where B pays for learning)
  follow-up          = tokens per success of positions 2..n

  python3 bench/pooled.py --a claude=base-claude-A,py-claude-A --b claude=fin-claude-B-holdout ...
"""
from __future__ import annotations

import argparse
import json
from collections import defaultdict
from pathlib import Path

RESULTS = Path(__file__).resolve().parent / ".work" / "results"


def rows(run_ids: list[str]) -> list[dict]:
    out = []
    for run_id in run_ids:
        path = RESULTS / run_id / "results.jsonl"
        out += [json.loads(line) for line in path.read_text().splitlines() if line.strip()]
    return out


def suite_of(task: str) -> str:
    return {"h": "holdout", "p": "py-holdout", "g": "go-holdout"}.get(task[0], task[0])


def stats(items: list[dict]) -> dict:
    ok = sum(r["success"] for r in items)
    tokens = sum(r["totalTokens"] for r in items)
    first = [r["totalTokens"] for r in items if r["position"] == 1]
    follow = [r for r in items if r["position"] > 1]
    follow_ok = sum(r["success"] for r in follow)
    return {"runs": len(items), "success": ok, "tokensPerSuccess": tokens / ok if ok else None,
            "firstTaskMean": sum(first) / len(first) if first else None,
            "followUpPerSuccess": sum(r["totalTokens"] for r in follow) / follow_ok if follow_ok else None,
            "turnsMean": sum(r.get("turns") or 0 for r in items) / len(items) if items else None}


def parse(specs: list[str]) -> dict[str, list[str]]:
    out: dict[str, list[str]] = defaultdict(list)
    for spec in specs:
        agent, runs = spec.split("=", 1)
        out[agent] += [r for r in runs.split(",") if r]
    return out


def pct(b, a) -> str:
    return f"{100 * (b - a) / a:+.1f}%" if a and b is not None else "n/a"


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--a", action="append", default=[], help="agent=run,run,... for arm A")
    ap.add_argument("--b", action="append", default=[], help="agent=run,run,... for arm B")
    ap.add_argument("--json", default="", help="also write the numbers to this file")
    args = ap.parse_args()
    a_runs, b_runs = parse(args.a), parse(args.b)
    report = {}
    for agent in sorted(set(a_runs) | set(b_runs)):
        a_rows = [r for r in rows(a_runs[agent]) if r["arm"] == "A"]
        b_rows = [r for r in rows(b_runs[agent]) if r["arm"] == "B"]
        report[agent] = {"all": {"A": stats(a_rows), "B": stats(b_rows)}}
        for suite in sorted({suite_of(r["task"]) for r in a_rows + b_rows}):
            report[agent][suite] = {"A": stats([r for r in a_rows if suite_of(r["task"]) == suite]),
                                    "B": stats([r for r in b_rows if suite_of(r["task"]) == suite])}
    print("| Agent | Scope | A success | B success | A tokens/success | B tokens/success | Δ | A first task | "
          "B first task | Δ first | A follow-up/success | B follow-up/success |")
    print("|---|---|---|---|---|---|---|---|---|---|---|---|")
    for agent, scopes in report.items():
        for scope, ab in scopes.items():
            a, b = ab["A"], ab["B"]
            k = lambda v: f"{v / 1000:.1f}k" if v is not None else "n/a"
            print(f"| {agent} | {scope} | {a['success']}/{a['runs']} | {b['success']}/{b['runs']} | "
                  f"{k(a['tokensPerSuccess'])} | {k(b['tokensPerSuccess'])} | "
                  f"**{pct(b['tokensPerSuccess'], a['tokensPerSuccess'])}** | {k(a['firstTaskMean'])} | "
                  f"{k(b['firstTaskMean'])} | {pct(b['firstTaskMean'], a['firstTaskMean'])} | "
                  f"{k(a['followUpPerSuccess'])} | {k(b['followUpPerSuccess'])} |")
    if args.json:
        Path(args.json).write_text(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
