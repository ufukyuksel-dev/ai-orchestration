package com.mbworldwideapps.aiorchestration.modules.scanner;

import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
class JdbcScannerProjectRootRegistry implements ScannerProjectRootRegistry {

    private final JdbcTemplate jdbcTemplate;

    JdbcScannerProjectRootRegistry(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional
    public ScannerRootPreparation prepare(String projectKey, String rootPath) {
        String normalizedProjectKey = require(projectKey, "projectKey");
        String normalizedRootPath = require(rootPath, "rootPath");
        claim(normalizedProjectKey, normalizedRootPath);

        List<UUID> removedCapsuleIds = jdbcTemplate.queryForList("""
                SELECT capsule.id
                FROM code_semantic_capsules capsule
                JOIN code_files file ON file.id = capsule.file_id
                JOIN code_scan_runs run ON run.id = file.last_scan_run_id
                WHERE capsule.project_key = ?
                  AND run.project_key = ?
                  AND run.root_path <> ?
                """, UUID.class, normalizedProjectKey, normalizedProjectKey, normalizedRootPath);

        int fileStatesDeleted = jdbcTemplate.update("""
                DELETE FROM scanner_file_state state
                USING code_files file, code_scan_runs run
                WHERE state.project_key = ?
                  AND file.project_key = ?
                  AND state.file_path = file.file_path
                  AND file.last_scan_run_id = run.id
                  AND run.project_key = ?
                  AND run.root_path <> ?
                """, normalizedProjectKey, normalizedProjectKey, normalizedProjectKey, normalizedRootPath);

        int filesDeleted = jdbcTemplate.update("""
                DELETE FROM code_files file
                USING code_scan_runs run
                WHERE file.project_key = ?
                  AND file.last_scan_run_id = run.id
                  AND run.project_key = ?
                  AND run.root_path <> ?
                """, normalizedProjectKey, normalizedProjectKey, normalizedRootPath);

        int anchorsStaled = 0; // semantic anchors were retired with Neo4j
        return new ScannerRootPreparation(normalizedProjectKey, normalizedRootPath, filesDeleted,
                fileStatesDeleted, anchorsStaled, removedCapsuleIds);
    }

    private void claim(String projectKey, String rootPath) {
        int claimed = jdbcTemplate.update("""
                INSERT INTO scanner_project_roots (project_key, root_path)
                VALUES (?, ?)
                ON CONFLICT (project_key) DO UPDATE
                SET last_seen_at = now()
                WHERE scanner_project_roots.root_path = EXCLUDED.root_path
                """, projectKey, rootPath);
        if (claimed > 0) {
            return;
        }
        String boundRoot = jdbcTemplate.queryForObject("""
                SELECT root_path
                FROM scanner_project_roots
                WHERE project_key = ?
                """, String.class, projectKey);
        throw new ScannerProjectRootMismatchException(projectKey, rootPath, boundRoot);
    }

    private static String require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
