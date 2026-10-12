#!/usr/bin/env python3
"""A/B token benchmark: the same coding agent with (B) and without (A) AI Orchestration.

Each family is a chain T1..Tn of related tasks on a pinned public repository. Arm B keeps what the
agent learned in T1 (memory in the isolated bench backend) for the later tasks; arm A starts every
task with nothing but the repository. Code is reset to the pinned commit before every task, so both
arms face identical starting states. Success = the hidden tests and the project's own quality gates.

  bench/fetch.sh && bench/server.sh start
  python3 bench/run.py --suite dev --agents claude --arms A,B --reps 2
  python3 bench/summary.py bench/.work/results/<run-id>

v2 (plan-harness-benchmark-v2.md): arms A, B, Blearn (memory.learn required), Bnomem (memory cleared before every
position), Blean (B + lean tools); per-position code baselines (sha + patch, optional re-scan for stale-memory
probes); task kinds code / investigation (ANSWER: block vs. answer key) / tests (mutants must be killed); one
(agent, arm, family, rep) cell is the unit of --resume, an interrupted cell reruns from its first position.
"""
from __future__ import annotations

import argparse
import fnmatch
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import threading
import time
import urllib.request
import uuid
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
# Suites on private repositories keep their task files, hidden checks, answer keys, mutants and patches outside this
# (public) repository; a suite with "private": true resolves every asset path against this directory instead of bench/.
PRIVATE = Path(os.environ.get("BENCH_PRIVATE", Path.home() / ".config/super-computer-ai/private/ai-orch-bench"))
ALWAYS_LOAD = os.environ.get("BENCH_ALWAYS_LOAD", "0") == "1"
# A nested `codex exec` cannot apply its own Seatbelt sandbox when the parent process is already sandboxed
# (`sandbox_apply: Operation not permitted`); the worktrees are disposable, so BENCH_CODEX_SANDBOX=off runs unsandboxed.
CODEX_SANDBOX = (["--dangerously-bypass-approvals-and-sandbox"] if os.environ.get("BENCH_CODEX_SANDBOX") == "off"
                 else ["-s", "workspace-write"])
# Product arms: everything B installs; the variants differ only where named.
B_ARMS = {"B", "Blearn", "Bnomem", "Blean"}
LEARN_REQUIRED = ("\n\nBenchmark arm requirement: at the end of every task, after your final check, call memory.learn "
                  "once with what this task taught about the repository.\n")


def is_b(arm: str) -> bool:
    return arm in B_ARMS


class InfraError(RuntimeError):
    """An external fault outside the agent and the product (git, Docker, toolchain download): retried, reported
    apart. Agent timeouts, hook/MCP hangs and scoring failures of the agent's tree are never InfraError."""


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
    """Bind the worktree exactly as an agent's session.bootstrap would (the scan follows in scan_project)."""
    return mcp_call("scanner.project.resolve", {"rootPath": str(root)})["projectKey"]


def scan_project(root: Path, key: str) -> None:
    """The panel's "add project": structural + extractive capsules, no LLM."""
    http_json("POST", "/api/scanner/scan", {"rootPath": str(root), "projectKey": key, "force": True})


QDRANT = os.environ.get("BENCH_QDRANT", "http://localhost:6333")
MEMORY_COLLECTION = os.environ.get("BENCH_MEMORY_COLLECTION", "bench_memory_episodic_bge_m3_1024")


def reset_memory(project_key: str) -> None:
    """Forget everything learned for this worktree binding (start of a B chain): cards, their vectors and work
    trails. The rows go by SQL, which fires no app event, so their Qdrant points are deleted here too: orphaned points
    still pass the search prefilter, take top-k places and are dropped at hydration without refill, which silently
    hides live cards."""
    psql(f"DELETE FROM memory_items WHERE project_key = '{project_key}'")
    psql(f"DELETE FROM work_trails WHERE project_key = '{project_key}'")
    body = json.dumps({"filter": {"must": [{"key": "project_key", "match": {"value": project_key}}]}}).encode()
    req = urllib.request.Request(f"{QDRANT}/collections/{MEMORY_COLLECTION}/points/delete?wait=true", body,
                                 {"Content-Type": "application/json"}, method="POST")
    with urllib.request.urlopen(req, timeout=60) as r:
        r.read()


def memory_count(project_key: str) -> int:
    try:
        return int(psql(f"SELECT count(*) FROM memory_items WHERE project_key = '{project_key}'") or 0)
    except (RuntimeError, ValueError):
        return -1


def memory_snapshot(project_key: str) -> list[dict]:
    """The cell's memory rows (cards live in Postgres; Qdrant holds only their vectors) for card metrics."""
    try:
        raw = psql(f"SELECT row_to_json(m) FROM memory_items m WHERE project_key = '{project_key}' ORDER BY created_at")
    except RuntimeError:
        return []
    return [json.loads(line) for line in raw.splitlines() if line.strip()]


# ------------------------------------------------------------------ worktrees
def sha_of(repo: str) -> str:
    return SUITE_SHA.get(repo) or SHA[repo]


def fresh_worktree(repo: str, path: Path) -> None:
    src = WORK / "repos" / repo
    if path.exists():
        subprocess.run(["git", "-C", str(src), "worktree", "remove", "--force", str(path)], capture_output=True)
        shutil.rmtree(path, ignore_errors=True)
    subprocess.run(["git", "-C", str(src), "worktree", "prune"], capture_output=True)
    path.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run(["git", "-C", str(src), "worktree", "add", "--detach", "-q", str(path), sha_of(repo)], check=True)
    for name, source in SUITE_FILES.items():  # git-ignored files the build needs (no secrets: placeholders only)
        (path / name).parent.mkdir(parents=True, exist_ok=True)
        shutil.copy(ASSETS / source, path / name)


SNAPSHOT_ENV = {"GIT_AUTHOR_NAME": "bench", "GIT_AUTHOR_EMAIL": "bench@localhost", "GIT_AUTHOR_DATE": "2026-01-01T00:00:00Z",
                "GIT_COMMITTER_NAME": "bench", "GIT_COMMITTER_EMAIL": "bench@localhost",
                "GIT_COMMITTER_DATE": "2026-01-01T00:00:00Z"}


