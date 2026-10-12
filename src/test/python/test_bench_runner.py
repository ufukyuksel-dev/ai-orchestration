"""bench/run.py v2 pieces that need no agent or backend: answer scoring, baselines, diff scope, mutants, resume."""
from __future__ import annotations

import json
import subprocess
import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "bench"))
import run  # noqa: E402


def git(repo: Path, *args: str) -> str:
    return subprocess.run(["git", "-C", str(repo), *args], capture_output=True, text=True, check=True).stdout.strip()


@pytest.fixture
def repo(tmp_path: Path) -> Path:
    r = tmp_path / "repo"
    r.mkdir()
    git(r, "init", "-q")
    git(r, "config", "user.email", "t@t")
    git(r, "config", "user.name", "t")
    git(r, "config", "commit.gpgsign", "false")
    (r / "src").mkdir()
    (r / "src" / "calc.py").write_text("def add(a, b):\n    return a + b\n")
    (r / ".gitignore").write_text("build/\n")
    git(r, "add", "-A")
    git(r, "commit", "-qm", "init")
    return r


# ------------------------------------------------------------------ ANSWER: scoring
def test_answer_block_takes_last_block_and_stops_at_prose():
    text = "ANSWER:\nfile: old\n\nmore text\n**ANSWER:**\n- file: `src/a.py:12`\n- symbol: Foo#bar()\nThat's all.\n"
    assert run.answer_block(text) == {"file": "`src/a.py:12`", "symbol": "Foo#bar()"}


def test_answer_block_inside_code_fence():
    assert run.answer_block("ANSWER:\n```\nvalue: 3\n```\n") == {"value": "3"}


def test_answer_block_missing():
    assert run.answer_block("the file is src/a.py") is None


@pytest.fixture
def answers(tmp_path, monkeypatch):
    bench = tmp_path / "bench"
    (bench / "answers").mkdir(parents=True)
    monkeypatch.setattr(run, "ASSETS", bench)

    def write(task_id: str, key: dict) -> dict:
        (bench / "answers" / f"{task_id}.json").write_text(json.dumps(key))
        return {"id": task_id, "kind": "investigation"}
    return write


KEY = {"keys": {"file": {"kind": "path", "accept": ["src/score/Scorer.kt"]},
                "symbol": {"kind": "symbol", "accept": ["Scorer#score"]},
                "value": {"kind": "text", "accept": ["3"]}},
       "contradict": [r"Legacy"]}


def test_answer_correct_with_normalized_forms(answers, tmp_path):
    task = answers("i1", KEY)
    wt = tmp_path / "wt"
    text = f"ANSWER:\nfile: {wt}/src/score/Scorer.kt:40\nsymbol: `Scorer.score(Int)`\nvalue: 3.\n"
    result = run.score_answer(task, text, [], wt)
    assert result["success"] and result["answerStatus"] == "scored"


def test_answer_listing_alternatives_fails(answers, tmp_path):
    task = answers("i1", KEY)
    text = "ANSWER:\nfile: src/score/Scorer.kt or src/score/Other.kt\nsymbol: Scorer#score\nvalue: 3\n"
    result = run.score_answer(task, text, [], tmp_path)
    assert not result["success"] and result["answerWrong"] == ["file"]


def test_answer_contradiction_fails(answers, tmp_path):
    task = answers("i1", KEY)
    text = "ANSWER:\nfile: src/score/Scorer.kt\nsymbol: Scorer#score\nvalue: 3\nnote: LegacyScorer also\n"
    assert run.score_answer(task, text, [], tmp_path)["answerContradictions"] == ["Legacy"]


def test_contradict_anchors_match_any_answer_line(answers, tmp_path):
    task = answers("i3", {"keys": {"a": {"accept": ["1"]}, "b": {"accept": ["2"]}}, "contradict": [r"^note: legacy$"]})
    assert run.score_answer(task, "ANSWER:\na: 1\nb: 2\n", [], tmp_path)["success"]
    result = run.score_answer(task, "ANSWER:\na: 1\nb: 2\nnote: legacy\n", [], tmp_path)  # third line
    assert not result["success"] and result["answerContradictions"] == [r"^note: legacy$"]


def test_trailing_notes_are_ignored():
    assert run.normalize("FindManyArticle (package articles)", "symbol") == "findmanyarticle"
    assert run.normalize("`src/a.kt` (line 40)", "path") == "src/a.kt"


