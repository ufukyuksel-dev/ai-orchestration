#!/usr/bin/env python3
"""Runtime adapter and namespace-boundary tests for the Copilot preToolUse guard.

Passing these proves the decision core and the envelope mapping. It does not
prove that any Copilot runtime actually invoked the hook.
"""

from __future__ import annotations

import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import copilot_pre_tool_guard as guard


class NamespaceBoundaryTest(unittest.TestCase):
    def test_this_service_native_calls_are_denied(self) -> None:
        for name in (
            "memory.search",
            "memory_write",
            "rules.instructions",
            "codebase.impact.analyze",
            "mcp__ai-orchestration__memory_search",
            "mcp__ai-orchestration__rules_instructions",
            "ai-orchestration-last-job-save",
            "ai_orchestration.personal_memory.search",
        ):
            with self.subTest(name=name):
                decision = guard.evaluate({"toolName": name, "toolArgs": "{}"})
                self.assertIsNotNone(decision)
                self.assertEqual(decision["permissionDecision"], "deny")  # type: ignore[index]

    def test_unrelated_mcp_services_are_not_blocked_by_word_similarity(self) -> None:
        for name in (
            "mcp__mem0__add_memory",
            "mcp__github__create_issue",
            "mcp__other-server__memory_bank_read",
            "mcp__notion__search",
            "read_file",
            "str_replace_editor",
        ):
            with self.subTest(name=name):
                self.assertIsNone(guard.evaluate({"toolName": name, "toolArgs": "{}"}))

    def test_ordinary_development_shell_commands_are_not_blocked(self) -> None:
        for command in (
            "rg -n 'memory.write' src/main/java",
            "python3 -m unittest discover -s skills/copilot/ai-orchestration-memory/scripts -p 'test_*.py'",
            "mvn -q -Dtest=MemoryMcpToolTest test",
            "cat src/test/resources/fixtures/memory-write-request.json",
            "git diff --stat",
        ):
            with self.subTest(command=command):
                decision = guard.evaluate(
                    {
                        "toolName": "bash",
                        "cwd": "/tmp",
                        "toolArgs": {"command": command},
                    }
                )
                self.assertIsNone(decision, command)


class RuntimeAdapterTest(unittest.TestCase):
    def test_cli_and_vscode_payloads_normalize_to_one_shape(self) -> None:
        runtime, cli = guard.normalize_event(
            {"toolName": "bash", "toolArgs": '{"command":"ls"}', "cwd": "/repo"}
        )
        self.assertEqual(runtime, guard.RUNTIME_CLI)
        self.assertEqual(cli["toolName"], "bash")

        runtime, ide = guard.normalize_event(
            {
                "hook_event_name": "PreToolUse",
                "tool_name": "run_in_terminal",
                "tool_input": {"command": "ls"},
                "workspaceRoot": "/repo",
            }
        )
        self.assertEqual(runtime, guard.RUNTIME_VSCODE)
        self.assertEqual(ide["toolName"], "run_in_terminal")
        self.assertEqual(ide["toolArgs"], {"command": "ls"})
        self.assertEqual(ide["cwd"], "/repo")

    def test_tool_args_may_be_a_json_string_or_an_object(self) -> None:
        for payload in (
            {"toolName": "bash", "toolArgs": '{"command":"scanner.scan"}'},
            {"toolName": "bash", "toolArgs": {"command": "scanner.scan"}},
        ):
            with self.subTest(payload=payload):
                decision = guard.evaluate(payload)
                self.assertIsNotNone(decision)
                self.assertEqual(decision["permissionDecision"], "deny")  # type: ignore[index]

    def test_vscode_decisions_use_the_hook_specific_output_envelope(self) -> None:
        decision = guard._fixed_deny("blocked")
        rendered = guard.render_decision(guard.RUNTIME_VSCODE, decision)
        self.assertEqual(
            rendered,
            {
                "hookSpecificOutput": {
                    "hookEventName": "PreToolUse",
                    "permissionDecision": "deny",
                    "permissionDecisionReason": "blocked",
                }
            },
        )
        self.assertEqual(guard.render_decision(guard.RUNTIME_CLI, decision), decision)
        self.assertIsNone(guard.render_decision(guard.RUNTIME_VSCODE, None))

    def test_malformed_payloads_fail_closed(self) -> None:
        with self.assertRaises(ValueError):
            guard.normalize_event(["not", "an", "object"])  # type: ignore[arg-type]


class AiOrchContractTest(unittest.TestCase):
    def setUp(self) -> None:
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        (self.root / ".git").mkdir()
        patcher = patch.object(
            guard, "_verify_trusted_launcher", lambda name="ai-orch-memory": None
        )
        patcher.start()
        self.addCleanup(patcher.stop)

    def decide(self, command: str):
        return guard.evaluate(
            {"toolName": "bash", "cwd": str(self.root), "toolArgs": {"command": command}}
        )

    def test_canonical_commands_are_allowed(self) -> None:
        for command in (
            "ai_orch rule instructions --scope effective",
            "ai_orch memory search --query 'write gate' --top-k 3",
            "ai_orch capabilities",
            "ai_orch doctor",
            "ai-orch-memory search --query safe --top-k 3",
        ):
            with self.subTest(command=command):
                decision = self.decide(command)
                self.assertIsNotNone(decision)
                self.assertEqual(decision["permissionDecision"], "allow")  # type: ignore[index]

    def test_unknown_groups_and_bridge_owned_overrides_are_denied(self) -> None:
        for command in (
            "ai_orch",
            "ai_orch call rules.promote",
            "ai_orch memory search --project-key OTHER --query x",
            "ai_orch memory search --endpoint http://example.test/mcp --query x",
            "ai_orch memory search --provider-override claude --query x",
        ):
            with self.subTest(command=command):
                decision = self.decide(command)
                self.assertIsNotNone(decision)
                self.assertEqual(decision["permissionDecision"], "deny")  # type: ignore[index]

    def test_pipelines_substitution_and_implicit_cd_are_denied(self) -> None:
        for command in (
            "cd /other && ai_orch capabilities",
            "ai_orch capabilities | jq .",
            "ai_orch memory search --query $(whoami)",
            "ai_orch capabilities; rm -rf /tmp/x",
            "python3 ai_orch.py capabilities",
        ):
            with self.subTest(command=command):
                decision = self.decide(command)
                self.assertIsNotNone(decision)
                self.assertEqual(decision["permissionDecision"], "deny")  # type: ignore[index]


if __name__ == "__main__":
    unittest.main()
