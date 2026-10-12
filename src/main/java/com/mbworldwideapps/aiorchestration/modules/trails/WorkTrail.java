package com.mbworldwideapps.aiorchestration.modules.trails;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * What one agent request touched, captured by the Stop hook: retrieval telemetry for later requests in the same
 * project, never a verified fact. Locators name symbols, resolved against the current source when used.
 */
public record WorkTrail(UUID id, String projectKey, String trailKey, String client, String codeFingerprint,
        String requestText, List<Locator> edited, List<Locator> cited, List<Locator> supportingReads,
        List<Check> checks, List<String> factIds, Instant createdAt, Instant updatedAt) {

    /**
     * A symbol in a file (`symbol` may be empty for file-level edits). `kind`: a definition kind for edits, `cited`,
     * `read` (observed read; `range` = the lines read) or `ref` (referenced by the request's changed lines). Edited
     * definitions may carry their final captured `text` (bounded) with its first `line`, the `tree` it was captured
     * from and a `hash` of the text: an observation of earlier work, never current source or a verified fact.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Locator(String path, String symbol, String kind, String range, Integer line, String text,
            String tree, String hash) {

        public Locator(String path, String symbol, String kind) {
            this(path, symbol, kind, null, null, null, null, null);
        }
    }

    /**
     * A check of the request: the command that really ran, the one the agent requested when a repair changed it,
     * the directory it ran in, the tree (content id) it checked, what it proved, its real outcome and whether tests
     * demonstrably ran.
     */
    public record Check(String command, String requested, String cwd, String tree, String scope, String status,
            String testEvidence) {
    }
}
