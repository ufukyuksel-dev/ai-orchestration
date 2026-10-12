#!/usr/bin/env python3
"""`PreToolUse` hook for shell commands (Claude Code `Bash`, Codex `Bash`): makes a build/test check run on the
formatted tree and records when it passed.

- When the project has a formatter (see ai_orch_profile.py), the formatter runs first, so a check never fails only on formatting and the agent never pays a validate -> format -> validate round.
  Maven projects get the formatter goal inside the same Maven call.
- The check's output goes to a log file and the agent sees a compact summary (ai_orch_compact.py): a passing Maven
  test run is a few lines instead of thousands of log lines that every later model call would re-read.
- After the command it records, for what the check covered (compile, quality gate, whole test suite; see
  ai_orch_session_state.coverage), the tree's fingerprint if the check passed. The Stop hook re-runs only what was not
  covered on the final tree: a passing lint or a single test class never stands in for the test suite.

- J2: known-equivalent repairs (repair()): pytest in a unittest repository without pytest -> unittest with the same
  tests; GNU `sed -i '<expr>'` on macOS -> `sed -i '' '<expr>'`. Each prints a receipt into the command output.

Every other command passes through unchanged. Prints nothing on any error: the command then runs as written.
Usage: ai_orch_command_hook.py [--client codex]       (hook)
       ai_orch_command_hook.py --verified <state> <root>   (called by the rewritten command)
"""
from __future__ import annotations

import json
import re
import shlex
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import ai_orch_profile  # noqa: E402
import ai_orch_memory_gate as memory_gate  # noqa: E402
import ai_orch_session_state as session  # noqa: E402

MAVEN = re.compile(r"(^|[\s;&|(])(\./mvnw|mvn)(?=\s)")


CAT = re.compile(r"(^|;|&&|\n)(\s*)cat\s+((?:[\w./@+{},*-]+[ \t]*)+?)(?=[ \t]*(?:;|&&|\n|$))")


def rewrite_reads(command: str) -> str | None:
    """`cat file...` segments (no pipe, no redirect, no heredoc) read through ai_orch_read.py: small files whole,
    large ones as an outline the agent can read ranges from."""
    if "<<" in command:
        return None
    reader = shlex.quote(str(Path(__file__).resolve().parent / "ai_orch_read.py"))
    new = CAT.sub(lambda m: f"{m.group(1)}{m.group(2)}python3 {reader} {m.group(3).strip()}", command)
    return new if new != command else None


# ---------------------------------------------------------------- J2: known-equivalent command repairs
# Only exact, intent- and coverage-preserving mappings; every repair prints a receipt into the command's own output
# ("[ai-orch] requested: ...; executed: ...; scope: ..."), so no model turn is spent on a command that cannot work.
PYTEST_COMMAND = re.compile(
    r"^\s*(?:(?P<py>python(?:3(?:\.\d+)?)?)\s+-m\s+)?pytest(?P<args>(?:\s+[^\s|;&<>`$()'\"\\]+)*)"
    r"(?P<tail>\s+2>&1)?(?P<pipe>\s*\|\s*(?:tail|head)(?:\s+-n)?\s+-?\d+)?\s*$")
PYTEST_TO_UNITTEST = {"-q": "-q", "-v": "-v", "-vv": "-v", "-x": "-f"}
SHELL_META = re.compile(r"[|;<>`$()&\n]")


def _bsd_sed() -> bool:
    import shutil
    return sys.platform == "darwin" and shutil.which("sed") == "/usr/bin/sed"


def _pytest_missing(python: str, cwd: str) -> bool:
    import shutil
    import subprocess
    if shutil.which("pytest"):
        return False
    try:
        return subprocess.run([python, "-c", "import pytest"], cwd=cwd, capture_output=True, timeout=10).returncode != 0
    except (OSError, subprocess.SubprocessError):
        return False


def _unittest_args(args: str) -> list[str] | None:
    """pytest arguments -> unittest arguments (`tests.py::C::t` -> `tests.C.t`, `-x` -> `-f`); None when any argument
    has no exact unittest equivalent (directories, -k, markers, plugins)."""
    out = []
    for arg in args.split():
        if arg.startswith("--tb="):
            continue  # traceback format only
        if arg in PYTEST_TO_UNITTEST:
            out.append(PYTEST_TO_UNITTEST[arg])
            continue
        path, _, rest = arg.partition("::")
        if arg.startswith("-") or not path.endswith(".py"):
            return None
        out.append(".".join([path[:-3].replace("/", ".")] + [p for p in rest.split("::") if p]))
    return out


