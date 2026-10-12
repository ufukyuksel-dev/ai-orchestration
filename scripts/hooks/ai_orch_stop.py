#!/usr/bin/env python3
"""Claude Code `Stop` hook: the final tree is checked, and what the task taught is saved without an agent turn.

1. Check: if the working tree changed during the session, the hook runs the project's formatter and whichever of the
   quality gate and the whole test suite (ai_orch_profile.py) no passing check covered on this exact tree (see
   ai_orch_command_hook.py), all within one time budget below the hook's timeout.
   When one fails, the agent gets the compact failure and continues (`decision: block`), instead of finishing on a
   broken or unformatted tree. Asked at most once per request (`stop_hook_active`).
2. Learn: the transcript of the request is turned into `memory.learn` candidates and sent through the same
   validation and write gate as the agent's own call. Only verified facts are proposed:
   a compile/API error a check reported and the edit after which the next check no longer reported it (a pitfall,
   e.g. a class that moved package in this framework version). Failing assertions are about the task's own code and
   are left out.
   Nothing is sent when the request changed no files or nothing failed; the server drops duplicates.

Never blocks on its own errors; prints nothing when there is nothing to do.
"""
from __future__ import annotations

import hashlib
import json
import os
import re
import subprocess
import sys
import time
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import ai_orch_profile  # noqa: E402
import ai_orch_trail  # noqa: E402
import ai_orch_session_state as session  # noqa: E402

URL = os.environ.get("AI_ORCH_URL", "http://127.0.0.1:18080").rstrip("/") + "/mcp"
LEVERS = {x.strip() for x in os.environ.get("AI_ORCH_LEVERS", "all").split(",")}
CHECK_TIMEOUT = int(os.environ.get("AI_ORCH_STOP_CHECK_TIMEOUT", "900"))  # all checks together; hook timeout is 960
EDIT_TOOLS = {"Edit", "Write", "MultiEdit", "NotebookEdit"}
BASH_EDIT = re.compile(r"\bsed\s+-i|\bperl\s+-p?i|(^|\s)(cat|tee)\s[^|]*>|\bpython3?\s+-\s*<<|>\s*[\w./-]+\.(java|py|ts|js|go|kt|xml|yml|yaml|properties|sql|html|json)\b|\bapply_patch\b")
ERROR_LINE = re.compile(r"\[ERROR\]|ERROR:|error:|Error:|FAIL|Failed|failed|Exception|expected|violation|cannot find symbol|does not exist|not found", re.I)
SPECIFIC = re.compile(r"does not exist|cannot find symbol|symbol:|expected|but was|violation|incompatible|undefined|"
                      r"not found|No such|AssertionError|Tests run:.*(Failures|Errors): [1-9]|\.\w+:\[?\d+", re.I)
NOISE = re.compile(r"re-run Maven|-e switch|-X switch|Help 1|For more information|MojoFailureException|"
                   r"^\[ERROR\]\s*$|^\[ERROR\] -> |^WARNING|To see the full stack trace", re.I)
PITFALL = re.compile(r"does not exist|cannot find symbol|incompatible types|cannot be applied|is not public|"
                     r"has private access|undefined:|ImportError|ModuleNotFoundError|has no attribute|"
                     r"Cannot find module|TS\d{4}|cannot use .* as|no such method|deprecated and marked for removal",
                     re.I)
FAILED = re.compile(r"BUILD FAILURE|COMPILATION ERROR|Failed to execute goal|Tests run:.*(Failures|Errors): [1-9]|"
                    r"^FAIL\b|^--- FAIL|\b\d+ failed\b|npm ERR!|Exit code [1-9]", re.M)


def lever(name: str) -> bool:
    return "all" in LEVERS or name in LEVERS


