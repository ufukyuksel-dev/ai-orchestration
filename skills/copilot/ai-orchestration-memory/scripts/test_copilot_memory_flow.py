#!/usr/bin/env python3
"""Copilot memory flow (copilot_memory_gate.py): the prompt hook delivers the prompt's memory cards once per session
and starts a new request, with the learning line; the stop hook saves the learning the final answer carries in its
hidden comment, and asks once (one short reply) only when the agent fixed its own failure and wrote none."""

from __future__ import annotations

import io
import json
import os
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import copilot_memory_gate as gate

CARD = {"id": "c1", "summary": "Token auth for API routes", "text": "Use token_auth.login_required.",
        "files": ["app/api/auth.py"]}


def block(answer: dict) -> str:
    """The memory cards of a prompt answer, without the learning line every prompt carries."""
    return answer.get("additionalContext", "").replace(gate.LEARN_LINE, "").strip()


def stop_event(stamp: int) -> dict:
    """Copilot's transcript record of an agentStop hook call (`hook.start` with the hook's input)."""
    return {"type": "hook.start", "data": {"hookType": "agentStop", "input": {"timestamp": stamp}}}


class FlowTestCase(unittest.TestCase):
    def setUp(self) -> None:
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.tmp = Path(tmp.name)
        self.repo = self.tmp / "repo"
        self.repo.mkdir()
        subprocess.run(["git", "init", "-q", str(self.repo)], check=True)
        (self.repo / "a.py").write_text("x = 1\n")
        subprocess.run(["git", "-C", str(self.repo), "add", "."], check=True)
        subprocess.run(["git", "-C", str(self.repo), "-c", "user.email=a@b", "-c", "user.name=x", "commit", "-qm",
                        "init"], check=True)
        for patcher in (patch.object(gate.tempfile, "gettempdir", return_value=str(self.tmp)),
                        patch.dict(os.environ, {"COPILOT_AGENT_SESSION_ID": "s1"})):
            patcher.start()
            self.addCleanup(patcher.stop)

    def prompt(self, text: str = "add a posts endpoint", cards: list | None = None) -> dict:
        result = {"memory": [CARD] if cards is None else cards}
        with patch.object(gate, "fetch_bootstrap", return_value=result) as fetch:
            answer = gate.prompt_hook({"sessionId": "s1", "cwd": str(self.repo), "prompt": text})
        self.fetch = fetch
        return answer

    def stop(self, active: bool = False, answer: str = "Done.") -> dict:
        transcript = self.tmp / "events.jsonl"
        with transcript.open("a") as stream:
            self.stamp = getattr(self, "stamp", 1000) + 1
            for event in ({"type": "assistant.message", "data": {"content": answer}}, {"type": "assistant.turn_end"},
                          stop_event(self.stamp)):
                stream.write(json.dumps(event) + "\n")
        return gate.stop_hook({"sessionId": "s1", "cwd": str(self.repo), "stop_hook_active": active,
                               "transcriptPath": str(transcript), "timestamp": self.stamp})