def reset_code(path: Path, repo: str, task: dict | None = None) -> str:
    """Agents sometimes commit or reset; always return to the position's baseline: the task's `baseline.sha` (else the
    suite's pinned commit) with the suite's `basePatch` and the task's `baseline.patch` applied, checked out as one
    parentless snapshot commit (fixed author/date, so the same tree gives the same commit in every arm). The snapshot
    hides what a patch changed (an injected bug must not show up in `git diff HEAD~1`); both arms see the same
    one-commit history. Returns the baseline commit."""
    base = (task or {}).get("baseline") or {}
    sha = base.get("sha") or sha_of(repo)
    git = ["git", "-C", str(path)]
    subprocess.run(git + ["checkout", "-q", "--detach", "-f", sha], check=True)
    subprocess.run(git + ["reset", "--hard", "-q", sha], check=True)
    subprocess.run(git + ["clean", "-fdq"], check=True)
    for patch in [p for p in (SUITE_BASE_PATCH, base.get("patch")) if p]:
        subprocess.run(git + ["apply", "--index", str(ASSETS / patch)], check=True)
    tree = subprocess.run(git + ["write-tree"], capture_output=True, text=True, check=True).stdout.strip()
    snapshot = subprocess.run(git + ["commit-tree", tree, "-m", "snapshot"], capture_output=True, text=True,
                              check=True, env=dict(os.environ, **SNAPSHOT_ENV)).stdout.strip()
    subprocess.run(git + ["checkout", "-q", "--detach", "-f", snapshot], check=True)
    return snapshot


def changed_files(path: Path, baseline: str) -> list[str]:
    """Files the agent changed against the baseline: committed, staged, unstaged and untracked (ignored excluded)."""
    git = ["git", "-C", str(path)]
    tracked = subprocess.run(git + ["diff", "--name-only", baseline], capture_output=True, text=True).stdout
    untracked = subprocess.run(git + ["ls-files", "--others", "--exclude-standard"], capture_output=True,
                               text=True).stdout
    return sorted({line for line in (tracked + untracked).splitlines() if line.strip()})


def outside(files: list[str], allowed: list[str]) -> list[str]:
    return [f for f in files if not any(fnmatch.fnmatch(f, pattern) for pattern in allowed)]


# ------------------------------------------------------------------ agent commands
def claude_cmd(prompt: str, wt: Path, arm: str, model: str) -> tuple[list[str], dict]:
    cfg = wt.parent / (wt.name + ".mcp.json")  # outside the repo: the project's nohttp check scans every file
    servers = {}
    if is_b(arm):
        # ALWAYS_LOAD=0 mirrors a real `claude mcp add` registration (install.sh): Claude Code loads the tool
        # schemas on demand instead of carrying them in every turn.
        servers[SERVER] = {"type": "http", "url": f"{BASE}/mcp", **({"alwaysLoad": True} if ALWAYS_LOAD else {}),
                           "headers": {"X-AI-Orch-Client": "claude-code",
                                       "X-AI-Orch-Tools": "minimal" if CLAUDE_MODE == "hook" else TOOLS_PROFILE}}
    cfg.write_text(json.dumps({"mcpServers": servers}))
    allowed = ["Read", "Grep", "Glob", "Edit", "Write", "MultiEdit", "Bash", "TodoWrite"]
    if is_b(arm):
        allowed.append(f"mcp__{SERVER}")
    cmd = ["claude", "-p", prompt, "--model", model, "--output-format", "stream-json", "--verbose",
           *(["--append-system-prompt", instructions("claude", arm)] if is_b(arm) else []),
           "--setting-sources", "project", "--settings", json.dumps(claude_settings(arm)),
           "--strict-mcp-config", "--mcp-config", str(cfg),
           "--max-turns", str(MAX_TURNS), "--permission-mode", "acceptEdits",
           "--allowedTools", *allowed, "--disallowedTools", "WebFetch", "WebSearch", "Task"]
    return cmd, suite_env(dict(os.environ, AI_ORCH_URL=BASE, **({"AI_ORCH_LEVERS": levers_for(arm)} if levers_for(arm) else {})))


# Claude Code tools a coding task never uses (scheduling, multi-agent messaging, workflows, worktrees, notebooks);
# their schemas are most of the fixed context every model call re-reads. Lever "lean".
LEAN_DENY = ["CronCreate", "CronDelete", "CronList", "DesignSync", "EnterWorktree", "ExitWorktree", "ListAgents",
             "Monitor", "NotebookEdit", "PushNotification", "RemoteTrigger", "ReportFindings", "ScheduleWakeup",
             "SendMessage", "TaskCreate", "TaskGet", "TaskList", "TaskStop", "TaskUpdate", "Workflow", "Skill",
             "ListMcpResourcesTool", "ReadMcpResourceTool", "ReadMcpResourceDirTool"]


# Codex tool groups a coding task never uses; their instructions and schemas are re-read on every model call.
CODEX_LEAN_FEATURES = ["browser_use", "browser_use_external", "computer_use", "image_generation", "apps", "plugins",
                       "multi_agent", "tool_suggest", "in_app_browser"]


