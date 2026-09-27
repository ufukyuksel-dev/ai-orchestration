// README screenshots from a throw-away demo backend with realistic content (never the user's server).
//   node src/test/browser/readme-screens.cjs <baseUrl> <petclinic-folder> <outDir> [--lang=en|tr]
//   --lang  panel language of the screenshots (browser locale en-US / tr-TR), default en (the README is English)
// Adds the project through the "Add project" card, lets an agent-style memory.learn write a card, attaches a rule to
// a method through the panel's node flow, then captures the views the README shows.
const path = require("path");
const fs = require("fs");
const { chromium } = require(path.resolve(__dirname, "../../../.benchmark/browser/node_modules/playwright"));

const [base = "http://127.0.0.1:18185", repo, out = "docs/assets"] = process.argv.slice(2).filter((a) => !a.startsWith("--"));
const lang = (process.argv.find((a) => a.startsWith("--lang=")) || "--lang=en").slice("--lang=".length);
if (!repo) throw new Error("usage: readme-screens.cjs <baseUrl> <petclinic-folder> <outDir> [--lang=en|tr]");
if (!["en", "tr"].includes(lang)) throw new Error("--lang must be en or tr");
// Visible labels the script clicks, per panel language.
const L = { en: { thisNode: "This node" }, tr: { thisNode: "Bu düğüm" } }[lang];
if (/:18080\b/.test(base)) throw new Error("refusing to run against the user's 18080 server");
fs.mkdirSync(out, { recursive: true });
const H = { "X-Workspace-Request": "1", "X-Workspace-Lang": lang, "Content-Type": "application/json" };
const api = async (p, body) => {
  const r = await fetch(base + "/workspace/api/" + p, { method: body ? "POST" : "GET", headers: H, body: body ? JSON.stringify(body) : undefined });
  if (!r.ok) throw new Error(p + " → " + r.status + " " + (await r.text()).slice(0, 200));
  return r.json();
};
async function mcp(tool, args) {
  const h = { "Content-Type": "application/json", Accept: "application/json, text/event-stream", "X-AI-Orch-Client": "claude-code" };
  const post = async (body, sid) => {
    const r = await fetch(base + "/mcp", { method: "POST", headers: { ...h, ...(sid ? { "Mcp-Session-Id": sid } : {}) }, body: JSON.stringify(body) });
    return { text: await r.text(), sid: r.headers.get("mcp-session-id") };
  };
  const init = await post({ jsonrpc: "2.0", id: 1, method: "initialize", params: { protocolVersion: "2025-06-18", capabilities: {}, clientInfo: { name: "screens", version: "1" } } });
  await post({ jsonrpc: "2.0", method: "notifications/initialized" }, init.sid);
  const res = await post({ jsonrpc: "2.0", id: 2, method: "tools/call", params: { name: tool, arguments: args } }, init.sid);
  for (let line of res.text.split("\n")) {
    line = line.startsWith("data:") ? line.slice(5).trim() : line.trim();
    if (line.startsWith("{")) return JSON.parse(JSON.parse(line).result.content[0].text);
  }
  throw new Error("no MCP answer for " + tool);
}
const shot = async (page, name, w = 1440) => {
  await page.setViewportSize({ width: w, height: Math.round(w * 0.5625) });
  await page.waitForTimeout(900);
  // notifications from earlier steps (e.g. "project added") are not part of the view being shown
  await page.evaluate(() => document.querySelectorAll("#toasts .toast").forEach((t) => t.remove()));
  await page.screenshot({ path: `${out}/${name}.png` });
  console.log("wrote", `${out}/${name}.png`);
};

(async () => {
  const browser = await chromium.launch({ headless: false, args: ["--window-size=1440,900"] });
  const page = await browser.newPage({ locale: lang === "tr" ? "tr-TR" : "en-US", viewport: { width: 1440, height: 810 } });

  // 1. empty panel → Add project
  await page.goto(base + "/");
  await page.waitForSelector(".onboarding-card input[name=rootPath]", { timeout: 20000 });
  await page.fill(".onboarding-card input[name=rootPath]", repo);
  await shot(page, "panel-add-project");
  await page.click(".onboarding-card button[type=submit]");
  await page.waitForFunction("location.hash.startsWith('#/graph') && window.__graph && window.__graph.ready", null, { timeout: 600000 });
  const project = await page.evaluate(() => window.__graph.project);

  // 2. what an agent leaves behind at the end of a task, and a rule a person attaches to a method
  await mcp("memory.learn", { rootPath: repo, candidates: [{
    kind: "procedure", summary: "Adding a new optional field to an entity end to end",
    content: "Change the entity, the form template and the details template; add the column to schema.sql for h2, mysql and postgres; add the message key to messages.properties AND every messages_xx file or I18nPropertiesSyncTest fails. Verify: ./mvnw test -Dtest='!MySqlIntegrationTests,!PostgresIntegrationTests'",
    locators: [{ kind: "file", ref: "src/main/java/org/springframework/samples/petclinic/owner/Owner.java" },
               { kind: "file", ref: "src/main/resources/db/h2/schema.sql" }] }] });
  const root = await api(`code-tree?project=${project}`);
  const pkg = root.children.find((c) => c.path.endsWith("src/main/java/org/springframework/samples/petclinic/owner"));
  const classes = (await api(`code-tree?project=${project}&parent=${encodeURIComponent(pkg.id)}`)).children;
  const owner = classes.find((c) => c.name === "Owner");
  const members = (await api(`code-tree?project=${project}&parent=${owner.id}`)).children;
  const addPet = members.find((m) => m.name.startsWith("addPet"));
  const draft = await api("rules/draft", { project, scope: "node", statement: "Never write owner personal data (name, address, telephone) to logs in this method.",
    nodeTarget: { kind: "member", path: addPet.path, fqn: addPet.fqn, signature: addPet.signature } });
  const preview = await api("rules/draft/preview", { draftId: draft.draftId, project });
  await api("rules/draft/promote", { draftId: draft.draftId, project, candidateHash: draft.candidateHash,
    approvalContentHash: preview.approvalContentHash, confirmationCardHash: preview.confirmationCardHash,
    workflowContractVersion: preview.workflowContractVersion, humanRawText: "Approved (README demo)" });

  // 3. the layered graph with the path to Owner#addPet selected
  await page.goto(base + "/#/graph");
  await page.waitForFunction("window.__graph && window.__graph.ready", null, { timeout: 60000 });
  await page.evaluate(async ([p, o, m]) => { await window.__graph.expand(p); await window.__graph.expand(o); window.__graph.select(m); }, [pkg.id, owner.id, addPet.id]);
  await shot(page, "panel-graph");
  await shot(page, "panel-graph-1920", 1920);

  // 4. the node's detail card: attach a rule to this method (the confirmation says where it applies)
  await page.evaluate((m) => window.__graph.select(m), addPet.id);
  const ruleButton = await page.$(`aside.graph-detail button:has-text('${L.thisNode}')`);
  if (ruleButton) { await ruleButton.click(); await page.waitForTimeout(600); }
  await shot(page, "panel-rule-on-method");

  // 5. memory list
  await page.goto(base + "/#/memory");
  await page.waitForSelector(".rows .row, .empty", { timeout: 20000 });
  await shot(page, "panel-memory");
  await browser.close();
})().catch((e) => { console.error(e); process.exit(1); });
