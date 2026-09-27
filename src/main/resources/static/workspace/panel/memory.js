// Memory: search, filter, detail, create, edit, revalidate/invalidate, archive and permanent delete.
import {
  api, h, query, relativeTime, statusChip, TYPE_LABELS, rawId, shortId, toast, openModal, field,
  counter, emptyState, errorBox, skeletonRows, debounce, fill } from "./core.js";
import { t, locale } from "./i18n.js";

const STATUSES = [["", t("Tüm durumlar")], ["active", t("Etkin")], ["pending_review", t("Onay bekliyor")], ["archived", t("Arşivde")], ["rejected", t("Reddedildi")], ["superseded", t("Yerini yenisi aldı")], ["invalidated", t("Geçersiz")]];
const TYPES = [["", t("Tüm türler")], ["decision", t("Karar")], ["discovery", t("Öğrenilen bilgi")], ["correction", t("Düzeltme")], ["anti_pattern", t("Kaçınılacak durum")], ["preference", t("Tercih")], ["rule", t("Kural kaynağı")]];

export async function renderMemory(ctx) {
  const selectedId = ctx.params[0] && ctx.params[0] !== "new" ? ctx.params[0] : null;
  const filters = {
    query: ctx.search.get("q") || "",
    status: ctx.search.get("status") === "all" ? "" : (ctx.search.get("status") ?? "active"),
    type: ctx.search.get("type") || "",
    scope: ctx.search.get("scope") || "",
    tag: ctx.search.get("tag") || "",
  };

  const searchInput = h("input", { type: "search", value: filters.query, placeholder: t("Özet veya içerikte ara"), "aria-label": t("Hafızada ara") });
  const statusSelect = h("select", { "aria-label": t("Durum") }, STATUSES.map(([v, l]) => h("option", { value: v, selected: v === filters.status }, l)));
  const typeSelect = h("select", { "aria-label": t("Tür") }, TYPES.map(([v, l]) => h("option", { value: v, selected: v === filters.type }, l)));
  const scopeSelect = h("select", { "aria-label": t("Kapsam") },
    [["", t("Tüm kapsamlar")], ["project", t("Bu proje")], ["global", t("Tüm projeler (genel)")]].map(([v, l]) => h("option", { value: v, selected: v === filters.scope }, l)));
  const tagInput = h("input", { type: "search", value: filters.tag, placeholder: t("Etiket"), "aria-label": t("Etikete göre süz"), style: "width:140px" });
  const countNode = h("span.count");
  const listHolder = h("div", skeletonRows());
  const detailHolder = h("div");
  const split = h("div.split" + (selectedId ? ".with-detail" : ""), listHolder, selectedId ? detailHolder : null);

  ctx.main.append(
    h("div.page-head",
      h("div", h("h1", t("Hafıza")), h("p", t("Ajanların kaydettiği ve senin eklediğin bilgiler. Bir kayda tıklayıp inceleyebilir, düzeltebilir ya da silebilirsin."))),
      h("div.page-actions", h("button.btn.primary", { onclick: () => createMemory(ctx) }, t("Yeni kayıt")))),
    h("div.toolbar",
      h("label.search", h("span.visually-hidden", t("Ara")), iconSearch(), searchInput),
      statusSelect, typeSelect, scopeSelect, tagInput, countNode),
    split,
  );

  const reflect = () => {
    const params = query({ q: searchInput.value, status: statusSelect.value || "all", type: typeSelect.value,
      scope: scopeSelect.value, tag: tagInput.value.trim() });
    const base = "#/memory" + (selectedId ? "/" + encodeURIComponent(selectedId) : "");
    history.replaceState(null, "", base + (params ? "?" + params : ""));
  };
  let items = [];
  let cursor = null;
  let listSeq = 0;

  async function loadList(append = false) {
    const seq = ++listSeq;
    if (!append) fill(listHolder, skeletonRows());
    try {
      const page = await api("items?" + query({
        kind: "memory", project: ctx.project, query: searchInput.value.trim(),
        status: statusSelect.value, type: typeSelect.value, scope: scopeSelect.value, tag: tagInput.value.trim(),
        limit: 30, cursor: append ? cursor : null,
      }), { signal: ctx.signal });
      if (!ctx.live() || seq !== listSeq) return;
      items = append ? items.concat(page.items) : page.items;
      cursor = page.nextCursor;
      renderList();
    } catch (error) {
      if (!ctx.live() || seq !== listSeq || error.name === "AbortError") return;
      fill(listHolder, errorBox(error.message, () => loadList()));
    }
  }

  function renderList() {
    countNode.textContent = items.length ? t("{n} kayıt", { n: `${items.length}${cursor ? "+" : ""}` }) : "";
    if (!items.length) {
      fill(listHolder, emptyState(
        searchInput.value || typeSelect.value || scopeSelect.value || tagInput.value ? t("Bu filtreyle kayıt yok") : t("Bu projede henüz hafıza yok"),
        searchInput.value || typeSelect.value || scopeSelect.value || tagInput.value
          ? t("Aramayı veya filtreleri değiştir.")
          : t("Ajanlar görevleri bitirdikçe öğrendiklerini buraya kaydeder. İlk kaydı kendin de ekleyebilirsin."),
        h("button.btn.primary", { onclick: () => createMemory(ctx) }, t("Yeni kayıt"))));
      return;
    }
    fill(listHolder, 
      h("div.rows", { role: "listbox", "aria-label": t("Hafıza kayıtları") },
        items.map((item) => h("button.row", {
          role: "option",
          "aria-selected": item.id === selectedId ? "true" : "false",
          onclick: () => ctx.navigate("#/memory/" + encodeURIComponent(item.id) + locationQuery()),
        },
        h("span.dot.st-" + String(item.status).toLowerCase(), { title: item.status }),
        h("div", h("div.t", item.title), h("div.s", [TYPE_LABELS[item.info?.memoryType] || item.info?.memoryType, item.project || t("Genel"), item.text].filter(Boolean).join(" · "))),
        h("span.when", relativeTime(item.updatedAt))))),
      cursor ? h("div.more", h("button.btn", { onclick: () => loadList(true) }, t("Daha fazla göster"))) : null,
    );
  }

  const refresh = debounce(() => { reflect(); loadList(); }, 250);
  searchInput.addEventListener("input", refresh);
  statusSelect.addEventListener("change", () => { reflect(); loadList(); });
  typeSelect.addEventListener("change", () => { reflect(); loadList(); });
  scopeSelect.addEventListener("change", () => { reflect(); loadList(); });
  tagInput.addEventListener("input", refresh);

  loadList();
  if (selectedId) renderDetail(ctx, selectedId, detailHolder, () => loadList());
  if (ctx.params[0] === "new") createMemory(ctx);
}

