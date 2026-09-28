package com.mbworldwideapps.aiorchestration.config;

import java.util.List;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Which MCP tools an agent sees in tools/list. Every tool schema is resent to the model on every turn, so the
 * default "lean" profile advertises only the everyday tools; the rest stay callable and are listed for clients
 * that ask for the "full" profile (header X-AI-Orch-Tools: full) or are configured as full-profile clients.
 */
@ConfigurationProperties(prefix = "ai-orchestration.mcp.tools")
public record McpToolSurfaceProperties(String profile, List<String> lean, List<String> fullProfileClients,
        List<String> minimal) {

    public static final List<String> DEFAULT_LEAN = List.of(
            "session.bootstrap", "memory.search", "memory.learn", "memory.get",
            "codebase.symbol.get", "codebase.baseline.search",
            "last_job.get", "last_job.save", "job_memory.save", "job_memory.search", "job_memory.get",
            "personal_memory.save", "personal_memory.search", "reference.read", "rules.instructions", "extras");

    /**
     * For clients whose startup context comes from a hook (Claude Code): the session start needs no tool, so only
     * what an agent calls itself stays in every turn's context.
     */
    public static final List<String> DEFAULT_MINIMAL = List.of("memory.learn", "rules.instructions", "extras");

    public McpToolSurfaceProperties {
        profile = profile == null || profile.isBlank() ? "lean" : profile.trim().toLowerCase(java.util.Locale.ROOT);
        lean = lean == null || lean.isEmpty() ? DEFAULT_LEAN : List.copyOf(lean);
        fullProfileClients = fullProfileClients == null
                ? List.of("copilot-cli-memory-skill")
                : List.copyOf(fullProfileClients);
        minimal = minimal == null || minimal.isEmpty() ? DEFAULT_MINIMAL : List.copyOf(minimal);
    }

    public static McpToolSurfaceProperties defaults() {
        return new McpToolSurfaceProperties(null, null, null, null);
    }

    public Set<String> leanSet() {
        return Set.copyOf(lean);
    }

    public Set<String> minimalSet() {
        return Set.copyOf(minimal);
    }
}
