#!/usr/bin/env python3
"""Render the AI Orchestration motion promo (MP4 + GIF) with Pillow and ffmpeg.

Every frame is drawn from a pure function of time, so the output is reproducible. All numbers
shown in the video (recall example, benchmark bars, repo URL) come from docs/assets/benchmark.json.

    python3 tools/promo-video/render.py                          # docs/assets/promo.mp4 + promo.gif
    python3 tools/promo-video/render.py --preview 9.0 17.5       # PNG frames at these seconds
    python3 tools/promo-video/render.py --preview 9 --out-dir /tmp/promo-preview

Requirements: Python 3.9+, Pillow, ffmpeg on PATH. Fonts: Helvetica Neue / Menlo on macOS,
DejaVu on Linux, otherwise Pillow's built-in font.
"""
from __future__ import annotations

import argparse
import json
import math
import random
import subprocess
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter, ImageFont

W, H = 1920, 1080
FPS = 30

BG = (8, 11, 24)
PANEL = (13, 17, 34)
PANEL_HI = (22, 28, 52)
TEXT = (236, 239, 246)
DIM = (128, 138, 160)
FAINT = (70, 80, 108)
CYAN = (34, 211, 238)
VIOLET = (167, 139, 250)
PINK = (244, 114, 182)
GREEN = (52, 211, 153)
AMBER = (251, 191, 36)
BLUE = (96, 165, 250)
RED = (248, 113, 113)

ROOT = Path(__file__).resolve().parents[2]
DEFAULT_BENCH = ROOT / "docs" / "assets" / "benchmark.json"

BENCH: dict = {}  # filled by load_benchmark() before any frame is drawn


# ---------------------------------------------------------------- benchmark data

def load_benchmark(path: Path) -> dict:
    data = json.loads(path.read_text(encoding="utf-8"))
    rec = data.get("recallExample") or {}
    for key in ("learnTokens", "learnTurns", "recallTokens", "recallTurns"):
        if not isinstance(rec.get(key), (int, float)):
            raise SystemExit(f"{path}: recallExample.{key} must be a number")
    bars = data.get("bars")
    if not isinstance(bars, list) or not bars:
        raise SystemExit(f"{path}: bars must be a non-empty list")
    for i, b in enumerate(bars):
        if not isinstance(b.get("label"), str) or not all(isinstance(b.get(k), (int, float)) for k in ("a", "b")):
            raise SystemExit(f"{path}: bars[{i}] needs label (str), a and b (numbers)")
    data.setdefault("repoUrl", "github.com/<owner>/ai-orchestration")
    data.setdefault("caption", "")
    data.setdefault("title", "Fewer tokens on related tasks")
    data.setdefault("legend", {})
    data["legend"].setdefault("a", "without AI Orchestration")
    data["legend"].setdefault("b", "with AI Orchestration")
    data["placeholder"] = bool(data.get("placeholder", False))
    return data


# ---------------------------------------------------------------- fonts

def _font(candidates: list[tuple[str, int]], size: int) -> ImageFont.FreeTypeFont:
    for path, index in candidates:
        if Path(path).exists():
            return ImageFont.truetype(path, size, index=index)
    return ImageFont.load_default(size)


_FONT_CACHE: dict = {}


def sans(size: int, weight: str = "bold") -> ImageFont.FreeTypeFont:
    key = ("sans", size, weight)
    if key not in _FONT_CACHE:
        mac = {"bold": 1, "regular": 0, "light": 7, "medium": 10, "thin": 12}[weight]
        linux = "DejaVuSans-Bold.ttf" if weight in ("bold", "medium") else "DejaVuSans.ttf"
        _FONT_CACHE[key] = _font([("/System/Library/Fonts/HelveticaNeue.ttc", mac),
                                  (f"/usr/share/fonts/truetype/dejavu/{linux}", 0)], size)
    return _FONT_CACHE[key]


def mono(size: int, bold: bool = False) -> ImageFont.FreeTypeFont:
    key = ("mono", size, bold)
    if key not in _FONT_CACHE:
        linux = "DejaVuSansMono-Bold.ttf" if bold else "DejaVuSansMono.ttf"
        _FONT_CACHE[key] = _font([("/System/Library/Fonts/Menlo.ttc", 1 if bold else 0),
                                  (f"/usr/share/fonts/truetype/dejavu/{linux}", 0)], size)
    return _FONT_CACHE[key]


# ---------------------------------------------------------------- easing / helpers

def clamp(x: float, lo: float = 0.0, hi: float = 1.0) -> float:
    return max(lo, min(hi, x))


def prog(t: float, start: float, dur: float) -> float:
    return clamp((t - start) / dur)


def ease_out(x: float) -> float:
    return 1 - (1 - x) ** 3


def ease_in_out(x: float) -> float:
    return 4 * x ** 3 if x < 0.5 else 1 - (-2 * x + 2) ** 3 / 2


def ease_back(x: float) -> float:
    c1, c3 = 1.70158, 2.70158
    return 1 + c3 * (x - 1) ** 3 + c1 * (x - 1) ** 2


def lerp(a: float, b: float, k: float) -> float:
    return a + (b - a) * k


def rgba(color: tuple[int, int, int], alpha: float) -> tuple[int, int, int, int]:
    return (*color, int(255 * clamp(alpha)))


def mix(bg: tuple[int, int, int], fg: tuple[int, int, int], a: float) -> tuple[int, int, int, int]:
    """Opaque colour of `fg` at opacity `a` over `bg` (text on panels without punching holes)."""
    a = clamp(a)
    return (*(int(b + (f - b) * a) for b, f in zip(bg, fg)), 255)


def fade_layer(layer: Image.Image, opacity: float) -> Image.Image:
    if opacity >= 1:
        return layer
    alpha = layer.getchannel("A").point(lambda v: int(v * clamp(opacity)))
    layer.putalpha(alpha)
    return layer


def text_center(draw: ImageDraw.ImageDraw, xy, text, font, fill) -> None:
    draw.text(xy, text, font=font, fill=fill, anchor="mm")


def draw_runs(draw: ImageDraw.ImageDraw, cx: float, y: float, runs, anchor_x: str = "center") -> float:
    """Draw [(text, font, fill), ...] on one baseline, centred on cx (or left-aligned). Returns width."""
    total = sum(draw.textlength(txt, font=f) for txt, f, _ in runs)
    x = cx - total / 2 if anchor_x == "center" else cx
    for txt, f, fill in runs:
        draw.text((x, y), txt, font=f, fill=fill, anchor="ls")
        x += draw.textlength(txt, font=f)
    return total


def fmt_int(v: float) -> str:
    return f"{int(round(v)):,}"


def scene_opacity(t: float, start: float, end: float, fade: float = 0.4) -> float:
    if t < start or t > end:
        return 0.0
    return min(ease_out(prog(t, start, fade)), ease_out(clamp((end - t) / fade)))


