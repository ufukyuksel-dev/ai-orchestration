#!/usr/bin/env python3
"""`PostToolUse` hook for code searches (Claude Code `Grep`, a `grep`/`rg` command in `Bash`; Codex `Bash`): adds the
definitions around the hits to the search result, so the agent can edit or answer in its next call instead of
searching or reading again.

The agent's own search is the question: the hits it got (path:line) are mapped to the enclosing definition (function,
method, class) in the current source and the best one or two are shown with line numbers. Nothing is shown for broad
or empty searches, file listings, or definitions already shown in this session; a session gets at most a fixed budget.
The native search result is never changed. Prints nothing on any error.

Usage: ai_orch_search_hook.py      (hook; event JSON on stdin)
"""
from __future__ import annotations

import hashlib
import json
import os
import re
import shlex
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import ai_orch_session_state as session  # noqa: E402
import ai_orch_memory_gate  # noqa: E402
import ai_orch_step_memory  # noqa: E402

MAX_HITS = 30          # more hits: the search is too broad to say which code it is about
MAX_FILES = 6
MAX_SHOWN = 3          # definitions per search (within EVENT_CHARS)
MAX_LINES = 30         # lines per definition
EVENT_CHARS = 6000     # characters per search, header included (~1.5k tokens)
SESSION_CHARS = 12000  # characters per session (~3k tokens)
DEADLINE_S = 0.8       # per event; past it the search result goes back unchanged
INDENTED = (".py",)    # blocks by indentation; the other languages by braces
BRACED = (".kt", ".kts", ".java", ".go", ".ts", ".tsx", ".js", ".jsx", ".swift", ".rs", ".cs", ".scala")
SOURCE = INDENTED + BRACED
HIT = re.compile(r"^(?:(?P<path>[^:\n]+?):)?(?P<line>\d+)[:-](?P<text>.*)$")
TRUNCATED = re.compile(r"\[?(output )?truncated\b|\.\.\. \(\d+ more|results? limited", re.I)
# options of grep / rg that take a separate value (the value is not the pattern or a path)
VALUE_OPTS = {"-e", "-f", "-m", "-A", "-B", "-C", "-d", "-D", "-g", "-t", "-T", "--include", "--exclude",
              "--exclude-dir", "--glob", "--type", "--type-not", "--max-count", "--context", "--after-context",
              "--before-context", "--regexp", "--file", "--max-depth"}
HEADER = ("[ai-orch] Definitions around these matches (current source, with line numbers; enough to edit or answer "
          "without reading the file again):\n")
DEFINITION = re.compile(
    r"^\s*(?:(?:public|private|protected|internal|open|override|abstract|static|final|suspend|inline|data|sealed|"
    r"async|export|default|fileprivate|func|@\w+(?:\([^)]*\))?)\s+)*"
    r"(?:def|class|fun|func|interface|object|enum|struct|function|fn|"
    r"[\w<>\[\],.? ]+\s+\w+\s*\([^;]*$)")


def parse_search(command: str, cwd: str) -> tuple[str, str | None] | None:
    """A supported shell search: `[cd DIR &&] grep|rg|git grep [options] PATTERN [PATH...]` without pipes, redirects
    or other commands. Returns (directory the output paths are relative to, the single searched file if exactly one
    file is named, else None); None for anything else."""
    cd = re.match(r"^\s*cd\s+(\S+)\s*&&\s*(.*)$", command, re.S)
    rest = cd.group(2) if cd else command
    if re.search(r"[|;<>`$()&\n]", re.sub(r"'[^']*'|\"[^\"]*\"", "", rest)):
        return None
    try:
        tokens = shlex.split(rest)
    except ValueError:
        return None
    if tokens[:2] == ["git", "grep"]:
        tokens = tokens[2:]
    elif tokens[:1] in (["grep"], ["rg"]):
        tokens = tokens[1:]
    else:
        return None
    base = Path(cwd) / cd.group(1) if cd else Path(cwd)
    if cd and not base.is_dir():
        return None
    operands, pattern_given, i = [], False, 0
    while i < len(tokens):
        tok = tokens[i]
        if tok == "--":
            operands += tokens[i + 1:]
            break
        if tok in ("-e", "--regexp"):
            pattern_given = True
        if tok in VALUE_OPTS:
            i += 2
            continue
        if not tok.startswith("-") or tok == "-":
            operands.append(tok)
        i += 1
    paths = operands if pattern_given else operands[1:]
    single = paths[0] if len(paths) == 1 and (base / paths[0]).is_file() else None
    return str(base), single


