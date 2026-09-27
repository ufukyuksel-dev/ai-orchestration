#!/usr/bin/env python3
"""Build the public release copy from an explicit allowlist, then scrub it.

  python3 scripts/release/make_release_copy.py ../ai-orchestration-public      # copy + scrub report
  python3 scripts/release/make_release_copy.py ../ai-orchestration-public --scrub-only

The history of this working repository is never published: the release is a fresh folder built from the allowlist
below (git init / commit / push happen separately, with the owner's approval). The copy is rejected when any scrub
check finds a secret, a personal path or a name that must not ship.
"""
from __future__ import annotations

import argparse
import re
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

# top-level entries that ship (directories are copied recursively, minus EXCLUDE)
ALLOW = [
    "src", "pom.xml", "mvnw", "mvnw.cmd", ".mvn",
    "install.sh", "deploy/docker-compose.local.yml",
    "scripts/install_agent_instructions.py", "scripts/install_project_copilot_instructions.py",
    "scripts/legacy_instruction_hashes.json", "scripts/ai_orch_admin.py", "scripts/codex_mcp_config.py",
    "scripts/smoke_install.py", "scripts/claude_hook_config.py", "scripts/test_install_agent_instructions.py",
    "scripts/test_install_project_copilot_instructions.py", "scripts/release", "scripts/hooks",
    "skills", "bench", "config", "contracts", "eval", "tools", "docs/public", "docs/assets",
    ".github/workflows/ci.yml", ".github/copilot-instructions.md", ".gitignore", ".gitleaks.toml",
    "LICENSE", "README.md", "CLAUDE.md", "AGENTS.md",
]
# never shipped, even inside allowed directories
# the internal measurement harness that bench/ replaced (scripts/benchmarks, scripts/learning) and its tests
EXCLUDE_FILES = {
    "src/test/python/test_codex_transfer_client.py",
    "src/test/python/test_acceptance_manifest.py",
    "src/test/python/test_learning_transfer_benchmark.py",
    "src/test/python/test_runtime_parity.py",
    "src/test/python/test_backend_cost_f9.py",
    "src/test/python/test_retrieval_f8b_eval.py",
}
# docs/public/* is published as docs/*
RENAME = {"docs/public": "docs"}

SCRUB = [
    (r"/Users/(?!you/|sen/)[A-Za-z]", "absolute macOS home path"),
    (r"/home/(?!dev/|you/|sen/)[a-z][a-z0-9_-]+/", "absolute Linux home path"),
    (r"(?i)\bufuk\b|U0103383", "personal name / employee id"),
    (r"(?i)\bykb\b|yapı ?kredi|tappay|worldmobile", "former organisation / product name"),
    (r"[A-Za-z0-9._%+-]+@(gmail|hotmail|outlook|yahoo)\.com", "personal e-mail"),
    (r"mcp_[0-9a-f]{32,}", "MCP API key"),
    (r"(?i)(api[_-]?key|secret|token)\s*[:=]\s*['\"][A-Za-z0-9_\-]{20,}['\"]", "credential literal"),
]
# Deliberately fake secrets in redaction tests: (file, text on the line). Nothing else may match.
ALLOWED_FIXTURES = {
    ("skills/copilot/ai-orchestration-memory/scripts/test_memory_bridge.py", "do-not-reflect-this-token"),
    ("skills/copilot/ai-orchestration-memory/scripts/test_copilot_pre_tool_guard.py", "ghp_secret_should_not_appear"),
    ("src/test/java/com/mbworldwideapps/aiorchestration/modules/scanner/ScannerServiceTest.java",
     "superSecretToken123456789"),
}
TEXT_SUFFIXES = {".java", ".py", ".js", ".cjs", ".mjs", ".ts", ".md", ".yml", ".yaml", ".json", ".sql", ".xml",
                 ".properties", ".html", ".css", ".sh", ".toml", ".txt", ".cmd", ""}


def excluded(rel: str) -> bool:
    parts = rel.split("/")
    if {"__pycache__", "node_modules", ".DS_Store"} & set(parts) or rel.endswith(".pyc"):
        return True
    return rel.startswith("bench/.work/") or rel in EXCLUDE_FILES


def copy(dest: Path) -> int:
    count = 0
    for entry in ALLOW:
        src = ROOT / entry
        if not src.exists():
            print(f"  missing allowlisted entry: {entry}", file=sys.stderr)
            continue
        files = [src] if src.is_file() else [p for p in src.rglob("*") if p.is_file()]
        for f in files:
            rel = f.relative_to(ROOT).as_posix()
            if excluded(rel):
                continue
            out_rel = rel
            for old, new in RENAME.items():
                if rel == old or rel.startswith(old + "/"):
                    out_rel = new + rel[len(old):]
            target = dest / out_rel
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(f, target)
            count += 1
    return count


def scrub(dest: Path) -> list[str]:
    findings = []
    patterns = [(re.compile(p), why) for p, why in SCRUB]
    for f in dest.rglob("*"):
        if not f.is_file() or f.suffix not in TEXT_SUFFIXES or f.stat().st_size > 2_000_000:
            continue
        if f.relative_to(dest).as_posix() == "scripts/release/make_release_copy.py":
            continue  # holds the scrub patterns themselves
        try:
            text = f.read_text(encoding="utf-8")
        except UnicodeDecodeError:
            continue
        for number, line in enumerate(text.splitlines(), 1):
            for pattern, why in patterns:
                rel = f.relative_to(dest).as_posix()
                if pattern.search(line) and not any(rel == path and text in line for path, text in ALLOWED_FIXTURES):
                    findings.append(f"{f.relative_to(dest)}:{number}: {why}: {line.strip()[:140]}")
    config = ["--config", str(dest / ".gitleaks.toml")] if (dest / ".gitleaks.toml").exists() else []
    leaks = subprocess.run(["gitleaks", "detect", "--no-git", "--source", str(dest), "--no-banner", *config,
                            "--redact", "--exit-code", "3"], capture_output=True, text=True)
    if leaks.returncode == 3:
        findings.append("gitleaks: " + (leaks.stdout + leaks.stderr).strip()[-2000:])
    elif leaks.returncode not in (0,):
        findings.append(f"gitleaks could not run (exit {leaks.returncode}): {leaks.stderr.strip()[:300]}")
    return findings


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("dest")
    ap.add_argument("--scrub-only", action="store_true")
    args = ap.parse_args()
    dest = Path(args.dest).expanduser().resolve()
    if dest == ROOT or ROOT in dest.parents:
        print("the release copy must live outside this repository", file=sys.stderr)
        return 2
    if not args.scrub_only:
        if dest.exists() and any(dest.iterdir()):
            print(f"{dest} is not empty; remove it first", file=sys.stderr)
            return 2
        dest.mkdir(parents=True, exist_ok=True)
        print(f"copied {copy(dest)} files to {dest}")
    findings = scrub(dest)
    for line in findings:
        print("  ✗ " + line)
    print("scrub: clean" if not findings else f"scrub: {len(findings)} finding(s): the copy must not be published")
    return 0 if not findings else 1


if __name__ == "__main__":
    sys.exit(main())
