// Pending: memory proposals that wait for the human's approve / reject decision.
import { api, h, query, toast, openModal, field, emptyState, errorBox, skeletonRows, TYPE_LABELS, fill } from "./core.js";
import { t } from "./i18n.js";

export async function renderPending(ctx) {
  const holder = h("div", skeletonRows(4));
  ctx.main.append(
    h("div.page-head", h("div", h("h1", t("Onay bekleyen kayıtlar")),
      h("p", t("Ajanların önerdiği ama senin kararını bekleyen bilgiler. Onayladığın kayıt aranabilir hale gelir; reddettiğin kayıt kullanılmaz.")))),
    holder,
  );
  const PAGE = 25;
  let offset = 0;
  const cards = h("div");
  const more = h("div.more");
  async function loadPage() {
    let data;
    try {
      data = await api("memory/pending?" + query({ project: ctx.project, limit: PAGE, offset }), { signal: ctx.signal });
    } catch (error) {
      if (!ctx.live() || error.name === "AbortError") return;
      fill(holder, errorBox(error.message, () => ctx.navigate("#/pending")));
      return;
    }
    if (!ctx.live()) return;
    const items = data.items || [];
    if (!items.length && offset === 0) {
      fill(holder, emptyState(t("Onay bekleyen kayıt yok"), t("Yeni öneri geldiğinde burada ve sol menüdeki rozette görünür.")));
      return;
    }
    cards.append(...items.map((card) => pendingCard(ctx, card)));
    offset += items.length;
    const total = data.count ?? offset;
    fill(more, offset < total ? h("button.btn", { onclick: loadPage }, t("Daha fazla göster ({n})", { n: total - offset })) : null);
    fill(holder, cards, more);
  }
  await loadPage();
}

function pendingCard(ctx, card) {
  const node = h("section.rule",
    h("div", { style: "display:flex;justify-content:space-between;gap:16px;align-items:flex-start" },
      h("div",
        h("strong", card.summary || t("Adsız öneri")),
        h("div.rule-meta",
          h("span", TYPE_LABELS[card.type] || card.type || t("Kayıt")),
          h("span", card.projectKey || t("Genel")),
          card.scopeDecisionReason ? h("span", card.scopeDecisionReason) : null)),
      h("div.page-actions",
        h("button.btn.primary", { onclick: () => decide(ctx, card, "approve") }, t("Onayla")),
        h("button.btn.danger", { onclick: () => decide(ctx, card, "reject") }, t("Reddet")))),
    h("p", { style: "white-space:pre-wrap;margin:10px 0 0" }, card.proposedContent || ""),
  );
  return node;
}

function decide(ctx, card, decision) {
  const approve = decision === "approve";
  const note = h("textarea", { name: "note", rows: 2, placeholder: approve ? t("İsteğe bağlı not") : t("Neden reddediyorsun? (isteğe bağlı)") });
  openModal({
    title: approve ? t("Kaydı onayla") : t("Kaydı reddet"),
    submitLabel: approve ? t("Onayla") : t("Reddet"),
    danger: !approve,
    build: () => [h("p", card.summary || ""), field(t("Not"), note)],
    submit: async (data) => {
      await api("memory/" + encodeURIComponent(card.memoryId) + "/confirm", {
        body: { decision, note: data.get("note") || "", project: card.projectKey || "" },
      });
      toast(approve ? t("Kayıt onaylandı.") : t("Kayıt reddedildi."));
      ctx.refreshBadges();
      ctx.navigate("#/pending");
    },
  });
}
