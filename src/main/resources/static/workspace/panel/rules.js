// Rules: grouped list, detail with scope, revision (preview → typed approval → activate) and the
// new-rule wizard (draft → server confirmation card → typed approval → promote).
import { api, h, query, relativeTime, statusChip, rawId, toast, openModal, field, counter, emptyState, errorBox, skeletonRows, fill } from "./core.js";
import { t } from "./i18n.js";

export async function renderRules(ctx) {
  if (ctx.params[0] === "new") return renderWizard(ctx);
  const selectedId = ctx.params[0] || null;
  const listHolder = h("div", skeletonRows(5));
  const detailHolder = h("div");
  ctx.main.append(
    h("div.page-head",
      h("div", h("h1", t("Kurallar")), h("p", t("Ajanların her oturumun başında yüklediği onaylı talimatlar. Genel kurallar her projede, proje kuralları yalnız o projede, modül ve düğüm kuralları belirli dizin ya da dosyalarda geçerlidir. Bir düğüme kural bağlamak için Kod grafı sekmesini kullan."))),
      h("div.page-actions", h("a.btn.primary", { href: "#/rules/new" }, t("Yeni kural")))),
    h("div.split" + (selectedId ? ".with-detail" : ""), listHolder, selectedId ? detailHolder : null),
  );

  let page;
  try {
    page = await api("items?" + query({ kind: "rule", project: ctx.project, status: "active", limit: 100 }), { signal: ctx.signal });
  } catch (error) {
    if (!ctx.live() || error.name === "AbortError") return;
    fill(listHolder, errorBox(error.message, () => ctx.navigate(location.hash)));
    return;
  }
  if (!ctx.live()) return;
  const rules = page.items || [];
  if (!rules.length) {
    fill(listHolder, emptyState(t("Etkin kural yok"),
      t("Kural, ajanların her oturumda uyacağı kalıcı bir talimattır. İlk kuralı yazıp onayladığında ajanlar bir sonraki oturumda yükler."),
      h("a.btn.primary", { href: "#/rules/new" }, t("Yeni kural"))));
  } else {
    const groups = [
      [t("Genel"), t("Her projede"), rules.filter((r) => !r.project)],
      [t("Proje"), ctx.project || t("Proje geneli"), rules.filter((r) => r.project && r.info?.appliesAll !== false)],
      [t("Modül ve düğüm"), t("Belirli dizinler, dosyalar ve metotlar"), rules.filter((r) => r.project && r.info?.appliesAll === false)],
    ].filter(([, , list]) => list.length);
    fill(listHolder, ...groups.map(([title, note, list]) =>
      h("section.rule-group", h("h3", title, h("small", `${note} · ${list.length}`)),
        list.map((rule) => h("a.rule", {
          href: "#/rules/" + encodeURIComponent(rule.id),
          style: "display:block;text-decoration:none" + (rule.id === selectedId ? ";border-color:var(--brass)" : ""),
        },
        h("div", rule.title),
        h("div.rule-meta", h("span", t("Sürüm {n}", { n: rule.info?.version ?? "?" })), h("span", rule.project || t("Tüm projeler")), h("span", relativeTime(rule.updatedAt))))))));
  }
  if (selectedId) renderRuleDetail(ctx, selectedId, detailHolder);
}

