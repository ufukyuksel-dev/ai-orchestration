// Code atlas view: wires the lazily loaded code tree, the radial layout, the canvas scene, the bundled dependency
// links, labels, the detail card, the focus trail (breadcrumb / Esc goes one layer up), keyboard/pointer
// interaction, the list fallback and test hooks.
// Mounted by the panel's Graph tab and by the full-screen /universe.html with the same code.
import { api as request, createTree, KIND_LABELS, LAYER_LABELS, isSymbolId } from "./data.js";
import { layoutAtlas, RING } from "./atlas-layout.js";
import { createLabels } from "./labels.js";
import { renderDetail, el } from "./detail.js";
import { t } from "/workspace/panel/i18n.js";

const MAX_GHOSTS = 60;
const LINK_PARALLEL = 6;
const DETAIL_INSET = 412;
const LABEL_BUDGET = 42;

/** The atlas draws with Canvas 2D (no GPU needed); the list fallback is for browsers without it. */
export function canvasAvailable() {
  try {
    return !!document.createElement("canvas").getContext("2d");
  } catch {
    return false;
  }
}

function trim(text, max = 44) {
  const value = String(text || "");
  return value.length > max ? value.slice(0, max - 1) + "…" : value;
}

/**
 * options: {project, reducedMotion, fullscreen, actions(node, view) → Node|null, footer(node) → Node|null,
 *           onSelect(id|null), initialNode, memoryHref(id), ruleHref(id), emptyAction: Node}
 */
/**
 * Short package names: each name drops the deepest prefix that many packages share
 * ("com.acme.app.modules.memory" → "memory", "com.acme.app.core.pipeline" → "core.pipeline").
 */
let sharedPrefixes = [];
function computePrefix(names) {
  const dotted = names.map((n) => n.split(" · ")[0]).filter((n) => n.includes(".") && !n.includes("/"));
  const counts = new Map();
  for (const n of dotted) {
    const parts = n.split(".");
    for (let k = 1; k < parts.length; k++) {
      const p = parts.slice(0, k).join(".") + ".";
      counts.set(p, (counts.get(p) || 0) + 1);
    }
  }
  const need = Math.max(3, Math.ceil(dotted.length * 0.2));
  return [...counts].filter(([, c]) => c >= need).map(([p]) => p).sort((a, b) => b.length - a.length);
}
function shortName(node) {
  if (!node) return "";
  if (node.kind !== "package") return node.name;
  const p = sharedPrefixes.find((pre) => node.name.startsWith(pre) && node.name.length > pre.length + 1);
  const name = p ? node.name.slice(p.length) : node.name;
  return name.replace(/ · src\/main$/, "").replace(/ · src\/test$/, " · test");
}

