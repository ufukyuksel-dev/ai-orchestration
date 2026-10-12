#!/usr/bin/env python3
"""Replay the admitted oracle corpus through local dense retrieval; no scans or agent runs.

The original diagnostic already tested ingestion. These fixtures preserve embedding text/tags,
confidence, scope and active status, using decision admission rather than the reserved discovery
admission. Memory type does not affect dense scoring. This measures delivery, not ingestion.
"""
from __future__ import annotations

import json
import subprocess
import time
import urllib.parse

import card_diagnostic as diag
import run

VERIFY_QUERY = "how to build, run the tests and verify a change in this repository, and which tests fail here"
FLOORS = (0.45, 0.55, 0.65, 0.75, 0.85)


def select(trace: dict, floor: float, filler: str) -> list[dict]:
    def lane(name: str, procedure_only: bool = False) -> list[dict]:
        items = [c for c in trace[name] if c["semanticScore"] >= floor
                 and (not procedure_only or c["kind"] in {"procedure", "test_address"})]
        items.sort(key=lambda c: c["semanticScore"], reverse=True)
        if not items:
            return []
        return [c for c in items if c["semanticScore"] >= items[0]["semanticScore"] - 0.10][:3]

    out = lane("task")
    if len(out) < 3 and filler != "none":
        seen = {c["memoryId"] for c in out}
        out += [c for c in lane("verify", filler == "procedure") if c["memoryId"] not in seen]
    return out[:3]


def pointers(trace: dict, floor: float, pointer_floor: float, shown: list[dict]) -> list[dict]:
    """At most one task match between the pointer floor and the card floor, not already shown (bootstrap's pointer)."""
    seen = {c["memoryId"] for c in shown}
    weaker = sorted((c for c in trace["task"] if pointer_floor <= c["semanticScore"] < floor
                     and c["memoryId"] not in seen), key=lambda c: c["semanticScore"], reverse=True)
    return weaker[:1]


def task_inventory() -> dict:
    tasks = {}
    for root in (run.BENCH, run.PRIVATE):
        for path in (root / "tasks").glob("*.json"):
            if path.stem.endswith("-pilot"):
                continue
            suite = json.loads(path.read_text())
            for family in suite["families"]:
                for task in family["tasks"]:
                    tasks[task["id"]] = {**task, "repo": suite["repo"]}
    return tasks


def fixtures(key: str, saved: dict, oracle: dict) -> dict:
    """Only candidates admitted in the original row; rejected cards are never substituted."""
    run.reset_memory(key)
    by_id = {}
    for receipt in saved["ingested"]:
        if not (receipt.get("created", 0) or receipt.get("reused", 0)):
            continue
        if not receipt.get("capture"):
            raise RuntimeError("Replay needs a recorded discovery admission, not an inferred fallback")
        source = receipt["source"]
        for candidate in oracle[source]:
            if candidate["summary"] not in receipt["summaries"]:
                continue
            kind = candidate["kind"]
            locators = [{"kind": x["kind"], "ref": x["ref"], "relationship": "mentions",
                         **({"path": x["path"]} if x.get("path") else {})}
                        for x in candidate.get("locators", [])]
            item = run.http_json("POST", "/api/memory", {
                "scope": "project", "projectKey": key, "memoryType": "decision",
                "summary": candidate["summary"].strip(), "text": candidate["content"].strip(),
                "tags": ["discovery", kind], "confidence": 0.9, "status": "active",
                "sourceType": "mcp_external", "sourceRef": "card-relevance-fixture",
                "lastVerifiedAt": "2026-10-12T00:00:00Z",
                "metadata": {"discoveryKind": kind, "codeLocators": {"version": 1, "items": locators}},
            })
            by_id[item["id"]] = {"source": source, "kind": kind, "summary": item["summary"],
                                  "text": item["text"]}
    # All scores at/above the existing 0.45 floor fit the fixed backend's candidate and project budgets.
    # Therefore filtering this complete score set by source reproduces isolated single-source retrieval.
    if len(by_id) > 10 or sum(len((c["summary"] + " " + c["text"]).split()) for c in by_id.values()) > 700:
        raise RuntimeError("Corpus exceeds retrieval budgets; isolated pairs must be measured separately")
    # Index updates are asynchronous: wait for exactly this fixture set before collecting scores.
    deadline = time.monotonic() + 20
    while True:
        body = json.dumps({"filter": {"must": [{"key": "project_key", "match": {"value": key}}]},
                           "exact": True}).encode()
        req = run.urllib.request.Request(
            f"{run.QDRANT}/collections/{run.MEMORY_COLLECTION}/points/count", body,
            {"Content-Type": "application/json"}, method="POST")
        with run.urllib.request.urlopen(req, timeout=10) as response:
            count = json.load(response)["result"]["count"]
        if count == len(by_id):
            return by_id
        if time.monotonic() >= deadline:
            raise RuntimeError("Fixture vectors were not ready; no scores recorded")
        time.sleep(0.05)


