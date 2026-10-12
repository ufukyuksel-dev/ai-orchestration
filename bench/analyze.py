#!/usr/bin/env python3
"""v2 analysis with the decision rules frozen in output/plan-harness-benchmark-v2.md §2 and §6.

Rows: every results.jsonl of the given run directories, kept only when their (cell, attempt) is the completed
attempt recorded in cells/<cell>.json (interrupted attempts are superseded). Rows scored `success: null` (scorer
crash) are infra failures: excluded and counted.

Per task and arm, repetitions are averaged first (tokens and success rate); pools are task-weighted.
  R = 1 - (sum_t tokens_B / sum_t success_B) / (sum_t tokens_A / sum_t success_A)   (failed-run tokens retained)
  success difference = mean_t (success_B - success_A)
CIs: paired cluster bootstrap, 10,000 resamples; a cluster is a whole memory chain (family with "chain": true)
or a single task otherwise.

  python3 bench/analyze.py --runs v2-claude-A,v2-claude-B --treatment B --control A
  python3 bench/analyze.py --runs ... --treatment B --control Bnomem --role control   # Q5 injection tax
"""
from __future__ import annotations

import argparse
import json
import random
from collections import defaultdict
from pathlib import Path

RESULTS = Path(__file__).resolve().parent / ".work" / "results"
TARGET = 0.40          # Q1 token reduction
NONINFERIORITY = -0.05  # Q2: success difference margin (B - A)
TAX_LIMIT = 0.05        # Q5: B may cost at most +5 % tokens vs. Bnomem
MIN_TASKS_FOR_CI = 6    # per-type slices below this are descriptive only
RESAMPLES = 10_000


def load(run_ids: list[str]) -> tuple[list[dict], dict]:
    rows, dropped = [], {"superseded": 0, "incompleteCell": 0, "infra": 0}
    for run_id in run_ids:
        out = RESULTS / run_id
        done = {json.loads(p.read_text())["cell"]: json.loads(p.read_text())["attempt"]
                for p in (out / "cells").glob("*.json")} if (out / "cells").exists() else {}
        for line in (out / "results.jsonl").read_text().splitlines():
            if not line.strip():
                continue
            row = json.loads(line)
            if row.get("cell") not in done:
                dropped["incompleteCell"] += 1
            elif row.get("attempt") != done[row["cell"]]:
                dropped["superseded"] += 1
            elif row.get("success") is None:
                dropped["infra"] += 1
            else:
                rows.append(row)
    return rows, dropped


def per_task(rows: list[dict]) -> dict[tuple, dict]:
    """(agent, arm, task) -> mean tokens, success rate, cluster, type."""
    groups: dict[tuple, list[dict]] = defaultdict(list)
    for r in rows:
        groups[(r["agent"], r["arm"], r["task"])].append(r)
    return {k: {"tokens": sum(r["totalTokens"] for r in v) / len(v),
                "success": sum(bool(r["success"]) for r in v) / len(v), "reps": len(v),
                "cluster": f"{v[0]['suite']}/{v[0]['family']}" if v[0].get("chain") else v[0]["task"],
                "type": v[0].get("type", ""), "role": v[0].get("role", "")}
            for k, v in groups.items()}


def reduction(pairs: list[tuple[dict, dict]]) -> float | None:
    tok_t = sum(t["tokens"] for t, _ in pairs)
    ok_t = sum(t["success"] for t, _ in pairs)
    tok_c = sum(c["tokens"] for _, c in pairs)
    ok_c = sum(c["success"] for _, c in pairs)
    if not ok_t or not ok_c:
        return None  # zero successes: undefined, a quality failure, never zero cost
    return 1 - (tok_t / ok_t) / (tok_c / ok_c)


def success_diff(pairs: list[tuple[dict, dict]]) -> float:
    return sum(t["success"] - c["success"] for t, c in pairs) / len(pairs)


def bootstrap(pairs: list[tuple[dict, dict]], stat, seed: int = 7) -> tuple[float | None, float | None]:
    clusters: dict[str, list] = defaultdict(list)
    for p in pairs:
        clusters[p[0]["cluster"]].append(p)
    keys = sorted(clusters)
    rnd = random.Random(seed)
    values = []
    for _ in range(RESAMPLES):
        sample = [p for k in (rnd.choice(keys) for _ in keys) for p in clusters[k]]
        v = stat(sample)
        if v is not None:
            values.append(v)
    if len(values) < RESAMPLES * 0.95:
        return None, None  # too many undefined resamples: no interval
    values.sort()
    return values[int(0.025 * len(values))], values[int(0.975 * len(values)) - 1]


