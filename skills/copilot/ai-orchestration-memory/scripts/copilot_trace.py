#!/usr/bin/env python3
"""A local trace of the Copilot hooks (trace contract v1), for a reader such as an IDE panel: which hook ran, which
memory cards and repository paths it delivered, how many characters it added to the model's context.

One JSON line per event in `${COPILOT_HOME:-~/.copilot}/ai-orch/trace/YYYY-MM-DD.jsonl` (UTC day; directory 0700,
file 0600). `AI_ORCH_TRACE=0` turns it off, `AI_ORCH_TRACE_DIR` moves it. Each line is appended with one `write` on an
`O_APPEND` descriptor (atomic per line) and is at most 16 KiB. Files older than 30 days are removed when a new day's
file is started. Every line of one hook run carries the same `call` id; `durationMs` is the time since the run began.

Safe target: the trace directory (and `ai-orch` above it at the default location) and the day file must be the user's
own, no symbolic link, and closed to group and others (0700 / 0600). A loose mode is tightened first; a target that
is someone else's, a link, or cannot be tightened is not written.

Privacy: no prompt, answer, command or tool output text is written; the prompt only as its length and a short hash.
A tool line names the repository files of the tool's path fields (never a pattern, query or content) and, for an
`ai_orch memory` command, only its sub-command and card UUIDs.
Card summaries come from the memory store, not from the user. Writing never raises and never delays the hook: in a
hook run (`hold` ... `flush`) lines are kept in memory and written after the answer is out, within FLUSH_SECONDS; a
trace that cannot be written in time is dropped.
"""
from __future__ import annotations

import datetime as _dt
import hashlib
import json
import math
import os
import re
import secrets
import shlex
import stat
import threading
import time
import uuid
from pathlib import Path
from typing import Any

VERSION = 1
MAX_LINE = 16 * 1024          # bytes per line, newline included
KEEP_DAYS = 30
SUMMARY_CHARS = 160
CLIENTS = ("cli", "vscode", "app", "team")  # team: Copilot Team VS Code extension
CLIENT_ENV = "AI_ORCH_TRACE_CLIENT"  # set by whoever starts Copilot and knows the surface; never guessed here
DAY_FILE = re.compile(r"^(\d{4}-\d{2}-\d{2})\.jsonl$")
LOCATED = re.compile(r"^(\S+?):\d+-\d+(?:\s|$)")
FLUSH_SECONDS = 0.2           # the whole trace I/O of a hook run, after its answer
_DIR = os.O_RDONLY | getattr(os, "O_DIRECTORY", 0) | getattr(os, "O_CLOEXEC", 0)
_NOFOLLOW = getattr(os, "O_NOFOLLOW", 0)

# one hook run (`hold` ... `flush`): its call id, its start, and its lines kept until the answer is out
_run: dict[str, Any] = {}


def enabled() -> bool:
    return os.environ.get("AI_ORCH_TRACE", "").strip() != "0"


def directory() -> Path:
    return _location()[0]


def _location() -> tuple[Path, int]:
    """The trace directory and how many of its last path components are the trace's own (checked strictly): the
    directory alone when chosen with AI_ORCH_TRACE_DIR, `ai-orch/trace` at the default location."""
    custom = os.environ.get("AI_ORCH_TRACE_DIR", "").strip()
    if custom:
        return Path(custom).expanduser(), 1
    home = os.environ.get("COPILOT_HOME", "").strip()
    return (Path(home).expanduser() if home else Path.home() / ".copilot") / "ai-orch" / "trace", 2


def hold() -> None:
    """A hook run begins: one call id for all its lines, which are kept in memory until `flush`."""
    _run.clear()
    _run.update(call=secrets.token_hex(6), started=time.monotonic(), lines=[])


def flush(seconds: float = FLUSH_SECONDS) -> None:
    """Write the run's kept lines (after the hook's answer is out), giving the disk at most `seconds`; a writer
    still busy then is left behind (daemon thread) and what it did not write is dropped."""
    lines = _run.get("lines") or []
    _run.clear()
    if not lines:
        return
    try:
        worker = threading.Thread(target=_write_all, args=(lines, time.monotonic() + seconds), daemon=True)
        worker.start()
        worker.join(seconds)
    except Exception:  # noqa: BLE001
        pass


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


