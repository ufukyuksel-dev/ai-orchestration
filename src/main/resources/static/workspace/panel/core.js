// Shared helpers: API access, DOM building, toasts, modal forms, formatting.
import { lang, t } from "./i18n.js";

export class ApiError extends Error {
  constructor(message, status) {
    super(message);
    this.status = status;
  }
}

/** Every call carries the workspace marker header (the server guard rejects anything else) and the panel language. */
export async function api(path, { body, method, signal } = {}) {
  let response;
  try {
    response = await fetch("/workspace/api/" + path, {
      method: method || (body !== undefined ? "POST" : "GET"),
      headers: {
        "X-Workspace-Request": "1",
        "X-Workspace-Lang": lang,
        ...(body !== undefined ? { "Content-Type": "application/json" } : {}),
      },
      body: body !== undefined ? JSON.stringify(body) : undefined,
      signal,
    });
  } catch (error) {
    if (error.name === "AbortError") throw error;
    throw new ApiError(t("Sunucuya ulaşılamadı. AI Orchestration çalışıyor mu?"), 0);
  }
  let data = null;
  const text = await response.text();
  if (text) {
    try {
      data = JSON.parse(text);
    } catch {
      data = { error: text.slice(0, 300) };
    }
  }
  if (!response.ok) {
    const reason = data && (data.error || data.message || data.detail);
    throw new ApiError(reason || t("İstek tamamlanamadı (HTTP {status}).", { status: response.status }), response.status);
  }
  return data;
}

export function query(params) {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined && value !== null && value !== "") search.set(key, value);
  }
  return search.toString();
}

/** Small hyperscript: h("div.row", {onclick}, child, ...). Strings become text nodes (never HTML). */
export function h(tag, props, ...children) {
  const [name, ...classes] = tag.split(".");
  const node = document.createElement(name || "div");
  if (classes.length) node.className = classes.join(" ");
  if (props && (typeof props !== "object" || props instanceof Node || Array.isArray(props))) {
    children.unshift(props);
    props = null;
  }
  for (const [key, value] of Object.entries(props || {})) {
    if (value === undefined || value === null || value === false) continue;
    if (key.startsWith("on")) node.addEventListener(key.slice(2), value);
    else if (key === "dataset") Object.assign(node.dataset, value);
    else if (key === "style") node.setAttribute("style", value);
    else if (key in node && key !== "list") node[key] = value;
    else node.setAttribute(key, value === true ? "" : value);
  }
  for (const child of children.flat(Infinity)) {
    if (child === null || child === undefined || child === false) continue;
    node.append(child instanceof Node ? child : document.createTextNode(String(child)));
  }
  return node;
}

/** replaceChildren that flattens arrays and skips null/false (the DOM API does neither). */
export function fill(node, ...children) {
  node.replaceChildren(...children.flat(Infinity).filter((c) => c !== null && c !== undefined && c !== false)
    .map((c) => (c instanceof Node ? c : document.createTextNode(String(c)))));
  return node;
}

export function svg(pathD) {
  const node = document.createElementNS("http://www.w3.org/2000/svg", "svg");
  node.setAttribute("viewBox", "0 0 20 20");
  node.setAttribute("aria-hidden", "true");
  const path = document.createElementNS("http://www.w3.org/2000/svg", "path");
  path.setAttribute("d", pathD);
  node.append(path);
  return node;
}

export function toast(message, kind = "ok") {
  const node = h("div.toast" + (kind === "error" ? ".error" : ""), message);
  document.getElementById("toasts").append(node);
  setTimeout(() => node.remove(), kind === "error" ? 7000 : 4000);
}

const STATUS_LABELS = {
  active: t("Etkin"),
  pending_review: t("Onay bekliyor"),
  archived: t("Arşivde"),
  rejected: t("Reddedildi"),
  superseded: t("Yerini yenisi aldı"),
  invalidated: t("Geçersiz"),
  completed: t("Tamamlandı"),
  failed: t("Başarısız"),
  running: t("Çalışıyor"),
  recorded: t("Kayıtlı"),
  saved: t("Kaydedildi"),
  current: t("Güncel"),
};

