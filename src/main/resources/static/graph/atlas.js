// The code atlas: a calm, precisely drawn radial map on a 2D canvas. Packages are arcs on the inner orbit, open nodes
// fan their children out on the next orbit, and real code dependencies run through the middle as hierarchically
// bundled curves (Holten 2006). One accent colour; direction is shown with arrowheads, memories and rules with fixed
// shapes. Nothing moves on its own. Same interface as the former scenes, so view.js drives it unchanged.

export const COLORS = {
  bg: "#11161d", text: "#e5eaf0", soft: "#a8b3c2", faint: "#6b7788", line: "#8a96a8",
  accent: "#5bc0be", memory: "#5bc0be", rule: "#d6a85c",
};
const BETA = 0.85;
const MOVE_MS = 260;

const hex = (h) => { const n = parseInt(h.slice(1), 16); return [(n >> 16) & 255, (n >> 8) & 255, n & 255]; };
const rgba = (h, a) => { const [r, g, b] = hex(h); return `rgba(${r},${g},${b},${a})`; };
const ease = (t) => 1 - Math.pow(1 - t, 3);
const TAU = Math.PI * 2;
const norm = (a) => Math.atan2(Math.sin(a), Math.cos(a));

/** Draws a uniform cubic B-spline through the control points (the same maths as d3.curveBasis). */
function basis(ctx, pts) {
  let x0 = NaN; let y0 = NaN; let x1 = NaN; let y1 = NaN; let p = 0;
  const bez = (x, y) => ctx.bezierCurveTo((2 * x0 + x1) / 3, (2 * y0 + y1) / 3, (x0 + 2 * x1) / 3, (y0 + 2 * y1) / 3, (x0 + 4 * x1 + x) / 6, (y0 + 4 * y1 + y) / 6);
  for (const [x, y] of pts) {
    if (p === 0) { p = 1; ctx.moveTo(x, y); } else if (p === 1) p = 2;
    else { if (p === 2) { p = 3; ctx.lineTo((5 * x0 + x1) / 6, (5 * y0 + y1) / 6); } bez(x, y); }
    x0 = x1; x1 = x; y0 = y1; y1 = y;
  }
  if (p === 3) bez(x1, y1);
  if (p >= 2) ctx.lineTo(x1, y1);
}