_GLOW_CACHE: dict = {}


def glow(color, radius: int) -> Image.Image:
    key = (color, radius)
    if key not in _GLOW_CACHE:
        size = radius * 2
        img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
        d = ImageDraw.Draw(img)
        d.ellipse((radius * 0.55, radius * 0.55, size - radius * 0.55, size - radius * 0.55),
                  fill=rgba(color, 0.9))
        _GLOW_CACHE[key] = img.filter(ImageFilter.GaussianBlur(radius * 0.28))
    return _GLOW_CACHE[key]


def paste_glow(canvas: Image.Image, center, color, radius: int, alpha: float) -> None:
    if alpha <= 0.004 or radius < 2:
        return
    g = glow(color, radius)
    if alpha < 1:
        g = fade_layer(g.copy(), alpha)
    canvas.alpha_composite(g, (int(center[0] - radius), int(center[1] - radius)))


def draw_window(layer: Image.Image, box, title: str, alpha: float, accent=CYAN, glow_alpha: float = 0.08):
    """Terminal-style window chrome. Returns the ImageDraw for further drawing."""
    x0, y0, x1, y1 = box
    d = ImageDraw.Draw(layer)
    paste_glow(layer, ((x0 + x1) / 2, (y0 + y1) / 2), accent, int(max(x1 - x0, y1 - y0) * 0.6),
               glow_alpha * alpha)
    d = ImageDraw.Draw(layer)
    d.rounded_rectangle(box, radius=20, fill=rgba(PANEL, 0.97 * alpha), outline=rgba((58, 70, 110), alpha), width=2)
    d.rounded_rectangle((x0, y0, x1, y0 + 52), radius=20, fill=rgba(PANEL_HI, alpha))
    d.rectangle((x0 + 2, y0 + 32, x1 - 2, y0 + 52), fill=rgba(PANEL_HI, alpha))
    for k, c in enumerate([(255, 95, 86), (255, 189, 46), (39, 201, 63)]):
        d.ellipse((x0 + 24 + k * 30, y0 + 18, x0 + 40 + k * 30, y0 + 34), fill=rgba(c, alpha))
    text_center(d, ((x0 + x1) / 2, y0 + 26), title, mono(20), rgba(DIM, alpha))
    return d


# ---------------------------------------------------------------- background

_rng = random.Random(11)
PARTICLES = [(_rng.uniform(0, W), _rng.uniform(0, H), _rng.uniform(-12, 12), _rng.uniform(-8, 8),
              _rng.uniform(1.2, 3.0), _rng.choice([CYAN, CYAN, VIOLET, BLUE, AMBER])) for _ in range(80)]


def make_base() -> Image.Image:
    base = Image.new("RGBA", (W, H), (*BG, 255))
    d = ImageDraw.Draw(base)
    for y in range(0, H, 4):  # vertical gradient
        k = y / H
        d.rectangle((0, y, W, y + 4), fill=(int(8 + 6 * k), int(11 + 5 * k), int(24 + 16 * k), 255))
    for x in range(0, W + 1, 60):
        for y in range(0, H + 1, 60):
            d.point((x, y), fill=(34, 44, 72, 255))
    vign = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    paste_glow(vign, (W * 0.18, H * 0.12), VIOLET, 700, 0.08)
    paste_glow(vign, (W * 0.86, H * 0.92), CYAN, 700, 0.08)
    base.alpha_composite(vign)
    return base


def draw_particles(canvas: Image.Image, t: float) -> None:
    d = ImageDraw.Draw(canvas)
    for x0, y0, vx, vy, r, color in PARTICLES:
        x = (x0 + vx * t) % W
        y = (y0 + vy * t) % H
        tw = 0.25 + 0.3 * math.sin(t * 1.7 + x0)
        d.ellipse((x - r, y - r, x + r, y + r), fill=rgba(color, tw))


# ---------------------------------------------------------------- scene 1: title

_title_rng = random.Random(3)
FORGET_JITTER = [_title_rng.uniform(0, 0.5) for _ in range(40)]


def scene_title(lt: float) -> Image.Image:
    layer = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    cy = H / 2 - 20

    # line 1: "Your coding agent forgets." -- the last word dissolves letter by letter
    p1 = ease_out(prog(lt, 0.15, 0.7))
    dim = ease_in_out(prog(lt, 1.4, 0.6))
    f1 = sans(76, "light")
    head, word = "Your coding agent ", "forgets."
    line_col = tuple(int(lerp(c1, c2, dim)) for c1, c2 in zip(TEXT, DIM))
    total = d.textlength(head + word, font=f1)
    x = W / 2 - total / 2
    y1 = cy - 150 + 24 * (1 - p1) - 20 * dim
    d.text((x, y1), head, font=f1, fill=rgba(line_col, p1), anchor="ls")
    x += d.textlength(head, font=f1)
    for i, ch in enumerate(word):
        k = prog(lt, 1.15 + FORGET_JITTER[i], 0.55)
        a = p1 * (1 - 0.85 * ease_in_out(k))
        d.text((x, y1 - 26 * ease_in_out(k)), ch, font=f1, fill=rgba(line_col, a), anchor="ls")
        x += d.textlength(ch, font=f1)

    # line 2: "AI Orchestration remembers."
    p2 = ease_out(prog(lt, 1.55, 0.8))
    f2 = sans(112)
    y2 = cy + 10 + 30 * (1 - p2)
    w2 = d.textlength("AI Orchestration remembers.", font=f2)
    wr = d.textlength("remembers.", font=f2)
    paste_glow(layer, (W / 2 + w2 / 2 - wr / 2, y2 - 40), CYAN, 380, 0.22 * p2)
    paste_glow(layer, (W / 2 - wr / 2, y2 - 40), VIOLET, 520, 0.10 * p2)
    d = ImageDraw.Draw(layer)
    draw_runs(d, W / 2, y2, [("AI Orchestration ", f2, rgba(TEXT, p2)), ("remembers.", f2, rgba(CYAN, p2))])

    # underline sweep under "remembers."
    sw = ease_in_out(prog(lt, 2.1, 0.6))
    if sw > 0:
        ux1 = W / 2 + w2 / 2
        ux0 = ux1 - wr
        d.rounded_rectangle((ux0, y2 + 22, ux0 + (ux1 - ux0) * sw, y2 + 28), radius=3, fill=rgba(CYAN, 0.9))

    p3 = ease_out(prog(lt, 2.35, 0.7))
    text_center(d, (W / 2, cy + 130 + 16 * (1 - p3)),
                "Project memory and rules for Claude Code  ·  Codex  ·  Copilot",
                sans(38, "regular"), rgba(DIM, p3))
    return layer


# ---------------------------------------------------------------- scene 2: the problem

