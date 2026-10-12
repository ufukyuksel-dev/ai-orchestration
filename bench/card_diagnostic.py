#!/usr/bin/env python3
"""Card diagnostic (output/plan-card-diagnostic.md): do durable cards carry the knowledge a labelled follow-up needs,
and where does it get lost — capture, ingestion, retrieval or delivery? Offline, on the isolated bench backend; no
agent runs. Two lanes kept apart: `observed` (cards the product really produced: agent memory.learn calls in saved
transcripts + the Stop hook's autolearn replayed on them) and `oracle` (reference-authored cards).

  python3 bench/card_diagnostic.py --observed            # inventory observed cards from saved transcripts
  python3 bench/card_diagnostic.py --run <suite> --lane observed|oracle   # one process per suite
  python3 bench/card_diagnostic.py --report               # tables; K verdicts come from verdicts.json files

Results: bench/.work/card-diagnostic/<lane>-<suite>.json (private suites under $BENCH_PRIVATE/card-diagnostic/).
"""
from __future__ import annotations

import argparse
import glob
import hashlib
import json
import os
import subprocess
import sys
import time
import uuid
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parent / "scripts" / "hooks"))
import run  # noqa: E402

os.environ.setdefault("AI_ORCH_URL", run.BASE)
import ai_orch_prompt_context as hook  # noqa: E402  (reads AI_ORCH_URL at import)
import ai_orch_stop as stop  # noqa: E402

V1 = {"holdout", "py-holdout", "go-holdout"}


def out_dir(private: bool) -> Path:
    d = (run.PRIVATE if private else run.WORK) / "card-diagnostic"
    d.mkdir(parents=True, exist_ok=True)
    return d


def load_labels() -> dict[str, dict]:
    out = {}
    for f in (run.BENCH / "trail-labels.json", run.PRIVATE / "trail-labels.json"):
        if f.exists():
            for suite, labels in json.loads(f.read_text()).items():
                out.update({x["task"]: dict(x, suite=suite) for x in labels})
    return out


def load_oracle() -> tuple[dict[str, list[dict]], dict[str, str]]:
    cards: dict[str, list[dict]] = {}
    checks: dict[str, str] = {}
    for f in (run.BENCH / "oracle-cards.json", run.PRIVATE / "oracle-cards.json"):
        if f.exists():
            data = json.loads(f.read_text())
            for c in data["cards"]:
                cards.setdefault(c["source_task"], []).append(c["candidate"])
            checks.update({k["task"]: k["k_check"] for k in data["k_checks"]})
    return cards, checks


# ------------------------------------------------------------------ observed lane: inventory
def _codex_steps(path: str) -> list[dict]:
    out = []
    for line in open(path, encoding="utf-8"):
        e = json.loads(line)
        it = e.get("item") or {}
        if e.get("type") != "item.completed":
            continue
        if it.get("type") == "command_execution":
            out.append({"name": "Bash", "input": {"command": it.get("command", "")}, "done": True,
                        "is_error": it.get("exit_code") not in (0, None), "output": str(it.get("aggregated_output") or "")})
        elif it.get("type") == "file_change":
            out += [{"name": "Edit", "input": {"file_path": c.get("path", "")}, "is_error": False, "output": "",
                     "done": True} for c in it.get("changes") or []]
    return out


def _learn_calls(path: str) -> list[tuple[list[dict], str]]:
    """(candidates, response text) of every memory.learn call in a saved transcript (Claude or Codex)."""
    out, pending = [], {}
    for line in open(path, encoding="utf-8"):
        e = json.loads(line)
        it = e.get("item") or {}
        if e.get("type") == "item.completed" and it.get("type") == "mcp_tool_call" and it.get("tool") == "memory.learn":
            args = it.get("arguments") or {}
            args = json.loads(args) if isinstance(args, str) else args
            res = it.get("result") or {}
            text = " ".join(b.get("text", "") for b in (res.get("content") or []) if isinstance(b, dict))
            out.append((args.get("candidates") or [], text or json.dumps(it.get("error"))))
        for b in ((e.get("message") or {}).get("content") or []) if isinstance((e.get("message") or {}).get("content"), list) else []:
            if b.get("type") == "tool_use" and "memory_learn" in b.get("name", ""):
                pending[b.get("id")] = b.get("input", {}).get("candidates") or []
            elif b.get("type") == "tool_result" and b.get("tool_use_id") in pending:
                content = b.get("content")
                text = content if isinstance(content, str) else " ".join(
                    x.get("text", "") for x in content or [] if isinstance(x, dict))
                out.append((pending.pop(b["tool_use_id"]), text))
    return out


