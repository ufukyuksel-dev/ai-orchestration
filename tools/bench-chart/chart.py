#!/usr/bin/env python3
"""Turn benchmark results into the README chart (SVG) and the promo's numbers (JSON). Numbers come only from the
results files; nothing is typed in by hand.

  python3 tools/bench-chart/chart.py \
      --run claude:A=bench/.work/results/final-claude-A --run claude:B=bench/.work/results/final-claude-B \
      --run codex:A=bench/.work/results/final-codex-A   --run codex:B=bench/.work/results/final-codex-B \
      --out docs/assets

Writes docs/assets/benchmark.svg, benchmark.json (read by tools/promo-video) and benchmark.md (a results table).
--fill FILE:NAME puts the table between FILE's `<!-- NAME:BEGIN ... -->` and `<!-- NAME:END -->` lines.
"""
from __future__ import annotations

import argparse
import json
import re
import statistics
from datetime import date
from pathlib import Path

NAMES = {"claude": "Claude Code", "codex": "Codex"}
BG, CARD, TEXT, DIM = "#080b18", "#0f1426", "#eceff6", "#808aa0"
A_COLOR, B_COLOR, GOOD = "#5b6478", "#22d3ee", "#34d399"


def load(paths: list[str]) -> dict[str, dict[str, list[dict]]]:
    runs: dict[str, dict[str, list[dict]]] = {}
    for spec in paths:
        key, path = spec.split("=", 1)
        agent, arm = key.split(":")
        rows = [json.loads(line) for line in (Path(path) / "results.jsonl").read_text().splitlines() if line.strip()]
        runs.setdefault(agent, {}).setdefault(arm, []).extend(rows)
    return runs


def stats(rows: list[dict]) -> dict:
    """Unrounded values (percentages are computed from these); display code rounds."""
    related = [r for r in rows if r["position"] > 1]
    first = [r for r in rows if r["position"] == 1]

    def mean_m(rs):
        return statistics.mean(r["totalTokens"] for r in rs) / 1e6 if rs else None

    def per_success(rs, field="totalTokens", unit=1e6):
        ok = sum(r["success"] for r in rs)
        if not ok or any(r.get(field) is None for r in rs):
            return None
        return sum(r[field] for r in rs) / ok / unit

    return {"tasks": len(rows), "success": sum(r["success"] for r in rows),
            "relatedMean": mean_m(related), "relatedPerSuccess": per_success(related),
            "firstMean": mean_m(first), "allMean": mean_m(rows), "allPerSuccess": per_success(rows),
            "relatedUsdPerSuccess": per_success(related, "costUsd", 1), "allUsdPerSuccess": per_success(rows, "costUsd", 1),
            # Codex reports a whole `exec` run as one turn: no turn count to compare
            "relatedTurns": statistics.mean(r["turns"] for r in related)
            if related and any(r["turns"] > 1 for r in related) else None}


def pct(a, b):
    return None if not a or b is None else 100 * (b - a) / a


def fmt(value, spec, suffix=""):
    return "—" if value is None else f"{value:{spec}}{suffix}"


def svg(summary: dict, caption: str) -> str:
    agents = list(summary)
    row_h, top, left, width = 150, 96, 250, 1000
    height = top + row_h * len(agents) + 70
    scale_max = max(max(s["A"]["relatedPerSuccess"] or 0, s["B"]["relatedPerSuccess"] or 0) for s in summary.values())
    bar_w = width - left - 250
    out = [f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}" viewBox="0 0 {width} {height}" '
           f'font-family="-apple-system, Segoe UI, Helvetica, Arial, sans-serif">',
           f'<rect width="{width}" height="{height}" rx="18" fill="{BG}"/>',
           f'<text x="40" y="52" fill="{TEXT}" font-size="26" font-weight="700">Tokens per successful task on '
           f'follow-up tasks</text>',
           f'<text x="40" y="80" fill="{DIM}" font-size="16">Same agent, same tasks: without (grey) and with '
           f'AI Orchestration (cyan). Lower is better.</text>']
    for i, agent in enumerate(agents):
        s = summary[agent]
        y = top + i * row_h + 20
        out.append(f'<text x="40" y="{y + 44}" fill="{TEXT}" font-size="22" font-weight="600">{NAMES.get(agent, agent)}</text>')
        out.append(f'<text x="40" y="{y + 70}" fill="{DIM}" font-size="14">success {s["B"]["success"]}/{s["B"]["tasks"]}'
                   f' vs {s["A"]["success"]}/{s["A"]["tasks"]}</text>')
        for j, (arm, color, label) in enumerate((("A", A_COLOR, "without"), ("B", B_COLOR, "with"))):
            value = s[arm]["relatedPerSuccess"] or 0
            w = max(4, bar_w * value / scale_max) if scale_max else 4
            by = y + j * 48
            out.append(f'<rect x="{left}" y="{by}" width="{w:.1f}" height="36" rx="8" fill="{color}"/>')
            out.append(f'<text x="{left + w + 12:.1f}" y="{by + 25}" fill="{TEXT}" font-size="18">{value:.2f}M'
                       f' <tspan fill="{DIM}" font-size="14">{label}</tspan></text>')
        delta = s["relatedDeltaPct"]
        if delta is not None:
            color = GOOD if delta < 0 else "#f87171"
            out.append(f'<text x="{width - 40}" y="{y + 56}" fill="{color}" font-size="34" font-weight="700" '
                       f'text-anchor="end">{delta:+.0f}%</text>')
    # long captions go on two lines, split at the separator nearest the middle
    parts = caption.split(" · ")
    split = min(range(1, len(parts)), key=lambda i: abs(len(" · ".join(parts[:i])) - len(caption) / 2), default=len(parts))
    for k, line in enumerate((" · ".join(parts[:split]), " · ".join(parts[split:]))):
        if line:
            out.append(f'<text x="40" y="{height - 38 + 18 * k}" fill="{DIM}" font-size="13">{line}</text>')
    out.append("</svg>")
    return "\n".join(out)


