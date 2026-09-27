#!/usr/bin/env python3
"""Regression tests for the canonical ai_orch command surface.

These use a fake transport. They prove argument construction, scope binding and
refusal boundaries; they are not evidence that a real Copilot session ran.
"""

from __future__ import annotations

import io
import json
import os
import tempfile
import unittest
import uuid
from pathlib import Path
from unittest.mock import patch

import ai_orch


class FakeClient:
    def __init__(self, results: dict | None = None) -> None:
        self.calls: list[tuple[str, dict]] = []
        self.results = results or {}

    def initialize(self) -> dict:
        return {"serverInfo": {"name": "fake"}}

    def list_tools(self) -> list[dict]:
        return [{"name": name} for name in ai_orch.ALLOWED_TOOLS]

    def call_tool(self, name: str, arguments: dict):
        ai_orch.validate_dispatch(name, arguments)
        self.calls.append((name, arguments))
        if name in self.results:
            return self.results[name]
        if name == "scanner.project.resolve":
            return {
                "projectKey": "RESOLVED",
                "rootPath": arguments["rootPath"],
                "workspaceBindingId": "00000000-0000-0000-0000-000000000123",
            }
        return {"tool": name, "accepted": True}

    def capture_learning(self, cwd: str, task_run_id: str, candidates: list[dict]):
        request = {"cwd": cwd, "taskRunId": task_run_id, "learningCandidates": candidates}
        self.calls.append(("terminal.learning.capture", request))
        return self.results.get("terminal.learning.capture", {"status": "ACCEPTED", "created": 1})


def parser():
    return ai_orch.build_parser()


