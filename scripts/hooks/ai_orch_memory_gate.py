#!/usr/bin/env python3
"""Memory searches within one user request: the agent may search memory whenever it needs to, but once a search came
back with nothing, memory is not searched again in that request, and the same query is never asked twice.

One record per session, reset by the prompt hook at every new request (`reset`), shared by every route:
- `mcp__ai-orchestration__memory_search` and `extras(op="memory.search")`: this script on PreToolUse / PostToolUse
  (`ai_orch_memory_gate.py pre|post`);
- `ai_orch memory search` in a shell: the existing Bash hooks call `pre` / `post` (no second Bash hook);
- the automatic step lookups (ai_orch_step_memory): `refusal` before and `close` / `record` after each lookup.

Only an answer the server gave successfully with zero items closes the request ("empty"); a timeout, an error or an
unreadable answer ("failed") closes nothing and does not count as asked. Prompt-time cards do not count either.
`memory.get` (opening a pointer that was delivered) is never gated. Prints nothing on any error (fail-open).
Usage: ai_orch_memory_gate.py pre|post   (hook; the event on stdin)
"""
from __future__ import annotations

import json
import os
import re
import shlex
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import ai_orch_session_state as session  # noqa: E402

EMPTY = ("AI Orchestration: memory already had nothing for this task (query: '{query}'). This is final for this task: "
         "do not search memory again through any tool or command; continue from the code.")
REPEAT = ("AI Orchestration: you already searched memory for '{query}' in this task; use that answer and continue "
          "from the code. Do not search memory again with the same query through any tool or command.")
CLI = re.compile(r"(?:^|[\s;&|(/])(?:ai_orch|ai-orch-memory)\s+memory\s+search\b")  # cheap pre-check only
LAUNCHERS = ("ai_orch", "ai-orch-memory")
ASSIGNMENT = re.compile(r"[A-Za-z_]\w*=.*")


def normalize(query: str) -> str:
    return re.sub(r"\s+", " ", str(query or "")).strip().lower()


def _args(value) -> dict:
    if isinstance(value, str):
        try:
            value = json.loads(value)
        except ValueError:
            return {}
    return value if isinstance(value, dict) else {}


def cli_query(command: str) -> str | None:
    """The --query of an `ai_orch memory search` command, or None when the command does not run one. Only a parsed
    command position counts (after `;`, `&&`, `||`, `|` and leading VAR=value assignments): text that merely mentions
    the command (`echo ...`, comments, heredocs) is not a search; a form this parser does not know fails open."""
    if not CLI.search(command or "") or "<<" in command:
        return None
    segments = []
    for part in _simple_commands(command):
        try:
            segments.append(shlex.split(part))
        except ValueError:
            return None
    for words in segments:
        i = 0
        while i < len(words) and ASSIGNMENT.fullmatch(words[i]):
            i += 1
        if i < len(words) and os.path.basename(words[i]) in LAUNCHERS and words[i + 1:i + 3] == ["memory", "search"]:
            rest = words[i + 3:]
            for j, word in enumerate(rest):
                if word == "--query" and j + 1 < len(rest):
                    return rest[j + 1]
                if word.startswith("--query="):
                    return word.split("=", 1)[1]
            return ""
    return None


def _simple_commands(command: str) -> list[str]:
    """The command split at unquoted `;`, `&`, `|`, `(`, `)` and newlines, up to an unquoted `#` comment: a quoted
    separator is text, not a separator."""
    parts, current, quote, i = [], [], None, 0
    while i < len(command):
        ch = command[i]
        if quote:
            current.append(ch)
            if ch == "\\" and quote == '"' and i + 1 < len(command):
                current.append(command[i + 1])
                i += 1
            elif ch == quote:
                quote = None
        elif ch == "\\" and i + 1 < len(command):
            current += [ch, command[i + 1]]
            i += 1
        elif ch in "'\"":
            quote = ch
            current.append(ch)
        elif ch == "#" and (not current or current[-1].isspace()):
            break
        elif ch in ";&|()\n":
            parts.append("".join(current))
            current = []
        else:
            current.append(ch)
        i += 1
    parts.append("".join(current))
    return [p for p in parts if p.strip()]


