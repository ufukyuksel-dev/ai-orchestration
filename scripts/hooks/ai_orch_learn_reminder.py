#!/usr/bin/env python3
"""Claude Code `Stop` hook: the end-of-task learn gate (Copilot's single `memory.learn` at task end).

When the agent is about to finish a request in which it changed files and has not called `memory.learn`, the hook
asks it once to save what the task taught about the repository. Claude Code loads MCP tool schemas on demand, so a
text instruction alone is easily skipped at the end of a long task; this makes the step reliable.

Prints nothing (the session stops normally) for chat answers, when learn was already called, on the second stop
(`stop_hook_active`), and on any error; it never blocks for long and never loops.
"""
from __future__ import annotations

import json
import sys

EDIT_TOOLS = {"Edit", "Write", "MultiEdit", "NotebookEdit"}
REASON = (
    "AI Orchestration, before you finish: if this task taught stable, reusable knowledge about this repository that "
    "no memory card already says (how to build or test here, a pitfall that cost you turns, where to change "
    "something), call memory.learn once now as your AI Orchestration instructions describe. The tool is "
    "mcp__ai-orchestration__memory_learn; if its schema is not loaded, load it first with ToolSearch "
    "\"select:mcp__ai-orchestration__memory_learn\". If there is nothing new, just finish. Do not repeat your answer."
)


def since_last_prompt(transcript: str) -> list[dict]:
    """The transcript entries after the user's last own prompt (tool results are not prompts)."""
    entries: list[dict] = []
    for line in open(transcript, encoding="utf-8"):
        try:
            entry = json.loads(line)
        except ValueError:
            continue
        message = entry.get("message") or {}
        content = message.get("content")
        if entry.get("type") == "user" and not entry.get("isMeta") and (
                isinstance(content, str) or any(b.get("type") == "text" for b in content or [])):
            entries = []
        else:
            entries.append(entry)
    return entries


def tools_used(entries: list[dict]) -> list[str]:
    return [block.get("name", "") for entry in entries if entry.get("type") == "assistant"
            for block in (entry.get("message") or {}).get("content") or [] if block.get("type") == "tool_use"]


def should_remind(event: dict) -> bool:
    if event.get("stop_hook_active") or not event.get("transcript_path"):
        return False
    tools = tools_used(since_last_prompt(event["transcript_path"]))
    return any(t in EDIT_TOOLS for t in tools) and not any(t.endswith("memory_learn") for t in tools)


def main() -> int:
    try:
        if should_remind(json.load(sys.stdin)):
            print(json.dumps({"decision": "block", "reason": REASON}))
    except (OSError, ValueError, AttributeError, TypeError):
        pass  # a broken or missing transcript must never keep the session from stopping
    return 0


if __name__ == "__main__":
    sys.exit(main())