def observed() -> None:
    """source task -> [{run, via (agent|autolearn), accepted, candidates}] from every saved B-arm transcript."""
    inventory: dict[str, list[dict]] = {}
    for f in sorted(glob.glob(str(run.WORK / "results" / "*-B-*" / "raw" / "*.jsonl"))):
        name = Path(f).name  # <agent>-<arm>-<task>-r<rep>.jsonl
        task = name.split("-")[2]
        rel = str(Path(f).relative_to(run.WORK / "results"))
        for cands, text in _learn_calls(f):
            accepted = '"status":"ok"' in text.replace(" ", "")
            inventory.setdefault(task, []).append({"run": rel, "via": "agent", "accepted": accepted,
                                                   "response": text[:200], "candidates": cands})
        steps = stop.steps_since_last_prompt(f) if name.startswith("claude-") else _codex_steps(f)
        found = stop.candidates(steps, "/nonexistent", {})
        if found:
            inventory.setdefault(task, []).append({"run": rel, "via": "autolearn", "accepted": True,
                                                   "candidates": found})
    dest = out_dir(False) / "observed-cards.json"
    dest.write_text(json.dumps(inventory, indent=1))
    print(f"observed cards: {sum(len(v) for v in inventory.values())} learn events for {sorted(inventory)} -> {dest}")


def load_observed() -> dict[str, list[dict]]:
    """source task -> accepted candidates (union over runs, deduplicated by summary)."""
    f = out_dir(False) / "observed-cards.json"
    raw = json.loads(f.read_text()) if f.exists() else {}
    out = {}
    for task, events in raw.items():
        seen, cards = set(), []
        for ev in events:
            if not ev["accepted"]:
                continue
            for c in ev["candidates"]:
                if c.get("summary") not in seen:
                    seen.add(c.get("summary"))
                    cards.append(dict(c, _via=ev["via"], _run=ev["run"]))
        out[task] = cards
    return out


