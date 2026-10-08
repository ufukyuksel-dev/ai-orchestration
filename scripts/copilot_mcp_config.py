#!/usr/bin/env python3
"""Own the `ai-orchestration` entry in Copilot's mcp-config.json (used by the Copilot installer).

  copilot_mcp_config.py set <mcp-config.json> <mcp-url>   write or update our entry (backup first, only on change)
  copilot_mcp_config.py remove <mcp-config.json>          remove the entry only if it points to a local server

Exit codes: 0 ok, 3 a different (non-local) ai-orchestration server is configured and is left untouched,
4 the file is not a JSON object.
"""
from __future__ import annotations

import json
import os
import re
import shutil
import sys
import time
from pathlib import Path

NAME = "ai-orchestration"
LOCAL = re.compile(r"https?://(127\.0\.0\.1|localhost)[:/]")


class ConfigError(RuntimeError):
    def __init__(self, message: str, code: int) -> None:
        super().__init__(message)
        self.code = code


def entry(url: str) -> dict:
    return {"type": "http", "url": url, "headers": {"X-AI-Orch-Client": "copilot"}, "tools": ["*"]}


def load(path: Path) -> dict:
    if not path.exists():
        return {}
    text = path.read_text(encoding="utf-8")
    if not text.strip():
        return {}
    try:
        data = json.loads(text)
    except ValueError as exc:
        raise ConfigError(f"{path} is not valid JSON: {exc}", 4) from exc
    if not isinstance(data, dict) or not isinstance(data.get("mcpServers", {}), dict):
        raise ConfigError(f"{path} is not a Copilot MCP configuration object", 4)
    return data


def owned(value: object) -> bool:
    return isinstance(value, dict) and bool(LOCAL.search(str(value.get("url", ""))))


def desired(path: Path, url: str) -> tuple[dict, dict]:
    """(current, wanted) documents; raises ConfigError for a foreign server under our name."""
    current = load(path)
    servers = current.get("mcpServers", {})
    if NAME in servers and not owned(servers[NAME]):
        raise ConfigError(f"a different '{NAME}' MCP server is configured in {path}; not touching it", 3)
    wanted = dict(current)
    wanted["mcpServers"] = {**servers, NAME: entry(url)}
    return current, wanted


def without(path: Path) -> tuple[dict, dict]:
    current = load(path)
    servers = dict(current.get("mcpServers", {}))
    if NAME in servers and owned(servers[NAME]):
        del servers[NAME]
    wanted = dict(current)
    if "mcpServers" in current or servers:
        wanted["mcpServers"] = servers
    return current, wanted


def render(document: dict) -> str:
    return json.dumps(document, indent=2, ensure_ascii=False) + "\n"


def write(path: Path, document: dict, backup_dir: Path | None = None) -> None:
    if path.exists():
        if backup_dir is None:
            shutil.copy2(path, f"{path}.bak-{int(time.time())}")
        else:
            backup_dir.mkdir(mode=0o700, parents=True, exist_ok=True)
            shutil.copy2(path, backup_dir / path.name)
    path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    temporary = path.with_name(path.name + ".ai-orch-tmp")
    temporary.write_text(render(document), encoding="utf-8")
    temporary.chmod(0o600)
    os.replace(temporary, path)


def main(argv: list[str]) -> int:
    try:
        if len(argv) >= 3 and argv[0] == "set":
            path = Path(argv[1])
            current, wanted = desired(path, argv[2])
            if current != wanted:
                write(path, wanted)
            return 0
        if len(argv) >= 2 and argv[0] == "remove":
            path = Path(argv[1])
            current, wanted = without(path)
            if current != wanted:
                write(path, wanted)
            return 0
    except ConfigError as exc:
        print(str(exc), file=sys.stderr)
        return exc.code
    print(__doc__, file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