class PromptHookTest(FlowTestCase):
    def test_the_prompts_cards_reach_the_model_once_per_session(self) -> None:
        answer = self.prompt()
        self.assertIn("AI Orchestration, memory for this request:", answer["additionalContext"])
        self.assertIn("Token auth for API routes. Use token_auth.login_required. Files: app/api/auth.py.",
                      answer["additionalContext"])
        root, prompt, timeout, _ = self.fetch.call_args.args
        self.assertEqual((root, prompt), (str(self.repo.resolve()), "add a posts endpoint"))
        self.assertTrue(0 < timeout <= gate.DEADLINE)
        self.assertEqual(block(self.prompt()), "")  # shown before in this session: not repeated

    def test_no_cards_or_no_server_adds_nothing_and_a_new_request_still_starts(self) -> None:
        gate.record("csv", {"knowledge": []})
        self.assertEqual(block(self.prompt(cards=[])), "")
        self.assertIsNone(gate.refusal("csv"))  # the search record was reset for the new request

    def test_the_block_stays_within_its_budget(self) -> None:
        cards = [dict(CARD, id=f"c{i}", text="t" * 900) for i in range(6)]
        text = block(self.prompt(cards=cards))
        self.assertLessEqual(len(text), gate.CARD_CHARS)
        self.assertGreaterEqual(text.count("- Memory card"), 2)

    def test_a_card_first_shown_as_a_pointer_can_come_in_full_but_a_pointer_does_not_repeat(self) -> None:
        pointer = {"id": "p1", "summary": "Pagination helper", "pointer": True}
        self.assertIn("Possibly relevant memory (not loaded): Pagination helper. If it fits, read it with "
                      "`ai_orch memory search --query \"Pagination helper\"`.",
                      self.prompt(cards=[pointer])["additionalContext"])
        self.assertEqual(block(self.prompt(cards=[pointer])), "")            # the same pointer: not again
        full = {"id": "p1", "summary": "Pagination helper", "text": "Use to_collection_dict."}
        self.assertIn("Use to_collection_dict.", self.prompt(cards=[full])["additionalContext"])
        self.assertEqual(block(self.prompt(cards=[full, dict(pointer)])), "")  # in full once: never again

    def test_a_late_answer_is_dropped_and_not_recorded_as_shown(self) -> None:
        now = [0.0]

        def late_fetch(*args):  # the answer comes back after the deadline
            now[0] = gate.DEADLINE + 0.5
            return {"memory": [CARD]}

        with patch.object(gate.time, "monotonic", side_effect=lambda: now[0]), \
                patch.object(gate, "fetch_bootstrap", side_effect=late_fetch):
            self.assertEqual(block(gate.prompt_hook({"sessionId": "s1", "cwd": str(self.repo), "prompt": "x"})), "")
        self.assertIn("Token auth", self.prompt()["additionalContext"])     # still unseen, so it comes next time
        self.assertEqual(gate.fetch_cards(str(self.repo), "x", 0), [])       # no time left: no server call

    def test_the_whole_prompt_hook_stays_within_the_deadline_even_with_a_slow_server(self) -> None:
        import time as clock

        def slow(*args):  # e.g. initialize and bootstrap, each just under a per-request timeout of 0.4 s
            clock.sleep(0.7)
            return {"memory": [CARD]}

        with patch.object(gate, "DEADLINE", 0.4), patch.object(gate, "_bootstrap", side_effect=slow):
            started = clock.monotonic()
            answer = gate.prompt_hook({"sessionId": "s1", "cwd": str(self.repo), "prompt": "x"})
            elapsed = clock.monotonic() - started
        self.assertLess(elapsed, 0.4 + 0.15)
        self.assertEqual(answer, {"additionalContext": gate.LEARN_LINE})  # the learning line still comes
        self.assertEqual(gate._load(gate.state_file("s1")).get("shown"), [])  # nothing recorded as shown

    def test_a_server_failure_is_fail_open(self) -> None:
        with patch("memory_bridge.McpHttpClient", side_effect=OSError("down")):  # inside the worker thread
            self.assertEqual(gate.fetch_cards(str(self.repo), "x"), [])

    def test_hook_entry_point_prints_the_answer(self) -> None:
        payload = json.dumps({"sessionId": "s1", "cwd": str(self.repo), "prompt": "add a posts endpoint"})
        with patch.object(gate, "fetch_bootstrap", return_value={"memory": [CARD]}), patch("sys.stdin", io.StringIO(payload)), \
                patch("sys.stdout", new_callable=io.StringIO) as out:
            gate.main([])
        self.assertIn("Token auth", json.loads(out.getvalue())["additionalContext"])


LEARNED = ('Added the endpoint.\n<!-- ai-orch-learn {"learningCandidates":[{"kind":"procedure","summary":"Run tests '
           'with python tests.py","content":"pytest is not installed; run `python tests.py`.","locators":[{"kind":'
           '"file","ref":"tests.py","role":"validation"}]}]} -->')
ACCEPTED = {"status": "ACCEPTED", "created": 1, "reused": 0}


