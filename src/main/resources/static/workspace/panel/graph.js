// Graph tab (#/graph): the layered code graph inside the panel. Selecting a node opens its detail card;
// from there a memory or a rule can be attached to the node (or added for the project / globally).
import { h, emptyState, fill } from "./core.js";
import { t } from "./i18n.js";
import { createMemory } from "./memory.js";
import { mountRuleFlow } from "./rules.js";
import { openAddProjectDialog } from "./projects.js";

/** Code locator for a memory attached to a node (package → DIRECTORY, class/file → FILE, member → SYMBOL). */
export function locatorFor(node, memberTarget) {
  if (node.kind === "package") return { kind: "DIRECTORY", ref: node.path };
  if (node.kind === "class" || node.kind === "file") return { kind: "FILE", ref: node.path };
  if (node.kind === "member") return { kind: "SYMBOL", ref: memberTarget(node), path: node.path };
  return null;
}

/** Rule node target (package / file / class / member); the project node has none. */
export function nodeTargetFor(node) {
  if (!["package", "file", "class", "member"].includes(node.kind)) return null;
  const target = { kind: node.kind, path: node.path };
  if (node.fqn) target.fqn = node.fqn;
  if (node.signature) target.signature = node.signature;
  return target;
}

export async function renderGraph(ctx) {
  const wanted = ctx.search.get("project");
  if (wanted && wanted !== ctx.project && ctx.switchProject) {
    ctx.switchProject(wanted, "#/graph?" + new URLSearchParams({ node: ctx.search.get("node") || "" }));
    return;
  }
  if (!ctx.project) {
    ctx.main.append(
      h("div.page-head", h("div", h("h1", t("Kod grafı")), h("p", t("Projenin paketleri, sınıfları ve metotları katman katman.")))),
      emptyState(t("Önce bir proje seç"), t("Kod grafı tek bir projeyi gösterir. Üstten bir proje seç ya da yeni bir klasör ekle."),
        h("button.btn.primary", { onclick: () => openAddProjectDialog(ctx.onProjectAdded) }, t("Proje ekle"))));
    return;
  }
  ctx.main.classList.add("bleed");
  const host = h("div.graph-host");
  const drawer = h("div.graph-drawer", { hidden: true, role: "dialog", "aria-label": t("Kural ekle") });
  const frame = h("div.graph-frame", host, drawer);
  ctx.main.append(frame);

  const [{ createGraphView }, { memberTarget }, { deliveryText }] = await Promise.all([
    import("/graph/view.js"), import("/graph/data.js"), import("/graph/detail.js"),
  ]);
  if (!ctx.live()) return;

  const closeDrawer = () => { drawer.hidden = true; drawer.replaceChildren(); };

  function openRuleFlow(view, node, scope) {
    const target = scope === "node" ? nodeTargetFor(node) : null;
    const label = scope === "global" ? t("Her projede geçerli")
      : scope === "project" ? ctx.project
      : `${node.kind === "member" ? memberTarget(node) : node.path} (${{ package: t("paket"), class: t("sınıf"), file: t("dosya"), member: t("metot") }[node.kind]})`;
    const holder = h("div.drawer-body");
    fill(drawer,
      h("header.drawer-head",
        h("div", h("span.drawer-kicker", t("Kural ekle")), h("h2", node.name)),
        h("button.btn.quiet", { type: "button", onclick: closeDrawer, "aria-label": t("Kapat") }, t("Kapat"))),
      holder);
    drawer.hidden = false;
    mountRuleFlow(holder, {
      project: ctx.project,
      compact: true,
      live: ctx.live,
      fixed: {
        scope,
        nodeTarget: target,
        targetLabel: label,
        notice: scope === "node" ? deliveryText(node) : null,
        statement: scope === "node" && node.kind === "member" ? t("{target} metodunda: ", { target: memberTarget(node) }) : "",
      },
      onCancel: closeDrawer,
      onDone: async () => {
        closeDrawer();
        await view.refresh(node.id);
      },
    });
  }

  function actions(node, view) {
    if (node.kind === "ghost") {
      return h("div.d-actions", h("p.d-note", t("Bu sembol açık ağaçta değil; bağlamak için paketini açıp düğümü seç.")));
    }
    const locator = locatorFor(node, memberTarget);
    const targetLabel = locator ? `${locator.kind} · ${locator.ref}` : null;
    const addMemory = (scope) => createMemory(ctx, {
      scope,
      codeLocators: locator ? [locator] : [],
      targetLabel: scope === "project" ? targetLabel : null,
      onSaved: () => view.refresh(node.id),
    });
    const canTarget = !!nodeTargetFor(node);
    return h("div.d-actions",
      h("div.d-row.mem", h("span", h("i"), t("Hafıza ekle")),
        h("button", { type: "button", onclick: () => addMemory("project"), title: locator ? t("Bu projeye kaydet ve bu düğüme bağla") : t("Bu projeye kaydet") }, t("Bu proje")),
        h("button", { type: "button", onclick: () => addMemory("global"), title: t("Her projede geçerli genel bilgi (koda bağlanmaz)") }, t("Genel"))),
      h("div.d-row.rules", h("span", h("i"), t("Kural ekle")),
        h("button", { type: "button", onclick: () => openRuleFlow(view, node, "global") }, t("Genel")),
        h("button", { type: "button", onclick: () => openRuleFlow(view, node, "project") }, t("Proje")),
        canTarget ? h("button.node-target", { type: "button", onclick: () => openRuleFlow(view, node, "node"), title: deliveryText(node) }, t("Bu düğüm")) : null),
      canTarget ? h("p.d-note", node.kind === "member"
        ? t("Metoda bağlanan kural {path} dosyasının tamamında çalışılırken gösterilir.", { path: node.path })
        : deliveryText(node)) : null);
  }

  const view = createGraphView(host, {
    project: ctx.project,
    initialNode: ctx.search.get("node") || null,
    actions,
    memoryHref: (id) => "#/memory/" + encodeURIComponent("memory:" + id),
    ruleHref: (id) => "#/rules/" + encodeURIComponent("rule:" + id),
    footer: (node) => node.kind === "ghost" ? null : h("div.d-foot",
      h("a", { href: "/universe.html?" + new URLSearchParams({ project: ctx.project, node: node.id }), target: "_blank", rel: "noopener" }, t("Tam ekranda aç"))),
    onSelect: (id) => {
      if (!ctx.live()) return;
      closeDrawer();
      history.replaceState(null, "", "#/graph" + (id ? "?" + new URLSearchParams({ node: id }) : ""));
    },
    emptyAction: h("button.btn.primary", { onclick: () => openAddProjectDialog(ctx.onProjectAdded) }, t("Klasörü indeksle")),
  });
  ctx.signal.addEventListener("abort", () => view.destroy(), { once: true });
  document.addEventListener("keydown", function onKey(event) {
    if (!ctx.live()) { document.removeEventListener("keydown", onKey); return; }
    if (event.key === "Escape" && !drawer.hidden && !document.querySelector("dialog[open]")) { event.preventDefault(); closeDrawer(); }
  });
}
