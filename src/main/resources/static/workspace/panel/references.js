// References: browse the shared reference root, read files, create folders/files and edit with hash checks.
import { api, h, query, toast, openModal, field, emptyState, errorBox, skeletonRows, fill } from "./core.js";
import { t, locale } from "./i18n.js";

export async function renderReferences(ctx) {
  const path = ctx.search.get("path") || "";
  const file = ctx.search.get("file") || "";
  const tree = h("div", skeletonRows(6));
  const viewer = h("section.section.ref-viewer", emptyState(t("Bir dosya seç"), t("Soldaki listeden bir referans dosyası açabilir ya da yeni bir dosya oluşturabilirsin.")));
  ctx.main.append(
    h("div.page-head",
      h("div", h("h1", t("Referanslar")), h("p", t("Hafıza kayıtlarının bağlandığı uzun prosedürler, sorgular ve eşleme notları. Tüm projeler aynı paylaşılan klasörü kullanır."))),
      h("div.page-actions",
        h("button.btn", { onclick: () => createFolder(ctx, path) }, t("Yeni klasör")),
        h("button.btn.primary", { onclick: () => editFile(ctx, path ? path + "/" : "", null) }, t("Yeni dosya")))),
    crumbs(ctx, path),
    h("div.ref-layout", tree, viewer),
  );

  try {
    // The list endpoint pages at most 100 entries; follow the cursor for larger folders (bounded).
    const items = [];
    let listCursor = null;
    for (let pageNo = 0; pageNo < 5; pageNo++) {
      const page = await api("references/list?" + query({ dir: path, limit: 100, cursor: listCursor }), { signal: ctx.signal });
      items.push(...(page.items || []));
      listCursor = page.nextCursor;
      if (!listCursor) break;
    }
    if (!ctx.live()) return;
    fill(tree, items.length
      ? h("div.rows", items
          .sort((a, b) => (a.kind === b.kind ? a.path.localeCompare(b.path, locale()) : a.kind === "directory" ? -1 : 1))
          .map((item) => h("button.row", {
            "aria-selected": item.path === file ? "true" : "false",
            onclick: () => ctx.navigate("#/references?" + query(item.kind === "directory" ? { path: item.path } : { path, file: item.path })),
          },
          h("span.dot", { style: item.kind === "directory" ? "background:var(--brass);border-radius:2px" : "" }),
          h("div", h("div.t", item.path.split("/").pop()), h("div.s", item.kind === "directory" ? t("Klasör") : statusText(item.status))),
          h("span.when", ""))))
      : emptyState(t("Bu klasör boş"), t("Yeni bir dosya ya da klasör oluşturabilirsin.")));
  } catch (error) {
    if (!ctx.live() || error.name === "AbortError") return;
    fill(tree, errorBox(error.message, () => ctx.navigate(location.hash)));
  }
  if (file) readFile(ctx, file, viewer);
}

function statusText(status) {
  return { current: t("Güncel"), stale: t("Değişmiş"), missing: t("Dosya yok") }[status] || status || "";
}

function crumbs(ctx, path) {
  const parts = path ? path.split("/") : [];
  return h("nav.crumbs", { "aria-label": t("Klasör yolu") },
    h("button", { onclick: () => ctx.navigate("#/references") }, t("Kök")),
    parts.map((part, index) => [" / ", h("button", { onclick: () => ctx.navigate("#/references?" + query({ path: parts.slice(0, index + 1).join("/") })) }, part)]));
}

async function readFile(ctx, file, viewer) {
  fill(viewer, h("div.skeleton"));
  let text = "";
  let item = null;
  let redacted = false;
  try {
    let offset = 0;
    for (let page = 0; page < 16; page++) {
      const data = await api("references/read?" + query({ path: file, offsetBytes: offset, maxBytes: 65536 }), { signal: ctx.signal });
      text += data.text || "";
      item = data.item;
      redacted = redacted || data.redacted;
      if (data.nextOffset === null || data.nextOffset === undefined) break;
      offset = data.nextOffset;
    }
  } catch (error) {
    if (!ctx.live() || error.name === "AbortError") return;
    fill(viewer, errorBox(error.message));
    return;
  }
  if (!ctx.live()) return;
  fill(viewer, 
    h("h3", file, h("span.page-actions",
      h("button.btn", { onclick: () => editFile(ctx, file, { text, hash: item?.hash, redacted }) }, t("Düzenle")))),
    redacted ? h("p.callout", { style: "margin:12px 18px" }, t("Bu dosyada hassas içerik maskelendi; düzenlerken maskelenmiş metin kaydedilir.")) : null,
    h("pre", text),
  );
}

function createFolder(ctx, base) {
  const input = h("input", { name: "name", required: true, placeholder: t("Örn: Pilotlama/petclinic") });
  openModal({
    title: t("Yeni klasör"),
    submitLabel: t("Oluştur"),
    build: () => [field(t("Klasör yolu"), input, base ? t("Şu klasörün altında: {base}", { base }) : t("Paylaşılan kökün altında"))],
    submit: async (data) => {
      const relativePath = [base, data.get("name").trim().replace(/^\/+|\/+$/g, "")].filter(Boolean).join("/");
      await api("references/mkdir", { body: { relativePath } });
      toast(t("Klasör oluşturuldu."));
      ctx.navigate("#/references?" + query({ path: relativePath }));
    },
  });
}

function editFile(ctx, path, existing) {
  const nameInput = h("input", { name: "path", required: true, value: path, readOnly: !!existing });
  const content = h("textarea", { name: "content", rows: 16, style: "font-family:ui-monospace,Menlo,monospace" }, existing?.text || "");
  openModal({
    title: existing ? t("Dosyayı düzenle") : t("Yeni dosya"),
    submitLabel: t("Kaydet"),
    build: () => [
      field(t("Dosya yolu"), nameInput, t("Örn: Pilotlama/petclinic/prosedur.md — .md, .sql, .json, .yaml gibi metin dosyaları")),
      field(t("İçerik"), content, t("Parola, anahtar veya token yazma; kayıttan önce otomatik maskelenir.")),
    ],
    submit: async (data) => {
      const relativePath = data.get("path").trim().replace(/^\/+/, "");
      const saved = await api("references/write", {
        body: { relativePath, content: data.get("content"), expectedHash: existing?.hash || null },
      }).catch((error) => {
        if (/hash conflict/i.test(error.message)) {
          throw new Error(t("Dosya sen düzenlerken değişmiş. Sayfayı yenileyip güncel içerikten tekrar dene."));
        }
        throw error;
      });
      toast(saved.redacted ? t("Kaydedildi; hassas içerik maskelendi.") : t("Kaydedildi."));
      const dir = relativePath.includes("/") ? relativePath.slice(0, relativePath.lastIndexOf("/")) : "";
      ctx.navigate("#/references?" + query({ path: dir, file: relativePath }));
    },
  });
}
