"""J1: the PostToolUse search hook adds the definitions around a search's hits (current source, line numbers)."""
from __future__ import annotations

import json
import subprocess
import uuid
import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[3]
HOOK = ROOT / "scripts" / "hooks" / "ai_orch_search_hook.py"
sys.path.insert(0, str(ROOT / "scripts" / "hooks"))
import ai_orch_search_hook as hook  # noqa: E402

MODELS = '''class User(Base):
    id = 1

    def follow(self, user):
        self.following.add(user)

    def followers_count(self):
        return count(self.following)

    def following_count(self):
        return count(self.following)
'''
KOTLIN = '''class Rewards {
    fun forTetris(score: Int): Int {
        val coins = score * PER_1000 / 100
        return coins
    }

    fun forAds(): Int = 5
}
'''


@pytest.fixture
def repo(tmp_path):
    (tmp_path / "app").mkdir()
    (tmp_path / "app" / "models.py").write_text(MODELS)
    (tmp_path / "app" / "Rewards.kt").write_text(KOTLIN)
    (tmp_path / "tests.py").write_text("def test_follow():\n    assert u.followers_count() == 1\n")
    return tmp_path


RUN = uuid.uuid4().hex[:8]  # session state lives in a temp file: fresh sessions on every test run


def run_hook(event: dict) -> str:
    event = dict(event, session_id=f"{event.get('session_id')}-{RUN}")
    out = subprocess.run([sys.executable, str(HOOK)], input=json.dumps(event), capture_output=True, text=True).stdout
    return json.loads(out)["hookSpecificOutput"]["additionalContext"] if out.strip() else ""


def transcript(tmp_path: Path, prompt: str) -> str:
    path = tmp_path / "t.jsonl"
    path.write_text(json.dumps({"type": "user", "message": {"role": "user", "content": prompt}}) + "\n")
    return str(path)


def grep_event(repo: Path, content: str, session: str, path: str | None = None, prompt: str = "") -> dict:
    return {"tool_name": "Grep", "tool_input": {"pattern": "x", "output_mode": "content", **({"path": path} if path else {})},
            "tool_response": {"mode": "content", "content": content}, "cwd": str(repo), "session_id": session,
            "transcript_path": transcript(repo, prompt) if prompt else None}


def test_grep_hits_get_the_enclosing_definition(repo):
    text = run_hook(grep_event(repo, "app/models.py:7:    def followers_count(self):", "s1",
                               prompt="The follower count is wrong"))
    assert "app/models.py:7-9" in text and "    7      def followers_count(self):" in text
    assert "class User" not in text  # the enclosing class is too large a block to be an answer


def test_single_file_grep_uses_the_path_argument_and_kotlin_braces(repo):
    text = run_hook(grep_event(repo, "3:        val coins = score * PER_1000 / 100", "s2",
                               path=str(repo / "app" / "Rewards.kt"), prompt="tetris coins wrong"))
    assert "app/Rewards.kt:2-5" in text and "fun forTetris" in text and "forAds" not in text


def test_task_words_rank_the_matching_definition_first(repo):
    content = "\n".join(["app/models.py:5:        self.following.add(user)",
                         "app/models.py:8:        return count(self.following)",
                         "app/models.py:11:        return count(self.following)"])
    text = run_hook(grep_event(repo, content, "s3", prompt="Followers count shows the wrong number"))
    assert "followers_count" in text and "def follow(" not in text  # one hit, no shared word: not shown


def test_broad_empty_listing_and_piped_searches_add_nothing(repo):
    many = "\n".join(f"app/models.py:{i % 11 + 1}:x" for i in range(40))
    assert run_hook(grep_event(repo, many, "s4")) == ""
    assert run_hook(grep_event(repo, "", "s5")) == ""
    listing = {"tool_name": "Grep", "tool_input": {}, "tool_response": {"mode": "files_with_matches",
               "filenames": ["app/models.py"]}, "cwd": str(repo), "session_id": "s6"}
    assert run_hook(listing) == ""
    piped = {"tool_name": "Bash", "tool_input": {"command": "grep -n follow app/models.py | head"},
             "tool_response": {"stdout": "app/models.py:4:    def follow(self, user):"}, "cwd": str(repo),
             "session_id": "s7"}
    assert run_hook(piped) == ""


def test_bash_grep_output_is_used(repo):
    event = {"tool_name": "Bash", "tool_input": {"command": "grep -rn 'def follow' app"},
             "tool_response": {"stdout": "app/models.py:4:    def follow(self, user):"}, "cwd": str(repo),
             "session_id": "s8"}
    assert "app/models.py:4-6" in run_hook(event)


def test_definitions_are_shown_once_per_session_and_budgeted(repo, monkeypatch):
    event = grep_event(repo, "app/models.py:7:    def followers_count(self):", "s9")
    assert run_hook(event)
    assert run_hook(event) == ""  # already shown in this session
    text, keys = hook.enrich([("app/models.py", 7, "")], repo, {"app/models.py:7"})
    assert (text, keys) == ("", [])


def test_lever_off_adds_nothing(repo, monkeypatch):
    event = grep_event(repo, "app/models.py:7:    def followers_count(self):", "s10")
    event = dict(event, session_id=f"s10-{RUN}")
    out = subprocess.run([sys.executable, str(HOOK)], input=json.dumps(event), capture_output=True, text=True,
                         env={"AI_ORCH_LEVERS": "profile,codemap", "PATH": "/usr/bin:/bin"}).stdout
    assert out == ""


