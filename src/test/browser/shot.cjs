// Quick screenshot helper: node src/test/browser/shot.cjs <url> <out.png> [width] [height] [waitExpr] [actJs]
// Uses the Playwright copy under .benchmark/browser (not a project dependency). Read-only unless actJs writes.
const path = require("path");
const { chromium } = require(path.resolve(__dirname, "../../../.benchmark/browser/node_modules/playwright"));

(async () => {
  const [url, out, width = "1440", height = "900", waitExpr = "true", actJs = ""] = process.argv.slice(2);
  const browser = await chromium.launch({
    headless: true,
    args: ["--use-gl=angle", "--use-angle=swiftshader", "--enable-unsafe-swiftshader", "--ignore-gpu-blocklist"],
  });
  const page = await browser.newPage({ viewport: { width: Number(width), height: Number(height) } });
  const errors = [];
  page.on("pageerror", (e) => errors.push("pageerror: " + e.message));
  page.on("console", (m) => { if (m.type() === "error") errors.push("console: " + m.text()); });
  await page.goto(url);
  await page.waitForFunction(waitExpr, null, { timeout: 60000 });
  if (actJs) await page.evaluate(actJs);
  await page.waitForTimeout(1500);
  await page.screenshot({ path: out });
  console.log(JSON.stringify({ out, errors }));
  await browser.close();
})().catch((e) => { console.error(e); process.exit(1); });
