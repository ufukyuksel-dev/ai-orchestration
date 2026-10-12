"""Work-trail capture (scripts/hooks/ai_orch_trail.py): request-attributed edits (tree snapshot at the prompt vs the
tree at Stop), receipts of the checks that really ran (J2 repairs, the Stop hook's own checks), learned fact ids,
cited symbols for questions, Claude and Codex transcripts, fail-open posting."""
from __future__ import annotations

import base64
import hashlib
import json
import subprocess
import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "scripts" / "hooks"))
import ai_orch_command_hook as command_hook  # noqa: E402
import ai_orch_session_state as session  # noqa: E402
import ai_orch_stop as stop  # noqa: E402
import ai_orch_trail as trail  # noqa: E402

MODELS = """class User:
    def follow(self, user):
        self.following.add(user)

    def followers_count(self):
        return count(self.following)

    def following_count(self):
        return count(self.following)
"""


def git(root: Path, *args: str) -> None:
    subprocess.run(["git", "-C", str(root), "-c", "user.email=t@t", "-c", "user.name=t", "-c", "commit.gpgsign=false",
                    *args], check=True, capture_output=True)


@pytest.fixture
def repo(tmp_path: Path) -> Path:
    root = tmp_path / "repo"
    (root / "app").mkdir(parents=True)
    (root / "app" / "models.py").write_text(MODELS)
    (root / "notes.py").write_text("def note():\n    return 1\n")
    git(root, "init", "-q")
    git(root, "add", "-A")
    git(root, "commit", "-qm", "init")
    return root


def edit(repo: Path, old: str, new: str, path: str = "app/models.py") -> None:
    p = repo / path
    p.write_text(p.read_text().replace(old, new))


def claude_transcript(path: Path, prompts: list[str], tools: list[tuple[dict, dict]] = (), answer: str = "") -> str:
    lines = [{"type": "user", "message": {"role": "user", "content": p}} for p in prompts]
    for i, (use, result) in enumerate(tools):
        lines.append({"type": "assistant", "message": {"content": [dict(use, type="tool_use", id=f"t{i}")]}})
        lines.append({"type": "user", "message": {"content": [dict(result, type="tool_result", tool_use_id=f"t{i}")]}})
    if answer:
        lines.append({"type": "assistant", "message": {"content": [{"type": "text", "text": answer}]}})
    path.write_text("\n".join(json.dumps(x) for x in lines) + "\n")
    return str(path)


# ------------------------------------------------------------------ request-attributed edits
def test_only_this_requests_edits_are_attributed(repo, tmp_path):
    edit(repo, "return 1", "return 2", "notes.py")                      # the user's own dirty change, before
    (repo / "scratch.py").write_text("def scratch():\n    pass\n")      # and an untracked file, before
    state: dict = {}
    session.begin_request(state, str(repo))                              # the prompt: snapshot
    edit(repo, "    def followers_count(self):\n        return count(self.following)",
         "    def followers_count(self):\n        return count(self.followers)")
    tr = claude_transcript(tmp_path / "c.jsonl", ["Follower count is wrong"])
    got = trail.build(tr, [], str(repo), "sess", state["request"])
    end_tree = session.snapshot_tree(str(repo))
    body = "    def followers_count(self):\n        return count(self.followers)"
    assert got["edited"] == [{"path": "app/models.py", "symbol": "followers_count", "kind": "def", "text": body,
                              "line": 5, "tree": end_tree, "hash": hashlib.sha1(body.encode()).hexdigest()[:16]}]
    assert got["trailKey"] == "sess:1" and got["codeFingerprint"] == end_tree


def test_committed_edits_count_and_no_snapshot_means_no_edits(repo, tmp_path):
    state: dict = {}
    session.begin_request(state, str(repo))
    edit(repo, "self.following.add(user)", "self.following.add(user)  # once")
    git(repo, "commit", "-qam", "agent commit")                          # the agent committed its edit
    tr = claude_transcript(tmp_path / "c.jsonl", ["Tidy follow"])
    assert [{k: e[k] for k in ("path", "symbol", "kind")}
            for e in trail.build(tr, [], str(repo), "sess", state["request"])["edited"]] == [
        {"path": "app/models.py", "symbol": "follow", "kind": "def"}]
    assert trail.build(tr, [], str(repo), "sess", {"ordinal": 1})["edited"] == []  # attribution unknown


