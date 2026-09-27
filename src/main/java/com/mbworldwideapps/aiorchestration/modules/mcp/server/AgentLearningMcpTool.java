package com.mbworldwideapps.aiorchestration.modules.mcp.server;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mbworldwideapps.aiorchestration.modules.agentlearning.AgentLearningModels.Candidate;
import com.mbworldwideapps.aiorchestration.modules.agentlearning.AgentLearningModels.CompactLearnReceipt;
import com.mbworldwideapps.aiorchestration.modules.agentlearning.AgentLearningModels.ContextReservation;
import com.mbworldwideapps.aiorchestration.modules.agentlearning.AgentLearningModels.LearnReceipt;
import com.mbworldwideapps.aiorchestration.modules.agentlearning.AgentLearningModels.Knowledge;
import com.mbworldwideapps.aiorchestration.modules.agentlearning.AgentLearningModels.LearningBatch;
import com.mbworldwideapps.aiorchestration.modules.agentlearning.AgentLearningModels.LearningCaptureCommand;
import com.mbworldwideapps.aiorchestration.modules.agentlearning.AgentLearningModels.OpenedContext;
import com.mbworldwideapps.aiorchestration.modules.agentlearning.AgentLearningModels.OpenedSource;
import com.mbworldwideapps.aiorchestration.modules.agentlearning.AgentLearningModels.OpenedReference;
import com.mbworldwideapps.aiorchestration.modules.agentlearning.AgentLearningModels.Target;
import com.mbworldwideapps.aiorchestration.modules.agentlearning.AgentLearningService;
import com.mbworldwideapps.aiorchestration.modules.agentlearning.LearningOrchestrator;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryProjectionQueueFullException;
import com.mbworldwideapps.aiorchestration.modules.llmgateway.TokenEstimator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;

