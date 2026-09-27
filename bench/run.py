#!/usr/bin/env python3
"""A/B token benchmark: the same coding agent with (B) and without (A) AI Orchestration.

Each family is a chain T1..Tn of related tasks on a pinned public repository. Arm B keeps what the
agent learned in T1 (memory in the isolated bench backend) for the later tasks; arm A starts every
task with nothing but the repository. Code is reset to the pinned commit before every task, so both
arms face identical starting states. Success = the hidden tests and the project's own quality gates.

  bench/fetch.sh && bench/server.sh start
  python3 bench/run.py --suite dev --agents claude --arms A,B --reps 2
  python3 bench/summary.py bench/.work/results/<run-id>
"""
from __future__ import annotations

import argparse
import json
import os
import shutil
import subprocess
import sys
import threading
import time
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BENCH = ROOT / "bench"
WORK = BENCH / ".work"
PORT = int(os.environ.get("AI_ORCH_BENCH_PORT", "18200"))
BASE = f"http://127.0.0.1:{PORT}"
PG = os.environ.get("AI_ORCH_PG_CONTAINER", "ai-orch-local-postgres")
PG_DB = os.environ.get("BENCH_DB", "ai_orch_bench")
SERVER = "ai-orchestration"
WALL_LIMIT_S = int(os.environ.get("BENCH_WALL_LIMIT_S", "1500"))
MAX_TURNS = int(os.environ.get("BENCH_MAX_TURNS", "80"))
SHA = {"spring-petclinic": "a6efbed773f61a271c071461326940786998722e"}
# Worktrees live outside this repository: agents auto-load instruction files (CLAUDE.md/AGENTS.md) from parent
# directories, and this repository's own instructions must never leak into either arm.
WT_ROOT = Path(os.environ.get("BENCH_WT_ROOT", "/private/tmp/aiorch-bench/wt"))
ALWAYS_LOAD = os.environ.get("BENCH_ALWAYS_LOAD", "0") == "1"


# ------------------------------------------------------------------ backend helpers
def http_json(method: str, path: str, body: dict | None = None, timeout: int = 300) -> dict:
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method,
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        raw = r.read()
    return json.loads(raw) if raw else {}


def psql(sql: str) -> str:
    out = subprocess.run(["docker", "exec", PG, "psql", "-U", "ai_orch", "-d", PG_DB, "-qAtc", sql],
                         capture_output=True, text=True, timeout=60)
    if out.returncode != 0:
        raise RuntimeError(out.stderr.strip())
    return out.stdout.strip()


def mcp_call(tool: str, arguments: dict) -> dict:
    """Minimal MCP client: initialize a session, call one tool, return its JSON text result."""
    headers = {"Content-Type": "application/json", "Accept": "application/json, text/event-stream",
               "X-AI-Orch-Client": "bench-harness"}

    def post(payload: dict, session: str | None = None) -> tuple[str, str | None]:
        h = dict(headers, **({"Mcp-Session-Id": session} if session else {}))
        req = urllib.request.Request(BASE + "/mcp", json.dumps(payload).encode(), h, method="POST")
        with urllib.request.urlopen(req, timeout=120) as r:
            return r.read().decode(), r.headers.get("Mcp-Session-Id")

    _, sid = post({"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {
        "protocolVersion": "2025-06-18", "capabilities": {}, "clientInfo": {"name": "bench", "version": "1"}}})
    post({"jsonrpc": "2.0", "method": "notifications/initialized"}, sid)
    body, _ = post({"jsonrpc": "2.0", "id": 2, "method": "tools/call",
                    "params": {"name": tool, "arguments": arguments}}, sid)
    for line in body.splitlines():
        line = line[5:].strip() if line.startswith("data:") else line.strip()
        if line.startswith("{"):
            return json.loads(json.loads(line)["result"]["content"][0]["text"])
    raise RuntimeError(f"no result from {tool}: {body[:300]}")


def project_key_for(root: Path) -> str:
    """Bind the worktree exactly as an agent's session.bootstrap would, then scan it under that key
    (the panel's "add project": structural + extractive capsules, no LLM)."""
    key = mcp_call("scanner.project.resolve", {"rootPath": str(root)})["projectKey"]
    http_json("POST", "/api/scanner/scan", {"rootPath": str(root), "projectKey": key, "force": True})
    return key


