#!/usr/bin/env python3
"""Per-tool-call token accounting for Claude runs (from the raw stream-json kept in <run>/raw/).

Every tool result enters the context and is read again by every later model call, so a call's real cost is what
it added times the number of calls that came after it (as cache reads), plus the first write. For each model call:

  added   = context size of the next call - context size of this call   (the tool results + text this step added)
  carried = added x the number of later model calls                     (re-read on every later call)

  python3 bench/calls.py bench/.work/results/<run>/raw/<file>.jsonl [...]      one table per transcript
  python3 bench/calls.py --by-kind <files...>                                   totals per kind of call
"""
from __future__ import annotations

import json
import sys


def kind_of(name: str, command: str) -> str:
    if name.startswith("mcp__"):
        return "ai-orchestration"
    if name in ("Read", "Grep", "Glob") or (name == "Bash" and any(command.lstrip().startswith(c) for c in
                                                                   ("find", "grep", "cat", "ls", "sed", "head", "tail -n", "rg"))):
        return "explore"
    if name in ("Edit", "Write", "MultiEdit"):
        return "edit"
    if name == "Bash" and ("mvnw" in command or "mvn " in command):
        return "build/test"
    if name == "Bash" and any(k in command for k in ("docker", "5432", "surefire-reports", "lsof", "curl", "spring-boot:run")):
        return "env/manual check"
    return "other"


def calls(path: str) -> list[dict]:
    """One row per model call (assistant events sharing a message id), in order."""
    rows: dict[str, dict] = {}
    order: list[str] = []
    for line in open(path):
        event = json.loads(line)
        if event.get("type") != "assistant":
            continue
        message = event["message"]
        mid = message.get("id")
        if mid not in rows:
            usage = message.get("usage", {})
            rows[mid] = {"context": usage.get("input_tokens", 0) + usage.get("cache_read_input_tokens", 0)
                         + usage.get("cache_creation_input_tokens", 0), "output": usage.get("output_tokens", 0), "tools": []}
            order.append(mid)
        for block in message.get("content", []):
            if block.get("type") == "tool_use":
                args = block.get("input", {})
                command = str(args.get("command") or args.get("file_path") or args.get("pattern") or "")
                rows[mid]["tools"].append((block["name"], command))
    out = [rows[m] for m in order]
    for i, row in enumerate(out):
        nxt = out[i + 1]["context"] if i + 1 < len(out) else row["context"]
        row["added"] = max(0, nxt - row["context"])
        row["carried"] = row["added"] * (len(out) - i - 1)
        name, command = row["tools"][0] if row["tools"] else ("text", "")
        row["kind"] = kind_of(name, command) if row["tools"] else "answer"
        row["label"] = f"{name} {command}"[:90]
    return out


def main(argv: list[str]) -> None:
    by_kind = "--by-kind" in argv
    files = [a for a in argv if not a.startswith("--")]
    totals: dict[str, list[int]] = {}
    for path in files:
        rows = calls(path)
        base = rows[0]["context"] if rows else 0
        if not by_kind:
            print(f"\n{path}\n  start context {base/1000:.1f}k  calls {len(rows)}")
            print(f"  {'#':>3} {'ctx':>6} {'added':>6} {'carried':>8}  kind              call")
            for i, row in enumerate(rows):
                print(f"  {i:>3} {row['context']/1000:>5.1f}k {row['added']/1000:>5.1f}k {row['carried']/1000:>7.0f}k  "
                      f"{row['kind']:<17} {row['label']}")
        # what every call re-reads before anything happened: system prompt, tool schemas, instructions, hook text
        t = totals.setdefault("start context", [0, 0, 0])
        t[0] += len(rows)
        t[1] += base
        t[2] += base * len(rows)
        for row in rows:
            t = totals.setdefault(row["kind"], [0, 0, 0])
            t[0] += 1
            t[1] += row["added"]
            t[2] += row["carried"]
    if by_kind:
        runs = max(1, len(files))
        print(f"  per run, averaged over {runs} transcript(s)")
        totals = {k: [v[0] / runs, v[1] / runs, v[2] / runs] for k, v in totals.items()}
        grand = sum(v[2] for v in totals.values()) or 1
        print(f"  {'kind':<18} {'calls':>5} {'added':>8} {'carried':>9} {'share':>6}")
        for kind, (n, added, carried) in sorted(totals.items(), key=lambda kv: -kv[1][2]):
            print(f"  {kind:<18} {n:>5.0f} {added/1000:>7.1f}k {carried/1000:>8.0f}k {100*carried/grand:>5.0f}%")
        print(f"  {'total':<18} {'':>5} {'':>8} {grand/1000:>8.0f}k")


if __name__ == "__main__":
    main(sys.argv[1:])
