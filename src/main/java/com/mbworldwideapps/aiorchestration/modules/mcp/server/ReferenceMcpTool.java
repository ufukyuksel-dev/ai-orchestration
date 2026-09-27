package com.mbworldwideapps.aiorchestration.modules.mcp.server;

import com.mbworldwideapps.aiorchestration.modules.references.ReferenceService;
import java.time.Instant;
import java.util.function.Supplier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;

@Component
@ConditionalOnProperty(prefix = "ai-orchestration.mcp", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ReferenceMcpTool {
    private final ReferenceService references;
    private final McpAuditLogger audit;

    public ReferenceMcpTool(ReferenceService references, McpAuditLogger audit) {
        this.references = references;
        this.audit = audit;
    }

    @McpTool(name = "reference.mkdir", description = "reference.mkdir: Create a directory in the single shared ai-references root. Local-user only; no project subdirectory is imposed. Returns a stable directory reference ID.")
    public ReferenceService.Saved mkdir(
            @McpToolParam(description = "Canonical path relative to shared ai-references, max1024 UTF-8 bytes/32 segments") String relativePath) {
        return invoke("reference.mkdir", "memory.write", () -> references.mkdir(relativePath));
    }

    @McpTool(name = "reference.write", description = "reference.write: Create or update UTF-8 reference text in the shared root. Local-user only. Max256KiB; existing different content requires its current SHA-256 expectedHash. Secrets are redacted before writing.")
    public ReferenceService.Saved write(
            @McpToolParam(description = "Canonical relative file path, max1024 UTF-8 bytes/32 segments") String relativePath,
            @McpToolParam(description = "Reference content, at most256KiB UTF-8") String content,
            @McpToolParam(description = "Current file SHA-256 for update; omit for create", required = false) String expectedHash) {
        return invoke("reference.write", "memory.write", () -> references.write(relativePath, content, expectedHash));
    }

    @McpTool(name = "reference.read", description = "Read a shared reference file listed in a memory result (linkedReferences).")
    public ReferenceService.Read read(
            @McpToolParam(description = "Canonical relative file path") String relativePath,
            @McpToolParam(description = "UTF-8 byte offset, default0", required = false) Integer offsetBytes,
            @McpToolParam(description = "Page size4..65536 bytes, default16384", required = false) Integer maxBytes) {
        return invoke("reference.read", "memory.read", () -> references.read(relativePath, offsetBytes, maxBytes));
    }

    @McpTool(name = "reference.list", description = "reference.list: List a directory of the shared reference catalog, including retained missing entries. Local-user only. Discovers at most1000 immediate disk entries; no recursive scan. Listing does not verify file content hashes.")
    public ReferenceService.Page list(
            @McpToolParam(description = "Canonical relative directory, empty or omitted for shared root", required = false) String dir,
            @McpToolParam(description = "Opaque nextCursor from this directory", required = false) String cursor,
            @McpToolParam(description = "Page size1..100, default25", required = false) Integer limit) {
        return invoke("reference.list", "memory.read", () -> references.list(dir, cursor, limit));
    }

    private <T> T invoke(String tool, String scope, Supplier<T> action) {
        McpClientContext context = McpClientContextHolder.require();
        Instant started = Instant.now();
        if (!McpProjectKeys.isLocalTrust(context) || !context.hasScope(scope)) {
            audit.log(context, tool, null, 0, started, "denied_scope");
            throw new McpAccessException("References require local-trust and " + scope);
        }
        try {
            T result = action.get();
            audit.log(context, tool, null, 1, started, "success");
            return result;
        } catch (RuntimeException e) {
            audit.log(context, tool, null, 0, started, "error");
            throw e;
        }
    }
}
