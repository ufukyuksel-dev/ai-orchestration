import importlib.util
import io
import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

ROOT = Path(__file__).resolve().parents[3]
SPEC = importlib.util.spec_from_file_location("ai_orch_learn_reminder", ROOT / "scripts/hooks/ai_orch_learn_reminder.py")
hook = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(hook)


def prompt(text):
    return {"type": "user", "message": {"role": "user", "content": text}}


def tool(name):
    return {"type": "assistant", "message": {"content": [{"type": "tool_use", "name": name, "input": {}}]}}


def result():
    return {"type": "user", "message": {"content": [{"type": "tool_result", "content": "ok"}]}}


class LearnReminderHookTest(unittest.TestCase):
    def run_hook(self, entries, **event):
        with tempfile.NamedTemporaryFile("w", suffix=".jsonl", delete=False) as f:
            f.write("\n".join(json.dumps(e) for e in entries) + "\n{broken line\n")
        out = io.StringIO()
        payload = {"transcript_path": f.name, "stop_hook_active": False, **event}
        with mock.patch.object(sys, "stdin", io.StringIO(json.dumps(payload))), mock.patch.object(sys, "stdout", out):
            self.assertEqual(0, hook.main())
        return out.getvalue()

    def test_a_task_that_changed_files_without_learning_is_asked_once(self):
        text = self.run_hook([prompt("add a csv export"), tool("Read"), result(), tool("Edit"), result()])
        answer = json.loads(text)
        self.assertEqual("block", answer["decision"])
        self.assertIn("mcp__ai-orchestration__memory_learn", answer["reason"])
        # the stop after the reminder goes through, whatever the agent decided
        self.assertEqual("", self.run_hook([prompt("x"), tool("Edit")], stop_hook_active=True))

    def test_learned_chat_or_earlier_edits_stop_normally(self):
        self.assertEqual("", self.run_hook([prompt("task"), tool("Edit"), tool("mcp__ai-orchestration__memory_learn")]))
        self.assertEqual("", self.run_hook([prompt("what does this class do?"), tool("Read"), result()]))
        # edits belong to an earlier request; this one was a question
        self.assertEqual("", self.run_hook([prompt("task"), tool("Edit"), result(), prompt("thanks, why?")]))

    def test_missing_transcript_never_blocks(self):
        out = io.StringIO()
        with mock.patch.object(sys, "stdin", io.StringIO(json.dumps({"transcript_path": "/nonexistent.jsonl"}))), \
                mock.patch.object(sys, "stdout", out):
            self.assertEqual(0, hook.main())
        self.assertEqual("", out.getvalue())


if __name__ == "__main__":
    unittest.main()
