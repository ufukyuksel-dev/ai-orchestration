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
