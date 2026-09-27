package com.mbworldwideapps.aiorchestration.modules.mcp.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.mbworldwideapps.aiorchestration.config.AiOrchestrationProperties;
import com.mbworldwideapps.aiorchestration.modules.scanner.CodeBaselineRepository;
import com.mbworldwideapps.aiorchestration.modules.scanner.CodeBaselineVectorIndex;
import com.mbworldwideapps.aiorchestration.modules.scanner.CodeDiagnosticRecord;
import com.mbworldwideapps.aiorchestration.modules.scanner.CodeEdgeRecord;
import com.mbworldwideapps.aiorchestration.modules.scanner.CodeFileRecord;
import com.mbworldwideapps.aiorchestration.modules.scanner.CodeScanRunRecord;
import com.mbworldwideapps.aiorchestration.modules.scanner.CodeSemanticCapsuleRecord;
import com.mbworldwideapps.aiorchestration.modules.scanner.CodeSymbolRecord;
import com.mbworldwideapps.aiorchestration.modules.scanner.CodebaseService;
import com.mbworldwideapps.aiorchestration.modules.scanner.ScoredCodeCapsuleRef;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class CodebaseMcpToolTest {

    private final InMemoryAccessLogRepository accessLogRepository = new InMemoryAccessLogRepository();
    private final CodebaseMcpTool tool = new CodebaseMcpTool(
            new CodebaseService(new EmptyCodeBaselineRepository(), new EmptyCodeBaselineVectorIndex()),
            new McpAuditLogger(accessLogRepository, properties()),
            properties());

    @AfterEach
    void clearContext() {
        McpClientContextHolder.clear();
    }

    @Test
    void searchRequiresCodebaseReadScope() {
        McpClientContextHolder.set(new McpClientContext("PROJECT_A", "codex", "mcp_test",
                List.of("memory.read")));

        assertThatThrownBy(() -> tool.search("PaymentService", null, 5))
                .isInstanceOf(McpAccessException.class)
                .hasMessageContaining("codebase.read");

        assertThat(accessLogRepository.entries).hasSize(1);
        assertThat(accessLogRepository.entries.get(0).toolName()).isEqualTo("codebase.baseline.search");
        assertThat(accessLogRepository.entries.get(0).decision()).isEqualTo("denied_scope");
    }

    @Test
    void searchRejectsDifferentProjectAndAuditsDecision() {
        McpClientContextHolder.set(new McpClientContext("PROJECT_A", "codex", "mcp_test",
                List.of("codebase.read")));

        assertThatThrownBy(() -> tool.search("PaymentService", "PROJECT_B", 5))
                .isInstanceOf(McpAccessException.class)
                .hasMessageContaining("different project");

        assertThat(accessLogRepository.entries).hasSize(1);
        assertThat(accessLogRepository.entries.get(0).decision()).isEqualTo("denied_project");
        assertThat(accessLogRepository.entries.get(0).errorClass()).isEqualTo("McpAccessException");
    }

    @Test
    void searchEchoesEffectiveProjectKey() {
        McpClientContextHolder.set(new McpClientContext("PROJECT_A", "codex", "mcp_test",
                List.of("codebase.read")));

        CodebaseService.BaselineSearchResponse response = tool.search("PaymentService", null, 5);

        assertThat(response.projectKey()).isEqualTo("PROJECT_A");
        assertThat(accessLogRepository.entries).hasSize(1);
        assertThat(accessLogRepository.entries.get(0).metadata()).containsEntry("projectKey", "PROJECT_A");
    }

    @Test
    void symbolGetRejectsDifferentProjectAndAuditsDecision() {
        McpClientContextHolder.set(new McpClientContext("PROJECT_A", "codex", "mcp_test",
                List.of("codebase.read")));

        assertThatThrownBy(() -> tool.symbolGet("PaymentService", "PROJECT_B"))
                .isInstanceOf(McpAccessException.class)
                .hasMessageContaining("different project");

        assertThat(accessLogRepository.entries).hasSize(1);
        assertThat(accessLogRepository.entries.get(0).toolName()).isEqualTo("codebase.symbol.get");
        assertThat(accessLogRepository.entries.get(0).decision()).isEqualTo("denied_project");
    }

    private static AiOrchestrationProperties properties() {
        return new AiOrchestrationProperties(
                1200,
                160,
                5,
                "logs/test-codebase-mcp.jsonl",
                384,
                "qwen3:8b",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                new AiOrchestrationProperties.Mcp(true, "sse", "/mcp", true, 5, 50, 10_000L),
                365);
    }

    private static final class InMemoryAccessLogRepository implements McpAccessLogRepository {
        private final List<McpAccessLogEntry> entries = new ArrayList<>();

        @Override
        public void insert(McpAccessLogEntry entry) {
            entries.add(entry);
        }
    }

    private static final class EmptyCodeBaselineVectorIndex implements CodeBaselineVectorIndex {
        @Override
        public void upsert(CodeSemanticCapsuleRecord capsule) {
        }

        @Override
        public List<ScoredCodeCapsuleRef> search(String query, int topK, String projectKey) {
            return List.of();
        }
    }

    private static final class EmptyCodeBaselineRepository implements CodeBaselineRepository {
        @Override
        public void queueRun(UUID scanRunId, String projectKey, String rootPath, String provider,
                String semanticModel, Map<String, Object> metadata) {
        }

        @Override
        public void startRun(UUID scanRunId, String projectKey, String rootPath, String provider,
                String semanticModel, String promptVersion, boolean dataEgress, Map<String, Object> metadata) {
        }

        @Override
        public void completeRun(UUID scanRunId, String status, int filesDiscovered, int filesScanned,
                int filesSkipped, int filesRejected, int candidatesCreated, Map<String, Object> metadata) {
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
        public List<CodeEdgeRecord> findEdgesFrom(String projectKey, UUID symbolId, Set<String> edgeTypes,
                int limit) {
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
        public Optional<CodeScanRunRecord> latestCompletedRun(String projectKey) {
            return Optional.empty();
        }
    }
}
