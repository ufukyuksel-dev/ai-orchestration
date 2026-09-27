package com.mbworldwideapps.aiorchestration.modules.mcp.server;

import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryRelationService;
import java.time.Instant;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;

@Component
@ConditionalOnProperty(prefix = "ai-orchestration.mcp", name = "enabled", havingValue = "true", matchIfMissing = true)
public class MemoryRelationMcpTool {
    private final MemoryRelationService relations;
    private final McpAuditLogger audit;

    public MemoryRelationMcpTool(MemoryRelationService relations, McpAuditLogger audit) {
        this.relations = relations;
        this.audit = audit;
    }

    @McpTool(name = "memory.relation.write", description = "memory.relation.write: Save an explicit relationship between eligible memories in one project, or from a memory to a shared reference ID (local-trust only). Same explanation returns the same ID and refreshes changed endpoint hashes/status with refreshed=true; different explanation or provenance conflicts. Returns canonical SQL state, not graph projection status. Does not approve or activate memories; supersedes is unsupported here.")
    public MemoryRelationService.Saved write(
            @McpToolParam(description = "Explicit project key") String projectKey,
            @McpToolParam(description = "Source memory UUID") String sourceId,
            @McpToolParam(description = "memory or reference") String targetKind,
            @McpToolParam(description = "Target memory or reference UUID") String targetId,
            @McpToolParam(description = "related_to, extends, depends_on, alternative_to, causes; references for reference targets") String type,
            @McpToolParam(description = "Relationship evidence, 1..2048 characters; redacted before persistence") String explanation,
            @McpToolParam(description = "Optional stable Markdown section key for a reference target", required = false) String sectionKey,
            @McpToolParam(description = "Required current reference SHA-256 when sectionKey is supplied", required = false) String contentHash) {
        McpClientContext context = McpClientContextHolder.require();
        Instant started = Instant.now();
        try {
            if (!context.hasScope("memory.write") || ("reference".equals(targetKind) && !McpProjectKeys.isLocalTrust(context)))
                throw new McpAccessException("Relationship write requires memory.write; reference targets require local-trust");
            if (projectKey == null || projectKey.isBlank())
                throw new IllegalArgumentException("Explicit projectKey is required");
            String project = McpProjectKeys.effective(context, projectKey);
            var saved = relations.write(project, UUID.fromString(sourceId), targetKind, UUID.fromString(targetId),
                    type, explanation, sectionKey, contentHash);
            audit.log(context, "memory.relation.write", null, 1, started, "success");
            return saved;
        } catch (McpAccessException e) {
            audit.log(context, "memory.relation.write", null, 0, started, "denied_scope");
            throw e;
        } catch (RuntimeException e) {
            audit.log(context, "memory.relation.write", null, 0, started, "error");
            throw e;
        }
    }

    /** Source-compatible Java overload; the MCP schema is emitted from the annotated method above. */
    public MemoryRelationService.Saved write(String projectKey, String sourceId, String targetKind,
            String targetId, String type, String explanation) {
        return write(projectKey, sourceId, targetKind, targetId, type, explanation, null, null);
    }
}
