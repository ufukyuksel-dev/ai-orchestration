"""Shared state of one agent session for the AI Orchestration hooks: the working tree's fingerprint when the session
started and when a build/test check last passed, so the Stop hook knows whether the final tree was ever checked.

The fingerprint covers what `git status` lists (tracked changes and untracked files) with each file's size and mtime;
outside a git repository there is none and the hooks that need it do nothing.
"""
from __future__ import annotations

import hashlib
import json
import os
import re
import shlex
import subprocess
import tempfile
from pathlib import Path

# Build and test commands: a check that passed on the final tree is what counts as "verified".
VERIFY = re.compile(
    r"(^|[\s;&|(])(\./mvnw|mvn|\./gradlew|gradle)\s[^;&|]*\b(test|verify|validate|package|install|check|build"
    r"|jvmTest|allTests|testAndroidHostTest|test\w*UnitTest)\b"
    r"|(^|[\s;&|(])(go\s+(test|vet|build)|(python3?\s+-m\s+)?pytest|python3?\s+-m\s+unittest|tox|nox|ruff\s+check"
    r"|python3?\s+(\S*/)?(tests?|test_\w+|\w+_tests?)\.py"  # a test script run directly: `python tests.py`
    r"|cargo\s+(test|check|clippy)"
    r"|(npm|pnpm|yarn)\s+(run\s+)?(test|lint|typecheck|check)|npx\s+(vitest|jest|tsc))\b")


def is_verify(command: str) -> bool:
    return bool(VERIFY.search(command or ""))


MAVEN_CALL = re.compile(r"(?:^|[\s;&|(])(?:\./mvnw|mvn)\s([^;&|\n]*)")
TARGETED = re.compile(r"-Dtest=['\"]?(?!!)[\w*#.,]|--tests\b|\s-run\s|::|\s-k\s|\.java\b|_test\.go\b|\.(py|ts|js)\b")


def coverage(command: str) -> set[str]:
    """What a passing check proves about the tree: `compile` (the code builds), `quality` (the lint/format gate ran)
    and `tests` (the whole fast test suite ran, not a targeted subset). The Stop hook only skips what was covered."""
    command = command or ""
    covered: set[str] = set()
    for args in MAVEN_CALL.findall(command):
        goals = set(re.findall(r"(?<![\w:-])(validate|compile|test|package|verify|install)(?![\w:-])", args))
        if goals and "-Dspring-javaformat.skip" not in args and "-Dcheckstyle.skip" not in args:
            covered.add("quality")  # the gate plugins are bound to validate, the first phase of every goal
        if goals - {"validate"}:
            covered.add("compile")
        skipped = re.search(r"-DskipTests|-Dmaven\.test\.skip", args)
        if goals & {"test", "package", "verify", "install"} and not skipped and not TARGETED.search(" " + args):
            covered.add("tests")
    for match in re.finditer(r"(?:^|[\s;&|(])(?:\./gradlew|gradle)\s([^;&|\n]*)", command):
        covered |= gradle_coverage(match.group(1))
    for match in re.finditer(r"go\s+(test|vet|build)\s*([^;&|\n]*)", command):
        verb, args = match.groups()
        covered.add("compile")
        if verb == "test" and "./..." in args and not TARGETED.search(" " + args):
            covered.add("tests")
    if re.search(r"(?:^|[\s;&|(])(?:python3?\s+-m\s+)?(?:pytest|unittest)(\s+-q|\s+-v)*\s*($|[;&|\n])", command):
        covered |= {"compile", "tests"}
    elif re.search(r"(?:^|[\s;&|(])(?:python3?\s+-m\s+)?(?:pytest|unittest)\b", command):
        covered.add("compile")  # a targeted run imports and exercises the touched modules
    if re.search(r"go\s+vet\b", command):
        covered.add("quality")  # gofmt runs before every check (the command hook), so vet completes the gate
    if re.search(r"ruff\s+check|flake8|eslint|npm\s+run\s+lint|pnpm\s+lint|yarn\s+lint|compileall", command):
        covered.add("quality")
    if re.search(r"(npm|pnpm|yarn)\s+(run\s+)?test\s*($|[;&|\n])", command):
        covered |= {"compile", "tests"}
    return covered


GRADLE_TESTS = re.compile(r"^(test|jvmTest|allTests|testAndroidHostTest|test\w*UnitTest|check|build)$")
GRADLE_OPTS_WITH_VALUE = {"-x", "--exclude-task", "-p", "--project-dir", "-c", "--settings-file", "--tests", "-D",
                          "-P", "--init-script", "-I", "--console", "--max-workers"}


