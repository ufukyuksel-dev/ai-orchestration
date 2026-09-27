// Node detail card shared by the panel's Graph tab and the full-screen view: what the node is, where it
// sits, which memories and rules are attached, and its code edges. Hosts add their own action buttons.
import { KIND_LABELS, memberTarget } from "./data.js";
import { t, locale } from "/workspace/panel/i18n.js";

export function el(tag, cls, ...children) {
  const node = document.createElement(tag);
  if (cls) node.className = cls;
  for (const child of children.flat()) {
    if (child === null || child === undefined || child === false) continue;
    node.append(child instanceof Node ? child : document.createTextNode(String(child)));
  }
  return node;
}

function button(cls, label, onclick, title) {
  const b = el("button", cls, label);
  b.type = "button";
  b.onclick = onclick;
  if (title) b.title = title;
  return b;
}

const SCOPE_LABELS = { global: t("Genel"), project: t("Proje"), module: t("Dizin"), node: t("Düğüm"), file: t("Dosya"), symbol: t("Metot") };
const scopeLabel = (scope) => SCOPE_LABELS[String(scope || "").toLowerCase()] || scope || "";

/** Where a rule attached to this node is delivered to agents, in words. */
export function deliveryText(node) {
  if (node.kind === "member") return t("Bu kural {path} dosyasının tamamını düzenlerken gösterilir; hedef metot: {target}",
    { path: node.path || t("dosya"), target: memberTarget(node) });
  if (node.kind === "class" || node.kind === "file") return t("Bu kural {path} dosyasını düzenlerken gösterilir.", { path: node.path });
  if (node.kind === "package") return t("Bu kural {path}/ altındaki dosyaları düzenlerken gösterilir ({path}/**).", { path: node.path });
  return "";
}

/**
 * state: {node, path: [nodes], attached: undefined|{memories, rules}|Error, edges: undefined|null|{nodes, edges}|Error,
 *         onPick(id), onClose(), actions: Node|null, footer: Node|null, memoryHref(id), ruleHref(id), known(id),
 *         children: [nodes]|null (an open node's children)}
 */