SESSION_LINES = [
    ("tool", "Read   pom.xml"),
    ("tool", "Glob   src/**/*Tests.java"),
    ("tool", "Read   src/test/java/.../OwnerControllerTests.java"),
    ("tool", "Read   README.md"),
    ("tool", "Grep   \"IntegrationTests\"  src/test"),
    ("bash", "$ ./mvnw test"),
    ("err", "✗ MySqlIntegrationTests: Docker is not available"),
    ("tool", "Read   docker-compose.yml"),
    ("bash", "$ ./mvnw test -Dtest='!*IntegrationTests'"),
    ("ok", "BUILD SUCCESS"),
]
PROBLEM_LINES = []
for _s in range(1, 4):
    PROBLEM_LINES.append(("hdr", f"session {_s}  ·  fresh context, same repo"))
    PROBLEM_LINES.extend(SESSION_LINES)
PER_SESSION = len(SESSION_LINES) + 1
PROBLEM_RATE = 9.5  # lines per second


def scene_problem(lt: float) -> Image.Image:
    layer = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    p = ease_out(prog(lt, 0, 0.6))
    text_center(d, (W / 2, 105), "Every new session starts from zero.", sans(64), rgba(TEXT, p))
    text_center(d, (W / 2, 170), "The agent re-explores the same repo, and pays for it every time.",
                sans(32, "light"), rgba(DIM, p))

    # terminal with auto-scrolling exploration log
    wx0, wy0, wx1, wy1 = 130, 235 + 30 * (1 - p), 1170, 975 + 30 * (1 - p)
    draw_window(layer, (wx0, wy0, wx1, wy1), "claude  —  spring-petclinic", p, accent=PINK, glow_alpha=0.05)
    vx0, vy0, vw, vh = int(wx0 + 36), int(wy0 + 72), int(wx1 - wx0 - 60), int(wy1 - wy0 - 96)
    view = Image.new("RGBA", (vw, vh), (*PANEL, 0))
    vd = ImageDraw.Draw(view)
    lh = 46
    pos = max(0.0, (lt - 0.45) * PROBLEM_RATE)
    shown = min(len(PROBLEM_LINES), int(pos) + 1) if pos > 0 else 0
    scroll = max(0.0, min(pos, len(PROBLEM_LINES)) * lh - (vh - lh))
    f, fb = mono(25), mono(25, bold=True)
    for i in range(shown):
        kind, txt = PROBLEM_LINES[i]
        y = i * lh - scroll
        if y < -lh or y > vh:
            continue
        a = ease_out(clamp(pos - i))
        if kind == "hdr":
            vd.rounded_rectangle((0, y + 2, vw, y + lh - 6), radius=8, fill=mix(PANEL, PINK, 0.12 * a))
            vd.text((14, y + lh / 2 - 2), "▸ " + txt, font=fb, fill=mix(PANEL, PINK, a), anchor="lm")
            continue
        col = {"tool": DIM, "bash": TEXT, "err": RED, "ok": GREEN}[kind]
        vd.text((14, y + lh / 2 - 2), txt, font=fb if kind in ("bash", "ok") else f,
                fill=mix(PANEL, col, a), anchor="lm")
    # soft fade at the top edge of the viewport
    fade = Image.linear_gradient("L").resize((vw, 60)).point(lambda v: v)
    top = view.crop((0, 0, vw, 60))
    top.putalpha(Image.composite(top.getchannel("A"), Image.new("L", (vw, 60), 0), fade))
    view.paste(top, (0, 0))
    layer.alpha_composite(fade_layer(view, p), (vx0, vy0))

    # right: token counter + one bar per session (all the same length: repeated work)
    learn = BENCH["recallExample"]["learnTokens"]
    sessions_done = min(pos, len(PROBLEM_LINES)) / PER_SESSION
    rx = 1250
    pa = ease_out(prog(lt, 0.3, 0.6))
    d = ImageDraw.Draw(layer)
    d.text((rx, 300), "TOKENS SPENT", font=mono(24, bold=True), fill=rgba(DIM, pa), anchor="ls")
    count = learn * sessions_done
    d.text((rx, 400), fmt_int(count), font=sans(96), fill=rgba(TEXT, pa), anchor="ls")
    d.text((rx, 450), "and counting", font=sans(28, "light"), fill=rgba(DIM, pa), anchor="ls")
    bw = 540
    for s in range(3):
        fill_k = clamp(sessions_done - s)
        by = 540 + s * 120
        a = pa * (0.35 + 0.65 * (fill_k > 0))
        d.text((rx, by), f"session {s + 1}", font=mono(24), fill=rgba(DIM, a), anchor="ls")
        d.text((rx + bw, by), fmt_int(learn * fill_k), font=mono(24), fill=rgba(DIM, a), anchor="rs")
        d.rounded_rectangle((rx, by + 16, rx + bw, by + 44), radius=14, fill=rgba((28, 34, 58), pa))
        if fill_k > 0:
            d.rounded_rectangle((rx, by + 16, rx + max(28, bw * fill_k), by + 44), radius=14,
                                fill=rgba(PINK, 0.85 * pa))
    pr = ease_out(prog(lt, 3.0, 0.6))
    text_center(d, (rx + bw / 2, 905), "same files · same mistakes · same bill", sans(28, "medium"),
                rgba(PINK, pr))
    return layer


# ---------------------------------------------------------------- scene 3: learn -> recall

CARD_W, CARD_H = 620, 450
LEARN_LINES = [("tool", "Read   pom.xml"), ("tool", "Grep   IntegrationTests"), ("bash", "$ ./mvnw test"),
               ("err", "✗ needs Docker"), ("bash", "$ ./mvnw test -Dtest=…"), ("ok", "BUILD SUCCESS"),
               ("learn", "memory.learn  →  1 card")]
RECALL_LINES = [("recall", "card recalled"), ("dim", "  Running this project's tests"),
                ("bash", "$ ./mvnw test -Dtest=…"), ("ok", "BUILD SUCCESS")]