def claude_settings(arm: str) -> dict:
    """In hook mode arm B gets what install.sh sets up for Claude Code: a UserPromptSubmit hook that puts the rules
    and matching memory cards into the context (no session.bootstrap tool call), the Stop hook that asks for the
    task-end memory.learn, and the minimal tool profile. With --levers it gets the token levers instead: the prompt
    hook adds the build profile and the code map, the command hook formats before checks, the Stop hook checks the
    final tree and learns without an agent turn."""
    settings = {"autoMemoryEnabled": False}
    if is_b(arm) and CLAUDE_MODE == "hook" and levers_for(arm):
        hooks = ROOT / "scripts" / "hooks"
        settings["hooks"] = {
            "UserPromptSubmit": [{"hooks": [
                {"type": "command", "command": f"python3 {hooks / 'ai_orch_prompt_context.py'}", "timeout": 30}]}],
            "PreToolUse": [{"matcher": "Bash", "hooks": [
                {"type": "command", "command": f"python3 {hooks / 'ai_orch_command_hook.py'}", "timeout": 20}]}],
            "PostToolUse": [{"matcher": "Grep|Bash", "hooks": [
                {"type": "command", "command": f"python3 {hooks / 'ai_orch_search_hook.py'}", "timeout": 5}]}],
            "Stop": [{"hooks": [
                {"type": "command", "command": f"python3 {hooks / 'ai_orch_stop.py'}", "timeout": 960}]}]}
        gated = "mcp__ai-orchestration__memory_search|mcp__ai-orchestration__extras"  # as install.sh
        for event, arg in (("PreToolUse", "pre"), ("PostToolUse", "post")):
            settings["hooks"][event].append({"matcher": gated, "hooks": [
                {"type": "command", "command": f"python3 {hooks / 'ai_orch_memory_gate.py'} {arg}", "timeout": 5}]})
        if lever_on("lean", arm):
            settings["permissions"] = {"deny": LEAN_DENY}
    elif is_b(arm) and CLAUDE_MODE == "hook":
        hooks = ROOT / "scripts" / "hooks"
        settings["hooks"] = {
            "UserPromptSubmit": [{"hooks": [
                {"type": "command", "command": f"python3 {hooks / 'ai_orch_prompt_context.py'}", "timeout": 10}]}],
            "Stop": [{"hooks": [
                {"type": "command", "command": f"python3 {hooks / 'ai_orch_learn_reminder.py'}", "timeout": 10}]}]}
    return settings


def codex_cmd(prompt: str, wt: Path, arm: str, model: str) -> tuple[list[str], dict]:
    cmd = ["codex", "exec", "--json", "--skip-git-repo-check", "-C", str(wt), *CODEX_SANDBOX,
           "-c", "memories.use_memories=false", "-c", "memories.generate_memories=false",
           "-c", 'web_search="disabled"', "-c", 'approval_policy="never"']
    if model:
        cmd += ["-m", model]
    hooks_file = WORK / "codex-home" / (arm + os.environ.get("BENCH_CODEX_HOME_TAG", "")) / "hooks.json"
    if is_b(arm) and levers_for(arm):
        hooks = ROOT / "scripts" / "hooks"
        hooks_file.parent.mkdir(parents=True, exist_ok=True)
        hooks_file.write_text(json.dumps({"hooks": {
            "UserPromptSubmit": [{"hooks": [{"type": "command", "command": f"python3 {hooks / 'ai_orch_prompt_context.py'}",
                                             "timeout": 30}]}],
            "PreToolUse": [{"matcher": "^Bash$", "hooks": [
                {"type": "command", "command": f"python3 {hooks / 'ai_orch_command_hook.py'} --client codex", "timeout": 20}]}],
            "PostToolUse": [{"matcher": "^Bash$", "hooks": [
                {"type": "command", "command": f"python3 {hooks / 'ai_orch_search_hook.py'}", "timeout": 5}]}],
            "Stop": [{"hooks": [{"type": "command", "command": f"python3 {hooks / 'ai_orch_stop.py'}", "timeout": 960}]}]}}))
        cmd += ["--dangerously-bypass-hook-trust"]  # unattended run: no interactive /hooks review
        if lever_on("lean", arm):
            for feature in CODEX_LEAN_FEATURES:
                cmd += ["-c", f"features.{feature}=false"]
    elif hooks_file.exists():
        hooks_file.unlink()
    if is_b(arm):
        cmd += ["-c", f'mcp_servers.{SERVER}.url="{BASE}/mcp"',
                "-c", f'mcp_servers.{SERVER}.http_headers={{"X-AI-Orch-Client"="codex","X-AI-Orch-Tools"="{TOOLS_PROFILE}"}}',
                "-c", f'mcp_servers.{SERVER}.default_tools_approval_mode="approve"']
    cmd.append(prompt)
    env = dict(os.environ)
    # BENCH_CODEX_HOME_TAG keeps parallel runs with different instructions/hooks from sharing AGENTS.md and hooks.json
    home = WORK / "codex-home" / (arm + os.environ.get("BENCH_CODEX_HOME_TAG", ""))
    home.mkdir(parents=True, exist_ok=True)
    auth = Path.home() / ".codex" / "auth.json"
    if auth.exists() and not (home / "auth.json").exists():
        shutil.copy(auth, home / "auth.json")
    # Global (user-level) instructions, exactly where the installer puts them for a real user.
    agents_md = home / "AGENTS.md"
    if is_b(arm):
        agents_md.write_text(instructions("codex", arm))
    elif agents_md.exists():
        agents_md.unlink()
    env["CODEX_HOME"] = str(home)
    env.update(AI_ORCH_URL=BASE, AI_ORCH_CLIENT="codex", **({"AI_ORCH_LEVERS": levers_for(arm)} if levers_for(arm) else {}))
    env = suite_env(env)
    # Codex also loads skills from $HOME/.agents/skills; a clean HOME keeps the user's own skills out of both arms.
    # Build caches and the Docker client config stay reachable.
    fake_home = WORK / "codex-home" / f"home-{arm}"
    fake_home.mkdir(parents=True, exist_ok=True)
    for name in (".m2", ".docker", ".testcontainers.properties", ".gradle", ".npm", ".cache"):
        target, link = Path.home() / name, fake_home / name
        if target.exists() and not link.exists():
            link.symlink_to(target)
    env["HOME"] = str(fake_home)
    for var, default in (("GOPATH", Path.home() / "go"), ("GOCACHE", Path.home() / "Library/Caches/go-build")):
        env.setdefault(var, str(default))  # Go caches resolve from HOME; keep the warm ones
    return cmd, env


COPILOT_PORTED = ("memory_bridge.py", "ai_orch.py")  # the two files that pin the product's local endpoint


