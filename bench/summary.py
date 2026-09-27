#!/usr/bin/env python3
"""Summarise a benchmark run: success rate and cost-per-success per agent/arm, related-task (T2..Tn)
savings, net savings including the learning task T1, and the break-even task count.

  python3 bench/summary.py bench/.work/results/<run-id>   # writes summary.json next to results.jsonl
"""
from __future__ import annotations

import json
import sys
from collections import defaultdict
from pathlib import Path


def cost_per_success(rows: list[dict]) -> float | None:
    ok = sum(1 for r in rows if r["success"])
    return sum(r["totalTokens"] for r in rows) / ok if ok else None


def summarize(rows: list[dict]) -> dict:
    by = defaultdict(list)
    for r in rows:
        by[(r["agent"], r["family"], r["arm"])].append(r)
    families = {}
    for (agent, family, arm), rs in sorted(by.items()):
        related = [r for r in rs if r["position"] > 1]
        learn = [r for r in rs if r["position"] == 1]
        families.setdefault(f"{agent}/{family}", {})[arm] = {
            "runs": len(rs), "successRate": round(sum(r["success"] for r in rs) / len(rs), 3),
            "relatedCostPerSuccess": cost_per_success(related),
            "learningTaskTokensMean": round(sum(r["totalTokens"] for r in learn) / len(learn)) if learn else None,
            "allTasksCostPerSuccess": cost_per_success(rs),
            "meanTurns": round(sum(r["turns"] for r in rs) / len(rs), 1),
        }
    for key, arms in families.items():
        a, b = arms.get("A"), arms.get("B")
        if not (a and b):
            continue
        cmp = {}
        if a["relatedCostPerSuccess"] and b["relatedCostPerSuccess"]:
            cmp["relatedSavingsPct"] = round(100 * (1 - b["relatedCostPerSuccess"] / a["relatedCostPerSuccess"]), 1)
        if a["allTasksCostPerSuccess"] and b["allTasksCostPerSuccess"]:
            cmp["netSavingsInclT1Pct"] = round(100 * (1 - b["allTasksCostPerSuccess"] / a["allTasksCostPerSuccess"]), 1)
        if a["learningTaskTokensMean"] and b["learningTaskTokensMean"] and a["relatedCostPerSuccess"] \
                and b["relatedCostPerSuccess"]:
            tax = b["learningTaskTokensMean"] - a["learningTaskTokensMean"]
            gain = a["relatedCostPerSuccess"] - b["relatedCostPerSuccess"]
            cmp["learningTaxTokens"] = tax
            cmp["breakEvenRelatedTasks"] = (round(tax / gain, 2) if gain > 0 and tax > 0
                                            else (0 if tax <= 0 < gain else None))
        cmp["successRateDelta"] = round(b["successRate"] - a["successRate"], 3)
        arms["comparison"] = cmp
    return families


def main() -> None:
    run = Path(sys.argv[1])
    rows = [json.loads(line) for line in (run / "results.jsonl").read_text().splitlines() if line.strip()]
    summary = {"runs": len(rows), "families": summarize(rows)}
    (run / "summary.json").write_text(json.dumps(summary, indent=2))
    for key, arms in summary["families"].items():
        print(key)
        for arm in ("A", "B"):
            if arm in arms:
                s = arms[arm]
                print(f"  {arm}: success {s['successRate']:.0%}  related cost/success {s['relatedCostPerSuccess']}"
                      f"  T1 tokens {s['learningTaskTokensMean']}  turns {s['meanTurns']}")
        if "comparison" in arms:
            print("  ->", arms["comparison"])


if __name__ == "__main__":
    main()
