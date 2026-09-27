#!/usr/bin/env python3

from __future__ import annotations

import argparse
import io
import json
import math
import tempfile
import unittest
import uuid
from pathlib import Path
from unittest.mock import patch

import ai_orch


class FakeClient:
    def __init__(self) -> None:
        self.calls: list[tuple[str, dict]] = []

    def initialize(self) -> dict:
        return {"serverInfo": {"name": "fake"}}

    def list_tools(self) -> list[dict]:
        return [{"name": name} for name in ai_orch.ALLOWED_TOOLS]

    def call_tool(self, name: str, arguments: dict) -> dict:
        self.calls.append((name, arguments))
        if name == "scanner.project.resolve":
            return {"projectKey": "RESOLVED", "rootPath": arguments["rootPath"]}
        return {"tool": name, "accepted": True}


def parser() -> argparse.ArgumentParser:
    return ai_orch.build_parser()


class ParserAndAllowlistTest(unittest.TestCase):
    def test_exposes_learning_rules_reference_reads_jobs_and_diagnostics(self) -> None:
        root_action = next(
            action for action in parser()._actions if isinstance(action, argparse._SubParsersAction)
        )
        self.assertEqual(
            set(root_action.choices),
            {
                "rule",
                "memory",
                "reference",
                "last-job",
                "job",
                "personal-memory",
                "capabilities",
                "doctor",
            },
        )
        self.assertEqual(
            set(ai_orch.ALLOWED_TOOLS),
            {
                # Internal project binding; never advertised as a command.
                "scanner.project.resolve",
                "rules.instructions",
                "rules.draft",
                "rules.preview",
                "rules.promote",
                "memory.search",
                "reference.list",
                "reference.read",
                "last_job.get",
                "last_job.save",
                "job_memory.save",
                "job_memory.search",
                "job_memory.get",
                "personal_memory.save",
                "personal_memory.search",
            },
        )
        for name in (
            "scanner.project.resolve",
            "rules.instructions",
            "rules.draft",
            "rules.preview",
            "rules.promote",
            "memory.search",
            "reference.list",
            "reference.read",
        ):
            self.assertIn(name, ai_orch.ALLOWED_TOOLS)
        for forbidden in (
            "plans.check",
            "scanner.scan",
            "memory.get",
            "memory.write",
            "memory.approve",
            "memory.review.pending",
            "memory.review.decide",
            "reference.mkdir",
            "reference.write",
            "knowledge.ingest",
        ):
            self.assertNotIn(forbidden, ai_orch.ALLOWED_TOOLS)
        self.assertEqual(len(set(ai_orch.ALLOWED_TOOLS)), len(ai_orch.ALLOWED_TOOLS))
        for retired in ("context.resolve", "context.open", "operation.status"):
            self.assertNotIn(retired, ai_orch.ALLOWED_TOOLS)
            self.assertNotIn(retired, ai_orch._OPERATION_TOOLS)

    def test_parser_has_no_endpoint_project_key_or_generic_tool_override(self) -> None:
        with patch("sys.stderr", new=io.StringIO()):
            for argv in (
                ["--endpoint", "http://example.test/mcp", "memory", "search", "--query", "x"],
                ["memory", "search", "--project-key", "OTHER", "--query", "x"],
                ["call", "rules.promote"],
            ):
                with self.subTest(argv=argv), self.assertRaises(SystemExit):
                    parser().parse_args(argv)

    def test_promote_requires_explicit_approval_flag_and_finite_confidence(self) -> None:
        base = [
            "rule",
            "promote",
            "--draft-id",
            str(uuid.uuid4()),
            "--expected-candidate-hash",
            "a" * 64,
            "--expected-approval-content-hash",
            "b" * 64,
            "--expected-confirmation-card-hash",
            "c" * 64,
            "--workflow-contract-version",
            "rule-authoring-v1",
            "--human-raw-text",
            "onayliyorum",
            "--human-turn-ref",
            "turn-1",
            "--agent-confidence",
            "0.99",
        ]
        # The approval flag is still mandatory. It is now enforced when the promote
        # arguments are built, because --approval-file is the alternative input form.
        # Both paths still fail the command with a non-zero exit.
        with self.assertRaises(ai_orch.BridgeError):
            ai_orch._b_rule_promote(parser().parse_args(base), "RESOLVED", None)
        with patch("sys.stderr", new=io.StringIO()), self.assertRaises(SystemExit):
            parser().parse_args(base + ["--ai-interpreted-as-approval", "--agent-confidence", "nan"])
        self.assertTrue(math.isfinite(ai_orch._confidence("0.99")))


