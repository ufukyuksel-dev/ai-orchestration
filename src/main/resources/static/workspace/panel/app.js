// Atlas panel shell: hash router, project switch, "Proje ekle" onboarding, health, pending badge, the EN | TR
// switch and the ⌘K palette. Served at /, /workspace and /workspace/index.html, so every URL here is absolute.
import { lang, t, setLang, locale, localize } from "./i18n.js";
import { api, h, query, debounce, TYPE_LABELS, fill, toast } from "./core.js";
import { renderOverview } from "./overview.js";
import { renderMemory } from "./memory.js";
import { renderPending } from "./pending.js";
import { renderRules } from "./rules.js";
import { renderReferences } from "./references.js";
import { renderGraph } from "./graph.js";
import { renderJobs } from "./jobs.js";
import { mountAddProject, openAddProjectDialog } from "./projects.js";

// The static shell in index.html is written in Turkish; translate it before anything renders.
localize(document);

const ROUTES = {
  overview: renderOverview,
  memory: renderMemory,
  pending: renderPending,
  rules: renderRules,
  references: renderReferences,
  graph: renderGraph,
  jobs: renderJobs,
};

const state = {
  project: localStorage.getItem("atlas.project") || "",
  controller: null,
  generation: 0,
};

const main = document.getElementById("view");
const projectSelect = document.getElementById("project");
const onboarding = document.getElementById("onboarding");
const fullscreenLink = document.getElementById("fullscreen-link");

export function currentProject() {
  return state.project;
}

export function navigate(hash) {
  if (location.hash === hash) route();
  else location.hash = hash;
}

