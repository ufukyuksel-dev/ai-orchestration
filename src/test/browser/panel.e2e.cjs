// Panel end-to-end checks against a REAL isolated backend (never the user's 18080 server/data).
// Usage: node src/test/browser/panel.e2e.cjs <baseUrl> <projectA> <projectB> [outDir] [codeProject] [--scan-root=/abs/path]
//   codeProject  an already scanned project of the isolated backend used for the Graph tab (default PANEL_PETCLINIC)
//   --scan-root  optional: a small source folder; the onboarding "Proje ekle" flow then really scans it
// Every record it creates carries the prefix "PANEL-E2E" and lives in the throw-away test database.
// The flows run with the tr-TR locale (the panel then speaks Turkish, panel/i18n.js), so the Turkish selectors match;
// one check at the end opens the panel with en-US and verifies it is English.
const path = require("path");
const fs = require("fs");
const { chromium } = require(path.resolve(__dirname, "../../../.benchmark/browser/node_modules/playwright"));

const positional = process.argv.slice(2).filter((a) => !a.startsWith("--"));
const [base = "http://127.0.0.1:18184", projectA = "PANEL_E2E_A", projectB = "PANEL_E2E_B", out = "output/panel-screens", codeProject = "PANEL_PETCLINIC"] = positional;
const scanRoot = (process.argv.find((a) => a.startsWith("--scan-root=")) || "").slice("--scan-root=".length);
if (/:18080\b/.test(base)) { console.error("refusing to run against the user's 18080 server"); process.exit(3); }
fs.mkdirSync(out, { recursive: true });
const report = { base, checks: [] };
const check = (name, ok, detail) => { report.checks.push({ name, ok: !!ok, detail }); console.log((ok ? "PASS " : "FAIL ") + name, detail === undefined ? "" : JSON.stringify(detail)); };
const run = Date.now().toString(36);
// The write gate rejects paraphrases of existing facts, so each run needs genuinely new facts.
const topics = ["shipment labels", "loyalty points", "tax exports", "refund windows", "audit logs", "API tokens", "night batches", "PDF invoices"];
const pick = (k) => topics[(parseInt(run.slice(-4), 36) + k) % topics.length];
const num = (k) => (parseInt(run.slice(-3), 36) % 90) + 10 + k;

// DOM append() turns null into the text "null"; no page may show it.
async function strayNull(page) {
  return page.evaluate(() => {
    const main = document.querySelector("main");
    const direct = [...(main?.childNodes || [])].filter((n) => n.nodeType === 3 && n.textContent.trim()).map((n) => n.textContent.trim());
    return { direct, visible: /(^|\s)null(\s|$)/.test(main?.innerText || "") };
  });
}

/** Minimal MCP client (Streamable HTTP): initialize, then one tools/call; returns the tool's JSON result. */
async function mcpCall(tool, args) {
  const headers = { "Content-Type": "application/json", Accept: "application/json, text/event-stream",
    "X-AI-Orch-Client": "claude-code" };
  const post = async (body, session) => {
    const r = await fetch(base + "/mcp", { method: "POST", headers: { ...headers, ...(session ? { "Mcp-Session-Id": session } : {}) },
      body: JSON.stringify(body) });
    return { text: await r.text(), session: r.headers.get("mcp-session-id") };
  };
  const init = await post({ jsonrpc: "2.0", id: 1, method: "initialize",
    params: { protocolVersion: "2025-06-18", capabilities: {}, clientInfo: { name: "panel-e2e", version: "1" } } });
  await post({ jsonrpc: "2.0", method: "notifications/initialized" }, init.session);
  const res = await post({ jsonrpc: "2.0", id: 2, method: "tools/call", params: { name: tool, arguments: args } }, init.session);
  for (let line of res.text.split("\n")) {
    line = line.startsWith("data:") ? line.slice(5).trim() : line.trim();
    if (line.startsWith("{")) return JSON.parse(JSON.parse(line).result.content[0].text);
  }
  throw new Error("no MCP result for " + tool + ": " + res.text.slice(0, 300));
}

async function api(page, p, body) {
  return page.evaluate(async ([p, body]) => {
    const r = await fetch("/workspace/api/" + p, {
      method: body ? "POST" : "GET",
      headers: { "X-Workspace-Request": "1", ...(body ? { "Content-Type": "application/json" } : {}) },
      body: body ? JSON.stringify(body) : undefined,
    });
    let data = null;
    try { data = await r.json(); } catch {}
    return { status: r.status, data };
  }, [p, body]);
}

