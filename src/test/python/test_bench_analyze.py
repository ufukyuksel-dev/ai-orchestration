"""bench/analyze.py: the frozen v2 decision rules."""
from __future__ import annotations

import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "bench"))
import analyze  # noqa: E402


def row(task, arm, tokens, success, cell, attempt="a1", agent="claude", family=None, chain=False, typ="B", rep=1):
    return {"agent": agent, "arm": arm, "task": task, "totalTokens": tokens, "success": success, "cell": cell,
            "attempt": attempt, "family": family or task, "chain": chain, "suite": "s", "type": typ, "role": "",
            "rep": rep}


def write_run(tmp_path, run_id, rows, done):
    out = tmp_path / run_id
    (out / "cells").mkdir(parents=True)
    (out / "results.jsonl").write_text("\n".join(json.dumps(r) for r in rows) + "\n")
    for cell, attempt in done.items():
        (out / "cells" / f"{cell}.json").write_text(json.dumps({"cell": cell, "attempt": attempt}))


def test_load_keeps_only_completed_attempts_and_drops_infra(tmp_path, monkeypatch):
    monkeypatch.setattr(analyze, "RESULTS", tmp_path)
    rows = [row("t1", "B", 100, True, "c1", "old"), row("t1", "B", 90, True, "c1", "new"),
            row("t2", "B", 50, None, "c2"), row("t3", "B", 10, True, "c3")]
    write_run(tmp_path, "r", rows, {"c1": "new", "c2": "a1"})
    kept, dropped = analyze.load(["r"])
    assert [r["totalTokens"] for r in kept] == [90]
    assert dropped == {"superseded": 1, "incompleteCell": 1, "infra": 1}


def test_reduction_formula_retains_failed_tokens_and_zero_success_is_undefined():
    a = {"tokens": 100, "success": 1.0}
    b_fail = {"tokens": 40, "success": 0.0}
    b_ok = {"tokens": 40, "success": 1.0}
    # B: 80 tokens / 1 success = 80 per success; A: 200 / 2 = 100 -> R = 20 %
    assert abs(analyze.reduction([(b_ok, a), (b_fail, a)]) - 0.2) < 1e-9
    assert analyze.reduction([(b_fail, a)]) is None


def test_labels():
    assert analyze.q1_label(0.45, 0.41) == "reduction shown, target met, target confirmed"
    assert analyze.q1_label(0.45, 0.30) == "reduction shown, target met"
    assert analyze.q1_label(0.10, -0.05) == "not shown"
    assert analyze.q2_label(-0.04, 0.03) == "pass"
    assert analyze.q2_label(-0.20, -0.06) == "fail"
    assert analyze.q2_label(-0.08, 0.02) == "inconclusive"
    assert analyze.tax_label(0.0, -0.03, 0.02) == "pass"        # tax at most +3 %
    assert analyze.tax_label(-0.2, -0.3, -0.1) == "fail"        # tax at least +10 %
    assert analyze.tax_label(-0.02, -0.08, 0.04) == "inconclusive"


def test_chain_is_one_bootstrap_cluster_and_reps_average_first():
    rows = []
    for t in ("c1", "c2", "c3"):
        rows += [row(t, "A", 100, True, "ca", family="W1", chain=True),
                 row(t, "B", 50, True, "cb", family="W1", chain=True)]
    rows += [row("x", "A", 100, True, "xa", rep=1), row("x", "A", 300, False, "xa", rep=2),
             row("x", "B", 100, True, "xb")]
    tasks = analyze.per_task(rows)
    assert tasks[("claude", "A", "x")] == {"tokens": 200, "success": 0.5, "reps": 2, "cluster": "x", "type": "B",
                                           "role": ""}
    assert {t["cluster"] for t in tasks.values()} == {"s/W1", "x"}
    res = analyze.compare(tasks, "claude", "B", "A")
    assert res["tasks"] == 4 and res["clusters"] == 2
    # B: 250 tokens / 4 successes; A: 500 / 3.5 -> R = 1 - 62.5/142.86
    assert abs(res["reduction"] - (1 - 62.5 / (500 / 3.5))) < 1e-9
