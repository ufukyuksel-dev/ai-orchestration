#!/usr/bin/env python3
"""The Copilot hooks' local trace (copilot_trace, contract v1): every event type is written with the common fields,
no prompt, command or output text reaches the file, `AI_ORCH_TRACE=0` turns it off, a trace that cannot be written
changes nothing in the hooks' answers, lines stay within 16 KiB and old day files are removed."""

from __future__ import annotations

import datetime as dt
import hashlib
import io
import json
import os
import stat
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import copilot_memory_gate as gate
import copilot_trace
import test_copilot_memory_flow as flow

COMMON = {"v", "ts", "sessionId", "hook", "event", "durationMs"}
SECRET_PROMPT = "add a posts endpoint, zebra-quartz-7731 is my private phrase"
CODE = "  12-30 def login_required(f):"


class TraceMixin:
    """Each test writes its trace into a directory of its own."""

    def use_trace_dir(self) -> None:
        trace_dir = tempfile.TemporaryDirectory()
        self.addCleanup(trace_dir.cleanup)
        self.trace_dir = Path(trace_dir.name) / "trace"
        patcher = patch.dict(os.environ, {"AI_ORCH_TRACE_DIR": str(self.trace_dir), "AI_ORCH_TRACE": "1"})
        patcher.start()
        self.addCleanup(patcher.stop)

    def lines(self) -> list[dict]:
        files = sorted(self.trace_dir.glob("*.jsonl")) if self.trace_dir.is_dir() else []
        return [json.loads(line) for f in files for line in f.read_text(encoding="utf-8").splitlines()]

    def raw(self) -> str:
        return "".join(f.read_text(encoding="utf-8") for f in self.trace_dir.glob("*.jsonl"))

    def events(self, name: str | None = None) -> list[dict]:
        return [line for line in self.lines() if name is None or line["event"] == name]

    def skips(self) -> list[str]:
        return [line["reason"] for line in self.events("skip")]


