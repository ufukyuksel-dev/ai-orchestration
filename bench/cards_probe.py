#!/usr/bin/env python3
"""Card-threshold probe: for every benchmark project that holds learned cards, score related and unrelated task
requests by bge-m3 cosine similarity against that project's memories (embedded here, per retrieval-text schema:
legacy-v1 = text, discovery-v2 = summary + text), then report per threshold how often a related request gets a
card (hit rate) and how often an unrelated one does (false-card rate).

  python3 bench/cards_probe.py            # writes bench/.work/results/cards-probe.json
"""
from __future__ import annotations

import json
import math
import subprocess
import sys
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import run  # noqa: E402

OLLAMA = "http://127.0.0.1:11434/api/embed"
THRESHOLDS = [0.40, 0.45, 0.50, 0.52, 0.55, 0.58, 0.60, 0.62, 0.65, 0.70]

QUERIES = {
    "D1": [  # validation / error message changes
        "Reject owner telephone numbers shorter than 7 digits with the error code `tooShort` and a message key `tooShort`.",
        "When a visit is booked, reject descriptions longer than 255 characters with error code `tooLong`.",
        "Pet type is required when adding a pet; show a proper validation message.",
        "Owner last names must not contain digits; add a validation error with a user-facing message.",
    ],
    "D2": [  # new optional field end to end
        "Owners should have an optional date of birth, editable on the owner form and shown on the owner page.",
        "Vets should have an optional phone number stored in the database and shown in the vet list.",
        "Visits should have an optional cost field saved with the visit and shown in the visit list.",
        "Pets should have an optional color attribute, editable and displayed on the owner details page.",
    ],
    "unrelated": [
        "Add a JSON endpoint that lists all specialties.",
        "Upgrade the Spring Boot version and fix any deprecations.",
        "Change the welcome page title and the logo.",
        "Speed up the owner search query with an index.",
        "Explain how the application's caching is configured.",
        "Add a Dockerfile for the application.",
    ],
}


def embed(text: str) -> list[float]:
    req = urllib.request.Request(OLLAMA, json.dumps({"model": "bge-m3", "input": text}).encode(),
                                 {"Content-Type": "application/json"}, method="POST")
    with urllib.request.urlopen(req, timeout=120) as r:
        return json.loads(r.read())["embeddings"][0]


def cosine(a: list[float], b: list[float]) -> float:
    dot = sum(x * y for x, y in zip(a, b))
    return dot / (math.sqrt(sum(x * x for x in a)) * math.sqrt(sum(y * y for y in b)))


def main() -> None:
    rows = run.psql("SELECT project_key, summary, replace(text, E'\\n', ' ') FROM memory_items WHERE status='active' "
                    "AND project_key LIKE 'CLAUDE_B_D%'").splitlines()
    memories: dict[str, list[tuple[str, str]]] = {}
    for line in rows:
        key, summary, text = line.split("|", 2)
        memories.setdefault(key, []).append((summary, text))
    projects = {k: v for k, v in memories.items() if len(v) >= 2}
    vectors = {q: embed(q) for qs in QUERIES.values() for q in qs}
    report = {}
    for schema in ("legacy-v1", "discovery-v2"):
        mem_vecs = {k: [embed(t if schema == "legacy-v1" else f"{s}\n{t}") for s, t in v] for k, v in projects.items()}
        samples = []
        for key, vecs in mem_vecs.items():
            family = key.split("_")[2]
            for group, qs in QUERIES.items():
                for q in qs:
                    samples.append({"project": key, "group": group, "related": group == family,
                                    "score": round(max(cosine(vectors[q], m) for m in vecs), 4), "query": q})
        related = [x["score"] for x in samples if x["related"]]
        other = [x["score"] for x in samples if not x["related"]]
        table = [{"threshold": t, "hitRate": round(sum(x >= t for x in related) / len(related), 2),
                  "falseCardRate": round(sum(x >= t for x in other) / len(other), 2)} for t in THRESHOLDS]
        report[schema] = {"table": table, "samples": samples}
        print(f"== {schema}: related {min(related):.3f}..{max(related):.3f}  other {min(other):.3f}..{max(other):.3f}")
        for row in table:
            print("  ", row)
    out = run.WORK / "results" / "cards-probe.json"
    out.write_text(json.dumps({"projects": {k: len(v) for k, v in projects.items()}, **report}, indent=2))
    print("projects:", {k: len(v) for k, v in projects.items()})


if __name__ == "__main__":
    main()
