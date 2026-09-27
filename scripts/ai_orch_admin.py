#!/usr/bin/env python3
"""`ai_orch doctor` and `ai_orch project add`: the two commands a person runs by hand.

  ai_orch doctor                       # is everything installed and reachable? one line per check
  ai_orch project add <folder> [--copilot]
                                       # index a repository (structural, no LLM) under the key agents resolve;
                                       # --copilot also installs the Copilot instructions into <folder>/.github

Standard library only. The server address comes from AI_ORCH_URL (default http://127.0.0.1:18080).
"""
from __future__ import annotations

import argparse
import json
import os
import shutil
import subprocess
import sys
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BASE = os.environ.get("AI_ORCH_URL", "http://127.0.0.1:18080").rstrip("/")
QDRANT = os.environ.get("AI_ORCH_QDRANT_URL", "http://127.0.0.1:6333")
OLLAMA = os.environ.get("OLLAMA_BASE_URL", "http://127.0.0.1:11434")
MCP_URL = BASE + "/mcp"
STATE = Path(os.environ.get("AI_ORCH_STATE", Path.home() / ".local/share/ai-orch/state.json"))


def _agents_skipped() -> bool:
    """install.sh --no-agents leaves Claude/Codex alone on purpose: their checks are then not failures."""
    try:
        return json.loads(STATE.read_text()).get("agents") is False
    except (OSError, ValueError):
        return False


GREEN, RED, DIM, RESET = ("\033[32m", "\033[31m", "\033[2m", "\033[0m") if sys.stdout.isatty() else ("",) * 4


def http(method: str, url: str, body: dict | None = None, headers: dict | None = None, timeout: int = 10):
    data = json.dumps(body).encode() if body is not None else None
    request = urllib.request.Request(url, data=data, method=method,
                                     headers={"Content-Type": "application/json", **(headers or {})})
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return response.status, response.read().decode(), response.headers


def mcp_call(tool: str, arguments: dict, client: str = "ai-orch-cli") -> dict:
    headers = {"Accept": "application/json, text/event-stream", "X-AI-Orch-Client": client}
    _, _, h = http("POST", MCP_URL, {"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {
        "protocolVersion": "2025-06-18", "capabilities": {}, "clientInfo": {"name": "ai_orch", "version": "1"}}},
        headers)
    session = {"Mcp-Session-Id": h.get("Mcp-Session-Id")} if h.get("Mcp-Session-Id") else {}
    http("POST", MCP_URL, {"jsonrpc": "2.0", "method": "notifications/initialized"}, {**headers, **session})
    _, text, _ = http("POST", MCP_URL, {"jsonrpc": "2.0", "id": 2, "method": "tools/call",
                                        "params": {"name": tool, "arguments": arguments}},
                      {**headers, **session}, timeout=120)
    for line in text.splitlines():
        line = line[5:].strip() if line.startswith("data:") else line.strip()
        if line.startswith("{"):
            result = json.loads(line)
            if "error" in result:
                raise RuntimeError(result["error"].get("message", "MCP error"))
            payload = result["result"]["content"][0]["text"]
            if result["result"].get("isError"):
                raise RuntimeError(payload)
            return json.loads(payload)
    raise RuntimeError(f"no answer from {tool}")


# ------------------------------------------------------------------ doctor
def _check(name: str, fn) -> bool:
    try:
        ok, detail, fix = fn()
    except Exception as e:  # a check must never crash the report
        ok, detail, fix = False, str(e).splitlines()[0][:120], None
    mark = f"{GREEN}✓{RESET}" if ok else f"{RED}✗{RESET}"
    print(f" {mark} {name:<34} {DIM}{detail}{RESET}")
    if not ok and fix:
        print(f"     → {fix}")
    return ok


def _server():
    status, text, _ = http("GET", BASE + "/actuator/health")
    return json.loads(text).get("status") == "UP", f"{BASE} UP", "see ~/.local/state/ai-orch/server.log; re-run ./install.sh"


def _qdrant():
    status, _, _ = http("GET", QDRANT + "/readyz")
    return status == 200, QDRANT, "docker compose -f deploy/docker-compose.local.yml up -d"


def _embedding():
    _, text, _ = http("GET", OLLAMA + "/api/tags")
    names = [m["name"] for m in json.loads(text).get("models", [])]
    ok = any(n.startswith("bge-m3") for n in names)
    return ok, "bge-m3 present" if ok else "bge-m3 missing", "ollama pull bge-m3"


def _mcp():
    headers = {"Accept": "application/json, text/event-stream", "X-AI-Orch-Client": "claude-code"}
    _, _, h = http("POST", MCP_URL, {"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {
        "protocolVersion": "2025-06-18", "capabilities": {}, "clientInfo": {"name": "doctor", "version": "1"}}},
        headers)
    session = {"Mcp-Session-Id": h.get("Mcp-Session-Id")} if h.get("Mcp-Session-Id") else {}
    http("POST", MCP_URL, {"jsonrpc": "2.0", "method": "notifications/initialized"}, {**headers, **session})
    _, text, _ = http("POST", MCP_URL, {"jsonrpc": "2.0", "id": 2, "method": "tools/list"}, {**headers, **session})
    tools = []
    for line in text.splitlines():
        line = line[5:].strip() if line.startswith("data:") else line.strip()
        if line.startswith("{"):
            tools = [t["name"] for t in json.loads(line)["result"]["tools"]]
    ok = "session.bootstrap" in tools and "memory.learn" in tools
    return ok, f"{len(tools)} tools for agents", None