class StopHookTest(FlowTestCase):
    def setUp(self) -> None:
        super().setUp()
        self.captures: list[tuple] = []
        self.result: dict | None = ACCEPTED

        def capture(root, run_id, raw):
            self.captures.append((root, run_id, raw))
            return self.result

        patcher = patch.object(gate, "capture", side_effect=capture)
        patcher.start()
        self.addCleanup(patcher.stop)

    def own_fix(self) -> None:
        (self.repo / "a.py").write_text("x = 2\n")
        state = gate._load(gate.state_file("s1"))
        state["fixed_after_failure"] = True
        gate._save(gate.state_file("s1"), state)

    def test_the_answers_hidden_learning_is_saved_once_with_no_extra_call(self) -> None:
        self.prompt()
        (self.repo / "a.py").write_text("x = 2\n")
        self.assertEqual(self.stop(answer=LEARNED), {})  # saved by the hook: the agent is not stopped
        root, run_id, raw = self.captures[0]
        self.assertEqual(root, str(self.repo.resolve()))
        self.assertRegex(run_id, r"^copilot-[0-9a-f]{16}-r1$")
        self.assertEqual(raw[0]["summary"], "Run tests with python tests.py")
        self.assertEqual(gate._load(gate.state_file("s1"))["receipt"]["runId"], run_id)
        self.assertEqual(self.stop(answer=LEARNED), {})  # a second stop of the same request: no resubmission
        self.assertEqual(len(self.captures), 1)

    def test_a_failed_capture_is_not_a_receipt_and_a_later_stop_retries_it(self) -> None:
        self.prompt()
        for failed in (None, {"status": "REJECTED"}):  # timeout or unreachable, or not accepted
            self.result = failed
            self.assertEqual(self.stop(answer=LEARNED), {})
            self.assertIsNone(gate._load(gate.state_file("s1"))["receipt"])
        self.result = ACCEPTED
        self.stop(answer=LEARNED)
        self.assertEqual(len(self.captures), 3)
        self.assertEqual(len({c[1] for c in self.captures}), 1)  # the same request id each time: idempotent
        self.assertIsNotNone(gate._load(gate.state_file("s1"))["receipt"])

    def test_edits_after_a_delivered_card_without_a_comment_are_not_asked(self) -> None:
        self.prompt()  # a full card arrived: the task had knowledge to go on
        (self.repo / "b.py").write_text("y = 1\n")
        self.assertEqual(self.stop(), {})
        self.assertEqual(self.captures, [])

    def test_a_change_with_no_knowledge_and_no_comment_asks_once(self) -> None:
        self.prompt(cards=[])  # memory had nothing for it
        (self.repo / "b.py").write_text("y = 1\n")
        answer = self.stop()
        self.assertEqual(answer["decision"], "block")
        self.assertTrue(answer["reason"].startswith(gate.LEARN_REASON))
        self.assertEqual(self.stop(active=True, answer=LEARNED.split("\n", 1)[1]), {})  # the reply is saved
        self.assertEqual(len(self.captures), 1)
        self.assertEqual(self.stop(), {})  # at most one fallback

    def test_no_change_with_no_knowledge_is_not_asked(self) -> None:
        self.prompt(cards=[])
        self.assertEqual(self.stop(), {})

    def test_knowledge_found_by_the_agents_own_search_counts_as_delivered(self) -> None:
        self.prompt(cards=[])
        gate.record("posts api", {"knowledge": [{"id": "k9", "text": "New API endpoints go in app/api/<resource>.py."}]})
        state = gate._load(gate.state_file("s1"))
        self.assertTrue(state["full_delivered"])
        self.assertIn("k9", state["shown"])
        self.assertIn("New API endpoints go in app/api/<resource>.py.", state["delivered"])
        (self.repo / "b.py").write_text("y = 1\n")
        self.assertEqual(self.stop(), {})  # it had knowledge: no fallback
        gate.record("csv", {"knowledge": []})  # an empty search delivers nothing
        self.assertEqual(gate._load(gate.state_file("s1"))["delivered"],
                         ["New API endpoints go in app/api/<resource>.py."])

    def test_an_own_fix_without_a_comment_asks_once_and_the_reply_is_saved_in_the_same_request(self) -> None:
        self.prompt()
        self.own_fix()
        answer = self.stop()
        self.assertEqual(answer["decision"], "block")
        self.assertTrue(answer["reason"].startswith(gate.LEARN_REASON))
        # the reminder arrives as a prompt: not a new request (no reset, no server call, same request id)
        gate.record("csv", {"knowledge": []})
        with patch.object(gate, "fetch_bootstrap") as fetch:
            self.assertEqual(gate.prompt_hook({"sessionId": "s1", "cwd": str(self.repo),
                                               "prompt": answer["reason"]}), {})
        fetch.assert_not_called()
        self.assertIsNotNone(gate.refusal("anything"))
        self.assertEqual(gate._load(gate.state_file("s1"))["request"], 1)
        self.assertEqual(self.stop(active=True, answer=LEARNED.split("\n", 1)[1]), {})
        self.assertTrue(self.captures[0][1].endswith("-r1"))
        self.assertEqual(self.stop(), {})  # asked once; saved

    def test_a_reply_of_none_saves_nothing_and_is_not_asked_again(self) -> None:
        self.prompt()
        self.own_fix()
        self.assertEqual(self.stop()["decision"], "block")
        self.assertEqual(self.stop(active=True, answer="none"), {})
        self.assertEqual(self.stop(), {})
        self.assertEqual(self.captures, [])

    def test_a_new_request_has_its_own_id_and_may_be_asked_again(self) -> None:
        self.prompt()
        self.stop(answer=LEARNED)
        self.prompt("next task")
        self.own_fix()
        self.assertEqual(self.stop()["decision"], "block")
        self.stop(active=True, answer=LEARNED)
        self.assertEqual([c[1][-3:] for c in self.captures], ["-r1", "-r2"])

    def test_an_unconfirmed_stop_saves_nothing_and_asks_nothing(self) -> None:
        self.prompt()
        self.own_fix()
        transcript = self.tmp / "old.jsonl"  # only the previous request's stop, with its comment
        transcript.write_text("\n".join(json.dumps(e) for e in (
            {"type": "assistant.message", "data": {"content": LEARNED}}, {"type": "assistant.turn_end"},
            stop_event(500))))
        with patch.object(gate, "TRANSCRIPT_WAIT", 0.2):
            answer = gate.stop_hook({"sessionId": "s1", "cwd": str(self.repo), "stop_hook_active": False,
                                     "transcriptPath": str(transcript), "timestamp": 501})
        self.assertEqual(answer, {})
        self.assertEqual(self.captures, [])
        self.assertFalse(gate._load(gate.state_file("s1"))["asked"])

    def test_without_a_prompt_record_nothing_is_asked_or_saved(self) -> None:
        (self.repo / "a.py").write_text("x = 2\n")
        self.assertEqual(self.stop(answer=LEARNED), {})
        self.assertEqual(self.captures, [])