# A tool call's `access` and the argument fields its `paths` come from (never a pattern, query, content or command).
READ_TOOLS = frozenset({"view", "read", "read_file"})
WRITE_TOOLS = frozenset({"create", "edit", "str_replace", "write", "write_file", "edit_file", "create_file",
                         "apply_patch", "replace_string_in_file", "multi_replace_string_in_file",
                         "insert_edit_into_file"})
SEARCH_TOOLS = frozenset({"rg", "grep", "glob", "search", "list_files", "list_dir", "grep_search", "file_search"})
RUN_TOOLS = frozenset({"bash", "shell", "run_command", "run_in_terminal"})
PATH_FIELDS = ("path", "paths", "file", "files", "filePath", "filePaths", "file_path")
SEARCH_PATH_FIELDS = ("path", "paths")  # where a search looks; its pattern / query is never read
MAX_PATHS = 20
MAX_PATH_CHARS = 1024
PATCH_FILE = re.compile(r"^\*\*\* (?:(?:Add|Update|Delete) File|Move to): (.+)$")  # apply_patch headers: the path only
MEMORY_OPS = frozenset({"search", "learn", "update", "archive"})
SHELL_BREAK = frozenset({";", "&&", "||", "|", "&", "|&", ";;", "(", ")"})
ENV_ASSIGN = re.compile(r"^[A-Za-z_][A-Za-z0-9_]*=")
PYTHON = re.compile(r"^python(?:\d+(?:\.\d+)?)?$")


def tool_args(raw: Any) -> Any:
    """The hook's `toolArgs`: an object, or a JSON text of one (kept as text when it is not, e.g. a patch)."""
    if isinstance(raw, str):
        try:
            parsed = json.loads(raw)
        except ValueError:
            return raw
        return parsed if isinstance(parsed, dict) else raw
    return raw if isinstance(raw, dict) else {}


def tool_access(tool_name: str, args: Any) -> str:
    """read | write | search | run | other, from the tool's name (`str_replace_editor` by its own sub-command)."""
    if tool_name == "str_replace_editor":
        sub = args.get("command") if isinstance(args, dict) else None
        return "read" if sub == "view" else "write" if isinstance(sub, str) and sub else "other"
    for access, names in (("read", READ_TOOLS), ("write", WRITE_TOOLS), ("search", SEARCH_TOOLS),
                          ("run", RUN_TOOLS)):
        if tool_name in names:
            return access
    return "other"


def tool_paths(tool_name: str, args: Any, cwd: str, root: str | None) -> list[str]:
    """The files a tool call names in its path fields, repository-relative: at most MAX_PATHS, each at most
    MAX_PATH_CHARS. Nothing from a shell command, a pattern or a query; a path with `..`, a NUL or a glob, or one
    outside the repository is left out, and so is the repository root itself."""
    access = tool_access(tool_name, args)
    if not root or access in ("run", "other"):
        return []
    raw: list[Any] = []
    if isinstance(args, str):
        if tool_name == "apply_patch":
            raw = [m.group(1) for m in map(PATCH_FILE.match, args.splitlines()) if m]
    elif isinstance(args, dict):
        if tool_name == "apply_patch" and isinstance(args.get("input"), str):
            raw = [m.group(1) for m in map(PATCH_FILE.match, args["input"].splitlines()) if m]
        for field in SEARCH_PATH_FIELDS if access == "search" else PATH_FIELDS:
            value = args.get(field)
            raw.extend(value if isinstance(value, list) else [value])
    paths: list[str] = []
    for value in raw:
        if len(paths) >= MAX_PATHS:
            break
        entry = repo_relative(value, cwd, root)
        if entry and entry not in paths:
            paths.append(entry)
    return paths


