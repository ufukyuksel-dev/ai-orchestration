package com.mbworldwideapps.aiorchestration.modules.scanner;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.List;

final class NoOpCodeBaselineRepository implements CodeBaselineRepository {

    @Override
    public void queueRun(UUID scanRunId, String projectKey, String rootPath, String provider, String semanticModel,
            Map<String, Object> metadata) {
    }

    @Override
    public void startRun(UUID scanRunId, String projectKey, String rootPath, String provider, String semanticModel,
            String promptVersion, boolean dataEgress, Map<String, Object> metadata) {
    }

    @Override
    public void completeRun(UUID scanRunId, String status, int filesDiscovered, int filesScanned, int filesSkipped,
            int filesRejected, int candidatesCreated, Map<String, Object> metadata) {
    }

    @Override
    public int failIncompleteRuns(String reason) {
        return 0;
    }

    @Override
    public boolean cancelRun(String projectKey, UUID scanRunId, String reason) {
        return false;
    }

    @Override
    public void upsertFile(CodeFileRecord file) {
    }

    @Override
    public void deleteFileFacts(String projectKey, UUID fileId) {
    }

    @Override
    public void deleteFilesForRootNotIn(String projectKey, String rootPath, Set<String> keepRelativePaths) {
    }

    @Override
    public void upsertSymbol(CodeSymbolRecord symbol) {
    }

    @Override
    public void upsertEdge(CodeEdgeRecord edge) {
    }

    @Override
    public boolean capsuleExists(String projectKey, String targetKey, String capsuleKind, String provider,
            String semanticModel, String promptVersion, String summarizerInputHash) {
        return false;
    }

    @Override
    public void upsertCapsule(CodeSemanticCapsuleRecord capsule) {
    }

    @Override
    public void insertDiagnostic(CodeDiagnosticRecord diagnostic) {
    }

    @Override
    public Optional<CodeSemanticCapsuleRecord> findCapsuleById(UUID capsuleId) {
        return Optional.empty();
    }

    @Override
    public List<CodeSemanticCapsuleRecord> findCapsulesBySymbolId(String projectKey, UUID symbolId) {
        return List.of();
    }

    @Override
    public List<CodeSemanticCapsuleRecord> searchCapsulesText(String projectKey, String query, int limit) {
        return List.of();
    }

    @Override
    public List<CodeSemanticCapsuleRecord> findCapsulesForRoot(String projectKey, String rootPath, int limit) {
        return List.of();
    }

    @Override
    public Optional<CodeSymbolRecord> findSymbolById(UUID symbolId) {
        return Optional.empty();
    }

    @Override
    public List<CodeSymbolRecord> findSymbolsByIds(String projectKey, Set<UUID> symbolIds, int limit) {
        return List.of();
    }

    @Override
    public List<CodeSymbolRecord> findSymbolsByRef(String projectKey, String ref, int limit) {
        return List.of();
    }

    @Override
    public List<CodeSymbolAnchorCandidateRecord> findSymbolAnchorCandidates(String projectKey, UUID afterSymbolId,
            int limit) {
        return List.of();
    }

    @Override
    public List<CodeEndpointAnchorCandidateRecord> findEndpointAnchorCandidates(String projectKey, UUID afterEdgeId,
            int limit) {
        return List.of();
    }

    @Override
    public List<CodeEdgeRecord> findEdgesFrom(String projectKey, UUID symbolId, Set<String> edgeTypes, int limit) {
        return List.of();
    }

    @Override
    public List<CodeEdgeRecord> findEdgesTo(String projectKey, UUID symbolId, Set<String> edgeTypes, int limit) {
        return List.of();
    }

    @Override
    public List<CodeEdgeRecord> findEdgesByTargetRefs(String projectKey, Set<String> targetRefs,
            Set<String> edgeTypes, int limit) {
        return List.of();
    }

    @Override
    public List<CodeDiagnosticRecord> recentDiagnostics(String projectKey, int limit) {
        return List.of();
    }

    @Override
    public List<CodeDiagnosticRecord> diagnosticsForRun(String projectKey, UUID scanRunId, int limit) {
        return List.of();
    }

    @Override
    public Optional<CodeScanRunRecord> findRun(String projectKey, UUID scanRunId) {
        return Optional.empty();
    }

    @Override
    public Optional<CodeScanRunRecord> findRun(UUID scanRunId) {
        return Optional.empty();
    }

    @Override
    public Optional<CodeScanRunRecord> latestCompletedRun(String projectKey) {
        return Optional.empty();
    }
}
