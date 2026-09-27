package com.mbworldwideapps.aiorchestration.modules.scanner;

import java.util.List;
import java.util.UUID;

public interface CodeBaselineVectorIndex {

    void upsert(CodeSemanticCapsuleRecord capsule);

    /** Removes obsolete physical capsule vectors after baseline root cleanup. */
    default void deleteAll(List<UUID> capsuleIds) {
        // Optional for non-persistent/test indexes. Search hydration still rejects
        // rows that no longer exist in Postgres.
    }

    List<ScoredCodeCapsuleRef> search(String query, int topK, String projectKey);
}