function locationQuery() {
  const index = location.hash.indexOf("?");
  return index >= 0 ? location.hash.slice(index) : "";
}

async function renderDetail(ctx, id, holder, reloadList) {
  const memoryId = rawId(id);
  fill(holder, h("aside.detail", h("div.skeleton"), h("div.skeleton")));
  let item;
  let neighbors = null;
  let events = [];
  try {
    [item, neighbors, events] = await Promise.all([
      api("items/memory/" + encodeURIComponent(memoryId), { signal: ctx.signal }),
      api("items/memory/" + encodeURIComponent(memoryId) + "/neighbors?limit=20", { signal: ctx.signal }).catch(() => null),
      api("memory/" + encodeURIComponent(memoryId) + "/events?limit=20", { signal: ctx.signal }).catch(() => []),
    ]);
  } catch (error) {
    if (!ctx.live() || error.name === "AbortError") return;
    fill(holder, h("aside.detail", h("div.body", error.status === 404
      ? emptyState(t("Kayıt bulunamadı"), t("Silinmiş ya da başka bir projeye ait olabilir."))
      : errorBox(error.message, () => renderDetail(ctx, id, holder, reloadList)))));
    return;
  }
  if (!ctx.live()) return;
  const info = item.info || {};
  const editable = (item.editableFields || []).length > 0;
  const active = String(item.status).toLowerCase() === "active";
  const links = (neighbors?.links || []).slice(0, 12);
  const nodeTitle = new Map((neighbors?.nodes || []).map((node) => [node.id, node]));

  const actions = h("div.actions",
    editable ? h("button.btn", { onclick: () => editMemory(ctx, item, memoryId) }, t("Düzenle")) : null,
    active && item.project ? h("button.btn", { onclick: () => lifecycle(ctx, item, memoryId, "REVALIDATE") }, t("Hâlâ doğru")) : null,
    active && item.project ? h("button.btn", { onclick: () => lifecycle(ctx, item, memoryId, "INVALIDATE") }, t("Artık geçerli değil")) : null,
    active && item.project ? h("button.btn", { onclick: () => supersede(ctx, item, memoryId) }, t("Yenisiyle değiştir")) : null,
    h("button.btn.danger", { onclick: () => deleteMemory(ctx, item, memoryId, reloadList) }, t("Sil")),
  );

  fill(holder, h("aside.detail", { "aria-label": t("Kayıt ayrıntısı") },
    h("header",
      h("a.btn.quiet.close-detail", { href: "#/memory" + locationQuery(), "aria-label": t("Ayrıntıyı kapat") }, t("Kapat")),
      h("div.meta", statusChip(item.status), h("span.chip", TYPE_LABELS[info.memoryType] || info.memoryType || t("Kayıt")), h("span.chip", item.project || t("Genel"))),
      h("h2", item.title)),
    h("div.content", item.text || ""),
    h("dl",
      h("dt", t("Kimlik")), h("dd", memoryId),
      h("dt", t("Güncellendi")), h("dd", `${new Date(item.updatedAt).toLocaleString(locale())} (${relativeTime(item.updatedAt)})`),
      item.tags?.length ? [h("dt", t("Etiketler")), h("dd", item.tags.join(", "))] : null,
      info.sourceRef ? [h("dt", t("Kaynak")), h("dd", info.sourceRef)] : null,
      info.scope ? [h("dt", t("Kapsam")), h("dd", info.scope === "global" ? t("Tüm projeler") : t("Bu proje"))] : null,
      item.blockedReason ? [h("dt", t("Not")), h("dd", item.blockedReason)] : null),
    actions,
    links.length ? h("div.links", h("h4", t("Bağlantılar")),
      links.map((link) => {
        const otherId = link.source === item.id ? link.target : link.source;
        const other = nodeTitle.get(otherId);
        return h("div.link-row", h("span", link.label || t("ilişkili")), h("span", other?.title || shortId(otherId)));
      })) : null,
    events.length ? h("div.links", h("h4", t("Geçmiş")),
      events.map((event) => h("div.link-row",
        h("span", EVENT_LABELS[event.type] || event.type),
        h("span", [relativeTime(event.at), event.metadata?.actor, event.metadata?.action].filter(Boolean).join(" · "))))) : null,
  ));
}

