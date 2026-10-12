"""Work-trail retrieval and delivery (scripts/hooks/ai_orch_trail_retrieve.py): scoring, threshold, rendering from the
current source, versioned dedupe shared with the search hook (both directions), refresh after a change, earlier
requests of the same session, compaction, check advice provenance, fail-open fetch."""
from __future__ import annotations

import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "scripts" / "hooks"))
import ai_orch_search_hook as search  # noqa: E402
import ai_orch_session_state as session  # noqa: E402
import ai_orch_trail_retrieve as retrieve  # noqa: E402

MODELS = """class User:
    def follow(self, user):
        self.following.add(user)

    def followers_count(self):
        return self.followers.count()

    def following_count(self):
        return self.following.count()
"""


def trail(key: str, request: str, edited=(), cited=(), checks=()) -> dict:
    return {"trailKey": key, "requestText": request, "updatedAt": "2026-10-11T09:00:00Z",
            "edited": [{"path": p, "symbol": s, "kind": "def"} for p, s in edited],
            "cited": [{"path": p, "symbol": s, "kind": "def"} for p, s in cited], "supportingReads": [],
            "checks": list(checks)}


FOLLOWERS = trail("s1:1", "The follower count on the profile page shows the number of followed users instead of followers",
                  edited=[("app/models.py", "followers_count")],
                  checks=[{"command": "python -m unittest -q tests", "cwd": "ROOT", "scope": "compile,tests",
                           "status": "passed", "testEvidence": "ran"}])
TOKENS = trail("s1:2", "API tokens expire too early: a token issued an hour ago is rejected with 401",
               edited=[("app/models.py", "check_token")])
SEARCH = trail("s2:1", "Search results page shows deleted posts", edited=[("app/search.py", "query_index")])


@pytest.fixture
def repo(tmp_path: Path) -> Path:
    (tmp_path / "app").mkdir()
    (tmp_path / "app" / "models.py").write_text(MODELS)
    return tmp_path


def test_related_prompt_selects_its_trail_and_unrelated_prompt_selects_nothing():
    trails = [FOLLOWERS, TOKENS, SEARCH]
    picked = retrieve.select("The following count on the profile page is wrong too, it shows followers", trails)
    assert picked and picked[1] is FOLLOWERS
    assert retrieve.select("Add a dark mode toggle to the settings screen", trails) is None


def test_trails_without_locators_are_never_selected():
    bare = trail("s3:1", "The follower count on the profile page shows the number of followed users instead of followers")
    assert retrieve.select(bare["requestText"], [bare]) is None


def with_root(t: dict, root: Path, sub: str = "") -> dict:
    return dict(t, checks=[dict(c, cwd=str(root / sub) if c["cwd"] == "ROOT" else c["cwd"]) for c in t["checks"]])


def test_render_shows_current_source_and_check_provenance(repo: Path):
    text, keys = retrieve.render(with_root(FOLLOWERS, repo), str(repo), set())
    assert "app/models.py:5-6" in text and "    5      def followers_count(self):" in text
    assert "`python -m unittest -q tests` (run in `.`, proved: compile,tests, on 2026-10-11)" in text
    assert keys == [search.block_key("app/models.py", MODELS.splitlines(), 4, 6)]
    (repo / "app" / "sub").mkdir()
    assert "run in `app/sub`" in retrieve.render(with_root(FOLLOWERS, repo, "app/sub"), str(repo), set())[0]


def test_advice_is_omitted_when_the_check_ran_outside_this_checkout_or_has_no_directory(repo: Path):
    elsewhere = with_root(FOLLOWERS, repo.parent / f"{repo.name}-other-checkout")
    text, _ = retrieve.render(elsewhere, str(repo), set())
    assert "followers_count" in text and "Advice" not in text
    no_cwd = dict(FOLLOWERS, checks=[dict(FOLLOWERS["checks"][0], cwd=None)])
    assert "Advice" not in retrieve.render(no_cwd, str(repo), set())[0]


