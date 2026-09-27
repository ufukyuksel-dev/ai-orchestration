package com.mbworldwideapps.aiorchestration.modules.memoryai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mbworldwideapps.aiorchestration.config.MemoryCodeLinkProperties;
import com.mbworldwideapps.aiorchestration.modules.scanner.CodeBaselineRepository;
import com.mbworldwideapps.aiorchestration.modules.scanner.ScannerPayloadRedactor;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Canonical lifecycle evidence. Automatic observation can mark stale, never approve or delete a memory. */
@Service
public class MemoryLifecycleService {
    private static final Logger log = LoggerFactory.getLogger(MemoryLifecycleService.class);
    public enum Observation { OBSERVED, STALE, UNCHANGED, UNKNOWN, SKIPPED, CONFLICT }
    public record CodeTarget(String locatorKind, String canonicalRef, String targetContentHash) {}
    public record CodeSnapshot(String fingerprint, List<CodeTarget> targets, int missingTargets) {}
    private final JdbcTemplate jdbc;
    private final MemoryRepository memories;
    private final CodeBaselineRepository baseline;
    private final RuleMemoryLinkLookup rules;
    private final ApplicationEventPublisher events;
    private final ObjectMapper json;
    private final TransactionTemplate transactions;
    private final MemoryCodeLinkResolver resolver;
    private final RuleMemoryActivationPolicy activation;
    private final ScannerPayloadRedactor redactor;
    private final MemoryCodeLinkProperties graphProperties;

    public MemoryLifecycleService(JdbcTemplate jdbc, MemoryRepository memories, CodeBaselineRepository baseline,
            RuleMemoryLinkLookup rules, ApplicationEventPublisher events, ObjectMapper json, PlatformTransactionManager manager,
            MemoryCodeLinkResolver resolver, RuleMemoryActivationPolicy activation, ScannerPayloadRedactor redactor, MemoryCodeLinkProperties graphProperties) {
        this.jdbc = jdbc; this.memories = memories; this.baseline = baseline; this.rules = rules;
        this.events = events; this.json = json; this.transactions = new TransactionTemplate(manager);
        this.resolver = resolver; this.activation = activation; this.redactor = redactor; this.graphProperties = graphProperties;
    }

    public enum Decision { REVALIDATE, INVALIDATE, SUPERSEDE }
    public record Selection(Decision decision, String projectKey, UUID memoryId, UUID replacementId, UUID proposalId) {}
    public record Preview(Selection selection, String summary, String replacementSummary, String memoryHash,
            String replacementHash, CodeSnapshot codeEvidence, String previewHash) {}
    public record Approval(Selection selection, String previewHash, boolean humanConfirmed, String reason,
            String evidence, String humanRawText, String humanTurnRef) {}
    public record DecisionResult(UUID memoryId, MemoryStatus status, UUID replacementId, UUID proposalId) {}
    private record Proposal(UUID id, UUID memoryId, Map<String,Object> metadata, String status) {}
    private record Prepared(Preview preview, MemoryItem old, MemoryItem replacement, Proposal proposal) {}

    /** Read-only preview; locks end before returning and its digest must be rechecked on decision. */
    public Preview preview(Selection selection) {
        return transactions.execute(tx -> prepare(selection).preview());
    }

