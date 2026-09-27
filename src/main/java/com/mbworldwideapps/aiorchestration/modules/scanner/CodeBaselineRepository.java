package com.mbworldwideapps.aiorchestration.modules.scanner;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface CodeBaselineRepository {

    void queueRun(UUID scanRunId, String projectKey, String rootPath, String provider, String semanticModel,
            Map<String, Object> metadata);

    void startRun(UUID scanRunId, String projectKey, String rootPath, String provider, String semanticModel,
            String promptVersion, boolean dataEgress, Map<String, Object> metadata);

    void completeRun(UUID scanRunId, String status, int filesDiscovered, int filesScanned, int filesSkipped,
            int filesRejected, int candidatesCreated, Map<String, Object> metadata);

    /**
     * Running-status progress heartbeat: merge {@code metadataDelta} (and optionally set files_scanned) WITHOUT
     * touching completed_at or status. No-op by default so scanner.scan.status reflects in-flight progress instead
     * of appearing frozen at 0 until completion. Only persistent repositories override.
     */
    default void updateRunProgress(UUID scanRunId, Integer filesScanned, Map<String, Object> metadataDelta) {
        // no-op
    }

    int failIncompleteRuns(String reason);

    boolean cancelRun(String projectKey, UUID scanRunId, String reason);

    void upsertFile(CodeFileRecord file);

    void deleteFileFacts(String projectKey, UUID fileId);

    void deleteFilesForRootNotIn(String projectKey, String rootPath, Set<String> keepRelativePaths);

    /**
     * Returns physical capsule ids that will be removed when one file is rebuilt.
     * Persistent vector indexes use these ids to remove the matching documents after
     * the Postgres facts are deleted.
     */
    default List<UUID> findCapsuleIdsForFile(String projectKey, UUID fileId) {
        return List.of();
    }

    /**
     * Returns physical capsule ids that will be removed by the same-root stale-file
     * sweep.
     */
    default List<UUID> findCapsuleIdsForRootNotIn(String projectKey, String rootPath,
            Set<String> keepRelativePaths) {
        return List.of();
    }

    void upsertSymbol(CodeSymbolRecord symbol);

    void upsertEdge(CodeEdgeRecord edge);

    boolean capsuleExists(String projectKey, String targetKey, String capsuleKind, String provider,
            String semanticModel, String promptVersion, String summarizerInputHash);

    void upsertCapsule(CodeSemanticCapsuleRecord capsule);

    void insertDiagnostic(CodeDiagnosticRecord diagnostic);

    Optional<CodeSemanticCapsuleRecord> findCapsuleById(UUID capsuleId);

    List<CodeSemanticCapsuleRecord> findCapsulesBySymbolId(String projectKey, UUID symbolId);

    List<CodeSemanticCapsuleRecord> searchCapsulesText(String projectKey, String query, int limit);

    List<CodeSemanticCapsuleRecord> findCapsulesForRoot(String projectKey, String rootPath, int limit);

    Optional<CodeSymbolRecord> findSymbolById(UUID symbolId);

    default Optional<CodeFileRecord> findFileById(String projectKey, UUID fileId) {
        return Optional.empty();
    }

    default Optional<CodeFileRecord> findFileByPath(String projectKey, String filePath) {
        return Optional.empty();
    }

    default List<CodeSymbolRecord> findSymbolsForRun(String projectKey, UUID scanRunId, int limit) {
        return List.of();
    }

    default boolean directoryExists(String projectKey, String canonicalDirectory) {
        return false;
    }

    default List<CodeFileRecord> findFilesForProject(String projectKey, String afterFilePath, int limit) {
        return List.of();
    }

    default List<CodeSymbolRecord> findSymbolsForProject(String projectKey, UUID afterSymbolId, int limit) {
        return List.of();
    }

    default List<CodeEdgeRecord> findEdgesForProject(String projectKey, UUID afterEdgeId, int limit) {
        return List.of();
    }

    /**
     * Returns all edges recorded in the given scan run (up to {@code limit}).
     * Used by {@link com.mbworldwideapps.aiorchestration.modules.architecture.StructuralImportanceScorer}
     * to compute per-symbol degree counts without per-symbol round-trips.
     */
    default List<CodeEdgeRecord> findEdgesForRun(String projectKey, UUID scanRunId, int limit) {
        return List.of();
    }

    /**
     * Batch-resolves file paths for the given set of file IDs.
     * Used by {@link com.mbworldwideapps.aiorchestration.modules.architecture.StructuralImportanceScorer}
     * to populate {@link com.mbworldwideapps.aiorchestration.modules.architecture.ImportanceRankedSymbol#filePath()}
     * without loading the full {@code CodeFileRecord}.
     *
     * @param fileIds set of file UUIDs to look up; may be empty (returns empty map)
     * @return map from file ID to file path; missing IDs are absent from the map
     */
    default Map<UUID, String> findFilePathsByIds(Set<UUID> fileIds) {
        return Map.of();
    }

    default List<CodeCapsuleProjectionRecord> findCurrentCapsulesForProject(String projectKey, String afterTargetKey,
            String afterCapsuleKind, int limit) {
        return List.of();
    }

    default Optional<CodeCapsuleProjectionRecord> findCurrentCapsuleForTarget(String projectKey, String targetKey,
            String capsuleKind) {
        return Optional.empty();
    }

    default List<CodeSymbolAnchorCandidateRecord> findSymbolAnchorCandidates(String projectKey, UUID afterSymbolId,
            int limit) {
        return List.of();
    }

    default List<CodeEndpointAnchorCandidateRecord> findEndpointAnchorCandidates(String projectKey, UUID afterEdgeId,
            int limit) {
        return List.of();
    }

    default Optional<CodeCapsuleLinkTargetRecord> findCapsuleLinkTargetById(UUID capsuleId) {
        return Optional.empty();
    }

    default Optional<CodeCapsuleLinkTargetRecord> findCurrentCapsuleLinkTarget(String projectKey, String targetKey,
            String capsuleKind) {
        return Optional.empty();
    }

    List<CodeSymbolRecord> findSymbolsByIds(String projectKey, Set<UUID> symbolIds, int limit);

    List<CodeSymbolRecord> findSymbolsByRef(String projectKey, String ref, int limit);

    default List<CodeSymbolRecord> findSymbolsByLocator(String projectKey, String filePath,
            String fqn, String signature, int limit) {
        return List.of();
    }

    List<CodeEdgeRecord> findEdgesFrom(String projectKey, UUID symbolId, Set<String> edgeTypes, int limit);

    List<CodeEdgeRecord> findEdgesTo(String projectKey, UUID symbolId, Set<String> edgeTypes, int limit);

    List<CodeEdgeRecord> findEdgesByTargetRefs(String projectKey, Set<String> targetRefs, Set<String> edgeTypes,
            int limit);

    List<CodeDiagnosticRecord> recentDiagnostics(String projectKey, int limit);

    List<CodeDiagnosticRecord> diagnosticsForRun(String projectKey, UUID scanRunId, int limit);

    Optional<CodeScanRunRecord> findRun(String projectKey, UUID scanRunId);

    default Optional<CodeScanRunRecord> findRun(UUID scanRunId) {
        return Optional.empty();
    }

    Optional<CodeScanRunRecord> latestCompletedRun(String projectKey);
}
