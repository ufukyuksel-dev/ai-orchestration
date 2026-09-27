package com.mbworldwideapps.aiorchestration.modules.mcp.server;

import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryCodeLocator;

import com.mbworldwideapps.aiorchestration.core.security.LoopbackAddressMatcher;
import com.mbworldwideapps.aiorchestration.modules.rules.WorkspaceRuleRevisionService;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryLifecycleService;
import com.mbworldwideapps.aiorchestration.modules.workspace.PanelText;
import com.mbworldwideapps.aiorchestration.modules.workspace.WorkspaceReadService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;

@RestController
@RequestMapping("/workspace/api")
@ConditionalOnProperty(
        prefix = "ai-orchestration.mcp",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class WorkspaceController {
    public record MemoryEdit(
            @NotBlank @Size(max = 16384) String summary,
            @NotBlank @Size(max = 262144) String text,
            @Size(max = 100) List<@Size(max = 128) String> tags,
            @NotBlank @Size(max = 2048) String reason) {}

    public record RuleEdit(
            @NotBlank @Size(max = 16384) String statement, @Size(max = 4096) String rationale) {}

    public record MemoryCreate(
            @NotBlank @Size(max = 160) String summary,
            @NotBlank @Size(max = 700) String content,
            @Size(max = 32) String memoryType,
            @Size(max = 20) List<@Size(max = 64) String> tags,
            @Size(max = 200) String project,
            @Size(max = 16) String scope,
            @Size(max = 16) List<MemoryCodeLocator> codeLocators) {

        public MemoryCreate(String summary, String content, String memoryType, List<String> tags, String project) {
            this(summary, content, memoryType, tags, project, null, null);
        }
    }

    public record MemoryDelete(
            @NotBlank @Size(max = 16) String mode,
            @NotBlank @Size(max = 2048) String reason,
            Boolean humanConfirmed,
            @Size(max = 2048) String humanRawText) {}

    public record MemoryDecision(
            @NotBlank @Size(max = 16) String decision,
            @Size(max = 2048) String note,
            @Size(max = 200) String project) {}

    public record RuleDraft(
            @Size(max = 200) String project,
            @NotBlank @Size(max = 16) String scope,
            @NotBlank @Size(max = 16384) String statement,
            @Size(max = 4096) String rationale,
            @Size(max = 32) List<@Size(max = 512) String> moduleGlobs,
            @Valid NodeTarget nodeTarget) {

        public RuleDraft(String project, String scope, String statement, String rationale, List<String> moduleGlobs) {
            this(project, scope, statement, rationale, moduleGlobs, null);
        }
    }

    /** A graph node a rule or memory is attached to: package (directory), class/file (file) or member (symbol). */
    public record NodeTarget(
            @NotBlank @Size(max = 16) String kind,
            @NotBlank @Size(max = 1024) String path,
            @Size(max = 1024) String fqn,
            @Size(max = 1024) String signature) {}

    public record RuleDraftRef(@NotBlank @Size(max = 64) String draftId, @Size(max = 200) String project) {}

    public record RulePromotion(
            @NotBlank @Size(max = 64) String draftId,
            @Size(max = 200) String project,
            @NotBlank @Size(max = 128) String candidateHash,
            @NotBlank @Size(max = 128) String approvalContentHash,
            @NotBlank @Size(max = 128) String confirmationCardHash,
            @NotBlank @Size(max = 64) String workflowContractVersion,
            @NotBlank @Size(max = 4096) String humanRawText) {}

    public record ReferenceWrite(
            @NotBlank @Size(max = 1024) String relativePath,
            @Size(max = 262144) String content,
            @Size(max = 128) String expectedHash) {}

    public record ReferencePath(@NotBlank @Size(max = 1024) String relativePath) {}

    private final WorkspaceReadService reads;
    private final MemoryMcpTool memory;
    private final WorkspaceRuleRevisionService revisions;
    private final McpAuditLogger audit;
    private final MemoryLifecycleService lifecycle;
    private final com.mbworldwideapps.aiorchestration.modules.references.ReferenceService references;
    private final MemoryReviewMcpTool review;
    private final RulesMcpTool rules;
    private final ReferenceMcpTool referenceTool;
    private final com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();

    public WorkspaceController(
            WorkspaceReadService reads,
            MemoryMcpTool memory,
            WorkspaceRuleRevisionService revisions,
            McpAuditLogger audit,
            MemoryLifecycleService lifecycle,
            com.mbworldwideapps.aiorchestration.modules.references.ReferenceService references,
            MemoryReviewMcpTool review,
            RulesMcpTool rules,
            ReferenceMcpTool referenceTool) {
        this.reads = reads;
        this.memory = memory;
        this.revisions = revisions;
        this.audit = audit;
        this.lifecycle = lifecycle;
        this.references = references;
        this.review = review;
        this.rules = rules;
        this.referenceTool = referenceTool;
    }

    private ScannerMcpTool scanner;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setScanner(ScannerMcpTool scanner) {
        this.scanner = scanner;
    }

    private com.mbworldwideapps.aiorchestration.modules.workspace.CodeTreeService codeTree;
    private com.mbworldwideapps.aiorchestration.modules.workspace.JobsPanelService jobsPanel;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setPanelServices(com.mbworldwideapps.aiorchestration.modules.workspace.CodeTreeService codeTree,
            com.mbworldwideapps.aiorchestration.modules.workspace.JobsPanelService jobsPanel) {
        this.codeTree = codeTree;
        this.jobsPanel = jobsPanel;
    }

    @ModelAttribute
    public void guard(HttpServletRequest request, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");
        // Reuse the real MCP authentication context. This UI is intentionally local-user only.
        McpClientContext c = McpClientContextHolder.require();
        if (!McpProjectKeys.isLocalTrust(c)
                || !LoopbackAddressMatcher.isLoopback(request.getRemoteAddr())
                || !("localhost".equalsIgnoreCase(request.getServerName())
                        || LoopbackAddressMatcher.isLoopback(request.getServerName()))
                || !"1".equals(request.getHeader("X-Workspace-Request")))
            deny(PanelText.t("Access to the local workspace was denied",
                    "Yerel çalışma alanına erişim reddedildi"));
        String origin = request.getHeader("Origin");
        if (origin != null) {
            try {
                URI u = URI.create(origin);
                int port =
                        u.getPort() < 0 ? ("https".equals(u.getScheme()) ? 443 : 80) : u.getPort();
                if (!Objects.equals(u.getScheme(), request.getScheme())
                        || !Objects.equals(u.getHost(), request.getServerName())
                        || port != request.getServerPort())
                    deny(PanelText.t("Cross-origin request denied", "Farklı origin isteği reddedildi"));
            } catch (IllegalArgumentException e) {
                deny(PanelText.t("Invalid origin", "Geçersiz origin"));
            }
        }
        String site = request.getHeader("Sec-Fetch-Site");
        if (site != null && !Set.of("same-origin", "none").contains(site))
            deny(PanelText.t("Cross-site request denied", "Farklı site isteği reddedildi"));
    }

    @GetMapping("/projects")
    public Object projects() {
        return read(
                "workspace.projects",
                "memory.read",
                () ->
                        Map.of(
                                "projects",
                                reads.projects(),
                                "defaultProject",
                                McpClientContextHolder.require().projectKey()));
    }

    @GetMapping("/items")
    public Object items(
            @RequestParam String kind,
            @RequestParam(defaultValue = "") String project,
            @RequestParam(defaultValue = "") String query,
            @RequestParam(defaultValue = "") String status,
            @RequestParam(defaultValue = "") String type,
            @RequestParam(name = "scope", defaultValue = "") String memoryScope,
            @RequestParam(defaultValue = "") String tag,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") int limit) {
        if (query.length() > 2048 || project.length() > 200 || status.length() > 80 || type.length() > 40
                || memoryScope.length() > 16 || tag.length() > 64)
            throw new IllegalArgumentException(PanelText.t("Filter is too long", "Filtre çok uzun"));
        return read(
                "workspace.list",
                scope(kind),
                () -> type.isEmpty() && memoryScope.isEmpty() && tag.isEmpty()
                        ? reads.list(kind, project, query, status, cursor, limit)
                        : reads.list(kind, project, query, status, type, memoryScope, tag, cursor, limit));
    }

    @GetMapping("/code-memory")
    public Object codeMemory(@RequestParam String project, @RequestParam(required=false) String cursor) {
        require("memory.read"); require("context.graph.read");
        return read("workspace.code-memory","codebase.read",() -> reads.codeMemory(project,cursor));
    }

    @GetMapping("/code-graph")
    public Object codeGraph(
            @RequestParam String project,
            @RequestParam String part,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "5000") int limit) {
        if (project.length() > 200) throw new IllegalArgumentException("Project too long");
        return read(
                "workspace.code-graph",
                "codebase.read",
                () -> reads.codeGraph(project, part, cursor, limit));
    }

    /** Layered graph: children of one node (project → package → class/file → member), opened on demand. */
    @GetMapping("/code-tree")
    public Object codeTree(
            @RequestParam String project,
            @RequestParam(defaultValue = "") String parent,
            @RequestParam(required = false) String cursor) {
        return read("workspace.code-tree", "codebase.read", () -> codeTree.children(project, parent, cursor));
    }

    @GetMapping("/code-tree/edges")
    public Object codeTreeEdges(@RequestParam String project, @RequestParam String symbol) {
        return read("workspace.code-tree.edges", "codebase.read", () -> codeTree.edges(project, symbol));
    }

    @GetMapping("/code-tree/links")
    public Object codeTreeLinks(@RequestParam String project, @RequestParam(defaultValue = "") String parent) {
        return read("workspace.code-tree.links", "codebase.read", () -> codeTree.links(project, parent));
    }

    @GetMapping("/code-tree/search")
    public Object codeTreeSearch(@RequestParam String project, @RequestParam String q,
            @RequestParam(defaultValue = "20") int limit) {
        return read("workspace.code-tree.search", "codebase.read", () -> codeTree.search(project, q, limit));
    }

    @GetMapping("/code-tree/attached")
    public Object codeTreeAttached(@RequestParam String project, @RequestParam String node) {
        return read("workspace.code-tree.attached", "memory.read", () -> codeTree.attached(project, node));
    }

    /**
     * "Proje ekle": the project key a folder gets is the one an agent's session.bootstrap resolves for it, so rules
     * and memories attached in the panel reach the agents; an already bound folder keeps its key.
     */
    @GetMapping("/projects/resolve")
    public Object resolveProject(@RequestParam String rootPath) {
        if (rootPath.isBlank() || rootPath.length() > 4096 || !rootPath.startsWith("/"))
            throw new IllegalArgumentException(PanelText.t("Enter the full (absolute) path of the folder",
                    "Klasörün tam (mutlak) yolunu gir"));
        var resolved = scanner.resolveProject(rootPath.trim(), null);
        return Map.of("projectKey", resolved.projectKey(), "rootPath", resolved.rootPath());
    }

    @GetMapping("/jobs")
    public Object jobs() {
        return read("workspace.jobs", "memory.read", () -> jobsPanel.overview());
    }

    @GetMapping("/jobs/{kind}/{id}")
    public Object job(@PathVariable String kind, @PathVariable String id) {
        return read("workspace.jobs.get", "memory.read", () -> jobsPanel.get(kind, id));
    }

    @PostMapping("/jobs/{kind}/{id}/delete")
    public Object deleteJob(@PathVariable String kind, @PathVariable String id) {
        require("memory.write");
        return jobsPanel.delete(kind, id);
    }

    @GetMapping("/hierarchy")
    public Object hierarchy(
            @RequestParam String kind,
            @RequestParam(defaultValue = "") String project,
            @RequestParam(defaultValue = "") String group,
            @RequestParam(defaultValue = "") String query,
            @RequestParam(defaultValue = "") String status,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int limit) {
        if (query.length() > 2048
                || project.length() > 200
                || group.length() > 64
                || status.length() > 80) throw new IllegalArgumentException(PanelText.t("Filter is too long",
                        "Filtre çok uzun"));
        return read(
                "workspace.hierarchy",
                scope(kind),
                () -> reads.hierarchy(kind, project, group, query, status, cursor, limit));
    }

    @GetMapping("/items/{kind}/{id}")
    public Object item(@PathVariable String kind, @PathVariable String id) {
        return read("workspace.get", scope(kind), () -> reads.get(kind, id));
    }

    @GetMapping("/items/{kind}/{id}/neighbors")
    public Object neighbors(
            @PathVariable String kind,
            @PathVariable String id,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "false") boolean similarity,
            @RequestParam(defaultValue = "50") int limit) {
        require("memory.read");
        require("rules.read");
        require("codebase.read");
        require("context.graph.read");
        return read(
                "workspace.neighbors",
                scope(kind),
                () -> reads.neighbors(kind, id, cursor, similarity, limit));
    }

    @PostMapping("/memory/{id}")
    public Object edit(@PathVariable String id, @Valid @RequestBody MemoryEdit edit) {
        // The registered authentication filter set this context; no fabricated privileged context.
        return memory.update(
                id,
                edit.summary(),
                edit.text(),
                edit.tags(),
                null,
                null,
                null,
                null,
                null,
                edit.reason());
    }

    @PostMapping("/memory/lifecycle/preview")
    public Object lifecyclePreview(@RequestBody MemoryLifecycleService.Selection selection) {
        require("codebase.read");
        return read("workspace.memory.lifecycle.preview", "memory.read", () -> lifecycle.preview(selection));
    }

    @PostMapping("/memory/lifecycle/decide")
    public Object lifecycleDecide(@RequestBody MemoryLifecycleService.Approval approval) {
        require("memory.read");
        require("codebase.read");
        return read("workspace.memory.lifecycle.decide", "memory.write", () -> lifecycle.decide(approval,
                "workspace:" + McpClientContextHolder.require().clientId()));
    }

    /**
     * AI references katalog listesi — ReferenceService sözleşmesi aynen korunur.
     * Yalnız doğrudan alt öğeler listelenir; recursive tarama yoktur, path normalizasyonu
     * ve symlink reddi servisin kendisindedir. Bu uç yeni bir dosya sistemi yüzü AÇMAZ.
     */
    @GetMapping("/references/list")
    public Object referenceList(
            @RequestParam(defaultValue = "") String dir,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        return read("workspace.reference.list", "memory.read", () -> references.list(dir, cursor, limit));
    }

    /**
     * AI reference dosya okuma. Redaksiyon → sayfalama sırası, nextOffset'in redakte
     * bayt uzayında olması, stale/missing durum ve son doğrulanmış hash davranışı
     * ReferenceService'te kalır; burada yeniden uygulanmaz.
     */
    @GetMapping("/references/read")
    public Object referenceRead(
            @RequestParam String path,
            @RequestParam(required = false) Integer offsetBytes,
            @RequestParam(required = false) Integer maxBytes) {
        return read("workspace.reference.read", "memory.read", () -> references.read(path, offsetBytes, maxBytes));
    }

    /**
     * Kural KAPSAMI (uygunluk değil). Read-only; mevcut guard + scope + audit aynen geçerli.
     * Frontend'in prose/regex ile kapsam icat etmemesi için gerçek kayıtlı bağlamaları döner.
     */
    @GetMapping("/rules/{id}/scope")
    public Object ruleScope(
            @PathVariable UUID id,
            @RequestParam String project,
            @RequestParam(defaultValue = "50") int limit) {
        require("codebase.read");
        return read("workspace.rule.scope", "rules.read", () -> reads.ruleScope(id.toString(), project, limit));
    }

    @PostMapping("/rules/{id}/preview")
    public Object preview(@PathVariable UUID id, @Valid @RequestBody RuleEdit edit) {
        require("rules.read");
        require("memory.write");
        return read(
                "workspace.rule.preview",
                "rules.write",
                () ->
                        revisions.preview(
                                id,
                                edit.statement(),
                                edit.rationale(),
                                "workspace:" + McpClientContextHolder.require().clientId()));
    }

    @PostMapping("/rules/{id}/activate")
    public Object activate(
            @PathVariable UUID id,
            @Valid @RequestBody WorkspaceRuleRevisionService.Approval approval) {
        require("rules.write");
        if (revisions.definition(id).projectKey() == null) require("rules.global.confirm");
        return read(
                "workspace.rule.activate",
                "rules.confirm",
                () ->
                        revisions.activate(
                                id,
                                approval,
                                "workspace:" + McpClientContextHolder.require().clientId()));
    }

    /** Cheap liveness for the panel's health pill; the guard above already proved local trust. */
    @GetMapping("/health")
    public Object health() {
        McpClientContext c = McpClientContextHolder.require();
        return Map.of("status", "UP", "client", Objects.toString(c.clientId(), ""), "time", Instant.now().toString());
    }

    @GetMapping("/stats")
    public Object stats(@RequestParam(defaultValue = "") String project) {
        if (project.length() > 200) throw new IllegalArgumentException(PanelText.t("Project key is too long",
                "Proje anahtarı çok uzun"));
        require("rules.read");
        return read("workspace.stats", "memory.read", () -> reads.stats(project));
    }

    /** Human-authored memory from the panel; the gated memory.write path decides and its verdict is returned verbatim. */
    @PostMapping("/memory")
    public Object createMemory(@Valid @RequestBody MemoryCreate create) {
        String type = create.memoryType() == null || create.memoryType().isBlank() ? "decision" : create.memoryType();
        boolean global = "global".equalsIgnoreCase(create.scope())
                || create.project() == null || create.project().isBlank();
        List<MemoryCodeLocator> locators = global || create.codeLocators() == null || create.codeLocators().isEmpty()
                ? null : create.codeLocators();
        return memory.write(create.content(), create.summary(), type, create.tags(), "workspace-ui",
                global ? "global" : "project", null, global ? null : create.project(), locators);
    }

    /**
     * Archive is reversible; hard delete is permanent and requires the human's own typed confirmation.
     * The panel is the human's direct UI, so the typed text is the human-authored confirmation.
     */
    @PostMapping("/memory/{id}/delete")
    public Object deleteMemory(@PathVariable String id, @Valid @RequestBody MemoryDelete delete) {
        String mode = delete.mode().trim().toLowerCase(Locale.ROOT);
        if ("hard_delete".equals(mode)) {
            if (!Boolean.TRUE.equals(delete.humanConfirmed())
                    || delete.humanRawText() == null || delete.humanRawText().isBlank())
                throw new IllegalArgumentException(PanelText.t("Permanent deletion needs an explicit confirmation text",
                        "Kalıcı silme için açık onay metni gerekli"));
            return memory.delete(id, mode, delete.reason(), true, humanTurnRef(), delete.humanRawText());
        }
        if (!"archive".equals(mode)) throw new IllegalArgumentException(PanelText.t("Delete mode must be archive or hard_delete",
                "Silme türü archive veya hard_delete olmalı"));
        return memory.delete(id, mode, delete.reason(), null, null, null);
    }

    @GetMapping("/memory/{id}/events")
    public Object memoryEvents(@PathVariable UUID id, @RequestParam(defaultValue = "30") int limit) {
        return read("workspace.memory.events", "memory.read", () -> reads.memoryEvents(id.toString(), limit));
    }

    @GetMapping("/memory/pending")
    public Object pending(
            @RequestParam(defaultValue = "") String project,
            @RequestParam(defaultValue = "25") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        return review.pending(limit, offset, project.isBlank() ? null : project);
    }

    @PostMapping("/memory/{id}/confirm")
    public Object confirm(@PathVariable String id, @Valid @RequestBody MemoryDecision decision) {
        String choice = decision.decision().trim().toLowerCase(Locale.ROOT);
        if (!Set.of("approve", "reject").contains(choice))
            throw new IllegalArgumentException(PanelText.t("Decision must be approve or reject",
                    "Karar approve veya reject olmalı"));
        String raw = "Panel: " + (choice.equals("approve") ? "Onayla" : "Reddet")
                + (decision.note() == null || decision.note().isBlank() ? "" : " — " + decision.note());
        return review.confirm(id, choice, choice.equals("approve"), raw, 1.0, humanTurnRef(), null,
                decision.note(), decision.project() == null || decision.project().isBlank() ? null : decision.project());
    }

    @GetMapping("/rules/effective")
    public Object effectiveRules(@RequestParam String project) {
        return rules.instructions(project, "effective", null);
    }

    @PostMapping("/rules/draft")
    public Object draftRule(@Valid @RequestBody RuleDraft draft) {
        String scope = draft.scope().trim().toLowerCase(Locale.ROOT);
        Map<String, Object> candidate = new LinkedHashMap<>();
        candidate.put("statement", draft.statement());
        if (draft.rationale() != null && !draft.rationale().isBlank()) candidate.put("rationale", draft.rationale());
        candidate.put("enforcement", "instruction");
        String project = draft.project() == null || draft.project().isBlank() ? null : draft.project();
        switch (scope) {
            case "global" -> {
                candidate.put("appliesAll", true);
                project = null;
            }
            case "project" -> {
                if (project == null) throw new IllegalArgumentException(PanelText.t("Select a project for a project rule",
                        "Proje kuralı için proje seçin"));
                candidate.put("appliesAll", true);
            }
            case "module" -> {
                if (project == null) throw new IllegalArgumentException(PanelText.t("Select a project for a module rule",
                        "Modül kuralı için proje seçin"));
                List<String> globs = draft.moduleGlobs() == null ? List.of() : draft.moduleGlobs().stream()
                        .map(String::trim).filter(g -> !g.isEmpty()).toList();
                if (globs.isEmpty()) throw new IllegalArgumentException(PanelText.t("Enter at least one directory pattern for a module rule",
                        "Modül kuralı için en az bir dizin deseni girin"));
                candidate.put("appliesAll", false);
                candidate.put("targets", globs.stream()
                        .map(g -> Map.of("kind", "path_glob", "targetKey", g)).toList());
            }
            case "node" -> {
                if (project == null) throw new IllegalArgumentException(PanelText.t("Select a project for a node rule",
                        "Düğüm kuralı için proje seçin"));
                NodeTarget node = draft.nodeTarget();
                if (node == null) throw new IllegalArgumentException(PanelText.t("A node rule needs a target node",
                        "Düğüm kuralı için hedef düğüm gerekli"));
                String kind = node.kind().trim().toLowerCase(Locale.ROOT);
                String path = node.path().trim();
                List<Map<String, String>> targets = new java.util.ArrayList<>();
                switch (kind) {
                    case "package" -> targets.add(Map.of("kind", "path_glob", "targetKey",
                            (path.endsWith("/") ? path.substring(0, path.length() - 1) : path) + "/**"));
                    case "class", "file" -> targets.add(Map.of("kind", "file", "targetKey", path));
                    case "member" -> {
                        // Delivered with its file (agents receive rules per edited file); the symbol names the method.
                        targets.add(Map.of("kind", "file", "targetKey", path));
                        String symbol = symbolKey(node.fqn(), node.signature());
                        if (symbol != null) targets.add(Map.of("kind", "symbol", "targetKey", symbol));
                    }
                    default -> throw new IllegalArgumentException(PanelText.t("Node kind must be package, class, file or member",
                            "Düğüm türü package, class, file veya member olmalı"));
                }
                candidate.put("appliesAll", false);
                candidate.put("targets", targets);
            }
            default -> throw new IllegalArgumentException(PanelText.t("Scope must be global, project, module or node",
                    "Kapsam global, project, module veya node olmalı"));
        }
        try {
            return rules.draft(project, json.writeValueAsString(candidate));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException(PanelText.t("Could not create the rule draft",
                    "Kural taslağı oluşturulamadı"));
        }
    }

    /** `pkg.Owner#getPet` + `getPet(String)` → `pkg.Owner#getPet(String)`: overloads stay distinct. */
    static String symbolKey(String fqn, String signature) {
        if (fqn == null || fqn.isBlank()) return null;
        String f = fqn.trim();
        if (signature == null || signature.isBlank()) return f;
        int hash = f.indexOf('#');
        return (hash < 0 ? f + "#" : f.substring(0, hash + 1)) + signature.trim();
    }

    @PostMapping("/rules/draft/preview")
    public Object previewDraft(@Valid @RequestBody RuleDraftRef ref) {
        return rules.preview(ref.draftId(), ref.project() == null || ref.project().isBlank() ? null : ref.project());
    }

    /** Promotion echoes the server hashes verbatim; the typed approval text is the human's own words. */
    @PostMapping("/rules/draft/promote")
    public Object promoteDraft(@Valid @RequestBody RulePromotion promotion) {
        return rules.promote(promotion.draftId(),
                promotion.project() == null || promotion.project().isBlank() ? null : promotion.project(),
                promotion.candidateHash(), promotion.approvalContentHash(), promotion.confirmationCardHash(),
                promotion.workflowContractVersion(), promotion.humanRawText(), humanTurnRef(), true, 1.0);
    }

    @PostMapping("/references/mkdir")
    public Object referenceMkdir(@Valid @RequestBody ReferencePath path) {
        return referenceTool.mkdir(path.relativePath());
    }

    @PostMapping("/references/write")
    public Object referenceWrite(@Valid @RequestBody ReferenceWrite write) {
        return referenceTool.write(write.relativePath(), write.content() == null ? "" : write.content(),
                write.expectedHash() == null || write.expectedHash().isBlank() ? null : write.expectedHash());
    }

    private static String humanTurnRef() {
        return "workspace-ui:" + Instant.now().toEpochMilli() + ":" + UUID.randomUUID().toString().substring(0, 8);
    }

    private Object read(String operation, String scope, Supplier<?> action) {
        Instant start = Instant.now();
        require(scope);
        try {
            Object result = action.get();
            audit.log(McpClientContextHolder.require(), operation, null, 1, start, "success");
            return result;
        } catch (RuntimeException e) {
            audit.log(McpClientContextHolder.require(), operation, null, 0, start, "error");
            throw e;
        }
    }

    private static String scope(String kind) {
        return switch (kind) {
            case "rule", "rule-version" -> "rules.read";
            case "scan", "file", "symbol", "capsule" -> "codebase.read";
            case "knowledge", "ingestion" -> "knowledge.read";
            default -> "memory.read";
        };
    }

    private void require(String scope) {
        if (!McpClientContextHolder.require().hasScope(scope))
            deny(PanelText.t("Missing scope: ", "Eksik yetki: ") + scope, "denied_scope");
    }

    private void deny(String reason) {
        deny(reason, "denied_auth");
    }

    private void deny(String reason, String decision) {
        audit.log(
                McpClientContextHolder.get(), "workspace.access", null, 0, Instant.now(), decision);
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, reason);
    }

    @ExceptionHandler(NoSuchElementException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Object missing(NoSuchElementException e) {
        return Map.of("error", PanelText.t("Record not found", "Kayıt bulunamadı"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Object invalid(IllegalArgumentException e) {
        return Map.of("error", Objects.toString(e.getMessage(), PanelText.t("Invalid request", "Geçersiz istek")));
    }

    @ExceptionHandler(McpAccessException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public Object forbidden(McpAccessException e) {
        return Map.of("error", PanelText.t("You do not have permission for this action",
                "Bu işlem için erişim izni yok"));
    }

    @ExceptionHandler(com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryPolicyViolationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Object policy(
            com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryPolicyViolationException e) {
        return Map.of("error", PanelText.t("The record policy did not accept this change: ",
                "Kayıt politikası bu değişikliği kabul etmedi: ") + e.reason());
    }

    @ExceptionHandler(org.springframework.web.bind.MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Object validation() {
        return Map.of("error", PanelText.t("Check the required fields and text lengths.",
                "Gerekli alanları ve metin uzunluklarını kontrol edin."));
    }

    @ExceptionHandler(IllegalStateException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Object conflict(IllegalStateException e) {
        return Map.of(
                "error",
                PanelText.t("The action could not be completed. The record may have changed or the data source may be"
                                + " unavailable; reload and try again.",
                        "İşlem tamamlanamadı. Kayıt değişmiş veya veri kaynağı erişilemiyor olabilir;"
                                + " yeniden yükleyin."));
    }
}