    public DecisionResult decide(Approval approval, String actor) {
        if (approval == null || !approval.humanConfirmed()) throw new IllegalArgumentException("Human confirmation is required");
        String reason = redactedText(approval.reason(), "reason", 2048);
        String humanText = redactedText(approval.humanRawText(), "humanRawText", 8192);
        String turn = redactedText(approval.humanTurnRef(), "humanTurnRef", 512);
        String auditActor = redactedText(actor, "actor", 256);
        if (approval.previewHash() == null || !approval.previewHash().matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Exact preview hash is required");
        String evidence = approval.selection() != null && approval.selection().decision() == Decision.REVALIDATE
                ? redactedText(approval.evidence(), "evidence", 8192) : "";
        return transactions.execute(tx -> {
            Prepared p = prepare(approval.selection());
            if (!p.preview().previewHash().equals(approval.previewHash()))
                throw new IllegalStateException("Lifecycle preview changed; preview again");
            Instant now = Instant.now();
            Decision decision = p.preview().selection().decision();
            Map<String,Object> audit = new LinkedHashMap<>();
            audit.put("action", decision.name().toLowerCase(Locale.ROOT));
            audit.put("actor", auditActor); audit.put("reason", reason); audit.put("humanConfirmed", true);
            audit.put("humanRawTextScrubbed", humanText); audit.put("humanTurnRef", turn);
            audit.put("humanRawTextHash", sha256(approval.humanRawText()));
            audit.put("humanRawTextLength", approval.humanRawText().length());
            audit.put("previewHash", approval.previewHash()); audit.put("memoryHash", p.preview().memoryHash());
            MemoryStatus status;
            MemoryEventType eventType;
            UUID proposalId = p.proposal() == null ? null : p.proposal().id();
            if (decision == Decision.REVALIDATE) {
                status = MemoryStatus.ACTIVE; eventType = MemoryEventType.ACCEPTED;
                audit.put("evidence", evidence); audit.put("codeFingerprint", p.preview().codeEvidence().fingerprint());
                // Accept this exact observation before activation, avoiding an immediate repeat of the accepted drift.
                Map<String,Object> observation = new LinkedHashMap<>();
                observation.put("action", "code_evidence_observed"); observation.put("kind", "code_drift");
                observation.put("codeFingerprint", p.preview().codeEvidence().fingerprint());
                observation.put("codeTargets", p.preview().codeEvidence().targets()); observation.put("missingTargets", 0);
                observation.put("memoryHash", p.preview().memoryHash()); observation.put("acceptedPreviewHash", approval.previewHash());
                memories.insertEvent(new MemoryEvent(UUID.randomUUID(), p.old().id(), MemoryEventType.UPDATED, observation, now));
                closeReviews(p.old().id(), "approved", audit, now, true);
            } else if (decision == Decision.INVALIDATE) {
                status = MemoryStatus.REJECTED; eventType = MemoryEventType.REJECTED;
                closeReviews(p.old().id(), "rejected", audit, now, false);
            } else {
                status = MemoryStatus.ARCHIVED; eventType = MemoryEventType.ARCHIVED;
                audit.put("supersededBy", p.replacement().id().toString());
                audit.put("sourceHash", p.preview().replacementHash()); audit.put("targetHash", p.preview().memoryHash());
                if (proposalId == null) {
                    proposalId = UUID.randomUUID();
                    Map<String,Object> proposal = new LinkedHashMap<>();
                    proposal.put("kind", "memory_relation_supersedes_proposal"); proposal.put("origin", "human");
                    proposal.put("replacementMemoryId", p.replacement().id().toString());
                    proposal.put("sourceHash", p.preview().replacementHash()); proposal.put("targetHash", p.preview().memoryHash());
                    memories.insertReviewQueue(new ReviewQueueItem(proposalId, p.old().id(), ReviewReason.CONFLICT,
                            ReviewStatus.OPEN, proposal, now, null));
                }
                audit.put("proposalId", proposalId.toString());
                jdbc.update("UPDATE memory_review_queue SET status='approved',reviewed_at=?,metadata=metadata || ?::jsonb WHERE id=? AND status='open'",
                        java.sql.Timestamp.from(now), serialize(audit), proposalId);
                // Other proposals are no longer actionable; do not falsely mark them as human-approved.
                closeReviews(p.old().id(), "rejected", Map.of("action", "closed_by_supersession", "proposalId", proposalId.toString()), now, false);
            }
            audit.put("oldStatus", p.old().status().value()); audit.put("newStatus", status.value());
            MemoryItem updated = withStatus(p.old(), status, now);
            if (decision == Decision.REVALIDATE)
                updated = new MemoryItem(updated.id(), updated.vectorId(), updated.scope(), updated.projectKey(), updated.memoryType(),
                        updated.summary(), updated.text(), updated.tags(), updated.confidence(), updated.status(), updated.sourceType(),
                        updated.sourceRef(), updated.owner(), updated.metadata(), updated.createdAt(), now, updated.lastUsedAt(), now, updated.expiresAt());
            memories.update(updated);
            memories.insertEvent(new MemoryEvent(UUID.randomUUID(), updated.id(), eventType, audit, now));
            events.publishEvent(new MemoryItemChangedEvent(updated));
            return new DecisionResult(updated.id(), status, p.replacement() == null ? null : p.replacement().id(), proposalId);
        });
    }

    private Prepared prepare(Selection input) {
        if (input == null || input.decision() == null || input.projectKey() == null || input.projectKey().isBlank()
                || input.projectKey().length() > 200) throw new IllegalArgumentException("Action and project are required");
        UUID oldId = input.memoryId(), newId = input.replacementId();
        Proposal proposed = null;
        if (input.proposalId() != null) {
            if (input.decision() != Decision.SUPERSEDE) throw new IllegalArgumentException("Proposal only applies to supersede");
            proposed = proposal(input.proposalId(), false);
            UUID proposedNew;
            try { proposedNew = UUID.fromString(Objects.toString(proposed.metadata().get("replacementMemoryId"), "")); }
            catch (IllegalArgumentException e) { throw new IllegalArgumentException("Invalid supersession proposal"); }
            if ((oldId != null && !oldId.equals(proposed.memoryId())) || (newId != null && !newId.equals(proposedNew)))
                throw new IllegalArgumentException("Proposal endpoints do not match");
            oldId = proposed.memoryId(); newId = proposedNew;
        }
        if (oldId == null || (input.decision() == Decision.SUPERSEDE && (newId == null || newId.equals(oldId)))
                || (input.decision() != Decision.SUPERSEDE && newId != null))
            throw new IllegalArgumentException("Invalid lifecycle endpoints");
        List<UUID> ids = new ArrayList<>(List.of(oldId)); if (newId != null) ids.add(newId);
        ids.sort(Comparator.comparing(UUID::toString));
        Map<UUID,MemoryItem> locked = new HashMap<>();
        for (UUID id : ids) {
            MemoryItem item = memories.findByIdForUpdate(id).orElseThrow(() -> new NoSuchElementException("Memory unavailable"));
            if (item.scope() != MemoryScope.PROJECT || !input.projectKey().equals(item.projectKey())
                    || (item.expiresAt() != null && !item.expiresAt().isAfter(Instant.now())))
                throw new NoSuchElementException("Memory unavailable");
            if (item.memoryType() == MemoryType.RULE && rules.hasLinkedDefinition(id))
                throw new IllegalArgumentException("Linked rule memory must be changed through rule authority");
            locked.put(id, item);
        }
        MemoryItem old = locked.get(oldId), replacement = locked.get(newId);
        if (input.decision() == Decision.REVALIDATE ? old.status() != MemoryStatus.STALE
                : old.status() != MemoryStatus.ACTIVE && old.status() != MemoryStatus.STALE)
            throw new IllegalStateException("Memory status is not eligible for this decision");
        if (replacement != null && replacement.status() != MemoryStatus.ACTIVE)
            throw new IllegalStateException("Replacement must be ACTIVE");
        String oldHash = MemoryRelationService.contentHash(old.summary(), old.text());
        String newHash = replacement == null ? null : MemoryRelationService.contentHash(replacement.summary(), replacement.text());
        if (proposed != null) {
            proposed = proposal(input.proposalId(), true);
            if (!oldId.equals(proposed.memoryId()) || !newId.toString().equals(proposed.metadata().get("replacementMemoryId"))
                    || !"open".equals(proposed.status()) || !"memory_relation_supersedes_proposal".equals(proposed.metadata().get("kind"))
                    || !oldHash.equals(proposed.metadata().get("targetHash")) || !newHash.equals(proposed.metadata().get("sourceHash")))
                throw new IllegalStateException("Supersession proposal changed or is no longer current");
        }
        CodeSnapshot code = null;
        if (input.decision() == Decision.REVALIDATE) {
            activation.assertActivationAllowed(old.memoryType(), old.status(), MemoryStatus.ACTIVE);
            if (!graphProperties.deterministicLinksEnabled())
                throw new IllegalStateException("Deterministic code evidence is disabled");
            if (baseline.latestCompletedRun(old.projectKey()).isEmpty()) throw new IllegalStateException("Code baseline unavailable");
            MemoryCodeLocatorMetadata.read(old.metadata()); // Malformed explicit bindings cannot be approved as empty evidence.
            code = snapshot(resolver.resolve(old));
            if (code.missingTargets() != 0) throw new IllegalStateException("Bound code target is missing");
        }
        Selection selection = new Selection(input.decision(), input.projectKey(), oldId, newId, input.proposalId());
        Map<String,Object> digest = new TreeMap<>();
        digest.put("selection", selection); digest.put("memory", memorySnapshot(old));
        digest.put("replacement", replacement == null ? null : memorySnapshot(replacement));
        digest.put("proposal", proposed); digest.put("codeEvidence", code);
        Preview preview = new Preview(selection, old.summary(), replacement == null ? null : replacement.summary(), oldHash, newHash,
                code, MemoryRelationService.contentHash("", serialize(digest)));
        return new Prepared(preview, old, replacement, proposed);
    }

    private Map<String,Object> memorySnapshot(MemoryItem item) {
        Map<String,Object> state = new TreeMap<>();
        state.put("id", item.id()); state.put("hash", MemoryRelationService.contentHash(item.summary(), item.text()));
        state.put("status", item.status().value()); state.put("type", item.memoryType().value());
        state.put("metadata", item.metadata()); state.put("sourceRef", item.sourceRef());
        state.put("updatedAt", Objects.toString(item.updatedAt(), "")); state.put("expiresAt", Objects.toString(item.expiresAt(), ""));
        return state;
    }

    private Proposal proposal(UUID id, boolean lock) {
        var rows = jdbc.query("SELECT candidate_memory_id,metadata::text,status FROM memory_review_queue WHERE id=?" + (lock ? " FOR UPDATE" : ""),
                (rs, row) -> {
                    try { return new Proposal(id, rs.getObject(1, UUID.class), json.readValue(rs.getString(2), new TypeReference<Map<String,Object>>() {}), rs.getString(3)); }
                    catch (java.io.IOException e) { throw new IllegalStateException("Invalid proposal metadata", e); }
                }, id);
        if (rows.isEmpty()) throw new NoSuchElementException("Proposal unavailable");
        return rows.getFirst();
    }

    private void closeReviews(UUID id, String status, Map<String,Object> audit, Instant now, boolean codeOnly) {
        jdbc.update("UPDATE memory_review_queue SET status=?,reviewed_at=?,metadata=metadata || ?::jsonb WHERE candidate_memory_id=? AND status='open'"
                        + (codeOnly ? " AND metadata->>'kind'='code_drift'" : ""),
                status, java.sql.Timestamp.from(now), serialize(audit), id);
    }

    private String redactedText(String value, String name, int maxBytes) {
        if (value == null || value.isBlank() || value.getBytes(StandardCharsets.UTF_8).length > maxBytes)
            throw new IllegalArgumentException(name + " is required and must fit its byte limit");
        String result = redactor.redact(value);
        if (result == null || result.isBlank() || result.getBytes(StandardCharsets.UTF_8).length > maxBytes)
            throw new IllegalArgumentException(name + " is empty or too large after redaction");
        return result;
    }

    private static String sha256(String text) {
        try { return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    /** A fingerprint uses semantic locator identity and code hashes, never target UUIDs or scan run IDs. */
    public CodeSnapshot snapshot(MemoryCodeLinkResolveResult resolved) {
        Set<CodeTarget> unique = new HashSet<>();
        for (var link : resolved.links()) {
            if ("similarity_backfill".equals(link.resolution())) continue;
            String ref = Objects.toString(link.evidence().get("canonicalRef"), "");
            String hash = Objects.toString(link.evidence().get("targetContentHash"), "");
            if (link.targetKind() == MemoryCodeTargetKind.DIRECTORY) { ref = link.directoryPath(); hash = "exists"; }
            if (link.targetKind() == MemoryCodeTargetKind.CAPSULE) { ref = link.capsuleLogicalKey(); hash = link.outputHash(); }
            if (ref == null || ref.isBlank() || hash == null || hash.isBlank())
                throw new IllegalArgumentException("Incomplete code evidence");
            unique.add(new CodeTarget(link.targetKind().name(), ref, hash));
            if (unique.size() > 64) throw new IllegalArgumentException("Code evidence exceeds target bound");
        }
        List<CodeTarget> targets = unique.stream().sorted(Comparator.comparing(CodeTarget::locatorKind)
                .thenComparing(CodeTarget::canonicalRef).thenComparing(CodeTarget::targetContentHash)).toList();
        int missing = resolved.missingTypedTargets();
        Map<String,Object> payloadFields = new LinkedHashMap<>();
        payloadFields.put("targets", targets); payloadFields.put("missingTargets", missing);
        String payload = serialize(payloadFields);
        if (payload.getBytes(StandardCharsets.UTF_8).length > 65_536)
            throw new IllegalArgumentException("Code evidence exceeds byte bound");
        return new CodeSnapshot(MemoryRelationService.contentHash("", payload), targets, missing);
    }

    public Observation observe(MemoryItem observed, MemoryCodeLinkResolveResult resolved) {
        if (observed == null || observed.scope() != MemoryScope.PROJECT || observed.projectKey() == null)
            return Observation.SKIPPED;
        if (baseline.latestCompletedRun(observed.projectKey()).isEmpty()) {
            log.warn("Memory code evidence unavailable: memoryId={} reason=no_completed_baseline", observed.id());
            return Observation.UNKNOWN;
        }
        CodeSnapshot snapshot;
        try { snapshot = snapshot(resolved); }
        catch (IllegalArgumentException e) {
            log.warn("Memory code evidence unavailable: memoryId={} reason=incomplete_or_oversized", observed.id());
            return Observation.UNKNOWN;
        }
        return transactions.execute(tx -> observeLocked(observed, snapshot));
    }

    private Observation observeLocked(MemoryItem observed, CodeSnapshot snapshot) {
        MemoryItem current = memories.findByIdForUpdate(observed.id()).orElse(null);
        if (current == null || current.scope() != MemoryScope.PROJECT || !Objects.equals(current.projectKey(), observed.projectKey())
                || !(current.status() == MemoryStatus.ACTIVE || current.status() == MemoryStatus.STALE)
                || (current.expiresAt() != null && !current.expiresAt().isAfter(Instant.now()))) return Observation.SKIPPED;
        if (!MemoryRelationService.contentHash(current.summary(), current.text()).equals(
                MemoryRelationService.contentHash(observed.summary(), observed.text()))
                || !Objects.equals(current.metadata(), observed.metadata()) || !Objects.equals(current.sourceRef(), observed.sourceRef()))
            return Observation.CONFLICT;
        if (current.memoryType() == MemoryType.RULE && rules.hasLinkedDefinition(current.id())) return Observation.SKIPPED;
        Map<String,Object> previous = latestCodeEvidence(current.id());
        if (snapshot.fingerprint().equals(previous.get("codeFingerprint"))) return Observation.UNCHANGED;
        if (previous.isEmpty() && snapshot.targets().isEmpty() && snapshot.missingTargets() == 0) return Observation.SKIPPED;
        boolean drift = snapshot.missingTargets() > 0 || !previous.isEmpty();
        Map<String,Object> evidence = new LinkedHashMap<>();
        evidence.put("action", "code_evidence_observed"); evidence.put("kind", "code_drift");
        evidence.put("codeFingerprint", snapshot.fingerprint()); evidence.put("codeTargets", snapshot.targets());
        evidence.put("missingTargets", snapshot.missingTargets());
        evidence.put("memoryHash", MemoryRelationService.contentHash(current.summary(), current.text()));
        if (!previous.isEmpty()) evidence.put("previousFingerprint", previous.get("codeFingerprint"));
        Instant now = Instant.now();
        memories.insertEvent(new MemoryEvent(UUID.randomUUID(), current.id(), MemoryEventType.UPDATED, evidence, now));
        if (!drift) return Observation.OBSERVED;
        if (!Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM memory_review_queue WHERE candidate_memory_id=? AND status='open'
                    AND metadata->>'kind'='code_drift')
                """, Boolean.class, current.id())))
            memories.insertReviewQueue(new ReviewQueueItem(UUID.randomUUID(), current.id(), ReviewReason.LOW_CONFIDENCE,
                    ReviewStatus.OPEN, evidence, now, null));
        if (current.status() != MemoryStatus.STALE) {
            MemoryItem stale = withStatus(current, MemoryStatus.STALE, now);
            memories.update(stale);
            Map<String,Object> transition = new LinkedHashMap<>(evidence);
            transition.put("action", "code_drift"); transition.put("oldStatus", current.status().value()); transition.put("newStatus", "stale");
            transition.put("actor", "system:code-evidence"); transition.put("reason", "Code evidence changed or a bound target is missing");
            memories.insertEvent(new MemoryEvent(UUID.randomUUID(), current.id(), MemoryEventType.STATUS_CHANGED, transition, now));
            events.publishEvent(new MemoryItemChangedEvent(stale));
        }
        return Observation.STALE;
    }

    private Map<String,Object> latestCodeEvidence(UUID id) {
        var rows = jdbc.queryForList("""
                SELECT metadata::text FROM memory_events WHERE memory_id=? AND event_type='updated'
                    AND metadata->>'action'='code_evidence_observed' ORDER BY created_at DESC,id DESC LIMIT 1
                """, String.class, id);
        if (rows.isEmpty()) return Map.of();
        try { return json.readValue(rows.getFirst(), new TypeReference<Map<String,Object>>() {}); }
        catch (java.io.IOException e) { throw new IllegalStateException("Invalid code evidence event", e); }
    }

    private String serialize(Object value) {
        try { return json.writeValueAsString(value); }
        catch (java.io.IOException e) { throw new IllegalArgumentException("Cannot serialize code evidence", e); }
    }

    private static MemoryItem withStatus(MemoryItem item, MemoryStatus status, Instant now) {
        return new MemoryItem(item.id(), item.vectorId(), item.scope(), item.projectKey(), item.memoryType(), item.summary(), item.text(),
                item.tags(), item.confidence(), status, item.sourceType(), item.sourceRef(), item.owner(), item.metadata(),
                item.createdAt(), now, item.lastUsedAt(), item.lastVerifiedAt(), item.expiresAt());
    }
}
