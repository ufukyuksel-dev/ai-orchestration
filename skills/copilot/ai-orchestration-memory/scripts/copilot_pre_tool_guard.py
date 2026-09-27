#!/usr/bin/env python3
"""Copilot preToolUse guard for trusted AI Orchestration terminal bridges.

The hook deliberately logs nothing because tool arguments can contain secrets.
It emits only a fixed decision when a protected boundary is crossed.

Structure:

* ``evaluate`` is the runtime-independent decision core.
* ``normalize_event`` / ``render_decision`` are the runtime adapters for the
  Copilot CLI and the VS Code agent hook envelopes.

This hook is defence in depth only. It is not the server's authorization, not an
operating-system sandbox, and not proof of enforcement: the runtime may disable
it, fail to discover it, or time out fail-open. A script path inside a checkout
the user can edit is not an immutable root of trust.
"""

from __future__ import annotations

import json
import re
import shlex
import shutil
import sys
from pathlib import Path
from typing import Any


_SCAN_TOOL = re.compile(r"(?:^|\.)scanner\.scan(?:\.|$)")
_SCAN_TEXT = re.compile(
    r"(?<![a-z0-9])scanner(?:[._ -]+)scan"
    r"(?:[._ -]+(?:start|status|result|diagnostics|cancel))?(?![a-z0-9])",
    re.IGNORECASE,
)
_ADMIN_MEMORY_TOOL = re.compile(
    r"(?:^|\.)memory\.(?:approve|review\.(?:pending|decide))(?:\.|$)"
)
_ADMIN_MEMORY_TEXT = re.compile(
    r"(?<![a-z0-9])memory(?:[._ -]+)(?:approve|review(?:[._ -]+)(?:pending|decide))"
    r"(?![a-z0-9])",
    re.IGNORECASE,
)
_HARD_DELETE_TEXT = re.compile(r"(?<![a-z0-9])hard(?:[._ -]+)delete(?![a-z0-9])", re.IGNORECASE)
_MEMORY_CONTEXT_TEXT = re.compile(r"memory|mcp|ai-orch", re.IGNORECASE)
_RAW_MEMORY_MUTATION_TEXT = re.compile(
    r"(?<![a-z0-9])memory(?:[._ -]+)(?:write|update|delete|confirm)(?![a-z0-9])",
    re.IGNORECASE,
)
_RAW_MCP_CLIENT_TEXT = re.compile(r"(?:curl|wget|httpie|/mcp)", re.IGNORECASE)
_SHELL_CONTROL_TOKEN = re.compile(r"[;&|<>()]+")
_TRUSTED_LAUNCHER_NAMES = ("ai-orch-memory", "ai_orch")

# Denial reasons must tell the agent how to succeed, otherwise it abandons a
# mandatory step instead of retrying in the supported shape.
_RETRY_AS_ONE_COMMAND = (
    "AI Orchestration commands must be exactly ONE direct launcher command with "
    "no lookup, chaining, pipe, redirection or substitution. Retry as a single "
    "command, for example: ai_orch rule instructions --scope effective"
)

