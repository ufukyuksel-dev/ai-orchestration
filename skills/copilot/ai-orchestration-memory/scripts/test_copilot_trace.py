#!/usr/bin/env python3
"""The Copilot hooks' local trace (copilot_trace, contract v1): every event type is written with the common fields,
no prompt, command or output text reaches the file, `AI_ORCH_TRACE=0` turns it off, a trace that cannot be written
changes nothing in the hooks' answers, lines stay within 16 KiB and old day files are removed."""

from __future__ import annotations

import trace_isolation  # noqa: F401 - before anything runs a hook: the trace goes to a throw-away directory

import datetime as dt
import hashlib
import io
import json
import os
import stat
import tempfile
import time
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
        line = (f"- [m1] Memory card: Token auth for API routes. Use token_auth.login_required. Files: "
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

    def test_one_call_id_per_hook_run(self) -> None:
        self.prompt(cards=[{"id": "c1", "summary": "A", "text": "B"}])
        self.prompt("next one", cards=[])
        lines = self.lines()
        for line in lines:
            self.assertRegex(line["call"], r"^[0-9a-f]{12}$")
        first = [line["call"] for line in lines[:2]]
        second = [line["call"] for line in lines[2:]]
        self.assertEqual(len(set(first)), 1)
        self.assertEqual(len(set(second)), 1)
        self.assertNotEqual(first[0], second[0])
        durations = [line["durationMs"] for line in lines[2:]]
        self.assertEqual(durations, sorted(durations))  # since the call began: cumulative

    def test_a_hook_run_through_main_and_its_error_share_one_call(self) -> None:
        def failing(payload):
            gate.tracer("userPromptSubmitted", "s1").emit("skip", reason="no_cards")
            raise ValueError("x")

        payload = json.dumps({"sessionId": "s1", "cwd": str(self.repo), "prompt": "fix it"})
        with patch.object(gate, "prompt_hook", side_effect=failing), patch("sys.stdin", io.StringIO(payload)), \
                patch("sys.stdout", new_callable=io.StringIO):
            gate.main([])
        self.assertEqual([line["event"] for line in self.lines()], ["skip", "error"])
        self.assertEqual(len({line["call"] for line in self.lines()}), 1)

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
        for secret in ("TOPSECRET", "-m pytest", "tests.py 2>&1"):
            self.assertNotIn(secret, self.raw())


    def test_a_step_delivery_is_traced_as_an_inject_after_its_tool_line(self) -> None:
        self.prompt(cards=[])
        card = self.card("Run the suite with `python tests.py`.")
        with patch.object(self.step, "fetch", return_value=("found", [card])):
            answer = self.post("python -m pytest -q", self.FAIL)
        text = answer["additionalContext"]
        lines = [line for line in self.lines() if line["hook"] == "postToolUse"]
        self.assertEqual([line["event"] for line in lines], ["tool", "inject"])  # the cost counts the calls after it
        tool, inject = lines
        self.assertEqual(tool["call"], inject["call"])
        self.assertLessEqual(tool["durationMs"], inject["durationMs"])
        self.assertEqual(inject["injected"], {"chars": len(text), "tokensEst": -(-len(text) * 2 // 7)})
        self.assertEqual(inject["parts"], {"profile": False, "cards": 1, "pointers": 0, "learnLine": False})
        entry = inject["cards"][0]
        self.assertEqual((entry["id"], entry["summary"], entry["paths"], entry["pointer"], entry["codeChars"]),
                         ("t9", "No pytest here", ["tests.py"], False, 0))
        line = "- Memory card: No pytest here. Run the suite with `python tests.py`. Files: tests.py."
        self.assertIn(line, text)
        self.assertEqual(entry["chars"], len(line))
        self.post("echo hi", "hi")  # nothing delivered: no inject
        self.assertEqual(len(self.events("inject")), 2)  # the prompt's and the step's

    def test_a_step_pointer_is_traced_as_a_pointer(self) -> None:
        self.prompt(cards=[])
        with patch.object(self.step, "fetch", return_value=("found", [self.card("", pointer=True)])):
            self.post("python -m pytest -q", self.FAIL)
        inject = self.events("inject")[-1]
        self.assertEqual((inject["hook"], inject["parts"]["cards"], inject["parts"]["pointers"]),
                         ("postToolUse", 0, 1))
        self.assertEqual((inject["cards"][0]["pointer"], inject["cards"][0]["paths"]), (True, []))


class ToolFactsTraceTest(TraceMixin, flow.FlowTestCase):
    """`tool` lines carry `access`, repository-relative `paths` from the tool's path fields only, and `memoryOp` for
    `ai_orch memory` commands: no file content, pattern, query, command text or memory text reaches the trace."""
    ID = "3f2b8c1e-9a4d-4e6f-8b7a-1c2d3e4f5a6b"
    ID2 = "0d9e8f7a-6b5c-4d3e-a2b1-c0d9e8f7a6b5"

    def setUp(self) -> None:
        super().setUp()
        self.use_trace_dir()
        self.step = gate.shared_hooks()
        self.assertIsNotNone(self.step)
        for patcher in (patch.object(self.step, "fetch", return_value=("empty", [])),
                        patch.object(self.step.session, "state_path",
                                     side_effect=lambda sid, root: self.tmp / f"shared-{sid}.json"),
                        patch.object(self.step.tempfile, "gettempdir", return_value=str(self.tmp))):
            patcher.start()
            self.addCleanup(patcher.stop)
        self.prompt(cards=[])
        self.root = str(self.repo.resolve())

    def call(self, tool: str, args: object, output: str = "TOPSECRET-OUTPUT", cwd: str | None = None) -> dict:
        gate.post_hook({"sessionId": "s1", "cwd": cwd or str(self.repo), "toolName": tool, "toolArgs": args,
                        "toolResult": {"resultType": "success", "textResultForLlm": output}})
        return self.events("tool")[-1]

    def test_every_tool_kind_gets_its_access_and_paths(self) -> None:
        cases = [
            ("view", {"path": f"{self.root}/src/a.py", "view_range": [1, 9]}, "read", ["src/a.py"]),
            ("view", {"path": "src/b.py"}, "read", ["src/b.py"]),
            ("create", {"path": "new/c.py", "file_text": "TOPSECRET-CONTENT"}, "write", ["new/c.py"]),
            ("edit", {"path": "a.py", "old_str": "TOPSECRET-OLD", "new_str": "TOPSECRET-NEW"}, "write", ["a.py"]),
            ("str_replace", {"path": "a.py", "old_str": "TOPSECRET-OLD"}, "write", ["a.py"]),
            ("str_replace_editor", {"command": "view", "path": "a.py"}, "read", ["a.py"]),
            ("str_replace_editor", {"command": "str_replace", "path": "a.py", "old_str": "TOPSECRET"}, "write",
             ["a.py"]),
            ("rg", {"pattern": "TOPSECRET-PATTERN", "paths": [f"{self.root}/src"], "glob": "*.py", "-n": True},
             "search", ["src"]),
            ("rg", {"pattern": "TOPSECRET-PATTERN", "output_mode": "content"}, "search", None),
            ("grep", {"pattern": "TOPSECRET-PATTERN", "path": "lib"}, "search", ["lib"]),
            ("glob", {"pattern": "**/TOPSECRET*.py", "paths": [self.root]}, "search", None),
            ("apply_patch", "*** Begin Patch\n*** Update File: " + self.root + "/a.py\n@@\n-TOPSECRET-OLD\n"
             "+TOPSECRET-NEW\n*** Add File: docs/n.md\n+TOPSECRET-CONTENT\n*** End Patch", "write",
             ["a.py", "docs/n.md"]),
            ("bash", {"command": "cat src/TOPSECRET.py && grep TOPSECRET-PATTERN -r ."}, "run", None),
            ("read_file", {"filePath": f"{self.root}/x/y.ts"}, "read", ["x/y.ts"]),
            ("write_file", {"file": "x/z.ts", "content": "TOPSECRET-CONTENT"}, "write", ["x/z.ts"]),
            ("edit_file", {"path": "x/z.ts", "edits": "TOPSECRET-NEW"}, "write", ["x/z.ts"]),
            ("list_files", {"path": "x"}, "search", ["x"]),
            ("search", {"query": "TOPSECRET-PATTERN", "path": "x", "file": "TOPSECRET.ts"}, "search", ["x"]),
            ("run_command", {"command": "TOPSECRET-CMD x/z.ts"}, "run", None),
            ("skill", {"skill": "TOPSECRET-SKILL", "path": "a.py"}, "other", None),
            ("mcp__srv__tool", {"path": "a.py"}, "other", None),
        ]
        for tool, args, access, paths in cases:
            with self.subTest(tool=tool, args=args):
                line = self.call(tool, args)
                self.assertEqual((line["toolName"], line["access"], line.get("paths")), (tool, access, paths))
                self.assertNotIn("memoryOp", line)
        self.assertNotIn("TOPSECRET", self.raw())

    def test_tool_args_given_as_json_text_are_read(self) -> None:
        line = self.call("view", json.dumps({"path": "a.py"}))
        self.assertEqual((line["access"], line["paths"]), ("read", ["a.py"]))

    def test_paths_that_escape_the_repository_are_left_out(self) -> None:
        outside = str(self.tmp / "elsewhere.py")
        line = self.call("view", {"paths": ["../repo/a.py", "src/../../x.py", "/etc/passwd", outside, "a\x00b.py",
                                            "src/*.py", "~/notes.md", "", 7, None, self.root, self.root + "/",
                                            f"{self.root}-other/a.py", "ok.py"]})
        self.assertEqual(line["paths"], ["ok.py"])
        nested = self.repo / "sub"
        nested.mkdir()
        line = self.call("view", {"path": "../a.py"}, cwd=str(nested))
        self.assertNotIn("paths", line)
        line = self.call("view", {"path": "b.py"}, cwd=str(nested))
        self.assertEqual(line["paths"], ["sub/b.py"])

    def test_no_repository_no_paths(self) -> None:
        line = self.call("view", {"path": "/x/a.py"}, cwd=str(self.tmp))  # cwd outside the request's repository
        self.assertEqual(line["access"], "read")
        self.assertNotIn("paths", line)

    def test_path_limits(self) -> None:
        many = [f"d/f{i}.py" for i in range(30)] + ["d/f0.py"]
        line = self.call("view", {"paths": many})
        self.assertEqual(line["paths"], [f"d/f{i}.py" for i in range(20)])
        long_ok, too_long = "d/" + "a" * 1000, "d/" + "a" * 1100
        line = self.call("view", {"paths": [long_ok, too_long]})
        self.assertEqual(line["paths"], [long_ok])

    def test_an_oversized_tool_line_drops_whole_paths(self) -> None:
        paths = ["d/" + str(i) * 1000 for i in range(10)] + ["e/" + "x" * 1000 for _ in range(1)]
        paths = [p + str(i) for i, p in enumerate(paths)] + [f"g/{i}" + "y" * 1000 for i in range(9)]
        line = self.call("view", {"paths": paths})
        self.assertLess(len(json.dumps(line)), copilot_trace.MAX_LINE)
        self.assertTrue(set(line["paths"]) < set(paths))  # whole paths only, none cut short
        self.assertEqual(line["paths"], paths[:len(line["paths"])])

    def test_memory_ops_keep_only_the_sub_command_and_card_ids(self) -> None:
        cases = [
            (f"ai_orch memory archive --memory-id {self.ID} --reason 'TOPSECRET wrong card'",
             {"op": "archive", "cardIds": [self.ID]}),
            (f"cd {self.root} && ai_orch memory update --memory-id={self.ID.upper()} --file TOPSECRET.json",
             {"op": "update", "cardIds": [self.ID]}),
            ('ai_orch memory search --query "TOPSECRET query" --top-k 3', {"op": "search"}),
            ("ai_orch memory learn --summary 'TOPSECRET summary' --content TOPSECRET", {"op": "learn"}),
            (f"AI_ORCH_X=1 python3 /opt/s/ai_orch.py memory update --memory-id {self.ID} --memory-id {self.ID2}",
             {"op": "update", "cardIds": [self.ID, self.ID2]}),
            ("ai_orch memory archive --memory-id TOPSECRET-not-a-uuid --reason x", {"op": "archive"}),
            ("ai_orch memory archive --memory-id '$(cat TOPSECRET)'", {"op": "archive"}),
            (f"ai_orch memory search --query a; ai_orch memory archive --memory-id {self.ID}", {"op": "search"}),
            (f"ai_orch memory archive --reason x | ai_orch memory update --memory-id {self.ID}", {"op": "archive"}),
            (f"ai_orch memory get --memory-id {self.ID} && ai_orch memory archive --memory-id {self.ID}", None),
            (f"echo ai_orch memory archive --memory-id {self.ID}", None),
            (f"git log -1 && ai_orch memory archive --memory-id {self.ID} 2>&1 | tail -5",
             {"op": "archive", "cardIds": [self.ID]}),
            ("ai_orch memory archive --reason 'TOPSECRET unbalanced", None),
            ("ai_orch rule show TOPSECRET", None),
        ]
        for command, expected in cases:
            with self.subTest(command=command):
                line = self.call("bash", {"command": command, "description": "TOPSECRET-DESC"})
                self.assertEqual((line["access"], line.get("memoryOp")), ("run", expected))
                self.assertNotIn("paths", line)
        self.assertNotIn("TOPSECRET", self.raw())
        self.assertNotIn("--reason", self.raw())

    def test_memory_op_only_for_shell_commands(self) -> None:
        line = self.call("view", {"command": f"ai_orch memory archive --memory-id {self.ID}", "path": "a.py"})
        self.assertNotIn("memoryOp", line)


class BashReadTraceTest(ToolFactsTraceTest):
    """A shell command made only of simple reads (cat, head, tail, sed -n, grep/rg, wc, ls, nl, awk), alone or joined
    by `|` / `&&`, names the repository files and folders it reads: only arguments that are not options, not the
    pattern / expression, and that exist in the repository. Anything that writes, redirects, runs a sub-shell or
    expands a variable stays `run` with no paths. The command text itself never reaches the trace."""

    def setUp(self) -> None:
        super().setUp()
        (self.repo / "src").mkdir()
        (self.repo / "src" / "a.py").write_text("TOPSECRET-CONTENT\n")
        (self.repo / "src" / "b.py").write_text("y = 2\n")

    def test_simple_reads_name_their_files(self) -> None:
        cases = [
            ("cat src/a.py", "read", ["src/a.py"]),
            (f"cat {self.root}/src/a.py", "read", ["src/a.py"]),
            ("sed -n '10,40p' src/a.py", "read", ["src/a.py"]),
            ("sed -n '$p' src/a.py", "read", ["src/a.py"]),
            ("head -n 50 src/a.py", "read", ["src/a.py"]),
            ("tail -20 src/b.py", "read", ["src/b.py"]),
            ("grep -n TOPSECRET-PATTERN src/a.py", "read", ["src/a.py"]),
            ("grep -n src/b.py src/a.py", "read", ["src/a.py"]),  # the first operand is the pattern, not a file
            ("grep -A 3 -n TOPSECRET-PATTERN src/a.py", "read", ["src/a.py"]),
            ("grep -e TOPSECRET-PATTERN src/a.py src/b.py", "read", ["src/a.py", "src/b.py"]),
            ("rg -n TOPSECRET-PATTERN src", "read", ["src"]),
            ("wc -l src/a.py src/b.py", "read", ["src/a.py", "src/b.py"]),
            ("nl -ba src/a.py | head -40", "read", ["src/a.py"]),
            ("awk '{print $1}' src/a.py", "read", ["src/a.py"]),
            ("cat src/a.py && sed -n '1,5p' src/b.py", "read", ["src/a.py", "src/b.py"]),
            ("cat src/missing.py src/a.py", "read", ["src/a.py"]),  # only what exists
            ("ls src", "search", ["src"]),
            ("ls -la src && cat src/b.py", "read", ["src", "src/b.py"]),
        ]
        for command, access, paths in cases:
            with self.subTest(command=command):
                line = self.call("bash", {"command": command, "description": "TOPSECRET-DESCRIPTION"})
                self.assertEqual((line["access"], line.get("paths")), (access, paths))
        self.assertNotIn("TOPSECRET", self.raw())
        self.assertNotIn("sed -n", self.raw())

    def test_anything_else_stays_run_without_paths(self) -> None:
        commands = [
            "cat src/a.py > out.txt", "cat src/a.py >> out.txt", "cat src/a.py 2>/dev/null", "cat < src/a.py",
            "cat src/a.py | tee out.txt", "sed -i 's/a/b/' src/a.py", "sed -n 'w out' src/a.py",
            "sed 's/a/b/' src/a.py", "cat $(echo src/a.py)", 'cat "$F"', "cat `echo src/a.py`",
            "cat ${HOME}/src/a.py", "cat $F", "(cat src/a.py)", "cat src/a.py; rm -rf x", "cat src/a.py || true",
            "cat src/a.py &", "python x.py src/a.py", "FOO=1 cat src/a.py", "cd src && cat a.py",
            "awk '{print > \"o\"}' src/a.py", "awk -f p.awk src/a.py", "awk '{system(\"x\")}' src/a.py",
            "cat 'src/a.py", "cat ../outside.py", "cat /etc/passwd", "grep -rn TOPSECRET-PATTERN .",
            "find src -name a.py", "git show HEAD:src/a.py", "rm src/a.py",
        ]
        for command in commands:
            with self.subTest(command=command):
                line = self.call("bash", {"command": command})
                self.assertEqual((line["access"], line.get("paths")), ("run", None))
        self.assertNotIn("TOPSECRET", self.raw())

    def test_a_memory_command_is_still_a_memory_op(self) -> None:
        line = self.call("bash", {"command": f"ai_orch memory archive --memory-id {self.ID}"})
        self.assertEqual((line["access"], line["memoryOp"]), ("run", {"op": "archive", "cardIds": [self.ID]}))


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

        def spy_open(path, flag, mode=0o777, **kwargs):
            flags.append(flag)
            return real_open(path, flag, mode, **kwargs)

        def spy_write(fd, data):
            writes.append((fd, data))
            return real_write(fd, data)

        with patch.object(copilot_trace.os, "open", side_effect=spy_open), \
                patch.object(copilot_trace.os, "write", side_effect=spy_write):
            copilot_trace.Hook("postToolUse", "s").emit("tool", toolName="bash", verify=False, outcome="other",
                                                         fixedFromMemory=False)
        self.assertEqual(len(writes), 1)
        self.assertTrue(writes[0][1].endswith(b"\n") and writes[0][1].count(b"\n") == 1)
        appends = [flag for flag in flags if flag & os.O_APPEND]
        self.assertEqual(len(appends), 1)
        self.assertTrue(appends[0] & os.O_NOFOLLOW and appends[0] & os.O_CREAT)


class SafeTargetTest(TraceMixin, unittest.TestCase):
    """The trace is only written into a directory and a file the user alone owns and can read (0700 / 0600), never
    through a symbolic link; a loose mode the user owns is tightened first, and dropped when it cannot be."""

    def setUp(self) -> None:
        self.use_trace_dir()
        self.trace_dir.parent.chmod(0o700)

    def emit(self) -> None:
        copilot_trace.Hook("agentStop", "s").emit("stop", decision="allow", reason="none", stopHookActive=False)

    def today(self, folder: Path | None = None) -> Path:
        return (folder or self.trace_dir) / f"{dt.datetime.now(dt.timezone.utc):%Y-%m-%d}.jsonl"

    def test_a_loose_existing_directory_and_file_are_tightened(self) -> None:
        self.trace_dir.mkdir(mode=0o755)
        self.trace_dir.chmod(0o755)
        self.today().write_text("")
        self.today().chmod(0o644)
        self.emit()
        self.assertEqual(stat.S_IMODE(self.trace_dir.stat().st_mode), 0o700)
        self.assertEqual(stat.S_IMODE(self.today().stat().st_mode), 0o600)
        self.assertEqual(len(self.lines()), 1)

    def test_a_loose_directory_that_cannot_be_tightened_is_not_written(self) -> None:
        self.trace_dir.mkdir()
        self.trace_dir.chmod(0o755)
        with patch.object(copilot_trace.os, "fchmod", side_effect=PermissionError), \
                patch.object(copilot_trace.os, "chmod", side_effect=PermissionError):
            self.emit()
        self.assertEqual(list(self.trace_dir.iterdir()), [])

    def test_a_loose_file_that_cannot_be_tightened_is_not_written(self) -> None:
        self.trace_dir.mkdir(mode=0o700)
        self.today().write_text("")
        self.today().chmod(0o644)
        with patch.object(copilot_trace.os, "fchmod", side_effect=PermissionError), \
                patch.object(copilot_trace.os, "chmod", side_effect=PermissionError):
            self.emit()
        self.assertEqual(self.today().read_text(), "")

    def test_a_directory_owned_by_someone_else_is_not_written(self) -> None:
        self.trace_dir.mkdir(mode=0o700)
        with patch.object(copilot_trace.os, "getuid", return_value=os.getuid() + 1):
            self.emit()
        self.assertEqual(list(self.trace_dir.iterdir()), [])

    def test_a_file_owned_by_someone_else_is_not_written(self) -> None:
        self.trace_dir.mkdir(mode=0o700)
        self.today().write_text("")
        self.today().chmod(0o600)
        real = os.fstat

        def foreign(fd):
            result = real(fd)
            if stat.S_ISREG(result.st_mode):  # the day file only: its directory stays the user's
                values = list(result)
                values[stat.ST_UID] = os.getuid() + 1
                return os.stat_result(values)
            return result

        with patch.object(copilot_trace.os, "fstat", side_effect=foreign):
            self.emit()
        self.assertEqual(self.today().read_text(), "")

    def test_a_symlinked_directory_is_not_followed(self) -> None:
        elsewhere = self.trace_dir.parent / "elsewhere"
        elsewhere.mkdir(mode=0o700)
        self.trace_dir.symlink_to(elsewhere, target_is_directory=True)
        self.emit()
        self.assertEqual(list(elsewhere.iterdir()), [])

    def test_a_symlinked_file_is_not_followed(self) -> None:
        self.trace_dir.mkdir(mode=0o700)
        target = self.trace_dir.parent / "target.txt"
        target.write_text("keep")
        self.today().symlink_to(target)
        self.emit()
        self.assertEqual(target.read_text(), "keep")

    def test_the_default_location_checks_ai_orch_too(self) -> None:
        home = self.trace_dir.parent / "copilot-home"
        home.mkdir()
        with patch.dict(os.environ, {"AI_ORCH_TRACE_DIR": "", "COPILOT_HOME": str(home)}):
            elsewhere = self.trace_dir.parent / "elsewhere"
            (elsewhere / "trace").mkdir(parents=True, mode=0o700)
            (home / "ai-orch").symlink_to(elsewhere, target_is_directory=True)
            self.emit()
            self.assertEqual(list((elsewhere / "trace").iterdir()), [])
            (home / "ai-orch").unlink()
            (home / "ai-orch").mkdir()
            (home / "ai-orch").chmod(0o755)
            self.emit()
            self.assertEqual(stat.S_IMODE((home / "ai-orch").stat().st_mode), 0o700)
            self.assertEqual(stat.S_IMODE((home / "ai-orch" / "trace").stat().st_mode), 0o700)
            self.assertTrue(self.today(home / "ai-orch" / "trace").is_file())


class DeferredWriteTest(TraceMixin, flow.FlowTestCase):
    """Through `main` the trace is written after the hook's answer is on stdout, within a short time bound: a slow
    disk can neither delay the answer nor eat the prompt hook's time for the cards."""

    def setUp(self) -> None:
        super().setUp()
        self.use_trace_dir()

    def run_main(self, prompt: str = "add a posts endpoint") -> tuple[str, float]:
        payload = json.dumps({"sessionId": "s1", "cwd": str(self.repo), "prompt": prompt})
        started = time.monotonic()
        with patch.object(gate, "fetch_bootstrap", return_value={"memory": [flow.CARD]}), \
                patch("sys.stdin", io.StringIO(payload)), patch("sys.stdout", new_callable=io.StringIO) as out:
            self.assertEqual(gate.main([]), 0)
        return out.getvalue(), time.monotonic() - started

    def test_a_slow_writer_changes_neither_the_answer_nor_the_cards(self) -> None:
        with patch.object(gate, "DEADLINE", 0.5):
            with patch.dict(os.environ, {"AI_ORCH_TRACE": "0"}):
                expected, _ = self.run_main()
            self.assertIn("Token auth for API routes", expected)
            gate._save(gate.state_file("s1"), {})
            real_write = os.write
            order: list[str] = []

            def slow(fd, data):
                order.append("trace")
                time.sleep(0.6)  # longer than the prompt hook's whole budget
                return real_write(fd, data)

            with patch.object(copilot_trace.os, "write", side_effect=slow):
                answer, elapsed = self.run_main()
        self.assertEqual(answer, expected)
        self.assertLess(elapsed, 1.2)  # the trace's own bound, not the writer's pace

    def test_the_answer_is_on_stdout_before_any_trace_write(self) -> None:
        order: list[str] = []
        real_write = os.write

        class Out(io.StringIO):
            def flush(self) -> None:
                order.append("flush")
                super().flush()

        def spy(fd, data):
            order.append("trace")
            return real_write(fd, data)

        payload = json.dumps({"sessionId": "s1", "cwd": str(self.repo), "prompt": "add a posts endpoint"})
        with patch.object(gate, "fetch_bootstrap", return_value={"memory": [flow.CARD]}), \
                patch("sys.stdin", io.StringIO(payload)), patch("sys.stdout", new=Out()) as out, \
                patch.object(copilot_trace.os, "write", side_effect=spy):
            gate.main([])
        self.assertIn("Token auth", out.getvalue())
        self.assertEqual(order[0], "flush")
        self.assertIn("trace", order)
        self.assertEqual([line["event"] for line in self.lines()], ["prompt", "inject"])

    def test_a_slow_cleanup_does_not_hold_the_hook(self) -> None:
        self.trace_dir.mkdir(mode=0o700)
        (self.trace_dir / "2000-01-01.jsonl").write_text("{}\n")

        def slow(*args, **kwargs):
            time.sleep(1.5)

        with patch.object(copilot_trace, "cleanup", side_effect=slow):
            answer, elapsed = self.run_main()
        self.assertIn("Token auth", answer)
        self.assertLess(elapsed, 1.2)


if __name__ == "__main__":
    unittest.main()


def test_team_client_is_accepted(monkeypatch):
    """Copilot Team (VS Code extension) runs the same hooks with AI_ORCH_TRACE_CLIENT=team."""
    monkeypatch.setenv(copilot_trace.CLIENT_ENV, "team")
    assert copilot_trace.client() == "team"
    monkeypatch.setenv(copilot_trace.CLIENT_ENV, "nope")
    assert copilot_trace.client() is None
