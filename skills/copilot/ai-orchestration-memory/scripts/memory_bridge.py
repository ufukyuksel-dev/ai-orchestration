#!/usr/bin/env python3
"""Narrow AI Orchestration MemoryAI client for Copilot CLI.

This is intentionally not a generic MCP client. It exposes a fixed set of
project-memory commands and never dispatches a model-supplied tool name. It is
a safety guard for normal CLI use, not a sandbox for hostile local Python code.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import uuid
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterable
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen


DEFAULT_ENDPOINT = "http://127.0.0.1:18080/mcp"
PROTOCOL_VERSION = "2025-03-26"
CLIENT_NAME = "copilot-cli-memory-skill"
CLIENT_VERSION = "1.0.0"
DEFAULT_TIMEOUT_SECONDS = 10.0

ALLOWED_TOOLS = (
    "scanner.project.resolve",
    "memory.search",
    "memory.get",
    "memory.write",
    "memory.update",
    "memory.pending",
    "memory.confirm",
    "memory.delete",
    "mcp.transcript.submit",
)


class BridgeError(RuntimeError):
    """Expected, safely reportable bridge failure."""


def validate_endpoint(value: str) -> str:
    if value != DEFAULT_ENDPOINT:
        raise BridgeError("the bridge endpoint is pinned to the local AI Orchestration MCP service")
    return value


def canonical_repo_root(value: str | Path) -> str:
    path = Path(value).expanduser()
    try:
        resolved = path.resolve(strict=True)
    except (OSError, RuntimeError) as exc:
        raise BridgeError("the current repository root cannot be resolved") from exc
    if not resolved.is_dir():
        raise BridgeError("the current repository root is not a directory")
    if not resolved.is_absolute():
        raise BridgeError("repository root must resolve to an absolute path")
    return str(resolved)


def current_repo_root() -> str:
    """Bind operations to the nearest Git root for the bridge process's cwd."""
    try:
        cwd = Path.cwd().resolve(strict=True)
    except (OSError, RuntimeError) as exc:
        raise BridgeError("the bridge working directory cannot be resolved") from exc
    for candidate in (cwd, *cwd.parents):
        if (candidate / ".git").exists():
            actual_root = canonical_repo_root(candidate)
            break
    else:
        actual_root = canonical_repo_root(cwd)
    guarded = os.environ.get("AI_ORCH_COPILOT_GUARD") == "1"
    expected = os.environ.get("AI_ORCH_EXPECTED_ROOT")
    if guarded and not expected:
        raise BridgeError("Copilot bridge invocation is missing its trusted workspace binding")
    if expected:
        expected_root = canonical_repo_root(expected)
        if actual_root != expected_root:
            raise BridgeError("bridge working directory does not match the trusted Copilot workspace")
    return actual_root


def parse_wire_response(
    body: bytes,
    content_type: str = "",
    expected_id: int | None = None,
) -> dict[str, Any]:
    try:
        text = body.decode("utf-8").strip()
    except UnicodeDecodeError as exc:
        raise BridgeError("MCP response was not valid UTF-8") from exc
    if not text:
        return {}
    if "text/event-stream" not in content_type.lower() and text.startswith("{"):
        return _select_response([_load_json_object(text)], expected_id)

    events: list[dict[str, Any]] = []
    data_lines: list[str] = []
    for line in text.splitlines() + [""]:
        if line.startswith("data:"):
            data_lines.append(line[5:].lstrip())
        elif not line.strip() and data_lines:
            events.append(_load_json_object("\n".join(data_lines)))
            data_lines = []
    if not events:
        raise BridgeError("MCP response was neither JSON nor a valid SSE data event")
    return _select_response(events, expected_id)


def _select_response(events: list[dict[str, Any]], expected_id: int | None) -> dict[str, Any]:
    if expected_id is None:
        return events[-1]
    matches = [event for event in events if event.get("id") == expected_id]
    if len(matches) != 1:
        raise BridgeError("MCP response did not contain exactly one matching request ID")
    return matches[0]


def _load_json_object(text: str) -> dict[str, Any]:
    try:
        value = json.loads(text)
    except json.JSONDecodeError as exc:
        raise BridgeError("MCP response contained invalid JSON") from exc
    if not isinstance(value, dict):
        raise BridgeError("MCP response JSON must be an object")
    return value


