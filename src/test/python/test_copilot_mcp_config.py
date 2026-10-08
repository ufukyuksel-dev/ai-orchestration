import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
spec = importlib.util.spec_from_file_location("copilot_mcp_config", ROOT / "scripts/copilot_mcp_config.py")
cfg = importlib.util.module_from_spec(spec)
spec.loader.exec_module(cfg)

URL = "http://127.0.0.1:18080/mcp"
OTHER = {"github": {"type": "http", "url": "https://api.github.com/mcp"}}


class CopilotMcpConfigTest(unittest.TestCase):
    def setUp(self):
        self.dir = Path(tempfile.mkdtemp())
        self.path = self.dir / "mcp-config.json"

    def servers(self):
        return json.loads(self.path.read_text())["mcpServers"]

    def test_writes_once_keeps_other_servers_and_rerun_changes_nothing(self):
        self.path.write_text(json.dumps({"mcpServers": OTHER}))
        self.assertEqual(cfg.main(["set", str(self.path), URL]), 0)
        first = self.path.read_text()
        self.assertEqual(self.servers()["github"], OTHER["github"])
        self.assertEqual(self.servers()["ai-orchestration"],
                         {"type": "http", "url": URL, "headers": {"X-AI-Orch-Client": "copilot"}, "tools": ["*"]})
        backups = sorted(self.dir.glob("mcp-config.json.bak-*"))
        self.assertEqual(cfg.main(["set", str(self.path), URL]), 0)
        self.assertEqual(self.path.read_text(), first)
        self.assertEqual(sorted(self.dir.glob("mcp-config.json.bak-*")), backups)

    def test_a_remote_server_under_our_name_is_left_alone(self):
        remote = {"mcpServers": {"ai-orchestration": {"type": "http", "url": "https://remote.example/mcp"}}}
        self.path.write_text(json.dumps(remote))
        self.assertEqual(cfg.main(["set", str(self.path), URL]), 3)
        self.assertEqual(cfg.main(["remove", str(self.path)]), 0)
        self.assertEqual(json.loads(self.path.read_text()), remote)

    def test_remove_drops_only_our_local_entry(self):
        self.assertEqual(cfg.main(["set", str(self.path), URL]), 0)
        self.assertEqual(cfg.main(["remove", str(self.path)]), 0)
        self.assertEqual(self.servers(), {})

    def test_invalid_json_is_reported_not_overwritten(self):
        self.path.write_text("{not json")
        self.assertEqual(cfg.main(["set", str(self.path), URL]), 4)
        self.assertEqual(self.path.read_text(), "{not json")


if __name__ == "__main__":
    unittest.main()