def reset_memory(project_key: str) -> None:
    """Forget everything learned for this worktree binding (start of a B chain)."""
    psql(f"DELETE FROM memory_items WHERE project_key = '{project_key}'")


def memory_count(project_key: str) -> int:
    try:
        return int(psql(f"SELECT count(*) FROM memory_items WHERE project_key = '{project_key}'") or 0)
    except (RuntimeError, ValueError):
        return -1


# ------------------------------------------------------------------ worktrees
def fresh_worktree(repo: str, path: Path) -> None:
    src = WORK / "repos" / repo
    if path.exists():
        subprocess.run(["git", "-C", str(src), "worktree", "remove", "--force", str(path)], capture_output=True)
        shutil.rmtree(path, ignore_errors=True)
    subprocess.run(["git", "-C", str(src), "worktree", "prune"], capture_output=True)
    path.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run(["git", "-C", str(src), "worktree", "add", "--detach", "-q", str(path), SHA[repo]], check=True)


def reset_code(path: Path, repo: str) -> None:
    # Agents sometimes commit or reset; always return to the pinned commit.
    subprocess.run(["git", "-C", str(path), "checkout", "-q", "--detach", "-f", SHA[repo]], check=True)
    subprocess.run(["git", "-C", str(path), "reset", "--hard", "-q", SHA[repo]], check=True)
    subprocess.run(["git", "-C", str(path), "clean", "-fdq"], check=True)


# ------------------------------------------------------------------ agent commands
def claude_cmd(prompt: str, wt: Path, arm: str, model: str) -> tuple[list[str], dict]:
    cfg = wt.parent / (wt.name + ".mcp.json")  # outside the repo: the project's nohttp check scans every file
    servers = {}
    if arm == "B":
        # ALWAYS_LOAD=0 mirrors a real `claude mcp add` registration (install.sh): Claude Code loads the tool
        # schemas on demand instead of carrying them in every turn.
        servers[SERVER] = {"type": "http", "url": f"{BASE}/mcp", **({"alwaysLoad": True} if ALWAYS_LOAD else {}),
                           "headers": {"X-AI-Orch-Client": "claude-code",
                                       "X-AI-Orch-Tools": "minimal" if CLAUDE_MODE == "hook" else TOOLS_PROFILE}}
    cfg.write_text(json.dumps({"mcpServers": servers}))
    allowed = ["Read", "Grep", "Glob", "Edit", "Write", "MultiEdit", "Bash", "TodoWrite"]
    if arm == "B":
        allowed.append(f"mcp__{SERVER}")
    cmd = ["claude", "-p", prompt, "--model", model, "--output-format", "stream-json", "--verbose",
           *(["--append-system-prompt", instructions("claude")] if arm == "B" else []),
           "--setting-sources", "project", "--settings", json.dumps(claude_settings(arm)),
           "--strict-mcp-config", "--mcp-config", str(cfg),
           "--max-turns", str(MAX_TURNS), "--permission-mode", "acceptEdits",
           "--allowedTools", *allowed, "--disallowedTools", "WebFetch", "WebSearch", "Task"]
    return cmd, dict(os.environ, AI_ORCH_URL=BASE)


def claude_settings(arm: str) -> dict:
    """In hook mode arm B gets what install.sh sets up for Claude Code: a UserPromptSubmit hook that puts the rules
    and matching memory cards into the context (no session.bootstrap tool call), the Stop hook that asks for the
    task-end memory.learn, and the minimal tool profile."""
    settings = {"autoMemoryEnabled": False}
    if arm == "B" and CLAUDE_MODE == "hook":
        hooks = ROOT / "scripts" / "hooks"
        settings["hooks"] = {
            "UserPromptSubmit": [{"hooks": [
                {"type": "command", "command": f"python3 {hooks / 'ai_orch_prompt_context.py'}", "timeout": 10}]}],
            "Stop": [{"hooks": [
                {"type": "command", "command": f"python3 {hooks / 'ai_orch_learn_reminder.py'}", "timeout": 10}]}]}
    return settings


