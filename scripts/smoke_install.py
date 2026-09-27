#!/usr/bin/env python3
"""Post-install smoke test (CI and local): the product's first minutes, without an LLM.

  1. `ai_orch project add` on a tiny throw-away git repository
  2. a session learns one card (MCP memory.learn, as an agent would at the end of a task)
  3. a NEW session's session.bootstrap(task) returns that card
  4. the panel answers at /

  AI_ORCH_URL=http://127.0.0.1:18080 python3 scripts/smoke_install.py
"""
from __future__ import annotations

import os
import subprocess
import sys
import tempfile
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import ai_orch_admin as admin  # noqa: E402


def main() -> int:
    repo = Path(tempfile.mkdtemp(prefix="ai-orch-smoke-")).resolve()
    (repo / "src").mkdir()
    (repo / "src/Greeter.java").write_text("public class Greeter {\n  public String greet(String n) { return \"Hi \" + n; }\n}\n")
    (repo / "Makefile").write_text("test:\n\t@echo ok\n")
    subprocess.run(["git", "init", "-q", str(repo)], check=True)

    assert admin.main(["project", "add", str(repo)]) == 0, "project add failed"
    learned = admin.mcp_call("memory.learn", {"rootPath": str(repo), "candidates": [{
        "kind": "procedure", "summary": "Running this project's tests",
        "content": "Run `make test` from the repository root; it prints ok when the suite passes.",
        "locators": [{"kind": "file", "ref": "Makefile"}]}]}, client="claude-code")
    assert learned.get("created", 0) + learned.get("reused", 0) >= 1, f"learn failed: {learned}"
    boot = admin.mcp_call("session.bootstrap", {"rootPath": str(repo), "task": "run the tests",
                                                "loadRules": True}, client="claude-code")
    cards = [c.get("summary") for c in boot.get("memory", [])]
    assert "Running this project's tests" in cards, f"card not recalled: {boot}"
    with urllib.request.urlopen(admin.BASE + "/", timeout=10) as r:
        assert r.status == 200, "panel not served"
    print(f"smoke ok: project added, card learned and recalled ({cards}), panel up")
    return 0


if __name__ == "__main__":
    sys.exit(main())
