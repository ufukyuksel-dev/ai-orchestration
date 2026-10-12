"""J2: known-equivalent command repairs in the command hook (pytest in a unittest repository, GNU sed -i on macOS)."""
from __future__ import annotations

import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "scripts" / "hooks"))
import ai_orch_command_hook as hook  # noqa: E402

UNITTEST = {"build": "Python (unittest)"}


@pytest.fixture
def no_pytest(monkeypatch):
    monkeypatch.setattr(hook, "_pytest_missing", lambda python, root: True)
    monkeypatch.setattr(hook, "_bsd_sed", lambda: False)


def test_pytest_becomes_unittest_with_same_targets_and_receipt(no_pytest):
    cmd, receipts = hook.repair("python -m pytest -q tests.py 2>&1 | tail -8", "/r", UNITTEST)
    assert cmd == "python -m unittest -q tests 2>&1 | tail -8"
    assert receipts == ["requested: python -m pytest -q tests.py; executed: python -m unittest -q tests; "
                        "scope: same tests (pytest is not installed; this repository uses unittest)"]
    cmd, _ = hook.repair("pytest tests/test_api.py::UserCase::test_x -v", "/r", UNITTEST)
    assert cmd.endswith(" -m unittest tests.test_api.UserCase.test_x -v")  # python or python3, whichever exists


def test_pytest_without_exact_equivalent_passes_through(no_pytest):
    for original in ("pytest -k follow", "pytest tests/", "python -m pytest --cov=app", "echo pytest -q",
                     "cd sub && pytest tests.py", "pytest tests.py; echo done", "uv run pytest tests.py"):
        assert hook.repair(original, "/r", UNITTEST) == (original, [])


def test_pytest_keeps_interpreter_and_maps_fail_fast(no_pytest):
    assert hook.repair("python3.11 -m pytest -q tests.py", "/r", UNITTEST)[0] == "python3.11 -m unittest -q tests"
    assert hook.repair("pytest -x tests.py", "/r", UNITTEST)[0].endswith("-m unittest -f tests")


def test_pytest_kept_when_installed_or_repo_uses_pytest(monkeypatch):
    monkeypatch.setattr(hook, "_bsd_sed", lambda: False)
    monkeypatch.setattr(hook, "_pytest_missing", lambda python, root: False)
    assert hook.repair("pytest -q tests.py", "/r", UNITTEST) == ("pytest -q tests.py", [])
    monkeypatch.setattr(hook, "_pytest_missing", lambda python, root: True)
    assert hook.repair("pytest -q", "/r", {"build": "Python"}) == ("pytest -q", [])


def test_gnu_sed_inplace_gets_empty_suffix_on_bsd_sed_only(monkeypatch):
    monkeypatch.setattr(hook, "_bsd_sed", lambda: True)
    cmd, receipts = hook.repair("sed -i '158s/a/b/' app/models.py && python -m unittest", "/r", {})
    assert cmd == "sed -i '' '158s/a/b/' app/models.py && python -m unittest" and len(receipts) == 1
    assert hook.repair('sed -i "s/a;b/c/" f', "/r", {})[0] == "sed -i '' \"s/a;b/c/\" f"
    # the original text is kept: variables still expand, globs still match
    assert hook.repair('sed -i "s/$needle/new/" file.txt', "/r", {})[0] == "sed -i '' \"s/$needle/new/\" file.txt"
    assert hook.repair("sed -i 's/a/b/' *.txt", "/r", {})[0] == "sed -i '' 's/a/b/' *.txt"
    for untouched in ("sed -i '' 's/a/b/' f", "sed -i.bak 's/a/b/' f", "sed -i -e 's/a/b/' f", "sed -n '1,5p' f",
                      "echo sed -i 's/a/b/' f", "sed -i 's/a/b/' f | cat", "cd x && sed -i 's/a/b/' f",
                      "sed -i 's/a/b/' f > out"):
        assert hook.repair(untouched, "/r", {}) == (untouched, [])
    monkeypatch.setattr(hook, "_bsd_sed", lambda: False)
    assert hook.repair("sed -i 's/a/b/' f", "/r", {}) == ("sed -i 's/a/b/' f", [])


def test_rewrite_prints_receipt_for_non_check_commands(monkeypatch, tmp_path):
    monkeypatch.setattr(hook, "_bsd_sed", lambda: True)
    new = hook.rewrite("sed -i 's/a/b/' f.txt", str(tmp_path), tmp_path / "state.json", {"all"})
    assert new.startswith("echo '[ai-orch] requested: sed -i <expr>;") and new.endswith("sed -i '' 's/a/b/' f.txt")
