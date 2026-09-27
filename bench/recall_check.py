#!/usr/bin/env python3
"""Two-session recall check (no code change): session 1 learns how to run the tests and is asked to remember it;
session 2, a fresh process, must get it back from session.bootstrap cards.

  AI_ORCH_BENCH_PORT=18204 python3 bench/recall_check.py [--instructions bench/instructions/v4]
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import run  # noqa: E402
from profile import profile  # noqa: E402

LEARN = ("Find the exact command that runs this project's tests without the Docker-based MySQL/Postgres "
         "integration tests, run it once to confirm it works, and remember it for later sessions.")
RECALL = "Run this project's tests (skip the Docker-based database integration tests) and tell me the result."


def session(prompt: str, wt: Path, name: str, out: Path) -> dict:
    cmd, env = run.claude_cmd(prompt, wt, "B", "sonnet")
    events, _, code, wall, _ = run.run_process(cmd, env, wt)
    (out / f"{name}.jsonl").write_text("\n".join(json.dumps(e) for e in events))
    boot = next((e for e in events if e.get("type") == "user" and "session_bootstrap" in json.dumps(e)), None)
    return {"session": name, "exit": code, "wallS": round(wall, 1), **profile("claude", events),
            "bootstrapCards": ('"memory"' in json.dumps(boot)) if boot else None}


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--instructions", default="bench/instructions/v4")
    args = ap.parse_args()
    run.INSTRUCTIONS = (run.ROOT / args.instructions).resolve()
    run.TOOLS_PROFILE = "lean"
    out = run.WORK / "results" / "recall-check"
    out.mkdir(parents=True, exist_ok=True)
    wt = run.WT_ROOT / "claude-B-recall"
    run.fresh_worktree("spring-petclinic", wt)
    key = run.project_key_for(wt)
    run.reset_memory(key)
    rows = [session(LEARN, wt, "s1-learn", out)]
    rows.append({"memoriesAfterS1": run.memory_count(key),
                 "cards": run.psql(f"SELECT summary FROM memory_items WHERE project_key='{key}'")})
    run.reset_code(wt, "spring-petclinic")
    rows.append(session(RECALL, wt, "s2-recall", out))
    (out / "results.json").write_text(json.dumps(rows, indent=2))
    print(json.dumps(rows, indent=2))


if __name__ == "__main__":
    main()
