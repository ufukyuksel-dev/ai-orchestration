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

    def test_the_hook_resets_the_memory_gate_per_prompt_and_replaces_an_earlier_guard_only_hook(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            guard, _, _ = installer.integration_paths()
            hook = base / "copilot" / "hooks" / installer.HOOK_FILENAME
            hook.parent.mkdir(parents=True)
            hook.write_text(json.dumps(installer.legacy_hook(guard)))
            installer.install(base / "copilot", base / "bin")
            hook.write_text(json.dumps(installer.gate_only_hook(guard)))  # the shape before memory delivery
            installer.install(base / "copilot", base / "bin")
            hook.write_text(json.dumps(installer.stop_hook_shape(guard)))  # the shape before step memory
            installer.install(base / "copilot", base / "bin")
            hooks = json.loads(hook.read_text())["hooks"]
            [reset] = hooks["userPromptSubmitted"]
            [stop] = hooks["agentStop"]
            [post] = hooks["postToolUse"]
            self.assertEqual(post["bash"], f"{guard.with_name('copilot_memory_gate.py')} post")
            self.assertEqual(stop["bash"], f"{guard.with_name('copilot_memory_gate.py')} stop")
            self.assertEqual(Path(reset["bash"]), guard.with_name("copilot_memory_gate.py"))
            self.assertTrue(Path(reset["bash"]).is_file())
            installer.install(base / "copilot", base / "bin", uninstall=True)
            self.assertFalse(hook.exists())

    def test_installs_the_lean_custom_agent_checks_it_and_removes_only_its_own(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            home = base / "copilot"
            installer.install(home, base / "bin")
            agent = home / "agents" / installer.AGENT_FILENAME
            text = agent.read_text()
            self.assertTrue(text.startswith("---\nname: ai-orchestration\n"))
            tools = json.loads(next(line for line in text.splitlines() if line.startswith("tools: "))[7:])
            self.assertIn("bash", tools)
            self.assertIn("skill", tools)
            for left_out in ("task", "read_agent", "list_agents", "write_agent", "sql"):
                self.assertNotIn(left_out, tools)
            self.assertEqual(agent.stat().st_mode & 0o777, 0o600)
            installer.install(home, base / "bin", check=True)
            agent.write_text(text + "changed\n")
            with self.assertRaises(installer.InstallError):
                installer.install(home, base / "bin", check=True)  # drift is reported
            installer.install(home, base / "bin")  # ours (manifest): rewritten
            self.assertEqual(agent.read_text(), text)
            installer.install(home, base / "bin", uninstall=True)
            self.assertFalse(agent.exists())

    def test_refuses_to_replace_a_foreign_custom_agent_and_uninstall_keeps_it(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            agent = base / "copilot" / "agents" / installer.AGENT_FILENAME
            agent.parent.mkdir(parents=True)
            agent.write_text("---\nname: ai-orchestration\n---\nmine\n")
            with self.assertRaises(installer.InstallError):
                installer.install(base / "copilot", base / "bin")
            self.assertFalse((base / "copilot" / "hooks" / installer.HOOK_FILENAME).exists())
            installer.install(base / "copilot", base / "bin", uninstall=True)
            self.assertEqual(agent.read_text(), "---\nname: ai-orchestration\n---\nmine\n")

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
