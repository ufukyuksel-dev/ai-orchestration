# AI Orchestration — Agent Skills

Bu klasör, AI Orchestration üzerinde çalışan AI agent'lar için **invokable skill**'leri içerir. Skill = bir task workflow'u (debug etme, MCP tool ekleme, kod değiştirme). Agent ilgili bir göreve başladığında skill'in `description`'ı tetiklenir ve skill otomatik yüklenir — her turn yüklenen instruction dosyalarından (`CLAUDE.md`/`AGENTS.md`/`.github/copilot-instructions.md`) farklıdır.

Ortak skill'ler aynı iş akışını taşır; runtime'a özgü güvenlik ve araç erişimi farkları kendi klasöründeki varyantta tutulur. Copilot varyantları hiçbir yoldan `scanner.scan*` kullanmaz ve yalnızca `ai_orch` terminal komutunu çağırır; AI Orchestration skill'leri Copilot'a özeldir. Ayrı klasörler her runtime'ın resmi keşif yoluna temiz kurulum sağlar.

## Skill'ler
- `debugging-this-project` — bir hata / test failure debug ederken.
- `adding-an-mcp-tool` — yeni bir MCP tool eklerken.
- `changing-code-safely` — bir sembolü değiştirmeden önce impact analizi.
- `ai-orchestration-memory` — Copilot'da seçici proje belleği: dar retrieval, duplicate NOOP/update, codeLocators, pending/approval, archive. Copilot'a özeldir.
- `ai-orchestration-rules` — instruction vs selector rule; global/project/module kapsam; tam confirmation card ve gerçek insan onayı. Copilot'a özeldir.
- `ai-orchestration-references` — shared reference katalogu, expected-hash, relation ve bounded graph. Copilot'a özeldir.
- `ai-orchestration-jobs` — LAST_JOB, adlandırılmış iş ve kişisel memory ayrımı. Copilot'a özeldir.

Format her runtime'da aynı: `<skill-adı>/SKILL.md`, frontmatter'da `name` + `description`.

## Global kurulum (tüm projelerde aktif)

Her runtime'ın user-level skill dizini farklıdır (doğrulanmış yollar):

### Claude Code → `~/.claude/skills/`
```bash
python3 scripts/install_agent_instructions.py --dry-run
python3 scripts/install_agent_instructions.py
python3 scripts/install_agent_instructions.py --check
```
Not: Claude Code cross-runtime `~/.agents/skills/` dizinini **okumaz**; kendi diziniyle çalışır.

### Codex → `~/.codex/skills/`
```bash
# Aynı installer Codex ve Claude kurulumunu birlikte yönetir.
python3 scripts/install_agent_instructions.py --check
```

### GitHub Copilot CLI → `~/.copilot/`
```bash
python3 skills/copilot/ai-orchestration-memory/scripts/install_user_integration.py --dry-run
python3 skills/copilot/ai-orchestration-memory/scripts/install_user_integration.py
python3 skills/copilot/ai-orchestration-memory/scripts/install_user_integration.py --check
```

Installer yalnızca sahibi olduğu öğeleri kurar: user-level `preToolUse` hook'u
(`~/.copilot/hooks/ai-orchestration-safety.json`), `~/.local/bin` altındaki
`ai_orch` ve `ai-orch-memory` symlink'leri, `~/.copilot/copilot-instructions.md`
içindeki işaretli managed blok, `~/.copilot/skills/` altındaki checkout'a bağlı
skill symlink'leri, özel staging dizini ve ownership manifest'i. Managed blok
dışındaki kişisel metin byte düzeyinde korunur; yabancı dosya/symlink ezilmez,
çakışma raporlanır. `--dry-run` tam planı ve diff'i yazar, hiçbir şeyi
değiştirmez. `--check` kurulumu değiştirmeden drift arar. `--uninstall` yalnızca
sahip olunan öğeleri kaldırır; sunucuda oluşmuş memory/rule/job/reference
kayıtlarına dokunmaz. `COPILOT_HOME` desteklenir.

**Copilot için tek arayüz `ai_orch` terminal komutudur.** Copilot'a native AI
Orchestration MCP kaydı, araç keşfi veya tool invocation eklenmez; hata sonrası
native yola düşülmez. Installer hiçbir MCP kaydı oluşturmaz. Bridge süreci
içeride mevcut loopback MCP taşımasını kullanır — bu bir taşıma detayıdır,
"hiç MCP yok" demek değildir. Kurumsal ağ/politika kısıtı varsa gizlice aşma.

Copilot'ta scanner erişimi yalnızca proje-bağlı `ai_orch scan` komutlarıyla
vardır; provider/model sorulmaz, kalıcı silme yoktur ve admin approval bypass'ı
yoktur. Hook bu sınırları uygular: yalnız
bu checkout'a çözülen `ai_orch`/`ai-orch-memory` launcher'ını tek doğrudan komut
olarak pre-approve eder, bu servise ait native araç çağrılarını reddeder ve
başka MCP servislerini yalnızca kelime benzerliğinden engellemez. Bu user-level
koruma defense-in-depth'tir: hook devre dışı bırakılabilir, keşfedilmeyebilir
veya timeout'ta fail-open davranabilir; kullanıcının değiştirebildiği bir
checkout'taki script yolu değişmez bir güven kökü değildir. Mutlak enforcement
gereken kurulumlarda Copilot'a `scanner.scan*` scope'u olmayan ayrı bir server
credential/role verilmelidir.

