#!/usr/bin/env python3
"""Offline retrieval acceptance for connected memory (plan-connected-memory.md §8.3). No agent runs.

1. `--build <suite>` (one process per suite: run.py keeps suite settings in module globals): for every task, the trail
   its reference solution would leave — the real capture code on a scratch worktree (snapshot tree, apply
   reference/<task>.patch, snapshot, `edited_locators` + `referenced_locators` + `with_text`), or for investigation
   tasks the symbols the reference answer cites. No reads (there is no transcript): real trails add observed reads. Written to .work/trail-eval/<suite>.json (private suites: under $BENCH_PRIVATE).
2. `--replay <suite>`: selection and rendering for every task against its own baseline checkout (pooled corpus and
   own-chain-only corpus), so a correctly ranked trail whose symbols do not resolve counts as no context.
3. default: labels from the chain notes (load_labels, written before scoring) — recall@1 on positives, false
   injections on negatives only, single-trail negative pairs, the shares-file proxy as a separate diagnostic, and the
   replayed delivery (right trail → block rendered → a labelled symbol shown).
These tasks are development data for retrieval now (used for tuning); confirmation needs untouched tasks.

  python3 bench/trail_retrieval_eval.py --build <suite>; --replay <suite>; then python3 bench/trail_retrieval_eval.py
"""
from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parent / "scripts" / "hooks"))
import run  # noqa: E402

V1 = {"holdout", "py-holdout", "go-holdout"}


def out_dir(private: bool) -> Path:
    return (run.PRIVATE if private else run.WORK) / "trail-eval"


def build(suite_name: str) -> None:
    import verify_task
    import ai_orch_session_state as session
    import ai_orch_trail as trail
    suite = verify_task.configure(suite_name)
    wt = run.WT_ROOT / f"trail-eval-{suite_name}"
    run.fresh_worktree(suite["repo"], wt)
    trails = []
    try:
        for family in suite["families"]:
            chain = suite_name in V1 or bool(family.get("chain"))
            for pos, task in enumerate(family["tasks"], 1):
                run.reset_code(wt, suite["repo"], task)
                start = session.snapshot_tree(str(wt))
                edited, cited, refs = [], [], []
                if task.get("kind") == "investigation":
                    answer = (run.ASSETS / "reference" / f"{task['id']}.answer.md").read_text()
                    cited = trail.cited_locators(answer, str(wt))
                else:
                    patch = run.ASSETS / "reference" / f"{task['id']}.patch"
                    if patch.exists() and subprocess.run(["git", "-C", str(wt), "apply", str(patch)],
                                                         capture_output=True).returncode == 0:
                        end = session.snapshot_tree(str(wt))
                        edited = trail.edited_locators(str(wt), start, end)
                        refs = trail.referenced_locators(str(wt), start, end, edited)
                        edited = trail.with_text(str(wt), edited, end)
                trails.append({"suite": suite_name, "repo": suite["repo"], "family": f"{suite_name}/{family['id']}",
                               "chain": chain, "position": pos, "task": task["id"], "requestText": task["prompt"],
                               "edited": edited, "cited": cited, "supportingReads": refs, "checks": []})
    finally:
        subprocess.run(["git", "-C", str(run.WORK / "repos" / suite["repo"]), "worktree", "remove", "--force", str(wt)],
                       capture_output=True)
    dest = out_dir(bool(suite.get("private"))) / f"{suite_name}.json"
    dest.parent.mkdir(parents=True, exist_ok=True)
    dest.write_text(json.dumps(trails, indent=1))
    print(f"{suite_name}: {len(trails)} trails, {sum(bool(t['edited'] or t['cited']) for t in trails)} with locators")


def load_trails() -> list[dict]:
    trails = []
    for private in (False, True):
        for f in sorted(out_dir(private).glob("*.json")):
            if not f.name.endswith(".replay.json"):
                trails += json.loads(f.read_text())
    return trails


