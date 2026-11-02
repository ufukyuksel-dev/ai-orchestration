#!/usr/bin/env python3
"""The agent's verdict on the memory cards it was given (trace contract v4), with no extra model call: the prompt hook
labels each delivered card ([m1], [m2] ...) and, only when it delivered cards, asks for a `memoryFeedback` list in the
same hidden ai-orch-learn comment; the stop hook turns the labels back into card ids and writes one `feedback` trace
line (ids, verdicts, counts and an optional repository path; no text). A missing verdict never stops the agent."""

from __future__ import annotations

import json
import unittest
from unittest.mock import patch

import copilot_memory_gate as gate
import test_copilot_memory_flow as flow
from test_copilot_trace import TraceMixin

FULL = {"id": "11111111-1111-1111-1111-111111111111", "summary": "Token auth for API routes",
        "text": "Use token_auth.login_required.", "files": ["app/api/auth.py"]}
POINTER = {"id": "22222222-2222-2222-2222-222222222222", "summary": "Pagination helper", "pointer": True}
STALE = {"id": "33333333-3333-3333-3333-333333333333", "summary": "Old route layout", "text": "Routes in app/main.",
         "stale": True, "files": ["app/main/routes.py"]}


def comment(value: dict) -> str:
    return f"Done.\n<!-- ai-orch-learn {json.dumps(value)} -->"


class CardFeedbackTestCase(TraceMixin, flow.FlowTestCase):
    def setUp(self) -> None:
        super().setUp()
        self.use_trace_dir()
        self.captures: list = []
        self.result: dict | None = flow.ACCEPTED

        def capture(root, run_id, raw):
            self.captures.append(raw)
            return self.result

        patcher = patch.object(gate, "capture", side_effect=capture)
        patcher.start()
        self.addCleanup(patcher.stop)

    def state(self) -> dict:
        return gate._load(gate.state_file("s1"))


class LabelTest(CardFeedbackTestCase):
    def test_each_delivered_card_gets_a_label_and_the_labels_map_to_card_ids(self) -> None:
        context = self.prompt(cards=[FULL, POINTER, STALE])["additionalContext"]
        self.assertIn("- [m1] Memory card: Token auth for API routes. Use token_auth.login_required. "
                      "Files: app/api/auth.py.", context)
        self.assertIn("- [m2] Possibly relevant memory (not loaded): Pagination helper.", context)
        self.assertIn("- [m3] Memory card (may be outdated: the code changed since): Old route layout.", context)
        self.assertEqual(self.state()["refs"], {"m1": FULL["id"], "m2": POINTER["id"], "m3": STALE["id"]})
        self.assertEqual(self.state()["delivered"], ["Token auth for API routes", "Old route layout"])
        self.assertEqual(self.state()["card_files"], ["app/api/auth.py", "app/main/routes.py"])
        inject = self.events("inject")[0]
        line = "- [m1] Memory card: Token auth for API routes. Use token_auth.login_required. Files: app/api/auth.py."
        self.assertEqual({c["id"]: c["chars"] for c in inject["cards"]}[FULL["id"]], len(line))

    def test_labels_restart_per_request_and_a_request_without_cards_has_none(self) -> None:
        self.prompt(cards=[FULL])
        self.prompt(cards=[STALE])
        self.assertEqual(self.state()["refs"], {"m1": STALE["id"]})
        self.prompt(cards=[])
        self.assertEqual(self.state()["refs"], {})

    def test_the_label_counts_against_the_card_budget(self) -> None:
        cards = [dict(FULL, id=f"c{i}", text="t" * 900) for i in range(6)]
        text, shown = gate.render(cards, set())
        self.assertLessEqual(len(text), gate.CARD_CHARS)
        self.assertEqual(text.count("Memory card"), len(shown))

    def test_the_learn_reminder_prompt_keeps_the_requests_labels(self) -> None:
        self.prompt(cards=[FULL])
        gate.prompt_hook({"sessionId": "s1", "cwd": str(self.repo), "prompt": gate.LEARN_REASON + " x"})
        self.assertEqual(self.state()["refs"], {"m1": FULL["id"]})


class FeedbackRequestTest(CardFeedbackTestCase):
    def test_only_a_request_with_cards_asks_for_the_feedback(self) -> None:
        with_cards = self.prompt(cards=[FULL])["additionalContext"]
        self.assertTrue(with_cards.endswith(gate.LEARN_LINE + " " + gate.FEEDBACK_LINE))
        self.assertIn('"memoryFeedback":[{"ref":"m1","verdict":"helped|unused|misled"', gate.FEEDBACK_LINE)
        self.assertIn("unused is a normal answer", gate.FEEDBACK_LINE)
        self.assertEqual(self.prompt(cards=[])["additionalContext"], gate.LEARN_LINE)  # no cards: unchanged text
        self.assertEqual(self.prompt(cards=[FULL])["additionalContext"], gate.LEARN_LINE)  # shown before: none now

    def test_the_inject_line_counts_the_feedback_line_too(self) -> None:
        context = self.prompt(cards=[FULL])["additionalContext"]
        self.assertEqual(self.events("inject")[0]["injected"]["chars"], len(context))


