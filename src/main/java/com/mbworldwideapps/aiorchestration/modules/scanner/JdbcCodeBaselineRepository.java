package com.mbworldwideapps.aiorchestration.modules.scanner;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcCodeBaselineRepository implements CodeBaselineRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final RowMapper<CodeFileRecord> fileMapper = (rs, rowNum) -> new CodeFileRecord(
            rs.getObject("id", UUID.class),
            rs.getString("project_key"),
            rs.getString("file_path"),
            rs.getString("content_hash"),
            rs.getString("language"),
            rs.getObject("last_scan_run_id", UUID.class),
            readJson(rs.getString("metadata")));
    private final RowMapper<CodeSymbolRecord> symbolMapper = (rs, rowNum) -> new CodeSymbolRecord(
            rs.getObject("id", UUID.class),
            rs.getString("project_key"),
            rs.getObject("file_id", UUID.class),
            rs.getString("symbol_kind"),
            rs.getString("name"),
            rs.getString("fqn"),
            rs.getString("signature"),
            rs.getString("role"),
            nullableInt(rs, "start_line"),
            nullableInt(rs, "end_line"),
            rs.getString("content_hash"),
            rs.getObject("last_scan_run_id", UUID.class),
            readJson(rs.getString("metadata")));
    private final RowMapper<CodeEdgeRecord> edgeMapper = (rs, rowNum) -> new CodeEdgeRecord(
            rs.getObject("id", UUID.class),
            rs.getString("project_key"),
            rs.getObject("source_symbol_id", UUID.class),
            rs.getObject("target_symbol_id", UUID.class),
            rs.getString("target_ref"),
            rs.getString("edge_type"),
            rs.getString("resolution"),
            rs.getDouble("confidence"),
            rs.getObject("last_scan_run_id", UUID.class),
            readJson(rs.getString("evidence")));
    private final RowMapper<CodeSemanticCapsuleRecord> capsuleMapper = (rs, rowNum) -> new CodeSemanticCapsuleRecord(
            rs.getObject("id", UUID.class),
            rs.getString("project_key"),
            rs.getObject("symbol_id", UUID.class),
            rs.getObject("file_id", UUID.class),
            rs.getString("capsule_kind"),
            rs.getString("summary"),
            rs.getString("text"),
            rs.getString("provider"),
            rs.getString("semantic_model"),
            rs.getString("prompt_version"),
            rs.getString("summarizer_input_hash"),
            rs.getString("output_hash"),
            rs.getBoolean("data_egress"),
            rs.getDouble("confidence"),
            rs.getObject("last_scan_run_id", UUID.class),
            readJson(rs.getString("evidence")));
    private final RowMapper<CodeCapsuleProjectionRecord> capsuleProjectionMapper =
            (rs, rowNum) -> new CodeCapsuleProjectionRecord(
                    rs.getObject("id", UUID.class),
                    rs.getString("project_key"),
                    rs.getObject("symbol_id", UUID.class),
                    rs.getObject("file_id", UUID.class),
                    rs.getString("target_key"),
                    rs.getString("capsule_kind"),
                    rs.getString("summary"),
                    rs.getString("text"),
                    rs.getString("provider"),
                    rs.getString("semantic_model"),
                    rs.getString("prompt_version"),
                    rs.getString("summarizer_input_hash"),
                    rs.getString("output_hash"),
                    rs.getBoolean("data_egress"),
                    rs.getDouble("confidence"),
                    rs.getObject("last_scan_run_id", UUID.class),
                    readJson(rs.getString("evidence")));
    private final RowMapper<CodeCapsuleLinkTargetRecord> capsuleLinkTargetMapper =
            (rs, rowNum) -> new CodeCapsuleLinkTargetRecord(
                    rs.getObject("id", UUID.class),
                    rs.getString("project_key"),
                    rs.getObject("symbol_id", UUID.class),
                    rs.getObject("file_id", UUID.class),
                    rs.getString("target_key"),
                    rs.getString("capsule_kind"),
                    rs.getString("output_hash"),
                    rs.getString("provider"),
                    rs.getString("semantic_model"),
                    rs.getString("prompt_version"),
                    rs.getObject("last_scan_run_id", UUID.class),
                    readJson(rs.getString("evidence")));
    private final RowMapper<CodeDiagnosticRecord> diagnosticMapper = (rs, rowNum) -> new CodeDiagnosticRecord(
            rs.getObject("id", UUID.class),
            rs.getString("project_key"),
            rs.getObject("scan_run_id", UUID.class),
            rs.getString("severity"),
            rs.getString("code"),
            rs.getString("message"),
            rs.getString("file_path"),
            rs.getObject("symbol_id", UUID.class),
            readJson(rs.getString("metadata")));
    private final RowMapper<CodeScanRunRecord> scanRunMapper = (rs, rowNum) -> new CodeScanRunRecord(
            rs.getObject("id", UUID.class),
            rs.getString("project_key"),
            rs.getString("root_path"),
            rs.getString("status"),
            rs.getTimestamp("started_at").toInstant(),
            rs.getTimestamp("completed_at") == null ? null : rs.getTimestamp("completed_at").toInstant(),
            rs.getString("provider"),
            rs.getString("semantic_model"),
            rs.getBoolean("data_egress"),
            rs.getInt("files_discovered"),
            rs.getInt("files_scanned"),
            rs.getInt("files_skipped"),
            rs.getInt("files_rejected"),
            rs.getInt("candidates_created"),
            readJson(rs.getString("metadata")));

    public JdbcCodeBaselineRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public void queueRun(UUID scanRunId, String projectKey, String rootPath, String provider, String semanticModel,
            Map<String, Object> metadata) {
        jdbcTemplate.update("""
                INSERT INTO code_scan_runs (
                    id, project_key, root_path, provider, semantic_model, prompt_version, data_egress,
                    status, metadata
                )
                VALUES (?, ?, ?, ?, ?, 'queued', false, 'queued', ?::jsonb)
                ON CONFLICT (id) DO NOTHING
                """, scanRunId, projectKey, rootPath, provider, semanticModel, json(metadata));
    }

    @Override
    public void startRun(UUID scanRunId, String projectKey, String rootPath, String provider, String semanticModel,
            String promptVersion, boolean dataEgress, Map<String, Object> metadata) {
        jdbcTemplate.update("""
                INSERT INTO code_scan_runs (
                    id, project_key, root_path, provider, semantic_model, prompt_version, data_egress,
                    status, metadata
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, 'running', ?::jsonb)
                ON CONFLICT (id) DO UPDATE
                SET project_key = EXCLUDED.project_key,
                    root_path = EXCLUDED.root_path,
                    provider = EXCLUDED.provider,
                    semantic_model = EXCLUDED.semantic_model,
                    prompt_version = EXCLUDED.prompt_version,
                    data_egress = EXCLUDED.data_egress,
                    status = 'running',
                    metadata = EXCLUDED.metadata
                """, scanRunId, projectKey, rootPath, provider, semanticModel, promptVersion, dataEgress,
                json(metadata));
    }

    @Override
    public void completeRun(UUID scanRunId, String status, int filesDiscovered, int filesScanned, int filesSkipped,
            int filesRejected, int candidatesCreated, Map<String, Object> metadata) {
        jdbcTemplate.update("""
                UPDATE code_scan_runs
                SET completed_at = ?, status = ?, files_discovered = ?, files_scanned = ?, files_skipped = ?,
                    files_rejected = ?, candidates_created = ?, metadata = ?::jsonb
                WHERE id = ?
                  AND status <> 'cancelled'
                """, Timestamp.from(Instant.now()), status, filesDiscovered, filesScanned, filesSkipped,
                filesRejected, candidatesCreated, json(metadata), scanRunId);
    }

    @Override
    public void updateRunProgress(UUID scanRunId, Integer filesScanned, Map<String, Object> metadataDelta) {
        Map<String, Object> delta = metadataDelta == null ? Map.of() : metadataDelta;
        if (filesScanned != null) {
            jdbcTemplate.update("""
                    UPDATE code_scan_runs
                    SET files_scanned = ?, metadata = metadata || ?::jsonb
                    WHERE id = ?
                      AND status = 'running'
                    """, filesScanned, json(delta), scanRunId);
        } else {
            jdbcTemplate.update("""
                    UPDATE code_scan_runs
                    SET metadata = metadata || ?::jsonb
                    WHERE id = ?
                      AND status = 'running'
                    """, json(delta), scanRunId);
        }
    }

    @Override
    public int failIncompleteRuns(String reason) {
        return jdbcTemplate.update("""
                UPDATE code_scan_runs
                SET completed_at = ?,
                    status = 'failed',
                    metadata = metadata || ?::jsonb
                WHERE status IN ('queued', 'running')
                """, Timestamp.from(Instant.now()), json(Map.of(
                "asyncInterrupted", true,
                "reason", reason == null || reason.isBlank() ? "interrupted" : reason)));
    }

    @Override
    public boolean cancelRun(String projectKey, UUID scanRunId, String reason) {
        int updated = jdbcTemplate.update("""
                UPDATE code_scan_runs
                SET completed_at = ?,
                    status = 'cancelled',
                    metadata = metadata || ?::jsonb
                WHERE project_key = ?
                  AND id = ?
                  AND status IN ('queued', 'running')
                """, Timestamp.from(Instant.now()), json(Map.of(
                "cancelled", true,
                "reason", reason == null || reason.isBlank() ? "cancelled_by_request" : reason)),
                projectKey, scanRunId);
        return updated > 0;
    }

    @Override
    public void upsertFile(CodeFileRecord file) {
        jdbcTemplate.update("""
                INSERT INTO code_files (
                    id, project_key, file_path, content_hash, language, last_scan_run_id, metadata
                )
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb)
                ON CONFLICT (project_key, file_path) DO UPDATE SET
                    content_hash = EXCLUDED.content_hash,
                    language = EXCLUDED.language,
                    last_scan_run_id = EXCLUDED.last_scan_run_id,
                    last_scanned_at = now(),
                    metadata = EXCLUDED.metadata
                """, file.id(), file.projectKey(), file.filePath(), file.contentHash(), file.language(),
                file.scanRunId(), json(file.metadata()));
    }

    @Override
    public void deleteFileFacts(String projectKey, UUID fileId) {
        jdbcTemplate.update("""
                DELETE FROM code_semantic_capsules
                WHERE project_key = ?
                  AND file_id = ?
                  AND symbol_id IS NULL
                """, projectKey, fileId);
        jdbcTemplate.update("""
                DELETE FROM code_symbols
                WHERE project_key = ?
                  AND file_id = ?
                """, projectKey, fileId);
    }

    @Override
    public List<UUID> findCapsuleIdsForFile(String projectKey, UUID fileId) {
        if (projectKey == null || projectKey.isBlank() || fileId == null) {
            return List.of();
        }
        return jdbcTemplate.queryForList("""
                SELECT id
                FROM code_semantic_capsules
                WHERE project_key = ?
                  AND file_id = ?
                ORDER BY id
                """, UUID.class, projectKey.trim(), fileId);
    }

    @Override
    public void deleteFilesForRootNotIn(String projectKey, String rootPath, Set<String> keepRelativePaths) {
        List<Object> args = new ArrayList<>();
        args.add(projectKey);
        args.add(projectKey);
        args.add(rootPath);
        StringBuilder sql = new StringBuilder("""
                DELETE FROM code_files file
                USING code_scan_runs run
                WHERE file.last_scan_run_id = run.id
                  AND file.project_key = ?
                  AND run.project_key = ?
                  AND run.root_path = ?
                """);
        if (keepRelativePaths != null && !keepRelativePaths.isEmpty()) {
            sql.append(" AND file.file_path NOT IN (");
            sql.append(String.join(", ", java.util.Collections.nCopies(keepRelativePaths.size(), "?")));
            sql.append(")");
            args.addAll(keepRelativePaths);
        }
        jdbcTemplate.update(sql.toString(), args.toArray());
    }

    @Override
    public List<UUID> findCapsuleIdsForRootNotIn(String projectKey, String rootPath,
            Set<String> keepRelativePaths) {
        List<Object> args = new ArrayList<>();
        args.add(projectKey);
        args.add(projectKey);
        args.add(rootPath);
        StringBuilder sql = new StringBuilder("""
                SELECT capsule.id
                FROM code_semantic_capsules capsule
                JOIN code_files file ON file.id = capsule.file_id
                JOIN code_scan_runs run ON run.id = file.last_scan_run_id
                WHERE capsule.project_key = ?
                  AND file.project_key = ?
                  AND run.root_path = ?
                """);
        if (keepRelativePaths != null && !keepRelativePaths.isEmpty()) {
            sql.append(" AND file.file_path NOT IN (");
            sql.append(String.join(", ", java.util.Collections.nCopies(keepRelativePaths.size(), "?")));
            sql.append(")");
            args.addAll(keepRelativePaths);
        }
        sql.append(" ORDER BY capsule.id");
        return jdbcTemplate.queryForList(sql.toString(), UUID.class, args.toArray());
    }

    @Override
    public void upsertSymbol(CodeSymbolRecord symbol) {
        jdbcTemplate.update("""
                INSERT INTO code_symbols (
                    id, project_key, file_id, symbol_kind, name, fqn, signature, role,
                    start_line, end_line, content_hash, last_scan_run_id, metadata
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                ON CONFLICT (project_key, file_id, symbol_kind, name, fqn, signature) DO UPDATE SET
                    name = EXCLUDED.name,
                    fqn = EXCLUDED.fqn,
                    role = EXCLUDED.role,
                    start_line = EXCLUDED.start_line,
                    end_line = EXCLUDED.end_line,
                    content_hash = EXCLUDED.content_hash,
                    last_scan_run_id = EXCLUDED.last_scan_run_id,
                    metadata = EXCLUDED.metadata
                """, symbol.id(), symbol.projectKey(), symbol.fileId(), symbol.symbolKind(), symbol.name(),
                blank(symbol.fqn()), blank(symbol.signature()), symbol.role(), symbol.startLine(), symbol.endLine(),
                symbol.contentHash(), symbol.scanRunId(), json(symbol.metadata()));
    }

    @Override
    public void upsertEdge(CodeEdgeRecord edge) {
        jdbcTemplate.update("""
                INSERT INTO code_edges (
                    id, project_key, source_symbol_id, target_symbol_id, target_ref, edge_type,
                    resolution, confidence, evidence, last_scan_run_id
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                ON CONFLICT (project_key, source_symbol_id, edge_type, target_ref)
                DO UPDATE SET
                    resolution = EXCLUDED.resolution,
                    confidence = EXCLUDED.confidence,
                    evidence = EXCLUDED.evidence,
                    last_scan_run_id = EXCLUDED.last_scan_run_id
                """, edge.id(), edge.projectKey(), edge.sourceSymbolId(), edge.targetSymbolId(), targetKey(edge),
                edge.edgeType(), edge.resolution(), edge.confidence(), json(edge.evidence()), edge.scanRunId());
    }

    @Override
    public boolean capsuleExists(String projectKey, String targetKey, String capsuleKind, String provider,
            String semanticModel, String promptVersion, String summarizerInputHash) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM code_semantic_capsules
                WHERE project_key = ?
                  AND target_key = ?
                  AND capsule_kind = ?
                  AND provider = ?
                  AND semantic_model = ?
                  AND prompt_version = ?
                  AND summarizer_input_hash = ?
                """, Integer.class, projectKey, targetKey, capsuleKind, provider, blank(semanticModel), promptVersion,
                summarizerInputHash);
        return count != null && count > 0;
    }

    @Override
    public void upsertCapsule(CodeSemanticCapsuleRecord capsule) {
        jdbcTemplate.update("""
                INSERT INTO code_semantic_capsules (
                    id, project_key, symbol_id, file_id, capsule_kind, summary, text, provider, semantic_model,
                    target_key, prompt_version, summarizer_input_hash, output_hash, data_egress, confidence, evidence,
                    last_scan_run_id
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                ON CONFLICT (project_key, target_key, capsule_kind, provider, semantic_model, prompt_version,
                             summarizer_input_hash)
                DO UPDATE SET
                    summary = EXCLUDED.summary,
                    text = EXCLUDED.text,
                    output_hash = EXCLUDED.output_hash,
                    data_egress = EXCLUDED.data_egress,
                    confidence = EXCLUDED.confidence,
                    evidence = EXCLUDED.evidence,
                    last_scan_run_id = EXCLUDED.last_scan_run_id,
                    updated_at = now()
                """, capsule.id(), capsule.projectKey(), capsule.symbolId(), capsule.fileId(),
                capsule.capsuleKind(), capsule.summary(), capsule.text(), capsule.provider(), blank(capsule.semanticModel()),
                capsuleTargetKey(capsule), capsule.promptVersion(), capsule.summarizerInputHash(), capsule.outputHash(),
                capsule.dataEgress(), capsule.confidence(), json(capsule.evidence()), capsule.scanRunId());
    }

    @Override
    public void insertDiagnostic(CodeDiagnosticRecord diagnostic) {
        jdbcTemplate.update("""
                INSERT INTO code_diagnostics (
                    id, project_key, scan_run_id, severity, code, message, file_path, symbol_id, metadata
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                """, diagnostic.id(), diagnostic.projectKey(), diagnostic.scanRunId(), diagnostic.severity(),
                diagnostic.code(), diagnostic.message(), diagnostic.filePath(), diagnostic.symbolId(),
                json(diagnostic.metadata()));
    }

    @Override
    public Optional<CodeSemanticCapsuleRecord> findCapsuleById(UUID capsuleId) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject("""
                    SELECT *
                    FROM code_semantic_capsules
                    WHERE id = ?
                    """, capsuleMapper, capsuleId));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public List<CodeSemanticCapsuleRecord> findCapsulesBySymbolId(String projectKey, UUID symbolId) {
        return jdbcTemplate.query("""
                SELECT *
                FROM code_semantic_capsules
                WHERE project_key = ?
                  AND symbol_id = ?
                ORDER BY updated_at DESC
                """, capsuleMapper, projectKey, symbolId);
    }

    @Override
    public List<CodeSemanticCapsuleRecord> searchCapsulesText(String projectKey, String query, int limit) {
        String like = "%" + (query == null ? "" : query.trim()) + "%";
        return jdbcTemplate.query("""
                SELECT *
                FROM code_semantic_capsules
                WHERE project_key = ?
                  AND (summary ILIKE ? OR text ILIKE ?)
                ORDER BY updated_at DESC
                LIMIT ?
                """, capsuleMapper, projectKey, like, like, Math.max(1, limit));
    }

    @Override
    public List<CodeSemanticCapsuleRecord> findCapsulesForRoot(String projectKey, String rootPath, int limit) {
        return jdbcTemplate.query("""
                SELECT capsule.*
                FROM code_semantic_capsules capsule
                JOIN code_files file ON file.id = capsule.file_id
                JOIN code_scan_runs run ON run.id = file.last_scan_run_id
                WHERE capsule.project_key = ?
                  AND run.project_key = ?
                  AND run.root_path = ?
                ORDER BY capsule.updated_at DESC
                LIMIT ?
                """, capsuleMapper, projectKey, projectKey, rootPath, Math.max(1, limit));
    }

    @Override
    public Optional<CodeSymbolRecord> findSymbolById(UUID symbolId) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject("""
                    SELECT *
                    FROM code_symbols
                    WHERE id = ?
                    """, symbolMapper, symbolId));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<CodeFileRecord> findFileById(String projectKey, UUID fileId) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject("""
                    SELECT *
                    FROM code_files
                    WHERE project_key = ?
                      AND id = ?
                    """, fileMapper, projectKey, fileId));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<CodeFileRecord> findFileByPath(String projectKey, String filePath) {
        String normalizedPath = filePath == null ? "" : filePath.trim().replace('\\', '/');
        if (projectKey == null || projectKey.isBlank() || normalizedPath.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject("""
                    SELECT *
                    FROM code_files
                    WHERE project_key = ?
                      AND file_path = ?
                    """, fileMapper, projectKey.trim(), normalizedPath));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public List<CodeSymbolRecord> findSymbolsForRun(String projectKey, UUID scanRunId, int limit) {
        return jdbcTemplate.query("""
                SELECT *
                FROM code_symbols
                WHERE project_key = ?
                  AND last_scan_run_id = ?
                ORDER BY
                  CASE symbol_kind WHEN 'method' THEN 0 WHEN 'class' THEN 1 ELSE 2 END,
                  fqn
                LIMIT ?
                """, symbolMapper, projectKey, scanRunId, Math.max(1, limit));
    }

    @Override
    public boolean directoryExists(String projectKey, String canonicalDirectory) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM code_files
                    WHERE project_key = ? AND starts_with(file_path, ?))
                """, Boolean.class, projectKey, canonicalDirectory + "/"));
    }

    @Override
    public List<CodeFileRecord> findFilesForProject(String projectKey, String afterFilePath, int limit) {
        String cursor = blankToNull(afterFilePath);
        if (cursor == null) {
            return jdbcTemplate.query("""
                    SELECT *
                    FROM code_files
                    WHERE project_key = ?
                    ORDER BY file_path
                    LIMIT ?
                    """, fileMapper, projectKey, Math.max(1, limit));
        }
        return jdbcTemplate.query("""
                SELECT *
                FROM code_files
                WHERE project_key = ?
                  AND file_path > ?
                ORDER BY file_path
                LIMIT ?
                """, fileMapper, projectKey, cursor, Math.max(1, limit));
    }

    @Override
    public List<CodeSymbolRecord> findSymbolsForProject(String projectKey, UUID afterSymbolId, int limit) {
        if (afterSymbolId == null) {
            return jdbcTemplate.query("""
                    SELECT *
                    FROM code_symbols
                    WHERE project_key = ?
                    ORDER BY id
                    LIMIT ?
                    """, symbolMapper, projectKey, Math.max(1, limit));
        }
        return jdbcTemplate.query("""
                SELECT *
                FROM code_symbols
                WHERE project_key = ?
                  AND id > ?
                ORDER BY id
                LIMIT ?
                """, symbolMapper, projectKey, afterSymbolId, Math.max(1, limit));
    }

    @Override
    public List<CodeEdgeRecord> findEdgesForProject(String projectKey, UUID afterEdgeId, int limit) {
        if (afterEdgeId == null) {
            return jdbcTemplate.query("""
                    SELECT *
                    FROM code_edges
                    WHERE project_key = ?
                    ORDER BY id
                    LIMIT ?
                    """, edgeMapper, projectKey, Math.max(1, limit));
        }
        return jdbcTemplate.query("""
                SELECT *
                FROM code_edges
                WHERE project_key = ?
                  AND id > ?
                ORDER BY id
                LIMIT ?
                """, edgeMapper, projectKey, afterEdgeId, Math.max(1, limit));
    }

    @Override
    public List<CodeCapsuleProjectionRecord> findCurrentCapsulesForProject(String projectKey, String afterTargetKey,
            String afterCapsuleKind, int limit) {
        String targetCursor = blankToNull(afterTargetKey);
        if (targetCursor == null) {
            return jdbcTemplate.query("""
                    SELECT *
                    FROM (
                        SELECT DISTINCT ON (project_key, target_key, capsule_kind)
                            id, project_key, symbol_id, file_id, target_key, capsule_kind, summary, text, provider,
                            semantic_model, prompt_version, summarizer_input_hash, output_hash, data_egress,
                            confidence, last_scan_run_id, evidence
                        FROM code_semantic_capsules
                        WHERE project_key = ?
                        ORDER BY project_key, target_key, capsule_kind, updated_at DESC, id
                    ) cur
                    ORDER BY cur.target_key, cur.capsule_kind
                    LIMIT ?
                    """, capsuleProjectionMapper, projectKey, Math.max(1, limit));
        }
        return jdbcTemplate.query("""
                SELECT *
                FROM (
                    SELECT DISTINCT ON (project_key, target_key, capsule_kind)
                        id, project_key, symbol_id, file_id, target_key, capsule_kind, summary, text, provider,
                        semantic_model, prompt_version, summarizer_input_hash, output_hash, data_egress,
                        confidence, last_scan_run_id, evidence
                    FROM code_semantic_capsules
                    WHERE project_key = ?
                    ORDER BY project_key, target_key, capsule_kind, updated_at DESC, id
                ) cur
                WHERE (cur.target_key, cur.capsule_kind) > (?, ?)
                ORDER BY cur.target_key, cur.capsule_kind
                LIMIT ?
                """, capsuleProjectionMapper, projectKey, targetCursor, blank(afterCapsuleKind),
                Math.max(1, limit));
    }

    @Override
    public Optional<CodeCapsuleProjectionRecord> findCurrentCapsuleForTarget(String projectKey, String targetKey,
            String capsuleKind) {
        if (projectKey == null || projectKey.isBlank() || targetKey == null || targetKey.isBlank()
                || capsuleKind == null || capsuleKind.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject("""
                    SELECT *
                    FROM (
                        SELECT DISTINCT ON (project_key, target_key, capsule_kind)
                            id, project_key, symbol_id, file_id, target_key, capsule_kind, summary, text, provider,
                            semantic_model, prompt_version, summarizer_input_hash, output_hash, data_egress,
                            confidence, last_scan_run_id, evidence
                        FROM code_semantic_capsules
                        WHERE project_key = ?
                          AND target_key = ?
                          AND capsule_kind = ?
                        ORDER BY project_key, target_key, capsule_kind, updated_at DESC, id
                    ) cur
                    """, capsuleProjectionMapper, projectKey.trim(), targetKey.trim(), capsuleKind.trim()));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<CodeCapsuleLinkTargetRecord> findCapsuleLinkTargetById(UUID capsuleId) {
        if (capsuleId == null) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject("""
                    SELECT id, project_key, symbol_id, file_id, target_key, capsule_kind, output_hash, provider,
                           semantic_model, prompt_version, last_scan_run_id, evidence
                    FROM code_semantic_capsules
                    WHERE id = ?
                    """, capsuleLinkTargetMapper, capsuleId));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<CodeCapsuleLinkTargetRecord> findCurrentCapsuleLinkTarget(String projectKey, String targetKey,
            String capsuleKind) {
        if (projectKey == null || projectKey.isBlank() || targetKey == null || targetKey.isBlank()
                || capsuleKind == null || capsuleKind.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject("""
                    SELECT *
                    FROM (
                        SELECT DISTINCT ON (project_key, target_key, capsule_kind)
                            id, project_key, symbol_id, file_id, target_key, capsule_kind, output_hash, provider,
                            semantic_model, prompt_version, last_scan_run_id, evidence
                        FROM code_semantic_capsules
                        WHERE project_key = ?
                          AND target_key = ?
                          AND capsule_kind = ?
                        ORDER BY project_key, target_key, capsule_kind, updated_at DESC, id
                    ) cur
                    """, capsuleLinkTargetMapper, projectKey.trim(), targetKey.trim(), capsuleKind.trim()));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public List<CodeSymbolAnchorCandidateRecord> findSymbolAnchorCandidates(String projectKey, UUID afterSymbolId,
            int limit) {
        List<Object> args = new ArrayList<>();
        args.add(projectKey);
        args.add(projectKey);
        args.add(projectKey);
        StringBuilder sql = new StringBuilder("""
                SELECT s.*,
                       COALESCE(out_stats.outgoing_edges, 0) AS outgoing_edges,
                       COALESCE(out_stats.endpoint_edges, 0) AS endpoint_edges,
                       COALESCE(out_stats.edge_annotations, '[]'::jsonb)::text AS edge_annotations_json,
                       COALESCE(in_stats.incoming_edges, 0) AS incoming_edges
                FROM code_symbols s
                LEFT JOIN (
                    SELECT source_symbol_id,
                           COUNT(*) FILTER (WHERE edge_type IN ('CALLS','INJECTS')) AS outgoing_edges,
                           COUNT(*) FILTER (WHERE edge_type = 'EXPOSES_ENDPOINT') AS endpoint_edges,
                           jsonb_agg(DISTINCT target_ref) FILTER (
                               WHERE edge_type = 'ANNOTATED_WITH' AND target_ref <> ''
                           ) AS edge_annotations
                    FROM code_edges
                    WHERE project_key = ?
                    GROUP BY source_symbol_id
                ) out_stats ON out_stats.source_symbol_id = s.id
                LEFT JOIN (
                    SELECT target_symbol_id,
                           COUNT(*) FILTER (WHERE edge_type IN ('CALLS','DECLARES')) AS incoming_edges
                    FROM code_edges
                    WHERE project_key = ?
                      AND target_symbol_id IS NOT NULL
                    GROUP BY target_symbol_id
                ) in_stats ON in_stats.target_symbol_id = s.id
                WHERE s.project_key = ?
                """);
        if (afterSymbolId != null) {
            sql.append(" AND s.id > ?");
            args.add(afterSymbolId);
        }
        sql.append(" ORDER BY s.id LIMIT ?");
        args.add(Math.max(1, limit));
        return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> {
            CodeSymbolRecord symbol = symbolMapper.mapRow(rs, rowNum);
            return new CodeSymbolAnchorCandidateRecord(symbol, rs.getInt("incoming_edges"),
                    rs.getInt("outgoing_edges"), rs.getInt("endpoint_edges"),
                    mergedAnnotations(symbol.metadata().get("annotations"), rs.getString("edge_annotations_json")));
        }, args.toArray());
    }

    @Override
    public List<CodeEndpointAnchorCandidateRecord> findEndpointAnchorCandidates(String projectKey, UUID afterEdgeId,
            int limit) {
        List<Object> args = new ArrayList<>();
        args.add(projectKey);
        StringBuilder sql = new StringBuilder("""
                SELECT e.id AS edge_id,
                       e.project_key,
                       e.source_symbol_id,
                       e.target_ref,
                       e.confidence,
                       s.name AS source_name,
                       s.fqn AS source_fqn,
                       s.start_line,
                       s.end_line
                FROM code_edges e
                JOIN code_symbols s ON s.id = e.source_symbol_id
                WHERE e.project_key = ?
                  AND e.edge_type = 'EXPOSES_ENDPOINT'
                """);
        if (afterEdgeId != null) {
            sql.append(" AND e.id > ?");
            args.add(afterEdgeId);
        }
        sql.append(" ORDER BY e.id LIMIT ?");
        args.add(Math.max(1, limit));
        return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> new CodeEndpointAnchorCandidateRecord(
                rs.getObject("edge_id", UUID.class),
                rs.getString("project_key"),
                rs.getObject("source_symbol_id", UUID.class),
                rs.getString("target_ref"),
                rs.getString("source_name"),
                rs.getString("source_fqn"),
                nullableInt(rs, "start_line"),
                nullableInt(rs, "end_line"),
                rs.getDouble("confidence")), args.toArray());
    }

    @Override
    public List<CodeSymbolRecord> findSymbolsByIds(String projectKey, Set<UUID> symbolIds, int limit) {
        if (symbolIds == null || symbolIds.isEmpty()) {
            return List.of();
        }
        List<Object> args = new ArrayList<>();
        args.add(projectKey);
        StringBuilder sql = new StringBuilder("""
                SELECT *
                FROM code_symbols
                WHERE project_key = ?
                  AND id IN (
                """);
        sql.append(String.join(", ", java.util.Collections.nCopies(symbolIds.size(), "?")));
        sql.append(") ORDER BY fqn LIMIT ?");
        args.addAll(symbolIds);
        args.add(Math.max(1, limit));
        return jdbcTemplate.query(sql.toString(), symbolMapper, args.toArray());
    }

    @Override
    public List<CodeSymbolRecord> findSymbolsByRef(String projectKey, String ref, int limit) {
        String value = ref == null ? "" : ref.trim();
        if (value.isBlank()) {
            return List.of();
        }
        try {
            UUID id = UUID.fromString(value);
            return findSymbolById(id)
                    .filter(symbol -> projectKey.equals(symbol.projectKey()))
                    .map(List::of)
                    .orElseGet(List::of);
        } catch (IllegalArgumentException ignored) {
        }
        int boundedLimit = Math.max(1, limit);
        List<CodeSymbolRecord> matches = findSymbolsByExactColumn(projectKey, "fqn", value, boundedLimit);
        if (!matches.isEmpty()) {
            return matches;
        }
        matches = findSymbolsByExactColumn(projectKey, "name", value, boundedLimit);
        if (!matches.isEmpty()) {
            return matches;
        }
        matches = findSymbolsByFilePath(projectKey, value, boundedLimit, true);
        if (!matches.isEmpty()) {
            return matches;
        }
        return findSymbolsByFilePath(projectKey, value, boundedLimit, false);
    }

    @Override
    public List<CodeSymbolRecord> findSymbolsByLocator(String projectKey, String filePath,
            String fqn, String signature, int limit) {
        int boundedLimit = Math.max(1, limit);
        if (signature == null || signature.isBlank()) {
            return jdbcTemplate.query("""
                    SELECT symbol.*
                    FROM code_symbols symbol
                    JOIN code_files file ON file.id=symbol.file_id
                    WHERE symbol.project_key=? AND file.file_path=? AND symbol.fqn=?
                    ORDER BY symbol.signature
                    LIMIT ?
                    """, symbolMapper, projectKey, filePath, fqn, boundedLimit);
        }
        return jdbcTemplate.query("""
                SELECT symbol.*
                FROM code_symbols symbol
                JOIN code_files file ON file.id=symbol.file_id
                WHERE symbol.project_key=? AND file.file_path=? AND symbol.fqn=? AND symbol.signature=?
                ORDER BY symbol.id
                LIMIT ?
                """, symbolMapper, projectKey, filePath, fqn, signature, boundedLimit);
    }

    private List<CodeSymbolRecord> findSymbolsByExactColumn(String projectKey, String column, String value,
            int limit) {
        String sql = """
                SELECT symbol.*
                FROM code_symbols symbol
                WHERE symbol.project_key = ?
                  AND symbol.%s = ?
                ORDER BY symbol.fqn
                LIMIT ?
                """.formatted(column);
        return jdbcTemplate.query(sql, symbolMapper, projectKey, value, limit);
    }

    private List<CodeSymbolRecord> findSymbolsByFilePath(String projectKey, String value, int limit,
            boolean exact) {
        String pathPredicate = exact
                ? "file.file_path = ?"
                : "POSITION(LOWER(?) IN LOWER(file.file_path)) > 0";
        String sql = """
                SELECT symbol.*
                FROM code_symbols symbol
                JOIN code_files file ON file.id = symbol.file_id
                WHERE symbol.project_key = ?
                  AND %s
                ORDER BY symbol.fqn
                LIMIT ?
                """.formatted(pathPredicate);
        return jdbcTemplate.query(sql, symbolMapper, projectKey, value, limit);
    }

    @Override
    public List<CodeEdgeRecord> findEdgesFrom(String projectKey, UUID symbolId, Set<String> edgeTypes, int limit) {
        return queryEdges("""
                SELECT *
                FROM code_edges
                WHERE project_key = ?
                  AND source_symbol_id = ?
                """, projectKey, symbolId, edgeTypes, limit);
    }

    @Override
    public List<CodeEdgeRecord> findEdgesTo(String projectKey, UUID symbolId, Set<String> edgeTypes, int limit) {
        return queryEdges("""
                SELECT *
                FROM code_edges
                WHERE project_key = ?
                  AND target_symbol_id = ?
                """, projectKey, symbolId, edgeTypes, limit);
    }

    @Override
    public List<CodeEdgeRecord> findEdgesByTargetRefs(String projectKey, Set<String> targetRefs,
            Set<String> edgeTypes, int limit) {
        if (targetRefs == null || targetRefs.isEmpty()) {
            return List.of();
        }
        List<Object> args = new ArrayList<>();
        args.add(projectKey);
        StringBuilder sql = new StringBuilder("""
                SELECT *
                FROM code_edges
                WHERE project_key = ?
                  AND target_ref IN (
                """);
        sql.append(String.join(", ", java.util.Collections.nCopies(targetRefs.size(), "?")));
        sql.append(")");
        args.addAll(targetRefs);
        if (edgeTypes != null && !edgeTypes.isEmpty()) {
            sql.append(" AND edge_type IN (");
            sql.append(String.join(", ", java.util.Collections.nCopies(edgeTypes.size(), "?")));
            sql.append(")");
            args.addAll(edgeTypes);
        }
        sql.append(" ORDER BY confidence DESC LIMIT ?");
        args.add(Math.max(1, limit));
        return jdbcTemplate.query(sql.toString(), edgeMapper, args.toArray());
    }

    @Override
    public List<CodeDiagnosticRecord> recentDiagnostics(String projectKey, int limit) {
        return jdbcTemplate.query("""
                SELECT *
                FROM code_diagnostics
                WHERE project_key = ?
                ORDER BY created_at DESC
                LIMIT ?
                """, diagnosticMapper, projectKey, Math.max(1, limit));
    }

    @Override
    public List<CodeDiagnosticRecord> diagnosticsForRun(String projectKey, UUID scanRunId, int limit) {
        return jdbcTemplate.query("""
                SELECT *
                FROM code_diagnostics
                WHERE project_key = ?
                  AND scan_run_id = ?
                ORDER BY created_at DESC
                LIMIT ?
                """, diagnosticMapper, projectKey, scanRunId, Math.max(1, limit));
    }

    @Override
    public Optional<CodeScanRunRecord> findRun(String projectKey, UUID scanRunId) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject("""
                    SELECT *
                    FROM code_scan_runs
                    WHERE project_key = ?
                      AND id = ?
                    """, scanRunMapper, projectKey, scanRunId));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<CodeScanRunRecord> findRun(UUID scanRunId) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject("""
                    SELECT *
                    FROM code_scan_runs
                    WHERE id = ?
                    """, scanRunMapper, scanRunId));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<CodeScanRunRecord> latestCompletedRun(String projectKey) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject("""
                    SELECT *
                    FROM code_scan_runs
                    WHERE project_key = ?
                      AND status = 'completed'
                    ORDER BY completed_at DESC NULLS LAST, started_at DESC
                    LIMIT 1
                    """, scanRunMapper, projectKey));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public List<CodeEdgeRecord> findEdgesForRun(String projectKey, UUID scanRunId, int limit) {
        return jdbcTemplate.query("""
                SELECT *
                FROM code_edges
                WHERE project_key = ?
                  AND last_scan_run_id = ?
                ORDER BY id
                LIMIT ?
                """, edgeMapper, projectKey, scanRunId, Math.max(1, limit));
    }

    @Override
    public Map<UUID, String> findFilePathsByIds(Set<UUID> fileIds) {
        if (fileIds == null || fileIds.isEmpty()) {
            return Map.of();
        }
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("""
                SELECT id, file_path
                FROM code_files
                WHERE id IN (
                """);
        sql.append(String.join(", ", java.util.Collections.nCopies(fileIds.size(), "?")));
        sql.append(")");
        args.addAll(fileIds);
        Map<UUID, String> result = new HashMap<>();
        jdbcTemplate.query(sql.toString(), rs -> {
            result.put(rs.getObject("id", UUID.class), rs.getString("file_path"));
        }, args.toArray());
        return Map.copyOf(result);
    }

    private List<CodeEdgeRecord> queryEdges(String baseSql, String projectKey, UUID symbolId, Set<String> edgeTypes,
            int limit) {
        List<Object> args = new ArrayList<>();
        args.add(projectKey);
        args.add(symbolId);
        StringBuilder sql = new StringBuilder(baseSql);
        if (edgeTypes != null && !edgeTypes.isEmpty()) {
            sql.append(" AND edge_type IN (");
            sql.append(String.join(", ", java.util.Collections.nCopies(edgeTypes.size(), "?")));
            sql.append(")");
            args.addAll(edgeTypes);
        }
        sql.append(" ORDER BY confidence DESC LIMIT ?");
        args.add(Math.max(1, limit));
        return jdbcTemplate.query(sql.toString(), edgeMapper, args.toArray());
    }

    private static String targetKey(CodeEdgeRecord edge) {
        if (edge.targetRef() != null && !edge.targetRef().isBlank()) {
            return edge.targetRef();
        }
        return edge.targetSymbolId() == null ? "" : edge.targetSymbolId().toString();
    }

    private static String capsuleTargetKey(CodeSemanticCapsuleRecord capsule) {
        if (capsule.symbolId() != null) {
            return capsule.symbolId().toString();
        }
        return capsule.fileId() == null ? "" : capsule.fileId().toString();
    }

    private static String blank(String value) {
        return value == null ? "" : value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private String json(Map<String, Object> metadata) {
        try {
            return objectMapper.writeValueAsString(metadata == null ? Map.of() : metadata);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize code baseline metadata", e);
        }
    }

    private List<String> mergedAnnotations(Object metadataAnnotations, String edgeAnnotationsJson) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        addAnnotationValues(values, metadataAnnotations);
        for (String value : readStringList(edgeAnnotationsJson)) {
            addAnnotationValue(values, value);
        }
        return List.copyOf(values);
    }

    private void addAnnotationValues(LinkedHashSet<String> values, Object source) {
        if (source instanceof Iterable<?> iterable) {
            for (Object value : iterable) {
                addAnnotationValue(values, value);
            }
            return;
        }
        addAnnotationValue(values, source);
    }

    private static void addAnnotationValue(LinkedHashSet<String> values, Object value) {
        if (value == null) {
            return;
        }
        String text = value.toString().trim();
        if (!text.isBlank()) {
            values.add(text);
        }
    }

    private List<String> readStringList(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        try {
            List<?> raw = objectMapper.readValue(value, new TypeReference<List<?>>() {
            });
            return raw.stream()
                    .filter(item -> item != null && !item.toString().isBlank())
                    .map(item -> item.toString().trim())
                    .distinct()
                    .toList();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot deserialize code baseline annotation list", e);
        }
    }

    private Map<String, Object> readJson(String value) {
        if (value == null || value.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(value, new TypeReference<Map<String, Object>>() {
            });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot deserialize code baseline metadata", e);
        }
    }

    private static Integer nullableInt(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }
}
