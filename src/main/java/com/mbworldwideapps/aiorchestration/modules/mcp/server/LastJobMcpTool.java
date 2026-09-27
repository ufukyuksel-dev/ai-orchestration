package com.mbworldwideapps.aiorchestration.modules.mcp.server;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;

@Component
@ConditionalOnProperty(prefix = "ai-orchestration.mcp", name = "enabled", havingValue = "true", matchIfMissing = true)
public class LastJobMcpTool {
    private final JdbcTemplate jdbc;
    private final McpAuditLogger audit;

    public LastJobMcpTool(JdbcTemplate jdbc, McpAuditLogger audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    public record Checkpoint(boolean found, String content, Instant updatedAt) {}
    public record Saved(boolean saved, Instant updatedAt) {}

    @McpTool(name = "last_job.get", description = "Read the saved \"continue where I left off\" handoff (only when the user asks to resume).")
    public Checkpoint get() {
        var context = McpClientContextHolder.require();
        var started = Instant.now();
        authorize(context, "memory.read", "last_job.get", started);
        try {
            var rows = jdbc.query("SELECT content, updated_at FROM last_job WHERE singleton_id = 1",
                    (rs, n) -> new Checkpoint(true, rs.getString(1), rs.getTimestamp(2).toInstant()));
            var result = rows.isEmpty() ? new Checkpoint(false, null, null) : rows.getFirst();
            audit.log(context, "last_job.get", null, rows.size(), started, "success");
            return result;
        } catch (RuntimeException e) {
            audit.log(context, "last_job.get", null, 0, started, "error");
            throw e;
        }
    }

    @McpTool(name = "last_job.save", description = "Save the single \"continue where I left off\" handoff (only when the user asks to save progress).")
    public Saved save(@McpToolParam(description = "Complete replacement handoff: goal, repositories, artifacts, verified outcomes, pending work and approval provenance. No secrets.") String content) {
        var context = McpClientContextHolder.require();
        var started = Instant.now();
        authorize(context, "memory.write", "last_job.save", started);
        try {
            if (content == null || content.isBlank() || content.getBytes(StandardCharsets.UTF_8).length > 262144) {
                throw new IllegalArgumentException("LAST_JOB must be nonblank and at most256KiB UTF-8");
            }
            var updated = jdbc.queryForObject("""
                    INSERT INTO last_job (singleton_id, content, updated_at) VALUES (1, ?, clock_timestamp())
                    ON CONFLICT (singleton_id) DO UPDATE SET content = EXCLUDED.content, updated_at = EXCLUDED.updated_at
                    RETURNING updated_at
                    """, (rs, n) -> rs.getTimestamp(1).toInstant(), content);
            audit.log(context, "last_job.save", null, 1, started, "success");
            return new Saved(true, updated);
        } catch (RuntimeException e) {
            audit.log(context, "last_job.save", null, 0, started, "error");
            throw e;
        }
    }

    private void authorize(McpClientContext context, String scope, String tool, Instant started) {
        if (!McpProjectKeys.isLocalTrust(context) || !context.hasScope(scope)) {
            audit.log(context, tool, null, 0, started, "denied_scope");
            throw new McpAccessException("LAST_JOB requires local-trust access and " + scope);
        }
    }
}
