#!/usr/bin/env python3
"""Compact output of a build/test command for the agent's context.

The command hook runs a check with its output going to a log file, then calls this script. A short output is printed
unchanged. A long one is reduced: on success to the summary lines (test totals, BUILD SUCCESS, pass lines); on failure
to the lines that say what failed (compiler errors, failing tests, violations) plus the last lines. Either way the
path of the full log is printed, so the agent can read more of it when the summary is not enough.

Usage: ai_orch_compact.py <log> <exit-code>
Exit status: 0 the check passed and tests ran; 3 it passed without evidence that any test ran; 1 it failed.
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import ai_orch_session_state as session  # noqa: E402

KEEP_SHORT_LINES = 25
KEEP_SHORT_CHARS = 2500
SUMMARY = re.compile(r"Tests run:|BUILD SUCCESS|BUILD FAILURE|^ok\s|^PASS|^FAIL|\b\d+ passed\b|\b\d+ failed\b|"
                     r"Test Files|Tests\s+\d+|^\s*✓|Total time", re.M)


def surefire_totals(since: float | None) -> list[str]:
    """Maven in quiet mode prints no test totals; the reports it just wrote have them."""
    if since is None:
        return []
    totals = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0}
    found = False
    for report in Path("target/surefire-reports").glob("TEST-*.xml"):
        try:
            if report.stat().st_mtime < since:
                continue
            head = report.read_text(encoding="utf-8", errors="replace")[:2000]
        except OSError:
            continue
        suite = re.search(r"<testsuite\b[^>]*>", head)
        for key in totals if suite else ():
            value = re.search(rf'\b{key}="(\d+)"', suite.group(0))
            totals[key] += int(value.group(1)) if value else 0
        found = found or bool(suite)
    if not found:
        return []
    return [f"Tests run: {totals['tests']}, Failures: {totals['failures']}, Errors: {totals['errors']}, "
            f"Skipped: {totals['skipped']}"]


def passed(text: str, code: int) -> bool:
    """A check passed when it exited 0 and its output reports no failure: agents often pipe a build into `tail`,
    which hides the build's exit code."""
    import ai_orch_stop
    return code == 0 and not ai_orch_stop.FAILED.search(text)


def compact(text: str, code: int, since: float | None = None) -> str:
    lines = text.splitlines()
    if len(lines) <= KEEP_SHORT_LINES and len(text) <= KEEP_SHORT_CHARS:
        return text
    if passed(text, code):
        keep = [line for line in lines if SUMMARY.search(line)]
        totals = [line for line in keep if re.search(r"Tests run:.*Time elapsed", line) is None]
        body = (totals or keep)[-12:] or surefire_totals(since)
        return "\n".join(body + [f"[ai-orch] passed; {len(lines)} output lines compacted"])
    import ai_orch_stop  # the same error-line selection the Stop hook uses
    errors = ai_orch_stop.first_errors(text, 40)
    tail = [line for line in lines[-8:] if line not in errors and not ai_orch_stop.NOISE.search(line.strip())
            and (not line.startswith("[INFO]") or SUMMARY.search(line))]
    status = f"exit {code}" if code else "exit 0, but the output reports failures"
    return "\n".join(errors + ["..."] + tail + [f"[ai-orch] failed ({status}); {len(lines)} output lines compacted"])


def main(argv: list[str]) -> int:
    log, code = Path(argv[0]), int(argv[1])
    try:
        text = log.read_text(encoding="utf-8", errors="replace")
    except OSError:
        return code
    try:
        since = log.stat().st_ctime - 1  # the log was created when the check started
    except OSError:
        since = None
    out = compact(text, code, since)
    print(out)
    if out != text:
        print(f"[ai-orch] full log: {log}")
    if not passed(text, code):
        return 1
    # passed: 0 when tests demonstrably ran, 3 when the check passed but ran no tests (zero, all skipped) or gives no
    # evidence of running any (quiet output, no fresh reports): then test coverage is not recorded
    return 0 if session.test_evidence(text, str(Path.cwd()), since) == "ran" else 3


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
