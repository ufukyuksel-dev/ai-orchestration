#!/usr/bin/env python3
"""Installer tests for the extended Copilot integration.

Everything runs against an isolated temporary HOME/bin. Nothing here touches the
real user configuration, and a passing run is not evidence that Copilot loaded
the artifacts.
"""

from __future__ import annotations

import json
import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import install_user_integration as installer


class InstallerTestCase(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.base = Path(self.tmp.name)
        self.copilot_home = self.base / "copilot"
        self.bin_dir = self.base / "bin"
        self.staging = self.base / "staging"
        patcher = patch.dict(os.environ, {"AI_ORCH_STAGING_DIR": str(self.staging)})
        patcher.start()
        self.addCleanup(patcher.stop)

    def install(self, **kwargs):
        plan = installer.Plan()
        result = installer.install(self.copilot_home, self.bin_dir, plan=plan, **kwargs)
        return result, plan

    @property
    def bootstrap(self) -> Path:
        return self.copilot_home.resolve() / installer.BOOTSTRAP_FILENAME

    @property
    def manifest(self) -> Path:
        return self.copilot_home.resolve() / installer.MANIFEST_FILENAME


class InstallAndCheckTest(InstallerTestCase):
    def test_full_install_creates_every_owned_artifact(self) -> None:
        (hook, memory_launcher, ai_orch_launcher), _ = self.install()
        guard, memory_bridge, ai_orch = installer.integration_paths()

        self.assertEqual(json.loads(hook.read_text()), installer.expected_hook(guard))
        self.assertEqual(hook.stat().st_mode & 0o777, 0o600)
        self.assertEqual(memory_launcher.resolve(), memory_bridge)
        self.assertEqual(ai_orch_launcher.resolve(), ai_orch)

        text = self.bootstrap.read_text(encoding="utf-8")
        self.assertIn(installer.BEGIN, text)
        self.assertIn("ai_orch rule instructions --scope effective", text)
        self.assertIn("Copilot terminal protocol still applies", text)
        self.assertIn("Do not spend a tool call reading or printing", text)
        self.assertIn("only for a directory explicitly listed", text)
        self.assertIn("`ask_user`", text)
        self.assertIn("global ve proje kurallarını yükleyeyim mi?", text)
        self.assertIn("Hayır, bu oturumda atla", text)
        self.assertIn("`memory search`, `memory learn`, `reference list`, `reference read`", text)
        self.assertIn("`capabilities` and `doctor` are diagnostics only", text)
        self.assertNotIn("plan check", text)

        skills_dir = self.copilot_home.resolve() / "skills"
        for source in installer.skill_sources():
            link = skills_dir / source.name
            self.assertTrue(link.is_symlink(), source.name)
            self.assertEqual(link.resolve(), source)
        self.assertTrue(self.staging.is_dir())
        self.assertEqual(self.staging.stat().st_mode & 0o777, 0o700)

        manifest = json.loads(self.manifest.read_text())
        self.assertEqual(manifest["version"], installer.MANIFEST_VERSION)
        self.assertEqual(len(manifest["links"]), len(installer.skill_sources()))
        self.assertEqual(
            {source.name for source in installer.skill_sources()},
            {
                "ai-orchestration-learning",
                "ai-orchestration-memory",
                "ai-orchestration-references",
                "ai-orchestration-rules",
            },
        )

    def test_reinstall_removes_only_retired_owned_skill_links(self) -> None:
        self.install()
        retired_source = installer.repository_root() / "skills" / "copilot" / "ai-orchestration-jobs"
        retired_link = self.copilot_home.resolve() / "skills" / retired_source.name
        retired_link.symlink_to(retired_source)
        manifest = json.loads(self.manifest.read_text(encoding="utf-8"))
        manifest["links"].append(
            {"target": str(retired_link), "source": str(retired_source)}
        )
        self.manifest.write_text(json.dumps(manifest), encoding="utf-8")

        self.install()

        self.assertFalse(retired_link.exists())
        refreshed = json.loads(self.manifest.read_text(encoding="utf-8"))
        self.assertEqual(
            {Path(row["target"]).name for row in refreshed["links"]},
            {source.name for source in installer.skill_sources()},
        )

    def test_second_install_is_a_no_op_and_check_passes(self) -> None:
        self.install()
        before = {
            path: path.read_bytes()
            for path in (self.bootstrap, self.manifest)
        }
        self.install()
        for path, content in before.items():
            self.assertEqual(path.read_bytes(), content, path)
        self.install(check=True)

    def test_never_writes_an_mcp_registration(self) -> None:
        self.install()
        produced = {path.name for path in self.base.rglob("*") if path.is_file()}
        self.assertNotIn("mcp.json", produced)
        self.assertNotIn("mcp-config.json", produced)

    def test_check_detects_bootstrap_drift_and_a_missing_skill_link(self) -> None:
        self.install()
        self.install(check=True)

        text = self.bootstrap.read_text(encoding="utf-8")
        start = text.index(installer.BEGIN)
        end = text.index(installer.END) + len(installer.END)
        self.bootstrap.write_text(
            text[:start] + installer.BEGIN + "\nedited\n" + installer.END + text[end:],
            encoding="utf-8",
        )
        with self.assertRaises(installer.InstallError):
            self.install(check=True)

        self.install()  # repair
        link = self.copilot_home.resolve() / "skills" / installer.skill_sources()[0].name
        link.unlink()
        with self.assertRaises(installer.InstallError):
            self.install(check=True)


class PersonalContentTest(InstallerTestCase):
    def test_personal_instruction_text_is_preserved_byte_for_byte(self) -> None:
        self.copilot_home.mkdir(parents=True)
        personal = "# Benim kişisel talimatlarım\n\nTürkçe cevap ver.\r\n"
        self.bootstrap.write_bytes(personal.encode("utf-8"))
        self.install()
        text = self.bootstrap.read_bytes().decode("utf-8")
        self.assertTrue(text.startswith(personal))
        self.assertIn(installer.BEGIN, text)

        # A re-install replaces only the managed span.
        self.install()
        self.assertTrue(
            self.bootstrap.read_bytes().decode("utf-8").startswith(personal)
        )

    def test_a_malformed_marker_pair_is_a_conflict_not_an_overwrite(self) -> None:
        self.copilot_home.mkdir(parents=True)
        broken = f"{installer.BEGIN}\nx\n{installer.BEGIN}\ny\n{installer.END}\n"
        self.bootstrap.write_text(broken, encoding="utf-8")
        with self.assertRaises(installer.InstallError):
            self.install()
        self.assertEqual(self.bootstrap.read_text(encoding="utf-8"), broken)


class ForeignArtifactTest(InstallerTestCase):
    def test_a_foreign_skill_entry_is_a_conflict(self) -> None:
        skills_dir = self.copilot_home / "skills"
        skills_dir.mkdir(parents=True)
        name = installer.skill_sources()[0].name
        (skills_dir / name).mkdir()
        with self.assertRaises(installer.InstallError):
            self.install()
        self.assertFalse(
            (self.copilot_home / "hooks" / installer.HOOK_FILENAME).exists()
        )

    def test_a_redirected_configuration_directory_is_refused(self) -> None:
        real = self.base / "elsewhere"
        real.mkdir()
        self.copilot_home.symlink_to(real, target_is_directory=True)
        with self.assertRaises(installer.InstallError):
            self.install()
        self.bin_dir.mkdir()
        redirected_bin = self.base / "redirected-bin"
        redirected_bin.symlink_to(real, target_is_directory=True)
        with self.assertRaises(installer.InstallError):
            installer.install(self.base / "fresh-home", redirected_bin)

    def test_a_platform_symlink_above_the_target_does_not_fail(self) -> None:
        # /tmp and /var are symlinks on macOS; that is not a redirected
        # configuration directory and must not block an isolated test install.
        installer.install(self.copilot_home, self.bin_dir)
        installer.install(self.copilot_home, self.bin_dir, check=True)


class DryRunAndUninstallTest(InstallerTestCase):
    def test_dry_run_mutates_nothing_and_reports_the_plan(self) -> None:
        _, plan = self.install(dry_run=True)
        self.assertFalse(self.copilot_home.exists())
        self.assertFalse(self.bin_dir.exists())
        joined = "\n".join(plan.actions)
        self.assertIn("write hook", joined)
        self.assertIn("link skill", joined)
        self.assertIn("write manifest", joined)
        self.assertTrue(plan.diffs)

    def test_uninstall_removes_only_owned_artifacts(self) -> None:
        self.copilot_home.mkdir(parents=True)
        personal = "# kişisel\n"
        self.bootstrap.write_text(personal, encoding="utf-8")
        self.install()

        foreign_link = self.copilot_home.resolve() / "skills" / "someone-elses-skill"
        foreign_link.mkdir()

        _, plan = self.install(uninstall=True)
        self.assertFalse((self.copilot_home.resolve() / "hooks" / installer.HOOK_FILENAME).exists())
        self.assertFalse((self.bin_dir.resolve() / installer.AI_ORCH_LAUNCHER_NAME).exists())
        self.assertFalse(self.manifest.exists())
        self.assertTrue(foreign_link.exists())
        self.assertEqual(self.bootstrap.read_text(encoding="utf-8"), personal)
        for source in installer.skill_sources():
            self.assertFalse(
                (self.copilot_home.resolve() / "skills" / source.name).exists(), source.name
            )
        self.assertTrue(any("server data untouched" in entry for entry in plan.actions))

    def test_uninstall_keeps_a_foreign_hook_and_launcher(self) -> None:
        hook_dir = self.copilot_home / "hooks"
        hook_dir.mkdir(parents=True)
        foreign_hook = '{"version":1,"hooks":{"preToolUse":[]}}'
        (hook_dir / installer.HOOK_FILENAME).write_text(foreign_hook)
        self.bin_dir.mkdir(parents=True)
        (self.bin_dir / installer.AI_ORCH_LAUNCHER_NAME).write_text("user-owned")

        _, plan = self.install(uninstall=True)
        self.assertEqual(
            (hook_dir / installer.HOOK_FILENAME).read_text(), foreign_hook
        )
        self.assertEqual(
            (self.bin_dir / installer.AI_ORCH_LAUNCHER_NAME).read_text(), "user-owned"
        )
        joined = "\n".join(plan.actions)
        self.assertIn("kept foreign hook", joined)
        self.assertIn("kept foreign launcher", joined)

    def test_dry_run_uninstall_mutates_nothing(self) -> None:
        self.install()
        self.install(uninstall=True, dry_run=True)
        self.install(check=True)


class ParserTest(InstallerTestCase):
    def test_every_mode_flag_parses_and_modes_are_exclusive(self) -> None:
        parser = installer.build_parser()
        for argv in (["--check"], ["--dry-run"], ["--uninstall"], ["--runtime", "all"]):
            with self.subTest(argv=argv):
                parser.parse_args(argv)
        with self.assertRaises(SystemExit):
            parser.parse_args(["--check", "--uninstall"])

    def test_copilot_home_env_override_is_honoured(self) -> None:
        with patch.dict(os.environ, {"COPILOT_HOME": str(self.base / "custom home")}):
            self.assertEqual(
                installer.default_copilot_home(), Path(str(self.base / "custom home"))
            )
        environment = dict(os.environ)
        environment.pop("COPILOT_HOME", None)
        with patch.dict(os.environ, environment, clear=True):
            self.assertEqual(installer.default_copilot_home(), Path.home() / ".copilot")

    def test_installation_into_a_path_with_spaces_works(self) -> None:
        self.copilot_home = self.base / "copilot home"
        self.bin_dir = self.base / "local bin"
        self.install()
        self.install(check=True)


if __name__ == "__main__":
    unittest.main()