export function createGraphScene(canvas, { reducedMotion = false } = {}) {
  const ctx = canvas.getContext("2d", { alpha: false });
  const state = {
    items: [], index: new Map(), links: [], codeEdges: [], groups: new Map(), routes: new Map(), kids: new Map(),
    rings: [], packages: [], marks: [], badged: [], strong: new Set(),
    selected: null, path: new Set(), hover: null, neighbours: new Set(), dimmed: new Set(),
    cam: { x: 0, y: 0, s: 1, rot: 0 }, anim: null, inset: 0, dirty: true, reducedMotion, focusId: null,
    frameCallbacks: [], times: [], lastFrame: 0, width: 1, height: 1, dpr: 1, draws: 0, destroyed: false, extent: 220,
  };

  function resize() {
    const dpr = Math.min(window.devicePixelRatio || 1, 2);
    const w = Math.max(1, canvas.clientWidth);
    const h = Math.max(1, canvas.clientHeight);
    if (w === state.width && h === state.height && dpr === state.dpr) return;
    state.width = w; state.height = h; state.dpr = dpr;
    canvas.width = Math.round(w * dpr);
    canvas.height = Math.round(h * dpr);
    state.dirty = true;
  }

  // ---------------------------------------------------------------- camera (pan, zoom and a rotation)
  const rotated = (x, y, rot = state.cam.rot) => [x * Math.cos(rot) - y * Math.sin(rot), x * Math.sin(rot) + y * Math.cos(rot)];
  function toScreen(x, y) {
    const [rx, ry] = rotated(x, y);
    return [(rx - state.cam.x) * state.cam.s + (state.width - state.inset) / 2, (ry - state.cam.y) * state.cam.s + state.height / 2];
  }
  const item = (id) => state.items[state.index.get(id)];

  function animateTo(to, ms = MOVE_MS) {
    const from = { ...state.cam };
    to.rot = from.rot + norm(to.rot - from.rot);
    return new Promise((resolve) => {
      if (state.reducedMotion || !ms) { state.cam = to; state.anim = null; state.dirty = true; resolve(0); return; }
      state.anim = { from, to, start: performance.now(), duration: ms, resolve };
      state.dirty = true;
    });
  }

  /** Camera that shows an open node's fan, turned so the fan faces right and its labels read as a list. */
  function frameOf(id) {
    const target = item(id);
    if (!target || target.ring === 0) return homeView();
    const rot = -target.angle;
    const pts = [[target.x, target.y]];
    for (const it of state.items) if (it.parent === id) pts.push([it.x, it.y]);
    if (target.kind === "package" && pts.length === 1) {
      for (const t of [-0.5, 0.5]) pts.push([Math.cos(target.angle + target.span * t) * target.ring, Math.sin(target.angle + target.span * t) * target.ring]);
    }
    let x0 = Infinity; let y0 = Infinity; let x1 = -Infinity; let y1 = -Infinity;
    for (const [x, y] of pts) {
      const [rx, ry] = rotated(x, y, rot);
      x0 = Math.min(x0, rx); x1 = Math.max(x1, rx); y0 = Math.min(y0, ry); y1 = Math.max(y1, ry);
    }
    const labelRoom = 230;
    const w = Math.max(40, state.width - state.inset - labelRoom - 160);
    const h = Math.max(40, state.height - 230);
    const s = Math.min(Math.max(Math.min(w / Math.max(x1 - x0, 60), h / Math.max(y1 - y0, 60)), 0.35), 2.6);
    return { x: (x0 + x1) / 2 + labelRoom / 2 / s, y: (y0 + y1) / 2, s, rot };
  }

  function homeView() {
    const r = state.extent * 1.2 + 40;
    return { x: 0, y: 0, s: (Math.min(state.width - state.inset - 300, state.height - 80) / 2) / r, rot: 0 };
  }

  function focus(id, { instant = false } = {}) {
    resize();
    let it = item(id);
    if (!it) return Promise.resolve(0);
    if (!it.open && it.parent !== undefined && item(it.parent)?.ring > 0) it = item(it.parent);
    if (!it.open && it.ring > 0 && it.kind !== "package") it = item(it.parent) || it;
    state.focusId = it.ring === 0 ? null : it.id;
    state.focusKids = state.items.filter((k) => k.parent === state.focusId).length;
    return animateTo(state.focusId ? frameOf(state.focusId) : homeView(), instant ? 0 : MOVE_MS);
  }

  function resetView(instant = false) {
    resize();
    state.focusId = null;
    return animateTo(homeView(), instant ? 0 : MOVE_MS);
  }

  let drag = null;
  const onDown = (e) => { drag = { x: e.clientX, y: e.clientY, cam: { ...state.cam } }; state.anim = null; };
  const onMove = (e) => {
    if (!drag || !(e.buttons & 1)) return;
    state.cam.x = drag.cam.x - (e.clientX - drag.x) / state.cam.s;
    state.cam.y = drag.cam.y - (e.clientY - drag.y) / state.cam.s;
    state.dirty = true;
  };
  const onUp = () => { drag = null; };
  const onWheel = (e) => {
    e.preventDefault();
    state.anim = null;
    const rect = canvas.getBoundingClientRect();
    const sx = e.clientX - rect.left - (state.width - state.inset) / 2;
    const sy = e.clientY - rect.top - state.height / 2;
    const before = [sx / state.cam.s + state.cam.x, sy / state.cam.s + state.cam.y];
    state.cam.s = Math.min(Math.max(state.cam.s * Math.exp(-e.deltaY * (e.ctrlKey ? 0.01 : 0.0015)), 0.15), 30);
    state.cam.x = before[0] - sx / state.cam.s;
    state.cam.y = before[1] - sy / state.cam.s;
    state.dirty = true;
  };
  canvas.addEventListener("pointerdown", onDown);
  window.addEventListener("pointermove", onMove);
  window.addEventListener("pointerup", onUp);
  canvas.addEventListener("wheel", onWheel, { passive: false });
  const ro = new ResizeObserver(() => resize());
  ro.observe(canvas);

  // ---------------------------------------------------------------- bundled routes
  /** Control points from a node up to the project: the node, its ancestors, and its package group's meeting point. */
  function chain(id) {
    const out = [];
    let it = item(id);
    while (it) {
      out.push({ key: it.id, x: it.ring === 0 ? 0 : it.x * (it.kind === "package" ? 0.97 : 1), y: it.ring === 0 ? 0 : it.y * (it.kind === "package" ? 0.97 : 1) });
      if (it.kind === "package" && it.group && state.groups.has(it.group)) {
        const g = state.groups.get(it.group);
        out.push({ key: "g:" + it.group, x: g.x, y: g.y });
      }
      it = it.parent !== undefined ? item(it.parent) : null;
    }
    return out;
  }

  /** World-space control points of a bundled dependency curve (cached until the next sync). */
  function route(a, b) {
    const key = a + ">" + b;
    const hit = state.routes.get(key);
    if (hit) return hit;
    const ca = chain(a);
    const cb = chain(b);
    const keys = new Map(cb.map((p, k) => [p.key, k]));
    let ia = ca.findIndex((p) => keys.has(p.key));
    if (ia < 0) ia = ca.length - 1;
    const ib = keys.has(ca[ia].key) ? keys.get(ca[ia].key) : cb.length - 1;
    const pts = ca.slice(0, ia + 1).concat(cb.slice(0, ib).reverse());
    // through the project: neighbours stay near the orbit, opposite packages cross the middle
    const top = pts.findIndex((p) => item(p.key)?.ring === 0);
    if (top > 0 && top < pts.length - 1) {
      const a0 = Math.atan2(pts[top - 1].y, pts[top - 1].x);
      const b0 = Math.atan2(pts[top + 1].y, pts[top + 1].x);
      const sep = Math.abs(norm(b0 - a0));
      const mid = a0 + norm(b0 - a0) / 2;
      const r = Math.min(Math.hypot(pts[top - 1].x, pts[top - 1].y), Math.hypot(pts[top + 1].x, pts[top + 1].y)) * Math.cos(sep / 2) * 0.6;
      pts[top] = { key: pts[top].key, x: Math.cos(mid) * r, y: Math.sin(mid) * r };
    }
    if (pts.length === 3 && pts[1].key === pts[0].key) pts.splice(1, 1);
    const n = pts.length - 1;
    const [sx, sy] = [pts[0].x, pts[0].y];
    const [ex, ey] = [pts[n].x, pts[n].y];
    const out = pts.map((p, k) => {
      const t = n ? k / n : 0;
      return [BETA * p.x + (1 - BETA) * (sx + t * (ex - sx)), BETA * p.y + (1 - BETA) * (sy + t * (ey - sy))];
    });
    state.routes.set(key, out);
    return out;
  }

  function within(id, anc) {
    let it = item(id);
    while (it) { if (it.id === anc) return true; it = it.parent !== undefined ? item(it.parent) : null; }
    return false;
  }

  /** Arrowhead at the end of a world-space curve; `px` sizes are screen pixels. */
  function arrow(pts, color, back) {
    const k = 1 / state.cam.s;
    const n = pts.length - 1;
    const [ex, ey] = pts[n];
    const [px, py] = pts[Math.max(0, n - 1)];
    const len = Math.hypot(ex - px, ey - py) || 1;
    const ux = (ex - px) / len;
    const uy = (ey - py) / len;
    const tx = ex - ux * back * k;
    const ty = ey - uy * back * k;
    ctx.fillStyle = color;
    ctx.beginPath();
    ctx.moveTo(tx, ty);
    ctx.lineTo(tx - (ux * 7 + uy * 3.5) * k, ty - (uy * 7 - ux * 3.5) * k);
    ctx.lineTo(tx - (ux * 7 - uy * 3.5) * k, ty - (uy * 7 + ux * 3.5) * k);
    ctx.closePath();
    ctx.fill();
  }

  // ---------------------------------------------------------------- drawing
  /**
   * Everything is drawn in world coordinates under one canvas transform (camera pan, zoom and rotation), so a frame
   * does no per-point maths; sizes meant in screen pixels are divided by the zoom. Marks of one style go in one path.
   */
  function draw() {
    const { width: w, height: h, dpr } = state;
    const { s, rot } = state.cam;
    const px = 1 / s;
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    ctx.fillStyle = COLORS.bg;
    ctx.fillRect(0, 0, w, h);
    const cs = Math.cos(rot) * s;
    const sn = Math.sin(rot) * s;
    const ox = (w - state.inset) / 2 - state.cam.x * s;
    const oy = h / 2 - state.cam.y * s;
    ctx.setTransform(dpr * cs, dpr * sn, -dpr * sn, dpr * cs, dpr * ox, dpr * oy);
    let draws = 1;
    const items = state.items;
    const active = state.hover || state.selected;
    const dimmed = state.dimmed;
    // the visible world rectangle (rotation-safe: a circle around the view centre), for culling
    const [vx, vy] = [(-ox + (w - state.inset) / 2) , (-oy + h / 2)];
    const cxw = (vx * Math.cos(rot) + vy * Math.sin(rot)) / s;
    const cyw = (-vx * Math.sin(rot) + vy * Math.cos(rot)) / s;
    const reach = (Math.hypot(w, h) / 2 + 40) / s;
    const onScreen = (x, y) => Math.abs(x - cxw) < reach && Math.abs(y - cyw) < reach;

    // orbit guides: one hairline per layer that has content
    ctx.lineWidth = px;
    ctx.strokeStyle = rgba(COLORS.line, 0.08);
    for (const r of state.rings) { ctx.beginPath(); ctx.arc(0, 0, r, 0, TAU); ctx.stroke(); draws++; }

    // hierarchy: a comb from each open node to its fan (neutral, thin)
    for (const [pid, kids] of state.kids) {
      const parent = item(pid);
      if (!parent || !parent.open || parent.ring === 0 || !kids.length) continue;
      const ring = kids[0].ring;
      const inner = parent.ring + (parent.kind === "package" ? 8 : 10);
      const bar = ring - 22;
      let a0 = Infinity; let a1 = -Infinity;
      for (const k of kids) { if (k.angle < a0) a0 = k.angle; if (k.angle > a1) a1 = k.angle; }
      ctx.globalAlpha = dimmed.has(pid) ? 0.38 : 1;
      ctx.strokeStyle = rgba(COLORS.line, state.path.has(pid) ? 0.55 : 0.32);
      ctx.beginPath();
      ctx.moveTo(Math.cos(parent.angle) * inner, Math.sin(parent.angle) * inner);
      ctx.lineTo(Math.cos(parent.angle) * bar, Math.sin(parent.angle) * bar);
      ctx.moveTo(Math.cos(a0) * bar, Math.sin(a0) * bar);
      ctx.arc(0, 0, bar, a0, a1);
      ctx.stroke();
      ctx.strokeStyle = rgba(COLORS.line, 0.2);
      ctx.beginPath();
      for (const k of kids) {
        ctx.moveTo(Math.cos(k.angle) * bar, Math.sin(k.angle) * bar);
        ctx.lineTo(Math.cos(k.angle) * (ring - 7), Math.sin(k.angle) * (ring - 7));
      }
      ctx.stroke();
      draws += 2;
    }
    ctx.globalAlpha = 1;

    // dependencies: bundled curves, quiet by default, the selection's own in the accent colour with arrowheads
    const lit = [];
    for (const l of state.links) {
      if (!state.index.has(l.source) || !state.index.has(l.target)) continue;
      const ia = active && within(l.source, active);
      const ib = active && within(l.target, active);
      if ((ia || ib) && (ia !== ib || l.source === active || l.target === active)) { lit.push(l); continue; }
      let alpha = (state.strong.has(l) ? 0.2 : 0.07) + l.weight * 0.18;
      if (active && !(ia && ib)) alpha *= 0.4;
      if (dimmed.has(l.source) || dimmed.has(l.target)) alpha *= 0.4;
      ctx.strokeStyle = rgba(COLORS.soft, alpha);
      ctx.lineWidth = (0.7 + l.weight * 1.3) * px;
      ctx.beginPath(); basis(ctx, route(l.source, l.target)); ctx.stroke();
      draws++;
    }
    const litEdges = lit.map((l) => ({ ...l, pts: route(l.source, l.target) }));
    for (const e of state.codeEdges) {
      if (!state.index.has(e.source) || !state.index.has(e.target)) continue;
      litEdges.push({ ...e, weight: 0.7, pts: route(e.source, e.target) });
    }
    for (const l of litEdges) {
      ctx.strokeStyle = rgba(COLORS.accent, 0.9);
      ctx.lineWidth = (1.1 + l.weight * 1.4) * px;
      ctx.beginPath(); basis(ctx, l.pts); ctx.stroke();
      arrow(l.pts, COLORS.accent, item(l.target)?.kind === "package" ? 3 : 6);
      draws += 2;
    }

    // a closed package carries a fine ruler outside its arc: one tick per class it holds
    ctx.lineWidth = px;
    ctx.strokeStyle = rgba(COLORS.line, 0.45);
    ctx.beginPath();
    for (const it of state.packages) {
      if (it.open || !it.children || dimmed.has(it.id)) continue;
      const n = Math.min(it.children, Math.floor((it.ring * s * it.span) / 3));
      for (let k = 0; k < n; k++) {
        const a = it.angle - it.span / 2 + (it.span * (k + 0.5)) / n;
        ctx.moveTo(Math.cos(a) * (it.ring + 7), Math.sin(a) * (it.ring + 7));
        ctx.lineTo(Math.cos(a) * (it.ring + 12), Math.sin(a) * (it.ring + 12));
      }
    }
    ctx.stroke();
    draws++;

    // packages: arcs on the inner orbit
    ctx.lineCap = "butt";
    for (const it of state.packages) {
      const sel = it.id === state.selected;
      const strong = sel || it.id === state.hover || state.path.has(it.id);
      ctx.globalAlpha = dimmed.has(it.id) ? 0.38 : 1;
      ctx.strokeStyle = sel ? COLORS.accent : strong ? COLORS.text : rgba(COLORS.line, 0.85);
      ctx.lineWidth = (sel ? 6 : strong ? 5.5 : 4.5) * px;
      const half = Math.max(it.span / 2 - 0.002, 0.0015);
      ctx.beginPath(); ctx.arc(0, 0, it.ring, it.angle - half, it.angle + half); ctx.stroke();
      draws++;
    }
    ctx.globalAlpha = 1;

    // classes, files, members, neighbours: small marks, one path per style
    const groups = new Map();
    const special = [];
    for (const it of state.marks) {
      if (!onScreen(it.x, it.y)) continue;
      const sel = it.id === state.selected;
      if (sel || it.id === state.hover || it.open || state.path.has(it.id)) special.push(it);
      const hollow = it.kind === "file" || it.kind === "ghost";
      const color = sel || state.neighbours.has(it.id) ? COLORS.accent : it.kind === "ghost" ? COLORS.faint
        : hollow ? COLORS.soft : it.kind === "member" ? COLORS.soft : "#c9d2de";
      const key = (hollow ? "s" : "f") + color + (dimmed.has(it.id) ? "d" : "") + (it.kind === "member" || it.kind === "ghost" ? "m" : "");
      let g = groups.get(key);
      if (!g) groups.set(key, g = { hollow, color, dim: dimmed.has(it.id), r: (it.kind === "member" || it.kind === "ghost" ? 2.4 : 3.2) * px, list: [] });
      g.list.push(it);
    }
    for (const g of groups.values()) {
      ctx.globalAlpha = g.dim ? 0.38 : 1;
      ctx.beginPath();
      for (const it of g.list) { ctx.moveTo(it.x + g.r, it.y); ctx.arc(it.x, it.y, g.r, 0, TAU); }
      if (g.hollow) { ctx.strokeStyle = g.color; ctx.lineWidth = 1.2 * px; ctx.stroke(); } else { ctx.fillStyle = g.color; ctx.fill(); }
      draws++;
    }
    for (const it of special) {
      const sel = it.id === state.selected;
      const r = (it.kind === "member" || it.kind === "ghost" ? 2.4 : 3.2) + 4;
      ctx.globalAlpha = dimmed.has(it.id) ? 0.38 : 1;
      ctx.strokeStyle = sel ? COLORS.text : it.id === state.hover ? COLORS.soft : rgba(COLORS.text, 0.6);
      ctx.lineWidth = (sel ? 1.6 : 1.1) * px;
      ctx.beginPath(); ctx.arc(it.x, it.y, r * px, 0, TAU); ctx.stroke();
      draws++;
    }

    // memories (circle) and rules (diamond), just inside the node on the orbit
    for (const it of state.badged) {
      const inset = it.kind === "package" ? 12 : 11;
      const spread = Math.min(0.5, 4.5 / (it.ring * s));
      const put = (k) => [Math.cos(it.angle + k) * (it.ring - inset), Math.sin(it.angle + k) * (it.ring - inset)];
      ctx.globalAlpha = dimmed.has(it.id) ? 0.38 : 1;
      if (it.memories) {
        const [x, y] = put(it.rules ? -spread : 0);
        ctx.fillStyle = COLORS.memory;
        ctx.beginPath(); ctx.arc(x, y, 2.6 * px, 0, TAU); ctx.fill();
      }
      if (it.rules) {
        const [x, y] = put(it.memories ? spread : 0);
        const d = 3.4 * px;
        ctx.fillStyle = COLORS.rule;
        ctx.beginPath(); ctx.moveTo(x, y - d); ctx.lineTo(x + d, y); ctx.lineTo(x, y + d); ctx.lineTo(x - d, y); ctx.closePath(); ctx.fill();
      }
      draws++;
    }
    ctx.globalAlpha = 1;
    state.draws = draws;
  }

  function frame(now) {
    if (state.destroyed) return;
    requestAnimationFrame(frame);
    resize();
    if (state.anim) {
      const a = state.anim;
      const t = Math.min(1, (now - a.start) / a.duration);
      const k = ease(t);
      const ls = Math.log(a.from.s) + (Math.log(a.to.s) - Math.log(a.from.s)) * k;
      state.cam = { x: a.from.x + (a.to.x - a.from.x) * k, y: a.from.y + (a.to.y - a.from.y) * k, s: Math.exp(ls), rot: a.from.rot + (a.to.rot - a.from.rot) * k };
      if (t >= 1) { state.anim = null; a.resolve(a.duration); }
      state.dirty = true;
    }
    if (!state.dirty) return;
    // frames are drawn only when something changed, so the cost of a frame is its own work (drawing + labels),
    // not the time since the previous one
    const started = performance.now();
    draw();
    state.dirty = false;
    for (const cb of state.frameCallbacks) cb(true, 0);
    state.times.push(performance.now() - started);
    if (state.times.length > 600) state.times.shift();
  }
  requestAnimationFrame(frame);

  // ---------------------------------------------------------------- data in
  function sync(items, _treeEdges, extras = {}) {
    state.items = items;
    state.index = new Map(items.map((it, i) => [it.id, i]));
    state.routes = new Map();
    state.kids = new Map();
    for (const it of items) if (it.parent !== undefined && it.kind !== "ghost") {
      if (!state.kids.has(it.parent)) state.kids.set(it.parent, []);
      state.kids.get(it.parent).push(it);
    }
    state.rings = [...new Set(items.filter((it) => it.ring > 0 && it.kind !== "ghost").map((it) => it.ring))];
    state.packages = items.filter((it) => it.kind === "package");
    state.marks = items.filter((it) => it.kind !== "package" && it.ring > 0);
    state.badged = items.filter((it) => (it.memories || it.rules) && it.ring > 0);
    state.groups = extras.groups || state.groups;
    state.extent = extras.extent || state.extent;
    state.dirty = true;
    // a node opened while the camera was heading to it: frame its new fan once its children arrive
    const kids = state.focusId ? (state.kids.get(state.focusId) || []).length : 0;
    if (state.focusId && state.index.has(state.focusId) && kids !== state.focusKids && !drag) animateTo(frameOf(state.focusId));
    state.focusKids = kids;
  }

  // ---------------------------------------------------------------- queries
  function screenOf(id) {
    const it = item(id);
    if (!it) return null;
    const [x, y] = toScreen(it.x, it.y);
    const a = it.angle + state.cam.rot;
    return { x, y, radius: it.ring === 0 ? state.extent * state.cam.s : it.kind === "package" ? 6 : 4, depth: 1e9, dx: Math.cos(a), dy: Math.sin(a) };
  }

  /** The node under the pointer: the nearest mark within reach, or the package arc under it. */
  function pick(clientX, clientY) {
    const rect = canvas.getBoundingClientRect();
    const px = clientX - rect.left;
    const py = clientY - rect.top;
    let best = null;
    let bestD = 14;
    for (const it of state.items) {
      if (it.ring === 0 || it.kind === "package") continue;
      const [x, y] = toScreen(it.x, it.y);
      const d = Math.hypot(px - x, py - y);
      if (d < bestD) { best = it.id; bestD = d; }
    }
    if (best) return best;
    const [cx, cy] = toScreen(0, 0);
    const r = Math.hypot(px - cx, py - cy) / state.cam.s;
    const angle = Math.atan2(py - cy, px - cx) - state.cam.rot;
    for (const it of state.items) {
      if (it.kind !== "package") continue;
      if (Math.abs(r - it.ring) * state.cam.s > 12) continue;
      if (Math.abs(norm(angle - it.angle)) <= it.span / 2 + 3 / (it.ring * state.cam.s)) return it.id;
    }
    return null;
  }

  return {
    state,
    sync,
    setLinks: (links) => { state.links = links; state.strong = new Set(links.slice().sort((x, y) => y.weight - x.weight).slice(0, 14)); state.dirty = true; },
    setCodeEdges: (edges) => { state.codeEdges = edges || []; state.dirty = true; },
    setHighlight: ({ selected = null, path = [], hover = null, neighbours = [], dimmed = [] } = {}) => {
      state.selected = selected; state.path = new Set(path); state.hover = hover;
      state.neighbours = new Set(neighbours); state.dimmed = new Set(dimmed); state.dirty = true;
    },
    setHover: (id) => { state.hover = id; state.dirty = true; },
    focus, flyTo: focus, resetView,
    setInset: (px) => { if (state.inset !== px) { state.inset = px; state.dirty = true; if (!state.anim) animateTo(state.focusId ? frameOf(state.focusId) : homeView()); } },
    pick, screenOf,
    projectAll: () => state.items.map((it) => screenOf(it.id)),
    positionOf: (id) => { const it = item(id); return it ? [it.x, it.y, 0] : null; },
    radiusOf: () => 0,
    requestRender: () => { state.dirty = true; },
    touch: () => {},
    onFrame: (cb) => state.frameCallbacks.push(cb),
    setReducedMotion: (value) => { state.reducedMotion = value; state.dirty = true; },
    setBloom: () => {},
    animating: () => !!state.anim,
    drawCalls: () => state.draws,
    frameStats: () => {
      const times = state.times.slice().sort((a, b) => a - b);
      if (!times.length) return null;
      return { median: times[Math.floor(times.length / 2)], p95: times[Math.floor(times.length * 0.95)], samples: times.length };
    },
    resetFrameStats: () => { state.times = []; state.lastFrame = 0; },
    destroy: () => {
      state.destroyed = true;
      canvas.removeEventListener("pointerdown", onDown);
      window.removeEventListener("pointermove", onMove);
      window.removeEventListener("pointerup", onUp);
      canvas.removeEventListener("wheel", onWheel);
      ro.disconnect();
    },
  };
}
