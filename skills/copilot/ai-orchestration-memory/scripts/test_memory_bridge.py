#!/usr/bin/env python3

from __future__ import annotations

import argparse
import io
import json
import tempfile
import unittest
import uuid
from urllib.error import HTTPError
from pathlib import Path
from unittest.mock import patch

import memory_bridge as bridge


class EndpointValidationTest(unittest.TestCase):
    def test_accepts_only_the_pinned_mcp_endpoint(self) -> None:
        self.assertEqual(
            bridge.validate_endpoint("http://127.0.0.1:18080/mcp"),
            "http://127.0.0.1:18080/mcp",
        )

    def test_rejects_remote_or_ambiguous_endpoints(self) -> None:
        rejected = (
            "http://localhost:18080/mcp",
            "https://127.0.0.1:18080/mcp",
            "http://example.com:18080/mcp",
            "http://user:pass@127.0.0.1:18080/mcp",
            "http://127.0.0.1:18080/api/memory",
            "http://127.0.0.1:18080/mcp?tool=memory.search",
            "http://127.0.0.1/mcp",
            "http://[::1]:99999/mcp",
        )
        for endpoint in rejected:
            with self.subTest(endpoint=endpoint), self.assertRaises(bridge.BridgeError):
                bridge.validate_endpoint(endpoint)


class WireParsingTest(unittest.TestCase):
    def test_parses_json_and_sse(self) -> None:
        payload = {"jsonrpc": "2.0", "id": 1, "result": {"ok": True}}
        encoded = json.dumps(payload).encode()
        self.assertEqual(bridge.parse_wire_response(encoded, "application/json", 1), payload)
        sse = f"id:abc\nevent:message\ndata:{json.dumps(payload)}\n\n".encode()
        self.assertEqual(bridge.parse_wire_response(sse, "text/event-stream", 1), payload)

    def test_rejects_wrong_response_id_and_invalid_utf8(self) -> None:
        wrong = json.dumps({"jsonrpc": "2.0", "id": 99, "result": {}}).encode()
        with self.assertRaises(bridge.BridgeError):
            bridge.parse_wire_response(wrong, "application/json", 1)
        with self.assertRaises(bridge.BridgeError):
            bridge.parse_wire_response(b"\xff", "application/json", 1)

    def test_decodes_json_text_and_rejects_is_error(self) -> None:
        response = {
            "result": {"content": [{"type": "text", "text": '{"projectKey":"P"}'}]}
        }
        self.assertEqual(bridge.decode_tool_result(response), {"projectKey": "P"})
        with self.assertRaises(bridge.BridgeError):
            bridge.decode_tool_result(
                {"result": {"isError": True, "content": [{"type": "text", "text": "denied"}]}}
            )


class AllowlistTest(unittest.TestCase):
    def test_allowlist_contains_no_scan_or_admin_bypass(self) -> None:
        scanner_tools = [name for name in bridge.ALLOWED_TOOLS if name.startswith("scanner.")]
        self.assertEqual(scanner_tools, ["scanner.project.resolve"])
        self.assertNotIn("memory.approve", bridge.ALLOWED_TOOLS)
        self.assertNotIn("memory.review.pending", bridge.ALLOWED_TOOLS)
        self.assertNotIn("memory.review.decide", bridge.ALLOWED_TOOLS)
        self.assertFalse(any(name.startswith("scanner.scan") for name in bridge.ALLOWED_TOOLS))

    def test_catalog_filters_unknown_and_scan_tools(self) -> None:
        catalog = bridge.filtered_catalog(
            [
                {"name": "memory.search", "description": "ok", "inputSchema": {}},
                {"name": "scanner.scan", "description": "forbidden", "inputSchema": {}},
                {"name": "memory.approve", "description": "forbidden", "inputSchema": {}},
            ]
        )
        self.assertEqual([item["name"] for item in catalog], ["memory.search"])
        self.assertNotIn("inputSchema", catalog[0])

    def test_search_accepts_canonical_and_json_style_top_k_flags(self) -> None:
        parser = bridge.build_parser()
        canonical = parser.parse_args(["search", "--query", "known issue", "--top-k", "2"])
        json_style = parser.parse_args(["search", "--query", "known issue", "--topK", "2"])
        self.assertEqual(canonical.top_k, 2)
        self.assertEqual(json_style.top_k, 2)

    def test_get_accepts_canonical_and_compatibility_id_flags(self) -> None:
        parser = bridge.build_parser()
        memory_id = str(uuid.uuid4())
        canonical = parser.parse_args(["get", "--memory-id", memory_id])
        compatibility = parser.parse_args(["get", "--id", memory_id])
        self.assertEqual(canonical.memory_id, memory_id)
        self.assertEqual(compatibility.memory_id, memory_id)

    def test_parser_has_no_endpoint_or_repo_root_override(self) -> None:
        parser = bridge.build_parser()
        with patch("sys.stderr", new=io.StringIO()):
            with self.assertRaises(SystemExit):
                parser.parse_args(["search", "/another/repo", "--query", "known issue"])
            with self.assertRaises(SystemExit):
                parser.parse_args(["--endpoint", "http://127.0.0.1:19090/mcp", "catalog"])

    def test_parser_has_no_generic_call_or_hard_delete_command(self) -> None:
        parser = bridge.build_parser()
        subparser_action = next(
            action for action in parser._actions if isinstance(action, argparse._SubParsersAction)
        )
        commands = set(subparser_action.choices)
        self.assertNotIn("call", commands)
        self.assertNotIn("delete", commands)
        self.assertNotIn("hard-delete", commands)
        self.assertIn("archive", commands)


