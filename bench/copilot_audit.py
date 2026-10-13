#!/usr/bin/env python3
"""Per-task behaviour of a Copilot bench run: how the agent got to the work (searches and reads before its first edit
or final answer), whether it built or ran tests anyway, what memory handed it (hook context in the prompt), what it
asked to learn (the hidden ai-orch-learn comment) and which cards the chain held afterwards.

  python3 bench/copilot_audit.py <run-id> [--cards]

Reads bench/.work/results/<run-id>/{results.jsonl,raw,memory}. Prints one row per task, then the token totals per arm
for whole chains, warm positions (2-3) and control positions (role "control")."""
from __future__ import annotations

import argparse
import json
import re
import sys
from collections import defaultdict
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import run  # noqa: E402

SEARCH_TOOLS = {"grep", "glob", "grep_search", "file_search", "search_code_subagent", "rg"}
READ_TOOLS = {"view", "read_file"}
EDIT_TOOLS = {"edit", "create", "str_replace_editor", "apply_patch"}
SHELL_TOOLS = {"bash", "shell", "run_in_terminal"}
BUILD = re.compile(r"\b(gradlew|gradle|mvnw?|kotlinc|xcodebuild|pytest|go test|npm (run )?test)\b")
SHELL_SEARCH = re.compile(r"(^|[|;&\s])(grep|rg|find|ag|git grep)\s")
LEARN = re.compile(r"<!--\s*ai-orch-learn\b(.*?)-->", re.S)


def behaviour(events: list[dict]) -> dict:
    searches = reads = before_searches = before_reads = builds = memory_cmds = 0
    edited = False
    hook_context = ""
    for e in events:
        data = e.get("data") or {}
        if e.get("type") == "user.message" and not hook_context:
            transformed = str(data.get("transformedContent") or "")
            hook_context = transformed if "AI Orchestration" in transformed else ""
        if e.get("type") != "tool.execution_start":
            continue
        name = str(data.get("toolName") or "")
        args = json.dumps(data.get("arguments") or {})
        if name in EDIT_TOOLS:
            edited = True
        is_search = name in SEARCH_TOOLS or (name in SHELL_TOOLS and SHELL_SEARCH.search(args) is not None)
        is_read = name in READ_TOOLS or (name in SHELL_TOOLS and re.search(r"\b(cat|sed -n|head|tail)\b", args))
        if name in SHELL_TOOLS and BUILD.search(args):
            builds += 1
        if name in SHELL_TOOLS and "ai_orch" in args:
            memory_cmds += 1
        searches += is_search
        reads += bool(is_read)
        if not edited:
            before_searches += is_search
            before_reads += bool(is_read)
    final = run.final_message("copilot", events)
    learn = LEARN.findall(final)
    return {"searches": searches, "reads": reads, "searchesBeforeEdit": before_searches,
            "readsBeforeEdit": before_reads, "builds": builds, "aiOrchCmds": memory_cmds,
            "hookChars": len(hook_context), "cardsInPrompt": hook_context.count("\n- ") if hook_context else 0,
            "learnComment": bool(learn), "learnChars": sum(len(x) for x in learn)}


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("run_id")
    ap.add_argument("--cards", action="store_true", help="print the chain's saved cards after each position")
    args = ap.parse_args()
    out = run.WORK / "results" / args.run_id
    every = [json.loads(line) for line in (out / "results.jsonl").read_text().splitlines() if line.strip()]
    # only the attempt each completed cell names counts (bench/run.py); the rest is listed apart, never summed
    current = {json.loads(f.read_text())["cell"]: json.loads(f.read_text())["attempt"]
               for f in (out / "cells").glob("*.json")} if (out / "cells").exists() else {}
    rows = [r for r in every if current.get(r["cell"]) == r["attempt"]]
    other = [r for r in every if current.get(r["cell"]) != r["attempt"]]
    print(f"{'cell':22} {'task':5} {'pos':>3} {'ok':>3} {'tokens':>9} {'turns':>5} {'srch<ed':>7} {'read<ed':>7} "
          f"{'build':>5} {'hook':>6} {'learn':>5} {'mem':>4}")
    totals: dict = defaultdict(lambda: defaultdict(int))
    for row in rows:
        raw = out / "raw" / f"{row['cell']}-{row['task']}-{row['attempt']}.jsonl"
        events = [json.loads(line) for line in raw.read_text().splitlines() if line.strip()]
        b = behaviour(events)
        print(f"{row['cell']:22} {row['task']:5} {row['position']:>3} {'y' if row.get('success') else 'n':>3} "
              f"{row['totalTokens'] if row.get('totalTokens') is not None else 'MISSING':>9} {row.get('turns') or 0:>5} {b['searchesBeforeEdit']:>7} "
              f"{b['readsBeforeEdit']:>7} {b['builds']:>5} {b['hookChars']:>6} {'y' if b['learnComment'] else '-':>5} "
              f"{row.get('memoriesAfter') if row.get('memoriesAfter') is not None else '-':>4}")
        group = "control" if row.get("role") == "control" else ("first" if row["position"] == 1 else "warm")
        for g in ("all", group):
            if row.get("totalTokens") is None:
                totals[row["arm"]][g + "Missing"] += 1  # unmeasured: the comparison becomes inconclusive
            else:
                totals[row["arm"]][g + "Tokens"] += row["totalTokens"]
            totals[row["arm"]][g + "Ok"] += bool(row.get("success"))
            totals[row["arm"]][g + "N"] += 1
        if args.cards and row.get("memoriesAfter") is not None:
            snap = out / "memory" / row["cell"] / f"{row['attempt']}-p{row['position']}.json"
            if snap.exists():
                for card in json.loads(snap.read_text()):
                    print(f"      card: {json.dumps(card, ensure_ascii=False)[:600]}")
    print()
    for arm, t in sorted(totals.items()):
        print(arm, " ".join(f"{g}={t[g + 'Tokens']:,} ({t[g + 'Ok']}/{t[g + 'N']})"
                            + (f" INCONCLUSIVE: {t[g + 'Missing']} without tokens" if t[g + "Missing"] else "")
                            for g in ("all", "first", "warm", "control") if t[g + "N"]))
    if other:
        print(f"\nnot counted (incomplete or superseded attempts): "
              + ", ".join(f"{r['cell']}/{r['task']}@{r['attempt']}" for r in other))


if __name__ == "__main__":
    main()