def test_changed_lines_reference_unique_definitions_only(repo, tmp_path):
    (repo / "app" / "auth.py").write_text("def verify_token(token):\n    return token\n\n\ndef shared_name():\n    pass\n")
    (repo / "notes.py").write_text("def note():\n    return 1\n\n\ndef shared_name():\n    pass\n")
    git(repo, "add", "-A")
    git(repo, "commit", "-qm", "helpers")
    state: dict = {}
    session.begin_request(state, str(repo))
    edit(repo, "        return count(self.following)\n\n    def following_count",
         "        verify_token(self)  # see note()\n        shared_name()\n        log('following_count')\n"
         "        return count(self.followers)\n\n    def following_count")
    tr = claude_transcript(tmp_path / "c.jsonl", ["Check the token first"])
    got = trail.build(tr, [], str(repo), "sess", state["request"])
    refs = [r for r in got["supportingReads"] if r["kind"] == "ref"]
    # verify_token: one definition; note (comment) and following_count (string): not used; shared_name: ambiguous
    assert refs == [{"path": "app/auth.py", "symbol": "verify_token", "kind": "ref"}]


def test_reads_in_any_file_with_ranges_most_read_first_and_failed_reads_ignored(repo, tmp_path):
    (repo / "app" / "auth.py").write_text("def verify_token(token):\n    return token\n\n\ndef issue(user):\n    pass\n")
    git(repo, "add", "-A")
    git(repo, "commit", "-qm", "auth")
    state: dict = {}
    session.begin_request(state, str(repo))
    edit(repo, "return count(self.following)\n\n    def following_count", "return count(self.followers)\n\n    def following_count")
    tr = claude_transcript(tmp_path / "c.jsonl", ["Fix the follower count"])
    steps = [{"name": "Read", "input": {"file_path": str(repo / "notes.py"), "offset": 1, "limit": 2}, "is_error": False},
             {"name": "Bash", "input": {"command": "sed -n 1,6p app/auth.py"}, "is_error": False},
             {"name": "Read", "input": {"file_path": str(repo / "app/auth.py"), "offset": 5, "limit": 2}, "is_error": False},
             {"name": "Read", "input": {"file_path": str(repo / "app/auth.py"), "offset": 1}, "is_error": True}]
    reads = [r for r in trail.build(tr, steps, str(repo), "sess", state["request"])["supportingReads"]
             if r["kind"] == "read"]
    assert reads[0] == {"path": "app/auth.py", "symbol": "issue", "kind": "read", "range": "1-6"}  # read twice
    assert {"path": "notes.py", "symbol": "note", "kind": "read", "range": "1-2"} in reads
    assert {"path": "app/auth.py", "symbol": "verify_token", "kind": "read", "range": "1-6"} in reads


def test_a_completed_read_without_offset_counts_from_the_first_line(repo, tmp_path):
    (repo / "dep.py").write_text("def helper():\n    return 1\n")
    git(repo, "add", "-A")
    git(repo, "commit", "-qm", "dep")
    state: dict = {}
    session.begin_request(state, str(repo))
    edit(repo, "return count(self.following)\n\n    def following_count", "return count(self.followers)\n\n    def following_count")
    tr = claude_transcript(tmp_path / "c.jsonl", ["Fix the follower count"])
    done = [{"name": "Read", "input": {"file_path": str(repo / "dep.py")}, "is_error": False}]
    reads = trail.build(tr, done, str(repo), "sess", state["request"])["supportingReads"]
    assert {"path": "dep.py", "symbol": "helper", "kind": "read", "range": "1-2000"} in reads
    failed = [dict(done[0], is_error=True)]
    assert not [r for r in trail.build(tr, failed, str(repo), "sess", state["request"])["supportingReads"]
                if r["kind"] == "read"]


def test_a_read_without_a_result_is_not_a_read(repo, tmp_path):
    (repo / "dep.py").write_text("def helper():\n    return 1\n")
    git(repo, "add", "-A")
    git(repo, "commit", "-qm", "dep")
    state: dict = {}
    session.begin_request(state, str(repo))
    tr = tmp_path / "c.jsonl"
    tr.write_text("\n".join(json.dumps(x) for x in [
        {"type": "user", "message": {"role": "user", "content": "Look at the helper"}},
        {"type": "assistant", "message": {"content": [{"type": "tool_use", "id": "r1", "name": "Read",
                                                       "input": {"file_path": str(repo / "dep.py")}}]}}]) + "\n")
    steps = stop.steps_since_last_prompt(str(tr))
    assert steps[0]["done"] is False
    assert not trail.supporting_locators(steps, str(repo), [])
    steps[0]["done"] = True
    assert trail.supporting_locators(steps, str(repo), [])[0]["symbol"] == "helper"


def test_captured_text_is_bounded(repo, tmp_path):
    long_body = "    def followers_count(self):\n" + "".join(f"        x{i} = {i}\n" for i in range(40)) + \
        "        return count(self.followers)\n"
    state: dict = {}
    session.begin_request(state, str(repo))
    edit(repo, "    def followers_count(self):\n        return count(self.following)\n", long_body)
    tr = claude_transcript(tmp_path / "c.jsonl", ["Fix the follower count"])
    edited = trail.build(tr, [], str(repo), "sess", state["request"])["edited"]
    assert len(edited[0]["text"].splitlines()) == trail.MAX_TEXT_LINES and edited[0]["line"] == 5