def hits_of(event: dict) -> tuple[list[tuple[str, int, str]], str] | None:
    """(path, line, text) of the search hits and the directory the paths are relative to; None for non-searches,
    unsupported search commands and truncated results."""
    tool = event.get("tool_name")
    tool_input = event.get("tool_input") or {}
    response = event.get("tool_response")
    cwd = str(event.get("cwd") or "")
    if tool == "Grep":
        if not isinstance(response, dict) or response.get("mode") != "content" or response.get("appliedLimit"):
            return None
        text = str(response.get("content") or "")
        given = tool_input.get("path")
        target = (Path(given) if Path(str(given)).is_absolute() else Path(cwd) / str(given)) if given else None
        default = str(target) if target and target.is_file() else None
        base = cwd
    elif tool == "Bash":
        parsed = parse_search(str(tool_input.get("command") or ""), cwd)
        if not parsed:
            return None
        base, default = parsed
        text = response.get("stdout", "") if isinstance(response, dict) else str(response or "")
    else:
        return None
    if TRUNCATED.search(text):
        return None
    hits = []
    for line in text.splitlines():
        m = HIT.match(line)
        if not m:
            continue
        path = m.group("path") or default
        if path:
            hits.append((path, int(m.group("line")), m.group("text")))
    return hits, base


PY_DEF = re.compile(r"^\s*(?:async\s+)?(?:def|class)\s+\w")
CALL = re.compile(r"^\s*(?:return\s+|await\s+)?[\w.]+\s*\(.*\)\s*;?\s*(?:(?://|#).*)?$")
# Languages whose declarations always start with a keyword (after modifiers/annotations): anything else is not one.
KEYWORDED = (".kt", ".kts", ".go", ".swift", ".rs", ".scala")
DECLARATION = re.compile(
    r"^\s*(?:(?:public|private|protected|internal|open|override|abstract|static|final|suspend|inline|data|sealed|"
    r"inner|value|annotation|tailrec|operator|infix|external|actual|expect|const|lateinit|fileprivate|mutating|"
    r"convenience|required|pub(?:\([^)]*\))?|unsafe|async|case|implicit|lazy|@\w+(?:\([^)]*\))?)\s+)*"
    r"(?:fun|class|interface|object|enum|struct|func|fn|def|init|constructor|trait|impl|type|protocol|extension|"
    r"typealias)\b")
# a bare `name(args) {` line: a declaration in Java/C#/JS/TS only when its parameters are names/types, not values
BARE = re.compile(r"^\s*[\w.]+\s*\((?P<args>[^)]*)\)\s*(?:throws\s+[\w.,\s]+)?\{\s*$")


def is_definition(line: str, braced: bool = True, suffix: str = "") -> bool:
    """A definition line: def/class for indentation-based code; a keyword declaration for Kotlin, Go, Swift, Rust and
    Scala (a call with a trailing lambda, `node("root") {`, is not one); otherwise DEFINITION without control
    statements, plain call statements, or `name(values) {` (DEFINITION alone accepts `helper(x)  # note()`)."""
    if not braced:
        return bool(PY_DEF.match(line))
    if suffix in KEYWORDED:
        return bool(DECLARATION.match(line))
    if not DEFINITION.match(line) or line.strip().startswith(("return", "if ", "for ", "while ", "else", "switch")):
        return False
    bare = BARE.match(line)
    if bare:
        return not re.search(r"[\"'`]|\d|\w\s*\(", bare.group("args"))
    return not CALL.match(line)


def definition_at(lines: list[str], index: int, braced: bool = True, suffix: str = "") -> tuple[int, int] | None:
    """[start, end) of the definition enclosing line `index` (0-based): the nearest definition line above whose block
    still contains it (indentation for Python-like code, braces otherwise)."""
    for start in range(index, max(-1, index - 200), -1):
        if not is_definition(lines[start], braced, suffix):
            continue
        end = block_end(lines, start, braced)
        if start <= index < end:
            return start, end
    return None


