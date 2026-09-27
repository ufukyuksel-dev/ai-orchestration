package com.mbworldwideapps.aiorchestration.modules.agentlearning;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mbworldwideapps.aiorchestration.config.AgentLearningProperties;
import com.mbworldwideapps.aiorchestration.modules.agentlearning.AgentLearningModels.Anchor;
import com.mbworldwideapps.aiorchestration.modules.agentlearning.AgentLearningModels.Candidate;
import com.mbworldwideapps.aiorchestration.modules.agentlearning.AgentLearningModels.CandidateResult;
import com.mbworldwideapps.aiorchestration.modules.agentlearning.AgentLearningModels.CaptureCandidate;
import com.mbworldwideapps.aiorchestration.modules.agentlearning.AgentLearningModels.CaptureLocator;
import com.mbworldwideapps.aiorchestration.modules.agentlearning.AgentLearningModels.ClaimKind;
import com.mbworldwideapps.aiorchestration.modules.agentlearning.AgentLearningModels.CompactLearnReceipt;
import com.mbworldwideapps.aiorchestration.modules.agentlearning.AgentLearningModels.ContextReservation;
import com.mbworldwideapps.aiorchestration.modules.agentlearning.AgentLearningModels.LearnReceipt;
import com.mbworldwideapps.aiorchestration.modules.agentlearning.AgentLearningModels.Knowledge;
import com.mbworldwideapps.aiorchestration.modules.agentlearning.AgentLearningModels.KnowledgeBinding;
import com.mbworldwideapps.aiorchestration.modules.agentlearning.AgentLearningModels.LearningBatch;
import com.mbworldwideapps.aiorchestration.modules.agentlearning.AgentLearningModels.LearningReference;
import com.mbworldwideapps.aiorchestration.modules.agentlearning.AgentLearningModels.Observation;
import com.mbworldwideapps.aiorchestration.modules.agentlearning.AgentLearningModels.OpenedContext;
import com.mbworldwideapps.aiorchestration.modules.agentlearning.AgentLearningModels.OpenedReference;
import com.mbworldwideapps.aiorchestration.modules.agentlearning.AgentLearningModels.OpenedSource;
import com.mbworldwideapps.aiorchestration.modules.agentlearning.AgentLearningModels.Operation;
import com.mbworldwideapps.aiorchestration.modules.agentlearning.AgentLearningModels.ResearchContext;
import com.mbworldwideapps.aiorchestration.modules.agentlearning.AgentLearningModels.ReferenceTarget;
import com.mbworldwideapps.aiorchestration.modules.agentlearning.AgentLearningModels.SemanticCandidate;
import com.mbworldwideapps.aiorchestration.modules.agentlearning.AgentLearningModels.Target;
import com.mbworldwideapps.aiorchestration.modules.agentlearning.AgentLearningModels.TaskContextPlan;
import com.mbworldwideapps.aiorchestration.modules.agentlearning.AgentLearningModels.WorkspaceBinding;
import com.mbworldwideapps.aiorchestration.modules.memoryai.CreateMemoryRequest;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryCodeLocator;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryCodeLocatorKind;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryCodeLocatorMetadata;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryCodeLocatorRelationship;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryItem;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryPolicyViolationException;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryProjectionQueueFullException;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryScope;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryService;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryRelationService;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemorySourceType;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryStatus;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryTextPreview;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryType;
import com.mbworldwideapps.aiorchestration.modules.scanner.ProjectWorkspace;
import com.mbworldwideapps.aiorchestration.modules.scanner.ProjectWorkspaceResolutionException;
import com.mbworldwideapps.aiorchestration.modules.scanner.ProjectWorkspaceResolver;
import com.mbworldwideapps.aiorchestration.modules.scanner.ScannerPayloadRedactor;
import com.mbworldwideapps.aiorchestration.modules.scanner.CodeBaselineRepository;
import com.mbworldwideapps.aiorchestration.modules.scanner.UnresolvedSymbolScanScheduler;
import com.mbworldwideapps.aiorchestration.modules.references.ReferenceService;
import com.mbworldwideapps.aiorchestration.modules.llmgateway.TokenEstimator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AgentLearningService {

    private static final int MAX_QUERY_CHARS = 8_192;
    private static final int MAX_CONTEXT_TARGETS = 8;
    private static final int MAX_RESOLVE_TARGETS = 3;
    private static final Set<String> INTENTS = Set.of("locate", "explain", "change", "debug", "recall");
    private static final Set<String> REFERENCE_NEEDED_INTENTS = Set.of("explain", "change", "debug");
    private static final Set<String> KINDS = Set.of(
            "behavior", "navigation", "procedure", "field_mapping", "debug_finding", "change_point", "test_address");
    private static final Set<String> ANCHOR_ROLES = Set.of(
            "primary_change_point", "supporting", "validation", "configuration", "entry_point");

    private final AgentLearningRepository repository;
    private final ProjectWorkspaceResolver workspaceResolver;
    private final CodeBaselineRepository codeBaselineRepository;
    private final ScannerPayloadRedactor redactor;
    private final MemoryService memoryService;
    private final TaskContextFacade taskContexts;
    private final AgentLearningProperties properties;
    private final ObjectMapper objectMapper;
    private final ObjectMapper canonicalObjectMapper;
    private final AgentLearningCanonicalizer canonicalizer;
    private final AgentLearningContract contract;
    private final ReferenceService references;
    private final MemoryRelationService relations;
    private final AgentLearningMetrics metrics;
    private final UnresolvedSymbolScanScheduler unresolvedSymbolScanScheduler;
    private final TransactionTemplate transactions;

    public AgentLearningService(AgentLearningRepository repository, ProjectWorkspaceResolver workspaceResolver,
            CodeBaselineRepository codeBaselineRepository, ScannerPayloadRedactor redactor,
            MemoryService memoryService, TaskContextFacade taskContexts, AgentLearningProperties properties,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager) {
        this(repository, workspaceResolver, codeBaselineRepository, redactor, memoryService, taskContexts, properties,
                objectMapper, transactionManager, null, null, new AgentLearningContract(objectMapper), null,
                UnresolvedSymbolScanScheduler.noop());
    }

    public AgentLearningService(AgentLearningRepository repository, ProjectWorkspaceResolver workspaceResolver,
            CodeBaselineRepository codeBaselineRepository, ScannerPayloadRedactor redactor,
            MemoryService memoryService, TaskContextFacade taskContexts, AgentLearningProperties properties,
            ObjectMapper objectMapper, PlatformTransactionManager transactionManager,
            ReferenceService references, MemoryRelationService relations, AgentLearningContract contract) {
        this(repository, workspaceResolver, codeBaselineRepository, redactor, memoryService, taskContexts, properties,
                objectMapper, transactionManager, references, relations, contract, null,
                UnresolvedSymbolScanScheduler.noop());
    }

    public AgentLearningService(AgentLearningRepository repository, ProjectWorkspaceResolver workspaceResolver,
            CodeBaselineRepository codeBaselineRepository, ScannerPayloadRedactor redactor,
            MemoryService memoryService, TaskContextFacade taskContexts, AgentLearningProperties properties,
            ObjectMapper objectMapper, PlatformTransactionManager transactionManager,
            ReferenceService references, MemoryRelationService relations, AgentLearningContract contract,
            UnresolvedSymbolScanScheduler unresolvedSymbolScanScheduler) {
        this(repository, workspaceResolver, codeBaselineRepository, redactor, memoryService, taskContexts, properties,
                objectMapper, transactionManager, references, relations, contract, null,
                unresolvedSymbolScanScheduler);
    }

    @Autowired
    public AgentLearningService(AgentLearningRepository repository, ProjectWorkspaceResolver workspaceResolver,
            CodeBaselineRepository codeBaselineRepository, ScannerPayloadRedactor redactor,
            MemoryService memoryService, TaskContextFacade taskContexts, AgentLearningProperties properties,
            ObjectMapper objectMapper, PlatformTransactionManager transactionManager,
            ReferenceService references, MemoryRelationService relations, AgentLearningContract contract,
            AgentLearningMetrics metrics, UnresolvedSymbolScanScheduler unresolvedSymbolScanScheduler) {
        this.repository = repository;
        this.workspaceResolver = workspaceResolver;
        this.codeBaselineRepository = codeBaselineRepository;
        this.redactor = redactor;
        this.memoryService = memoryService;
        this.taskContexts = taskContexts;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.canonicalObjectMapper = objectMapper.copy()
                .configure(com.fasterxml.jackson.databind.MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
                .configure(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
        this.canonicalizer = new AgentLearningCanonicalizer(objectMapper);
        this.contract = contract;
        this.references = references;
        this.relations = relations;
        this.metrics = metrics;
        this.unresolvedSymbolScanScheduler = unresolvedSymbolScanScheduler == null
                ? UnresolvedSymbolScanScheduler.noop()
                : unresolvedSymbolScanScheduler;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public boolean enabled() {
        return properties.enabled();
    }

    private void requireEnabled() {
        if (!properties.enabled()) {
            throw new IllegalStateException("AGENT_LEARNING_DISABLED");
        }
    }

    public UUID issueWorkspaceBinding(String principalKey, String clientId, ProjectWorkspace workspace) {
        requireEnabled();
        Instant now = Instant.now();
        WorkspaceBinding binding = new WorkspaceBinding(UUID.randomUUID(), require(principalKey, "principalKey"),
                require(clientId, "clientId"), workspace.projectKey(), workspace.repositoryFingerprint(),
                workspace.repositoryRoot(), now, now.plus(properties.contextTtl()));
        return transactions.execute(status -> repository.insertBinding(binding).id());
    }

    public ContextReservation resolveContext(String principalKey, String clientId, String projectKey,
            UUID workspaceBindingId, String query, String intent, List<String> pathHints, List<String> symbolHints,
            Integer requestedMaxTokens, String includeReferences) {
        return resolveContext(principalKey, clientId, projectKey, workspaceBindingId, query, intent,
                pathHints, symbolHints, List.of(), requestedMaxTokens, includeReferences);
    }

    public ContextReservation resolveContext(String principalKey, String clientId, String projectKey,
            UUID workspaceBindingId, String query, String intent, List<String> pathHints, List<String> symbolHints,
            List<String> termHints, Integer requestedMaxTokens, String includeReferences) {
        return resolveContext(principalKey, clientId, legacySessionScope(clientId), projectKey, workspaceBindingId,
                query, intent, pathHints, symbolHints, termHints, requestedMaxTokens, includeReferences);
    }

    public ContextReservation resolveContext(String principalKey, String clientId, String sessionScopeHash,
            String projectKey, UUID workspaceBindingId, String query, String intent, List<String> pathHints,
            List<String> symbolHints, List<String> termHints, Integer requestedMaxTokens, String includeReferences) {
        requireEnabled();
        if (metrics != null) metrics.toolCall();
        String normalizedQuery = normalizeBounded(query, "query", MAX_QUERY_CHARS);
        String normalizedIntent = normalizeIntent(intent);
        int maxTokens = requestedMaxTokens == null ? properties.defaultContextTokenBudget() : requestedMaxTokens;
        if (maxTokens < 128 || maxTokens > properties.maxContextTokenBudget()) {
            throw new IllegalArgumentException("maxTokens must be between 128 and " + properties.maxContextTokenBudget());
        }
        String referenceMode = normalizeIncludeReferences(includeReferences);
        WorkspaceBinding binding = requireBinding(workspaceBindingId, principalKey, projectKey);
        ProjectWorkspace workspace = currentWorkspace(binding);
        List<String> paths = normalizePaths(pathHints);
        List<String> symbols = normalizeSymbolHints(symbolHints);
        List<String> terms = normalizeTermHints(termHints);
        if (paths.size() + symbols.size() > MAX_CONTEXT_TARGETS) {
            throw new IllegalArgumentException("path and symbol hints must contain at most 8 distinct targets");
        }
        List<TargetSeed> seeds = new ArrayList<>();
        for (String path : paths) {
            Path target = requireRegularSource(workspace, path);
            seeds.add(new TargetSeed(path, null, "primary_change_point", sha256(readBounded(target)),
                    "UNCHANGED", null, null, null));
        }
        for (String symbolRef : symbols) {
            var matches = codeBaselineRepository.findSymbolsByRef(projectKey, symbolRef, 3).stream()
                    .filter(symbol -> projectKey.equals(symbol.projectKey()))
                    .toList();
            if (matches.isEmpty()) throw new IllegalArgumentException("TARGET_MISSING:" + symbolRef);
            if (matches.size() != 1) throw new IllegalArgumentException("AMBIGUOUS_TARGET:" + symbolRef);
            var symbol = matches.getFirst();
            var file = codeBaselineRepository.findFileById(projectKey, symbol.fileId())
                    .filter(value -> projectKey.equals(value.projectKey()))
                    .orElseThrow(() -> new IllegalArgumentException("TARGET_MISSING:" + symbolRef));
            if (seeds.stream().anyMatch(value -> value.relativePath().equals(file.filePath()))) continue;
            Path target = requireRegularSource(workspace, file.filePath());
            seeds.add(new TargetSeed(file.filePath(),
                    symbol.fqn() == null || symbol.fqn().isBlank() ? symbol.signature() : symbol.fqn(),
                    "primary_change_point", sha256(readBounded(target)), "UNCHANGED", null,
                    symbol.startLine(), symbol.endLine()));
        }

        TaskContextPlan learned = TaskContextPlan.empty();
        Set<UUID> missingLearnedMemories = new LinkedHashSet<>();
        List<String> learnedTargetGaps = new ArrayList<>();
        if (properties.navigationEnabled() && taskContexts != null) {
            String retrievalQuery = terms.isEmpty() ? normalizedQuery
                    : normalizedQuery + " " + String.join(" ", terms);
            boolean expandReferences = "always".equals(referenceMode)
                    || ("when_needed".equals(referenceMode)
                        && REFERENCE_NEEDED_INTENTS.contains(normalizedIntent));
            learned = !expandReferences
                    ? taskContexts.resolve(retrievalQuery, projectKey, normalizedIntent, maxTokens)
                    : taskContexts.resolve(retrievalQuery, projectKey, normalizedIntent, maxTokens, true);
            for (var suggestion : learned.targets()) {
                if (seeds.size() >= MAX_CONTEXT_TARGETS
                        || seeds.stream().anyMatch(value -> value.relativePath().equals(suggestion.relativePath()))) {
                    continue;
                }
                try {
                    Path target = requireRegularSource(workspace, suggestion.relativePath());
                    String currentHash = sha256(readBounded(target));
                    String sourceCheck = suggestion.expectedHash() == null || suggestion.expectedHash().isBlank()
                            ? "UNKNOWN"
                            : suggestion.expectedHash().equals(currentHash) ? "UNCHANGED" : "CHANGED";
                    seeds.add(new TargetSeed(suggestion.relativePath(), suggestion.symbolRef(), suggestion.role(),
                            currentHash, sourceCheck, suggestion.sourceMemoryId(), suggestion.startLine(),
                            suggestion.endLine()));
                } catch (IllegalArgumentException | ProjectWorkspaceResolutionException unavailable) {
                    // Learned locators are hints, not authority. Preserve the
                    // missing gap and never auto-bind a similar path.
                    if (suggestion.sourceMemoryId() != null) {
                        missingLearnedMemories.add(suggestion.sourceMemoryId());
                    }
                    learnedTargetGaps.add("learned_target_missing:" + suggestion.relativePath());
                }
            }
        }

        int omittedTargets = learned.omittedTargets() + Math.max(0, seeds.size() - MAX_RESOLVE_TARGETS);
        List<TargetSeed> selectedSeeds = seeds.stream().limit(MAX_RESOLVE_TARGETS).toList();
        List<Target> targets = new ArrayList<>();
        Map<String, String> sourceChecks = new LinkedHashMap<>();
        int index = 1;
        for (TargetSeed seed : selectedSeeds) {
            String targetId = "t" + index++;
            targets.add(new Target(targetId, seed.relativePath(), seed.symbolRef(), seed.role(), seed.currentHash(),
                    seed.startLine(), seed.endLine()));
            sourceChecks.put(targetId, seed.sourceCheck());
        }
        List<ReferenceTarget> references = new ArrayList<>();
        int referenceIndex = 1;
        for (var suggestion : learned.references()) {
            String targetId = "r" + referenceIndex++;
            references.add(new ReferenceTarget(targetId, suggestion.referenceId(), suggestion.relativePath(),
                    suggestion.contentHash(), suggestion.sectionKey(), suggestion.sourceMemoryId()));
            sourceChecks.put(targetId, suggestion.sourceCheck());
        }
        List<Knowledge> knowledge = applyKnowledgeFreshness(
                learned.knowledge(), selectedSeeds, learned.references(), missingLearnedMemories);
        List<String> gaps = new ArrayList<>(learned.gaps());
        gaps.addAll(learnedTargetGaps);
        if (targets.isEmpty() && references.isEmpty() && !gaps.contains("no_focused_target")) {
            gaps.add("no_focused_target");
        } else if (!references.isEmpty()) {
            gaps.remove("no_focused_target");
        }
        String routeStatus = routeStatus(learned.retrievalUnavailable(), knowledge, targets, references, gaps);
        Instant now = Instant.now();
        ResearchContext context = new ResearchContext(UUID.randomUUID(), binding.id(), principalKey, clientId,
                requireSessionScope(sessionScopeHash), projectKey, UUID.randomUUID(), normalizedIntent,
                sha256(normalizedQuery), "server_observed",
                now, now.plus(properties.contextTtl()), targets, references);
        UUID learningHandle = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        List<KnowledgeBinding> knowledgeBindings = new ArrayList<>();
        int knowledgeIndex = 1;
        for (Knowledge item : knowledge) {
            if (item.id() == null || item.learningRevision() == null || item.canonicalHash() == null) continue;
            knowledgeBindings.add(new KnowledgeBinding("k" + knowledgeIndex++, item.id(),
                    item.learningRevision(), item.canonicalHash()));
        }
        transactions.executeWithoutResult(status -> {
            repository.supersedeActiveContexts(principalKey, projectKey, clientId,
                    context.sessionScopeHash(), now);
            repository.insertContext(context, learningHandle, requestId, now.plus(properties.operationRetention()));
            repository.insertKnowledgeBindings(context.id(), knowledgeBindings);
        });
        return new ContextReservation(context, learningHandle, requestId, routeStatus, knowledge, sourceChecks,
                gaps, learned.omittedKnowledge(), omittedTargets);
    }

    public OpenedContext openContext(String principalKey, String projectKey, UUID contextId,
            List<String> requestedTargetIds, Integer requestedMaxTokens) {
        requireEnabled();
        if (metrics != null) metrics.toolCall();
        ResearchContext context = requireContext(contextId, principalKey, projectKey);
        WorkspaceBinding binding = requireBinding(context.workspaceBindingId(), principalKey, projectKey);
        ProjectWorkspace workspace = currentWorkspace(binding);
        List<String> targetIds = normalizeTargetIds(requestedTargetIds);
        int maxTokens = requestedMaxTokens == null ? 1800 : requestedMaxTokens;
        if (maxTokens < 128 || maxTokens > properties.maxContextTokenBudget()) {
            throw new IllegalArgumentException("maxTokens must be between 128 and " + properties.maxContextTokenBudget());
        }
        int charsPerTarget = Math.max(128, Math.multiplyExact(maxTokens, 4) / targetIds.size());
        List<OpenedSource> opened = new ArrayList<>();
        List<OpenedReference> openedReferences = new ArrayList<>();
        for (String targetId : targetIds) {
            var sourceTarget = context.targets().stream().filter(value -> value.targetId().equals(targetId)).findFirst();
            if (sourceTarget.isPresent()) {
                Target target = sourceTarget.orElseThrow();
                Path source = requireRegularSource(workspace, target.relativePath());
                byte[] raw = readBounded(source);
                String rawHash = sha256(raw);
                String rawText = decodeUtf8(raw);
                String safe = redactor.redact(rawText);
                SourceWindow window = sourceWindow(safe, target, charsPerTarget);
                UUID evidenceId = UUID.randomUUID();
                Observation observation = new Observation(evidenceId, context.id(), target.targetId(), "source_read",
                        target.relativePath(), rawHash, sha256(safe), binding.id(), raw.length, "server_observed",
                        Instant.now());
                transactions.executeWithoutResult(status -> repository.insertObservation(observation));
                opened.add(new OpenedSource(target.targetId(), evidenceId, target.relativePath(),
                        window.startLine(), window.endLine(), window.content(), window.complete(),
                        !rawHash.equals(target.resolvedHash())));
                continue;
            }
            ReferenceTarget reference = context.references().stream()
                    .filter(value -> value.targetId().equals(targetId)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("TARGET_UNKNOWN"));
            try {
                var section = taskContexts.openReference(reference.referenceId(), reference.contentHash(),
                        reference.sectionKey(), Math.min(65536, charsPerTarget));
                openedReferences.add(new OpenedReference(reference.targetId(), reference.referenceId(),
                        reference.relativePath(), reference.sectionKey(), section.title(), section.text(),
                        section.contentComplete(), false, section.visibleHash()));
            } catch (IllegalArgumentException drift) {
                if (!Set.of("REFERENCE_CONTENT_CHANGED", "REFERENCE_MISSING", "REFERENCE_SECTION_MISSING")
                        .contains(drift.getMessage())) throw drift;
                openedReferences.add(new OpenedReference(reference.targetId(), reference.referenceId(),
                        reference.relativePath(), reference.sectionKey(), null, "", true, true, null));
            }
        }
        return new OpenedContext(context.id(), opened, openedReferences);
    }

    public OpenedContext openActiveContext(String principalKey, String clientId, String sessionScopeHash,
            String projectKey, List<String> targetRefs, Integer maxTokens) {
        ResearchContext context = repository.findActiveContext(principalKey, projectKey, clientId,
                requireSessionScope(sessionScopeHash), Instant.now())
                .orElseThrow(() -> new IllegalArgumentException("ACTIVE_CONTEXT_REQUIRED"));
        Map<String, String> referenceRefs = new LinkedHashMap<>();
        Map<String, KnowledgeBinding> knowledge = repository.findKnowledgeBindings(context.id()).stream()
                .collect(java.util.stream.Collectors.toMap(KnowledgeBinding::ref, value -> value));
        List<String> internalRefs = targetRefs.stream().map(ref -> {
            if (!ref.startsWith("k")) return ref;
            KnowledgeBinding binding = knowledge.get(ref);
            if (binding == null) throw new IllegalArgumentException("KNOWLEDGE_REF_UNKNOWN");
            ReferenceTarget reference = context.references().stream()
                    .filter(item -> binding.memoryId().equals(item.sourceMemoryId())).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("KNOWLEDGE_REFERENCE_UNAVAILABLE"));
            referenceRefs.put(reference.targetId(), ref);
            return reference.targetId();
        }).toList();
        for (String ref : internalRefs) {
            if (ref.startsWith("r")) continue;
            Target target = target(context, ref);
            if (target.startLine() == null || target.endLine() == null) {
                throw new IllegalArgumentException("TARGET_RANGE_REQUIRED");
            }
        }
        OpenedContext opened = openContext(principalKey, projectKey, context.id(), internalRefs, maxTokens);
        List<OpenedReference> projected = opened.references().stream().map(item -> new OpenedReference(
                referenceRefs.getOrDefault(item.targetId(), item.targetId()), item.referenceId(), item.path(),
                item.sectionKey(), item.title(), item.content(), item.contentComplete(), item.changedSinceResolve(),
                item.visibleHash())).toList();
        return new OpenedContext(opened.contextId(), opened.items(), projected);
    }

    public String projectForActiveContext(String principalKey, String clientId, String sessionScopeHash) {
        return repository.findLatestActiveContext(principalKey, clientId, requireSessionScope(sessionScopeHash),
                Instant.now()).orElseThrow(() -> new IllegalArgumentException("ACTIVE_CONTEXT_REQUIRED"))
                .projectKey();
    }

    /**
     * Handles the compact v2 contract. The active context, evidence observations,
     * correction CAS values and persistence identifiers stay server-side.
     */
    public CompactLearnReceipt learnSemantic(String principalKey, String clientId, String sessionScopeHash,
            String projectKey, LearningBatch requested, String producerRuntime, String producerModel) {
        requireEnabled();
        if (!properties.captureEnabled()) throw new IllegalStateException("LEARNING_CAPTURE_DISABLED");
        LearningBatch batch;
        try {
            batch = contract.validate(requested);
        } catch (IllegalArgumentException invalid) {
            if (metrics != null) metrics.validationFailure();
            throw invalid;
        }
        if (metrics != null) {
            metrics.toolCall();
            metrics.learnCall(batch.learnings().size(), TokenEstimator.estimate(canonicalJson(batch)));
        }
        if (batch.learnings().isEmpty()) {
            CompactLearnReceipt empty = new CompactLearnReceipt("ACCEPTED", 0, 0, 0, 0, 0, 0, 0);
            if (metrics != null) metrics.receipt(empty, TokenEstimator.estimate(json(empty)));
            return empty;
        }
        String scope = requireSessionScope(sessionScopeHash);
        ResearchContext context = repository.findActiveContext(principalKey, projectKey, clientId, scope, Instant.now())
                .orElseThrow(() -> new IllegalArgumentException("ACTIVE_CONTEXT_REQUIRED"));
        WorkspaceBinding binding = requireBinding(context.workspaceBindingId(), principalKey, projectKey);
        currentWorkspace(binding);

        Map<String, Observation> observations = registerCurrentEvidence(context, binding,
                batch.learnings().stream().flatMap(item -> item.evidence().stream()).distinct().toList());
        Map<String, KnowledgeBinding> knowledge = repository.findKnowledgeBindings(context.id()).stream()
                .collect(java.util.stream.Collectors.toMap(KnowledgeBinding::ref, value -> value));
        List<Candidate> candidates = new ArrayList<>();
        List<String> candidateIdentities = new ArrayList<>();
        for (var item : batch.learnings()) {
            List<Anchor> anchors = item.anchors().stream().map(ref -> {
                Target target = target(context, ref);
                return new Anchor(ref, normalizeAnchorRole(target.role()));
            }).toList();
            List<UUID> evidenceIds = item.evidence().stream().map(ref -> {
                Observation observation = observations.get(ref);
                if (observation == null) throw new IllegalArgumentException("EVIDENCE_SCOPE_DENIED");
                return observation.id();
            }).toList();
            KnowledgeBinding correction = item.correctionRef() == null ? null : knowledge.get(item.correctionRef());
            if (item.correctionRef() != null && correction == null) {
                throw new IllegalArgumentException("CORRECTION_REF_UNKNOWN");
            }
            Candidate candidate = new Candidate(item.kind(), item.summary(), item.content(), item.appliesWhen(),
                    item.limitations(), anchors, evidenceIds, item.reusableFor(),
                    correction == null ? null : correction.memoryId(),
                    correction == null ? null : correction.learningRevision(),
                    correction == null ? null : correction.canonicalHash());
            candidates.add(candidate);
            AgentLearningCanonicalizer.Identity identity = canonicalizer.identity(candidate, context.targets(),
                    item.evidence().stream().map(observations::get).toList());
            String referenceRevision = item.reference() == null ? "" : sha256(item.reference().details());
            candidateIdentities.add(identity.semanticIdentity() + ":" + identity.contentRevision() + ":"
                    + identity.evidenceRevision() + ":" + referenceRevision + ":"
                    + (item.correctionRef() == null ? "" : item.correctionRef()));
        }
        String payloadHash = sha256("agent-learning/batch-v2:" + candidateIdentities.stream().sorted().toList());
        String operationKey = sha256("agent-learning/operation-v2:" + scope + ":" + context.id() + ":" + payloadHash);
        Operation operation = repository.findOperationByKey(operationKey).orElseGet(() -> reserveSemanticOperation(
                context, scope, operationKey));
        CompactLearnReceipt compact = decodeCompactReceipt(operation);
        if (compact != null) {
            if (metrics != null) metrics.receipt(compact, TokenEstimator.estimate(json(compact)));
            return compact;
        }

        LearnReceipt raw = learn(principalKey, projectKey, context.id(), operation.learningHandle(), candidates,
                producerRuntime, producerModel);
        if (!"COMPLETED".equals(raw.status())) {
            return new CompactLearnReceipt(raw.status(), 0, 0, 0, 0, 0, 0, 0);
        }

        int referencesCreated = 0;
        int referencesReused = 0;
        for (int index = 0; index < batch.learnings().size(); index++) {
            var reference = batch.learnings().get(index).reference();
            CandidateResult result = raw.results().get(index);
            if (reference == null || result.memoryId() == null || references == null || relations == null) continue;
            String details = redactor.redact(reference.details());
            String referencePath = "learning/" + sha256(details) + ".md";
            ReferenceService.Saved saved = references.write(referencePath, details, null);
            relations.write(projectKey, result.memoryId(), "reference", saved.item().id(), "references",
                    "Long-form evidence for the reusable learning");
            if (saved.created()) referencesCreated++; else referencesReused++;
        }
        CompactLearnReceipt receipt = compactReceipt(raw, referencesCreated, referencesReused);
        transactions.executeWithoutResult(status -> repository.replaceCompletedResult(raw.requestId(),
                json(receipt), Instant.now()));
        if (metrics != null) metrics.receipt(receipt, TokenEstimator.estimate(json(receipt)));
        return receipt;
    }

    /**
     * Persists a final-response learning sidecar without retrieval or an active
     * agent context. The runtime supplies a verified workspace binding and a
     * stable taskRunId; duplicate hook delivery replays the same receipt.
     */
    public CompactLearnReceipt captureSemantic(String principalKey, String clientId, String sessionScopeHash,
            String projectKey, UUID workspaceBindingId, String taskRunId,
            List<CaptureCandidate> requested, String producerRuntime, String producerModel) {
        requireEnabled();
        if (!properties.captureEnabled()) throw new IllegalStateException("LEARNING_CAPTURE_DISABLED");
        String scope = requireSessionScope(sessionScopeHash);
        String runId = normalizeBounded(taskRunId, "taskRunId", 128);
        if (!runId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
            throw new IllegalArgumentException("taskRunId has an invalid format");
        }
        List<CaptureCandidate> captures = normalizeCaptureCandidates(requested);
        LinkedHashMap<String, CaptureLocator> locators = captureLocators(captures);
        Map<String, String> refsByLocator = new LinkedHashMap<>();
        int refIndex = 1;
        for (String key : locators.keySet()) refsByLocator.put(key, "t" + refIndex++);
        LearningBatch semanticBatch = captureBatch(captures, refsByLocator);
        try {
            contract.validate(semanticBatch);
        } catch (IllegalArgumentException invalid) {
            if (metrics != null) metrics.validationFailure();
            throw invalid;
        }
        if (metrics != null) {
            metrics.toolCall();
            metrics.learnCall(captures.size(), TokenEstimator.estimate(canonicalJson(captures)));
        }

        String operationKey = sha256("agent-learning/capture-v1:" + principalKey + ":" + projectKey + ":"
                + runId + ":" + canonicalJson(captures));
        Operation operation = repository.findOperationByKey(operationKey).orElse(null);
        ResearchContext context;
        if (operation == null) {
            WorkspaceBinding binding = requireBinding(workspaceBindingId, principalKey, projectKey);
            ProjectWorkspace workspace = currentWorkspace(binding);
            Set<String> unresolvedSymbolPaths = new LinkedHashSet<>();
            List<Target> targets = resolveCaptureCandidates(projectKey, workspace, captures, refsByLocator,
                    unresolvedSymbolPaths);
            if (!unresolvedSymbolPaths.isEmpty()) {
                unresolvedSymbolScanScheduler.schedule(projectKey, workspace.repositoryRoot(),
                        List.copyOf(unresolvedSymbolPaths));
            }
            List<KnowledgeBinding> knowledgeBindings = captures.stream()
                    .anyMatch(candidate -> candidate.correctionRef() != null)
                    ? repository.findActiveContext(principalKey, projectKey, clientId, scope, Instant.now())
                            .map(active -> repository.findKnowledgeBindings(active.id())).orElse(List.of())
                    : List.of();
            Instant now = Instant.now();
            context = new ResearchContext(UUID.randomUUID(), binding.id(), principalKey, clientId, scope,
                    projectKey, UUID.randomUUID(), "explain", sha256("capture:" + runId),
                    "server_observed", now, now.plus(properties.contextTtl()), targets, List.of());
            operation = new Operation(UUID.randomUUID(), UUID.randomUUID(), context.id(), principalKey,
                    projectKey, null, "ISSUED", null, null, now, now,
                    now.plus(properties.operationRetention()));
            ResearchContext proposedContext = context;
            Operation proposed = operation;
            try {
                transactions.executeWithoutResult(status -> {
                    repository.insertCaptureContext(proposedContext, proposed, scope, operationKey);
                    repository.insertKnowledgeBindings(proposedContext.id(), knowledgeBindings);
                });
            } catch (org.springframework.dao.DataIntegrityViolationException duplicate) {
                operation = repository.findOperationByKey(operationKey).orElseThrow(() -> duplicate);
                context = requireContext(operation.contextId(), principalKey, projectKey);
            }
        } else {
            if (!principalKey.equals(operation.principalKey()) || !projectKey.equals(operation.projectKey())) {
                throw new IllegalArgumentException("CAPTURE_OPERATION_SCOPE_DENIED");
            }
            context = requireContext(operation.contextId(), principalKey, projectKey);
            CompactLearnReceipt replay = decodeCompactReceipt(operation);
            if (replay != null) {
                if (metrics != null) metrics.receipt(replay, TokenEstimator.estimate(json(replay)));
                return replay;
            }
            if ("COMPLETED".equals(operation.state()) && operation.resultJson() != null) {
                CompactLearnReceipt recovered = compactReceipt(decodeReceipt(operation), 0, 0);
                UUID completedRequest = operation.requestId();
                transactions.executeWithoutResult(status -> repository.replaceCompletedResult(completedRequest,
                        json(recovered), Instant.now()));
                if (metrics != null) metrics.receipt(recovered, TokenEstimator.estimate(json(recovered)));
                return recovered;
            }
        }

        WorkspaceBinding binding = requireBinding(context.workspaceBindingId(), principalKey, projectKey);
        Map<String, Observation> observations = registerCurrentEvidence(context, binding,
                context.targets().stream().filter(target -> !"directory".equals(target.locatorKind()))
                        .map(Target::targetId).toList());
        Map<String, Target> targetsById = context.targets().stream()
                .collect(java.util.stream.Collectors.toMap(Target::targetId, value -> value));
        Map<String, KnowledgeBinding> knowledge = repository.findKnowledgeBindings(context.id()).stream()
                .collect(java.util.stream.Collectors.toMap(KnowledgeBinding::ref, value -> value));
        List<Candidate> candidates = new ArrayList<>();
        List<CaptureCandidate> learnedCaptures = new ArrayList<>();
        int preRejected = 0;
        for (CaptureCandidate capture : captures) {
            List<Map.Entry<CaptureLocator, Target>> candidateTargets = new ArrayList<>();
            boolean unresolved = false;
            for (CaptureLocator locator : capture.locators()) {
                Target target = targetsById.get(refsByLocator.get(captureLocatorKey(locator)));
                if (target == null) {
                    unresolved = true;
                    break;
                }
                candidateTargets.add(Map.entry(locator, target));
            }
            KnowledgeBinding correction = capture.correctionRef() == null
                    ? null : knowledge.get(capture.correctionRef());
            if (unresolved || capture.correctionRef() != null && correction == null) {
                preRejected++;
                continue;
            }
            candidates.add(new Candidate(capture.kind(), capture.summary(), capture.content(),
                    capture.appliesWhen(), capture.limitations(), candidateTargets.stream()
                            .map(entry -> new Anchor(entry.getValue().targetId(), entry.getKey().role())).toList(),
                    candidateTargets.stream().map(Map.Entry::getValue)
                            .filter(target -> !"directory".equals(target.locatorKind()))
                            .map(target -> observations.get(target.targetId()).id()).toList(),
                    capture.reusableFor(), correction == null ? null : correction.memoryId(),
                    correction == null ? null : correction.learningRevision(),
                    correction == null ? null : correction.canonicalHash()));
            learnedCaptures.add(capture);
        }
        if (candidates.isEmpty()) {
            CompactLearnReceipt rejected = new CompactLearnReceipt("REJECTED", 0, 0, 0,
                    preRejected, 0, 0, 0);
            String rejectedPayloadHash = sha256("agent-learning/capture-rejected-v1:" + operationKey);
            Operation rejectedOperation = operation;
            var claim = transactions.execute(status -> repository.claim(rejectedOperation.learningHandle(),
                    rejectedOperation.contextId(), principalKey, projectKey, rejectedPayloadHash,
                    Instant.now().plus(properties.operationLease()), Instant.now()));
            if (claim == null) throw new IllegalStateException("learning claim did not complete");
            if (claim.kind() == ClaimKind.REPLAY) {
                CompactLearnReceipt replay = decodeCompactReceipt(claim.operation());
                if (replay != null) return replay;
            }
            if (claim.kind() == ClaimKind.QUEUED) {
                return new CompactLearnReceipt("QUEUED", 0, 0, 0, preRejected, 0, 0, 0);
            }
            if (claim.kind() == ClaimKind.CONFLICT) {
                return new CompactLearnReceipt("CONFLICT", 0, 0, 0, preRejected, 0, 0, 0);
            }
            UUID requestId = claim.operation().requestId();
            transactions.executeWithoutResult(status -> repository.complete(requestId,
                    rejectedPayloadHash, json(rejected), Instant.now()));
            if (metrics != null) metrics.receipt(rejected, TokenEstimator.estimate(json(rejected)));
            return rejected;
        }
        LearnReceipt raw = learn(principalKey, projectKey, context.id(), operation.learningHandle(), candidates,
                producerRuntime, producerModel);
        if (!"COMPLETED".equals(raw.status())) {
            return new CompactLearnReceipt(raw.status(), 0, 0, 0, 0, 0, 0, 0);
        }
        int referencesCreated = 0;
        int referencesReused = 0;
        for (int index = 0; index < learnedCaptures.size(); index++) {
            var reference = learnedCaptures.get(index).reference();
            CandidateResult result = raw.results().get(index);
            if (reference == null || result.memoryId() == null || references == null || relations == null) continue;
            String details = redactor.redact(reference.details());
            String referencePath = "learning/" + sha256(details) + ".md";
            ReferenceService.Saved saved = references.write(referencePath, details, null);
            relations.write(projectKey, result.memoryId(), "reference", saved.item().id(), "references",
                    "Long-form evidence for the reusable learning");
            if (saved.created()) referencesCreated++; else referencesReused++;
        }
        CompactLearnReceipt receipt = withPreRejected(
                compactReceipt(raw, referencesCreated, referencesReused), preRejected);
        transactions.executeWithoutResult(status -> repository.replaceCompletedResult(raw.requestId(),
                json(receipt), Instant.now()));
        if (metrics != null) metrics.receipt(receipt, TokenEstimator.estimate(json(receipt)));
        return receipt;
    }

    private List<CaptureCandidate> normalizeCaptureCandidates(List<CaptureCandidate> requested) {
        if (requested == null || requested.isEmpty()
                || requested.size() > properties.maxCandidatesPerWrite()) {
            throw new IllegalArgumentException("learningCandidates must contain 1 to "
                    + properties.maxCandidatesPerWrite() + " items");
        }
        List<CaptureCandidate> normalized = new ArrayList<>();
        for (CaptureCandidate candidate : requested) {
            if (candidate == null) throw new IllegalArgumentException("learning candidate must not be null");
            if (candidate.locators().isEmpty() || candidate.locators().size() > MAX_CONTEXT_TARGETS) {
                throw new IllegalArgumentException("locators must contain 1 to 8 items");
            }
            List<CaptureLocator> normalizedLocators = candidate.locators().stream()
                    .map(this::normalizeCaptureLocator).toList();
            if (normalizedLocators.stream().map(AgentLearningService::captureLocatorKey).distinct().count()
                    != normalizedLocators.size()) {
                throw new IllegalArgumentException("locators must be unique");
            }
            if (normalizedLocators.stream().allMatch(locator -> "directory".equals(locator.kind()))) {
                throw new IllegalArgumentException("a learning candidate requires at least one symbol or file locator");
            }
            LearningReference reference = candidate.reference() == null ? null
                    : new LearningReference(redactBounded(candidate.reference().details(),
                            "reference.details", 4000));
            normalized.add(new CaptureCandidate(candidate.kind(), candidate.summary(), candidate.content(),
                    normalizedLocators, candidate.appliesWhen(), candidate.limitations(), candidate.reusableFor(),
                    normalizeOptional(candidate.correctionRef(), 16, "correctionRef"), reference));
        }
        return List.copyOf(normalized);
    }

    private LinkedHashMap<String, CaptureLocator> captureLocators(List<CaptureCandidate> captures) {
        LinkedHashMap<String, CaptureLocator> locators = new LinkedHashMap<>();
        for (CaptureCandidate candidate : captures) {
            for (CaptureLocator locator : candidate.locators())
                locators.putIfAbsent(captureLocatorKey(locator), locator);
        }
        if (locators.size() > MAX_CONTEXT_TARGETS) {
            throw new IllegalArgumentException("capture must contain at most 8 distinct locators");
        }
        return locators;
    }

    private static LearningBatch captureBatch(List<CaptureCandidate> captures, Map<String, String> refsByLocator) {
        return new LearningBatch(captures.stream().map(candidate -> {
            List<String> refs = candidate.locators().stream()
                    .map(locator -> refsByLocator.get(captureLocatorKey(locator))).toList();
            List<String> evidence = candidate.locators().stream()
                    .filter(locator -> !"directory".equals(locator.kind()))
                    .map(locator -> refsByLocator.get(captureLocatorKey(locator))).toList();
            return new SemanticCandidate(candidate.kind(), candidate.summary(), candidate.content(), refs, evidence,
                    candidate.appliesWhen(), candidate.limitations(), candidate.reusableFor(),
                    candidate.correctionRef(), candidate.reference());
        }).toList());
    }

    private CaptureLocator normalizeCaptureLocator(CaptureLocator requested) {
        if (requested == null) throw new IllegalArgumentException("locator must not be null");
        String kind = normalizeEnum(requested.kind(), Set.of("symbol", "file", "directory"), "locator kind");
        String role = requested.role() == null || requested.role().isBlank()
                ? "supporting" : normalizeEnum(requested.role(), ANCHOR_ROLES, "locator role");
        if ("symbol".equals(kind)) {
            String ref = normalizeBounded(requested.ref(), "symbol ref", 512);
            String path = normalizeCapturePath(requested.path(), "symbol path");
            String signature = normalizeOptional(requested.signature(), 160, "symbol signature");
            return new CaptureLocator(kind, ref, signature, path, role);
        }
        if (requested.signature() != null || requested.path() != null) {
            throw new IllegalArgumentException(kind + " locator must not declare signature or path");
        }
        return new CaptureLocator(kind, normalizeCapturePath(requested.ref(), kind + " ref"), null, null, role);
    }

    private String normalizeCapturePath(String value, String field) {
        String path = normalizeBounded(value, field, 512).replace('\\', '/');
        if (path.startsWith("/") || path.startsWith("./") || path.contains("../") || path.contains(":")) {
            throw new IllegalArgumentException(field + " must be a canonical repository-relative path");
        }
        return path;
    }

    private Target resolveCaptureLocator(String projectKey, ProjectWorkspace workspace,
            String targetId, CaptureLocator locator, Set<String> unresolvedSymbolPaths) {
        if ("directory".equals(locator.kind())) {
            requireDirectory(workspace, locator.ref());
            return new Target(targetId, locator.ref(), null, "supporting",
                    sha256("directory:" + locator.ref()), null, null, "directory");
        }
        if ("file".equals(locator.kind())) {
            Path source = requireRegularSource(workspace, locator.ref());
            return new Target(targetId, locator.ref(), null, "supporting",
                    sha256(readBounded(source)), null, null, "file");
        }
        List<com.mbworldwideapps.aiorchestration.modules.scanner.CodeSymbolRecord> matches = codeBaselineRepository
                .findSymbolsByLocator(projectKey, locator.path(), locator.ref(), locator.signature(), 2);
        if (matches.isEmpty() && locator.signature() != null) {
            matches = codeBaselineRepository.findSymbolsByLocator(projectKey, locator.path(), locator.ref(), null, 2);
        }
        if (matches.isEmpty()) {
            String normalizedMethodRef = dottedMethodRef(locator.ref());
            if (!normalizedMethodRef.equals(locator.ref())) {
                matches = codeBaselineRepository.findSymbolsByLocator(projectKey, locator.path(),
                        normalizedMethodRef, locator.signature(), 2);
                if (matches.isEmpty() && locator.signature() != null) {
                    matches = codeBaselineRepository.findSymbolsByLocator(projectKey, locator.path(),
                            normalizedMethodRef, null, 2);
                }
            }
        }
        if (matches.isEmpty()) {
            Path source = requireRegularSource(workspace, locator.path());
            unresolvedSymbolPaths.add(locator.path());
            return new Target(targetId, locator.path(), declaredSymbolRef(locator), "supporting",
                    sha256(readBounded(source)), null, null, "symbol");
        }
        if (matches.size() != 1) throw new IllegalArgumentException("LOCATOR_AMBIGUOUS:" + locator.ref());
        var symbol = matches.getFirst();
        Path source = requireRegularSource(workspace, locator.path());
        return new Target(targetId, locator.path(), canonicalSymbolRef(symbol), "supporting",
                sha256(readBounded(source)), null, null, "symbol");
    }

    private static String canonicalSymbolRef(com.mbworldwideapps.aiorchestration.modules.scanner.CodeSymbolRecord symbol) {
        String fqn = normalizeBounded(symbol.fqn(), "resolved symbol fqn", 512);
        String signature = symbol.signature();
        if (signature == null || signature.isBlank() || !signature.contains("(")) return fqn;
        return fqn + signature.substring(signature.indexOf('('));
    }

    private static String declaredSymbolRef(CaptureLocator locator) {
        // Before a baseline exists, the exact FQN + source path is the stable
        // identity. Signature formatting varies by language/parser and would
        // prevent the post-scan graph projector from binding an otherwise
        // unambiguous symbol.
        return locator.ref();
    }

    private static String captureLocatorKey(CaptureLocator locator) {
        String canonicalRef = "symbol".equals(locator.kind())
                ? locator.ref() + (locator.signature() == null || !locator.signature().contains("(")
                        ? "" : locator.signature().substring(locator.signature().indexOf('(')))
                : locator.ref();
        return locator.kind() + '\u001f' + (locator.path() == null ? "" : locator.path()) + '\u001f' + canonicalRef;
    }

    private List<Target> resolveCaptureCandidates(String projectKey, ProjectWorkspace workspace,
            List<CaptureCandidate> captures, Map<String, String> refsByLocator,
            Set<String> unresolvedSymbolPaths) {
        LinkedHashMap<String, Target> resolved = new LinkedHashMap<>();
        for (CaptureCandidate capture : captures) {
            LinkedHashMap<String, Target> candidateResolved = new LinkedHashMap<>();
            Set<String> candidateUnresolvedPaths = new LinkedHashSet<>();
            try {
                for (CaptureLocator locator : capture.locators()) {
                    String key = captureLocatorKey(locator);
                    Target target = resolved.get(key);
                    if (target == null) {
                        target = resolveCaptureLocator(projectKey, workspace, refsByLocator.get(key), locator,
                                candidateUnresolvedPaths);
                        candidateResolved.put(key, target);
                    }
                }
                resolved.putAll(candidateResolved);
                unresolvedSymbolPaths.addAll(candidateUnresolvedPaths);
            } catch (IllegalArgumentException invalidLocator) {
                // Candidate-level rejection is counted after context creation from
                // its unresolved target refs; other candidates remain independent.
            }
        }
        return List.copyOf(resolved.values());
    }

    private static String dottedMethodRef(String ref) {
        int separator = ref == null ? -1 : ref.lastIndexOf('.');
        if (separator <= 0 || separator == ref.length() - 1 || ref.indexOf('#') >= 0) return ref;
        return ref.substring(0, separator) + "#" + ref.substring(separator + 1);
    }

    private Operation reserveSemanticOperation(ResearchContext context, String sessionScopeHash, String operationKey) {
        Instant now = Instant.now();
        Operation operation = new Operation(UUID.randomUUID(), UUID.randomUUID(), context.id(),
                context.principalKey(), context.projectKey(), null, "ISSUED", null, null,
                now, now, now.plus(properties.operationRetention()));
        try {
            return transactions.execute(status -> repository.reserveSemanticOperation(operation,
                    sessionScopeHash, operationKey));
        } catch (org.springframework.dao.DataIntegrityViolationException duplicate) {
            return repository.findOperationByKey(operationKey).orElseThrow(() -> duplicate);
        }
    }

    private Map<String, Observation> registerCurrentEvidence(ResearchContext context, WorkspaceBinding binding,
            List<String> refs) {
        Map<String, Observation> latest = repository.findLatestObservations(context.id(), refs).stream()
                .collect(java.util.stream.Collectors.toMap(Observation::targetId, value -> value));
        ProjectWorkspace workspace = currentWorkspace(binding);
        for (String ref : refs) {
            Target target = target(context, ref);
            Path source = requireRegularSource(workspace, target.relativePath());
            byte[] raw = readBounded(source);
            String rawHash = sha256(raw);
            Observation existing = latest.get(ref);
            if (existing != null && rawHash.equals(existing.rawContentHash())
                    && binding.id().equals(existing.workspaceSnapshotId())) {
                if (metrics != null) metrics.evidenceReadAvoided();
                continue;
            }
            String safe = redactor.redact(decodeUtf8(raw));
            Observation registered = new Observation(UUID.randomUUID(), context.id(), ref, "source_register",
                    target.relativePath(), rawHash, sha256(safe), binding.id(), raw.length,
                    "server_observed", Instant.now());
            transactions.executeWithoutResult(status -> repository.insertObservation(registered));
            latest.put(ref, registered);
        }
        return latest;
    }

    private CompactLearnReceipt decodeCompactReceipt(Operation operation) {
        if (!"COMPLETED".equals(operation.state()) || operation.resultJson() == null) return null;
        try {
            var tree = objectMapper.readTree(operation.resultJson());
            return tree.has("created") ? objectMapper.treeToValue(tree, CompactLearnReceipt.class) : null;
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("stored compact learning receipt is invalid", exception);
        }
    }

    private static CompactLearnReceipt compactReceipt(LearnReceipt receipt, int referencesCreated,
            int referencesReused) {
        int created = 0, updated = 0, reused = 0, rejected = 0, conflicts = 0;
        for (CandidateResult result : receipt.results()) {
            switch (result.outcome()) {
                case "CREATED" -> created++;
                case "UPDATED" -> updated++;
                case "EXISTS" -> reused++;
                case "CONFLICT" -> conflicts++;
                default -> rejected++;
            }
        }
        boolean allFailed = created + updated + reused == 0 && rejected + conflicts > 0;
        return new CompactLearnReceipt(allFailed ? "REJECTED" : "ACCEPTED",
                created, updated, reused, rejected, conflicts,
                referencesCreated, referencesReused);
    }

    private static CompactLearnReceipt withPreRejected(CompactLearnReceipt receipt, int preRejected) {
        if (preRejected <= 0) return receipt;
        int rejected = Math.addExact(receipt.rejected(), preRejected);
        boolean allFailed = receipt.created() + receipt.updated() + receipt.reused() == 0
                && rejected + receipt.conflicts() > 0;
        return new CompactLearnReceipt(allFailed ? "REJECTED" : "ACCEPTED",
                receipt.created(), receipt.updated(), receipt.reused(), rejected, receipt.conflicts(),
                receipt.referencesCreated(), receipt.referencesReused());
    }

    public LearnReceipt learn(String principalKey, String projectKey, UUID contextId, UUID learningHandle,
            List<Candidate> candidates, String producerRuntime, String producerModel) {
        requireEnabled();
        if (!properties.captureEnabled()) {
            throw new IllegalStateException("LEARNING_CAPTURE_DISABLED");
        }
        ResearchContext context = requireContext(contextId, principalKey, projectKey);
        WorkspaceBinding binding = requireBinding(context.workspaceBindingId(), principalKey, projectKey);
        currentWorkspace(binding);
        List<Candidate> normalized = normalizeCandidates(candidates);
        String runtime = normalizeRuntime(producerRuntime);
        String model = normalizeOptional(producerModel, 120, "producerModel");
        String payloadHash = canonicalPayloadHash(contextId, learningHandle, normalized, runtime, model);
        Instant now = Instant.now();
        var claim = transactions.execute(status -> repository.claim(learningHandle, contextId, principalKey,
                projectKey, payloadHash, now.plus(properties.operationLease()), now));
        if (claim == null) throw new IllegalStateException("learning claim did not complete");
        if (claim.kind() == ClaimKind.CONFLICT) {
            return conflictReceipt(claim.operation().requestId(), normalized.size());
        }
        if (claim.kind() == ClaimKind.QUEUED) {
            return new LearnReceipt(1, "QUEUED", claim.operation().requestId(), List.of(), null);
        }
        if (claim.kind() == ClaimKind.REPLAY) {
            return decodeReceipt(claim.operation());
        }
        try {
            return transactions.execute(status -> process(context, binding, normalized, runtime, model,
                    claim.operation().requestId(), payloadHash));
        } catch (MemoryProjectionQueueFullException queueFull) {
            LearnReceipt receipt = new LearnReceipt(1, "RETRYABLE_ERROR", claim.operation().requestId(), List.of(), null);
            transactions.executeWithoutResult(status -> repository.retryableError(claim.operation().requestId(),
                    payloadHash, json(receipt), Instant.now()));
            throw queueFull;
        } catch (RuntimeException failure) {
            LearnReceipt receipt = new LearnReceipt(1, "RETRYABLE_ERROR", claim.operation().requestId(), List.of(), null);
            transactions.executeWithoutResult(status -> repository.retryableError(claim.operation().requestId(),
                    payloadHash, json(receipt), Instant.now()));
            return receipt;
        }
    }

    public LearnReceipt operationStatus(String principalKey, String projectKey, UUID requestId,
            UUID learningHandle) {
        requireEnabled();
        Operation operation = repository.findOperation(requestId, learningHandle)
                .orElseThrow(() -> new IllegalArgumentException("REQUEST_ID_UNKNOWN"));
        if (!principalKey.equals(operation.principalKey()) || !projectKey.equals(operation.projectKey())) {
            throw new IllegalArgumentException("OPERATION_SCOPE_DENIED");
        }
        if (!operation.expiresAt().isAfter(Instant.now())) {
            throw new IllegalArgumentException("OPERATION_EXPIRED");
        }
        if (operation.resultJson() != null) return decodeReceipt(operation);
        return new LearnReceipt(1, operation.state(), operation.requestId(), List.of(), null);
    }

    public CompactLearnReceipt operationStatus(String principalKey, String projectKey, String sessionScopeHash) {
        Operation operation = repository.findLatestUncertainOperation(principalKey, projectKey,
                requireSessionScope(sessionScopeHash), Instant.now())
                .orElseThrow(() -> new IllegalArgumentException("UNCERTAIN_OPERATION_NOT_FOUND"));
        CompactLearnReceipt compact = decodeCompactReceipt(operation);
        return compact != null ? compact : new CompactLearnReceipt(operation.state(), 0, 0, 0, 0, 0, 0, 0);
    }

    public CompactLearnReceipt operationStatus(String principalKey, String sessionScopeHash) {
        Operation operation = repository.findLatestSessionOperation(principalKey,
                requireSessionScope(sessionScopeHash), Instant.now())
                .orElseThrow(() -> new IllegalArgumentException("UNCERTAIN_OPERATION_NOT_FOUND"));
        CompactLearnReceipt compact = decodeCompactReceipt(operation);
        return compact != null ? compact : new CompactLearnReceipt(operation.state(), 0, 0, 0, 0, 0, 0, 0);
    }

    public String projectForContext(String principalKey, UUID contextId) {
        ResearchContext context = repository.findContext(contextId)
                .orElseThrow(() -> new IllegalArgumentException("CONTEXT_UNKNOWN"));
        if (!principalKey.equals(context.principalKey())) throw new IllegalArgumentException("CONTEXT_SCOPE_DENIED");
        return context.projectKey();
    }

    public String projectForOperation(String principalKey, UUID requestId, UUID learningHandle) {
        Operation operation = repository.findOperation(requestId, learningHandle)
                .orElseThrow(() -> new IllegalArgumentException("REQUEST_ID_UNKNOWN"));
        if (!principalKey.equals(operation.principalKey())) throw new IllegalArgumentException("OPERATION_SCOPE_DENIED");
        return operation.projectKey();
    }

    private LearnReceipt process(ResearchContext context, WorkspaceBinding binding, List<Candidate> candidates,
            String runtime, String model, UUID requestId, String payloadHash) {
        List<CandidateResult> results = new ArrayList<>();
        for (int index = 0; index < candidates.size(); index++) {
            Candidate candidate = candidates.get(index);
            try {
                List<Observation> candidateEvidence = requireEvidence(context, binding, candidate);
                requireFreshEvidence(binding, candidateEvidence);
                AgentLearningCanonicalizer.Identity identity = canonicalizer.identity(
                        candidate, context.targets(), candidateEvidence);
                String canonicalHash = identity.semanticIdentity();
                if (candidate.correctionTargetId() != null) {
                    results.add(correct(index, context, candidate, candidateEvidence, canonicalHash,
                            identity, runtime, model, requestId));
                    continue;
                }
                repository.lockCanonical(context.projectKey(), canonicalHash);
                repository.lockCanonical(context.projectKey(), identity.exactContentIdentity());
                var existing = repository.findCanonicalMemory(context.projectKey(), canonicalHash);
                if (existing.isPresent()) {
                    UUID memoryId = existing.orElseThrow();
                    var profile = repository.lockLearningProfile(memoryId, context.projectKey())
                            .orElseThrow(() -> new IllegalStateException("DISCOVERY_PROFILE_MISSING"));
                    if (!identity.contentRevision().equals(profile.contentRevision())) {
                        if (identity.evidenceRevision().equals(profile.evidenceRevision())) {
                            repository.refreshLearningEvidence(memoryId, profile.semanticIdentity(),
                                    identity.evidenceRevision(), candidateEvidence);
                            if (metrics != null) metrics.contentDiffersWithoutEvidenceChange();
                            results.add(result(index, "EXISTS", memoryId, true,
                                    "CONTENT_DIFFERS_EVIDENCE_UNCHANGED"));
                            continue;
                        }
                        MemoryItem revised = memoryService.correctDiscovery(memoryId, context.projectKey(),
                                createRequest(context, principalKey(context), candidate, requestId, index));
                        repository.replaceLearningMetadata(revised.id(), context.projectKey(),
                                profile.learningRevision(), profile.canonicalHash(), canonicalHash,
                                context.id(), context.principalKey(), runtime, model, "server_observed",
                                candidate, context.targets(), candidateEvidence);
                        repository.updateIdentityRevisions(revised.id(), identity.semanticIdentity(),
                                identity.contentRevision(), identity.evidenceRevision());
                        results.add(result(index, "UPDATED", revised.id(), true, null));
                    } else {
                        repository.refreshLearningEvidence(memoryId, profile.semanticIdentity(),
                                identity.evidenceRevision(), candidateEvidence);
                        results.add(result(index, "EXISTS", memoryId, true, null));
                    }
                    continue;
                }
                List<String> durableAnchors = candidate.anchors().stream()
                        .map(anchor -> target(context, anchor.targetId()).relativePath()).distinct().toList();
                Set<String> anchorSignatures = candidateAnchorSignatures(context, candidate);
                List<AgentLearningModels.DuplicateCandidate> comparable = repository
                        .findAnchorDuplicateCandidates(context.projectKey(), durableAnchors, 5).stream()
                        .filter(value -> candidate.kind().equals(value.kind()))
                        .filter(value -> anchorSignatures.equals(Set.copyOf(value.anchorSignatures())))
                        .toList();
                String normalizedContent = MemoryTextPreview.normalize(candidate.content());
                var nearDuplicate = comparable.stream()
                        .filter(value -> normalizedContent.equals(MemoryTextPreview.normalize(value.text())))
                        .findFirst()
                        .or(() -> comparable.stream()
                                .filter(value -> strongSemanticOverlap(candidate.content(), value.text()))
                                .findFirst());
                if (nearDuplicate.isPresent()) {
                    UUID memoryId = nearDuplicate.orElseThrow().memoryId();
                    var profile = repository.lockLearningProfile(memoryId, context.projectKey())
                            .orElseThrow(() -> new IllegalStateException("DISCOVERY_PROFILE_MISSING"));
                    repository.refreshLearningEvidence(memoryId, profile.semanticIdentity(),
                            identity.evidenceRevision(), candidateEvidence);
                    results.add(result(index, "EXISTS", memoryId, true, null));
                    continue;
                }
                MemoryItem created = memoryService.createDiscovery(createRequest(context, principalKey(context),
                        candidate, requestId, index));
                repository.insertLearningMetadata(created.id(), context.projectKey(), canonicalHash, context.id(),
                        context.principalKey(), runtime, model, "server_observed", candidate, context.targets(),
                        candidateEvidence);
                repository.updateIdentityRevisions(created.id(), identity.semanticIdentity(),
                        identity.contentRevision(), identity.evidenceRevision());
                results.add(result(index, "CREATED", created.id(), true, null));
            } catch (MemoryPolicyViolationException rejected) {
                results.add(result(index, "NOT_SAVED", null, false, safePolicyReason(rejected)));
            } catch (CorrectionConflictException conflict) {
                results.add(result(index, "CONFLICT", candidate.correctionTargetId(), false,
                        safeReason(conflict)));
            } catch (IllegalArgumentException invalid) {
                results.add(result(index, "NOT_SAVED", null, false, safeReason(invalid)));
            }
        }
        Instant now = Instant.now();
        UUID nextHandle = UUID.randomUUID();
        repository.insertOperation(new Operation(UUID.randomUUID(), nextHandle, context.id(),
                context.principalKey(), context.projectKey(), null, "ISSUED", null, null,
                now, now, now.plus(properties.operationRetention())));
        LearnReceipt receipt = new LearnReceipt(1, "COMPLETED", requestId, results, nextHandle);
        repository.complete(requestId, payloadHash, json(receipt), Instant.now());
        return receipt;
    }

    private CandidateResult correct(int index, ResearchContext context, Candidate candidate,
            List<Observation> evidence, String canonicalHash, AgentLearningCanonicalizer.Identity identity,
            String runtime, String model,
            UUID requestId) {
        var profile = repository.lockLearningProfile(candidate.correctionTargetId(), context.projectKey())
                .orElseThrow(() -> new CorrectionConflictException("CORRECTION_TARGET_UNKNOWN"));
        if (profile.learningRevision() != candidate.expectedLearningRevision()
                || !profile.canonicalHash().equals(candidate.expectedCanonicalHash())) {
            throw new CorrectionConflictException("CORRECTION_EXPECTATION_MISMATCH");
        }
        repository.lockCanonical(context.projectKey(), canonicalHash);
        var canonicalOwner = repository.findCanonicalMemory(context.projectKey(), canonicalHash);
        if (canonicalOwner.isPresent() && !candidate.correctionTargetId().equals(canonicalOwner.orElseThrow())) {
            throw new CorrectionConflictException("CORRECTION_CANONICAL_CONFLICT");
        }
        MemoryItem corrected = memoryService.correctDiscovery(candidate.correctionTargetId(), context.projectKey(),
                createRequest(context, principalKey(context), candidate, requestId, index));
        repository.replaceLearningMetadata(corrected.id(), context.projectKey(),
                candidate.expectedLearningRevision(), candidate.expectedCanonicalHash(), canonicalHash,
                context.id(), context.principalKey(), runtime, model, "server_observed",
                candidate, context.targets(), evidence);
        repository.updateIdentityRevisions(corrected.id(), identity.semanticIdentity(),
                identity.contentRevision(), identity.evidenceRevision());
        return result(index, "UPDATED", corrected.id(), true, null);
    }

    private CreateMemoryRequest createRequest(ResearchContext context, String owner, Candidate candidate,
            UUID requestId, int candidateIndex) {
        List<MemoryCodeLocator> locators = candidate.anchors().stream().map(anchor -> {
            Target target = target(context, anchor.targetId());
            MemoryCodeLocatorKind kind = switch (target.locatorKind()) {
                case "symbol" -> MemoryCodeLocatorKind.SYMBOL;
                case "directory" -> MemoryCodeLocatorKind.DIRECTORY;
                default -> MemoryCodeLocatorKind.FILE;
            };
            String ref = kind == MemoryCodeLocatorKind.SYMBOL ? target.symbolRef() : target.relativePath();
            String path = kind == MemoryCodeLocatorKind.SYMBOL ? target.relativePath() : null;
            return new MemoryCodeLocator(kind, ref, null,
                    MemoryCodeLocatorRelationship.MENTIONS, path, anchor.role());
        }).toList();
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("learningSchemaVersion", 1);
        metadata.put("discoveryKind", candidate.kind());
        metadata.put("appliesWhen", candidate.appliesWhen());
        metadata.put("limitations", candidate.limitations());
        metadata.put("reusableFor", candidate.reusableFor());
        metadata = MemoryCodeLocatorMetadata.replace(metadata, locators, MemoryScope.PROJECT, context.projectKey());
        return new CreateMemoryRequest(MemoryScope.PROJECT, context.projectKey(), MemoryType.DISCOVERY,
                candidate.summary(), candidate.content(), List.of("discovery", candidate.kind()), 0.9,
                MemoryStatus.ACTIVE, MemorySourceType.MCP_EXTERNAL,
                "learning:" + requestId + ":" + candidateIndex, owner, metadata, Instant.now(), null);
    }

    private List<Observation> requireEvidence(ResearchContext context, WorkspaceBinding binding,
            Candidate candidate) {
        List<UUID> unique = candidate.evidenceIds().stream().distinct().toList();
        List<Observation> found = repository.findObservations(context.id(), unique);
        if (found.size() != unique.size()) throw new IllegalArgumentException("EVIDENCE_SCOPE_DENIED");
        if (found.stream().anyMatch(value -> !binding.id().equals(value.workspaceSnapshotId()))) {
            throw new IllegalArgumentException("EVIDENCE_WORKSPACE_MISMATCH");
        }
        Set<String> observedTargets = found.stream().map(Observation::targetId).collect(java.util.stream.Collectors.toSet());
        if (candidate.anchors().stream()
                .filter(anchor -> !"directory".equals(target(context, anchor.targetId()).locatorKind()))
                .anyMatch(anchor -> !observedTargets.contains(anchor.targetId()))) {
            throw new IllegalArgumentException("EVIDENCE_INSUFFICIENT");
        }
        return found;
    }

    private void requireFreshEvidence(WorkspaceBinding binding, List<Observation> evidence) {
        ProjectWorkspace workspace = currentWorkspace(binding);
        for (Observation item : evidence) {
            Path path = requireRegularSource(workspace, item.resourceRef());
            if (!sha256(readBounded(path)).equals(item.rawContentHash())) {
                throw new IllegalArgumentException("STALE_EVIDENCE");
            }
        }
    }

    private WorkspaceBinding requireBinding(UUID id, String principalKey, String projectKey) {
        if (id == null) throw new IllegalArgumentException("workspaceBindingId is required");
        WorkspaceBinding binding = repository.findBinding(id)
                .orElseThrow(() -> new IllegalArgumentException("WORKSPACE_BINDING_UNKNOWN"));
        if (!require(principalKey, "principalKey").equals(binding.principalKey())
                || !require(projectKey, "projectKey").equals(binding.projectKey())) {
            throw new IllegalArgumentException("WORKSPACE_BINDING_SCOPE_DENIED");
        }
        if (!binding.expiresAt().isAfter(Instant.now())) {
            throw new IllegalArgumentException("WORKSPACE_BINDING_EXPIRED");
        }
        return binding;
    }

    private ProjectWorkspace currentWorkspace(WorkspaceBinding binding) {
        ProjectWorkspace workspace = workspaceResolver.resolve(binding.projectKey(), binding.repositoryFingerprint());
        if (!workspace.repositoryRoot().equals(binding.rootPath())) {
            throw new IllegalArgumentException("WORKSPACE_BINDING_STALE");
        }
        return workspace;
    }

    private ResearchContext requireContext(UUID id, String principalKey, String projectKey) {
        if (id == null) throw new IllegalArgumentException("contextId is required");
        ResearchContext context = repository.findContext(id)
                .orElseThrow(() -> new IllegalArgumentException("CONTEXT_UNKNOWN"));
        if (!principalKey.equals(context.principalKey()) || !projectKey.equals(context.projectKey())) {
            throw new IllegalArgumentException("CONTEXT_SCOPE_DENIED");
        }
        if (!context.expiresAt().isAfter(Instant.now())) throw new IllegalArgumentException("CONTEXT_EXPIRED");
        return context;
    }

    private List<Candidate> normalizeCandidates(List<Candidate> candidates) {
        if (candidates == null || candidates.isEmpty() || candidates.size() > properties.maxCandidatesPerWrite()) {
            throw new IllegalArgumentException("candidates must contain 1 to " + properties.maxCandidatesPerWrite() + " items");
        }
        List<Candidate> result = new ArrayList<>();
        for (Candidate candidate : candidates) {
            if (candidate == null) throw new IllegalArgumentException("candidate must not be null");
            String kind = normalizeEnum(candidate.kind(), KINDS, "kind");
            String summary = redactBounded(candidate.summary(), "summary", 160);
            String content = redactBounded(candidate.content(), "content", 700);
            List<String> applies = normalizeShortList(candidate.appliesWhen(), "appliesWhen");
            List<String> limitations = normalizeShortList(candidate.limitations(), "limitations");
            List<String> reusable = normalizeShortList(candidate.reusableFor(), "reusableFor");
            if (candidate.anchors().isEmpty() || candidate.anchors().size() > 8) {
                throw new IllegalArgumentException("anchors must contain 1 to 8 items");
            }
            List<Anchor> anchors = candidate.anchors().stream().map(anchor -> {
                if (anchor == null) throw new IllegalArgumentException("anchor must not be null");
                return new Anchor(normalizeBounded(anchor.targetId(), "targetId", 32),
                        normalizeEnum(anchor.role(), ANCHOR_ROLES, "anchor role"));
            }).distinct().toList();
            if (candidate.evidenceIds().size() > properties.maxEvidencePerCandidate()) {
                throw new IllegalArgumentException("evidenceIds must contain at most "
                        + properties.maxEvidencePerCandidate() + " items");
            }
            if (candidate.evidenceIds().isEmpty()) {
                throw new IllegalArgumentException("evidenceIds must not be empty");
            }
            boolean anyCorrection = candidate.correctionTargetId() != null
                    || candidate.expectedLearningRevision() != null
                    || candidate.expectedCanonicalHash() != null;
            boolean completeCorrection = candidate.correctionTargetId() != null
                    && candidate.expectedLearningRevision() != null
                    && candidate.expectedCanonicalHash() != null;
            if (anyCorrection && !completeCorrection) {
                throw new IllegalArgumentException("correction fields must be provided together");
            }
            Long expectedRevision = candidate.expectedLearningRevision();
            String expectedHash = null;
            if (completeCorrection) {
                if (expectedRevision <= 0) {
                    throw new IllegalArgumentException("expectedLearningRevision must be positive");
                }
                expectedHash = candidate.expectedCanonicalHash().trim().toLowerCase(Locale.ROOT);
                if (!expectedHash.matches("[0-9a-f]{64}")) {
                    throw new IllegalArgumentException("expectedCanonicalHash must be a SHA-256 hex value");
                }
            }
            Candidate normalized = new Candidate(kind, summary, content, applies, limitations, anchors,
                    candidate.evidenceIds().stream().distinct().toList(), reusable,
                    candidate.correctionTargetId(), expectedRevision, expectedHash);
            result.add(normalized);
        }
        return List.copyOf(result);
    }

    private List<String> normalizeShortList(List<String> input, String field) {
        if (input == null) return List.of();
        if (input.size() > 6) throw new IllegalArgumentException(field + " must contain at most 6 items");
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : input) normalized.add(redactBounded(value, field, 160));
        return List.copyOf(normalized);
    }

    private String canonicalPayloadHash(UUID contextId, UUID learningHandle, List<Candidate> candidates,
            String runtime, String model) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("schemaVersion", 1);
        value.put("contextId", contextId.toString());
        value.put("learningHandle", learningHandle.toString());
        value.put("candidates", candidates);
        value.put("producerRuntime", runtime);
        value.put("producerModel", model == null ? "" : model);
        return sha256(canonicalJson(value));
    }

    private String canonicalCandidateHash(Candidate candidate, List<Target> targets,
            List<Observation> evidence) {
        return canonicalizer.identity(candidate, targets, evidence).semanticIdentity();
    }

    private String canonicalJson(Object value) {
        try {
            return canonicalObjectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("INVALID_INPUT", exception);
        }
    }

    private LearnReceipt decodeReceipt(Operation operation) {
        if (operation.resultJson() == null) {
            return new LearnReceipt(1, operation.state(), operation.requestId(), List.of(), null);
        }
        try {
            return objectMapper.readValue(operation.resultJson(), LearnReceipt.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("stored learning receipt is invalid", exception);
        }
    }

    private String json(Object receipt) {
        try {
            return objectMapper.writeValueAsString(receipt);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("learning receipt serialization failed", exception);
        }
    }

    private List<String> normalizePaths(List<String> values) {
        if (values == null) return List.of();
        if (values.size() > MAX_CONTEXT_TARGETS) throw new IllegalArgumentException("path hints must contain at most 8 items");
        LinkedHashSet<String> paths = new LinkedHashSet<>();
        for (String value : values) {
            String path = normalizeBounded(value, "path hint", 512).replace('\\', '/');
            if (path.startsWith("/") || path.startsWith("./") || path.contains("../") || path.contains(":")) {
                throw new IllegalArgumentException("path hint must be a canonical repository-relative path");
            }
            paths.add(path);
        }
        return List.copyOf(paths);
    }

    private static List<String> normalizeSymbolHints(List<String> values) {
        if (values == null) return List.of();
        if (values.size() > MAX_CONTEXT_TARGETS) throw new IllegalArgumentException("symbol hints must contain at most 8 items");
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : values) normalized.add(normalizeBounded(value, "symbol hint", 512));
        return List.copyOf(normalized);
    }

    private static List<String> normalizeTermHints(List<String> values) {
        if (values == null) return List.of();
        if (values.size() > MAX_CONTEXT_TARGETS) {
            throw new IllegalArgumentException("term hints must contain at most 8 items");
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : values) normalized.add(normalizeBounded(value, "term hint", 160));
        return List.copyOf(normalized);
    }

    private static List<Knowledge> applyKnowledgeFreshness(List<Knowledge> knowledge, List<TargetSeed> targets,
            List<com.mbworldwideapps.aiorchestration.modules.agentlearning.AgentLearningModels.SuggestedReference> references,
            Set<UUID> missingMemories) {
        if (knowledge.isEmpty()) return List.of();
        return knowledge.stream().map(item -> {
            if ("STALE".equals(item.freshness())) return item;
            List<String> checks = targets.stream()
                    .filter(target -> item.id().equals(target.sourceMemoryId()))
                    .map(TargetSeed::sourceCheck)
                    .toList();
            List<String> referenceChecks = references.stream()
                    .filter(reference -> item.id().equals(reference.sourceMemoryId()))
                    .map(com.mbworldwideapps.aiorchestration.modules.agentlearning.AgentLearningModels.SuggestedReference::sourceCheck)
                    .toList();
            if (!referenceChecks.isEmpty()) {
                checks = java.util.stream.Stream.concat(checks.stream(), referenceChecks.stream()).toList();
            }
            String freshness = missingMemories.contains(item.id()) ? "MISSING"
                    : checks.isEmpty() ? "UNKNOWN"
                    : checks.contains("MISSING") ? "MISSING"
                    : checks.contains("CHANGED") ? "CHANGED"
                    : checks.contains("UNKNOWN") ? "UNKNOWN" : "UNCHANGED";
            return new Knowledge(item.id(), item.type(), item.text(), item.contentComplete(),
                    item.verification(), freshness, item.learningRevision(), item.canonicalHash());
        }).toList();
    }

    private static String routeStatus(boolean unavailable, List<Knowledge> knowledge,
            List<Target> targets, List<ReferenceTarget> references, List<String> gaps) {
        if (unavailable && knowledge.isEmpty() && targets.isEmpty() && references.isEmpty()) return "UNAVAILABLE";
        if (knowledge.stream().anyMatch(item -> Set.of("STALE", "CHANGED", "MISSING").contains(item.freshness()))) {
            return "STALE";
        }
        if (knowledge.isEmpty() && targets.isEmpty() && references.isEmpty()) return "MISS";
        if (!gaps.isEmpty()) return "PARTIAL";
        return "FOCUSED";
    }

    private static String normalizeIncludeReferences(String value) {
        if (value == null || value.isBlank()) return "when_needed";
        return normalizeEnum(value, Set.of("never", "when_needed", "always"), "includeReferences");
    }

    private List<String> normalizeTargetIds(List<String> values) {
        if (values == null || values.isEmpty() || values.size() > properties.maxSourceTargetsPerOpen()) {
            throw new IllegalArgumentException("targetIds must contain 1 to " + properties.maxSourceTargetsPerOpen() + " items");
        }
        List<String> ids = values.stream().map(value -> normalizeBounded(value, "targetId", 32)).distinct().toList();
        if (ids.size() != values.size()) throw new IllegalArgumentException("targetIds must be unique");
        return ids;
    }

    private Path requireRegularSource(ProjectWorkspace workspace, String relativePath) {
        Path path = workspace.resolveInside(relativePath);
        try {
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
                throw new IllegalArgumentException("SOURCE_NOT_REGULAR_FILE");
            }
            if (Files.size(path) > properties.maxSourceFileBytes()) {
                throw new IllegalArgumentException("SOURCE_TOO_LARGE");
            }
            return path;
        } catch (IOException exception) {
            throw new IllegalArgumentException("SOURCE_UNAVAILABLE", exception);
        }
    }

    private Path requireDirectory(ProjectWorkspace workspace, String relativePath) {
        Path path = workspace.resolveInside(relativePath);
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
            throw new IllegalArgumentException("DIRECTORY_NOT_FOUND");
        }
        return path;
    }

    private byte[] readBounded(Path path) {
        try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(properties.maxSourceFileBytes() + 1);
            if (bytes.length > properties.maxSourceFileBytes()) throw new IllegalArgumentException("SOURCE_TOO_LARGE");
            return bytes;
        } catch (IOException exception) {
            throw new IllegalArgumentException("SOURCE_UNAVAILABLE", exception);
        }
    }

    private static String decodeUtf8(byte[] value) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(value)).toString();
        } catch (java.nio.charset.CharacterCodingException exception) {
            throw new IllegalArgumentException("SOURCE_NOT_UTF8", exception);
        }
    }

    private static SourceWindow sourceWindow(String safeText, Target target, int maxChars) {
        String[] lines = safeText.split("\\R", -1);
        int requestedStart = target.startLine() == null ? 1 : Math.max(1, target.startLine() - 10);
        int requestedEnd = target.endLine() == null ? lines.length : Math.min(lines.length, target.endLine() + 10);
        if (requestedStart > lines.length) {
            throw new IllegalArgumentException("TARGET_LINE_RANGE_STALE");
        }
        String selected = String.join("\n", java.util.Arrays.copyOfRange(lines, requestedStart - 1, requestedEnd));
        String content = MemoryTextPreview.truncate(selected, maxChars);
        int visibleLines = Math.max(1, content.split("\\R", -1).length);
        int actualEnd = Math.min(requestedEnd, requestedStart + visibleLines - 1);
        boolean complete = requestedStart == 1 && requestedEnd == lines.length
                && MemoryTextPreview.normalize(selected).length() <= maxChars;
        return new SourceWindow(requestedStart, actualEnd, content, complete);
    }

    private String redactBounded(String value, String field, int maxChars) {
        String redacted = redactor.redact(normalizeBounded(value, field, maxChars));
        if (redacted.isBlank()) throw new IllegalArgumentException(field + " must not be blank after redaction");
        return redacted;
    }

    private static String combinedText(Candidate candidate) {
        StringBuilder value = new StringBuilder(candidate.content());
        if (!candidate.appliesWhen().isEmpty()) value.append(" Applies when: ").append(String.join("; ", candidate.appliesWhen())).append('.');
        if (!candidate.limitations().isEmpty()) value.append(" Limitations: ").append(String.join("; ", candidate.limitations())).append('.');
        return MemoryTextPreview.normalize(value.toString());
    }

    private static CandidateResult result(int index, String outcome, UUID memoryId, boolean persisted, String reason) {
        return new CandidateResult(index, outcome, memoryId, persisted,
                persisted ? "SUPPORTED" : "UNVERIFIED", "PENDING", "PENDING", reason);
    }

    private static LearnReceipt conflictReceipt(UUID requestId, int candidates) {
        List<CandidateResult> results = java.util.stream.IntStream.range(0, candidates)
                .mapToObj(index -> result(index, "CONFLICT", null, false, "HANDLE_PAYLOAD_CONFLICT")).toList();
        return new LearnReceipt(1, "COMPLETED", requestId, results, null);
    }

    private static Target target(ResearchContext context, String targetId) {
        return target(context.targets(), targetId);
    }

    private static Target target(List<Target> targets, String targetId) {
        return targets.stream().filter(item -> item.targetId().equals(targetId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("ANCHOR_TARGET_UNKNOWN"));
    }

    private static String principalKey(ResearchContext context) {
        return context.principalKey();
    }

    private static String safeReason(IllegalArgumentException failure) {
        String value = failure.getMessage();
        return value == null || value.isBlank() ? "INVALID_INPUT" : MemoryTextPreview.truncate(value, 120);
    }

    private static final class CorrectionConflictException extends IllegalArgumentException {
        private CorrectionConflictException(String message) {
            super(message);
        }
    }

    private static String safePolicyReason(MemoryPolicyViolationException failure) {
        String value = failure.reason();
        return value == null || value.isBlank() ? "POLICY_REJECTED" : MemoryTextPreview.truncate(value, 120);
    }

    private static String normalizeIntent(String value) {
        if (value == null || value.isBlank()) return "locate";
        return normalizeEnum(value, INTENTS, "intent");
    }

    private static String normalizeRuntime(String value) {
        if (value == null || value.isBlank()) return "unknown";
        String normalized = value.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        return Set.of("copilot", "claude_code", "codex").contains(normalized) ? normalized : "unknown";
    }

    private static String normalizeEnum(String value, Set<String> allowed, String field) {
        String normalized = normalizeBounded(value, field, 80).toLowerCase(Locale.ROOT);
        if (!allowed.contains(normalized)) throw new IllegalArgumentException("unsupported " + field + ": " + value);
        return normalized;
    }

    private static String normalizeOptional(String value, int maxChars, String field) {
        return value == null || value.isBlank() ? null : normalizeBounded(value, field, maxChars);
    }

    private static String normalizeBounded(String value, String field, int maxChars) {
        String normalized = MemoryTextPreview.normalize(value);
        if (normalized.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        if (normalized.length() > maxChars || normalized.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(field + " exceeds its safe bound");
        }
        return normalized;
    }

    private static String require(String value, String field) {
        return normalizeBounded(value, field, 512);
    }

    private static String requireSessionScope(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("SESSION_SCOPE_REQUIRED");
        }
        return value;
    }

    private static String legacySessionScope(String clientId) {
        return sha256((clientId == null ? "unknown" : clientId) + ":legacy");
    }

    private static String normalizeAnchorRole(String role) {
        if (role == null || !ANCHOR_ROLES.contains(role)) return "supporting";
        return role;
    }

    private static boolean strongSemanticOverlap(String left, String right) {
        Set<String> a = lexicalTerms(left);
        Set<String> b = lexicalTerms(right);
        if (a.isEmpty() || b.isEmpty()) return false;
        long intersection = a.stream().filter(b::contains).count();
        int union = a.size() + b.size() - (int) intersection;
        return union > 0 && (double) intersection / union >= 0.82d;
    }

    private Set<String> candidateAnchorSignatures(ResearchContext context, Candidate candidate) {
        return candidate.anchors().stream().map(anchor -> {
            Target selected = target(context, anchor.targetId());
            return selected.locatorKind() + '\u001f' + selected.relativePath() + '\u001f'
                    + (selected.symbolRef() == null ? "" : selected.symbolRef()) + '\u001f'
                    + anchor.role();
        }).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static Set<String> lexicalTerms(String value) {
        if (value == null) return Set.of();
        return java.util.Arrays.stream(value.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}_]+"))
                .filter(term -> term.length() >= 3).collect(java.util.stream.Collectors.toSet());
    }

    private static String sha256(String value) {
        return sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private record SourceWindow(int startLine, int endLine, String content, boolean complete) {
    }

    private record TargetSeed(String relativePath, String symbolRef, String role, String currentHash,
            String sourceCheck, UUID sourceMemoryId, Integer startLine, Integer endLine) {
    }

}