(async () => {
  const browser = await chromium.launch({ headless: true });
  const page = await browser.newPage({ locale: "tr-TR", viewport: { width: 1440, height: 900 } });
  const errors = [];
  page.on("pageerror", (e) => errors.push(e.message));
  // ---- the panel is served at / (forward, not redirect) and still at /workspace/ ----
  // A fresh database has no project yet (the panel then shows only "Proje ekle"): index the code project once
  // through the same scanner endpoint the onboarding card calls; the onboarding UI itself is exercised below.
  const known = (await (await fetch(base + "/workspace/api/projects", { headers: { "X-Workspace-Request": "1" } })).json()).projects || [];
  const seed = async (rootPath, projectKey) => {
    const r = await fetch(base + "/api/scanner/scan", { method: "POST", headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ rootPath, projectKey, force: false, semanticEnabled: false }) });
    if (!r.ok) throw new Error("seed scan failed: " + r.status + " " + (await r.text()).slice(0, 300));
  };
  if (!known.includes(codeProject)) {
    if (!scanRoot) throw new Error("empty database: pass --scan-root=/abs/path so a project can be added first");
    await seed(scanRoot, codeProject);
  }
  if (!known.includes(codeProject)) {
    // an agent's learned card with a symbol and a file locator, written through the real MCP memory.learn path
    const learned = await mcpCall("memory.learn", { rootPath: scanRoot, candidates: [{
      kind: "navigation", summary: "Where the application starts",
      content: "PetClinicApplication#main boots Spring; runtime hints live in PetClinicRuntimeHints.",
      locators: [
        { kind: "symbol", ref: "org.springframework.samples.petclinic.PetClinicApplication#main",
          path: "src/main/java/org/springframework/samples/petclinic/PetClinicApplication.java" },
        { kind: "file", ref: "src/main/java/org/springframework/samples/petclinic/PetClinicRuntimeHints.java" }] }] });
    if (!learned || learned.status === "rejected") throw new Error("seed memory.learn failed: " + JSON.stringify(learned));
  }
  const jobs0 = await (await fetch(base + "/workspace/api/jobs", { headers: { "X-Workspace-Request": "1" } })).json();
  if (!(jobs0.jobs || []).length) {
    await mcpCall("job_memory.save", { title: "Panel e2e saved job", summary: "throw-away job for the Jobs tab e2e",
      content: "Goal: exercise the Jobs tab. Pending: nothing." });
  }
  for (const key of [projectA, projectB].filter((k) => !known.includes(k))) {
    // the two throw-away memory/rule projects only need to exist: a one-file folder each
    const dir = require("os").tmpdir() + "/" + key.toLowerCase();
    fs.mkdirSync(dir, { recursive: true });
    fs.writeFileSync(dir + "/README.md", "# " + key + "\n");
    await seed(fs.realpathSync(dir), key);
  }
  await page.goto(base + "/");
  await page.waitForSelector("nav.rail .rail-link[data-route=graph]");
  check("/ serves the panel (Graph and Jobs in the menu, no Explorer)",
    new URL(page.url()).pathname === "/" && !!(await page.$(".rail-link[data-route=jobs]")) && !(await page.$(".rail-link[data-route=explorer]")));
  const legacy = await browser.newPage({ locale: "tr-TR" });
  await legacy.goto(base + "/workspace/");
  check("/workspace/ still serves the panel", !!(await legacy.waitForSelector("nav.rail", { timeout: 15000 }).catch(() => null)));
  await legacy.close();
  // Repeatable runs: remove this test's own leftovers from the throw-away PANEL_E2E_* projects only.
  if (!/^PANEL_E2E_/.test(projectA) || !/^PANEL_E2E_/.test(projectB)) throw new Error("test projects must start with PANEL_E2E_");
  for (const project of [projectA, projectB]) {
    const left = await api(page, "items?kind=memory&limit=100&project=" + project);
    for (const item of (left.data?.items || []).filter((i) => i.project === project)) {
      await api(page, "memory/" + item.id.split(":")[1] + "/delete", { mode: "hard_delete", reason: "panel e2e reset", humanConfirmed: true, humanRawText: "panel e2e reset of throw-away test project" });
    }
  }
  await page.evaluate((p) => { localStorage.setItem("atlas.project", p); localStorage.setItem("atlas.project.chosen", "1"); }, projectA);
  await page.goto(base + "/#/overview");
  await page.reload();
  await page.waitForSelector(".strata-wrap h2", { timeout: 30000 });
  check("overview renders for the selected project", (await page.inputValue("#project")) === projectA);
  const emptyOverview = await strayNull(page);
  check("overview without pending records shows no callout and no stray null",
    !(await page.$("main .callout")) && emptyOverview.direct.length === 0 && !emptyOverview.visible, emptyOverview);
  await page.screenshot({ path: `${out}/panel-overview-1440.png` });

  // ---- create memory through the UI (and guard against double submit) ----
  const posts = [];
  page.on("request", (r) => { if (r.method() === "POST" && r.url().endsWith("/workspace/api/memory")) posts.push(r.url()); });
  await page.goto(base + "/#/memory/new");
  await page.waitForSelector("#modal[open]");
  const summaryX = `Warehouse ${pick(0)} retention is ${num(0)} days (clause ${run})`;
  await page.fill("#modal input[name=summary]", summaryX);
  await page.fill("#modal textarea[name=content]", `Warehouse ${pick(0)} must be retained for exactly ${num(0)} days, as required by logistics contract clause ${run}.`);
  await page.fill("#modal input[name=tags]", "panel-e2e");
  await page.check("#modal input[name=scope][value=project]");
  await page.screenshot({ path: `${out}/panel-memory-create-1440.png` });
  await page.dblclick("#modal button[type=submit]");
  await page.waitForFunction(() => decodeURIComponent(location.hash).startsWith("#/memory/memory:") || document.querySelector("#modal .form-error:not([hidden])"), null, { timeout: 180000 });
  const createError = await page.$eval("#modal .form-error", (n) => (n.hidden ? null : n.textContent)).catch(() => null);
  check("double-click on save sends exactly one POST", posts.length === 1, { posts: posts.length });
  const idX = decodeURIComponent((await page.evaluate(() => location.hash)).split("/")[2] || "").split("?")[0].replace("memory:", "");
  check("memory created through the gated write", !createError && /^[0-9a-f-]{36}$/.test(idX), { createError, idX });
  if (!idX) throw new Error("cannot continue without the created memory: " + createError);
  await page.waitForSelector(".detail h2");
  await page.screenshot({ path: `${out}/panel-memory-detail-1440.png` });

  // ---- edit + read-back ----
  await page.click(".detail .actions button:has-text('Düzenle')");
  await page.fill("#modal textarea[name=text]", `Warehouse ${pick(0)} must be retained for exactly ${num(0)} days under logistics contract clause ${run} (edited).`);
  await page.fill("#modal textarea[name=reason]", "e2e edit");
  await page.click("#modal button[type=submit]");
  await page.waitForFunction(() => document.querySelector(".detail .content")?.textContent.includes("(edited)"), null, { timeout: 30000 });
  const readBack = await api(page, "items/memory/" + idX);
  check("edit is read back from the server", readBack.data?.text?.includes("(edited)"), { status: readBack.status });

  // ---- archive A ----
  const a = await api(page, "memory", { summary: `Release trains for ${pick(3)} need ${num(3)} reviewer approvals`, content: `Every release train touching ${pick(3)} requires ${num(3)} independent reviewer approvals before deployment (ref ${run}a).`, memoryType: "decision", tags: ["panel-e2e"], project: projectA });
  const idA = a.data?.memoryId;
  check("seed A created", !!idA, a.data);
  await page.goto(base + `/#/memory/memory:${idA}?status=all`);
  await page.waitForSelector(".detail h2");
  await page.click(".detail .actions button:has-text('Sil')");
  await page.fill("#modal textarea[name=reason]", "e2e archive");
  await page.screenshot({ path: `${out}/panel-delete-dialog-1440.png` });
  await page.click("#modal button[type=submit]");
  await page.waitForTimeout(1200);
  const archived = await api(page, "items/memory/" + idA);
  check("archive keeps the record with ARCHIVED status", archived.status === 200 && String(archived.data?.status).toLowerCase() === "archived", { status: archived.status, value: archived.data?.status });

  // ---- hard delete B (typed confirmation) ----
  const b = await api(page, "memory", { summary: `Staging ${pick(5)} gateway listens on port ${num(50)}${num(5)}`, content: `In the staging cluster the ${pick(5)} gateway container listens on TCP port ${num(50)}${num(5)} (ref ${run}b).`, memoryType: "decision", tags: ["panel-e2e"], project: projectA });
  const idB = b.data?.memoryId;
  await page.goto(base + `/#/memory/memory:${idB}?status=all`);
  await page.waitForSelector(".detail h2");
  await page.click(".detail .actions button:has-text('Sil')");
  await page.check("#modal input[name=mode][value=hard_delete]");
  await page.fill("#modal textarea[name=reason]", "e2e hard delete");
  await page.fill("#modal input[name=confirm]", "wrong");
  await page.click("#modal button[type=submit]");
  const refused = await page.textContent("#modal .form-error");
  check("hard delete refuses a wrong typed confirmation", refused.includes(idB.slice(0, 8)), { refused });
  await page.fill("#modal input[name=confirm]", idB.slice(0, 8));
  await page.click("#modal button[type=submit]");
  await page.waitForTimeout(1200);
  const gone = await api(page, "items/memory/" + idB);
  check("hard delete removes the record (404 on read-back)", gone.status === 404, { status: gone.status });

  // ---- rule wizard ----
  await page.goto(base + "/#/rules/new");
  await page.waitForSelector("textarea[name=statement]");
  await page.check("input[name=scope][value=project]");
  const statement = `PANEL-E2E ${run}: Use constructor injection in new services.`;
  await page.fill("textarea[name=statement]", statement);
  await page.click("form.section button[type=submit]");
  await page.waitForSelector("pre.card-preview", { timeout: 60000 });
  await page.screenshot({ path: `${out}/panel-rule-card-1440.png` });
  await page.fill("textarea[name=approval]", "Bu kuralı onaylıyorum (panel e2e).");
  await page.click("form.section button[type=submit]:has-text('Kuralı etkinleştir')");
  await page.waitForFunction(() => decodeURIComponent(location.hash).startsWith("#/rules/rule:"), null, { timeout: 60000 });
  const effective = await api(page, "rules/effective?project=" + projectA);
  check("promoted rule appears in effective instructions", JSON.stringify(effective.data || {}).includes(statement), { status: effective.status });
  await page.waitForSelector(".detail .content");
  await page.screenshot({ path: `${out}/panel-rules-1440.png` });

  // ---- references: mkdir, create, stale-hash conflict ----
  const dir = `panel-e2e-${run}`;
  await page.goto(base + "/#/references");
  await page.click("button:has-text('Yeni klasör')");
  await page.fill("#modal input[name=name]", dir);
  await page.click("#modal button[type=submit]");
  await page.waitForFunction((d) => location.hash.includes(encodeURIComponent(d)) || location.hash.includes(d), dir);
  await page.waitForSelector(".ref-layout .rows, .ref-layout .empty");
  check("reference folder listing loads without an error", !(await page.$(".ref-layout .error-box")));
  await page.click("button:has-text('Yeni dosya')");
  await page.fill("#modal input[name=path]", `${dir}/procedure.md`);
  await page.fill("#modal textarea[name=content]", "# Procedure\n\n1. first step\n");
  await page.click("#modal button[type=submit]");
  await page.waitForSelector(".ref-viewer pre");
  await page.click(".ref-viewer button:has-text('Düzenle')");
  const current = await api(page, "references/read?path=" + encodeURIComponent(`${dir}/procedure.md`));
  await api(page, "references/write", { relativePath: `${dir}/procedure.md`, content: "# Procedure\n\nchanged elsewhere\n", expectedHash: current.data.item.hash });
  await page.fill("#modal textarea[name=content]", "# Procedure\n\nmy edit\n");
  await page.click("#modal button[type=submit]");
  await page.waitForSelector("#modal .form-error:not([hidden])");
  const conflict = await page.textContent("#modal .form-error");
  check("stale reference edit reports a conflict instead of overwriting", conflict.includes("değişmiş"), { conflict });
  await page.keyboard.press("Escape");
  await page.goto(base + "/#/references?" + new URLSearchParams({ path: dir, file: `${dir}/procedure.md` }));
  await page.waitForSelector(".ref-layout .rows .row");
  check("reference tree lists the created file", (await page.$$eval(".ref-layout .rows .row .t", (n) => n.map((x) => x.textContent))).includes("procedure.md"));
  await page.screenshot({ path: `${out}/panel-references-1440.png` });

  // ---- project-switch race: slow project A answer must not paint over project B ----
  await page.goto(base + "/#/memory?status=all");
  await page.waitForSelector(".rows, .empty");
  await page.route(/\/workspace\/api\/items\?.*kind=memory.*project=/, async (route) => {
    if (route.request().url().includes("project=" + projectA)) await new Promise((r) => setTimeout(r, 2500));
    await route.continue();
  });
  await page.selectOption("#project", projectA);
  await page.waitForTimeout(150);
  await page.selectOption("#project", projectB);
  await page.waitForTimeout(3500);
  const shown = await page.$$eval(".rows .row .s", (nodes) => nodes.map((n) => n.textContent));
  check("stale project A response never paints into project B", shown.every((text) => !text.includes(projectA)), { rows: shown.length });
  await page.unroute(/.*/);

  // ---- pending: 27 real PENDING_REVIEW records, paging, approve and reject with read-back ----
  await page.selectOption("#project", projectA);
  await page.waitForTimeout(300);
  const pendingIds = [];
  for (let k = 0; k < 27; k++) {
    const created = await page.evaluate(async ([project, k, run]) => {
      const r = await fetch("/api/memory", { method: "POST", headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ scope: "PROJECT", projectKey: project, memoryType: "DECISION",
          summary: `Pending proposal ${k} for queue ${run}`, text: `Proposal ${k}: queue ${run} item awaiting a human decision.`,
          tags: ["panel-e2e"], confidence: 0.6, status: "PENDING_REVIEW", sourceType: "MCP_EXTERNAL", sourceRef: "panel-e2e" }) });
      return (await r.json()).id;
    }, [projectA, k, run]);
    pendingIds.push(created);
  }
  await page.goto(base + "/#/pending");
  await page.waitForSelector("section.rule");
  const firstPage = await page.$$eval("section.rule", (n) => n.length);
  await page.click(".more button");
  await page.waitForFunction(() => document.querySelectorAll("section.rule").length >= 27);
  check("pending pages 25 then loads the rest", firstPage === 25 && (await page.$$eval("section.rule", (n) => n.length)) === 27, { firstPage });
  await page.screenshot({ path: `${out}/panel-pending-1440.png` });
  const approveTarget = await page.$eval("section.rule strong", (n) => n.textContent);
  await page.click("section.rule >> nth=0 >> button:has-text('Onayla')");
  await page.fill("#modal textarea[name=note]", "e2e approve");
  await page.click("#modal button[type=submit]");
  await page.waitForTimeout(1200);
  await page.click("section.rule >> nth=0 >> button:has-text('Reddet')");
  await page.click("#modal button[type=submit]");
  await page.waitForTimeout(1200);
  const statuses = await page.evaluate(async (project) => {
    const r = await fetch("/workspace/api/items?kind=memory&limit=100&project=" + project, { headers: { "X-Workspace-Request": "1" } });
    return (await r.json()).items.filter((i) => i.title.startsWith("Pending proposal")).map((i) => [i.title, i.status]);
  }, projectA);
  const approved = statuses.find(([title]) => title === approveTarget);
  check("approve turns the pending record active (read-back)", approved && approved[1] === "active", { approved });
  check("reject moves exactly one other pending record out of the queue",
    statuses.filter(([, st]) => st === "pending_review").length === 25, { pending: statuses.filter(([, st]) => st === "pending_review").length });

  // ---- filters, history, supersede ----
  await page.goto(base + "/#/memory?status=all&tag=panel-e2e");
  await page.waitForSelector(".rows .row, .empty");
  const tagRows = await page.$$eval(".rows .row .t", (n) => n.map((x) => x.textContent));
  await page.goto(base + "/#/overview");
  await page.waitForSelector(".strata-wrap h2", { timeout: 30000 });
  await page.waitForSelector("main .callout", { timeout: 10000 }).catch(() => null);
  const busyOverview = await strayNull(page);
  check("overview with pending records shows the review callout and no stray null",
    !!(await page.$("main .callout")) && busyOverview.direct.length === 0 && !busyOverview.visible, busyOverview);
  check("tag filter lists only tagged records", tagRows.length > 0 && tagRows.every((t) => t.startsWith("Warehouse") || t.startsWith("Release") || t.startsWith("Pending") || t.startsWith("Staging")), { rows: tagRows.length });
  await page.goto(base + `/#/memory/memory:${idX}?status=all`);
  await page.waitForSelector(".detail h4");
  const history = await page.$$eval(".detail .links", (n) => n.map((x) => x.textContent).join(" "));
  check("detail shows the event history (created + edited)", history.includes("Oluşturuldu") && history.includes("Düzenlendi"));
  // Supersede is under test, not the gate: seed C and D directly as active records in the throw-away DB.
  const seedActive = (summary, text) => page.evaluate(async ([project, summary, text]) => {
    const r = await fetch("/api/memory", { method: "POST", headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ scope: "PROJECT", projectKey: project, memoryType: "DECISION", summary, text,
        tags: ["panel-e2e"], confidence: 0.9, status: "ACTIVE", sourceType: "MANUAL", sourceRef: "panel-e2e" }) });
    return { data: { memoryId: (await r.json()).id } };
  }, [projectA, summary, text]);
  const c = await seedActive(`Nightly batch window ${run} is 40 minutes`, `The nightly batch window (${run}) lasts 40 minutes.`);
  const d = await seedActive(`Nightly batch window ${run} grew to 70 minutes`, `Since the migration the nightly batch window (${run}) lasts 70 minutes.`);
  if (c.data?.memoryId && d.data?.memoryId) {
    await page.goto(base + `/#/memory/memory:${c.data.memoryId}?status=all`);
    await page.waitForSelector(".detail .actions");
    await page.click(".detail .actions button:has-text('Yenisiyle değiştir')");
    await page.fill("#modal input[name=q]", "grew to 70");
    await page.waitForSelector("#modal .rows .row");
    await page.click("#modal .rows .row >> nth=0");
    await page.fill("#modal textarea[name=reason]", "newer measurement");
    await page.click("#modal button[type=submit]");
    await page.waitForTimeout(1500);
    // MemoryLifecycleService.SUPERSEDE archives the old record and records a human "supersedes" proposal.
    const superseded = await api(page, "items/memory/" + c.data.memoryId);
    const replacement = await api(page, "items/memory/" + d.data.memoryId);
    check("supersede archives the old record and keeps the replacement active (read-back)",
      String(superseded.data?.status).toLowerCase() === "archived" && String(replacement.data?.status).toLowerCase() === "active",
      { old: superseded.data?.status, replacement: replacement.data?.status });
  } else {
    check("supersede seeds created by the gate", false, { c: c.data, d: d.data });
  }

  // ---- learned knowledge is attached to real code (anchors written by memory.learn) ----
  const learned = await api(page, "items?kind=memory&type=discovery&limit=10&project=PANEL_PETCLINIC");
  const learnedLinks = [];
  for (const item of learned.data?.items || []) {
    const around = await api(page, "items/memory/" + item.id.split(":")[1] + "/neighbors?limit=50");
    for (const l of around.data?.links || []) if (l.target.startsWith("symbol:") || l.target.startsWith("file:")) learnedLinks.push({ memory: item.id, ...l });
  }
  const learnedId = learnedLinks.find((l) => l.target.startsWith("symbol:"))?.memory;
  check("learned memory links to its resolved code anchors (API)", !!learnedId && learnedLinks.every((l) => !l.inferred), { links: learnedLinks.map((l) => l.target) });
  const fileLink = learnedLinks.find((l) => l.target.startsWith("file:"));
  check("learned memory links to its resolved file anchor (API)", !!fileLink, { fileLink });
  if (fileLink) {
    const fromFile = await api(page, "items/file/" + fileLink.target.slice(5) + "/neighbors?limit=50");
    check("the file lists the learned memory back (reverse edge)", (fromFile.data?.links || []).some((l) => l.target === fileLink.memory), { links: (fromFile.data?.links || []).map((l) => l.target) });
  }
  // ---- Graph tab: code atlas, click → detail, attach memory and rules to nodes ----
  const graphPosts = [];
  page.on("request", (r) => {
    if (r.method() === "POST" && /\/workspace\/api\/(memory|rules\/draft)$/.test(r.url())) graphPosts.push({ url: r.url(), body: r.postDataJSON() });
  });
  await page.goto(base + "/");
  await page.evaluate((p) => { localStorage.setItem("atlas.project", p); localStorage.setItem("atlas.project.chosen", "1"); }, codeProject);
  await page.goto(base + "/#/graph");
  await page.reload();
  await page.waitForFunction("window.__graph && window.__graph.ready", null, { timeout: 60000 });
  const opening = await page.evaluate(() => window.__graph.visibleIds().map((id) => window.__graph.node(id).kind));
  check("graph opens with the project and its packages only",
    opening[0] === "project" && opening.length > 1 && opening.slice(1).every((k) => k === "package"), { nodes: opening.length, fallback: await page.evaluate(() => window.__graph.fallback) });
  await page.waitForTimeout(1200);
  await page.screenshot({ path: `${out}/panel-graph-open-1440.png` });
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
  check("a package fans its classes out on the next orbit", !!target && (await page.evaluate(({ pkg, cls }) => {
    const r = (p) => Math.hypot(p[0], p[1]);
    return r(window.__graph.node(cls).pos) > r(window.__graph.node(pkg).pos);
  }, target)), target);
  if (!target) throw new Error("graph project has no class with members: " + codeProject);
  await page.waitForFunction("window.__graph.idle()", null, { timeout: 10000 });
  await page.evaluate((id) => window.__graph.focus(id), target.cls);
  await page.waitForTimeout(300);
  const at = await page.evaluate((id) => window.__graph.screenOf(id), target.cls);
  if (at?.inside) await page.mouse.click(at.x, at.y); else await page.evaluate((id) => window.__graph.pick(id), target.cls);
  await page.waitForFunction((id) => document.querySelector(".graph-detail")?.dataset.node === id && window.__graph.node(id).expanded, target.cls, { timeout: 30000 });
  const pathIds = await page.evaluate(() => window.__graph.pathIds());
  check("clicking a class selects it, opens the detail card and its member layer; the path from the root is highlighted",
    [target.root, target.pkg, target.cls].every((id) => pathIds.includes(id)), { realClick: !!at?.inside, pathIds });
  const member = await page.evaluate((id) => window.__graph.children(id)[0], target.cls);
  await page.evaluate((id) => window.__graph.select(id), member);
  await page.waitForSelector(".graph-detail .d-section");
  const memberNode = await page.evaluate((id) => window.__graph.node(id), member);

  // memory on the member node (project scope → SYMBOL locator)
  const memoryBefore = memberNode.memoryCount;
  await page.click(".graph-detail .d-row.mem button:has-text('Bu proje')");
  await page.waitForSelector("#modal[open] .attach-target");
  const memSummary = `PANEL-E2E ${run}: ${memberNode.name} retries ${num(7)} times`;
  await page.fill("#modal input[name=summary]", memSummary);
  await page.fill("#modal textarea[name=content]", `PANEL-E2E ${run}: ${memberNode.name} retries exactly ${num(7)} times before it reports a failure (ref ${run}m).`);
  await page.screenshot({ path: `${out}/panel-graph-memory-1440.png` });
  await page.click("#modal button[type=submit]");
  await page.waitForFunction(([id, before]) => window.__graph.node(id).memoryCount > before || document.querySelector("#modal .form-error:not([hidden])"), [member, memoryBefore], { timeout: 180000 });
  const memPost = graphPosts.find((p) => p.url.endsWith("/memory"))?.body;
  const memError = await page.$eval("#modal .form-error", (n) => (n.hidden ? null : n.textContent)).catch(() => null);
  check("memory added from a member node is linked with a SYMBOL locator and its badge counts it",
    !memError && memPost?.scope === "project" && memPost?.codeLocators?.[0]?.kind === "SYMBOL" && memPost.codeLocators[0].path === memberNode.path
      && (await page.evaluate((id) => window.__graph.node(id).memoryCount, member)) === memoryBefore + 1,
    { memError, locator: memPost?.codeLocators });
  if (memError) await page.keyboard.press("Escape");
  await page.waitForFunction((s) => document.querySelector(".graph-detail")?.textContent.includes(s), memSummary, { timeout: 15000 }).catch(() => null);
  check("the detail card lists the attached memory", (await page.textContent(".graph-detail")).includes(memSummary));

  // rule on the member: the card must say it is shown for the whole file and name the method
  await page.click(".graph-detail .d-row.rules button.node-target");
  await page.fill(".graph-drawer textarea[name=statement]", `PANEL-E2E ${run}: ${memberNode.name} metodunda log'a kişisel veri yazma.`);
  await page.click(".graph-drawer form.section button[type=submit]");
  await page.waitForSelector(".graph-drawer pre.card-preview", { timeout: 60000 });
  const memberNote = await page.textContent(".graph-drawer .delivery-note.on-card");
  const memberDraft = graphPosts.filter((p) => p.url.endsWith("/rules/draft")).pop()?.body;
  check("member rule card says it is shown for the whole file and names the target method (fqn + signature)",
    memberNote.includes(`${memberNode.path} dosyasının tamamını düzenlerken gösterilir; hedef metot:`) && memberNote.includes(memberNode.signature)
      && memberDraft?.scope === "node" && memberDraft?.nodeTarget?.kind === "member" && memberDraft.nodeTarget.signature === memberNode.signature,
    { memberNote, nodeTarget: memberDraft?.nodeTarget });
  await page.screenshot({ path: `${out}/panel-graph-member-rule-card-1440.png` });
  await page.click(".graph-drawer .drawer-head button:has-text('Kapat')");

  // rule on the class, promoted (plan acceptance 3)
  await page.evaluate((id) => window.__graph.select(id), target.cls);
  await page.waitForSelector(".graph-detail .d-section");
  const classNode = await page.evaluate((id) => window.__graph.node(id), target.cls);
  const classRule = `PANEL-E2E ${run}: Bu dosyada log'a kişisel veri yazma.`;
  await page.click(".graph-detail .d-row.rules button.node-target");
  await page.fill(".graph-drawer textarea[name=statement]", classRule);
  await page.click(".graph-drawer form.section button[type=submit]");
  await page.waitForSelector(".graph-drawer pre.card-preview", { timeout: 60000 });
  check("class rule card summarises the rule in words above the raw card",
    (await page.textContent(".graph-drawer .card-summary")).includes(classRule) && (await page.textContent(".graph-drawer .delivery-note")).includes(classNode.path));
  await page.fill(".graph-drawer textarea[name=approval]", "Bu kuralı onaylıyorum (panel e2e).");
  await page.click(".graph-drawer button:has-text('Kuralı etkinleştir')");
  await page.waitForFunction(([id, before]) => window.__graph.node(id).ruleCount > before || document.querySelector(".graph-drawer .form-error:not([hidden])"), [target.cls, classNode.ruleCount], { timeout: 60000 });
  const attachedRules = await api(page, `code-tree/attached?project=${encodeURIComponent(codeProject)}&node=${encodeURIComponent(target.cls)}`);
  check("promoted class rule is attached to the node and counted in its badge",
    (attachedRules.data?.rules || []).some((r) => (r.summary || r.statement || "").includes(classRule))
      && (await page.evaluate((id) => window.__graph.node(id).ruleCount, target.cls)) === classNode.ruleCount + 1,
    { rules: (attachedRules.data?.rules || []).length });
  await page.waitForTimeout(800);
  await page.screenshot({ path: `${out}/panel-graph-attached-1440.png` });

  // ---- empty workspace: only the "Proje ekle" card ----
  const onboarding = await browser.newPage({ locale: "tr-TR", viewport: { width: 1440, height: 900 } });
  let projectCalls = 0;
  await onboarding.route("**/workspace/api/projects", async (route) => {
    if (projectCalls++ === 0) return route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ projects: [], defaultProject: "" }) });
    return route.continue();
  });
  await onboarding.goto(base + "/");
  await onboarding.waitForSelector(".onboarding-card input[name=rootPath]", { timeout: 15000 });
  check("empty workspace shows only the centred Proje ekle card",
    !(await onboarding.isVisible("nav.rail")) && (await onboarding.isVisible(".onboarding-card button[type=submit]")));
  await onboarding.fill(".onboarding-card input[name=rootPath]", scanRoot || "/path/to/project");
  await onboarding.screenshot({ path: `${out}/panel-onboarding-1440.png` });
  if (scanRoot) {
    // no key field: the folder gets the key an agent's session.bootstrap resolves (an added folder keeps its key)
    const scanKey = (await api(page, "projects/resolve?rootPath=" + encodeURIComponent(scanRoot))).data?.projectKey;
    check("add-project form asks only for the folder", !(await onboarding.$(".onboarding-card input[name=projectKey]")) && !!scanKey, { scanKey });
    await onboarding.click(".onboarding-card button[type=submit]");
    await onboarding.waitForSelector(".scan-progress[data-state=running]", { timeout: 10000 }).catch(() => null);
    await onboarding.screenshot({ path: `${out}/panel-onboarding-scanning-1440.png` });
    await onboarding.waitForFunction("location.hash.startsWith('#/graph') && window.__graph && window.__graph.ready", null, { timeout: 600000 });
    const scanned = await onboarding.evaluate(() => ({ project: window.__graph.project, nodes: window.__graph.stats?.().nodes || 0, empty: !!window.__graph.empty }));
    check("adding a project scans it structurally and opens its graph", scanned.project === scanKey && scanned.nodes > 1 && !scanned.empty, scanned);
    await onboarding.waitForTimeout(1200);
    await onboarding.screenshot({ path: `${out}/panel-onboarding-graph-1440.png` });
  } else {
    console.log("SKIP real scan through onboarding (pass --scan-root=/abs/path to exercise it)");
  }
  await onboarding.close();

  // ---- Jobs tab ----
  await page.goto(base + "/#/jobs");
  await page.waitForSelector(".tabs a .tab-count", { timeout: 15000 });
  const jobsTab = await strayNull(page);
  check("jobs tab lists last job / saved jobs / personal memory and shows no stray null",
    (await page.$$eval(".tabs a", (n) => n.length)) === 3 && jobsTab.direct.length === 0 && !jobsTab.visible, jobsTab);
  await page.screenshot({ path: `${out}/panel-jobs-1440.png` });
  const jobs = await api(page, "jobs");
  check("GET jobs answers with the three lists", jobs.status === 200 && Array.isArray(jobs.data?.jobs) && Array.isArray(jobs.data?.personal), { status: jobs.status });
  const openable = [["job", jobs.data?.jobs || []], ["personal", jobs.data?.personal || []]].find(([, list]) => list.length);
  if (openable) {
    const [kind, list] = openable;
    await page.goto(base + `/#/jobs/${kind}/${encodeURIComponent(list[0].id)}`);
    await page.waitForSelector("aside.detail h2", { timeout: 15000 });
    check("opening a job shows its content", (await page.textContent("aside.detail h2")).length > 0);
    await page.click("aside.detail .actions button:has-text('Sil')");
    await page.waitForSelector("#modal[open]");
    check("delete asks for confirmation first", (await page.textContent("#modal h2")).toLocaleLowerCase("tr").includes("sil"));
    await page.screenshot({ path: `${out}/panel-jobs-delete-1440.png` });
    await page.click("#modal button:has-text('Vazgeç')");
  } else {
    console.log("SKIP job detail/delete: the isolated backend has no saved job or personal memory");
  }

  // ---- palette ----
  await page.selectOption("#project", projectA);
  await page.waitForTimeout(500);
  await page.keyboard.press("Meta+k");
  await page.fill("#palette-input", "Warehouse");
  await page.waitForTimeout(700);
  await page.screenshot({ path: `${out}/panel-palette-1440.png` });
  const paletteHits = await page.$$eval("#palette-results li", (n) => n.length);
  check("command palette finds records", paletteHits > 1, { paletteHits });
  await page.keyboard.press("Escape");

  // ---- 1920 screenshots ----
  const wide = await browser.newPage({ locale: "tr-TR", viewport: { width: 1920, height: 1080 } });
  await wide.goto(base + "/");
  await wide.evaluate((p) => { localStorage.setItem("atlas.project", p); localStorage.setItem("atlas.project.chosen", "1"); }, projectA);
  for (const [hash, name, p] of [["#/overview", "overview"], [`#/memory/memory:${idX}`, "memory"], ["#/rules", "rules"], ["#/graph", "graph", codeProject], ["#/jobs", "jobs"]]) {
    await wide.evaluate((key) => localStorage.setItem("atlas.project", key), p || projectA);
    await wide.goto(base + "/" + hash);
    await wide.reload();
    await wide.waitForTimeout(1500);
    await wide.screenshot({ path: `${out}/panel-${name}-1920.png` });
  }

  // ---- English: an en-US browser gets the panel in English ----
  // Heuristic for "no Turkish on screen": walk the visible text nodes of the page and count Turkish-specific letters
  // (ğüşıöçĞÜŞİÖÇ). User data is excluded (record titles and texts, rule statements, project / package / member
  // names, the raw confirmation card), since those are whatever people and agents wrote; product text is not.
  const en = await browser.newPage({ locale: "en-US", viewport: { width: 1440, height: 900 } });
  en.on("pageerror", (e) => errors.push("en: " + e.message));
  const langHeaders = new Set();
  en.on("request", (r) => { if (r.url().includes("/workspace/api/")) langHeaders.add(r.headers()["x-workspace-lang"]); });
  await en.goto(base + "/");
  await en.evaluate((p) => { localStorage.setItem("atlas.project", p); localStorage.setItem("atlas.project.chosen", "1"); }, projectA);
  const turkishOnScreen = () => en.evaluate(() => {
    const USER_DATA = [".row .t", ".row .s", ".detail h2", ".detail .content", ".detail dd", ".detail .link-row span:last-child",
      "a.rule > div:first-child", ".card-preview", "#project", ".strata-title em", ".graph-labels", ".graph-crumbs",
      ".graph-detail h2", ".graph-detail .d-path", ".graph-detail dd", ".graph-detail ul", ".find-list", ".toast"].join(", ");
    const hits = [];
    const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
    for (let node = walker.nextNode(); node; node = walker.nextNode()) {
      const el = node.parentElement;
      if (!node.nodeValue.trim() || !el || el.closest(USER_DATA)) continue;
      if (!el.checkVisibility({ checkOpacity: true, checkVisibilityCSS: true })) continue;
      if (/[ğüşıöçĞÜŞİÖÇ]/.test(node.nodeValue)) hits.push(node.nodeValue.trim().slice(0, 60));
    }
    return hits;
  });
  const tabs = {};
  for (const [hash, ready] of [["#/overview", ".strata-wrap h2"], [`#/memory/memory:${idX}?status=all`, ".detail h2"], ["#/rules", ".rule-group, .empty"]]) {
    await en.goto(base + "/" + hash);
    await en.waitForSelector(ready, { timeout: 30000 });
    await en.waitForTimeout(600);
    tabs[hash.split("?")[0].split("/")[1]] = await turkishOnScreen();
  }
  const rail = await en.$$eval(".rail-link span", (n) => n.map((x) => x.textContent));
  await en.evaluate((p) => localStorage.setItem("atlas.project", p), codeProject);
  await en.goto(base + "/#/graph");
  await en.reload();
  await en.waitForFunction("window.__graph && window.__graph.ready", null, { timeout: 60000 });
  const enPkg = await en.evaluate(() => window.__graph.visibleIds()[1]);
  await en.evaluate((id) => window.__graph.select(id), enPkg);
  await en.waitForSelector(".graph-detail .d-section", { timeout: 15000 });
  await en.waitForTimeout(800);
  tabs.graph = await turkishOnScreen();
  const hint = await en.textContent(".graph-hint");
  const card = await en.evaluate(() => ({
    kind: document.querySelector(".graph-detail .d-kind")?.textContent,
    rows: [...document.querySelectorAll(".graph-detail .d-actions .d-row span, .graph-detail .d-actions button")].map((n) => n.textContent),
    sections: [...document.querySelectorAll(".graph-detail .d-section h3")].map((n) => n.textContent),
  }));
  await en.screenshot({ path: `${out}/panel-graph-en-1440.png` });
  check("en-US: rail menu, graph hint and detail card are English, and the panel sends X-Workspace-Lang: en",
    rail.join("|").startsWith("Overview|Memory|Pending|Rules|References|Code map|Jobs") && hint.startsWith("Click: open and select")
      && card.kind?.startsWith("Package") && card.rows.includes("Add memory") && card.rows.includes("This node")
      && card.sections.some((s) => s.startsWith("Attached memory")) && [...langHeaders].every((h) => h === "en") && langHeaders.size > 0,
    { rail, hint, card, langHeaders: [...langHeaders] });
  check("en-US: overview, memory, rules and graph show no Turkish characters outside user data",
    Object.values(tabs).every((hits) => hits.length === 0), tabs);
  await en.close();
  await browser.close();
  check("no page errors", errors.length === 0, errors.slice(0, 5));
  fs.writeFileSync(`${out}/panel-e2e-report.json`, JSON.stringify(report, null, 2));
  process.exit(report.checks.every((c) => c.ok) ? 0 : 1);
})().catch((e) => { console.error(e); process.exit(2); });
