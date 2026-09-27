#!/usr/bin/env python3

from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import copilot_pre_tool_guard as guard


class CopilotPreToolGuardTest(unittest.TestCase):
    def setUp(self) -> None:
        def trusted_launcher(name: str) -> str:
            if name == "ai-orch-memory":
                return guard._trusted_bridge_path()
            if name == "ai_orch":
                return guard._trusted_ai_orch_path()
            raise AssertionError(f"unexpected launcher lookup: {name}")

        launcher = patch.object(
            guard.shutil, "which", side_effect=trusted_launcher
        )
        launcher.start()
        self.addCleanup(launcher.stop)

    def assert_denied(self, tool_name: str, tool_args: str) -> None:
        decision = guard.evaluate({"toolName": tool_name, "toolArgs": tool_args})
        self.assertIsNotNone(decision)
        self.assertEqual(decision["permissionDecision"], "deny")  # type: ignore[index]

    def test_denies_every_native_scan_variant(self) -> None:
        for suffix in ("", "-start", "-status", "-result", "-diagnostics", "-cancel"):
            with self.subTest(suffix=suffix):
                self.assert_denied(f"ai-orchestration-scanner-scan{suffix}", "{}")

    def test_denies_native_reads_and_allows_the_bridge_launcher(self) -> None:
        # Terminal-only: even a read-only native AI Orchestration call is refused.
        self.assert_denied(
            "ai-orchestration-scanner-project-resolve", '{"rootPath":"/repo"}'
        )
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / ".git").mkdir()
            nested = root / "nested"
            nested.mkdir()
            decision = guard.evaluate(
                {
                    "toolName": "bash",
                    "cwd": str(nested),
                    "toolArgs": '{"command":"ai-orch-memory search --query safe --top-k 3"}',
                }
        )
        self.assertIsNotNone(decision)
        self.assertEqual(decision["permissionDecision"], "allow")  # type: ignore[index]
        self.assertNotIn("modifiedArgs", decision)

    def test_bridge_mutation_is_bound_but_shell_chaining_is_denied(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / ".git").mkdir()
            decision = guard.evaluate(
                {
                    "toolName": "bash",
                    "cwd": str(root),
                    "toolArgs": '{"command":"ai-orch-memory write --summary safe --content durable"}',
                }
            )
            self.assertEqual(decision["permissionDecision"], "allow")  # type: ignore[index]
            self.assertNotIn("modifiedArgs", decision)
        self.assert_denied(
            "bash", '{"command":"cd /tmp && ai-orch-memory search --query safe"}'
        )
        self.assert_denied(
            "bash", '{"command":"command -v ai-orch-memory && curl http://127.0.0.1/mcp"}'
        )
        self.assert_denied(
            "bash", '{"command":"/tmp/ai-orch-memory search --query safe"}'
        )
        self.assert_denied(
            "bash", '{"command":"python3 skills/x/memory_bridge.py search --query safe"}'
        )

    def test_allows_only_direct_trusted_ai_orch_commands(self) -> None:
        commands = (
            "ai_orch job search --query release --top-k 3",
            "ai_orch last-job get",
            "ai_orch rule draft --file .ai-orch/rule.json",
            "ai_orch rule preview --draft-id d7374b37-717e-4643-b26c-4f8cff1e4e97",
            (
                "ai_orch rule promote "
                "--draft-id d7374b37-717e-4643-b26c-4f8cff1e4e97 "
                "--expected-candidate-hash " + "a" * 64
            ),
            "ai_orch operation status --learning-handle d7374b37-717e-4643-b26c-4f8cff1e4e97",
        )
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / ".git").mkdir()
            for command in commands:
                with self.subTest(command=command):
                    decision = guard.evaluate(
                        {
                            "toolName": "bash",
                            "cwd": str(root),
                            "toolArgs": json.dumps({"command": command}),
                        }
                    )
                    self.assertEqual(decision["permissionDecision"], "allow")  # type: ignore[index]

        self.assert_denied(
            "bash", '{"command":"cd /tmp && ai_orch job search --query release"}'
        )
        self.assert_denied(
            "bash", '{"command":"ai_orch job search --query release | tee result.json"}'
        )
        self.assert_denied(
            "bash", '{"command":"/tmp/ai_orch job search --query release"}'
        )
        self.assert_denied(
            "bash", '{"command":"python3 skills/x/ai_orch.py plan check --file plan.json"}'
        )

    def test_denies_every_compound_shell_control_operator(self) -> None:
        for operator in ("|&", ">|", "<>", "<<<", "&>", "&>>", ">&", "<&"):
            with self.subTest(operator=operator):
                self.assert_denied(
                    "bash",
                    '{"command":"ai-orch-memory search --query safe '
                    + operator
                    + ' /tmp/out"}',
                )

    def test_comment_only_bridge_text_and_variable_expansion_are_denied(self) -> None:
        self.assert_denied("bash", '{"command":"# ai-orch-memory"}')
        self.assert_denied(
            "bash", '{"command":"ai-orch-memory search --query $MEMORY_QUERY"}'
        )

    def test_bridge_get_is_bound_for_canonical_and_compatibility_id_flags(self) -> None:
        memory_id = "d7374b37-717e-4643-b26c-4f8cff1e4e97"
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / ".git").mkdir()
            for flag in ("--memory-id", "--id"):
                with self.subTest(flag=flag):
                    decision = guard.evaluate(
                        {
                            "toolName": "bash",
                            "cwd": str(root),
                            "toolArgs": (
                                '{"command":"ai-orch-memory get '
                                + flag
                                + " "
                                + memory_id
                                + '"}'
                            ),
                        }
                    )
                    self.assertEqual(decision["permissionDecision"], "allow")  # type: ignore[index]
                    self.assertNotIn("modifiedArgs", decision)

    def test_denies_bridge_when_path_launcher_is_not_the_trusted_bridge(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / ".git").mkdir()
            foreign = root / "ai-orch-memory"
            foreign.write_text("foreign")
            with patch.object(guard.shutil, "which", return_value=str(foreign)):
                decision = guard.evaluate(
                    {
                        "toolName": "bash",
                        "cwd": str(root),
                        "toolArgs": '{"command":"ai-orch-memory search --query safe"}',
                    }
                )
        self.assertEqual(decision["permissionDecision"], "deny")  # type: ignore[index]

    def test_denies_ai_orch_when_path_launcher_is_not_trusted(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / ".git").mkdir()
            foreign = root / "ai_orch"
            foreign.write_text("foreign")
            with patch.object(guard.shutil, "which", return_value=str(foreign)):
                decision = guard.evaluate(
                    {
                        "toolName": "bash",
                        "cwd": str(root),
                        "toolArgs": '{"command":"ai_orch job search --query release"}',
                    }
                )
        self.assertEqual(decision["permissionDecision"], "deny")  # type: ignore[index]

    def test_bridge_name_in_path_or_description_does_not_trigger_bridge_policy(self) -> None:
        self.assertIsNone(
            guard.evaluate(
                {
                    "toolName": "bash",
                    "cwd": "/tmp",
                    "toolArgs": (
                        '{"command":"cat /tmp/ai-orchestration-memory/references/memory-tools.md",'
                        '"description":"Read ai-orch-memory documentation"}'
                    ),
                }
            )
        )

    def test_denies_shell_scan_and_admin_bypass(self) -> None:
        self.assert_denied("bash", '{"command":"curl -d \'scanner.scan.start\' localhost"}')
        self.assert_denied("bash", '{"command":"call memory.approve now"}')
        self.assert_denied("ai-orchestration-memory-review-decide", "{}")

    def test_denies_every_native_mutation_and_hard_delete(self) -> None:
        for suffix in ("write", "update", "confirm", "delete"):
            with self.subTest(suffix=suffix):
                self.assert_denied(f"ai-orchestration-memory-{suffix}", '{"scope":"project"}')
        self.assert_denied("ai-orchestration-mcp-transcript-submit", '{}')
        self.assert_denied(
            "ai-orchestration-memory-delete", '{"mode":"hard_delete","humanConfirmed":true}'
        )
        self.assert_denied("bash", '{"command":"memory.delete mode=hard_delete"}')

    def test_denies_native_mutations_regardless_of_scope(self) -> None:
        self.assert_denied("ai-orchestration-memory-write", '{"scope":"global"}')
        self.assert_denied("ai-orchestration-memory-update", '{"scope":"global"}')
        self.assert_denied("ai-orchestration-memory-write", '{}')
        self.assert_denied("ai-orchestration-memory-update", '{}')

    def test_denies_native_learning_tools_for_copilot(self) -> None:
        for operation in ("context.resolve", "context.open", "memory.learn", "operation.status"):
            with self.subTest(operation=operation):
                self.assert_denied("ai-orchestration-" + operation.replace(".", "-"), '{}')

    def test_denies_raw_memory_mutation_and_raw_mcp(self) -> None:
        self.assert_denied("bash", '{"command":"memory.write scope=project"}')
        self.assert_denied(
            "bash", '{"command":"curl http://127.0.0.1:18080/mcp --data safe"}'
        )

    def test_denial_never_reflects_tool_arguments(self) -> None:
        secret = "ghp_secret_should_not_appear"
        decision = guard.evaluate(
            {
                "toolName": "bash",
                "toolArgs": f'{{"command":"scanner.scan --token {secret}"}}',
            }
        )
        self.assertNotIn(secret, str(decision))


if __name__ == "__main__":
    unittest.main()