# ------------------------------------------------------------------ transcript
def steps_since_last_prompt(transcript: str) -> list[dict]:
    """Tool calls of the current request, in order: name, input, is_error, output text, done (False for a Claude
    tool call without a result yet; Codex items are completed). Reads Claude Code transcripts and Codex rollouts."""
    calls: dict[str, dict] = {}
    order: list[dict] = []
    with open(transcript, encoding="utf-8") as handle:
        lines = handle.readlines()
    for line in lines:
        try:
            entry = json.loads(line)
        except ValueError:
            continue
        if entry.get("type") in ("event_msg", "response_item", "session_meta", "turn_context"):  # Codex rollout
            item = (entry.get("payload") or {}).get("item") or {}
            if (entry.get("payload") or {}).get("type") != "item_completed":
                continue
            if item.get("type") == "UserMessage":
                order = []
            elif item.get("type") == "CommandExecution":
                command = item.get("command")
                command = command[-1] if isinstance(command, list) and command else str(command or "")
                order.append({"name": "Bash", "input": {"command": command}, "is_error": item.get("exit_code") not in (0, None),
                              "output": str(item.get("aggregated_output") or "")})
            elif "mcp" in str(item.get("type") or "").lower():
                tool = str(item.get("tool") or item.get("name") or item.get("tool_name") or "")
                order.append({"name": "mcp:" + tool, "input": {}, "is_error": False, "output": ""})
            elif item.get("type") in ("FileChange", "PatchApply") or item.get("changes"):
                for path in (item.get("changes") or {}):
                    order.append({"name": "Edit", "input": {"file_path": path}, "is_error": False, "output": ""})
            continue
        message = entry.get("message") or {}
        content = message.get("content")
        if entry.get("type") == "user" and not entry.get("isMeta") and (
                isinstance(content, str) or any(b.get("type") == "text" for b in content or [])):
            calls, order = {}, []
            continue
        for block in content if isinstance(content, list) else []:
            if block.get("type") == "tool_use":
                step = {"name": block.get("name", ""), "input": block.get("input") or {}, "is_error": False,
                        "output": "", "done": False}
                calls[block.get("id")] = step
                order.append(step)
            elif block.get("type") == "tool_result" and block.get("tool_use_id") in calls:
                step = calls[block["tool_use_id"]]
                step["done"] = True
                step["is_error"] = bool(block.get("is_error"))
                out = block.get("content")
                step["output"] = out if isinstance(out, str) else "\n".join(
                    b.get("text", "") for b in out or [] if isinstance(b, dict))
    return order


def is_edit(step: dict) -> bool:
    if step["name"] in EDIT_TOOLS:
        return True
    return step["name"] == "Bash" and bool(BASH_EDIT.search(str(step["input"].get("command") or "")))


def failed(step: dict) -> bool:
    return step["is_error"] or bool(FAILED.search(step["output"]))


def first_errors(text: str, limit: int = 3, root: str = "") -> list[str]:
    """The most telling error lines: specific compiler/test messages first, generic [ERROR] lines only to fill up."""
    specific, generic = [], []
    for line in text.splitlines():
        line = line.strip()
        if len(line) <= 8 or NOISE.search(line) or not (ERROR_LINE.search(line) or SPECIFIC.search(line)):
            continue
        if root:
            line = line.replace(root.rstrip("/") + "/", "")
        line = re.sub(r"/(private/)?(var|tmp)/\S*?/(src/)", r"\3", line)[:240]
        bucket = specific if SPECIFIC.search(line) else generic
        if line not in specific and line not in generic:
            bucket.append(line)
    return (specific + generic)[:limit]