# ------------------------------------------------------------------ replay against the bench backend
class Cell:
    """One worktree + project binding; scans follow the runner (a baseline is scanned when it changes)."""

    def __init__(self, suite: dict, name: str):
        self.suite, self.repo = suite, suite["repo"]
        self.wt = run.WT_ROOT / f"card-diag-{name}"
        run.fresh_worktree(self.repo, self.wt)
        self.key = run.project_key_for(self.wt)
        self.scanned = None
        self.scans = 0

    def at(self, task: dict, solved: bool = False) -> str:
        baseline = run.reset_code(self.wt, self.repo, task)
        if baseline != self.scanned:  # run.scan_project's 300 s HTTP timeout is too short for the KMP repositories
            run.http_json("POST", "/api/scanner/scan", {"rootPath": str(self.wt), "projectKey": self.key, "force": True},
                          timeout=1200)
            self.scanned, self.scans = baseline, self.scans + 1
        if solved:
            patch = run.ASSETS / "reference" / f"{task['id']}.patch"
            if patch.exists():
                subprocess.run(["git", "-C", str(self.wt), "apply", str(patch)], capture_output=True)
        return baseline

    def learn(self, task: dict, cards: list[dict]) -> list[dict]:
        if not cards:
            return []
        self.at(task, solved=True)
        clean = [{k: v for k, v in c.items() if not k.startswith("_")} for c in cards]
        out = []
        for i in range(0, len(clean), 3):  # memory.learn takes 1-3 candidates per call
            batch = clean[i:i + 3]
            try:
                res = run.mcp_call("memory.learn", {"rootPath": str(self.wt), "candidates": batch,
                                                    "taskRunId": f"card-diag-{task['id']}-{uuid.uuid4().hex[:12]}"})
            except Exception as exc:  # noqa: BLE001 - the server's rejection text is the finding
                res = {"status": "error", "error": str(exc)[:300]}
            out.append({"source": task["id"], "summaries": [c.get("summary") for c in batch],
                        "status": res.get("status"), "created": res.get("created"), "reused": res.get("reused"),
                        "rejected": res.get("rejected"), "duplicates": res.get("duplicates"),
                        "capture": res.get("capture"), "error": res.get("error")})
        return out

    def deliver(self, task: dict) -> dict:
        self.at(task)
        started = time.monotonic()
        try:
            data = hook.bootstrap(str(self.wt), task["prompt"][:500]) or {}
        except (OSError, ValueError, KeyError) as exc:
            return {"error": str(exc)[:200], "cards": [], "text": ""}
        lines = hook.render({"memory": data.get("memory") or []}, {"rules": True, "cards": []})
        return {"latencyS": round(time.monotonic() - started, 2), "text": "\n".join(lines),
                "cards": [{"id": c.get("id"), "summary": c.get("summary"), "text": c.get("text"),
                           "stale": c.get("stale"), "files": c.get("files")} for c in data.get("memory") or []]}

    def close(self) -> None:
        subprocess.run(["git", "-C", str(run.WORK / "repos" / self.repo), "worktree", "remove", "--force",
                        str(self.wt)], capture_output=True)


def run_suite(suite_name: str, lane: str) -> None:
    import verify_task
    suite = verify_task.configure(suite_name)
    labels = load_labels()
    cards = load_observed() if lane == "observed" else load_oracle()[0]
    # every labelled task of every suite on this repository (pooled corpus), with family and position
    tasks: dict[str, dict] = {}
    for f in [*(run.BENCH / "tasks").glob("*.json"), *(run.PRIVATE / "tasks").glob("*.json")]:
        s = json.loads(f.read_text())
        if s.get("repo") != suite["repo"] or f.stem.endswith("-pilot") or f.stem in ("dev", "final"):
            continue
        for fam in s["families"]:
            for pos, t in enumerate(fam["tasks"], 1):
                if t["id"] in labels:
                    tasks[t["id"]] = dict(t, _family=f"{f.stem}/{fam['id']}", _position=pos, _suite=f.stem)
    mine = [t for t in tasks.values() if t["_suite"] == suite_name]
    cell = Cell(suite, f"{lane}-{suite_name}")
    rows = []
    try:
        for t in mine:
            label = labels[t["id"]]
            modes = {}
            if label["label"] == "positive":
                modes["chain"] = [tasks[e] for e in label["expect"] if e in tasks]
            modes["pooled"] = [s for s in tasks.values() if s["id"] != t["id"] and (
                s["_family"] != t["_family"] or s["_position"] < t["_position"])]
            row = {"task": t["id"], "label": label["label"], "uncertain": bool(label.get("uncertain"))}
            for mode, sources in modes.items():
                run.reset_memory(cell.key)
                ingested = []
                for s in sorted(sources, key=lambda x: (x["_family"], x["_position"])):
                    ingested += cell.learn(s, cards.get(s["id"]) or [])
                got = cell.deliver(t)
                by_summary = {c.get("summary"): s["id"] for s in sources for c in cards.get(s["id"]) or []}
                for c in got["cards"]:
                    c["source"] = by_summary.get(c["summary"])  # None: a card not from this lane (e.g. verify card)
                row[mode] = {"sources": [s["id"] for s in sources if cards.get(s["id"])], "ingested": ingested,
                             **got}
            rows.append(row)
            print(f"{lane} {t['id']}: " + ", ".join(
                f"{m} {len(row[m]['sources'])} sources -> {[c['source'] for c in row[m]['cards']]}" for m in modes),
                flush=True)
    finally:
        cell.close()
        run.reset_memory(cell.key)
    meta = {"lane": lane, "suite": suite_name, "scans": cell.scans, "fingerprint": fingerprint()}
    dest = out_dir(bool(suite.get("private"))) / f"{lane}-{suite_name}.json"
    dest.write_text(json.dumps({"meta": meta, "rows": rows}, indent=1))
    print(f"{suite_name} {lane}: {len(rows)} tasks, {cell.scans} scans -> {dest}")