class LearningPayloadTest(FlowTestCase):
    def test_the_last_comment_wins_and_a_fenced_block_is_accepted(self) -> None:
        first = '<!-- ai-orch-learn {"learningCandidates":[{"summary":"one"}]} -->'
        second = '<!-- ai-orch-learn {"learningCandidates":[{"summary":"two"}]} -->'
        self.assertEqual(gate.learning_payload(f"a {first} b {second}"), [{"summary": "two"}])
        fenced = 'Done.\n```ai-orch-learn\n{"learningCandidates":[{"summary":"f"}]}\n```'
        self.assertEqual(gate.learning_payload(fenced), [{"summary": "f"}])
        for nothing in ("Done.", "<!-- ai-orch-learn not json -->", '<!-- ai-orch-learn {"learningCandidates":[]} -->',
                        "<!-- other comment -->"):
            self.assertIsNone(gate.learning_payload(nothing))

    def test_the_final_answer_is_the_last_assistant_message_before_this_stop(self) -> None:
        transcript = self.tmp / "t.jsonl"
        pre_tool = {"type": "hook.start", "data": {"hookType": "preToolUse", "input": {"timestamp": 5}}}
        events = [{"type": "user.message", "data": {"content": "task"}},
                  {"type": "assistant.message", "data": {"content": "first"}},
                  {"type": "assistant.turn_end"}, pre_tool,          # every model call ends a turn, then some hook
                  {"type": "tool.execution_start", "data": {}},
                  {"type": "assistant.message", "data": {"content": ""}},  # a tool-call turn without text
                  {"type": "assistant.message", "data": {"content": "final"}},
                  {"type": "assistant.turn_end"}, stop_event(7)]
        transcript.write_text("\n".join(json.dumps(e) for e in events) + "\nnot json\n")
        self.assertEqual(gate.final_answer(str(transcript), 7), "final")
        self.assertIsNone(gate.final_answer(str(self.tmp / "missing.jsonl"), 7, wait=0))
        self.assertIsNone(gate.final_answer(str(transcript), None, wait=0))  # no timestamp: cannot be confirmed
        # the reminder's reply: only an answer after the last user message counts
        transcript.write_text("\n".join(json.dumps(e) for e in events + [
            {"type": "user.message", "data": {"content": "reminder"}}, {"type": "assistant.turn_start"},
            {"type": "assistant.message", "data": {"content": "none"}}, {"type": "assistant.turn_end"},
            stop_event(9)]))
        self.assertEqual(gate.final_answer(str(transcript), 9), "none")
        self.assertEqual(gate.final_answer(str(transcript), 7), "final")

    def test_the_hook_waits_until_copilot_has_written_this_stop(self) -> None:
        import threading
        transcript = self.tmp / "late.jsonl"
        early = [{"type": "user.message", "data": {"content": "task"}},
                 {"type": "assistant.message", "data": {"content": "let me look"}}, {"type": "assistant.turn_end"},
                 {"type": "hook.start", "data": {"hookType": "preToolUse", "input": {"timestamp": 3}}}]
        transcript.write_text("".join(json.dumps(e) + "\n" for e in early))  # an earlier call's turn end: not ours

        def finish() -> None:  # Copilot writes the final turn 0.3 s after the hook started
            with transcript.open("a") as stream:
                for event in ({"type": "assistant.message", "data": {"content": "the answer"}},
                              {"type": "assistant.turn_end"}, stop_event(11)):
                    stream.write(json.dumps(event) + "\n")

        timer = threading.Timer(0.3, finish)
        timer.start()
        self.addCleanup(timer.cancel)
        self.assertEqual(gate.final_answer(str(transcript), 11, wait=2.0), "the answer")
        # this stop never recorded: an earlier answer (with its own comment) is not taken for this one
        self.assertIsNone(gate.final_answer(str(transcript), 99, wait=0.2))

    def test_machine_paths_drop_the_candidate_repository_and_endpoint_paths_stay(self) -> None:
        root = str(self.repo)
        local = gate.localize({"summary": "Add GET /api/ping", "content": f"Edit {root}/app/api/ping.py; serve "
                               "`/api/ping` and /users/<id>.", "appliesWhen": [f"under {root}/app"]}, root)
        self.assertEqual(local["content"], "Edit app/api/ping.py; serve `/api/ping` and /users/<id>.")
        self.assertEqual(local["appliesWhen"], ["under app"])
        for machine in ("Writing to /tmp is blocked by the sandbox.", f"Use {self.tmp} for scratch files."):
            self.assertIsNone(gate.localize({"summary": "s", "content": machine}, root))
        self.assertIsNone(gate.localize({"summary": "s", "content": "c", "limitations": ["needs /usr/bin"]}, root))

    def test_capture_validates_like_ai_orch_drops_invalid_ones_and_submits_once(self) -> None:
        import ai_orch
        good = {"kind": "procedure", "summary": "Run tests with python tests.py", "content": "x" * 900,
                "locators": [{"kind": "file", "ref": "tests.py", "role": "validation"}]}
        bad = {"kind": "nonsense", "summary": "s", "content": "c", "locators": []}
        machine = dict(good, content="Write to /tmp only.")
        calls = []

        class Client:
            def __init__(self, **kwargs):
                calls.append(("init", kwargs.get("timeout")))

            def __enter__(self):
                return self

            def __exit__(self, *exc):
                return False

            def capture_learning(self, cwd, run_id, candidates):
                calls.append((cwd, run_id, candidates))
                return ACCEPTED

        with patch.object(ai_orch, "AiOrchClient", Client), patch.object(ai_orch, "bridge_context_key",
                                                                        return_value="k" * 64), \
                patch.object(ai_orch, "record_learning_telemetry"):
            self.assertEqual(gate.capture(str(self.repo), "copilot-x-r1", [bad, good, machine]), ACCEPTED)
            self.assertIsNone(gate.capture(str(self.repo), "copilot-x-r1", [bad, machine]))  # nothing valid
        sent = calls[1][2]
        self.assertEqual(len(sent), 1)
        self.assertEqual(len(sent[0]["content"]), 700)  # fitted, as `ai_orch memory learn` fits it
        self.assertEqual(calls[0], ("init", gate.CAPTURE_TIMEOUT))
        self.assertEqual(len(calls), 2)  # no second client for the batch without valid candidates

    def test_a_capture_failure_is_none(self) -> None:
        import ai_orch

        def down(**kwargs):
            raise ai_orch.BridgeError("cannot reach the pinned terminal learning endpoint")

        good = {"kind": "procedure", "summary": "s", "content": "c",
                "locators": [{"kind": "file", "ref": "tests.py", "role": "validation"}]}
        with patch.object(ai_orch, "AiOrchClient", side_effect=down), \
                patch.object(ai_orch, "bridge_context_key", return_value="k" * 64):
            self.assertIsNone(gate.capture(str(self.repo), "copilot-x-r1", [good]))
        self.assertEqual(os.getcwd(), os.getcwd())