def describe_edit(step: dict, root: str) -> tuple[str, str | None]:
    """A short description of an edit and the repository path it touched."""
    args = step["input"]
    path = args.get("file_path") or args.get("notebook_path")
    rel = os.path.relpath(path, root) if path and path.startswith(root) else path
    if step["name"] == "Edit" and args.get("old_string") is not None:
        old, new = str(args["old_string"]).strip(), str(args.get("new_string") or "").strip()
        return f"in {rel} replaced `{old[:160]}` with `{new[:160]}`", rel
    if step["name"] == "MultiEdit":
        edits = args.get("edits") or []
        if edits:
            e = edits[0]
            return f"in {rel} replaced `{str(e.get('old_string'))[:160]}` with `{str(e.get('new_string'))[:160]}`", rel
    if step["name"] == "Write":
        return f"rewrote {rel}", rel
    command = str(args.get("command") or "")
    parts = [p.strip() for p in re.split(r"&&|;|\n|\|\|", command.split("<<")[0])
             if p.strip() and not session.is_verify(p) and not p.strip().startswith("cd ")]
    edited = re.findall(r"(?:^|\s)((?:src|lib|app|test|tests|pkg|internal|cmd)/[\w./-]+\.\w+)", " ".join(parts))
    return f"ran `{' && '.join(parts)[:260]}`", (edited[-1] if edited else None)


def candidates(steps: list[dict], root: str, prof: dict) -> list[dict]:
    """Compile/API pitfalls the task ran into and the edit that made them go away. Failing assertions are left out:
    they are about the task's own code, not about the repository."""
    out: list[dict] = []
    for i, step in enumerate(steps):
        command = str(step["input"].get("command") or "") if step["name"] == "Bash" else ""
        if not command or not session.is_verify(command) or not failed(step):
            continue
        errors = [e for e in first_errors(step["output"], 6, root) if PITFALL.search(e)][:3]
        if not errors:
            continue
        edits: list[dict] = []
        for later in steps[i + 1:]:
            later_cmd = str(later["input"].get("command") or "") if later["name"] == "Bash" else ""
            if is_edit(later):
                edits.append(later)
            if not (later_cmd and session.is_verify(later_cmd)):
                continue
            still = [e for e in errors if _key(e) in {_key(x) for x in first_errors(later["output"], 50, root)}]
            if still:
                break  # the same error again: no verified fix
            if failed(later) or "compile" not in session.coverage(later_cmd):
                continue  # failed for another reason, or did not compile: no evidence yet, keep looking
            fixes = [describe_edit(e, root) for e in edits[:3]]
            fixes = [(text, f) for text, f in fixes if "replaced" in text or "sed" in text or "perl" in text]
            if fixes:
                files = sorted({f for _, f in fixes if f})
                out.append({
                    "kind": "debug_finding",
                    "summary": f"Compile pitfall: {_key(errors[0])[:130]}",
                    "content": ("Seen while building: " + " / ".join(_key(e) for e in errors)
                                + ". What fixed it: " + "; ".join(text for text, _ in fixes)
                                + f". Verified: `{short(later_cmd)[:100]}` then passed.")[:700],
                    "locators": [{"kind": "file", "ref": f, "role": "supporting"} for f in files[:8]]
                                or [{"kind": "directory", "ref": ".", "role": "supporting"}],
                })
            break
    unique, seen = [], set()
    for c in out:
        if c["summary"] not in seen:
            seen.add(c["summary"])
            unique.append(c)
    return unique[:3]


def _key(line: str) -> str:
    """An error line without its location prefix, so the same error in another file or line still matches."""
    line = re.sub(r"^\[ERROR\]\s*", "", line)
    return re.sub(r"^\S+:\[?\d+[,:]?\d*\]?\s*", "", line).strip()


def short(command: str) -> str:
    """The command as the agent typed it, without the hook's own suffix and the formatter goal it inserted."""
    command = command.split("\n__aio_rc=$?")[0]
    command = re.sub(r"\s+spring-javaformat:apply(?=\s)", "", command)
    return re.sub(r"\s+", " ", command).strip()[:300]