async function renderRuleDetail(ctx, id, holder) {
  const ruleId = rawId(id);
  fill(holder, h("aside.detail", h("div.skeleton")));
  let rule;
  let scope = null;
  try {
    rule = await api("items/rule/" + encodeURIComponent(ruleId), { signal: ctx.signal });
    if (rule.project) {
      scope = await api("rules/" + encodeURIComponent(ruleId) + "/scope?" + query({ project: rule.project, limit: 20 }), { signal: ctx.signal }).catch(() => null);
    }
  } catch (error) {
    if (!ctx.live() || error.name === "AbortError") return;
    fill(holder, h("aside.detail", errorBox(error.message)));
    return;
  }
  if (!ctx.live()) return;
  const info = rule.info || {};
  const targets = scope?.modulePaths || [];
  fill(holder, h("aside.detail",
    h("header",
      h("a.btn.quiet.close-detail", { href: "#/rules" }, t("Kapat")),
      h("div.meta", statusChip(rule.status), h("span.chip", rule.project || t("Genel")), h("span.chip", t("Sürüm {n}", { n: info.version ?? "?" }))),
      h("h2", t("Kural"))),
    h("div.content", rule.text || rule.title),
    h("dl",
      info.rationale ? [h("dt", t("Gerekçe")), h("dd", info.rationale)] : null,
      h("dt", t("Uygulanma")), h("dd", info.appliesAll === false ? t("Belirli dizinler / dosyalar") : rule.project ? t("Tüm proje") : t("Tüm projeler")),
      targets.length ? [h("dt", t("Hedefler")), h("dd", targets.map(targetText).join(", "))] : null,
      h("dt", t("Güncellendi")), h("dd", relativeTime(rule.updatedAt))),
    (rule.editableFields || []).length
      ? h("div.actions", h("button.btn", { onclick: () => reviseRule(ctx, rule, ruleId) }, t("Yeni sürüm hazırla")))
      : null,
  ));
}

function reviseRule(ctx, rule, ruleId) {
  const statement = h("textarea", { name: "statement", required: true, rows: 6 }, rule.text || rule.title);
  const rationale = h("textarea", { name: "rationale", rows: 2 }, rule.info?.rationale || "");
  openModal({
    title: t("Kuralın yeni sürümü"),
    submitLabel: t("Onay kartını göster"),
    build: () => [field(t("Kural metni"), statement), counter(statement, 16384, true), field(t("Gerekçe"), rationale), counter(rationale, 4096, true)],
    submit: async (data) => {
      const preview = await api("rules/" + encodeURIComponent(ruleId) + "/preview", {
        body: { statement: data.get("statement"), rationale: data.get("rationale") || null },
      });
      setTimeout(() => approveRevision(ctx, ruleId, preview), 0);
    },
  });
}

function approveRevision(ctx, ruleId, preview) {
  const card = preview.confirmation;
  const approval = h("textarea", { name: "approval", required: true, rows: 2, placeholder: t("Örn: Bu kartı onaylıyorum.") });
  openModal({
    title: t("Onay kartı"),
    submitLabel: t("Etkinleştir"),
    build: () => [
      h("p", t("Sunucunun hazırladığı kart aşağıda aynen gösteriliyor. Etkinleştirmek için kendi onay cümleni yaz.")),
      h("pre.card-preview", card.confirmationCard),
      h("div.hashes", `approval ${card.approvalContentHash} · card ${card.confirmationCardHash} · ${card.workflowContractVersion}`),
      field(t("Onay metnin"), approval),
    ],
    submit: async (data) => {
      await api("rules/" + encodeURIComponent(ruleId) + "/activate", {
        body: {
          edit: preview.edit,
          approvalContentHash: card.approvalContentHash,
          confirmationCardHash: card.confirmationCardHash,
          workflowContractVersion: card.workflowContractVersion,
          humanRawText: data.get("approval").trim(),
          humanTurnRef: "workspace-ui:" + Date.now(),
        },
      });
      toast(t("Kuralın yeni sürümü etkinleştirildi."));
      ctx.navigate(location.hash);
    },
  });
}

/** Three explicit steps; the server's card and hashes are shown and sent back unchanged. */
function renderWizard(ctx) {
  const holder = h("div");
  ctx.main.append(
    h("div.page-head", h("div", h("h1", t("Yeni kural")),
      h("p", t("Kural önce taslak olarak kaydedilir, sonra sunucunun onay kartını görürsün. Kartı kendi cümlenle onayladığında etkinleşir.")))),
    holder);
  mountRuleFlow(holder, {
    project: ctx.project,
    live: ctx.live,
    onDone: (result) => ctx.navigate("#/rules/" + encodeURIComponent("rule:" + result.ruleId)),
  });
}

const FIXED_LABELS = { global: t("Tüm projeler"), project: t("Bu proje"), node: t("Bu düğüm") };

/**
 * The rule flow: write → server confirmation card → typed approval → promote. Used by the rules page
 * (scope chosen with radios) and by the graph's detail card (`fixed` scope, optionally a node target).
 * fixed: {scope: "global"|"project"|"node", nodeTarget, targetLabel, notice, statement}
 */