def scores(key: str, query: str, by_id: dict) -> list[dict]:
    params = urllib.parse.urlencode({"projectKey": key, "query": query})
    data = run.http_json("GET", "/api/memory/retrieve?" + params)
    unknown = [c["memoryId"] for c in data["items"] if c["memoryId"] not in by_id]
    if unknown:
        raise RuntimeError("Unexpected memories in isolated fixture retrieval")
    return [{"memoryId": c["memoryId"], "semanticScore": c["semanticScore"], **by_id[c["memoryId"]]}
            for c in data["items"]]


def measure(verify_floor: float = 0.45, pointer_floor: float = 1.0) -> None:
    oracle, _ = diag.load_oracle()
    tasks = task_inventory()
    all_rows = []
    explicit_search_verified = False
    for private in (False, True):
        for path in sorted(diag.out_dir(private).glob("oracle-*.json")):
            if path.name.endswith(".relevance.json"):
                continue
            data = json.loads(path.read_text())
            rows = data.get("rows", [])
            if not rows:
                continue
            import verify_task
            verify_task.configure(data["meta"]["suite"])
            root = run.WT_ROOT / ("card-relevance-" + path.stem)
            run.fresh_worktree(tasks[rows[0]["task"]]["repo"], root)
            key = run.project_key_for(root)
            traced = []
            try:
                for row in rows:
                    task = tasks[row["task"]]
                    run.reset_code(root, task["repo"], task)
                    item = {"task": row["task"], "label": row["label"], "uncertain": row["uncertain"]}
                    for mode in ("chain", "pooled"):
                        if mode not in row:
                            continue
                        ids = fixtures(key, row[mode], oracle)
                        item[mode] = {"task": scores(key, task["prompt"][:500], ids),
                                      "verify": scores(key, VERIFY_QUERY, ids),
                                      "sources": row[mode]["sources"], "candidateCount": len(ids),
                                      "tokenUpperBound": sum(len((c["summary"] + " " + c["text"]).split())
                                                             for c in ids.values())}
                        # Real bootstrap verifies the implemented selector at the configured floor.
                        actual = diag.hook.bootstrap(str(root), task["prompt"][:500]) or {}
                        actual_ids = [(c["id"], bool(c.get("pointer"))) for c in actual.get("memory", [])]
                        full = select(item[mode], verify_floor, "procedure")
                        expected_ids = [(c["memoryId"], False) for c in full] + [
                            (c["memoryId"], True) for c in pointers(item[mode], verify_floor, pointer_floor, full)]
                        if actual_ids != expected_ids:
                            raise RuntimeError(f"Bootstrap/selector mismatch: {row['task']} {mode}")
                        if not explicit_search_verified and verify_floor > 0.45 and not actual_ids \
                                and item[mode]["task"]:
                            found = run.mcp_call("memory.search", {
                                "query": task["prompt"][:500], "projectKey": key, "view": "debug", "topK": 10})
                            if not any(c["memoryId"] in ids for c in found.get("items", [])):
                                raise RuntimeError("A weak match disappeared from explicit memory.search")
                            explicit_search_verified = True
                        item[mode]["bootstrapVerified"] = True
                        item[mode]["bootstrapFloor"] = verify_floor
                        item[mode]["pointerFloor"] = pointer_floor
                    traced.append(item)
                    print(f"relevance {row['task']}: scored", flush=True)
                dest = diag.out_dir(private) / (path.stem + ".relevance.json")
                dest.write_text(json.dumps({"fixtureReplay": True, "fingerprint": diag.fingerprint(),
                                            "bootstrapFloor": verify_floor, "rows": traced}, indent=1))
                all_rows.extend(traced)
            finally:
                run.reset_memory(key)
                subprocess.run(["git", "-C", str(run.WORK / "repos" / tasks[rows[0]["task"]]["repo"]),
                                "worktree", "remove", "--force", str(root)], capture_output=True, check=True)
    report(all_rows)
    print(f"explicit_search_retains_weak_matches={explicit_search_verified}")
    if pointer_floor < 1.0:
        tiered(all_rows, verify_floor, pointer_floor)


