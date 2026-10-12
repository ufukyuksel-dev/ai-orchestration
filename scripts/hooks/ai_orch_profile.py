#!/usr/bin/env python3
"""The project's build profile, read from its files: build tool, the fast test command, how to run one test, the
formatter and the quality gate. The prompt hook shows it on the first request of a session, so even a first task
on a repository starts with the right commands instead of discovering them by trial and error. The command hooks use
the same profile to run the formatter before a check.

Pure file inspection: no network, no build is started. Usage: ai_orch_profile.py [root]  (prints the profile lines)
"""
from __future__ import annotations

import json
import re
import sys
from pathlib import Path

DOCKER_MARKERS = re.compile(r"org\.testcontainers|DockerClientFactory|@Testcontainers|spring\.docker\.compose\.skip\.in-tests=false")
MAX_SCAN = 4000  # test files read at most
MAX_EXCLUDED = 6  # more Docker tests than this: no exclusion list, the agent is told to prefer targeted tests


def _read(path: Path) -> str:
    try:
        return path.read_text(encoding="utf-8", errors="replace")
    except OSError:
        return ""


def _docker_tests(root: Path, pattern: str) -> list[str]:
    names = []
    for i, path in enumerate(sorted(root.glob(pattern))):
        if i >= MAX_SCAN:
            break
        if "/build/" in str(path) or "/target/" in str(path):
            continue
        if path.stem.endswith(("Test", "Tests", "IT")) and DOCKER_MARKERS.search(_read(path)):
            names.append(path.stem)
    return names


def _maven(root: Path) -> dict:
    pom = _read(root / "pom.xml")
    mvn = "./mvnw" if (root / "mvnw").exists() else "mvn"
    prof: dict = {"build": "Maven"}
    boot = re.search(r"<artifactId>spring-boot-starter-parent</artifactId>\s*<version>([^<]+)</version>", pom)
    if boot:
        prof["framework"] = f"Spring Boot {boot.group(1)}"
    docker = _docker_tests(root, "src/test/**/*.java")
    excluded = docker if len(docker) <= MAX_EXCLUDED else []
    skip = f" -Dtest='{','.join('!' + n for n in excluded)}' -Dsurefire.failIfNoSpecifiedTests=false" if excluded else ""
    prof["test"] = f"{mvn} -q test{skip}"
    prof["one_test"] = f"{mvn} -q test -Dtest=<TestClass> -Dsurefire.failIfNoSpecifiedTests=false"
    if docker:
        prof["docker_tests"] = docker
    gates = []
    if "spring-javaformat-maven-plugin" in pom:
        prof["format"] = f"{mvn} -q spring-javaformat:apply"
        prof["format_goal"] = "spring-javaformat:apply"
        gates.append("spring-javaformat")
    if "maven-checkstyle-plugin" in pom:
        gates.append("checkstyle" + (" incl. nohttp: no 'http://' URLs in any file" if "nohttp" in pom else ""))
    if gates:
        prof["quality"] = f"{mvn} -q -DskipTests validate"
        prof["quality_note"] = ", ".join(gates)
    return prof


def gradle_modules(root: Path) -> list[str]:
    """Module paths (`:a:b`) included by settings.gradle(.kts); empty for a single-module build."""
    settings = _read(root / "settings.gradle.kts") + _read(root / "settings.gradle")
    settings = re.sub(r"//[^\n]*", "", settings)
    modules = []
    for block in re.findall(r"\binclude\s*\(([^)]*)\)|\binclude\s+((?:['\"][^'\"]+['\"]\s*,?\s*)+)", settings):
        for name in re.findall(r"['\"]([^'\"]+)['\"]", block[0] or block[1]):
            modules.append(name if name.startswith(":") else ":" + name)
    return list(dict.fromkeys(modules))


def _convention_text(root: Path, build: str) -> str:
    """The convention plugins a module applies by id (buildSrc / build-logic precompiled script plugins)."""
    text = ""
    for plugin_id in re.findall(r"id\s*\(?\s*['\"]([\w.-]+)['\"]", build):
        for base in ("buildSrc", "build-logic"):
            for path in (root / base).glob(f"**/{plugin_id}.gradle.kts"):
                text += _read(path)
    return text


