#!/usr/bin/env python3
"""Launcher and upgrade-path tests for the Copilot installer (isolated temporary HOME/bin)."""

from __future__ import annotations

import json
import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import install_user_integration as installer

RELATIVE_SKILLS = ("skills", "copilot")


class UserIntegrationInstallerTest(unittest.TestCase):
    def setUp(self) -> None:
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.base = Path(tmp.name).resolve()
        self.copilot_home = self.base / "copilot"
        self.bin_dir = self.base / "bin"
        patcher = patch.dict(os.environ, {"AI_ORCH_STAGING_DIR": str(self.base / "staging")})
        patcher.start()
        self.addCleanup(patcher.stop)

    def old_checkout(self) -> Path:
        """A former checkout of this project, recognised by its session workflow file."""
        old = self.base / "old-checkout"
        (old / "skills").mkdir(parents=True)
        (old / "skills" / "session-instructions.md").write_text("old")
        return old

    def test_installs_both_exact_launchers_then_checks(self) -> None:
        mcp_config, memory_launcher, ai_orch_launcher = installer.install(self.copilot_home, self.bin_dir)
        memory_bridge, ai_orch = installer.integration_paths()
        self.assertTrue(memory_launcher.is_symlink())
        self.assertEqual(memory_launcher.resolve(), memory_bridge)
        self.assertTrue(ai_orch_launcher.is_symlink())
        self.assertEqual(ai_orch_launcher.resolve(), ai_orch)
        self.assertEqual(
            installer.install(self.copilot_home, self.bin_dir, check=True),
            (mcp_config, memory_launcher, ai_orch_launcher),
        )

    def test_refuses_to_replace_an_existing_launcher_file(self) -> None:
        for launcher_name in (installer.LAUNCHER_NAME, installer.AI_ORCH_LAUNCHER_NAME):
            with self.subTest(launcher_name=launcher_name), tempfile.TemporaryDirectory() as directory:
                base = Path(directory)
                bin_dir = base / "bin"
                bin_dir.mkdir()
                (bin_dir / launcher_name).write_text("user-owned")
                with self.assertRaises(installer.InstallError):
                    installer.install(base / "copilot", bin_dir)
                self.assertFalse((base / "copilot" / "mcp-config.json").exists())

    def test_keeps_the_ai_orch_command_written_by_install_sh(self) -> None:
        self.bin_dir.mkdir()
        shim = self.bin_dir / installer.AI_ORCH_LAUNCHER_NAME
        shim.write_text("#!/usr/bin/env bash\n# AI Orchestration command (installed by /repo/install.sh)\n")
        installer.install(self.copilot_home, self.bin_dir)
        self.assertFalse(shim.is_symlink())
        self.assertIn("installed by", shim.read_text())
        installer.install(self.copilot_home, self.bin_dir, check=True)
        manifest = json.loads((self.copilot_home / installer.MANIFEST_FILENAME).read_text())
        self.assertNotIn(str(shim), manifest["launchers"])

    def test_removes_the_retired_hook_of_any_checkout_and_keeps_a_foreign_one(self) -> None:
        hook = self.copilot_home / "hooks" / installer.HOOK_FILENAME
        hook.parent.mkdir(parents=True)
        legacy = {
            "version": 1,
            "hooks": {"preToolUse": [{"type": "command", "timeoutSec": 5, "bash": str(
                self.base / "elsewhere" / "skills/copilot/ai-orchestration-memory/scripts/copilot_pre_tool_guard.py")}]},
        }
        hook.write_text(json.dumps(legacy))
        installer.install(self.copilot_home, self.bin_dir)
        self.assertFalse(hook.exists())

        foreign = '{"version":1,"hooks":{"preToolUse":[]}}'
        hook.write_text(foreign)
        installer.install(self.copilot_home, self.bin_dir)
        self.assertEqual(hook.read_text(), foreign)

    def test_relinks_skills_and_launchers_from_another_checkout(self) -> None:
        old = self.old_checkout()
        repo = installer.repository_root()
        skills_dir = self.copilot_home / "skills"
        skills_dir.mkdir(parents=True)
        source = installer.skill_sources()[0]
        old_skill = old / source.relative_to(repo)
        (skills_dir / source.name).symlink_to(old_skill)
        memory_bridge, _ = installer.integration_paths()
        self.bin_dir.mkdir()
        (self.bin_dir / installer.LAUNCHER_NAME).symlink_to(old / memory_bridge.relative_to(repo))

        installer.install(self.copilot_home, self.bin_dir)

        self.assertEqual((skills_dir / source.name).resolve(), source)
        self.assertEqual((self.bin_dir / installer.LAUNCHER_NAME).resolve(), memory_bridge)
        installer.install(self.copilot_home, self.bin_dir, check=True)

    def test_a_symlink_into_an_unrelated_directory_is_still_refused(self) -> None:
        skills_dir = self.copilot_home / "skills"
        skills_dir.mkdir(parents=True)
        source = installer.skill_sources()[0]
        unrelated = self.base / "unrelated" / "skills" / "copilot" / source.name
        unrelated.mkdir(parents=True)
        (skills_dir / source.name).symlink_to(unrelated)
        with self.assertRaises(installer.InstallError):
            installer.install(self.copilot_home, self.bin_dir)

    def test_removes_another_checkouts_skill_directory_from_settings(self) -> None:
        old = self.old_checkout()
        self.copilot_home.mkdir(parents=True)
        settings = self.copilot_home / "settings.json"
        mine = str(self.base / "my-own-skills")
        settings.write_text(json.dumps({
            "model": "gpt-5.5",
            "skillDirectories": [str(old / "skills" / "copilot"), mine],
        }))
        with self.assertRaises(installer.InstallError):
            installer.install(self.copilot_home, self.bin_dir, check=True)
        installer.install(self.copilot_home, self.bin_dir)
        data = json.loads(settings.read_text())
        self.assertEqual(data, {"model": "gpt-5.5", "skillDirectories": [mine]})
        installer.install(self.copilot_home, self.bin_dir, check=True)


if __name__ == "__main__":
    unittest.main()
