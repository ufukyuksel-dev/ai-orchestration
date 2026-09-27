package com.mbworldwideapps.aiorchestration.modules.memoryai;

import com.mbworldwideapps.aiorchestration.modules.scanner.ScannerPayloadRedactor;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Canonical explicit relationships. This service neither activates memories nor claims graph projection. */
@Service
public class MemoryRelationService {
    private static final Set<String> MEMORY_TYPES = Set.of("related_to", "extends", "depends_on", "alternative_to", "causes");
    private final org.springframework.context.ApplicationEventPublisher events;
    public record Changed(UUID memoryId) {}
    private final JdbcTemplate jdbc;
    private final ScannerPayloadRedactor redactor;
    private final TransactionTemplate transactions;
    private final com.mbworldwideapps.aiorchestration.modules.references.ReferenceService references;

    public MemoryRelationService(JdbcTemplate jdbc, ScannerPayloadRedactor redactor, PlatformTransactionManager manager, org.springframework.context.ApplicationEventPublisher events) {
        this(jdbc, redactor, manager, events, null);
    }

    @Autowired
    public MemoryRelationService(JdbcTemplate jdbc, ScannerPayloadRedactor redactor, PlatformTransactionManager manager,
            org.springframework.context.ApplicationEventPublisher events,
            com.mbworldwideapps.aiorchestration.modules.references.ReferenceService references) {
        this.events = events;
        this.jdbc = jdbc;
        this.redactor = redactor;
        this.transactions = new TransactionTemplate(manager);
        this.references = references;
    }

    public record Relation(UUID id, String projectKey, UUID sourceId, String targetKind, UUID targetId,
            String type, String provenance, double confidence, String explanation,
            String sourceHash, String targetHash, String status,
            String referenceSectionKey, String referenceContentHash) {}
    public record Saved(Relation relation, boolean created, boolean refreshed) {}
    private record Endpoint(String hash, boolean stale) {}

    public Saved write(String project, UUID source, String targetKind, UUID target, String type, String explanation) {
        return write(project, source, targetKind, target, type, explanation, null, null);
    }

    public Saved write(String project, UUID source, String targetKind, UUID target, String type, String explanation,
            String referenceSectionKey, String referenceContentHash) {
        if (project == null || project.isBlank() || source == null || target == null)
            throw new IllegalArgumentException("Project and both endpoint IDs are required");
        if (!("memory".equals(targetKind) && MEMORY_TYPES.contains(type == null ? "" : type))
                && !("reference".equals(targetKind) && "references".equals(type)))
            throw new IllegalArgumentException("Unsupported relationship type or target kind; supersedes requires the review lifecycle");
        if ("memory".equals(targetKind) && source.equals(target))
            throw new IllegalArgumentException("Self relationships are not allowed");
        String clean = boundedExplanation(explanation);
        clean = boundedExplanation(redactor.redact(clean));
        boolean swap = Set.of("related_to", "alternative_to").contains(type)
                && source.toString().compareTo(target.toString()) > 0;
        UUID canonicalSource = swap ? target : source;
        UUID canonicalTarget = swap ? source : target;
        String safeExplanation = clean;
        boolean hasSection = referenceSectionKey != null || referenceContentHash != null;
        if (hasSection && (!"reference".equals(targetKind) || referenceSectionKey == null
                || referenceContentHash == null)) {
            throw new IllegalArgumentException("Reference sectionKey and contentHash must be supplied together");
        }
        if (hasSection) {
            if (references == null) throw new IllegalStateException("Reference section resolver unavailable");
            // ReferenceService commits its current/stale catalog observation before
            // a conflict is raised; do not wrap that observation in the relation
            // transaction or a failed CAS would erase the drift signal.
            references.readSection(canonicalTarget, referenceContentHash, referenceSectionKey, 4);
        }
        return transactions.execute(status -> writeLocked(project, canonicalSource, targetKind, canonicalTarget,
                type, safeExplanation, referenceSectionKey, referenceContentHash));
    }

    private Saved writeLocked(String project, UUID source, String targetKind, UUID target, String type, String explanation) {
        return writeLocked(project, source, targetKind, target, type, explanation, null, null);
    }

