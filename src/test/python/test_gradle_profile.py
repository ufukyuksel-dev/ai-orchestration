"""Multi-module Gradle in the build profile: per-module test tasks (KMP host tests / jvm / JVM `test`), the Stop
hook's changed-module command, and module test runs counted as test coverage."""
from __future__ import annotations

import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "scripts" / "hooks"))
import ai_orch_profile  # noqa: E402
import ai_orch_session_state as session  # noqa: E402


def kmp_project(tmp_path: Path, host_tests: bool = True) -> Path:
    root = tmp_path / "app"
    (root / "buildSrc/src/main/kotlin").mkdir(parents=True)
    (root / "gradlew").write_text("#!/bin/sh\n")
    (root / "build.gradle.kts").write_text("plugins {}\n")
    (root / "settings.gradle.kts").write_text(
        'rootProject.name = "app"\n// include(":old")\ninclude(":shared:core:engine")\ninclude(":shared:core:model")\n'
        'include(\n    "backend",\n    ":shared:core:ios-only",\n)\n')
    (root / "buildSrc/src/main/kotlin/app.multiplatform.gradle.kts").write_text(
        'plugins { id("org.jetbrains.kotlin.multiplatform") }\nkotlin { androidLibrary {'
        + (" withHostTest {} " if host_tests else "") + "} }\n")
    for module, build in {
        "shared/core/engine": 'plugins { id("app.multiplatform") }\n',
        "shared/core/model": 'plugins { id("app.multiplatform") }\nkotlin { jvm() }\n',
        "shared/core/ios-only": 'plugins { kotlin("multiplatform") }\nkotlin { iosArm64() }\n',
        "backend": 'plugins { kotlin("jvm") }\n',
    }.items():
        (root / module).mkdir(parents=True)
        (root / module / "build.gradle.kts").write_text(build)
    return root


def test_modules_and_tasks(tmp_path):
    root = kmp_project(tmp_path)
    assert ai_orch_profile.gradle_modules(root) == [":shared:core:engine", ":shared:core:model", ":backend",
                                                    ":shared:core:ios-only"]
    assert ai_orch_profile.gradle_test_task(root, ":shared:core:engine") == "testAndroidHostTest"
    assert ai_orch_profile.gradle_test_task(root, ":backend") == "test"
    assert ai_orch_profile.gradle_test_task(root, ":shared:core:ios-only") == "allTests"
    assert ai_orch_profile.gradle_test_task(kmp_project(tmp_path / "x", host_tests=False),
                                            ":shared:core:model") == "jvmTest"


def test_profile_renders_module_commands_instead_of_root_test(tmp_path):
    lines = ai_orch_profile.render(ai_orch_profile.detect(kmp_project(tmp_path)))
    text = "\n".join(lines)
    assert "multi-module, Kotlin Multiplatform" in lines[0]
    assert "`./gradlew :<module>:<task> -q`" in text and "`:shared:core:engine:testAndroidHostTest`" in text
    assert "- Tests: `./gradlew test -q`" not in text


def test_single_module_gradle_unchanged(tmp_path):
    (tmp_path / "build.gradle.kts").write_text("plugins {}\n")
    assert ai_orch_profile.detect(tmp_path)["test"] == "gradle test -q"


def test_stop_hook_command_covers_changed_modules_only(tmp_path):
    root = kmp_project(tmp_path)
    files = ["shared/core/engine/src/commonMain/kotlin/A.kt", "backend/src/main/kotlin/B.kt", "README.md",
             "shared/core/ios-only/src/C.kt"]
    assert ai_orch_profile.module_test_command(root, files) == \
        "./gradlew :backend:test :shared:core:engine:testAndroidHostTest -q"
    assert ai_orch_profile.module_test_command(root, ["README.md"]) is None


def test_module_test_runs_cover_only_their_modules():
    assert session.is_verify("./gradlew :shared:core:engine:testAndroidHostTest -q")
    assert session.coverage("./gradlew :a:testAndroidHostTest :b:c:jvmTest -q") == {"compile", "tests::a", "tests::b:c"}
    assert session.coverage("./gradlew :a:testDebugUnitTest") == {"compile", "tests::a"}
    assert session.coverage("./gradlew :a:jvmTest --tests Foo") == {"compile"}
    assert session.coverage("./gradlew test -q") == {"compile", "tests"}


def test_scoped_build_check_and_exclusions():
    assert session.coverage("./gradlew :a:build") == {"compile", "tests::a"}
    assert session.coverage("./gradlew :a:check") == {"compile", "tests::a"}
    assert session.coverage("./gradlew :a:jvmTest -x :a:jvmTest") == {"compile"}
    assert session.coverage("./gradlew :a:build -x test") == {"compile"}
    assert session.coverage("./gradlew :a:testAndroidHostTest :b:jvmTest -x :b:jvmTest") == {"compile", "tests::a"}
    assert session.coverage("./gradlew build -x test") == {"compile", "quality"}
    # a root task with a module excluded no longer covers the whole suite or the quality gate
    assert session.coverage("./gradlew test -x :a:test") == {"compile"}
    assert session.coverage("./gradlew build -x :a:check") == {"compile"}