function parseHash() {
  const [path, search = ""] = location.hash.replace(/^#\/?/, "").split("?");
  const [route = "overview", ...rest] = path.split("/").map(decodeURIComponent);
  return { route: ROUTES[route] ? route : "overview", params: rest.filter(Boolean), search: new URLSearchParams(search) };
}

/**
 * Each render gets its own AbortController and generation number. Leaving a view or switching project
 * aborts in-flight requests, and views must check `ctx.live()` before writing results, so a slow answer
 * for the previous project can never paint into the current one.
 */
async function route() {
  state.controller?.abort();
  const controller = new AbortController();
  state.controller = controller;
  const generation = ++state.generation;
  const { route: name, params, search } = parseHash();
  document.querySelectorAll(".rail-link[data-route]").forEach((link) => {
    link.toggleAttribute("aria-current", link.dataset.route === name);
    if (link.dataset.route === name) link.setAttribute("aria-current", "page");
  });
  main.replaceChildren();
  main.className = "";
  const ctx = {
    main,
    params,
    search,
    project: state.project,
    signal: controller.signal,
    live: () => generation === state.generation && !controller.signal.aborted,
    navigate,
    refreshBadges,
    switchProject,
    onProjectAdded,
  };
  try {
    await ROUTES[name](ctx);
  } catch (error) {
    if (error.name === "AbortError" || !ctx.live()) return;
    fill(main, h("div.error-box", h("span", error.message)));
  }
  main.focus({ preventScroll: true });
}

/** Returns the project list, or null when the server could not be reached. */
async function loadProjects() {
  try {
    const data = await api("projects");
    const projects = data.projects || [];
    state.projects = projects;
    fill(projectSelect, 
      h("option", { value: "" }, t("Tüm projeler")),
      projects.map((key) => h("option", { value: key }, key)),
    );
    if (state.project && !projects.includes(state.project)) state.project = "";
    if (!state.project && data.defaultProject && projects.includes(data.defaultProject) && !localStorage.getItem("atlas.project.chosen")) {
      state.project = data.defaultProject;
    }
    projectSelect.value = state.project;
    syncFullscreenLink();
    return projects;
  } catch {
    /* health indicator already reports the outage */
    return null;
  }
}

function syncFullscreenLink() {
  fullscreenLink.href = "/universe.html" + (state.project ? "?project=" + encodeURIComponent(state.project) : "");
}

function setProject(key) {
  state.project = key;
  localStorage.setItem("atlas.project", key);
  localStorage.setItem("atlas.project.chosen", "1");
  if (projectSelect.value !== key) projectSelect.value = key;
  syncFullscreenLink();
}

/** Switches project (e.g. a link from the full-screen view) and then opens `hash`. */
function switchProject(key, hash) {
  if (!state.projects?.includes(key)) {
    projectSelect.append(h("option", { value: key }, key));
    state.projects = [...(state.projects || []), key];
  }
  setProject(key);
  refreshBadges();
  if (location.hash === hash) route(); else location.hash = hash;
}

/** After a successful scan: leave onboarding, reload the project list and open the new project's graph. */
async function onProjectAdded(key) {
  onboarding.hidden = true;
  onboarding.replaceChildren();
  document.body.classList.remove("is-onboarding");
  await loadProjects();
  switchProject(key, "#/graph");
  toast(t("{key} eklendi ve yapısal olarak indekslendi.", { key }));
}

/** Empty workspace: only the centred "Proje ekle" card is shown. */
function showOnboarding() {
  document.body.classList.add("is-onboarding");
  onboarding.hidden = false;
  const card = h("div.onboarding-card");
  fill(onboarding,
    h("div.onboarding-mark", markSvg(), h("span", "Atlas")),
    card,
    h("p.onboarding-foot", t("Proje eklendikten sonra kod grafı açılır; paketlere, sınıflara ve metotlara hafıza ve kural bağlayabilirsin.")));
  mountAddProject(card, { onDone: (key) => onProjectAdded(key) });
}

function markSvg() {
  const node = document.createElementNS("http://www.w3.org/2000/svg", "svg");
  node.setAttribute("viewBox", "0 0 32 32");
  node.setAttribute("aria-hidden", "true");
  node.innerHTML = '<circle cx="16" cy="16" r="13"/><circle cx="16" cy="16" r="5"/><path d="M16 3v26M3 16h26"/>';
  return node;
}

projectSelect.addEventListener("change", () => {
  setProject(projectSelect.value);
  refreshBadges();
  route();
});
document.getElementById("add-project").addEventListener("click", () => openAddProjectDialog(onProjectAdded));

// Health: one probe per 30 s while the tab is visible; never a tight retry loop.
const healthNode = document.getElementById("health");
const healthText = document.getElementById("health-text");
async function checkHealth() {
  if (document.hidden) return;
  try {
    await api("health");
    healthNode.dataset.state = "up";
    healthText.textContent = t("Sunucu çalışıyor");
  } catch {
    healthNode.dataset.state = "down";
    healthText.textContent = t("Sunucuya ulaşılamıyor");
  }
}

// EN | TR in the rail footer: the choice is stored and the page reloads in that language.
document.querySelectorAll(".lang-switch button[data-lang]").forEach((button) => {
  button.setAttribute("aria-pressed", String(button.dataset.lang === lang));
  button.addEventListener("click", () => { if (button.dataset.lang !== lang) setLang(button.dataset.lang); });
});

async function refreshBadges() {
  const badge = document.getElementById("pending-badge");
  try {
    const data = await api("memory/pending?" + query({ project: state.project, limit: 25 }));
    const count = data.count ?? (data.items || []).length;
    badge.hidden = !count;
    badge.textContent = count > 24 ? "25+" : String(count);
  } catch {
    badge.hidden = true;
  }
}

// ---------- command palette ----------
const palette = document.getElementById("palette");
const paletteInput = document.getElementById("palette-input");
const paletteList = document.getElementById("palette-results");
let paletteItems = [];
let paletteIndex = 0;
let paletteSeq = 0;

const PAGES = [
  [t("Genel bakış"), "#/overview"],
  [t("Hafıza"), "#/memory"],
  [t("Yeni hafıza kaydı"), "#/memory/new"],
  [t("Onay bekleyen kayıtlar"), "#/pending"],
  [t("Kurallar"), "#/rules"],
  [t("Yeni kural"), "#/rules/new"],
  [t("Referanslar"), "#/references"],
  [t("Kod grafı"), "#/graph"],
  [t("İşler"), "#/jobs"],
  [t("Kayıtlı işler"), "#/jobs/job"],
  [t("Kişisel hafıza"), "#/jobs/personal"],
  [t("Proje ekle"), "#!add-project"],
];

function openPalette() {
  paletteInput.value = "";
  renderPalette(PAGES.map(([label, hash]) => ({ label, hash, note: t("Sayfa") })));
  palette.showModal();
  paletteInput.focus();
}

function renderPalette(items) {
  paletteItems = items;
  paletteIndex = 0;
  fill(paletteList, 
    ...items.map((item, index) =>
      h("li", {
        role: "option",
        "aria-selected": index === 0 ? "true" : "false",
        onclick: () => choose(index),
      }, h("span", item.label), h("small", item.note || "")),
    ),
  );
  if (!items.length) paletteList.append(h("li", { "aria-disabled": "true" }, h("span", t("Sonuç yok"))));
}

function choose(index) {
  const item = paletteItems[index];
  if (!item) return;
  palette.close();
  if (item.hash === "#!add-project") openAddProjectDialog(onProjectAdded);
  else navigate(item.hash);
}

const searchPalette = debounce(async (text) => {
  const seq = ++paletteSeq;
  const pages = PAGES.filter(([label]) => label.toLocaleLowerCase(locale()).includes(text.toLocaleLowerCase(locale())))
    .map(([label, hash]) => ({ label, hash, note: t("Sayfa") }));
  if (text.trim().length < 2) {
    renderPalette(pages);
    return;
  }
  try {
    const [memories, rules] = await Promise.all([
      api("items?" + query({ kind: "memory", project: state.project, query: text, limit: 6 })),
      api("items?" + query({ kind: "rule", project: state.project, query: text, limit: 4 })),
    ]);
    if (seq !== paletteSeq) return;
    renderPalette([
      ...pages,
      ...(memories.items || []).map((item) => ({
        label: item.title, hash: "#/memory/" + encodeURIComponent(item.id),
        note: TYPE_LABELS[item.info?.memoryType] || t("Hafıza"),
      })),
      ...(rules.items || []).map((item) => ({
        label: item.title, hash: "#/rules/" + encodeURIComponent(item.id), note: t("Kural"),
      })),
    ]);
  } catch {
    if (seq === paletteSeq) renderPalette(pages);
  }
}, 220);

paletteInput.addEventListener("input", () => searchPalette(paletteInput.value));
paletteInput.addEventListener("keydown", (event) => {
  if (event.key === "ArrowDown" || event.key === "ArrowUp") {
    event.preventDefault();
    const nodes = [...paletteList.children];
    if (!nodes.length) return;
    paletteIndex = (paletteIndex + (event.key === "ArrowDown" ? 1 : -1) + nodes.length) % nodes.length;
    nodes.forEach((node, index) => node.setAttribute("aria-selected", index === paletteIndex ? "true" : "false"));
    nodes[paletteIndex].scrollIntoView({ block: "nearest" });
  } else if (event.key === "Enter") {
    event.preventDefault();
    choose(paletteIndex);
  }
});
document.getElementById("palette-open").addEventListener("click", openPalette);
document.addEventListener("keydown", (event) => {
  if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === "k") {
    event.preventDefault();
    palette.open ? palette.close() : openPalette();
  }
});

window.addEventListener("hashchange", () => { if (!document.body.classList.contains("is-onboarding")) route(); });
document.addEventListener("visibilitychange", checkHealth);

const initialProjects = await loadProjects();
checkHealth();
setInterval(checkHealth, 30000);
if (initialProjects && initialProjects.length === 0) {
  showOnboarding();
} else {
  refreshBadges();
  route();
}