> **Kısayol uyarısı:** Codex ve Copilot CLI ortak `~/.agents/skills/` dizinini de
> okuyabilir. Aynı skill'i hem `~/.copilot/skills` hem `~/.agents/skills` hem de
> ayrı bir kayıt yolundan kurma; çift katalog oluşur. Tek kanonik kayıt yukarıdaki
> installer'dır. Gerçek durumu `copilot /skills list` ve `/skills info` ile
> doğrula; gerekirse `/skills reload`. Skill reload ile instruction reload aynı
> şey değildir.

### IntelliJ / JetBrains Copilot → her proje için `.github/copilot-instructions.md`

IntelliJ Copilot eklentisi `~/.copilot/` altını **okumaz**: instruction yüzeyi
repo içindeki `.github/copilot-instructions.md`, skill yüzeyi ise repo içindeki
`.github/skills/` dizinidir — global skill dizini algılanmaz
([copilot-intellij-feedback#1517](https://github.com/microsoft/copilot-intellij-feedback/issues/1517)).
Bu yüzden AI Orchestration dışındaki her projeye **hem işaretli instruction
bloğunu hem de sekiz Copilot skill'inin checkout'a symlink'ini** kuran ayrı bir
installer var. Skill'ler kopya değil symlink; kaynak skill değişince tüm projeler
aynı anda güncellenir. IDE tarafında
`Settings > GitHub Copilot > Chat > Agent > Agent Skills` açık olmalı.

Tek seferlik global launcher (PATH'te `~/.local/bin` zaten var):

```bash
ln -sfn "$PWD/scripts/install_project_copilot_instructions.py" ~/.local/bin/ai-orch-copilot
```

Sonrası: **projeye gir, argümansız çalıştır.** Hedef, çalışma dizininin en yakın
`.git` köküdür:

```bash
cd /path/to/repo
ai-orch-copilot --dry-run
ai-orch-copilot
ai-orch-copilot --check
ai-orch-copilot --uninstall
```

`--target` sadece başka bir repoyu dışarıdan sürmek için; tekrarlanabilir.
`--allow-non-git` git olmayan dizin için, `--no-skills` yalnızca instruction
bloğunu kurmak için. Launcher kurmak istemezsen
`python3 scripts/install_project_copilot_instructions.py` de aynı şekilde
çalışır (o da cwd'nin git köküne kurar).

Sahip olduğu şeyler: `<!-- ai-orchestration:copilot-project:begin/end -->`
işaretleri arasındaki blok ve `.github/skills/` altında **bu checkout'a çözülen**
symlink'ler. Yabancı skill dizini veya başka yere bakan symlink ezilmez, çakışma
sayılır; `--uninstall` yalnız sahip olduğu link'leri siler. Blok dışındaki
kişisel metin byte düzeyinde korunur,
symlink'li `.github` veya instruction dosyası ezilmez, bozuk/çift işaret
çakışma sayılır. Birden fazla hedefte tüm preflight bitmeden hiçbir yazma
yapılmaz. `--dry-run` tam diff'i yazar, `--check` drift'te 1 döner.

Ek launcher, hook veya MCP kaydı **gerekmez**: `ai_orch` zaten `~/.local/bin`
üzerinden global olarak PATH'tedir ve çalışma dizininin en yakın `.git` köküne
bağlanır, yani hedef projede o projenin kendi projectKey'ini çözer.

Bu dosya hedef repoya commit edilir — IntelliJ için amaç budur, ama bilinçli bir
tercih olsun. Dosyanın yazılmış olması eklentinin onu yüklediğinin kanıtı
değildir; gerçek bir IntelliJ Copilot oturumunda doğrula.

Testler: `python3 scripts/test_install_project_copilot_instructions.py`

## Onaylı başlangıç kuralları

Bu talimat yükleme akışı Codex, Claude Code ve Copilot'u kapsar. Codex/Claude `rules.instructions` aracını native çağırır. Copilot önce `ask_user` ile oturumun global/proje kurallarını yüklemeyi teklif eder; evet cevabında `ai_orch rule instructions --scope effective` ve gerektiğinde modül çağrısını yapar, hayır cevabında bu oturum/proje için ikisini de atlayıp yerel bağlamla devam eder. Evet cevabından sonraki yükleme eksikse bağımlı iş durur; "kural yok" denmez. Skill yedekleri keşif dizinlerinin dışında `~/.ai-orchestration-backups/<runtime>-skills/` (Copilot için `~/.ai-orchestration-backups/copilot/`) altında tutulur.

Installer Codex skill kaynaklarını `~/.codex/skills/`, Claude kaynaklarını `~/.claude/skills/` altına symlink ile bağlar. `CODEX_HOME` veya `--codex-home` ayarı desteklenir; Copilot'ın da okuduğu `~/.agents/skills/` değiştirilmez. Kaynak skill değişiklikleri yeni oturumlarda kopyalamadan görünür.

Global talimat dosyasına küçük, sahipliği işaretlenmiş bir blok eklenir: Claude `@import`, Codex açık okuma talimatıyla [session-instructions.md](session-instructions.md) dosyasını yükler. Agent her yeni oturumun ilk kullanıcı isteğinde, esas yanıtı vermeden veya göreve başlamadan proje anahtarını çözer ve `rules.instructions` ile global/proje kurallarını alır. Skill yazımı, belge, tarayıcı işi ve basit sorular da buna dahildir; görevin ayrıca AI Orchestration/MCP kullanmasını, planı veya değişecek sınıf listesini beklemez. Başarılı okuma aynı oturum/projede tekrar kullanılır; modüle geçmeden ilgili modül kurallarını yükler. Bu bir istemci talimatıdır, davranışa yüzde yüz uyum veya sistem seviyesi yaptırım garantisi değildir.

`--dry-run` somut diff'i gösterir; `--check` drift/çatışma ve görünen talimat byte boyutlarını raporlar. Eşleşen eski kopyalar yedeklenir; farklı içerik ve yabancı symlink'ler ezilmez. Mevcut `MCP Lifecycle` bölümü varsa içeriği bayt bayt korunur; yeni başlangıç bloğu hemen önüne eklenir. Bu durum bilgi satırı olarak raporlanır, çakışma sayılmaz. Diğer kişisel bölümler ve AI_Developer bloğu korunur. `--uninstall` yalnız sahip olunan bağlantı/blokları kaldırır, kendi oluşturduğu yedekleri geri getirir.

DB'deki yeni `instruction` türü mevcut `rules.draft → rules.preview → rules.promote` onay akışını kullanır. Global kuralları yazarken projectKey bilinçli olarak boş bırakılır; proje/modül için çözümlenmiş projectKey zorunludur. Örnekler ve limitler startup dosyasındadır. Yerel güven ilişkili `scanner.project.resolve`, `.git` dosyası/dizini bulunan kökü tarama yapmadan metadata olarak kaydeder; explicit projectKey verildiğinde aynı projenin aktif dizinini günceller. Aynı dizine ait alias kayıtları engel değildir; anahtar verilmezse alfabetik ilk kayıt seçilir. Resolver ve scanner yol izin listesi uygulamaz. Tarama yalnızca kullanıcı mevcut konuşmada açıkça istediğinde başlatılır; stale/missing baseline otomatik tarama nedeni değildir. Yeni sunucu sürümü ve V51 migration'ı çalışmadan okuma aracı kullanılamaz. MCP kapalıysa veya kapasite aşılırsa yükleme tamamlanmış sayılmaz.

## Sadece bu repo için (project-scope, Claude Code)
Global yerine yalnızca bu repoda istersen:
```bash
mkdir -p .claude/skills && cp -R skills/claude/* .claude/skills/
```
`.claude/` `.gitignore`'da olduğu için bu kopya commit edilmez; commit'li kaynak her zaman bu `skills/` klasörüdür.

## Kurulum sonrası doğrulama
- **Claude Code:** yeni session aç; ilgili bir task'ta skill otomatik tetiklenir.
- **Codex:** skill'ler native yüklenir; ilgili görevde devreye girer.
- **Copilot:** `copilot /skills list` çıktısında proje skill'lerini gör; yeni bir session'da ilk istekte `ask_user` ile kural yükleme sorusunun çıktığını doğrula. Evet cevabında `ai_orch rule instructions --scope effective` çalışmalı ve tam metin gelmeli; hayır cevabında bu çağrı yapılmadan görev devam etmeli. `ai_orch capabilities` çıktısında scanner komutlarını `available` gör. Dosyanın var olması yeterli kanıt değildir — gerçek session çıktısını gör.

## Bağımlılık
Genel skill'ler MCP server'a bağlı olmanı varsayar. Copilot skill'leri yalnızca `ai_orch` terminal komutunu kullanır; native MCP kaydı seçeneği yoktur. Bridge de AI Orchestration'ın Streamable HTTP/MCP wire protokolünü kullandığı için şirket politikasını gizlice aşmak amacıyla kullanılmamalıdır. Bağlantı için: `output/mcp-client-connection-guide.md`. Daha derin proje haritası: `docs/agent/PROJECT-MAP.md`.

### Belirli bir eski işi hatırlama

Açık kullanıcı isteğinde `job_memory.save(title, summary, content, jobId?)` ile ayrı iş kaydı saklanır. Kullanıcının ipucuyla `job_memory.search(query, topK=3)`, seçilen kaydın tam notu için `job_memory.get(jobId)` kullanılır. LAST_JOB tek son iş olarak kalır; otomatik çift kayıt veya başlangıçta arama yapılmaz. Ortak davranış ve limitler `session-instructions.md` içindeki “Saved jobs” bölümündedir.