def decode_tool_result(response: dict[str, Any]) -> Any:
    if "error" in response:
        error = response.get("error")
        if isinstance(error, dict):
            code = error.get("code")
            raise BridgeError(f"MCP JSON-RPC error code {code}")
        raise BridgeError("JSON-RPC error")

    result = response.get("result")
    if not isinstance(result, dict):
        raise BridgeError("MCP tool response is missing a result object")

    text_parts = [
        part.get("text", "")
        for part in result.get("content", [])
        if isinstance(part, dict) and part.get("type") == "text"
    ]
    if result.get("isError") is True:
        raise BridgeError("MCP tool returned isError=true; inspect the server audit log")

    if len(text_parts) == 1:
        candidate = text_parts[0]
        try:
            return json.loads(candidate)
        except json.JSONDecodeError:
            return candidate
    if text_parts:
        return {"text": text_parts}
    if "structuredContent" in result:
        return result["structuredContent"]
    return result


@dataclass
class HttpResponse:
    status: int
    headers: Any
    body: bytes


class McpHttpClient:
    def __init__(self, token: str | None = None, timeout: float = DEFAULT_TIMEOUT_SECONDS,
                 context_key: str | None = None):
        self.endpoint = validate_endpoint(DEFAULT_ENDPOINT)
        self.token = _validated_header_secret(token)
        self.timeout = timeout
        self.context_key = _validated_session_id(context_key) if context_key else None
        self.session_id: str | None = None
        self.next_id = 1
        self.initialized = False

    def __enter__(self) -> "McpHttpClient":
        return self

    def __exit__(self, exc_type: Any, exc: Any, traceback: Any) -> None:
        self.close()

    def initialize(self) -> dict[str, Any]:
        request_id = self._id()
        payload = {
            "jsonrpc": "2.0",
            "id": request_id,
            "method": "initialize",
            "params": {
                "protocolVersion": PROTOCOL_VERSION,
                "capabilities": {},
                "clientInfo": {"name": CLIENT_NAME, "version": CLIENT_VERSION},
            },
        }
        http_response = self._post(payload, include_session=False)
        session_id = http_response.headers.get("Mcp-Session-Id")
        if not session_id or not str(session_id).strip():
            raise BridgeError("MCP initialize response did not include Mcp-Session-Id")
        self.session_id = _validated_session_id(str(session_id))
        response = parse_wire_response(
            http_response.body,
            http_response.headers.get("Content-Type", ""),
            expected_id=request_id,
        )
        if "error" in response:
            decode_tool_result(response)
        result = response.get("result")
        if not isinstance(result, dict):
            raise BridgeError("MCP initialize response is missing result")

        self._post(
            {"jsonrpc": "2.0", "method": "notifications/initialized", "params": {}},
            include_session=True,
        )
        self.initialized = True
        return result

    def list_tools(self) -> list[dict[str, Any]]:
        response = self._rpc("tools/list", {})
        result = response.get("result")
        tools = result.get("tools") if isinstance(result, dict) else None
        if not isinstance(tools, list):
            raise BridgeError("tools/list response is missing tools")
        return [tool for tool in tools if isinstance(tool, dict)]

    def _call_bridge_tool(self, name: str, arguments: dict[str, Any]) -> Any:
        _validate_dispatch(name, arguments)
        response = self._rpc("tools/call", {"name": name, "arguments": arguments})
        return decode_tool_result(response)

    def close(self) -> None:
        if not self.session_id:
            return
        try:
            request = Request(self.endpoint, method="DELETE", headers=self._headers(include_session=True))
            urlopen(request, timeout=self.timeout).close()
        except (HTTPError, URLError, OSError):
            pass
        finally:
            self.session_id = None
            self.initialized = False

    def _rpc(self, method: str, params: dict[str, Any]) -> dict[str, Any]:
        if not self.initialized or not self.session_id:
            raise BridgeError("MCP client is not initialized")
        request_id = self._id()
        payload = {"jsonrpc": "2.0", "id": request_id, "method": method, "params": params}
        http_response = self._post(payload, include_session=True)
        response = parse_wire_response(
            http_response.body,
            http_response.headers.get("Content-Type", ""),
            expected_id=request_id,
        )
        if "error" in response:
            error = response.get("error")
            if isinstance(error, dict):
                raise BridgeError(f"MCP JSON-RPC error code {error.get('code')}")
            raise BridgeError("JSON-RPC error")
        return response

    def _post(self, payload: dict[str, Any], include_session: bool) -> HttpResponse:
        data = json.dumps(payload, separators=(",", ":")).encode("utf-8")
        request = Request(
            self.endpoint,
            data=data,
            method="POST",
            headers=self._headers(include_session=include_session),
        )
        try:
            with urlopen(request, timeout=self.timeout) as response:
                return HttpResponse(response.status, response.headers, response.read())
        except HTTPError as exc:
            exc.read(1024)
            raise BridgeError(f"MCP HTTP request failed with status {exc.code}") from exc
        except URLError as exc:
            raise BridgeError("cannot reach the pinned local MCP endpoint") from exc
        except OSError as exc:
            raise BridgeError("MCP transport failed") from exc

    def _headers(self, include_session: bool) -> dict[str, str]:
        headers = {
            "Content-Type": "application/json",
            "Accept": "application/json, text/event-stream",
            "X-AI-Orch-Client": CLIENT_NAME,
        }
        if include_session:
            if not self.session_id:
                raise BridgeError("MCP session is missing")
            headers["Mcp-Session-Id"] = self.session_id
        if self.token:
            headers["Authorization"] = f"Bearer {self.token}"
        if self.context_key:
            headers["X-AI-Orch-Context-Key"] = self.context_key
        return headers

    def _id(self) -> int:
        value = self.next_id
        self.next_id += 1
        return value