def test_excluded_module_still_needs_its_check(tmp_path, monkeypatch):
    import ai_orch_stop
    root = kmp_project(tmp_path)
    monkeypatch.setattr(session, "changed_files", lambda r: ["backend/src/B.kt"])
    covered = session.coverage("./gradlew test -x :backend:test")
    assert ai_orch_stop.tests_check(str(root), ai_orch_profile.detect(root), covered)[0] == "./gradlew :backend:test -q"


def report(root: Path, module_dir: str, tests: int, skipped: int = 0) -> None:
    d = root / module_dir / "build" / "test-results" / "test"
    d.mkdir(parents=True, exist_ok=True)
    (d / "TEST-x.xml").write_text(f'<testsuite name="x" tests="{tests}" skipped="{skipped}" failures="0">')


@pytest.mark.parametrize("b_tests,b_skipped", [(0, 0), (2, 2), (None, None)])
def test_each_module_needs_its_own_evidence(tmp_path, monkeypatch, b_tests, b_skipped):
    import ai_orch_stop
    root = kmp_project(tmp_path)
    monkeypatch.setattr(session, "changed_files", lambda r: ["shared/core/engine/src/A.kt", "backend/src/B.kt"])

    def fake_run(cmd, r, deadline):
        report(root, "shared/core/engine", 3)          # engine ran 3 tests
        if b_tests is not None:
            report(root, "backend", b_tests, b_skipped)  # backend: zero tests / all skipped / no report
        return 0, "BUILD SUCCESSFUL\n3 tests completed"
    monkeypatch.setattr(ai_orch_stop, "run", fake_run)
    failure, got = ai_orch_stop.final_check(str(root), ai_orch_profile.detect(root), {"tests"})
    assert failure is None and got == {"compile", "tests::shared:core:engine"}


def test_command_hook_records_only_modules_with_reports(tmp_path):
    import time
    root = kmp_project(tmp_path)
    t0 = time.time()
    report(root, "backend", 2)
    kinds = session.attributable({"compile", "tests::backend", "tests::shared:core:engine"}, str(root), t0 - 1)
    assert kinds == {"compile", "tests::backend"}
    assert session.attributable({"compile", "tests::backend"}, str(root), None) == {"compile", "tests::backend"}


def test_stop_needs_only_uncovered_modules_and_full_suite_on_build_config(tmp_path, monkeypatch):
    import ai_orch_stop
    root = kmp_project(tmp_path)
    prof = ai_orch_profile.detect(root)
    files = ["shared/core/engine/src/A.kt", "backend/src/B.kt"]
    monkeypatch.setattr(session, "changed_files", lambda r: files)
    assert ai_orch_stop.tests_check(str(root), prof, {"tests::backend"}) == \
        ("./gradlew :shared:core:engine:testAndroidHostTest -q", {"tests::shared:core:engine"})
    # after `:backend:build` only backend is covered: engine still needs its tests
    assert ai_orch_stop.tests_check(str(root), prof, session.coverage("./gradlew :backend:build"))[1] == \
        {"tests::shared:core:engine"}
    assert ai_orch_stop.tests_check(str(root), prof, {"tests::backend", "tests::shared:core:engine"}) == (None, set())
    files.append("shared/core/ios-only/src/C.kt")  # native-only module: nothing runnable, never certified
    assert ai_orch_stop.tests_check(str(root), prof, {"tests::backend", "tests::shared:core:engine"}) == (None, set())
    files.append("buildSrc/src/main/kotlin/app.multiplatform.gradle.kts")
    assert ai_orch_stop.tests_check(str(root), prof, {"tests::backend"}) == ("./gradlew test -q", {"tests"})
    assert ai_orch_stop.tests_check(str(root), prof, {"tests"}) == (None, set())


@pytest.mark.parametrize("output,proved", [
    ("Ran 0 tests in 0.0s\n\nOK", {"compile"}),
    ("Ran 2 tests in 0.1s\n\nOK (skipped=2)", {"compile"}),
    ("BUILD SUCCESSFUL", {"compile"}),          # quiet, no reports: no evidence that tests ran
    ("Ran 3 tests in 0.1s\n\nOK", {"compile", "tests"}),
])
def test_final_check_proves_tests_only_when_tests_ran(tmp_path, monkeypatch, output, proved):
    import ai_orch_stop
    monkeypatch.setattr(ai_orch_stop, "run", lambda cmd, root, deadline: (0, output))
    failure, got = ai_orch_stop.final_check(str(tmp_path), {"test": "python -m unittest"}, {"tests"})
    assert failure is None and got == proved


def test_final_check_module_scope(tmp_path, monkeypatch):
    import ai_orch_stop
    root = kmp_project(tmp_path)
    monkeypatch.setattr(session, "changed_files", lambda r: ["backend/src/B.kt"])
    monkeypatch.setattr(ai_orch_stop, "run", lambda cmd, root, deadline: (0, "4 tests completed"))
    failure, got = ai_orch_stop.final_check(str(root), ai_orch_profile.detect(root), {"tests"})
    assert failure is None and got == {"compile", "tests::backend"}