# Registered AI Orchestration server operations. A native call to any of these
# is refused for Copilot: the terminal launcher is the only supported surface.
# Matching is exact on the normalized name, or on an explicit service-prefixed
# name, so an unrelated MCP server is never blocked by word similarity alone.
AI_ORCH_OPERATIONS = frozenset(
    {
        "scanner.project.resolve",
        "scanner.scan",
        "scanner.scan.start",
        "scanner.scan.status",
        "scanner.scan.result",
        "scanner.scan.diagnostics",
        "scanner.scan.cancel",
        "rules.instructions",
        "rules.draft",
        "rules.preview",
        "rules.promote",
        "memory.search",
        "memory.get",
        "memory.write",
        "memory.update",
        "memory.pending",
        "memory.confirm",
        "memory.delete",
        "memory.approve",
        "memory.relation.write",
        "memory.learn",
        "memory.review.pending",
        "memory.review.decide",
        "mcp.transcript.submit",
        "codebase.baseline.search",
        "codebase.symbol.get",
        "codebase.symbol.neighbors",
        "codebase.impact.analyze",
        "codebase.diagnose",
        "context.graph.retrieve",
        "context.resolve",
        "context.open",
        "operation.status",
        "context.semantic.retrieve",
        "reference.list",
        "reference.read",
        "reference.mkdir",
        "reference.write",
        "last.job.get",
        "last.job.save",
        "job.memory.get",
        "job.memory.save",
        "job.memory.search",
        "personal.memory.save",
        "personal.memory.search",
        "knowledge.ask",
        "knowledge.cite",
        "knowledge.ingest",
        "architecture.understand.start",
        "architecture.understand.status",
        "architecture.understand.result",
    }
)

# Service identity markers seen in prefixed MCP tool names.
_SERVICE_MARKERS = ("ai.orchestration", "aiorchestration", "ai.orch")

# Shell tool names across the supported runtimes.
_SHELL_TOOL_NAMES = {
    "bash",
    "shell",
    "powershell",
    "run.in.terminal",
    "runintermnal",
    "terminal",
    "execute.command",
    "copilot.run.in.terminal",
}

# Valid canonical ai_orch groups. A command outside the published contract is
# refused before it can reach the bridge.
_AI_ORCH_GROUPS = {
    "project",
    "scan",
    "rule",
    "memory",
    "codebase",
    "reference",
    "context",
    "last-job",
    "job",
    "personal-memory",
    "knowledge",
    "operation",
    "capabilities",
    "doctor",
}


# Commands that can themselves perform a raw service call.
_RAW_CLIENT_COMMANDS = {
    "curl",
    "wget",
    "http",
    "https",
    "httpie",
    "nc",
    "ncat",
    "socat",
    "openssl",
    "psql",
    "mysql",
    "mongosh",
    "redis-cli",
    "python",
    "python3",
    "node",
    "ruby",
    "perl",
    "php",
    "deno",
    "bun",
}

# Wrappers that only shift the real command one token to the right.
_COMMAND_WRAPPERS = {"sudo", "env", "command", "call", "exec", "run", "nohup", "time"}


def _shell_tokens(command: str) -> list[str]:
    try:
        lexer = shlex.shlex(command, posix=True, punctuation_chars=";&|<>()")
        lexer.whitespace_split = True
        return list(lexer)
    except ValueError:
        return command.split()


def _effective_command(tokens: list[str]) -> str:
    """The program the shell would actually run, skipping simple wrappers."""
    for token in tokens:
        if not token or token.startswith("-"):
            continue
        base = token.replace("\\", "/").rsplit("/", 1)[-1].lower()
        if base in _COMMAND_WRAPPERS:
            continue
        return base
    return ""


def _is_service_execution(command: str, operation_pattern: "re.Pattern[str]") -> bool:
    """Distinguish running a service operation from merely naming it in text.

    Searching for an API name, reading a fixture or running the project's own
    tests must not be blocked. Only an execution position counts: the program
    being run is the operation itself, or a raw client/interpreter that could
    perform the call.
    """
    tokens = _shell_tokens(command)
    effective = _effective_command(tokens)
    if not effective:
        return False
    if effective in _RAW_CLIENT_COMMANDS:
        return True
    return bool(operation_pattern.fullmatch(effective) or operation_pattern.match(effective))


def _normalize_tool_name(value: Any) -> str:
    return re.sub(r"[^a-z0-9]+", ".", str(value or "").lower()).strip(".")