export function statusLabel(status) {
  const key = String(status || "").toLowerCase();
  return STATUS_LABELS[key] || status || t("Bilinmiyor");
}

export function statusChip(status) {
  const key = String(status || "").toLowerCase();
  return h("span.chip", h("i.st-" + key), statusLabel(status));
}

export const TYPE_LABELS = {
  decision: t("Karar"),
  discovery: t("Öğrenilen bilgi"),
  correction: t("Düzeltme"),
  anti_pattern: t("Kaçınılacak durum"),
  preference: t("Tercih"),
  rule: t("Kural"),
};

export function relativeTime(iso) {
  if (!iso) return "";
  const then = new Date(iso);
  const seconds = Math.round((Date.now() - then.getTime()) / 1000);
  const rtf = new Intl.RelativeTimeFormat(lang, { numeric: "auto" });
  const steps = [[60, "second"], [60, "minute"], [24, "hour"], [30, "day"], [12, "month"]];
  let value = -seconds;
  for (const [size, unit] of steps) {
    if (Math.abs(value) < size) return rtf.format(value, unit);
    value = Math.round(value / size);
  }
  return rtf.format(value, "year");
}

export function shortId(id) {
  const raw = String(id || "").split(":").pop();
  return raw.slice(0, 8);
}

export function rawId(id) {
  const text = String(id || "");
  return text.includes(":") ? text.split(":").slice(1).join(":") : text;
}

export function counter(input, max, bytes = false) {
  const node = h("span.counter");
  const update = () => {
    const size = bytes ? new TextEncoder().encode(input.value).length : input.value.length;
    node.textContent = `${size} / ${max}${bytes ? " " + t("bayt") : ""}`;
    node.classList.toggle("over", size > max);
  };
  input.addEventListener("input", update);
  update();
  return node;
}

/**
 * Opens the shared modal with a form. `build(form)` returns the body nodes; `submit(values)` runs on
 * confirm and may throw to show an inline error. The confirm button is disabled while a request runs,
 * so a double click cannot submit twice.
 */
export function openModal({ title, build, submitLabel = t("Kaydet"), danger = false, submit }) {
  const dialog = document.getElementById("modal");
  const form = document.getElementById("modal-form");
  form.replaceChildren();
  const error = h("p.form-error", { hidden: true, role: "alert" });
  const confirm = h("button.btn." + (danger ? "danger" : "primary"), { type: "submit", value: "ok" }, submitLabel);
  const cancel = h("button.btn.quiet", { type: "button", onclick: () => dialog.close() }, t("Vazgeç"));
  const body = h("div.modal-body", build(form), error);
  form.append(h("h2", title), body, h("div.modal-foot", cancel, confirm));
  let busy = false;
  form.onsubmit = async (event) => {
    event.preventDefault();
    if (busy) return;
    busy = true;
    confirm.disabled = true;
    error.hidden = true;
    try {
      await submit(new FormData(form), form);
      dialog.close();
    } catch (err) {
      error.textContent = err.message || String(err);
      error.hidden = false;
    } finally {
      busy = false;
      confirm.disabled = false;
    }
  };
  dialog.showModal();
  const first = form.querySelector("input:not([type=radio]), textarea, select");
  if (first) first.focus();
}

export function field(label, control, hint) {
  return h("label.field", label, control, hint ? h("span.hint", hint) : null);
}

export function emptyState(title, text, action) {
  return h("div.empty", h("h2", title), h("p", text), action || null);
}

export function errorBox(message, retry) {
  return h("div.error-box", h("span", message), retry ? h("button.btn", { onclick: retry }, t("Tekrar dene")) : null);
}

export function skeletonRows(count = 6) {
  return h("div.rows", Array.from({ length: count }, () => h("div.skeleton")));
}

export function debounce(fn, wait = 250) {
  let timer;
  return (...args) => {
    clearTimeout(timer);
    timer = setTimeout(() => fn(...args), wait);
  };
}