def test_unchanged_code_is_not_repeated_and_changed_code_at_the_same_line_is(repo: Path):
    _, keys = retrieve.render(FOLLOWERS, str(repo), set())
    assert retrieve.render(FOLLOWERS, str(repo), set(keys)) == ("", [])
    (repo / "app" / "models.py").write_text(MODELS.replace("self.followers.count()", "len(self.followers)"))
    text, new_keys = retrieve.render(FOLLOWERS, str(repo), set(keys))
    assert "len(self.followers)" in text and "app/models.py:5-6" in text and new_keys != keys
    # moved and unchanged otherwise: a new line, so shown again
    (repo / "app" / "models.py").write_text("import x\n\n" + MODELS)
    assert "app/models.py:7-8" in retrieve.render(FOLLOWERS, str(repo), set(keys + new_keys))[0]


def test_search_hook_and_trail_delivery_share_keys_both_ways(repo: Path):
    # the search hook showed the definition: the trail does not show it again
    text, keys = search.enrich([("app/models.py", 5, "    def followers_count(self):")], repo, set())
    assert "followers_count" in text
    assert retrieve.render(FOLLOWERS, str(repo), set(keys)) == ("", [])
    # the trail showed it: the search hook does not show it again
    _, keys = retrieve.render(FOLLOWERS, str(repo), set())
    assert search.enrich([("app/models.py", 5, "    def followers_count(self):")], repo, set(keys)) == ("", [])


def test_render_skips_missing_and_ambiguous_symbols(repo: Path):
    gone = trail("s4:1", "x", edited=[("app/models.py", "removed_method"), ("app/missing.py", "f")])
    assert retrieve.render(gone, str(repo), set()) == ("", [])
    (repo / "app" / "models.py").write_text(MODELS + "\n\nclass Other:\n    def followers_count(self):\n        return 0\n")
    assert retrieve.render(FOLLOWERS, str(repo), set()) == ("", [])


PROMPT = "The following count on the profile page is wrong too, it shows followers"


def test_earlier_requests_of_the_same_session_are_retrieved_but_not_the_current_one(repo: Path, monkeypatch):
    monkeypatch.setattr(retrieve, "fetch", lambda root, client: [FOLLOWERS, TOKENS])
    state: dict = {"request": {"ordinal": 1}}
    current = dict(FOLLOWERS, trailKey="s1:2")
    monkeypatch.setattr(retrieve, "fetch", lambda root, client: [current])
    state["request"]["ordinal"] = 2
    assert retrieve.context(str(repo), PROMPT, state, "s1", "claude-code") == ""  # the request being answered
    monkeypatch.setattr(retrieve, "fetch", lambda root, client: [FOLLOWERS, TOKENS])
    state["request"]["ordinal"] = 3
    assert "followers_count" in retrieve.context(str(repo), PROMPT, state, "s1", "claude-code")  # request 1
    assert state["trail_log"][-1]["picked"] == "s1:1" and state["trail_log"][0]["picked"] is None
    assert retrieve.context(str(repo), PROMPT, state, "s1", "claude-code") == ""  # already shown, unchanged


def test_after_a_compaction_delivered_code_is_shown_again(repo: Path, tmp_path: Path, monkeypatch):
    monkeypatch.setattr(retrieve, "fetch", lambda root, client: [FOLLOWERS])
    transcript = tmp_path / "t.jsonl"
    transcript.write_text('{"type":"user"}\n')
    state: dict = {"request": {"ordinal": 2}}
    session.forget_after_compaction(state, str(transcript))
    assert retrieve.context(str(repo), PROMPT, state, "s9", "claude-code")
    session.forget_after_compaction(state, str(transcript))
    assert retrieve.context(str(repo), PROMPT, state, "s9", "claude-code") == ""
    transcript.write_text('{"type":"user"}\n{"type":"system","subtype":"compact_boundary"}\n')
    session.forget_after_compaction(state, str(transcript))
    assert retrieve.context(str(repo), PROMPT, state, "s9", "claude-code")
    transcript.write_text(transcript.read_text() + '{"type": "compacted", "payload": {}}\n')  # Codex rollout
    session.forget_after_compaction(state, str(transcript))
    assert state["compactions"] == 2 and retrieve.context(str(repo), PROMPT, state, "s9", "claude-code")


