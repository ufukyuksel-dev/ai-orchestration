"""Work-trail retrieval for the prompt hook: which earlier request of this project is about the same code as the new
prompt, and what of it to show.

Score = cosine of TF-IDF vectors of the prompt and each trail's request text (IDF over the project's trails). A trail
is used only above THRESHOLD (calibrated offline, bench/trail_retrieval_eval.py; symbol-name overlap and bge-m3
embeddings were measured there too and did not separate better).

Rendering: the trail's definitions (edited, cited, referenced by its changes, read) ranked by how many of the new
prompt's words their names share, then by role; each resolved in the CURRENT source (one definition in its file, or,
when its file has none, exactly one elsewhere in the repository). At most two excerpts with line numbers. Only an
edited definition that is confirmed absent (no definition of that name anywhere) may be shown from the text captured
with the trail, labelled as a historical example; ambiguous, unsupported or oversized definitions are skipped. As
advice only, the earlier check that passed (its directory relative to this checkout, what it proved, when). Nothing
here is a verified fact or an instruction. Linked facts (`factIds`) are not delivered yet (deferred).
"""
from __future__ import annotations

import hashlib
import json
import math
import os
import re
import urllib.parse
import urllib.request
from pathlib import Path

import ai_orch_search_hook as search
import ai_orch_trail as capture

THRESHOLD = 0.3          # TF-IDF cosine; calibrated in bench/trail_retrieval_eval.py (plan-connected-memory.md §8.3)
IDF_PRIOR = 10           # pseudo-documents in the IDF, so a project with one or two trails does not zero shared words
CODE_WEIGHT = None       # two-channel variant (request + weight × code vocabulary): off until the offline grid picks one
MAX_EXCERPTS = 2
EXCERPT_LINES = 25
MAX_CHARS = 6000         # header included


def _tokens(text: str) -> list[str]:
    text = re.sub(r"([a-z0-9])([A-Z])", r"\1 \2", text)
    out = []
    for w in re.findall(r"[A-Za-z]{3,}", text):
        w = w.lower()
        if w not in search.STOP:
            out.append(w[:-1] if w.endswith("s") and len(w) > 4 else w)
    return out


def _vectors(docs: list[list[str]]) -> list[dict[str, float]]:
    df: dict[str, int] = {}
    for d in docs:
        for w in set(d):
            df[w] = df.get(w, 0) + 1
    n, out = len(docs), []
    for d in docs:
        v = {w: (1 + math.log(d.count(w))) * math.log((n + IDF_PRIOR) / (df[w] + 0.5)) for w in set(d)}
        norm = math.sqrt(sum(x * x for x in v.values())) or 1.0
        out.append({w: x / norm for w, x in v.items()})
    return out


def _cosines(prompt: str, docs: list[list[str]]) -> list[float]:
    vectors = _vectors([_tokens(prompt)] + docs)
    query = vectors[0]
    return [sum(x * v.get(w, 0.0) for w, x in query.items()) for v in vectors[1:]]


def code_words(trail: dict) -> list[str]:
    """The trail's code vocabulary: its definitions' names, their source paths and the captured text."""
    parts = []
    for loc in [*(trail.get("edited") or []), *(trail.get("cited") or []), *(trail.get("supportingReads") or [])]:
        parts += [str(loc.get("symbol") or ""), str(loc.get("path") or "").replace("/", " "), str(loc.get("text") or "")]
    return _tokens(" ".join(parts))


def channels(prompt: str, trails: list[dict]) -> list[tuple[float, float]]:
    """(request, code) per trail: cosine of the prompt with the trail's request text, and with its code vocabulary
    (separate documents and IDF, so a long captured body cannot swamp request relevance)."""
    request = _cosines(prompt, [_tokens(str(t.get("requestText") or "")) for t in trails])
    code = _cosines(prompt, [code_words(t) for t in trails])
    return list(zip(request, code))


def score(prompt: str, trails: list[dict], weight: float | None = None) -> list[tuple[float, dict]]:
    """(score, trail), best first. Baseline (weight None): the request channel alone — cosine of TF-IDF vectors of
    the prompt and each trail's request text, IDF over the prompt and the trails. Variant: request + weight × code."""
    pairs = channels(prompt, trails) if weight is not None else [(c, 0.0) for c in _cosines(
        prompt, [_tokens(str(t.get("requestText") or "")) for t in trails])]
    out = [(a + (weight or 0.0) * b, t) for (a, b), t in zip(pairs, trails)]
    return sorted(out, key=lambda x: -x[0])


def select(prompt: str, trails: list[dict], threshold: float | None = None,
           weight: float | None = None) -> tuple[float, dict] | None:
    threshold = THRESHOLD if threshold is None else threshold
    weight = CODE_WEIGHT if weight is None else weight
    ranked = score(prompt, [t for t in trails if t.get("edited") or t.get("cited")], weight)
    return ranked[0] if ranked and ranked[0][0] >= threshold else None


ROLE = {"cited": 3, "ref": 2, "read": 1}  # edited definitions (any other kind): 3
EDIT_KINDS = {"def", "class", "fun", "func", "interface", "object", "enum", "struct", "function", "fn", "method"}
MAX_LOOKUPS = 4  # repository-wide searches per rendering (each is one `git grep`)


