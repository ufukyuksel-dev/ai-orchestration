package com.mbworldwideapps.aiorchestration.modules.memoryai;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
final class JdbcRuleMemoryLinkLookup implements RuleMemoryLinkLookup {

    private final JdbcTemplate jdbcTemplate;

    JdbcRuleMemoryLinkLookup(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public boolean hasLinkedDefinition(UUID memoryId) {
        Boolean linked = jdbcTemplate.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                    FROM rule_definitions
                    WHERE origin_memory_id = ?
                    UNION ALL
                    SELECT 1
                    FROM rule_versions
                    WHERE origin_memory_id = ?
                )
                """, Boolean.class, memoryId, memoryId);
        return Boolean.TRUE.equals(linked);
    }

    @Override
    public Set<UUID> linkedMemoryIds(Collection<UUID> memoryIds) {
        if (memoryIds == null || memoryIds.isEmpty()) {
            return Set.of();
        }
        List<UUID> ids = memoryIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Set.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        return jdbcTemplate.query(("""
                SELECT DISTINCT origins.origin_memory_id
                FROM (
                    SELECT origin_memory_id FROM rule_definitions
                    UNION ALL
                    SELECT origin_memory_id FROM rule_versions
                ) origins
                WHERE origins.origin_memory_id IN (%s)
                  AND origins.origin_memory_id IS NOT NULL
                """).formatted(placeholders),
                (rs, rowNum) -> rs.getObject("origin_memory_id", UUID.class), ids.toArray()).stream()
                .collect(Collectors.toUnmodifiableSet());
    }
}
