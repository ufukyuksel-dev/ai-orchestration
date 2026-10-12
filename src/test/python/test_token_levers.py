"""The token-lever hooks: build profile, command rewrite (formatter + compact output), Stop check and auto-learning."""
import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

HOOKS = Path(__file__).resolve().parents[3] / "scripts/hooks"
sys.path.insert(0, str(HOOKS))
import ai_orch_command_hook as command_hook  # noqa: E402
import ai_orch_compact as compact  # noqa: E402
import ai_orch_profile as profile  # noqa: E402
import ai_orch_session_state as session  # noqa: E402
import ai_orch_stop as stop  # noqa: E402

POM = """<project><parent><artifactId>spring-boot-starter-parent</artifactId><version>4.0.3</version></parent>
<build><plugins><plugin><artifactId>spring-javaformat-maven-plugin</artifactId></plugin>
<plugin><artifactId>maven-checkstyle-plugin</artifactId><dependency>nohttp-checkstyle</dependency></plugin>
</plugins></build></project>"""


def git_repo(files: dict) -> str:
    root = tempfile.mkdtemp()
    for name, text in files.items():
        path = Path(root) / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)
    for args in (["init", "-q"], ["add", "-A"], ["-c", "user.email=t@t", "-c", "user.name=t", "commit", "-qm", "init"]):
        subprocess.run(["git", "-C", root, *args], check=True, capture_output=True)
    return root


class ProfileTest(unittest.TestCase):
    def test_maven_profile_skips_docker_tests_and_knows_formatter_and_gate(self):
        root = git_repo({"pom.xml": POM, "mvnw": "", "src/test/java/a/DbTests.java": "import org.testcontainers.x;",
                         "src/test/java/a/PlainTests.java": "class PlainTests {}"})
        prof = profile.detect(root)
        self.assertEqual("./mvnw -q test -Dtest='!DbTests' -Dsurefire.failIfNoSpecifiedTests=false", prof["test"])
        self.assertEqual("spring-javaformat:apply", prof["format_goal"])
        self.assertEqual("./mvnw -q -DskipTests validate", prof["quality"])
        text = "\n".join(profile.render(prof))
        self.assertIn("Spring Boot 4.0.3", text)
        self.assertIn("skips DbTests", text)
        self.assertIn("nohttp", text)

    def test_many_docker_tests_are_not_listed(self):
        files = {"pom.xml": "<project/>"}
        files.update({f"src/test/java/a/Db{i}Test.java": "@Testcontainers" for i in range(10)})
        prof = profile.detect(git_repo(files))
        self.assertEqual("mvn -q test", prof["test"])
        self.assertIn("10 test classes need Docker", "\n".join(profile.render(prof)))

    def test_other_ecosystems_and_unknown(self):
        self.assertEqual("go test ./...", profile.detect(git_repo({"go.mod": "module x"}))["test"])
        node = profile.detect(git_repo({"package.json": json.dumps({"scripts": {"test": "vitest", "lint": "eslint ."}}),
                                        "pnpm-lock.yaml": ""}))
        self.assertEqual(("pnpm test", "pnpm lint"), (node["test"], node["quality"]))
        self.assertIn("ruff", profile.detect(git_repo({"pyproject.toml": "[tool.ruff]"}))["format"])
        self.assertEqual({}, profile.detect(tempfile.mkdtemp()))