def block_end(lines: list[str], start: int, braced: bool = True) -> int:
    head = lines[start]
    indent = len(head) - len(head.lstrip())
    if braced and "{" in "".join(lines[start:start + 3]):
        depth, seen = 0, False
        for i in range(start, min(len(lines), start + 400)):
            depth += lines[i].count("{") - lines[i].count("}")
            seen = seen or "{" in lines[i]
            if seen and depth <= 0:
                return i + 1
        return min(len(lines), start + MAX_LINES)
    for i in range(start + 1, min(len(lines), start + 400)):
        line = lines[i]
        if line.strip() and len(line) - len(line.lstrip()) <= indent:
            return i
    return min(len(lines), start + 400)


MAX_SPAN = 60  # a longer block (a class, a long file-level function) is not a precise answer to a search
STOP = {"the", "a", "an", "and", "or", "is", "are", "be", "to", "of", "in", "on", "for", "it", "this", "that", "with",
        "when", "should", "must", "not", "does", "do", "make", "sure", "all", "any", "as", "by", "from", "at", "but",
        "def", "fun", "func", "class", "self", "public", "private", "static", "void", "override", "suspend"}


def words(text: str) -> set[str]:
    """Lower-case word stems of a text or an identifier (camelCase and snake_case split, plural s dropped)."""
    text = re.sub(r"([a-z0-9])([A-Z])", r"\1 \2", text)
    out = set()
    for w in re.findall(r"[A-Za-z]{3,}", text):
        w = w.lower()
        if w not in STOP:
            out.add(w[:-1] if w.endswith("s") and len(w) > 4 else w)
    return out


def task_words(transcript: str | None) -> set[str]:
    """Words of the session's first user prompt (the task), from the agent's transcript."""
    if not transcript:
        return set()
    try:
        with open(transcript, encoding="utf-8", errors="replace") as handle:
            for n, raw in enumerate(handle):
                if n > 200:
                    break
                try:
                    entry = json.loads(raw)
                except ValueError:
                    continue
                if entry.get("type") != "user":
                    continue
                content = (entry.get("message") or {}).get("content")
                if isinstance(content, str):
                    return words(content)
                if isinstance(content, list):
                    text = " ".join(c.get("text", "") for c in content if isinstance(c, dict) and c.get("type") == "text")
                    if text:
                        return words(text)
    except (OSError, ValueError):
        pass
    return set()


def block_key(path: str, lines: list[str], start: int, end: int) -> str:
    """Delivery key of a definition shown to the agent: path, first line and a hash of its current text (trailing
    blank lines ignored). Shared by every hook that shows source, so an unchanged block is shown once per session and
    a changed one is shown again."""
    while end > start + 1 and not lines[end - 1].strip():
        end -= 1
    return f"{path}:{start + 1}:{hashlib.sha1(chr(10).join(lines[start:end]).encode()).hexdigest()[:10]}"