def _first_and_rest(command: str) -> tuple[str, str]:
    """Split at the first `&&` outside quotes: (first command, `&& ...` remainder or "")."""
    quote = None
    for i, ch in enumerate(command):
        if quote:
            if ch == quote:
                quote = None
        elif ch in "'\"":
            quote = ch
        elif command.startswith("&&", i):
            return command[:i].rstrip(), command[i:]
    return command, ""


def _repair_sed(command: str) -> tuple[str, str] | None:
    """`sed -i <expr> <file>...` (GNU in-place without suffix) as the first command -> BSD form `sed -i '' ...`."""
    first, rest = _first_and_rest(command)
    unquoted = re.sub(r"'[^']*'|\"[^\"]*\"", "", first)
    if SHELL_META.search(unquoted.replace("$", "")):  # `$var` and globs are kept verbatim; pipes/redirects are not
        return None
    try:
        tokens = shlex.split(first)
    except ValueError:
        return None
    if len(tokens) < 4 or tokens[:2] != ["sed", "-i"] or tokens[2] == "" or tokens[2].startswith("-"):
        return None
    # the original text stays as written (quoting, `$var`, globs); only the empty backup suffix is inserted
    fixed = re.sub(r"^(\s*sed\s+-i)(\s+)", r"\1 ''\2", first, count=1)
    return fixed + (" " + rest if rest else ""), "requested: sed -i <expr>; executed: sed -i '' <expr>; " \
                                                 "scope: same edit (macOS sed needs an explicit empty backup suffix)"


def _repair_pytest(command: str, cwd: str, prof: dict) -> tuple[str, str] | None:
    """The whole command is `[python[3[.x]] -m] pytest <args>` (optionally `2>&1` and `| tail/head -N`) in a unittest
    repository where pytest is not importable -> the same interpreter's unittest with the same tests."""
    if prof.get("build") != "Python (unittest)":
        return None
    m = PYTEST_COMMAND.match(command)
    if not m:
        return None
    import shutil
    python = m.group("py") or ("python" if shutil.which("python") else "python3")
    args = _unittest_args(m.group("args") or "")
    if args is None or not _pytest_missing(python, cwd):
        return None
    replacement = " ".join([python, "-m", "unittest", *args])
    requested = command[:m.end("args")].strip()
    return (replacement + (m.group("tail") or "") + (m.group("pipe") or ""),
            f"requested: {requested}; executed: {replacement}; scope: same tests (pytest is not installed; "
            f"this repository uses unittest)")


def repair(command: str, cwd: str, prof: dict) -> tuple[str, list[str]]:
    """The command with known-equivalent repairs applied, and one receipt per repair. Only fully parsed forms are
    repaired; anything else (wrappers, other interpreters' flags, compound commands) passes through unchanged."""
    receipts = []
    for fix in ((lambda c: _repair_sed(c)) if _bsd_sed() else (lambda c: None),
                lambda c: _repair_pytest(c, cwd, prof)):
        result = fix(command)
        if result:
            command, receipt = result
            receipts.append(receipt)
    return command, receipts


def _b64(text: str) -> str:
    import base64
    return base64.urlsafe_b64encode(text.encode()).decode()


def rewrite(command: str, root: str, state_file: Path, levers: set[str], cwd: str | None = None) -> str | None:
    prof = ai_orch_profile.detect(root)
    requested = command
    receipts: list[str] = []
    if "all" in levers or "repair" in levers:
        command, receipts = repair(command, cwd or root, prof)
    note = "".join(f"echo {shlex.quote('[ai-orch] ' + r)} >&2; " for r in receipts)
    if not session.is_verify(command):
        reads = rewrite_reads(command) if ("all" in levers or "readguard" in levers) else None
        return (note + (reads or command)) if receipts else reads
    new = command
    fmt = prof.get("format")
    # also on a clean tree: agents often edit and check in one command; on unchanged files the formatter is a no-op
    if fmt and ("all" in levers or "format" in levers):
        goal = prof.get("format_goal")
        if goal and MAVEN.search(command):
            if goal not in command:
                new = MAVEN.sub(lambda m: f"{m.group(1)}{m.group(2)} {goal}", command, count=1)
        elif fmt not in command:
            new = (f"{{ {fmt} ; }} >/dev/null 2>&1 || echo \"[ai-orch] the formatter failed: {fmt}\" >&2\n{command}")
    here = Path(__file__).resolve().parent
    me = shlex.quote(str(here / "ai_orch_command_hook.py"))
    covers = session.coverage(command)
    kinds = ",".join(sorted(covers)) or "none"
    target = f"{shlex.quote(str(state_file))} {shlex.quote(root)}"
    compacted = "all" in levers or "compact" in levers
    # one call records the coverage the check proved (verdict 0: all it covers; 3 = passed without test evidence: all
    # but test coverage) and the request's receipt: requested and executed command, real status, scopes, evidence
    record = (f"python3 {me} --record {target} $__aio_ok $__aio_rc $__aio_t0 {kinds} {int(compacted)} "
              f"{_b64(requested)} {_b64(command)} {_b64(cwd or root)} >/dev/null 2>&1; (exit $__aio_rc)")
    # A subshell, so an `exit` inside the agent's command ends only the command, not the compaction and the record
    # after it; a `cd` inside it holds for the rest of the command.
    if "all" in levers or "compact" in levers:  # output to a log; the agent sees a compact summary of it
        compact = shlex.quote(str(here / "ai_orch_compact.py"))
        return (f"{note}__aio_t0=$(date +%s); __aio_log=$(mktemp \"${{TMPDIR:-/tmp}}/ai-orch-check.XXXXXX\")\n( {new}\n) < /dev/null > \"$__aio_log\" 2>&1; "
                f"__aio_rc=$?; python3 {compact} \"$__aio_log\" $__aio_rc; __aio_ok=$?; {record}")
    return f"{note}__aio_t0=$(date +%s); ( {new}\n); __aio_rc=$?; __aio_ok=$__aio_rc; {record}"