def is_ai_orchestration_tool(normalized: str) -> bool:
    """True only for this service's operations, not any tool mentioning memory."""
    if not normalized:
        return False
    if normalized in AI_ORCH_OPERATIONS:
        return True
    for marker in _SERVICE_MARKERS:
        index = normalized.find(marker)
        if index < 0:
            continue
        suffix = normalized[index + len(marker) :].strip(".")
        if not suffix:
            continue
        if suffix in AI_ORCH_OPERATIONS:
            return True
        # Prefixed registrations often flatten dots, e.g. memory_search.
        flattened = {operation.replace(".", "") for operation in AI_ORCH_OPERATIONS}
        if suffix.replace(".", "") in flattened:
            return True
    return False


def _decode_tool_args(value: Any) -> tuple[dict[str, Any], str]:
    if isinstance(value, dict):
        return value, json.dumps(value, separators=(",", ":"), ensure_ascii=True)
    if not isinstance(value, str):
        return {}, ""
    try:
        decoded = json.loads(value)
    except json.JSONDecodeError:
        return {}, value
    if isinstance(decoded, dict):
        return decoded, value
    return {}, value


def _fixed_deny(reason: str) -> dict[str, str]:
    return {"permissionDecision": "deny", "permissionDecisionReason": reason}


def _trusted_repo_root(value: str) -> str:
    try:
        cwd = Path(value).resolve(strict=True)
    except (OSError, RuntimeError) as exc:
        raise ValueError("workspace root cannot be resolved") from exc
    if not cwd.is_dir():
        raise ValueError("workspace root is not a directory")
    for candidate in (cwd, *cwd.parents):
        if (candidate / ".git").exists():
            return str(candidate)
    return str(cwd)


def _trusted_bridge_path() -> str:
    try:
        path = (Path(__file__).resolve(strict=True).parent / "memory_bridge.py").resolve(strict=True)
    except (OSError, RuntimeError) as exc:
        raise ValueError("trusted bridge path cannot be resolved") from exc
    if not path.is_file():
        raise ValueError("trusted bridge path is not a file")
    return str(path)


def _trusted_ai_orch_path() -> str:
    try:
        path = (Path(__file__).resolve(strict=True).parent / "ai_orch.py").resolve(strict=True)
    except (OSError, RuntimeError) as exc:
        raise ValueError("trusted ai_orch path cannot be resolved") from exc
    if not path.is_file():
        raise ValueError("trusted ai_orch path is not a file")
    return str(path)


def _trusted_launcher_path(launcher_name: str) -> str:
    if launcher_name == "ai-orch-memory":
        return _trusted_bridge_path()
    if launcher_name == "ai_orch":
        return _trusted_ai_orch_path()
    raise ValueError("launcher name is not trusted")


def _verify_trusted_launcher(launcher_name: str = "ai-orch-memory") -> None:
    candidate = shutil.which(launcher_name)
    if not candidate:
        raise ValueError("trusted launcher is unavailable")
    try:
        resolved = Path(candidate).resolve(strict=True)
    except (OSError, RuntimeError) as exc:
        raise ValueError("trusted launcher cannot be resolved") from exc
    if str(resolved) != _trusted_launcher_path(launcher_name):
        raise ValueError("launcher does not target this checkout")


def _validate_ai_orch_contract(tokens: list[str]) -> str | None:
    """Reject an ai_orch invocation outside the published command contract."""
    if not tokens or tokens[0] != "ai_orch":
        return None
    arguments = [token for token in tokens[1:] if not token.startswith("-")]
    if not arguments:
        return "An ai_orch command without a subcommand was blocked."
    if arguments[0] not in _AI_ORCH_GROUPS:
        return "An unknown ai_orch command group was blocked."
    flags = [token for token in tokens[1:] if token.startswith("--")]
    for flag in flags:
        name = flag.split("=", 1)[0]
        if name in ("--endpoint", "--project-key", "--tool", "--provider-override"):
            return "Bridge-owned ai_orch overrides are not permitted."
    return None


