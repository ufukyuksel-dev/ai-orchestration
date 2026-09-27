#!/usr/bin/env python3
"""Adds or removes AI Orchestration's Claude Code prompt hook in a settings.json (install.sh / --uninstall).

  python3 scripts/claude_hook_config.py set    ~/.claude/settings.json "<prompt hook command>" "<stop hook command>"
  python3 scripts/claude_hook_config.py remove ~/.claude/settings.json

Each command goes to the event of the script it runs (`ai_orch_prompt_context.py`: UserPromptSubmit,
`ai_orch_learn_reminder.py`: Stop). Only entries running those scripts are ours; every other setting and hook is kept.
The file is written atomically and a copy of the previous version is kept next to it (settings.json.ai-orch.bak).
"""
from __future__ import annotations

import json
import os
import shutil
import sys
import tempfile
from pathlib import Path

EVENTS = {"ai_orch_prompt_context.py": "UserPromptSubmit", "ai_orch_learn_reminder.py": "Stop"}


def event_of(command: str) -> str | None:
    return next((event for marker, event in EVENTS.items() if marker in command), None)


def ours(hook: dict) -> bool:
    return event_of(str(hook.get("command", ""))) is not None


def strip(settings: dict) -> dict:
    for event in set(EVENTS.values()):
        kept = []
        for group in settings.get("hooks", {}).get(event, []):
            hooks = [h for h in group.get("hooks", []) if not ours(h)]
            if hooks:
                kept.append({**group, "hooks": hooks})
        if kept:
            settings.setdefault("hooks", {})[event] = kept
        elif "hooks" in settings:
            settings["hooks"].pop(event, None)
    if "hooks" in settings and not settings["hooks"]:
        settings.pop("hooks")
    return settings


def write(path: Path, settings: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.exists():
        shutil.copy2(path, path.with_name(path.name + ".ai-orch.bak"))
    fd, tmp = tempfile.mkstemp(dir=path.parent, prefix=".settings-")
    with os.fdopen(fd, "w") as handle:
        json.dump(settings, handle, indent=2)
        handle.write("\n")
    os.replace(tmp, path)


def main(argv: list[str]) -> int:
    if len(argv) < 3 or argv[1] not in ("set", "remove") or (argv[1] == "set" and len(argv) < 4):
        print(__doc__, file=sys.stderr)
        return 2
    path = Path(argv[2]).expanduser()
    try:
        settings = json.loads(path.read_text()) if path.exists() and path.read_text().strip() else {}
    except ValueError:
        print(f"{path} is not valid JSON; not touching it", file=sys.stderr)
        return 1
    before = json.dumps(settings, sort_keys=True)
    settings = strip(settings)
    if argv[1] == "set":
        for command in argv[3:]:
            event = event_of(command)
            if event is None:
                print(f"not an AI Orchestration hook: {command}", file=sys.stderr)
                return 2
            entry = {"hooks": [{"type": "command", "command": command, "timeout": 10}]}
            settings.setdefault("hooks", {}).setdefault(event, []).append(entry)
    if json.dumps(settings, sort_keys=True) != before:
        write(path, settings)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
