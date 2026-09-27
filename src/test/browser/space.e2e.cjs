// Code atlas end-to-end checks and performance probe (full-screen /universe.html and the panel's Graph tab share
// the same modules under /graph/). The atlas draws with Canvas 2D; /universe.html?view=list is the tree-list view.
// Usage: node src/test/browser/space.e2e.cjs [baseUrl] [project] [outDir] [--headed] [--classes=N] [--fixture]
//   baseUrl   an ISOLATED backend (never the user's 18080 server), default http://127.0.0.1:18184
//   project   an already scanned project; use a large one (~7 500 symbols) for the performance numbers
//   --headed  run headed Chrome on the reference Mac: only these numbers count against the targets
//             (frame work p95 ≤ 16 ms — drawing + labels — while panning with all packages open, click → detail ≤ 100 ms)
//   --classes open up to N classes (member layer) for the probe, default 400
//   --fixture the base URL is space_scale_fixture.py: also exercises its /__fixture/down outage switch
// Headless frame times are recorded but not representative of a real screen.
// Pages open with the tr-TR locale, so the atlas speaks Turkish (panel/i18n.js) and the Turkish texts below match.
const path = require("path");
const fs = require("fs");
const { chromium } = require(path.resolve(__dirname, "../../../.benchmark/browser/node_modules/playwright"));

const positional = process.argv.slice(2).filter((a) => !a.startsWith("--"));
const [base = "http://127.0.0.1:18184", project = "PANEL_PETCLINIC", out = "output/panel-screens"] = positional;
const headed = process.argv.includes("--headed");
const fixture = process.argv.includes("--fixture");
const classLimit = Number((process.argv.find((a) => a.startsWith("--classes=")) || "--classes=400").split("=")[1]);
if (/:18080\b/.test(base)) { console.error("refusing to run against the user's 18080 server"); process.exit(3); }
fs.mkdirSync(out, { recursive: true });
const report = { base, project, headed, gpuRepresentative: headed, checks: [], metrics: {} };
const check = (name, ok, detail) => { report.checks.push({ name, ok: !!ok, detail }); console.log((ok ? "PASS " : "FAIL ") + name, detail === undefined ? "" : JSON.stringify(detail)); };
const overlaps = (rects) => {
  let n = 0;
  for (let i = 0; i < rects.length; i++) for (let j = i + 1; j < rects.length; j++) {
    const a = rects[i], b = rects[j];
    if (a.x < b.x + b.w - 0.5 && a.x + a.w > b.x + 0.5 && a.y < b.y + b.h - 0.5 && a.y + a.h > b.y + 0.5) n++;
  }
  return n;
};
const pct = (values, p) => {
  const sorted = values.slice().sort((a, b) => a - b);
  return sorted.length ? sorted[Math.min(sorted.length - 1, Math.floor(sorted.length * p))] : null;
};
const GL = ["--use-gl=angle", "--use-angle=swiftshader", "--enable-unsafe-swiftshader", "--ignore-gpu-blocklist"];
const url = `${base}/universe.html?project=${encodeURIComponent(project)}`;
const ready = (page) => page.waitForFunction("window.__graph && window.__graph.ready", null, { timeout: 60000 });

