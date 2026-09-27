package com.mbworldwideapps.aiorchestration.modules.memoryai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Commits a completed judge response, never calls a provider or the explicit write path. */
@Service
public class MemoryRelationJudgmentStore {
    public enum Action { EDGE, REVIEW, UNRELATED, LOW_CONFIDENCE, FAILED, SKIPPED_EXPLICIT }
    public enum Failure { TIMEOUT, DEGRADED, PROVIDER_ERROR, MALFORMED, INPUT_CHANGED, INELIGIBLE, SOURCE_MISSING }
    public record Guard(String projectKey, UUID sourceId, UUID targetId, String sourceHash, String targetHash, String rubricVersion) {
        public Guard {
            if (projectKey == null || projectKey.isBlank() || sourceId == null || targetId == null
                    || sourceHash == null || !sourceHash.matches("[0-9a-f]{64}")
                    || targetHash == null || !targetHash.matches("[0-9a-f]{64}")
                    || rubricVersion == null || rubricVersion.isBlank() || rubricVersion.length() > 128)
                throw new IllegalArgumentException("Invalid judge guard");
        }
    }
    public record Result(Action action, UUID relationId, boolean changed, String reason) {}
    private final JdbcTemplate jdbc;
    private final MemoryRepository memories;
    private final RuleMemoryInjectionFilter rules;
    private final MemoryRelationJudgeContract contract;
    private final ObjectMapper json;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate transactions;

    public MemoryRelationJudgmentStore(JdbcTemplate jdbc, MemoryRepository memories, RuleMemoryInjectionFilter rules,
            MemoryRelationJudgeContract contract, ObjectMapper json, ApplicationEventPublisher events,
            PlatformTransactionManager manager) {
        this.jdbc = jdbc; this.memories = memories; this.rules = rules; this.contract = contract;
        this.json = json; this.events = events; this.transactions = new TransactionTemplate(manager);
    }

    public Result complete(Guard guard, String response, double threshold) {
        MemoryRelationJudgeContract.Verdict verdict;
        try { verdict = contract.parse(response, threshold); }
        catch (IllegalArgumentException e) { return failed(guard, Failure.MALFORMED); }
        return transactions.execute(tx -> persist(guard, verdict, null));
    }

    public Result failed(Guard guard, Failure failure) {
        Objects.requireNonNull(failure, "failure");
        if (!Set.of(Failure.TIMEOUT, Failure.DEGRADED, Failure.PROVIDER_ERROR, Failure.MALFORMED).contains(failure))
            throw new IllegalArgumentException("failed accepts only provider or parse failures");
        return transactions.execute(tx -> persist(guard, null, failure));
    }

    /** Optimization before a provider call; commit still checks under endpoint locks. */
    public boolean alreadyEvaluated(Guard guard) { return sameGuard(latest(guard), guard); }