def search_query(event: dict) -> str | None:
    """The query of a memory search in this tool call, or None when the call is not a memory search."""
    tool = str(event.get("tool_name") or "")
    tool_input = event.get("tool_input") or {}
    if tool.endswith("__memory_search"):
        return str(tool_input.get("query") or "")
    if tool.endswith("__extras") and tool_input.get("op") == "memory.search":
        return str(_args(tool_input.get("args")).get("query") or "")
    if tool == "Bash":
        return cli_query(str(tool_input.get("command") or ""))
    return None


def _json(text: str):
    text = (text or "").strip()
    start = text.find("{")
    if start < 0:
        return None
    try:
        return json.loads(text[start:])
    except ValueError:
        return None


def _items(value) -> list | None:
    """The result list of a search answer (agent view `knowledge`, legacy `items`), looked up through envelopes."""
    if not isinstance(value, dict):
        return None
    for key in ("knowledge", "items"):
        if isinstance(value.get(key), list):
            return value[key]
    for key in ("result", "data", "structuredContent"):
        found = _items(value.get(key))
        if found is not None:
            return found
    return None


def outcome(event: dict) -> str:
    """found | empty | failed for the tool response of a memory search."""
    response = event.get("tool_response")
    if isinstance(response, dict) and str(event.get("tool_name")) == "Bash":
        if response.get("is_error") or response.get("interrupted"):
            return "failed"
        response = _json(str(response.get("stdout") or ""))
    elif isinstance(response, dict) and isinstance(response.get("content"), list):
        if response.get("isError"):
            return "failed"
        response = response.get("structuredContent") or response["content"]
    if isinstance(response, list):  # MCP content blocks: [{"type": "text", "text": "<json>"}]
        response = _json("".join(str(b.get("text") or "") for b in response if isinstance(b, dict)))
    elif isinstance(response, str):
        response = _json(response)
    if isinstance(response, dict) and (response.get("isError") or response.get("success") is False):
        return "failed"
    items = _items(response)
    if items is None:
        return "failed"
    return "found" if items else "empty"


def record_of(state: dict) -> dict:
    return state.setdefault("memory_gate", {"empty": None, "queries": []})


def reset(state: dict) -> None:
    """A new user request: memory may be searched again."""
    state["memory_gate"] = {"empty": None, "queries": []}


def refusal(state: dict, query: str) -> str | None:
    record = record_of(state)
    if record.get("empty"):
        return EMPTY.format(query=record["empty"][:200])
    if normalize(query) and normalize(query) in record.get("queries", []):
        return REPEAT.format(query=query[:200])
    return None


def record(state: dict, query: str, result: str) -> None:
    if result == "failed":
        return
    gate = record_of(state)
    if normalize(query) and normalize(query) not in gate["queries"]:
        gate["queries"].append(normalize(query))
    if result == "empty" and not gate.get("empty"):
        gate["empty"] = query or "(no query)"


def closed(state: dict) -> bool:
    return bool(record_of(state).get("empty"))


def close(state: dict, label: str) -> None:
    """An automatic lookup had nothing: memory is closed for the rest of the request."""
    gate = record_of(state)
    if not gate.get("empty"):
        gate["empty"] = label


def _state_file(event: dict) -> Path | None:
    cwd = str(event.get("cwd") or "")
    if not cwd.startswith("/"):
        return None
    return session.state_path(str(event.get("session_id") or ""), session.repo_root(cwd))


def pre(event: dict) -> str | None:
    """The refusal for this tool call, or None to let it run."""
    query = search_query(event)
    path = _state_file(event) if query is not None else None
    if path is None:
        return None
    return refusal(session.load(path), query)


def post(event: dict) -> None:
    query = search_query(event)
    path = _state_file(event) if query is not None else None
    if path is None:
        return
    state = session.load(path)
    record(state, query, outcome(event))
    session.save(path, state)


def deny(reason: str) -> dict:
    return {"hookSpecificOutput": {"hookEventName": "PreToolUse", "permissionDecision": "deny",
                                   "permissionDecisionReason": reason}}


def main(argv: list[str]) -> int:
    try:
        event = json.load(sys.stdin)
        if argv[:1] == ["pre"]:
            reason = pre(event)
            if reason:
                print(json.dumps(deny(reason)))
        elif argv[:1] == ["post"]:
            post(event)
    except (OSError, ValueError, KeyError, TypeError, AttributeError):
        pass
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
