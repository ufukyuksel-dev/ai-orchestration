# promo-video

Renders the project's motion promo ([docs/assets/promo.mp4](../../docs/assets/promo.mp4) and the README GIF)
entirely from code. Every frame is a pure function of time, drawn with Pillow and encoded with ffmpeg.

```sh
python3 tools/promo-video/render.py                                   # writes docs/assets/promo.mp4 and promo.gif
python3 tools/promo-video/render.py --preview 9.0 17.5                # PNG frames at these seconds, for quick iteration
python3 tools/promo-video/render.py --preview 12 --out-dir /tmp/promo-preview
python3 tools/promo-video/render.py --benchmark path/to/other.json    # render against different numbers
```

Requirements: Python 3.9+, Pillow, `ffmpeg` on `PATH`. Fonts: Helvetica Neue / Menlo on macOS, DejaVu on Linux.

Scenes (32.5 s, 1920×1080, 30 fps): title ("Your coding agent forgets." / "AI Orchestration remembers.") → the
problem (every session re-explores the repo while the token counter climbs) → learn → recall (session 1 writes a
memory card and session 2 starts from it) → layered code graph with a rule attached to a method → benchmark bars →
one-command install with the `ai_orch doctor` checks → call to action.

## Data: `docs/assets/benchmark.json`

Every number in the video comes from this file. The renderer never makes numbers up.

| Field | Used in | Meaning |
|---|---|---|
| `placeholder` | every frame | `true` draws a pink **PLACEHOLDER DATA** badge on every frame, and a second one on the benchmark scene |
| `repoUrl` | install, CTA | e.g. `github.com/<owner>/ai-orchestration`; the clone command is built from it |
| `recallExample` | problem, learn → recall | `learnTokens`, `learnTurns`, `recallTokens`, `recallTurns` from one measured learn/recall pair |
| `bars` | benchmark | list of `{label, a, b, unit}`: `a` = without, `b` = with AI Orchestration; any length; the delta pill is computed from `a` and `b` |
| `caption` | benchmark | small source line at the bottom |
| `title`, `legend.a`, `legend.b` | benchmark | optional; defaults are "Fewer tokens on related tasks" / "without AI Orchestration" / "with AI Orchestration" |

The file shipped now is **placeholder data**. Before the video goes into the README or an ad, replace it with the
final benchmark results, set `"placeholder": false`, re-render, and check the frames.

## Output

- MP4: H.264, CRF 20, `yuv420p`, `+faststart`.
- GIF: 800 px wide, 12 fps, 96-colour palette (`--gif-width` / `--gif-fps` to change). It is about 4.3 MB; keep it
  at or below 8 MB for the README.

Edit the text constants (`SESSION_LINES`, `LEARN_LINES`, `RECALL_LINES`, `GRAPH_NODES`, `DOCTOR`) when the story
changes, and the `SCENES` table to retime scenes.