const EVENT_LABELS = {
  created: t("Oluşturuldu"), updated: t("Düzenlendi"), archived: t("Arşivlendi"), status_changed: t("Durum değişti"),
  approved: t("Onaylandı"), rejected: t("Reddedildi"), superseded: t("Yerini yenisi aldı"), revalidated: t("Doğrulandı"),
  invalidated: t("Geçersiz sayıldı"), deleted: t("Silindi"),
};

/**
 * New-memory dialog. From the graph, `preset` fixes the scope and carries the node's code locators:
 * {scope: "project"|"global", codeLocators, targetLabel, onSaved(result)}.
 */
export function createMemory(ctx, preset = null) {
  const summary = h("input", { name: "summary", required: true, maxLength: 160, placeholder: t("Tek cümlelik, aranabilir özet") });
  const content = h("textarea", { name: "content", required: true, maxLength: 700, rows: 5, placeholder: t("Tek bir doğrulanmış bilgi: ne, neden, ne zaman geçerli") });
  const type = h("select", { name: "type" }, TYPES.slice(1, 6).filter(([v]) => v !== "discovery").map(([v, l]) => h("option", { value: v }, l)));
  const tags = h("input", { name: "tags", placeholder: t("virgülle ayır") });
  const presetProject = preset?.scope === "project";
  const scope = h("div.choice",
    h("label", h("input", { type: "radio", name: "scope", value: "project", checked: preset ? presetProject : !!ctx.project, disabled: !ctx.project }),
      t("Bu proje"), h("small", ctx.project || t("Önce üstten proje seç"))),
    h("label", h("input", { type: "radio", name: "scope", value: "global", checked: preset ? !presetProject : !ctx.project }),
      t("Tüm projeler"), h("small", t("Her projede geçerli genel bilgi"))));
  const locators = preset?.codeLocators || [];
  const target = preset?.targetLabel
    ? h("div.attach-target", h("span", t("Bağlanacak kod")), h("code", preset.targetLabel),
        h("small", t("Kod bağlantısı yalnız “Bu proje” kapsamında kaydedilir; genel kayıtlar hiçbir koda bağlanmaz.")))
    : null;
  openModal({
    title: preset?.targetLabel ? t("Düğüme hafıza ekle") : t("Hafızaya ekle"),
    submitLabel: t("Kaydet"),
    build: () => [
      field(t("Özet"), summary), counter(summary, 160),
      field(t("İçerik"), content, t("Sunucudaki kalite kapısı tekrar eden, çok parçalı veya hassas içeriği reddeder.")), counter(content, 700),
      field(t("Tür"), type), field(t("Etiketler"), tags), target, scope,
    ],
    submit: async (data) => {
      const result = await api("memory", {
        body: {
          summary: data.get("summary").trim(),
          content: data.get("content").trim(),
          memoryType: data.get("type"),
          tags: splitTags(data.get("tags")),
          project: data.get("scope") === "project" ? ctx.project : "",
          ...(preset ? { scope: data.get("scope"), codeLocators: data.get("scope") === "project" ? locators : [] } : {}),
        },
      });
      if (!result.memoryId) {
        const reason = result.gateReason === "duplicate_memory" ? t("Aynı bilgi zaten kayıtlı.") : (result.gateReason || t("kalite kapısı reddetti"));
        throw new Error(t("Kaydedilmedi: ") + reason + (result.suggestedQuestion ? " " + result.suggestedQuestion : ""));
      }
      toast(t("Hafızaya kaydedildi."));
      if (preset?.onSaved) await preset.onSaved(result);
      else ctx.navigate("#/memory/" + encodeURIComponent("memory:" + result.memoryId));
    },
  });
}