def copilot_product(arm: str) -> Path:
    """Arm B's copy of the Copilot integration, identical to skills/copilot except the pinned local port (the bench
    server instead of the user's): the guard trusts the launchers of this copy, as it trusts the real ones."""
    copy = WORK / "copilot-product" / (arm + os.environ.get("BENCH_CODEX_HOME_TAG", ""))
    shutil.rmtree(copy, ignore_errors=True)
    shutil.copytree(ROOT / "skills" / "copilot", copy / "skills" / "copilot",
                    ignore=shutil.ignore_patterns("__pycache__", "test_*.py"))
    # `ai_orch memory learn` validates against the repository's canonical schema (repository root of the copy)
    shutil.copytree(ROOT / "contracts" / "agent-learning", copy / "contracts" / "agent-learning")
    # the step-memory hook runs the repository's shared hook modules (scripts/hooks), as a real install does
    shutil.copytree(ROOT / "scripts" / "hooks", copy / "scripts" / "hooks", ignore=shutil.ignore_patterns("__pycache__"))
    scripts = copy / "skills" / "copilot" / "ai-orchestration-memory" / "scripts"
    for name in COPILOT_PORTED:
        path = scripts / name
        path.write_text(path.read_text().replace("127.0.0.1:18080", f"127.0.0.1:{PORT}"))
    return scripts


def copilot_cmd(prompt: str, wt: Path, arm: str, model: str) -> tuple[list[str], dict]:
    """GitHub Copilot CLI in a COPILOT_HOME of its own per arm (A: empty; B: what install_user_integration.py sets up
    for a real user: guard + memory-gate hooks, skills, startup instructions, the `ai_orch` launcher, the
`ai-orchestration` custom agent it is started with) and a clean
    HOME, so the user's own Copilot setup, skills and MCP servers reach neither arm. Built-in MCPs are off in both.
    Authentication: the GitHub token of `gh`, in this process's environment only."""
    tag = arm + os.environ.get("BENCH_CODEX_HOME_TAG", "")
    home = copilot_home(arm)
    shutil.rmtree(home, ignore_errors=True)
    home.mkdir(parents=True)
    env = dict(os.environ)
    if is_b(arm):
        scripts = copilot_product(arm)
        bin_dir = WORK / "copilot-home" / f"bin-{tag}"
        shutil.rmtree(bin_dir, ignore_errors=True)
        subprocess.run([sys.executable, str(scripts / "install_user_integration.py"), "--copilot-home", str(home),
                        "--bin-dir", str(bin_dir)], check=True, capture_output=True, text=True)
        env["PATH"] = f"{bin_dir}{os.pathsep}{env.get('PATH', '')}"
    usage = copilot_usage_file(wt)
    usage.unlink(missing_ok=True)
    cmd = ["copilot", "-p", prompt, "--output-format", "json", "--usage-output-file", str(usage),
           "--allow-all-tools", "--no-ask-user", "--no-auto-update", "--disable-builtin-mcps"]
    if is_b(arm):
        cmd += ["--agent", "ai-orchestration"]  # the documented launch of the installed product (its custom agent)
    if model:
        cmd += ["--model", model]
    token = os.environ.get("COPILOT_GITHUB_TOKEN") or command_output(["gh", "auth", "token"])
    if token:
        env["COPILOT_GITHUB_TOKEN"] = token
    env["COPILOT_HOME"] = str(home)
    env["AI_ORCH_UNATTENDED"] = "1"  # no user to answer a rules question (documented for unattended runs)
    fake_home = WORK / "copilot-home" / f"home-{tag}"
    fake_home.mkdir(parents=True, exist_ok=True)
    for name in (".m2", ".docker", ".testcontainers.properties", ".gradle", ".npm", ".cache"):
        target, link = Path.home() / name, fake_home / name
        if target.exists() and not link.exists():
            link.symlink_to(target)
    env["HOME"] = str(fake_home)
    for var, default in (("GOPATH", Path.home() / "go"), ("GOCACHE", Path.home() / "Library/Caches/go-build")):
        env.setdefault(var, str(default))
    return cmd, suite_env(env)


def copilot_home(arm: str) -> Path:
    return WORK / "copilot-home" / (arm + os.environ.get("BENCH_CODEX_HOME_TAG", ""))


def save_copilot_session(arm: str, target: Path) -> None:
    """Copilot's per-call usage (session-store.db, table assistant_usage_events) of the run that just ended, copied
    with SQLite's backup API (the WAL content is included) before the next position resets the home."""
    import sqlite3
    source = copilot_home(arm) / "session-store.db"
    if not source.exists():
        return
    target.parent.mkdir(parents=True, exist_ok=True)
    src, dst = sqlite3.connect(f"file:{source}?mode=ro", uri=True), sqlite3.connect(target)
    try:
        src.backup(dst)
    finally:
        src.close()
        dst.close()


def copilot_usage_file(wt: Path) -> Path:
    return wt.parent / (wt.name + ".copilot-usage.json")  # outside the repo, like the Claude MCP config


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
MAVEN_HIDDEN = ("./mvnw -q -Dspring-javaformat.skip=true -Dcheckstyle.skip test -Dtest='{tests}' "
                "-Dsurefire.failIfNoSpecifiedTests=false")


def _run(command, wt: Path, timeout: int, retries: int = 1) -> subprocess.CompletedProcess:
    """A scoring command. A hang (seen under heavy machine load: an integration test's HTTP client waiting forever on
    an idle embedded server) is retried once instead of failing the task or crashing the run."""
    env = suite_env(dict(os.environ))
    shell = not isinstance(command, list)
    for attempt in range(retries + 1):
        try:
            return subprocess.run(command, shell=shell, cwd=wt, capture_output=True, text=True, timeout=timeout,
                                  env=env)
        except subprocess.TimeoutExpired:
            print(f"scoring command timed out after {timeout}s (attempt {attempt + 1}): {command}", flush=True)
    return subprocess.CompletedProcess(command, 124, "", f"timed out after {timeout}s, {retries + 1} attempts")


