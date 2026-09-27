package com.mbworldwideapps.aiorchestration.modules.mcp.server;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.mbworldwideapps.aiorchestration.modules.agentlearning.AgentLearningModels.CaptureCandidate;
import com.mbworldwideapps.aiorchestration.modules.agentlearning.AgentLearningModels.CaptureLocator;
import com.mbworldwideapps.aiorchestration.modules.agentlearning.AgentLearningModels.CompactLearnReceipt;
import com.mbworldwideapps.aiorchestration.modules.agentlearning.AgentLearningModels.LearningCaptureCommand;
import com.mbworldwideapps.aiorchestration.modules.rules.InstructionReadService;
import com.mbworldwideapps.aiorchestration.modules.agentlearning.AgentLearningRepository;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryCodeLocator;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryCodeLocatorKind;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryRepository;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryRetrievalService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;

/**
 * Lean agent surface for Claude Code and Codex: one startup call and one optional end-of-task learn call.
 *
 * <p>Both tools compose existing, individually scope-checked and audited tools, so no new scope or
 * persistence path is introduced. {@code memory.learn} routes code-anchored candidates to the shared
 * agent-learning capture (the same backend path as the Copilot terminal) and locator-free decisions,
 * conventions and procedures to the gated {@code memory.write} path, whose duplicate check applies.</p>
 */