def _panel():
    status, text, _ = http("GET", BASE + "/")
    return status == 200 and "Atlas" in text, BASE + "/", None


def _claude():
    if _agents_skipped():
        return True, "skipped (installed with --no-agents)", None
    if not shutil.which("claude"):
        return True, "not installed (skipped)", None
    out = subprocess.run(["claude", "mcp", "get", "ai-orchestration"], capture_output=True, text=True, timeout=30)
    if out.returncode != 0 or MCP_URL not in out.stdout:
        return False, "not registered", "./install.sh (re-run registers it)"
    settings = Path.home() / ".claude" / "settings.json"
    text = settings.read_text() if settings.exists() else ""
    hooked = "ai_orch_prompt_context.py" in text and "ai_orch_learn_reminder.py" in text
    return hooked, "registered, hooks installed" if hooked else "registered, hooks missing", \
        "./install.sh (re-run installs the hooks)"


def _codex():
    if _agents_skipped():
        return True, "skipped (installed with --no-agents)", None
    if not shutil.which("codex"):
        return True, "not installed (skipped)", None
    config = Path(os.environ.get("CODEX_HOME", Path.home() / ".codex")) / "config.toml"
    text = config.read_text() if config.exists() else ""
    ok = "[mcp_servers.ai-orchestration]" in text and MCP_URL in text
    return ok, "registered" if ok else "not registered", "./install.sh (re-run registers it)"


def _instructions():
    if _agents_skipped():
        return True, "skipped (installed with --no-agents)", None
    agents = [a for a in ("claude", "codex") if shutil.which(a)]
    if not agents:
        return True, "no Claude/Codex (skipped)", None
    out = subprocess.run([sys.executable, str(ROOT / "scripts/install_agent_instructions.py"), "--check"],
                         capture_output=True, text=True, timeout=60)
    return out.returncode == 0, "session block up to date" if out.returncode == 0 else "drift", "./install.sh"


def doctor(_: argparse.Namespace) -> int:
    print("AI Orchestration doctor")
    checks = [("server", _server), ("vector store (Qdrant)", _qdrant), ("embedding model (Ollama bge-m3)", _embedding),
              ("MCP endpoint", _mcp), ("panel", _panel), ("Claude Code registration", _claude),
              ("Codex registration", _codex), ("agent session instructions", _instructions)]
    results = [_check(name, fn) for name, fn in checks]
    print(f"{'all good' if all(results) else 'fix the ✗ lines above'} · panel: {BASE}/")
    return 0 if all(results) else 1


# ------------------------------------------------------------------ project add
def project_add(args: argparse.Namespace) -> int:
    folder = Path(args.folder).expanduser().resolve()
    if not folder.is_dir():
        print(f"not a folder: {folder}", file=sys.stderr)
        return 2
    # the same key an agent's session.bootstrap resolves for this folder
    key = mcp_call("scanner.project.resolve", {"rootPath": str(folder)})["projectKey"]
    print(f"indexing {folder} as {key} (structural, no LLM)…", flush=True)
    status, text, _ = http("POST", BASE + "/api/scanner/scan", {"rootPath": str(folder), "projectKey": key,
                                                                "force": False, "semanticEnabled": False},
                           timeout=3600)
    result = json.loads(text)
    print(f"done: {result.get('filesScanned', 0)} files in {round(result.get('durationMs', 0) / 1000)} s · "
          f"graph: {BASE}/#/graph")
    if args.copilot:
        out = subprocess.run([sys.executable, str(ROOT / "scripts/install_project_copilot_instructions.py"),
                              "--target", str(folder)], text=True)
        if out.returncode != 0:
            return out.returncode
        print(f"Copilot instructions installed in {folder}/.github")
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="ai_orch")
    groups = parser.add_subparsers(dest="group", required=True)
    groups.add_parser("doctor", help="check the installation").set_defaults(run=doctor)
    project = groups.add_parser("project", help="add a repository")
    actions = project.add_subparsers(dest="action", required=True)
    add = actions.add_parser("add", help="index a repository (structural, no LLM)")
    add.add_argument("folder")
    add.add_argument("--copilot", action="store_true",
                     help="also install the Copilot instructions into <folder>/.github")
    add.set_defaults(run=project_add)
    args = parser.parse_args(argv)
    try:
        return args.run(args)
    except (urllib.error.URLError, ConnectionError) as e:
        print(f"AI Orchestration is not reachable at {BASE}: {e}. Run `ai_orch doctor`.", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