def gradle_coverage(args: str) -> set[str]:
    """What one Gradle invocation proves. Tasks are parsed with their module path (`:a:b:task` -> module `:a:b`) and
    `-x` exclusions (`-x test` excludes every module's test, `-x :a:test` only module `:a`'s). A module-scoped task
    covers only `tests::<module>`; only an unscoped task covers the whole suite (`tests`) or the quality gate;
    `--tests` filters cover no suite at all."""
    try:
        tokens = shlex.split(args)
    except ValueError:
        return set()
    tasks, excluded, i = [], [], 0
    while i < len(tokens):
        tok = tokens[i]
        if tok in GRADLE_OPTS_WITH_VALUE or tok.startswith("--exclude-task=") or tok.startswith("--tests="):
            if tok in ("-x", "--exclude-task"):
                excluded.append(tokens[i + 1] if i + 1 < len(tokens) else "")
            elif tok.startswith("--exclude-task="):
                excluded.append(tok.split("=", 1)[1])
            i += 1 if "=" in tok else 2
            continue
        if not tok.startswith("-"):
            tasks.append(tok)
        i += 1
    targeted = "--tests" in tokens or any(t.startswith("--tests=") for t in tokens)
    covered: set[str] = set()

    def split(task: str) -> tuple[str | None, str]:
        module, _, name = task.rpartition(":")
        return (module or None) if task.startswith(":") or module else None, name

    def excluded_for(module: str | None, names: set[str]) -> bool:
        for ex in excluded:
            ex_mod, ex_name = split(ex)
            if ex_name in names and (ex_mod is None or ex_mod == module):
                return True
        return False

    module_excluded = any(split(ex)[0] for ex in excluded)  # e.g. `-x :a:test`: a root task is no longer whole
    for task in tasks:
        module, name = split(task)
        if not GRADLE_TESTS.match(name):
            if name in ("assemble", "compileKotlin", "compileJava", "classes", "testClasses") or name.startswith("compile"):
                covered.add("compile")
            continue
        covered.add("compile")
        if name in ("check", "build") and module is None and not excluded_for(None, {"check"}) and not module_excluded:
            covered.add("quality")
        if targeted:
            continue
        if name in ("check", "build"):  # tests run inside check; `-x test` / `-x check` take them out
            runs_tests = not excluded_for(module, {"test", "check", "jvmTest", "testAndroidHostTest", "allTests"})
        else:
            runs_tests = not excluded_for(module, {name})
        if runs_tests and not (module is None and module_excluded):
            covered.add(f"tests:{module}" if module else "tests")
    return covered


TEST_REPORT_GLOBS = ("**/build/test-results/**/*.xml", "**/target/surefire-reports/*.xml")


def test_evidence(text: str, root: str | None = None, since: float | None = None) -> str:
    """Did a passing check actually run tests? "ran" | "none" (zero tests, or every test skipped) | "unknown"
    (quiet output and no fresh runner reports). Only "ran" may be recorded as test coverage."""
    if re.search(r"\bRan 0 tests\b|NO TESTS RAN|collected 0 items|\bno tests ran\b", text):
        return "none"
    m = re.search(r"\bRan (\d+) tests?\b[\s\S]*?\bOK \(skipped=(\d+)\)", text)
    if m and m.group(1) == m.group(2):
        return "none"
    if re.search(r"^=+ (\d+) skipped\b(?![^\n]*passed)", text, re.M):
        return "none"
    m = re.search(r"Tests run: (\d+), Failures: \d+, Errors: \d+, Skipped: (\d+)", text)
    if m:
        return "none" if m.group(1) == m.group(2) else "ran"
    if re.search(r"\bRan [1-9]\d* tests?\b|\b[1-9]\d* passed\b|\b[1-9]\d* tests? completed\b|^ok\s", text, re.M):
        return "ran"
    if root and since is not None:
        tests = skipped = 0
        base = Path(root)
        for pattern in TEST_REPORT_GLOBS:
            for report in base.glob(pattern):
                try:
                    if report.stat().st_mtime < since:
                        continue
                    head = report.read_text(encoding="utf-8", errors="replace")[:2000]
                except OSError:
                    continue
                suite = re.search(r"<testsuite\b[^>]*>", head)
                if suite:
                    t = re.search(r'\btests="(\d+)"', suite.group(0))
                    sk = re.search(r'\bskipped="(\d+)"', suite.group(0))
                    tests += int(t.group(1)) if t else 0
                    skipped += int(sk.group(1)) if sk else 0
        if tests:
            return "none" if tests == skipped else "ran"
    return "unknown"


def module_tests_ran(root: str, module: str, since: float | None) -> bool:
    """Fresh test reports of one Gradle module (`:a:b` -> a/b/build/test-results) with at least one non-skipped
    test."""
    if since is None:
        return False
    tests = skipped = 0
    for report in Path(root, module.strip(":").replace(":", "/"), "build", "test-results").glob("**/*.xml"):
        try:
            if report.stat().st_mtime < since:
                continue
            head = report.read_text(encoding="utf-8", errors="replace")[:2000]
        except OSError:
            continue
        suite = re.search(r"<testsuite\b[^>]*>", head)
        if suite:
            t = re.search(r'\btests="(\d+)"', suite.group(0))
            sk = re.search(r'\bskipped="(\d+)"', suite.group(0))
            tests += int(t.group(1)) if t else 0
            skipped += int(sk.group(1)) if sk else 0
    return tests > skipped