if __name__ == "__main__":
    unittest.main()


class PostHookTest(FlowTestCase):
    """The step moments through the repository's shared step module (scripts/hooks/ai_orch_step_memory)."""

    def setUp(self) -> None:
        super().setUp()
        self.step = gate.shared_hooks()
        self.assertIsNotNone(self.step)
        self.lookups: list[tuple[str, str]] = []
        test_card = {"id": "t1", "summary": "No pytest here; run the tests with python -m unittest -v tests",
                     "text": "pytest is not installed in this venv.", "files": ["tests.py"]}

        def fetch(root, query, kinds, exclude, timeout=1.0):
            self.lookups.append((kinds, query))
            return ("found", [test_card]) if "test_address" in kinds else ("empty", [])

        for patcher in (patch.object(self.step, "fetch", side_effect=fetch),
                        patch.object(self.step.session, "state_path",
                                     side_effect=lambda sid, root: self.tmp / f"shared-{sid}.json"),
                        patch.object(self.step.tempfile, "gettempdir", return_value=str(self.tmp))):
            patcher.start()
            self.addCleanup(patcher.stop)

    def post(self, command: str, output: str, result: str = "success") -> dict:
        return gate.post_hook({"sessionId": "s1", "cwd": str(self.repo), "toolName": "bash",
                               "toolArgs": {"command": command},
                               "toolResult": {"resultType": result, "textResultForLlm": output}})

    def test_a_missing_test_tool_brings_the_test_card_at_the_failure(self) -> None:
        self.prompt(cards=[])
        out = "/x/venv/bin/python: No module named pytest\n<shellId: 0 completed with exit code 0>"
        answer = self.post("python -m pytest -q", out)
        self.assertIn("memory for a failing check", answer["additionalContext"])
        self.assertIn("python -m unittest -v tests", answer["additionalContext"])
        self.assertIn("test_address", self.lookups[0][0].split(","))
        state = gate._load(gate.state_file("s1"))
        self.assertTrue(state["check_failed"])
        self.assertIn("t1", state["shown"])            # shown once: not delivered again in this session
        self.assertIn("tests.py", state["card_files"])
        self.post("python -m unittest -v tests", "Ran 4 tests\n\nOK")
        self.assertTrue(gate._load(gate.state_file("s1"))["fixed_after_failure"])

    def test_other_tools_and_closed_memory_make_no_lookup(self) -> None:
        self.prompt(cards=[])
        self.assertEqual(gate.post_hook({"sessionId": "s1", "cwd": str(self.repo), "toolName": "view",
                                         "toolArgs": {"path": "a.py"}, "toolResult": {}}), {})
        state = gate._load(gate.state_file("s1"))
        state["empty"] = "csv export"  # an `ai_orch memory search` of the agent found nothing in this request
        gate._save(gate.state_file("s1"), state)
        self.assertEqual(self.post("python -m pytest -q", "No module named pytest"), {})
        self.assertEqual(self.lookups, [])

    def test_an_empty_step_answer_closes_the_agents_search_too(self) -> None:
        self.prompt(cards=[])
        with patch.object(self.step, "fetch", return_value=("empty", [])):
            self.assertEqual(self.post("python -m unittest -v tests", "Ran 4 tests\n\nOK"), {})
        self.assertIsNotNone(gate.refusal("anything"))


