#!/usr/bin/env python3
"""Adds or removes AI Orchestration's hooks in a Claude Code settings.json or a Codex hooks.json (same format), used
by install.sh and --uninstall.

  python3 scripts/claude_hook_config.py set    ~/.claude/settings.json "<hook command>"... [--lean]
  python3 scripts/claude_hook_config.py set    ~/.codex/hooks.json "<hook command>"...
  python3 scripts/claude_hook_config.py remove ~/.claude/settings.json

Each command goes to the event of the script it runs (`ai_orch_prompt_context.py`: UserPromptSubmit,
`ai_orch_command_hook.py`: PreToolUse on Bash, `ai_orch_search_hook.py`: PostToolUse on Grep/Bash,
`ai_orch_memory_gate.py pre|post`: PreToolUse/PostToolUse on the memory-search MCP tools, `ai_orch_stop.py` and the
older `ai_orch_learn_reminder.py`: Stop).
Only entries running those scripts are ours; every other setting and hook is kept. `--lean` also hides the Claude Code
tools a coding session does not use (permissions.deny), which shrinks the context every model call re-reads; `remove`
takes exactly those entries out again. The file is written atomically and a copy of the previous version is kept next
to it (<name>.ai-orch.bak).
"""
from __future__ import annotations

import json
import os
import shutil
import sys
import tempfile
from pathlib import Path

EVENTS = {"ai_orch_prompt_context.py": "UserPromptSubmit", "ai_orch_command_hook.py": "PreToolUse",
          "ai_orch_search_hook.py": "PostToolUse", "ai_orch_stop.py": "Stop", "ai_orch_learn_reminder.py": "Stop",
          "ai_orch_memory_gate.py": "PreToolUse"}  # `... post` runs on PostToolUse
MATCHERS = {"ai_orch_command_hook.py": "Bash", "ai_orch_search_hook.py": "Grep|Bash",
            "ai_orch_memory_gate.py": "mcp__ai-orchestration__memory_search|mcp__ai-orchestration__extras"}
# the Stop hook may run the project's formatter and tests on an unchecked final tree
TIMEOUTS = {"ai_orch_prompt_context.py": 15, "ai_orch_command_hook.py": 20, "ai_orch_search_hook.py": 5,
            "ai_orch_stop.py": 960, "ai_orch_memory_gate.py": 5}
LEAN_DENY = ["CronCreate", "CronDelete", "CronList", "DesignSync", "EnterWorktree", "ExitWorktree", "ListAgents",
             "Monitor", "NotebookEdit", "PushNotification", "RemoteTrigger", "ReportFindings", "ScheduleWakeup",
             "SendMessage", "TaskCreate", "TaskGet", "TaskList", "TaskStop", "TaskUpdate", "Workflow", "Skill",
             "ListMcpResourcesTool", "ReadMcpResourceTool", "ReadMcpResourceDirTool"]


def event_of(command: str) -> str | None:
    if "ai_orch_memory_gate.py" in command:
        return "PostToolUse" if command.rstrip().endswith(" post") else "PreToolUse"
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
    deny = settings.get("permissions", {}).get("deny")
    if isinstance(deny, list) and all(d in deny for d in LEAN_DENY):  # the full set is ours; a user's own entry is not
        settings["permissions"]["deny"] = [d for d in deny if d not in LEAN_DENY]
        if not settings["permissions"]["deny"]:
            settings["permissions"].pop("deny")
        if not settings["permissions"]:
            settings.pop("permissions")
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
    lean = "--lean" in argv
    argv = [a for a in argv if a != "--lean"]
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
            script = next(marker for marker in EVENTS if marker in command)
            entry = {"hooks": [{"type": "command", "command": command, "timeout": TIMEOUTS.get(script, 10)}]}
            if script in MATCHERS:
                entry = {"matcher": MATCHERS[script], **entry}
            settings.setdefault("hooks", {}).setdefault(event, []).append(entry)
        if lean:
            deny = settings.setdefault("permissions", {}).setdefault("deny", [])
            deny += [d for d in LEAN_DENY if d not in deny]
    if json.dumps(settings, sort_keys=True) != before:
        write(path, settings)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