def load_labels() -> dict[str, dict]:
    """task id -> label, written from the chain notes before any scoring (bench/trail-labels.json, private ones in
    $BENCH_PRIVATE/trail-labels.json): positive = a follow-up that needs knowledge of earlier tasks (`expect`) living
    in `symbols`; negative = first positions, independent tasks and chain controls."""
    out = {}
    for f in (run.BENCH / "trail-labels.json", run.PRIVATE / "trail-labels.json"):
        if f.exists():
            for suite, labels in json.loads(f.read_text()).items():
                out.update({x["task"]: dict(x, suite=suite) for x in labels})
    return out


def shares_file(task: dict, trail: dict) -> bool:
    """Exploratory proxy only: the trail's code shares a file with the task's reference solution."""
    files = {loc["path"] for loc in task["edited"] + task["cited"]}
    return bool(files & {loc["path"] for loc in trail["edited"] + trail["cited"]})


def has_locators(t: dict) -> bool:
    return bool(t["edited"] or t["cited"])


def pooled_corpus(t: dict, trails: list[dict]) -> list[dict]:
    """What a later request could see: the repository's other trails, without later steps of its own chain."""
    return [c for c in trails if c["repo"] == t["repo"] and c is not t and has_locators(c)
            and not (c["family"] == t["family"] and c["position"] >= t["position"])]


def chain_corpus(t: dict, trails: list[dict]) -> list[dict]:
    """Cold start (a benchmark chain): only the earlier steps of its own chain."""
    return [c for c in trails if c["family"] == t["family"] and c["position"] < t["position"] and has_locators(c)]


def blocks_of(text: str) -> list[tuple[str, str, str]]:
    """(kind current|historical, path, first code line) of each excerpt of a rendered trail block: a header line
    followed by a numbered code line."""
    out, lines = [], text.splitlines()
    numbered = re.compile(r"^\s*\d+  ")
    for head, nxt in zip(lines, lines[1:]):
        if numbered.match(head) or not numbered.match(nxt):
            continue
        m = re.match(r"^(\S+):\d+-\d+ \(current source\)$", head)
        kind, path = ("current", m.group(1)) if m else ("historical", head.split(" ", 1)[0])
        out.append((kind, path, numbered.sub("", nxt)))
    return out


# Declared before the variant's first run (Codex round-3 contract): the frozen baseline plus a small grid of the
# two-channel variant (request + weight × code vocabulary). Chosen config: the highest own-chain delivery on all
# labels among configs with <= 10% rendered negatives in both controls; ties -> higher threshold, then lower weight.
CONFIGS = {"baseline": (None, 0.30)} | {f"w{w}-t{t}": (w, t) for w in (0.5, 1.0) for t in (0.30, 0.35, 0.40, 0.45)}


