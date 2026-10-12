"""Work-trail capture for the Stop hook: what this request touched, as bounded retrieval telemetry (not memory).

- edited: the definitions this request changed — the diff between the tree snapshot taken at its prompt and the
  tree at Stop (earlier dirty changes excluded, committed edits included), the same for every agent client;
  file-level when a change sits outside any definition; nothing when no start snapshot exists;
- edited definitions also carry their final text (the first two, <= 25 lines: `text`, `line`, `tree`, `hash`), an
  observation of earlier work shown only when the definition is confirmed absent later;
- supporting_reads: definitions the agent read in any file with a completed read (Claude `Read`, with or without an
  offset, `sed -n a,bp`; kind `read`, `range` = the lines read; most-read first) and definitions the request's changed lines
  reference (kind `ref`: identifiers on added lines, outside strings and comments, with exactly one definition in the
  repository; <= 8). What the hooks showed the agent is not a read;
- cited: for a request that changed nothing (a question), the code spans of the final answer that resolve to a
  path or a definition in the repository;
- checks: the receipts the command hook and the Stop hook recorded for this request (executed command, the
  requested one when J2 repaired it, real status, proved scopes, test evidence); transcript-only checks are "unknown";
- fact_ids: ids returned by successful memory.learn calls of this request (the Stop hook's and the agent's own).

`post()` sends the trail to the server (`/terminal/api/trails`), fail-open within a short timeout.
"""
from __future__ import annotations

import hashlib
import json
import os
import re
import subprocess
import urllib.request
from pathlib import Path

import ai_orch_search_hook as search
import ai_orch_session_state as session

MAX_LOCATORS = 20
MAX_CHECKS = 10
NAME = re.compile(r"\b(?:def|class|fun|func|interface|object|enum|struct|function|fn)\s+(?:\([^)]*\)\s*)?([A-Za-z_]\w*)"
                  r"|\b([A-Za-z_]\w*)\s*\(")
SPAN = re.compile(r"`([^`\n]{2,120})`")


def _git(root: str, *args: str) -> str:
    try:
        return subprocess.run(["git", "-C", root, *args], capture_output=True, text=True, timeout=10).stdout
    except (OSError, subprocess.SubprocessError):
        return ""


def symbol_name(line: str) -> tuple[str, str]:
    """(name, kind) of a definition line: `def followers_count(self):` -> ("followers_count", "def")."""
    m = NAME.search(line)
    if not m:
        return "", "def"
    kind = re.search(r"\b(def|class|fun|func|interface|object|enum|struct|function|fn)\b", line)
    return (m.group(1) or m.group(2) or ""), (kind.group(1) if kind else "method")


def definition_locator(root: str, path: str, line: int, cache: dict) -> dict | None:
    full = Path(root, path)
    if full.suffix not in search.SOURCE:
        return None
    if path not in cache:
        try:
            cache[path] = full.read_text(encoding="utf-8", errors="replace").splitlines()
        except OSError:
            cache[path] = None
    lines = cache[path]
    if not lines or not 0 < line <= len(lines):
        return None
    span = search.definition_at(lines, line - 1, braced=full.suffix in search.BRACED, suffix=full.suffix)
    if not span or span[1] - span[0] > search.MAX_SPAN * 2:
        return None
    name, kind = symbol_name(lines[span[0]])
    return {"path": path, "symbol": name, "kind": kind} if name else None


def edited_locators(root: str, start_tree: str | None, end_tree: str | None) -> list[dict]:
    """Definitions this request changed: the diff between the tree it started from (snapshot at the prompt) and the
    tree now, so earlier dirty changes are excluded and committed edits are included. Without a start snapshot the
    request's edits cannot be attributed: no edited locators."""
    out, cache, seen = [], {}, set()
    if not start_tree or not end_tree or start_tree == end_tree:
        return out

    def add(loc: dict) -> None:
        key = (loc["path"], loc["symbol"])
        if key not in seen and len(out) < MAX_LOCATORS:
            seen.add(key)
            out.append(loc)

    path = None
    for line in _git(root, "diff", "-U0", "--no-renames", start_tree, end_tree).splitlines():
        if line.startswith("+++ "):
            path = line[6:] if line.startswith("+++ b/") else None
            continue
        m = re.match(r"@@ -\d+(?:,\d+)? \+(\d+)(?:,(\d+))? @@", line)
        if not (m and path):
            continue
        start, count = int(m.group(1)), int(m.group(2) or 1)
        found = False
        for ln in range(start, start + max(count, 1)):
            loc = definition_locator(root, path, ln, cache)
            if loc:
                add(loc)
                found = True
        if not found:
            add({"path": path, "symbol": "", "kind": "file"})
    return out


