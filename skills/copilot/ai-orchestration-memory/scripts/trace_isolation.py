"""Tests never write the user's real Copilot trace (`${COPILOT_HOME:-~/.copilot}/ai-orch/trace`).

Imported by every test entry: the pytest conftest of each test root and, for a module run with unittest (no conftest
then), the test modules that run the Copilot hooks. On import, when `AI_ORCH_TRACE_DIR` is not set, it points the trace
at a throw-away directory for the whole process (hook subprocesses inherit it); the trace module itself never guesses
whether it runs under a test. It also keeps a snapshot of the real trace directory taken at that moment, so a test
run can check at its end that the real trace did not change (`changed`).
"""
from __future__ import annotations

import atexit
import os
import shutil
import tempfile
from pathlib import Path

ENV = "AI_ORCH_TRACE_DIR"


def real_directory() -> Path:
    """The real trace directory, as the trace module finds it without AI_ORCH_TRACE_DIR."""
    home = os.environ.get("COPILOT_HOME", "").strip()
    return (Path(home).expanduser() if home else Path.home() / ".copilot") / "ai-orch" / "trace"


def snapshot(directory: Path) -> dict[str, tuple[int, int]]:
    """{file name: (size, mtime_ns)} of the directory's files; empty when it does not exist."""
    try:
        return {entry.name: (entry.stat().st_size, entry.stat().st_mtime_ns)
                for entry in directory.iterdir() if entry.is_file()}
    except OSError:
        return {}


def isolate() -> str:
    """Point the trace at a throw-away directory unless the environment already chose one; the directory in use."""
    chosen = os.environ.get(ENV, "").strip()
    if chosen:
        return chosen
    folder = tempfile.mkdtemp(prefix="ai-orch-trace-test-")
    atexit.register(shutil.rmtree, folder, True)
    os.environ[ENV] = folder
    return folder


REAL = real_directory()
BEFORE = snapshot(REAL)
isolate()


def changed(directory: Path = REAL, before: dict[str, tuple[int, int]] | None = None) -> list[str]:
    """The real trace's files that are new or changed since this module was imported."""
    before = BEFORE if before is None else before
    now = snapshot(directory)
    return sorted(name for name, stat in now.items() if before.get(name) != stat)


# ------------------------------------------------------------------------------------- pytest: used by the conftests
try:
    import pytest
except ImportError:  # a unittest run without pytest: the import-time isolation above is enough
    pytest = None

_checked: list[bool] = []

if pytest is not None:
    @pytest.fixture(autouse=True)
    def ai_orch_trace_dir(tmp_path_factory, monkeypatch):
        """Each test its own trace directory."""
        monkeypatch.setenv(ENV, str(tmp_path_factory.mktemp("ai-orch-trace")))

    def pytest_sessionfinish(session, exitstatus):
        """The guard: the run fails when the user's real trace changed while it ran (once, whichever conftest)."""
        if _checked:
            return
        _checked.append(True)
        touched = changed()
        if touched:
            print(f"\nai-orch trace guard: tests changed the real trace in {REAL}: {', '.join(touched)} "
                  "(a test ran a Copilot hook without AI_ORCH_TRACE_DIR; or a real Copilot session wrote meanwhile)")
            session.exitstatus = pytest.ExitCode.TESTS_FAILED