class CommandHookTest(unittest.TestCase):
    def setUp(self):
        self.root = git_repo({"pom.xml": POM, "mvnw": "", "src/A.java": "class A {}"})
        self.state = Path(tempfile.mkdtemp()) / "state.json"

    def test_non_check_commands_pass_through(self):
        self.assertIsNone(command_hook.rewrite("grep -rn foo src", self.root, self.state, {"all"}))

    def test_formatter_goal_joins_the_maven_call(self):
        dirty = command_hook.rewrite("cd x && ./mvnw -q test", self.root, self.state, {"all"})
        self.assertIn("cd x && ./mvnw spring-javaformat:apply -q test", dirty)
        self.assertIn("ai_orch_compact.py", dirty)
        plain = command_hook.rewrite("./mvnw -q test", self.root, self.state, {"format"})
        self.assertNotIn("ai_orch_compact.py", plain)

    def test_rewritten_command_keeps_exit_code_compacts_output_and_records_a_passing_check(self):
        mvnw = Path(self.root, "mvnw")
        mvnw.write_text("#!/bin/bash\nfor i in $(seq 1 200); do echo \"[INFO] line $i\"; done\n"  # tests must show
                        "echo '[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0'\nexit $FAKE_RC\n")
        mvnw.chmod(0o755)
        for code in (0, 3):
            cmd = command_hook.rewrite("./mvnw -q test", self.root, self.state, {"compact"})
            done = subprocess.run(["bash", "-c", cmd], capture_output=True, text=True, cwd=self.root,
                                  env=dict(os.environ, FAKE_RC=str(code)))
            self.assertEqual(code, done.returncode)
            self.assertIn("output lines compacted", done.stdout)
            self.assertLess(len(done.stdout.splitlines()), 20)
        verified = json.loads(self.state.read_text())["verified"]
        self.assertEqual({"compile", "quality", "tests"}, set(verified))
        self.assertEqual({session.fingerprint(self.root)}, set(verified.values()))

    def test_exit_heredoc_and_cd_inside_the_command_keep_output_status_and_record(self):
        Path(self.root, "sub").mkdir()
        Path(self.root, "sub", "test_one.py").write_text(  # a run of zero tests would record no coverage
            "import unittest\nclass T(unittest.TestCase):\n    def test_ok(self):\n        pass\n")
        for code in (0, 4):
            state = Path(tempfile.mkdtemp()) / "state.json"
            original = (f"cd sub && cat > note.txt <<'EOF'\nhello\nEOF\npwd; cat note.txt; "
                        f"for i in $(seq 1 40); do echo line $i; done; python3 -m unittest; exit {code}")
            cmd = command_hook.rewrite(original, self.root, state, {"compact"})
            done = subprocess.run(["bash", "-c", cmd + "\necho after-the-check"], capture_output=True, text=True,
                                  cwd=self.root)
            self.assertIn("after-the-check", done.stdout)  # the agent's `exit` ended only its own command
            self.assertIn("output lines compacted", done.stdout)
            self.assertTrue(Path(self.root, "sub/note.txt").exists())  # the cd held for the rest of the command
            self.assertEqual(code == 0, state.exists() and "tests" in json.loads(state.read_text()).get("verified", {}))

    def test_a_failure_hidden_by_a_pipe_is_reported_and_not_recorded_as_verified(self):
        mvnw = Path(self.root, "mvnw")
        mvnw.write_text("#!/bin/bash\nfor i in $(seq 1 100); do echo \"[INFO] line $i\"; done\n"
                        "echo '[ERROR] ATest.x:5 expected:<1> but was:<2>'\necho '[ERROR] Tests run: 3, Failures: 1'\n"
                        "echo 'BUILD FAILURE'\nexit 1\n")
        mvnw.chmod(0o755)
        cmd = command_hook.rewrite("./mvnw -q test 2>&1 | tail -30", self.root, self.state, {"compact"})
        done = subprocess.run(["bash", "-c", cmd], capture_output=True, text=True, cwd=self.root)
        self.assertEqual(0, done.returncode)  # the agent's own pipeline exit code is kept
        self.assertIn("expected:<1> but was:<2>", done.stdout)
        self.assertIn("output reports failures", done.stdout)
        self.assertFalse(self.state.exists() and json.loads(self.state.read_text()).get("verified"))

    def test_hook_output_shape_for_claude_and_codex(self):
        event = {"tool_input": {"command": "./mvnw -q test"}, "cwd": self.root, "session_id": "s"}
        for argv, decision in (([], None), (["--client", "codex"], "allow")):
            with mock.patch.object(sys, "stdin", __import__("io").StringIO(json.dumps(event))), \
                    mock.patch("sys.stdout", new_callable=__import__("io").StringIO) as out:
                command_hook.main(argv)
            data = json.loads(out.getvalue())["hookSpecificOutput"]
            self.assertIn("./mvnw", data["updatedInput"]["command"])
            self.assertEqual(decision, data.get("permissionDecision"))