def _validated_header_secret(value: str | None) -> str | None:
    if not value or not value.strip():
        return None
    candidate = value.strip()
    if len(candidate) > 4096 or any(ord(char) < 32 or ord(char) == 127 for char in candidate):
        raise BridgeError("AI_ORCH_MCP_TOKEN contains invalid header characters")
    return candidate


def _validated_session_id(value: str) -> str:
    candidate = value.strip()
    if not candidate or len(candidate) > 512:
        raise BridgeError("MCP session ID is invalid")
    if any(ord(char) < 32 or ord(char) == 127 for char in candidate):
        raise BridgeError("MCP session ID contains invalid header characters")
    return candidate


_TOOL_ARGUMENTS: dict[str, frozenset[str]] = {
    "scanner.project.resolve": frozenset({"rootPath"}),
    "memory.search": frozenset({"query", "topK", "projectKey"}),
    "memory.get": frozenset({"memoryId"}),
    "memory.write": frozenset(
        {"summary", "content", "memoryType", "tags", "sourceRef", "scope", "projectKey"}
    ),
    "memory.update": frozenset(
        {
            "memoryId",
            "summary",
            "content",
            "tags",
            "confidence",
            "scope",
            "projectKey",
            "sourceRef",
            "reason",
        }
    ),
    "memory.pending": frozenset({"projectKey", "limit", "offset"}),
    "memory.confirm": frozenset(
        {
            "memoryId",
            "decision",
            "aiInterpretedAsApproval",
            "humanRawText",
            "agentConfidence",
            "humanTurnRef",
            "projectKey",
            "editedText",
            "reason",
        }
    ),
    "memory.delete": frozenset({"memoryId", "mode", "reason"}),
    "mcp.transcript.submit": frozenset({"content", "sessionId", "source", "projectKey"}),
}


def _validate_dispatch(name: str, arguments: dict[str, Any]) -> None:
    allowed_arguments = _TOOL_ARGUMENTS.get(name)
    if name not in ALLOWED_TOOLS or allowed_arguments is None:
        raise BridgeError(f"tool is not allowed by the memory bridge: {name}")
    unknown = set(arguments) - allowed_arguments
    if unknown:
        raise BridgeError(f"unsupported argument for {name}: {sorted(unknown)[0]}")
    if name == "scanner.project.resolve" and arguments.get("rootPath") != current_repo_root():
        raise BridgeError("project resolution must use the bridge process's current repository root")
    if name == "memory.delete" and arguments.get("mode") != "archive":
        raise BridgeError("the bridge permits archive mode only")
    if name in {"memory.write", "memory.update"} and arguments.get("scope") != "project":
        raise BridgeError("the bridge permits project-scoped mutations only")
    if name == "mcp.transcript.submit" and arguments.get("source") != "copilot":
        raise BridgeError("the bridge transcript source must be copilot")
    if "projectKey" in allowed_arguments and name != "memory.get":
        project_key = arguments.get("projectKey")
        if not isinstance(project_key, str) or not project_key.strip():
            raise BridgeError(f"{name} requires the resolved project key")


