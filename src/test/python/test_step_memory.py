"""Step memory (scripts/hooks/ai_orch_step_memory.py): lookups only at the first check per kind and on a new failure,
at most 4 per request (timeouts count), none after memory answered "nothing", never a card the session was already
shown, fail-open."""
from __future__ import annotations

import json
import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "scripts" / "hooks"))
import ai_orch_step_memory as step  # noqa: E402

CARD = {"id": "c1", "summary": "Boot 4 test imports", "text": "Use org.springframework.boot.webmvc.test.autoconfigure."}


@pytest.fixture
def env(tmp_path, monkeypatch):
    calls = []
    answers = {"cards": [CARD], "outcome": None}
    monkeypatch.setattr(step.session, "repo_root", lambda r: str(tmp_path))
    monkeypatch.setattr(step.session, "state_path", lambda sid, root: tmp_path / f"state-{sid}.json")
    monkeypatch.setattr(step.tempfile, "gettempdir", lambda: str(tmp_path))

    def fetch(root, query, kinds, exclude, timeout=1.0):
        calls.append((kinds, query, list(exclude)))
        if answers["outcome"]:
            return answers["outcome"], []
        # like the server: "empty" only when nothing matched, "found" even if every match was shown before
        return ("found" if answers["cards"] else "empty"), [c for c in answers["cards"] if c["id"] not in exclude]

    monkeypatch.setattr(step, "fetch", fetch)
    return calls, answers, tmp_path


def event(command, out="", error=False, sid="s1"):
    return {"tool_name": "Bash", "tool_input": {"command": command}, "cwd": "/repo", "session_id": sid,
            "tool_response": {"stdout": out, "is_error": error}}


def test_first_check_per_kind_asks_once_for_how_to_check(env):
    calls, _, _ = env
    text = step.context(event("./mvnw -q test", "Tests run: 3, Failures: 0"))
    assert "memory for checking the change" in text and "Boot 4 test imports" in text
    assert calls[0][0] == step.CHECK_KINDS
    assert step.context(event("./mvnw -q test", "Tests run: 3, Failures: 0")) == ""  # same kind of check again
    assert len(calls) == 1
    assert step.context(event("ls src")) == "" and len(calls) == 1                  # not a check: no lookup


def test_a_new_failure_asks_for_earlier_fixes_and_the_same_failure_does_not(env):
    calls, answers, _ = env
    answers["cards"] = [dict(CARD, id="c2", summary="Fix for missing package")]
    out = "[ERROR] /repo/src/A.java:[3,8] package org.springframework.boot.test.autoconfigure.web.servlet does not exist"
    text = step.context(event("./mvnw -q test", out, error=True))
    assert "memory for a failing check" in text and calls[0][0] == step.FAILURE_KINDS
    assert "/repo" not in calls[0][1] and "3,8" not in calls[0][1]                   # paths and positions stripped
    assert step.context(event("./mvnw -q test", out.replace("A.java:[3,8]", "B.java:[9,1]"), error=True)) == ""
    assert len(calls) == 1


def failures(n):
    return [event("./mvnw -q test", f"[ERROR] symbol number{i} not found here {'x' * i}", error=True) for i in range(n)]


def test_at_most_four_lookups_per_request_failed_lookups_included(env):
    calls, answers, _ = env
    answers["outcome"] = "failed"
    for e in failures(6):
        step.context(e)
    assert len(calls) == step.MAX_LOOKUPS


def test_an_empty_answer_ends_memory_lookups_for_the_request(env):
    calls, answers, tmp_path = env
    answers["cards"] = []
    for e in failures(3):
        step.context(e)
    assert len(calls) == 1
    state = json.loads((tmp_path / "state-s1.json").read_text())
    assert step.memory_gate.closed(state)
    # the agent's own search is refused too, through every route
    search = {"tool_name": "mcp__ai-orchestration__memory_search", "tool_input": {"query": "how to test"},
              "cwd": "/repo", "session_id": "s1"}
    assert "final for this task" in step.memory_gate.pre(search)