def attributable(kinds: list[str] | set[str], root: str, since: float | None) -> set[str]:
    """The kinds a passing check with test evidence may record. Aggregate evidence (console summary) identifies one
    scope only: with several module scopes (`tests::a`, `tests::b`) each needs its own fresh reports."""
    kinds = set(kinds)
    modules = [k for k in kinds if k.startswith("tests:")]
    if len(modules) <= 1:
        return kinds
    return (kinds - set(modules)) | {k for k in modules if module_tests_ran(root, k.split(":", 1)[1], since)}


def snapshot_tree(root: str) -> str | None:
    """A git tree id of the working tree as it is now (tracked changes and untracked, not ignored, files), built in a
    temporary copy of the index: the user's index and files are not touched. Content-based: same content, same id."""
    import os
    import shutil
    try:
        index = subprocess.run(["git", "-C", root, "rev-parse", "--git-path", "index"], capture_output=True,
                               text=True, timeout=10).stdout.strip()
        if not index:
            return None
        index = index if os.path.isabs(index) else os.path.join(root, index)
        with tempfile.TemporaryDirectory() as tmp:
            temp_index = os.path.join(tmp, "index")
            if os.path.exists(index):
                # the stat cache, so only changed files are hashed again; copy2 keeps the index mtime, which git's
                # racy-file check needs to rehash files modified in the same second the index was written
                shutil.copy2(index, temp_index)
            env = dict(os.environ, GIT_INDEX_FILE=temp_index)
            if subprocess.run(["git", "-C", root, "add", "-A"], env=env, capture_output=True, timeout=60).returncode:
                return None
            tree = subprocess.run(["git", "-C", root, "write-tree"], env=env, capture_output=True, text=True,
                                  timeout=30)
            return tree.stdout.strip() if tree.returncode == 0 and tree.stdout.strip() else None
    except (OSError, subprocess.SubprocessError):
        return None


def begin_request(state: dict, root: str) -> dict:
    """A new user request: its ordinal, the tree it started from, and empty receipt / learned-fact lists."""
    previous = state.get("request") or {}
    state["request"] = {"ordinal": int(previous.get("ordinal") or 0) + 1, "tree": snapshot_tree(root),
                        "receipts": [], "facts": []}
    return state["request"]


def add_receipt(state: dict, receipt: dict, limit: int = 10) -> None:
    """A check of the current request with its real outcome (see ai_orch_trail): kept per request, newest last."""
    request = state.setdefault("request", {"ordinal": 0, "tree": None, "receipts": [], "facts": []})
    request.setdefault("receipts", []).append(receipt)
    del request["receipts"][:-limit]


COMPACTION = re.compile(r'"subtype":\s*"compact_boundary"|"type":\s*"compacted"')


def compactions(transcript: str | None) -> int:
    """How often the agent's context was compacted so far (Claude `compact_boundary`, Codex `compacted` entries)."""
    try:
        return len(COMPACTION.findall(Path(transcript).read_text(encoding="utf-8", errors="replace"))) if transcript else 0
    except OSError:
        return 0


def forget_after_compaction(state: dict, transcript: str | None) -> None:
    """After a compaction the agent no longer has what the hooks showed it: delivered blocks may be shown again."""
    count = compactions(transcript)
    if count > int(state.get("compactions") or 0):
        state["j1"] = {"delivered": [], "chars": 0}
    state["compactions"] = count


def state_path(session_id: str, root: str) -> Path:
    key = hashlib.sha256((session_id or root).encode()).hexdigest()[:16]
    return Path(tempfile.gettempdir()) / f"ai-orch-session-{key}.json"


def load(path: Path) -> dict:
    try:
        return json.loads(path.read_text())
    except (OSError, ValueError):
        return {}


def save(path: Path, state: dict) -> None:
    try:
        path.write_text(json.dumps(state))
    except OSError:
        pass


def repo_root(cwd: str) -> str:
    """The repository the agent works in: the shell may have changed into a subdirectory since the session started."""
    try:
        out = subprocess.run(["git", "-C", cwd, "rev-parse", "--show-toplevel"], capture_output=True, text=True,
                             timeout=10)
    except (OSError, subprocess.SubprocessError):
        return cwd
    top = out.stdout.strip()
    return top if out.returncode == 0 and top else cwd


def fingerprint(root: str) -> str | None:
    try:
        out = subprocess.run(["git", "-C", root, "status", "--porcelain=v1", "-uall", "-z"], capture_output=True,
                             timeout=20)
    except (OSError, subprocess.SubprocessError):
        return None
    if out.returncode != 0:
        return None
    digest = hashlib.sha256()
    for entry in out.stdout.split(b"\0"):
        if not entry:
            continue
        digest.update(entry)
        try:
            st = os.stat(os.path.join(root, entry[3:].decode("utf-8", "replace")))
            digest.update(f"{st.st_size}:{st.st_mtime_ns}".encode())
        except OSError:
            pass
    return digest.hexdigest()[:24]


def changed_files(root: str) -> list[str]:
    try:
        out = subprocess.run(["git", "-C", root, "status", "--porcelain=v1", "-uall"], capture_output=True, text=True,
                             timeout=20)
    except (OSError, subprocess.SubprocessError):
        return []
    return [line[3:].split(" -> ")[-1].strip().strip('"') for line in out.stdout.splitlines() if len(line) > 3]
