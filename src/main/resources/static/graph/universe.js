// Full-screen graph page (/universe.html): the same layered graph as the panel's Graph tab, without
// the panel around it. Attaching memories and rules happens in the panel ("Panelde bağla").
import { api } from "./data.js";
import { createGraphView } from "./view.js";
import { el } from "./detail.js";
import { t, localize } from "/workspace/panel/i18n.js";

// The static header in universe.html is written in Turkish; translate it before anything renders.
localize(document);

const params = new URLSearchParams(location.search);
const host = document.getElementById("graph");
const select = document.getElementById("project");

function message(title, text, link) {
  const box = el("div", "page-message", el("h2", null, title), el("p", null, text), link || null);
  document.body.append(box);
}

async function boot() {
  let data;
  try {
    data = await api("projects");
  } catch (error) {
    const retry = el("a", null, t("Tekrar dene"));
    retry.href = location.href;
    return message(t("Sunucuya ulaşılamadı"), error.message, retry);
  }
  const projects = data.projects || [];
  const project = params.get("project") || localStorage.getItem("atlas.project") || data.defaultProject || projects[0] || "";
  if (project && !projects.includes(project)) projects.unshift(project);
  select.replaceChildren(...projects.map((p) => new Option(p, p, false, p === project)));
  select.onchange = () => {
    const url = new URL(location.href);
    url.searchParams.set("project", select.value);
    url.searchParams.delete("node");
    localStorage.setItem("atlas.project", select.value);
    location.href = url.toString();
  };
  if (!project) {
    const link = el("a", null, t("Panelde proje ekle"));
    link.href = "/";
    return message(t("Proje yok"), t("Henüz kayıtlı bir proje bulunmuyor. Panelden bir klasör ekleyip yapısal olarak indeksleyebilirsin."), link);
  }
  document.title = `${project} · ${t("Kod grafı")}`;
  createGraphView(host, {
    project,
    fullscreen: true,
    forceList: params.get("view") === "list",
    initialNode: params.get("node"),
    onSelect: (id) => {
      const url = new URL(location.href);
      if (id) url.searchParams.set("node", id); else url.searchParams.delete("node");
      history.replaceState(null, "", url);
    },
    footer: (node) => {
      if (node.kind === "ghost") return null;
      const link = el("a", null, t("Panelde bağla"));
      link.href = "/#/graph?" + new URLSearchParams({ project, node: node.id });
      link.title = t("Bu düğüme hafıza ya da kural eklemek için paneli aç");
      return el("div", "d-foot", link);
    },
  });
}

boot();
