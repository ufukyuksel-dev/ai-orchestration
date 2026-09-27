#!/usr/bin/env python3
"""Own the `[mcp_servers.ai-orchestration]` table in Codex's config.toml (used by install.sh).

  codex_mcp_config.py set <config.toml> <mcp-url>     write or update our table (backup first, only on change)
  codex_mcp_config.py remove <config.toml>            remove the table only if install.sh wrote it

Exit codes: 0 ok, 3 a different (non-local) ai-orchestration server is configured and is left untouched.
"""
from __future__ import annotations

import re
import shutil
import sys
import time
from pathlib import Path

MARKER = "# managed by AI Orchestration install.sh"
# our table and its sub-tables, up to the next unrelated table header
TABLE = re.compile(r"(?:^# managed by AI Orchestration install\.sh\n)?"
                   r"^\[mcp_servers\.ai-orchestration(?:\.[^\]]+)?\]\n(?:(?!^\[).*\n?)*", re.M)
LOCAL = re.compile(r"https?://(127\.0\.0\.1|localhost)[:/]")


def block(url: str) -> str:
    return (f"{MARKER}\n[mcp_servers.ai-orchestration]\nurl = \"{url}\"\n"
            f"http_headers = {{ \"X-AI-Orch-Client\" = \"codex\" }}\n")


def set_table(path: Path, url: str) -> int:
    text = path.read_text() if path.exists() else ""
    found = TABLE.findall(text)
    if found and not any(LOCAL.search(f) for f in found):
        return 3  # somebody else's server under our name: never take it over
    wanted = block(url)
    if found and len(found) == 1 and found[0].strip() == wanted.strip():
        return 0
    if path.exists():
        shutil.copy(path, f"{path}.bak-{int(time.time())}")
    rest = TABLE.sub("", text).rstrip("\n")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text((rest + "\n\n" if rest else "") + wanted)
    return 0


def remove_table(path: Path) -> int:
    if not path.exists():
        return 0
    text = path.read_text()
    owned = [f for f in TABLE.findall(text) if f.startswith(MARKER)]
    if owned:
        for f in owned:
            text = text.replace(f, "")
        path.write_text(text.rstrip("\n") + "\n" if text.strip() else "")
    return 0


def main(argv: list[str]) -> int:
    if len(argv) >= 3 and argv[0] == "set":
        return set_table(Path(argv[1]), argv[2])
    if len(argv) >= 2 and argv[0] == "remove":
        return remove_table(Path(argv[1]))
    print(__doc__, file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
