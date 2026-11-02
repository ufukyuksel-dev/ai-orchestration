"""Test-wide setup: the Copilot hooks' local trace (copilot_trace) writes into a throw-away directory, never into the
user's `~/.copilot/ai-orch/trace` (hook subprocesses inherit the environment), and the run fails if the real trace
changed while it ran. The setup lives in skills/copilot/ai-orchestration-memory/scripts/trace_isolation.py, shared by
the conftest of every test root (pytest loads only those at or below its root directory) and by the test modules
themselves for a unittest run."""
import importlib.util
import sys
from pathlib import Path


def _trace_isolation():
    if "trace_isolation" not in sys.modules:
        path = Path(__file__).resolve().parents[0] / "skills/copilot/ai-orchestration-memory/scripts/trace_isolation.py"
        spec = importlib.util.spec_from_file_location("trace_isolation", path)
        module = importlib.util.module_from_spec(spec)
        sys.modules["trace_isolation"] = module
        spec.loader.exec_module(module)
    return sys.modules["trace_isolation"]


ai_orch_trace_dir = _trace_isolation().ai_orch_trace_dir
pytest_sessionfinish = _trace_isolation().pytest_sessionfinish