class StopFallbackSignalTest(FlowTestCase):
    def test_no_learning_signal_no_reminder(self) -> None:
        self.prompt()  # the delivered card points at app/api/auth.py
        (self.repo / "app" / "api").mkdir(parents=True)
        (self.repo / "app" / "api" / "auth.py").write_text("x = 1\n")
        state = gate._load(gate.state_file("s1"))
        self.assertIn("app/api/auth.py", state["card_files"])
        self.assertEqual(self.stop(), {})  # only files a card already covered, no failure fixed

    def test_a_fixed_failure_is_a_learning_signal(self) -> None:
        self.prompt()
        (self.repo / "app" / "api").mkdir(parents=True)
        (self.repo / "app" / "api" / "auth.py").write_text("x = 1\n")
        state = gate._load(gate.state_file("s1"))
        state["fixed_after_failure"] = True
        gate._save(gate.state_file("s1"), state)
        self.assertEqual(self.stop()["decision"], "block")

    def test_every_prompt_carries_the_learning_line_also_with_empty_memory(self) -> None:
        self.assertEqual(self.prompt(cards=[]), {"additionalContext": gate.LEARN_LINE})
        self.assertTrue(self.prompt()["additionalContext"].endswith(gate.LEARN_LINE))
        for part in ("<!-- ai-orch-learn", '"learningCandidates"', "<=700 chars", "not about this machine",
                     "No tool call is needed"):
            self.assertIn(part, gate.LEARN_LINE)


class StepSyncTest(PostHookTest):
    def test_a_fixed_failure_signals_the_stop_fallback_and_closed_memory_does_not_hide_it(self) -> None:
        self.prompt(cards=[])
        state = gate._load(gate.state_file("s1"))
        state["empty"] = "csv export"  # memory closed for the request: no lookups, signals still recorded
        gate._save(gate.state_file("s1"), state)
        (self.repo / "a.py").write_text("x = 2\n")
        self.post("python -m pytest -q", "/v/bin/python: No module named pytest\n<shellId: 0 completed with exit code 0>")
        self.assertEqual(self.post("python tests.py", "Ran 4 tests\n\nOK"), {})  # no nudge, no lookup
        self.assertEqual(self.lookups, [])
        self.assertEqual(self.stop(), {"decision": "block", "reason": gate.LEARN_REASON})

    def test_a_pointer_from_the_step_lookup_names_the_copilot_search_command(self) -> None:
        self.prompt(cards=[])
        pointer = {"id": "p7", "summary": "Pagination helper for API lists", "pointer": True}
        with patch.object(self.step, "fetch", return_value=("found", [pointer])):
            text = self.post("python -m pytest -q", "No module named pytest")["additionalContext"]
        self.assertIn('`ai_orch memory search --query "Pagination helper for API lists"`', text)
        self.assertNotIn("extras(", text)

    def test_queries_are_shared_both_ways_and_failures_stay_retryable(self) -> None:
        self.prompt(cards=[])
        self.post("python tests.py", "OK")  # first check: a successful step lookup
        step_query = self.lookups[0][1]
        self.assertIn("already searched memory", gate.refusal(step_query))  # the agent's search: refused locally
        # the other way: a query the agent asked is not looked up again by the step hook
        self.prompt(cards=[])
        gate.record(self.step.VERIFY_QUERY + ": python tests.py", {"knowledge": [{"id": "m"}]})
        self.lookups.clear()
        self.post("python tests.py", "OK")
        self.assertEqual(self.lookups, [])
        # a failed lookup is not recorded: the same query may be asked again
        self.prompt(cards=[])
        with patch.object(self.step, "fetch", return_value=("failed", [])):
            self.post("python tests.py", "OK")
        self.assertIsNone(gate.refusal(self.step.VERIFY_QUERY + ": python tests.py"))


