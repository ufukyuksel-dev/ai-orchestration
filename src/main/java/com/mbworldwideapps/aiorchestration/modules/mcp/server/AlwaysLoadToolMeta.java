package com.mbworldwideapps.aiorchestration.modules.mcp.server;

import java.util.Map;

import org.springaicommunity.mcp.context.MetaProvider;

/**
 * Marks a tool as always loaded for Claude Code ({@code _meta["anthropic/alwaysLoad"]}), so the few tools an
 * agent needs every session are not hidden behind client-side tool search. The lean-agent benchmark showed a
 * small model loading the deferred schema and then failing to invoke it. Keep this on as few tools as
 * possible: every always-loaded schema costs context in every session.
 */
public class AlwaysLoadToolMeta implements MetaProvider {

    static final String KEY = "anthropic/alwaysLoad";

    @Override
    public Map<String, Object> getMeta() {
        return Map.of(KEY, true);
    }
}
