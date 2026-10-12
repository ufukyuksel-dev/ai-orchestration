"""Memory searches within one request (scripts/hooks/ai_orch_memory_gate.py): after a search that found nothing, no
route searches memory again until the next request; the same query is never asked twice; failures close nothing."""
from __future__ import annotations

import io
import json
import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[2].parent
sys.path.insert(0, str(ROOT / "scripts" / "hooks"))
import ai_orch_command_hook as command_hook  # noqa: E402
import ai_orch_memory_gate as gate  # noqa: E402
import ai_orch_search_hook as search_hook  # noqa: E402


@pytest.fixture(autouse=True)
def state_dir(tmp_path, monkeypatch):
    monkeypatch.setattr(gate.session, "repo_root", lambda cwd: "/repo")
    monkeypatch.setattr(gate.session, "state_path", lambda sid, root: tmp_path / f"state-{sid}.json")
    return tmp_path


def text_blocks(payload) -> list[dict]:
    return [{"type": "text", "text": json.dumps(payload)}]


def direct(query, response=None, sid="s1"):
    return {"tool_name": "mcp__ai-orchestration__memory_search", "tool_input": {"query": query}, "cwd": "/repo",
            "session_id": sid, "tool_response": response}


def extras(query, response=None, sid="s1", as_string=False):
    args = {"query": query, "rootPath": "/repo"}
    return {"tool_name": "mcp__ai-orchestration__extras", "cwd": "/repo", "session_id": sid,
            "tool_input": {"op": "memory.search", "args": json.dumps(args) if as_string else args},
            "tool_response": response}


def shell(query, stdout="", error=False, sid="s1"):
    return {"tool_name": "Bash", "cwd": "/repo", "session_id": sid,
            "tool_input": {"command": f"ai_orch memory search --query \"{query}\" --top-k 3"},
            "tool_response": {"stdout": stdout, "stderr": "", "is_error": error}}


EMPTY = text_blocks({"schemaVersion": 1, "projectKey": "P", "knowledge": [], "hint": "Memory has nothing on this."})
FOUND = text_blocks({"schemaVersion": 1, "projectKey": "P", "knowledge": [{"id": "m1", "text": "fact"}]})


def test_outcomes_of_the_real_answer_shapes():
    assert gate.outcome(direct("q", EMPTY)) == "empty"
    assert gate.outcome(direct("q", FOUND)) == "found"
    assert gate.outcome(direct("q", {"content": EMPTY, "isError": False})) == "empty"
    assert gate.outcome(direct("q", {"content": FOUND, "isError": True})) == "failed"
    assert gate.outcome(direct("q", json.dumps({"items": [], "projectKey": "P"}))) == "empty"   # legacy view
    assert gate.outcome(direct("q", "Error: connection refused")) == "failed"
    assert gate.outcome(direct("q", None)) == "failed"
    envelope = json.dumps({"success": True, "command": "memory.search", "result": {"knowledge": []}})
    assert gate.outcome(shell("q", envelope)) == "empty"
    assert gate.outcome(shell("q", "", error=True)) == "failed"


def test_only_memory_searches_are_gated():
    assert gate.search_query(direct("csv")) == "csv"
    assert gate.search_query(extras("csv", as_string=True)) == "csv"
    assert gate.search_query(shell("csv")) == "csv"
    get = {"tool_name": "mcp__ai-orchestration__extras", "tool_input": {"op": "memory.get", "args": {"memoryId": "x"}}}
    assert gate.search_query(get) is None
    assert gate.search_query({"tool_name": "Bash", "tool_input": {"command": "grep -r memory search ."}}) is None


def test_an_empty_search_closes_every_route_until_the_next_request(state_dir):
    assert gate.pre(direct("csv export")) is None
    gate.post(direct("csv export", EMPTY))
    for call in (direct("vets controller"), extras("anything else"), shell("one more")):
        assert "final for this task" in gate.pre(call) and "csv export" in gate.pre(call)
    state = gate.session.load(state_dir / "state-s1.json")
    gate.reset(state)
    gate.session.save(state_dir / "state-s1.json", state)
    assert gate.pre(direct("vets controller")) is None


