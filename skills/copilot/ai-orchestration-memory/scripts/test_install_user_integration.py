#!/usr/bin/env python3

from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

import install_user_integration as installer


class UserIntegrationInstallerTest(unittest.TestCase):
    def test_installs_private_hook_and_both_exact_launchers_then_checks(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            copilot_home = base / "copilot"
            bin_dir = base / "bin"
            hook, memory_launcher, ai_orch_launcher = installer.install(copilot_home, bin_dir)
            guard, memory_bridge, ai_orch = installer.integration_paths()

            self.assertEqual(json.loads(hook.read_text()), installer.expected_hook(guard))
            self.assertEqual(hook.stat().st_mode & 0o777, 0o600)
            self.assertTrue(memory_launcher.is_symlink())
            self.assertEqual(memory_launcher.resolve(), memory_bridge)
            self.assertTrue(ai_orch_launcher.is_symlink())
            self.assertEqual(ai_orch_launcher.resolve(), ai_orch)
            self.assertEqual(
                installer.install(copilot_home, bin_dir, check=True),
                (hook, memory_launcher, ai_orch_launcher),
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
                self.assertFalse(
                    (base / "copilot" / "hooks" / installer.HOOK_FILENAME).exists()
                )
                other_launcher = (
                    installer.AI_ORCH_LAUNCHER_NAME
                    if launcher_name == installer.LAUNCHER_NAME
                    else installer.LAUNCHER_NAME
                )
                self.assertFalse((bin_dir / other_launcher).exists())

    def test_refuses_to_replace_a_foreign_hook(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            hook_dir = base / "copilot" / "hooks"
            hook_dir.mkdir(parents=True)
            hook = hook_dir / installer.HOOK_FILENAME
            hook.write_text('{"version":1,"hooks":{"preToolUse":[]}}')
            with self.assertRaises(installer.InstallError):
                installer.install(base / "copilot", base / "bin")
            self.assertEqual(hook.read_text(), '{"version":1,"hooks":{"preToolUse":[]}}')

    def test_refuses_same_basename_from_a_foreign_checkout(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            hook_dir = base / "copilot" / "hooks"
            hook_dir.mkdir(parents=True)
            hook = hook_dir / installer.HOOK_FILENAME
            foreign = installer.expected_hook(Path("/tmp/copilot_pre_tool_guard.py"))
            hook.write_text(json.dumps(foreign))
            with self.assertRaises(installer.InstallError):
                installer.install(base / "copilot", base / "bin")
            self.assertEqual(json.loads(hook.read_text()), foreign)


if __name__ == "__main__":
    unittest.main()
