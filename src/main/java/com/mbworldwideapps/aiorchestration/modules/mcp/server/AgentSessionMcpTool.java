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
    static final Set<String> VERIFICATION_KINDS = Set.of("procedure", "test_address");

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
    // A task match between this floor and cardMinScore goes out as a pointer (id + summary, no text): a missed card
    // costs a whole exploration, a pointer a line. 1.0 = no pointers (profiles calibrate it).
    private com.mbworldwideapps.aiorchestration.modules.scanner.CodeBaselineRepository baseline;
    private double cardPointerMinScore = 1.0;

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

    /** The code map, to give a card's symbol locators their current place in the code (optional). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setCodeBaseline(com.mbworldwideapps.aiorchestration.modules.scanner.CodeBaselineRepository baseline) {
        this.baseline = baseline;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setCardPointerMinScore(
            @org.springframework.beans.factory.annotation.Value("${ai-orchestration.agent.card-pointer-min-score:1.0}")
            double cardPointerMinScore) {
        this.cardPointerMinScore = cardPointerMinScore;
    }

    /** One approved rule, as the agent needs it (approval evidence stays in the panel). */
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY)
    public record Rule(String scope, String statement, String rationale, List<String> modulePaths) {
    }

    /** A remembered piece of project knowledge relevant to the task: what it says and where to look. */
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY)
    public record Card(String id, String summary, String text, List<String> files, Boolean stale, Boolean pointer,
            String code) {

        public Card(String id, String summary, String text, List<String> files, Boolean stale, Boolean pointer) {
            this(id, summary, text, files, stale, pointer, null);
        }

        public Card(String id, String summary, String text, List<String> files, Boolean stale) {
            this(id, summary, text, files, stale, null, null);
        }
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
            var taskRanked = ranked(task, projectKey, Math.min(cardMinScore, cardPointerMinScore), false);
            for (var item : band(taskRanked)) merged.putIfAbsent(item.memoryId(), item);
            if (merged.size() < 3) {
                try {
                    for (var item : relevant(VERIFY_QUERY, projectKey, true)) {
                        merged.putIfAbsent(item.memoryId(), item);
                    }
                } catch (RuntimeException e) {
                    // the task's own cards still go out
                }
            }
            var items = merged.values().stream().limit(3).toList();
            var pointers = taskRanked.stream()
                    .filter(item -> item.semanticScore() >= cardPointerMinScore && item.semanticScore() < cardMinScore)
                    .filter(item -> !merged.containsKey(item.memoryId()))
                    .limit(MAX_POINTERS)
                    .map(item -> new Card(item.memoryId().toString(), item.summary(), null, List.of(),
                            item.stale() ? Boolean.TRUE : null, Boolean.TRUE))
                    .toList();
            if (items.isEmpty()) return pointers;
            List<Card> cards = full(items, projectKey);
            cards.addAll(pointers);
            return cards;
        } catch (RuntimeException e) {
            return List.of();  // memory cards are a best-effort accelerator, never a startup failure
        }
    }

    /** What one step lookup found: {@code found} (matching memories exist, possibly all shown before),
     *  {@code empty} (memory was searched and no memory of the step's kinds matches) or {@code skipped} (memory was
     *  not searched: injection off, no query or kinds); a failed lookup throws instead. */
    public record StepCards(String outcome, List<Card> cards) {
        static StepCards empty() {
            return new StepCards("empty", List.of());
        }

        static StepCards skipped() {
            return new StepCards("skipped", List.of());
        }
    }

    /**
     * Cards for one step of a task (the hooks ask before/after a build or test, on a new failure): only memories of
     * the given kinds, the same floors, band and pointer as the task's cards, none of {@code exclude} (already shown
     * in this session). Kinds and exclusions are applied before budget packing, so a stronger card of another kind or
     * one already shown cannot crowd them out. The outcome tells a real "nothing in memory" ({@code empty}) apart from
     * matches that were all shown before or did not fit the budget ({@code found}, possibly with no cards); it is
     * decided before packing. A retrieval failure is thrown to the caller.
     */
    public StepCards stepCards(String query, String projectKey, Set<String> kinds, Set<String> exclude) {
        if (query == null || query.isBlank() || retrieval == null || kinds == null || kinds.isEmpty()
                || !retrieval.injectionEnabled()) {
            return StepCards.skipped();  // not searched: says nothing about what memory holds
        }
        Set<String> skip = exclude == null ? Set.of() : exclude;
        double floor = Math.min(cardMinScore, cardPointerMinScore);
        // matched is decided before packing and exclusion; only cards not shown before compete for the budget
        var step = retrieval.retrieveForStep(query, projectKey,
                (com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryItem item) -> hasKind(item, kinds),
                item -> !skip.contains(item.id().toString()), floor);
        if (!step.matched()) {
            return StepCards.empty();
        }
        var ranked = step.response().items().stream()
                .filter(item -> item.semanticScore() >= floor)
                .sorted(java.util.Comparator.comparingDouble(
                        (com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryContextItem i) ->
                                i.semanticScore()).reversed())
                .toList();
        var items = band(ranked);
        var shown = items.stream().map(i -> i.memoryId()).collect(java.util.stream.Collectors.toSet());
        List<Card> cards = items.isEmpty() ? new ArrayList<>() : full(items, projectKey);
        ranked.stream()
                .filter(item -> item.semanticScore() >= cardPointerMinScore && item.semanticScore() < cardMinScore)
                .filter(item -> !shown.contains(item.memoryId()))
                .limit(MAX_POINTERS)
                .forEach(item -> cards.add(new Card(item.memoryId().toString(), item.summary(), null, List.of(),
                        item.stale() ? Boolean.TRUE : null, Boolean.TRUE)));
        return new StepCards("found", cards);
    }

    static final int MAX_LOCATION_LOOKUPS = 4;
    static final int MAX_CODE_CHARS = 2500;     // current code for all cards of one answer together
    static final int MAX_EXCERPT_LINES = 40;
    static final int MAX_OUTLINE_FILES = 3;
    static final int MAX_OUTLINE_ENTRIES = 12;

    /** A symbol locator found in the current code: its label and the symbol whose lines are current. */
    private record Located(String label,
            com.mbworldwideapps.aiorchestration.modules.scanner.CodeSymbolRecord symbol, List<String> lines) {
    }

    /**
     * Where a card's symbol locator is in the current code: {@code path:start-end ref (kind)} when the code map has it
     * and the file on disk is still the scanned one (same SHA-256); if the symbol itself is gone but its class is
     * there, the class, labelled as a class with the symbol named as missing. Null (the card keeps the declared path)
     * when nothing current can be said: no code map, a changed file, or neither symbol nor class found.
     */
    String currentLocation(String projectKey, String ref, String path) {
        Located located = locate(projectKey, ref, path);
        return located == null ? null : located.label();
    }

    private Located locate(String projectKey, String ref, String path) {
        if (baseline == null || ref == null || ref.isBlank()) {
            return null;
        }
        try {
            var symbol = symbolIn(projectKey, ref, path);
            if (symbol != null) {
                var lines = currentLines(projectKey, symbol.fileId());
                return lines == null ? null : new Located(path + ":" + symbol.startLine() + "-" + symbol.endLine()
                        + " " + ref + " (" + symbol.symbolKind() + ")", symbol, lines);
            }
            int member = ref.indexOf('#');
            if (member > 0) {
                String owner = ref.substring(0, member);
                var type = symbolIn(projectKey, owner, path);
                var lines = type != null && "class".equals(type.symbolKind())
                        ? currentLines(projectKey, type.fileId()) : null;
                if (lines != null) {
                    return new Located(path + ":" + type.startLine() + "-" + type.endLine() + " " + owner
                            + " (class; " + ref.substring(member + 1) + " is not in the current code)", type, lines);
                }
            }
        } catch (RuntimeException e) {
            return null;  // a location is an aid, never a reason to lose the card
        }
        return null;
    }

    private com.mbworldwideapps.aiorchestration.modules.scanner.CodeSymbolRecord symbolIn(String projectKey, String ref,
            String path) {
        for (var symbol : baseline.findSymbolsByRef(projectKey, ref, 5)) {
            if (!ref.equals(symbol.fqn()) || symbol.startLine() == null || symbol.endLine() == null) {
                continue;
            }
            var file = baseline.findFileById(projectKey, symbol.fileId());
            if (file.isPresent() && file.get().filePath().equals(path)) {
                return symbol;
            }
        }
        return partiallyQualified(projectKey, ref, path);
    }

    /**
     * A ref written with part of its module path ({@code models.Post#to_dict} for the scanner's
     * {@code app.models.Post#to_dict}): the one symbol of the declared file whose name ends with {@code "." + ref}.
     * Nothing when no symbol or more than one matches; a symbol is never guessed across files.
     */
    private com.mbworldwideapps.aiorchestration.modules.scanner.CodeSymbolRecord partiallyQualified(String projectKey,
            String ref, String path) {
        var file = baseline.findFileByPath(projectKey, path).orElse(null);
        if (file == null) {
            return null;
        }
        var matches = baseline.findSymbolsForFile(projectKey, file.id(), 500).stream()
                .filter(symbol -> symbol.fqn() != null && symbol.fqn().endsWith("." + ref)
                        && symbol.startLine() != null && symbol.endLine() != null)
                .limit(2)
                .toList();
        return matches.size() == 1 ? matches.get(0) : null;
    }

    /**
     * The lines of a scanned file when the file on disk is still the scanned one (same SHA-256): only then are the
     * code map's line numbers, and these lines, current. Null otherwise.
     */
    private List<String> currentLines(String projectKey, java.util.UUID fileId) {
        var file = baseline.findFileById(projectKey, fileId).orElse(null);
        return file == null ? null : currentLines(projectKey, file);
    }

    private List<String> currentLines(String projectKey,
            com.mbworldwideapps.aiorchestration.modules.scanner.CodeFileRecord file) {
        if (file.contentHash() == null) {
            return null;
        }
        var run = baseline.findRun(projectKey, file.scanRunId()).orElse(null);
        if (run == null || run.rootPath() == null) {
            return null;
        }
        try {
            byte[] bytes = java.nio.file.Files.readAllBytes(java.nio.file.Path.of(run.rootPath()).resolve(file.filePath()));
            if (!file.contentHash().equals(java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(bytes)))) {
                return null;
            }
            return new String(bytes, java.nio.charset.StandardCharsets.UTF_8).lines().toList();
        } catch (java.io.IOException | java.security.NoSuchAlgorithmException | RuntimeException e) {
            return null;
        }
    }

    /**
     * The current lines of a located symbol, numbered, within {@code budget} characters and
     * {@link #MAX_EXCERPT_LINES}; a cut excerpt says which lines it shows and where the symbol ends, so it is never
     * taken for the whole symbol. Null when not even the header fits.
     */
    static String excerpt(String label, int start, int end, List<String> lines, int budget) {
        StringBuilder out = new StringBuilder("Current code, ").append(label).append(":\n");
        int last = Math.min(end, lines.size());
        int shown = start - 1;
        for (int n = start; n <= last && n - start < MAX_EXCERPT_LINES; n++) {
            String line = n + "│ " + lines.get(n - 1) + "\n";
            if (out.length() + line.length() + 60 > budget) {
                break;
            }
            out.append(line);
            shown = n;
        }
        if (shown < start) {
            return null;
        }
        if (shown < end) {
            out.append("… cut: lines ").append(start).append("-").append(shown).append(" shown; the symbol ends at line ")
                    .append(end).append("\n");
        }
        return out.toString();
    }

    /** Members of a type; everything else (classes, top-level functions such as Python's {@code module#f}) is listed. */
    private static final java.util.Set<String> MEMBER_KINDS = java.util.Set.of("method", "property");

    /** The top-level classes and functions of a current file with their lines (no source), or null. */
    private String outline(String projectKey, String path, int budget) {
        if (baseline == null || budget < 80) {
            return null;
        }
        try {
            var file = baseline.findFileByPath(projectKey, path).orElse(null);
            if (file == null || currentLines(projectKey, file) == null) {
                return null;
            }
            var entries = baseline.findSymbolsForFile(projectKey, file.id(), 200).stream()
                    .filter(sym -> sym.startLine() != null && sym.endLine() != null
                            && !MEMBER_KINDS.contains(sym.symbolKind()))
                    .toList();
            if (entries.isEmpty()) {
                return null;
            }
            StringBuilder out = new StringBuilder("Current outline, ").append(path).append(":\n");
            int listed = 0;
            for (var sym : entries) {
                String line = "  " + sym.startLine() + "-" + sym.endLine() + " " + sym.symbolKind() + " "
                        + (sym.name() != null ? sym.name() : sym.fqn()) + "\n";
                if (listed == MAX_OUTLINE_ENTRIES || out.length() + line.length() + 40 > budget) {
                    break;
                }
                out.append(line);
                listed++;
            }
            if (listed == 0) {
                return null;
            }
            if (listed < entries.size()) {
                out.append("  … ").append(entries.size() - listed).append(" more not listed\n");
            }
            return out.toString();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Full cards (text and the files they point to) for selected memories. */
    private List<Card> full(List<com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryContextItem> items,
            String projectKey) {
        {
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
            int[] lookups = {0};
            int[] codeLeft = {MAX_CODE_CHARS};
            int[] outlines = {0};
            for (var item : items) {
                java.util.LinkedHashSet<String> files = new java.util.LinkedHashSet<>(
                        anchorFiles.getOrDefault(item.memoryId(), List.of()));
                List<String> code = new ArrayList<>();
                if (memories != null) {
                    memories.findById(item.memoryId()).ifPresent(m -> {
                        try {
                            for (var l : com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryCodeLocatorMetadata
                                    .read(m.metadata()).items()) {
                                String path = l.path() != null && !l.path().isBlank() ? l.path() : l.ref();
                                var kind = l.kind();
                                Located found = kind == com.mbworldwideapps.aiorchestration.modules.memoryai
                                        .MemoryCodeLocatorKind.SYMBOL && lookups[0]++ < MAX_LOCATION_LOOKUPS
                                        ? locate(projectKey, l.ref(), path) : null;
                                if (found != null) {
                                    files.remove(path + " (" + l.ref() + ")");  // the same target, now with its place
                                    String lines = excerpt(found.label(), found.symbol().startLine(),
                                            found.symbol().endLine(), found.lines(), codeLeft[0]);
                                    if (lines != null) {
                                        code.add(lines);
                                        codeLeft[0] -= lines.length();
                                    }
                                } else if (kind == com.mbworldwideapps.aiorchestration.modules.memoryai
                                        .MemoryCodeLocatorKind.FILE && outlines[0] < MAX_OUTLINE_FILES) {
                                    String listed = outline(projectKey, path, codeLeft[0]);
                                    if (listed != null) {
                                        outlines[0]++;
                                        code.add(listed);
                                        codeLeft[0] -= listed.length();
                                    }
                                }
                                files.add(found != null ? found.label() : path);
                            }
                        } catch (IllegalArgumentException ignored) {
                            // malformed locator metadata: the card still carries the text
                        }
                    });
                }
                String text = item.text() == null ? "" : item.text().trim();
                if (text.length() > 700) text = text.substring(0, 700) + "…";
                cards.add(new Card(item.memoryId().toString(), item.summary(), text,
                        files.stream().limit(8).toList(), item.stale() ? Boolean.TRUE : null, null,
                        code.isEmpty() ? null : String.join("", code).stripTrailing()));
            }
            return cards;
        }
    }

    private List<com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryContextItem> relevant(String query,
            String projectKey, boolean verificationOnly) {
        return band(ranked(query, projectKey, cardMinScore, verificationOnly));
    }

    /** Injection candidates at or above {@code floor}, best first (the retrieval's own min similarity still applies). */
    private List<com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryContextItem> ranked(String query,
            String projectKey, double floor, boolean verificationOnly) {
        return retrieval.retrieveForInjection(query, projectKey, null).items().stream()
                .filter(item -> item.semanticScore() >= floor)
                .filter(item -> !verificationOnly || isVerificationKnowledge(item.memoryId()))
                .sorted(java.util.Comparator.comparingDouble(
                        (com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryContextItem i) ->
                                i.semanticScore()).reversed())
                .toList();
    }

    /** Full cards: at or above the card floor (abstain otherwise), and only those near the best match, at most 3. */
    private List<com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryContextItem> band(
            List<com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryContextItem> ranked) {
        var above = ranked.stream().filter(item -> item.semanticScore() >= cardMinScore).toList();
        if (above.isEmpty()) return List.of();
        double best = above.get(0).semanticScore();
        return above.stream().filter(item -> item.semanticScore() >= best - CARD_BAND).limit(3).toList();
    }

    private static boolean hasKind(com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryItem item,
            Set<String> kinds) {
        return kinds.stream().anyMatch(kind -> kind.equals(item.metadata().get("discoveryKind"))
                || item.tags().contains(kind));
    }

    private boolean isVerificationKnowledge(java.util.UUID memoryId) {
        if (memories == null) return false;
        return memories.findById(memoryId).map(item -> VERIFICATION_KINDS.stream().anyMatch(kind ->
                kind.equals(item.metadata().get("discoveryKind")) || item.tags().contains(kind))).orElse(false);
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
    static final int MAX_POINTERS = 1;
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