def suite_env(env: dict) -> dict:
    """The suite's toolchain (a prepared Python virtualenv first on PATH, JAVA_HOME for Gradle, ...), for both arms
    and for scoring."""
    for entry in reversed(SUITE_PATH):
        env["PATH"] = f"{(ROOT / entry).resolve()}{os.pathsep}{env.get('PATH', '')}"
    env.update(SUITE_ENV)
    return env


def setting(task: dict, suite: dict, name: str, default=None):
    """Per-task override of a suite setting (multi-module repos check different modules per task)."""
    return task.get(name, suite.get(name, default))


def score(task: dict, suite: dict, wt: Path, baseline: str, final_text: str) -> dict:
    """Score the final tree by task kind. Every kind records the files changed against the baseline; `allowedPaths`
    is enforced for investigation and tests tasks and reported (outOfScope) for code tasks."""
    files = changed_files(wt, baseline)
    allowed = task.get("allowedPaths")
    common = {"kind": task.get("kind", "code"), "changedFiles": files[:50],
              "outOfScope": outside(files, allowed)[:50] if allowed else []}
    kind = common["kind"]
    if kind == "investigation":
        return common | score_answer(task, final_text, files, wt)
    if kind == "tests":
        return common | score_tests(task, suite, wt, baseline)
    return common | score_code(task, suite, wt)


def score_code(task: dict, suite: dict, wt: Path) -> dict:
    """Success = the quality gate, the hidden tests and the project's own tests all pass on the final tree. A suite
    can run hidden and own tests in one command (petclinic: the whole Maven suite with the hidden test classes in it,
    `ownTestCommand` absent) or separately."""
    quality = _run(setting(task, suite, "qualityCommand"), wt, 900)
    hidden_target = setting(task, suite, "hiddenTarget")

    def run_hidden(names: list[str], tests_filter: str) -> subprocess.CompletedProcess:
        placed = []
        for name in names:
            dest = wt / hidden_target / name
            dest.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy(ASSETS / "hidden" / task["id"] / Path(name).name, dest)
            placed.append(dest)
        try:
            return _run(setting(task, suite, "hiddenTestCommand", MAVEN_HIDDEN).format(
                tests=tests_filter, hidden=" ".join(str(Path(hidden_target) / n) for n in names)), wt, 900)
        finally:
            for dest in placed:
                dest.unlink(missing_ok=True)

    tests = run_hidden(task["hidden"], task.get("tests", ""))
    # Convention probes (plan §6.2): reported as probeOk, never part of success.
    probe = run_hidden(task["probe"], task.get("probeTests", "")) if task.get("probe") else None
    own_cmd = setting(task, suite, "ownTestCommand")
    own = _run(own_cmd, wt, 900) if own_cmd else None
    own_ok = own is None or own.returncode == 0
    return {"success": quality.returncode == 0 and tests.returncode == 0 and own_ok,
            "qualityOk": quality.returncode == 0, "testsOk": tests.returncode == 0, "ownTestsOk": own_ok,
            "probeOk": None if probe is None else probe.returncode == 0,
            "testTail": failure_lines(tests.stdout + tests.stderr) if tests.returncode else "",
            "ownTail": failure_lines(own.stdout + own.stderr) if own is not None and own.returncode else "",
            "qualityTail": failure_lines(quality.stdout + quality.stderr) if quality.returncode else ""}


def score_tests(task: dict, suite: dict, wt: Path, baseline: str) -> dict:
    """Test-writing task: no change outside `allowedPaths` (test sources), the agent's tests pass on the baseline
    code, and every hidden mutant of the behavior (bench/mutants/<task>/*.patch, production code only) makes them
    fail."""
    files = changed_files(wt, baseline)
    scope_ok = not outside(files, task["allowedPaths"]) and bool(files)
    quality = _run(setting(task, suite, "qualityCommand"), wt, 900)
    command = task["testCommand"]
    passes = _run(command, wt, 900)
    killed, survivors = 0, []
    for mutant in task["mutants"]:
        patch = ASSETS / "mutants" / task["id"] / mutant
        if subprocess.run(["git", "-C", str(wt), "apply", str(patch)], capture_output=True).returncode != 0:
            survivors.append(f"{mutant}: does not apply")
            continue
        try:
            if _run(command, wt, 900, retries=0).returncode != 0:
                killed += 1
            else:
                survivors.append(mutant)
        finally:
            subprocess.run(["git", "-C", str(wt), "apply", "-R", str(patch)], capture_output=True)
    ok = scope_ok and quality.returncode == 0 and passes.returncode == 0 and killed == len(task["mutants"])
    return {"success": ok, "scopeOk": scope_ok, "qualityOk": quality.returncode == 0,
            "testsOk": passes.returncode == 0, "mutantsKilled": killed, "mutants": len(task["mutants"]),
            "survivors": survivors,
            "testTail": failure_lines(passes.stdout + passes.stderr) if passes.returncode else "",
            "qualityTail": failure_lines(quality.stdout + quality.stderr) if quality.returncode else ""}


ANSWER_LINE = re.compile(r"^\s*[-*]?\s*`?([A-Za-z][\w-]*)`?\s*:\s*(.+?)\s*$")


def answer_block(text: str) -> dict[str, str] | None:
    """The last `ANSWER:` block of the final message: `key: value` lines up to the first blank or non-matching line."""
    lines = text.splitlines()
    starts = [i for i, line in enumerate(lines) if re.match(r"^\s*[#*`]*\s*ANSWER\s*:?\s*[*`]*\s*$", line)]
    if not starts:
        return None
    out: dict[str, str] = {}
    for line in lines[starts[-1] + 1:]:
        if line.strip().startswith("```"):
            continue
        m = ANSWER_LINE.match(line)
        if not m:
            if out:
                break
            continue
        out[m.group(1).lower()] = m.group(2)
    return out