export function mountRuleFlow(holder, { project, fixed = null, onDone, onCancel, live = () => true, compact = false }) {
  const steps = h("ol.steps");
  const body = h("div");
  fill(holder, steps, body);
  const width = compact ? "" : ";max-width:760px";

  const setStep = (current) => {
    fill(steps, ...[t("Kuralı yaz"), t("Onay kartını incele"), t("Etkinleştir")].map((label, index) =>
      h("li", { dataset: { state: index < current ? "done" : index === current ? "current" : "todo" } }, `${index + 1}. ${label}`)));
  };

  const scopeChoice = fixed
    ? h("div.scope-fixed",
        h("span.scope-label", t("Kapsam")),
        h("strong", FIXED_LABELS[fixed.scope] || fixed.scope),
        fixed.targetLabel ? h("span.scope-target", fixed.targetLabel) : null)
    : h("div.choice", { style: "grid-template-columns:repeat(3,1fr)" },
        radio("scope", "project", t("Bu proje"), project || t("Önce üstten proje seç"), !!project, !project),
        radio("scope", "module", t("Belirli dizinler"), t("Örn: core/**"), false, !project),
        radio("scope", "global", t("Tüm projeler"), t("Her projede geçerli"), !project, false));
  const notice = fixed?.notice ? h("p.delivery-note", fixed.notice) : null;
  const globs = h("input", { name: "globs", placeholder: "core/**, api/src/**" });
  const globField = field(t("Dizin desenleri"), globs, t("Virgülle ayır. Yalnız bu dizinlerde çalışırken yüklenir."));
  globField.hidden = true;
  if (!fixed) scopeChoice.addEventListener("change", () => { globField.hidden = scopeChoice.querySelector("input:checked").value !== "module"; });
  const statement = h("textarea", { name: "statement", required: true, rows: 6, placeholder: t("Ajanın uyması gereken talimat, tek ve net") }, fixed?.statement || "");
  const rationale = h("textarea", { name: "rationale", rows: 2, placeholder: t("Neden? (isteğe bağlı)") });
  const error = h("p.form-error", { hidden: true, role: "alert" });
  const next = h("button.btn.primary", { type: "submit" }, t("Taslağı kaydet ve kartı göster"));
  const form = h("form.section.rule-flow", { style: "padding:20px;display:flex;flex-direction:column;gap:14px" + width },
    scopeChoice, notice, globField, field(t("Kural"), statement), counter(statement, 16384, true), field(t("Gerekçe"), rationale), counter(rationale, 4096, true), error,
    h("div.page-actions", onCancel ? h("button.btn.quiet", { type: "button", onclick: onCancel }, t("Vazgeç")) : null, next));
  let busy = false;
  form.onsubmit = async (event) => {
    event.preventDefault();
    if (busy) return;
    busy = true;
    next.disabled = true;
    error.hidden = true;
    try {
      const scope = fixed ? fixed.scope : scopeChoice.querySelector("input:checked").value;
      const draft = await api("rules/draft", {
        body: {
          project: scope === "global" ? "" : project,
          scope,
          statement: statement.value.trim(),
          rationale: rationale.value.trim() || null,
          moduleGlobs: scope === "module" ? globs.value.split(",").map((g) => g.trim()).filter(Boolean) : [],
          ...(scope === "node" ? { nodeTarget: fixed.nodeTarget } : {}),
        },
      });
      const preview = await api("rules/draft/preview", { body: { draftId: draft.draftId, project: draft.projectKey || "" } });
      if (!live()) return;
      showCard(preview);
    } catch (err) {
      error.textContent = err.message;
      error.hidden = false;
    } finally {
      busy = false;
      next.disabled = false;
    }
  };

  /** The server card is raw JSON; this is the same content in words, shown above it. */
  const summaryFor = (scope, text) => {
    const where = fixed?.notice ? "" : (scope === "global" ? t("Her projede, her ajan oturumunun başında yüklenir.")
        : scope === "project" ? t("{project} projesinde, her ajan oturumunun başında yüklenir.", { project })
        : scope === "module" ? t("{project} projesinde yalnız şu dizinlerde çalışırken gösterilir: {globs}",
          { project, globs: globs.value.split(",").map((g) => g.trim()).filter(Boolean).join(", ") })
        : "");
    return h("dl.card-summary",
      h("dt", t("Kapsam")), h("dd", (FIXED_LABELS[scope] || (scope === "module" ? t("Belirli dizinler") : scope)) + (fixed?.targetLabel ? " · " + fixed.targetLabel : scope === "project" ? " · " + project : "")),
      h("dt", t("Kural")), h("dd.statement", text),
      where ? [h("dt", t("Nerede gösterilir")), h("dd.where", where)] : null);
  };

  const prettyCard = (card) => {
    try { return JSON.stringify(JSON.parse(card), null, 2); } catch { return String(card ?? ""); }
  };

  const showCard = (preview) => {
    setStep(1);
    const scopeNow = fixed ? fixed.scope : scopeChoice.querySelector("input:checked").value;
    const approval = h("textarea", { name: "approval", required: true, rows: 2, placeholder: t("Örn: Bu kuralı onaylıyorum.") });
    const promote = h("button.btn.primary", { type: "submit" }, t("Kuralı etkinleştir"));
    const cardError = h("p.form-error", { hidden: true, role: "alert" });
    const approveForm = h("form.section.rule-flow", { style: "padding:20px;display:flex;flex-direction:column;gap:14px" + (compact ? "" : ";max-width:860px") },
      h("h3.card-title", t("Onaylayacağın kural")),
      summaryFor(scopeNow, statement.value.trim()),
      fixed?.notice ? h("p.delivery-note.on-card", h("strong", t("Nerede gösterilir: ")), fixed.notice) : null,
      h("p.card-caption", t("Sunucunun onay kartı (değiştirilmeden, okunur biçimde). Onayın bu karta bağlanır.")),
      h("pre.card-preview", prettyCard(preview.confirmationCard)),
      h("div.hashes", `candidate ${preview.candidateHash} · approval ${preview.approvalContentHash} · card ${preview.confirmationCardHash} · ${preview.workflowContractVersion}`),
      field(t("Onay metnin"), approval, t("Bu metin kendi onayın olarak denetim kaydına yazılır.")),
      cardError,
      h("div.page-actions", h("button.btn.quiet", { type: "button", onclick: () => { setStep(0); fill(body, form); } }, t("Geri dön")), promote));
    let promoting = false;
    approveForm.onsubmit = async (event) => {
      event.preventDefault();
      if (promoting) return;
      promoting = true;
      promote.disabled = true;
      cardError.hidden = true;
      setStep(2);
      try {
        const result = await api("rules/draft/promote", {
          body: {
            draftId: preview.draftId, project: preview.projectKey || "",
            candidateHash: preview.candidateHash, approvalContentHash: preview.approvalContentHash,
            confirmationCardHash: preview.confirmationCardHash, workflowContractVersion: preview.workflowContractVersion,
            humanRawText: approval.value.trim(),
          },
        });
        toast(t("Kural etkinleştirildi. Ajanlar bir sonraki oturumda yükleyecek."));
        await onDone?.(result, preview);
      } catch (err) {
        setStep(1);
        cardError.textContent = err.message;
        cardError.hidden = false;
      } finally {
        promoting = false;
        promote.disabled = false;
      }
    };
    fill(body, approveForm);
    approval.focus({ preventScroll: true });
    steps.scrollIntoView({ block: "nearest" });
  };

  setStep(0);
  body.append(form);
  statement.focus();
  if (fixed?.statement) statement.setSelectionRange(statement.value.length, statement.value.length);
}

/** A directory target is shown as its glob; a single-file (or already globbed) target as-is. */
function targetText(path) {
  const value = String(path || "");
  if (value.endsWith("/**") || /\.[A-Za-z0-9]+$/.test(value.split("/").pop()) || value.includes("#")) return value;
  return value + "/**";
}

function radio(name, value, label, note, checked, disabled) {
  return h("label", h("input", { type: "radio", name, value, checked, disabled }), label, h("small", note));
}