# ------------------------------------------------------------------ server
def mcp_call(name: str, arguments: dict) -> dict | None:
    headers = {"Content-Type": "application/json", "Accept": "application/json, text/event-stream",
               "X-AI-Orch-Client": os.environ.get("AI_ORCH_CLIENT", "claude-code")}

    def post(payload: dict, sid: str | None) -> tuple[dict | None, str | None]:
        h = dict(headers, **({"Mcp-Session-Id": sid} if sid else {}))
        request = urllib.request.Request(URL, json.dumps(payload).encode(), h, method="POST")
        with urllib.request.urlopen(request, timeout=30) as response:
            sid = response.headers.get("mcp-session-id") or sid
            for line in response.read().decode("utf-8", "replace").splitlines():
                line = line[5:].strip() if line.startswith("data:") else line.strip()
                if line.startswith("{"):
                    return json.loads(line), sid
        return None, sid

    init, sid = post({"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {
        "protocolVersion": "2025-06-18", "capabilities": {}, "clientInfo": {"name": "ai-orch-stop", "version": "1"}}},
        None)
    if not init:
        return None
    post({"jsonrpc": "2.0", "method": "notifications/initialized"}, sid)
    answer, _ = post({"jsonrpc": "2.0", "id": 2, "method": "tools/call",
                      "params": {"name": name, "arguments": arguments}}, sid)
    return answer


# ------------------------------------------------------------------ check
def run(command: str, root: str, deadline: float) -> tuple[int, str]:
    left = deadline - time.monotonic()
    if left <= 1:
        return 124, "no time left"
    try:
        done = subprocess.run(command, shell=True, cwd=root, capture_output=True, text=True, timeout=left,
                              stdin=subprocess.DEVNULL)
        return done.returncode, done.stdout + done.stderr
    except subprocess.TimeoutExpired:
        return 124, "timed out"


def tests_check(root: str, prof: dict, covered: set[str]) -> tuple[str | None, set[str]]:
    """The test command the final tree still needs and the coverage it proves when it passes and tests ran.
    Single-module: the suite unless covered. Multi-module Gradle: the changed modules no run covered yet (each proves
    `tests::<module>`); a change to the build configuration itself (settings, root build files, convention plugins)
    is not covered by module tests and needs the whole suite. (None, set()) when nothing runnable remains."""
    if "tests" in covered:
        return None, set()
    if not prof.get("module_test"):
        return (prof["test"], {"tests"}) if prof.get("test") else (None, set())
    files = session.changed_files(root)
    gw = "./gradlew" if Path(root, "gradlew").exists() else "gradle"
    if ai_orch_profile.touches_build_config(root, files):
        return f"{gw} test -q", {"tests"}
    done = {kind.split(":", 1)[1] for kind in covered if kind.startswith("tests:")}
    targets = ai_orch_profile.module_test_targets(root, files, skip=done)
    if not targets:
        return None, set()
    return f"{gw} " + " ".join(f"{m}:{t}" for m, t in targets) + " -q", {f"tests:{m}" for m, _ in targets}


def final_check(root: str, prof: dict, needed: set[str], covered: set[str] = frozenset(),
                receipts: list | None = None) -> tuple[str | None, set[str]]:
    """Runs the formatter and the checks in `needed` ("quality", "tests") within one shared time budget, below the
    hook's own timeout. Returns (failure for the agent or None, the coverage this run actually proved). A test
    command that passes without running any test (zero, all skipped, no evidence) proves no test coverage."""
    deadline = time.monotonic() + CHECK_TIMEOUT
    tests, test_scope = tests_check(root, prof, set(covered)) if "tests" in needed else (None, set())
    commands = [(label, cmd) for label, cmd in (("quality", prof.get("quality")), ("tests", tests))
                if label in needed and cmd]
    proved: set[str] = set()
    if not commands:
        return None, proved
    if prof.get("format"):
        code, out = run(prof["format"], root, deadline)
        if code == 124:
            return f"The final check did not finish within {CHECK_TIMEOUT}s (formatter `{prof['format']}`).", proved
        if code != 0:
            return f"The formatter `{prof['format']}` failed:\n" + "\n".join(first_errors(out, 15)), proved
    for label, command in commands:
        started = time.time() - 1
        code, out = run(command, root, deadline)
        evidence = session.test_evidence(out, root, started) if label == "tests" else "n/a"
        step: set[str] = set()
        if code == 0:
            step = {"compile"} | ({"quality"} if label == "quality" else set())
            if label == "tests" and evidence == "ran":
                step |= session.attributable(test_scope, root, started)
        if receipts is not None:  # the Stop hook's own check, with its real outcome
            receipts.append({"command": command, "status": "passed" if code == 0 else "timeout" if code == 124
                             else "failed", "scope": ",".join(sorted(step)), "testEvidence": evidence,
                             "cwd": root, "tree": session.snapshot_tree(root), "by": "stop-hook"})
        if code == 124:
            return f"The final check did not finish within {CHECK_TIMEOUT}s (`{command}`); run it yourself.", proved
        if code != 0:
            lines = first_errors(out, 25) or out.strip().splitlines()[-25:]
            return f"`{command}` fails on the final tree:\n" + "\n".join(lines), proved
        proved |= step
    return None, proved