def draw_card(alpha: float, reveal: float) -> Image.Image:
    """The memory card as its own RGBA image (so it can be scaled while flying)."""
    card = Image.new("RGBA", (CARD_W + 80, CARD_H + 80), (0, 0, 0, 0))
    paste_glow(card, (card.width / 2, card.height / 2), CYAN, 380, 0.22 * alpha)
    d = ImageDraw.Draw(card)
    x0, y0 = 40, 40
    x1, y1 = x0 + CARD_W, y0 + CARD_H
    bg = (15, 24, 44)
    d.rounded_rectangle((x0, y0, x1, y1), radius=24, fill=rgba(bg, alpha), outline=rgba(CYAN, alpha), width=3)
    d.rounded_rectangle((x0, y0, x0 + 10, y1), radius=5, fill=rgba(CYAN, alpha))
    px = x0 + 40

    def a_of(k: int) -> float:
        return alpha * ease_out(clamp(reveal * 5 - k))

    d.text((px, y0 + 50), "◆ MEMORY CARD", font=mono(22, bold=True), fill=mix(bg, CYAN, a_of(0)), anchor="ls")
    d.text((x1 - 30, y0 + 50), "spring-petclinic", font=mono(20), fill=mix(bg, DIM, a_of(0)), anchor="rs")
    d.text((px, y0 + 108), "Running this project's tests", font=sans(40), fill=mix(bg, TEXT, a_of(1)),
           anchor="ls")
    d.text((px, y0 + 158), "COMMAND", font=mono(18, bold=True), fill=mix(bg, DIM, a_of(2)), anchor="ls")
    cb = (px, y0 + 172, x1 - 30, y0 + 220)
    d.rounded_rectangle(cb, radius=10, fill=mix(bg, (4, 8, 18), a_of(2)))
    d.text((px + 16, y0 + 196), "./mvnw test -Dtest='!…IntegrationTests'", font=mono(22),
           fill=mix(bg, GREEN, a_of(2)), anchor="lm")
    d.text((px, y0 + 268), "FILES", font=mono(18, bold=True), fill=mix(bg, DIM, a_of(3)), anchor="ls")
    d.text((px, y0 + 302), "pom.xml  ·  OwnerControllerTests.java", font=sans(26, "regular"),
           fill=mix(bg, TEXT, a_of(3)), anchor="ls")
    d.text((px, y0 + 352), "PITFALL", font=mono(18, bold=True), fill=mix(bg, AMBER, a_of(4)), anchor="ls")
    d.text((px, y0 + 388), "Integration tests need Docker — exclude them", font=sans(26, "medium"),
           fill=mix(bg, AMBER, a_of(4)), anchor="ls")
    d.text((px, y0 + 420), "locally; the filter above does exactly that.", font=sans(24, "regular"),
           fill=mix(bg, DIM, a_of(4)), anchor="ls")
    return card


def draw_log(d, x, y, lines, lt, start, step, bg=PANEL, size=24):
    f, fb = mono(size), mono(size, bold=True)
    for i, (kind, txt) in enumerate(lines):
        a = ease_out(prog(lt, start + i * step, 0.25))
        if a <= 0:
            break
        col = {"tool": DIM, "bash": TEXT, "err": RED, "ok": GREEN, "learn": CYAN, "recall": CYAN,
               "dim": DIM}[kind]
        prefix = {"ok": "✓ ", "learn": "◆ ", "recall": "◆ "}.get(kind, "")
        d.text((x, y + i * 44), prefix + txt, font=fb if kind in ("bash", "ok", "learn", "recall") else f,
               fill=mix(bg, col, a), anchor="lm")


def counter_block(d, cx, y, title, tokens, turns, color, a):
    text_center(d, (cx, y), title, mono(22, bold=True), rgba(color, a))
    text_center(d, (cx, y + 70), fmt_int(tokens), sans(80), rgba(TEXT, a))
    text_center(d, (cx, y + 130), f"tokens  ·  {int(round(turns))} turn{'' if int(round(turns)) == 1 else 's'}", sans(28, "regular"), rgba(DIM, a))


def scene_recall(lt: float) -> Image.Image:
    layer = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    rec = BENCH["recallExample"]
    p = ease_out(prog(lt, 0, 0.6))
    text_center(d, (W / 2, 95), "Learn once. Start from the card next time.", sans(62), rgba(TEXT, p))

    L = (80, 200, 590, 600)
    R = (1330, 200, 1840, 600)
    # session 1 (learn)
    pl = ease_out(prog(lt, 0.15, 0.5))
    if pl > 0:
        d = draw_window(layer, L, "session 1 · learn", pl, accent=VIOLET, glow_alpha=0.06)
        draw_log(d, L[0] + 34, L[1] + 100, LEARN_LINES, lt, 0.45, 0.27)
    # counters
    k1 = ease_out(prog(lt, 0.45, 1.95))
    if pl > 0:
        counter_block(d, (L[0] + L[2]) / 2, 700, "SESSION 1 · LEARN", rec["learnTokens"] * k1,
                      max(1, rec["learnTurns"] * k1), VIOLET, pl)

    # the card flies from session 1 into the centre
    fly = ease_in_out(prog(lt, 2.35, 0.8))
    if fly > 0:
        reveal = prog(lt, 2.7, 0.9)
        card = draw_card(1.0, reveal)
        s = lerp(0.3, 1.0, fly)
        cw, ch = int(card.width * s), int(card.height * s)
        card = card.resize((cw, ch), Image.LANCZOS)
        sx, sy = (L[0] + L[2]) / 2, L[3] - 60
        ex, ey = W / 2, 420
        cx, cy = lerp(sx, ex, fly), lerp(sy, ey, fly) - 60 * math.sin(math.pi * fly)
        layer.alpha_composite(fade_layer(card, min(1.0, fly * 2.5)), (int(cx - cw / 2), int(cy - ch / 2)))

    # beam from card to session 2
    beam = ease_in_out(prog(lt, 3.55, 0.45))
    d = ImageDraw.Draw(layer)
    if beam > 0:
        bx0, bx1, by = W / 2 + CARD_W / 2 + 4, R[0] - 6, 420
        ex = lerp(bx0, bx1, beam)
        d.line((bx0, by, ex, by), fill=rgba(CYAN, 0.8), width=4)
        paste_glow(layer, (ex, by), CYAN, 40, 0.9 * (1 - 0.5 * prog(lt, 4.0, 0.4)))
        d = ImageDraw.Draw(layer)
    pr = ease_out(prog(lt, 3.8, 0.5))
    if pr > 0:
        d = draw_window(layer, R, "session 2 · recall", pr, accent=CYAN, glow_alpha=0.10)
        draw_log(d, R[0] + 34, R[1] + 100, RECALL_LINES, lt, 4.0, 0.3)
        k2 = ease_out(prog(lt, 4.0, 1.1))
        counter_block(d, (R[0] + R[2]) / 2, 700, "SESSION 2 · RECALL", rec["recallTokens"] * k2,
                      max(1, rec["recallTurns"] * k2), CYAN, pr)
        done = ease_out(prog(lt, 5.1, 0.4))
        text_center(d, ((R[0] + R[2]) / 2, R[1] + 100 + 4 * 44 + 20),
                    f"done in {int(rec['recallTurns'])} turns", sans(26, "medium"), mix(PANEL, GREEN, done))

    # result
    pz = ease_back(prog(lt, 5.25, 0.6))
    if pz > 0:
        saved = 1 - rec["recallTokens"] / rec["learnTokens"]
        a = clamp(pz)
        text_center(d, (W / 2, 790), f"−{saved * 100:.0f}%", sans(int(120 * max(pz, 0.2))), rgba(GREEN, a))
        text_center(d, (W / 2, 870), f"tokens on the repeat task  ·  {int(rec['learnTurns'])} turns down to "
                                     f"{int(rec['recallTurns'])}", sans(28, "regular"), rgba(DIM, a))
    pn = ease_out(prog(lt, 5.6, 0.5))
    text_center(d, (W / 2, 1000), "measured example: same repository, same task, a fresh session",
                sans(24, "light"), rgba(DIM, pn * 0.9))
    return layer