def table(summary: dict, caption: str) -> str:
    lines = ["| Agent | Arm | Success | Follow-up tasks: tokens per success | First task (learns): mean tokens | "
             "All tasks: tokens per success | All tasks: $ per success | Follow-up turns |",
             "|---|---|---|---|---|---|---|---|"]
    for agent, s in summary.items():
        for arm, label in (("A", "without"), ("B", "with AI Orchestration")):
            x = s[arm]
            lines.append(f"| {NAMES.get(agent, agent)} | {label} | {x['success']}/{x['tasks']} | "
                         f"{fmt(x['relatedPerSuccess'], '.2f', 'M')} | {fmt(x['firstMean'], '.2f', 'M')} | "
                         f"{fmt(x['allPerSuccess'], '.2f', 'M')} | {fmt(x['allUsdPerSuccess'], '.2f')} | "
                         f"{fmt(x['relatedTurns'], '.1f')} |")
        lines.append(f"| | **difference** | | **{fmt(s['relatedDeltaPct'], '+.1f', '%')}** | "
                     f"{fmt(s['firstDeltaPct'], '+.1f', '%')} | **{fmt(s['allDeltaPct'], '+.1f', '%')}** | "
                     f"{fmt(s['usdDeltaPct'], '+.1f', '%')} | |")
    return "\n".join(lines) + f"\n\n{caption}\n"


def fill(path: Path, name: str, body: str) -> None:
    text = path.read_text()
    pattern = re.compile(rf"(<!-- {name}:BEGIN[^\n]*-->\n).*?(<!-- {name}:END -->)", re.S)
    if not pattern.search(text):
        raise SystemExit(f"{path}: no {name}:BEGIN/END markers")
    path.write_text(pattern.sub(lambda m: m.group(1) + body + m.group(2), text))


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--run", action="append", required=True, help="agent:ARM=results-dir (repeatable)")
    ap.add_argument("--out", default="docs/assets")
    ap.add_argument("--caption", default="")
    ap.add_argument("--note", default="", help="appended to the caption, e.g. how the measurement relates to tuning")
    ap.add_argument("--repo-url", default="github.com/<owner>/ai-orchestration")
    ap.add_argument("--fill", action="append", default=[], help="FILE:NAME, e.g. README.md:BENCHMARK (repeatable)")
    args = ap.parse_args()
    runs = load(args.run)
    summary = {}
    for agent, arms in runs.items():
        if not {"A", "B"} <= set(arms):
            continue
        a, b = stats(arms["A"]), stats(arms["B"])
        summary[agent] = {"A": a, "B": b, "relatedDeltaPct": pct(a["relatedPerSuccess"], b["relatedPerSuccess"]),
                          "firstDeltaPct": pct(a["firstMean"], b["firstMean"]),
                          "allDeltaPct": pct(a["allPerSuccess"], b["allPerSuccess"]),
                          "usdDeltaPct": pct(a["allUsdPerSuccess"], b["allUsdPerSuccess"])}
    reps = max((r.get("rep", 1) for arms in runs.values() for rows in arms.values() for r in rows), default=1)
    models = sorted({r.get("model") or "default" for arms in runs.values() for rows in arms.values() for r in rows})
    suites = sorted({json.loads((Path(spec.split("=", 1)[1]) / "meta.json").read_text()).get("suite", "?")
                     for spec in args.run if (Path(spec.split("=", 1)[1]) / "meta.json").exists()})
    families = len({r["family"] for arms in runs.values() for rows in arms.values() for r in rows})
    caption = args.caption or (f"Spring Petclinic ({', '.join(suites)} suite), {families} task families × 3 related tasks, "
                               f"{reps} repetitions, cumulative "
                               f"learning chain · models: {', '.join(models)} · {date.today().isoformat()}")
    if args.note:
        caption += f" · {args.note}"
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    (out / "benchmark.svg").write_text(svg(summary, caption))
    (out / "benchmark.md").write_text(table(summary, caption))
    for spec in args.fill:
        path, name = spec.rsplit(":", 1)
        fill(Path(path), name, table(summary, caption))
    promo = {"placeholder": False, "repoUrl": args.repo_url, "caption": caption,
             "recallExample": json.loads((out / "benchmark.json").read_text()).get("recallExample")
             if (out / "benchmark.json").exists() else None,
             "bars": [{"label": f"{NAMES.get(k, k)} · follow-up tasks", "a": round(v["A"]["relatedPerSuccess"], 2),
                       "b": round(v["B"]["relatedPerSuccess"], 2), "unit": "M tokens/task"} for k, v in summary.items()],
             "summary": summary}
    (out / "benchmark.json").write_text(json.dumps(promo, indent=2))
    print(json.dumps({k: {"related": round(v["relatedDeltaPct"], 1), "all": round(v["allDeltaPct"], 1),
                          "usd": v["usdDeltaPct"] and round(v["usdDeltaPct"], 1),
                          "success": f"{v['B']['success']}/{v['B']['tasks']} vs {v['A']['success']}/{v['A']['tasks']}"}
                      for k, v in summary.items()}, indent=2))


if __name__ == "__main__":
    main()