def main() -> int:
    try:
        event = json.load(sys.stdin)
    except ValueError:
        return 0
    root = str(event.get("cwd") or "")
    transcript = event.get("transcript_path")
    if not root.startswith("/"):
        return 0
    root = session.repo_root(root)
    state_file = session.state_path(str(event.get("session_id") or ""), root)
    state = session.load(state_file)
    prof = ai_orch_profile.detect(root)
    try:
        now = session.fingerprint(root)
        changed = bool(now) and "start_fp" in state and now != state.get("start_fp")
        covered = {kind for kind, fp in (state.get("verified") or {}).items() if fp == now}
        needed = {"quality", "tests"} - covered
        stop_receipts: list = []
        if changed and lever("check") and not event.get("stop_hook_active") and needed and prof:
            failure, proved = final_check(root, prof, needed, covered, stop_receipts)
            for receipt in stop_receipts:
                session.add_receipt(state, receipt)
            session.save(state_file, state)
            if failure:
                print(json.dumps({"decision": "block", "reason": "AI Orchestration checked the final tree. " + failure
                                  + "\nFix it, then finish."}))
                return 0
            if proved:  # only what this run proved; earlier receipts stay
                fp = session.fingerprint(root)
                state.setdefault("verified", {}).update({kind: fp for kind in proved})
                session.save(state_file, state)
        request = state.get("request") or {}
        run_id = ai_orch_trail.trail_key(str(event.get("session_id") or ""), request, transcript)
        if changed and lever("autolearn") and transcript and not state.get("learned_fp") == now:
            found = candidates(steps_since_last_prompt(transcript), root, prof)
            if found:
                response = mcp_call("memory.learn", {"rootPath": root, "candidates": found,
                                                     **({"taskRunId": run_id} if run_id else {})})
                request.setdefault("facts", []).extend(ai_orch_trail.learned_ids(response))
                state["request"] = request
            state["learned_fp"] = now
            session.save(state_file, state)
        steps = steps_since_last_prompt(transcript) if transcript else []
        if lever("trails") and transcript and not event.get("stop_hook_active"):
            # after the final check: the trail records what this request touched and how its checks really ended
            trail = ai_orch_trail.build(transcript, steps_since_last_prompt(transcript), root,
                                        str(event.get("session_id") or ""), request)
            digest = hashlib.sha256(json.dumps(trail, sort_keys=True).encode()).hexdigest() if trail else None
            if trail and digest != state.get("trail_sent"):
                if ai_orch_trail.post(trail, os.environ.get("AI_ORCH_CLIENT", "claude-code")):
                    state["trail_sent"] = digest
                    session.save(state_file, state)
    except (OSError, ValueError, KeyError, TypeError, AttributeError):
        pass
    return 0


if __name__ == "__main__":
    sys.exit(main())