export function createGraphView(host, options) {
  const project = options.project;
  const controller = new AbortController();
  const signal = controller.signal;
  const tree = createTree(project);
  const view = {
    scene: null, labels: null, items: [], itemIndex: new Map(), treeEdges: [], depths: 0,
    selected: null, hover: null, focus: null, ghosts: new Map(), codeEdges: [], edgeData: null, attached: undefined,
    links: new Map(), linkLoads: new Map(),
    selectSeq: 0, clickStartedAt: null, metrics: {}, destroyed: false, fallback: false,
    reducedMotion: options.reducedMotion ?? (window.matchMedia("(prefers-reduced-motion: reduce)").matches || localStorage.getItem("space.motion") === "reduced"),
  };

  // ---------------------------------------------------------------- DOM
  host.classList.add("graph-view");
  if (options.fullscreen) host.classList.add("fullscreen");
  const canvas = el("canvas", "graph-canvas");
  canvas.setAttribute("aria-label", t("Katmanlı kod haritası"));
  canvas.tabIndex = 0;
  const vignette = el("div", "graph-vignette");
  const labelLayer = el("div", "graph-labels");
  labelLayer.setAttribute("aria-hidden", "true");
  const detail = el("aside", "graph-detail");
  detail.hidden = true;
  detail.setAttribute("aria-live", "polite");
  const loading = el("div", "graph-loading", el("div", "bar", el("i")), el("span", null, t("Kod haritası hazırlanıyor")));
  const banner = el("div", "graph-banner");
  banner.hidden = true;
  banner.setAttribute("role", "alert");
  const empty = el("div", "graph-empty");
  empty.hidden = true;
  const fallbackBox = el("div", "graph-fallback");
  fallbackBox.hidden = true;
  const stat = el("p", "stat");
  const legend = el("div", "graph-legend",
    el("h2", null, t("Katmanlar")),
    el("ol", "layers", LAYER_LABELS.slice(0, 4).map((label, k) => el("li", "l" + k, el("i"), label))),
    el("div", "badge-legend", el("span", "bm", el("i"), t("Hafıza")), el("span", "br", el("i"), t("Kural")), el("span", "bp", el("i"), t("Bağımlılık"))),
    stat);
  const resetButton = el("button", null, t("Tümünü göster"));
  resetButton.type = "button";
  resetButton.title = t("Tüm grafı göster (F)");
  const motionButton = el("button", null, t("Hareket"));
  motionButton.type = "button";
  const findInput = el("input");
  findInput.type = "search";
  findInput.placeholder = t("Sınıf, metot, paket bul  /");
  findInput.setAttribute("aria-label", t("Kod haritasında bul"));
  findInput.autocomplete = "off";
  const findList = el("ul", "find-list");
  findList.setAttribute("role", "listbox");
  findList.hidden = true;
  const finder = el("div", "graph-find", findInput, findList);
  const tools = el("div", "graph-tools", finder, resetButton, motionButton);
  if (!options.fullscreen) {
    const full = el("a", null, t("Tam ekran"));
    full.href = "/universe.html?project=" + encodeURIComponent(project);
    full.target = "_blank";
    full.rel = "noopener";
    full.title = t("Grafı ayrı sekmede tam ekran aç");
    tools.append(full);
    view.fullLink = full;
  }
  const crumbs = el("nav", "graph-crumbs");
  crumbs.setAttribute("aria-label", t("Bulunduğun katman"));
  crumbs.hidden = true;
  const hint = el("footer", "graph-hint", t("Tıkla: aç ve seç · ↑↓: komşu · Enter: aç · Esc: bir üst katman · /: bul · F: tümünü göster"));
  host.append(canvas, vignette, labelLayer, legend, tools, crumbs, detail, loading, banner, empty, fallbackBox, hint);

  function showBanner(message, retry) {
    if (!message) { banner.hidden = true; return; }
    banner.replaceChildren(el("span", null, message));
    if (retry) {
      const b = el("button", null, t("Tekrar dene"));
      b.type = "button";
      b.onclick = () => { banner.hidden = true; retry(); };
      banner.append(b);
    }
    banner.hidden = false;
  }

  // ---------------------------------------------------------------- model → render items
  const childrenOf = (node) => (node.expanded && node.children ? node.children.map((id) => tree.get(id)) : []);

  /** Is `id` the focus node or inside it? */
  function insideFocus(id) {
    if (!view.focus || view.focus === tree.root?.id) return true;
    let node = tree.get(id);
    while (node) {
      if (node.id === view.focus) return true;
      node = node.parent ? tree.get(node.parent) : null;
    }
    return false;
  }

  /** Packages whose short names share a first segment are bundled together ("core.pipeline", "core.store"). */
  const groupOf = (node) => shortName(node).split(" · ")[0].split(/[./]/)[0];

  function rebuild() {
    const root = tree.root;
    const { place, groups, groupOfId, extent } = layoutAtlas(root, childrenOf, groupOf);
    view.groups = groups;
    view.extent = extent;
    const items = [];
    const treeEdges = [];
    const depths = new Set();
    const stack = [root];
    while (stack.length) {
      const node = stack.pop();
      const at = place.get(node.id);
      if (!at) continue;
      const kids = childrenOf(node);
      depths.add(node.depth);
      items.push({
        id: node.id, kind: node.depth === 0 ? "project" : node.kind, depth: node.depth, x: at.x, y: at.y, z: 0,
        angle: at.angle, ring: at.ring, span: at.span, radius: node.depth === 0 ? extent : 4, parent: node.parent || undefined,
        group: groupOfId.get(node.id), open: kids.length > 0, children: node.childCount || 0, memories: node.memoryCount || 0, rules: node.ruleCount || 0,
      });
      for (const child of kids) { treeEdges.push([node.id, child.id]); stack.push(child); }
    }
    // Code neighbours that are not in the open tree sit on a short arc just outside the selected node.
    const sel = view.selected ? items.find((it) => it.id === view.selected) : null;
    if (sel && sel.ring > 0 && view.ghosts.size) {
      const ghosts = [...view.ghosts.values()];
      const ring = sel.ring + 90;
      const step = Math.min(20 / ring, (Math.PI * 1.2) / ghosts.length);
      ghosts.forEach((g, k) => {
        const angle = sel.angle + (k - (ghosts.length - 1) / 2) * step;
        items.push({
          id: g.id, kind: "ghost", depth: sel.depth + 1, parent: undefined, x: Math.cos(angle) * ring, y: Math.sin(angle) * ring, z: 0,
          angle, ring, span: step, radius: 3, open: false, memories: 0, rules: 0,
        });
      });
    }
    view.items = items;
    view.itemIndex = new Map(items.map((it, i) => [it.id, i]));
    view.treeEdges = treeEdges;
    view.depths = depths.size;
    if (view.scene) {
      view.scene.sync(items, treeEdges, { groups: view.groups, extent: view.extent });
      view.scene.setLinks(linkList());
      view.scene.setCodeEdges(view.codeEdges);
      highlight();
    }
    paintCrumbs();
    const counts = {};
    for (const it of items) counts[it.kind] = (counts[it.kind] || 0) + 1;
    stat.textContent = [
      counts.package ? t("{n} paket", { n: counts.package }) : null,
      counts.class ? t("{n} sınıf", { n: counts.class }) : null,
      counts.file ? t("{n} dosya", { n: counts.file }) : null,
      counts.member ? t("{n} üye", { n: counts.member }) : null,
    ].filter(Boolean).join(" · ") || t("Proje");
  }

  function highlight() {
    if (!view.scene) return;
    const path = view.selected && tree.has(view.selected) ? tree.pathTo(view.selected).map((n) => n.id) : view.selected ? [view.selected] : [];
    const neighbours = new Set(view.ghosts.keys());
    for (const e of view.codeEdges) { neighbours.add(e.source); neighbours.add(e.target); }
    const focusPath = view.focus && tree.has(view.focus) ? tree.pathTo(view.focus).map((n) => n.id) : [];
    const focusAncestors = new Set(focusPath);
    const focusSet = [];
    const dimmed = [];
    for (const it of view.items) {
      if (it.kind === "ghost") continue;
      if (insideFocus(it.id)) focusSet.push(it.id);
      else if (!focusAncestors.has(it.id)) dimmed.push(it.id);
    }
    view.dimmedSet = new Set(dimmed);
    view.scene.setHighlight({ selected: view.selected, path, hover: view.hover, neighbours: [...neighbours], focus: dimmed.length ? focusSet : [], dimmed });
  }

  // ---------------------------------------------------------------- sibling links (code-tree/links)
  function loadLinks(id) {
    if (view.links.has(id) || view.linkLoads.has(id) || view.destroyed) return view.linkLoads.get(id) || Promise.resolve();
    const parent = id === tree.root?.id ? "" : id;
    const load = request("code-tree/links?" + new URLSearchParams({ project, parent }), { signal })
      .then((data) => { view.links.set(id, data?.links || []); })
      .catch(() => { view.links.set(id, []); })
      .finally(() => {
        view.linkLoads.delete(id);
        if (!view.destroyed && view.scene) { view.scene.setLinks(linkList()); highlight(); }
      });
    view.linkLoads.set(id, load);
    return load;
  }

  async function loadLinksMany(ids) {
    for (let k = 0; k < ids.length; k += LINK_PARALLEL) await Promise.all(ids.slice(k, k + LINK_PARALLEL).map(loadLinks));
  }

  /** Links of every open node whose children are visible, weights normalised per parent (log scale). */
  function linkList() {
    const out = [];
    for (const [parent, links] of view.links) {
      const node = tree.get(parent);
      if (!node?.expanded || !view.itemIndex.has(parent) || !links.length) continue;
      const visible = links.filter((l) => view.itemIndex.has(l.source) && view.itemIndex.has(l.target) && l.source !== l.target);
      if (!visible.length) continue;
      const max = Math.log1p(Math.max(...visible.map((l) => l.weight || 1)));
      const pulses = Math.min(16, Math.max(3, Math.round(visible.length * 0.25)));
      visible.forEach((l, k) => out.push({
        source: l.source, target: l.target, parent,
        weight: max > 0 ? Math.max(0.08, Math.log1p(l.weight || 1) / max) : 0.5,
        pulse: k < pulses,
      }));
    }
    return out;
  }

  // ---------------------------------------------------------------- focus trail
  function paintCrumbs() {
    if (!tree.root || view.fallback) { crumbs.hidden = true; return; }
    const chain = tree.pathTo(view.focus && tree.has(view.focus) ? view.focus : tree.root.id);
    const parts = [];
    if (chain.length > 1) {
      const back = el("button", "back", "‹");
      back.type = "button";
      back.title = t("Bir üst katmana dön (Esc)");
      back.onclick = () => focusOut();
      parts.push(back);
    }
    chain.forEach((node, k) => {
      if (k) parts.push(el("span", "sep", "›"));
      const b = el("button", k === chain.length - 1 ? "here" : null, trim(node.name, 36));
      b.type = "button";
      b.onclick = () => (k === 0 ? showAll() : flyInto(node.id));
      parts.push(b);
    });
    crumbs.replaceChildren(...parts);
    crumbs.hidden = false;
  }

  function setFocus(id) {
    const next = id && tree.has(id) ? id : tree.root?.id;
    if (view.focus === next) return;
    view.focus = next;
    highlight();
    paintCrumbs();
  }

  /** Camera distance that frames a node: open spheres fill most of the view, leaves are seen inside their parent. */
  function frameDistance(id) {
    const i = view.itemIndex.get(id);
    if (i === undefined) return 60;
    const it = view.items[i];
    const node = tree.get(id);
    if (it.depth === 0) return it.radius * 2.75;
    if (it.open) return it.radius * 2.7;
    const parent = node?.parent ? view.items[view.itemIndex.get(node.parent)] : null;
    if (it.shape === "dot" || it.kind === "ghost") return Math.max(it.radius * 10, (parent?.radius || it.radius * 8) * 1.9);
    return it.radius * 3.1;
  }

  function flyInto(id) {
    if (!view.scene || !view.itemIndex.has(id)) return Promise.resolve(0);
    const node = tree.get(id);
    setFocus(node && (node.expanded || !node.parent) ? id : node?.parent || id);
    return view.scene.focus(id, { distance: frameDistance(id) });
  }

  function showAll() {
    setFocus(tree.root?.id);
    return view.scene ? view.scene.resetView() : Promise.resolve(0);
  }

  /** One level up: the parent of the current focus (Esc, back button). */
  function focusOut() {
    const current = tree.get(view.focus);
    if (!current || !current.parent) return showAll();
    const parent = tree.get(current.parent);
    if (!parent.parent) return showAll();
    setFocus(parent.id);
    return view.scene ? view.scene.focus(parent.id, { distance: frameDistance(parent.id) }) : Promise.resolve(0);
  }

  // ---------------------------------------------------------------- expand / collapse / select
  async function expand(id, { frame = false } = {}) {
    const node = tree.get(id);
    if (!node || node.expanded) return node;
    if (!node.childCount && !(node.children && node.children.length)) return node;
    node.pending = true;
    view.scene?.requestRender();
    if (frame && view.scene) flyInto(id);
    const links = loadLinks(id);
    try {
      await tree.loadChildren(id, signal);
    } catch (error) {
      node.pending = false;
      if (error.name === "AbortError" || view.destroyed) return node;
      showBanner(t("Alt katman getirilemedi: ") + error.message, () => expand(id));
      return node;
    }
    node.pending = false;
    if (view.destroyed) return node;
    node.expanded = true;
    rebuild();
    if (frame) { setFocus(id); view.scene?.focus(id); }
    if (view.fallback) renderFallbackTree();
    links.catch(() => null);
    return node;
  }

  function collapse(id) {
    const node = tree.get(id);
    if (!node || !node.expanded) return;
    node.expanded = false;
    if (view.selected && view.selected !== id && tree.has(view.selected) && tree.pathTo(view.selected).some((n) => n.id === id)) {
      select(id, { fly: false });
    }
    if (view.focus && tree.has(view.focus) && tree.pathTo(view.focus).some((n) => n.id === id)) view.focus = node.parent || tree.root.id;
    rebuild();
    if (view.fallback) renderFallbackTree();
  }

  function nodeFor(id) {
    return tree.get(id) || view.ghosts.get(id) || null;
  }

  function detailState(node) {
    return {
      node,
      path: tree.has(node.id) ? tree.pathTo(node.id) : [],
      attached: view.attached,
      edges: view.edgeData,
      onPick: (id) => pick(id),
      onClose: () => clearSelection(),
      children: tree.get(node.id)?.expanded ? childrenOf(tree.get(node.id)) : null,
      actions: options.actions ? options.actions(node, handle) : null,
      footer: options.footer ? options.footer(node) : null,
      memoryHref: options.memoryHref || ((id) => "/#/memory/" + encodeURIComponent("memory:" + id)),
      ruleHref: options.ruleHref || ((id) => "/#/rules/" + encodeURIComponent("rule:" + id)),
    };
  }

  function updateInset() {
    if (!view.scene) return;
    view.scene.setInset(!detail.hidden && host.clientWidth > 900 ? DETAIL_INSET : 0);
  }

  function paintDetail() {
    const node = view.selected ? nodeFor(view.selected) : null;
    if (!node) { detail.hidden = true; updateInset(); return; }
    renderDetail(detail, detailState(node));
    updateInset();
  }

  /**
   * Visible stand-in for an edge endpoint: itself, its visible class, its nearest open ancestor, the package of its
   * file, or null (becomes a ghost).
   */
  function visibleFor(id, nodeInfo) {
    // everything inside the selected node counts as the node itself: its edges read as one bundle
    const sel = tree.get(view.selected);
    if (sel?.fqn && nodeInfo?.fqn && nodeInfo.fqn.startsWith(sel.fqn + "#")) return sel.id;
    if (sel && tree.has(id) && tree.pathTo(id).some((n) => n.id === sel.id)) return sel.id;
    if (view.itemIndex.has(id) && !view.ghosts.has(id)) return id;
    if (tree.has(id)) {
      const chain = tree.pathTo(id);
      for (let k = chain.length - 1; k >= 0; k--) if (view.itemIndex.has(chain[k].id)) return chain[k].id;
    }
    const owner = String(nodeInfo?.fqn || "").split("#")[0];
    const dir = String(nodeInfo?.path || "").replace(/\/[^/]*$/, "");
    for (const it of view.items) {
      const n = tree.get(it.id);
      if (!n) continue;
      if (owner && n.kind === "class" && n.fqn === owner && (!nodeInfo.path || n.path === nodeInfo.path)) return it.id;
    }
    for (const it of view.items) if (it.kind === "package" && dir && tree.get(it.id)?.path === dir) return it.id;
    return null;
  }

  function applyEdges(data) {
    view.ghosts = new Map();
    const mapped = [];
    const seen = new Set();
    const infos = new Map((data?.nodes || []).map((n) => [n.id, n]));
    for (const e of data?.edges || []) {
      let s = visibleFor(e.source, infos.get(e.source));
      let t = visibleFor(e.target, infos.get(e.target));
      for (const [id, which] of [[e.source, "s"], [e.target, "t"]]) {
        if ((which === "s" ? s : t) !== null) continue;
        if (view.ghosts.size >= MAX_GHOSTS && !view.ghosts.has(id)) continue;
        const info = infos.get(id) || { id, name: id };
        view.ghosts.set(id, { id, kind: "ghost", realKind: info.kind, name: info.name || info.fqn || id, fqn: info.fqn || "", path: info.path || "", signature: "", childCount: 0, memoryCount: 0, ruleCount: 0 });
        if (which === "s") s = id; else t = id;
      }
      if (s === null || t === null || s === t) continue;
      const key = s + ">" + t;
      if (seen.has(key)) continue;
      seen.add(key);
      mapped.push({ source: s, target: t, edgeType: e.edgeType });
    }
    view.codeEdges = mapped;
  }

  /**
   * Selecting paints the detail card synchronously (so click → detail stays well under 100 ms), then
   * fetches attached records and code edges and repaints.
   */
  async function select(id, { fly = true } = {}) {
    const node = nodeFor(id);
    if (!node) throw new Error(t("Bilinmeyen düğüm: ") + id);
    const seq = ++view.selectSeq;
    const changed = view.selected !== id;
    view.selected = id;
    const hadGhosts = view.ghosts.size > 0;
    if (changed && !view.ghosts.has(id)) { view.ghosts = new Map(); view.codeEdges = []; }
    view.attached = undefined;
    view.edgeData = isSymbolId(id) ? undefined : null;
    if (hadGhosts && !view.ghosts.size) rebuild();
    else { view.scene?.setCodeEdges(view.codeEdges); highlight(); }
    paintDetail();
    if (view.clickStartedAt !== null) {
      const started = view.clickStartedAt;
      view.clickStartedAt = null;
      view.metrics.clickToDetailMs = performance.now() - started;
      requestAnimationFrame(() => {
        view.metrics.clickToPaintMs = performance.now() - started;
        (view.metrics.clickSamples ||= []).push(view.metrics.clickToPaintMs);
      });
    }
    options.onSelect?.(id);
    if (fly && view.scene) flyInto(id);
    const [attached, edges] = await Promise.allSettled([
      tree.attached(id, signal),
      isSymbolId(id) ? tree.edges(id, signal) : Promise.resolve(null),
    ]);
    if (seq !== view.selectSeq || view.destroyed) return;
    view.attached = attached.status === "fulfilled" ? attached.value || { memories: [], rules: [] } : attached.reason;
    view.edgeData = edges.status === "fulfilled" ? edges.value : edges.reason;
    if (edges.status === "fulfilled" && edges.value && !view.ghosts.has(id)) {
      applyEdges(edges.value);
      rebuild();
    }
    paintDetail();
  }

  function clearSelection() {
    view.selectSeq++;
    view.selected = null;
    view.ghosts = new Map();
    view.codeEdges = [];
    detail.hidden = true;
    updateInset();
    if (view.scene) rebuild();
    options.onSelect?.(null);
  }

  /** Click semantics: open a closed node (and dive into it), close the selected open node, otherwise select. */
  async function pick(id) {
    const node = tree.get(id);
    if (!node) return select(id);
    if (!node.expanded && node.childCount > 0) {
      const selecting = select(id, { fly: false });
      await expand(id, { frame: true });
      return selecting;
    }
    if (node.expanded && view.selected === id && node.depth > 0) {
      collapse(id);
      const parent = node.parent || tree.root.id;
      setFocus(parent);
      if (view.scene) {
        if (parent === tree.root.id) view.scene.resetView(); else view.scene.focus(parent, { distance: frameDistance(parent) });
      }
      return select(id, { fly: false });
    }
    return select(id);
  }

  /** Re-reads the badge counts of a node (and its siblings) from the server after an attach. */
  async function refresh(id) {
    const node = tree.get(id);
    if (!node) return;
    try {
      const siblings = await fetchCounts(node.parent || "");
      for (const s of siblings) {
        const known = tree.get(s.id);
        if (known) { known.memoryCount = s.memoryCount ?? known.memoryCount; known.ruleCount = s.ruleCount ?? known.ruleCount; }
      }
    } catch { /* counts stay; the detail still reloads below */ }
    rebuild();
    if (view.selected === id) await select(id, { fly: false });
    if (view.fallback) renderFallbackTree();
  }

  async function fetchCounts(parentId) {
    const out = [];
    let cursor = null;
    do {
      const q = new URLSearchParams({ project, parent: parentId });
      if (cursor) q.set("cursor", cursor);
      const page = await request("code-tree?" + q, { signal });
      if (!parentId && page.node) out.push(page.node);
      out.push(...(page.children || []));
      cursor = page.nextCursor || null;
    } while (cursor);
    return out;
  }

  // ---------------------------------------------------------------- labels
  /**
   * Horizontal labels just outside each mark, aligned away from the centre (right half: to the right, left half: to
   * the left, top and bottom: centred). At most LABEL_BUDGET are placed; overlapping ones are skipped.
   */
  function labelTick() {
    return () => {
      const screens = view.scene.projectAll();
      const items = view.items;
      const candidates = [];
      const used = new Set();
      const idx = (id) => view.itemIndex.get(id);
      const width = labelLayer.clientWidth;
      const height = labelLayer.clientHeight;
      const push = (i, className, opacity = 1) => {
        const it = items[i];
        const s = screens[i];
        if (!it || !s || used.has(it.id) || it.depth === 0 || candidates.length > 400) return;
        if (s.x < -60 || s.y < -30 || s.x > width + 60 || s.y > height + 30) return;
        used.add(it.id);
        const node = nodeFor(it.id);
        const gap = it.kind === "package" ? 12 : 9;
        const side = s.dx > 0.34 ? "start" : s.dx < -0.34 ? "end" : "center";
        const offsetX = side === "center" ? 0 : s.dx * gap;
        const offsetY = side === "center" ? s.dy * (gap + 9) : s.dy * gap;
        candidates.push({
          id: it.id, screen: s, text: trim(shortName(node), 34) + (node?.pending ? t(" · açılıyor…") : ""), className, opacity, offsetX, offsetY, align: side,
          memories: node?.memoryCount || 0, rules: node?.ruleCount || 0,
        });
      };
      const dimmedNow = (i) => !!view.dimmedSet?.has(items[i].id);
      if (view.selected) {
        push(idx(view.selected), "focus selected");
        if (tree.has(view.selected)) for (const n of tree.pathTo(view.selected).slice(1).reverse()) push(idx(n.id), "path");
      }
      if (view.hover) push(idx(view.hover), "focus");
      for (const e of view.codeEdges) { push(idx(e.source), "near"); push(idx(e.target), "near"); }
      // children of the open node in focus, then everything carrying memories or rules, then packages by size
      const focusId = view.focus && view.focus !== tree.root?.id ? view.focus : null;
      if (focusId) for (let i = 0; i < items.length; i++) if (items[i].parent === focusId) push(i, "near");
      for (let i = 0; i < items.length; i++) if ((items[i].memories || items[i].rules) && !dimmedNow(i)) push(i, "near");
      const packages = [];
      for (let i = 0; i < items.length; i++) if (items[i].kind === "package") packages.push(i);
      packages.sort((a, b) => (tree.get(items[b].id)?.childCount || 0) - (tree.get(items[a].id)?.childCount || 0));
      for (const i of packages) push(i, "pkg", dimmedNow(i) ? 0.55 : 1);
      for (let i = 0; i < items.length; i++) push(i, "near", dimmedNow(i) ? 0.55 : 1);
      const root = idx(tree.root.id);
      const rs = screens[root];
      if (rs) candidates.unshift({ screen: rs, text: tree.root.name, className: "plane", opacity: 1, offsetY: -rs.radius - 34, align: "center" });
      // labels never hide under the legend, the buttons, the trail or the hint
      const base = labelLayer.getBoundingClientRect();
      const blocked = [legend, tools, crumbs, hint].filter((e) => !e.hidden && e.offsetParent).map((e) => {
        const r = e.getBoundingClientRect();
        return { x: r.left - base.left - 4, y: r.top - base.top - 4, w: r.width + 8, h: r.height + 8 };
      });
      view.labels.update(candidates, LABEL_BUDGET, blocked);
    };
  }

  // ---------------------------------------------------------------- interaction
  const cleanups = [];
  function listen(target, type, fn, opts) {
    target.addEventListener(type, fn, opts);
    cleanups.push(() => target.removeEventListener(type, fn, opts));
  }

  /** Opens the path to a node (package, then class), selects it and brings it into view. */
  async function reveal(id, path = []) {
    for (const p of path) if (tree.has(p)) await expand(p);
    if (!nodeFor(id)) throw new Error(t("Düğüm haritada bulunamadı: ") + id);
    await select(id);
  }

  // ---------------------------------------------------------------- find (server-side search over the whole project)
  let findHits = [];
  let findActive = 0;
  let findTimer = 0;
  let findSeq = 0;
  function paintFind() {
    findList.replaceChildren(...findHits.map((h, k) => {
      const li = el("li", k === findActive ? "on" : null,
        el("i", "k-" + h.node.kind), el("span", "n", trim(h.node.name, 48)),
        h.node.memoryCount ? el("b", "bm", String(h.node.memoryCount)) : null,
        h.node.ruleCount ? el("b", "br", String(h.node.ruleCount)) : null,
        el("small", null, h.node.kind === "package" ? KIND_LABELS.package : shortName(tree.get(h.path[0]) || { name: "" }) || KIND_LABELS[h.node.kind]));
      li.setAttribute("role", "option");
      li.onmousedown = (e) => { e.preventDefault(); chooseFind(k); };
      return li;
    }));
    findList.hidden = !findHits.length && !findInput.value.trim();
    if (!findHits.length && findInput.value.trim()) findList.replaceChildren(el("li", "none", t("Sonuç yok")));
  }
  async function runFind() {
    const q = findInput.value.trim();
    const seq = ++findSeq;
    if (!q) { findHits = []; paintFind(); return; }
    try {
      const data = await request("code-tree/search?" + new URLSearchParams({ project, q, limit: "12" }), { signal });
      if (seq !== findSeq) return;
      findHits = data?.hits || [];
      findActive = 0;
      paintFind();
    } catch (error) {
      if (error.name !== "AbortError") showBanner(t("Arama yapılamadı: ") + error.message);
    }
  }
  function chooseFind(k) {
    const hit = findHits[k];
    if (!hit) return;
    findList.hidden = true;
    findInput.blur();
    reveal(hit.node.id, hit.path).catch((error) => showBanner(error.message));
  }

  /** The previous / next node on the same orbit as the selection (keyboard arrows). */
  function stepSibling(delta) {
    const sel = view.selected ? view.items[view.itemIndex.get(view.selected)] : null;
    const parent = sel ? sel.parent : tree.root?.id;
    const ring = view.items.filter((it) => it.parent === parent && it.kind !== "ghost").sort((a, b) => a.angle - b.angle);
    if (!ring.length) return;
    const at = sel ? ring.findIndex((it) => it.id === sel.id) : -1;
    const next = ring[(at + delta + ring.length) % ring.length];
    select(next.id, { fly: false }).catch(() => {});
    view.scene?.requestRender();
  }

  /** A node's mark or its label under the pointer. */
  function pickAt(clientX, clientY) {
    const rect = labelLayer.getBoundingClientRect();
    return view.labels?.hit(clientX - rect.left, clientY - rect.top) || view.scene.pick(clientX, clientY);
  }

  function wireInteraction() {
    let down = null;
    listen(canvas, "pointerdown", (e) => { down = { x: e.clientX, y: e.clientY }; view.scene.touch(); });
    listen(canvas, "pointerup", (e) => {
      if (!down || Math.hypot(e.clientX - down.x, e.clientY - down.y) > 5) return;
      down = null;
      const started = performance.now();
      const id = pickAt(e.clientX, e.clientY);
      // a miss keeps the selection (and the detail card), so the map never jumps under the pointer; Esc or × clears
      if (!id) return;
      view.clickStartedAt = started;
      pick(id).catch((error) => showBanner(error.message));
    });
    listen(canvas, "wheel", () => view.scene.touch(), { passive: true });
    let moves = 0;
    listen(canvas, "pointermove", (e) => {
      if (e.buttons || ++moves % 3) return;
      const id = pickAt(e.clientX, e.clientY);
      canvas.style.cursor = id ? "pointer" : "grab";
      if (id !== view.hover) {
        view.hover = id;
        view.scene.setHover(id);
      }
    });
    listen(canvas, "pointerleave", () => { if (view.hover) { view.hover = null; view.scene.setHover(null); } });
    resetButton.onclick = () => showAll();
    findInput.addEventListener("input", () => { clearTimeout(findTimer); findTimer = setTimeout(runFind, 140); });
    findInput.addEventListener("focus", () => { if (findInput.value.trim()) paintFind(); });
    findInput.addEventListener("blur", () => setTimeout(() => { findList.hidden = true; }, 120));
    findInput.addEventListener("keydown", (e) => {
      if (e.key === "ArrowDown" || e.key === "ArrowUp") {
        e.preventDefault();
        if (!findHits.length) return;
        findActive = (findActive + (e.key === "ArrowDown" ? 1 : -1) + findHits.length) % findHits.length;
        paintFind();
      } else if (e.key === "Enter") { e.preventDefault(); chooseFind(findActive); }
      else if (e.key === "Escape") { e.preventDefault(); findInput.value = ""; findHits = []; findList.hidden = true; findInput.blur(); }
    });
    cleanups.push(() => clearTimeout(findTimer));
    const paintMotion = () => {
      motionButton.setAttribute("aria-pressed", String(view.reducedMotion));
      motionButton.textContent = view.reducedMotion ? t("Hareket azaltıldı") : t("Hareket");
      motionButton.title = t("Kamera uçuşlarını ve akış animasyonunu aç / kapat");
    };
    paintMotion();
    motionButton.onclick = () => {
      view.reducedMotion = !view.reducedMotion;
      localStorage.setItem("space.motion", view.reducedMotion ? "reduced" : "full");
      view.scene.setReducedMotion(view.reducedMotion);
      paintMotion();
    };
    listen(document, "keydown", (e) => {
      if (!host.isConnected || e.defaultPrevented) return;
      const t = e.target;
      if (t instanceof HTMLInputElement || t instanceof HTMLTextAreaElement || t instanceof HTMLSelectElement || document.querySelector("dialog[open]")) return;
      if (e.metaKey || e.ctrlKey || e.altKey) return;
      // a focused button or link keeps its own Enter / arrow behaviour (× closes the card, a list row opens its node)
      const own = t instanceof Element && t.closest("button, a[href], [contenteditable], [role=option], summary");
      if (own && e.key !== "Escape") return;
      if (e.key === "Escape") {
        if (view.selected) clearSelection();
        focusOut();
      } else if (e.key === "f" || e.key === "F") showAll();
      else if (e.key === "/") { e.preventDefault(); findInput.focus(); }
      else if (e.key === "ArrowDown" || e.key === "ArrowRight") { e.preventDefault(); stepSibling(1); }
      else if (e.key === "ArrowUp" || e.key === "ArrowLeft") { e.preventDefault(); stepSibling(-1); }
      else if (e.key === "Enter" && view.selected) { e.preventDefault(); pick(view.selected).catch((error) => showBanner(error.message)); }
    });
    const ro = new ResizeObserver(() => updateInset());
    ro.observe(host);
    cleanups.push(() => ro.disconnect());
  }

  // ---------------------------------------------------------------- no-WebGL list
  function renderFallbackTree() {
    const list = el("ul", "tree");
    const draw = (node, into) => {
      const li = el("li");
      const caret = node.childCount ? (node.expanded ? "▾" : "▸") : "·";
      const b = el("button", "tree-node" + (view.selected === node.id ? " selected" : ""),
        el("span", "caret", caret), el("i", "k-" + node.kind), el("span", "name", node.name),
        node.memoryCount ? el("b", "bm", String(node.memoryCount)) : null,
        node.ruleCount ? el("b", "br", String(node.ruleCount)) : null,
        el("small", null, KIND_LABELS[node.kind] || ""));
      b.type = "button";
      b.dataset.id = node.id;
      b.onclick = async () => {
        if (node.expanded && node.depth > 0) collapse(node.id);
        else if (node.childCount) await expand(node.id);
        await select(node.id, { fly: false });
        renderFallbackTree();
      };
      li.append(b);
      if (node.expanded && node.children) {
        const ul = el("ul");
        for (const c of childrenOf(node)) draw(c, ul);
        li.append(ul);
      }
      into.append(li);
    };
    draw(tree.root, list);
    const old = fallbackBox.querySelector("ul.tree");
    if (old) old.replaceWith(list); else fallbackBox.append(list);
  }

  // ---------------------------------------------------------------- boot
  const handle = {
    get project() { return project; },
    tree, expand, collapse, select, pick, refresh, clearSelection,
    selectedId: () => view.selected,
    ready: null,
    destroy,
  };

  async function boot() {
    const started = performance.now();
    loading.hidden = false;
    try {
      await tree.loadRoot(signal);
    } catch (error) {
      if (error.name === "AbortError" || view.destroyed) return;
      loading.hidden = true;
      showBanner(error.message, () => boot());
      exposeHooks({ ready: true, error: error.message });
      return;
    }
    if (view.destroyed) return;
    view.focus = tree.root.id;
    sharedPrefixes = computePrefix(tree.root.children.map((id) => tree.get(id).name));
    view.metrics.loadMs = Math.round(performance.now() - started);
    if (!tree.root.children.length) {
      loading.hidden = true;
      empty.replaceChildren(el("h2", null, t("Bu projede kod haritası yok")),
        el("p", null, t("Proje henüz yapısal olarak indekslenmedi. Klasörü ekleyip indekslediğinde paketler, sınıflar ve metotlar burada katman katman görünür.")),
        options.emptyAction || "");
      empty.hidden = false;
      legend.hidden = true;
      exposeHooks({ ready: true, empty: true });
      return;
    }
    if (tree.root.truncated) showBanner(t("Proje çok büyük: ilk 5000 paket gösteriliyor."));
    if (!canvasAvailable() || options.forceList) {
      view.fallback = true;
      loading.hidden = true;
      host.classList.add("no-webgl");
      fallbackBox.replaceChildren(el("h2", null, t("Kod haritası liste olarak")),
        el("p", null, t("Harita bu tarayıcıda çizilemediği için kod yapısını ağaç listesi olarak gösteriyoruz. Bir düğüme tıklayınca alt katmanı açılır ve ayrıntısı sağda görünür.")));
      fallbackBox.hidden = false;
      rebuild();
      renderFallbackTree();
      view.metrics.firstRenderMs = Math.round(performance.now() - started);
      exposeHooks({ ready: true, fallback: true });
      return;
    }
    const { createGraphScene } = await import("./atlas.js");
    if (view.destroyed) return;
    view.scene = createGraphScene(canvas, { reducedMotion: view.reducedMotion });
    view.labels = createLabels(labelLayer);
    rebuild();
    view.scene.resetView(true);
    wireInteraction();
    view.scene.onFrame(labelTick());
    loading.hidden = true;
    view.metrics.firstRenderMs = Math.round(performance.now() - started);
    loadLinks(tree.root.id);
    exposeHooks({ ready: true, fallback: false });
    const initial = options.initialNode;
    if (initial && tree.has(initial)) select(initial).catch(() => {});
  }

  function exposeHooks(extra) {
    if (view.destroyed) return;
    window.__graph = {
      ...extra,
      project,
      metrics: view.metrics,
      expand: (id) => expand(id).then(() => true),
      collapse: (id) => collapse(id),
      select: (id) => select(id),
      pick: (id) => pick(id),
      reveal: (id, path) => reveal(id, path),
      clear: () => clearSelection(),
      selectedId: () => view.selected,
      focusId: () => view.focus,
      visibleIds: () => view.items.map((it) => it.id),
      node: (id) => {
        const n = nodeFor(id);
        if (!n) return null;
        const i = view.itemIndex.get(id);
        const it = i === undefined ? null : view.items[i];
        return { id: n.id, kind: n.kind, name: n.name, path: n.path, fqn: n.fqn, signature: n.signature, depth: n.depth, parent: n.parent,
          childCount: n.childCount, memoryCount: n.memoryCount, ruleCount: n.ruleCount, expanded: !!n.expanded, visible: !!it,
          pos: it ? [it.x, it.y, it.z] : null, radius: it ? it.radius : null };
      },
      children: (id) => (tree.get(id)?.children || []).slice(),
      pathIds: () => (view.selected && tree.has(view.selected) ? tree.pathTo(view.selected).map((n) => n.id) : []),
      codeEdges: () => view.codeEdges.slice(),
      links: () => linkList(),
      ghostIds: () => [...view.ghosts.keys()],
      stats: () => ({
        nodes: view.items.length,
        treeEdges: view.treeEdges.length,
        codeEdges: view.codeEdges.length,
        links: view.scene ? view.scene.state.links.length : 0,
        planes: view.depths,
        drawCalls: view.scene ? view.scene.drawCalls() : 0,
        labels: view.labels ? view.labels.placedCount() : 0,
      }),
      frameStats: () => view.scene?.frameStats() ?? null,
      resetFrameStats: () => view.scene?.resetFrameStats(),
      labelRects: () => (view.labels ? view.labels.rects().map((r) => ({ x: r.x, y: r.y, w: r.width, h: r.height })) : []),
      /** Viewport (client) coordinates of a node, for real mouse clicks in tests. */
      screenOf: (id) => {
        const p = view.scene?.screenOf(id);
        if (!p) return null;
        const rect = canvas.getBoundingClientRect();
        return { x: rect.left + p.x, y: rect.top + p.y, radius: p.radius, depth: p.depth, inside: p.x >= 0 && p.y >= 0 && p.x <= rect.width && p.y <= rect.height };
      },
      idle: () => !view.scene || !view.scene.animating(),
      resetView: () => showAll(),
      /** Flies the camera to a node (framed by its sphere); resolves when the flight ends. */
      focus: (id) => (view.scene ? flyInto(id) : Promise.resolve(0)),
      setBloom: (on) => view.scene?.setBloom(on),
      reducedMotion: () => view.reducedMotion,
      /** Opens every node at `depth` (0 = project); returns how many were opened. */
      expandAll: async (depth = 1, limit = Infinity) => {
        const targets = [...tree.all()].filter((n) => n.depth === depth && n.childCount > 0 && !n.expanded && view.itemIndex.has(n.id)).slice(0, limit);
        for (let k = 0; k < targets.length; k += 6) await Promise.all(targets.slice(k, k + 6).map((n) => tree.loadChildren(n.id, signal).catch(() => null)));
        for (const n of targets) if (n.children) n.expanded = true;
        rebuild();
        showAll();
        if (view.fallback) renderFallbackTree();
        if (depth <= 1) loadLinksMany(targets.map((n) => n.id));
        return targets.length;
      },
    };
  }

  function destroy() {
    if (view.destroyed) return;
    view.destroyed = true;
    controller.abort();
    cleanups.forEach((fn) => fn());
    view.scene?.destroy();
    host.replaceChildren();
    host.classList.remove("graph-view", "fullscreen", "no-webgl");
    if (window.__graph?.project === project) window.__graph = { ready: false, destroyed: true };
  }

  window.__graph = { ready: false };
  handle.ready = boot();
  return handle;
}