# ---------------------------------------------------------------- scene 4: layered code graph

PLANES = [("PROJECT", VIOLET), ("PACKAGES", BLUE), ("CLASSES", CYAN), ("METHODS", GREEN)]
PW, PD, SK = 1180, 150, 260
GRAPH_CX = W / 2 + 130
# (plane, u, v, parent index or None, label)
_g = random.Random(5)
GRAPH_NODES: list[tuple[int, float, float, int | None, str]] = [(0, 0.50, 0.5, None, "petclinic")]
_pkgs = [(0.18, 0.35, "vet"), (0.38, 0.65, "visit"), (0.62, 0.45, "owner"), (0.84, 0.6, "system")]
for u, v, name in _pkgs:
    GRAPH_NODES.append((1, u, v, 0, name))
_cls = [(0.10, 0.3, 1, "Vet"), (0.24, 0.7, 1, "Specialty"), (0.38, 0.35, 2, "Visit"), (0.54, 0.75, 3, "Pet"),
        (0.66, 0.30, 3, "Owner"), (0.84, 0.72, 3, "OwnerController"), (0.94, 0.3, 4, "CacheConfig")]
for u, v, par, name in _cls:
    GRAPH_NODES.append((2, u, v, par, name))
_cls_idx = {name: 5 + i for i, (_, _, _, name) in enumerate(_cls)}
_meth = [(0.06, 0.4, "Vet"), (0.15, 0.75, "Vet"), (0.26, 0.3, "Specialty"), (0.35, 0.7, "Visit"),
         (0.45, 0.35, "Pet"), (0.52, 0.8, "Pet"), (0.61, 0.62, "Owner"), (0.70, 0.25, "Owner"),
         (0.79, 0.72, "OwnerController"), (0.87, 0.3, "OwnerController"), (0.95, 0.65, "CacheConfig")]
for u, v, par in _meth:
    GRAPH_NODES.append((3, u, v, _cls_idx[par], ""))
PATH = [0, 3, _cls_idx["Owner"], 5 + len(_cls) + 6]  # petclinic -> owner -> Owner -> setTelephone()
GRAPH_LABELS = {PATH[3]: "setTelephone()"}


def plane_y(k: int, spread: float) -> float:
    return 585 + (k - 1.5) * 190 * spread


def plane_pt(k: int, u: float, v: float, spread: float) -> tuple[float, float]:
    return (GRAPH_CX + (u - 0.5) * PW + (0.5 - v) * SK, plane_y(k, spread) + (v - 0.5) * PD)


def scene_graph(lt: float) -> Image.Image:
    layer = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    p = ease_out(prog(lt, 0, 0.6))
    text_center(d, (W / 2, 90), "Rules live on the code graph.", sans(62), rgba(TEXT, p))
    text_center(d, (W / 2, 150), "Attach a rule to a class or method in the local panel.",
                sans(30, "light"), rgba(DIM, p))

    spread = ease_out(prog(lt, 0.1, 1.1))
    # planes (each on its own layer so translucent planes blend)
    for k, (name, color) in enumerate(PLANES):
        a = ease_out(prog(lt, 0.1 + k * 0.15, 0.6))
        if a <= 0:
            continue
        pl = Image.new("RGBA", (W, H), (0, 0, 0, 0))
        pd = ImageDraw.Draw(pl)
        corners = [plane_pt(k, 0, 0, spread), plane_pt(k, 1, 0, spread), plane_pt(k, 1, 1, spread),
                   plane_pt(k, 0, 1, spread)]
        pd.polygon(corners, fill=rgba(color, 0.07 * a))
        for g in range(1, 8):
            u = g / 8
            pd.line((plane_pt(k, u, 0, spread), plane_pt(k, u, 1, spread)), fill=rgba(color, 0.08 * a), width=1)
        pd.line((plane_pt(k, 0, 0.5, spread), plane_pt(k, 1, 0.5, spread)), fill=rgba(color, 0.06 * a), width=1)
        pd.line(corners + [corners[0]], fill=rgba(color, 0.55 * a), width=2)
        layer.alpha_composite(pl)
        d = ImageDraw.Draw(layer)
        lx, ly = plane_pt(k, 0, 1, spread)
        d.text((lx - 40, ly - PD / 2 + 4), name, font=mono(24, bold=True), fill=rgba(color, a), anchor="rm")

    # edges parent -> child
    for i, (k, u, v, par, _) in enumerate(GRAPH_NODES):
        if par is None:
            continue
        a = ease_out(prog(lt, 0.9 + k * 0.2, 0.5))
        if a <= 0:
            continue
        pk, pu, pv = GRAPH_NODES[par][:3]
        d.line((plane_pt(pk, pu, pv, spread), plane_pt(k, u, v, spread)),
               fill=rgba(PLANES[k][1], 0.18 * a), width=2)

    # glowing path root -> method
    for s in range(len(PATH) - 1):
        sp = ease_in_out(prog(lt, 2.0 + s * 0.35, 0.4))
        if sp <= 0:
            break
        a0 = plane_pt(*[GRAPH_NODES[PATH[s]][j] for j in (0, 1, 2)], spread)
        b0 = plane_pt(*[GRAPH_NODES[PATH[s + 1]][j] for j in (0, 1, 2)], spread)
        e = (lerp(a0[0], b0[0], sp), lerp(a0[1], b0[1], sp))
        d.line((a0, e), fill=rgba(CYAN, 0.35), width=12)
        d.line((a0, e), fill=rgba(CYAN, 0.95), width=4)
        if sp < 1:
            paste_glow(layer, e, CYAN, 36, 0.9)
            d = ImageDraw.Draw(layer)

    # nodes
    on_path = set(PATH)
    for i, (k, u, v, par, label) in enumerate(GRAPH_NODES):
        appear = prog(lt, 0.7 + k * 0.2 + (i % 5) * 0.04, 0.45)
        if appear <= 0:
            continue
        x, y = plane_pt(k, u, v, spread)
        color = PLANES[k][1]
        lit = i in on_path and lt >= 2.0 + max(0, PATH.index(i) - 1) * 0.35 + (0.4 if i != PATH[0] else 0)
        s = ease_back(appear)
        r = (13 if lit else 8) * s
        paste_glow(layer, (x, y), CYAN if lit else color, 44 if lit else 26, (0.8 if lit else 0.35) * clamp(appear))
        d = ImageDraw.Draw(layer)
        node_col = AMBER if (i == PATH[3] and lt >= 3.55) else (CYAN if lit else color)
        d.ellipse((x - r, y - r, x + r, y + r), fill=rgba(node_col, clamp(appear)),
                  outline=rgba(TEXT, 0.9 if lit else 0), width=2)
        name = GRAPH_LABELS.get(i) or (label if k < 3 else "")
        if name and appear > 0.5:
            if k == 3:  # method label sits left of the node, clear of the path and the rule badge
                d.text((x - 24, y + 2), name, font=sans(26, "medium"), fill=rgba(TEXT, ease_out(prog(lt, 3.0, 0.4))),
                       anchor="rm")
                continue
            if lit:
                fcol, font = TEXT, sans(26, "medium")
            elif i in on_path or k < 2:
                fcol, font = DIM, sans(22, "regular")
            else:
                fcol, font = FAINT, sans(20, "regular")
            d.text((x, y - 18), name, font=font, fill=rgba(fcol, clamp(appear * 2 - 1)), anchor="ms")

    # amber rule badge attaches to the method
    ba = prog(lt, 3.55, 0.6)
    if ba > 0:
        mk, mu, mv = GRAPH_NODES[PATH[3]][:3]
        mx, my = plane_pt(mk, mu, mv, spread)
        e = ease_back(ba)
        bx0, by1 = mx + 90, my - 36
        bw, bh = 470, 96
        bx0 += 40 * (1 - e)
        pulse = 0.5 + 0.5 * math.sin((lt - 3.55) * 4)
        paste_glow(layer, (mx, my), AMBER, 60, 0.55 * clamp(ba) * (0.6 + 0.4 * pulse))
        paste_glow(layer, (bx0 + bw / 2, by1 - bh / 2), AMBER, 300, 0.18 * clamp(ba))
        d = ImageDraw.Draw(layer)
        a = clamp(ba * 1.6)
        d.line((mx + 14, my - 6, bx0, by1 - bh / 2), fill=rgba(AMBER, a), width=3)
        d.ellipse((mx - 18, my - 18, mx + 18, my + 18), outline=rgba(AMBER, a), width=3)
        bg = (40, 30, 10)
        d.rounded_rectangle((bx0, by1 - bh, bx0 + bw, by1), radius=18, fill=rgba((30, 24, 12), a),
                            outline=rgba(AMBER, a), width=3)
        d.text((bx0 + 24, by1 - bh + 32), "RULE  ·  Owner.setTelephone()", font=mono(18, bold=True),
               fill=mix(bg, AMBER, a * 0.9), anchor="lm")
        d.text((bx0 + 24, by1 - 30), "Never log personal data here", font=sans(30), fill=mix(bg, TEXT, a),
               anchor="lm")

    pe = ease_out(prog(lt, 4.3, 0.6))
    if pe > 0:
        draw_runs(d, W / 2, 1022 + 12 * (1 - pe), [
            ("The agent sees it before editing ", sans(40, "light"), rgba(TEXT, pe)),
            ("Owner.java", mono(40, bold=True), rgba(AMBER, pe))])
    return layer


