"""Memory for the step the agent is at (called from the PostToolUse search hook on Bash): the task's cards arrive at
the prompt; this adds the cards for checking and for failures, at the moment they are needed.

- first check: the first build/test command of a request for a kind of check (compile, tests, quality) → how to build
  and test here (`procedure`, `test_address` cards), asked with the command;
- failure: a check that fails with error lines not seen before in this request → what fixed it before
  (`debug_finding`, `procedure`), asked with the normalized error lines.

Delivered with the result of that command (PostToolUse), which the agent reads before its next step. Fires on these
transitions only: at most MAX_LOOKUPS lookups per request (timeouts count), none after memory answered "nothing",
never a query asked before in the request by any route
(an empty step lookup or an empty search of the agent closes memory for the request, see ai_orch_memory_gate), and
MAX_CHARS of cards per request; never repeats a card shown in full or a pointer shown before (a card first shown as a pointer may still come
in full; shared with the prompt hook's record); the whole step stays within DEADLINE seconds, fail-open. The result
rides on the tool output the agent reads anyway: no extra model call.
"""
from __future__ import annotations

import hashlib
import json
import os
import re
import tempfile
import time
import urllib.parse
import urllib.request
from pathlib import Path

import ai_orch_memory_gate as memory_gate
import ai_orch_session_state as session

MAX_LOOKUPS = 4
MAX_CHARS = 3000   # cards per request, all step lookups together
DEADLINE = 1.0     # seconds for the whole step, lookup included
CHECK_KINDS = "procedure,test_address"
FAILURE_KINDS = "debug_finding,procedure,test_address"  # "how to run the tests here" answers a failing test run
# the same question the task's cards use for verification knowledge (AgentSessionMcpTool.VERIFY_QUERY), plus the command
VERIFY_QUERY = "how to build, run the tests and verify a change in this repository, and which tests fail here"
# Read from the output, not only the exit code: a missing test tool (`python -m pytest` without pytest) prints its
# error and may still end with exit code 0 in an agent shell.
MISSING_TOOL = r"No module named \S+|ModuleNotFoundError|command not found|is not recognized as an internal or external command"
FAILED = re.compile(r"BUILD FAILURE|COMPILATION ERROR|Failed to execute goal|Tests run:.*(Failures|Errors): [1-9]|"
                    r"^FAIL\b|^--- FAIL|\b\d+ failed\b|npm ERR!|error:|Error:|\[ERROR\]|Traceback|" + MISSING_TOOL, re.M)
ERROR_LINE = re.compile(r"error|exception|fail|cannot find|does not exist|undefined|not found|" + MISSING_TOOL, re.I)


def _output(event: dict) -> tuple[str, bool]:
    response = event.get("tool_response")
    if isinstance(response, dict):
        text = "\n".join(str(response.get(k) or "") for k in ("stdout", "stderr", "output"))
        return text, bool(response.get("is_error") or response.get("interrupted"))
    return str(response or ""), False


def error_signature(output: str, root: str) -> tuple[str, str]:
    """(key, query text) of the first error lines, with paths, line numbers and ids stripped."""
    lines = []
    for line in output.splitlines():
        if ERROR_LINE.search(line) and len(lines) < 4:
            line = line.replace(root, "").strip()
            line = re.sub(r"(/[\w.-]+)+", " ", line)
            line = re.sub(r"[:\[(]\d+[,:\])]?\d*\]?|\b\d+\b", " ", line)
            lines.append(re.sub(r"\s+", " ", line)[:200])
    text = " | ".join(lines)
    return hashlib.sha1(text.encode()).hexdigest()[:12], text


def moment(command: str, output: str, is_error: bool, request: dict, root: str) -> tuple[str, str, str] | None:
    """(label, kinds, query) when this command is a transition worth a lookup, else None (and records the step)."""
    if not session.is_verify(command):
        return None
    if is_error or FAILED.search(output):
        key, text = error_signature(output, root)
        seen = request.setdefault("step_errors", [])
        if text and key not in seen:
            seen.append(key)
            return "a failing check", FAILURE_KINDS, text
        return None
    family = ",".join(sorted(session.coverage(command))) or "check"
    seen = request.setdefault("step_checks", [])
    if family not in seen:
        seen.append(family)
        return "checking the change", CHECK_KINDS, VERIFY_QUERY + ": " + command[:300]
    return None


def _seen_file(event: dict, root: str) -> Path:
    key = hashlib.sha256(str(event.get("session_id") or root).encode()).hexdigest()[:16]
    return Path(tempfile.gettempdir()) / f"ai-orch-hook-{key}.json"


