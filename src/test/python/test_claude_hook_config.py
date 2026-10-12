import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[3] / "scripts/claude_hook_config.py"
CMD = "AI_ORCH_URL=http://127.0.0.1:18080 python3 /opt/ai/scripts/hooks/ai_orch_prompt_context.py"
STOP = "python3 /opt/ai/scripts/hooks/ai_orch_learn_reminder.py"


def run(*args):
    return subprocess.run([sys.executable, str(SCRIPT), *args], capture_output=True, text=True)


class ClaudeHookConfigTest(unittest.TestCase):
    def test_set_is_idempotent_keeps_other_settings_and_remove_restores_them(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "settings.json"
            original = {"model": "opus", "hooks": {"UserPromptSubmit": [{"hooks": [{"type": "command", "command": "mine.sh"}]}],
                                                   "Stop": [{"hooks": [{"type": "command", "command": "stop.sh"}]}]}}
            path.write_text(json.dumps(original))
            self.assertEqual(0, run("set", str(path), CMD, STOP).returncode)
            self.assertEqual(0, run("set", str(path), CMD, STOP).returncode)
            data = json.loads(path.read_text())
            commands = [h["command"] for g in data["hooks"]["UserPromptSubmit"] for h in g["hooks"]]
            self.assertEqual(["mine.sh", CMD], commands)
            self.assertEqual(["stop.sh", STOP], [h["command"] for g in data["hooks"]["Stop"] for h in g["hooks"]])
            self.assertEqual("opus", data["model"])
            self.assertEqual(0, run("remove", str(path)).returncode)
            self.assertEqual(original, json.loads(path.read_text()))

    def test_missing_file_is_created_and_invalid_json_is_left_alone(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "new" / "settings.json"
            self.assertEqual(0, run("set", str(path), CMD).returncode)
            self.assertIn("ai_orch_prompt_context.py", path.read_text())
            broken = Path(tmp) / "broken.json"
            broken.write_text("{not json")
            self.assertEqual(1, run("set", str(broken), CMD).returncode)
            self.assertEqual("{not json", broken.read_text())
            self.assertEqual(2, run("set", str(path), "some-other-hook.sh").returncode)


if __name__ == "__main__":
    unittest.main()


class TokenLeverHooksTest(unittest.TestCase):
    def test_command_and_stop_hooks_get_their_event_matcher_timeout_and_lean_is_reversible(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "settings.json"
            path.write_text(json.dumps({"permissions": {"deny": ["Bash(rm -rf:*)", "NotebookEdit"]}}))
            commands = ["python3 /opt/ai/scripts/hooks/ai_orch_prompt_context.py",
                        "python3 /opt/ai/scripts/hooks/ai_orch_command_hook.py",
                        "python3 /opt/ai/scripts/hooks/ai_orch_stop.py"]
            self.assertEqual(0, run("set", str(path), *commands, "--lean").returncode)
            data = json.loads(path.read_text())
            self.assertEqual("Bash", data["hooks"]["PreToolUse"][0]["matcher"])
            self.assertEqual(960, data["hooks"]["Stop"][0]["hooks"][0]["timeout"])
            self.assertIn("Workflow", data["permissions"]["deny"])
            self.assertEqual(1, data["permissions"]["deny"].count("NotebookEdit"))
            self.assertEqual(0, run("remove", str(path)).returncode)
            self.assertEqual({"permissions": {"deny": ["Bash(rm -rf:*)"]}}, json.loads(path.read_text()))


class MemoryGateHookTest(unittest.TestCase):
    def test_the_memory_gate_sits_on_pre_and_post_for_the_memory_search_tools_and_is_removed_again(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "settings.json"
            gate = "python3 /opt/ai/scripts/hooks/ai_orch_memory_gate.py"
            self.assertEqual(0, run("set", str(path), gate + " pre", gate + " post").returncode)
            data = json.loads(path.read_text())
            for event, arg in (("PreToolUse", "pre"), ("PostToolUse", "post")):
                [group] = data["hooks"][event]
                self.assertEqual("mcp__ai-orchestration__memory_search|mcp__ai-orchestration__extras",
                                 group["matcher"])
                self.assertEqual(f"{gate} {arg}", group["hooks"][0]["command"])
            self.assertEqual(0, run("remove", str(path)).returncode)
            self.assertEqual({}, json.loads(path.read_text()))