    private Result persist(Guard g, MemoryRelationJudgeContract.Verdict verdict, Failure failure) {
        // Matches MemoryRelationService's lock order, including reverse directed calls.
        List<UUID> ids = new ArrayList<>(new HashSet<>(List.of(g.sourceId(), g.targetId())));
        ids.sort(Comparator.comparing(UUID::toString));
        for (UUID id : ids) jdbc.queryForList("SELECT id FROM memory_items WHERE id=? FOR UPDATE", id);
        MemoryItem source = memories.findById(g.sourceId()).orElse(null);
        MemoryItem target = memories.findById(g.targetId()).orElse(null);
        if (source == null) return new Result(Action.FAILED, null, false, Failure.SOURCE_MISSING.name());
        if (source.scope() != MemoryScope.PROJECT || !g.projectKey().equals(source.projectKey()))
            return new Result(Action.FAILED, null, false, Failure.INELIGIBLE.name());
        boolean eligible = !g.sourceId().equals(g.targetId()) && eligible(source, g.projectKey(), true)
                && eligible(target, g.projectKey(), false);
        if (!eligible) return record(g, null, new Result(Action.FAILED, null, false, Failure.INELIGIBLE.name()), false);
        if (!MemoryRelationService.contentHash(source.summary(), source.text()).equals(g.sourceHash())
                || !MemoryRelationService.contentHash(target.summary(), target.text()).equals(g.targetHash()))
            return record(g, null, new Result(Action.FAILED, null, false, Failure.INPUT_CHANGED.name()), false);

        // A failed attempt is not a semantic verdict, even if an earlier attempt completed this guard.
        if (failure != null)
            return record(g, null, new Result(Action.FAILED, null, false, failure.name()), false);

        String type = verdict == null ? "" : verdict.relationshipType();
        boolean swap = Set.of("related_to", "alternative_to").contains(type)
                && g.sourceId().toString().compareTo(g.targetId().toString()) > 0;
        UUID from = swap ? g.targetId() : g.sourceId(), to = swap ? g.sourceId() : g.targetId();
        String fromHash = swap ? g.targetHash() : g.sourceHash(), toHash = swap ? g.sourceHash() : g.targetHash();
        Map<String, Object> row = relation(from, to, type);
        if (row != null && !g.projectKey().equals(row.get("project_key")))
            return record(g, null, new Result(Action.FAILED, null, false, Failure.INELIGIBLE.name()), false);
        Map<String, Object> previous = latest(g);
        if (verdict != null && verdict.action() == MemoryRelationJudgeContract.Action.EDGE
                && row != null && "explicit".equals(row.get("provenance"))) {
            boolean changed = !sameGuard(previous, g) && retire(g) > 0;
            if (changed) events.publishEvent(new MemoryRelationService.Changed(g.sourceId()));
            return record(g, verdict, new Result(Action.SKIPPED_EXPLICIT, (UUID) row.get("id"), changed, "explicit_exists"), true);
        }

        if (sameGuard(previous, g)) {
            var repeated = new Result(Action.valueOf((String) previous.get("action")),
                    previous.containsKey("relationId") ? UUID.fromString((String) previous.get("relationId")) : null,
                    false, "already_evaluated");
            return record(g, null, repeated, true, previous);
        }

        // Every fresh pair evaluation retires prior judge evidence for this direction/symmetric pair.
        // A new positive verdict below can make only its own current evidence active again.
        int retired = retire(g);
        Result result;
        if (verdict.action() == MemoryRelationJudgeContract.Action.EDGE) {
            String status = target.status() == MemoryStatus.STALE ? "stale" : "active";
            UUID proposedId = UUID.randomUUID();
            int inserted = jdbc.update("""
                    INSERT INTO memory_relations(id,project_key,source_memory_id,target_memory_id,relationship_type,
                        provenance,confidence,explanation,source_hash,target_hash,status)
                    VALUES (?,?,?,?,?,'judge',?,?,?,?,?) ON CONFLICT DO NOTHING
                    """, proposedId, g.projectKey(), from, to, type, verdict.confidence(), verdict.explanation(), fromHash, toHash, status);
            row = relation(from, to, type);
            if (row == null) throw new IllegalStateException("Judge relation was not persisted");
            UUID id = (UUID) row.get("id");
            if ("explicit".equals(row.get("provenance"))) {
                result = new Result(Action.SKIPPED_EXPLICIT, id, retired > 0, "explicit_exists");
            } else {
                if (inserted == 0) jdbc.update("""
                        UPDATE memory_relations SET confidence=?,explanation=?,source_hash=?,target_hash=?,status=?,updated_at=now()
                        WHERE id=? AND provenance='judge'
                        """, verdict.confidence(), verdict.explanation(), fromHash, toHash, status, id);
                result = new Result(Action.EDGE, id, true, inserted == 1 ? "created" : "refreshed");
            }
        } else {
            result = new Result(Action.valueOf(verdict.action().name()), null, retired > 0, "evaluated");
            if (verdict.action() == MemoryRelationJudgeContract.Action.REVIEW && !hasOpenProposal(g)) {
                Map<String, Object> proposal = new LinkedHashMap<>(metadata(g, verdict, result, true));
                proposal.put("kind", "memory_relation_supersedes_proposal");
                proposal.put("replacementMemoryId", g.sourceId().toString());
                // No supersededBy field: this is a proposal, not a completed supersession.
                memories.insertReviewQueue(new ReviewQueueItem(UUID.randomUUID(), g.targetId(), ReviewReason.CONFLICT,
                        ReviewStatus.OPEN, proposal, Instant.now(), null));
            }
        }
        if (result.changed()) events.publishEvent(new MemoryRelationService.Changed(g.sourceId()));
        return record(g, verdict, result, true);
    }

