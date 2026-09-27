// Panel and code atlas language (EN | TR). The choice: localStorage "atlas.lang", else the browser language
// (tr* → Turkish), else English. The Turkish source text is the key: t("Kaydet") is "Save" in English, and a
// missing translation falls back to the Turkish text, never to an empty string. Placeholders: {name}.
// Also imported by /graph/*.js, so the full-screen graph follows the same choice.

const LANGS = ["en", "tr"];

function detect() {
  try {
    const stored = localStorage.getItem("atlas.lang");
    if (LANGS.includes(stored)) return stored;
  } catch { /* storage may be blocked; fall back to the browser language */ }
  return /^tr\b/i.test(navigator.language || "") ? "tr" : "en";
}

export const lang = detect();
document.documentElement.lang = lang;

/** Number and date locale for toLocaleString / localeCompare. */
export function locale() {
  return lang === "tr" ? "tr-TR" : "en-US";
}

/** Stores the choice and reloads, so every view (and module-level label) is rebuilt in that language. */
export function setLang(next) {
  if (!LANGS.includes(next)) return;
  try { localStorage.setItem("atlas.lang", next); } catch { /* the reload then keeps the browser language */ }
  location.reload();
}

const plural = (n, one, many) => (String(n) === "1" ? one : many);

const EN = {
  // shell, rail and palette
  "Bölümler": "Sections",
  "Genel bakış": "Overview",
  "Hafıza": "Memory",
  "Onay bekleyen": "Pending",
  "Kurallar": "Rules",
  "Referanslar": "References",
  "Kod grafı": "Code map",
  "İşler": "Jobs",
  "Tam ekran graf": "Full-screen map",
  "Sunucu durumu": "Server status",
  "Kontrol ediliyor": "Checking",
  "Sunucu çalışıyor": "Server running",
  "Sunucuya ulaşılamıyor": "Server unreachable",
  "Dil": "Language",
  "Proje": "Project",
  "Tüm projeler": "All projects",
  "tüm projeler": "all projects",
  "Proje ekle": "Add project",
  "Bir kod klasörünü proje olarak ekle ve yapısal olarak indeksle": "Add a code folder as a project and index its structure",
  "Kayıt, kural veya sayfa bul": "Find a record, rule or page",
  "Hızlı arama": "Quick search",
  "Hafıza, kural ya da bölüm ara": "Search memory, rules or sections",
  "Yeni hafıza kaydı": "New memory record",
  "Onay bekleyen kayıtlar": "Pending records",
  "Yeni kural": "New rule",
  "Kayıtlı işler": "Saved jobs",
  "Kişisel hafıza": "Personal memory",
  "Sayfa": "Page",
  "Sonuç yok": "No results",
  "Kural": "Rule",
  "{key} eklendi ve yapısal olarak indekslendi.": "{key} was added and its structure indexed.",
  "Proje eklendikten sonra kod grafı açılır; paketlere, sınıflara ve metotlara hafıza ve kural bağlayabilirsin.":
    "Once the project is added, its code map opens; you can attach memories and rules to packages, classes and methods.",
  "Kod grafı · AI Orchestration": "Code map · AI Orchestration",
  "Panele dön": "Back to the panel",

  // shared helpers
  "Sunucuya ulaşılamadı. AI Orchestration çalışıyor mu?": "Could not reach the server. Is AI Orchestration running?",
  "İstek tamamlanamadı (HTTP {status}).": "The request could not be completed (HTTP {status}).",
  "Etkin": "Active",
  "Onay bekliyor": "Pending",
  "Arşivde": "Archived",
  "Reddedildi": "Rejected",
  "Yerini yenisi aldı": "Superseded",
  "Geçersiz": "Invalidated",
  "Tamamlandı": "Completed",
  "Başarısız": "Failed",
  "Çalışıyor": "Running",
  "Kayıtlı": "Recorded",
  "Kaydedildi": "Saved",
  "Güncel": "Current",
  "Bilinmiyor": "Unknown",
  "Karar": "Decision",
  "Öğrenilen bilgi": "Learned knowledge",
  "Düzeltme": "Correction",
  "Kaçınılacak durum": "Anti-pattern",
  "Tercih": "Preference",
  "bayt": "bytes",
  "Kaydet": "Save",
  "Vazgeç": "Cancel",
  "Tekrar dene": "Try again",

  // overview
  "Ajanların bu çalışma alanında bildikleri: kararlar, öğrenilen bilgiler ve kurallar.":
    "What agents know in this workspace: decisions, learned knowledge and rules.",
  "Hafızaya ekle": "Add to memory",
  "Kural yaz": "Write a rule",
  "Etkin hafızanın türlere göre dağılımı": "Active memory by type",
  "{type}: {n} kayıt": (p) => `${p.type}: ${p.n} ${plural(p.n, "record", "records")}`,
  "{n} etkin kayıt": (p) => `${p.n} active ${plural(p.n, "record", "records")}`,
  "Henüz etkin kayıt yok": "No active records yet",
  "{pending} onay bekliyor · {archived} arşivde": "{pending} pending · {archived} archived",
  "Ajanlar görevlerde öğrendikçe katmanlar burada birikir. İlk kaydı kendin de ekleyebilirsin.":
    "Layers build up here as agents learn during their tasks. You can also add the first record yourself.",
  "Etkin kural": "Active rules",
  "Taslak / beklemede": "Draft / pending",
  "Taranmış dosya": "Scanned files",
  "Kod sembolü": "Code symbols",
  "Son değişiklik yok.": "No recent changes.",
  "{n} kayıt senin onayını bekliyor.": (p) => `${p.n} ${plural(p.n, "record is", "records are")} waiting for your approval.`,
  "İncele": "Review",
  "Son değişen kayıtlar": "Recently changed records",
  "Tüm hafıza": "All memory",
  "Durum": "Status",
  "Kod grafında aç": "Open in the code map",

  // memory
  "Tüm durumlar": "All statuses",
  "Tüm türler": "All types",
  "Kural kaynağı": "Rule source",
  "Özet veya içerikte ara": "Search summary or content",
  "Hafızada ara": "Search memory",
  "Tür": "Type",
  "Kapsam": "Scope",
  "Tüm kapsamlar": "All scopes",
  "Bu proje": "This project",
  "Tüm projeler (genel)": "All projects (global)",
  "Etiket": "Tag",
  "Etikete göre süz": "Filter by tag",
  "Ajanların kaydettiği ve senin eklediğin bilgiler. Bir kayda tıklayıp inceleyebilir, düzeltebilir ya da silebilirsin.":
    "Knowledge saved by agents and added by you. Click a record to review, correct or delete it.",
  "Yeni kayıt": "New record",
  "Ara": "Search",
  "{n} kayıt": (p) => `${p.n} ${plural(p.n, "record", "records")}`,
  "Bu filtreyle kayıt yok": "No records match this filter",
  "Bu projede henüz hafıza yok": "No memory in this project yet",
  "Aramayı veya filtreleri değiştir.": "Change the search or the filters.",
  "Ajanlar görevleri bitirdikçe öğrendiklerini buraya kaydeder. İlk kaydı kendin de ekleyebilirsin.":
    "Agents save what they learn here as they finish tasks. You can also add the first record yourself.",
  "Hafıza kayıtları": "Memory records",
  "Genel": "Global",
  "Daha fazla göster": "Show more",
  "Kayıt bulunamadı": "Record not found",
  "Silinmiş ya da başka bir projeye ait olabilir.": "It may have been deleted or belong to another project.",
  "Düzenle": "Edit",
  "Hâlâ doğru": "Still accurate",
  "Artık geçerli değil": "No longer valid",
  "Yenisiyle değiştir": "Replace with newer",
  "Sil": "Delete",
  "Kayıt ayrıntısı": "Record details",
  "Ayrıntıyı kapat": "Close details",
  "Kapat": "Close",
  "Kayıt": "Record",
  "Kimlik": "ID",
  "Güncellendi": "Updated",
  "Etiketler": "Tags",
  "Kaynak": "Source",
  "Not": "Note",
  "Bağlantılar": "Links",
  "ilişkili": "related",
  "Geçmiş": "History",
  "Oluşturuldu": "Created",
  "Düzenlendi": "Edited",
  "Arşivlendi": "Archived",
  "Durum değişti": "Status changed",
  "Onaylandı": "Approved",
  "Doğrulandı": "Revalidated",
  "Geçersiz sayıldı": "Invalidated",
  "Silindi": "Deleted",
  "Tek cümlelik, aranabilir özet": "A one-sentence, searchable summary",
  "Tek bir doğrulanmış bilgi: ne, neden, ne zaman geçerli": "A single verified fact: what, why, and when it applies",
  "virgülle ayır": "comma-separated",
  "Önce üstten proje seç": "Select a project at the top first",
  "Her projede geçerli genel bilgi": "Global knowledge that applies to every project",
  "Bağlanacak kod": "Code to attach",
  "Kod bağlantısı yalnız “Bu proje” kapsamında kaydedilir; genel kayıtlar hiçbir koda bağlanmaz.":
    "Code links are saved only with the “This project” scope; global records are never attached to code.",
  "Düğüme hafıza ekle": "Add memory to node",
  "Özet": "Summary",
  "İçerik": "Content",
  "Sunucudaki kalite kapısı tekrar eden, çok parçalı veya hassas içeriği reddeder.":
    "The server's quality gate rejects duplicate, multi-part or sensitive content.",
  "Aynı bilgi zaten kayıtlı.": "The same fact is already saved.",
  "kalite kapısı reddetti": "rejected by the quality gate",
  "Kaydedilmedi: ": "Not saved: ",
  "Hafızaya kaydedildi.": "Saved to memory.",
  "Neden değiştiriyorsun? (denetim kaydına yazılır)": "Why are you changing it? (written to the audit log)",
  "Kaydı düzenle": "Edit record",
  "Değişiklik nedeni": "Reason for change",
  "Değişiklik kaydedildi.": "Change saved.",
  "Neye bakarak doğruladın?": "What did you check to confirm it?",
  "Neden artık geçerli değil?": "Why is it no longer valid?",
  "Kaydı doğrula": "Revalidate record",
  "Kaydı geçersiz say": "Invalidate record",
  "Doğrula": "Revalidate",
  "Geçersiz say": "Invalidate",
  "Bağlı kod hedefi: {n}": "Linked code targets: {n}",
  ", bulunamayan: {n}": ", not found: {n}",
  "Açıklama": "Explanation",
  "Panel: Hâlâ doğru — ": "Panel: Still accurate — ",
  "Panel: Artık geçerli değil — ": "Panel: No longer valid — ",
  "Kayıt doğrulandı.": "Record revalidated.",
  "Kayıt geçersiz sayıldı.": "Record invalidated.",
  "Yerine geçecek kaydı ara": "Search for the replacement record",
  "Neden yenisiyle değiştiriyorsun?": "Why are you replacing it?",
  "Değiştir": "Replace",
  "Yerine geçecek kayıt": "Replacement record",
  "Yerine geçecek kaydı seçmelisin.": "Select the replacement record.",
  "Panel: Yenisiyle değiştir — ": "Panel: Replace with newer — ",
  "Kaydın yerini yenisi aldı.": "The record was superseded.",
  "Onay için kimliğin ilk 8 karakteri": "First 8 characters of the ID, to confirm",
  "Kalıcı silmeyi onaylamak için {prefix} yaz": "Type {prefix} to confirm permanent deletion",
  "Neden siliyorsun? (denetim kaydına yazılır)": "Why are you deleting it? (written to the audit log)",
  "Arşivle": "Archive",
  "Geri alınabilir; kayıt ve denetim izi kalır, aramalarda çıkmaz.":
    "Reversible; the record and its audit trail stay, but it no longer appears in searches.",
  "Kalıcı sil": "Delete permanently",
  "Geri alınamaz; kayıt ve olay geçmişi silinir.": "Cannot be undone; the record and its event history are deleted.",
  "Kaydı sil": "Delete record",
  "Neden": "Reason",
  "Kalıcı silme için {prefix} yazmalısın.": "Type {prefix} to delete permanently.",
  "Kayıt kalıcı olarak silindi.": "Record permanently deleted.",
  "Kayıt arşivlendi.": "Record archived.",

  // pending
  "Ajanların önerdiği ama senin kararını bekleyen bilgiler. Onayladığın kayıt aranabilir hale gelir; reddettiğin kayıt kullanılmaz.":
    "Knowledge proposed by agents that is waiting for your decision. Approved records become searchable; rejected ones are not used.",
  "Onay bekleyen kayıt yok": "No pending records",
  "Yeni öneri geldiğinde burada ve sol menüdeki rozette görünür.":
    "New proposals appear here and in the badge in the left menu.",
  "Daha fazla göster ({n})": "Show more ({n})",
  "Adsız öneri": "Untitled proposal",
  "Onayla": "Approve",
  "Reddet": "Reject",
  "İsteğe bağlı not": "Optional note",
  "Neden reddediyorsun? (isteğe bağlı)": "Why are you rejecting it? (optional)",
  "Kaydı onayla": "Approve record",
  "Kaydı reddet": "Reject record",
  "Kayıt onaylandı.": "Record approved.",
  "Kayıt reddedildi.": "Record rejected.",

  // rules
  "Ajanların her oturumun başında yüklediği onaylı talimatlar. Genel kurallar her projede, proje kuralları yalnız o projede, modül ve düğüm kuralları belirli dizin ya da dosyalarda geçerlidir. Bir düğüme kural bağlamak için Kod grafı sekmesini kullan.":
    "Approved instructions that agents load at the start of every session. Global rules apply to every project, project rules only to that project, and module and node rules to specific directories or files. To attach a rule to a node, use the Code map tab.",
  "Etkin kural yok": "No active rules",
  "Kural, ajanların her oturumda uyacağı kalıcı bir talimattır. İlk kuralı yazıp onayladığında ajanlar bir sonraki oturumda yükler.":
    "A rule is a lasting instruction agents follow in every session. Once you write and approve the first rule, agents load it in their next session.",
  "Her projede": "Every project",
  "Proje geneli": "Whole project",
  "Modül ve düğüm": "Module and node",
  "Belirli dizinler, dosyalar ve metotlar": "Specific directories, files and methods",
  "Sürüm {n}": "Version {n}",
  "Gerekçe": "Rationale",
  "Uygulanma": "Applies to",
  "Belirli dizinler / dosyalar": "Specific directories / files",
  "Tüm proje": "Whole project",
  "Hedefler": "Targets",
  "Yeni sürüm hazırla": "Prepare a new version",
  "Kuralın yeni sürümü": "New version of the rule",
  "Onay kartını göster": "Show the confirmation card",
  "Kural metni": "Rule text",
  "Örn: Bu kartı onaylıyorum.": "E.g. I approve this card.",
  "Onay kartı": "Confirmation card",
  "Etkinleştir": "Activate",
  "Sunucunun hazırladığı kart aşağıda aynen gösteriliyor. Etkinleştirmek için kendi onay cümleni yaz.":
    "The card prepared by the server is shown below unchanged. To activate it, write your own approval sentence.",
  "Onay metnin": "Your approval text",
  "Kuralın yeni sürümü etkinleştirildi.": "The new version of the rule is active.",
  "Kural önce taslak olarak kaydedilir, sonra sunucunun onay kartını görürsün. Kartı kendi cümlenle onayladığında etkinleşir.":
    "The rule is saved as a draft first, then you see the server's confirmation card. It becomes active once you approve the card in your own words.",
  "Bu düğüm": "This node",
  "Kuralı yaz": "Write the rule",
  "Onay kartını incele": "Review the confirmation card",
  "Belirli dizinler": "Specific directories",
  "Örn: core/**": "E.g. core/**",
  "Her projede geçerli": "Applies to every project",
  "Dizin desenleri": "Directory patterns",
  "Virgülle ayır. Yalnız bu dizinlerde çalışırken yüklenir.": "Comma-separated. Loaded only while working in these directories.",
  "Ajanın uyması gereken talimat, tek ve net": "The instruction the agent must follow, single and clear",
  "Neden? (isteğe bağlı)": "Why? (optional)",
  "Taslağı kaydet ve kartı göster": "Save draft and show the card",
  "Her projede, her ajan oturumunun başında yüklenir.": "Loaded in every project, at the start of every agent session.",
  "{project} projesinde, her ajan oturumunun başında yüklenir.": "Loaded in project {project}, at the start of every agent session.",
  "{project} projesinde yalnız şu dizinlerde çalışırken gösterilir: {globs}":
    "Shown in project {project} only while working in these directories: {globs}",
  "Nerede gösterilir": "Where it is shown",
  "Nerede gösterilir: ": "Where it is shown: ",
  "Örn: Bu kuralı onaylıyorum.": "E.g. I approve this rule.",
  "Kuralı etkinleştir": "Activate rule",
  "Onaylayacağın kural": "The rule you are approving",
  "Sunucunun onay kartı (değiştirilmeden, okunur biçimde). Onayın bu karta bağlanır.":
    "The server's confirmation card (unchanged, formatted for reading). Your approval is bound to this card.",
  "Bu metin kendi onayın olarak denetim kaydına yazılır.": "This text is written to the audit log as your own approval.",
  "Geri dön": "Back",
  "Kural etkinleştirildi. Ajanlar bir sonraki oturumda yükleyecek.": "Rule activated. Agents will load it in their next session.",

  // references
  "Bir dosya seç": "Select a file",
  "Soldaki listeden bir referans dosyası açabilir ya da yeni bir dosya oluşturabilirsin.":
    "Open a reference file from the list on the left or create a new one.",
  "Hafıza kayıtlarının bağlandığı uzun prosedürler, sorgular ve eşleme notları. Tüm projeler aynı paylaşılan klasörü kullanır.":
    "Long procedures, queries and mapping notes that memory records link to. All projects use the same shared folder.",
  "Yeni klasör": "New folder",
  "Yeni dosya": "New file",
  "Klasör": "Folder",
  "Bu klasör boş": "This folder is empty",
  "Yeni bir dosya ya da klasör oluşturabilirsin.": "You can create a new file or folder.",
  "Değişmiş": "Changed",
  "Dosya yok": "File missing",
  "Klasör yolu": "Folder path",
  "Kök": "Root",
  "Bu dosyada hassas içerik maskelendi; düzenlerken maskelenmiş metin kaydedilir.":
    "Sensitive content in this file was masked; editing saves the masked text.",
  "Örn: Pilotlama/petclinic": "E.g. Pilot/petclinic",
  "Oluştur": "Create",
  "Şu klasörün altında: {base}": "Inside folder: {base}",
  "Paylaşılan kökün altında": "Inside the shared root",
  "Klasör oluşturuldu.": "Folder created.",
  "Dosyayı düzenle": "Edit file",
  "Dosya yolu": "File path",
  "Örn: Pilotlama/petclinic/prosedur.md — .md, .sql, .json, .yaml gibi metin dosyaları":
    "E.g. Pilot/petclinic/procedure.md — text files such as .md, .sql, .json, .yaml",
  "Parola, anahtar veya token yazma; kayıttan önce otomatik maskelenir.":
    "Do not enter passwords, keys or tokens; they are masked automatically before saving.",
  "Dosya sen düzenlerken değişmiş. Sayfayı yenileyip güncel içerikten tekrar dene.":
    "The file changed while you were editing it. Reload the page and try again from the current content.",
  "Kaydedildi; hassas içerik maskelendi.": "Saved; sensitive content was masked.",
  "Kaydedildi.": "Saved.",

  // graph tab
  "Projenin paketleri, sınıfları ve metotları katman katman.": "The project's packages, classes and methods, layer by layer.",
  "Önce bir proje seç": "Select a project first",
  "Kod grafı tek bir projeyi gösterir. Üstten bir proje seç ya da yeni bir klasör ekle.":
    "The code map shows one project. Select a project at the top or add a new folder.",
  "Kural ekle": "Add rule",
  "paket": "package",
  "sınıf": "class",
  "dosya": "file",
  "metot": "method",
  "{target} metodunda: ": "In method {target}: ",
  "Bu sembol açık ağaçta değil; bağlamak için paketini açıp düğümü seç.":
    "This symbol is not in the open tree; to attach something, open its package and select the node.",
  "Hafıza ekle": "Add memory",
  "Bu projeye kaydet ve bu düğüme bağla": "Save to this project and attach to this node",
  "Bu projeye kaydet": "Save to this project",
  "Her projede geçerli genel bilgi (koda bağlanmaz)": "Global knowledge for every project (not attached to code)",
  "Metoda bağlanan kural {path} dosyasının tamamında çalışılırken gösterilir.":
    "A rule attached to a method is shown while working anywhere in {path}.",
  "Tam ekranda aç": "Open full screen",
  "Klasörü indeksle": "Index a folder",

  // jobs
  "Son iş": "Last job",
  "“Son işe devam” ile açılan tek kayıt. Ajan bir işi bitirmeden bıraktığında burada durur.":
    "The single record opened by “continue last job”. When an agent stops before finishing a job, it stays here.",
  "Sonra devam etmek için adıyla kaydedilen işler.": "Jobs saved by name to continue later.",
  "Yalnız sana ait, projeden bağımsız notlar ve tercihler.": "Notes and preferences that belong only to you, independent of any project.",
  "Ajanların yarım bıraktığı ve kaydettiği işler ile kişisel hafızan. Bu bölüm yalnız okunur; kayıtları açıp silebilirsin.":
    "Jobs that agents left unfinished or saved, and your personal memory. This section is read-only; you can open and delete records.",
  "Son iş kaydı yok": "No last job",
  "Ajan bir işi yarım bıraktığında “son iş” olarak burada görünür ve sonraki oturumda devam edilebilir.":
    "When an agent leaves a job unfinished, it appears here as the “last job” and can be continued in the next session.",
  "Adsız": "Untitled",
  "Kayıtlı iş yok": "No saved jobs",
  "Kişisel hafıza boş": "Personal memory is empty",
  "Bir işi adıyla kaydettiğinde burada listelenir.": "Jobs you save by name are listed here.",
  "Ajana kişisel bir tercih ya da not kaydettirdiğinde burada görünür.": "Personal preferences or notes you have an agent save appear here.",
  "Silinmiş olabilir.": "It may have been deleted.",
  "Kayıtlı iş": "Saved job",
  "Kayıtlı işi sil": "Delete saved job",
  "Kişisel hafıza kaydını sil": "Delete personal memory record",
  "Kalıcı olarak sil": "Delete permanently",
  "Bu işlem geri alınamaz. Kayıt ajanların son işe devam ve arama akışlarından da kalkar.":
    "This cannot be undone. The record is also removed from the agents' continue-last-job and search flows.",
  "Kayıt silindi.": "Record deleted.",

  // add project
  "İndeksleme başlatılamadı: {reason}": "Indexing could not start: {reason}",
  "İndeksleme başlatılamadı (HTTP {status}).": "Indexing could not start (HTTP {status}).",
  "/home/sen/projeler/uygulama": "/home/you/projects/app",
  "Yapısal indekslemeyi başlat": "Start structural indexing",
  "Kod klasörünün tam yolunu gir. Yapısal indeksleme dosyaları, paketleri, sınıfları ve metotları çıkarır; dil modeli kullanmaz ve kod bu makineden çıkmaz.":
    "Enter the full path of the code folder. Structural indexing extracts files, packages, classes and methods; it uses no language model and no code leaves this machine.",
  "Sunucunun çalıştığı makinedeki mutlak yol.": "Absolute path on the machine the server runs on.",
  "Copilot kullanıyorsan, bu depoya talimatını kurmak için terminalde: ":
    "If you use Copilot, install its instructions for this repository from the terminal: ",
  "ai_orch project add <klasör> --copilot": "ai_orch project add <folder> --copilot",
  "İndeksleniyor…": "Indexing…",
  "İndeksleniyor · {s} sn": "Indexing · {s} s",
  "{files} dosya · {symbols} sembol": (p) => `${p.files} ${plural(p.files, "file", "files")} · ${p.symbols} ${plural(p.symbols, "symbol", "symbols")}`,
  "Tamamlandı · {s} sn": "Done · {s} s",
  "{n} dosya indekslendi": (p) => `${p.n} ${plural(p.n, "file", "files")} indexed`,
  ", {n} değişmediği için atlandı": ", {n} skipped as unchanged",

  // code atlas (graph/*.js, universe.html)
  "Katmanlı kod haritası": "Layered code map",
  "Kod haritası hazırlanıyor": "Preparing the code map",
  "Katmanlar": "Layers",
  "Bağımlılık": "Dependency",
  "Tümünü göster": "Show all",
  "Tüm grafı göster (F)": "Show the whole map (F)",
  "Hareket": "Motion",
  "Hareket azaltıldı": "Reduced motion",
  "Kamera uçuşlarını ve akış animasyonunu aç / kapat": "Turn camera flights and flow animation on / off",
  "Sınıf, metot, paket bul  /": "Find a class, method, package  /",
  "Kod haritasında bul": "Find in the code map",
  "Tam ekran": "Full screen",
  "Grafı ayrı sekmede tam ekran aç": "Open the map full screen in a new tab",
  "Bulunduğun katman": "Current layer",
  "Tıkla: aç ve seç · ↑↓: komşu · Enter: aç · Esc: bir üst katman · /: bul · F: tümünü göster":
    "Click: open and select · ↑↓: sibling · Enter: open · Esc: up one layer · /: find · F: show all",
  "{n} paket": (p) => `${p.n} ${plural(p.n, "package", "packages")}`,
  "{n} sınıf": (p) => `${p.n} ${plural(p.n, "class", "classes")}`,
  "{n} dosya": (p) => `${p.n} ${plural(p.n, "file", "files")}`,
  "{n} üye": (p) => `${p.n} ${plural(p.n, "member", "members")}`,
  "Bir üst katmana dön (Esc)": "Up one layer (Esc)",
  "Alt katman getirilemedi: ": "Could not load the layer below: ",
  "Bilinmeyen düğüm: ": "Unknown node: ",
  " · açılıyor…": " · opening…",
  "Düğüm haritada bulunamadı: ": "Node not found on the map: ",
  "Arama yapılamadı: ": "Search failed: ",
  "Bu projede kod haritası yok": "No code map for this project",
  "Proje henüz yapısal olarak indekslenmedi. Klasörü ekleyip indekslediğinde paketler, sınıflar ve metotlar burada katman katman görünür.":
    "The project has not been indexed yet. Once you add and index its folder, packages, classes and methods appear here layer by layer.",
  "Proje çok büyük: ilk 5000 paket gösteriliyor.": "The project is very large: showing the first 5000 packages.",
  "Kod haritası liste olarak": "Code map as a list",
  "Harita bu tarayıcıda çizilemediği için kod yapısını ağaç listesi olarak gösteriyoruz. Bir düğüme tıklayınca alt katmanı açılır ve ayrıntısı sağda görünür.":
    "This browser cannot draw the map, so the code structure is shown as a tree list. Click a node to open its layer below and see its details on the right.",
  "Dizin": "Directory",
  "Düğüm": "Node",
  "Dosya": "File",
  "Metot": "Method",
  "Paket": "Package",
  "Sınıf": "Class",
  "Üye": "Member",
  "Paketler": "Packages",
  "Sınıflar ve dosyalar": "Classes and files",
  "Üyeler": "Members",
  "Alt üyeler": "Nested members",
  "Kod ağacı boş döndü.": "The code tree came back empty.",
  "Bu kural {path} dosyasının tamamını düzenlerken gösterilir; hedef metot: {target}":
    "This rule is shown while editing anywhere in {path}; target method: {target}",
  "Bu kural {path} dosyasını düzenlerken gösterilir.": "This rule is shown while editing {path}.",
  "Bu kural {path}/ altındaki dosyaları düzenlerken gösterilir ({path}/**).": "This rule is shown while editing files under {path}/ ({path}/**).",
  "Seçimi bırak (Esc)": "Clear selection (Esc)",
  "Komşu düğüm": "Neighbour node",
  " · katman {n}": " · layer {n}",
  "Düğümün yolu": "Node path",
  "Yol": "Path",
  "Tam ad": "Full name",
  "İmza": "Signature",
  "Alt öğe": "Children",
  "{n} hafıza": (p) => `${p.n} ${plural(p.n, "memory", "memories")}`,
  "{n} kural": (p) => `${p.n} ${plural(p.n, "rule", "rules")}`,
  "İçindekiler ({n})": "Contents ({n})",
  "Filtrele": "Filter",
  "İçindekileri filtrele": "Filter contents",
  "Bağlı kayıtlar getirilemedi: ": "Could not load attached records: ",
  "Bağlı hafıza ({n})": "Attached memory ({n})",
  "Bu düğüme bağlı hafıza kaydı yok.": "No memory is attached to this node.",
  "Bağlı kurallar ({n})": "Attached rules ({n})",
  "Bu düğüme bağlı kural yok.": "No rules are attached to this node.",
  "metot: {target} · uygulanır: dosya {path}": "method: {target} · applies to: file {path}",
  "Kod bağlantıları getirilemedi: ": "Could not load code links: ",
  "bağlantı": "link",
  "Kod bağlantıları ({n})": "Code links ({n})",
  "Bu sembol için çağrı ya da enjeksiyon kaydı yok.": "No calls or injections are recorded for this symbol.",
  "Sunucuya ulaşılamadı": "Could not reach the server",
  "Panelde proje ekle": "Add a project in the panel",
  "Proje yok": "No projects",
  "Henüz kayıtlı bir proje bulunmuyor. Panelden bir klasör ekleyip yapısal olarak indeksleyebilirsin.":
    "No project has been added yet. Add a folder in the panel to index its structure.",
  "Panelde bağla": "Attach in the panel",
  "Bu düğüme hafıza ya da kural eklemek için paneli aç": "Open the panel to add memory or a rule to this node",
};

/** The text for the current language; params fill {name} placeholders. */
export function t(text, params) {
  const entry = lang === "en" ? EN[text] : undefined;
  if (typeof entry === "function") return entry(params || {});
  const out = entry ?? text;
  return params ? out.replace(/\{(\w+)\}/g, (m, key) => (key in params ? String(params[key]) : m)) : out;
}

/** Translates the static Turkish text of an HTML page: text nodes, title / aria-label / placeholder, <title>. */
export function localize(root = document) {
  if (lang === "tr") return;
  document.title = t(document.title);
  const body = root.body || root;
  const walker = document.createTreeWalker(body, NodeFilter.SHOW_TEXT);
  for (let node = walker.nextNode(); node; node = walker.nextNode()) {
    const text = node.nodeValue.trim();
    if (text && EN[text] !== undefined) node.nodeValue = node.nodeValue.replace(text, t(text));
  }
  for (const el of body.querySelectorAll("[title], [aria-label], [placeholder]")) {
    for (const attr of ["title", "aria-label", "placeholder"]) {
      const value = el.getAttribute(attr);
      if (value && EN[value] !== undefined) el.setAttribute(attr, t(value));
    }
  }
}
