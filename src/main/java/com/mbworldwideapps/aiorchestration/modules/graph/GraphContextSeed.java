package com.mbworldwideapps.aiorchestration.modules.graph;

import java.util.Map;
import java.util.UUID;

record GraphContextSeed(
        GraphContextSeedKind kind,
        String key,
        String projectKey,
        double score,
        String title,
        String summary,
        String text,
        UUID memoryId,
        UUID capsuleId,
        String capsuleLogicalKey,
        UUID symbolId,
        UUID fileId,
        String sourceRef,
        Map<String, Object> metadata) {

    static final String METADATA_RETRIEVAL_LANE = "retrievalLane";
    static final String LANE_POSTGRES_LEXICAL = "postgres-lexical";

    GraphContextSeed {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    boolean isCode() {
        return kind == GraphContextSeedKind.CODE_CAPSULE
                || kind == GraphContextSeedKind.CODE_SYMBOL
                || kind == GraphContextSeedKind.CODE_FILE;
    }

    boolean isMemory() {
        return kind == GraphContextSeedKind.MEMORY;
    }

    boolean isPostgresLexical() {
        return LANE_POSTGRES_LEXICAL.equals(metadata.get(METADATA_RETRIEVAL_LANE));
    }
}