# ---------------------------------------------------------------- scene 5: benchmark

def _decimals(v: float) -> int:
    s = repr(float(v))
    return min(3, len(s.split(".")[1].rstrip("0"))) if "." in s else 0


def placeholder_badge(d: ImageDraw.ImageDraw, x1: float, y0: float, alpha: float, size: int = 22) -> None:
    txt = "PLACEHOLDER DATA"
    f = mono(size, bold=True)
    w = d.textlength(txt, font=f) + 32
    d.rounded_rectangle((x1 - w, y0, x1, y0 + size + 22), radius=(size + 22) // 2,
                        fill=rgba((60, 14, 30), 0.9 * alpha), outline=rgba(PINK, alpha), width=2)
    text_center(d, (x1 - w / 2, y0 + (size + 22) / 2), txt, f, rgba(PINK, alpha))


def scene_bench(lt: float) -> Image.Image:
    layer = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    p = ease_out(prog(lt, 0, 0.6))
    text_center(d, (W / 2, 100), BENCH["title"], sans(62), rgba(TEXT, p))

    # legend
    lf = sans(28, "regular")
    la, lb = BENCH["legend"]["a"], BENCH["legend"]["b"]
    wa, wb = d.textlength(la, font=lf), d.textlength(lb, font=lf)
    lx = W / 2 - (wa + wb + 36 * 2 + 60) / 2
    d.rounded_rectangle((lx, 180, lx + 24, 204), radius=6, fill=rgba((98, 108, 138), p))
    d.text((lx + 36, 192), la, font=lf, fill=rgba(DIM, p), anchor="lm")
    lx += 36 + wa + 60
    d.rounded_rectangle((lx, 180, lx + 24, 204), radius=6, fill=rgba(CYAN, p))
    d.text((lx + 36, 192), lb, font=lf, fill=rgba(TEXT, p), anchor="lm")

    bars = BENCH["bars"]
    n = len(bars)
    top, bottom = 270, 930
    row_h = min(300, (bottom - top) / n)
    y = top + ((bottom - top) - row_h * n) / 2
    vmax = max(max(b["a"], b["b"]) for b in bars) or 1
    x0, maxw = 240, 960
    bh = min(56, row_h * 0.2)
    for i, b in enumerate(bars):
        a = ease_out(prog(lt, 0.35 + i * 0.25, 0.5))
        if a <= 0:
            continue
        ry = y + i * row_h
        d.text((x0, ry + 38), b["label"], font=sans(36, "medium"), fill=rgba(TEXT, a), anchor="ls")
        unit = b.get("unit", "")
        for j, (key, color, vcol) in enumerate([("a", (98, 108, 138), DIM), ("b", CYAN, TEXT)]):
            g = ease_in_out(prog(lt, 0.6 + i * 0.25 + j * 0.15, 1.1))
            val = b[key]
            by = ry + 64 + j * (bh + 14)
            wv = max(bh, maxw * val / vmax * g)
            d.rounded_rectangle((x0, by, x0 + maxw, by + bh), radius=bh / 2, fill=rgba((24, 30, 54), a))
            if g > 0:
                if key == "b":
                    paste_glow(layer, (x0 + wv, by + bh / 2), CYAN, 60, 0.5 * g)
                    d = ImageDraw.Draw(layer)
                d.rounded_rectangle((x0, by, x0 + wv, by + bh), radius=bh / 2, fill=rgba(color, a))
            dec = max(_decimals(b["a"]), _decimals(b["b"]))
            d.text((x0 + maxw + 30, by + bh / 2), f"{val * g:.{dec}f} {unit}".rstrip(),
                   font=mono(28, bold=(key == "b")), fill=rgba(vcol, a), anchor="lm")
        dp = ease_back(prog(lt, 1.9 + i * 0.25, 0.5))
        if dp > 0 and b["a"]:
            delta = (b["b"] - b["a"]) / b["a"] * 100
            good = delta <= 0
            col = GREEN if good else RED
            txt = f"{'−' if delta < 0 else '+'}{abs(delta):.0f}%"
            cx, cy = 1740, ry + 64 + bh + 7
            pw = 150 * max(dp, 0.1)
            d.rounded_rectangle((cx - pw / 2, cy - 34, cx + pw / 2, cy + 34), radius=34,
                                fill=rgba((12, 40, 34) if good else (50, 16, 20), clamp(dp)),
                                outline=rgba(col, clamp(dp)), width=3)
            text_center(d, (cx, cy), txt, sans(int(36 * max(dp, 0.3))), rgba(col, clamp(dp)))

    pc = ease_out(prog(lt, 1.2, 0.6))
    if BENCH["caption"]:
        # a caption wider than the frame goes on two lines, split at the separator nearest the middle
        font, caption = sans(24, "regular"), BENCH["caption"]
        lines = [caption]
        if d.textlength(caption, font=font) > W - 160 and " · " in caption:
            parts = caption.split(" · ")
            k = min(range(1, len(parts)), key=lambda i: abs(len(" · ".join(parts[:i])) - len(caption) / 2))
            lines = [" · ".join(parts[:k]), " · ".join(parts[k:])]
        for i, line in enumerate(lines):
            text_center(d, (W / 2, 1010 - 17 * (len(lines) - 1) + 34 * i), line, font, rgba(DIM, pc))
    if BENCH["placeholder"]:
        f = mono(24, bold=True)
        bw = d.textlength("PLACEHOLDER DATA", font=f) + 32
        placeholder_badge(d, W / 2 + bw / 2, 925, pc, size=24)
    return layer