MAX_REFS = 8
MAX_TEXT_LINES = 25
TEXTS = 2
KEYWORDS = {"self", "this", "true", "false", "null", "none", "None", "True", "False", "return", "import", "from",
            "else", "elif", "while", "for", "when", "try", "catch", "except", "finally", "throw", "raise", "new", "val",
            "var", "let", "const", "func", "fun", "def", "class", "interface", "object", "enum", "struct", "public",
            "private", "protected", "internal", "static", "final", "override", "suspend", "async", "await", "with",
            "yield", "lambda", "string", "String", "Int", "Integer", "Long", "Boolean", "List", "Map", "Set", "error",
            "err", "nil", "range", "make", "len", "print", "println", "super", "package", "type", "data", "open"}
STRINGS = re.compile(r'"(?:[^"\\]|\\.)*"|\'(?:[^\'\\]|\\.)*\'|`[^`]*`')
COMMENT = re.compile(r"(?://|#).*$")
TEST_PATH = re.compile(r"(^|/)(tests?|spec)(/|_|\.py)|_test\.|Test\.|\.spec\.")


def is_definition(path: str, line: str) -> bool:
    return search.is_definition(line, Path(path).suffix in search.BRACED, Path(path).suffix)


def _definitions(root: str, names: list[str]) -> dict[str, list[tuple[str, int]]] | None:
    """name -> [(path, line)] of its definition lines in non-test source files of the working tree; None when the
    search itself failed (error, timeout), which is not the same as finding nothing."""
    out: dict[str, list[tuple[str, int]]] = {n: [] for n in names}
    if not names:
        return out
    try:
        done = subprocess.run(["git", "-C", root, "grep", "-n", "-w", "-E", "|".join(re.escape(n) for n in names),
                               "--", "."], capture_output=True, text=True, timeout=10)
    except (OSError, subprocess.SubprocessError):
        return None
    if done.returncode not in (0, 1):  # 1: no match; anything else: the search failed
        return None
    for hit in done.stdout.splitlines():
        m = re.match(r"^(.+?):(\d+):(.*)$", hit)
        if not m or Path(m.group(1)).suffix not in search.SOURCE or TEST_PATH.search(m.group(1)):
            continue
        text = m.group(3)
        if not is_definition(m.group(1), text):
            continue
        name, _ = symbol_name(text)
        if name in out:
            out[name].append((m.group(1), int(m.group(2))))
    return out


def referenced_locators(root: str, start_tree: str | None, end_tree: str | None, edited: list[dict]) -> list[dict]:
    """Definitions the request's added/changed lines use: identifiers outside strings and comments that have exactly
    one definition (non-test) in the repository, first use first; the edited definitions themselves excluded."""
    if not start_tree or not end_tree or start_tree == end_tree:
        return []
    own = {e["symbol"] for e in edited}
    names: list[str] = []
    for line in _git(root, "diff", "-U0", "--no-renames", start_tree, end_tree).splitlines():
        if not line.startswith("+") or line.startswith("+++"):
            continue
        code = COMMENT.sub("", STRINGS.sub(" ", line[1:]))
        for name in re.findall(r"\b[A-Za-z_][A-Za-z0-9_]{3,}\b", code):
            if name not in KEYWORDS and name not in own and name not in names:
                names.append(name)
    names = names[:40]
    found = _definitions(root, names)
    if found is None:
        return []  # the search failed: no references rather than guesses
    out, cache = [], {}
    for name in names:
        if len(found[name]) != 1:
            continue  # undefined here (library, local) or ambiguous: no guess
        path, line = found[name][0]
        loc = definition_locator(root, path, line, cache)
        if loc and loc["symbol"] == name:
            out.append({"path": path, "symbol": name, "kind": "ref"})
        if len(out) >= MAX_REFS:
            break
    return out


def with_text(root: str, edited: list[dict], tree: str | None) -> list[dict]:
    """The first TEXTS edited definitions with their final text (<= MAX_TEXT_LINES), first line, tree and hash."""
    out, given = [], 0
    for loc in edited:
        if given < TEXTS and loc.get("symbol"):
            full = Path(root, loc["path"])
            try:
                lines = full.read_text(encoding="utf-8", errors="replace").splitlines()
            except OSError:
                lines = []
            starts = [i for i, ln in enumerate(lines) if is_definition(loc["path"], ln) and symbol_name(ln)[0] == loc["symbol"]]
            if len(starts) == 1:
                start = starts[0]
                end = search.block_end(lines, start, full.suffix in search.BRACED)
                while end > start + 1 and not lines[end - 1].strip():
                    end -= 1
                text = "\n".join(lines[start:min(end, start + MAX_TEXT_LINES)])[:3900]
                loc = dict(loc, text=text, line=start + 1, tree=(tree or "")[:200],
                           hash=hashlib.sha1(text.encode()).hexdigest()[:16])
                given += 1
        out.append(loc)
    return out