def test_camel_case_answer_keys(answers, tmp_path):
    task = answers("i4", {"keys": {"pageSize": {"accept": ["5"]}}})
    assert run.score_answer(task, "ANSWER:\npageSize: 5\n", [], tmp_path)["success"]


def test_answer_missing_key_is_ambiguous_failure(answers, tmp_path):
    task = answers("i1", KEY)
    result = run.score_answer(task, "ANSWER:\nfile: src/score/Scorer.kt\n", [], tmp_path)
    assert result["success"] is False and result["answerStatus"] == "ambiguous"
    assert result["answerMissing"] == ["symbol", "value"]


def test_answer_with_code_edit_fails(answers, tmp_path):
    task = answers("i1", KEY)
    text = "ANSWER:\nfile: src/score/Scorer.kt\nsymbol: Scorer#score\nvalue: 3\n"
    result = run.score_answer(task, text, ["src/score/Scorer.kt"], tmp_path)
    assert not result["success"] and result["editedInInvestigation"]


def test_answer_multi_key_is_set_equality(answers, tmp_path):
    task = answers("i2", {"keys": {"modules": {"kind": "path", "multi": True, "accept": ["a", "b"]}}})
    assert run.score_answer(task, "ANSWER:\nmodules: b, a\n", [], tmp_path)["success"]
    assert not run.score_answer(task, "ANSWER:\nmodules: a, b, c\n", [], tmp_path)["success"]


# ------------------------------------------------------------------ final message
def test_final_message_claude_and_codex():
    assert run.final_message("claude", [{"type": "result", "result": "done"}]) == "done"
    events = [{"type": "item.completed", "item": {"type": "agent_message", "text": "first"}},
              {"type": "item.completed", "item": {"type": "command_execution"}},
              {"type": "item.completed", "item": {"type": "agent_message", "text": "last"}}]
    assert run.final_message("codex", events) == "last"


# ------------------------------------------------------------------ baselines and diff scope
def test_reset_code_applies_suite_and_task_patches(repo, tmp_path, monkeypatch):
    bench = tmp_path / "bench"
    bench.mkdir()
    sha = git(repo, "rev-parse", "HEAD")
    (repo / "src" / "calc.py").write_text("def add(a, b):\n    return a + b\n\n\ndef sub(a, b):\n    return a - b\n")
    (bench / "base.patch").write_text(git(repo, "diff") + "\n")
    git(repo, "checkout", "-q", "--", ".")
    (repo / "src" / "calc.py").write_text("def plus(a, b):\n    return a + b\n")
    monkeypatch.setattr(run, "ASSETS", bench)
    monkeypatch.setattr(run, "SUITE_BASE_PATCH", "")
    monkeypatch.setitem(run.SUITE_SHA, "r", sha)
    plain = run.reset_code(repo, "r")  # agent edits discarded
    assert "def add" in (repo / "src" / "calc.py").read_text()
    assert git(repo, "rev-parse", f"{plain}^{{tree}}") == git(repo, "rev-parse", f"{sha}^{{tree}}")
    monkeypatch.setattr(run, "SUITE_BASE_PATCH", "base.patch")
    baseline = run.reset_code(repo, "r")
    assert "def sub" in (repo / "src" / "calc.py").read_text()
    assert run.changed_files(repo, baseline) == []
    assert git(repo, "log", "--format=%s", "HEAD") == "snapshot"  # one parentless commit: the patch is not visible
    assert run.reset_code(repo, "r") == baseline  # deterministic: same tree, same commit


def test_changed_files_counts_commits_and_untracked_not_ignored(repo):
    base = git(repo, "rev-parse", "HEAD")
    (repo / "src" / "calc.py").write_text("x = 1\n")
    git(repo, "commit", "-qam", "agent commit")
    (repo / "src" / "new.py").write_text("y = 2\n")
    (repo / "build").mkdir()
    (repo / "build" / "out.class").write_text("bin")
    assert run.changed_files(repo, base) == ["src/calc.py", "src/new.py"]
    assert run.outside(["src/calc.py", "tests/t.py"], ["tests/*"]) == ["src/calc.py"]