class CompactTest(unittest.TestCase):
    def test_short_output_is_unchanged_and_failures_keep_the_error_lines(self):
        self.assertEqual("ok", compact.compact("ok", 0))
        log = "\n".join(["[INFO] noise"] * 300 + [
            "[ERROR] /w/src/A.java:[3,8] package org.x.y does not exist",
            "[ERROR] To see the full stack trace of the errors, re-run Maven with the -e switch."])
        out = compact.compact(log, 1)
        self.assertIn("package org.x.y does not exist", out)
        self.assertNotIn("-e switch", out)
        self.assertNotIn("noise", out)


def transcript(steps: list) -> str:
    """A Claude Code transcript: the user's prompt, then (tool_use, is_error, output) steps."""
    lines = [{"type": "user", "message": {"content": "add an endpoint"}}]
    for i, (name, args, error, output) in enumerate(steps):
        lines.append({"type": "assistant", "message": {"content": [
            {"type": "tool_use", "id": f"t{i}", "name": name, "input": args}]}})
        lines.append({"type": "user", "message": {"content": [
            {"type": "tool_result", "tool_use_id": f"t{i}", "is_error": error, "content": output}]}})
    path = Path(tempfile.mkdtemp()) / "t.jsonl"
    path.write_text("\n".join(json.dumps(x) for x in lines))
    return str(path)


COMPILE_ERROR = ("[ERROR] COMPILATION ERROR :\n[ERROR] /r/src/test/A.java:[14,63] package "
                 "org.springframework.boot.test.autoconfigure.web.servlet does not exist\n[INFO] BUILD FAILURE")


class AutoLearnTest(unittest.TestCase):
    def test_compile_pitfall_with_the_edit_that_fixed_it(self):
        steps = stop.steps_since_last_prompt(transcript([
            ("Bash", {"command": "./mvnw -q test"}, False, COMPILE_ERROR),
            ("Edit", {"file_path": "/r/src/test/A.java", "old_string": "boot.test.autoconfigure.web.servlet",
                      "new_string": "boot.webmvc.test.autoconfigure"}, False, "ok"),
            ("Bash", {"command": "./mvnw -q test"}, False, "[INFO] BUILD SUCCESS")]))
        found = stop.candidates(steps, "/r", {})
        self.assertEqual(1, len(found))
        self.assertEqual("debug_finding", found[0]["kind"])
        self.assertIn("web.servlet does not exist", found[0]["summary"])
        self.assertIn("boot.webmvc.test.autoconfigure", found[0]["content"])
        self.assertEqual([{"kind": "file", "ref": "src/test/A.java", "role": "supporting"}], found[0]["locators"])

    def test_no_candidate_without_a_verified_fix_or_for_assertion_failures(self):
        still_failing = transcript([
            ("Bash", {"command": "./mvnw -q test"}, False, COMPILE_ERROR),
            ("Edit", {"file_path": "/r/src/test/A.java", "old_string": "a", "new_string": "b"}, False, "ok"),
            ("Bash", {"command": "./mvnw -q test"}, False, COMPILE_ERROR)])
        assertion = transcript([
            ("Bash", {"command": "./mvnw -q test"}, False, "[ERROR] ATest.x:5 expected:<1> but was:<2>\nBUILD FAILURE"),
            ("Edit", {"file_path": "/r/src/A.java", "old_string": "1", "new_string": "2"}, False, "ok"),
            ("Bash", {"command": "./mvnw -q test"}, False, "BUILD SUCCESS")])
        for path in (still_failing, assertion):
            self.assertEqual([], stop.candidates(stop.steps_since_last_prompt(path), "/r", {}))

    def test_an_unrelated_failure_or_a_check_that_does_not_compile_is_no_evidence(self):
        fix = ("Edit", {"file_path": "/r/src/test/A.java", "old_string": "boot.test.autoconfigure.web.servlet",
                        "new_string": "boot.webmvc.test.autoconfigure"}, False, "ok")
        unrelated = [("Bash", {"command": "./mvnw -q test"}, False, COMPILE_ERROR), fix,
                     ("Bash", {"command": "./mvnw -q test"}, False,
                      "[ERROR] Could not resolve dependencies for project x\nBUILD FAILURE"),
                     ("Bash", {"command": "./mvnw -q -DskipTests validate"}, False, "BUILD SUCCESS")]
        self.assertEqual([], stop.candidates(stop.steps_since_last_prompt(transcript(unrelated)), "/r", {}))
        later_pass = unrelated + [("Bash", {"command": "./mvnw -q test"}, False, "BUILD SUCCESS")]
        found = stop.candidates(stop.steps_since_last_prompt(transcript(later_pass)), "/r", {})
        self.assertEqual(1, len(found))
        self.assertIn("`./mvnw -q test` then passed", found[0]["content"])

    def test_codex_rollout_is_read(self):
        rows = [{"type": "event_msg", "payload": {"type": "item_completed", "item": {"type": "UserMessage"}}},
                {"type": "event_msg", "payload": {"type": "item_completed", "item": {
                    "type": "CommandExecution", "command": ["/bin/zsh", "-lc", "./mvnw -q test"], "exit_code": 1,
                    "aggregated_output": COMPILE_ERROR}}}]
        path = Path(tempfile.mkdtemp()) / "rollout.jsonl"
        path.write_text("\n".join(json.dumps(r) for r in rows))
        steps = stop.steps_since_last_prompt(str(path))
        self.assertEqual([("Bash", "./mvnw -q test", True)], [(s["name"], s["input"]["command"], s["is_error"]) for s in steps])


