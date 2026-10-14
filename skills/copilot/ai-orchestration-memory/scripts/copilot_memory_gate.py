#!/usr/bin/env python3
"""Memory searches within one Copilot request: once `ai_orch memory search` found nothing, memory is not searched
again in that request, and the same query is never asked twice (answered locally, without a server call).

The record belongs to the Copilot session: Copilot gives its shell commands `COPILOT_AGENT_SESSION_ID`, and its
`userPromptSubmitted` hook (this script, run with the hook payload on stdin) starts a new request by clearing the
record of that `sessionId`. The same hook delivers the prompt's memory cards (`additionalContext`), and the `agentStop`
hook (`copilot_memory_gate.py stop`) saves what the final answer's hidden `ai-orch-learn` comment holds, with no extra
model call: the two moments Claude Code and Codex get from their prompt and Stop hooks. Without the session id (not run by Copilot) nothing is gated. Only an answer the server
gave successfully with zero items closes the request; a failed call closes nothing and does not count as asked.
"""
from __future__ import annotations

import hashlib
import json
import os
import re
import subprocess
import sys
import tempfile
import threading
import time
from pathlib import Path
from typing import Any

SESSION_ENV = "COPILOT_AGENT_SESSION_ID"
EMPTY = ("AI Orchestration: memory already had nothing for this task (query: '{query}'). This is final for this task: "
         "do not search memory again through any tool or command; continue from the code.")
NOTHING = "Memory has nothing on this. Do not search memory again in this task; continue from the code."
REPEAT = ("AI Orchestration: you already searched memory for '{query}' in this task; use that answer and continue "
          "from the code. Do not search memory again with the same query.")


def normalize(query: str) -> str:
    return re.sub(r"\s+", " ", str(query or "")).strip().lower()


def state_file(session_id: str) -> Path:
    key = hashlib.sha256(session_id.encode()).hexdigest()[:16]
    return Path(tempfile.gettempdir()) / f"ai-orch-copilot-{key}.json"