def filtered_catalog(tools: Iterable[dict[str, Any]]) -> list[dict[str, Any]]:
    by_name = {
        str(tool.get("name")): tool
        for tool in tools
        if isinstance(tool.get("name"), str) and tool.get("name") in ALLOWED_TOOLS
    }
    return [
        {
            "name": name,
            "description": by_name[name].get("description", ""),
        }
        for name in ALLOWED_TOOLS
        if name in by_name
    ]


def require_tool(tools: Iterable[dict[str, Any]], name: str) -> None:
    names = {tool.get("name") for tool in tools}
    if name not in names:
        raise BridgeError(f"required server tool is unavailable: {name}")


def resolve_project(client: McpHttpClient, tools: list[dict[str, Any]], repo_root: str) -> tuple[str, Any]:
    require_tool(tools, "scanner.project.resolve")
    root = canonical_repo_root(repo_root)
    result = client._call_bridge_tool("scanner.project.resolve", {"rootPath": root})
    if not isinstance(result, dict):
        raise BridgeError("scanner.project.resolve returned a non-object result")
    project_key = result.get("projectKey")
    if not isinstance(project_key, str) or not project_key.strip():
        raise BridgeError("scanner.project.resolve did not return projectKey")
    return project_key.strip(), result


def _memory_item(payload: Any) -> dict[str, Any]:
    if not isinstance(payload, dict):
        raise BridgeError("memory.get returned a non-object result")
    item = payload.get("item")
    if isinstance(item, dict):
        return item
    return payload


def validate_item_project(payload: Any, project_key: str, *, allow_global: bool, mutation: bool) -> dict[str, Any]:
    item = _memory_item(payload)
    scope = str(item.get("scope") or "").strip().lower()
    item_project = str(item.get("projectKey") or "").strip()
    if scope == "global":
        if mutation or not allow_global:
            raise BridgeError("bridge does not mutate global memory")
        return item
    if item_project != project_key:
        raise BridgeError("memory item does not belong to the resolved project")
    return item


def validated_item(
    client: McpHttpClient,
    tools: list[dict[str, Any]],
    memory_id: str,
    project_key: str,
    *,
    mutation: bool,
) -> dict[str, Any]:
    require_tool(tools, "memory.get")
    _validate_uuid(memory_id)
    payload = client._call_bridge_tool("memory.get", {"memoryId": memory_id})
    return validate_item_project(payload, project_key, allow_global=not mutation, mutation=mutation)


def _validate_uuid(value: str) -> str:
    try:
        return str(uuid.UUID(value))
    except ValueError as exc:
        raise BridgeError("memory ID must be a UUID") from exc


def _bounded_int(value: str, minimum: int, maximum: int, label: str) -> int:
    try:
        parsed = int(value)
    except ValueError as exc:
        raise argparse.ArgumentTypeError(f"{label} must be an integer") from exc
    if parsed < minimum or parsed > maximum:
        raise argparse.ArgumentTypeError(f"{label} must be between {minimum} and {maximum}")
    return parsed


def _confidence(value: str) -> float:
    try:
        parsed = float(value)
    except ValueError as exc:
        raise argparse.ArgumentTypeError("confidence must be a number") from exc
    if parsed < 0.0 or parsed > 1.0:
        raise argparse.ArgumentTypeError("confidence must be between 0 and 1")
    return parsed


def _nonblank(value: str, label: str) -> str:
    if not value or not value.strip():
        raise BridgeError(f"{label} must not be blank")
    return value.strip()


