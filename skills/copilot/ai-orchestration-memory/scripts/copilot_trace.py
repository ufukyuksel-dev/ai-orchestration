#!/usr/bin/env python3
"""A local trace of the Copilot hooks (trace contract v1), for a reader such as an IDE panel: which hook ran, which
memory cards and repository paths it delivered, how many characters it added to the model's context.

One JSON line per event in `${COPILOT_HOME:-~/.copilot}/ai-orch/trace/YYYY-MM-DD.jsonl` (UTC day; directory 0700,
file 0600). `AI_ORCH_TRACE=0` turns it off, `AI_ORCH_TRACE_DIR` moves it. Each line is appended with one `write` on an
`O_APPEND` descriptor (atomic per line) and is at most 16 KiB. Files older than 30 days are removed when a new day's
file is started.

Privacy: no prompt, answer, command or tool output text is written; the prompt only as its length and a short hash.
Card summaries come from the memory store, not from the user. Writing never raises and never waits: a trace that
cannot be written is dropped.
"""
from __future__ import annotations

import datetime as _dt
import hashlib
import json
import math
import os
import re
import time
from pathlib import Path
from typing import Any

VERSION = 1
MAX_LINE = 16 * 1024          # bytes per line, newline included
KEEP_DAYS = 30
SUMMARY_CHARS = 160
CLIENTS = ("cli", "vscode", "app")
CLIENT_ENV = "AI_ORCH_TRACE_CLIENT"  # set by whoever starts Copilot and knows the surface; never guessed here
DAY_FILE = re.compile(r"^(\d{4}-\d{2}-\d{2})\.jsonl$")
LOCATED = re.compile(r"^(\S+?):\d+-\d+(?:\s|$)")


def enabled() -> bool:
    return os.environ.get("AI_ORCH_TRACE", "").strip() != "0"


def directory() -> Path:
    custom = os.environ.get("AI_ORCH_TRACE_DIR", "").strip()
    if custom:
        return Path(custom).expanduser()
    home = os.environ.get("COPILOT_HOME", "").strip()
    return (Path(home).expanduser() if home else Path.home() / ".copilot") / "ai-orch" / "trace"


