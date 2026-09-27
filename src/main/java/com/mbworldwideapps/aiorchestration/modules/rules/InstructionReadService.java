package com.mbworldwideapps.aiorchestration.modules.rules;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Complete, bounded instruction retrieval. A single SQL statement fixes rows and sequences to one snapshot. */
@Service
public class InstructionReadService {
    public static final int MAX_RULES = 100;
    public static final int MAX_BYTES = 65_536;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public InstructionReadService(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public record Instruction(UUID ruleId, int version, String scope, boolean contentLoaded,
            String statement, String rationale, List<String> modulePaths,
            String approvedBy, String humanTurnRef, String approvalContentHash) { }

    public record Result(String projectKey, String scope, long globalEffectiveSeq, long projectEffectiveSeq,
            boolean complete, List<Instruction> instructions) { }

    /** Bounded diagnostics: observed count is a lower bound when SQL fetched cap+1. */
    public static final class CapacityExceeded extends IllegalStateException {
        public CapacityExceeded(String reason, List<Instruction> rows, int observedBytes) {
            super("CAPACITY_EXCEEDED reason=" + reason + " ruleCap=" + MAX_RULES + " byteCap=" + MAX_BYTES
                    + " observedCount=" + rows.size() + " countIsLowerBound=" + (rows.size() > MAX_RULES)
                    + " observedBytes=" + observedBytes + " observedRuleIds="
                    + rows.stream().map(Instruction::ruleId).sorted().toList());
        }
    }

    /**
     * Every path (directory or file) an active instruction rule of this project is bound to. Cheap and unpaged, so
     * a paged startup read can still hand the agent the complete path index on its last page.
     */
    public List<String> boundPaths(String projectKey) {
        return jdbc.queryForList("""
                SELECT DISTINCT CASE WHEN b.binding_kind = 'path_glob'
                                     THEN left(b.target_key, length(b.target_key) - 3) ELSE b.target_key END AS path
                FROM rule_definitions d
                JOIN rule_versions v ON v.rule_id = d.id AND v.version = d.current_version
                JOIN rule_target_bindings b ON b.rule_id = d.id AND b.rule_version = v.version
                WHERE d.status = 'active' AND v.enforcement = 'instruction' AND NOT v.applies_all
                  AND d.project_key = ? AND b.binding_kind IN ('path_glob', 'file')
                ORDER BY 1""", String.class, projectKey);
    }

    public Result read(String projectKey, String requestedScope, List<String> modulePaths) {
        if (projectKey == null || !projectKey.matches("[A-Z0-9][A-Z0-9_]{0,199}")) {
            throw new IllegalArgumentException("explicit canonical projectKey from scanner.project.resolve is required");
        }
        String scope = requestedScope == null ? "effective" : requestedScope.toLowerCase(Locale.ROOT);
        if (!Set.of("effective", "global_strict", "project", "module").contains(scope)) {
            throw new IllegalArgumentException("scope must be effective, global_strict, project or module");
        }
        if (modulePaths != null && modulePaths.size() > 32) {
            throw new IllegalArgumentException("at most 32 modulePaths are allowed");
        }
        List<String> paths = modulePaths == null ? List.of()
                : modulePaths.stream().map(InstructionModulePaths::requireDirectory).distinct().sorted().toList();
        if (scope.equals("module") && paths.isEmpty()) {
            throw new IllegalArgumentException("module scope requires modulePaths");
        }
        if (!paths.isEmpty() && !scope.equals("module") && !scope.equals("effective")) {
            throw new IllegalArgumentException("modulePaths requires effective or module scope");
        }
        Result result = load(projectKey, scope, paths, MAX_RULES + 1);
        if (result.instructions().size() > MAX_RULES) {
            throw new CapacityExceeded("count", result.instructions(), -1);
        }
        int bytes;
        try {
            bytes = mapper.writeValueAsBytes(result).length;
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("cannot serialize instruction response", exception);
        }
        if (bytes > MAX_BYTES) {
            throw new CapacityExceeded("bytes", result.instructions(), bytes);
        }
        return result;
    }

    private Result load(String projectKey, String scope, List<String> paths, int limit) {
        return jdbc.queryForObject("""
                WITH request AS (SELECT ?::text AS project_key, ?::text AS scope, ?::jsonb AS paths),
                visible AS (
                    SELECT d.id, v.version, v.statement, v.rationale, v.approved_by,
                           v.human_turn_ref, v.approval_content_hash,
                           CASE WHEN d.project_key IS NULL THEN 'GLOBAL_STRICT'
                                WHEN v.applies_all THEN 'PROJECT' ELSE 'MODULE' END AS scope,
                           d.project_key IS NULL OR v.applies_all OR EXISTS (
                               SELECT 1 FROM rule_target_bindings b,
                                    jsonb_array_elements_text(request.paths) path(value)
                               WHERE b.rule_id = d.id AND b.rule_version = v.version
                                 AND ((b.binding_kind = 'path_glob'
                                       AND (path.value = left(b.target_key, length(b.target_key) - 3)
                                            OR starts_with(path.value, left(b.target_key, length(b.target_key) - 2))))
                                      OR (b.binding_kind = 'file' AND path.value = b.target_key))
                           ) AS content_loaded,
                           COALESCE((SELECT jsonb_agg(CASE WHEN b.binding_kind = 'path_glob'
                                                           THEN left(b.target_key, length(b.target_key) - 3)
                                                           ELSE b.target_key END
                                                     ORDER BY b.target_key)
                                     FROM rule_target_bindings b
                                     WHERE b.rule_id = d.id AND b.rule_version = v.version
                                       AND b.binding_kind IN ('path_glob', 'file')), '[]'::jsonb) AS module_paths
                    FROM rule_definitions d JOIN rule_versions v
                      ON v.rule_id = d.id AND v.version = d.current_version
                    CROSS JOIN request
                    WHERE d.status = 'active' AND v.enforcement = 'instruction'
                      AND (d.project_key IS NULL OR d.project_key = request.project_key)
                ), selected AS (
                    SELECT visible.* FROM visible CROSS JOIN request
                    WHERE request.scope = 'effective'
                       OR (request.scope = 'global_strict' AND visible.scope = 'GLOBAL_STRICT')
                       OR (request.scope = 'project' AND visible.scope = 'PROJECT')
                       OR (request.scope = 'module' AND visible.scope = 'MODULE' AND content_loaded)
                    ORDER BY CASE visible.scope WHEN 'GLOBAL_STRICT' THEN 0 WHEN 'PROJECT' THEN 1 ELSE 2 END, id
                    LIMIT ?
                )
                SELECT EXISTS(SELECT 1 FROM scanner_project_roots roots
                              WHERE roots.project_key = request.project_key) AS known_project,
                       global_seq.seq AS global_seq, COALESCE(project_seq.seq, 0) AS project_seq,
                       COALESCE((SELECT jsonb_agg(jsonb_build_object(
                           'ruleId', id, 'version', version, 'scope', scope, 'contentLoaded', content_loaded,
                           'statement', CASE WHEN content_loaded THEN statement END,
                           'rationale', CASE WHEN content_loaded THEN rationale END,
                           'modulePaths', module_paths, 'approvedBy', approved_by,
                           'humanTurnRef', human_turn_ref, 'approvalContentHash', approval_content_hash)
                           ORDER BY CASE scope WHEN 'GLOBAL_STRICT' THEN 0 WHEN 'PROJECT' THEN 1 ELSE 2 END, id)
                           FROM selected), '[]'::jsonb)::text AS rules_json
                FROM request CROSS JOIN rules_global_effective_seq global_seq
                LEFT JOIN rules_project_effective_seq project_seq ON project_seq.project_key = request.project_key
                WHERE global_seq.singleton = true
                """, (rs, rowNum) -> {
                    if (!rs.getBoolean("known_project")) {
                        throw new IllegalArgumentException("unknown projectKey; resolve the repository first");
                    }
                    try {
                        List<Instruction> rows = mapper.readValue(rs.getString("rules_json"), new TypeReference<>() { });
                        return new Result(projectKey, scope, rs.getLong("global_seq"), rs.getLong("project_seq"),
                                true, List.copyOf(rows));
                    } catch (JsonProcessingException exception) {
                        throw new IllegalStateException("invalid stored instruction response", exception);
                    }
                }, projectKey, scope, json(paths), limit);
    }

    /** Hard ceiling for paged reads; approved rule sets are human-curated and far below this. */
    public static final int MAX_PAGED_RULES = 2_000;
    public static final int PAGE_RULES = 60;
    public static final int PAGE_BYTES = 48 * 1024;

    public record Page(Result result, int offset, int total, String nextCursor) {
        public boolean complete() {
            return nextCursor == null;
        }
    }

    public static final class RulesChanged extends IllegalStateException {
        public RulesChanged() {
            super("RULES_CHANGED: the effective rules changed while paging; restart from the first page");
        }
    }

    /**
     * Effective instructions in pages (at most {@value #PAGE_RULES} rules / {@value #PAGE_BYTES} bytes each) so a
     * large approved rule set is read completely instead of failing with CAPACITY_EXCEEDED. The cursor pins the
     * global/project effective sequence values and the project key; if rules change mid-read the caller must
     * restart, and a cursor from another project is rejected.
     */
    public Page readPage(String projectKey, String cursor) {
        if (projectKey == null || !projectKey.matches("[A-Z0-9][A-Z0-9_]{0,199}")) {
            throw new IllegalArgumentException("explicit canonical projectKey from scanner.project.resolve is required");
        }
        int offset = 0;
        Long pinnedGlobal = null;
        Long pinnedProject = null;
        if (cursor != null && !cursor.isBlank()) {
            String[] parts;
            try {
                parts = new String(java.util.Base64.getUrlDecoder().decode(cursor),
                        java.nio.charset.StandardCharsets.UTF_8).split(":");
                offset = Integer.parseInt(parts[0]);
                pinnedGlobal = Long.parseLong(parts[1]);
                pinnedProject = Long.parseLong(parts[2]);
            } catch (RuntimeException invalid) {
                throw new IllegalArgumentException("invalid rules cursor");
            }
            if (offset < 0 || parts.length != 4) throw new IllegalArgumentException("invalid rules cursor");
            if (!parts[3].equals(projectKey)) {
                throw new IllegalArgumentException("rules cursor belongs to a different project");
            }
        }
        Result all = load(projectKey, "effective", List.of(), MAX_PAGED_RULES + 1);
        if (all.instructions().size() > MAX_PAGED_RULES) {
            throw new CapacityExceeded("count", all.instructions(), -1);
        }
        if (pinnedGlobal != null && (pinnedGlobal != all.globalEffectiveSeq()
                || pinnedProject != all.projectEffectiveSeq())) {
            throw new RulesChanged();
        }
        List<Instruction> rows = all.instructions();
        if (offset > rows.size()) throw new IllegalArgumentException("invalid rules cursor");
        List<Instruction> page = new java.util.ArrayList<>();
        int bytes = 0;
        int index = offset;
        while (index < rows.size() && page.size() < PAGE_RULES) {
            int size;
            try {
                size = mapper.writeValueAsBytes(rows.get(index)).length;
            } catch (JsonProcessingException exception) {
                throw new IllegalStateException("cannot serialize instruction", exception);
            }
            if (!page.isEmpty() && bytes + size > PAGE_BYTES) break;
            page.add(rows.get(index));
            bytes += size;
            index++;
        }
        String next = index < rows.size()
                ? java.util.Base64.getUrlEncoder().withoutPadding().encodeToString((index + ":"
                        + all.globalEffectiveSeq() + ":" + all.projectEffectiveSeq() + ":" + projectKey)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8))
                : null;
        Result result = new Result(projectKey, "effective", all.globalEffectiveSeq(), all.projectEffectiveSeq(),
                next == null, List.copyOf(page));
        return new Page(result, offset, rows.size(), next);
    }

    private String json(List<String> paths) {
        try {
            return mapper.writeValueAsString(paths);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("invalid modulePaths", exception);
        }
    }
}