def repo_relative(value: Any, cwd: str, root: str) -> str | None:
    if not isinstance(value, str):
        return None
    value = value.strip()
    if not value or len(value) > MAX_PATH_CHARS or any(c in value for c in "\x00*?\n\r"):
        return None
    if ".." in value.replace("\\", "/").split("/"):
        return None
    base = cwd if cwd.startswith("/") else root
    full = os.path.normpath(os.path.join(base, os.path.expanduser(value) if value.startswith("~") else value))
    top = os.path.normpath(root)
    for candidate in (full, os.path.realpath(full)):
        if candidate.startswith(top.rstrip("/") + "/"):
            rel = candidate[len(top.rstrip("/")) + 1:]
            return rel if rel and len(rel) <= MAX_PATH_CHARS else None
    return None


def memory_op(command: str) -> dict[str, Any] | None:
    """`{op, cardIds?}` for the first `ai_orch memory <search|learn|update|archive>` call in a shell command (at a
    command position: the start or after `;`, `&&`, `|` ..., past `NAME=value` and a python interpreter). Only the
    sub-command and the canonical UUIDs given with `--memory-id` are kept; no query, summary or other text."""
    try:
        lexer = shlex.shlex(command, posix=True, punctuation_chars=True)
        lexer.whitespace_split = True
        tokens = list(lexer)
    except ValueError:
        return None
    start = 0
    while start < len(tokens):
        end = next((i for i in range(start, len(tokens)) if tokens[i] in SHELL_BREAK), len(tokens))
        segment = tokens[start:end]
        start = end + 1
        i = 0
        while i < len(segment) and ENV_ASSIGN.match(segment[i]):
            i += 1
        if i < len(segment) and PYTHON.match(os.path.basename(segment[i])):
            i += 1
        if i >= len(segment) or os.path.basename(segment[i]) not in ("ai_orch", "ai_orch.py"):
            continue
        if segment[i + 1:i + 2] != ["memory"]:
            continue
        op = segment[i + 2] if len(segment) > i + 2 else ""
        if op not in MEMORY_OPS:
            return None  # the first memory call is not one the trace counts
        ids: list[str] = []
        rest = segment[i + 3:]
        for j, token in enumerate(rest):
            value = rest[j + 1] if token == "--memory-id" and j + 1 < len(rest) else \
                token.split("=", 1)[1] if token.startswith("--memory-id=") else None
            card = canonical_uuid(value)
            if card and card not in ids:
                ids.append(card)
        return {"op": op, "cardIds": ids[:MAX_PATHS]} if ids else {"op": op}
    return None


def canonical_uuid(value: Any) -> str | None:
    if not isinstance(value, str) or len(value) > 45:
        return None
    try:
        return str(uuid.UUID(value))
    except ValueError:
        return None


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
        self.call = str(_run.get("call") or secrets.token_hex(6))
        self.started = float(_run.get("started") or time.monotonic())
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
            record["call"] = self.call
            record["durationMs"] = int((time.monotonic() - self.started) * 1000)  # since the run began
            surface = client()
            if surface:
                record["client"] = surface
            record.update(fields)
            line = encode(record)
            kept = _run.get("lines")
            if isinstance(kept, list):
                kept.append((line, now))  # written by `flush`, after the answer
            else:
                _write_all([(line, now)], time.monotonic() + FLUSH_SECONDS)
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
        """What a hook added to the model's context: its parts and, per card, (card, chars, codeChars)."""
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
    """One line of at most MAX_LINE bytes: an oversized record loses a tool's paths from the end, card paths first,
    then cards from the end (`parts.cards` still counts them all), then long strings; the common fields always stay."""
    line = _dump(record)
    if len(line) <= MAX_LINE:
        return line
    record = dict(record)
    if isinstance(record.get("paths"), list):  # a tool line: whole paths from the end, never a cut path
        while len(line) > MAX_LINE and record["paths"]:
            record["paths"] = record["paths"][:-1]
            line = _dump(record)
        if not record["paths"]:
            del record["paths"]
            line = _dump(record)
        if len(line) <= MAX_LINE:
            return line
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
    keep = ("v", "ts", "sessionId", "hook", "event", "call", "durationMs", "client")
    return _dump({k: _cut(record[k], 40) for k in keep if k in record})


def write(line: bytes, now: _dt.datetime) -> None:
    """One line, now (outside a hook run)."""
    _write_all([(line, now)], time.monotonic() + FLUSH_SECONDS)