class Workspace:
    """An isolated Git-like repository plus a private staging directory."""

    def __init__(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        base = Path(self._tmp.name)
        self.root = base / "repo"
        self.root.mkdir()
        (self.root / ".git").mkdir()
        self.root = self.root.resolve()
        self.staging = (base / "staging").resolve()
        self.staging.mkdir()

    def request(self, name: str, payload) -> str:
        path = self.staging / name
        if isinstance(payload, str):
            path.write_text(payload, encoding="utf-8")
        else:
            path.write_text(json.dumps(payload, ensure_ascii=False), encoding="utf-8")
        return str(path)

    def close(self) -> None:
        self._tmp.cleanup()


class CommandTestCase(unittest.TestCase):
    def setUp(self) -> None:
        self.workspace = Workspace()
        self.addCleanup(self.workspace.close)
        patcher = patch.dict(
            os.environ, {"AI_ORCH_STAGING_DIR": str(self.workspace.staging)}
        )
        patcher.start()
        self.addCleanup(patcher.stop)

    def run_command(self, argv: list[str], results: dict | None = None):
        client = FakeClient(results)
        args = parser().parse_args(argv)
        with patch.object(
            ai_orch.memory_bridge, "current_repo_root", return_value=str(self.workspace.root)
        ):
            output = ai_orch.execute(args, client)  # type: ignore[arg-type]
        return output, client

    def expect_failure(self, argv: list[str], results: dict | None = None):
        client = FakeClient(results)
        args = parser().parse_args(argv)
        with patch.object(
            ai_orch.memory_bridge, "current_repo_root", return_value=str(self.workspace.root)
        ):
            with self.assertRaises(ai_orch.BridgeError) as captured:
                ai_orch.execute(args, client)  # type: ignore[arg-type]
        return captured.exception, client


class LearningRuntimeParityContractTest(unittest.TestCase):
    def test_copilot_adapter_matches_shared_runtime_parity_fixture(self) -> None:
        repo = Path(__file__).resolve().parents[4]
        contract = json.loads(
            (repo / "contracts/agent-learning/runtime-parity.json").read_text(encoding="utf-8")
        )
        adapter = contract["adapters"]["COPILOT"]

        self.assertEqual(adapter["transport"], "single_direct_ai_orch_terminal_command")
        self.assertEqual(adapter["producerRuntime"], "copilot")
        self.assertIn("native_mcp", adapter["forbidden"])
        self.assertEqual(adapter["operations"]["runtime.capture"], "ai_orch memory learn")
        self.assertEqual(adapter["operations"]["runtime.prefetch"], "ai_orch memory search")
        self.assertNotIn("memory.learn", ai_orch.ALLOWED_TOOLS)
        self.assertNotIn("memory.learn", ai_orch._OPERATION_TOOLS)
        parsed = parser().parse_args(["memory", "learn", "--file", "memory-learn.json"])
        self.assertEqual(parsed.operation, "memory.learn")


COMPLETE_INSTRUCTIONS = {
    "projectKey": "RESOLVED",
    "scope": "effective",
    "complete": True,
    "globalEffectiveSeq": 7,
    "projectEffectiveSeq": 2,
    "instructions": [
        {
            "ruleId": str(uuid.uuid4()),
            "version": 1,
            "scope": "GLOBAL_STRICT",
            "contentLoaded": True,
            "statement": "Tam talimat metni burada.",
        }
    ],
}


class InstructionLoadTest(CommandTestCase):
    def test_effective_load_sends_resolved_key_and_explicit_scope(self) -> None:
        output, client = self.run_command(
            ["rule", "instructions", "--scope", "effective"],
            {"rules.instructions": COMPLETE_INSTRUCTIONS},
        )
        self.assertEqual(
            client.calls[-1],
            ("rules.instructions", {"projectKey": "RESOLVED", "scope": "effective"}),
        )
        self.assertEqual(output["context"]["projectKey"], "RESOLVED")
        self.assertEqual(output["schemaVersion"], "2")

    def test_incomplete_response_is_not_a_loaded_state(self) -> None:
        payload = dict(COMPLETE_INSTRUCTIONS, complete=False)
        error, _ = self.expect_failure(
            ["rule", "instructions"], {"rules.instructions": payload}
        )
        self.assertIn("incomplete", str(error))

    def test_metadata_only_instruction_is_rejected(self) -> None:
        payload = dict(
            COMPLETE_INSTRUCTIONS,
            instructions=[
                {"ruleId": str(uuid.uuid4()), "scope": "PROJECT", "contentLoaded": False}
            ],
        )
        error, _ = self.expect_failure(
            ["rule", "instructions"], {"rules.instructions": payload}
        )
        self.assertIn("not complete", str(error))

    def test_effective_module_index_row_is_not_treated_as_missing_policy_text(self) -> None:
        payload = dict(
            COMPLETE_INSTRUCTIONS,
            instructions=[
                *COMPLETE_INSTRUCTIONS["instructions"],
                {
                    "ruleId": str(uuid.uuid4()),
                    "version": 1,
                    "scope": "MODULE",
                    "contentLoaded": False,
                    "statement": None,
                    "modulePaths": ["src/main/java/com/example/module"],
                },
            ],
        )

        output, _ = self.run_command(
            ["rule", "instructions", "--scope", "effective"],
            {"rules.instructions": payload},
        )

        self.assertTrue(any("index, not loaded" in w for w in output["warnings"]))

    def test_effective_module_index_is_reported_as_an_index(self) -> None:
        payload = dict(COMPLETE_INSTRUCTIONS, modules=["src/main/java/x"])
        output, _ = self.run_command(
            ["rule", "instructions"], {"rules.instructions": payload}
        )
        self.assertTrue(any("index, not loaded" in w for w in output["warnings"]))

    def test_module_scope_requires_and_normalizes_module_paths(self) -> None:
        _, client = self.run_command(
            [
                "rule",
                "instructions",
                "--scope",
                "module",
                "--module-path",
                "src/main/java/com/mbworldwideapps/aiorchestration/modules/memoryai",
                "--module-path",
                "src/main/java/com/mbworldwideapps/aiorchestration/modules/scanner",
            ],
            {"rules.instructions": dict(COMPLETE_INSTRUCTIONS, scope="module")},
        )
        self.assertEqual(
            client.calls[-1][1]["modulePaths"],
            [
                "src/main/java/com/mbworldwideapps/aiorchestration/modules/memoryai",
                "src/main/java/com/mbworldwideapps/aiorchestration/modules/scanner",
            ],
        )
        self.expect_failure(["rule", "instructions", "--scope", "module"])
        self.expect_failure(
            ["rule", "instructions", "--scope", "module", "--module-path", "../escape"]
        )

    def test_module_path_is_rejected_outside_module_scope(self) -> None:
        self.expect_failure(
            ["rule", "instructions", "--scope", "project", "--module-path", "src"]
        )

    def test_module_path_list_is_bounded_to_the_server_signature(self) -> None:
        with self.assertRaises(ai_orch.BridgeError):
            ai_orch.validate_dispatch(
                "rules.instructions",
                {
                    "projectKey": "P",
                    "scope": "module",
                    "modulePaths": [f"src/m{i}" for i in range(33)],
                },
            )


class GlobalRuleAuthoringTest(CommandTestCase):
    def test_global_draft_omits_project_key_entirely(self) -> None:
        candidate = self.workspace.request(
            "candidate.json",
            '{"statement":"Her projede testler calistirilir","enforcement":"instruction","appliesAll":true}',
        )
        output, client = self.run_command(["rule", "draft", "--file", candidate, "--global"])
        name, arguments = client.calls[-1]
        self.assertEqual(name, "rules.draft")
        self.assertNotIn("projectKey", arguments)
        self.assertEqual(output["context"]["scopeKind"], ai_orch.SCOPE_GLOBAL_RULE)
        # No project resolution happened for an explicit global authoring call.
        self.assertNotIn("scanner.project.resolve", [call[0] for call in client.calls])

    def test_project_draft_still_sends_the_resolved_key(self) -> None:
        candidate = self.workspace.request(
            "candidate.json",
            '{"statement":"Bu projede DAO degisiklikleri test ister","enforcement":"instruction","appliesAll":true}',
        )
        output, client = self.run_command(["rule", "draft", "--file", candidate])
        self.assertEqual(client.calls[-1][1]["projectKey"], "RESOLVED")
        # Legacy envelope is preserved for the pre-existing project form.
        self.assertEqual(set(output), {"success", "command", "projectKey", "result"})

    def test_global_and_repo_root_are_mutually_exclusive(self) -> None:
        candidate = self.workspace.request("candidate.json", '{"statement":"x"}')
        self.expect_failure(
            [
                "rule",
                "draft",
                "--file",
                candidate,
                "--global",
                "--repo-root",
                str(self.workspace.root),
            ]
        )

    def test_project_resolution_failure_never_becomes_a_global_write(self) -> None:
        candidate = self.workspace.request("candidate.json", '{"statement":"x"}')
        client = FakeClient()
        args = parser().parse_args(["rule", "draft", "--file", candidate])
        with patch.object(
            ai_orch.memory_bridge,
            "current_repo_root",
            side_effect=ai_orch.BridgeError("no repository"),
        ):
            with self.assertRaises(ai_orch.BridgeError):
                ai_orch.execute(args, client)  # type: ignore[arg-type]
        self.assertEqual(client.calls, [])

    def test_instruction_candidate_rejects_detectors_and_selectors(self) -> None:
        for field in ("detectorType", "selectorGroups", "checks"):
            candidate = self.workspace.request(
                "candidate.json",
                json.dumps(
                    {
                        "statement": "x",
                        "enforcement": "instruction",
                        "appliesAll": True,
                        field: "anything",
                    }
                ),
            )
            with self.subTest(field=field):
                self.expect_failure(["rule", "draft", "--file", candidate, "--global"])

    def test_rule_statement_byte_limit_is_enforced_before_the_network(self) -> None:
        candidate = self.workspace.request(
            "candidate.json",
            json.dumps({"statement": "ş" * 9000, "enforcement": "instruction"}),
        )
        error, client = self.expect_failure(["rule", "draft", "--file", candidate, "--global"])
        self.assertIn("16384", str(error))

    def test_promote_accepts_an_approval_file_but_not_a_half_combination(self) -> None:
        draft_id = str(uuid.uuid4())
        approval = self.workspace.request(
            "approval.json",
            {
                "draftId": draft_id,
                "expectedCandidateHash": "a" * 64,
                "expectedApprovalContentHash": "b" * 64,
                "expectedConfirmationCardHash": "c" * 64,
                "workflowContractVersion": "rule-authoring-v1",
                "humanRawText": "evet, onayliyorum",
                "humanTurnRef": "turn-9",
                "aiInterpretedAsApproval": True,
                "agentConfidence": 0.95,
            },
        )
        _, client = self.run_command(
            ["rule", "promote", "--approval-file", approval, "--global"]
        )
        name, arguments = client.calls[-1]
        self.assertEqual(name, "rules.promote")
        self.assertNotIn("projectKey", arguments)
        self.assertEqual(arguments["humanTurnRef"], "turn-9")
        self.expect_failure(
            [
                "rule",
                "promote",
                "--approval-file",
                approval,
                "--draft-id",
                draft_id,
                "--global",
            ]
        )

    def test_approval_file_rejects_non_finite_confidence(self) -> None:
        approval = self.workspace.request(
            "approval.json",
            '{"draftId":"%s","expectedCandidateHash":"%s","expectedApprovalContentHash":"%s",'
            '"expectedConfirmationCardHash":"%s","workflowContractVersion":"v1",'
            '"humanRawText":"ok","humanTurnRef":"t","aiInterpretedAsApproval":true,'
            '"agentConfidence":NaN}'
            % (str(uuid.uuid4()), "a" * 64, "b" * 64, "c" * 64),
        )
        error, _ = self.expect_failure(
            ["rule", "promote", "--approval-file", approval, "--global"]
        )
        self.assertIn("NaN", str(error))


class MemoryLifecycleTest(CommandTestCase):
    def test_search_sends_bounded_excerpt_and_resolved_key(self) -> None:
        _, client = self.run_command(
            [
                "memory",
                "search",
                "--query",
                "LlmMemoryWriteGate",
                "--top-k",
                "3",
                "--excerpt-max-chars",
                "512",
            ]
        )
        self.assertEqual(
            client.calls[-1],
            (
                "memory.search",
                {
                    "query": "LlmMemoryWriteGate",
                    "topK": 3,
                    "projectKey": "RESOLVED",
                    "excerptMaxChars": 512,
                },
            ),
        )

    def test_excerpt_bounds_are_enforced_by_the_parser(self) -> None:
        with patch("sys.stderr", new=io.StringIO()):
            for value in ("127", "1025"):
                with self.subTest(value=value), self.assertRaises(SystemExit):
                    parser().parse_args(
                        ["memory", "search", "--query", "x", "--excerpt-max-chars", value]
                    )

    def test_agent_view_is_additive_for_search(self) -> None:
        _, client = self.run_command(
            ["memory", "search", "--query", "routing", "--view", "agent"]
        )
        self.assertEqual(
            client.calls[-1],
            (
                "memory.search",
                {
                    "query": "routing",
                    "topK": 3,
                    "projectKey": "RESOLVED",
                    "view": "agent",
                },
            ),
        )


class AgentLearningCommandTest(CommandTestCase):
    def test_only_search_and_learn_are_exposed_under_memory(self) -> None:
        root_choices = parser()._subparsers._group_actions[0].choices
        self.assertNotIn("operation", root_choices)
        self.assertNotIn("context", root_choices)
        memory_choices = root_choices["memory"]._subparsers._group_actions[0].choices
        self.assertEqual(set(memory_choices), {"search", "learn"})
        for retired in ("context.resolve", "context.open", "operation.status"):
            self.assertNotIn(retired, ai_orch.ALLOWED_TOOLS)
            self.assertNotIn(retired, ai_orch._OPERATION_TOOLS)

    def test_non_learning_command_ignores_malformed_optional_workspace_binding(self) -> None:
        scanner_result = {
            "scanner.project.resolve": {
                "projectKey": "RESOLVED",
                "rootPath": str(self.workspace.root),
                "workspaceBindingId": "not-a-uuid",
            }
        }
        _, client = self.run_command(["memory", "search", "--query", "routing"], scanner_result)
        self.assertEqual(client.calls[-1][0], "memory.search")

    def test_memory_learn_forces_runtime_and_stages_before_network(self) -> None:
        expired_directory = self.workspace.staging / "learning-operations"
        expired_directory.mkdir()
        expired = expired_directory / "expired.json"
        expired.write_text("{}", encoding="utf-8")
        os.utime(expired, (0, 0))
        request = self.workspace.request(
            "learn.json",
            {
                "learningCandidates": [{
                    "kind": "change_point",
                    "summary": "Routing changes here",
                    "content": "The switch is implemented by this class.",
                    "locators": [{"kind": "symbol", "ref": "com.acme.Router#route",
                                  "signature": "route()", "path": "src/Router.java",
                                  "role": "primary_change_point"}],
                }],
            },
        )
        _, client = self.run_command(
            ["memory", "learn", "--file", request],
            {"terminal.learning.capture": {"status": "RETRYABLE_ERROR"}},
        )
        arguments = client.calls[-1][1]
        self.assertEqual(client.calls[-1][0], "terminal.learning.capture")
        self.assertEqual(arguments["learningCandidates"][0]["locators"][0]["ref"],
                         "com.acme.Router#route")
        self.assertRegex(arguments["taskRunId"], r"^[0-9a-f]{32}$")
        staged = list((self.workspace.staging / "learning-operations").glob("*.json"))
        self.assertEqual(len(staged), 1)
        self.assertFalse(expired.exists())
        self.assertTrue(Path(request).exists())
        self.assertEqual(json.loads(staged[0].read_text())["contractVersion"], "agent-learning/v2")

    def test_memory_learn_consume_removes_request_only_after_completed_receipt(self) -> None:
        request = self.workspace.request(
            "learn-consume.json",
            {
                "learningCandidates": [{
                    "kind": "behavior",
                    "summary": "Routing falls back here",
                    "content": "The resolver uses the fallback when primary is absent.",
                    "locators": [{"kind": "file", "ref": "src/Router.java",
                                  "role": "supporting"}],
                }],
            },
        )

        output, _ = self.run_command(
            ["memory", "learn", "--file", request, "--consume"],
            {"terminal.learning.capture": {"status": "ACCEPTED", "created": 1}},
        )

        self.assertEqual(output["result"]["status"], "ACCEPTED")
        self.assertFalse(Path(request).exists())
        self.assertEqual(len(list((self.workspace.staging / "learning-operations").glob("*.json"))), 0)

    def test_memory_learn_consume_preserves_request_for_non_completed_receipt(self) -> None:
        request = self.workspace.request(
            "learn-retryable.json",
            {
                "learningCandidates": [{
                    "kind": "behavior",
                    "summary": "Routing falls back here",
                    "content": "The resolver uses the fallback when primary is absent.",
                    "locators": [{"kind": "file", "ref": "src/Router.java",
                                  "role": "supporting"}],
                }],
            },
        )

        output, _ = self.run_command(
            ["memory", "learn", "--file", request, "--consume"],
            {"terminal.learning.capture": {"status": "RETRYABLE_ERROR"}},
        )

        self.assertTrue(Path(request).exists())
        self.assertIn("preserved", output["warnings"][0])

    def test_memory_learn_retry_reuses_bridge_owned_task_run_id(self) -> None:
        request = self.workspace.request(
            "learn-retry.json",
            {"learningCandidates": [{
                "kind": "behavior", "summary": "Stable retry", "content": "Retry the same batch safely.",
                "locators": [{"kind": "file", "ref": "src/Retry.java", "role": "supporting"}],
            }]},
        )
        results = {"terminal.learning.capture": {"status": "RETRYABLE_ERROR"}}

        _, first = self.run_command(["memory", "learn", "--file", request], results)
        _, second = self.run_command(["memory", "learn", "--file", request], results)

        self.assertEqual(first.calls[-1][1]["taskRunId"], second.calls[-1][1]["taskRunId"])

    def test_learning_files_reject_bridge_owned_fields_at_any_depth(self) -> None:
        request = self.workspace.request(
            "bad-learn.json",
            {
                "learningCandidates": [{
                    "kind": "behavior", "summary": "s", "content": "c",
                    "locators": [{"kind": "file", "ref": "src/A.java", "role": "supporting"}],
                    "reference": {"details": "long", "projectKey": "OTHER"},
                }],
            },
        )
        error, client = self.expect_failure(["memory", "learn", "--file", request])
        self.assertIn("owned by the bridge", str(error))
        self.assertNotIn("terminal.learning.capture", [name for name, _ in client.calls])

    def test_memory_learn_rejects_more_than_three_candidates_and_runtime_spoofing(self) -> None:
        candidate = {
            "kind": "behavior", "summary": "s", "content": "c",
            "locators": [{"kind": "file", "ref": "src/A.java", "role": "supporting"}],
        }
        request = self.workspace.request(
            "too-many.json",
            {"learningCandidates": [candidate, candidate, candidate, candidate]},
        )
        self.expect_failure(["memory", "learn", "--file", request])
        with self.assertRaises(ai_orch.BridgeError):
            ai_orch.validate_dispatch("memory.learn", {
                "learningCandidates": [candidate],
                "producerRuntime": "claude_code"
            })

    def test_memory_learn_rejects_invalid_candidate_and_locator_values(self) -> None:
        candidate = {
            "kind": "behavior", "summary": "s", "content": "c",
            "locators": [{"kind": "file", "ref": "src/A.java", "role": "supporting"}],
        }
        invalid_values = (
            ("kind", "invented", "unsupported kind"),
            ("locator_kind", "invented", "locator kind"),
            ("path", "../A.java", "repository-relative"),
        )
        for field, value, expected in invalid_values:
            with self.subTest(field=field):
                invalid = json.loads(json.dumps(candidate))
                if field == "kind":
                    invalid["kind"] = value
                elif field == "locator_kind":
                    invalid["locators"][0]["kind"] = value
                else:
                    invalid["locators"][0]["ref"] = value
                request = self.workspace.request(
                    f"invalid-{field}.json",
                    {"learningCandidates": [invalid]},
                )
                error, client = self.expect_failure(["memory", "learn", "--file", request])
                self.assertIn(expected, str(error))
                self.assertNotIn("terminal.learning.capture", [name for name, _ in client.calls])

    def test_memory_learn_rejects_bounded_list_entries_over_160_characters(self) -> None:
        candidate = {
            "kind": "behavior", "summary": "s", "content": "c",
            "locators": [{"kind": "file", "ref": "src/A.java", "role": "supporting"}],
        }
        for field in ("appliesWhen", "limitations", "reusableFor"):
            with self.subTest(field=field):
                invalid = dict(candidate, **{field: ["x" * 161]})
                request = self.workspace.request(
                    f"invalid-{field}-length.json",
                    {"learningCandidates": [invalid]},
                )
                error, client = self.expect_failure(["memory", "learn", "--file", request])
                self.assertIn(f"{field} entry exceeds 160 characters", str(error))
                self.assertNotIn("terminal.learning.capture", [name for name, _ in client.calls])

    def test_memory_learn_accepts_context_local_correction_ref(self) -> None:
        candidate = {
            "kind": "behavior", "summary": "corrected", "content": "fresh evidence",
            "locators": [{"kind": "file", "ref": "src/A.java", "role": "supporting"}],
            "correctionRef": "k1",
        }
        request = self.workspace.request(
            "correction.json",
            {"learningCandidates": [candidate]},
        )

        _, client = self.run_command(["memory", "learn", "--file", request])

        sent = client.calls[-1][1]["learningCandidates"][0]
        self.assertEqual(sent["correctionRef"], "k1")

        partial = dict(candidate, correctionRef="memory-id")
        bad_request = self.workspace.request(
            "partial-correction.json",
            {"learningCandidates": [partial]},
        )
        error, partial_client = self.expect_failure(["memory", "learn", "--file", bad_request])
        self.assertIn("active-context kN ref", str(error))
        self.assertNotIn("terminal.learning.capture", [name for name, _ in partial_client.calls])

    def test_different_semantic_payloads_are_staged_independently(self) -> None:
        base = {
            "learningCandidates": [{"kind": "behavior", "summary": "s", "content": "first",
                                     "locators": [{"kind": "file", "ref": "src/A.java",
                                                   "role": "supporting"}]}],
        }
        first = self.workspace.request("learn-first.json", base)
        self.run_command(["memory", "learn", "--file", first],
                         {"terminal.learning.capture": {"status": "RETRYABLE_ERROR"}})
        second_payload = dict(base)
        second_payload["learningCandidates"] = [dict(base["learningCandidates"][0], content="different")]
        second = self.workspace.request("learn-second.json", second_payload)
        _, client = self.run_command(["memory", "learn", "--file", second],
                                     {"terminal.learning.capture": {"status": "RETRYABLE_ERROR"}})
        self.assertIn("terminal.learning.capture", [name for name, _ in client.calls])
        staged = list((self.workspace.staging / "learning-operations").glob("*.json"))
        self.assertEqual(len(staged), 2)

class RequestFileContractTest(CommandTestCase):
    def _write(self, name: str, text: str) -> str:
        path = self.workspace.staging / name
        path.write_text(text, encoding="utf-8")
        return str(path)

    def test_duplicate_keys_trailing_tokens_and_non_objects_are_refused(self) -> None:
        for text in (
            '{"content":"a","content":"b"}',
            '{"content":"a"} {"content":"b"}',
            '["content"]',
            '{"content":NaN}',
            '{"content":Infinity}',
        ):
            with self.subTest(text=text), self.assertRaises(ai_orch.BridgeError):
                ai_orch.read_request_file(
                    self._write("r.json", text), str(self.workspace.root), "request"
                )

    def test_bridge_owned_fields_are_refused(self) -> None:
        for field in (
            "projectKey",
            "scope",
            "rootPath",
            "providerOverride",
            "tool",
            "endpoint",
            "command",
            "Authorization",
        ):
            payload = {"content": "a", field: "x"}
            with self.subTest(field=field), self.assertRaises(ai_orch.BridgeError) as captured:
                ai_orch.take_fields(payload, required=("content",))
            self.assertIn(field, str(captured.exception))

    def test_unknown_and_missing_fields_are_refused(self) -> None:
        with self.assertRaises(ai_orch.BridgeError):
            ai_orch.take_fields({"content": "a", "surprise": 1}, required=("content",))
        with self.assertRaises(ai_orch.BridgeError):
            ai_orch.take_fields({}, required=("content",))

    def test_request_file_must_stay_inside_an_allowed_root(self) -> None:
        with tempfile.TemporaryDirectory() as other:
            outside = Path(other) / "outside.json"
            outside.write_text('{"content":"a"}', encoding="utf-8")
            with self.assertRaises(ai_orch.BridgeError) as captured:
                ai_orch.read_request_file(
                    str(outside), str(self.workspace.root), "request"
                )
            self.assertNotIn("content", str(captured.exception))

    def test_oversize_and_non_utf8_request_files_are_refused(self) -> None:
        big = self.workspace.staging / "big.json"
        big.write_text(
            json.dumps({"content": "x" * (ai_orch.MAX_REQUEST_FILE_BYTES + 10)}),
            encoding="utf-8",
        )
        with self.assertRaises(ai_orch.BridgeError):
            ai_orch.read_request_file(str(big), str(self.workspace.root), "request")
        broken = self.workspace.staging / "broken.json"
        broken.write_bytes(b'{"content":"\xff\xfe"}')
        with self.assertRaises(ai_orch.BridgeError):
            ai_orch.read_request_file(str(broken), str(self.workspace.root), "request")

    def test_a_directory_is_not_a_request_file(self) -> None:
        directory = self.workspace.staging / "adir"
        directory.mkdir()
        with self.assertRaises(ai_orch.BridgeError):
            ai_orch.read_request_file(str(directory), str(self.workspace.root), "request")


class RetiredCodebaseAndReferenceExamples:
    def test_symbol_neighbors_maps_the_cli_ref_alias_to_symbol_id(self) -> None:
        _, client = self.run_command(
            ["codebase", "symbol", "neighbors", "--ref", "MemoryService#write", "--depth", "2"]
        )
        self.assertEqual(
            client.calls[-1],
            (
                "codebase.symbol.neighbors",
                {"symbolId": "MemoryService#write", "depth": 2, "projectKey": "RESOLVED"},
            ),
        )

    def test_codebase_read_is_not_a_command_or_an_allowed_tool(self) -> None:
        self.assertNotIn("codebase.read", ai_orch.ALLOWED_TOOLS)
        with patch("sys.stderr", new=io.StringIO()), self.assertRaises(SystemExit):
            parser().parse_args(["codebase", "read", "--ref", "x"])

    def test_impact_and_baseline_bind_the_resolved_project(self) -> None:
        _, client = self.run_command(["codebase", "impact", "analyze", "--ref", "A#b"])
        self.assertEqual(client.calls[-1][1]["projectKey"], "RESOLVED")
        _, client = self.run_command(
            ["codebase", "baseline", "search", "--query", "write gate", "--top-k", "5"]
        )
        self.assertEqual(client.calls[-1][1]["topK"], 5)

    def test_reference_commands_are_local_user_and_send_no_project_key(self) -> None:
        output, client = self.run_command(["reference", "list", "--dir", "Pilotlama/Şubat"])
        self.assertEqual(output["context"]["scopeKind"], ai_orch.SCOPE_LOCAL_USER)
        self.assertNotIn("projectKey", output["context"])
        self.assertEqual(client.calls[-1], ("reference.list", {"dir": "Pilotlama/Şubat"}))
        self.assertNotIn(
            "scanner.project.resolve", [call[0] for call in client.calls]
        )

    def test_reference_list_accepts_the_empty_shared_root(self) -> None:
        _, client = self.run_command(["reference", "list"])
        self.assertEqual(client.calls[-1], ("reference.list", {}))

    def test_reference_paths_reject_traversal_and_accept_turkish_nfc(self) -> None:
        self.assertEqual(
            ai_orch.reference_path("Pilotlama/şubeler/işlem.md", "path"),
            "Pilotlama/şubeler/işlem.md",
        )
        for bad in (
            "../escape.md",
            "/absolute.md",
            "trailing/",
            "a//b.md",
            "back\\slash.md",
            "colon:name.md",
            "a/" * 40 + "b.md",
        ):
            with self.subTest(bad=bad), self.assertRaises(ai_orch.BridgeError):
                ai_orch.reference_path(bad, "path")

    def test_reference_write_carries_the_expected_hash_contract(self) -> None:
        request = self.workspace.request(
            "ref.json",
            {
                "relativePath": "Pilotlama/prosedür.md",
                "content": "# Prosedür\n",
                "expectedHash": "d" * 64,
            },
        )
        _, client = self.run_command(["reference", "write", "--file", request])
        self.assertEqual(client.calls[-1][1]["expectedHash"], "d" * 64)
        bad = self.workspace.request(
            "ref2.json",
            {"relativePath": "a.md", "content": "x", "expectedHash": "NOTAHASH"},
        )
        self.expect_failure(["reference", "write", "--file", bad])

    def test_graph_retrieval_is_bounded_and_project_bound(self) -> None:
        request = self.workspace.request(
            "graph.json", {"query": "write gate", "topK": 5, "maxDepth": 2}
        )
        _, client = self.run_command(["context", "graph", "retrieve", "--file", request])
        self.assertEqual(
            client.calls[-1][1],
            {"query": "write gate", "projectKey": "RESOLVED", "topK": 5, "maxDepth": 2},
        )
        too_deep = self.workspace.request(
            "graph2.json", {"query": "x", "maxDepth": 9}
        )
        self.expect_failure(["context", "graph", "retrieve", "--file", too_deep])


class JobAndPersonalMemoryTest(CommandTestCase):
    """LAST_JOB, named jobs and personal memory are local-user, explicit-request tools."""

    def assert_local_user(self, output: dict) -> None:
        self.assertEqual(output["context"]["scopeKind"], ai_orch.SCOPE_LOCAL_USER)
        self.assertNotIn("projectKey", output["context"])

    def expect_rejected_before_network(self, argv: list[str]) -> ai_orch.BridgeError:
        error, client = self.expect_failure(argv)
        # Neither a project resolution nor the target tool was called.
        self.assertEqual(client.calls, [])
        return error

    def test_last_job_save_replaces_the_singleton_with_one_exact_call(self) -> None:
        request = self.workspace.request("last.json", {"content": "tam devir teslim"})
        output, client = self.run_command(["last-job", "save", "--file", request])
        self.assertEqual(client.calls, [("last_job.save", {"content": "tam devir teslim"})])
        self.assertEqual(output["command"], "last-job.save")
        self.assert_local_user(output)

    def test_last_job_save_rejects_unknown_owned_missing_and_oversize_fields(self) -> None:
        for name, payload, message in (
            ("unknown.json", {"content": "c", "title": "t"}, "unsupported request field: title"),
            ("owned.json", {"content": "c", "projectKey": "P"}, "owned by the bridge"),
            ("missing.json", {}, "missing required request field: content"),
            ("blank.json", {"content": "  "}, "content must not be blank"),
            (
                "oversize.json",
                {"content": "x" * (ai_orch.MAX_LAST_JOB_CONTENT_BYTES + 1)},
                "exceeds",
            ),
        ):
            with self.subTest(name=name):
                request = self.workspace.request(name, payload)
                error = self.expect_rejected_before_network(
                    ["last-job", "save", "--file", request]
                )
                self.assertIn(message, str(error))

    def test_last_job_get_sends_no_arguments_and_reports_a_real_absence(self) -> None:
        output, client = self.run_command(
            ["last-job", "get"], {"last_job.get": {"found": False}}
        )
        self.assertEqual(client.calls, [("last_job.get", {})])
        self.assert_local_user(output)
        self.assertTrue(any("No saved record" in w for w in output["warnings"]))

    def test_last_job_get_marks_a_recovered_checkpoint_as_untrusted(self) -> None:
        output, _ = self.run_command(
            ["last-job", "get"],
            {"last_job.get": {"found": True, "content": "c", "updatedAt": "2026-09-01T00:00:00Z"}},
        )
        self.assertEqual(output["result"]["content"], "c")
        self.assertTrue(any("untrusted" in w for w in output["warnings"]))

    def test_job_save_without_job_id_creates_and_sends_exact_payload(self) -> None:
        request = self.workspace.request(
            "job.json", {"title": "Başlık", "summary": "özet", "content": "tam içerik"}
        )
        output, client = self.run_command(["job", "save", "--file", request])
        self.assertEqual(
            client.calls,
            [
                (
                    "job_memory.save",
                    {"title": "Başlık", "summary": "özet", "content": "tam içerik"},
                )
            ],
        )
        self.assert_local_user(output)
        self.assertEqual(output["warnings"], [])

    def test_job_save_with_explicit_uuid_upserts_that_job(self) -> None:
        job_id = str(uuid.uuid4())
        request = self.workspace.request(
            "job.json",
            {"title": "t", "summary": "s", "content": "c", "jobId": job_id.upper()},
        )
        _, client = self.run_command(["job", "save", "--file", request])
        self.assertEqual(
            client.calls,
            [
                (
                    "job_memory.save",
                    {"title": "t", "summary": "s", "content": "c", "jobId": job_id},
                )
            ],
        )

    def test_job_save_rejects_bad_payloads_before_network(self) -> None:
        for name, payload, message in (
            (
                "bad-id.json",
                {"title": "t", "summary": "s", "content": "c", "jobId": "not-a-uuid"},
                "jobId must be a UUID",
            ),
            (
                "unknown.json",
                {"title": "t", "summary": "s", "content": "c", "tags": ["x"]},
                "unsupported request field: tags",
            ),
            (
                "owned.json",
                {"title": "t", "summary": "s", "content": "c", "scope": "project"},
                "owned by the bridge",
            ),
            ("missing.json", {"title": "t", "content": "c"}, "missing required request field: summary"),
            (
                # 300 Turkish characters is 600 bytes and exceeds the 512-byte title limit.
                "title.json",
                {"title": "ş" * 300, "summary": "s", "content": "c"},
                "title exceeds 512 UTF-8 bytes",
            ),
            (
                "summary.json",
                {"title": "t", "summary": "s" * (ai_orch.MAX_JOB_SUMMARY_BYTES + 1), "content": "c"},
                "summary exceeds",
            ),
        ):
            with self.subTest(name=name):
                request = self.workspace.request(name, payload)
                error = self.expect_rejected_before_network(["job", "save", "--file", request])
                self.assertIn(message, str(error))

    def test_job_save_surfaces_server_redaction(self) -> None:
        request = self.workspace.request("job.json", {"title": "t", "summary": "s", "content": "c"})
        output, _ = self.run_command(
            ["job", "save", "--file", request],
            {"job_memory.save": {"saved": True, "jobId": str(uuid.uuid4()), "redacted": True}},
        )
        self.assertTrue(any("redacted" in w for w in output["warnings"]))

    def test_job_search_sends_the_clue_and_bounded_top_k(self) -> None:
        output, client = self.run_command(["job", "search", "--query", "kart ödeme işi"])
        self.assertEqual(
            client.calls, [("job_memory.search", {"query": "kart ödeme işi", "topK": 3})]
        )
        self.assert_local_user(output)
        _, client = self.run_command(["job", "search", "--query", "x", "--top-k", "10"])
        self.assertEqual(client.calls, [("job_memory.search", {"query": "x", "topK": 10})])
        for top_k in ("0", "11", "abc"):
            with self.subTest(top_k=top_k), patch("sys.stderr", new=io.StringIO()):
                with self.assertRaises(SystemExit):
                    parser().parse_args(["job", "search", "--query", "x", "--top-k", top_k])
        error = self.expect_rejected_before_network(
            ["job", "search", "--query", "q" * (ai_orch.MAX_JOB_QUERY_BYTES + 1)]
        )
        self.assertIn("query exceeds", str(error))

    def test_job_get_requires_an_exact_uuid(self) -> None:
        job_id = str(uuid.uuid4())
        output, client = self.run_command(["job", "get", "--job-id", job_id])
        self.assertEqual(client.calls, [("job_memory.get", {"jobId": job_id})])
        self.assert_local_user(output)
        error = self.expect_rejected_before_network(["job", "get", "--job-id", "latest"])
        self.assertIn("job ID must be a UUID", str(error))
        output, _ = self.run_command(
            ["job", "get", "--job-id", job_id], {"job_memory.get": {"found": False}}
        )
        self.assertTrue(any("No saved record" in w for w in output["warnings"]))

    def test_personal_memory_save_is_local_user_and_reports_stored_text(self) -> None:
        request = self.workspace.request("personal.json", {"content": "Bir kişisel not"})
        output, client = self.run_command(["personal-memory", "save", "--file", request])
        self.assertEqual(client.calls, [("personal_memory.save", {"content": "Bir kişisel not"})])
        self.assert_local_user(output)
        self.assertTrue(any("redacted text" in w for w in output["warnings"]))

    def test_personal_memory_save_rejects_bad_payloads_before_network(self) -> None:
        for name, payload, message in (
            ("unknown.json", {"content": "c", "tags": ["x"]}, "unsupported request field: tags"),
            ("owned.json", {"content": "c", "rootPath": "/x"}, "owned by the bridge"),
            ("missing.json", {}, "missing required request field: content"),
            (
                "oversize.json",
                {"content": "ş" * (ai_orch.MAX_PERSONAL_MEMORY_CONTENT_BYTES // 2 + 1)},
                "content exceeds 8192 UTF-8 bytes",
            ),
        ):
            with self.subTest(name=name):
                request = self.workspace.request(name, payload)
                error = self.expect_rejected_before_network(
                    ["personal-memory", "save", "--file", request]
                )
                self.assertIn(message, str(error))

    def test_personal_memory_search_sends_query_and_top_k(self) -> None:
        output, client = self.run_command(
            ["personal-memory", "search", "--query", "hangi editörü kullanıyorum", "--top-k", "5"]
        )
        self.assertEqual(
            client.calls,
            [("personal_memory.search", {"query": "hangi editörü kullanıyorum", "topK": 5})],
        )
        self.assert_local_user(output)
        _, client = self.run_command(["personal-memory", "search", "--query", "x"])
        self.assertEqual(client.calls, [("personal_memory.search", {"query": "x", "topK": 3})])
        with patch("sys.stderr", new=io.StringIO()), self.assertRaises(SystemExit):
            parser().parse_args(["personal-memory", "search", "--query", "x", "--top-k", "11"])

    def test_dispatch_allowlist_rejects_unknown_and_invalid_arguments(self) -> None:
        job_id = str(uuid.uuid4())
        for name, arguments in (
            ("last_job.get", {"projectKey": "P"}),
            ("last_job.save", {"content": "c", "projectKey": "P"}),
            ("last_job.save", {}),
            ("job_memory.save", {"title": "t", "summary": "s", "content": "c", "tags": []}),
            ("job_memory.save", {"title": "t", "summary": "s", "content": "c", "jobId": "x"}),
            ("job_memory.save", {"title": "t", "content": "c"}),
            ("job_memory.search", {"query": "q", "topK": 11}),
            ("job_memory.search", {"query": "q", "topK": True}),
            ("job_memory.search", {"query": "q", "projectKey": "P"}),
            ("job_memory.get", {"jobId": "latest"}),
            ("job_memory.get", {"jobId": job_id, "view": "agent"}),
            ("personal_memory.save", {"content": "c", "scope": "global"}),
            ("personal_memory.save", {"content": " "}),
            ("personal_memory.search", {"query": "q", "topK": 0}),
            ("personal_memory.search", {"query": "q", "providerOverride": "claude"}),
        ):
            with self.subTest(name=name, arguments=arguments):
                with self.assertRaises(ai_orch.BridgeError):
                    ai_orch.validate_dispatch(name, arguments)
        # The exact server argument shapes are accepted.
        for name, arguments in (
            ("last_job.get", {}),
            ("last_job.save", {"content": "c"}),
            ("job_memory.save", {"title": "t", "summary": "s", "content": "c", "jobId": job_id}),
            ("job_memory.search", {"query": "q", "topK": 3}),
            ("job_memory.get", {"jobId": job_id}),
            ("personal_memory.save", {"content": "c"}),
            ("personal_memory.search", {"query": "q", "topK": 10}),
        ):
            with self.subTest(name=name, accepted=True):
                ai_orch.validate_dispatch(name, arguments)

    def test_main_prints_the_envelope_for_a_job_command(self) -> None:
        client = FakeClient({"job_memory.search": [{"jobId": "j", "title": "t", "score": 0.9}]})
        stdout = io.StringIO()
        with patch.object(
            ai_orch.memory_bridge, "current_repo_root", return_value=str(self.workspace.root)
        ), patch.object(ai_orch, "AiOrchClient", return_value=_ContextClient(client)), patch(
            "sys.stdout", new=stdout
        ), patch("sys.stderr", new=io.StringIO()):
            code = ai_orch.main(["job", "search", "--query", "kart"])
        self.assertEqual(code, ai_orch.EXIT_OK)
        output = json.loads(stdout.getvalue())
        self.assertEqual(output["command"], "job.search")
        self.assertEqual(output["result"], [{"jobId": "j", "title": "t", "score": 0.9}])
        self.assertEqual(client.calls, [("job_memory.search", {"query": "kart", "topK": 3})])


class RetiredPlanCheckTest(unittest.TestCase):
    def test_plan_check_command_and_tool_are_gone(self) -> None:
        with patch("sys.stderr", new=io.StringIO()), self.assertRaises(SystemExit):
            parser().parse_args(["plan", "check", "--file", "plan.json"])
        self.assertNotIn("plans.check", ai_orch.ALLOWED_TOOLS)
        self.assertNotIn("plans.check", ai_orch._TOOL_ARGUMENTS)
        self.assertNotIn("plan.check", ai_orch._BUILDERS)
        self.assertNotIn("plan.check", ai_orch.LEGACY_ENVELOPE_OPERATIONS)


class _ContextClient:
    def __init__(self, inner) -> None:
        self.inner = inner

    def __enter__(self):
        return self.inner

    def __exit__(self, *args) -> None:
        return None


class DiagnosticTest(CommandTestCase):
    def test_capabilities_lists_only_allowlisted_commands(self) -> None:
        output, _ = self.run_command(["capabilities"])
        commands = {entry["command"] for entry in output["result"]["commands"]}
        self.assertEqual(
            commands,
            {
                "rule.instructions",
                "rule.draft",
                "rule.preview",
                "rule.promote",
                "memory.search",
                "memory.learn",
                "reference.list",
                "reference.read",
                "last-job.save",
                "last-job.get",
                "job.save",
                "job.search",
                "job.get",
                "personal-memory.save",
                "personal-memory.search",
            },
        )
        by_command = {
            entry["command"]: entry for entry in output["result"]["commands"]
        }
        for command, tool in (
            ("last-job.save", "last_job.save"),
            ("last-job.get", "last_job.get"),
            ("job.save", "job_memory.save"),
            ("job.search", "job_memory.search"),
            ("job.get", "job_memory.get"),
            ("personal-memory.save", "personal_memory.save"),
            ("personal-memory.search", "personal_memory.search"),
        ):
            self.assertEqual(by_command[command]["operation"], tool)
            self.assertEqual(by_command[command]["scopeKind"], ai_orch.SCOPE_LOCAL_USER)
        self.assertTrue(
            all(
                entry["availability"] in ("available", "unavailable")
                for entry in output["result"]["commands"]
            )
        )
        self.assertTrue(any("not proof" in w for w in output["warnings"]))

    def test_doctor_is_read_only_and_reports_missing_operations(self) -> None:
        client = FakeClient()
        client.list_tools = lambda: [{"name": "scanner.project.resolve"}]  # type: ignore[assignment]
        args = parser().parse_args(["doctor"])
        with patch.object(
            ai_orch.memory_bridge, "current_repo_root", return_value=str(self.workspace.root)
        ):
            output = ai_orch.execute(args, client)  # type: ignore[arg-type]
        self.assertEqual(client.calls, [])
        self.assertIn("rules.instructions", output["result"]["missingOperations"])
        self.assertTrue(any("read-only" in w for w in output["warnings"]))


class ScopeBoundaryTest(CommandTestCase):
    def test_no_command_reaches_a_scanner_scan_or_admin_operation(self) -> None:
        for name in (
            "scanner.scan",
            "scanner.scan.start",
            "memory.approve",
            "memory.review.pending",
            "memory.review.decide",
            "knowledge.ingest",
            "architecture.understand.start",
        ):
            with self.subTest(name=name), self.assertRaises(ai_orch.BridgeError):
                ai_orch.validate_dispatch(name, {})

    def test_provider_override_is_never_sent(self) -> None:
        with self.assertRaises(ai_orch.BridgeError):
            ai_orch.validate_dispatch(
                "memory.search",
                {"query": "x", "projectKey": "P", "providerOverride": "claude"},
            )

    def test_every_operation_maps_to_an_allowlisted_tool(self) -> None:
        for operation, tool in ai_orch._OPERATION_TOOLS.items():
            with self.subTest(operation=operation):
                self.assertIn(tool, ai_orch.ALLOWED_TOOLS)
                self.assertIn(tool, ai_orch._TOOL_ARGUMENTS)
                self.assertIn(operation, ai_orch._SCOPE_KINDS)
                self.assertIn(operation, ai_orch._BUILDERS)


class LinkedReferenceReadTest(CommandTestCase):
    def test_reference_read_is_local_user_and_sends_no_project_key(self) -> None:
        path = "Pilotlama/MOBIL_MOBILEAPP_BE/drw-cekilis-pilotlama-ve-force-update.md"
        output, client = self.run_command(["reference", "read", "--path", path])
        self.assertEqual(output["context"]["scopeKind"], ai_orch.SCOPE_LOCAL_USER)
        self.assertNotIn("projectKey", output["context"])
        self.assertEqual(client.calls, [("reference.read", {"relativePath": path})])

    def test_reference_list_is_read_only_and_shared_root_relative(self) -> None:
        output, client = self.run_command(
            ["reference", "list", "--dir", "Pilotlama/MOBIL_MOBILEAPP_BE"]
        )
        self.assertEqual(output["context"]["scopeKind"], ai_orch.SCOPE_LOCAL_USER)
        self.assertEqual(
            client.calls,
            [("reference.list", {"dir": "Pilotlama/MOBIL_MOBILEAPP_BE"})],
        )

    def test_reference_mutations_remain_unavailable(self) -> None:
        for argv in (
            ["reference", "mkdir", "--dir", "Pilotlama/x"],
            ["reference", "write", "--file", "reference.json"],
        ):
            with self.subTest(argv=argv), patch("sys.stderr", new=io.StringIO()):
                with self.assertRaises(SystemExit):
                    parser().parse_args(argv)

    def test_explicit_repo_root_must_match_a_bound_trusted_workspace(self) -> None:
        with tempfile.TemporaryDirectory() as other:
            foreign = Path(other) / "repo-b"
            foreign.mkdir()
            (foreign / ".git").mkdir()
            with patch.dict(
                os.environ, {"AI_ORCH_EXPECTED_ROOT": str(self.workspace.root)}
            ):
                with self.assertRaises(ai_orch.BridgeError):
                    ai_orch._repository_root(str(foreign.resolve()))
                self.assertEqual(
                    ai_orch._repository_root(str(self.workspace.root)),
                    str(self.workspace.root),
                )


if __name__ == "__main__":
    unittest.main()