@Component
@ConditionalOnProperty(prefix = "ai-orchestration.mcp", name = "enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnExpression("'${ai-orchestration.learning.enabled:false}' == 'true'")
public class AgentLearningMcpTool {

    private final AgentLearningService service;
    private final LearningOrchestrator orchestrator;
    private final McpAuditLogger audit;
    private final ObjectMapper objectMapper;

    public AgentLearningMcpTool(AgentLearningService service, McpAuditLogger audit) {
        this(service, new LearningOrchestrator(service), audit, new ObjectMapper());
    }

    public AgentLearningMcpTool(AgentLearningService service, McpAuditLogger audit, ObjectMapper objectMapper) {
        this(service, new LearningOrchestrator(service), audit, objectMapper);
    }

    @Autowired
    public AgentLearningMcpTool(AgentLearningService service, LearningOrchestrator orchestrator,
            McpAuditLogger audit, ObjectMapper objectMapper) {
        this.service = service;
        this.orchestrator = orchestrator;
        this.audit = audit;
        this.objectMapper = objectMapper;
    }

    public record ContextHints(List<String> symbols, List<String> paths, List<String> terms) {
        public ContextHints {
            symbols = symbols == null ? List.of() : List.copyOf(symbols);
            paths = paths == null ? List.of() : List.copyOf(paths);
            terms = terms == null ? List.of() : List.copyOf(terms);
        }
    }

    public record ContextTarget(String targetId, String symbol, String path, List<Integer> lines,
            String role, String sourceCheck) {
        public ContextTarget {
            lines = lines == null ? List.of() : List.copyOf(lines);
        }
    }

    public record Omitted(int targets, int knowledge) {
    }

    public record ContextReference(String targetId, UUID referenceId, String path, String sectionKey,
            UUID sourceMemoryId, String sourceCheck) {
    }

    public record ContextKnowledge(UUID id, String type, String text, boolean contentComplete,
            String verification, String freshness, Long learningRevision, String canonicalHash) {
    }

    public record ContextResolveResponse(int schemaVersion, String status, UUID contextId,
            UUID workspaceBindingId, UUID learningHandle, List<ContextKnowledge> knowledge, List<ContextTarget> targets,
            List<ContextReference> references, List<String> gaps, Omitted omitted) {
        public ContextResolveResponse {
            knowledge = knowledge == null ? List.of() : List.copyOf(knowledge);
            targets = targets == null ? List.of() : List.copyOf(targets);
            references = references == null ? List.of() : List.copyOf(references);
            gaps = gaps == null ? List.of() : List.copyOf(gaps);
        }
    }

    public record ContextOpenedItem(String targetId, UUID evidenceId, String path, List<Integer> lines,
            String content, boolean contentComplete, boolean changedSinceResolve) {
        public ContextOpenedItem {
            lines = lines == null ? List.of() : List.copyOf(lines);
        }
    }

    public record ContextOpenedReference(String targetId, UUID referenceId, String path, String sectionKey,
            String title, String content, boolean contentComplete, boolean changedSinceResolve, String visibleHash) {
    }

    public record ContextOpenResponse(int schemaVersion, String status, UUID contextId,
            List<ContextOpenedItem> items, List<ContextOpenedReference> references) {
        public ContextOpenResponse {
            items = items == null ? List.of() : List.copyOf(items);
            references = references == null ? List.of() : List.copyOf(references);
        }
    }

    public record CompactKnowledge(String ref, String summary, String verification, String freshness,
            String content, boolean hasReference, String referenceHint) {
    }

    public record CompactTarget(String ref, String display, String path, List<Integer> lines,
            String role, String sourceCheck) {
        public CompactTarget {
            lines = lines == null ? List.of() : List.copyOf(lines);
        }
    }

    public record CompactResolveResponse(int schemaVersion, String status, List<CompactKnowledge> knowledge,
            List<CompactTarget> targets, List<String> gaps, Omitted omitted) {
        public CompactResolveResponse {
            knowledge = knowledge == null ? List.of() : List.copyOf(knowledge);
            targets = targets == null ? List.of() : List.copyOf(targets);
            gaps = gaps == null ? List.of() : List.copyOf(gaps);
        }
    }

    public record CompactOpenedItem(String ref, String path, List<Integer> lines, String content,
            boolean contentComplete, boolean changedSinceResolve) {
        public CompactOpenedItem {
            lines = lines == null ? List.of() : List.copyOf(lines);
        }
    }

    public record CompactOpenedReference(String ref, String title, String content,
            boolean contentComplete, boolean changedSinceResolve) {
    }

    public record CompactOpenResponse(int schemaVersion, String status, List<CompactOpenedItem> items,
            List<CompactOpenedReference> references) {
        public CompactOpenResponse {
            items = items == null ? List.of() : List.copyOf(items);
            references = references == null ? List.of() : List.copyOf(references);
        }
    }

    /** Compatibility-only Java adapter; automatic prefetch replaces model-invoked context resolution. */
    public CompactResolveResponse resolveV2(
            @McpToolParam(description = "Project key returned by scanner.project.resolve") String projectKey,
            @McpToolParam(description = "Opaque workspaceBindingId returned by scanner.project.resolve") String workspaceBindingId,
            @McpToolParam(description = "Current task or question, max 8192 characters") String query,
            @McpToolParam(description = "locate, explain, change, debug, or recall", required = false) String intent,
            @McpToolParam(description = "Optional exact symbol/path/term hints", required = false) ContextHints hints,
            @McpToolParam(description = "Final compact response budget", required = false) Integer maxTokens,
            @McpToolParam(description = "never, when_needed, or always", required = false) String includeReferences) {
        McpClientContext context = McpClientContextHolder.require();
        Instant started = Instant.now();
        String effectiveProject = context.projectKey();
        try {
            requireScope(context, "memory.read");
            requireScope(context, "codebase.read");
            requireScope(context, "rules.read");
            String sessionScope = requireSessionScope(context);
            effectiveProject = McpProjectKeys.effective(context, projectKey);
            ContextHints safeHints = hints == null ? new ContextHints(List.of(), List.of(), List.of()) : hints;
            validateHints(safeHints);
            String safeReferenceMode = McpProjectKeys.isLocalTrust(context) ? includeReferences : "never";
            ContextReservation reservation = service.resolveContext(principal(context), context.clientId(),
                    sessionScope, effectiveProject, uuid(workspaceBindingId, "workspaceBindingId"),
                    query, intent, safeHints.paths(), safeHints.symbols(), safeHints.terms(), maxTokens,
                    safeReferenceMode);
            List<CompactKnowledge> knowledge = new java.util.ArrayList<>();
            for (int index = 0; index < reservation.knowledge().size(); index++) {
                Knowledge item = reservation.knowledge().get(index);
                String summary = summarize(item.text());
                boolean hasReference = reservation.context().references().stream()
                        .anyMatch(reference -> item.id() != null && item.id().equals(reference.sourceMemoryId()));
                knowledge.add(new CompactKnowledge("k" + (index + 1), summary, item.verification(),
                        item.freshness(), item.text().equals(summary) ? null : item.text(), hasReference,
                        hasReference ? "Open the linked AI Reference only if deeper detail is required" : null));
            }
            List<CompactTarget> targets = reservation.context().targets().stream()
                    .map(target -> new CompactTarget(target.targetId(),
                            target.symbolRef() == null ? target.relativePath() : target.symbolRef(),
                            target.relativePath(), target.startLine() == null
                                    ? List.of() : List.of(target.startLine(), target.endLine()), target.role(),
                            reservation.sourceChecks().getOrDefault(target.targetId(), "UNKNOWN")))
                    .toList();
            CompactResolveResponse response = new CompactResolveResponse(2, reservation.status(), knowledge,
                    targets, reservation.gaps(), new Omitted(reservation.omittedTargets(),
                            reservation.omittedKnowledge()));
            response = fitSerializedBudget(response, maxTokens == null ? 1200 : maxTokens);
            audit.log(context, "context.resolve", McpAuditLogger.hashQuery(query), targets.size(),
                    latency(started), "success", Map.of("projectKey", effectiveProject, "contract", "v2"), null);
            return response;
        } catch (RuntimeException failure) {
            audit.log(context, "context.resolve", McpAuditLogger.hashQuery(query), 0, latency(started), "error",
                    Map.of("projectKey", effectiveProject), failure.getClass().getSimpleName());
            throw failure;
        }
    }

    /** Compatibility-only Java adapter; automatic prefetch replaces model-invoked context opening. */
    public CompactOpenResponse openV2(
            @McpToolParam(description = "One to three tN source refs or kN knowledge refs with a linked reference") List<String> refs,
            @McpToolParam(description = "Total response budget", required = false) Integer maxTokens) {
        McpClientContext context = McpClientContextHolder.require();
        Instant started = Instant.now();
        String project = context.projectKey();
        List<String> auditRefs = refs == null ? List.of() : refs;
        try {
            requireScope(context, "codebase.read");
            String sessionScope = requireSessionScope(context);
            project = service.projectForActiveContext(principal(context), context.clientId(),
                    sessionScope);
            OpenedContext opened = service.openActiveContext(principal(context), context.clientId(),
                    sessionScope, project, refs, maxTokens);
            CompactOpenResponse response = new CompactOpenResponse(2, "READ", opened.items().stream()
                    .map(item -> new CompactOpenedItem(item.targetId(), item.path(),
                            List.of(item.startLine(), item.endLine()), item.content(), item.contentComplete(),
                            item.changedSinceResolve())).toList(), opened.references().stream()
                    .map(item -> new CompactOpenedReference(item.targetId(), item.title(), item.content(),
                            item.contentComplete(), item.changedSinceResolve())).toList());
            audit.log(context, "context.open", McpAuditLogger.hashQuery(String.join(",", auditRefs)),
                    response.items().size() + response.references().size(), latency(started), "success",
                    Map.of("projectKey", project, "contract", "v2"), null);
            return response;
        } catch (RuntimeException failure) {
            audit.log(context, "context.open", McpAuditLogger.hashQuery(String.join(",", auditRefs)), 0,
                    latency(started), "error", Map.of("projectKey", project),
                    failure.getClass().getSimpleName());
            throw failure;
        }
    }

    /** Internal compatibility adapter. Agent-driven learning is absent from the MCP catalog. */
    public CompactLearnReceipt learnV2(
            LearningBatch batch) {
        McpClientContext context = McpClientContextHolder.require();
        Instant started = Instant.now();
        String project = context.projectKey();
        int candidates = batch == null ? 0 : batch.learnings().size();
        try {
            requireScope(context, "memory.write");
            requireScope(context, "codebase.read");
            String sessionScope = requireSessionScope(context);
            project = service.projectForActiveContext(principal(context), context.clientId(),
                    sessionScope);
            CompactLearnReceipt receipt = orchestrator.learn(principal(context), context.clientId(),
                    sessionScope, project, batch, runtime(context.clientId()), null);
            audit.log(context, "memory.learn", McpAuditLogger.hashQuery("batch:" + candidates), candidates,
                    latency(started), "success", Map.of("projectKey", project, "contract", "v2",
                            "status", receipt.status()), null);
            return receipt;
        } catch (RuntimeException failure) {
            audit.log(context, "memory.learn", McpAuditLogger.hashQuery("batch:" + candidates), 0,
                    latency(started), "error", Map.of("projectKey", project),
                    failure.getClass().getSimpleName());
            throw failure;
        }
    }

    /** Runtime-only adapter. Deliberately absent from the MCP tool catalog. */
    public CompactLearnReceipt capture(
            LearningCaptureCommand command) {
        McpClientContext context = McpClientContextHolder.require();
        Instant started = Instant.now();
        String project = context.projectKey();
        int candidates = command == null ? 0 : command.learningCandidates().size();
        String taskRunId = command == null ? null : command.taskRunId();
        try {
            requireScope(context, "memory.write");
            requireScope(context, "codebase.read");
            String sessionScope = requireSessionScope(context);
            if (command == null) throw new IllegalArgumentException("capture command is required");
            project = McpProjectKeys.effective(context, command.projectKey());
            LearningCaptureCommand bound = new LearningCaptureCommand(command.taskRunId(), project,
                    command.workspaceBindingId(), command.learningCandidates());
            CompactLearnReceipt receipt = orchestrator.capture(principal(context), context.clientId(),
                    sessionScope, bound, runtime(context.clientId()), null);
            audit.log(context, "learning.capture", McpAuditLogger.hashQuery(taskRunId), candidates,
                    latency(started), "success", Map.of("projectKey", project, "contract", "post-turn-v1",
                            "status", receipt.status()), null);
            return receipt;
        } catch (McpAccessException denied) {
            audit.log(context, "learning.capture", McpAuditLogger.hashQuery(taskRunId), 0, latency(started),
                    "denied_scope", Map.of("projectKey", project), denied.getClass().getSimpleName());
            throw denied;
        } catch (RuntimeException failure) {
            audit.log(context, "learning.capture", McpAuditLogger.hashQuery(taskRunId), 0, latency(started),
                    "error", Map.of("projectKey", project), failure.getClass().getSimpleName());
            throw failure;
        }
    }

    /** Compatibility-only Java adapter; the runtime owns capture retries and receipts. */
    public CompactLearnReceipt statusV2() {
        McpClientContext context = McpClientContextHolder.require();
        Instant started = Instant.now();
        try {
            requireScope(context, "memory.write");
            String sessionScope = requireSessionScope(context);
            CompactLearnReceipt receipt = service.operationStatus(principal(context), sessionScope);
            audit.log(context, "operation.status", McpAuditLogger.hashQuery(context.sessionScopeHash()), 1,
                    latency(started), "success", Map.of("projectKey", context.projectKey(), "contract", "v2",
                            "status", receipt.status()), null);
            return receipt;
        } catch (RuntimeException failure) {
            audit.log(context, "operation.status", McpAuditLogger.hashQuery(context.sessionScopeHash()), 0,
                    latency(started), "error", Map.of("projectKey", context.projectKey()),
                    failure.getClass().getSimpleName());
            throw failure;
        }
    }

    /** Compatibility-only Java adapter; absent from the MCP tool catalog. */
    @Deprecated(forRemoval = true)
    public ContextResolveResponse resolve(
            @McpToolParam(description = "Contract schema version; must be 1") Integer schemaVersion,
            @McpToolParam(description = "Project key returned by scanner.project.resolve") String projectKey,
            @McpToolParam(description = "Opaque workspaceBindingId returned by scanner.project.resolve") String workspaceBindingId,
            @McpToolParam(description = "Current task or question, max 8192 characters") String query,
            @McpToolParam(description = "locate, explain, change, debug, or recall", required = false) String intent,
            @McpToolParam(description = "Optional bounded exact symbol and repository-relative path hints", required = false) ContextHints hints,
            @McpToolParam(description = "Desired compact response budget, 128 to configured maximum", required = false) Integer maxTokens,
            @McpToolParam(description = "never, when_needed, or always", required = false) String includeReferences) {
        McpClientContext context = McpClientContextHolder.require();
        Instant started = Instant.now();
        String effectiveProject = context.projectKey();
        try {
            requireScope(context, "memory.read");
            requireScope(context, "codebase.read");
            requireScope(context, "rules.read");
            requireSchema(schemaVersion);
            effectiveProject = McpProjectKeys.effective(context, projectKey);
            ContextHints safeHints = hints == null ? new ContextHints(List.of(), List.of(), List.of()) : hints;
            validateHints(safeHints);
            String safeReferenceMode = McpProjectKeys.isLocalTrust(context)
                    ? includeReferences : "never";
            ContextReservation reservation = service.resolveContext(principal(context), context.clientId(),
                    effectiveProject, uuid(workspaceBindingId, "workspaceBindingId"), query, intent,
                    safeHints.paths(), safeHints.symbols(), safeHints.terms(), maxTokens, safeReferenceMode);
            List<ContextTarget> targets = reservation.context().targets().stream()
                    .map(target -> targetResponse(target, reservation.sourceChecks())).toList();
            ContextResolveResponse response = new ContextResolveResponse(1, reservation.status(),
                    reservation.context().id(), reservation.context().workspaceBindingId(),
                    reservation.learningHandle(), reservation.knowledge().stream()
                            .map(AgentLearningMcpTool::knowledgeResponse).toList(),
                    targets, reservation.context().references().stream()
                            .map(reference -> new ContextReference(reference.targetId(), reference.referenceId(),
                                    reference.relativePath(), reference.sectionKey(), reference.sourceMemoryId(),
                                    reservation.sourceChecks().getOrDefault(reference.targetId(), "UNKNOWN")))
                            .toList(), reservation.gaps(),
                    new Omitted(reservation.omittedTargets(), reservation.omittedKnowledge()));
            audit.log(context, "context.resolve", McpAuditLogger.hashQuery(query),
                    targets.size() + reservation.context().references().size(),
                    latency(started), "success", Map.of("projectKey", effectiveProject), null);
            return response;
        } catch (McpAccessException denied) {
            audit.log(context, "context.resolve", McpAuditLogger.hashQuery(query), 0, latency(started),
                    "denied_scope", Map.of("projectKey", effectiveProject), denied.getClass().getSimpleName());
            throw denied;
        } catch (RuntimeException failure) {
            audit.log(context, "context.resolve", McpAuditLogger.hashQuery(query), 0, latency(started),
                    "error", Map.of("projectKey", effectiveProject), failure.getClass().getSimpleName());
            throw failure;
        }
    }

    /** Compatibility-only Java adapter; absent from the MCP tool catalog. */
    @Deprecated(forRemoval = true)
    public ContextOpenResponse open(
            @McpToolParam(description = "Server context UUID") String contextId,
            @McpToolParam(description = "One to three target IDs from context.resolve") List<String> targetIds,
            @McpToolParam(description = "Total response budget, 128 to configured maximum", required = false) Integer maxTokens) {
        McpClientContext context = McpClientContextHolder.require();
        Instant started = Instant.now();
        try {
            requireScope(context, "codebase.read");
            UUID parsedContext = uuid(contextId, "contextId");
            String project = service.projectForContext(principal(context), parsedContext);
            McpProjectKeys.effective(context, project);
            OpenedContext opened = service.openContext(principal(context), project, parsedContext, targetIds, maxTokens);
            ContextOpenResponse response = new ContextOpenResponse(1, "READ", opened.contextId(),
                    opened.items().stream().map(AgentLearningMcpTool::openedResponse).toList(),
                    opened.references().stream().map(AgentLearningMcpTool::openedReferenceResponse).toList());
            audit.log(context, "context.open", McpAuditLogger.hashQuery(contextId),
                    opened.items().size() + opened.references().size(),
                    latency(started), "success", Map.of("projectKey", project), null);
            return response;
        } catch (McpAccessException denied) {
            audit.log(context, "context.open", McpAuditLogger.hashQuery(contextId), 0, latency(started),
                    "denied_scope", Map.of(), denied.getClass().getSimpleName());
            throw denied;
        } catch (RuntimeException failure) {
            audit.log(context, "context.open", McpAuditLogger.hashQuery(contextId), 0, latency(started),
                    "error", Map.of(), failure.getClass().getSimpleName());
            throw failure;
        }
    }

    /** Compatibility-only Java adapter; absent from the MCP tool catalog. */
    @Deprecated(forRemoval = true)
    public LearnReceipt learn(
            @McpToolParam(description = "Contract schema version; must be 1") Integer schemaVersion,
            @McpToolParam(description = "Server context UUID") String contextId,
            @McpToolParam(description = "Opaque learning handle from context.resolve") String learningHandle,
            @McpToolParam(description = "One to three typed discovery candidates") List<Candidate> candidates,
            @McpToolParam(description = "Producer runtime provenance: copilot, claude_code, or codex", required = false) String producerRuntime,
            @McpToolParam(description = "Optional observed producer model provenance", required = false) String producerModel) {
        McpClientContext context = McpClientContextHolder.require();
        Instant started = Instant.now();
        try {
            requireScope(context, "memory.write");
            requireScope(context, "codebase.read");
            requireSchema(schemaVersion);
            UUID parsedContext = uuid(contextId, "contextId");
            String project = service.projectForContext(principal(context), parsedContext);
            McpProjectKeys.effective(context, project);
            LearnReceipt response = service.learn(principal(context), project, parsedContext,
                    uuid(learningHandle, "learningHandle"), candidates, producerRuntime, producerModel);
            audit.log(context, "memory.learn", McpAuditLogger.hashQuery(learningHandle), response.results().size(),
                    latency(started), "success", Map.of("projectKey", project, "status", response.status()), null);
            return response;
        } catch (McpAccessException denied) {
            audit.log(context, "memory.learn", McpAuditLogger.hashQuery(learningHandle), 0, latency(started),
                    "denied_scope", Map.of(), denied.getClass().getSimpleName());
            throw denied;
        } catch (MemoryProjectionQueueFullException queueFull) {
            audit.log(context, "memory.learn", McpAuditLogger.hashQuery(learningHandle), 0, latency(started),
                    "queue_full", Map.of("retryable", true), queueFull.getClass().getSimpleName());
            throw queueFull;
        } catch (RuntimeException failure) {
            audit.log(context, "memory.learn", McpAuditLogger.hashQuery(learningHandle), 0, latency(started),
                    "error", Map.of(), failure.getClass().getSimpleName());
            throw failure;
        }
    }

    /** Compatibility-only Java adapter; absent from the MCP tool catalog. */
    @Deprecated(forRemoval = true)
    public LearnReceipt status(
            @McpToolParam(description = "Server operation UUID", required = false) String requestId,
            @McpToolParam(description = "Server learning handle", required = false) String learningHandle) {
        McpClientContext context = McpClientContextHolder.require();
        Instant started = Instant.now();
        try {
            requireScope(context, "memory.write");
            UUID request = optionalUuid(requestId, "requestId");
            UUID handle = optionalUuid(learningHandle, "learningHandle");
            String project = service.projectForOperation(principal(context), request, handle);
            McpProjectKeys.effective(context, project);
            LearnReceipt response = service.operationStatus(principal(context), project, request, handle);
            audit.log(context, "operation.status", McpAuditLogger.hashQuery(requestId == null ? learningHandle : requestId),
                    response.results().size(), latency(started), "success",
                    Map.of("projectKey", project, "status", response.status()), null);
            return response;
        } catch (McpAccessException denied) {
            audit.log(context, "operation.status", null, 0, latency(started), "denied_scope", Map.of(),
                    denied.getClass().getSimpleName());
            throw denied;
        } catch (RuntimeException failure) {
            audit.log(context, "operation.status", null, 0, latency(started), "error", Map.of(),
                    failure.getClass().getSimpleName());
            throw failure;
        }
    }

    private static ContextKnowledge knowledgeResponse(Knowledge knowledge) {
        return new ContextKnowledge(knowledge.id(), knowledge.type(), knowledge.text(), knowledge.contentComplete(),
                knowledge.verification(), knowledge.freshness(), knowledge.learningRevision(),
                knowledge.canonicalHash());
    }

    private static ContextTarget targetResponse(Target target, Map<String, String> sourceChecks) {
        List<Integer> lines = target.startLine() == null ? List.of()
                : target.endLine() == null ? List.of(target.startLine())
                : List.of(target.startLine(), target.endLine());
        return new ContextTarget(target.targetId(), target.symbolRef(), target.relativePath(), lines,
                target.role(), sourceChecks.getOrDefault(target.targetId(), "UNKNOWN"));
    }

    private static ContextOpenedItem openedResponse(OpenedSource source) {
        return new ContextOpenedItem(source.targetId(), source.evidenceId(), source.path(),
                List.of(source.startLine(), source.endLine()), source.content(), source.contentComplete(),
                source.changedSinceResolve());
    }

    private static ContextOpenedReference openedReferenceResponse(OpenedReference reference) {
        return new ContextOpenedReference(reference.targetId(), reference.referenceId(), reference.path(),
                reference.sectionKey(), reference.title(), reference.content(), reference.contentComplete(),
                reference.changedSinceResolve(), reference.visibleHash());
    }

    private static void requireScope(McpClientContext context, String scope) {
        if (!context.hasScope(scope)) throw new McpAccessException("Missing scope: " + scope);
    }

    private static String requireSessionScope(McpClientContext context) {
        if (!context.hasSessionScope()) {
            throw new IllegalArgumentException("SESSION_SCOPE_UNAVAILABLE");
        }
        return context.sessionScopeHash();
    }

    private static void requireSchema(Integer schemaVersion) {
        if (schemaVersion == null || schemaVersion != 1) {
            throw new IllegalArgumentException("schemaVersion must be 1");
        }
    }

    private static void validateHints(ContextHints hints) {
        if (hints.symbols().size() > 8 || hints.paths().size() > 8 || hints.terms().size() > 8) {
            throw new IllegalArgumentException("each hints list accepts at most 8 entries");
        }
        for (String value : hints.terms()) {
            if (value == null || value.isBlank() || value.length() > 160
                    || value.codePoints().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("hint terms must be non-blank and at most 160 characters");
            }
        }
    }

    private static String summarize(String value) {
        if (value == null) return "";
        String normalized = value.strip().replaceAll("\\s+", " ");
        return normalized.length() <= 160 ? normalized : normalized.substring(0, 157) + "...";
    }

    private static String runtime(String clientId) {
        String normalized = clientId == null ? "" : clientId.toLowerCase(java.util.Locale.ROOT);
        if (normalized.contains("copilot")) return "copilot";
        if (normalized.contains("claude")) return "claude_code";
        return "codex";
    }

    CompactResolveResponse fitSerializedBudget(CompactResolveResponse response, int maxTokens) {
        List<CompactKnowledge> knowledge = new java.util.ArrayList<>(response.knowledge());
        List<CompactTarget> targets = new java.util.ArrayList<>(response.targets());
        List<String> gaps = new java.util.ArrayList<>(response.gaps());
        int omittedKnowledge = response.omitted().knowledge();
        int omittedTargets = response.omitted().targets();
        CompactResolveResponse current = response;
        while (serializedTokens(current) > maxTokens) {
            int contentIndex = -1;
            for (int index = knowledge.size() - 1; index >= 0; index--) {
                if (knowledge.get(index).content() != null) {
                    contentIndex = index;
                    break;
                }
            }
            if (contentIndex >= 0) {
                CompactKnowledge item = knowledge.get(contentIndex);
                knowledge.set(contentIndex, new CompactKnowledge(item.ref(), item.summary(), item.verification(),
                        item.freshness(), null, item.hasReference(), item.referenceHint()));
                if (!gaps.contains("knowledge_content_omitted_by_budget")) {
                    gaps.add("knowledge_content_omitted_by_budget");
                }
            } else if (!knowledge.isEmpty()) {
                knowledge.removeLast();
                omittedKnowledge++;
            } else if (!targets.isEmpty()) {
                targets.removeLast();
                omittedTargets++;
            } else {
                break;
            }
            current = new CompactResolveResponse(response.schemaVersion(), response.status(), knowledge, targets,
                    gaps, new Omitted(omittedTargets, omittedKnowledge));
        }
        return current;
    }

    private int serializedTokens(Object value) {
        try {
            return TokenEstimator.estimate(objectMapper.writeValueAsString(value));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize compact context", exception);
        }
    }

    private static UUID uuid(String value, String field) {
        UUID parsed = optionalUuid(value, field);
        if (parsed == null) throw new IllegalArgumentException(field + " is required");
        return parsed;
    }

    private static UUID optionalUuid(String value, String field) {
        if (value == null || value.isBlank()) return null;
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException(field + " must be a UUID", invalid);
        }
    }

    static String principal(McpClientContext context) {
        return McpAuditLogger.sha256Hex("agent-learning-principal/v1\0"
                + String.valueOf(context.clientId()) + "\0" + String.valueOf(context.keyPrefix()));
    }

    private static long latency(Instant started) {
        return Math.max(0L, Duration.between(started, Instant.now()).toMillis());
    }
}