def test_decision_log_is_bounded_on_both_branches(repo: Path, monkeypatch):
    monkeypatch.setattr(retrieve, "fetch", lambda root, client: [FOLLOWERS])
    state: dict = {"request": {"ordinal": 1}}
    for _ in range(25):
        retrieve.context(str(repo), "Add a dark mode toggle to the settings screen", state, "s9", "claude-code")
    assert len(state["trail_log"]) == 20


def test_fetch_fails_open(monkeypatch):
    monkeypatch.setenv("AI_ORCH_URL", "http://127.0.0.1:9")
    assert retrieve.fetch("/tmp", "claude-code", timeout=0.2) == []


# ------------------------------------------------------------------ references, reads, historical examples
import subprocess  # noqa: E402

HIST = "    def followers_count(self):\n        return len(self.followers)"


@pytest.fixture
def git_repo(repo: Path) -> Path:
    (repo / "app" / "auth.py").write_text("def verify_token(token):\n    return token.strip()\n")
    for args in (["init", "-q"], ["add", "-A"]):
        subprocess.run(["git", "-C", str(repo), *args], check=True, capture_output=True)
    return repo


def gone_trail(**extra) -> dict:
    t = trail("s5:1", "Fix the follower count", edited=[("app/models.py", "followers_count")])
    t["edited"][0].update(text=HIST, line=5, tree="abcdef0123456789", hash="h1", **extra)
    return t


def remove_followers_count(repo: Path) -> None:
    (repo / "app" / "models.py").write_text(MODELS.replace(
        "    def followers_count(self):\n        return self.followers.count()\n\n", ""))


def test_historical_text_only_for_a_confirmed_absent_edited_definition(git_repo: Path):
    remove_followers_count(git_repo)
    text, keys = retrieve.render(gone_trail(), str(git_repo), set())
    assert "historical example only, not current code" in text and "len(self.followers)" in text
    assert "tree abcdef0123" in text and "(current source)" not in text
    assert keys == ["hist:app/models.py:followers_count:h1"]
    # a cited / read / referenced locator never falls back to captured text
    cited = gone_trail()
    cited["edited"][0]["kind"] = "read"
    assert retrieve.render(cited, str(git_repo), set()) == ("", [])


def test_existing_or_moved_definitions_win_over_captured_text(git_repo: Path):
    # still there with other content: the current body, never the old one
    text, _ = retrieve.render(gone_trail(), str(git_repo), set())
    assert "self.followers.count()" in text and "historical example only" not in text
    # gone from its file; a same-named definition with other content elsewhere is not assumed to be it
    remove_followers_count(git_repo)
    (git_repo / "app" / "stats.py").write_text("class Stats:\n    def followers_count(self):\n        return 7\n")
    subprocess.run(["git", "-C", str(git_repo), "add", "-A"], check=True)
    text, _ = retrieve.render(gone_trail(), str(git_repo), set())
    assert "historical example only" in text and "app/stats.py" not in text
    # moved unchanged (identical text) to another file: found there, current source
    (git_repo / "app" / "stats.py").write_text("class Stats:\n" + HIST + "\n")
    subprocess.run(["git", "-C", str(git_repo), "add", "-A"], check=True)
    import hashlib
    moved = gone_trail()
    moved["edited"][0]["hash"] = hashlib.sha1(HIST.encode()).hexdigest()[:16]
    text, _ = retrieve.render(moved, str(git_repo), set())
    assert "app/stats.py:2-3 (current source)" in text and "historical example only" not in text