class ParseTest(unittest.TestCase):
    REFS = {"m1": "id-1", "m2": "id-2", "m3": "id-3"}

    def parse(self, feedback, root: str | None = "/repo") -> list | None:
        return gate.memory_feedback(comment({"memoryFeedback": feedback}), self.REFS, root)

    def test_a_table_of_valid_and_invalid_entries(self) -> None:
        cases = [
            ({"ref": "m1", "verdict": "helped", "savedCalls": 4, "evidencePath": "app/api/auth.py"},
             {"cardId": "id-1", "verdict": "helped", "savedCalls": 4, "evidencePath": "app/api/auth.py"}),
            ({"ref": "[m2]", "verdict": "unused"}, {"cardId": "id-2", "verdict": "unused"}),
            ({"ref": "M3", "verdict": "misled", "wastedCalls": 3}, {"cardId": "id-3", "verdict": "misled",
                                                                     "wastedCalls": 3}),
            ({"ref": "m1", "verdict": "helped", "savedCalls": 21}, {"cardId": "id-1", "verdict": "helped"}),
            ({"ref": "m1", "verdict": "helped", "savedCalls": -1}, {"cardId": "id-1", "verdict": "helped"}),
            ({"ref": "m1", "verdict": "helped", "savedCalls": 2.5}, {"cardId": "id-1", "verdict": "helped"}),
            ({"ref": "m1", "verdict": "helped", "savedCalls": True}, {"cardId": "id-1", "verdict": "helped"}),
            ({"ref": "m1", "verdict": "helped", "savedCalls": "3"}, {"cardId": "id-1", "verdict": "helped"}),
            ({"ref": "m1", "verdict": "unused", "savedCalls": 3, "wastedCalls": 2},
             {"cardId": "id-1", "verdict": "unused"}),  # counts only where the verdict uses them
            ({"ref": "m1", "verdict": "helped", "evidencePath": "/repo/app/x.py"},
             {"cardId": "id-1", "verdict": "helped", "evidencePath": "app/x.py"}),
            ({"ref": "m1", "verdict": "helped", "evidencePath": "/etc/passwd"}, {"cardId": "id-1", "verdict": "helped"}),
            ({"ref": "m1", "verdict": "helped", "evidencePath": "../outside.py"},
             {"cardId": "id-1", "verdict": "helped"}),
            ({"ref": "m1", "verdict": "helped", "evidencePath": "a" * 1025}, {"cardId": "id-1", "verdict": "helped"}),
            ({"ref": "m1", "verdict": "helped", "evidencePath": "src/*.py"}, {"cardId": "id-1", "verdict": "helped"}),
            ({"ref": "m1", "verdict": "helped", "evidencePath": 7}, {"cardId": "id-1", "verdict": "helped"}),
            ({"ref": "m9", "verdict": "helped"}, None),           # unknown label
            ({"ref": "m1", "verdict": "great"}, None),            # verdict outside the set
            ({"ref": "m1"}, None),
            ({"verdict": "helped"}, None),
            ("m1 helped", None),
        ]
        for entry, expected in cases:
            with self.subTest(entry=entry):
                self.assertEqual(self.parse([entry]), [expected] if expected else None)

    def test_one_verdict_per_card_the_first_wins(self) -> None:
        got = self.parse([{"ref": "m1", "verdict": "helped"}, {"ref": "m1", "verdict": "misled"},
                          {"ref": "m2", "verdict": "unused"}])
        self.assertEqual(got, [{"cardId": "id-1", "verdict": "helped"}, {"cardId": "id-2", "verdict": "unused"}])

    def test_no_feedback_or_broken_json_is_none(self) -> None:
        for answer in ("Done.", "Done.\n<!-- ai-orch-learn {broken -->", comment({"learningCandidates": []}),
                       comment({"memoryFeedback": "m1 helped"}), comment({"memoryFeedback": []})):
            with self.subTest(answer=answer):
                self.assertIsNone(gate.memory_feedback(answer, self.REFS, "/repo"))

    def test_without_a_repository_the_evidence_path_is_left_out(self) -> None:
        self.assertEqual(self.parse([{"ref": "m1", "verdict": "helped", "evidencePath": "a.py"}], root=None),
                         [{"cardId": "id-1", "verdict": "helped"}])

    def test_the_learning_candidates_are_read_as_before(self) -> None:
        both = comment({"learningCandidates": [{"summary": "s"}], "memoryFeedback": [{"ref": "m1",
                                                                                      "verdict": "unused"}]})
        self.assertEqual(gate.learning_payload(both), [{"summary": "s"}])
        self.assertIsNone(gate.learning_payload(comment({"memoryFeedback": [{"ref": "m1", "verdict": "unused"}]})))