(async () => {
  if (fixture) await fetch(base + "/__fixture/up");
  const browser = await chromium.launch(headed ? { headless: false, channel: "chrome" } : { headless: true, args: GL });
  const page = await browser.newPage({ locale: "tr-TR", viewport: { width: 1440, height: 900 } });
  const errors = [];
  page.on("pageerror", (e) => errors.push(e.message));

  // 1. opening view: project + packages on two planes
  await page.goto(url);
  await ready(page);
  const first = await page.evaluate(() => ({
    metrics: window.__graph.metrics, fallback: window.__graph.fallback, stats: window.__graph.stats(),
    kinds: window.__graph.visibleIds().map((id) => window.__graph.node(id).kind),
  }));
  report.metrics.firstRender = first.metrics;
  check("atlas view (not the list fallback)", first.fallback === false, first.metrics);
  check("opening view shows the project and its packages on the inner orbit",
    first.kinds[0] === "project" && first.kinds.slice(1).every((k) => k === "package") && first.stats.planes === 2, { nodes: first.stats.nodes, planes: first.stats.planes });
  check("first render ≤ 3000 ms after data", first.metrics.firstRenderMs - first.metrics.loadMs <= 3000, first.metrics);
  await page.waitForTimeout(1000);
  await page.screenshot({ path: `${out}/graph-open-1440.png` });
  let rects = await page.evaluate(() => window.__graph.labelRects());
  check("opening labels do not overlap", overlaps(rects) === 0, { labels: rects.length, overlaps: overlaps(rects) });

  // 2. expand a package: its classes fan out on the next orbit
  const target = await page.evaluate(async () => {
    const g = window.__graph;
    const root = g.visibleIds()[0];
    for (const pkg of g.children(root)) {
      await g.expand(pkg);
      const cls = g.children(pkg).find((c) => g.node(c).kind === "class" && g.node(c).childCount > 0);
      if (cls) return { root, pkg, cls };
      g.collapse(pkg);
    }
    return null;
  });
  if (!target) throw new Error("project has no class with members: " + project);
  const geometry = await page.evaluate(({ pkg, cls }) => ({ pkg: window.__graph.node(pkg).pos, cls: window.__graph.node(cls).pos, planes: window.__graph.stats().planes }), target);
  const orbit = (p) => Math.hypot(p[0], p[1]);
  check("an opened package fans its classes out on the next orbit", orbit(geometry.cls) > orbit(geometry.pkg) + 50 && geometry.planes === 3, geometry);

  // 3. real click on the class: select + open members + highlighted path + detail
  await page.waitForFunction("window.__graph.idle()", null, { timeout: 10000 });
  await page.evaluate((id) => window.__graph.focus(id), target.cls);
  await page.waitForTimeout(300);
  const at = await page.evaluate((id) => window.__graph.screenOf(id), target.cls);
  if (at?.inside) await page.mouse.click(at.x, at.y); else await page.evaluate((id) => window.__graph.pick(id), target.cls);
  await page.waitForFunction((id) => document.querySelector(".graph-detail")?.dataset.node === id && window.__graph.node(id).expanded, target.cls, { timeout: 30000 });
  const pathIds = await page.evaluate(() => window.__graph.pathIds());
  check("click selects the class, opens its members and highlights the path from the root",
    [target.root, target.pkg, target.cls].every((id) => pathIds.includes(id)) && (await page.evaluate(() => window.__graph.stats().planes)) === 4, { realClick: !!at?.inside });
  await page.waitForSelector(".graph-detail .d-section", { timeout: 30000 });
  report.metrics.selectedCodeEdges = await page.evaluate(() => window.__graph.codeEdges().length);
  check("detail card shows attached memories/rules and code edges of the symbol",
    (await page.$$eval(".graph-detail .d-section h3", (n) => n.map((x) => x.textContent))).length >= 2, { codeEdges: report.metrics.selectedCodeEdges });
  await page.waitForTimeout(900);
  rects = await page.evaluate(() => window.__graph.labelRects());
  check("labels around the selection do not overlap", overlaps(rects) === 0, { labels: rects.length, overlaps: overlaps(rects) });
  await page.screenshot({ path: `${out}/graph-class-selected-1440.png` });

  // 4. a member's label text (away from its dot) is clickable; a miss keeps the selection; Esc and × clear it
  const member = await page.evaluate((cls) => window.__graph.children(cls)[0], target.cls);
  await page.waitForFunction("window.__graph.idle()", null, { timeout: 10000 });
  await page.waitForTimeout(300);
  const labelAt = await page.evaluate((id) => {
    const name = window.__graph.node(id).name;
    const span = [...document.querySelectorAll(".graph-labels span")].find((e) => e.style.opacity !== "0" && e.querySelector("em").textContent === name);
    if (!span) return null;
    const r = span.getBoundingClientRect();
    return { x: r.left + r.width * 0.7, y: r.top + r.height / 2 };
  }, member);
  if (labelAt) await page.mouse.click(labelAt.x, labelAt.y);
  await page.waitForTimeout(400);
  check("clicking a member's label text selects that member", !!labelAt && (await page.evaluate(() => window.__graph.selectedId())) === member, { labelAt });
  const emptyAt = await page.evaluate(() => { const c = document.querySelector(".graph-canvas").getBoundingClientRect(); return { x: c.left + 40, y: c.bottom - 90 }; });
  await page.mouse.click(emptyAt.x, emptyAt.y);
  await page.waitForTimeout(300);
  check("a click on empty space keeps the selection and the card",
    (await page.evaluate(() => window.__graph.selectedId())) === member && (await page.isVisible(".graph-detail")));
  await page.click(".graph-detail .d-close");
  check("× closes the card and clears the selection", (await page.evaluate(() => window.__graph.selectedId())) === null && !(await page.isVisible(".graph-detail")));
  await page.evaluate((id) => window.__graph.select(id), member);
  await page.keyboard.press("Escape");
  check("Esc clears the selection", (await page.evaluate(() => window.__graph.selectedId())) === null);

  // 5. find: a closed node anywhere in the project, opened and selected from the search box
  await page.evaluate((id) => window.__graph.collapse(id), target.pkg);
  const wanted = await page.evaluate((id) => window.__graph.node(id).name, target.cls);
  await page.keyboard.press("/");
  await page.keyboard.type(wanted);
  await page.waitForSelector(".find-list li:not(.none)", { timeout: 10000 });
  await page.keyboard.press("Enter");
  await page.waitForFunction((id) => window.__graph.selectedId() === id, target.cls, { timeout: 10000 }).catch(() => null);
  check("find opens the path to a closed class and selects it", (await page.evaluate(() => window.__graph.selectedId())) === target.cls, { wanted });
  // an open node lists all its children in the card, filterable
  await page.evaluate((id) => window.__graph.select(id), target.pkg);
  await page.waitForSelector(".graph-detail .d-children li", { timeout: 10000 });
  const listed = await page.$$eval(".graph-detail .d-children li", (n) => n.length);
  const kids = await page.evaluate((id) => window.__graph.children(id).length, target.pkg);
  check("the card lists every child of the open node", listed === kids, { listed, kids });
  // Enter on a focused button does what the button does: a card row opens its child, × closes the card
  const firstChild = await page.evaluate((id) => window.__graph.children(id)[0], target.pkg);
  await page.focus(".graph-detail .d-children li button");
  await page.keyboard.press("Enter");
  await page.waitForTimeout(400);
  check("Enter on a focused card row selects that child", (await page.evaluate(() => window.__graph.selectedId())) === firstChild);
  await page.focus(".graph-detail .d-close");
  await page.keyboard.press("Enter");
  await page.waitForTimeout(300);
  check("Enter on the focused × closes the card", (await page.evaluate(() => window.__graph.selectedId())) === null && !(await page.isVisible(".graph-detail")));
  await page.evaluate((id) => window.__graph.select(id), target.pkg);
  await page.evaluate(() => document.activeElement?.blur());
  // arrows walk the siblings on the same orbit
  const beforeArrow = await page.evaluate(() => window.__graph.selectedId());
  await page.keyboard.press("ArrowDown");
  const afterArrow = await page.evaluate(() => window.__graph.selectedId());
  check("↓ selects the next node on the same orbit", afterArrow && afterArrow !== beforeArrow
    && (await page.evaluate(([a, b]) => window.__graph.node(a).parent === window.__graph.node(b).parent, [afterArrow, beforeArrow])));

  // 6. second click on the selected open node closes it
  await page.evaluate((id) => window.__graph.select(id), target.cls);
  if (!(await page.evaluate((id) => window.__graph.node(id).expanded, target.cls))) await page.evaluate((id) => window.__graph.expand(id), target.cls);
  await page.evaluate((id) => window.__graph.pick(id), target.cls);
  check("second click on the selected node closes its layer", !(await page.evaluate((id) => window.__graph.node(id).expanded, target.cls)));

  // 7. performance probe: every package open (+ up to N classes), pan, then real clicks
  await page.evaluate(() => window.__graph.clear());
  const opened = await page.evaluate(async (limit) => ({ packages: await window.__graph.expandAll(1), classes: await window.__graph.expandAll(2, limit) }), classLimit);
  await page.waitForFunction("window.__graph.idle()", null, { timeout: 20000 });
  await page.waitForTimeout(800);
  const probeStats = await page.evaluate(() => window.__graph.stats());
  report.metrics.probe = { opened, ...probeStats };
  await page.screenshot({ path: `${out}/graph-all-packages-1440.png` });
  await page.evaluate(() => window.__graph.resetFrameStats());
  await page.mouse.move(720, 450);
  await page.mouse.down();
  for (let k = 0; k < 90; k++) { await page.mouse.move(720 + Math.sin(k / 9) * 260, 450 + Math.sin(k / 6) * 50); await page.waitForTimeout(33); }
  await page.mouse.up();
  const frames = await page.evaluate(() => window.__graph.frameStats());
  report.metrics.frames = frames;
  const clickSamples = [];
  const candidates = await page.evaluate(() => window.__graph.visibleIds().filter((id) => {
    const s = window.__graph.screenOf(id);
    return s && s.inside && s.radius > 3 && window.__graph.node(id).kind !== "project";
  }));
  for (let k = 0; k < Math.min(12, candidates.length); k++) {
    const id = candidates[Math.floor((k * candidates.length) / 12)];
    await page.waitForFunction("window.__graph.idle()", null, { timeout: 10000 }).catch(() => null);
    const s = await page.evaluate((i) => window.__graph.screenOf(i), id);
    if (!s?.inside) continue;
    const before = await page.evaluate(() => (window.__graph.metrics.clickSamples || []).length);
    const started = Date.now();
    await page.mouse.click(s.x, s.y);
    await page.waitForFunction((n) => (window.__graph.metrics.clickSamples || []).length > n, before, { timeout: 5000 }).catch(() => null);
    const m = await page.evaluate(() => ({ dom: window.__graph.metrics.clickToDetailMs, paint: window.__graph.metrics.clickToPaintMs }));
    clickSamples.push({ ...m, wallMs: Date.now() - started });
    await page.keyboard.press("Escape");
  }
  const paint = clickSamples.map((c) => c.paint).filter((v) => typeof v === "number");
  const dom = clickSamples.map((c) => c.dom).filter((v) => typeof v === "number");
  report.metrics.click = { samples: clickSamples.length, detailDomP50: pct(dom, 0.5), detailDomP95: pct(dom, 0.95), nextFrameP50: pct(paint, 0.5), nextFrameP95: pct(paint, 0.95) };
  console.log(`PERF (${headed ? "headed" : "headless — informational"}):`, JSON.stringify({ nodes: probeStats.nodes, drawCalls: probeStats.drawCalls, frames, click: report.metrics.click }));
  if (headed) {
    check("frame work p95 ≤ 16 ms (drawing + labels) while panning with all packages open (headed)", frames && frames.p95 <= 16, frames);
    check("click → detail on screen ≤ 100 ms (headed, next painted frame)", report.metrics.click.nextFrameP95 !== null && report.metrics.click.nextFrameP95 <= 100, report.metrics.click);
  } else {
    check("frame and click numbers recorded (headless, informational only)", !!frames && paint.length > 0, { frames, click: report.metrics.click });
    check("click → detail card DOM ≤ 100 ms (renderer-independent part)", report.metrics.click.detailDomP95 !== null && report.metrics.click.detailDomP95 <= 100, report.metrics.click);
  }
  await page.screenshot({ path: `${out}/graph-pan-1440.png` });

  // 8. reduced motion: no camera flights
  const reduced = await browser.newPage({ locale: "tr-TR", viewport: { width: 1440, height: 900 }, reducedMotion: "reduce" });
  await reduced.goto(url);
  await ready(reduced);
  const pkg = await reduced.evaluate(() => window.__graph.visibleIds()[1]);
  await reduced.evaluate((id) => window.__graph.pick(id), pkg);
  check("reduced motion: instant camera and layout (idle right after a pick)", await reduced.evaluate(() => window.__graph.reducedMotion() && window.__graph.idle()));
  await reduced.close();

  // 9. the panel's Graph tab mounts the same view
  const panel = await browser.newPage({ locale: "tr-TR", viewport: { width: 1440, height: 900 } });
  await panel.goto(base + "/");
  await panel.evaluate((p) => { localStorage.setItem("atlas.project", p); localStorage.setItem("atlas.project.chosen", "1"); }, project);
  await panel.goto(base + "/#/graph");
  await panel.reload();
  await ready(panel);
  check("panel Graph tab renders the same atlas", (await panel.evaluate(() => window.__graph.project)) === project && (await panel.isVisible(".graph-view canvas")));
  await panel.close();

  // 10. 1920x1080
  const wide = await browser.newPage({ locale: "tr-TR", viewport: { width: 1920, height: 1080 } });
  await wide.goto(url);
  await ready(wide);
  await wide.evaluate(() => window.__graph.expandAll(1));
  await wide.waitForTimeout(1500);
  await wide.screenshot({ path: `${out}/graph-all-packages-1920.png` });
  await wide.close();

  // 11. backend down (fixture only)
  if (fixture) {
    await fetch(base + "/__fixture/down");
    await page.goto(url);
    await page.waitForSelector(".graph-banner:not([hidden]), .page-message", { timeout: 30000 });
    check("backend down shows a message with retry", /Tekrar dene/.test(await page.textContent("body")));
    await fetch(base + "/__fixture/up");
  }
  await browser.close();

  // 12. no GPU / WebGL: the atlas still draws (Canvas 2D); ?view=list is the tree-list view
  const plain = await chromium.launch({ headless: true, args: ["--disable-gpu", "--disable-software-rasterizer", "--disable-webgl", "--disable-webgl2"] });
  const p1 = await plain.newPage({ locale: "tr-TR", viewport: { width: 1440, height: 900 } });
  await p1.goto(url);
  await ready(p1);
  check("without WebGL the atlas still renders", await p1.evaluate(() => window.__graph.fallback === false));
  const p2 = await plain.newPage({ locale: "tr-TR", viewport: { width: 1440, height: 900 } });
  await p2.goto(url + "&view=list");
  await ready(p2);
  check("?view=list shows the tree list", await p2.evaluate(() => window.__graph.fallback === true));
  const beforeRows = await p2.$$eval(".graph-fallback .tree-node", (n) => n.length);
  await p2.click(".graph-fallback .tree-node >> nth=1");
  await p2.waitForSelector(".graph-detail:not([hidden])");
  check("fallback: clicking a package opens its children and the detail card", (await p2.$$eval(".graph-fallback .tree-node", (n) => n.length)) > beforeRows);
  await p2.screenshot({ path: `${out}/graph-list-1440.png` });
  await plain.close();

  check("no page errors", errors.length === 0, errors.slice(0, 5));
  fs.writeFileSync(`${out}/graph-e2e-report${headed ? "-headed" : ""}.json`, JSON.stringify(report, null, 2));
  process.exit(report.checks.every((c) => c.ok) ? 0 : 1);
})().catch((e) => { console.error(e); process.exit(2); });
