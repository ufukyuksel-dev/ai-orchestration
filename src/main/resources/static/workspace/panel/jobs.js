// Jobs (#/jobs): read-only lists of the last job, saved jobs and personal memories. A record opens to
// show its content; saved jobs and personal memories can be deleted after a confirmation.
import { api, h, relativeTime, toast, openModal, emptyState, errorBox, skeletonRows, fill } from "./core.js";
import { t, locale } from "./i18n.js";

const TABS = [
  ["last", t("Son iş"), t("“Son işe devam” ile açılan tek kayıt. Ajan bir işi bitirmeden bıraktığında burada durur.")],
  ["job", t("Kayıtlı işler"), t("Sonra devam etmek için adıyla kaydedilen işler.")],
  ["personal", t("Kişisel hafıza"), t("Yalnız sana ait, projeden bağımsız notlar ve tercihler.")],
];

export async function renderJobs(ctx) {
  const tab = TABS.some(([k]) => k === ctx.params[0]) ? ctx.params[0] : "last";
  const selectedId = tab !== "last" ? ctx.params[1] || null : null;
  const [, , description] = TABS.find(([k]) => k === tab);
  const tabs = h("nav.tabs");
  const listHolder = h("div", skeletonRows(4));
  const detailHolder = h("div");
  ctx.main.append(
    h("div.page-head", h("div", h("h1", t("İşler")), h("p", t("Ajanların yarım bıraktığı ve kaydettiği işler ile kişisel hafızan. Bu bölüm yalnız okunur; kayıtları açıp silebilirsin.")))),
    tabs,
    h("p.tab-note", description),
    h("div.split" + (selectedId ? ".with-detail" : ""), listHolder, selectedId ? detailHolder : null),
  );

  let data;
  try {
    data = await api("jobs", { signal: ctx.signal });
  } catch (error) {
    if (!ctx.live() || error.name === "AbortError") return;
    fill(tabs, TABS.map(([k, label]) => h("a", { href: "#/jobs/" + k, "aria-current": k === tab ? "page" : null }, label)));
    fill(listHolder, errorBox(error.message, () => ctx.navigate(location.hash)));
    return;
  }
  if (!ctx.live()) return;
  const lists = { job: data.jobs || [], personal: data.personal || [] };
  const counts = { last: data.lastJob ? 1 : 0, job: lists.job.length, personal: lists.personal.length };
  fill(tabs, TABS.map(([k, label]) => h("a", { href: "#/jobs/" + k, "aria-current": k === tab ? "page" : null }, label, h("span.tab-count", String(counts[k])))));

  if (tab === "last") {
    const last = data.lastJob;
    fill(listHolder, last
      ? h("article.job-card",
          h("header", h("span.chip", h("i.st-current"), t("Son iş")), h("span.when", last.updatedAt ? `${new Date(last.updatedAt).toLocaleString(locale())} · ${relativeTime(last.updatedAt)}` : "")),
          h("div.job-content", last.content || ""))
      : emptyState(t("Son iş kaydı yok"), t("Ajan bir işi yarım bıraktığında “son iş” olarak burada görünür ve sonraki oturumda devam edilebilir.")));
    return;
  }

  const items = lists[tab];
  fill(listHolder, items.length
    ? h("div.rows", { role: "listbox", "aria-label": TABS.find(([k]) => k === tab)[1] },
        items.map((item) => h("a.row", {
          href: "#/jobs/" + tab + "/" + encodeURIComponent(item.id),
          role: "option",
          "aria-selected": item.id === selectedId ? "true" : "false",
        },
        h("span.dot.st-" + (tab === "job" ? "saved" : "recorded")),
        h("div", h("div.t", item.summary || t("Adsız")), h("div.s", item.id)),
        h("span.when", relativeTime(item.updatedAt)))))
    : emptyState(tab === "job" ? t("Kayıtlı iş yok") : t("Kişisel hafıza boş"),
        tab === "job" ? t("Bir işi adıyla kaydettiğinde burada listelenir.") : t("Ajana kişisel bir tercih ya da not kaydettirdiğinde burada görünür.")));

  if (!selectedId) return;
  fill(detailHolder, h("aside.detail", h("div.skeleton")));
  let item;
  try {
    item = await api("jobs/" + tab + "/" + encodeURIComponent(selectedId), { signal: ctx.signal });
  } catch (error) {
    if (!ctx.live() || error.name === "AbortError") return;
    fill(detailHolder, h("aside.detail", error.status === 404
      ? emptyState(t("Kayıt bulunamadı"), t("Silinmiş olabilir."))
      : errorBox(error.message, () => ctx.navigate(location.hash))));
    return;
  }
  if (!ctx.live()) return;
  fill(detailHolder, h("aside.detail", { "aria-label": t("Kayıt ayrıntısı") },
    h("header",
      h("a.btn.quiet.close-detail", { href: "#/jobs/" + tab }, t("Kapat")),
      h("div.meta", h("span.chip", tab === "job" ? t("Kayıtlı iş") : t("Kişisel hafıza")), item.updatedAt ? h("span.chip", relativeTime(item.updatedAt)) : null),
      h("h2", item.summary || t("Adsız"))),
    h("div.content", item.content || ""),
    h("dl", h("dt", t("Kimlik")), h("dd", item.id),
      item.updatedAt ? [h("dt", t("Güncellendi")), h("dd", new Date(item.updatedAt).toLocaleString(locale()))] : null),
    h("div.actions", h("button.btn.danger", { onclick: () => confirmDelete(ctx, tab, item) }, t("Sil")))));
}

function confirmDelete(ctx, tab, item) {
  openModal({
    title: tab === "job" ? t("Kayıtlı işi sil") : t("Kişisel hafıza kaydını sil"),
    submitLabel: t("Kalıcı olarak sil"),
    danger: true,
    build: () => [
      h("p", item.summary || item.id),
      h("p.hint", t("Bu işlem geri alınamaz. Kayıt ajanların son işe devam ve arama akışlarından da kalkar.")),
    ],
    submit: async () => {
      await api("jobs/" + tab + "/" + encodeURIComponent(item.id) + "/delete", { body: {} });
      toast(t("Kayıt silindi."));
      ctx.navigate("#/jobs/" + tab);
    },
  });
}