def normalize(value: str, kind: str, wt: Path | None = None) -> str:
    v = value.strip().strip("`'\"").rstrip(".").strip()
    if kind == "path":
        if wt is not None:
            v = v.replace(str(wt) + "/", "")
        v = re.sub(r"\s+\(.*\)$", "", v)  # trailing note: "a/B.kt (line 40)"
        v = re.sub(r"^\./", "", v)
        return re.sub(r":\d+(-\d+)?$", "", v.strip().strip("`'\"")).strip()  # file:line
    if kind == "symbol":
        v = v.replace("::", ".").replace("#", ".")
        return re.sub(r"\(.*\)$", "", v).strip().strip("`").lower()  # "f(Int)", "f (package x)"
    return re.sub(r"\s+", " ", v).lower()


MULTI = re.compile(r"\s(?:or|and/or)\s|[;|]|,\s")


def score_answer(task: dict, text: str, files: list[str], wt: Path) -> dict:
    """Deterministic investigation scoring against bench/answers/<task>.json:
      {"keys": {"<key>": {"kind": "path|symbol|text", "accept": [aliases], "multi": false}},
       "contradict": ["<regex, case-insensitive and multiline, against the ANSWER block as key: value lines>"]}
    Pass iff every key matches an alias (a multi key: the set of values equals the accepted set), no contradictory
    fact appears, a single-valued key lists one candidate, and no file was changed. A missing block or key is
    `ambiguous` (failure, listed for manual adjudication; the score does not change)."""
    key = json.loads((ASSETS / "answers" / f"{task['id']}.json").read_text())
    key["keys"] = {name.lower(): spec for name, spec in key["keys"].items()}  # answer_block lowercases keys too
    block = answer_block(text)
    if block is None or any(k not in block for k in key["keys"]):
        missing = sorted(set(key["keys"]) - set(block or {}))
        return {"success": False, "answerStatus": "ambiguous", "answerMissing": missing, "answer": block}
    wrong = []
    for name, spec in key["keys"].items():
        kind, raw = spec.get("kind", "text"), block[name]
        accept = {normalize(a, kind) for a in spec["accept"]}
        if spec.get("multi"):
            given = {normalize(v, kind, wt) for v in re.split(r",\s*|;\s*", raw) if v.strip()}
            if given != accept:
                wrong.append(name)
        elif MULTI.search(raw) or normalize(raw, kind, wt) not in accept:
            wrong.append(name)
    joined = "\n".join(f"{k}: {v}" for k, v in block.items())
    contradictions = [c for c in key.get("contradict", []) if re.search(c, joined, re.IGNORECASE | re.MULTILINE)]
    ok = not wrong and not contradictions and not files
    return {"success": ok, "answerStatus": "scored", "answerWrong": wrong, "answerContradictions": contradictions,
            "editedInInvestigation": bool(files), "answer": block}


def final_message(agent: str, events: list[dict]) -> str:
    if agent == "copilot":
        messages = [(e.get("data") or {}).get("content") or "" for e in events if e.get("type") == "assistant.message"]
        messages = [m for m in messages if m]
        return messages[-1] if messages else ""
    if agent == "claude":
        results = [e for e in events if e.get("type") == "result"]
        return (results[-1].get("result") or "") if results else ""
    messages = [e["item"].get("text") or "" for e in events
                if e.get("type") == "item.completed" and (e.get("item") or {}).get("type") == "agent_message"]
    return messages[-1] if messages else ""


def failure_lines(text: str) -> str:
    keep = [line for line in text.splitlines()
            if any(k in line for k in ("FAIL", "ERROR]", "expected", "Expected", "Tests run:", "violation", "Caused by",
                                       "_test.go:", "Error", "assert"))
            and not line.startswith("WARNING")] if any(k in text for k in ("FAIL", "ERROR]", "Tests run:")) else \
        [line for line in text.splitlines() if line.strip()][-25:]
    return "\n".join(keep[:25])[-2000:]


# ------------------------------------------------------------------ main loop
INSTRUCTIONS = BENCH / "instructions"
TOOLS_PROFILE = "full"
CLAUDE_MODE = "tool"
UPTO = ""
SUITE_SHA: dict[str, str] = {}  # repo -> pinned commit, from the suite file ("sha")
SUITE_PATH: list[str] = []  # directories (relative to this repository) put first on PATH, from "pathPrepend"
SUITE_ENV: dict[str, str] = {}  # extra environment for agents and scoring, from the suite's "env" (e.g. JAVA_HOME)
SUITE_FILES: dict[str, str] = {}  # worktree path -> file under bench/, copied into every fresh worktree ("worktreeFiles")
BRIEFINGS: Path | None = None  # --briefings
ASSETS = BENCH  # where hidden checks, answers, mutants, patches and worktree files live (PRIVATE for private suites)
SUITE_BASE_PATCH = ""  # patch under ASSETS committed on top of the pinned sha before every position ("basePatch")


def resolve_env(value: str) -> str:
    """`{java_home:21}` -> the installed JDK 21 (macOS java_home), so suite files carry no machine paths."""
    m = re.fullmatch(r"\{java_home:(\d+)\}", value)
    if not m:
        return value
    return subprocess.run(["/usr/libexec/java_home", "-v", m.group(1)], capture_output=True, text=True,
                          check=True).stdout.strip()
LEVERS = ""  # empty: the product as of v12 (learn reminder); "all" or a comma list: the token levers


def levers_for(arm: str) -> str:
    """The levers an arm runs with: --levers for every B arm, plus the lean tool set for Blean."""
    if arm == "Blean":
        return ",".join(x for x in (LEVERS or "all", "lean") if x)
    return LEVERS if is_b(arm) else ""


def lever_on(name: str, arm: str) -> bool:
    levers = levers_for(arm).split(",")
    return bool(levers_for(arm)) and ("all" in levers or name in levers)


def instructions(agent: str, arm: str = "B") -> str:
    text = (INSTRUCTIONS / f"{agent}.md").read_text()
    return text + LEARN_REQUIRED if arm == "Blearn" else text