    private Saved writeLocked(String project, UUID source, String targetKind, UUID target, String type,
            String explanation, String referenceSectionKey, String referenceContentHash) {
        // Always lock memory endpoints in UUID order, even for directed edges.
        Endpoint from;
        Endpoint to;
        if ("memory".equals(targetKind) && source.toString().compareTo(target.toString()) > 0) {
            to = memory(project, target);
            from = memory(project, source);
        } else {
            from = memory(project, source);
            to = "memory".equals(targetKind) ? memory(project, target) : reference(target);
        }
        if (referenceContentHash != null && !Objects.equals(referenceContentHash, to.hash())) {
            throw new IllegalArgumentException("REFERENCE_CONTENT_CHANGED");
        }
        String state = from.stale() || to.stale() ? "stale" : "active";
        UUID id = UUID.randomUUID();
        int inserted = jdbc.update("""
                INSERT INTO memory_relations(id, project_key, source_memory_id, target_memory_id, reference_id,
                    relationship_type, provenance, confidence, explanation, source_hash, target_hash, status,
                    reference_section_key,reference_content_hash)
                VALUES (?, ?, ?, ?, ?, ?, 'explicit', 1, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING
                """, id, project, source, "memory".equals(targetKind) ? target : null,
                "reference".equals(targetKind) ? target : null, type, explanation, from.hash(), to.hash(), state,
                referenceSectionKey, referenceContentHash);
        String targetColumn = "memory".equals(targetKind) ? "target_memory_id" : "reference_id";
        Relation relation = jdbc.queryForObject("SELECT * FROM memory_relations WHERE source_memory_id=? AND "
                + targetColumn + "=? AND relationship_type=? FOR UPDATE", MemoryRelationService::map, source, target, type);
        boolean promoteJudge = relation.provenance().equals("judge");
        boolean sectionUpgrade = relation.referenceSectionKey() == null && referenceSectionKey != null;
        boolean sameSectionRefresh = relation.referenceSectionKey() != null && referenceSectionKey != null
                && Objects.equals(relation.referenceSectionKey(), referenceSectionKey)
                && !Objects.equals(relation.referenceContentHash(), referenceContentHash);
        boolean sectionConflict = relation.referenceSectionKey() != null && referenceSectionKey != null
                && !Objects.equals(relation.referenceSectionKey(), referenceSectionKey);
        if (!relation.projectKey().equals(project)
                || (!promoteJudge && !relation.explanation().equals(explanation))
                || sectionConflict)
            throw new IllegalArgumentException("Relationship conflict: existing evidence differs; no record was overwritten");
        String effectiveSectionKey = sectionUpgrade || sameSectionRefresh
                ? referenceSectionKey : relation.referenceSectionKey();
        String effectiveContentHash = sectionUpgrade || sameSectionRefresh
                ? referenceContentHash : relation.referenceContentHash();
        boolean refreshed = promoteJudge || !relation.sourceHash().equals(from.hash())
                || !Objects.equals(relation.targetHash(), to.hash()) || !relation.status().equals(state)
                || sectionUpgrade || sameSectionRefresh;
        if (refreshed) {
            jdbc.update("""
                    UPDATE memory_relations SET provenance='explicit',confidence=1,explanation=?,
                        source_hash=?,target_hash=?,status=?,reference_section_key=?,reference_content_hash=?,
                        updated_at=now() WHERE id=?
                    """, explanation, from.hash(), to.hash(), state, effectiveSectionKey,
                    effectiveContentHash, relation.id());
            relation = new Relation(relation.id(), project, source, targetKind, target, type, "explicit", 1,
                    explanation, from.hash(), to.hash(), state, effectiveSectionKey, effectiveContentHash);
        }
        events.publishEvent(new Changed(source));
        return new Saved(relation, inserted == 1, refreshed);
    }

    public record ReferenceLink(UUID memoryId, UUID referenceId, String relativePath, String kind,
            String catalogStatus, boolean evidenceStale, String contentHash, String sectionKey) {}
    public record ReferenceLinks(List<ReferenceLink> items, boolean hasMore) {}