function editMemory(ctx, item, memoryId) {
  const summary = h("input", { name: "summary", value: item.title, required: true, maxLength: 16384 });
  const text = h("textarea", { name: "text", required: true, rows: 8 }, item.text || "");
  const tags = h("input", { name: "tags", value: (item.tags || []).join(", ") });
  const reason = h("textarea", { name: "reason", required: true, rows: 2, placeholder: t("Neden değiştiriyorsun? (denetim kaydına yazılır)") });
  openModal({
    title: t("Kaydı düzenle"),
    build: () => [field(t("Özet"), summary), field(t("İçerik"), text), field(t("Etiketler"), tags), field(t("Değişiklik nedeni"), reason)],
    submit: async (data) => {
      await api("memory/" + encodeURIComponent(memoryId), {
        body: { summary: data.get("summary"), text: data.get("text"), tags: splitTags(data.get("tags")), reason: data.get("reason") },
      });
      toast(t("Değişiklik kaydedildi."));
      ctx.navigate(location.hash);
    },
  });
}

async function lifecycle(ctx, item, memoryId, decision) {
  let preview;
  try {
    preview = await api("memory/lifecycle/preview", {
      body: { decision, projectKey: item.project, memoryId, replacementId: null, proposalId: null },
    });
  } catch (error) {
    toast(error.message, "error");
    return;
  }
  const revalidate = decision === "REVALIDATE";
  const reason = h("textarea", { name: "reason", required: true, rows: 2, placeholder: revalidate ? t("Neye bakarak doğruladın?") : t("Neden artık geçerli değil?") });
  const code = preview.codeEvidence;
  openModal({
    title: revalidate ? t("Kaydı doğrula") : t("Kaydı geçersiz say"),
    submitLabel: revalidate ? t("Doğrula") : t("Geçersiz say"),
    danger: !revalidate,
    build: () => [
      h("p", preview.summary),
      code ? h("p.hint", t("Bağlı kod hedefi: {n}", { n: code.targets?.length || 0 })
        + (code.missingTargets ? t(", bulunamayan: {n}", { n: code.missingTargets }) : "")) : null,
      field(t("Açıklama"), reason),
    ],
    submit: async (data) => {
      await api("memory/lifecycle/decide", {
        body: {
          selection: preview.selection, previewHash: preview.previewHash, humanConfirmed: true,
          reason: data.get("reason"), evidence: data.get("reason"),
          humanRawText: (revalidate ? t("Panel: Hâlâ doğru — ") : t("Panel: Artık geçerli değil — ")) + data.get("reason"),
          humanTurnRef: "workspace-ui:" + Date.now(),
        },
      });
      toast(revalidate ? t("Kayıt doğrulandı.") : t("Kayıt geçersiz sayıldı."));
      ctx.navigate(location.hash);
    },
  });
}

