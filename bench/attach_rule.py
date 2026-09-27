#!/usr/bin/env python3
"""Attach an instruction rule to a code node through the panel API (draft → preview → typed approval → promote),
exactly as a person does in the graph's detail card. Used by the F3/F4 acceptance checks.

  AI_ORCH_BENCH_PORT=18190 python3 bench/attach_rule.py <projectKey> <file> "<statement>" [--member Owner#getPet(String)]
"""
from __future__ import annotations

import argparse
import json
import os
import urllib.request

BASE = f"http://127.0.0.1:{os.environ.get('AI_ORCH_BENCH_PORT', '18200')}/workspace/api/"


def call(method: str, path: str, body: dict | None = None) -> dict:
    req = urllib.request.Request(BASE + path, json.dumps(body).encode() if body is not None else None,
                                 {"Content-Type": "application/json", "X-Workspace-Request": "1"}, method=method)
    with urllib.request.urlopen(req, timeout=60) as r:
        return json.loads(r.read() or b"{}")


def attach(project: str, file: str, statement: str, member: str | None = None) -> str:
    if member:
        cls, sig = member.split("#", 1)
        fqn_owner = next(c["fqn"] for c in _classes(project, file) if c["name"] == cls)
        target = {"kind": "member", "path": file, "fqn": f"{fqn_owner}#{sig.split('(')[0]}", "signature": sig}
    else:
        target = {"kind": "file", "path": file}
    d = call("POST", "rules/draft", {"project": project, "scope": "node", "statement": statement, "nodeTarget": target})
    p = call("POST", "rules/draft/preview", {"draftId": d["draftId"], "project": project})
    r = call("POST", "rules/draft/promote", {"draftId": d["draftId"], "project": project,
                                             "candidateHash": d["candidateHash"],
                                             "approvalContentHash": p["approvalContentHash"],
                                             "confirmationCardHash": p["confirmationCardHash"],
                                             "workflowContractVersion": p["workflowContractVersion"],
                                             "humanRawText": "Onaylıyorum (kabul testi)"})
    return r.get("ruleId", "")


def _classes(project: str, file: str) -> list[dict]:
    root = call("GET", f"code-tree?project={project}")
    directory = file.rsplit("/", 1)[0]
    pkg = next(c for c in root["children"] if c["path"] == directory)
    return call("GET", f"code-tree?project={project}&parent={urllib.parse.quote(pkg['id'])}")["children"]


if __name__ == "__main__":
    import urllib.parse  # noqa: F401
    ap = argparse.ArgumentParser()
    ap.add_argument("project"); ap.add_argument("file"); ap.add_argument("statement")
    ap.add_argument("--member")
    a = ap.parse_args()
    print(attach(a.project, a.file, a.statement, a.member))