@Component
@ConditionalOnProperty(prefix = "ai-orchestration.mcp", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AgentSessionMcpTool {

    static final Set<String> LOCATOR_FREE_KINDS = Set.of("decision", "convention", "procedure");
    static final Set<String> LOCATOR_ROLES = Set.of(
            "primary_change_point", "supporting", "validation", "configuration", "entry_point");
    static final int MAX_CANDIDATES = 3;

    private final ScannerMcpTool scanner;
    private final InstructionReadService rules;
    private final MemoryMcpTool memory;
    private final ObjectProvider<AgentLearningMcpTool> learning;
    private final McpAuditLogger audit;
    private MemoryRetrievalService retrieval;
    private MemoryRepository memories;
    private AgentLearningRepository learned;
    private AgentPrefsStore prefs;
    private double cardMinScore = 0.0;

    public AgentSessionMcpTool(ScannerMcpTool scanner, InstructionReadService rules, MemoryMcpTool memory,
            ObjectProvider<AgentLearningMcpTool> learning, McpAuditLogger audit) {
        this.scanner = scanner;
        this.rules = rules;
        this.memory = memory;
        this.learning = learning;
        this.audit = audit;
    }

    /** Task-aware memory cards and the remembered rule preference (optional so narrow tests can omit them). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setSessionSupport(MemoryRetrievalService retrieval, MemoryRepository memories,
            ObjectProvider<AgentLearningRepository> learned, AgentPrefsStore prefs,
            @org.springframework.beans.factory.annotation.Value("${ai-orchestration.agent.card-min-score:0.0}")
            double cardMinScore) {
        this.retrieval = retrieval;
        this.memories = memories;
        this.learned = learned.getIfAvailable();
        this.prefs = prefs;
        this.cardMinScore = cardMinScore;
    }

    /** One approved rule, as the agent needs it (approval evidence stays in the panel). */
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY)
    public record Rule(String scope, String statement, String rationale, List<String> modulePaths) {
    }

    /** A remembered piece of project knowledge relevant to the task: what it says and where to look. */
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY)
    public record Card(String id, String summary, String text, List<String> files, Boolean stale) {
    }

    /**
     * {@code rulesLoaded} is true only on the final page ({@code nextCursor == null}) of a complete read.
     * {@code askUser} is set when the user has not decided yet whether rules load in every session.
     */
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY)
    public record BootstrapResponse(String projectKey, boolean rulesLoaded, String rulesEtag, Boolean rulesUnchanged,
            String askUser, String rulesError, List<Rule> rules, String nextCursor, List<Card> memory,
            List<PathRule> pathRules, List<String> pathIndex) {
        public BootstrapResponse {
            rules = rules == null ? List.of() : List.copyOf(rules);
            memory = memory == null ? List.of() : List.copyOf(memory);
            pathRules = pathRules == null ? List.of() : List.copyOf(pathRules);
            pathIndex = pathIndex == null ? List.of() : List.copyOf(pathIndex);
        }

        public BootstrapResponse(String projectKey, boolean rulesLoaded, String rulesEtag, Boolean rulesUnchanged,
                String askUser, String rulesError, List<Rule> rules, String nextCursor, List<Card> memory) {
            this(projectKey, rulesLoaded, rulesEtag, rulesUnchanged, askUser, rulesError, rules, nextCursor, memory,
                    null, null);
        }
    }

    /** A rule bound to directories ("dir" = everything below) or single files: follow it when editing there. */
    public record PathRule(List<String> paths, String statement) {
    }

    public record LearnedItem(String summary, String route, String status, String memoryId, String reason) {
    }

    /**
     * {@code reused} counts only matches the shared capture service verified against an existing record;
     * {@code duplicates} counts proposals the write gate refused as already covered (not saved, and the
     * gate does not name the existing record, so no ID is claimed).
     */
    public record LearnResponse(String status, int created, int reused, int duplicates, int rejected,
            List<LearnedItem> items, CompactLearnReceipt capture) {
        public LearnResponse {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }

    @McpTool(name = "session.bootstrap", metaProvider = AlwaysLoadToolMeta.class, description = "Call once at session start with the user's task. Returns projectKey, remembered project knowledge relevant to the task (memory cards: what to do, where, how to verify) and the approved rules. If askUser is set, ask the user that question once and call again with loadRules (and remember=true for 'always'). Call again only after context compaction (pass knownEtag only if you still have the rules) or in another git root. If nextCursor is set, call again with it.")
    public BootstrapResponse bootstrap(
            @McpToolParam(description = "Absolute path inside the repository") String rootPath,
            @McpToolParam(description = "The user's task in one sentence; returns matching memory cards", required = false) String task,
            @McpToolParam(description = "Load rules this session (answer to askUser)", required = false) Boolean loadRules,
            @McpToolParam(description = "true when the user answered 'always'/'never'", required = false) Boolean remember,
            @McpToolParam(description = "rulesEtag you already hold; unchanged rules are not resent", required = false) String knownEtag,
            @McpToolParam(description = "nextCursor from the previous page", required = false) String cursor) {
        McpClientContext context = McpClientContextHolder.require();
        Instant started = Instant.now();
        String projectKey = null;
        try {
            if (rootPath == null || rootPath.isBlank() || !rootPath.startsWith("/")) {
                throw new IllegalArgumentException("rootPath must be an absolute repository root");
            }
            ScannerProjectResolveResponse project = scanner.resolveProject(gitRoot(context, rootPath), null);
            projectKey = project.projectKey();
            List<Card> cards = cursor == null || cursor.isBlank() ? cards(task, projectKey) : List.of();
            if (Boolean.TRUE.equals(remember) && loadRules != null && prefs != null) {
                prefs.rememberRulesAutoload(loadRules);
            }
            Boolean load = loadRules;
            if (load == null) {
                String saved = prefs == null ? "always" : prefs.rulesAutoload();
                load = "always".equals(saved) ? Boolean.TRUE : "never".equals(saved) ? Boolean.FALSE : null;
            }
            BootstrapResponse response;
            if (load == null) {
                response = new BootstrapResponse(projectKey, false, null, null,
                        "Load the AI Orchestration rules for this session? (yes / no / always / never)",
                        null, List.of(), null, cards);
            } else if (!load) {
                response = new BootstrapResponse(projectKey, false, null, null, null, "RULES_SKIPPED_BY_USER",
                        List.of(), null, cards);
            } else {
                response = rulesResponse(context, projectKey, cursor, knownEtag, cards);
            }
            audit.log(context, "session.bootstrap", McpAuditLogger.hashQuery(rootPath), response.rules().size(),
                    latencyMs(started), "success",
                    metadata(projectKey, "rulesLoaded", response.rulesLoaded(), "cards", cards.size()), null);
            return response;
        } catch (McpAccessException denied) {
            audit.log(context, "session.bootstrap", McpAuditLogger.hashQuery(rootPath), 0,
                    latencyMs(started), "denied_scope", metadata(projectKey),
                    denied.getClass().getSimpleName());
            throw denied;
        } catch (RuntimeException failure) {
            audit.log(context, "session.bootstrap", McpAuditLogger.hashQuery(rootPath), 0,
                    latencyMs(started), "error", metadata(projectKey),
                    failure.getClass().getSimpleName());
            throw failure;
        }
    }

    /** Backwards-compatible two-argument form (older instruction blocks and tests). */
    public BootstrapResponse bootstrap(String rootPath, String cursor) {
        return bootstrap(rootPath, null, null, null, null, cursor);
    }

    private BootstrapResponse rulesResponse(McpClientContext context, String projectKey, String cursor,
            String knownEtag, List<Card> cards) {
        if (!context.hasScope("rules.read")) {
            return new BootstrapResponse(projectKey, false, null, null, null, "RULES_SCOPE_DENIED", List.of(),
                    null, cards);
        }
        InstructionReadService.Page page;
        try {
            page = rules.readPage(McpProjectKeys.effective(context, projectKey), cursor);
        } catch (InstructionReadService.CapacityExceeded | InstructionReadService.RulesChanged e) {
            return new BootstrapResponse(projectKey, false, null, null, null, e.getMessage(), List.of(), null,
                    cards);
        }
        InstructionReadService.Result loaded = page.result();
        String etag = page.complete() ? etag(projectKey, loaded) : null;
        if (etag != null && etag.equals(knownEtag) && (cursor == null || cursor.isBlank())) {
            return new BootstrapResponse(projectKey, true, etag, true, null, null, List.of(), null, cards);
        }
        List<Rule> view = loaded.instructions().stream()
                .filter(i -> !"MODULE".equals(i.scope()))
                .map(i -> new Rule(i.scope(), i.statement(), i.rationale(), i.modulePaths()))
                .toList();
        if (!page.complete()) {
            return new BootstrapResponse(projectKey, false, null, null, null, null, view, page.nextCursor(), cards);
        }
        // Path-bound rules of the whole project (not just this page): small sets travel inline (no extra call),
        // large ones only as the list of bound paths, fetched with rules.instructions(scope="module", ...).
        String effectiveKey = McpProjectKeys.effective(context, projectKey);
        List<String> paths = rules.boundPaths(effectiveKey);
        if (paths.isEmpty()) {
            return new BootstrapResponse(projectKey, true, etag, null, null, null, view, null, cards);
        }
        List<PathRule> inline = paths.size() <= 32 ? pathRules(projectKey, context, paths) : null;
        int size = inline == null ? Integer.MAX_VALUE
                : inline.stream().mapToInt(r -> r.statement().length() + String.join(",", r.paths()).length()).sum();
        return size <= MAX_INLINE_PATH_RULES
                ? new BootstrapResponse(projectKey, true, etag, null, null, null, view, null, cards, inline, null)
                : new BootstrapResponse(projectKey, true, etag, null, null, null, view, null, cards, null, paths);
    }

    private List<PathRule> pathRules(String projectKey, McpClientContext context, List<String> paths) {
        try {
            return rules.read(McpProjectKeys.effective(context, projectKey), "module", paths).instructions().stream()
                    .filter(i -> i.statement() != null)
                    .map(i -> new PathRule(i.modulePaths(), i.statement()))
                    .toList();
        } catch (RuntimeException e) {
            return null; // capacity or a concurrent change: fall back to the path index
        }
    }

    private static String etag(String projectKey, InstructionReadService.Result loaded) {
        return McpAuditLogger.sha256Hex(projectKey + "|" + loaded.globalEffectiveSeq() + "|"
                + loaded.projectEffectiveSeq() + "|" + loaded.instructions().size()).substring(0, 16);
    }

    /**
     * How to build and test a repository rarely resembles the task's wording, so it is looked up separately: the
     * working verify command and the environment's pitfalls are what save an agent the most turns.
     */
    static final String VERIFY_QUERY =
            "how to build, run the tests and verify a change in this repository, and which tests fail here";

    /**
     * Up to three remembered facts that clear the relevance bar (the task's matches first, then how to verify a
     * change here); none when nothing is relevant.
     */
    private List<Card> cards(String task, String projectKey) {
        if (task == null || task.isBlank() || retrieval == null) {
            return List.of();
        }
        try {
            java.util.LinkedHashMap<java.util.UUID, com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryContextItem>
                    merged = new java.util.LinkedHashMap<>();
            for (var item : relevant(task, projectKey)) merged.putIfAbsent(item.memoryId(), item);
            if (merged.size() < 3) {
                try {
                    for (var item : relevant(VERIFY_QUERY, projectKey)) merged.putIfAbsent(item.memoryId(), item);
                } catch (RuntimeException e) {
                    // the task's own cards still go out
                }
            }
            var items = merged.values().stream().limit(3).toList();
            if (items.isEmpty()) return List.of();
            java.util.Map<java.util.UUID, List<String>> anchorFiles = new java.util.HashMap<>();
            if (learned != null) {
                for (var route : learned.findDiscoveryRoutes(projectKey,
                        items.stream().map(i -> i.memoryId()).toList(), 3)) {
                    anchorFiles.put(route.memoryId(), route.anchors().stream()
                            .map(a -> a.symbolRef() == null || a.symbolRef().isBlank() ? a.relativePath()
                                    : a.relativePath() + " (" + a.symbolRef() + ")")
                            .toList());
                }
            }
            List<Card> cards = new ArrayList<>();
            for (var item : items) {
                java.util.LinkedHashSet<String> files = new java.util.LinkedHashSet<>(
                        anchorFiles.getOrDefault(item.memoryId(), List.of()));
                if (memories != null) {
                    memories.findById(item.memoryId()).ifPresent(m -> {
                        try {
                            com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryCodeLocatorMetadata
                                    .read(m.metadata()).items()
                                    .forEach(l -> files.add(l.path() != null && !l.path().isBlank() ? l.path() : l.ref()));
                        } catch (IllegalArgumentException ignored) {
                            // malformed locator metadata: the card still carries the text
                        }
                    });
                }
                String text = item.text() == null ? "" : item.text().trim();
                if (text.length() > 700) text = text.substring(0, 700) + "…";
                cards.add(new Card(item.memoryId().toString(), item.summary(), text,
                        files.stream().limit(8).toList(), item.stale() ? Boolean.TRUE : null));
            }
            return cards;
        } catch (RuntimeException e) {
            return List.of();  // memory cards are a best-effort accelerator, never a startup failure
        }
    }

    private List<com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryContextItem> relevant(String query,
            String projectKey) {
        // The injection path gates on raw semantic similarity (min-injection-similarity): exactly the
        // "is this relevant enough to put in front of the agent" question. Nothing relevant => no cards.
        var ranked = retrieval.retrieveForInjection(query, projectKey, null).items().stream()
                .filter(item -> item.semanticScore() >= cardMinScore)
                .sorted(java.util.Comparator.comparingDouble(
                        (com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryContextItem i) ->
                                i.semanticScore()).reversed())
                .toList();
        if (ranked.isEmpty()) return List.of();
        // A missed card costs a whole exploration, a spare card a few hundred tokens: the absolute floor is
        // low, and only cards close to the best match are shown so a clear winner is not diluted.
        double best = ranked.get(0).semanticScore();
        return ranked.stream().filter(item -> item.semanticScore() >= best - CARD_BAND).limit(3).toList();
    }

    @McpTool(name = "memory.learn", metaProvider = AlwaysLoadToolMeta.class, description = "Once at task end, save knowledge a later task in this repo would otherwise rediscover (1-3 candidates). Skip it when nothing durable was learned. Duplicates are detected by the server.")
    public LearnResponse learn(
            @McpToolParam(description = "Absolute path inside the repository") String rootPath,
            @McpToolParam(description = "1-3 of {kind: behavior|navigation|procedure|field_mapping|debug_finding|change_point|test_address, summary (<=160, when it applies), content (<=700: the reusable fact; for a procedure the verifying command first), locators: [{kind: symbol|file|directory, ref: pkg.Class#method | pkg.Class | repo path, signature?, path? (source file of a symbol), role?: primary_change_point|supporting|validation|configuration|entry_point}], appliesWhen?, limitations?, reusableFor?, reference?: {details: long-form evidence}}. Knowledge, not a task log.") List<CaptureCandidate> candidates,
            @McpToolParam(description = "Optional stable task id; defaults to one id per MCP session", required = false) String taskRunId) {
        McpClientContext context = McpClientContextHolder.require();
        Instant started = Instant.now();
        String projectKey = null;
        int count = candidates == null ? 0 : candidates.size();
        try {
            if (count < 1 || count > MAX_CANDIDATES) {
                throw new IllegalArgumentException("candidates must contain 1 to " + MAX_CANDIDATES + " items");
            }
            if (!context.hasScope("memory.write")) {
                throw new McpAccessException("Missing MCP scope: memory.write");
            }
            ScannerProjectResolveResponse project = scanner.resolveProject(gitRoot(context, rootPath), null);
            projectKey = project.projectKey();
            List<CaptureCandidate> anchored = new ArrayList<>();
            List<LearnedItem> items = new ArrayList<>();
            for (CaptureCandidate candidate : candidates) {
                if (candidate == null) {
                    throw new IllegalArgumentException("candidate must not be null");
                }
                if (candidate.locators().isEmpty()) {
                    items.add(writeLocatorFree(new CaptureCandidate(kindOf(candidate.kind()),
                            clip(candidate.summary(), MAX_SUMMARY), clip(candidate.content(), MAX_CONTENT),
                            candidate.locators(), candidate.appliesWhen(), candidate.limitations(),
                            candidate.reusableFor(), candidate.correctionRef(), candidate.reference()), projectKey));
                } else {
                    anchored.add(lenient(candidate));
                }
            }
            CompactLearnReceipt capture = anchored.isEmpty() ? null
                    : captureAnchored(context, project, anchored, taskRunId, items);
            if (capture != null && capture.rejected() > 0
                    && capture.created() + capture.updated() + capture.reused() + capture.conflicts() == 0) {
                // Nothing resolved against a code scan (e.g. the project was never scanned): keep the cards with
                // their file locators as declared links instead of losing them; the next scan resolves them.
                capture = null;
                for (CaptureCandidate candidate : anchored) {
                    items.add(writeLocatorFree(candidate, projectKey, declaredFileLocators(candidate)));
                }
            }
            int created = (int) items.stream().filter(i -> "created".equals(i.status())).count()
                    + (capture == null ? 0 : capture.created() + capture.updated());
            int reused = capture == null ? 0 : capture.reused();
            int duplicates = (int) items.stream().filter(i -> "duplicate_not_saved".equals(i.status())).count();
            int rejected = (int) items.stream().filter(i -> "rejected".equals(i.status())).count()
                    + (capture == null ? 0 : capture.rejected() + capture.conflicts());
            String status = rejected == 0 ? "ok" : (created + reused + duplicates > 0 ? "partial" : "rejected");
            LearnResponse response = new LearnResponse(status, created, reused, duplicates, rejected, items, capture);
            audit.log(context, "memory.learn", McpAuditLogger.hashQuery("learn:" + count), count,
                    latencyMs(started), "success", metadata(projectKey, "status", status), null);
            return response;
        } catch (McpAccessException denied) {
            audit.log(context, "memory.learn", McpAuditLogger.hashQuery("learn:" + count), 0,
                    latencyMs(started), "denied_scope", metadata(projectKey),
                    denied.getClass().getSimpleName());
            throw denied;
        } catch (RuntimeException failure) {
            audit.log(context, "memory.learn", McpAuditLogger.hashQuery("learn:" + count), 0,
                    latencyMs(started), "error", metadata(projectKey),
                    failure.getClass().getSimpleName());
            throw failure;
        }
    }

    /**
     * Agents often send a free-form role or put a file path in {@code path}; rejecting those costs a
     * whole retry round trip (seen in the lean-agent benchmark). Normalise the harmless variants here and
     * leave every real validation to the shared capture contract.
     */
    static CaptureCandidate lenient(CaptureCandidate candidate) {
        List<CaptureLocator> locators = candidate.locators().stream().map(locator -> {
            if (locator == null) {
                return null;
            }
            String kind = locator.kind() == null ? null : locator.kind().trim().toLowerCase(Locale.ROOT);
            String role = locator.role() != null && LOCATOR_ROLES.contains(locator.role().trim().toLowerCase(Locale.ROOT))
                    ? locator.role().trim().toLowerCase(Locale.ROOT) : null;
            if ("file".equals(kind) || "directory".equals(kind)) {
                boolean pathLooksReal = locator.path() != null && !locator.path().isBlank();
                boolean refLooksLikePath = locator.ref() != null && locator.ref().contains("/");
                String ref = pathLooksReal && !refLooksLikePath ? locator.path() : locator.ref();
                return new CaptureLocator(kind, ref, null, null, role);
            }
            return new CaptureLocator(kind, locator.ref(), locator.signature(), locator.path(), role);
        }).toList();
        return new CaptureCandidate(kindOf(candidate.kind()), clip(candidate.summary(), MAX_SUMMARY),
                clip(candidate.content(), MAX_CONTENT), locators,
                candidate.appliesWhen(), candidate.limitations(), candidate.reusableFor(), candidate.correctionRef(),
                candidate.reference());
    }

    static final double CARD_BAND = 0.10;
    static final int MAX_INLINE_PATH_RULES = 1_500;
    static final int MAX_SUMMARY = 160;
    static final int MAX_CONTENT = 700;

    /** Code-fact kinds of the shared capture contract plus the locator-free memory kinds. */
    static final Set<String> KINDS = Set.of("behavior", "navigation", "procedure", "field_mapping", "debug_finding",
            "change_point", "test_address", "decision", "convention");

    /**
     * An unknown or loosely written kind ("Procedure", "pitfall", "gotcha") is mapped instead of rejected: a rejected
     * learn call costs the agent a whole extra turn at full context (seen in the final benchmark).
     */
    static String kindOf(String kind) {
        String k = kind == null ? "" : kind.trim().toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        if (KINDS.contains(k)) return k;
        return k.contains("bug") || k.contains("pitfall") || k.contains("gotcha") || k.contains("debug") ? "debug_finding"
                : "procedure";
    }

    /** An over-long card is trimmed at a word boundary instead of rejected (a rejection costs a whole retry turn). */
    static String clip(String text, int max) {
        if (text == null || text.length() <= max) {
            return text;
        }
        int cut = text.lastIndexOf(' ', max - 1);
        return text.substring(0, cut > max / 2 ? cut : max - 1).stripTrailing() + "…";
    }

    private LearnedItem writeLocatorFree(CaptureCandidate candidate, String projectKey) {
        return writeLocatorFree(candidate, projectKey, null);
    }

    private static List<MemoryCodeLocator> declaredFileLocators(CaptureCandidate candidate) {
        List<MemoryCodeLocator> out = new ArrayList<>();
        for (CaptureLocator locator : candidate.locators()) {
            if (locator == null || locator.ref() == null || locator.ref().isBlank()) continue;
            String kind = locator.kind() == null ? "" : locator.kind().trim().toLowerCase(Locale.ROOT);
            MemoryCodeLocatorKind type = "file".equals(kind) ? MemoryCodeLocatorKind.FILE
                    : "directory".equals(kind) ? MemoryCodeLocatorKind.DIRECTORY : null;
            if (type != null) out.add(new MemoryCodeLocator(type, locator.ref().trim(), null, null, null, null));
        }
        return out.isEmpty() ? null : out;
    }

    private LearnedItem writeLocatorFree(CaptureCandidate candidate, String projectKey,
            List<MemoryCodeLocator> codeLocators) {
        String kind = candidate.kind() == null ? "" : candidate.kind().trim().toLowerCase(Locale.ROOT);
        if (!LOCATOR_FREE_KINDS.contains(kind) && codeLocators == null) {
            return new LearnedItem(candidate.summary(), "memory.write", "rejected", null,
                    "LOCATOR_REQUIRED: kind '" + kind + "' needs at least one symbol or file locator; "
                            + "only decision, convention and procedure may omit locators");
        }
        MemoryWriteResponse written = memory.write(candidate.content(), candidate.summary(), "decision",
                List.of("learned", kind), "memory.learn", "project", null, projectKey, codeLocators);
        boolean saved = written.memoryId() != null;
        boolean duplicate = "duplicate_memory".equals(written.gateReason());
        String status = saved ? "created" : duplicate ? "duplicate_not_saved" : "rejected";
        String reason = saved ? null : written.gateReason();
        return new LearnedItem(candidate.summary(), "memory.write", status,
                saved ? written.memoryId().toString() : null, reason);
    }

    private CompactLearnReceipt captureAnchored(McpClientContext context, ScannerProjectResolveResponse project,
            List<CaptureCandidate> anchored, String taskRunId, List<LearnedItem> items) {
        AgentLearningMcpTool learner = learning.getIfAvailable();
        if (learner == null || project.workspaceBindingId() == null) {
            String reason = learner == null ? "LEARNING_DISABLED" : "WORKSPACE_BINDING_UNAVAILABLE";
            anchored.forEach(c -> items.add(new LearnedItem(c.summary(), "capture", "rejected", null, reason)));
            return null;
        }
        String runId = taskRunId == null || taskRunId.isBlank() ? defaultRunId(context) : taskRunId.trim();
        return learner.capture(new LearningCaptureCommand(runId, project.projectKey(),
                project.workspaceBindingId().toString(), anchored));
    }

    /**
     * Local-trust callers may pass their working directory; the nearest ancestor holding {@code .git}
     * is used so agents need no separate `git rev-parse` call. Bearer callers keep the exact path.
     */
    static String gitRoot(McpClientContext context, String path) {
        if (path == null || !McpProjectKeys.isLocalTrust(context)) {
            return path;
        }
        java.nio.file.Path current = java.nio.file.Path.of(path).toAbsolutePath().normalize();
        for (int depth = 0; current != null && depth < 64; depth++, current = current.getParent()) {
            if (java.nio.file.Files.exists(current.resolve(".git"))) {
                return current.toString();
            }
        }
        return path;
    }

    static String defaultRunId(McpClientContext context) {
        String scope = context.sessionScopeHash();
        return "mcp-" + (scope == null ? Long.toString(System.nanoTime()) : scope.substring(0, 40));
    }

    private static long latencyMs(Instant started) {
        return java.time.Duration.between(started, Instant.now()).toMillis();
    }

    private static Map<String, Object> metadata(String projectKey, Object... pairs) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (projectKey != null) {
            metadata.put("projectKey", projectKey);
        }
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            metadata.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return metadata;
    }
}
