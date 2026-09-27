// Overview: the project's knowledge as strata (memory types), then facts, recent changes and pending work.
import { api, h, query, relativeTime, statusChip, TYPE_LABELS, errorBox, fill } from "./core.js";
import { t, locale } from "./i18n.js";

const STRATA_COLORS = {
  decision: "#c8a15a",
  discovery: "#5e9e92",
  correction: "#7b93c9",
  anti_pattern: "#cf5a4b",
  preference: "#b58bb8",
  rule: "#d9a441",
};

export async function renderOverview(ctx) {
  const scope = ctx.project || t("tüm projeler");
  ctx.main.append(
    h("div.page-head",
      h("div", h("h1", t("Genel bakış")), h("p", t("Ajanların bu çalışma alanında bildikleri: kararlar, öğrenilen bilgiler ve kurallar."))),
      h("div.page-actions",
        h("a.btn", { href: "#/memory/new" }, t("Hafızaya ekle")),
        h("a.btn", { href: "#/rules/new" }, t("Kural yaz")))),
  );
  const holder = h("div", h("div.strata-wrap", h("div.skeleton")));
  ctx.main.append(holder);

  let stats;
  let pending;
  try {
    [stats, pending] = await Promise.all([
      api("stats?" + query({ project: ctx.project }), { signal: ctx.signal }),
      api("memory/pending?" + query({ project: ctx.project, limit: 5 }), { signal: ctx.signal }).catch(() => null),
    ]);
  } catch (error) {
    if (!ctx.live()) return;
    fill(holder, errorBox(error.message, () => ctx.navigate("#/overview")));
    return;
  }
  if (!ctx.live()) return;

  const byType = stats.memoryByType || {};
  const activeTotal = Object.values(byType).reduce((a, b) => a + b, 0);
  const byStatus = stats.memoryByStatus || {};
  const rules = stats.rulesByStatus || {};

  const strata = activeTotal
    ? h("div.strata", { role: "img", "aria-label": t("Etkin hafızanın türlere göre dağılımı") },
        Object.entries(byType).sort((a, b) => b[1] - a[1]).map(([type, count]) =>
          h("button.stratum", {
            style: `flex:${count};background:${STRATA_COLORS[type] || "#74808c"}`,
            title: `${TYPE_LABELS[type] || type}: ${count}`,
            "aria-label": t("{type}: {n} kayıt", { type: TYPE_LABELS[type] || type, n: count }),
            onclick: () => ctx.navigate("#/memory?type=" + type),
          })))
    : h("div.strata", h("div.stratum", { style: "flex:1;background:#243343" }));

  const legend = h("div.strata-legend",
    Object.entries(byType).sort((a, b) => b[1] - a[1]).map(([type, count]) =>
      h("button", { onclick: () => ctx.navigate("#/memory?type=" + type) },
        h("i", { style: `background:${STRATA_COLORS[type] || "#74808c"}` }),
        TYPE_LABELS[type] || type, h("b", String(count)))));

  fill(holder, 
    h("section.strata-wrap",
      h("div.strata-title",
        h("h2", activeTotal ? t("{n} etkin kayıt", { n: activeTotal }) : t("Henüz etkin kayıt yok"), " · ", h("em", scope)),
        h("span.strata-total", t("{pending} onay bekliyor · {archived} arşivde", { pending: byStatus.pending_review || 0, archived: byStatus.archived || 0 }))),
      strata,
      activeTotal ? legend : h("p.strata-legend", t("Ajanlar görevlerde öğrendikçe katmanlar burada birikir. İlk kaydı kendin de ekleyebilirsin."))),
  );

  const facts = h("dl.facts",
    fact(t("Etkin kural"), rules.active || 0),
    fact(t("Taslak / beklemede"), (rules.draft || 0) + (rules.pending || 0) + (rules.pending_review || 0)),
    fact(t("Taranmış dosya"), stats.files || 0),
    fact(t("Kod sembolü"), stats.symbols || 0));

  const recent = (stats.recentMemories || []).length
    ? h("div.rows", stats.recentMemories.map((row) =>
        h("a.row", { href: "#/memory/" + encodeURIComponent("memory:" + row.id) },
          h("span.dot.st-" + String(row.status).toLowerCase()),
          h("div", h("div.t", row.title), h("div.s", [TYPE_LABELS[row.type] || row.type, row.project].filter(Boolean).join(" · "))),
          h("span.when", relativeTime(row.updatedAt)))))
    : h("div.body", h("p", t("Son değişiklik yok.")));

  const pendingItems = pending?.items || [];
  const pendingBlock = pendingItems.length
    ? h("div.callout",
        h("span", t("{n} kayıt senin onayını bekliyor.", { n: pending.count ?? pendingItems.length })),
        h("a.btn.primary", { href: "#/pending" }, t("İncele")))
    : null;

  // append() stringifies null, so the pending callout is only added when there is something to review.
  if (pendingBlock) ctx.main.append(h("div", { style: "margin-top:20px" }, pendingBlock));
  ctx.main.append(
    h("div.overview-grid",
      h("section.section", h("h3", t("Son değişen kayıtlar"), h("a", { href: "#/memory" }, t("Tüm hafıza"))), recent),
      h("section.section", h("h3", t("Durum"), h("a", { href: "#/graph" }, t("Kod grafında aç"))),
        facts,
        h("div.body", h("div.meta", Object.entries(byStatus).map(([status, count]) =>
          h("span", { style: "margin-right:14px" }, statusChip(status), " ", String(count))))))),
  );
}

function fact(label, value) {
  return h("div.fact", h("dt", label), h("dd", Number(value).toLocaleString(locale())));
}