class StopCheckTest(unittest.TestCase):
    def run_stop(self, root: str, state: dict, active=False):
        path = session.state_path("stop-test", root)
        session.save(path, state)
        event = {"cwd": root, "session_id": "stop-test", "stop_hook_active": active}
        with mock.patch.object(sys, "stdin", __import__("io").StringIO(json.dumps(event))), \
                mock.patch("sys.stdout", new_callable=__import__("io").StringIO) as out, \
                mock.patch.object(stop, "LEVERS", {"check"}):
            stop.main()
        return out.getvalue()

    FAILING_TESTS = {"build": "Go", "format": "true", "quality": "true",
                     "test": "echo 'a_test.go:3: expected 1' >&2; exit 1"}

    def test_unchanged_or_fully_verified_tree_is_not_checked(self):
        root = git_repo({"go.mod": "module x"})
        start = session.fingerprint(root)
        with mock.patch.object(stop.ai_orch_profile, "detect", return_value=self.FAILING_TESTS):
            self.assertEqual("", self.run_stop(root, {"start_fp": start}))
            Path(root, "a.go").write_text("package x")
            now = session.fingerprint(root)
            self.assertEqual("", self.run_stop(root, {"start_fp": start, "verified": {"quality": now, "tests": now}}))

    def test_a_passing_lint_or_partial_check_does_not_stand_in_for_the_test_suite(self):
        root = git_repo({"go.mod": "module x"})
        start = session.fingerprint(root)
        Path(root, "a.go").write_text("package x")
        now = session.fingerprint(root)
        with mock.patch.object(stop.ai_orch_profile, "detect", return_value=self.FAILING_TESTS):
            out = self.run_stop(root, {"start_fp": start, "verified": {"quality": now, "compile": now}})
        self.assertEqual("block", json.loads(out)["decision"])
        self.assertIn("expected 1", json.loads(out)["reason"])

    def test_checks_share_one_time_budget_and_report_a_timeout(self):
        root = git_repo({"go.mod": "module x"})
        start = session.fingerprint(root)
        Path(root, "a.go").write_text("package x")
        slow = {"format": "true", "quality": "sleep 1.5", "test": "sleep 1.5"}
        with mock.patch.object(stop.ai_orch_profile, "detect", return_value=slow), \
                mock.patch.object(stop, "CHECK_TIMEOUT", 2):
            reason = json.loads(self.run_stop(root, {"start_fp": start}))["reason"]
        self.assertIn("did not finish within 2s", reason)
        self.assertIn("sleep 1.5", reason)

    def test_changed_unverified_tree_that_fails_blocks_once(self):
        root = git_repo({"go.mod": "module x"})
        start = session.fingerprint(root)
        Path(root, "a.go").write_text("package x")
        failing = {"build": "Go", "format": "true", "test": "echo 'a_test.go:3: expected 1' >&2; exit 1"}
        with mock.patch.object(stop.ai_orch_profile, "detect", return_value=failing):
            out = self.run_stop(root, {"start_fp": start})
            self.assertEqual("block", json.loads(out)["decision"])
            self.assertIn("expected 1", json.loads(out)["reason"])
            self.assertEqual("", self.run_stop(root, {"start_fp": start}, active=True))

    def test_formatter_failure_is_reported_and_a_passing_check_is_recorded(self):
        root = git_repo({"go.mod": "module x"})
        start = session.fingerprint(root)
        Path(root, "a.go").write_text("package x")
        with mock.patch.object(stop.ai_orch_profile, "detect",
                               return_value={"format": "echo 'error: bad' >&2; exit 2", "test": "true"}):
            self.assertIn("formatter", json.loads(self.run_stop(root, {"start_fp": start}))["reason"])
        with mock.patch.object(stop.ai_orch_profile, "detect", return_value={"format": "true", "test": "true"}):
            self.assertEqual("", self.run_stop(root, {"start_fp": start}))
        verified = session.load(session.state_path("stop-test", root))["verified"]
        self.assertEqual({session.fingerprint(root)}, set(verified.values()))


