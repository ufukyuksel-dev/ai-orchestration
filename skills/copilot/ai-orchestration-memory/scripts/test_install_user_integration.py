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
        _, memory_bridge, ai_orch = installer.integration_paths()
        self.assertTrue(memory_launcher.is_symlink())
        self.assertEqual(memory_launcher.resolve(), memory_bridge)
        self.assertTrue(ai_orch_launcher.is_symlink())
        self.assertEqual(ai_orch_launcher.resolve(), ai_orch)
        self.assertEqual(
            installer.install(self.copilot_home, self.bin_dir, check=True),
            (mcp_config, memory_launcher, ai_orch_launcher),
        )

    def test_the_hook_resets_the_memory_gate_per_prompt_and_replaces_an_earlier_guard_hook(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            gate, _, _ = installer.integration_paths()
            guard = gate.with_name("copilot_pre_tool_guard.py")  # retired; earlier hook files still name it
            hook = base / "copilot" / "hooks" / installer.HOOK_FILENAME
            hook.parent.mkdir(parents=True)

            def entry(command: str) -> list[dict]:
                return [{"type": "command", "bash": command, "timeoutSec": 5}]

            for earlier in ({"preToolUse": entry(str(guard))},  # guard only
                            {"preToolUse": entry(str(guard)), "userPromptSubmitted": entry(str(gate))},
                            {"preToolUse": entry(str(guard)), "userPromptSubmitted": entry(str(gate)),
                             "agentStop": entry(f"{gate} stop")}):
                hook.write_text(json.dumps({"version": 1, "hooks": earlier}))
                installer.install(base / "copilot", base / "bin")
                self.assertEqual(json.loads(hook.read_text()), installer.expected_hook(gate))
            hooks = json.loads(hook.read_text())["hooks"]
            self.assertNotIn("preToolUse", hooks)
            [reset] = hooks["userPromptSubmitted"]
            [stop] = hooks["agentStop"]
            [post] = hooks["postToolUse"]
            self.assertEqual(post["bash"], f"{gate} post")
            self.assertEqual(stop["bash"], f"{gate} stop")
            self.assertEqual(Path(reset["bash"]), gate)
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

    def test_replaces_the_earlier_hook_of_any_checkout_and_refuses_a_foreign_one(self) -> None:
        hook = self.copilot_home / "hooks" / installer.HOOK_FILENAME
        hook.parent.mkdir(parents=True)
        legacy = {
            "version": 1,
            "hooks": {"preToolUse": [{"type": "command", "timeoutSec": 5, "bash": str(
                self.base / "elsewhere" / "skills/copilot/ai-orchestration-memory/scripts/copilot_pre_tool_guard.py")}]},
        }
        hook.write_text(json.dumps(legacy))
        installer.install(self.copilot_home, self.bin_dir)
        gate, _, _ = installer.integration_paths()
        self.assertEqual(json.loads(hook.read_text()), installer.expected_hook(gate))

        foreign = '{"version":1,"hooks":{"preToolUse":[]}}'
        hook.write_text(foreign)
        with self.assertRaises(installer.InstallError):
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
        _, memory_bridge, _ = installer.integration_paths()
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