class DispatchBoundaryTest(unittest.TestCase):
    def test_terminal_learning_transport_is_pinned_and_carries_private_context(self) -> None:
        class Response:
            def __enter__(self):
                return self

            def __exit__(self, exc_type, exc, traceback):
                return None

            def read(self):
                return b'{"status":"ACCEPTED","created":1}'

        with tempfile.TemporaryDirectory() as directory:
            client = ai_orch.AiOrchClient(token="secret", context_key="workspace-context")
            with patch.object(ai_orch, "urlopen", return_value=Response()) as opened:
                result = client.capture_learning(directory, "task-1", [{"kind": "behavior"}])

        request = opened.call_args.args[0]
        self.assertEqual(request.full_url, ai_orch.TERMINAL_LEARNING_ENDPOINT)
        self.assertEqual(request.get_header("X-ai-orch-client"), "copilot-cli-memory-skill")
        self.assertEqual(request.get_header("X-ai-orch-context-key"), "workspace-context")
        self.assertEqual(request.get_header("Authorization"), "Bearer secret")
        self.assertEqual(result["status"], "ACCEPTED")

    def test_tool_error_surfaces_bounded_server_validation_detail(self) -> None:
        response = {
            "result": {
                "isError": True,
                "content": [
                    {
                        "type": "text",
                        "text": "citations[0] must be a Citation object",
                    }
                ],
            }
        }
        with self.assertRaisesRegex(
            ai_orch.BridgeError, "citations.*Citation object"
        ):
            ai_orch.decode_tool_result(response)

    def test_inherited_memory_dispatch_is_closed(self) -> None:
        client = ai_orch.AiOrchClient()
        with self.assertRaises(ai_orch.BridgeError):
            # Not initialized and not a legal argument shape: the inherited memory
            # allowlist cannot be reused to reach a tool behind ai_orch validation.
            client._call_bridge_tool("memory.search", {"query": "x"})

    def test_rejects_unknown_tools_arguments_and_non_exact_promotions(self) -> None:
        with self.assertRaises(ai_orch.BridgeError):
            ai_orch.validate_dispatch("memory.search", {"query": "x"})
        with self.assertRaises(ai_orch.BridgeError):
            # plans.check was retired from the server and is no longer allowlisted.
            ai_orch.validate_dispatch("plans.check", {"projectKey": "P", "planJson": "{}"})
        with self.assertRaises(ai_orch.BridgeError):
            ai_orch.validate_dispatch(
                "job_memory.get", {"jobId": str(uuid.uuid4()), "tool": "rules.draft"}
            )
        promote = {
            "projectKey": "P",
            "draftId": str(uuid.uuid4()),
            "expectedCandidateHash": "a" * 64,
            "expectedApprovalContentHash": "b" * 64,
            "expectedConfirmationCardHash": "c" * 64,
            "workflowContractVersion": "rule-authoring-v1",
            "humanRawText": "onayliyorum",
            "humanTurnRef": "turn-1",
            "aiInterpretedAsApproval": False,
            "agentConfidence": 0.99,
        }
        with self.assertRaises(ai_orch.BridgeError):
            ai_orch.validate_dispatch("rules.promote", promote)
        promote["aiInterpretedAsApproval"] = True
        promote["agentConfidence"] = float("nan")
        with self.assertRaises(ai_orch.BridgeError):
            ai_orch.validate_dispatch("rules.promote", promote)

    def test_resolve_requires_a_canonical_git_repository_root(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "repo"
            root.mkdir()
            (root / ".git").mkdir()
            root = root.resolve()
            ai_orch.validate_dispatch(
                "scanner.project.resolve", {"rootPath": str(root)}
            )
            with self.assertRaises(ai_orch.BridgeError):
                ai_orch.validate_dispatch(
                    "scanner.project.resolve", {"rootPath": str(root / "missing")}
                )


class CommandConstructionTest(unittest.TestCase):
    def _workspace(self, base: Path) -> Path:
        root = base / "repo"
        root.mkdir()
        (root / ".git").mkdir()
        return root

    def test_explicit_repo_root_must_be_the_root_of_a_git_repository(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            not_a_repo = base / "not-a-repo"
            not_a_repo.mkdir()
            with self.assertRaises(ai_orch.BridgeError):
                ai_orch._repository_root(str(not_a_repo))

    def test_rule_draft_preview_and_promote_use_exact_server_arguments(self) -> None:
        draft_id = str(uuid.uuid4())
        with tempfile.TemporaryDirectory() as directory:
            root = self._workspace(Path(directory))
            candidate = root / "rule.json"
            candidate_text = '{"statement":"Repository changes require tests"}'
            candidate.write_text(candidate_text, encoding="utf-8")

            draft_client = FakeClient()
            with patch.object(ai_orch.memory_bridge, "current_repo_root", return_value=str(root)):
                ai_orch.execute(
                    parser().parse_args(["rule", "draft", "--file", "rule.json"]),
                    draft_client,  # type: ignore[arg-type]
                )
            self.assertEqual(
                draft_client.calls[-1],
                (
                    "rules.draft",
                    {"projectKey": "RESOLVED", "candidateJson": candidate_text},
                ),
            )

            preview_client = FakeClient()
            with patch.object(ai_orch.memory_bridge, "current_repo_root", return_value=str(root)):
                ai_orch.execute(
                    parser().parse_args(["rule", "preview", "--draft-id", draft_id]),
                    preview_client,  # type: ignore[arg-type]
                )
            self.assertEqual(
                preview_client.calls[-1],
                ("rules.preview", {"projectKey": "RESOLVED", "draftId": draft_id}),
            )

            promote_client = FakeClient()
            promote_args = parser().parse_args(
                [
                    "rule",
                    "promote",
                    "--draft-id",
                    draft_id,
                    "--expected-candidate-hash",
                    "a" * 64,
                    "--expected-approval-content-hash",
                    "b" * 64,
                    "--expected-confirmation-card-hash",
                    "c" * 64,
                    "--workflow-contract-version",
                    "rule-authoring-v1",
                    "--human-raw-text",
                    "evet, onayliyorum",
                    "--human-turn-ref",
                    "turn-42",
                    "--ai-interpreted-as-approval",
                    "--agent-confidence",
                    "0.99",
                ]
            )
            with patch.object(ai_orch.memory_bridge, "current_repo_root", return_value=str(root)):
                ai_orch.execute(promote_args, promote_client)  # type: ignore[arg-type]
            self.assertEqual(
                promote_client.calls[-1],
                (
                    "rules.promote",
                    {
                        "projectKey": "RESOLVED",
                        "draftId": draft_id,
                        "expectedCandidateHash": "a" * 64,
                        "expectedApprovalContentHash": "b" * 64,
                        "expectedConfirmationCardHash": "c" * 64,
                        "workflowContractVersion": "rule-authoring-v1",
                        "humanRawText": "evet, onayliyorum",
                        "humanTurnRef": "turn-42",
                        "aiInterpretedAsApproval": True,
                        "agentConfidence": 0.99,
                    },
                ),
            )

    def test_json_file_may_be_absolute_outside_target_repository_but_must_be_object(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            root = self._workspace(base)
            outside = base / "outside.json"
            outside.write_text("{}", encoding="utf-8")
            self.assertEqual(
                ai_orch._read_project_json(str(outside), str(root), "candidateJson"),
                "{}",
            )

            invalid = root / "invalid.json"
            invalid.write_text("[]", encoding="utf-8")
            with self.assertRaises(ai_orch.BridgeError):
                ai_orch._read_project_json(str(invalid), str(root), "candidateJson")

            with self.assertRaises(ai_orch.BridgeError):
                ai_orch._read_project_json(str(base / "missing.json"), str(root), "candidateJson")


if __name__ == "__main__":
    unittest.main()