def tiered(rows: list[dict], floor: float, pointer_floor: float) -> None:
    """Full cards at the card floor plus one pointer line between the pointer floor and it: how many related
    follow-ups reach their card (in full / only as a pointer) and how many unrelated queries get either."""
    labels = diag.load_labels()
    positives = [r for r in rows if r["label"] == "positive"]
    negatives = [r for r in rows if r["label"] == "negative"]
    for mode in ("chain", "pooled"):
        full_hit = pointer_hit = 0
        for r in positives:
            full = select(r[mode], floor, "procedure")
            expect = labels[r["task"]]["expect"]
            if any(c["source"] in expect for c in full):
                full_hit += 1
            elif any(c["source"] in expect for c in pointers(r[mode], floor, pointer_floor, full)):
                pointer_hit += 1
        print(f"tiered {floor:.2f}/{pointer_floor:.2f} {mode}: positives full {full_hit} + pointer {pointer_hit} "
              f"of {len(positives)}")
    neg_full = sum(bool(select(r["pooled"], floor, "procedure")) for r in negatives)
    neg_pointer = sum(not select(r["pooled"], floor, "procedure")
                      and bool(pointers(r["pooled"], floor, pointer_floor, [])) for r in negatives)
    print(f"tiered negatives: full card {neg_full}/{len(negatives)}, pointer line only {neg_pointer}/{len(negatives)}")


def report(rows: list[dict] | None = None) -> None:
    labels = diag.load_labels()
    if rows is None:
        rows = []
        for private in (False, True):
            for path in diag.out_dir(private).glob("oracle-*.relevance.json"):
                rows += json.loads(path.read_text())["rows"]
    positives = [r for r in rows if r["label"] == "positive"]
    negatives = [r for r in rows if r["label"] == "negative"]
    print("floor filler positives negatives pooled-positives isolated-source-negatives")
    for floor in FLOORS:
        for filler in ("all", "none", "procedure"):
            positive = sum(any(c["source"] in labels[r["task"]]["expect"]
                               for c in select(r["chain"], floor, filler)) for r in positives)
            pooled = sum(any(c["source"] in labels[r["task"]]["expect"]
                             for c in select(r["pooled"], floor, filler)) for r in positives)
            negative = sum(bool(select(r["pooled"], floor, filler)) for r in negatives)
            pair_hits = pair_count = 0
            for row in negatives:
                trace = row["pooled"]
                for source in trace["sources"]:
                    single = {lane: [c for c in trace[lane] if c["source"] == source]
                              for lane in ("task", "verify")}
                    pair_hits += bool(select(single, floor, filler))
                    pair_count += 1
            print(f"{floor:.2f} {filler} {positive}/{len(positives)} {negative}/{len(negatives)} "
                  f"{pooled}/{len(positives)} {pair_hits}/{pair_count}")


if __name__ == "__main__":
    import argparse
    parser = argparse.ArgumentParser()
    parser.add_argument("--report", action="store_true")
    parser.add_argument("--verify-floor", type=float, default=0.45)
    parser.add_argument("--pointer-floor", type=float, default=1.0)
    args = parser.parse_args()
    report() if args.report else measure(args.verify_floor, args.pointer_floor)