def _write_all(lines: list[tuple[bytes, _dt.datetime]], deadline: float) -> None:
    """Append each line to its day's file with one `write`, until `deadline`; never raises."""
    try:
        folder = _open_folder()
    except Exception:  # noqa: BLE001
        return
    if folder is None:
        return
    try:
        new_day = None
        for line, now in lines:
            if time.monotonic() > deadline:
                return
            day = now.strftime("%Y-%m-%d")
            try:
                if _append(folder, f"{day}.jsonl", line):
                    new_day = now
            except OSError:
                return
        if new_day is not None and time.monotonic() <= deadline:
            cleanup(folder, new_day, deadline)
    except Exception:  # noqa: BLE001
        pass
    finally:
        os.close(folder)


def _owned(fd: int, kind: int, mode: int) -> bool:
    """`fd` is a `kind` (directory or regular file) of this user with no permission bits beyond `mode`; looser
    bits are removed first, and when that fails the target is refused."""
    st = os.fstat(fd)
    if stat.S_IFMT(st.st_mode) != kind or st.st_uid != os.getuid():
        return False
    if kind == stat.S_IFREG and st.st_nlink != 1:
        return False
    if stat.S_IMODE(st.st_mode) & ~mode:
        try:
            os.fchmod(fd, stat.S_IMODE(st.st_mode) & mode)
        except OSError:
            return False
        return not stat.S_IMODE(os.fstat(fd).st_mode) & ~mode
    return True


def _open_folder() -> int | None:
    """A descriptor of the trace directory, reached without following a link through its own components (created
    0700 when missing), or None when it or one of them is not safe. The base above them (e.g. `~/.copilot`) is the
    user's choice and may be a link, but must not be writable by others unless sticky (like /tmp)."""
    folder, own = _location()
    parts = folder.parts[-own:]
    base = Path(*folder.parts[:-own]) if len(folder.parts) > own else Path(folder.anchor or ".")
    base.mkdir(parents=True, exist_ok=True, mode=0o700)
    fd = os.open(base, _DIR)
    try:
        st = os.fstat(fd)
        loose = stat.S_IMODE(st.st_mode) & 0o022 and not st.st_mode & stat.S_ISVTX
        if not stat.S_ISDIR(st.st_mode) or st.st_uid not in (os.getuid(), 0) or loose:
            os.close(fd)
            return None
        for name in parts:
            try:
                child = os.open(name, _DIR | _NOFOLLOW, dir_fd=fd)
            except FileNotFoundError:
                try:
                    os.mkdir(name, 0o700, dir_fd=fd)
                except FileExistsError:
                    pass
                child = os.open(name, _DIR | _NOFOLLOW, dir_fd=fd)
            os.close(fd)
            fd = child
            if not _owned(fd, stat.S_IFDIR, 0o700):
                os.close(fd)
                return None
        return fd
    except OSError:
        os.close(fd)
        return None


def _append(folder: int, name: str, line: bytes) -> bool:
    """Append one line to `name` in `folder` (never through a link, only to this user's 0600 file); True when the
    file was new (empty before this line)."""
    flags = os.O_WRONLY | os.O_APPEND | os.O_CREAT | _NOFOLLOW | getattr(os, "O_NONBLOCK", 0) \
        | getattr(os, "O_CLOEXEC", 0)
    fd = os.open(name, flags, 0o600, dir_fd=folder)
    try:
        if not _owned(fd, stat.S_IFREG, 0o600):
            return False
        new = os.fstat(fd).st_size == 0
        os.write(fd, line)
        return new
    finally:
        os.close(fd)


def cleanup(folder: int, now: _dt.datetime, deadline: float | None = None) -> None:
    """Remove day files whose UTC day is more than KEEP_DAYS days before today's (at most once a day: when the day's
    file is started), until `deadline`."""
    oldest = (now - _dt.timedelta(days=KEEP_DAYS)).date()
    for name in os.listdir(folder):
        if deadline is not None and time.monotonic() > deadline:
            return
        match = DAY_FILE.match(name)
        if not match:
            continue
        try:
            if _dt.date.fromisoformat(match.group(1)) < oldest:
                os.unlink(name, dir_fd=folder)
        except (ValueError, OSError):
            continue