def _bridge_command_decision(
    payload: dict[str, Any], arguments: dict[str, Any]
) -> dict[str, Any] | None:
    command = arguments.get("command")
    if not isinstance(command, str) or not command.strip():
        return None
    try:
        lexer = shlex.shlex(command, posix=True, punctuation_chars=";&|<>()")
        lexer.whitespace_split = True
        tokens = list(lexer)
    except ValueError:
        return (
            _fixed_deny("A malformed AI Orchestration command was blocked. " + _RETRY_AS_ONE_COMMAND)
            if any(name in command for name in _TRUSTED_LAUNCHER_NAMES)
            else None
        )
    if not tokens:
        return (
            _fixed_deny("An empty AI Orchestration bridge command was blocked.")
            if any(name in command for name in _TRUSTED_LAUNCHER_NAMES)
            else None
        )
    launcher_tokens = [
        token
        for token in tokens
        if token in _TRUSTED_LAUNCHER_NAMES
        or any(
            token.replace("\\", "/").endswith("/" + launcher)
            for launcher in _TRUSTED_LAUNCHER_NAMES
        )
    ]
    has_bridge_token = bool(launcher_tokens)
    if tokens[0:2] == ["command", "-v"]:
        if len(tokens) == 3 and tokens[2] in _TRUSTED_LAUNCHER_NAMES:
            return None
        return (
            _fixed_deny(_RETRY_AS_ONE_COMMAND)
            if has_bridge_token
            else None
        )
    if tokens[0] == "which":
        if len(tokens) == 2 and tokens[1] in _TRUSTED_LAUNCHER_NAMES:
            return None
        return (
            _fixed_deny(_RETRY_AS_ONE_COMMAND)
            if has_bridge_token
            else None
        )
    if not has_bridge_token:
        return None
    launcher_name = tokens[0] if tokens[0] in _TRUSTED_LAUNCHER_NAMES else None
    if (
        launcher_name is None
        or len(launcher_tokens) != 1
        or any(_SHELL_CONTROL_TOKEN.fullmatch(token) for token in tokens)
        or "$" in command
        or "`" in command
        or "\n" in command
        or "\r" in command
    ):
        return _fixed_deny(_RETRY_AS_ONE_COMMAND)
    contract_failure = _validate_ai_orch_contract(tokens)
    if contract_failure:
        return _fixed_deny(contract_failure)
    cwd = payload.get("cwd")
    if not isinstance(cwd, str) or not cwd.strip():
        return _fixed_deny("The Copilot session workspace root was unavailable; bridge call blocked.")
    try:
        _trusted_repo_root(cwd)
        _verify_trusted_launcher(launcher_name)
    except ValueError:
        return _fixed_deny("The trusted Copilot bridge launcher or session workspace could not be resolved.")
    return {
        "permissionDecision": "allow",
        "permissionDecisionReason": f"Approved project-bound {launcher_name} bridge command.",
    }


# --------------------------------------------------------------------------
# runtime adapters
# --------------------------------------------------------------------------

RUNTIME_CLI = "copilot-cli"
RUNTIME_VSCODE = "vscode"


def normalize_event(payload: dict[str, Any]) -> tuple[str, dict[str, Any]]:
    """Map a CLI or VS Code preToolUse payload onto one internal shape."""
    if not isinstance(payload, dict):
        raise ValueError("hook payload must be an object")
    runtime = RUNTIME_CLI
    if "tool_name" in payload or "tool_input" in payload or "hook_event_name" in payload:
        runtime = RUNTIME_VSCODE
    tool_name = payload.get("toolName")
    if tool_name is None:
        tool_name = payload.get("tool_name")
    tool_args: Any = payload.get("toolArgs")
    if tool_args is None:
        tool_args = payload.get("tool_input")
    cwd = payload.get("cwd")
    if not isinstance(cwd, str) or not cwd.strip():
        for key in ("workspaceRoot", "workspace_root", "workspaceFolder", "cwd"):
            candidate = payload.get(key)
            if isinstance(candidate, str) and candidate.strip():
                cwd = candidate
                break
    return runtime, {"toolName": tool_name, "toolArgs": tool_args, "cwd": cwd}