def test_historical_and_current_keys_are_separate(git_repo: Path):
    remove_followers_count(git_repo)
    _, hist_keys = retrieve.render(gone_trail(), str(git_repo), set())
    (git_repo / "app" / "models.py").write_text(MODELS)  # the definition is back
    text, keys = retrieve.render(gone_trail(), str(git_repo), set(hist_keys))
    assert "(current source)" in text and keys and not keys[0].startswith("hist:")


def test_definitions_matching_the_prompt_come_first(git_repo: Path):
    t = trail("s6:1", "Fix the follower count", edited=[("app/models.py", "followers_count")])
    t["supportingReads"] = [{"path": "app/auth.py", "symbol": "verify_token", "kind": "ref"}]
    text, keys = retrieve.render(t, str(git_repo), set(), prompt="The token check rejects valid tokens")
    assert text.index("app/auth.py:1-2") < text.index("app/models.py:5-6")
    text, _ = retrieve.render(t, str(git_repo), set(), prompt="Unrelated words only")
    assert text.index("app/models.py:5-6") < text.index("app/auth.py:1-2")  # edited before referenced


def test_bounds_two_excerpts_25_lines_6000_chars(git_repo: Path):
    big = "def big():\n" + "".join(f"    x{i} = {i}\n" for i in range(60))
    (git_repo / "app" / "big.py").write_text(big)
    t = trail("s7:1", "x", edited=[("app/big.py", "big"), ("app/models.py", "followers_count"),
                                   ("app/auth.py", "verify_token")])
    text, keys = retrieve.render(t, str(git_repo), set())
    assert len(keys) == 2 and len(text) <= retrieve.MAX_CHARS
    assert "   25      x23 = 23" in text and "x24 = 24" not in text and "(36 more lines)" in text


def test_failed_or_unknown_checks_give_no_advice(repo: Path):
    for status in ("failed", "unknown", "timeout"):
        t = with_root(dict(FOLLOWERS, checks=[dict(FOLLOWERS["checks"][0], status=status)]), repo)
        assert "Advice" not in retrieve.render(t, str(repo), set())[0]


def test_a_definition_inside_an_excerpt_already_shown_is_not_repeated(git_repo: Path):
    t = trail("s8:1", "x", edited=[("app/models.py", "User"), ("app/models.py", "followers_count"),
                                   ("app/auth.py", "verify_token")])
    text, keys = retrieve.render(t, str(git_repo), set())
    assert "app/models.py:1-" in text and "app/models.py:5-6" not in text and "app/auth.py:1-2" in text


def test_identical_copies_elsewhere_are_ambiguous_not_a_move(git_repo: Path):
    import hashlib
    remove_followers_count(git_repo)
    for name in ("a.py", "b.py"):
        (git_repo / "app" / name).write_text("class X:\n" + HIST + "\n")
    subprocess.run(["git", "-C", str(git_repo), "add", "-A"], check=True)
    t = gone_trail()
    t["edited"][0]["hash"] = hashlib.sha1(HIST.encode()).hexdigest()[:16]
    assert retrieve.render(t, str(git_repo), set()) == ("", [])  # neither current nor historical


def test_a_failed_lookup_never_enables_the_historical_fallback(git_repo: Path, monkeypatch):
    remove_followers_count(git_repo)
    monkeypatch.setattr(retrieve.capture, "_definitions", lambda root, names: None)  # git grep error / timeout
    assert retrieve.render(gone_trail(), str(git_repo), set()) == ("", [])


def test_lookup_reports_failure_separately_from_no_match(git_repo: Path, tmp_path: Path):
    assert retrieve.capture._definitions(str(git_repo), ["no_such_name_here"]) == {"no_such_name_here": []}
    assert retrieve.capture._definitions(str(tmp_path / "not-a-repo"), ["x_name"]) is None