class RulesPreferenceTest(FlowTestCase):
    def answer(self, result: dict, env: dict | None = None) -> str:
        with patch.object(gate, "fetch_bootstrap", return_value=result), patch.dict(os.environ, env or {}):
            return block(gate.prompt_hook({"sessionId": "s1", "cwd": str(self.repo), "prompt": "x"}))

    def test_saved_load_preference_delivers_the_rules_and_does_not_resend_them(self) -> None:
        rules = {"rulesLoaded": True, "rulesEtag": "e1", "rules": [{"scope": "project", "statement": "Use UTC."}]}
        long_rules = {"rulesLoaded": True, "rulesEtag": "e0",
                      "rules": [{"statement": f"Rule {i}: " + "x" * 300} for i in range(15)]}
        text = self.answer(long_rules)
        self.assertIn("Rule 14:", text)  # all rules the server sent, never cut
        self.assertIn("AI Orchestration rules for this repository (approved; follow them):\n- Use UTC. (project)",
                      self.answer(rules))
        with patch.object(gate, "fetch_bootstrap", return_value={"rulesUnchanged": True}) as fetch:
            gate.prompt_hook({"sessionId": "s1", "cwd": str(self.repo), "prompt": "next"})
        self.assertEqual(fetch.call_args.args[3], "e1")  # the next prompt sends the etag: rules are not resent

    def test_no_saved_preference_asks_interactively_and_skips_unattended(self) -> None:
        ask = {"askUser": "Load the rules?"}
        self.assertIn("Ask the user once with ask_user", self.answer(ask, {gate.UNATTENDED_ENV: ""}))
        unattended = self.answer(ask, {gate.UNATTENDED_ENV: "1"})
        self.assertIn("rules not loaded", unattended)
        self.assertNotIn("Ask the user", unattended)

    def test_saved_skip_preference_says_nothing(self) -> None:
        self.assertEqual(self.answer({"rulesLoaded": False}), "")


class LearningOnlyNewTest(PostHookTest):
    """A fix memory gave is not a learning signal; a pointer is not knowledge; the reminder names what is known."""

    FAIL = "/v/bin/python: No module named pytest\n<shellId: 0 completed with exit code 0>"

    def card(self, text: str, pointer: bool = False) -> dict:
        card = {"id": "t9", "summary": "No pytest here", "text": text, "files": ["tests.py"]}
        return dict(card, pointer=True) if pointer else card

    def run_failure_then(self, card: dict, passing_command: str) -> dict:
        self.prompt(cards=[])
        with patch.object(self.step, "fetch", return_value=("found", [card])):
            self.post("python -m pytest -q", self.FAIL)
        self.post(passing_command, "Ran 4 tests\n\nOK")
        return gate._load(gate.state_file("s1"))

    def test_running_the_fix_a_full_card_gave_is_not_a_learning_signal(self) -> None:
        state = self.run_failure_then(self.card("Run the suite with `python tests.py`."),
                                      "cd /r && python tests.py 2>&1 | tail -30")
        self.assertTrue(state["fixed_from_memory"])
        self.assertFalse(state["fixed_after_failure"])
        self.assertEqual(self.stop(), {})  # no edits outside known files, no own fix: no reminder

    def test_a_pointer_or_another_command_is_an_own_fix(self) -> None:
        state = self.run_failure_then(self.card("", pointer=True), "python tests.py")
        self.assertTrue(state["fixed_after_failure"])           # a pointer delivered no knowledge
        state = self.run_failure_then(self.card("Use `python -m unittest -v tests`."), "python tests.py")
        self.assertTrue(state["fixed_after_failure"])           # the passing command is not the delivered one

    def test_the_reminder_names_what_this_task_already_received(self) -> None:
        self.prompt()  # delivers "Token auth for API routes" in full
        (self.repo / "new.py").write_text("x = 1\n")
        state = gate._load(gate.state_file("s1"))
        state["fixed_after_failure"] = True  # an own fix: the fallback's signal
        gate._save(gate.state_file("s1"), state)
        reason = self.stop()["reason"]
        self.assertTrue(reason.startswith(gate.LEARN_REASON))
        self.assertIn("Already known (delivered to you in this task, do not save again): Token auth for API routes",
                      reason)
        # the reminder arriving as a prompt is still recognised: the request goes on
        self.assertEqual(gate.prompt_hook({"sessionId": "s1", "cwd": str(self.repo), "prompt": reason}), {})



class IncompleteRulesTest(RulesPreferenceTest):
    def test_incomplete_rules_keep_no_etag_and_say_how_to_load_the_rest(self) -> None:
        page = {"rulesLoaded": False, "rulesEtag": "e2", "nextCursor": "c1",
                "rules": [{"statement": "First page rule."}]}
        text = self.answer(page)
        self.assertIn("First page rule.", text)
        self.assertIn("`ai_orch rule instructions --scope effective`", text)
        self.assertIsNone(gate._load(gate.state_file("s1")).get("rules_etag"))  # the next prompt asks again


class LocatedCardFilesTest(FlowTestCase):
    def test_a_located_entry_counts_as_its_file_for_the_learning_signal(self) -> None:
        text = ("AI Orchestration, memory for this request:\n- Memory card: Model to_dict. Use UTC. Files: "
                "app/models.py:287-300 app.models.Post (class; to_dict is not in the current code), "
                "app/models.py:231-250 app.models.User#to_dict (method), app/api/__init__.py, app/x.py (app.x#f).")
        self.assertEqual(gate.card_files(text), {"app/models.py", "app/api/__init__.py", "app/x.py"})
        card = {"id": "c5", "summary": "Model to_dict", "text": "Use UTC.",
                "files": ["app/models.py:231-250 app.models.User#to_dict (method)"]}
        self.prompt(cards=[card])
        (self.repo / "app").mkdir()
        (self.repo / "app" / "models.py").write_text("x = 1\n")  # an edit only in the card's file
        self.assertEqual(self.stop(), {})  # covered: no reminder


