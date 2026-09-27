package com.mbworldwideapps.aiorchestration.modules.memoryai;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Memory-backed revisions require a fresh pending origin; the linked historical origin stays
 * immutable.
 */
@Service
public class WorkspaceRuleOriginService {
    public record Origin(UUID id, String contentHash) {}

    private final MemoryService memory;

    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    public WorkspaceRuleOriginService(
            MemoryService memory, org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.memory = memory;
        this.jdbc = jdbc;
    }

    public void archiveSuperseded(UUID ruleId, int baseVersion, UUID selected, String actor) {
        var ids =
                jdbc.query(
                        """
                        SELECT id FROM memory_items
                        WHERE memory_type = 'rule' AND status = 'pending_review'
                          AND metadata->>'workspaceRuleId' = ? AND source_ref LIKE ? AND id <> ?
                        ORDER BY id FOR UPDATE
                        """,
                        (rs, n) -> rs.getObject("id", UUID.class),
                        ruleId.toString(),
                        "workspace:rule:" + ruleId + ":" + baseVersion + ":%",
                        selected);
        for (UUID id : ids) {
            memory.updateStatus(id, MemoryStatus.ARCHIVED, actor, "superseded workspace draft");
        }
    }

    public Origin draft(
            UUID ruleId,
            int version,
            UUID previous,
            String project,
            String statement,
            String actor) {
        String text = statement.trim(),
                summary = text.length() > 200 ? text.substring(0, 200) : text;
        String hash = RuleMemoryContentHash.compute(summary, text);
        String ref = "workspace:rule:" + ruleId + ":" + version + ":" + hash;
        MemoryItem item =
                memory.findBySourceRef(ref)
                        .orElseGet(
                                () ->
                                        memory.create(
                                                new CreateMemoryRequest(
                                                        project == null
                                                                ? MemoryScope.GLOBAL
                                                                : MemoryScope.PROJECT,
                                                        project,
                                                        MemoryType.RULE,
                                                        summary,
                                                        text,
                                                        List.of(),
                                                        1.0,
                                                        MemoryStatus.PENDING_REVIEW,
                                                        MemorySourceType.MANUAL,
                                                        ref,
                                                        actor,
                                                        Map.of(
                                                                "revisionOf",
                                                                previous.toString(),
                                                                "workspaceRuleId",
                                                                ruleId.toString()),
                                                        null,
                                                        null)));
        if (item.memoryType() != MemoryType.RULE
                || !hash.equals(RuleMemoryContentHash.compute(item.summary(), item.text())))
            throw new IllegalStateException("Kural taslağı değişti; yeni önizleme oluşturun");
        return new Origin(item.id(), hash);
    }
}
