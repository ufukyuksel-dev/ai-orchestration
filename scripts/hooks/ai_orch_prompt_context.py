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
import re
import subprocess
import sys
import tempfile
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import ai_orch_profile  # noqa: E402  (same directory)
import ai_orch_memory_gate as memory_gate  # noqa: E402
import ai_orch_session_state as session  # noqa: E402
import ai_orch_trail_retrieve as trail_retrieve  # noqa: E402

URL = os.environ.get("AI_ORCH_URL", "http://127.0.0.1:18080").rstrip("/") + "/mcp"
TIMEOUT = float(os.environ.get("AI_ORCH_HOOK_TIMEOUT", "4"))
HEADERS = {"Content-Type": "application/json", "Accept": "application/json, text/event-stream",
           "X-AI-Orch-Client": os.environ.get("AI_ORCH_CLIENT", "claude-code")}


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
# Optional parts, all on by default; AI_ORCH_LEVERS="profile,codemap" keeps only those (benchmark ablation).
# Experimental parts are never implied by "all": "trailctx" (earlier work trails shown at the prompt) must be named.
LEVERS = {x.strip() for x in os.environ.get("AI_ORCH_LEVERS", "all").split(",")}


def lever(name: str) -> bool:
    return "all" in LEVERS or name in LEVERS


CODEMAP_HITS = 12
CODEMAP_FILES = 6
CODEMAP_MIN_SCORE = 0.35


