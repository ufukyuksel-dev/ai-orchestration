package com.mbworldwideapps.aiorchestration.modules.agentlearning;

import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.mbworldwideapps.aiorchestration.modules.agentlearning.AgentLearningModels.Candidate;
import com.mbworldwideapps.aiorchestration.modules.agentlearning.AgentLearningModels.ClaimKind;
import com.mbworldwideapps.aiorchestration.modules.agentlearning.AgentLearningModels.Observation;
import com.mbworldwideapps.aiorchestration.modules.agentlearning.AgentLearningModels.Operation;
import com.mbworldwideapps.aiorchestration.modules.agentlearning.AgentLearningModels.OperationClaim;
import com.mbworldwideapps.aiorchestration.modules.agentlearning.AgentLearningModels.ResearchContext;
import com.mbworldwideapps.aiorchestration.modules.agentlearning.AgentLearningModels.ReferenceTarget;
import com.mbworldwideapps.aiorchestration.modules.agentlearning.AgentLearningModels.Target;
import com.mbworldwideapps.aiorchestration.modules.agentlearning.AgentLearningModels.WorkspaceBinding;
import com.mbworldwideapps.aiorchestration.modules.agentlearning.AgentLearningModels.DiscoveryRoute;
import com.mbworldwideapps.aiorchestration.modules.agentlearning.AgentLearningModels.LearnedAnchor;
import com.mbworldwideapps.aiorchestration.modules.agentlearning.AgentLearningModels.LearningProfile;
import com.mbworldwideapps.aiorchestration.modules.agentlearning.AgentLearningModels.KnowledgeBinding;
import com.mbworldwideapps.aiorchestration.modules.agentlearning.AgentLearningModels.PurgeResult;
import com.mbworldwideapps.aiorchestration.modules.agentlearning.AgentLearningModels.DuplicateCandidate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAgentLearningRepository implements AgentLearningRepository {

    private final JdbcTemplate jdbc;

    public JdbcAgentLearningRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<DiscoveryRoute> findDiscoveryRoutes(String projectKey, List<UUID> memoryIds, int limit) {
        if (projectKey == null || projectKey.isBlank() || memoryIds == null || memoryIds.isEmpty() || limit <= 0) {
            return List.of();
        }
        List<DiscoveryRoute> routes = new ArrayList<>();
        for (UUID memoryId : memoryIds.stream().filter(java.util.Objects::nonNull).distinct().limit(limit).toList()) {
            List<RouteRow> rows = jdbc.query("""
                    SELECT mi.id,mi.summary,mi.text,mi.status AS memory_status,p.verification_level,
                           p.usefulness_state,p.learning_revision,p.canonical_hash,
                           a.canonical_ref,a.symbol_key,a.locator_kind,a.anchor_role,a.priority,a.resolution_state,
                           (SELECT e.observed_hash FROM memory_learning_evidence e
                            WHERE e.memory_id=mi.id AND e.canonical_ref=a.canonical_ref
                            ORDER BY e.observed_at DESC,e.id DESC LIMIT 1) AS expected_hash
                    FROM memory_items mi
                    JOIN memory_learning_profiles p ON p.memory_id=mi.id
                    LEFT JOIN memory_navigation_anchors a ON a.memory_id=mi.id
                    WHERE mi.id=? AND mi.project_key=? AND mi.memory_type='discovery'
                      AND mi.status IN ('active','stale')
                      AND p.usefulness_state IN ('ACTIVE','STALE','STALE_EVIDENCE')
                    ORDER BY a.priority NULLS LAST,a.locator_index NULLS LAST
                    """, (rs, row) -> new RouteRow(rs.getObject("id", UUID.class), rs.getString("summary"),
                            rs.getString("text"), rs.getString("verification_level"), rs.getString("memory_status"),
                            rs.getString("usefulness_state"), rs.getLong("learning_revision"),
                            rs.getString("canonical_hash"),
                            rs.getString("canonical_ref"), rs.getString("symbol_key"),
                            rs.getString("locator_kind"), rs.getString("anchor_role"), rs.getInt("priority"),
                            rs.getString("expected_hash"), rs.getString("resolution_state")),
                    memoryId, projectKey.trim());
            if (rows.isEmpty()) continue;
            RouteRow first = rows.getFirst();
            List<LearnedAnchor> anchors = rows.stream()
                    .filter(row -> row.relativePath() != null && !row.relativePath().isBlank())
                    .map(row -> new LearnedAnchor(row.relativePath(), row.symbolRef(), row.locatorKind(),
                            row.role(), row.priority(), row.expectedHash(), row.resolutionState()))
                    .toList();
            routes.add(new DiscoveryRoute(first.memoryId(), first.summary(), first.text(), first.verification(),
                    "stale".equalsIgnoreCase(first.memoryStatus())
                            || "STALE".equalsIgnoreCase(first.usefulnessState())
                            || "STALE_EVIDENCE".equalsIgnoreCase(first.usefulnessState()),
                    first.learningRevision(), first.canonicalHash(), anchors));
        }
        return List.copyOf(routes);
    }

    @Override
    public WorkspaceBinding insertBinding(WorkspaceBinding binding) {
        jdbc.update("""
                INSERT INTO agent_workspace_bindings
                    (id,principal_key,client_id,project_key,repository_fingerprint,root_path,created_at,expires_at)
                VALUES (?,?,?,?,?,?,?,?)
                """, binding.id(), binding.principalKey(), binding.clientId(), binding.projectKey(),
                binding.repositoryFingerprint(), binding.rootPath().toString(), timestamp(binding.createdAt()),
                timestamp(binding.expiresAt()));
        return binding;
    }

    @Override
    public Optional<WorkspaceBinding> findBinding(UUID id) {
        return jdbc.query("SELECT * FROM agent_workspace_bindings WHERE id=?", this::binding, id)
                .stream().findFirst();
    }

    @Override
    public ResearchContext insertContext(ResearchContext context, UUID learningHandle, UUID requestId,
            Instant operationExpiresAt) {
        jdbc.update("""
                INSERT INTO research_contexts
                    (id,workspace_binding_id,principal_key,client_id,session_scope_hash,project_key,context_epoch,
                     task_intent,query_hash,trace_coverage,created_at,expires_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                """, context.id(), context.workspaceBindingId(), context.principalKey(), context.clientId(),
                scopeHash(context.sessionScopeHash()), context.projectKey(), context.contextEpoch(), context.intent(), context.queryHash(),
                context.traceCoverage(), timestamp(context.createdAt()), timestamp(context.expiresAt()));
        for (Target target : context.targets()) {
            jdbc.update("""
                    INSERT INTO research_context_targets
                        (context_id,target_id,relative_path,symbol_ref,target_role,resolved_hash,start_line,end_line,
                         locator_kind)
                    VALUES (?,?,?,?,?,?,?,?,?)
                    """, context.id(), target.targetId(), target.relativePath(), target.symbolRef(), target.role(),
                    target.resolvedHash(), target.startLine(), target.endLine(), target.locatorKind());
        }
        for (ReferenceTarget reference : context.references()) {
            jdbc.update("""
                    INSERT INTO research_context_references
                        (context_id,target_id,reference_id,relative_path,content_hash,section_key,source_memory_id)
                    VALUES (?,?,?,?,?,?,?)
                    """, context.id(), reference.targetId(), reference.referenceId(), reference.relativePath(),
                    reference.contentHash(), reference.sectionKey(), reference.sourceMemoryId());
        }
        jdbc.update("""
                INSERT INTO learning_operations
                    (request_id,learning_handle,context_id,principal_key,project_key,state,created_at,updated_at,expires_at)
                VALUES (?,?,?,?,?,'ISSUED',?,?,?)
                """, requestId, learningHandle, context.id(), context.principalKey(), context.projectKey(),
                timestamp(context.createdAt()), timestamp(context.createdAt()), timestamp(operationExpiresAt));
        return context;
    }

    @Override
    public ResearchContext insertCaptureContext(ResearchContext context, Operation operation,
            String sessionScopeHash, String operationKey) {
        jdbc.update("""
                INSERT INTO research_contexts
                    (id,workspace_binding_id,principal_key,client_id,session_scope_hash,project_key,context_epoch,
                     task_intent,query_hash,trace_coverage,created_at,expires_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                """, context.id(), context.workspaceBindingId(), context.principalKey(), context.clientId(),
                scopeHash(context.sessionScopeHash()), context.projectKey(), context.contextEpoch(), context.intent(),
                context.queryHash(), context.traceCoverage(), timestamp(context.createdAt()),
                timestamp(context.expiresAt()));
        for (Target target : context.targets()) {
            jdbc.update("""
                    INSERT INTO research_context_targets
                        (context_id,target_id,relative_path,symbol_ref,target_role,resolved_hash,start_line,end_line,
                         locator_kind)
                    VALUES (?,?,?,?,?,?,?,?,?)
                    """, context.id(), target.targetId(), target.relativePath(), target.symbolRef(), target.role(),
                    target.resolvedHash(), target.startLine(), target.endLine(), target.locatorKind());
        }
        jdbc.update("""
                INSERT INTO learning_operations
                    (request_id,learning_handle,context_id,principal_key,project_key,payload_hash,state,
                     result_json,lease_until,created_at,updated_at,expires_at,session_scope_hash,operation_key)
                VALUES (?,?,?,?,?,NULL,'ISSUED',NULL,NULL,?,?,?,?,?)
                """, operation.requestId(), operation.learningHandle(), operation.contextId(),
                operation.principalKey(), operation.projectKey(), timestamp(operation.createdAt()),
                timestamp(operation.updatedAt()), timestamp(operation.expiresAt()), scopeHash(sessionScopeHash),
                operationKey);
        return context;
    }

    @Override
    public Operation insertOperation(Operation operation) {
        jdbc.update("""
                INSERT INTO learning_operations
                    (request_id,learning_handle,context_id,principal_key,project_key,payload_hash,state,
                     result_json,lease_until,created_at,updated_at,expires_at)
                VALUES (?,?,?,?,?,?,?,?::jsonb,?,?,?,?)
                """, operation.requestId(), operation.learningHandle(), operation.contextId(),
                operation.principalKey(), operation.projectKey(), operation.payloadHash(), operation.state(),
                operation.resultJson(), timestamp(operation.leaseUntil()), timestamp(operation.createdAt()),
                timestamp(operation.updatedAt()), timestamp(operation.expiresAt()));
        return operation;
    }

    @Override
    public Operation reserveSemanticOperation(Operation operation, String sessionScopeHash, String operationKey) {
        jdbc.update("""
                INSERT INTO learning_operations
                    (request_id,learning_handle,context_id,principal_key,project_key,payload_hash,state,
                     result_json,lease_until,created_at,updated_at,expires_at,session_scope_hash,operation_key)
                VALUES (?,?,?,?,?,NULL,'ISSUED',NULL,NULL,?,?,?,?,?)
                """, operation.requestId(), operation.learningHandle(), operation.contextId(),
                operation.principalKey(), operation.projectKey(), timestamp(operation.createdAt()),
                timestamp(operation.updatedAt()), timestamp(operation.expiresAt()), scopeHash(sessionScopeHash),
                operationKey);
        return operation;
    }

    @Override
    public Optional<Operation> findOperationByKey(String operationKey) {
        return jdbc.query("SELECT * FROM learning_operations WHERE operation_key=?", this::operation, operationKey)
                .stream().findFirst();
    }

    @Override
    public Optional<Operation> findLatestUncertainOperation(String principalKey, String projectKey,
            String sessionScopeHash, Instant now) {
        return jdbc.query("""
                SELECT * FROM learning_operations
                WHERE principal_key=? AND project_key=? AND session_scope_hash=? AND expires_at>?
                  AND operation_key IS NOT NULL
                ORDER BY updated_at DESC,request_id DESC LIMIT 1
                """, this::operation, principalKey, projectKey, scopeHash(sessionScopeHash), timestamp(now))
                .stream().findFirst();
    }

    @Override
    public Optional<Operation> findLatestSessionOperation(String principalKey, String sessionScopeHash,
            Instant now) {
        return jdbc.query("""
                SELECT * FROM learning_operations
                WHERE principal_key=? AND session_scope_hash=? AND expires_at>? AND operation_key IS NOT NULL
                ORDER BY updated_at DESC,request_id DESC LIMIT 1
                """, this::operation, principalKey, scopeHash(sessionScopeHash), timestamp(now))
                .stream().findFirst();
    }

    @Override
    public void replaceCompletedResult(UUID requestId, String resultJson, Instant now) {
        int updated = jdbc.update("""
                UPDATE learning_operations SET result_json=?::jsonb,updated_at=?
                WHERE request_id=? AND state='COMPLETED'
                """, resultJson, timestamp(now), requestId);
        if (updated != 1) throw new IllegalStateException("learning operation receipt replacement conflict");
    }

    @Override
    public Optional<ResearchContext> findContext(UUID id) {
        List<ResearchContext> rows = jdbc.query("SELECT * FROM research_contexts WHERE id=?", (rs, row) -> {
            List<Target> targets = jdbc.query("""
                    SELECT * FROM research_context_targets WHERE context_id=? ORDER BY target_id
                    """, this::target, id);
            List<ReferenceTarget> references = jdbc.query("""
                    SELECT * FROM research_context_references WHERE context_id=? ORDER BY target_id
                    """, this::referenceTarget, id);
            return new ResearchContext(rs.getObject("id", UUID.class),
                    rs.getObject("workspace_binding_id", UUID.class), rs.getString("principal_key"),
                    rs.getString("client_id"), rs.getString("session_scope_hash"), rs.getString("project_key"),
                    rs.getObject("context_epoch", UUID.class), rs.getString("task_intent"),
                    rs.getString("query_hash"), rs.getString("trace_coverage"),
                    instant(rs, "created_at"), instant(rs, "expires_at"), targets, references);
        }, id);
        return rows.stream().findFirst();
    }

    @Override
    public Optional<ResearchContext> findActiveContext(String principalKey, String projectKey,
            String clientId, String sessionScopeHash, Instant now) {
        return jdbc.query("""
                SELECT id FROM research_contexts
                WHERE principal_key=? AND project_key=? AND client_id=? AND session_scope_hash=?
                  AND active=TRUE AND expires_at>?
                ORDER BY created_at DESC,id DESC LIMIT 1
                """, (rs, row) -> rs.getObject("id", UUID.class), principalKey, projectKey, clientId,
                scopeHash(sessionScopeHash), timestamp(now)).stream().findFirst().flatMap(this::findContext);
    }

    @Override
    public Optional<ResearchContext> findLatestActiveContext(String principalKey, String clientId,
            String sessionScopeHash, Instant now) {
        return jdbc.query("""
                SELECT id FROM research_contexts
                WHERE principal_key=? AND client_id=? AND session_scope_hash=?
                  AND active=TRUE AND expires_at>?
                ORDER BY created_at DESC,id DESC LIMIT 1
                """, (rs, row) -> rs.getObject("id", UUID.class), principalKey, clientId,
                scopeHash(sessionScopeHash), timestamp(now)).stream().findFirst().flatMap(this::findContext);
    }

    @Override
    public void supersedeActiveContexts(String principalKey, String projectKey, String clientId,
            String sessionScopeHash, Instant now) {
        jdbc.update("""
                UPDATE research_contexts SET active=FALSE,superseded_at=?
                WHERE principal_key=? AND project_key=? AND client_id=? AND session_scope_hash=? AND active=TRUE
                """, timestamp(now), principalKey, projectKey, clientId, scopeHash(sessionScopeHash));
    }

    @Override
    public void insertKnowledgeBindings(UUID contextId, List<KnowledgeBinding> bindings) {
        for (KnowledgeBinding binding : bindings) {
            jdbc.update("""
                    INSERT INTO research_context_knowledge
                        (context_id,knowledge_ref,memory_id,learning_revision,canonical_hash)
                    VALUES (?,?,?,?,?)
                    """, contextId, binding.ref(), binding.memoryId(), binding.learningRevision(),
                    binding.canonicalHash());
        }
    }

    @Override
    public List<KnowledgeBinding> findKnowledgeBindings(UUID contextId) {
        return jdbc.query("""
                SELECT knowledge_ref,memory_id,learning_revision,canonical_hash
                FROM research_context_knowledge WHERE context_id=? ORDER BY knowledge_ref
                """, (rs, row) -> new KnowledgeBinding(rs.getString("knowledge_ref"),
                        rs.getObject("memory_id", UUID.class), rs.getLong("learning_revision"),
                        rs.getString("canonical_hash")), contextId);
    }

    @Override
    public Observation insertObservation(Observation observation) {
        jdbc.update("""
                INSERT INTO research_observations
                    (id,context_id,target_id,operation_kind,resource_ref,raw_content_hash,redacted_content_hash,
                     workspace_snapshot_id,byte_count,provenance,observed_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?)
                """, observation.id(), observation.contextId(), observation.targetId(), observation.operationKind(),
                observation.resourceRef(), observation.rawContentHash(), observation.redactedContentHash(),
                observation.workspaceSnapshotId(), observation.byteCount(), observation.provenance(),
                timestamp(observation.observedAt()));
        return observation;
    }

    @Override
    public List<Observation> findObservations(UUID contextId, List<UUID> evidenceIds) {
        if (evidenceIds == null || evidenceIds.isEmpty()) return List.of();
        String placeholders = String.join(",", java.util.Collections.nCopies(evidenceIds.size(), "?"));
        List<Object> arguments = new ArrayList<>();
        arguments.add(contextId);
        arguments.addAll(evidenceIds);
        return jdbc.query("SELECT * FROM research_observations WHERE context_id=? AND id IN (" + placeholders
                + ") ORDER BY observed_at,id", this::observation, arguments.toArray());
    }

    @Override
    public List<Observation> findLatestObservations(UUID contextId, List<String> targetIds) {
        if (targetIds == null || targetIds.isEmpty()) return List.of();
        String placeholders = String.join(",", java.util.Collections.nCopies(targetIds.size(), "?"));
        List<Object> arguments = new ArrayList<>();
        arguments.add(contextId);
        arguments.addAll(targetIds);
        return jdbc.query("""
                SELECT DISTINCT ON (target_id) * FROM research_observations
                WHERE context_id=? AND target_id IN (""" + placeholders + ") " + """
                ORDER BY target_id,observed_at DESC,id DESC
                """, this::observation, arguments.toArray());
    }

    @Override
    public OperationClaim claim(UUID learningHandle, UUID contextId, String principalKey, String projectKey,
            String payloadHash, Instant leaseUntil, Instant now) {
        Operation current = jdbc.query("SELECT * FROM learning_operations WHERE learning_handle=? FOR UPDATE",
                this::operation, learningHandle).stream()
                .findFirst().orElseThrow(() -> new IllegalArgumentException("LEARNING_HANDLE_UNKNOWN"));
        if (!principalKey.equals(current.principalKey()) || !projectKey.equals(current.projectKey())
                || !contextId.equals(current.contextId())) {
            throw new IllegalArgumentException("LEARNING_HANDLE_SCOPE_DENIED");
        }
        if (!current.expiresAt().isAfter(now)) {
            throw new IllegalArgumentException("LEARNING_HANDLE_EXPIRED");
        }
        if (current.payloadHash() == null && "ISSUED".equals(current.state())) {
            jdbc.update("""
                    UPDATE learning_operations SET payload_hash=?,state='PROCESSING',lease_until=?,updated_at=?
                    WHERE request_id=?
                    """, payloadHash, timestamp(leaseUntil), timestamp(now), current.requestId());
            return new OperationClaim(ClaimKind.CLAIMED, requireOperation(current.requestId(), null));
        }
        if (!payloadHash.equals(current.payloadHash())) {
            return new OperationClaim(ClaimKind.CONFLICT, current);
        }
        if ("COMPLETED".equals(current.state())) {
            return new OperationClaim(ClaimKind.REPLAY, current);
        }
        if ("RETRYABLE_ERROR".equals(current.state())) {
            jdbc.update("""
                    UPDATE learning_operations
                    SET state='PROCESSING',result_json=NULL,lease_until=?,updated_at=? WHERE request_id=?
                    """, timestamp(leaseUntil), timestamp(now), current.requestId());
            return new OperationClaim(ClaimKind.CLAIMED, requireOperation(current.requestId(), null));
        }
        if (current.leaseUntil() != null && !current.leaseUntil().isAfter(now)) {
            jdbc.update("""
                    UPDATE learning_operations SET state='PROCESSING',lease_until=?,updated_at=? WHERE request_id=?
                    """, timestamp(leaseUntil), timestamp(now), current.requestId());
            return new OperationClaim(ClaimKind.CLAIMED, requireOperation(current.requestId(), null));
        }
        return new OperationClaim(ClaimKind.QUEUED, current);
    }

    @Override
    public Optional<Operation> findOperation(UUID requestId, UUID learningHandle) {
        if ((requestId == null) == (learningHandle == null)) {
            throw new IllegalArgumentException("exactly one of requestId or learningHandle is required");
        }
        return requestId != null
                ? jdbc.query("SELECT * FROM learning_operations WHERE request_id=?", this::operation, requestId)
                        .stream().findFirst()
                : jdbc.query("SELECT * FROM learning_operations WHERE learning_handle=?", this::operation,
                        learningHandle).stream().findFirst();
    }

    @Override
    public void complete(UUID requestId, String payloadHash, String resultJson, Instant now) {
        int updated = jdbc.update("""
                UPDATE learning_operations
                SET state='COMPLETED',result_json=?::jsonb,lease_until=NULL,updated_at=?
                WHERE request_id=? AND payload_hash=?
                """, resultJson, timestamp(now), requestId, payloadHash);
        if (updated != 1) throw new IllegalStateException("learning operation completion conflict");
    }

    @Override
    public void retryableError(UUID requestId, String payloadHash, String resultJson, Instant now) {
        int updated = jdbc.update("""
                UPDATE learning_operations
                SET state='RETRYABLE_ERROR',result_json=?::jsonb,lease_until=NULL,updated_at=?
                WHERE request_id=? AND payload_hash=?
                """, resultJson, timestamp(now), requestId, payloadHash);
        if (updated != 1) throw new IllegalStateException("learning operation error receipt conflict");
    }

    @Override
    public void lockCanonical(String projectKey, String canonicalHash) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", rs -> {
        }, projectKey.length() + ":" + projectKey + canonicalHash);
    }

    @Override
    public Optional<UUID> findCanonicalMemory(String projectKey, String canonicalHash) {
        return jdbc.query("""
                SELECT memory_id FROM memory_learning_profiles
                WHERE project_key=? AND (semantic_identity=? OR canonical_hash=?)
                """, (rs, row) -> rs.getObject(1, UUID.class), projectKey, canonicalHash, canonicalHash)
                .stream().findFirst();
    }

    @Override
    public List<DuplicateCandidate> findAnchorDuplicateCandidates(String projectKey,
            List<String> canonicalRefs, int limit) {
        if (canonicalRefs == null || canonicalRefs.isEmpty()) return List.of();
        String slots = String.join(",", java.util.Collections.nCopies(canonicalRefs.size(), "?"));
        List<Object> arguments = new ArrayList<>();
        arguments.add(projectKey);
        arguments.addAll(canonicalRefs);
        arguments.add(Math.max(1, Math.min(limit, 10)));
        return jdbc.query("""
                SELECT mi.id,p.discovery_kind,mi.summary,mi.text,
                       array_agg(DISTINCT (a.locator_kind || chr(31) || a.canonical_ref || chr(31)
                           || coalesce(a.symbol_key,'') || chr(31) || a.anchor_role)
                           ORDER BY (a.locator_kind || chr(31) || a.canonical_ref || chr(31)
                           || coalesce(a.symbol_key,'') || chr(31) || a.anchor_role)) AS anchor_signatures
                FROM memory_items mi
                JOIN memory_learning_profiles p ON p.memory_id=mi.id
                JOIN memory_navigation_anchors a ON a.memory_id=mi.id
                WHERE mi.project_key=? AND mi.memory_type='discovery'
                  AND mi.status IN ('active','stale')
                  AND EXISTS (SELECT 1 FROM memory_navigation_anchors matched
                              WHERE matched.memory_id=mi.id AND matched.canonical_ref IN (""" + slots + ")) " + """
                GROUP BY mi.id,p.discovery_kind,mi.summary,mi.text
                ORDER BY mi.id LIMIT ?
                """, (rs, row) -> {
                    String[] signatures = (String[]) rs.getArray("anchor_signatures").getArray();
                    return new DuplicateCandidate(rs.getObject("id", UUID.class),
                            rs.getString("discovery_kind"), rs.getString("summary"), rs.getString("text"),
                            java.util.Arrays.asList(signatures));
                }, arguments.toArray());
    }

    @Override
    public Optional<LearningProfile> lockLearningProfile(UUID memoryId, String projectKey) {
        return jdbc.query("""
                SELECT memory_id,project_key,learning_revision,canonical_hash,usefulness_state,
                       semantic_identity,content_revision,evidence_revision
                FROM memory_learning_profiles
                WHERE memory_id=? AND project_key=?
                FOR UPDATE
                """, (rs, row) -> new LearningProfile(rs.getObject("memory_id", UUID.class),
                        rs.getString("project_key"), rs.getLong("learning_revision"),
                        rs.getString("canonical_hash"), rs.getString("usefulness_state"),
                        rs.getString("semantic_identity"), rs.getString("content_revision"),
                        rs.getString("evidence_revision")),
                memoryId, projectKey).stream().findFirst();
    }

    @Override
    public void insertLearningMetadata(UUID memoryId, String projectKey, String canonicalHash, UUID contextId,
            String principalKey, String producerRuntime, String producerModel, String captureCoverage,
            Candidate candidate, List<Target> targets, List<Observation> evidence) {
        jdbc.update("""
                INSERT INTO memory_learning_profiles
                    (memory_id,project_key,discovery_kind,origin,verification_level,usefulness_state,
                     canonical_hash,semantic_identity,content_revision,evidence_revision,
                     created_from_context_id,producer_runtime,producer_model,
                     producer_contract_version,capture_coverage)
                VALUES (?,?,?,'runtime_capture','SUPPORTED','ACTIVE',?,?,?,?,?,?,?, 'agent-learning/v2',?)
                """, memoryId, projectKey, candidate.kind(), canonicalHash, canonicalHash, canonicalHash,
                canonicalHash, contextId,
                producerRuntime, blank(producerModel), captureCoverage);
        insertEvidence(memoryId, canonicalHash, evidence);
        insertAnchors(memoryId, candidate, targets);
    }

    @Override
    public void replaceLearningMetadata(UUID memoryId, String projectKey, long expectedRevision,
            String expectedCanonicalHash, String canonicalHash, UUID contextId, String principalKey,
            String producerRuntime, String producerModel, String captureCoverage,
            Candidate candidate, List<Target> targets, List<Observation> evidence) {
        int updated = jdbc.update("""
                UPDATE memory_learning_profiles
                SET discovery_kind=?,verification_level='SUPPORTED',usefulness_state='ACTIVE',
                    learning_revision=learning_revision+1,canonical_hash=?,semantic_identity=?,
                    content_revision=?,evidence_revision=?,created_from_context_id=?,
                    producer_runtime=?,producer_model=?,capture_coverage=?,updated_at=now()
                WHERE memory_id=? AND project_key=? AND learning_revision=? AND canonical_hash=?
                """, candidate.kind(), canonicalHash, canonicalHash, canonicalHash, canonicalHash,
                contextId, producerRuntime, blank(producerModel),
                captureCoverage, memoryId, projectKey, expectedRevision, expectedCanonicalHash);
        if (updated != 1) throw new IllegalStateException("DISCOVERY_CORRECTION_CAS_CONFLICT");
        jdbc.update("DELETE FROM memory_learning_evidence WHERE memory_id=?", memoryId);
        jdbc.update("DELETE FROM memory_navigation_anchors WHERE memory_id=?", memoryId);
        insertEvidence(memoryId, canonicalHash, evidence);
        insertAnchors(memoryId, candidate, targets);
    }

    @Override
    public void updateIdentityRevisions(UUID memoryId, String semanticIdentity,
            String contentRevision, String evidenceRevision) {
        jdbc.update("""
                UPDATE memory_learning_profiles
                SET semantic_identity=?,content_revision=?,evidence_revision=?,updated_at=now()
                WHERE memory_id=?
                """, semanticIdentity, contentRevision, evidenceRevision, memoryId);
    }

    @Override
    public void refreshLearningEvidence(UUID memoryId, String semanticIdentity,
            String evidenceRevision, List<Observation> evidence) {
        String current = jdbc.queryForObject("""
                SELECT evidence_revision FROM memory_learning_profiles WHERE memory_id=? FOR UPDATE
                """, String.class, memoryId);
        if (evidenceRevision.equals(current)) return;
        insertEvidence(memoryId, semanticIdentity, evidence);
        jdbc.update("""
                UPDATE memory_learning_profiles
                SET evidence_revision=?,usefulness_state='ACTIVE',updated_at=now() WHERE memory_id=?
                """, evidenceRevision, memoryId);
    }

    @Override
    public void markLearningProfileStale(UUID memoryId) {
        jdbc.update("""
                UPDATE memory_learning_profiles
                SET usefulness_state='STALE_EVIDENCE',learning_revision=learning_revision+1,updated_at=now()
                WHERE memory_id=? AND usefulness_state<>'ARCHIVED'
                """, memoryId);
    }

    @Override
    public PurgeResult purgeExpired(Instant now, int batchSize) {
        int limit = Math.max(1, Math.min(batchSize, 1000));
        Timestamp cutoff = timestamp(now);
        int operations = jdbc.update("""
                WITH doomed AS (
                    SELECT request_id FROM learning_operations
                    WHERE expires_at<=? ORDER BY expires_at LIMIT ? FOR UPDATE SKIP LOCKED
                )
                DELETE FROM learning_operations o USING doomed d WHERE o.request_id=d.request_id
                """, cutoff, limit);
        int contexts = jdbc.update("""
                WITH doomed AS (
                    SELECT id FROM research_contexts
                    WHERE expires_at<=? ORDER BY expires_at LIMIT ? FOR UPDATE SKIP LOCKED
                )
                DELETE FROM research_contexts c USING doomed d WHERE c.id=d.id
                """, cutoff, limit);
        int bindings = jdbc.update("""
                WITH doomed AS (
                    SELECT b.id FROM agent_workspace_bindings b
                    WHERE b.expires_at<=?
                      AND NOT EXISTS (SELECT 1 FROM research_contexts c WHERE c.workspace_binding_id=b.id)
                    ORDER BY b.expires_at LIMIT ? FOR UPDATE SKIP LOCKED
                )
                DELETE FROM agent_workspace_bindings b USING doomed d WHERE b.id=d.id
                """, cutoff, limit);
        return new PurgeResult(operations, contexts, bindings);
    }

    private void insertEvidence(UUID memoryId, String canonicalHash, List<Observation> evidence) {
        for (Observation item : evidence) {
            jdbc.update("""
                    INSERT INTO memory_learning_evidence
                        (id,memory_id,claim_hash,evidence_kind,canonical_ref,observed_hash,observed_at,
                         workspace_snapshot_id,evidence_origin,relation_to_claim)
                    VALUES (?,?,?,'source',?,?,?,?,?,'supports') ON CONFLICT DO NOTHING
                    """, UUID.randomUUID(), memoryId, canonicalHash, item.resourceRef(), item.rawContentHash(),
                    timestamp(item.observedAt()), item.workspaceSnapshotId(), item.provenance());
        }
    }

    private void insertAnchors(UUID memoryId, Candidate candidate, List<Target> targets) {
        int index = 0;
        for (var anchor : candidate.anchors()) {
            Target target = targets.stream().filter(value -> value.targetId().equals(anchor.targetId()))
                    .findFirst().orElseThrow(() -> new IllegalArgumentException("ANCHOR_TARGET_UNKNOWN"));
            jdbc.update("""
                    INSERT INTO memory_navigation_anchors
                        (id,memory_id,locator_index,canonical_ref,anchor_role,priority,symbol_key,resolution_state,
                         locator_kind)
                    VALUES (?,?,?,?,?,?,?,'RESOLVED',?)
                    """, UUID.randomUUID(), memoryId, index, target.relativePath(), anchor.role(), index + 1,
                    target.symbolRef(), target.locatorKind());
            index++;
        }
    }

    private Operation requireOperation(UUID requestId, UUID learningHandle) {
        return findOperation(requestId, learningHandle)
                .orElseThrow(() -> new IllegalStateException("learning operation disappeared"));
    }

    private WorkspaceBinding binding(ResultSet rs, int row) throws SQLException {
        return new WorkspaceBinding(rs.getObject("id", UUID.class), rs.getString("principal_key"),
                rs.getString("client_id"), rs.getString("project_key"),
                rs.getString("repository_fingerprint"), Path.of(rs.getString("root_path")),
                instant(rs, "created_at"), instant(rs, "expires_at"));
    }

    private Target target(ResultSet rs, int row) throws SQLException {
        return new Target(rs.getString("target_id"), rs.getString("relative_path"), rs.getString("symbol_ref"),
                rs.getString("target_role"), rs.getString("resolved_hash"),
                integer(rs, "start_line"), integer(rs, "end_line"), rs.getString("locator_kind"));
    }

    private ReferenceTarget referenceTarget(ResultSet rs, int row) throws SQLException {
        return new ReferenceTarget(rs.getString("target_id"), rs.getObject("reference_id", UUID.class),
                rs.getString("relative_path"), rs.getString("content_hash"), rs.getString("section_key"),
                rs.getObject("source_memory_id", UUID.class));
    }

    private Observation observation(ResultSet rs, int row) throws SQLException {
        return new Observation(rs.getObject("id", UUID.class), rs.getObject("context_id", UUID.class),
                rs.getString("target_id"), rs.getString("operation_kind"), rs.getString("resource_ref"),
                rs.getString("raw_content_hash"), rs.getString("redacted_content_hash"),
                rs.getObject("workspace_snapshot_id", UUID.class), rs.getInt("byte_count"),
                rs.getString("provenance"), instant(rs, "observed_at"));
    }

    private Operation operation(ResultSet rs, int row) throws SQLException {
        return new Operation(rs.getObject("request_id", UUID.class), rs.getObject("learning_handle", UUID.class),
                rs.getObject("context_id", UUID.class), rs.getString("principal_key"), rs.getString("project_key"),
                rs.getString("payload_hash"), rs.getString("state"), rs.getString("result_json"),
                instantNullable(rs, "lease_until"), instant(rs, "created_at"), instant(rs, "updated_at"),
                instant(rs, "expires_at"));
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static String scopeHash(String value) {
        return value == null || value.isBlank() ? "0".repeat(64) : value;
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        return rs.getTimestamp(column).toInstant();
    }

    private static Instant instantNullable(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static Integer integer(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private record RouteRow(UUID memoryId, String summary, String text, String verification, String memoryStatus,
            String usefulnessState, long learningRevision, String canonicalHash,
            String relativePath, String symbolRef, String locatorKind, String role, int priority,
            String expectedHash, String resolutionState) {
    }
}