def test_a_later_read_only_request_gets_no_edits_and_cites_its_answer(repo, tmp_path):
    state: dict = {}
    session.begin_request(state, str(repo))
    edit(repo, "return count(self.following)\n\n    def following_count", "return count(self.followers)\n\n    def following_count")
    session.begin_request(state, str(repo))                              # second prompt, nothing changed after it
    tr = claude_transcript(tmp_path / "q.jsonl", ["Fix it", "Where is the following count computed?"],
                           answer="In `User.following_count` (`app/models.py:8`); `nothing_here` does not exist.")
    got = trail.build(tr, [], str(repo), "sess", state["request"])
    assert got["trailKey"] == "sess:2" and got["edited"] == []
    assert {"path": "app/models.py", "symbol": "following_count", "kind": "cited"} in got["cited"]
    assert {"path": "app/models.py", "symbol": "", "kind": "file"} in got["cited"]
    assert not any(c["symbol"] == "nothing_here" for c in got["cited"])


# ------------------------------------------------------------------ receipts
def b64(text: str) -> str:
    return base64.urlsafe_b64encode(text.encode()).decode()


def test_receipt_records_executed_and_requested_command(repo, tmp_path):
    state_file = tmp_path / "state.json"
    session.save(state_file, {"request": {"ordinal": 1, "tree": None, "receipts": [], "facts": []}})
    command_hook.record([str(state_file), str(repo), "0", "0", "", "compile,tests", "1",
                         b64("python -m pytest -x tests.py"), b64("python -m unittest -f tests"), b64(str(repo))])
    receipt = session.load(state_file)["request"]["receipts"][0]
    assert receipt["command"] == "python -m unittest -f tests" and receipt["requested"] == "python -m pytest -x tests.py"
    assert (receipt["status"], receipt["testEvidence"], receipt["scope"]) == ("passed", "ran", "compile,tests")
    command_hook.record([str(state_file), str(repo), "1", "2", "", "compile,tests", "1", b64("pytest"), b64("pytest"),
                         b64(str(repo))])
    failed = session.load(state_file)["request"]["receipts"][1]
    assert (failed["status"], failed["scope"], failed["testEvidence"]) == ("failed", "", "failed")
    assert receipt["cwd"] == str(repo) and receipt["tree"]  # where it ran and the content tree it checked


def test_without_compaction_a_passing_run_without_test_reports_proves_no_tests(repo, tmp_path):
    state_file = tmp_path / "state.json"
    session.save(state_file, {"request": {"ordinal": 1, "tree": None, "receipts": [], "facts": []}})
    command_hook.record([str(state_file), str(repo), "0", "0", "", "compile,tests", "0",
                         b64("python -m unittest"), b64("python -m unittest"), b64(str(repo))])
    state = session.load(state_file)
    receipt = state["request"]["receipts"][0]
    assert (receipt["status"], receipt["scope"], receipt["testEvidence"]) == ("passed", "compile", "unknown")
    assert "tests" not in state.get("verified", {})


def test_trail_checks_come_from_receipts_and_transcript_only_checks_are_unknown(repo, tmp_path):
    request = {"ordinal": 1, "tree": None, "facts": [], "receipts": [
        {"command": "python -m unittest -f tests", "requested": "python -m pytest -x tests.py", "status": "passed",
         "scope": "compile,tests", "testEvidence": "ran", "cwd": "/repo/sub", "tree": "t123"}]}
    steps = [{"name": "Bash", "input": {"command": "python -m pytest -x tests.py"}, "output": "", "is_error": False},
             {"name": "Bash", "input": {"command": "./gradlew test"}, "output": "BUILD SUCCESSFUL", "is_error": False}]
    tr = claude_transcript(tmp_path / "c.jsonl", ["x"])
    checks = trail.build(tr, steps, str(repo), "s", request)["checks"]
    assert checks == [{"command": "python -m unittest -f tests", "scope": "compile,tests", "status": "passed",
                       "testEvidence": "ran", "requested": "python -m pytest -x tests.py", "cwd": "/repo/sub",
                       "tree": "t123"},
                      {"command": "./gradlew test", "scope": "", "status": "unknown", "testEvidence": "unknown"}]