def fingerprint() -> dict:
    def sha(p: Path) -> str | None:
        return hashlib.sha256(p.read_bytes()).hexdigest()[:16] if p.exists() else None
    jar = next(iter(sorted(run.ROOT.glob("target/ai-orchestration-*.jar"))), None)
    return {"jar": sha(jar) if jar else None, "labels": sha(run.BENCH / "trail-labels.json"),
            "labelsPrivate": sha(run.PRIVATE / "trail-labels.json"), "oracle": sha(run.BENCH / "oracle-cards.json"),
            "oraclePrivate": sha(run.PRIVATE / "oracle-cards.json"), "embedding": "bge-m3 (Ollama, bench profile)"}


# ------------------------------------------------------------------ report
def report() -> None:
    """Stage-wise tables from the replays and the K verdicts (verdicts.json: {lane: {task: {mode: true|false}}},
    written by reading the delivered card texts against the k_checks)."""
    labels = load_labels()
    verdicts: dict = {}
    for private in (False, True):
        f = out_dir(private) / "verdicts.json"
        if f.exists():
            for lane, per in json.loads(f.read_text()).items():
                if isinstance(per, dict):  # "_note" and other strings are documentation
                    verdicts.setdefault(lane, {}).update(per)
    for lane in ("observed", "oracle"):
        rows = []
        for private in (False, True):
            for f in sorted(out_dir(private).glob(f"{lane}-*.json")):
                rows += json.loads(f.read_text()).get("rows") or []  # observed-cards.json is the inventory
        if not rows:
            print(f"\n{lane}: no replays")
            continue
        pos = [r for r in rows if r["label"] == "positive"]
        neg = [r for r in rows if r["label"] == "negative"]
        print(f"\n## {lane} lane ({len(pos)} positives, {len(neg)} negatives replayed)")
        for stratum, keep in (("all", lambda r: True), ("certain", lambda r: not r["uncertain"])):
            p = [r for r in pos if keep(r)]
            n = [r for r in neg if keep(r)]
            for mode in ("chain", "pooled"):
                has = [r for r in p if r[mode]["sources"]]
                ok_ingest = [r for r in has if any((i.get("created") or 0) + (i.get("reused") or 0) > 0
                                                   for i in r[mode]["ingested"])]
                expected = [r for r in ok_ingest if any(c["source"] in labels[r["task"]]["expect"]
                                                        for c in r[mode]["cards"])]
                k = [r for r in expected if (verdicts.get(lane, {}).get(r["task"]) or {}).get(mode) is True]
                pending = [r for r in expected if (verdicts.get(lane, {}).get(r["task"]) or {}).get(mode) is None]
                print(f"| {stratum} | {mode} | positives {len(p)} | with lane cards (capture) {len(has)} | ingested "
                      f"{len(ok_ingest)} | expected card delivered {len(expected)} | K delivered {len(k)} "
                      f"({len(k) / max(1, len(p)):.0%}){f' — {len(pending)} verdicts pending' if pending else ''} |")
            nb = [r for r in n if any(c["source"] for c in r["pooled"]["cards"])]
            print(f"| {stratum} | pooled negatives with a lane card delivered | {len(nb)}/{len(n)} = "
                  f"{len(nb) / max(1, len(n)):.0%} |")
            pairs = sum(len(r["pooled"]["sources"]) for r in n)
            hits = sum(len({c["source"] for c in r["pooled"]["cards"] if c["source"]}) for r in n)
            print(f"| {stratum} | single-card control (derived from pooled; undercounts) | {hits}/{pairs} = "
                  f"{hits / max(1, pairs):.1%} |")


