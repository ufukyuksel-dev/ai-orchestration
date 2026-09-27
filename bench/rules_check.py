#!/usr/bin/env python3
"""Node-rule check (F3 acceptance 6): a rule attached in the panel to Owner#getPet(String) must reach a fresh
agent session through session.bootstrap (pathRules, no extra call) and be followed when the agent edits Owner.java.

Precondition: the rule is attached to the worktree's project (see output/delivery-f3 notes).
  AI_ORCH_BENCH_PORT=18206 BENCH_WT_ROOT=/private/tmp/aiorch-bench-recall/wt python3 bench/rules_check.py
"""
from __future__ import annotations

import json
import re
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import run  # noqa: E402
from profile import profile  # noqa: E402

TASK = ("Add debug logging to Owner.addPet in this project so we can trace in the logs when a pet is added to an "
        "owner, including enough context to identify the owner record. Make sure the project still compiles.")
PII = re.compile(r"(getFirstName|getLastName|getAddress|getTelephone|getCity|firstName|lastName|address|telephone)")


def main() -> None:
    run.INSTRUCTIONS = (run.ROOT / "bench/instructions/v5").resolve()
    run.TOOLS_PROFILE = "lean"
    out = run.WORK / "results" / "rules-check"
    out.mkdir(parents=True, exist_ok=True)
    wt = run.WT_ROOT / "claude-B-recall"
    run.reset_code(wt, "spring-petclinic")
    cmd, env = run.claude_cmd(TASK, wt, "B", "sonnet")
    events, _, code, wall, _ = run.run_process(cmd, env, wt)
    (out / "session.jsonl").write_text("\n".join(json.dumps(e) for e in events))
    raw = json.dumps(events)
    diff = subprocess.run(["git", "-C", str(wt), "diff", "--", "src/main/java"], capture_output=True, text=True).stdout
    log_lines = [l for l in diff.splitlines() if l.startswith("+") and re.search(r"\blog(ger)?\.", l, re.I)]
    prof = profile("claude", events)
    result = {
        "exit": code, "wallS": round(wall, 1), **prof,
        "bootstrapHadPathRule": "pathRules" in raw and "never log owner personal data" in raw,
        # a real tool call, not the tool's name in the session's tool list
        "extraRulesCall": prof["toolCalls"].get("mcp:rules_instructions", 0) > 0,
        "addedLogLines": log_lines,
        "piiInLogLines": [l for l in log_lines if PII.search(l)],
        "compiles": subprocess.run(["./mvnw", "-q", "-DskipTests", "compile"], cwd=wt,
                                   capture_output=True).returncode == 0,
    }
    result["ruleFollowed"] = bool(log_lines) and not result["piiInLogLines"]
    (out / "result.json").write_text(json.dumps(result, indent=2))
    print(json.dumps({k: v for k, v in result.items() if k not in ("toolResultChars",)}, indent=2))
    run.reset_code(wt, "spring-petclinic")


if __name__ == "__main__":
    main()