/** SUPERSEDE: pick the active record that replaces this one, then confirm the server preview. */
function supersede(ctx, item, memoryId) {
  const search = h("input", { type: "search", name: "q", placeholder: t("Yerine geçecek kaydı ara"), autocomplete: "off" });
  const results = h("div.rows", { style: "max-height:240px;overflow:auto" });
  const reason = h("textarea", { name: "reason", required: true, rows: 2, placeholder: t("Neden yenisiyle değiştiriyorsun?") });
  let chosen = null;
  let seq = 0;
  const load = debounce(async () => {
    const mine = ++seq;
    const page = await api("items?" + query({ kind: "memory", project: item.project, status: "active", query: search.value.trim(), limit: 10 }))
      .catch(() => ({ items: [] }));
    if (mine !== seq) return;
    fill(results, (page.items || []).filter((c) => rawId(c.id) !== memoryId).map((c) =>
      h("button.row", { type: "button", "aria-selected": chosen === rawId(c.id) ? "true" : "false", onclick: (e) => {
        chosen = rawId(c.id);
        results.querySelectorAll(".row").forEach((r) => r.setAttribute("aria-selected", "false"));
        e.currentTarget.setAttribute("aria-selected", "true");
      } }, h("span.dot.st-active"), h("div", h("div.t", c.title)), h("span.when", shortId(c.id)))));
  }, 250);
  search.addEventListener("input", load);
  load();
  openModal({
    title: t("Yenisiyle değiştir"),
    submitLabel: t("Değiştir"),
    build: () => [h("p", item.title), field(t("Yerine geçecek kayıt"), search), results, field(t("Açıklama"), reason)],
    submit: async (data) => {
      if (!chosen) throw new Error(t("Yerine geçecek kaydı seçmelisin."));
      const preview = await api("memory/lifecycle/preview", {
        body: { decision: "SUPERSEDE", projectKey: item.project, memoryId, replacementId: chosen, proposalId: null },
      });
      await api("memory/lifecycle/decide", {
        body: {
          selection: preview.selection, previewHash: preview.previewHash, humanConfirmed: true,
          reason: data.get("reason"), evidence: data.get("reason"),
          humanRawText: t("Panel: Yenisiyle değiştir — ") + data.get("reason"),
          humanTurnRef: "workspace-ui:" + Date.now(),
        },
      });
      toast(t("Kaydın yerini yenisi aldı."));
      ctx.navigate("#/memory/" + encodeURIComponent("memory:" + chosen));
    },
  });
}

function deleteMemory(ctx, item, memoryId, reloadList) {
  const prefix = memoryId.slice(0, 8);
  const confirmInput = h("input", { name: "confirm", autocomplete: "off", placeholder: prefix, "aria-label": t("Onay için kimliğin ilk 8 karakteri") });
  const confirmField = field(t("Kalıcı silmeyi onaylamak için {prefix} yaz", { prefix }), confirmInput);
  confirmField.hidden = true;
  const reason = h("textarea", { name: "reason", required: true, rows: 2, placeholder: t("Neden siliyorsun? (denetim kaydına yazılır)") });
  const choice = h("div.choice",
    h("label", h("input", { type: "radio", name: "mode", value: "archive", checked: true }),
      t("Arşivle"), h("small", t("Geri alınabilir; kayıt ve denetim izi kalır, aramalarda çıkmaz."))),
    h("label.danger-choice", h("input", { type: "radio", name: "mode", value: "hard_delete" }),
      t("Kalıcı sil"), h("small", t("Geri alınamaz; kayıt ve olay geçmişi silinir."))));
  choice.addEventListener("change", () => { confirmField.hidden = choice.querySelector("input:checked").value !== "hard_delete"; });
  openModal({
    title: t("Kaydı sil"),
    submitLabel: t("Sil"),
    danger: true,
    build: () => [h("p", item.title), choice, field(t("Neden"), reason), confirmField],
    submit: async (data) => {
      const mode = data.get("mode");
      const body = { mode, reason: data.get("reason") };
      if (mode === "hard_delete") {
        const typed = (data.get("confirm") || "").trim();
        if (typed !== prefix) throw new Error(t("Kalıcı silme için {prefix} yazmalısın.", { prefix }));
        Object.assign(body, { humanConfirmed: true, humanRawText: typed });
      }
      await api("memory/" + encodeURIComponent(memoryId) + "/delete", { body });
      if (mode === "hard_delete") {
        toast(t("Kayıt kalıcı olarak silindi."));
        ctx.navigate("#/memory" + locationQuery());
      } else {
        toast(t("Kayıt arşivlendi."));
        ctx.navigate(location.hash);
      }
      reloadList();
    },
  });
}

function splitTags(value) {
  return String(value || "").split(",").map((t) => t.trim()).filter(Boolean);
}

function iconSearch() {
  const node = document.createElementNS("http://www.w3.org/2000/svg", "svg");
  node.setAttribute("viewBox", "0 0 20 20");
  node.innerHTML = '<circle cx="9" cy="9" r="5"/><path d="M13 13l4 4"/>';
  return node;
}