def _definitions_in(lines: list[str], symbol: str, path: str) -> list[int]:
    return [i for i, line in enumerate(lines) if capture.is_definition(path, line) and capture.symbol_name(line)[0] == symbol]


def _text_hash(lines: list[str], start: int, braced: bool) -> str:
    end = search.block_end(lines, start, braced)
    while end > start + 1 and not lines[end - 1].strip():
        end -= 1
    text = "\n".join(lines[start:min(end, start + capture.MAX_TEXT_LINES)])[:3900]
    return hashlib.sha1(text.encode()).hexdigest()[:16]


def locate(root: Path, loc: dict, lookups: list[int]) -> tuple[str, str, int, int, list[str]]:
    """(status, path, start, end, lines) of a trail locator in the current source. status: "current" (one definition
    of the name in its file; or, for a captured definition no longer in its file, the one definition elsewhere whose
    text is identical — it moved), "absent" (no definition of the name in its file and no identical one elsewhere;
    a same-named definition elsewhere is not assumed to be it), "ambiguous", "unsupported" or "oversized"."""
    path, symbol = str(loc.get("path") or ""), str(loc.get("symbol") or "")
    if not symbol or Path(path).suffix not in search.SOURCE:
        return "unsupported", path, 0, 0, []

    def read(rel: str) -> list[str] | None:
        try:
            return (root / rel).read_text(encoding="utf-8", errors="replace").splitlines() if (root / rel).is_file() else None
        except OSError:
            return None

    lines = read(path)
    starts = _definitions_in(lines, symbol, path) if lines else []
    if not starts:
        if not loc.get("hash"):
            return "absent", path, 0, 0, []
        if lookups[0] >= MAX_LOOKUPS:
            return "unsupported", path, 0, 0, []
        lookups[0] += 1
        found = capture._definitions(str(root), [symbol])
        if found is None:
            return "unsupported", path, 0, 0, []  # the search failed: absence is not confirmed, no fallback
        same = []
        for other, line in found[symbol]:
            other_lines = read(other)
            if other_lines is None:
                return "unsupported", path, 0, 0, []  # a candidate could not be read: absence is not confirmed
            if 0 < line <= len(other_lines) and \
                    _text_hash(other_lines, line - 1, Path(other).suffix in search.BRACED) == loc["hash"]:
                same.append((other, other_lines, line - 1))
        if not same:
            return "absent", path, 0, 0, []
        if len(same) > 1:
            return "ambiguous", path, 0, 0, []  # identical copies: which one is it? no guess, no fallback
        path, lines, starts = same[0][0], same[0][1], [same[0][2]]
    if len(starts) != 1:
        return "ambiguous", path, 0, 0, []
    start = starts[0]
    end = search.block_end(lines, start, Path(path).suffix in search.BRACED)
    while end > start + 1 and not lines[end - 1].strip():
        end -= 1
    if end - start > search.MAX_SPAN * 2:
        return "oversized", path, 0, 0, []
    return "current", path, start, end, lines


def ranked_locators(trail: dict, prompt: str) -> list[dict]:
    """The trail's definitions, those whose names share the most words with the prompt first, then by role."""
    query = search.words(prompt)
    locs, seen = [], set()
    for loc in [*(trail.get("edited") or []), *(trail.get("cited") or []), *(trail.get("supportingReads") or [])]:
        key = (loc.get("path"), loc.get("symbol"))
        if key not in seen:
            seen.add(key)
            locs.append(loc)

    def rank(item: tuple[int, dict]) -> tuple:
        i, loc = item
        names = search.words(str(loc.get("symbol") or "")) | search.words(Path(str(loc.get("path") or "")).stem)
        return -len(query & names), -ROLE.get(str(loc.get("kind")), 3), i

    return [loc for _, loc in sorted(enumerate(locs), key=rank)]


def _advice(trail: dict, root: str) -> str:
    """The last passed check of the trail (one that ran tests first), as advice: command, the directory it ran in
    relative to this checkout, what it proved and when. Omitted when its directory is not inside this checkout."""
    checks = [c for c in trail.get("checks") or [] if c.get("status") == "passed" and c.get("command")]
    best = next((c for c in reversed(checks) if c.get("testEvidence") == "ran"), checks[-1] if checks else None)
    if not best or not best.get("cwd"):
        return ""
    here, ran_in = os.path.realpath(root), os.path.realpath(str(best["cwd"]))
    if ran_in != here and not ran_in.startswith(here + os.sep):
        return ""
    where = os.path.relpath(ran_in, here)
    when = str(trail.get("updatedAt") or trail.get("createdAt") or "")[:10]
    proved = best.get("scope") or "unknown scope"
    return (f"\n\nAdvice, not a result for the current tree: earlier work on this code passed `{best['command']}` "
            f"(run in `{where}`, proved: {proved}{', on ' + when if when else ''}).")