def _stdin_text(label: str) -> str:
    if sys.stdin.isatty():
        raise BridgeError(f"{label} requested from stdin, but stdin is a terminal")
    return _nonblank(sys.stdin.read(), label)


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="ai-orch-memory",
        description="No-scan, project-bound AI Orchestration MemoryAI bridge.",
    )
    subparsers = parser.add_subparsers(dest="command", required=True)

    subparsers.add_parser("catalog", help="show only bridge-allowed server tools")

    subparsers.add_parser("resolve", help="resolve the current Git/workspace root without scanning")

    search = subparsers.add_parser("search", help="search compact project-memory previews")
    search.add_argument("--query", required=True)
    search.add_argument(
        "--top-k",
        "--topK",
        dest="top_k",
        default=3,
        type=lambda value: _bounded_int(value, 1, 5, "top-k"),
    )

    get = subparsers.add_parser("get", help="get one project/global memory record")
    get.add_argument("--memory-id", "--id", dest="memory_id", required=True)

    write = subparsers.add_parser("write", help="propose durable project-scoped memory")
    write.add_argument("--summary", required=True)
    write_content = write.add_mutually_exclusive_group(required=True)
    write_content.add_argument("--content")
    write_content.add_argument("--content-stdin", action="store_true")
    write.add_argument(
        "--memory-type",
        required=True,
        choices=("rule", "preference", "correction", "decision", "anti_pattern"),
    )
    write.add_argument("--tag", action="append", default=[])
    write.add_argument("--source-ref")

    pending = subparsers.add_parser("pending", help="list project-scoped pending memory cards")
    pending.add_argument("--limit", default=5, type=lambda value: _bounded_int(value, 1, 25, "limit"))
    pending.add_argument("--offset", default=0, type=lambda value: _bounded_int(value, 0, 10000, "offset"))

    confirm = subparsers.add_parser("confirm", help="apply an explicit human decision to pending memory")
    confirm.add_argument("--memory-id", required=True)
    confirm.add_argument("--decision", required=True, choices=("approve", "reject", "edit"))
    interpreted = confirm.add_mutually_exclusive_group(required=True)
    interpreted.add_argument(
        "--ai-interpreted-as-approval", dest="interpreted_approval", action="store_true"
    )
    interpreted.add_argument(
        "--ai-interpreted-as-rejection", dest="interpreted_approval", action="store_false"
    )
    raw_text = confirm.add_mutually_exclusive_group(required=True)
    raw_text.add_argument("--human-raw-text")
    raw_text.add_argument("--human-raw-text-stdin", action="store_true")
    confirm.add_argument("--agent-confidence", required=True, type=_confidence)
    confirm.add_argument("--human-turn-ref", required=True)
    confirm.add_argument("--edited-text")
    confirm.add_argument("--reason")

    update = subparsers.add_parser("update", help="repair an existing project memory record")
    update.add_argument("--memory-id", required=True)
    update.add_argument("--reason", required=True)
    update.add_argument("--summary")
    update_content = update.add_mutually_exclusive_group()
    update_content.add_argument("--content")
    update_content.add_argument("--content-stdin", action="store_true")
    update_tags = update.add_mutually_exclusive_group()
    update_tags.add_argument("--tag", action="append")
    update_tags.add_argument("--clear-tags", action="store_true")
    update.add_argument("--confidence", type=_confidence)
    update.add_argument("--source-ref")

    archive = subparsers.add_parser("archive", help="recoverably archive one project memory record")
    archive.add_argument("--memory-id", required=True)
    archive.add_argument("--reason", required=True)

    transcript = subparsers.add_parser("transcript", help="submit durable transcript candidates")
    transcript.add_argument("--session-id", required=True)
    transcript_content = transcript.add_mutually_exclusive_group(required=True)
    transcript_content.add_argument("--content")
    transcript_content.add_argument("--content-stdin", action="store_true")

    return parser

def execute(args: argparse.Namespace, client: McpHttpClient) -> dict[str, Any]:
    initialize_result = client.initialize()
    tools = client.list_tools()
    if args.command == "catalog":
        return {
            "success": True,
            "command": "catalog",
            "server": initialize_result.get("serverInfo", {}),
            "allowedTools": filtered_catalog(tools),
        }

    project_key, resolution = resolve_project(client, tools, current_repo_root())
    if args.command == "resolve":
        return {
            "success": True,
            "command": "resolve",
            "projectKey": project_key,
            "result": resolution,
        }

    command_result = _execute_memory_command(args, client, tools, project_key)
    return {
        "success": True,
        "command": args.command,
        "projectKey": project_key,
        "result": command_result,
    }