def cell_id(agent: str, arm: str, family: dict, rep: int) -> str:
    return f"{agent}-{arm}-{family['id']}-r{rep}"


def write_atomic(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + ".tmp")
    tmp.write_text(text)
    tmp.replace(path)


def with_infra_retry(what: str, fn, *args):
    """Setup steps outside the agent (worktree, scan, baseline): one retry, then InfraError with the reason."""
    for attempt in (1, 2):
        try:
            return fn(*args)
        except Exception as exc:  # noqa: BLE001 - classified and re-raised below
            print(f"infra: {what} failed (attempt {attempt}): {exc}", flush=True)
            if attempt == 2:
                raise InfraError(f"{what}: {exc}") from exc
            time.sleep(10)


def run_chain(agent: str, arm: str, family: dict, suite: dict, rep: int, model: str, out: Path) -> None:
    """One cell = (agent, arm, family, rep). Every position starts from its own baseline; memory is carried within
    the cell (cleared at its start, and before every position in Bnomem). The cell gets its own worktree path and so
    its own project binding: no state is shared across arms or repetitions. The cell is complete only when
    cells/<cell>.json names its attempt; rows of other attempts of the same cell are superseded."""
    from profile import profile  # local module: bench/profile.py

    cell = cell_id(agent, arm, family, rep)
    attempt = uuid.uuid4().hex[:12]
    wt = WT_ROOT / cell  # fixed path => stable project binding across the chain
    with_infra_retry("worktree", fresh_worktree, suite["repo"], wt)
    key = None
    if is_b(arm):
        key = with_infra_retry("bind", project_key_for, wt)
        reset_memory(key)
    scanned = None  # baseline the code map was built from
    tasks = family["tasks"]
    if UPTO and any(t["id"] == UPTO for t in tasks):
        tasks = tasks[: [t["id"] for t in tasks].index(UPTO) + 1]
    for index, task in enumerate(tasks):
        baseline = with_infra_retry("baseline", reset_code, wt, suite["repo"], task)
        if key:
            # "Proje ekle" on the position's code: the code map always follows the baseline (stale probes included),
            # memory does not. Rescanned before every task, also on the same baseline: the server rescans files on its
            # own (e.g. a learned symbol it could not resolve yet), which records the previous task's edits that the
            # reset just removed.
            with_infra_retry("scan", scan_project, wt, key)
            scanned = baseline
        if key and arm == "Bnomem":
            reset_memory(key)
        before = memory_count(key) if key else None
        prompt = task["prompt"]
        brief = BRIEFINGS / f"{task['id']}.md" if BRIEFINGS else None
        if brief and brief.exists() and is_b(arm):  # experiment: what the product's memory would hand the agent
            prompt = brief.read_text() + "\n\n" + prompt
        cmd, env = {"claude": claude_cmd, "codex": codex_cmd, "copilot": copilot_cmd}[agent](prompt, wt, arm, model)
        events, stderr, code, wall, killed = run_process(cmd, env, wt)
        raw = out / "raw" / f"{cell}-{task['id']}-{attempt}.jsonl"
        raw.parent.mkdir(parents=True, exist_ok=True)
        if agent == "copilot":  # token usage is only in the usage file: kept with the raw events
            usage = copilot_usage_file(wt)
            events.append({"type": "_usage", "usage": json.loads(usage.read_text()) if usage.exists() else None})
            save_copilot_session(arm, out / "sessions" / f"{cell}-{task['id']}-{attempt}.db")
        raw.write_text("\n".join(json.dumps(e) for e in events))
        prof = profile(agent, events)
        try:
            result = score(task, suite, wt, baseline, final_message(agent, events))
        except Exception as exc:  # noqa: BLE001 - a scorer crash is the harness's fault, not the agent's
            result = {"success": None, "infraFailure": f"scorer: {exc}"}
        if key:
            write_atomic(out / "memory" / cell / f"{attempt}-p{index + 1}.json", json.dumps(memory_snapshot(key)))
        row = {"at": datetime.now(timezone.utc).isoformat(timespec="seconds"), "agent": agent, "model": model,
               "arm": arm, "family": family["id"], "task": task["id"], "position": index + 1, "rep": rep,
               "cell": cell, "attempt": attempt, "type": task.get("type", ""), "role": task.get("role", ""),
               "chain": bool(family.get("chain")), "suite": suite.get("suite", ""), "baseline": baseline,
               "exitCode": code, "killed": killed, "wallS": round(wall, 1), **prof, **result,
               "memoriesBefore": before, "memoriesAfter": memory_count(key) if key else None,
               "stderrTail": stderr[-400:] if code else ""}
        with (out / "results.jsonl").open("a") as f:
            f.write(json.dumps(row) + "\n")
        print(f"{agent} {arm} {task['id']} r{rep}: success={row['success']} tokens={row['totalTokens']} "
              f"turns={row['turns']} wall={row['wallS']}s", flush=True)
    write_atomic(out / "cells" / f"{cell}.json", json.dumps({"cell": cell, "attempt": attempt, "positions": len(tasks)}))
    if os.environ.get("BENCH_KEEP_WORKTREES") != "1":
        subprocess.run(["git", "-C", str(WORK / "repos" / suite["repo"]), "worktree", "remove", "--force", str(wt)],
                       capture_output=True)


def done_cells(out: Path) -> set[str]:
    return {p.stem for p in (out / "cells").glob("*.json")} if (out / "cells").exists() else set()


def command_output(cmd: list[str], cwd: Path | None = None) -> str:
    try:
        return subprocess.run(cmd, cwd=cwd, capture_output=True, text=True, timeout=60).stdout.strip()
    except (OSError, subprocess.TimeoutExpired):
        return ""