def codex_cmd(prompt: str, wt: Path, arm: str, model: str) -> tuple[list[str], dict]:
    cmd = ["codex", "exec", "--json", "--skip-git-repo-check", "-C", str(wt), "-s", "workspace-write",
           "-c", "memories.use_memories=false", "-c", "memories.generate_memories=false",
           "-c", 'web_search="disabled"', "-c", 'approval_policy="never"']
    if model:
        cmd += ["-m", model]
    if arm == "B":
        cmd += ["-c", f'mcp_servers.{SERVER}.url="{BASE}/mcp"',
                "-c", f'mcp_servers.{SERVER}.http_headers={{"X-AI-Orch-Client"="codex","X-AI-Orch-Tools"="{TOOLS_PROFILE}"}}',
                "-c", f'mcp_servers.{SERVER}.default_tools_approval_mode="approve"']
    cmd.append(prompt)
    env = dict(os.environ)
    home = WORK / "codex-home" / arm
    home.mkdir(parents=True, exist_ok=True)
    auth = Path.home() / ".codex" / "auth.json"
    if auth.exists() and not (home / "auth.json").exists():
        shutil.copy(auth, home / "auth.json")
    # Global (user-level) instructions, exactly where the installer puts them for a real user.
    agents_md = home / "AGENTS.md"
    if arm == "B":
        agents_md.write_text(instructions("codex"))
    elif agents_md.exists():
        agents_md.unlink()
    env["CODEX_HOME"] = str(home)
    return cmd, env


def run_process(cmd: list[str], env: dict, cwd: Path) -> tuple[list[dict], str, int, float, bool]:
    start = time.monotonic()
    proc = subprocess.Popen(cmd, cwd=cwd, env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
                            stdin=subprocess.DEVNULL)
    events: list[dict] = []
    killed = {"v": False}

    def guard():
        while proc.poll() is None:
            if time.monotonic() - start > WALL_LIMIT_S:
                killed["v"] = True
                proc.kill()
                return
            time.sleep(1)

    threading.Thread(target=guard, daemon=True).start()
    for line in proc.stdout:  # type: ignore[union-attr]
        line = line.strip()
        if not line:
            continue
        try:
            e = json.loads(line)
        except json.JSONDecodeError:
            e = {"type": "_text", "text": line}
        e["_t"] = round(time.monotonic() - start, 2)
        events.append(e)
    stderr = proc.stderr.read() if proc.stderr else ""
    code = proc.wait()
    return events, stderr, code, time.monotonic() - start, killed["v"]


# ------------------------------------------------------------------ scoring
def score(task: dict, suite: dict, wt: Path) -> dict:
    quality = subprocess.run(suite["qualityCommand"], cwd=wt, capture_output=True, text=True, timeout=900)
    target = wt / suite["hiddenTarget"]
    for name in task["hidden"]:
        shutil.copy(BENCH / "hidden" / task["id"] / name, target / name)
    tests = subprocess.run(["./mvnw", "-q", "-Dspring-javaformat.skip=true", "-Dcheckstyle.skip", "test",
                            f"-Dtest={task['tests']}", "-Dsurefire.failIfNoSpecifiedTests=false"],
                           cwd=wt, capture_output=True, text=True, timeout=1800)
    for name in task["hidden"]:
        (target / name).unlink(missing_ok=True)
    return {"success": quality.returncode == 0 and tests.returncode == 0,
            "qualityOk": quality.returncode == 0, "testsOk": tests.returncode == 0,
            "testTail": failure_lines(tests.stdout + tests.stderr) if tests.returncode else "",
            "qualityTail": failure_lines(quality.stdout + quality.stderr) if quality.returncode else ""}


def failure_lines(text: str) -> str:
    keep = [line for line in text.splitlines()
            if any(k in line for k in ("FAIL", "ERROR]", "expected", "Expected", "Tests run:", "violation", "Caused by"))
            and not line.startswith("WARNING")]
    return "\n".join(keep[:25])[-2000:]


# ------------------------------------------------------------------ main loop
INSTRUCTIONS = BENCH / "instructions"
TOOLS_PROFILE = "full"
CLAUDE_MODE = "tool"
UPTO = ""


def instructions(agent: str) -> str:
    return (INSTRUCTIONS / f"{agent}.md").read_text()