class PromptTraceTest(TraceMixin, flow.FlowTestCase):
    def setUp(self) -> None:
        super().setUp()
        self.use_trace_dir()

    def test_prompt_and_inject_carry_the_schema_and_the_cards(self) -> None:
        root = str(self.repo.resolve())
        full = {"id": "c1", "summary": "Token auth for API routes", "text": "Use token_auth.login_required.",
                "files": ["app/api/auth.py:12-30 app.api.auth#login_required (function)", f"{root}/app/x.py",
                          "/elsewhere/outside.py"], "code": CODE, "score": 0.83, "kind": "procedure"}
        pointer = {"id": "p2", "summary": "Pagination helper", "pointer": True, "files": ["app/p.py"]}
        stale = {"id": "s3", "summary": "Old route layout", "text": "Routes live in app/main.", "stale": True,
                 "files": ["app/main/routes.py"]}
        answer = self.prompt(SECRET_PROMPT, cards=[full, pointer, stale])
        lines = self.lines()
        self.assertEqual([line["event"] for line in lines], ["prompt", "inject"])
        for line in lines:
            self.assertTrue(COMMON <= set(line), line)
            self.assertEqual((line["v"], line["sessionId"], line["hook"]), (1, "s1", "userPromptSubmitted"))
            self.assertRegex(line["ts"], r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$")
            self.assertEqual((line["repo"], line["project"]), (root, "repo"))
            self.assertIsInstance(line["durationMs"], int)
            self.assertNotIn("client", line)  # not known here: not guessed
        prompt, inject = lines
        self.assertEqual(prompt["promptChars"], len(SECRET_PROMPT))
        self.assertEqual(prompt["promptHash"], hashlib.sha256(SECRET_PROMPT.encode()).hexdigest()[:12])
        self.assertEqual(prompt["request"], 1)
        context = answer["additionalContext"]
        self.assertEqual(inject["injected"], {"chars": len(context), "tokensEst": -(-len(context) * 2 // 7)})
        self.assertEqual(inject["parts"], {"profile": False, "cards": 2, "pointers": 1, "learnLine": True})
        cards = {c["id"]: c for c in inject["cards"]}
        self.assertEqual(cards["c1"]["paths"], ["app/api/auth.py", "app/x.py"])  # repo-relative, outside left out
        self.assertEqual((cards["c1"]["codeChars"], cards["c1"]["score"], cards["c1"]["kind"]),
                         (len(CODE), 0.83, "procedure"))
        line = (f"- Memory card: Token auth for API routes. Use token_auth.login_required. Files: "
                f"{', '.join(full['files'])}.\n{CODE}")
        self.assertIn(line, context)
        self.assertEqual(cards["c1"]["chars"], len(line))
        self.assertEqual((cards["p2"]["pointer"], cards["p2"]["paths"], cards["p2"]["codeChars"]), (True, [], 0))
        self.assertTrue(cards["s3"]["stale"])
        self.assertNotIn("kind", cards["s3"])  # the server sent none
        self.assertNotIn("score", cards["s3"])
        self.assertEqual(self.prompt("next one", cards=[])["additionalContext"], gate.LEARN_LINE)
        self.assertEqual(self.events("prompt")[-1]["request"], 2)
        self.assertEqual(self.skips(), ["no_cards"])

    def test_the_prompt_text_never_reaches_the_file(self) -> None:
        self.prompt(SECRET_PROMPT)
        self.assertNotIn("zebra-quartz-7731", self.raw())
        self.assertNotIn("posts endpoint", self.raw())

    def test_skip_reasons_of_the_prompt_hook(self) -> None:
        with patch.object(gate, "fetch_bootstrap", return_value={}):
            answer = gate.prompt_hook({"sessionId": "s1", "cwd": str(self.repo), "prompt": "fix the login"})
        self.assertEqual(answer, {"additionalContext": gate.LEARN_LINE})
        self.assertEqual(self.skips(), ["server_unreachable"])
        self.assertEqual(self.events("inject")[-1]["parts"]["cards"], 0)
        gate.prompt_hook({"sessionId": "s1", "cwd": str(self.tmp / "nowhere"), "prompt": "fix it"})
        gate.prompt_hook({"sessionId": "s1", "cwd": str(self.repo), "prompt": "   "})
        gate.prompt_hook({"sessionId": "s1", "cwd": str(self.repo), "prompt": gate.LEARN_REASON + " x"})
        self.assertEqual(self.skips(), ["server_unreachable", "no_repo", "no_prompt", "learn_reminder"])
        self.assertNotIn("repo", self.events("skip")[1])

        def late(*args):
            gate.DEADLINE = -1.0  # the time ran out while the server was asked
            return {"memory": [{"id": "c9", "summary": "x", "text": "y"}]}

        with patch.object(gate, "DEADLINE", gate.DEADLINE), patch.object(gate, "fetch_bootstrap", side_effect=late):
            self.assertEqual(gate.prompt_hook({"sessionId": "s1", "cwd": str(self.repo), "prompt": "fix it"}),
                             {"additionalContext": gate.LEARN_LINE})
        self.assertEqual(self.skips()[-1], "late")
        self.assertEqual(self.events("inject")[-1]["cards"], [])

    def test_no_session_writes_nothing(self) -> None:
        gate.prompt_hook({"cwd": str(self.repo), "prompt": "fix it"})
        self.assertEqual(self.lines(), [])

    def test_trace_off_writes_nothing_and_the_answer_is_the_same(self) -> None:
        with patch.dict(os.environ, {"AI_ORCH_TRACE": "0"}):
            off = self.prompt(cards=[{"id": "c1", "summary": "A", "text": "B", "files": ["a.py"]}])
        self.assertFalse(self.trace_dir.exists())
        state = gate._load(gate.state_file("s1"))
        state["shown"] = []
        gate._save(gate.state_file("s1"), state)
        on = self.prompt(cards=[{"id": "c1", "summary": "A", "text": "B", "files": ["a.py"]}])
        self.assertEqual(off, on)

    def test_an_unwritable_trace_changes_nothing(self) -> None:
        blocker = self.tmp / "a-file"
        blocker.write_text("x")
        card = {"id": "c1", "summary": "A", "text": "B", "files": ["a.py"]}
        with patch.dict(os.environ, {"AI_ORCH_TRACE": "0"}):
            expected = self.prompt(cards=[card])
        for target in (blocker / "trace", blocker):  # below a file, and a file itself
            state = gate._load(gate.state_file("s1"))
            state["shown"] = []
            gate._save(gate.state_file("s1"), state)
            with patch.dict(os.environ, {"AI_ORCH_TRACE_DIR": str(target)}):
                self.assertEqual(self.prompt(cards=[card]), expected)
        self.assertEqual(blocker.read_text(), "x")

    def test_a_read_only_directory_changes_nothing(self) -> None:
        self.trace_dir.mkdir(mode=0o500)
        self.addCleanup(os.chmod, self.trace_dir, 0o700)
        if os.access(self.trace_dir, os.W_OK):
            self.skipTest("running with rights that ignore directory modes")
        with patch.dict(os.environ, {"AI_ORCH_TRACE": "0"}):
            expected = self.prompt(cards=[])
        self.assertEqual(self.prompt(cards=[]), expected)
        self.assertEqual(list(self.trace_dir.iterdir()), [])

    def test_a_hook_error_is_traced_by_its_kind_only(self) -> None:
        payload = json.dumps({"sessionId": "s1", "cwd": str(self.repo), "prompt": SECRET_PROMPT})
        with patch.object(gate, "prompt_hook", side_effect=ValueError(f"{self.repo} {SECRET_PROMPT}")), \
                patch("sys.stdin", io.StringIO(payload)), patch("sys.stdout", new_callable=io.StringIO) as out:
            self.assertEqual(gate.main([]), 0)
        self.assertEqual(out.getvalue(), "")
        error = self.events("error")[0]
        self.assertEqual((error["where"], error["message"], error["hook"]),
                         ("userPromptSubmitted", "ValueError", "userPromptSubmitted"))
        self.assertNotIn("zebra", self.raw())

    def test_the_client_is_written_only_when_named(self) -> None:
        with patch.dict(os.environ, {"AI_ORCH_TRACE_CLIENT": "vscode"}):
            self.prompt(cards=[])
        with patch.dict(os.environ, {"AI_ORCH_TRACE_CLIENT": "something-else"}):
            self.prompt(cards=[])
        clients = [line.get("client") for line in self.events("prompt")]
        self.assertEqual(clients, ["vscode", None])


class PostTraceTest(TraceMixin, flow.FlowTestCase):
    FAIL = flow.LearningOnlyNewTest.FAIL
    card = flow.LearningOnlyNewTest.card
    post = flow.PostHookTest.post
    run_failure_then = flow.LearningOnlyNewTest.run_failure_then

    def setUp(self) -> None:
        super().setUp()
        self.use_trace_dir()
        self.step = gate.shared_hooks()  # the shared step module, its lookups faked as in PostHookTest
        self.assertIsNotNone(self.step)
        for patcher in (patch.object(self.step, "fetch", return_value=("empty", [])),
                        patch.object(self.step.session, "state_path",
                                     side_effect=lambda sid, root: self.tmp / f"shared-{sid}.json"),
                        patch.object(self.step.tempfile, "gettempdir", return_value=str(self.tmp))):
            patcher.start()
            self.addCleanup(patcher.stop)

    def test_tool_events_with_verify_outcome_and_a_fix_from_memory(self) -> None:
        self.run_failure_then(self.card("Run the suite with `python tests.py`."),
                              "cd /r && python tests.py 2>&1 | tail -30")
        gate.post_hook({"sessionId": "s1", "cwd": str(self.repo), "toolName": "view", "toolArgs": {"path": "a.py"},
                        "toolResult": {"textResultForLlm": "TOPSECRET-OUTPUT"}})
        self.post("echo TOPSECRET-CMD", "TOPSECRET-OUTPUT")
        tools = self.events("tool")
        self.assertEqual([(t["toolName"], t["verify"], t["outcome"], t["fixedFromMemory"]) for t in tools],
                         [("bash", True, "failed", False), ("bash", True, "passed", True),
                          ("view", False, "other", False), ("bash", False, "other", False)])
        for line in tools:
            self.assertTrue(COMMON <= set(line))
            self.assertEqual((line["hook"], line["repo"]), ("postToolUse", str(self.repo.resolve())))
        self.assertEqual(self.skips(), ["no_cards", "not_shell"])
        for secret in ("TOPSECRET", "pytest", "tests.py 2>&1"):
            self.assertNotIn(secret, self.raw())


class StopTraceTest(TraceMixin, flow.FlowTestCase):
    own_fix = flow.StopHookTest.own_fix

    def setUp(self) -> None:
        super().setUp()
        self.use_trace_dir()
        self.result: dict | None = flow.ACCEPTED
        patcher = patch.object(gate, "capture", side_effect=lambda root, run_id, raw: self.result)
        patcher.start()
        self.addCleanup(patcher.stop)

    def test_learned_and_stop_events(self) -> None:
        self.prompt()
        (self.repo / "a.py").write_text("x = 2\n")
        self.result = dict(flow.ACCEPTED, updated=0, rejected=1, conflicts=0)
        self.assertEqual(self.stop(answer=flow.LEARNED), {})
        learned = self.events("learned")[0]
        self.assertEqual((learned["candidates"], learned["saved"], learned["rejected"]), (1, 1, 1))
        self.assertNotIn("cardIds", learned)  # the receipt has none
        stop = self.events("stop")[-1]
        self.assertEqual((stop["decision"], stop["reason"], stop["stopHookActive"], stop["hook"]),
                         ("allow", "none", False, "agentStop"))
        self.assertNotIn("Added the endpoint", self.raw())  # the answer text is not traced

    def test_a_block_and_then_max_blocks(self) -> None:
        self.prompt(cards=[])
        self.own_fix()
        self.assertEqual(self.stop()["decision"], "block")
        self.assertEqual(self.stop(active=True), {})
        self.assertEqual([(s["decision"], s["reason"], s["stopHookActive"]) for s in self.events("stop")],
                         [("block", "learn_request", False), ("allow", "max_blocks", True)])

    def test_a_failed_capture_saves_nothing_and_rejects_nothing(self) -> None:
        self.prompt()
        self.result = None
        self.stop(answer=flow.LEARNED)
        learned = self.events("learned")[0]
        self.assertEqual((learned["candidates"], learned["saved"], learned["rejected"]), (1, 0, 0))


class EncodingAndFilesTest(TraceMixin, unittest.TestCase):
    def setUp(self) -> None:
        self.use_trace_dir()

    def test_an_oversized_line_is_cut_to_16_kib_and_keeps_its_common_fields(self) -> None:
        cards = [{"id": f"id-{i}", "summary": "s" * 160, "pointer": False, "stale": False,
                  "paths": [f"src/{'deep/' * 40}file{j}.py" for j in range(30)], "codeChars": 0, "chars": 10}
                 for i in range(40)]
        record = {"v": 1, "ts": "t", "sessionId": "s", "hook": "userPromptSubmitted", "event": "inject",
                  "parts": {"profile": False, "cards": 40, "pointers": 0, "learnLine": True}, "cards": cards}
        line = copilot_trace.encode(record)
        self.assertLessEqual(len(line), copilot_trace.MAX_LINE)
        decoded = json.loads(line)
        self.assertEqual(decoded["parts"]["cards"], 40)
        self.assertTrue(0 < len(decoded["cards"]) < 40)
        self.assertTrue(all(len(c["paths"]) <= 5 for c in decoded["cards"]))
        huge = copilot_trace.encode({"v": 1, "ts": "t", "sessionId": "s", "hook": "h", "event": "error",
                                     "where": "w", "message": "é" * 40000})
        self.assertLessEqual(len(huge), copilot_trace.MAX_LINE)
        self.assertEqual(json.loads(huge)["event"], "error")
        self.assertTrue(line.endswith(b"\n") and huge.endswith(b"\n"))

    def test_modes_and_location(self) -> None:
        copilot_trace.Hook("agentStop", "s").emit("stop", decision="allow", reason="none", stopHookActive=False)
        files = list(self.trace_dir.glob("*.jsonl"))
        self.assertEqual(len(files), 1)
        self.assertRegex(files[0].name, r"^\d{4}-\d{2}-\d{2}\.jsonl$")
        self.assertEqual(files[0].name[:10], dt.datetime.now(dt.timezone.utc).strftime("%Y-%m-%d"))
        self.assertEqual(stat.S_IMODE(self.trace_dir.stat().st_mode), 0o700)
        self.assertEqual(stat.S_IMODE(files[0].stat().st_mode), 0o600)
        with patch.dict(os.environ, {"AI_ORCH_TRACE_DIR": "", "COPILOT_HOME": "/c/home"}):
            self.assertEqual(copilot_trace.directory(), Path("/c/home/ai-orch/trace"))
        with patch.dict(os.environ, {"AI_ORCH_TRACE_DIR": "", "COPILOT_HOME": ""}):
            self.assertEqual(copilot_trace.directory(), Path.home() / ".copilot" / "ai-orch" / "trace")

    def test_files_older_than_30_days_go_when_a_new_day_starts(self) -> None:
        self.trace_dir.mkdir()
        today = dt.datetime.now(dt.timezone.utc).date()
        old = self.trace_dir / f"{today - dt.timedelta(days=31)}.jsonl"
        kept = self.trace_dir / f"{today - dt.timedelta(days=30)}.jsonl"
        other = self.trace_dir / "notes.txt"
        for path in (old, kept, other):
            path.write_text("{}\n")
        copilot_trace.Hook("agentStop", "s").emit("stop", decision="allow", reason="none", stopHookActive=False)
        self.assertFalse(old.exists())
        self.assertTrue(kept.exists() and other.exists())

    def test_one_write_per_line_on_an_append_descriptor(self) -> None:
        writes: list[tuple[int, bytes]] = []
        real_open, real_write = os.open, os.write
        flags: list[int] = []

        def spy_open(path, flag, mode=0o777):
            flags.append(flag)
            return real_open(path, flag, mode)

        def spy_write(fd, data):
            writes.append((fd, data))
            return real_write(fd, data)

        with patch.object(copilot_trace.os, "open", side_effect=spy_open), \
                patch.object(copilot_trace.os, "write", side_effect=spy_write):
            copilot_trace.Hook("postToolUse", "s").emit("tool", toolName="bash", verify=False, outcome="other",
                                                         fixedFromMemory=False)
        self.assertEqual(len(writes), 1)
        self.assertTrue(writes[0][1].endswith(b"\n") and writes[0][1].count(b"\n") == 1)
        self.assertTrue(flags[0] & os.O_APPEND)


if __name__ == "__main__":
    unittest.main()