def _load(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
        return value if isinstance(value, dict) else {}
    except (OSError, ValueError):
        return {}


def _save(path: Path, value: dict[str, Any]) -> None:
    try:
        path.write_text(json.dumps(value), encoding="utf-8")
    except OSError:
        pass


def _session() -> str | None:
    value = os.environ.get(SESSION_ENV, "").strip()
    return value or None


def refusal(query: str) -> str | None:
    """Why this search must not run in this request, or None."""
    session_id = _session()
    if session_id is None:
        return None
    record = _load(state_file(session_id))
    if record.get("empty"):
        return EMPTY.format(query=str(record["empty"])[:200])
    if normalize(query) in record.get("queries", []):
        return REPEAT.format(query=query[:200])
    return None


def outcome(result: Any) -> str:
    """found | empty | failed for the decoded memory.search result."""
    if not isinstance(result, dict):
        return "failed"
    for key in ("knowledge", "items"):
        if isinstance(result.get(key), list):
            return "found" if result[key] else "empty"
    return "failed"


def record(query: str, result: Any) -> None:
    session_id = _session()
    found = outcome(result)
    if session_id is None or found == "failed":
        return
    path = state_file(session_id)
    state = _load(path)
    queries = state.setdefault("queries", [])
    if normalize(query) not in queries:
        queries.append(normalize(query))
    if found == "empty" and not state.get("empty"):
        state["empty"] = query
    if found == "found":  # knowledge the agent opened itself: delivered in this request, like a card
        state["full_delivered"] = True
        items = result.get("knowledge") if isinstance(result.get("knowledge"), list) else result.get("items") or []
        known = state.setdefault("delivered", [])
        for item in items:
            if not isinstance(item, dict):
                continue
            label = re.sub(r"\s+", " ", str(item.get("summary") or item.get("text") or "")).strip()[:120]
            if label and label not in known:
                known.append(label)
            if item.get("id"):
                state["shown"] = sorted(set(state.get("shown", [])) | {str(item["id"])})
    _save(path, state)


def reset(session_id: str, tree: str | None = None) -> None:
    """A new user request in this session: memory may be searched again; the tree it starts from is kept for the
    stop hook. Card ids shown earlier in the session stay recorded (in full and as pointers, apart), so a later
    prompt does not repeat them."""
    path = state_file(session_id)
    old = _load(path)
    _save(path, {"empty": None, "queries": [], "tree": tree, "learned": False, "asked": False,
                 "request": int(old.get("request") or 0) + 1, "receipt": None,
                 "shown": old.get("shown", []), "pointers": old.get("pointers", []), "card_files": [],
                 "profile_shown": old.get("profile_shown", False),
                 "check_failed": False, "fixed_after_failure": False,
                 "fixed_from_memory": False, "delivered": [], "memory_fixes": [], "full_delivered": False})


def record_learned() -> None:
    """A completed `ai_orch memory learn` in this request: the stop hook does not ask again."""
    session_id = _session()
    if session_id is None:
        return
    path = state_file(session_id)
    state = _load(path)
    state["learned"] = True
    _save(path, state)


# ---------------------------------------------------------------- hooks: the prompt (cards) and the stop (learning)
CARD_CHARS = 3000      # the card block per prompt, header included
CODE_CHARS = 2600      # the cards' current code on top (the server bounds it to 2,500)
DEADLINE = 2.0         # seconds for the prompt hook's git and server work together; later answers are dropped
HEADER = "AI Orchestration, memory for this request:\n"
LEARN_LINE = ("AI Orchestration learning: if this task taught you something reusable about this repository that no "
              "memory card above says (how to build or test here, a pitfall that cost you turns, where to change "
              "something), end your final answer with one HTML comment, which the terminal does not display: "
              '<!-- ai-orch-learn {"learningCandidates":[{"kind":"behavior|navigation|procedure|debug_finding|'
              'change_point|test_address|field_mapping","summary":"<=160 chars","content":"<=700 chars","locators":'
              '[{"kind":"file","ref":"<repo-relative path>","role":"primary_change_point|supporting|validation"}]}]} '
              "--> (1-3 candidates; a symbol locator is "
              '{"kind":"symbol","ref":"pkg.Class#method","path":"<repo-relative file>","role":"supporting"}). '
              "Only knowledge about this repository, not about this machine or sandbox. No tool call is needed for "
              "it; leave it out when nothing is new.")
LEARN_REASON = ("AI Orchestration, before you finish: this task changed code and saved nothing for the next one. If it "
                "taught something reusable about this repository, reply with only the ai-orch-learn HTML comment "
                "described in this request's AI Orchestration learning line; otherwise reply with the word none. Do "
                "not repeat your answer.")


def repo_root(cwd: str, timeout: float = 5) -> str | None:
    try:
        out = subprocess.run(["git", "-C", cwd, "rev-parse", "--show-toplevel"], capture_output=True, text=True,
                             timeout=max(0.05, timeout))
    except (OSError, subprocess.SubprocessError):
        return None
    if out.returncode != 0:
        return None
    return out.stdout.strip() or None


def tree_fingerprint(root: str, timeout: float = 10) -> str | None:  # timeout: for both git calls together
    """What `git status` lists (tracked changes and untracked files) with the diff and each untracked file's size and
    mtime: it changes when the task edits, creates or deletes a file."""
    end = time.monotonic() + timeout
    try:
        status = subprocess.run(["git", "-C", root, "status", "--porcelain", "-uall"], capture_output=True,
                                text=True, timeout=max(0.01, end - time.monotonic()))
        if end - time.monotonic() <= 0:
            return None
        diff = subprocess.run(["git", "-C", root, "diff", "HEAD"], capture_output=True,
                              timeout=max(0.01, end - time.monotonic()))
    except (OSError, subprocess.SubprocessError):
        return None
    if status.returncode != 0:
        return None
    digest = hashlib.sha256(status.stdout.encode() + diff.stdout)
    for line in status.stdout.splitlines():
        if line.startswith("??"):
            try:
                st = (Path(root) / line[3:]).stat()
                digest.update(f"{line[3:]}:{st.st_size}:{st.st_mtime_ns}".encode())
            except OSError:
                pass
    return digest.hexdigest()


def render(cards: list[dict[str, Any]], shown: set[str], pointers: set[str] = frozenset(),
           budget: int = CARD_CHARS) -> tuple[str, list[dict[str, Any]]]:
    """The card block (same lines as the Claude prompt hook) within `budget` characters, and the cards it shows. A
    card shown in full is never shown again; one shown as a pointer may still come in full, not as a pointer again."""
    lines, ids, used, code_used = [], [], len(HEADER), 0
    for card in cards:
        if not isinstance(card, dict) or card.get("id") in shown or (card.get("pointer") and card.get("id") in pointers):
            continue
        if card.get("pointer"):
            line = pointer_line(card)
        else:
            files = f" Files: {', '.join(map(str, card['files']))}." if card.get("files") else ""
            stale = " (may be outdated: the code changed since)" if card.get("stale") else ""
            line = f"- Memory card{stale}: {str(card.get('summary') or '').rstrip('.')}. {card.get('text') or ''}{files}"
        cost = len(line) + (1 if lines else 0)
        if used + cost > budget:
            break
        code = str(card.get("code") or "") if not card.get("pointer") else ""
        if code and code_used + len(code) + 1 <= CODE_CHARS:
            lines.append(f"{line}\n{code}")  # the current lines the card points at, when the code budget allows
            code_used += len(code) + 1
        else:
            lines.append(line)
        ids.append(card)
        used += cost
    return (HEADER + "\n".join(lines), ids) if lines else ("", [])


def fetch_bootstrap(root: str, prompt: str, timeout: float = DEADLINE) -> dict:
    """`session.bootstrap` for the prompt, through the pinned bridge: the memory cards only (Copilot never loads
    rules, so the server neither sends them nor asks about them); {} on any failure or when the whole exchange (initialize, call, close) does not finish
    within `timeout`: it runs in a daemon thread that is abandoned at the deadline (the hook process exits right after,
    so nothing outlives it)."""
    if timeout <= 0:
        return {}
    found: list[dict] = []
    worker = threading.Thread(target=lambda: found.append(_bootstrap(root, prompt, timeout)), daemon=True)
    worker.start()
    worker.join(timeout)
    return found[0] if found and not worker.is_alive() else {}


def fetch_cards(root: str, prompt: str, timeout: float = DEADLINE) -> list[dict[str, Any]]:
    cards = fetch_bootstrap(root, prompt, timeout).get("memory")
    return cards if isinstance(cards, list) else []


def _bootstrap(root: str, prompt: str, timeout: float) -> dict:
    import memory_bridge
    arguments = {"rootPath": root, "task": prompt[:2000], "loadRules": False}
    try:
        with memory_bridge.McpHttpClient(token=os.environ.get("AI_ORCH_MCP_TOKEN"), timeout=timeout) as client:
            client.initialize()
            response = client._rpc("tools/call", {"name": "session.bootstrap", "arguments": arguments})
            result = memory_bridge.decode_tool_result(response)
    except Exception:  # noqa: BLE001 - fail-open: the prompt goes on without cards
        return {}
    return result if isinstance(result, dict) else {}


FILES = re.compile(r" Files: (.+)\.$")


LOCATED = re.compile(r"^(\S+?):\d+-\d+(?:\s|$)")


def card_files(text: str) -> set[str]:
    """The repository files named by the card lines of a memory block. An entry may carry its place in the code
    (`app/models.py:231-250 app.models.User#to_dict (method)`) or a symbol (`app/models.py (pkg.f)`); only the file
    path counts, as git names it."""
    files = set()
    for line in text.splitlines():
        match = FILES.search(line)
        if not match:
            continue
        for part in match.group(1).split(", "):
            entry = part.split(" (")[0].strip()
            located = LOCATED.match(entry)
            entry = located.group(1) if located else entry.split(" ")[0]
            if entry:
                files.add(entry)
    return files


def shared_hooks():
    """The repository's shared hook modules (scripts/hooks: step moments, failure detection, the no-repeat gate), the
    same code Claude Code and Codex run; None when this install has no repository next to it (fail-open)."""
    hooks = Path(__file__).resolve().parents[4] / "scripts" / "hooks"
    if not (hooks / "ai_orch_step_memory.py").is_file():
        return None
    import memory_bridge
    endpoint = memory_bridge.DEFAULT_ENDPOINT  # the pinned local service; the step lookups use its host
    os.environ["AI_ORCH_URL"] = endpoint[: -len("/mcp")] if endpoint.endswith("/mcp") else endpoint
    os.environ.setdefault("AI_ORCH_CLIENT", "copilot")
    if str(hooks) not in sys.path:
        sys.path.insert(0, str(hooks))
    import ai_orch_step_memory
    return ai_orch_step_memory


QUESTION = re.compile(r"^\s*(what|why|how|where|when|which|who|whose|is|are|does|do|can|could|would|should|explain|"
                      r"describe|summari[sz]e|tell me|list|show me|ne|neden|nasıl|nerede|hangi|kim|açıkla|anlat)\b",
                      re.I)
# a request for a change, also when phrased as a question ("Can you fix …?", "… ekler misin?")
CHANGE = re.compile(r"\b(fix|add|implement|change|update|refactor|remove|delete|create|make|write|rename|move|"
                    r"migrate|replace|support|build|upgrade|improve|optimi[sz]e|convert|extend)\b|"
                    r"(?<!\w)(düzelt|ekle|değiştir|güncelle|sil|oluştur|kaldır|taşı|yaz|uygula|iyileştir|"
                    r"yeniden adlandır)",
                    re.I)


def is_question(prompt: str) -> bool:
    """A prompt that only asks about the code (no change, so no check follows); a change request phrased as a
    question is not one."""
    text = prompt.strip()
    shaped = bool(QUESTION.match(text)) or (text.endswith("?") and len(text) < 300)
    return shaped and not CHANGE.search(text)


# a prompt that rules out building or running checks, or any change ("Do not build, compile or run tests",
# "Do not change any files", "derleme yapma"): no advice on how to check
NO_CHECKS = re.compile(r"\b(?:do not|don't|never|without)\b[^.\n]{0,30}?\b(?:build|compil\w*|run\w*\s+(?:the\s+)?"
                       r"(?:\w+\s+)?(?:tests?|checks?)|change any files?)\b|(?<!\w)(?:derleme|test çalıştırma)\s*"
                       r"(?:yapma|yapmayın|yok)|(?<!\w)derlemeyin|test(?:leri)? çalıştırmayın", re.I)


def profile_text(root: str, prompt: str, timeout: float) -> str:
    """How to check a change here, read from the repository's build files (the shared `ai_orch_profile`), for a task
    that asks for a change and does not rule checks out: advice, not a test result. Nothing when the build files are being changed in the working
    tree (the declared command may be in flux), nothing is declared, or the work does not finish within `timeout`
    (it runs in a daemon thread that is abandoned at the deadline, like the bootstrap call)."""
    if timeout <= 0 or is_question(prompt) or NO_CHECKS.search(prompt):
        return ""
    found: list[str] = []
    worker = threading.Thread(target=lambda: found.append(_profile(root, timeout)), daemon=True)
    worker.start()
    worker.join(timeout)
    return found[0] if found and not worker.is_alive() else ""


def _profile(root: str, timeout: float) -> str:
    if shared_hooks() is None:
        return ""
    try:
        import ai_orch_profile
        changed = changed_paths(root, timeout)
        if changed is None or any(PROFILE_FILES.search(f) for f in changed) \
                or ai_orch_profile.touches_build_config(root, sorted(changed)):
            return ""
        lines = ai_orch_profile.render(ai_orch_profile.detect(root))
    except Exception:  # noqa: BLE001 - advice only: fail open without it
        return ""
    if not lines:
        return ""
    return "\n".join(lines) + "\n(Advice from the build files, not a test result: run the checks yourself.)"


# the files `ai_orch_profile.detect` reads the test command from (any ecosystem)
PROFILE_FILES = re.compile(r"(^|/)(pom\.xml|build\.gradle(\.kts)?|settings\.gradle(\.kts)?|go\.mod|package\.json|"
                           r"pyproject\.toml|setup\.(cfg|py)|tox\.ini|pytest\.ini|conftest\.py|requirements[^/]*\.txt)$")


def changed_paths(root: str, timeout: float = 2.0) -> set[str] | None:
    """Files `git status` lists as changed or new, or None when git cannot say."""
    try:
        out = subprocess.run(["git", "-C", root, "status", "--porcelain", "-uall"], capture_output=True, text=True,
                             timeout=max(0.05, timeout))
    except (OSError, subprocess.SubprocessError):
        return None
    if out.returncode != 0:
        return None
    return {line[3:].strip() for line in out.stdout.splitlines() if len(line) > 3}


def prompt_hook(payload: dict[str, Any]) -> dict[str, Any]:
    """userPromptSubmitted: a new request (search record, start tree, step lookups) and the prompt's memory cards."""
    session_id = str(payload.get("sessionId") or "").strip()
    cwd = str(payload.get("cwd") or "")
    if not session_id or str(payload.get("prompt") or "").strip().startswith(LEARN_REASON):
        return {}  # the stop hook's own reminder arrives as a prompt: the same request goes on
    started = time.monotonic()

    def left() -> float:
        return DEADLINE - (time.monotonic() - started)

    root = repo_root(cwd, left()) if cwd else None
    reset(session_id, tree_fingerprint(root, left()) if root else None)  # the new request starts even when late
    prompt = str(payload.get("prompt") or "").strip()
    if not root or not prompt:
        return {}
    _reset_steps(session_id, root)
    path = state_file(session_id)
    result = fetch_bootstrap(root, prompt, left())  # bounded by the time left
    profile = "" if _load(path).get("profile_shown") else profile_text(root, prompt, left())
    if left() <= 0:
        return {"additionalContext": LEARN_LINE}  # too late: no card or advice is shown or recorded as shown
    state = _load(path)
    cards = result.get("memory") if isinstance(result.get("memory"), list) else []
    text, shown = render(cards, set(state.get("shown", [])), set(state.get("pointers", [])))
    if profile:
        state["profile_shown"] = True
    if text:
        state["shown"] = sorted(set(state.get("shown", [])) | {str(c.get("id")) for c in shown if not c.get("pointer")})
        state["pointers"] = sorted(set(state.get("pointers", [])) | {str(c.get("id")) for c in shown
                                                                       if c.get("pointer")})
        state["card_files"] = sorted(set(state.get("card_files", [])) | card_files(text))
    _save(path, state)
    if text:
        _remember_delivery(session_id, text, at_failure=False)
    return {"additionalContext": "\n\n".join(part for part in (profile, text, LEARN_LINE) if part)}


def _reset_steps(session_id: str, root: str) -> None:
    """A new request for the shared step lookups (their budget, moments and no-repeat record)."""
    step = shared_hooks()
    if step is None:
        return
    path = step.session.state_path(session_id, root)
    state = step.session.load(path)
    state["request"] = {"ordinal": int((state.get("request") or {}).get("ordinal") or 0) + 1}
    step.memory_gate.reset(state)
    step.session.save(path, state)


def post_hook(payload: dict[str, Any]) -> dict[str, Any]:
    """postToolUse on a shell command: memory for the step the agent is at, as Claude Code gets it (the first check of
    a request: how to check here; a check failing with a new error: what fixed it before), through the shared step
    module; and the check outcomes the stop fallback's learning signal needs (recorded also when memory is closed)."""
    session_id = str(payload.get("sessionId") or "").strip()
    cwd = str(payload.get("cwd") or "")
    if not session_id or str(payload.get("toolName") or "") != "bash" or not cwd.startswith("/"):
        return {}
    step = shared_hooks()
    if step is None:
        return {}
    command = str((payload.get("toolArgs") or {}).get("command") or "")
    result = payload.get("toolResult") or {}
    output = str(result.get("textResultForLlm") or "")
    failed_tool = str(result.get("resultType") or "success") != "success"
    path = state_file(session_id)
    state = _load(path)
    failed = False
    if step.session.is_verify(command):  # the stop fallback's signal
        if failed_tool or step.FAILED.search(output):
            failed = state["check_failed"] = True
        elif state.get("check_failed"):
            if any(fix in command for fix in state.get("memory_fixes", [])):
                state["fixed_from_memory"] = True  # the fix a delivered card gave: known, not learned here
            else:
                state["fixed_after_failure"] = True
        _save(path, state)
    text = "" if state.get("empty") else _step_memory(step, session_id, cwd, command, output, failed_tool)
    if text:
        _remember_delivery(session_id, text, at_failure=failed)
    return {"additionalContext": text} if text else {}


CARD_LINE = re.compile(r"^- Memory card(?: \([^)]*\))?: (.+?)\. ")
COMMAND = re.compile(r"`([^`]{3,200})`")


def _remember_delivery(session_id: str, text: str, at_failure: bool) -> None:
    """What a block delivered in full: card summaries (named in the stop reminder as already known) and, for a
    failure, the commands those cards give (a later passing check running one of them is a fix from memory).
    Pointer lines deliver no knowledge and are not counted."""
    path = state_file(session_id)
    state = _load(path)
    for line in text.splitlines():
        match = CARD_LINE.match(line)
        if not match:
            continue
        state["full_delivered"] = True
        summaries = state.setdefault("delivered", [])
        if match.group(1) not in summaries:
            summaries.append(match.group(1))
        if at_failure:
            fixes = state.setdefault("memory_fixes", [])
            fixes += [c.strip() for c in COMMAND.findall(line) if c.strip() not in fixes]
    _save(path, state)


def _step_memory(step: Any, session_id: str, cwd: str, command: str, output: str, failed_tool: bool) -> str:
    """The shared step lookup, with this session's records synced both ways: cards shown at the prompt are not shown
    again, and queries asked or closed by `ai_orch memory search` count for the step lookups and the reverse."""
    path = state_file(session_id)
    state = _load(path)
    root = step.session.repo_root(cwd)
    shared_path = step.session.state_path(session_id, root)
    shared = step.session.load(shared_path)
    gate = step.memory_gate.record_of(shared)
    gate["queries"] = sorted(set(gate.get("queries", [])) | set(state.get("queries", [])))
    if state.get("empty") and not gate.get("empty"):
        gate["empty"] = state["empty"]
    step.session.save(shared_path, shared)
    seen_file = step._seen_file({"session_id": session_id}, root)
    try:
        seen = json.loads(seen_file.read_text())
    except (OSError, ValueError):
        seen = {}
    seen["cards"] = sorted(set(seen.get("cards", [])) | set(state.get("shown", [])))
    seen["pointers"] = sorted(set(seen.get("pointers", [])) | set(state.get("pointers", [])))
    seen_file.write_text(json.dumps(seen))
    step.pointer_line = pointer_line  # Copilot opens a pointer with its search command, not `extras`
    text = step.context({"tool_name": "Bash", "tool_input": {"command": command}, "cwd": cwd,
                         "session_id": session_id, "tool_response": {"stdout": output, "is_error": failed_tool}})
    try:
        seen = json.loads(seen_file.read_text())
    except (OSError, ValueError):
        seen = {}
    gate = step.memory_gate.record_of(step.session.load(shared_path))
    state = _load(path)
    state["shown"] = sorted(set(state.get("shown", [])) | set(seen.get("cards", [])))
    state["pointers"] = sorted(set(state.get("pointers", [])) | set(seen.get("pointers", [])))
    state["card_files"] = sorted(set(state.get("card_files", [])) | card_files(text))
    state["queries"] = sorted(set(state.get("queries", [])) | set(gate.get("queries", [])))
    if gate.get("empty") and not state.get("empty"):
        state["empty"] = gate["empty"]  # memory had nothing: the agent's search is refused too
    _save(path, state)
    return text


def pointer_line(card: dict[str, Any]) -> str:
    summary = str(card.get("summary") or "").rstrip(".")
    query = summary.replace('"', "'")[:160]
    return (f"- Possibly relevant memory (not loaded): {summary}. If it fits, read it with "
            f"`ai_orch memory search --query \"{query}\"`.")


def learning_signal(state: dict[str, Any], cwd: str) -> bool:
    """The stop fallback's signal: the task changed files since the prompt, and either fixed a failing check with no
    card's help (a failure fixed by a delivered card's command is `fixed_from_memory`, known, not learned here) or had
    no knowledge to go on at all (no full card at the prompt or a step, and no memory search that found something), so
    what it worked out is new for the next task."""
    if not (state.get("fixed_after_failure") or not state.get("full_delivered")):
        return False
    root = repo_root(cwd) if cwd else None
    return bool(root and state.get("tree") and tree_fingerprint(root) not in (None, state["tree"]))


LEARN_COMMENT = re.compile(r"<!--\s*ai-orch-learn\b(.*?)-->", re.S)
LEARN_FENCE = re.compile(r"```ai-orch-learn[ \t]*\n(.*?)```", re.S)


def learning_payload(answer: str) -> list[Any] | None:
    """The learning candidates the final answer carries: the last `<!-- ai-orch-learn {...} -->` comment (a fenced
    ```ai-orch-learn``` block is accepted too); None when there is none or it is not JSON."""
    found = [(m.start(), m.group(1)) for pattern in (LEARN_COMMENT, LEARN_FENCE) for m in pattern.finditer(answer)]
    if not found:
        return None
    try:
        value = json.loads(max(found)[1].strip())
    except ValueError:
        return None
    if isinstance(value, dict):
        value = value.get("learningCandidates")
    return value if isinstance(value, list) and value else None


TRANSCRIPT_WAIT = 3.0  # seconds the stop hook waits for Copilot to finish writing the turn to its transcript


def _events(transcript: str) -> list[dict[str, Any]]:
    events = []
    try:
        with open(transcript, encoding="utf-8") as stream:
            for line in stream:
                try:
                    event = json.loads(line)
                except ValueError:
                    continue
                if isinstance(event, dict):
                    events.append(event)
    except (OSError, TypeError):
        return []
    return events


def _this_stop(event: dict[str, Any], stamp: Any) -> bool:
    """The transcript's record of this agentStop hook call (Copilot logs each hook call with the input it got)."""
    data = event.get("data") if isinstance(event.get("data"), dict) else {}
    if event.get("type") != "hook.start" or data.get("hookType") != "agentStop":
        return False
    return (data.get("input") or {}).get("timestamp") == stamp


def final_answer(transcript: str, stamp: Any, wait: float | None = None) -> str | None:
    """The text of the final assistant message before this stop, from Copilot's session transcript (events.jsonl).
    Copilot writes the file behind the hook call (measured: at the hook's start the turn was often missing, half a
    second later it was there), so this waits, up to `wait` seconds, for the transcript's record of this very hook call
    (an agentStop `hook.start` whose input has this payload's `timestamp`; every model call ends a turn and most are
    followed by some hook, so only this record proves the final turn is written); then the last assistant message after
    the last user message before it. None when this stop cannot be confirmed (no timestamp, no record by the deadline):
    what the file holds then may be an earlier answer, which must not be saved for this request."""
    if stamp is None:
        return None
    deadline = time.monotonic() + (TRANSCRIPT_WAIT if wait is None else wait)
    while True:
        events = _events(transcript)
        mark = next((i for i in range(len(events) - 1, -1, -1) if _this_stop(events[i], stamp)), None)
        if mark is not None:
            break
        if time.monotonic() >= deadline:
            return None
        time.sleep(0.1)
    last = ""
    for event in events[:mark]:
        data = event.get("data")
        if event.get("type") == "user.message":
            last = ""  # an answer belongs to the turn after the last user message
        elif event.get("type") == "assistant.message" and isinstance(data, dict) and data.get("content"):
            last = str(data["content"])
    return last


ABSOLUTE = re.compile(r"(?<![\w.~:/-])(/[^\s`'\"(),;<>\[\]{}]+)")


def _exists_exact(path: str) -> bool:
    """`path` exists with exactly this spelling (a case-insensitive file system says `/users` exists for `/Users`)."""
    current = Path("/")
    for part in [p for p in path.split("/") if p]:
        try:
            if part not in os.listdir(current):
                return False
        except OSError:
            return False
        current = current / part
    return True


def localize(candidate: dict[str, Any], root: str) -> dict[str, Any] | None:
    """The candidate with paths under the repository written repo-relative; None when its text names a path that
    exists on this machine outside the repository (knowledge about the machine, not the repository). Paths that do not
    exist here, such as the endpoint `/api/ping`, are kept as written."""
    prefix = root.rstrip("/") + "/"

    def fix(text: str) -> str | None:
        for token in ABSOLUTE.findall(text):
            path = token.rstrip(".:")
            if path == root or path.startswith(prefix):
                text = text.replace(path, path[len(prefix):] or ".")
            elif _exists_exact(path):
                return None
        return text

    result = dict(candidate)
    for field in ("summary", "content"):
        if isinstance(result.get(field), str):
            fixed = fix(result[field])
            if fixed is None:
                return None
            result[field] = fixed
    for field in ("appliesWhen", "limitations", "reusableFor"):
        if isinstance(result.get(field), list):
            items = [fix(item) if isinstance(item, str) else item for item in result[field]]
            if any(item is None for item in items):
                return None
            result[field] = items
    return result


CAPTURE_TIMEOUT = 10.0  # seconds for the one capture request, inside the hook's 15 s


def capture(root: str, run_id: str, raw: list[Any]) -> dict[str, Any] | None:
    """Validate the answer's candidates exactly as `ai_orch memory learn` does (each on its own: an invalid one is
    dropped, the rest kept) and submit them once through the same endpoint and write gate. The server's receipt, or
    None when nothing valid was left or the request failed (timeout, unreachable, rejected)."""
    import ai_orch
    candidates: list[dict[str, Any]] = []
    for entry in raw[:3]:
        local = localize(entry, root) if isinstance(entry, dict) else None
        if local is None:
            continue
        try:
            candidates += ai_orch._learning_candidates([local])
        except ai_orch.BridgeError:
            continue
    try:
        candidates = ai_orch._learning_candidates(candidates) if candidates else []
    except ai_orch.BridgeError:  # together over the locator limit: the first one alone
        candidates = candidates[:1]
    if not candidates:
        return None
    previous = os.getcwd()
    try:
        os.chdir(root)  # the bridge's context key belongs to the repository
        with ai_orch.AiOrchClient(token=os.environ.get("AI_ORCH_MCP_TOKEN"), timeout=CAPTURE_TIMEOUT,
                                  context_key=ai_orch.bridge_context_key()) as client:
            result = client.capture_learning(root, run_id, candidates)
        ai_orch.record_learning_telemetry("memory.learn", {"learningCandidates": candidates}, result)
    except Exception:  # noqa: BLE001 - fail-open: a failed capture is not a saved one
        return None
    finally:
        os.chdir(previous)
    return result if isinstance(result, dict) else None


def run_id(session_id: str, state: dict[str, Any]) -> str:
    """The capture's idempotency key: one per user request of this session (the server replays the same receipt)."""
    return f"copilot-{hashlib.sha256(session_id.encode()).hexdigest()[:16]}-r{int(state.get('request') or 0)}"


def stop_hook(payload: dict[str, Any]) -> dict[str, Any]:
    """agentStop: save the learning the final answer carries in its hidden comment, at most once per request (the
    receipt is kept only for an ACCEPTED capture, so a failed one may be retried by a later stop). Without a comment,
    and only when the agent fixed its own failing check, ask once for that comment alone (one short reply); the reply
    arrives as a prompt that does not start a new request, and the next stop saves it."""
    session_id = str(payload.get("sessionId") or "").strip()
    if not session_id:
        return {}
    path = state_file(session_id)
    state = _load(path)
    if state.get("receipt") or state.get("learned") or not state.get("tree"):
        return {}
    cwd = str(payload.get("cwd") or "")
    answer = final_answer(str(payload.get("transcriptPath") or ""), payload.get("timestamp"))
    if answer is None:
        return {}  # this stop's answer cannot be confirmed: nothing is saved and nothing is asked
    raw = learning_payload(answer)
    if raw is not None:
        root = repo_root(cwd) if cwd else None
        result = capture(root, run_id(session_id, state), raw) if root else None
        if result and result.get("status") == "ACCEPTED":
            state = _load(path)
            state["receipt"] = {"runId": run_id(session_id, state), "created": result.get("created", 0),
                                "reused": result.get("reused", 0)}
            state["learned"] = True
            _save(path, state)
        return {}
    if payload.get("stop_hook_active") or state.get("asked") or not learning_signal(state, cwd):
        return {}
    state["asked"] = True
    _save(path, state)
    return {"decision": "block", "reason": learn_reason(state)}


def learn_reason(state: dict[str, Any]) -> str:
    """The stop reminder, naming what this task already received, so only knowledge that is new gets saved."""
    known = state.get("delivered", [])[:8]
    if not known:
        return LEARN_REASON
    return (LEARN_REASON + " Already known (delivered to you in this task, do not save again): "
            + "; ".join(known) + ".")


def main(argv: list[str] | None = None) -> int:
    """Copilot hook entry: `copilot_memory_gate.py` (userPromptSubmitted), `copilot_memory_gate.py post`
    (postToolUse) or `copilot_memory_gate.py stop` (agentStop), the hook payload on stdin. Prints the hook's JSON answer; any error prints nothing (fail-open)."""
    argv = sys.argv[1:] if argv is None else argv
    try:
        payload = json.loads(sys.stdin.read(), strict=False)  # tool output may carry raw control characters
        if not isinstance(payload, dict):
            return 0
        mode = argv[:1]
        answer = (stop_hook(payload) if mode == ["stop"] else post_hook(payload) if mode == ["post"]
                  else prompt_hook(payload))
    except Exception:  # noqa: BLE001 - a hook must never break the user's session
        return 0
    if answer:
        print(json.dumps(answer))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
