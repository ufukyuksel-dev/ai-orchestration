// Local baseline capture for the Workspace/Universe UI.
// Drives a headless Chrome over CDP with Node's built-in WebSocket; no npm dependencies.
// Read-only: it opens pages and reads window state. It never posts to the Workspace API.
//
// Usage:
//   node src/test/browser/capture.mjs --url <url> --out <png> [--width 1440] [--height 900]
//     [--wait-for "<js expression returning true>"] [--timeout 120000] [--metrics <json>]
//     [--act "<js run before the screenshot>"] [--settle <ms>] [--no-webgl]
import { spawn } from "node:child_process";
import { mkdirSync, writeFileSync, mkdtempSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { tmpdir } from "node:os";

const CHROME = "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome";

const BOOLEAN_FLAGS = new Set(["--no-webgl"]);

function args() {
  const a = process.argv.slice(2).filter((x) => !BOOLEAN_FLAGS.has(x));
  const o = { width: 1440, height: 900, timeout: 120000 };
  for (let i = 0; i < a.length; i += 2) o[a[i].replace(/^--/, "")] = a[i + 1];
  o.width = Number(o.width);
  o.height = Number(o.height);
  o.timeout = Number(o.timeout);
  return o;
}

function launch(port, width, height) {
  const profile = mkdtempSync(resolve(tmpdir(), "atlas-capture-"));
  const child = spawn(
    CHROME,
    [
      "--headless=new",
      "--no-first-run",
      "--no-default-browser-check",
      "--disable-background-timer-throttling",
      "--disable-renderer-backgrounding",
      ...(process.argv.includes("--no-webgl")
        ? ["--disable-gpu", "--disable-software-rasterizer", "--disable-webgl"]
        : ["--use-gl=angle", "--use-angle=swiftshader", "--enable-unsafe-swiftshader", "--ignore-gpu-blocklist"]),
      `--user-data-dir=${profile}`,
      `--window-size=${width},${height}`,
      `--remote-debugging-port=${port}`,
      "about:blank",
    ],
    { stdio: "ignore" },
  );
  return child;
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function target(port) {
  for (let i = 0; i < 100; i++) {
    try {
      const list = await (await fetch(`http://127.0.0.1:${port}/json/list`)).json();
      const page = list.find((t) => t.type === "page" && t.webSocketDebuggerUrl);
      if (page) return page.webSocketDebuggerUrl;
    } catch {
      /* chrome not up yet */
    }
    await sleep(200);
  }
  throw new Error("Chrome DevTools endpoint did not become available");
}

function connect(url) {
  const ws = new WebSocket(url);
  let seq = 0;
  const pending = new Map();
  const events = [];
  ws.addEventListener("message", (m) => {
    const msg = JSON.parse(m.data);
    if (msg.id && pending.has(msg.id)) {
      const { resolve: res, reject } = pending.get(msg.id);
      pending.delete(msg.id);
      msg.error ? reject(new Error(JSON.stringify(msg.error))) : res(msg.result);
    } else if (msg.method) events.push(msg);
  });
  const ready = new Promise((res, rej) => {
    ws.addEventListener("open", res, { once: true });
    ws.addEventListener("error", rej, { once: true });
  });
  const send = (method, params = {}) =>
    new Promise((res, rej) => {
      const id = ++seq;
      pending.set(id, { resolve: res, reject: rej });
      ws.send(JSON.stringify({ id, method, params }));
    });
  return { ws, send, events, ready };
}

const o = args();
if (!o.url || !o.out) {
  console.error("--url and --out are required");
  process.exit(2);
}
const port = 9222 + Math.floor(Math.random() * 500);
const chrome = launch(port, o.width, o.height);
let exitCode = 0;
try {
  const { ws, send, events, ready } = connect(await target(port));
  await ready;
  await send("Page.enable");
  await send("Runtime.enable");
  await send("Log.enable");
  await send("Network.enable");
  await send("Emulation.setDeviceMetricsOverride", {
    width: o.width,
    height: o.height,
    deviceScaleFactor: 1,
    mobile: false,
  });

  const started = Date.now();
  await send("Page.navigate", { url: o.url });

  const evaluate = async (expression) => {
    const r = await send("Runtime.evaluate", {
      expression,
      returnByValue: true,
      awaitPromise: true,
    });
    if (r.exceptionDetails) throw new Error(r.exceptionDetails.text);
    return r.result.value;
  };

  let satisfied = !o["wait-for"];
  while (!satisfied && Date.now() - started < o.timeout) {
    try {
      satisfied = !!(await evaluate(`(() => { try { return !!(${o["wait-for"]}); } catch { return false; } })()`));
    } catch {
      /* page still navigating */
    }
    if (!satisfied) await sleep(500);
  }
  // Optional scripted interaction (drill-down, selection) before the screenshot.
  let actResult = null;
  if (o.act) actResult = await evaluate(`(async () => { return ${o.act}; })()`);

  await sleep(Number(o.settle || 1200)); // let the last frame settle

  const shot = await send("Page.captureScreenshot", { format: "png", captureBeyondViewport: false });
  mkdirSync(dirname(resolve(o.out)), { recursive: true });
  writeFileSync(resolve(o.out), Buffer.from(shot.data, "base64"));

  const requests = events.filter((e) => e.method === "Network.requestWillBeSent");
  const metrics = {
    url: o.url,
    viewport: `${o.width}x${o.height}`,
    waitFor: o["wait-for"] || null,
    waitSatisfied: satisfied,
    act: o.act || null,
    actResult,
    elapsedMs: Date.now() - started,
    requests: requests.length,
    apiRequests: requests
      .map((e) => e.params.request.url)
      .filter((u) => u.includes("/workspace/api/"))
      .map((u) => u.replace(/^https?:\/\/[^/]+/, "")),
    consoleErrors: events
      .filter((e) => e.method === "Log.entryAdded" && e.params.entry.level === "error")
      .map((e) => e.params.entry.text)
      .slice(0, 20),
    // Modül yükleme sırasındaki runtime istisnaları Log.entryAdded'e düşmez.
    exceptions: events
      .filter((e) => e.method === "Runtime.exceptionThrown")
      .map((e) => {
        const d = e.params.exceptionDetails;
        return `${d.text} ${d.exception?.description || ""} @${d.url || ""}:${d.lineNumber}`.slice(0, 400);
      })
      .slice(0, 10),
    page: await evaluate(
      `JSON.stringify({ universeStats: (window.universeStats ? { ...window.universeStats, positions: undefined } : null), atlas: (window.atlasUniverse ? { drawn: window.atlasUniverse.drawn, counts: window.atlasUniverse.counts, labels: window.atlasUniverse.labels, hiddenLabels: window.atlasUniverse.hiddenLabels, selected: window.atlasUniverse.selected, expanded: window.atlasUniverse.expanded, camera: window.atlasUniverse.camera } : null), statsText: document.getElementById("stats")?.textContent || document.getElementById("scene-stats")?.textContent || null, errorText: document.getElementById("error")?.textContent || null, messageText: document.getElementById("status")?.textContent || null, countText: document.getElementById("count")?.textContent || null })`,
    ),
  };
  metrics.page = JSON.parse(metrics.page);
  if (o.metrics) {
    mkdirSync(dirname(resolve(o.metrics)), { recursive: true });
    writeFileSync(resolve(o.metrics), JSON.stringify(metrics, null, 2));
  }
  console.log(JSON.stringify(metrics, null, 2));
  ws.close();
} catch (e) {
  console.error("capture failed:", e.message);
  exitCode = 1;
} finally {
  chrome.kill("SIGKILL");
}
process.exit(exitCode);