def gradle_test_task(root: Path, module: str) -> str:
    """The JVM test task of one module: `test` for JVM modules; for Kotlin Multiplatform modules the Android host
    tests when enabled, else the JVM target's tests, else every target's."""
    folder = root / module.strip(":").replace(":", "/")
    build = _read(folder / "build.gradle.kts") + _read(folder / "build.gradle")
    text = build + _convention_text(root, build)
    if "multiplatform" not in text:
        return "test"
    if "withHostTest" in text:
        return "testAndroidHostTest"
    if re.search(r"\bjvm\s*\(", text):
        return "jvmTest"
    return "allTests"


def module_of(modules: list[str], path: str) -> str | None:
    """The deepest module whose folder contains the repository-relative `path`."""
    best = None
    for module in modules:
        folder = module.strip(":").replace(":", "/") + "/"
        if path.startswith(folder) and (best is None or len(folder) > len(best.strip(":").replace(":", "/")) + 1):
            best = module
    return best


BUILD_CONFIG = re.compile(r"(^|/)(settings\.gradle(\.kts)?|build\.gradle(\.kts)?|gradle\.properties|"
                          r"libs\.versions\.toml)$|^(buildSrc|build-logic|gradle)/")


def touches_build_config(root: str | Path, files: list[str]) -> bool:
    """A changed file outside every module that configures the build (settings, root build files, convention
    plugins, version catalog): module-scoped tests do not cover such a change."""
    modules = gradle_modules(Path(root))
    return any(BUILD_CONFIG.search(f) and not module_of(modules, f) for f in files)


def touched_modules(root: str | Path, files: list[str]) -> list[str]:
    modules = gradle_modules(Path(root))
    return sorted({m for m in (module_of(modules, f) for f in files) if m})


def module_test_targets(root: str | Path, files: list[str], skip: set[str] = frozenset()) -> list[tuple[str, str]]:
    """(module, test task) for the modules that contain `files`, minus `skip` (already covered) and minus modules
    whose only test task is `allTests` (it also runs native/simulator targets: not a quick, reliable check)."""
    root = Path(root)
    out = []
    for module in touched_modules(root, files):
        task = gradle_test_task(root, module)
        if module not in skip and task != "allTests":
            out.append((module, task))
    return out


def module_test_command(root: str | Path, files: list[str], skip: set[str] = frozenset()) -> str | None:
    """Tests of the modules that contain `files` (the Stop hook's check on a multi-module build)."""
    targets = module_test_targets(root, files, skip)
    if not targets:
        return None
    gw = "./gradlew" if (Path(root) / "gradlew").exists() else "gradle"
    return f"{gw} " + " ".join(f"{m}:{t}" for m, t in targets) + " -q"


def _gradle(root: Path) -> dict:
    gw = "./gradlew" if (root / "gradlew").exists() else "gradle"
    build = _read(root / "build.gradle") + _read(root / "build.gradle.kts")
    modules = gradle_modules(root)
    if len(modules) > 1:
        tasks = {gradle_test_task(root, m) for m in modules}
        kmp = tasks - {"test"}
        prof = {"build": "Gradle, multi-module" + (", Kotlin Multiplatform" if kmp else ""), "modules": len(modules),
                "module_test": f"{gw} :<module>:<task> -q",
                "module_tasks": ", ".join(f"`{t}`" for t in sorted(tasks)),
                "one_test": f"{gw} :<module>:<task> --tests '<TestClass>'"}
        if kmp:
            examples = [m for m in modules if gradle_test_task(root, m) != "test"][:2]
            prof["module_examples"] = ", ".join(f"`{m}:{gradle_test_task(root, m)}`" for m in examples)
    else:
        prof = {"build": "Gradle", "test": f"{gw} test -q", "one_test": f"{gw} test --tests <TestClass>"}
    if "spotless" in build:
        prof["format"] = f"{gw} spotlessApply -q"
    return prof