def replay(suite_name: str) -> None:
    """Selection AND rendering, per task of the suite and per config, against that task's own baseline checkout
    (what the next request really finds): pooled corpus, own-chain corpus, and every single other-family trail that
    clears the config's threshold (negative control). Written to <suite>.replay.json."""
    import verify_task
    import ai_orch_trail_retrieve as retrieve
    suite = verify_task.configure(suite_name)
    trails = load_trails()
    labels = load_labels()
    wt = run.WT_ROOT / f"trail-replay-{suite_name}"
    run.fresh_worktree(suite["repo"], wt)
    rows = []
    try:
        for family in suite["families"]:
            for task in family["tasks"]:
                t = next(x for x in trails if x["task"] == task["id"])
                run.reset_code(wt, suite["repo"], task)
                row = {"task": task["id"], "configs": {}}
                expect = set((labels.get(task["id"]) or {}).get("expect") or [])
                others = [c for c in trails if c["repo"] == t["repo"] and c["family"] != t["family"]
                          and has_locators(c) and c["task"] not in expect]
                for name, (weight, threshold) in CONFIGS.items():
                    out = {}
                    for mode, corpus in (("pooled", pooled_corpus(t, trails)), ("chain", chain_corpus(t, trails))):
                        picked = retrieve.select(t["requestText"], corpus, threshold, weight)
                        text, keys = (retrieve.render(picked[1], str(wt), set(), t["requestText"]) if picked
                                      else ("", []))
                        out[mode] = {"picked": picked[1]["task"] if picked else None,
                                     "score": round(picked[0], 3) if picked else None, "chars": len(text),
                                     "text": text, "blocks": blocks_of(text)}
                    out["pairs"] = {c["task"]: bool(retrieve.render(c, str(wt), set(), t["requestText"])[0])
                                    for c in others
                                    if retrieve.score(t["requestText"], [c], weight)[0][0] >= threshold}
                    out["pair_total"] = len(others)
                    row["configs"][name] = out
                rows.append(row)
    finally:
        subprocess.run(["git", "-C", str(run.WORK / "repos" / suite["repo"]), "worktree", "remove", "--force", str(wt)],
                       capture_output=True)
    dest = out_dir(bool(suite.get("private"))) / f"{suite_name}.replay.json"
    dest.write_text(json.dumps(rows, indent=1))
    print(f"{suite_name}: replayed {len(rows)} tasks x {len(CONFIGS)} configs")


def delivered(blocks: list, label: dict) -> str:
    """"current" / "historical" when an excerpt is the definition of a labelled symbol at its labelled path (current
    source preferred), else ""."""
    import ai_orch_trail as capture
    wanted = {(x["path"], x["symbol"]) for x in label.get("symbols") or []}
    kinds = {kind for kind, path, first in blocks if (path, capture.symbol_name(first)[0]) in wanted}
    return "current" if "current" in kinds else ("historical" if kinds else "")


def uncertain(label: dict) -> bool:
    """Labels the labeller flagged as uncertain (`"uncertain": true`, e.g. stale-tree positions)."""
    return bool(label.get("uncertain"))