def combined() -> None:
    """The declared union (plan-card-diagnostic.md): cards first, then the trail block with the frozen baseline scorer.
    Positive delivered = K delivered by a card (verdict) OR the labelled definition shown by the trail block (own
    chain, each per its own rule; dedupe does not change either outcome). Rendered negative = a lane card delivered
    or a trail block rendered in the pooled corpus. Same gates: >= 60% own-chain positives, <= 10% rendered negatives
    (pooled and single-other-family)."""
    import trail_retrieval_eval as tre
    labels = load_labels()
    replays = {}
    for private in (False, True):
        for f in sorted(tre.out_dir(private).glob("*.replay.json")):
            replays.update({r["task"]: r["configs"]["baseline"] for r in json.loads(f.read_text())})
    verdicts: dict = {}
    for private in (False, True):
        f = out_dir(private) / "verdicts.json"
        if f.exists():
            for lane, per in json.loads(f.read_text()).items():
                if isinstance(per, dict):
                    verdicts.setdefault(lane, {}).update(per)
    pos = [t for t, x in labels.items() if x["label"] == "positive"]
    neg = [t for t, x in labels.items() if x["label"] == "negative"]
    trail_pos = {t for t in pos if t in replays and replays[t]["chain"]["picked"] in labels[t]["expect"]
                 and tre.delivered(replays[t]["chain"]["blocks"], labels[t])}
    trail_neg = {t for t in neg if t in replays and replays[t]["pooled"]["text"]}
    trail_pairs = sum(sum(replays[t]["pairs"].values()) for t in neg if t in replays)
    pair_total = sum(replays[t]["pair_total"] for t in neg if t in replays)
    print("\n## combined: cards + trail block (baseline scorer), as declared")
    for lane in ("observed", "oracle"):
        rows = {}
        for private in (False, True):
            for f in sorted(out_dir(private).glob(f"{lane}-*.json")):
                rows.update({r["task"]: r for r in json.loads(f.read_text()).get("rows") or []})
        card_pos = {t for t in pos if (verdicts.get(lane, {}).get(t) or {}).get("chain") is True}
        card_neg = {t for t in neg if t in rows and any(c["source"] for c in rows[t]["pooled"]["cards"])}
        card_pairs = sum(len({c["source"] for c in rows[t]["pooled"]["cards"] if c["source"]}) for t in neg if t in rows)
        card_pair_total = sum(len(rows[t]["pooled"]["sources"]) for t in neg if t in rows)
        for stratum, keep in (("all", lambda t: True), ("certain", lambda t: not labels[t].get("uncertain"))):
            p = [t for t in pos if keep(t)]
            n = [t for t in neg if keep(t)]
            got = [t for t in p if t in card_pos or t in trail_pos]
            bad = [t for t in n if t in card_neg or t in trail_neg]
            print(f"| {lane}+trail | {stratum} | own-chain delivered {len(got)}/{len(p)} = {len(got) / max(1, len(p)):.0%} "
                  f"(cards {len([t for t in p if t in card_pos])}, trail {len([t for t in p if t in trail_pos])}) | "
                  f"pooled negatives rendered {len(bad)}/{len(n)} = {len(bad) / max(1, len(n)):.0%} |")
        print(f"| {lane}+trail | single-other-family control | trail {trail_pairs}/{pair_total}, cards (derived) "
              f"{card_pairs}/{card_pair_total} = {(trail_pairs + card_pairs) / max(1, pair_total + card_pair_total):.1%} |")
        if lane == "observed":
            print("|   | note | observed cards exist only for the v1 suites; v2 positives count as not delivered by cards "
                  "(no saved B run: UNVERIFIED) |")


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--observed", action="store_true")
    ap.add_argument("--run", default="")
    ap.add_argument("--lane", default="oracle", choices=("observed", "oracle"))
    ap.add_argument("--report", action="store_true")
    args = ap.parse_args()
    if args.observed:
        observed()
    elif args.run:
        run_suite(args.run, args.lane)
    else:
        report()
        combined()


if __name__ == "__main__":
    main()