# ------------------------------------------------------------------ test-writing tasks (mutants)
def test_score_tests_requires_killing_every_mutant(repo, tmp_path, monkeypatch):
    bench = tmp_path / "bench"
    (bench / "mutants" / "t1").mkdir(parents=True)
    base = git(repo, "rev-parse", "HEAD")
    (repo / "src" / "calc.py").write_text("def add(a, b):\n    return a - b\n")
    (bench / "mutants" / "t1" / "m1.patch").write_text(git(repo, "diff") + "\n")
    (repo / "src" / "calc.py").write_text("def add(a, b):\n    return a + b + 0\n")
    (bench / "mutants" / "t1" / "m2.patch").write_text(git(repo, "diff") + "\n")  # equivalent mutant: survives
    git(repo, "checkout", "-q", "--", ".")
    monkeypatch.setattr(run, "ASSETS", bench)
    (repo / "tests").mkdir()
    (repo / "tests" / "test_calc.py").write_text(
        "import sys; sys.path.insert(0, 'src')\nfrom calc import add\nassert add(2, 3) == 5\n")
    task = {"id": "t1", "kind": "tests", "allowedPaths": ["tests/*"], "testCommand": f"{sys.executable} -B tests/test_calc.py",
            "mutants": ["m1.patch"]}
    suite = {"qualityCommand": "true"}
    result = run.score_tests(task, suite, repo, base)
    assert result["success"] and result["mutantsKilled"] == 1, (result["survivors"], result["scopeOk"], result["testsOk"])
    assert "return a + b" in (repo / "src" / "calc.py").read_text()  # mutant reverted
    result = run.score_tests(dict(task, mutants=["m1.patch", "m2.patch"]), suite, repo, base)
    assert not result["success"] and result["survivors"] == ["m2.patch"]
    (repo / "src" / "calc.py").write_text("def add(a, b):\n    return b + a\n")  # production change: out of scope
    assert not run.score_tests(task, suite, repo, base)["scopeOk"]


# ------------------------------------------------------------------ arms and resume
def test_arm_variants(monkeypatch):
    monkeypatch.setattr(run, "LEVERS", "profile,codemap")  # the default install: every lever but lean
    assert run.levers_for("A") == "" and run.levers_for("Bnomem") == "profile,codemap"
    assert run.lever_on("lean", "Blean") and not run.lever_on("lean", "B") and run.lever_on("codemap", "Blearn")
    assert run.levers_for("Blean") == "profile,codemap,lean"
    assert run.instructions("claude", "Blearn").endswith(run.LEARN_REQUIRED)
    assert not run.instructions("claude", "B").endswith(run.LEARN_REQUIRED)


def test_done_cells_and_atomic_write(tmp_path):
    run.write_atomic(tmp_path / "cells" / "claude-B-F1-r1.json", "{}")
    assert run.done_cells(tmp_path) == {"claude-B-F1-r1"}
    assert run.cell_id("codex", "Bnomem", {"id": "W2"}, 2) == "codex-Bnomem-W2-r2"


def test_infra_retry_classifies_after_second_failure(monkeypatch):
    monkeypatch.setattr(run.time, "sleep", lambda s: None)
    calls = []

    def flaky():
        calls.append(1)
        raise OSError("docker down")
    with pytest.raises(run.InfraError, match="scan: docker down"):
        run.with_infra_retry("scan", flaky)
    assert len(calls) == 2


def test_resolve_env_passthrough():
    assert run.resolve_env("plain") == "plain"


def test_reset_memory_also_deletes_the_cards_vectors(monkeypatch):
    """SQL deletes fire no app event: orphaned Qdrant points would crowd live cards out of the top-k."""
    sql, posted = [], []
    monkeypatch.setattr(run, "psql", lambda q: sql.append(q) or "")

    class Response:
        def __enter__(self):
            return self

        def __exit__(self, *a):
            return False

        def read(self):
            return b"{}"

    def urlopen(req, timeout=0):
        posted.append((req.full_url, json.loads(req.data)))
        return Response()

    monkeypatch.setattr(run.urllib.request, "urlopen", urlopen)
    run.reset_memory("P_KEY")
    assert any("memory_items" in q for q in sql) and any("work_trails" in q for q in sql)
    url, body = posted[0]
    assert url.endswith(f"/collections/{run.MEMORY_COLLECTION}/points/delete?wait=true")
    assert body == {"filter": {"must": [{"key": "project_key", "match": {"value": "P_KEY"}}]}}
