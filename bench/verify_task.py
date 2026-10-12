#!/usr/bin/env python3
"""Check a suite's tasks before freeze: on a scratch worktree at the task's baseline,
  code / tests tasks:  the untouched baseline FAILS run.score(), the reference patch (reference/<task>.patch) PASSES;
  investigation tasks: the reference answer (reference/<task>.answer.md) PASSES, an empty answer FAILS.
No agent runs. Writes .work/verify/<suite>.json with the per-task outcome and the check wall times.

  python3 bench/verify_task.py --suite <suite> [--tasks id,id]
"""
from __future__ import annotations

import argparse
import json
import subprocess
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import run  # noqa: E402


def configure(suite_name: str) -> dict:
    suite_file = run.BENCH / "tasks" / f"{suite_name}.json"
    if not suite_file.exists():
        suite_file = run.PRIVATE / "tasks" / f"{suite_name}.json"
    suite = json.loads(suite_file.read_text())
    if suite.get("sha"):
        run.SUITE_SHA[suite["repo"]] = suite["sha"]
    run.SUITE_PATH.extend(suite.get("pathPrepend") or [])
    run.SUITE_ENV.update({k: run.resolve_env(v) for k, v in (suite.get("env") or {}).items()})
    run.SUITE_FILES.update(suite.get("worktreeFiles") or {})
    run.SUITE_BASE_PATCH = suite.get("basePatch", "")
    if suite.get("private"):
        run.ASSETS = run.PRIVATE
    return suite


def verify(task: dict, suite: dict, wt: Path) -> dict:
    kind = task.get("kind", "code")
    out: dict = {"task": task["id"], "kind": kind}
    t0 = time.monotonic()
    baseline = run.reset_code(wt, suite["repo"], task)
    if kind == "investigation":
        ref = (run.ASSETS / "reference" / f"{task['id']}.answer.md").read_text()
        good = run.score(task, suite, wt, baseline, ref)
        empty = run.score(task, suite, wt, baseline, "I could not find it.")
        out |= {"referencePasses": good["success"], "emptyFails": not empty["success"], "detail": good}
        out["ok"] = out["referencePasses"] and out["emptyFails"]
    else:
        before = run.score(task, suite, wt, baseline, "")
        out["baselineFails"] = not before["success"]
        out["baselineDetail"] = {k: v for k, v in before.items() if k.endswith("Ok") or k in ("mutantsKilled",)}
        t1 = time.monotonic()
        run.reset_code(wt, suite["repo"], task)
        patch = run.ASSETS / "reference" / f"{task['id']}.patch"
        subprocess.run(["git", "-C", str(wt), "apply", str(patch)], check=True)
        after = run.score(task, suite, wt, baseline, "")
        out["referencePasses"] = bool(after["success"])
        out["referenceDetail"] = after
        out["checkSeconds"] = round(time.monotonic() - t1, 1)
        out["ok"] = out["baselineFails"] and out["referencePasses"]
        if task.get("probe"):  # the reference must satisfy the (report-only) convention probe
            out["referenceProbeOk"] = after.get("probeOk")
            out["ok"] = out["ok"] and bool(after.get("probeOk"))
    out["wallSeconds"] = round(time.monotonic() - t0, 1)
    return out


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--suite", required=True)
    ap.add_argument("--tasks", default="")
    args = ap.parse_args()
    suite = configure(args.suite)
    wanted = set(filter(None, args.tasks.split(",")))
    wt = run.WT_ROOT / f"verify-{args.suite}"
    run.fresh_worktree(suite["repo"], wt)
    results = []
    try:
        for family in suite["families"]:
            for task in family["tasks"]:
                if wanted and task["id"] not in wanted:
                    continue
                try:
                    res = verify(task, suite, wt)
                except Exception as exc:  # noqa: BLE001 - report and continue with the next task
                    res = {"task": task["id"], "ok": False, "error": repr(exc)}
                results.append(res)
                print(f"{task['id']}: {'OK' if res['ok'] else 'FAIL'} "
                      f"{ {k: v for k, v in res.items() if k in ('baselineFails', 'referencePasses', 'emptyFails', 'checkSeconds', 'error')} }",
                      flush=True)
    finally:
        subprocess.run(["git", "-C", str(run.WORK / "repos" / suite["repo"]), "worktree", "remove", "--force", str(wt)],
                       capture_output=True)
    # Private suites: the outcome holds private test output, so it stays with the private assets.
    dest = (run.PRIVATE if suite.get("private") else run.WORK) / "verify" / f"{args.suite}.json"
    dest.parent.mkdir(parents=True, exist_ok=True)
    dest.write_text(json.dumps(results, indent=2))
    sys.exit(0 if results and all(r["ok"] for r in results) else 1)


if __name__ == "__main__":
    main()