# ---------------------------------------------------------------- scene 6: install

DOCTOR = [  # mirrors the checks printed by `ai_orch doctor` (scripts/ai_orch_admin.py)
    ("server", "running"), ("vector store (Qdrant)", "ready"), ("embedding model (Ollama bge-m3)", "ready"),
    ("MCP endpoint", "reachable"), ("panel", "ready"), ("Claude Code registration", "registered"),
    ("Codex registration", "registered")]


def scene_install(lt: float) -> Image.Image:
    layer = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    p = ease_out(prog(lt, 0, 0.6))
    text_center(d, (W / 2, 95), "One command. Everything local.", sans(64), rgba(TEXT, p))
    text_center(d, (W / 2, 160), "Postgres  ·  Qdrant  ·  Ollama bge-m3 embeddings  —  on your machine",
                sans(30, "light"), rgba(DIM, p))
    box = (250, 225 + 30 * (1 - p), W - 250, 985 + 30 * (1 - p))
    d = draw_window(layer, box, "zsh  —  ~/code", p, accent=CYAN, glow_alpha=0.09)
    x, y = box[0] + 50, box[1] + 105
    f, fb = mono(28), mono(28, bold=True)
    url = BENCH["repoUrl"]
    url = url if url.startswith("http") else "https://" + url
    repo = url.rstrip("/").split("/")[-1]
    typed = [("$ ", f"git clone {url} \\"), ("  ", f"  && cd {repo} && ./install.sh")]
    cur = 0.45
    speed = 0.016
    for prefix, txt in typed:
        if lt < cur:
            break
        dur = len(txt) * speed
        n = int(len(txt) * clamp((lt - cur) / dur))
        d.text((x, y), prefix, font=fb, fill=GREEN, anchor="lm")
        px = x + d.textlength("$ ", font=fb)
        d.text((px, y), txt[:n], font=f, fill=TEXT, anchor="lm")
        if n < len(txt):
            cx = px + d.textlength(txt[:n], font=f)
            d.rectangle((cx + 2, y - 17, cx + 18, y + 17), fill=CYAN)
        cur += dur + 0.12
        y += 52
    # setup progress line
    t_setup = cur + 0.15
    y += 22
    sa = ease_out(prog(lt, t_setup, 0.25))
    if sa > 0:
        d.text((x, y), "◆ ", font=fb, fill=mix(PANEL, AMBER, sa), anchor="lm")
        d.text((x + d.textlength("◆ ", font=fb), y), "setting up Postgres · Qdrant · Ollama (bge-m3)", font=f,
               fill=mix(PANEL, DIM, sa), anchor="lm")
        pk = ease_in_out(prog(lt, t_setup + 0.1, 0.5))
        bx = box[2] - 330
        d.rounded_rectangle((bx, y - 9, bx + 260, y + 9), radius=9, fill=mix(PANEL, (34, 42, 70), sa))
        d.rounded_rectangle((bx, y - 9, bx + max(18, 260 * pk), y + 9), radius=9, fill=mix(PANEL, AMBER, sa))
    y += 70
    t_doc = t_setup + 0.7
    da = ease_out(prog(lt, t_doc, 0.25))
    if da > 0:
        d.text((x, y), "AI Orchestration doctor", font=fb, fill=mix(PANEL, VIOLET, da), anchor="lm")
    y += 56
    for i, (name, status) in enumerate(DOCTOR):
        a = ease_out(prog(lt, t_doc + 0.2 + i * 0.13, 0.2))
        if a <= 0:
            break
        d.text((x + 20, y), "✓", font=fb, fill=mix(PANEL, GREEN, a), anchor="lm")
        d.text((x + 70, y), name, font=f, fill=mix(PANEL, TEXT, a), anchor="lm")
        d.text((x + 640, y), status, font=f, fill=mix(PANEL, DIM, a), anchor="lm")
        y += 46
    ra = ease_out(prog(lt, t_doc + 0.3 + len(DOCTOR) * 0.13, 0.3))
    if ra > 0:
        d.text((box[2] - 50, box[3] - 50), "all good", font=fb, fill=mix(PANEL, GREEN, ra), anchor="rm")
    return layer


# ---------------------------------------------------------------- scene 7: call to action