def test_matches_all_shown_before_and_failed_lookups_do_not_close_memory(env):
    calls, answers, tmp_path = env
    seen_file = step._seen_file({"session_id": "s1"}, str(tmp_path))
    seen_file.write_text(json.dumps({"cards": ["c1"], "pointers": []}))
    assert step.context(failures(1)[0]) == ""                       # the only match was shown before: nothing new
    answers["outcome"] = "failed"
    step.context(failures(2)[1])
    answers["outcome"] = None
    seen_file.write_text(json.dumps({"cards": [], "pointers": []}))
    assert "Boot 4" in step.context(failures(3)[2])                 # memory is still open
    assert len(calls) == 3


def test_an_empty_search_of_the_agent_stops_step_lookups_until_the_next_request(env):
    calls, _, tmp_path = env
    gate = step.memory_gate
    search = {"tool_name": "mcp__ai-orchestration__extras", "cwd": "/repo", "session_id": "s1",
              "tool_input": {"op": "memory.search", "args": {"query": "csv export"}},
              "tool_response": [{"type": "text", "text": json.dumps({"schemaVersion": 1, "knowledge": []})}]}
    gate.post(search)
    assert step.context(event("./mvnw -q test", "Tests run: 3, Failures: 0")) == "" and calls == []
    state_file = tmp_path / "state-s1.json"
    state = json.loads(state_file.read_text())
    gate.reset(state)                                                # the prompt hook, at the next request
    state_file.write_text(json.dumps(state))
    assert "Boot 4" in step.context(event("./mvnw -q test", "Tests run: 3, Failures: 0"))


def test_fetch_reads_the_servers_outcome(monkeypatch):
    class Answer:
        def __init__(self, body):
            self.body = body

        def __enter__(self):
            return self

        def __exit__(self, *a):
            return False

        def read(self):
            return self.body.encode()

    for body, expected in ((json.dumps({"outcome": "empty", "cards": []}), ("empty", [])),
                           (json.dumps({"outcome": "found", "cards": []}), ("found", [])),
                           (json.dumps([CARD]), ("found", [CARD])),
                           (json.dumps([]), ("failed", [])),
                           ("<html>", ("failed", []))):
        monkeypatch.setattr(step.urllib.request, "urlopen", lambda *a, b=body, **k: Answer(b))
        assert step.fetch("/repo", "q", step.CHECK_KINDS, []) == expected


def test_cards_already_shown_are_excluded_and_recorded_for_the_prompt_hook(env):
    calls, answers, tmp_path = env
    seen_file = step._seen_file({"session_id": "s1"}, str(tmp_path))
    seen_file.write_text(json.dumps({"cards": ["c1"], "pointers": []}))
    answers["cards"] = [CARD, dict(CARD, id="p1", summary="maybe", pointer=True)]
    text = step.context(event("./mvnw -q test", "Tests run: 3, Failures: 0"))
    assert "c1" in calls[0][2] and "Boot 4" not in text and "Possibly relevant memory (not loaded): maybe" in text
    assert json.loads(seen_file.read_text())["pointers"] == ["p1"]


def test_fetch_fails_open(monkeypatch):
    monkeypatch.setenv("AI_ORCH_URL", "http://127.0.0.1:9")
    assert step.fetch("/repo", "q", step.CHECK_KINDS, []) == ("failed", [])


def test_a_card_first_shown_as_a_pointer_can_come_in_full_but_a_pointer_does_not_repeat(env):
    calls, answers, tmp_path = env
    seen_file = step._seen_file({"session_id": "s1"}, str(tmp_path))
    seen_file.write_text(json.dumps({"cards": [], "pointers": ["c1", "p9"]}))
    answers["cards"] = [CARD, {"id": "p9", "summary": "old pointer", "pointer": True}]
    text = step.context(event("./mvnw -q test", "Tests run: 3, Failures: 0"))
    assert "c1" not in calls[0][2]                                   # pointers are not excluded from the lookup
    assert "Memory card: Boot 4 test imports" in text and "old pointer" not in text
    assert json.loads(seen_file.read_text())["cards"] == ["c1"]


