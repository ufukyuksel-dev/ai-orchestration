#!/usr/bin/env python3
"""Tests never write the user's real Copilot trace: every test entry (the conftest of each test root, and the hook test
modules for a unittest run) points AI_ORCH_TRACE_DIR at a throw-away directory, and the pytest run fails when the real
trace changed while it ran (trace_isolation)."""

from __future__ import annotations

import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import trace_isolation

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[3]


class IsolationTest(unittest.TestCase):
    def test_this_process_traces_away_from_the_real_directory(self) -> None:
        chosen = Path(os.environ["AI_ORCH_TRACE_DIR"]).resolve()
        real = trace_isolation.real_directory().resolve()
        self.assertNotEqual(chosen, real)
        self.assertFalse(str(chosen).startswith(str(real.parent.parent)))  # not under ~/.copilot at all

    def test_a_directory_chosen_by_the_environment_is_kept(self) -> None:
        with patch.dict(os.environ, {"AI_ORCH_TRACE_DIR": "/some/where"}):
            self.assertEqual(trace_isolation.isolate(), "/some/where")
        with patch.dict(os.environ, {"AI_ORCH_TRACE_DIR": ""}):
            chosen = trace_isolation.isolate()
            self.assertEqual(os.environ["AI_ORCH_TRACE_DIR"], chosen)
            self.assertTrue(Path(chosen).is_dir())

    def test_changed_sees_a_new_and_a_grown_file(self) -> None:
        with tempfile.TemporaryDirectory() as folder:
            day = Path(folder) / "2026-11-02.jsonl"
            day.write_text("a\n")
            before = trace_isolation.snapshot(Path(folder))
            self.assertEqual(trace_isolation.changed(Path(folder), before), [])
            with day.open("a") as stream:
                stream.write("b\n")
            (Path(folder) / "2026-11-03.jsonl").write_text("c\n")
            self.assertEqual(trace_isolation.changed(Path(folder), before), ["2026-11-02.jsonl", "2026-11-03.jsonl"])
        self.assertEqual(trace_isolation.snapshot(Path(folder)), {})  # gone: nothing to compare

    def test_every_test_root_has_the_conftest(self) -> None:
        for root in ("", "skills", "scripts", "src/test/python"):
            with self.subTest(root=root):
                text = (REPO / root / "conftest.py").read_text()
                self.assertIn("trace_isolation", text)
                self.assertIn("pytest_sessionfinish", text)

    def test_a_unittest_run_of_a_hook_module_from_elsewhere_leaves_the_real_trace_alone(self) -> None:
        """No conftest in a unittest run started from another folder: the module's own import isolates it."""
        with tempfile.TemporaryDirectory() as home:
            env = {k: v for k, v in os.environ.items() if k != "AI_ORCH_TRACE_DIR"}
            env.update(COPILOT_HOME=home, PYTHONPATH=str(HERE))
            run = subprocess.run([sys.executable, "-m", "unittest", "-q",
                                  "test_ai_orch_commands", "test_copilot_memory_flow"],
                                 cwd=home, env=env, capture_output=True, text=True, timeout=300)
            self.assertEqual(run.returncode, 0, run.stderr[-2000:])
            self.assertFalse((Path(home) / "ai-orch").exists())


if __name__ == "__main__":
    unittest.main()