def draw_logo(layer: Image.Image, cx: float, cy: float, s: float, lt: float) -> None:
    """Three stacked translucent planes with a glowing node path: the product mark."""
    colors = [VIOLET, CYAN, GREEN]
    pw, pd, sk = 260 * s, 64 * s, 70 * s
    for k, color in enumerate(colors):
        yk = cy + (k - 1) * 62 * s
        pts = [(cx - pw / 2 + sk / 2, yk - pd / 2), (cx + pw / 2 + sk / 2, yk - pd / 2),
               (cx + pw / 2 - sk / 2, yk + pd / 2), (cx - pw / 2 - sk / 2, yk + pd / 2)]
        pl = Image.new("RGBA", layer.size, (0, 0, 0, 0))
        pdr = ImageDraw.Draw(pl)
        pdr.polygon(pts, fill=rgba(color, 0.14))
        pdr.line(pts + [pts[0]], fill=rgba(color, 0.85), width=3)
        layer.alpha_composite(pl)
    d = ImageDraw.Draw(layer)
    nodes = [(cx - 10 * s, cy - 62 * s), (cx + 30 * s, cy), (cx + 5 * s, cy + 62 * s)]
    d.line(nodes, fill=rgba(CYAN, 0.95), width=max(2, int(4 * s)))
    for i, (nx, ny) in enumerate(nodes):
        pulse = 1 + 0.15 * math.sin(lt * 4 + i)
        paste_glow(layer, (nx, ny), AMBER if i == 2 else CYAN, int(34 * s), 0.8)
        d = ImageDraw.Draw(layer)
        r = 9 * s * pulse
        d.ellipse((nx - r, ny - r, nx + r, ny + r), fill=AMBER if i == 2 else CYAN)


def scene_cta(lt: float) -> Image.Image:
    layer = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    s = ease_back(prog(lt, 0, 0.8))
    paste_glow(layer, (W / 2, 250), CYAN, 360, 0.25 * clamp(s))
    if s > 0.05:
        draw_logo(layer, W / 2, 250, s, lt)
    d = ImageDraw.Draw(layer)
    p = ease_out(prog(lt, 0.35, 0.7))
    text_center(d, (W / 2, 470 + 20 * (1 - p)), "AI Orchestration", sans(110), rgba(TEXT, p))
    p = ease_out(prog(lt, 0.7, 0.7))
    draw_runs(d, W / 2, 590, [("Works with  ", sans(40, "light"), rgba(DIM, p)),
                              ("Claude Code", sans(40, "medium"), rgba(VIOLET, p)),
                              ("  ·  ", sans(40, "light"), rgba(DIM, p)),
                              ("Codex", sans(40, "medium"), rgba(CYAN, p)),
                              ("  ·  ", sans(40, "light"), rgba(DIM, p)),
                              ("GitHub Copilot", sans(40, "medium"), rgba(BLUE, p))])
    p = ease_out(prog(lt, 1.0, 0.7))
    badges = [("Local-first", GREEN), ("MIT", AMBER)]
    bf = sans(32, "medium")
    widths = [d.textlength(b, font=bf) + 64 for b, _ in badges]
    bx = (W - (sum(widths) + 26 * (len(badges) - 1))) / 2
    for (label, color), bw in zip(badges, widths):
        d.rounded_rectangle((bx, 648, bx + bw, 710), radius=31, fill=rgba((18, 22, 44), p),
                            outline=rgba(color, p), width=3)
        text_center(d, (bx + bw / 2, 679), label, bf, rgba(color, p))
        bx += bw + 26
    p = ease_out(prog(lt, 1.35, 0.7))
    url = BENCH["repoUrl"]
    uf = mono(44, bold=True)
    uw = d.textlength(url, font=uf) + 90
    paste_glow(layer, (W / 2, 820), CYAN, 420, 0.12 * p)
    d = ImageDraw.Draw(layer)
    d.rounded_rectangle((W / 2 - uw / 2, 780, W / 2 + uw / 2, 862), radius=20, fill=rgba((12, 20, 38), p),
                        outline=rgba(CYAN, p), width=3)
    text_center(d, (W / 2, 821), url, uf, rgba(TEXT, p))
    text_center(d, (W / 2, 925), "Your coding agent, with a memory.", sans(32, "light"), rgba(DIM, p))
    return layer


# ---------------------------------------------------------------- timeline

SCENES = [  # (start, end, renderer taking local time)
    (0.0, 3.6, scene_title),
    (3.4, 8.0, scene_problem),
    (7.8, 14.3, scene_recall),
    (14.1, 19.7, scene_graph),
    (19.5, 23.6, scene_bench),
    (23.4, 28.4, scene_install),
    (28.2, 32.5, scene_cta),
]
DURATION = 32.5


def render_frame(t: float, base: Image.Image) -> Image.Image:
    canvas = base.copy()
    draw_particles(canvas, t)
    for start, end, fn in SCENES:
        o = scene_opacity(t, start, end)
        if o > 0:
            canvas.alpha_composite(fade_layer(fn(t - start), o))
    if BENCH.get("placeholder"):
        placeholder_badge(ImageDraw.Draw(canvas), W - 30, 26, 0.85, size=18)
    fade_in = ease_out(prog(t, 0, 0.4))
    if fade_in < 1:
        canvas = Image.blend(Image.new("RGBA", (W, H), (*BG, 255)), canvas, fade_in)
    return canvas.convert("RGB")


# ---------------------------------------------------------------- output

def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--out-dir", type=Path, default=ROOT / "docs" / "assets")
    ap.add_argument("--benchmark", type=Path, default=DEFAULT_BENCH, help="benchmark JSON (default: %(default)s)")
    ap.add_argument("--preview", type=float, nargs="+", help="write PNG frames at these seconds and exit")
    ap.add_argument("--gif-width", type=int, default=800)
    ap.add_argument("--gif-fps", type=int, default=12)
    args = ap.parse_args()
    BENCH.update(load_benchmark(args.benchmark))
    if BENCH["placeholder"]:
        print("warning: benchmark.json is marked placeholder; frames carry a PLACEHOLDER DATA badge",
              file=sys.stderr)
    args.out_dir.mkdir(parents=True, exist_ok=True)
    base = make_base()

    if args.preview is not None:
        for sec in args.preview:
            out = args.out_dir / f"preview-{sec:05.1f}.png"
            render_frame(sec, base).save(out)
            print(out)
        return 0

    mp4 = args.out_dir / "promo.mp4"
    gif = args.out_dir / "promo.gif"
    ff = subprocess.Popen(
        ["ffmpeg", "-y", "-loglevel", "error", "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", f"{W}x{H}",
         "-r", str(FPS), "-i", "-", "-c:v", "libx264", "-preset", "slow", "-crf", "20",
         "-pix_fmt", "yuv420p", "-movflags", "+faststart", str(mp4)],
        stdin=subprocess.PIPE)
    total = int(DURATION * FPS)
    for i in range(total):
        ff.stdin.write(render_frame(i / FPS, base).tobytes())
        if i % FPS == 0:
            print(f"\rframe {i}/{total}", end="", file=sys.stderr, flush=True)
    ff.stdin.close()
    if ff.wait() != 0:
        print("\nffmpeg failed", file=sys.stderr)
        return 1
    print(f"\n{mp4}", file=sys.stderr)

    vf = (f"fps={args.gif_fps},scale={args.gif_width}:-1:flags=lanczos,split[a][b];"
          "[a]palettegen=max_colors=96:stats_mode=diff[p];[b][p]paletteuse=dither=bayer:bayer_scale=4")
    subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", str(mp4), "-vf", vf, str(gif)], check=True)
    print(gif, file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