def evaluate(thresholds: list[float]) -> None:
    import ai_orch_trail_retrieve as retrieve
    trails, labels = load_trails(), load_labels()
    unlabelled = [t["task"] for t in trails if t["task"] not in labels]
    if unlabelled:
        print(f"unlabelled tasks (left out): {unlabelled}")
    trails_l = [t for t in trails if t["task"] in labels]
    pos = [t for t in trails_l if labels[t["task"]]["label"] == "positive"]
    neg = [t for t in trails_l if labels[t["task"]]["label"] == "negative"]
    print(f"labelled: {len(pos)} positives, {len(neg)} negatives")
    for mode, corpus_of in (("pooled", pooled_corpus), ("cold start (own chain only)", chain_corpus)):
        print(f"\n{mode}: ranking")
        print("| threshold | recall@1 (positives) | false injections (negatives) | shares-file diagnostic (all injected) |")
        print("|---|---|---|---|")
        for th in thresholds:
            hit = fi = inj = sf = 0
            for t in trails_l:
                ranked = retrieve.score(t["requestText"], corpus_of(t, trails))
                if not ranked or ranked[0][0] < th:
                    continue
                top = ranked[0][1]
                inj += 1
                sf += shares_file(t, top)
                if t in pos and top["task"] in labels[t["task"]]["expect"]:
                    hit += 1
                elif t in neg:
                    fi += 1
            print(f"| {th:.2f} | {hit}/{len(pos)} | {fi}/{len(neg)} = {fi / max(1, len(neg)):.0%} | {sf}/{inj} |")
    # single-trail pairs: each task against one trail of another family; a pair is negative unless labelled expected
    pairs = [(t, c, retrieve.score(t["requestText"], [c])[0][0]) for t in trails_l for c in trails
             if c["repo"] == t["repo"] and c["family"] != t["family"] and has_locators(c)
             and c["task"] not in (labels[t["task"]].get("expect") or [])]
    print(f"\nsingle-trail negative pairs (other family, not expected): {len(pairs)}")
    for th in thresholds:
        n = sum(1 for *_, v in pairs if v >= th)
        print(f"  {th:.2f}: {n}/{len(pairs)} = {n / max(1, len(pairs)):.1%} injected")
    replays = {}
    for private in (False, True):
        for f in sorted(out_dir(private).glob("*.replay.json")):
            replays.update({r["task"]: r for r in json.loads(f.read_text())})
    if not replays:
        return
    print("\nreplay: selection + rendering against each task's own baseline, per config "
          "(own-chain delivery = labelled definition shown current / historical)")
    print("| config | stratum | own chain: right trail | own chain: delivered (cur/hist) | pooled: delivered | "
          "pooled negatives with a block | single-trail negatives with a block | max chars |")
    print("|---|---|---|---|---|---|---|---|")
    results = {}
    for name in CONFIGS:
        for stratum, keep in (("all", lambda x: True), ("certain", lambda x: not uncertain(labels[x["task"]]))):
            p = [t for t in pos if keep(t) and t["task"] in replays]
            n = [t for t in neg if keep(t) and t["task"] in replays]
            r = {t["task"]: replays[t["task"]]["configs"][name] for t in p + n}
            right = [t for t in p if r[t["task"]]["chain"]["picked"] in labels[t["task"]]["expect"]]
            got = [delivered(r[t["task"]]["chain"]["blocks"], labels[t["task"]]) for t in right]
            pooled_got = sum(1 for t in p if r[t["task"]]["pooled"]["picked"] in labels[t["task"]]["expect"]
                             and delivered(r[t["task"]]["pooled"]["blocks"], labels[t["task"]]))
            neg_b = sum(1 for t in n if r[t["task"]]["pooled"]["text"])
            pair_b = sum(sum(r[t["task"]]["pairs"].values()) for t in p + n)
            pair_n = sum(r[t["task"]]["pair_total"] for t in p + n)
            chars = max([r[t["task"]][m]["chars"] for t in p + n for m in ("chain", "pooled")] or [0])
            hit = len(got) - got.count("")
            results[(name, stratum)] = (hit / max(1, len(p)), neg_b / max(1, len(n)), pair_b / max(1, pair_n))
            print(f"| {name} | {stratum} | {len(right)}/{len(p)} | {hit}/{len(p)} = {hit / max(1, len(p)):.0%} "
                  f"({got.count('current')}/{got.count('historical')}) | {pooled_got}/{len(p)} | "
                  f"{neg_b}/{len(n)} = {neg_b / max(1, len(n)):.0%} | {pair_b}/{pair_n} = {pair_b / max(1, pair_n):.1%} "
                  f"| {chars} |")
    ok = [(name, results[(name, "all")]) for name in CONFIGS if name != "baseline"
          and results[(name, "all")][1] <= 0.10 and results[(name, "all")][2] <= 0.10]
    if ok:
        best = max(ok, key=lambda x: (x[1][0], CONFIGS[x[0]][1], -CONFIGS[x[0]][0]))
        print(f"\nchosen by the declared rule: {best[0]} (delivery {best[1][0]:.0%}, pooled negatives "
              f"{best[1][1]:.0%}, single-trail negatives {best[1][2]:.1%}); gate >= 60% and <= 10%/10%: "
              f"{'MET' if best[1][0] >= 0.60 else 'NOT MET'}")
    else:
        print("\nno variant config keeps both negative controls <= 10%: gate NOT MET")


def capture_name(line: str) -> str:
    import ai_orch_trail as capture
    return capture.symbol_name(line)[0]


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--build", default="")
    ap.add_argument("--replay", default="")
    ap.add_argument("--thresholds", default="0.1,0.15,0.2,0.25,0.3,0.4")
    args = ap.parse_args()
    if args.build:
        build(args.build)
    elif args.replay:
        replay(args.replay)
    else:
        evaluate([float(x) for x in args.thresholds.split(",")])


if __name__ == "__main__":
    main()