def enrich(hits: list[tuple[str, int, str]], base: Path, delivered: set[str], task: set[str] = frozenset(),
           root: Path | None = None, deadline: float | None = None) -> tuple[str, list[str]]:
    """The definitions to show for these hits, as (text, keys). A hit counts only if its text is still the current
    line (stale coordinates say nothing); a definition is shown only on clear evidence: the hit is its definition
    line, it holds two or more hits, or one hit plus a word of its name in the task. The text, header included, is at
    most EVENT_CHARS characters."""
    files = {p for p, _, _ in hits}
    if not hits or len(hits) > MAX_HITS or len(files) > MAX_FILES:
        return "", []
    root = root or base
    deadline = deadline or time.monotonic() + DEADLINE_S
    cache: dict[Path, list[str] | None] = {}
    candidates: dict[tuple[str, int], dict] = {}
    for path, line, text in hits:
        if time.monotonic() > deadline:
            return "", []
        full = Path(path) if Path(path).is_absolute() else base / path
        if full.suffix not in SOURCE:
            continue
        if full not in cache:
            try:
                cache[full] = full.read_text(encoding="utf-8", errors="replace").splitlines() if full.is_file() else None
            except OSError:
                cache[full] = None
        lines = cache[full]
        if not lines or not 0 < line <= len(lines) or lines[line - 1].strip() != text.strip():
            continue  # unknown file, or the hit no longer matches the source
        span = definition_at(lines, line - 1, braced=full.suffix in BRACED, suffix=full.suffix)
        if not span:
            continue
        if span[1] - span[0] > MAX_SPAN and line - 1 != span[0]:
            continue  # the hit sits in a large block (class body): no precise definition to show
        key = (str(full), span[0])
        if key not in candidates:
            shown = os.path.relpath(full, root)
            name_words = words(re.sub(r"\(.*", "", lines[span[0]]))
            is_test = bool(re.search(r"(^|/)(tests?|spec)(/|_|\.py)|_test\.|Test\.|\.spec\.", shown))
            candidates[key] = {"path": shown, "lines": lines, "span": span, "hits": 0, "defhit": False,
                               "overlap": len(name_words & task), "test": is_test and "test" not in task}
        c = candidates[key]
        c["hits"] += 1
        c["defhit"] = c["defhit"] or line - 1 == span[0]
    if time.monotonic() > deadline:
        return "", []  # a slow read used up the event's time: the search result goes back unchanged
    eligible = [c for c in candidates.values()
                if not c["test"] and (c["defhit"] or c["hits"] >= 2 or (c["hits"] >= 1 and c["overlap"] >= 1))]
    ranked = sorted(eligible, key=lambda c: -(4 * c["overlap"] + 3 * c["defhit"] + min(c["hits"], 3)))
    out, keys, used = [], [], len(HEADER)
    for c in ranked:
        start, end = c["span"]
        key = block_key(c["path"], c["lines"], start, end)
        if key in delivered:
            continue
        stop = min(end, start + MAX_LINES)
        body = "\n".join(f"{i + 1:5d}  {c['lines'][i]}" for i in range(start, stop))
        more = f"\n      … ({end - stop} more lines)" if end > stop else ""
        block = f"{c['path']}:{start + 1}-{end}\n{body}{more}"
        sep = 2 if out else 0
        if used + sep + len(block) > EVENT_CHARS:
            break
        out.append(block)
        keys.append(key)
        used += sep + len(block)
        if len(out) >= MAX_SHOWN:
            break
    if not out or time.monotonic() > deadline:
        return "", []
    return HEADER + "\n\n".join(out), keys


def definitions_text(event: dict) -> str:
    """The definitions block for a search result (or ""), within the session's budget and the event's deadline."""
    found = hits_of(event)
    if not found:
        return ""
    hits, cwd = found
    if not cwd.startswith("/"):
        return ""
    root = session.repo_root(str(event.get("cwd") or cwd))
    state_file = session.state_path(str(event.get("session_id") or ""), root)
    state = session.load(state_file)
    j1 = state.setdefault("j1", {"delivered": [], "chars": 0})
    if j1["chars"] >= SESSION_CHARS:
        return ""
    started = time.monotonic()
    text, keys = enrich(hits, Path(cwd), set(j1["delivered"]), task_words(event.get("transcript_path")),
                        root=Path(root), deadline=started + DEADLINE_S)
    if not text or j1["chars"] + len(text) > SESSION_CHARS or time.monotonic() > started + DEADLINE_S:
        return ""  # nothing to add, over the session budget, or too late: nothing is recorded or emitted
    j1["delivered"] += keys
    j1["chars"] += len(text)
    session.save(state_file, state)
    return "" if time.monotonic() > started + DEADLINE_S else text


def main() -> int:
    levers = {x.strip() for x in os.environ.get("AI_ORCH_LEVERS", "all").split(",")}
    try:
        event = json.load(sys.stdin)
    except ValueError:
        return 0
    try:
        ai_orch_memory_gate.post(event)  # what an `ai_orch memory search` in the shell found
    except (OSError, ValueError, KeyError, TypeError, AttributeError):
        pass
    parts = []
    if "all" in levers or "searchctx" in levers:
        try:
            parts.append(definitions_text(event))
        except (OSError, ValueError, KeyError, TypeError, AttributeError):
            pass
    if "all" in levers or "stepmem" in levers:  # memory for checking and for failures, at that step
        try:
            parts.append(ai_orch_step_memory.context(event))
        except (OSError, ValueError, KeyError, TypeError, AttributeError):
            pass
    text = "\n\n".join(p for p in parts if p)
    if text:
        print(json.dumps({"hookSpecificOutput": {"hookEventName": "PostToolUse", "additionalContext": text}}))
    return 0


if __name__ == "__main__":
    sys.exit(main())
