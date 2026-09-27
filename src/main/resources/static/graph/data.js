// Layered code graph data: a lazily opened tree (project → package → class/file → member) read from
// /workspace/api/code-tree, plus the code edges and attached memories/rules of one node.
import { lang, t } from "/workspace/panel/i18n.js";

export class ApiError extends Error {
  constructor(message, status) { super(message); this.status = status; }
}

/** Same guard and language headers as the panel; the server rejects workspace calls without the guard. */
export async function api(path, { signal, body } = {}) {
  let response;
  try {
    response = await fetch("/workspace/api/" + path, {
      method: body !== undefined ? "POST" : "GET",
      headers: { "X-Workspace-Request": "1", "X-Workspace-Lang": lang, ...(body !== undefined ? { "Content-Type": "application/json" } : {}) },
      body: body !== undefined ? JSON.stringify(body) : undefined,
      signal,
    });
  } catch (error) {
    if (error.name === "AbortError") throw error;
    throw new ApiError(t("Sunucuya ulaşılamadı. AI Orchestration çalışıyor mu?"), 0);
  }
  const text = await response.text();
  let data = null;
  try { data = text ? JSON.parse(text) : null; } catch { data = { error: text.slice(0, 200) }; }
  if (!response.ok) throw new ApiError(data?.error || data?.message || t("İstek tamamlanamadı (HTTP {status}).", { status: response.status }), response.status);
  return data;
}

export const KIND_LABELS = { project: t("Proje"), package: t("Paket"), class: t("Sınıf"), file: t("Dosya"), member: t("Üye") };
export const LAYER_LABELS = [t("Proje"), t("Paketler"), t("Sınıflar ve dosyalar"), t("Üyeler"), t("Alt üyeler")];
const MAX_CHILDREN = 5000;

const qs = (params) => new URLSearchParams(Object.entries(params).filter(([, v]) => v !== undefined && v !== null && v !== "")).toString();

/** Member label with its signature: `pkg.Owner#addPet(Pet)`. The server's member name already is the signature. */
export function memberTarget(node) {
  const fqn = node.fqn || "";
  const sig = node.signature || "";
  if (!sig) return fqn || node.name;
  if (sig.startsWith("(")) return fqn + sig;
  const hash = fqn.indexOf("#");
  return hash > 0 ? fqn.slice(0, hash + 1) + sig : (fqn ? fqn + " · " + sig : sig);
}

/** Directory of a file path ("a/b/C.java" → "a/b"). */
export function dirOf(path) {
  const i = String(path || "").lastIndexOf("/");
  return i > 0 ? path.slice(0, i) : "";
}

export function isSymbolId(id) {
  return /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(String(id || ""));
}

/**
 * Tree model. Nodes keep their loaded children when collapsed, so re-opening is instant.
 * node: {id, kind, name, path, fqn, signature, childCount, memoryCount, ruleCount, parent, depth, children, expanded}
 */
export function createTree(project) {
  const nodes = new Map();
  let root = null;

  function upsert(raw, parent, depth) {
    let node = nodes.get(raw.id);
    if (!node) {
      node = { children: null, expanded: false, loading: null };
      nodes.set(raw.id, node);
    }
    Object.assign(node, {
      id: raw.id, kind: raw.kind || "member", name: raw.name || raw.fqn || raw.path || raw.id,
      path: raw.path || "", fqn: raw.fqn || "", signature: raw.signature || "",
      childCount: raw.childCount ?? node.childCount ?? 0,
      memoryCount: raw.memoryCount ?? node.memoryCount ?? 0,
      ruleCount: raw.ruleCount ?? node.ruleCount ?? 0,
      parent: parent ?? node.parent ?? null,
      depth: depth ?? node.depth ?? 0,
    });
    return node;
  }

  async function fetchChildren(parentId, signal) {
    const all = [];
    let cursor = null;
    let first = null;
    do {
      const page = await api("code-tree?" + qs({ project, parent: parentId || "", cursor }), { signal });
      if (!first) first = page;
      all.push(...(page.children || []));
      cursor = page.nextCursor || null;
    } while (cursor && all.length < MAX_CHILDREN);
    return { node: first?.node, children: all, truncated: !!cursor };
  }

  async function loadRoot(signal) {
    const data = await fetchChildren("", signal);
    if (!data.node) throw new ApiError(t("Kod ağacı boş döndü."), 500);
    root = upsert({ ...data.node, childCount: data.children.length }, null, 0);
    root.children = data.children.map((c) => upsert(c, root.id, 1).id);
    root.expanded = true;
    root.truncated = data.truncated;
    return root;
  }

  /** Fetches the children once; concurrent calls share the same request. */
  function loadChildren(id, signal) {
    const node = nodes.get(id);
    if (!node) return Promise.reject(new Error(t("Bilinmeyen düğüm: ") + id));
    if (node.children) return Promise.resolve(node);
    if (node.loading) return node.loading;
    node.loading = fetchChildren(id, signal).then((data) => {
      node.children = data.children.map((c) => upsert(c, node.id, node.depth + 1).id);
      node.childCount = Math.max(node.childCount, node.children.length);
      node.truncated = data.truncated;
      node.loading = null;
      return node;
    }, (error) => { node.loading = null; throw error; });
    return node.loading;
  }

  return {
    project,
    get root() { return root; },
    get: (id) => nodes.get(id),
    has: (id) => nodes.has(id),
    all: () => nodes.values(),
    loadRoot,
    loadChildren,
    /** Ancestor chain root → node (inclusive). */
    pathTo(id) {
      const chain = [];
      let node = nodes.get(id);
      while (node) { chain.unshift(node); node = node.parent ? nodes.get(node.parent) : null; }
      return chain;
    },
    edges: (symbolId, signal) => api("code-tree/edges?" + qs({ project, symbol: symbolId }), { signal }),
    attached: (nodeId, signal) => api("code-tree/attached?" + qs({ project, node: nodeId }), { signal }),
  };
}
