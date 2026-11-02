"""Test-wide setup: the Copilot hooks' local trace (copilot_trace) writes into a throw-away directory, never into the
user's `~/.copilot/ai-orch/trace` (hook subprocesses inherit the environment)."""
import pytest


@pytest.fixture(autouse=True)
def _trace_dir(tmp_path_factory, monkeypatch):
    monkeypatch.setenv("AI_ORCH_TRACE_DIR", str(tmp_path_factory.mktemp("ai-orch-trace")))