def supporting_locators(steps: list[dict], root: str, edited: list[dict]) -> list[dict]:
    """Definitions the agent read with a completed read tool, in any file (lower weight than edits): the definition
    at the read's first line and those starting inside the range (<= 3 per read), most-read first, with the range."""
    have = {(e["path"], e["symbol"]) for e in edited}
    counts: dict[tuple[str, str], int] = {}
    found: dict[tuple[str, str], dict] = {}
    cache: dict = {}
    for step in steps:
        if step.get("is_error") or step.get("done") is False:  # failed, or no result: not a read
            continue
        path = first = None
        last = 0
        inp = step.get("input") or {}
        if step.get("name") == "Read" and inp.get("file_path"):
            # 1-based offset; without one the tool reads from the first line, up to `limit` (default 2000) lines
            path, first = inp.get("file_path"), int(inp.get("offset") or 1)
            last = first + int(inp.get("limit") or 2000) - 1
        elif step.get("name") == "Bash":
            m = re.match(r"^\s*sed\s+-n\s+['\"]?(\d+),(\d+)p['\"]?\s+(\S+)\s*$", str(inp.get("command") or ""))
            if m:
                first, last, path = int(m.group(1)), int(m.group(2)), m.group(3)
        if not path:
            continue
        rel = os.path.relpath(path, root) if os.path.isabs(path) else path
        if rel.startswith(".."):
            continue
        locs = [definition_locator(root, rel, first, cache)]
        lines = cache.get(rel) or []
        for ln in range(first + 1, min(last, len(lines)) + 1):
            if len(locs) >= 3:
                break
            if is_definition(rel, lines[ln - 1]):
                loc = definition_locator(root, rel, ln, cache)
                if loc and loc not in locs:
                    locs.append(loc)
        for loc in locs:
            if not loc or (loc["path"], loc["symbol"]) in have:
                continue
            key = (loc["path"], loc["symbol"])
            counts[key] = counts.get(key, 0) + 1
            found.setdefault(key, dict(loc, kind="read", range=f"{first}-{last}"))
    ranked = sorted(found, key=lambda k: -counts[k])  # stable: first read first among equals
    return [found[k] for k in ranked][:MAX_LOCATORS // 2]


def cited_locators(answer: str, root: str) -> list[dict]:
    """Code spans of a final answer that name an existing file or a definition in the repository."""
    out, seen = [], set()
    for span in SPAN.findall(answer or ""):
        token = re.sub(r":\d+(-\d+)?$", "", span.strip())
        if "/" in token and Path(root, token).is_file():
            key = (token, "")
            if key not in seen:
                seen.add(key)
                out.append({"path": token, "symbol": "", "kind": "file"})
            continue
        name = re.split(r"[.#:]", re.sub(r"\(.*\)$", "", token))[-1]
        if not re.fullmatch(r"[A-Za-z_]\w{2,}", name):
            continue
        hit = [h for h in _git(root, "grep", "-n", "-E",
                               rf"(def|fun|func|class|interface|object|enum|struct|function|fn)[[:space:]]+{name}([^A-Za-z0-9_]|$)",
                               "--", ".").splitlines() if not re.search(r"(^|/)(tests?|spec)(/|_|\.py)", h)][:1]
        if hit:
            path = hit[0].split(":", 1)[0]
            key = (path, name)
            if key not in seen:
                seen.add(key)
                out.append({"path": path, "symbol": name, "kind": "cited"})
        if len(out) >= MAX_LOCATORS // 2:
            break
    return out


def check_receipts(steps: list[dict], receipts: list[dict]) -> list[dict]:
    """The request's checks: the structured receipts the command hook and the Stop hook recorded (executed command,
    real status, proved scopes, test evidence); a check seen only in the transcript cannot be established and is
    recorded with status "unknown"."""
    out = [{"command": str(r.get("command") or "")[:2000], "scope": str(r.get("scope") or "")[:500],
            "status": str(r.get("status") or "unknown")[:16], "testEvidence": str(r.get("testEvidence") or "unknown")[:16],
            **({"requested": str(r["requested"])[:2000]} if r.get("requested") else {}),
            **({"cwd": str(r["cwd"])[:4096]} if r.get("cwd") else {}),
            **({"tree": str(r["tree"])[:200]} if r.get("tree") else {})}
           for r in receipts if r.get("command")]
    known = {str(r.get("requested") or r.get("command")) for r in receipts} | {str(r.get("command")) for r in receipts}
    for step in steps:
        command = str((step.get("input") or {}).get("command") or "")
        if step.get("name") == "Bash" and session.is_verify(command) and command not in known:
            out.append({"command": command[:2000], "scope": "", "status": "unknown", "testEvidence": "unknown"})
            known.add(command)
    return out[-MAX_CHECKS:]


def learned_ids(response) -> list[str]:
    """Memory ids from a successful memory.learn response (MCP JSON-RPC answer or its parsed result); [] for failures
    and for response shapes without ids (anchored captures report only counts)."""
    try:
        if isinstance(response, str):
            response = json.loads(response)
        result = response.get("result", response) if isinstance(response, dict) else None
        if not isinstance(result, dict) or result.get("isError"):
            return []
        if "content" in result:
            result = json.loads(result["content"][0]["text"])
        if result.get("status") not in ("ok", "partial"):
            return []
        return [str(i["memoryId"]) for i in result.get("items") or []
                if isinstance(i, dict) and i.get("memoryId") and i.get("status") in ("created", "reused",
                                                                                     "duplicate_not_saved")]
    except (ValueError, KeyError, IndexError, TypeError, AttributeError):
        return []


def agent_learned_ids(steps: list[dict]) -> list[str]:
    """Ids from the agent's own successful memory.learn calls in this request."""
    out = []
    for step in steps:
        if "memory_learn" in str(step.get("name") or "").replace(".", "_") and not step.get("is_error"):
            out += learned_ids(step.get("output"))
    return out


def trail_key(session_id: str, request: dict, transcript: str | None) -> str | None:
    ordinal = int((request or {}).get("ordinal") or 0)
    if not ordinal and transcript:
        ordinal = request_of(transcript)[1]
    if not ordinal:
        return None
    sid = re.sub(r"[^A-Za-z0-9._-]", "-", session_id or hashlib.sha256(str(transcript).encode()).hexdigest()[:16])
    return f"{sid}:{ordinal}"[:128].lstrip("-.:_") or None


def request_of(transcript: str) -> tuple[str, int, str]:
    """(text of the current request, its ordinal in the session, the last final answer) from a Claude Code
    transcript or a Codex rollout."""
    text, ordinal, answer = "", 0, ""
    try:
        with open(transcript, encoding="utf-8", errors="replace") as handle:
            for raw in handle:
                try:
                    entry = json.loads(raw)
                except ValueError:
                    continue
                item = ((entry.get("payload") or {}).get("item") or {}) if entry.get("type") == "event_msg" else {}
                if item.get("type") in ("UserMessage", "AgentMessage"):
                    joined = " ".join(c.get("text", "") for c in item.get("content") or [] if isinstance(c, dict))
                    if item["type"] == "UserMessage":
                        text, ordinal, answer = joined, ordinal + 1, ""
                    else:
                        answer = joined
                    continue
                message = entry.get("message") or {}
                content = message.get("content")
                if entry.get("type") == "user" and not entry.get("isMeta"):
                    if isinstance(content, str):
                        text, ordinal, answer = content, ordinal + 1, ""
                    elif any(isinstance(b, dict) and b.get("type") == "text" for b in content or []):
                        text = " ".join(b.get("text", "") for b in content if isinstance(b, dict) and b.get("type") == "text")
                        ordinal, answer = ordinal + 1, ""
                elif entry.get("type") == "assistant" and isinstance(content, list):
                    said = " ".join(b.get("text", "") for b in content if isinstance(b, dict) and b.get("type") == "text")
                    if said.strip():
                        answer = said
    except OSError:
        pass
    return text.strip(), ordinal, answer


def build(transcript: str, steps: list[dict], root: str, session_id: str, request: dict | None = None) -> dict | None:
    text, _, answer = request_of(transcript)
    request = request or {}
    key = trail_key(session_id, request, transcript)
    if not text or not key:
        return None
    end_tree = session.snapshot_tree(root)
    edited = edited_locators(root, request.get("tree"), end_tree)
    refs = referenced_locators(root, request.get("tree"), end_tree, edited)
    reads = supporting_locators(steps, root, edited)
    reads = (reads + [r for r in refs if (r["path"], r["symbol"]) not in {(x["path"], x["symbol"]) for x in reads}])
    facts = list(dict.fromkeys([*(request.get("facts") or []), *agent_learned_ids(steps)]))[:20]
    return {"cwd": root, "trailKey": key, "codeFingerprint": (end_tree or "")[:200],
            "requestText": text[:2000], "edited": with_text(root, edited, end_tree),
            "supportingReads": reads[:MAX_LOCATORS],
            "cited": [] if edited else cited_locators(answer, root),
            "checks": check_receipts(steps, request.get("receipts") or []), "factIds": facts}


def post(trail: dict, client: str, timeout: float = 2.0) -> bool:
    base = os.environ.get("AI_ORCH_URL", "http://127.0.0.1:18080").rstrip("/")
    request = urllib.request.Request(base + "/terminal/api/trails", json.dumps(trail).encode(),
                                     {"Content-Type": "application/json", "X-AI-Orch-Client": client}, method="POST")
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return 200 <= response.status < 300
    except (OSError, ValueError):
        return False