def test_a_found_query_is_not_asked_again_but_other_queries_are_allowed():
    gate.post(extras("Owner Validation", FOUND))
    assert "already searched memory for 'owner   validation'" in gate.pre(direct("owner   validation"))
    assert gate.pre(direct("pet type formatter")) is None


def test_a_failed_search_closes_nothing_and_may_be_retried():
    gate.post(direct("csv export", "Error: timeout"))
    gate.post(shell("csv export", "", error=True))
    assert gate.pre(direct("csv export")) is None


def test_sessions_are_independent():
    gate.post(direct("csv export", EMPTY, sid="a"))
    assert gate.pre(direct("csv export", sid="a")) is not None
    assert gate.pre(direct("csv export", sid="b")) is None


def test_hook_entry_points(monkeypatch, capsys):
    gate.post(direct("csv export", EMPTY))
    monkeypatch.setattr(sys, "stdin", io.StringIO(json.dumps(direct("other"))))
    gate.main(["pre"])
    decision = json.loads(capsys.readouterr().out)["hookSpecificOutput"]
    assert decision["permissionDecision"] == "deny" and "final for this task" in decision["permissionDecisionReason"]
    # the Bash route goes through the existing command hook (PreToolUse Bash): no second Bash hook
    monkeypatch.setattr(sys, "stdin", io.StringIO(json.dumps(shell("other"))))
    command_hook.main([])
    assert json.loads(capsys.readouterr().out)["hookSpecificOutput"]["permissionDecision"] == "deny"


def test_the_search_hook_records_a_shell_search(monkeypatch, capsys):
    monkeypatch.setenv("AI_ORCH_LEVERS", "none")
    envelope = json.dumps({"success": True, "command": "memory.search", "result": {"knowledge": []}})
    monkeypatch.setattr(sys, "stdin", io.StringIO(json.dumps(shell("csv export", envelope))))
    search_hook.main()
    assert "final for this task" in gate.pre(direct("anything"))


def test_only_a_command_that_runs_the_search_counts_not_text_that_mentions_it():
    for text in ('echo ai_orch memory search --query foo', 'printf "ai_orch memory search --query foo"',
                 '# ai_orch memory search --query foo', 'grep -n "ai_orch memory search" README.md',
                 'cat <<EOF\nai_orch memory search --query foo\nEOF', 'echo "ai_orch memory search --query foo; x"',
                 'echo ";" ai_orch memory search --query foo', "printf '&&' ai_orch memory search --query foo",
                 'echo "a|b" ai_orch memory search --query foo', "echo '(' ai_orch memory search --query foo"):
        assert gate.cli_query(text) is None, text
    assert gate.cli_query('ai_orch memory search --query "csv export" --top-k 3') == "csv export"
    assert gate.cli_query('cd /repo && ai_orch memory search --query=csv') == "csv"
    assert gate.cli_query('AI_ORCH_X=1 /home/u/.local/bin/ai_orch memory search --query csv') == "csv"
    assert gate.cli_query('ls; ai_orch memory search --query csv | head') == "csv"
    assert gate.cli_query("ai_orch memory search --query 'a; b && c'") == "a; b && c"
    assert gate.cli_query('ai_orch memory search --query="csv export"') == "csv export"


def test_text_that_mentions_the_command_is_never_denied_and_never_recorded():
    def echo(sid):
        return {"tool_name": "Bash", "cwd": "/repo", "session_id": sid,
                "tool_input": {"command": "echo ai_orch memory search --query foo"},
                "tool_response": {"stdout": json.dumps({"result": {"knowledge": []}}), "is_error": False}}

    gate.post(direct("csv export", EMPTY))
    assert gate.pre(echo("s1")) is None                    # memory is closed, but an echo is not a search
    gate.post(echo("s2"))                                  # its printed "empty" JSON is not a search answer
    assert gate.pre(direct("csv export", sid="s2")) is None
