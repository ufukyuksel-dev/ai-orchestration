package com.mbworldwideapps.aiorchestration.modules.mcp.server;

public final class McpClientContextHolder {

    private static final ThreadLocal<McpClientContext> HOLDER = new ThreadLocal<>();

    private McpClientContextHolder() {
    }

    public static void set(McpClientContext context) {
        HOLDER.set(context);
    }

    public static McpClientContext get() {
        return HOLDER.get();
    }

    public static McpClientContext require() {
        McpClientContext context = HOLDER.get();
        if (context == null) {
            throw new McpAccessException("MCP client context is missing");
        }
        return context;
    }

    public static void clear() {
        HOLDER.remove();
    }
}