def render(trail: dict, root: str, delivered: set[str], prompt: str = "") -> tuple[str, list[str]]:
    """The block for a selected trail and the keys of what it shows. Current source: search.block_key (path, line,
    hash of the current text), shared with the search hook. Historical text: "hist:path:symbol:hash", a separate key,
    so showing an old example never suppresses current code later. Empty when nothing new can be shown."""
    base = Path(root)
    blocks, keys, lookups = [], [], [0]
    spans: list[tuple[str, str, int, int]] = []  # (kind, path, first, last line) shown: nested definitions are skipped

    def nested(kind: str, path: str, first: int, last: int) -> bool:
        return any(k == kind and p == path and a <= first and last <= b for k, p, a, b in spans)

    for loc in ranked_locators(trail, prompt):
        if len(blocks) >= MAX_EXCERPTS:
            break
        status, path, start, end, lines = locate(base, loc, lookups)
        if status == "current":
            key = search.block_key(path, lines, start, end)
            if key in delivered or key in keys or nested("current", path, start + 1, end):
                continue
            spans.append(("current", path, start + 1, end))
            stop = min(end, start + EXCERPT_LINES)
            body = "\n".join(f"{i + 1:5d}  {lines[i]}" for i in range(start, stop))
            more = f"\n      … ({end - stop} more lines)" if end > stop else ""
            blocks.append(f"{path}:{start + 1}-{end} (current source)\n{body}{more}")
            keys.append(key)
        elif status == "absent" and loc.get("kind") in EDIT_KINDS and loc.get("text") and loc.get("hash"):
            key = f"hist:{loc['path']}:{loc['symbol']}:{loc['hash']}"
            first = int(loc.get("line") or 1)
            text_lines = str(loc["text"]).splitlines()[:EXCERPT_LINES]
            last = first + len(text_lines) - 1
            if key in delivered or key in keys or nested("historical", loc["path"], first, last):
                continue
            spans.append(("historical", loc["path"], first, last))
            body = "\n".join(f"{first + i:5d}  {line}" for i, line in enumerate(text_lines))
            when = str(trail.get("updatedAt") or trail.get("createdAt") or "")[:10]
            blocks.append(f"{loc['path']} `{loc['symbol']}` — captured in earlier work (tree "
                          f"{str(loc.get('tree') or '?')[:10]}{', ' + when if when else ''}); not resolved at this "
                          f"locator in the current checkout; historical example only, not current code:\n{body}")
            keys.append(key)
    if not blocks:
        return "", []
    request = re.sub(r"\s+", " ", str(trail.get("requestText") or ""))[:160]
    advice = _advice(trail, root)
    head = (f"[ai-orch] Earlier work in this repository touched code this request is likely about "
            f"(earlier request: \"{request}\"). Each excerpt says whether it is current source or a historical "
            f"example:\n")
    text = head + "\n\n".join(blocks) + advice
    while len(text) > MAX_CHARS and len(blocks) > 1:
        blocks.pop()
        keys.pop()
        text = head + "\n\n".join(blocks) + advice
    return text[:MAX_CHARS], keys


def fetch(root: str, client: str, timeout: float = 0.6, limit: int = 100) -> list[dict]:
    """The project's newest trails from the server; [] on any failure (the prompt goes on without them)."""
    base = os.environ.get("AI_ORCH_URL", "http://127.0.0.1:18080").rstrip("/")
    url = f"{base}/terminal/api/trails/recent?" + urllib.parse.urlencode({"cwd": root, "limit": limit})
    request = urllib.request.Request(url, headers={"X-AI-Orch-Client": client})
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            data = json.loads(response.read().decode("utf-8", "replace"))
            return data if isinstance(data, list) else []
    except (OSError, ValueError):
        return []


def context(root: str, prompt: str, state: dict, session_id: str, client: str) -> str:
    """The trail block for this prompt (or ""): the best earlier trail above THRESHOLD (any session, earlier requests
    of this one included; never the current request), rendered from the current source without what this session was
    already shown (state["j1"]["delivered"], shared with the search hook and cleared after a compaction). Each decision
    is logged under state["trail_log"] (last 20)."""
    current = f"{session_id}:{(state.get('request') or {}).get('ordinal')}"
    trails = [t for t in fetch(root, client) if str(t.get("trailKey") or "") != current]
    picked = select(prompt, trails)
    if picked:
        request_cos, code_cos = channels(prompt, [picked[1]])[0] if CODE_WEIGHT is not None else (picked[0], None)
    entry: dict = {"ordinal": (state.get("request") or {}).get("ordinal"), "candidates": len(trails), "picked": None}
    text = ""
    if picked:
        value, trail = picked
        j1 = state.setdefault("j1", {"delivered": [], "chars": 0})
        text, keys = render(trail, root, set(j1["delivered"]), prompt)
        j1["delivered"] += keys
        entry.update(picked=trail.get("trailKey"), score=round(value, 3), request=round(request_cos, 3),
                     code=None if code_cos is None else round(code_cos, 3), chars=len(text), shown=keys,
                     kinds=["historical" if k.startswith("hist:") else "current" for k in keys])
    log = state.setdefault("trail_log", [])
    log.append(entry)
    del log[:-20]
    return text