def _node(root: Path) -> dict:
    try:
        pkg = json.loads(_read(root / "package.json") or "{}")
    except ValueError:
        pkg = {}
    scripts = pkg.get("scripts") or {}
    pm = "pnpm" if (root / "pnpm-lock.yaml").exists() else "yarn" if (root / "yarn.lock").exists() else "npm"
    run = (lambda s: f"{pm} run {s}") if pm == "npm" else (lambda s: f"{pm} {s}")
    prof: dict = {"build": f"Node ({pm})"}
    if "test" in scripts:
        prof["test"] = f"{pm} test" if pm != "npm" else "npm test"
        runner = "vitest" if "vitest" in scripts["test"] else "jest" if "jest" in scripts["test"] else ""
        if runner:
            prof["one_test"] = f"npx {runner} run <file>" if runner == "vitest" else f"npx jest <file>"
    for name in ("format", "fmt", "prettier"):
        if name in scripts:
            prof["format"] = run(name)
            break
    lint = [s for s in ("lint", "typecheck", "type-check", "tsc") if s in scripts]
    if lint:
        prof["quality"] = " && ".join(run(s) for s in lint)
    return prof


def _python(root: Path) -> dict:
    cfg = _read(root / "pyproject.toml") + _read(root / "setup.cfg") + _read(root / "tox.ini")
    deps = cfg + _read(root / "requirements.txt") + _read(root / "requirements-dev.txt")
    if "pytest" in deps or (root / "pytest.ini").exists() or (root / "conftest.py").exists():
        prof = {"build": "Python", "test": "python -m pytest -q", "one_test": "python -m pytest -q <path>::<test>"}
    else:
        prof = {"build": "Python (unittest)", "test": "python -m unittest", "one_test": "python -m unittest <module>.<Class>.<test>"}
    if "ruff" in cfg:
        prof["format"] = "ruff format . && ruff check --fix ."
        prof["quality"] = "ruff check ."
    elif "black" in cfg:
        prof["format"] = "black ."
    return prof


def _go(root: Path) -> dict:
    return {"build": "Go", "test": "go test ./...", "one_test": "go test ./<pkg> -run <TestName>",
            "format": "gofmt -w .", "quality": "go vet ./... && test -z \"$(gofmt -l .)\""}


def detect(root: str | Path) -> dict:
    root = Path(root)
    if (root / "pom.xml").exists():
        return _maven(root)
    if (root / "build.gradle").exists() or (root / "build.gradle.kts").exists():
        return _gradle(root)
    if (root / "go.mod").exists():
        return _go(root)
    if (root / "package.json").exists():
        return _node(root)
    if any((root / f).exists() for f in ("pyproject.toml", "setup.py", "setup.cfg", "pytest.ini", "requirements.txt",
                                         "manage.py", "tests.py")):
        return _python(root)
    return {}


def render(prof: dict) -> list[str]:
    if not prof:
        return []
    head = prof["build"] + (f", {prof['framework']}" if prof.get("framework") else "")
    lines = [f"Project profile ({head}), read from the build files:"]
    docker = prof.get("docker_tests") or []
    if prof.get("test"):
        note = ""
        if docker and len(docker) <= MAX_EXCLUDED:
            note = f" (skips {', '.join(docker)}: they need Docker and are slow)"
        elif docker:
            note = f" ({len(docker)} test classes need Docker and are slow: prefer the one-class command)"
        lines.append(f"- Tests: `{prof['test']}`{note}")
    if prof.get("module_test"):
        examples = f", e.g. {prof['module_examples']}" if prof.get("module_examples") else ""
        lines.append(f"- {prof['modules']} modules: test only the modules you changed, `{prof['module_test']}` "
                     f"(task per module: {prof['module_tasks']}{examples}); `test` at the root runs every module")
    if prof.get("one_test"):
        lines.append(f"- One test class: `{prof['one_test']}`")
    if prof.get("format"):
        lines.append(f"- Formatter: `{prof['format']}` (applied automatically before your build/test commands)")
    if prof.get("quality"):
        note = f" ({prof['quality_note']})" if prof.get("quality_note") else ""
        lines.append(f"- Quality gate: `{prof['quality']}`{note}")
    return lines


if __name__ == "__main__":
    print("\n".join(render(detect(sys.argv[1] if len(sys.argv) > 1 else "."))))
