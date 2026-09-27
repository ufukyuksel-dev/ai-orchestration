import importlib.util
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
spec = importlib.util.spec_from_file_location("codex_mcp_config", ROOT / "scripts/codex_mcp_config.py")
cfg = importlib.util.module_from_spec(spec)
spec.loader.exec_module(cfg)

URL = "http://127.0.0.1:18080/mcp"
OTHER = '[profiles.fast]\nmodel = "gpt-5"\n\n[mcp_servers.github]\nurl = "https://api.github.com/mcp"\n'


class CodexMcpConfigTest(unittest.TestCase):
    def setUp(self):
        self.dir = Path(tempfile.mkdtemp())
        self.path = self.dir / "config.toml"

    def backups(self):
        return sorted(p.name for p in self.dir.glob("config.toml.bak-*"))

    def test_writes_once_and_rerun_changes_nothing(self):
        self.path.write_text(OTHER)
        self.assertEqual(cfg.main(["set", str(self.path), URL]), 0)
        first = self.path.read_text()
        self.assertIn(OTHER.strip(), first)
        self.assertIn('url = "http://127.0.0.1:18080/mcp"', first)
        self.assertIn('"X-AI-Orch-Client" = "codex"', first)
        self.assertEqual(cfg.main(["set", str(self.path), URL]), 0)
        self.assertEqual(self.path.read_text(), first)
        self.assertEqual(len(self.backups()), 1)  # only the first write backed the file up

    def test_an_old_local_entry_is_brought_up_to_date_and_other_tables_survive(self):
        self.path.write_text('[mcp_servers.ai-orchestration]\nurl = "http://localhost:18084/mcp"\nrequired = true\n\n'
                             '[mcp_servers.ai-orchestration.env]\nX = "1"\n\n' + OTHER)
        self.assertEqual(cfg.main(["set", str(self.path), URL]), 0)
        text = self.path.read_text()
        self.assertEqual(text.count("[mcp_servers.ai-orchestration"), 1)
        self.assertNotIn("18084", text)
        self.assertIn("[mcp_servers.github]", text)
        self.assertIn("[profiles.fast]", text)

    def test_a_foreign_server_under_our_name_is_never_taken_over(self):
        foreign = '[mcp_servers.ai-orchestration]\nurl = "https://team.example.com/mcp"\n'
        self.path.write_text(foreign)
        self.assertEqual(cfg.main(["set", str(self.path), URL]), 3)
        self.assertEqual(self.path.read_text(), foreign)

    def test_remove_takes_only_the_table_the_installer_wrote(self):
        self.path.write_text(OTHER)
        cfg.main(["set", str(self.path), URL])
        cfg.main(["remove", str(self.path)])
        self.assertEqual(self.path.read_text().strip(), OTHER.strip())
        hand_written = '[mcp_servers.ai-orchestration]\nurl = "http://127.0.0.1:18080/mcp"\n'
        self.path.write_text(hand_written)
        cfg.main(["remove", str(self.path)])
        self.assertEqual(self.path.read_text(), hand_written)


if __name__ == "__main__":
    unittest.main()