PY_WITH_DICT = """def config():
    opts = {
        "a": 1,
    }
    return opts
"""


def test_single_file_bash_search_and_cd(repo):
    (repo / "src").mkdir()
    (repo / "src" / "module.py").write_text(PY_WITH_DICT)
    single = {"tool_name": "Bash", "tool_input": {"command": "rg -n opts src/module.py"},
              "tool_response": '2:    opts = {\n5:    return opts\n', "cwd": str(repo), "session_id": "s11"}
    text = run_hook(single)
    assert "src/module.py:1-5" in text and "return opts" in text  # a dict does not end a Python function
    cd = {"tool_name": "Bash", "tool_input": {"command": "cd src && grep -n opts module.py"},
          "tool_response": "2:    opts = {\n5:    return opts\n", "cwd": str(repo), "session_id": "s12"}
    assert "src/module.py:1-5" in run_hook(cd)
    named = {"tool_name": "Bash", "tool_input": {"command": "cd src && rg -n opts"},
             "tool_response": "module.py:2:    opts = {\nmodule.py:5:    return opts\n", "cwd": str(repo),
             "session_id": "s13"}
    assert "src/module.py:1-5" in run_hook(named)
    for unsupported in ("cd src; grep -n opts module.py", "grep -n opts $(ls src)", "find . -name '*.py'"):
        assert run_hook(dict(single, tool_input={"command": unsupported}, session_id="s14")) == ""


def test_relative_grep_path_resolves_against_cwd(repo):
    text = run_hook(grep_event(repo, "7:    def followers_count(self):", "s15", path="app/models.py"))
    assert "app/models.py:7-9" in text


def test_stale_truncated_and_low_confidence_add_nothing(repo):
    stale = grep_event(repo, "app/models.py:7:    def stale_name(self):", "s16")
    assert run_hook(stale) == ""
    truncated = grep_event(repo, "app/models.py:7:    def followers_count(self):\n[Output truncated]", "s17")
    assert run_hook(truncated) == ""
    weak = grep_event(repo, "app/models.py:8:        return count(self.following)", "s18", prompt="unrelated words")
    assert run_hook(weak) == ""  # one body hit, nothing in the task points at it
    test_only = grep_event(repo, "tests.py:2:    assert u.followers_count() == 1", "s19", prompt="follower count")
    assert run_hook(test_only) == ""  # test code is not shown unless the task is about tests


def test_event_text_including_header_is_capped(repo):
    big = "def big():\n" + "".join(f"    x{i} = {i}\n" for i in range(25))
    for n in range(6):
        (repo / f"m{n}.py").write_text(big)
    hits = [(f"m{n}.py", 1, "def big():") for n in range(6)]
    text, keys = hook.enrich(hits, repo, set())
    assert len(text) <= hook.EVENT_CHARS and len(keys) <= hook.MAX_SHOWN


def test_a_slow_last_read_emits_nothing(repo, monkeypatch):
    """The clock passes the event deadline while the (only) file is read: no definitions are returned."""
    clock = iter([0.0, 0.1, 5.0, 5.0, 5.0, 5.0])  # start, check before the read, then every later check is late
    monkeypatch.setattr(hook.time, "monotonic", lambda: next(clock, 5.0))
    text, keys = hook.enrich([("app/models.py", 7, "    def followers_count(self):")], repo, set(), deadline=0.8)
    assert (text, keys) == ("", [])


def test_late_event_records_and_emits_nothing(repo, monkeypatch, capsys):
    import io
    event = grep_event(repo, "app/models.py:7:    def followers_count(self):", f"late-{RUN}")
    now = {"t": 0.0}
    monkeypatch.setattr(hook.time, "monotonic", lambda: now["t"])

    def slow_enrich(*args, **kwargs):  # the definitions are found, but only after the deadline
        now["t"] = 5.0
        return "[ai-orch] something", ["app/models.py:7"]
    monkeypatch.setattr(hook, "enrich", slow_enrich)
    monkeypatch.setattr(hook.sys, "stdin", io.StringIO(json.dumps(event)))
    assert hook.main() == 0
    assert capsys.readouterr().out == ""
    state = hook.session.load(hook.session.state_path(f"late-{RUN}", hook.session.repo_root(str(repo))))
    assert not (state.get("j1") or {}).get("delivered")


@pytest.mark.parametrize("line,suffix,expected", [
    ('    node("root") {', ".kt", False),                     # Kotlin DSL call with a trailing lambda
    ("    items.forEach {", ".kt", False),
    ("    fun node(name: String, block: Node.() -> Unit) {", ".kt", True),
    ("    private suspend fun load(id: Long): User {", ".kt", True),
    ("data class Card(val id: Int)", ".kt", True),
    ("    constructor(x: Int) : this() {", ".kt", True),
    ("func (s *Store) Get(id int) (*Item, error) {", ".go", True),
    ("type Store struct {", ".go", True),
    ("\twg.Go(func() {", ".go", False),
    ("    public Owner findById(Integer id) {", ".java", True),
    ("    OwnerRestController(OwnerRepository owners) {", ".java", True),
    ('    assertThat("x") {', ".java", False),
    ("    helper(x);", ".java", False),
    ("  render(props) {", ".js", True),
    ("    def followers_count(self):", ".py", True),
    ("    verify_token(self)  # see note()", ".py", False),
])
def test_definition_lines_need_a_declaration_form(line, suffix, expected):
    assert hook.is_definition(line, suffix != ".py", suffix) is expected