def render_decision(runtime: str, decision: dict[str, Any] | None) -> dict[str, Any] | None:
    if decision is None:
        return None
    if runtime == RUNTIME_VSCODE:
        return {
            "hookSpecificOutput": {
                "hookEventName": "PreToolUse",
                "permissionDecision": decision["permissionDecision"],
                "permissionDecisionReason": decision["permissionDecisionReason"],
            }
        }
    return decision


def evaluate(payload: dict[str, Any]) -> dict[str, Any] | None:
    tool_name = _normalize_tool_name(payload.get("toolName") or payload.get("tool_name"))
    arguments, raw_arguments = _decode_tool_args(
        payload.get("toolArgs", payload.get("tool_input"))
    )

    if _SCAN_TOOL.search(tool_name):
        return _fixed_deny("Copilot is not permitted to invoke any scanner.scan operation.")
    if _ADMIN_MEMORY_TOOL.search(tool_name):
        return _fixed_deny("Copilot must use the canonical project-memory review flow.")
    if is_ai_orchestration_tool(tool_name):
        return _fixed_deny(
            "Native AI Orchestration tool calls are not available to Copilot; "
            "use the ai_orch terminal command."
        )

    is_shell = tool_name in _SHELL_TOOL_NAMES
    shell_command = arguments.get("command") if is_shell else None
    command_text = shell_command if isinstance(shell_command, str) else raw_arguments
    if (
        is_shell
        and _SCAN_TEXT.search(raw_arguments)
        and _is_service_execution(command_text, _SCAN_TEXT)
        and not (isinstance(command_text, str) and command_text.strip().startswith("ai_orch scan "))
    ):
        return _fixed_deny("Raw scanner.scan calls are blocked; use the approved ai_orch scan command.")
    if (
        is_shell
        and _ADMIN_MEMORY_TEXT.search(raw_arguments)
        and _is_service_execution(command_text, _ADMIN_MEMORY_TEXT)
    ):
        return _fixed_deny("Memory approval/admin bypass commands are blocked for Copilot.")
    if is_shell and isinstance(shell_command, str) and (
        "memory_bridge.py" in shell_command or "ai_orch.py" in shell_command
    ):
        return _fixed_deny(
            "Direct bridge script execution is blocked. " + _RETRY_AS_ONE_COMMAND
        )
    if (
        is_shell
        and _HARD_DELETE_TEXT.search(raw_arguments)
        and _MEMORY_CONTEXT_TEXT.search(raw_arguments)
    ):
        return _fixed_deny("Permanent MemoryAI deletion is not exposed to Copilot.")
    if is_shell:
        bridge_decision = _bridge_command_decision(payload, arguments)
        if bridge_decision is not None:
            return bridge_decision
        if _RAW_MEMORY_MUTATION_TEXT.search(raw_arguments) and _is_service_execution(
            command_text, _RAW_MEMORY_MUTATION_TEXT
        ):
            return _fixed_deny("Raw MemoryAI mutation commands are blocked; use ai_orch.")
        if _RAW_MCP_CLIENT_TEXT.search(raw_arguments) and "/mcp" in raw_arguments.lower():
            return _fixed_deny("Raw MCP-over-shell calls are blocked; use an approved named transport.")

    return None


def main() -> int:
    runtime = RUNTIME_CLI
    try:
        payload = json.load(sys.stdin)
        runtime, normalized = normalize_event(payload)
        decision = evaluate(normalized)
    except (json.JSONDecodeError, UnicodeDecodeError, ValueError, TypeError):
        decision = _fixed_deny("Malformed preToolUse input was blocked fail-closed.")
    rendered = render_decision(runtime, decision)
    if rendered is not None:
        json.dump(rendered, sys.stdout, ensure_ascii=True, separators=(",", ":"))
        sys.stdout.write("\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