def record(argv: list[str]) -> int:
    """`--record <state> <root> <verdict> <rc> <t0> <kinds> <compacted> <b64 requested> <b64 executed> <b64 cwd>`."""
    import base64
    state_file, root, verdict, rc, t0, kinds, compacted = argv[0], argv[1], int(argv[2]), int(argv[3]), argv[4], \
        argv[5], argv[6] == "1"
    requested, executed, cwd = (base64.urlsafe_b64decode(a.encode()).decode() for a in argv[7:10])
    state = session.load(Path(state_file))
    fp = session.fingerprint(root)
    covers = [k for k in kinds.split(",") if k and k != "none"]
    since = float(t0) - 1 if t0 else None
    if not compacted:  # no output to inspect: tests count only when fresh runner reports show they ran
        report_evidence = session.test_evidence("", root, since)
        verdict = (0 if report_evidence == "ran" else 3) if verdict == 0 else 1
    if verdict == 0:
        proved = session.attributable(covers, root, since)
    elif verdict == 3:
        proved = {k for k in covers if not k.startswith("tests")}
    else:
        proved = set()
    verified = state.setdefault("verified", {})
    for kind in proved:
        verified[kind] = fp
    evidence = "ran" if verdict == 0 else ("none-or-unknown" if compacted else "unknown") if verdict == 3 else "failed"
    receipt = {"command": executed[:2000], "status": "passed" if rc == 0 and verdict in (0, 3) else "failed",
               "scope": ",".join(sorted(proved))[:500], "testEvidence": evidence, "cwd": cwd,
               "tree": session.snapshot_tree(root) or fp}
    if requested != executed:
        receipt["requested"] = requested[:2000]
    session.add_receipt(state, receipt)
    session.save(Path(state_file), state)
    return 0


def main(argv: list[str]) -> int:
    if argv[:1] == ["--record"] and len(argv) >= 11:
        return record(argv[1:])
    if argv[:1] == ["--verified"] and len(argv) >= 3:
        state_file = Path(argv[1])
        state = session.load(state_file)
        fp = session.fingerprint(argv[2])
        verified = state.setdefault("verified", {})
        kinds = [k for k in (argv[3] if len(argv) > 3 else "").split(",") if k and k != "none"]
        since = float(argv[4]) - 1 if len(argv) > 4 else None
        for kind in session.attributable(kinds, argv[2], since):
            verified[kind] = fp
        session.save(state_file, state)
        return 0
    import os
    levers = {x.strip() for x in os.environ.get("AI_ORCH_LEVERS", "all").split(",")}
    try:
        event = json.load(sys.stdin)
        tool_input = event.get("tool_input") or {}
        command = tool_input.get("command")
        root = str(event.get("cwd") or "")
        if not isinstance(command, str) or not root.startswith("/"):
            return 0
        refused = memory_gate.pre(event)  # `ai_orch memory search` after memory had nothing / the same query
        if refused:
            print(json.dumps(memory_gate.deny(refused)))
            return 0
        cwd = root
        root = session.repo_root(root)
        state_file = session.state_path(str(event.get("session_id") or ""), root)
        new = rewrite(command, root, state_file, levers, cwd)
    except (OSError, ValueError, AttributeError, TypeError):
        return 0
    if new is None:
        return 0
    output = {"hookEventName": "PreToolUse", "updatedInput": dict(tool_input, command=new)}
    if "--client" in argv and "codex" in argv:
        output["permissionDecision"] = "allow"  # Codex applies updatedInput only together with "allow"
    print(json.dumps({"hookSpecificOutput": output}))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