    private boolean eligible(MemoryItem item, String project, boolean source) {
        return item != null && item.scope() == MemoryScope.PROJECT && project.equals(item.projectKey())
                && (item.status() == MemoryStatus.ACTIVE || (!source && item.status() == MemoryStatus.STALE))
                && (item.expiresAt() == null || item.expiresAt().isAfter(Instant.now()))
                && !(item.memoryType() == MemoryType.RULE && item.metadata().containsKey("promotedRuleId"))
                && rules.eligibleForAutomaticInjection(item.id(), item.memoryType());
    }

    private int retire(Guard g) {
        return jdbc.update("""
                UPDATE memory_relations SET status='stale',updated_at=now()
                WHERE project_key=? AND provenance='judge' AND status<>'stale' AND
                  ((source_memory_id=? AND target_memory_id=?) OR
                   (source_memory_id=? AND target_memory_id=? AND relationship_type IN ('related_to','alternative_to')))
                """, g.projectKey(), g.sourceId(), g.targetId(), g.targetId(), g.sourceId());
    }

    private boolean hasOpenProposal(Guard g) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM memory_review_queue
                    WHERE candidate_memory_id=? AND status='open'
                    AND metadata->>'kind'='memory_relation_supersedes_proposal'
                    AND metadata->>'replacementMemoryId'=?)
                """, Boolean.class, g.targetId(), g.sourceId().toString()));
    }

    private Map<String, Object> relation(UUID source, UUID target, String type) {
        var rows = jdbc.queryForList("SELECT * FROM memory_relations WHERE source_memory_id=? AND target_memory_id=? AND relationship_type=? FOR UPDATE",
                source, target, type);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private Map<String, Object> latest(Guard g) {
        var rows = jdbc.queryForList("""
                SELECT metadata::text FROM memory_events WHERE memory_id=? AND event_type='relation_judged'
                    AND metadata->>'candidateId'=? ORDER BY created_at DESC,id DESC LIMIT 1
                """, String.class, g.sourceId(), g.targetId().toString());
        if (rows.isEmpty()) return Map.of();
        try { return json.readValue(rows.getFirst(), new TypeReference<Map<String, Object>>() {}); }
        catch (java.io.IOException e) { throw new IllegalStateException("Invalid persisted judge event", e); }
    }

    private static boolean sameGuard(Map<String, Object> previous, Guard g) {
        return Boolean.TRUE.equals(previous.get("completed")) && g.projectKey().equals(previous.get("projectKey"))
                && g.sourceHash().equals(previous.get("sourceHash")) && g.targetHash().equals(previous.get("targetHash"))
                && g.rubricVersion().equals(previous.get("rubricVersion"));
    }

    private Result record(Guard g, MemoryRelationJudgeContract.Verdict verdict, Result result, boolean completed) {
        return record(g, verdict, result, completed, Map.of());
    }

    private Result record(Guard g, MemoryRelationJudgeContract.Verdict verdict, Result result, boolean completed, Map<String, Object> previous) {
        var metadata = new LinkedHashMap<>(previous);
        metadata.putAll(metadata(g, verdict, result, completed));
        try {
            jdbc.update("""
                    INSERT INTO memory_events(id,memory_id,event_type,metadata,created_at)
                    VALUES (?,?,'relation_judged',?::jsonb,clock_timestamp())
                    """, UUID.randomUUID(), g.sourceId(), json.writeValueAsString(metadata));
        } catch (java.io.IOException e) { throw new IllegalStateException("Cannot serialize judge event", e); }
        return result;
    }

    private static Map<String, Object> metadata(Guard g, MemoryRelationJudgeContract.Verdict verdict, Result result, boolean completed) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("projectKey", g.projectKey()); data.put("candidateId", g.targetId().toString());
        data.put("sourceHash", g.sourceHash()); data.put("targetHash", g.targetHash()); data.put("rubricVersion", g.rubricVersion());
        data.put("action", result.action().name()); data.put("reason", result.reason()); data.put("completed", completed);
        if (result.relationId() != null) data.put("relationId", result.relationId().toString());
        if (verdict != null) {
            data.put("relationshipType", verdict.relationshipType()); data.put("confidence", verdict.confidence());
            data.put("explanation", verdict.explanation());
        }
        return data;
    }
}
