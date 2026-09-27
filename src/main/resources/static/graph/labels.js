// HTML labels over the canvas. Candidates arrive ranked (selected > path > hovered > badges > neighbours > largest)
// and are placed greedily; a label whose box would overlap an already placed one is skipped, so visible
// labels never overlap. A label may carry memory (circle) and rule (diamond) counts. Align: start | center | end.

const POOL_SIZE = 110;
const FONTS = {
  plane: "500 15px system-ui, -apple-system, sans-serif",
  focus: "500 14px system-ui, -apple-system, sans-serif",
  normal: "13px system-ui, -apple-system, sans-serif",
};

export function createLabels(container) {
  const pool = [];
  for (let i = 0; i < POOL_SIZE; i++) {
    const span = document.createElement("span");
    const text = document.createElement("em");
    const mem = document.createElement("b");
    const rule = document.createElement("b");
    mem.className = "bm";
    rule.className = "br";
    span.append(text, mem, rule);
    span.style.opacity = "0";
    container.append(span);
    pool.push({ span, text, mem, rule, key: "" });
  }
  let placed = [];
  const measureCtx = document.createElement("canvas").getContext("2d");
  const widths = new Map();

  function measure(text, kind) {
    const key = kind + "|" + text;
    let w = widths.get(key);
    if (w === undefined) {
      measureCtx.font = FONTS[kind];
      w = Math.ceil(measureCtx.measureText(text).width) + 6;
      if (widths.size > 20000) widths.clear();
      widths.set(key, w);
    }
    return w;
  }

  function update(candidates, budget = POOL_SIZE, blocked = []) {
    placed = [];
    const taken = blocked.slice();
    let used = 0;
    const width = container.clientWidth;
    const height = container.clientHeight;
    for (const c of candidates) {
      if (used >= Math.min(budget, POOL_SIZE)) break;
      if (!c.screen || c.screen.x < -40 || c.screen.y < -20 || c.screen.x > width + 40 || c.screen.y > height + 20) continue;
      const kind = c.className.includes("plane") ? "plane" : c.className.includes("focus") ? "focus" : "normal";
      const fontSize = kind === "focus" ? 14 : kind === "plane" ? 15 : 13;
      const badges = (c.memories ? 20 + String(c.memories).length * 7 : 0) + (c.rules ? 20 + String(c.rules).length * 7 : 0);
      const w = measure(c.text, kind) + badges + 4;
      const h = Math.ceil(fontSize * 1.4) + 2;
      const ox = c.offsetX ?? 10;
      const x = c.align === "center" ? c.screen.x + ox - w / 2 : c.align === "end" ? c.screen.x + ox - w : c.screen.x + ox;
      const y = c.screen.y - h / 2 + (c.offsetY ?? 0);
      const box = { x, y, w, h };
      if (taken.some((p) => box.x < p.x + p.w && box.x + box.w > p.x && box.y < p.y + p.h && box.y + box.h > p.y)) continue;
      box.id = c.id;
      placed.push(box);
      taken.push(box);
      const slot = pool[used++];
      const key = c.text + "|" + (c.memories || 0) + "|" + (c.rules || 0);
      if (slot.key !== key) {
        slot.key = key;
        slot.text.textContent = c.text;
        slot.mem.textContent = c.memories ? String(c.memories) : "";
        slot.rule.textContent = c.rules ? String(c.rules) : "";
        slot.mem.hidden = !c.memories;
        slot.rule.hidden = !c.rules;
      }
      if (slot.span.className !== c.className) slot.span.className = c.className;
      slot.span.style.transform = `translate(${x.toFixed(1)}px, ${y.toFixed(1)}px)`;
      slot.span.style.opacity = String(c.opacity ?? 1);
    }
    for (let i = used; i < POOL_SIZE; i++) if (pool[i].span.style.opacity !== "0") pool[i].span.style.opacity = "0";
  }

  /** Bounding boxes of visible labels, for tests: none may intersect. */
  function rects() {
    return pool.filter((s) => s.span.style.opacity !== "0").map((s) => s.span.getBoundingClientRect());
  }

  /** The node whose label is under a point (container coordinates), so a label can be clicked like its mark. */
  function hit(x, y) {
    const box = placed.find((b) => b.id && x >= b.x - 3 && x <= b.x + b.w + 3 && y >= b.y - 2 && y <= b.y + b.h + 2);
    return box ? box.id : null;
  }

  return { update, rects, hit, placedCount: () => placed.length };
}