def prompt_hash(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8", "replace")).hexdigest()[:12]


def tokens_est(chars: int) -> int:
    return math.ceil(chars / 3.5)


def client() -> str | None:
    value = os.environ.get(CLIENT_ENV, "").strip().lower()
    return value if value in CLIENTS else None


def repo_paths(files: Any, root: str | None) -> list[str]:
    """A card's `files` as repository-relative file paths: an entry may carry a line range and a symbol
    (`app/models.py:231-250 app.models.User#to_dict (method)`); absolute paths outside the repository are left out."""
    paths: list[str] = []
    prefix = (root or "").rstrip("/") + "/"
    for item in files if isinstance(files, list) else []:
        entry = str(item).split(" (")[0].strip()
        located = LOCATED.match(entry)
        entry = located.group(1) if located else (entry.split(" ")[0] if entry else "")
        if entry.startswith("/"):
            if not root or not entry.startswith(prefix):
                continue
            entry = entry[len(prefix):]
        if entry and entry not in paths:
            paths.append(entry[:300])
    return paths


def card_entry(card: dict[str, Any], root: str | None, chars: int, code_chars: int) -> dict[str, Any]:
    """The trace's Card: what the server sent about the card and what the hook added for it (`chars` the card's
    inserted line(s) with its code, `codeChars` the code alone). `kind` and `score` only when the server gave them."""
    entry: dict[str, Any] = {"id": str(card.get("id") or ""),
                             "summary": re.sub(r"\s+", " ", str(card.get("summary") or "")).strip()[:SUMMARY_CHARS]}
    if isinstance(card.get("kind"), str) and card["kind"]:
        entry["kind"] = card["kind"][:40]
    entry.update({"pointer": bool(card.get("pointer")), "stale": bool(card.get("stale")),
                  "paths": [] if card.get("pointer") else repo_paths(card.get("files"), root),
                  "codeChars": int(code_chars), "chars": int(chars)})
    score = card.get("score")
    if isinstance(score, (int, float)) and not isinstance(score, bool) and math.isfinite(score):
        entry["score"] = round(float(score), 4)
    return entry


class Hook:
    """The trace of one hook call: the common fields and the time since the call began."""

    def __init__(self, hook: str, session_id: str) -> None:
        self.hook = hook
        self.session_id = session_id
        self.started = time.monotonic()
        self.repo: str | None = None

    def emit(self, event: str, **fields: Any) -> None:
        if not self.session_id or not enabled():
            return
        try:
            now = _dt.datetime.now(_dt.timezone.utc)
            record: dict[str, Any] = {"v": VERSION, "ts": now.strftime("%Y-%m-%dT%H:%M:%S.") +
                                      f"{now.microsecond // 1000:03d}Z", "sessionId": self.session_id[:200],
                                      "hook": self.hook, "event": event}
            if self.repo:
                record["repo"] = self.repo
                record["project"] = Path(self.repo).name
            record["durationMs"] = int((time.monotonic() - self.started) * 1000)
            surface = client()
            if surface:
                record["client"] = surface
            record.update(fields)
            write(encode(record), now)
        except Exception:  # noqa: BLE001 - the trace never breaks or delays a hook
            pass

    def prompt(self, text: str, request: int) -> None:
        """A new user request: the prompt only as its length and hash."""
        try:
            self.emit("prompt", promptChars=len(text), promptHash=prompt_hash(text), request=int(request))
        except Exception:  # noqa: BLE001
            pass

    def inject(self, context: str, profile: bool, learn_line: bool,
               shown: list[tuple[dict[str, Any], int, int]]) -> None:
        """What the prompt hook added to the model's context: its parts and, per card, (card, chars, codeChars)."""
        try:
            cards = [card_entry(card, self.repo, chars, code) for card, chars, code in shown]
            self.emit("inject", parts={"profile": bool(profile), "cards": sum(not c["pointer"] for c in cards),
                                       "pointers": sum(c["pointer"] for c in cards), "learnLine": bool(learn_line)},
                      injected={"chars": len(context), "tokensEst": tokens_est(len(context))}, cards=cards)
        except Exception:  # noqa: BLE001
            pass


def _dump(record: dict[str, Any]) -> bytes:
    return (json.dumps(record, ensure_ascii=False, separators=(",", ":")) + "\n").encode("utf-8", "replace")


def _cut(value: Any, limit: int) -> Any:
    if isinstance(value, str):
        return value[:limit]
    if isinstance(value, list):
        return [_cut(v, limit) for v in value]
    if isinstance(value, dict):
        return {k: _cut(v, limit) for k, v in value.items()}
    return value


def encode(record: dict[str, Any]) -> bytes:
    """One line of at most MAX_LINE bytes: an oversized record loses card paths first, then cards from the end
    (`parts.cards` still counts them all), then long strings; the common fields always stay."""
    line = _dump(record)
    if len(line) <= MAX_LINE:
        return line
    record = dict(record)
    cards = [dict(c) for c in record.get("cards") or [] if isinstance(c, dict)]
    if cards:
        for card in cards:
            card["paths"] = list(card.get("paths") or [])[:5]
        record["cards"] = cards
        line = _dump(record)
        while len(line) > MAX_LINE and record["cards"]:
            record["cards"] = record["cards"][:-1]
            line = _dump(record)
        if len(line) <= MAX_LINE:
            return line
    for limit in (200, 40):
        record = _cut(record, limit)
        line = _dump(record)
        if len(line) <= MAX_LINE:
            return line
    keep = ("v", "ts", "sessionId", "hook", "event", "durationMs", "client")
    return _dump({k: _cut(record[k], 40) for k in keep if k in record})


def write(line: bytes, now: _dt.datetime) -> None:
    folder = directory()
    if not folder.is_dir():
        folder.mkdir(parents=True, exist_ok=True)
        os.chmod(folder, 0o700)  # only a directory this created: a chosen existing one keeps its mode
    path = folder / f"{now.strftime('%Y-%m-%d')}.jsonl"
    new = not path.exists()
    flags = os.O_WRONLY | os.O_APPEND | os.O_CREAT | getattr(os, "O_NONBLOCK", 0) | getattr(os, "O_NOFOLLOW", 0) \
        | getattr(os, "O_CLOEXEC", 0)
    fd = os.open(path, flags, 0o600)
    try:
        os.write(fd, line)
    finally:
        os.close(fd)
    if new:
        cleanup(folder, now)


def cleanup(folder: Path, now: _dt.datetime) -> None:
    """Remove day files whose UTC day is more than KEEP_DAYS days before today's."""
    oldest = (now - _dt.timedelta(days=KEEP_DAYS)).date()
    for name in os.listdir(folder):
        match = DAY_FILE.match(name)
        if not match:
            continue
        try:
            if _dt.date.fromisoformat(match.group(1)) < oldest:
                (folder / name).unlink()
        except (ValueError, OSError):
            continue