def _execute_memory_command(
    args: argparse.Namespace,
    client: McpHttpClient,
    tools: list[dict[str, Any]],
    project_key: str,
) -> Any:
    if args.command == "search":
        require_tool(tools, "memory.search")
        return client._call_bridge_tool(
            "memory.search",
            {"query": _nonblank(args.query, "query"), "topK": args.top_k, "projectKey": project_key},
        )
    if args.command == "get":
        return validated_item(client, tools, args.memory_id, project_key, mutation=False)
    if args.command == "write":
        require_tool(tools, "memory.write")
        content = _stdin_text("content") if args.content_stdin else _nonblank(args.content, "content")
        arguments: dict[str, Any] = {
            "summary": _nonblank(args.summary, "summary"),
            "content": content,
            "memoryType": args.memory_type,
            "scope": "project",
            "projectKey": project_key,
        }
        if args.tag:
            arguments["tags"] = [_nonblank(tag, "tag") for tag in args.tag]
        if args.source_ref:
            arguments["sourceRef"] = _nonblank(args.source_ref, "source-ref")
        return client._call_bridge_tool("memory.write", arguments)
    if args.command == "pending":
        require_tool(tools, "memory.pending")
        return client._call_bridge_tool(
            "memory.pending",
            {"projectKey": project_key, "limit": args.limit, "offset": args.offset},
        )
    if args.command == "confirm":
        item = validated_item(client, tools, args.memory_id, project_key, mutation=True)
        status = str(item.get("status") or "").strip().lower()
        if status not in {"pending", "pending_review"}:
            raise BridgeError("memory.confirm requires a pending project memory")
        if args.decision == "approve" and args.interpreted_approval is not True:
            raise BridgeError("approve requires --ai-interpreted-as-approval")
        if args.decision == "reject" and args.interpreted_approval is not False:
            raise BridgeError("reject requires --ai-interpreted-as-rejection")
        if args.decision == "edit" and not args.edited_text:
            raise BridgeError("edit requires --edited-text")
        raw_text = (
            _stdin_text("human raw text")
            if args.human_raw_text_stdin
            else _nonblank(args.human_raw_text, "human raw text")
        )
        arguments = {
            "memoryId": _validate_uuid(args.memory_id),
            "decision": args.decision,
            "aiInterpretedAsApproval": args.interpreted_approval,
            "humanRawText": raw_text,
            "agentConfidence": args.agent_confidence,
            "humanTurnRef": _nonblank(args.human_turn_ref, "human-turn-ref"),
            "projectKey": project_key,
        }
        if args.edited_text:
            arguments["editedText"] = _nonblank(args.edited_text, "edited-text")
        if args.reason:
            arguments["reason"] = _nonblank(args.reason, "reason")
        require_tool(tools, "memory.confirm")
        return client._call_bridge_tool("memory.confirm", arguments)
    if args.command == "update":
        validated_item(client, tools, args.memory_id, project_key, mutation=True)
        arguments = {
            "memoryId": _validate_uuid(args.memory_id),
            "reason": _nonblank(args.reason, "reason"),
            "projectKey": project_key,
            "scope": "project",
        }
        if args.summary is not None:
            arguments["summary"] = _nonblank(args.summary, "summary")
        if args.content_stdin:
            arguments["content"] = _stdin_text("content")
        elif args.content is not None:
            arguments["content"] = _nonblank(args.content, "content")
        if args.clear_tags:
            arguments["tags"] = []
        elif args.tag is not None:
            arguments["tags"] = [_nonblank(tag, "tag") for tag in args.tag]
        if args.confidence is not None:
            arguments["confidence"] = args.confidence
        if args.source_ref is not None:
            arguments["sourceRef"] = _nonblank(args.source_ref, "source-ref")
        changed = set(arguments) - {"memoryId", "reason", "projectKey", "scope"}
        if not changed:
            raise BridgeError("memory.update requires at least one replacement field")
        require_tool(tools, "memory.update")
        return client._call_bridge_tool("memory.update", arguments)
    if args.command == "archive":
        validated_item(client, tools, args.memory_id, project_key, mutation=True)
        require_tool(tools, "memory.delete")
        return client._call_bridge_tool(
            "memory.delete",
            {
                "memoryId": _validate_uuid(args.memory_id),
                "mode": "archive",
                "reason": _nonblank(args.reason, "reason"),
            },
        )
    if args.command == "transcript":
        require_tool(tools, "mcp.transcript.submit")
        content = _stdin_text("transcript") if args.content_stdin else _nonblank(args.content, "content")
        return client._call_bridge_tool(
            "mcp.transcript.submit",
            {
                "content": content,
                "sessionId": _nonblank(args.session_id, "session-id"),
                "source": "copilot",
                "projectKey": project_key,
            },
        )
    raise BridgeError(f"unsupported bridge command: {args.command}")


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    try:
        token = os.environ.get("AI_ORCH_MCP_TOKEN")
        with McpHttpClient(token=token) as client:
            output = execute(args, client)
        json.dump(output, sys.stdout, ensure_ascii=False, indent=2, sort_keys=True)
        sys.stdout.write("\n")
        return 0
    except BridgeError as exc:
        json.dump(
            {"success": False, "command": getattr(args, "command", None), "error": str(exc)},
            sys.stderr,
            ensure_ascii=False,
            sort_keys=True,
        )
        sys.stderr.write("\n")
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