class CardCodeTest(FlowTestCase):
    def test_current_code_comes_with_its_card_within_its_own_budget(self) -> None:
        code = "Current code, app/api/auth.py:1-2 app.api.auth#verify (function):\n1│ def verify(token):"
        text = block(self.prompt(cards=[dict(CARD, code=code)]))
        self.assertIn("Files: app/api/auth.py.\n" + code, text)
        self.assertEqual(gate.card_files(text), {"app/api/auth.py"})
        big = [dict(CARD, id=f"k{i}", code="x" * 2000) for i in range(2)]
        text = block(self.prompt(cards=big))
        self.assertEqual(text.count("- Memory card"), 2)  # both cards
        self.assertEqual(text.count("x" * 2000), 1)       # only the code that fits the code budget


class ProfileAdviceTest(FlowTestCase):
    """How to check a change here, from the build files, once per session and only for change tasks."""

    def setUp(self) -> None:
        super().setUp()
        (self.repo / "tests.py").write_text("import unittest\n")
        (self.repo / "requirements.txt").write_text("flask\n")
        subprocess.run(["git", "-C", str(self.repo), "add", "."], check=True)
        subprocess.run(["git", "-C", str(self.repo), "-c", "user.email=a@b", "-c", "user.name=x", "commit", "-qm",
                        "py"], check=True)

    def test_a_change_task_gets_the_declared_test_command_once_as_advice(self) -> None:
        text = self.prompt("Add a JSON endpoint for posts", cards=[])["additionalContext"]
        self.assertIn("- Tests: `python -m unittest`", text)
        self.assertIn("not a test result", text)
        self.assertNotIn("Tests:", self.prompt("Add another endpoint", cards=[])["additionalContext"])  # once

    def test_a_question_gets_no_test_advice(self) -> None:
        for question in ("How does token auth work here?", "Explain the API blueprint", "nasıl çalışıyor bu?"):
            with self.subTest(question=question):
                self.assertNotIn("Tests:", self.prompt(question, cards=[])["additionalContext"])
        self.assertIn("Tests:", self.prompt("Fix the posts pagination", cards=[])["additionalContext"])

    def test_a_change_request_phrased_as_a_question_gets_the_advice(self) -> None:
        for request in ("Can you fix the posts pagination?", "Could you add a JSON endpoint for posts?",
                        "Posts için sayfalama ekler misin?", "Bu hatayı düzeltebilir misin?"):
            with self.subTest(request=request):
                self.assertFalse(gate.is_question(request))
        for question in ("Can you explain how pagination works?", "Where is the token checked?", "Bu ne işe yarıyor?"):
            with self.subTest(question=question):
                self.assertTrue(gate.is_question(question))

    def test_advice_that_comes_after_the_deadline_is_neither_shown_nor_recorded(self) -> None:
        import time as clock

        def slow(root, timeout):  # e.g. a slow `git status` in a large working tree
            clock.sleep(0.5)
            return "Project profile (Python), read from the build files:\n- Tests: `python -m unittest`"

        with patch.object(gate, "DEADLINE", 0.3), patch.object(gate, "_profile", side_effect=slow), \
                patch.object(gate, "fetch_bootstrap", return_value={"memory": [CARD]}):
            started = clock.monotonic()
            answer = gate.prompt_hook({"sessionId": "s1", "cwd": str(self.repo), "prompt": "Add an endpoint"})
            self.assertLess(clock.monotonic() - started, 0.3 + 0.15)
        self.assertEqual(answer, {"additionalContext": gate.LEARN_LINE})
        state = gate._load(gate.state_file("s1"))
        self.assertFalse(state.get("profile_shown"))
        self.assertEqual(state.get("shown"), [])
        self.assertIn("Tests:", self.prompt("Add an endpoint", cards=[])["additionalContext"])  # comes next time

    def test_build_files_being_changed_give_no_advice(self) -> None:
        (self.repo / "requirements.txt").write_text("flask\npytest\n")
        self.assertNotIn("Tests:", self.prompt("Add a JSON endpoint", cards=[])["additionalContext"])


class LeanAgentTextTest(unittest.TestCase):
    def test_the_agent_asks_for_batched_independent_work_and_keeps_dependent_steps_in_order(self) -> None:
        import install_user_integration as installer
        body = installer.agent_text().split("---\n")[2]
        for part in ("request them together in one turn", "edits to different files", "create a file before you run it",
                     "same file one after another", "Run the tests and checks the task needs"):
            self.assertIn(part, body)