    /** SQL catalog hints only. Caller must enforce local-trust before accessing shared paths. */
    public ReferenceLinks linkedReferences(String project, List<UUID> memoryIds) {
        if (project == null || project.isBlank() || memoryIds == null || memoryIds.size() > 100)
            throw new IllegalArgumentException("Explicit project and bounded memory IDs required");
        if (memoryIds.isEmpty()) return new ReferenceLinks(List.of(), false);
        String slots = String.join(",", java.util.Collections.nCopies(memoryIds.size(), "?"));
        var args = new java.util.ArrayList<Object>();
        args.add(project); args.add(project); args.addAll(memoryIds);
        List<ReferenceLink> rows = jdbc.query("""
                SELECT m.id AS memory_id,m.summary,m.text,m.status AS memory_status,
                    r.id AS reference_id,r.relative_path,r.kind,r.status AS catalog_status,r.content_hash,
                    l.source_hash,l.target_hash,l.status AS link_status,
                    l.reference_section_key,l.reference_content_hash
                FROM memory_relations l JOIN memory_items m ON m.id=l.source_memory_id
                    JOIN reference_items r ON r.id=l.reference_id
                WHERE l.project_key=? AND m.project_key=? AND m.scope='project'
                    AND m.status IN ('active','stale') AND (m.expires_at IS NULL OR m.expires_at>now())
                    AND l.relationship_type='references' AND m.id IN (
                """ + slots + ") ORDER BY m.id,r.id LIMIT 7", (rs,n) -> new ReferenceLink(
                    rs.getObject("memory_id", UUID.class), rs.getObject("reference_id", UUID.class),
                    rs.getString("relative_path"), rs.getString("kind"), rs.getString("catalog_status"),
                    !"active".equals(rs.getString("memory_status")) || !"active".equals(rs.getString("link_status"))
                        || !"current".equals(rs.getString("catalog_status"))
                        || !Objects.equals(rs.getString("source_hash"), contentHash(rs.getString("summary"), rs.getString("text")))
                        || !Objects.equals(rs.getString("target_hash"), rs.getString("content_hash"))
                        || (rs.getString("reference_content_hash") != null
                            && !Objects.equals(rs.getString("reference_content_hash"), rs.getString("content_hash"))),
                    rs.getString("reference_content_hash"), rs.getString("reference_section_key")), args.toArray());
        return new ReferenceLinks(List.copyOf(rows.subList(0, Math.min(6, rows.size()))), rows.size() > 6);
    }

    private Endpoint memory(String project, UUID id) {
        List<Endpoint> rows = jdbc.query("""
                SELECT summary, text, status FROM memory_items
                WHERE id=? AND project_key=? AND scope='project' AND status IN ('active','stale')
                    AND (expires_at IS NULL OR expires_at > now()) FOR UPDATE
                """, (rs, n) -> new Endpoint(contentHash(rs.getString("summary"), rs.getString("text")),
                "stale".equals(rs.getString("status"))), id, project);
        if (rows.isEmpty()) throw new IllegalArgumentException("Memory endpoint is not eligible in this project");
        return rows.getFirst();
    }

    private Endpoint reference(UUID id) {
        List<Endpoint> rows = jdbc.query("""
                SELECT content_hash, status FROM reference_items WHERE id=? AND status <> 'missing' FOR SHARE
                """, (rs, n) -> new Endpoint(rs.getString("content_hash"), !"current".equals(rs.getString("status"))), id);
        if (rows.isEmpty()) throw new IllegalArgumentException("Reference endpoint is unavailable");
        return rows.getFirst();
    }

    private static Relation map(ResultSet rs, int n) throws SQLException {
        UUID memory = rs.getObject("target_memory_id", UUID.class);
        return new Relation(rs.getObject("id", UUID.class), rs.getString("project_key"),
                rs.getObject("source_memory_id", UUID.class), memory == null ? "reference" : "memory",
                memory == null ? rs.getObject("reference_id", UUID.class) : memory,
                rs.getString("relationship_type"), rs.getString("provenance"), rs.getDouble("confidence"),
                rs.getString("explanation"), rs.getString("source_hash"), rs.getString("target_hash"), rs.getString("status"),
                rs.getString("reference_section_key"), rs.getString("reference_content_hash"));
    }

    private static String boundedExplanation(String value) {
        if (value == null || value.isBlank() || value.length() > 2048)
            throw new IllegalArgumentException("Explanation must contain 1..2048 characters");
        return value.trim();
    }

    /** Length-framed summary plus text; both contribute to relationship evidence freshness. */
    public static String contentHash(String summary, String text) {
        try {
            byte[] prefix = summary.getBytes(StandardCharsets.UTF_8);
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            hash.update((prefix.length + ":").getBytes(StandardCharsets.UTF_8));
            hash.update(prefix);
            hash.update(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