class ProjectBoundaryTest(unittest.TestCase):
    def test_canonical_root_requires_existing_directory(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            self.assertEqual(bridge.canonical_repo_root(directory), str(Path(directory).resolve()))
        with self.assertRaises(bridge.BridgeError):
            bridge.canonical_repo_root("/definitely/not/a/repository/path")

    def test_current_root_uses_nearest_git_root(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / ".git").mkdir()
            nested = root / "a" / "b"
            nested.mkdir(parents=True)
            with patch.object(bridge.Path, "cwd", return_value=nested):
                self.assertEqual(bridge.current_repo_root(), str(root.resolve()))

    def test_copilot_guard_requires_matching_trusted_root(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            root = base / "trusted"
            other = base / "other"
            root.mkdir()
            other.mkdir()
            (root / ".git").mkdir()
            (other / ".git").mkdir()
            with patch.object(bridge.Path, "cwd", return_value=root), patch.dict(
                bridge.os.environ,
                {"AI_ORCH_COPILOT_GUARD": "1", "AI_ORCH_EXPECTED_ROOT": str(root)},
                clear=False,
            ):
                self.assertEqual(bridge.current_repo_root(), str(root.resolve()))
            with patch.object(bridge.Path, "cwd", return_value=other), patch.dict(
                bridge.os.environ,
                {"AI_ORCH_COPILOT_GUARD": "1", "AI_ORCH_EXPECTED_ROOT": str(root)},
                clear=False,
            ), self.assertRaises(bridge.BridgeError):
                bridge.current_repo_root()
            with patch.object(bridge.Path, "cwd", return_value=root), patch.dict(
                bridge.os.environ,
                {"AI_ORCH_COPILOT_GUARD": "1"},
                clear=True,
            ), self.assertRaises(bridge.BridgeError):
                bridge.current_repo_root()

    def test_project_item_is_accepted_and_cross_project_is_rejected(self) -> None:
        item = {"scope": "project", "projectKey": "P", "memoryId": str(uuid.uuid4())}
        self.assertEqual(
            bridge.validate_item_project(item, "P", allow_global=True, mutation=False), item
        )
        with self.assertRaises(bridge.BridgeError):
            bridge.validate_item_project(item, "OTHER", allow_global=True, mutation=False)

    def test_global_is_read_only_through_bridge(self) -> None:
        item = {"scope": "global", "projectKey": None, "memoryId": str(uuid.uuid4())}
        self.assertEqual(
            bridge.validate_item_project(item, "P", allow_global=True, mutation=False), item
        )
        with self.assertRaises(bridge.BridgeError):
            bridge.validate_item_project(item, "P", allow_global=True, mutation=True)


class DispatchBoundaryTest(unittest.TestCase):
    def test_dispatch_rejects_hard_delete_global_mutation_and_unknown_arguments(self) -> None:
        with self.assertRaises(bridge.BridgeError):
            bridge._validate_dispatch(
                "memory.delete", {"memoryId": str(uuid.uuid4()), "mode": "hard_delete", "reason": "x"}
            )
        with self.assertRaises(bridge.BridgeError):
            bridge._validate_dispatch(
                "memory.write",
                {
                    "summary": "s",
                    "content": "c",
                    "memoryType": "decision",
                    "scope": "global",
                    "projectKey": "P",
                },
            )
        with self.assertRaises(bridge.BridgeError):
            bridge._validate_dispatch("memory.search", {"projectKey": "P", "query": "q", "scan": True})

    def test_dispatch_allows_archive_and_rejects_redirected_root(self) -> None:
        bridge._validate_dispatch(
            "memory.delete", {"memoryId": str(uuid.uuid4()), "mode": "archive", "reason": "x"}
        )
        with patch.object(bridge, "current_repo_root", return_value="/trusted/root"):
            bridge._validate_dispatch("scanner.project.resolve", {"rootPath": "/trusted/root"})
            with self.assertRaises(bridge.BridgeError):
                bridge._validate_dispatch("scanner.project.resolve", {"rootPath": "/other/root"})

    def test_token_and_http_errors_are_not_reflected(self) -> None:
        with self.assertRaises(bridge.BridgeError):
            bridge.McpHttpClient(token="safe\r\nX-Evil: value")
        secret = "do-not-reflect-this-token"
        error = HTTPError(
            bridge.DEFAULT_ENDPOINT,
            500,
            secret,
            {},
            io.BytesIO(f"server echoed {secret}".encode()),
        )
        client = bridge.McpHttpClient(token=secret)
        with patch.object(bridge, "urlopen", side_effect=error), self.assertRaises(
            bridge.BridgeError
        ) as raised:
            client._post({"jsonrpc": "2.0"}, include_session=False)
        self.assertNotIn(secret, str(raised.exception))


class FakeClient:
    def __init__(self) -> None:
        self.calls: list[tuple[str, dict]] = []

    def initialize(self) -> dict:
        return {"serverInfo": {"name": "fake"}}

    def list_tools(self) -> list[dict]:
        return [{"name": name} for name in bridge.ALLOWED_TOOLS]

    def _call_bridge_tool(self, name: str, arguments: dict) -> dict:
        self.calls.append((name, arguments))
        if name == "scanner.project.resolve":
            return {"projectKey": "RESOLVED", "rootPath": arguments["rootPath"]}
        if name == "memory.search":
            return {"projectKey": arguments["projectKey"], "items": []}
        raise AssertionError(f"unexpected call: {name}")


class CommandConstructionTest(unittest.TestCase):
    def test_search_owns_resolved_project_key_and_top_k(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            args = bridge.build_parser().parse_args(
                ["search", "--query", "known issue", "--top-k", "3"]
            )
            client = FakeClient()
            with patch.object(bridge, "current_repo_root", return_value=str(Path(directory).resolve())):
                output = bridge.execute(args, client)  # type: ignore[arg-type]
        self.assertEqual(output["projectKey"], "RESOLVED")
        self.assertEqual(
            client.calls,
            [
                ("scanner.project.resolve", {"rootPath": str(Path(directory).resolve())}),
                (
                    "memory.search",
                    {"query": "known issue", "topK": 3, "projectKey": "RESOLVED"},
                ),
            ],
        )

    def test_confirm_stdin_does_not_require_raw_text_argument(self) -> None:
        parser = bridge.build_parser()
        args = parser.parse_args(
            [
                "confirm",
                "--memory-id",
                str(uuid.uuid4()),
                "--decision",
                "approve",
                "--ai-interpreted-as-approval",
                "--human-raw-text-stdin",
                "--agent-confidence",
                "0.99",
                "--human-turn-ref",
                "turn-1",
            ]
        )
        self.assertTrue(args.human_raw_text_stdin)
        with patch("sys.stdin", io.StringIO("evet, onaylıyorum")):
            self.assertEqual(bridge._stdin_text("human raw text"), "evet, onaylıyorum")


if __name__ == "__main__":
    unittest.main()