def test_stop_only_verification_and_partial_module_evidence(tmp_path, monkeypatch):
    sys.path.insert(0, str(ROOT / "src" / "test" / "python"))
    from test_gradle_profile import kmp_project, report
    import ai_orch_profile
    root = kmp_project(tmp_path)
    monkeypatch.setattr(session, "changed_files", lambda r: ["shared/core/engine/src/A.kt", "backend/src/B.kt"])

    def fake_run(cmd, r, deadline):
        report(root, "shared/core/engine", 3)                # only the engine module produced test reports
        return 0, "BUILD SUCCESSFUL"
    monkeypatch.setattr(stop, "run", fake_run)
    receipts: list = []
    failure, proved = stop.final_check(str(root), ai_orch_profile.detect(root), {"tests"}, set(), receipts)
    assert failure is None and proved == {"compile", "tests::shared:core:engine"}
    assert receipts == [{"command": "./gradlew :backend:test :shared:core:engine:testAndroidHostTest -q",
                         "status": "passed", "scope": "compile,tests::shared:core:engine",
                         "testEvidence": "ran", "cwd": str(root), "tree": None, "by": "stop-hook"}]


# ------------------------------------------------------------------ facts and request isolation
def learn_answer(items: list[dict], status: str = "ok") -> dict:
    payload = {"status": status, "created": len(items), "items": items}
    return {"jsonrpc": "2.0", "id": 2, "result": {"content": [{"type": "text", "text": json.dumps(payload)}]}}


def test_learned_ids_only_from_successful_responses():
    ok = learn_answer([{"summary": "a", "status": "created", "memoryId": "m1"},
                       {"summary": "b", "status": "rejected", "memoryId": "m2"},
                       {"summary": "c", "status": "duplicate_not_saved", "memoryId": "m3"}])
    assert trail.learned_ids(ok) == ["m1", "m3"]
    assert trail.learned_ids(learn_answer([{"status": "created", "memoryId": "m4"}], status="rejected")) == []
    assert trail.learned_ids({"result": {"isError": True, "content": [{"type": "text", "text": "boom"}]}}) == []
    assert trail.learned_ids(None) == [] and trail.learned_ids("not json") == []
    assert trail.learned_ids({"status": "ok", "created": 1, "items": [], "capture": {}}) == []  # anchored: no ids
    steps = [{"name": "mcp__ai-orchestration__memory_learn", "is_error": False,
              "output": json.dumps({"status": "ok", "items": [{"status": "created", "memoryId": "m9"}]})}]
    assert trail.agent_learned_ids(steps) == ["m9"]


def test_facts_and_receipts_do_not_leak_into_the_next_request(repo, tmp_path):
    state: dict = {}
    session.begin_request(state, str(repo))
    state["request"]["facts"].append("m1")
    session.add_receipt(state, {"command": "python -m unittest", "status": "passed", "scope": "tests",
                                "testEvidence": "ran"})
    tr = claude_transcript(tmp_path / "c.jsonl", ["first"])
    assert trail.build(tr, [], str(repo), "s", state["request"])["factIds"] == ["m1"]
    session.begin_request(state, str(repo))
    second = trail.build(claude_transcript(tmp_path / "c2.jsonl", ["first", "second"]), [], str(repo), "s",
                         state["request"])
    assert second["trailKey"] == "s:2" and second["factIds"] == [] and second["checks"] == []


# ------------------------------------------------------------------ clients and transport
def test_codex_rollout(repo, tmp_path):
    rollout = tmp_path / "rollout.jsonl"
    events = [
        {"type": "event_msg", "payload": {"type": "item_completed", "item": {
            "type": "UserMessage", "content": [{"type": "text", "text": "Fix the count"}]}}},
        {"type": "event_msg", "payload": {"type": "item_completed", "item": {
            "type": "CommandExecution", "command": ["/bin/zsh", "-lc", "python -m unittest"], "exit_code": 0,
            "aggregated_output": "Ran 2 tests\n\nOK"}}},
        {"type": "event_msg", "payload": {"type": "item_completed", "item": {
            "type": "AgentMessage", "content": [{"type": "Text", "text": "Done"}]}}}]
    rollout.write_text("\n".join(json.dumps(e) for e in events) + "\n")
    got = trail.build(str(rollout), stop.steps_since_last_prompt(str(rollout)), str(repo), "cx", {"ordinal": 1})
    assert got["trailKey"] == "cx:1" and got["requestText"] == "Fix the count"
    assert got["checks"] == [{"command": "python -m unittest", "scope": "", "status": "unknown",
                              "testEvidence": "unknown"}]  # no receipt: execution not established by the hook


def test_no_request_no_trail_and_post_fails_open(repo, tmp_path, monkeypatch):
    empty = tmp_path / "e.jsonl"
    empty.write_text("")
    assert trail.build(str(empty), [], str(repo), "s") is None
    monkeypatch.setenv("AI_ORCH_URL", "http://127.0.0.1:9")
    assert trail.post({"cwd": str(repo)}, "claude-code", timeout=0.5) is False