class FeedbackTraceTest(CardFeedbackTestCase):
    def feedback(self) -> list[dict]:
        return self.events("feedback")

    def test_the_agents_verdicts_become_one_feedback_line_with_card_ids(self) -> None:
        self.prompt(cards=[FULL, POINTER])
        answer = comment({"memoryFeedback": [
            {"ref": "m1", "verdict": "helped", "savedCalls": 3, "evidencePath": "app/api/auth.py"},
            {"ref": "m2", "verdict": "unused", "note": "zebra-quartz-7731"}]})
        self.assertEqual(self.stop(answer=answer), {})
        line = self.feedback()[0]
        self.assertEqual(line["parsed"], True)
        self.assertEqual(line["cards"], [
            {"cardId": FULL["id"], "verdict": "helped", "savedCalls": 3, "evidencePath": "app/api/auth.py"},
            {"cardId": POINTER["id"], "verdict": "unused"}])
        self.assertEqual((line["hook"], line["repo"]), ("agentStop", str(self.repo.resolve())))
        self.assertNotIn("zebra-quartz", self.raw())
        self.assertNotIn("Done.", self.raw())
        self.assertEqual([e["event"] for e in self.lines() if e["hook"] == "agentStop"], ["feedback", "stop"])
        self.assertEqual(self.captures, [])  # no learning candidates: nothing captured
        self.stop(answer=answer)
        self.assertEqual(len(self.feedback()), 1)  # once per request

    def test_cards_delivered_and_no_verdict_is_parsed_false_and_does_not_stop_the_agent(self) -> None:
        self.prompt(cards=[FULL])
        (self.repo / "a.py").write_text("x = 2\n")  # a change after a delivered card: no reminder before, none now
        self.assertEqual(self.stop(answer="Done."), {})
        self.assertEqual(self.feedback()[0]["cards"], [])
        self.assertEqual(self.feedback()[0]["parsed"], False)
        self.assertEqual([s["decision"] for s in self.events("stop")], ["allow"])

    def test_a_request_without_cards_writes_no_feedback_line(self) -> None:
        self.prompt(cards=[])
        self.stop(answer=comment({"memoryFeedback": [{"ref": "m1", "verdict": "helped"}]}))
        self.assertEqual(self.feedback(), [])

    def test_learning_and_feedback_in_one_comment_are_both_handled(self) -> None:
        self.prompt(cards=[FULL])
        learned = json.loads(flow.LEARNED.split("ai-orch-learn ", 1)[1].rsplit(" -->", 1)[0])
        learned["memoryFeedback"] = [{"ref": "m1", "verdict": "misled", "wastedCalls": 2}]
        self.assertEqual(self.stop(answer=comment(learned)), {})
        self.assertEqual(self.captures[0][0]["summary"], "Run tests with python tests.py")
        self.assertIsNotNone(self.state()["receipt"])
        self.assertEqual(self.feedback()[0]["cards"], [{"cardId": FULL["id"], "verdict": "misled", "wastedCalls": 2}])

    def test_a_learning_saved_by_command_still_reads_the_verdict(self) -> None:
        self.prompt(cards=[FULL])
        gate.record_learned()
        self.stop(answer=comment({"memoryFeedback": [{"ref": "m1", "verdict": "unused"}]}))
        self.assertEqual(self.feedback()[0]["cards"], [{"cardId": FULL["id"], "verdict": "unused"}])

    def test_a_blocked_stop_asks_for_the_feedback_too_and_the_reply_carries_it(self) -> None:
        pointer_only = dict(POINTER)  # a pointer delivers no knowledge: an own fix is still new
        self.prompt(cards=[pointer_only])
        (self.repo / "a.py").write_text("x = 2\n")
        state = self.state()
        state["fixed_after_failure"] = True
        gate._save(gate.state_file("s1"), state)
        answer = self.stop(answer="Done.")
        self.assertEqual(answer["decision"], "block")
        self.assertTrue(answer["reason"].startswith(gate.LEARN_REASON))
        self.assertIn("memoryFeedback", answer["reason"])
        self.assertEqual(self.feedback(), [])  # the reply may still carry it
        self.stop(active=True, answer=comment({"memoryFeedback": [{"ref": "m1", "verdict": "unused"}]}))
        self.assertEqual(self.feedback()[0]["parsed"], True)
        self.assertEqual([(s["decision"], s["reason"]) for s in self.events("stop")],
                         [("block", "learn_request"), ("allow", "max_blocks")])

    def test_the_reminder_without_cards_is_unchanged(self) -> None:
        self.prompt(cards=[])
        (self.repo / "a.py").write_text("x = 2\n")
        state = self.state()
        state["fixed_after_failure"] = True
        gate._save(gate.state_file("s1"), state)
        self.assertEqual(self.stop(answer="Done.")["reason"], gate.LEARN_REASON)

    def test_a_feedback_failure_is_fail_open(self) -> None:
        self.prompt(cards=[FULL])
        with patch.object(gate, "memory_feedback", side_effect=RuntimeError("boom")):
            self.assertEqual(self.stop(answer=flow.LEARNED), {})
        self.assertEqual(len(self.captures), 1)  # the learning is saved as before
        self.assertEqual(self.feedback(), [])
        self.assertEqual(self.events("stop")[-1]["decision"], "allow")


if __name__ == "__main__":
    unittest.main()