def fetch(root: str, query: str, kinds: str, exclude: list[str], timeout: float = DEADLINE) -> tuple[str, list[dict]]:
    """(outcome, cards): `empty` only when the server says no memory of these kinds matches; `found` when it matched,
    even if every match was excluded as shown before; `failed` on a timeout, an error or an unreadable answer."""
    base = os.environ.get("AI_ORCH_URL", "http://127.0.0.1:18080").rstrip("/")
    url = base + "/terminal/api/step-cards?" + urllib.parse.urlencode(
        {"cwd": root, "query": query[:1500], "kinds": kinds, "exclude": ",".join(exclude[-150:])})
    request = urllib.request.Request(url, headers={"X-AI-Orch-Client": os.environ.get("AI_ORCH_CLIENT", "claude-code")})
    try:
        with urllib.request.urlopen(request, timeout=max(0.05, timeout)) as response:
            data = json.loads(response.read().decode("utf-8", "replace"))
    except (OSError, ValueError):
        return "failed", []
    if isinstance(data, dict) and data.get("outcome") in ("found", "empty") and isinstance(data.get("cards"), list):
        return data["outcome"], data["cards"]
    if isinstance(data, list) and data:  # an older server: cards, but an empty list does not say why
        return "found", data
    return "failed", []


def header(label: str) -> str:
    return f"AI Orchestration, memory for {label}:\n"


def pointer_line(card: dict) -> str:
    """How a pointer card is offered; a client without `extras` (Copilot) replaces this with its own command."""
    return (f"- Possibly relevant memory (not loaded): {card.get('summary')}. If it fits, read it with "
            f"extras(op=\"memory.get\", args={{memoryId: \"{card.get('id')}\"}}).")


def lines_of(cards: list[dict], budget: int) -> tuple[list[str], list[dict]]:
    """The card lines that fit `budget` characters (joined with newlines), in order, and the cards they show."""
    lines, shown, used = [], [], 0
    for card in cards:
        if card.get("pointer"):
            line = pointer_line(card)
        else:
            files = f" Files: {', '.join(card['files'])}." if card.get("files") else ""
            line = f"- Memory card: {card.get('summary')}. {card.get('text')}{files}"
            if card.get("code") and used + len(line) + len(card["code"]) + 2 <= budget:
                line += f"\n{card['code']}"  # its current lines, when they fit this step's budget
        cost = len(line) + (1 if lines else 0)
        if used + cost > budget:
            break
        lines.append(line)
        shown.append(card)
        used += cost
    return lines, shown


def block(cards: list[dict], label: str, budget: int) -> tuple[str, list[dict]]:
    """The whole block, header included, within `budget` characters, and the cards it shows."""
    lines, shown = lines_of(cards, budget - len(header(label)))
    return (header(label) + "\n".join(lines), shown) if lines else ("", [])


def render(cards: list[dict], label: str, budget: int = MAX_CHARS) -> str:
    return block(cards, label, budget)[0]


def context(event: dict) -> str:
    """The step's memory block for this PostToolUse event, or ""."""
    started = time.monotonic()
    if event.get("tool_name") != "Bash":
        return ""
    command = str((event.get("tool_input") or {}).get("command") or "")
    root = session.repo_root(str(event.get("cwd") or ""))
    if not root.startswith("/"):
        return ""
    state_file = session.state_path(str(event.get("session_id") or ""), root)
    state = session.load(state_file)
    request = state.setdefault("request", {})
    used = int(request.get("step_chars") or 0)
    if int(request.get("step_lookups") or 0) >= MAX_LOOKUPS or used >= MAX_CHARS or memory_gate.closed(state):
        return ""
    output, is_error = _output(event)
    found = moment(command, output, is_error, request, root)
    if not found:
        session.save(state_file, state)
        return ""
    label, kinds, query = found
    if memory_gate.refusal(state, query):  # memory had nothing in this request, or this exact query was asked
        session.save(state_file, state)
        return ""
    request["step_lookups"] = int(request.get("step_lookups") or 0) + 1
    seen_file = _seen_file(event, root)
    try:
        seen = json.loads(seen_file.read_text())
    except (OSError, ValueError):
        seen = {}
    # only cards shown in full are excluded: one shown as a pointer may now come in full
    result, cards = fetch(root, query, kinds, list(seen.get("cards", [])), DEADLINE - (time.monotonic() - started))
    if result == "empty":
        memory_gate.close(state, f"automatic lookup for {label}")
    memory_gate.record(state, query, result)  # the same query is not asked again by any route in this request
    cards = [c for c in cards if not (c.get("pointer") and c.get("id") in seen.get("pointers", []))]
    text, shown = block(cards, label, MAX_CHARS - used)
    late = time.monotonic() - started > DEADLINE
    if late:
        text, shown = "", []  # too late for this step: nothing is shown or recorded as shown
    request.setdefault("step_log", []).append({"moment": label, "outcome": result, "cards": [c.get("summary") for c in shown],
                                               "pointers": [bool(c.get("pointer")) for c in shown],
                                               **({"late": True} if late else {})})
    request["step_chars"] = used + len(text)
    session.save(state_file, state)
    if not text:
        return ""
    seen["cards"] = sorted(set(seen.get("cards", [])) | {c["id"] for c in shown if not c.get("pointer")})
    seen["pointers"] = sorted(set(seen.get("pointers", [])) | {c["id"] for c in shown if c.get("pointer")})
    try:
        seen_file.write_text(json.dumps(seen))
    except OSError:
        pass
    return text