def run_chain(agent: str, arm: str, family: dict, suite: dict, rep: int, model: str, out: Path) -> None:
    from profile import profile  # local module: bench/profile.py

    wt = WT_ROOT / f"{agent}-{arm}-{family['id']}"  # fixed path => stable project binding across the chain
    fresh_worktree(suite["repo"], wt)
    key = None
    if arm == "B":
        key = project_key_for(wt)  # "Proje ekle": structural + extractive scan, no LLM
        reset_memory(key)
    tasks = family["tasks"]
    if UPTO and any(t["id"] == UPTO for t in tasks):
        tasks = tasks[: [t["id"] for t in tasks].index(UPTO) + 1]
    for index, task in enumerate(tasks):
        reset_code(wt, suite["repo"])
        before = memory_count(key) if key else None
        cmd, env = (claude_cmd if agent == "claude" else codex_cmd)(task["prompt"], wt, arm, model)
        events, stderr, code, wall, killed = run_process(cmd, env, wt)
        raw = out / "raw" / f"{agent}-{arm}-{task['id']}-r{rep}.jsonl"
        raw.parent.mkdir(parents=True, exist_ok=True)
        raw.write_text("\n".join(json.dumps(e) for e in events))
        prof = profile(agent, events)
        result = score(task, suite, wt)
        row = {"at": datetime.now(timezone.utc).isoformat(timespec="seconds"), "agent": agent, "model": model,
               "arm": arm, "family": family["id"], "task": task["id"], "position": index + 1, "rep": rep,
               "exitCode": code, "killed": killed, "wallS": round(wall, 1), **prof, **result,
               "memoriesBefore": before, "memoriesAfter": memory_count(key) if key else None,
               "stderrTail": stderr[-400:] if code else ""}
        with (out / "results.jsonl").open("a") as f:
            f.write(json.dumps(row) + "\n")
        print(f"{agent} {arm} {task['id']} r{rep}: success={row['success']} tokens={row['totalTokens']} "
              f"turns={row['turns']} wall={row['wallS']}s", flush=True)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--suite", default="dev")
    ap.add_argument("--agents", default="claude")
    ap.add_argument("--arms", default="A,B")
    ap.add_argument("--families", default="")
    ap.add_argument("--reps", type=int, default=1)
    ap.add_argument("--claude-model", default="sonnet")
    ap.add_argument("--codex-model", default="")
    ap.add_argument("--out", default="")
    ap.add_argument("--upto", default="",
                    help="probe: stop each family chain after this task id (the tasks before it still run and learn)")
    ap.add_argument("--claude-mode", choices=["tool", "hook"], default="tool",
                    help="arm B for Claude: session.bootstrap tool call (tool) or the UserPromptSubmit hook (hook)")
    ap.add_argument("--instructions", default="", help="directory with claude.md/codex.md (default bench/instructions)")
    ap.add_argument("--tools", default="full", choices=["full", "lean"], help="MCP tool profile for arm B")
    args = ap.parse_args()
    global INSTRUCTIONS, TOOLS_PROFILE, CLAUDE_MODE, UPTO
    if args.instructions:
        INSTRUCTIONS = (ROOT / args.instructions).resolve()
    TOOLS_PROFILE = args.tools
    CLAUDE_MODE = args.claude_mode
    UPTO = args.upto
    suite = json.loads((BENCH / "tasks" / f"{args.suite}.json").read_text())
    families = [f for f in suite["families"] if not args.families or f["id"] in args.families.split(",")]
    run_id = args.out or datetime.now().strftime(f"{args.suite}-%Y%m%d-%H%M%S")
    out = WORK / "results" / run_id
    out.mkdir(parents=True, exist_ok=True)
    (out / "meta.json").write_text(json.dumps(vars(args) | {"sha": SHA}, indent=2))
    sys.path.insert(0, str(BENCH))
    for rep in range(1, args.reps + 1):
        for family in families:
            for agent in args.agents.split(","):
                arms = args.arms.split(",")
                if rep % 2 == 0:
                    arms = list(reversed(arms))  # alternate arm order to spread provider cache warm-up
                for arm in arms:
                    model = args.claude_model if agent == "claude" else args.codex_model
                    run_chain(agent, arm, family, suite, rep, model, out)
    print(f"results: {out}")


if __name__ == "__main__":
    main()