if __name__ == "__main__":
    unittest.main()


class CoverageTest(unittest.TestCase):
    def test_what_a_passing_check_proves(self):
        cases = {
            "./mvnw -q test -Dtest='!MySqlIntegrationTests,!PostgresIntegrationTests'": {"compile", "quality", "tests"},
            "./mvnw -q test -Dtest=VetControllerTests": {"compile", "quality"},
            "./mvnw -q -DskipTests package": {"compile", "quality"},
            "./mvnw -q -DskipTests validate": {"quality"},
            "go test ./...": {"compile", "tests"},
            "go test ./articles -run TestX": {"compile"},
            "go vet ./...": {"compile", "quality"},
            "python -m unittest": {"compile", "tests"},
            "python -m pytest -q tests/test_a.py": {"compile"},
            "ruff check .": {"quality"},
            "./gradlew build": {"compile", "quality", "tests"},
            "./gradlew build -x test": {"compile", "quality"},
            "./gradlew build --exclude-task test": {"compile", "quality"},
            "./gradlew check -x check": {"compile"},
            "./gradlew build --tests com.x.FooTest": {"compile", "quality"},
            "./gradlew test --tests FooTest": {"compile"},
            "./gradlew test": {"compile", "tests"},
        }
        for command, expected in cases.items():
            self.assertEqual(expected, session.coverage(command), command)


class ReadGuardTest(unittest.TestCase):
    def test_only_plain_cat_segments_are_rewritten(self):
        self.assertIn("ai_orch_read.py a.go b.go; sed -n '1,20p' c.go",
                      command_hook.rewrite_reads("cat a.go b.go; sed -n '1,20p' c.go"))
        for unchanged in ("cat a | head", "cat x > y", "cat > f <<'EOF'\nx\nEOF", "sed -n 1,9p a.go"):
            self.assertIsNone(command_hook.rewrite_reads(unchanged), unchanged)
        self.assertIsNone(command_hook.rewrite("cat a.go", "/tmp", Path("/tmp/s"), {"compact"}))  # lever off

    def test_small_files_whole_large_ones_as_outline(self):
        import ai_orch_read as read
        root = Path(tempfile.mkdtemp())
        (root / "small.go").write_text("package x\nfunc A() {}\n")
        (root / "big.go").write_text("package x\n" + "\n".join(f"// line {i}" for i in range(500))
                                     + "\nfunc Wanted() {}\n")
        out = subprocess.run([sys.executable, read.__file__, "small.go", "big.go"], capture_output=True, text=True,
                             cwd=root).stdout
        self.assertIn("==> small.go <==\npackage x\nfunc A() {}", out)
        self.assertIn("big.go (502 lines", out)
        self.assertIn("502: func Wanted() {}", out)
        self.assertNotIn("// line 300", out)
        whole = subprocess.run([sys.executable, read.__file__, "small.go"], capture_output=True, text=True,
                               cwd=root).stdout
        self.assertEqual("package x\nfunc A() {}\n", whole)
        missing = subprocess.run([sys.executable, read.__file__, "nope.go"], capture_output=True, text=True, cwd=root)
        self.assertEqual(1, missing.returncode)