def q1_label(point, low) -> str:
    if point is None:
        return "undefined (zero successes)"
    labels = []
    if low is not None and low > 0:
        labels.append("reduction shown")
    if point >= TARGET:
        labels.append("target met")
    if low is not None and low >= TARGET:
        labels.append("target confirmed")
    return ", ".join(labels) or "not shown"


def q2_label(low, high) -> str:
    if low is None:
        return "inconclusive"
    if low >= NONINFERIORITY:
        return "pass"
    if high < NONINFERIORITY:
        return "fail"
    return "inconclusive"


def tax_label(point, low, high) -> str:
    """Q5 on tokens: tax = -R (extra cost of the treatment); pass if the upper CI of the tax <= +5 %."""
    if point is None or low is None:
        return "inconclusive"
    tax_high, tax_low = -low, -high
    if tax_high <= TAX_LIMIT:
        return "pass"
    if tax_low > TAX_LIMIT:
        return "fail"
    return "inconclusive"


def compare(tasks: dict, agent: str, treatment: str, control: str, select=lambda t: True) -> dict:
    pairs = [(tasks[(agent, treatment, task)], tasks[(agent, control, task)])
             for (a, arm, task) in tasks if a == agent and arm == treatment
             and (agent, control, task) in tasks and select(tasks[(agent, treatment, task)])]
    if not pairs:
        return {"tasks": 0}
    r = reduction(pairs)
    d = success_diff(pairs)
    r_ci = bootstrap(pairs, reduction)
    d_ci = bootstrap(pairs, success_diff)
    return {"tasks": len(pairs), "clusters": len({p[0]["cluster"] for p in pairs}),
            "reduction": r, "reductionCI": r_ci, "successDiff": d, "successDiffCI": d_ci,
            "treatmentSuccess": sum(t["success"] for t, _ in pairs) / len(pairs),
            "controlSuccess": sum(c["success"] for _, c in pairs) / len(pairs),
            "q1": q1_label(r, r_ci[0]), "q2": q2_label(*d_ci), "tax": tax_label(r, *r_ci)}


def fmt(v, pct=True) -> str:
    return "n/a" if v is None else (f"{100 * v:+.1f}%" if pct else f"{v:.2f}")


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--runs", required=True, help="comma list of run ids under bench/.work/results")
    ap.add_argument("--treatment", default="B")
    ap.add_argument("--control", default="A")
    ap.add_argument("--role", default="", help="only tasks with this role (control, stale, ...)")
    ap.add_argument("--json", default="")
    args = ap.parse_args()
    rows, dropped = load([r for r in args.runs.split(",") if r])
    tasks = per_task(rows)
    agents = sorted({a for a, _, _ in tasks})
    role = (lambda t: t["role"] == args.role) if args.role else (lambda t: True)
    report = {"dropped": dropped, "agents": {}}
    print(f"rows kept: {len(rows)}; dropped: {dropped}")
    print(f"| Agent | Scope | Tasks | {args.treatment} ok | {args.control} ok | tokens R (95% CI) | Q1 | "
          f"success Δ (95% CI) | Q2 | tax |")
    print("|---|---|---|---|---|---|---|---|---|---|")
    for agent in agents:
        scopes = {"all": compare(tasks, agent, args.treatment, args.control, role)}
        types = sorted({t["type"] for (a, _, _), t in tasks.items() if a == agent and t["type"]})
        for typ in types:
            res = compare(tasks, agent, args.treatment, args.control, lambda t, typ=typ: role(t) and t["type"] == typ)
            if res["tasks"] and res["tasks"] < MIN_TASKS_FOR_CI:
                res |= {"reductionCI": (None, None), "successDiffCI": (None, None), "q1": "descriptive",
                        "q2": "descriptive", "tax": "descriptive"}
            scopes[f"type {typ}"] = res
        report["agents"][agent] = scopes
        for scope, res in scopes.items():
            if not res["tasks"]:
                continue
            rl, rh = res["reductionCI"]
            dl, dh = res["successDiffCI"]
            print(f"| {agent} | {scope} | {res['tasks']} | {res['treatmentSuccess']:.0%} | {res['controlSuccess']:.0%} | "
                  f"**{fmt(res['reduction'])}** ({fmt(rl)} … {fmt(rh)}) | {res['q1']} | "
                  f"{fmt(res['successDiff'])} ({fmt(dl)} … {fmt(dh)}) | {res['q2']} | {res['tax']} |")
    if args.json:
        Path(args.json).write_text(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