export function renderDetail(aside, state) {
  const { node } = state;
  aside.replaceChildren();
  aside.dataset.node = node.id;
  aside.append(button("d-close", "×", state.onClose, t("Seçimi bırak (Esc)")));
  aside.append(el("div", "d-kind", el("i", "k-" + node.kind), KIND_LABELS[node.kind] || t("Komşu düğüm"),
    node.depth !== undefined && node.kind !== "ghost" ? el("span", null, t(" · katman {n}", { n: node.depth + 1 })) : null));
  aside.append(el("h2", null, node.name));

  if (state.path?.length > 1) {
    const crumbs = el("nav", "d-path");
    crumbs.setAttribute("aria-label", t("Düğümün yolu"));
    state.path.forEach((p, k) => {
      if (k) crumbs.append(el("span", "sep", "›"));
      if (p.id === node.id) crumbs.append(el("span", "here", p.name));
      else crumbs.append(button("crumb", p.name, () => state.onPick(p.id)));
    });
    aside.append(crumbs);
  }

  const dl = el("dl");
  const row = (k, v) => { if (v) dl.append(el("dt", null, k), el("dd", null, String(v))); };
  row(node.kind === "package" ? t("Dizin") : t("Yol"), node.path);
  row(t("Tam ad"), node.fqn);
  if (node.kind === "member") row(t("İmza"), node.signature);
  if (node.childCount) row(t("Alt öğe"), node.childCount.toLocaleString(locale()));
  aside.append(dl);

  const memCount = Array.isArray(state.attached?.memories) ? state.attached.memories.length : node.memoryCount || 0;
  const ruleCount = Array.isArray(state.attached?.rules) ? state.attached.rules.length : node.ruleCount || 0;
  aside.append(el("div", "d-badges",
    el("span", "bm" + (memCount ? "" : " zero"), el("i"), t("{n} hafıza", { n: memCount })),
    el("span", "br" + (ruleCount ? "" : " zero"), el("i"), t("{n} kural", { n: ruleCount }))));

  if (state.actions) aside.append(state.actions);

  // What an open node holds: every child, filterable (the map shows only the labels that fit).
  if (state.children?.length) {
    const section = el("section", "d-section d-children");
    section.append(el("h3", null, el("i", "dot-c"), t("İçindekiler ({n})", { n: state.children.length })));
    const ul = el("ul");
    const rows = state.children.map((c) => {
      const li = el("li", null, button("link", [el("i", "k-" + c.kind), el("span", null, c.name),
        c.memoryCount ? el("b", "bm", String(c.memoryCount)) : null, c.ruleCount ? el("b", "br", String(c.ruleCount)) : null],
      () => state.onPick(c.id)));
      li.dataset.name = String(c.name).toLowerCase();
      ul.append(li);
      return li;
    });
    if (rows.length > 8) {
      const filter = el("input", "d-filter");
      filter.type = "search";
      filter.placeholder = t("Filtrele");
      filter.setAttribute("aria-label", t("İçindekileri filtrele"));
      filter.oninput = () => {
        const q = filter.value.trim().toLowerCase();
        for (const li of rows) li.hidden = q && !li.dataset.name.includes(q);
      };
      section.append(filter);
    }
    section.append(ul);
    aside.append(section);
  }

  // Attached memories and rules.
  const attached = state.attached;
  if (attached === undefined) {
    aside.append(el("div", "d-skeleton"), el("div", "d-skeleton short"));
  } else if (attached instanceof Error) {
    aside.append(el("p", "d-error", t("Bağlı kayıtlar getirilemedi: ") + attached.message));
  } else {
    const memories = attached.memories || [];
    const rules = attached.rules || [];
    const memSection = el("section", "d-section");
    memSection.append(el("h3", null, el("i", "dot-m"), t("Bağlı hafıza ({n})", { n: memories.length })));
    if (!memories.length) memSection.append(el("p", "d-none", t("Bu düğüme bağlı hafıza kaydı yok.")));
    else {
      const ul = el("ul");
      memories.slice(0, 30).forEach((m) => {
        const a = el("a", null, el("span", null, m.summary || m.title || m.id), el("small", null, scopeLabel(m.scope)));
        a.href = state.memoryHref(m.id);
        ul.append(el("li", null, a));
      });
      memSection.append(ul);
    }
    aside.append(memSection);

    const ruleSection = el("section", "d-section");
    ruleSection.append(el("h3", null, el("i", "dot-r"), t("Bağlı kurallar ({n})", { n: rules.length })));
    if (!rules.length) ruleSection.append(el("p", "d-none", t("Bu düğüme bağlı kural yok.")));
    else {
      const ul = el("ul");
      rules.slice(0, 30).forEach((r) => {
        const target = r.target ? String(r.target) : "";
        const symbolic = node.kind === "member" && /[#(]/.test(target);
        const meta = symbolic
          ? t("metot: {target} · uygulanır: dosya {path}", { target, path: node.path })
          : [scopeLabel(r.scope), target].filter(Boolean).join(" · ");
        const a = el("a", null, el("span", null, r.statement || r.summary || r.id), el("small", "block", meta));
        a.href = state.ruleHref(r.id);
        ul.append(el("li", "d-rule", a));
      });
      ruleSection.append(ul);
    }
    aside.append(ruleSection);
  }

  // Code edges of a symbol.
  const edges = state.edges;
  if (edges === undefined) {
    aside.append(el("div", "d-skeleton short"));
  } else if (edges instanceof Error) {
    aside.append(el("p", "d-error", t("Kod bağlantıları getirilemedi: ") + edges.message));
  } else if (edges) {
    const names = new Map((edges.nodes || []).map((n) => [n.id, n]));
    const groups = new Map();
    for (const e of edges.edges || []) {
      const outgoing = e.source === node.id || String(names.get(e.source)?.fqn || "").startsWith((node.fqn || "\u0000") + "#");
      const other = outgoing ? e.target : e.source;
      if (other === node.id) continue;
      const key = (outgoing ? "→ " : "← ") + String(e.edgeType || t("bağlantı")).toUpperCase();
      if (!groups.has(key)) groups.set(key, new Map());
      groups.get(key).set(other, names.get(other));
    }
    const section = el("section", "d-section");
    const total = [...groups.values()].reduce((a, m) => a + m.size, 0);
    section.append(el("h3", null, el("i", "dot-e"), t("Kod bağlantıları ({n})", { n: total })));
    if (!total) section.append(el("p", "d-none", t("Bu sembol için çağrı ya da enjeksiyon kaydı yok.")));
    for (const [title, entries] of groups) {
      section.append(el("h4", null, `${title} · ${entries.size}`));
      const ul = el("ul");
      [...entries].slice(0, 25).forEach(([id, other]) => {
        ul.append(el("li", null, button("link", [el("span", null, other?.name || other?.fqn || id), el("small", null, KIND_LABELS[other?.kind] || "")], () => state.onPick(id))));
      });
      section.append(ul);
    }
    aside.append(section);
  }
  if (state.footer) aside.append(state.footer);
  aside.hidden = false;
}
