// "Proje ekle": a folder path → structural indexing through POST /api/scanner/scan (semanticEnabled=false,
// so no language model runs and no code leaves the machine). Used by the empty-panel onboarding card and
// by the dialog behind the top bar's "Proje ekle" button.
import { api, h, field, fill } from "./core.js";
import { lang, t, locale } from "./i18n.js";

/** Project key suggestion from the folder name: "/x/pet-clinic" → "PET_CLINIC". */
export function keyFromPath(path) {
  const base = String(path || "").trim().replace(/[\\/]+$/, "").split(/[\\/]/).pop() || "";
  return base.normalize("NFKD").replace(/[̀-ͯ]/g, "").replace(/[^A-Za-z0-9]+/g, "_").replace(/^_+|_+$/g, "").toUpperCase().slice(0, 64);
}

async function startScan(rootPath, projectKey, signal) {
  let response;
  try {
    response = await fetch("/api/scanner/scan", {
      method: "POST",
      headers: { "Content-Type": "application/json", "X-Workspace-Request": "1", "X-Workspace-Lang": lang },
      body: JSON.stringify({ rootPath, projectKey, force: false, semanticEnabled: false }),
      signal,
    });
  } catch (error) {
    if (error.name === "AbortError") throw error;
    throw new Error(t("Sunucuya ulaşılamadı. AI Orchestration çalışıyor mu?"));
  }
  const text = await response.text();
  let data = null;
  try { data = text ? JSON.parse(text) : null; } catch { data = { message: text.slice(0, 300) }; }
  if (!response.ok) {
    const reason = data?.message || data?.error || data?.detail;
    throw new Error(reason ? t("İndeksleme başlatılamadı: {reason}", { reason })
      : t("İndeksleme başlatılamadı (HTTP {status}).", { status: response.status }));
  }
  return data || {};
}

/**
 * Builds the add-project form into `holder`. `onDone(projectKey)` runs after a successful scan.
 * The scan request is synchronous on the server; while it runs the card shows elapsed time and the
 * file / symbol counts read from the project's stats.
 */
export function mountAddProject(holder, { onDone, onCancel, title = t("Proje ekle"), intro = true } = {}) {
  const path = h("input", { name: "rootPath", required: true, autocomplete: "off", spellcheck: false, placeholder: t("/home/sen/projeler/uygulama") });
  const start = h("button.btn.primary", { type: "submit" }, t("Yapısal indekslemeyi başlat"));
  const cancel = onCancel ? h("button.btn.quiet", { type: "button", onclick: onCancel }, t("Vazgeç")) : null;
  const error = h("p.form-error", { hidden: true, role: "alert" });
  const bar = h("i");
  const status = h("span.scan-status", "");
  const counts = h("span.scan-counts", "");
  const progress = h("div.scan-progress", { hidden: true }, h("div.scan-bar", bar), h("div.scan-line", status, counts));
  const form = h("form.add-project-form",
    h("h2", title),
    intro ? h("p.lead", t("Kod klasörünün tam yolunu gir. Yapısal indeksleme dosyaları, paketleri, sınıfları ve metotları çıkarır; dil modeli kullanmaz ve kod bu makineden çıkmaz.")) : null,
    field(t("Klasör yolu"), path, t("Sunucunun çalıştığı makinedeki mutlak yol.")),
    error, progress,
    h("p.hint", t("Copilot kullanıyorsan, bu depoya talimatını kurmak için terminalde: "), h("code", t("ai_orch project add <klasör> --copilot"))),
    h("div.add-project-actions", cancel, start));

  let busy = false;
  form.onsubmit = async (event) => {
    event.preventDefault();
    if (busy) return;
    const rootPath = path.value.trim();
    if (!rootPath) return;
    busy = true;
    start.disabled = true;
    path.disabled = true;
    if (cancel) cancel.disabled = true;
    error.hidden = true;
    progress.hidden = false;
    progress.dataset.state = "running";
    bar.style.width = "";
    const started = Date.now();
    status.textContent = t("İndeksleniyor…");
    // the key agents resolve for this folder (an already added folder keeps its key)
    let projectKey;
    try {
      projectKey = (await api("projects/resolve?rootPath=" + encodeURIComponent(rootPath))).projectKey;
    } catch (err) {
      progress.hidden = true;
      error.textContent = err.message;
      error.hidden = false;
      busy = false; start.disabled = false; path.disabled = false; if (cancel) cancel.disabled = false;
      return;
    }
    counts.textContent = "";
    const tick = setInterval(async () => {
      const seconds = Math.round((Date.now() - started) / 1000);
      status.textContent = t("İndeksleniyor · {s} sn", { s: seconds });
      try {
        const stats = await api("stats?project=" + encodeURIComponent(projectKey));
        if (stats && (stats.files || stats.symbols)) {
          counts.textContent = t("{files} dosya · {symbols} sembol", {
            files: Number(stats.files || 0).toLocaleString(locale()), symbols: Number(stats.symbols || 0).toLocaleString(locale()) });
        }
      } catch { /* counts are a courtesy while the scan runs */ }
    }, 1500);
    try {
      const result = await startScan(rootPath, projectKey, undefined);
      clearInterval(tick);
      progress.dataset.state = "done";
      bar.style.width = "100%";
      const scanned = result.filesScanned ?? 0;
      const skipped = result.filesSkipped ?? 0;
      status.textContent = t("Tamamlandı · {s} sn", { s: Math.max(1, Math.round((result.durationMs ?? Date.now() - started) / 1000)) });
      counts.textContent = t("{n} dosya indekslendi", { n: scanned.toLocaleString(locale()) })
        + (skipped ? t(", {n} değişmediği için atlandı", { n: skipped.toLocaleString(locale()) }) : "");
      await new Promise((r) => setTimeout(r, 700));
      await onDone?.(result.projectKey || projectKey, result);
    } catch (err) {
      clearInterval(tick);
      progress.dataset.state = "failed";
      progress.hidden = true;
      error.textContent = err.message;
      error.hidden = false;
    } finally {
      busy = false;
      start.disabled = false;
      path.disabled = false;
      if (cancel) cancel.disabled = false;
    }
  };
  fill(holder, form);
  path.focus();
  return form;
}

/** The top bar's "Proje ekle" dialog. */
export function openAddProjectDialog(onDone) {
  const dialog = document.getElementById("project-dialog");
  const holder = h("div.project-dialog-body");
  dialog.replaceChildren(holder);
  mountAddProject(holder, {
    onDone: async (key, result) => { dialog.close(); await onDone(key, result); },
    onCancel: () => dialog.close(),
  });
  dialog.showModal();
  holder.querySelector("input")?.focus();
}