def test_cards_per_request_stay_within_the_character_budget(env, monkeypatch):
    calls, answers, _ = env
    monkeypatch.setattr(step, "MAX_CHARS", 400)
    answers["cards"] = [dict(CARD, id=f"c{i}", text="x" * 150) for i in range(5)]
    first = step.context(event("./mvnw -q test", "Tests run: 3, Failures: 0"))
    second = step.context(event("./mvnw -q compile", "BUILD SUCCESS"))
    shown = first.count("Memory card") + second.count("Memory card")
    assert len(first) + len(second) <= 400                          # the headers count too
    assert 1 <= shown <= 2


def test_the_header_counts_against_a_small_budget():
    text, shown = step.block([dict(CARD, text="t" * 40)], "checking the change", 100)
    assert len(text) <= 100
    text, shown = step.block([dict(CARD, text="t" * 80)], "checking the change", 100)
    assert (text, shown) == ("", [])


def test_a_slow_lookup_past_the_deadline_shows_nothing_and_logs_nothing_as_shown(env, monkeypatch, tmp_path):
    calls, answers, _ = env
    clock = iter([0.0, 5.0, 5.0, 5.0])
    monkeypatch.setattr(step.time, "monotonic", lambda: next(clock))
    assert step.context(event("./mvnw -q test", "Tests run: 3, Failures: 0")) == ""
    log = json.loads((tmp_path / "state-s1.json").read_text())["request"]["step_log"]
    assert log == [{"moment": "checking the change", "outcome": "found", "cards": [], "pointers": [], "late": True}]
    seen_file = step._seen_file({"session_id": "s1"}, str(tmp_path))
    assert not seen_file.exists() or "c1" not in seen_file.read_text()


def test_a_step_query_and_an_explicit_search_never_repeat_each_other(env):
    calls, _, tmp_path = env
    gate = step.memory_gate
    query = step.VERIFY_QUERY + ": ./mvnw -q test"
    # a successful step lookup: the same query through the agent's own search is refused locally
    step.context(event("./mvnw -q test", "Tests run: 3, Failures: 0"))
    assert len(calls) == 1 and calls[0][1] == query
    direct = {"tool_name": "mcp__ai-orchestration__memory_search", "tool_input": {"query": query.upper()},
              "cwd": "/repo", "session_id": "s1"}
    assert "already searched memory" in gate.pre(direct)
    # an explicit successful search: the step hook does not ask the same query again
    found = [{"type": "text", "text": json.dumps({"knowledge": [{"id": "m"}]})}]
    gate.post({"tool_name": "mcp__ai-orchestration__memory_search", "cwd": "/repo", "session_id": "s2",
               "tool_input": {"query": query}, "tool_response": found})
    assert step.context(event("./mvnw -q test", "Tests run: 3, Failures: 0", sid="s2")) == ""
    assert len(calls) == 1
    # a failed step lookup does not count as asked
    env[1]["outcome"] = "failed"
    step.context(event("./mvnw -q compile", "BUILD SUCCESS", sid="s3"))
    state = json.loads((tmp_path / "state-s3.json").read_text())
    assert gate.refusal(state, step.VERIFY_QUERY + ": ./mvnw -q compile") is None


def test_a_missing_test_tool_is_a_failure_even_with_exit_code_0_and_asks_for_test_addresses(env):
    calls, answers, _ = env
    answers["cards"] = [dict(CARD, id="t1", summary="No pytest here; run python -m unittest tests")]
    out = ("/opt/u/bench/.work/venvs/microblog/bin/python: No module named pytest\n"
           "<shellId: 3 completed with exit code 0>")
    text = step.context(event("python -m pytest -q", out))
    assert "memory for a failing check" in text and "No pytest here" in text
    kinds, query, _ = calls[0]
    assert "test_address" in kinds.split(",") and "No module named pytest" in query and "/opt/u" not in query
    assert step.context(event("python -m pytest -q", out.replace("/opt/u", "/home/v"))) == ""  # same failure
    assert len(calls) == 1


def test_a_test_script_run_directly_is_a_check_but_not_proof_of_the_whole_suite():
    for command in ("python tests.py", "python3 tests.py -v", "cd /x && python test_api.py", "python backend/tests.py"):
        assert step.session.is_verify(command), command
        assert "tests" not in step.session.coverage(command), command  # a file name is no proof of full coverage
    assert not step.session.is_verify("python app.py")