def provenance(suite_file: Path, args: argparse.Namespace) -> dict:
    """What produced the numbers: agent CLI versions, product commit and a hash of its uncommitted changes (tracked
    diff + untracked files), the suite file hash and the harness settings."""
    dirty = hashlib.sha256(command_output(["git", "diff", "HEAD"], ROOT).encode())
    for name in sorted(command_output(["git", "ls-files", "--others", "--exclude-standard"], ROOT).splitlines()):
        path = ROOT / name
        if path.is_file():
            dirty.update(name.encode() + path.read_bytes())
    config = {k: v for k, v in vars(args).items() if k not in ("resume", "out")}
    return {"claudeVersion": command_output(["claude", "--version"]),
            "codexVersion": command_output(["codex", "--version"]),
            "copilotVersion": (command_output(["copilot", "--version"]).splitlines() or [""])[0],
            "productCommit": command_output(["git", "rev-parse", "HEAD"], ROOT), "productDirtyHash": dirty.hexdigest(),
            "suiteHash": hashlib.sha256(suite_file.read_bytes()).hexdigest(),
            "instructionsHash": hashlib.sha256(b"".join(p.read_bytes() for p in sorted(INSTRUCTIONS.glob("*.md"))))
            .hexdigest(), "config": config,
            "configHash": hashlib.sha256(json.dumps(config, sort_keys=True).encode()).hexdigest()}


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--suite", default="dev")
    ap.add_argument("--agents", default="claude")
    ap.add_argument("--arms", default="A,B", help="comma list of A, B, Blearn, Bnomem, Blean")
    ap.add_argument("--families", default="")
    ap.add_argument("--reps", type=int, default=1)
    ap.add_argument("--claude-model", default="sonnet")
    ap.add_argument("--codex-model", default="")
    ap.add_argument("--copilot-model", default="claude-sonnet-5")
    ap.add_argument("--out", default="")
    ap.add_argument("--resume", action="store_true",
                    help="continue --out: skip completed cells, rerun interrupted ones from their first position")
    ap.add_argument("--upto", default="",
                    help="probe: stop each family chain after this task id (the tasks before it still run and learn)")
    ap.add_argument("--claude-mode", choices=["tool", "hook"], default="tool",
                    help="arm B for Claude: session.bootstrap tool call (tool) or the UserPromptSubmit hook (hook)")
    ap.add_argument("--instructions", default="", help="directory with claude.md/codex.md (default bench/instructions)")
    ap.add_argument("--tools", default="full", choices=["full", "lean"], help="MCP tool profile for arm B")
    ap.add_argument("--briefings", default="",
                    help="experiment: directory of <task>.md texts prepended to B-arm prompts (simulated memory briefing)")
    ap.add_argument("--levers", default="", help="arm B token levers: all, or profile,codemap,format,check,autolearn,lean")
    args = ap.parse_args()
    global INSTRUCTIONS, TOOLS_PROFILE, CLAUDE_MODE, UPTO, LEVERS, SUITE_BASE_PATCH, ASSETS, BRIEFINGS
    LEVERS = args.levers
    BRIEFINGS = Path(args.briefings).expanduser() if args.briefings else None
    if args.instructions:
        INSTRUCTIONS = (ROOT / args.instructions).resolve()
    TOOLS_PROFILE = args.tools
    CLAUDE_MODE = args.claude_mode
    UPTO = args.upto
    for arm in args.arms.split(","):
        if arm != "A" and not is_b(arm):
            ap.error(f"unknown arm {arm}")
    suite_file = BENCH / "tasks" / f"{args.suite}.json"
    if not suite_file.exists():
        suite_file = PRIVATE / "tasks" / f"{args.suite}.json"
    suite = json.loads(suite_file.read_text())
    if suite.get("private"):
        ASSETS = PRIVATE
    if suite.get("sha"):
        SUITE_SHA[suite["repo"]] = suite["sha"]
    SUITE_PATH.extend(suite.get("pathPrepend") or [])
    SUITE_ENV.update({k: resolve_env(v) for k, v in (suite.get("env") or {}).items()})
    SUITE_FILES.update(suite.get("worktreeFiles") or {})
    SUITE_BASE_PATCH = suite.get("basePatch", "")
    families = [f for f in suite["families"] if not args.families or f["id"] in args.families.split(",")]
    if args.resume and not args.out:
        ap.error("--resume needs --out <run-id>")
    run_id = args.out or datetime.now().strftime(f"{args.suite}-%Y%m%d-%H%M%S")
    out = WORK / "results" / run_id
    out.mkdir(parents=True, exist_ok=True)
    prov = provenance(suite_file, args)
    meta = out / "meta.json"
    if args.resume and meta.exists():
        old = json.loads(meta.read_text())
        changed = [k for k in ("productDirtyHash", "productCommit", "suiteHash", "instructionsHash", "configHash",
                               "claudeVersion", "codexVersion") if old.get(k) != prov[k]]
        if changed:
            sys.exit(f"--resume refused: {', '.join(changed)} differ from the interrupted run")
    else:
        write_atomic(meta, json.dumps(prov | {"args": vars(args), "sha": SHA | SUITE_SHA}, indent=2))
    finished = done_cells(out) if args.resume else set()
    sys.path.insert(0, str(BENCH))
    infra = []
    for rep in range(1, args.reps + 1):
        for family in families:
            for agent in args.agents.split(","):
                arms = args.arms.split(",")
                if rep % 2 == 0:
                    arms = list(reversed(arms))  # alternate arm order to spread provider cache warm-up
                for arm in arms:
                    if cell_id(agent, arm, family, rep) in finished:
                        continue
                    model = {"claude": args.claude_model, "copilot": args.copilot_model}.get(agent, args.codex_model)
                    try:
                        run_chain(agent, arm, family, suite, rep, model, out)
                    except InfraError as exc:  # recorded, cell left incomplete: --resume reruns it
                        infra.append(cell_id(agent, arm, family, rep))
                        with (out / "infra.jsonl").open("a") as f:
                            f.write(json.dumps({"cell": cell_id(agent, arm, family, rep), "reason": str(exc),
                                                "at": datetime.now(timezone.utc).isoformat(timespec="seconds")}) + "\n")
    print(f"results: {out}" + (f" (infra failures, rerun with --resume: {', '.join(infra)})" if infra else ""))


if __name__ == "__main__":
    main()