def bootstrap(root: str, task: str) -> dict | None:
    """Rules plus up to three cards: task matches first, then procedure/test-address verification cards (test commands
    rarely resemble task wording). Large rule sets come in pages: all of them are
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


def _call(name: str, arguments: dict) -> dict | None:
    init, sid = _post({"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {
        "protocolVersion": "2025-06-18", "capabilities": {}, "clientInfo": {"name": "ai-orch-hook", "version": "1"}}}, None)
    if not init:
        return None
    _post({"jsonrpc": "2.0", "method": "notifications/initialized"}, sid)
    answer, _ = _post({"jsonrpc": "2.0", "id": 2, "method": "tools/call",
                       "params": {"name": name, "arguments": arguments}}, sid)
    result = (answer or {}).get("result") or {}
    content = result.get("content") or []
    if result.get("isError") or not content or not content[0].get("text"):
        return None
    return json.loads(content[0]["text"])


def code_map(project_key: str, task: str) -> list[str]:
    """Where the request probably lands: the files the code index ranks highest for the prompt, with the symbols and
    line ranges that matched, so the agent can open the right file first instead of searching for it."""
    found = _call("codebase.baseline.search", {"query": task, "projectKey": project_key, "topK": CODEMAP_HITS}) or {}
    files: dict[str, list[str]] = {}
    for hit in found.get("hits") or []:
        path = hit.get("filePath")
        if not path or (hit.get("score") or 0) < CODEMAP_MIN_SCORE:
            continue
        if path not in files and len(files) >= CODEMAP_FILES:
            continue
        name = (hit.get("fqn") or "").rsplit(".", 1)[-1]
        detail = f"{name} {hit.get('lineStart')}-{hit.get('lineEnd')}"
        if hit.get("capsuleKind") == "endpoint_flow" and hit.get("summary"):
            detail += f" ({hit['summary']})"
        files.setdefault(path, [])
        if detail not in files[path]:
            files[path].append(detail)
    if not files:
        return []
    return ["Code the request most likely touches (code index, best match first; may be incomplete):"] + [
        f"- {path}: {'; '.join(details[:4])}" for path, details in files.items()]


TEST_FILE = re.compile(r"(^|/)(tests?|__tests__|spec)/|_test\.go$|(^|/)test_[^/]*\.py$|(^|/)tests?\.py$|"
                       r"Tests?\.(java|kt|swift|cs)$|\.(test|spec)\.[jt]sx?$")
SOURCE = re.compile(r"\.(py|go|ts|tsx|js|jsx|rb|java|kt|swift|cs|php|rs)$")
DEFINITION = re.compile(r"^\s*(def |async def |class |func |type |export |public |@\w*(route|get|post|Get|Post|Mapping))")
STOP = {"must", "with", "that", "this", "return", "returns", "status", "string", "number", "field", "fields", "each",
        "when", "line", "first", "make", "sure", "still", "pass", "tests", "project", "build", "existing", "json",
        "object", "array", "content", "type", "format", "exactly", "only", "from", "have", "their", "they", "unknown",
        "order", "ordered", "header", "value", "values", "every", "other", "after", "before", "plain", "text"}


def local_code_map(root: str, task: str) -> list[str]:
    """When the code index has nothing (a language the scanner does not model), rank the tracked source files by the
    request's words: in the path, in definitions (def/class/func/routes) and in the body."""
    words = {w.rstrip("s") for w in re.findall(r"[a-z][a-z_]{3,}", task.lower()) if w not in STOP}
    if not words:
        return []
    try:
        listed = subprocess.run(["git", "-C", root, "ls-files"], capture_output=True, text=True, timeout=10).stdout
    except (OSError, subprocess.SubprocessError):
        return []
    ranked = []
    sources = [f for f in listed.splitlines() if SOURCE.search(f)][:3000]
    for rel in [f for f in sources if not TEST_FILE.search(f)] or sources:  # tests are large and rarely the target
        try:
            text = (Path(root) / rel).read_text(encoding="utf-8", errors="replace")[:200_000]
        except OSError:
            continue
        lower, score, hits = text.lower(), 0.0, []
        score += 3 * sum(w in rel.lower() for w in words)
        for number, line in enumerate(text.splitlines(), 1):
            if DEFINITION.match(line) and any(w in line.lower() for w in words):
                score += 2
                if len(hits) < 3:
                    hits.append(f"{line.strip()[:70]} :{number}")
        score += min(5, sum(lower.count(w) for w in words) / 10)
        if score >= 6:
            ranked.append((score, rel, hits))
    ranked.sort(key=lambda r: -r[0])
    if not ranked:
        return []
    return ["Files the request most likely touches (matched by its words; may be incomplete):"] + [
        f"- {rel}" + (f": {'; '.join(hits)}" if hits else "") for _, rel, hits in ranked[:CODEMAP_FILES]]


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
        if card.get("id") in seen.get("cards", []):  # shown in full already
            continue
        if card.get("pointer"):  # a weaker match: one line, opened only if it fits the task
            if card.get("id") in seen.get("pointers", []):
                continue
            out.append(f"- Possibly relevant memory (not loaded): {card.get('summary')}. If it fits the task, read "
                       f"it with extras(op=\"memory.get\", args={{memoryId: \"{card.get('id')}\"}}).")
            continue
        files = f" Files: {', '.join(card['files'])}." if card.get("files") else ""
        missing = f": {', '.join(card['missingRefs'])}" if card.get("missingRefs") else ""
        stale = (f" (code it points to is missing{missing}; fix its locators with ai_orch memory update or archive it)"
                 if card.get("stale") else "")
        code = f"\n{card['code']}" if card.get("code") else ""  # the current lines it points at (server-bounded)
        out.append(f"- Memory card{stale}: {card.get('summary')}. {card.get('text')}{files}{code}")
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
    root = session.repo_root(root)
    session_file = session.state_path(str(event.get("session_id") or ""), root)
    started = session.load(session_file)
    if "start_fp" not in started:  # the tree as the session found it: the Stop hook checks only what changed since
        started["start_fp"] = session.fingerprint(root)
        session.save(session_file, started)
    session.forget_after_compaction(started, event.get("transcript_path"))  # shown source is gone from context
    memory_gate.reset(started)  # a new request: memory may be searched again
    session.save(session_file, started)
    trail_text = ""
    if lever("trails"):  # each prompt is a request: the tree it starts from attributes its edits to its trail
        session.begin_request(started, root)
        if "trailctx" in LEVERS:  # experimental, opt-in only (not part of "all"): it has not passed its replay gates
            try:  # earlier work on the code this prompt is about (fail-open, ~0.6 s cap)
                trail_text = trail_retrieve.context(root, prompt, started, str(event.get("session_id") or ""),
                                                    HEADERS["X-AI-Orch-Client"])
            except (OSError, ValueError, KeyError, TypeError, AttributeError):
                trail_text = ""
        session.save(session_file, started)
    key = hashlib.sha256(str(event.get("session_id") or root).encode()).hexdigest()[:16]
    state = Path(tempfile.gettempdir()) / f"ai-orch-hook-{key}.json"
    try:
        seen = json.loads(state.read_text())
    except (OSError, ValueError):
        seen = {"rules": False, "cards": []}
    try:
        data = bootstrap(root, prompt[:500]) or {}
    except (OSError, ValueError, KeyError):
        data = {}  # server down or not reachable: no rules or cards, the local profile and file map still help
    lines = render(data, seen) if data else []
    if not seen.get("profile") and lever("profile"):
        lines += ai_orch_profile.render(ai_orch_profile.detect(root))
        seen["profile"] = True
    if lever("codemap"):
        found = []
        try:
            found = code_map(data["projectKey"], prompt[:500]) if data.get("projectKey") else []
        except (OSError, ValueError, KeyError):
            pass
        if not found and not seen.get("localmap"):  # the word-matched map is rougher: first request only
            found = local_code_map(root, prompt[:500])
            seen["localmap"] = True
        lines += found
    # only a complete delivery (or the question, asked once) counts; otherwise the next prompt tries again
    seen["rules"] = seen.get("rules") or bool(data.get("complete"))
    # a pointer is not the card: a later prompt may still need the full text, so the two are remembered apart
    cards = data.get("memory") or []
    seen["cards"] = sorted(set(seen.get("cards", [])) | {c.get("id") for c in cards if not c.get("pointer")})
    seen["pointers"] = sorted(set(seen.get("pointers", [])) | {c.get("id") for c in cards if c.get("pointer")})
    try:
        state.write_text(json.dumps(seen))
    except OSError:
        pass
    if lines:
        print("AI Orchestration (project memory and rules for this repository):")
        print("\n".join(lines))
    if trail_text:
        print(trail_text)
    return 0


if __name__ == "__main__":
    sys.exit(main())
