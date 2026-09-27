#!/usr/bin/env python3
"""Claude Code `UserPromptSubmit` hook: puts AI Orchestration's rules and the memory cards that match the prompt into
the agent's context, so the session needs no `session.bootstrap` tool call (no extra turn, no extra tool schema).

Prints nothing when there is nothing relevant, when everything was already shown in this session, or when the server
cannot be reached; it never blocks the prompt. Server: $AI_ORCH_URL (default http://127.0.0.1:18080).
"""
from __future__ import annotations

import hashlib
import json
import os
import sys
import tempfile
import urllib.request
from pathlib import Path

URL = os.environ.get("AI_ORCH_URL", "http://127.0.0.1:18080").rstrip("/") + "/mcp"
TIMEOUT = float(os.environ.get("AI_ORCH_HOOK_TIMEOUT", "4"))
HEADERS = {"Content-Type": "application/json", "Accept": "application/json, text/event-stream",
           "X-AI-Orch-Client": "claude-code"}


def _post(payload: dict, session: str | None) -> tuple[dict | None, str | None]:
    headers = dict(HEADERS, **({"Mcp-Session-Id": session} if session else {}))
    request = urllib.request.Request(URL, json.dumps(payload).encode(), headers, method="POST")
    with urllib.request.urlopen(request, timeout=TIMEOUT) as response:
        sid = response.headers.get("mcp-session-id") or session
        for line in response.read().decode("utf-8", "replace").splitlines():
            line = line[5:].strip() if line.startswith("data:") else line.strip()
            if line.startswith("{"):
                return json.loads(line), sid
    return None, sid


MAX_PAGES = 20


def bootstrap(root: str, task: str) -> dict | None:
    """Rules plus up to three cards: the task's matches first, then how to verify a change here (the server adds
    those, since the test command rarely resembles the task's wording). Large rule sets come in pages: all of them are
    read, and `complete` says whether the whole set arrived."""
    first = _bootstrap(root, task)
    if not first:
        return None
    data, page = dict(first), first
    rules = list(first.get("rules") or [])
    for _ in range(MAX_PAGES):
        if not page.get("nextCursor"):
            break
        page = _bootstrap(root, None, page["nextCursor"])
        if not page:
            break
        rules += page.get("rules") or []
        for key in ("rulesLoaded", "rulesError", "pathRules", "pathIndex", "nextCursor"):
            data[key] = page.get(key)
    data["rules"] = rules
    data["complete"] = not data.get("nextCursor") and (
        bool(data.get("rulesLoaded")) or bool(data.get("askUser")) or data.get("rulesError") == "RULES_SKIPPED_BY_USER")
    return data


def _bootstrap(root: str, task: str | None, cursor: str | None = None) -> dict | None:
    init, sid = _post({"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {
        "protocolVersion": "2025-06-18", "capabilities": {}, "clientInfo": {"name": "ai-orch-hook", "version": "1"}}}, None)
    if not init:
        return None
    _post({"jsonrpc": "2.0", "method": "notifications/initialized"}, sid)
    answer, _ = _post({"jsonrpc": "2.0", "id": 2, "method": "tools/call",
                       "params": {"name": "session.bootstrap", "arguments": {
                           "rootPath": root, **({"task": task} if task else {}), **({"cursor": cursor} if cursor else {})}}},
                      sid)
    result = (answer or {}).get("result") or {}
    if result.get("isError"):
        return None
    content = result.get("content") or []
    return json.loads(content[0]["text"]) if content and content[0].get("text") else None


def render(data: dict, seen: dict) -> list[str]:
    """What this session has not seen yet, compactly."""
    out: list[str] = []
    if not seen.get("rules"):
        if data.get("askUser"):
            out.append(f"Project rules are available but not loaded. Ask the user once: {data['askUser']} Then call "
                       f"extras(op=\"session.bootstrap\", args={{rootPath: <this repository>, loadRules: <true for "
                       f"yes/always, false for no/never>, remember: <true for always/never>}}) and follow the rules "
                       f"it returns (if it returns nextCursor, call it again with cursor).")
        for rule in data.get("rules") or []:
            out.append(f"- Rule ({rule.get('scope')}): {rule.get('statement')}")
        for rule in data.get("pathRules") or []:
            out.append(f"- Rule for {', '.join(rule.get('paths') or [])}: {rule.get('statement')}")
        if data.get("pathIndex"):
            out.append(f"- Paths with their own rules (read them with rules.instructions(projectKey=\"{data.get('projectKey')}\", "
                       f"scope=\"module\", modulePaths=[<path>]) before editing there): {', '.join(data['pathIndex'])}")
    for card in data.get("memory") or []:
        if card.get("id") in seen.get("cards", []):
            continue
        files = f" Files: {', '.join(card['files'])}." if card.get("files") else ""
        stale = " (may be outdated: the code changed since)" if card.get("stale") else ""
        out.append(f"- Memory card{stale}: {card.get('summary')}. {card.get('text')}{files}")
    return out


def main() -> int:
    try:
        event = json.load(sys.stdin)
    except ValueError:
        return 0
    prompt = str(event.get("prompt") or "").strip()
    root = str(event.get("cwd") or os.getcwd())
    if not prompt or not root.startswith("/"):
        return 0
    key = hashlib.sha256(str(event.get("session_id") or root).encode()).hexdigest()[:16]
    state = Path(tempfile.gettempdir()) / f"ai-orch-hook-{key}.json"
    try:
        seen = json.loads(state.read_text())
    except (OSError, ValueError):
        seen = {"rules": False, "cards": []}
    try:
        data = bootstrap(root, prompt[:500])
    except (OSError, ValueError, KeyError):
        return 0  # server down or not reachable: the prompt goes on without project memory
    if not data:
        return 0
    lines = render(data, seen)
    # only a complete delivery (or the question, asked once) counts; otherwise the next prompt tries again
    seen["rules"] = seen.get("rules") or bool(data.get("complete"))
    seen["cards"] = sorted(set(seen.get("cards", [])) | {c.get("id") for c in data.get("memory") or []})
    try:
        state.write_text(json.dumps(seen))
    except OSError:
        pass
    if lines:
        print("AI Orchestration (project memory and rules for this repository):")
        print("\n".join(lines))
    return 0


if __name__ == "__main__":
    sys.exit(main())
