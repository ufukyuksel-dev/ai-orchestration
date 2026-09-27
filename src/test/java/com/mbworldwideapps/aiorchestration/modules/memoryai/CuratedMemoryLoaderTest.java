package com.mbworldwideapps.aiorchestration.modules.memoryai;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.mbworldwideapps.aiorchestration.config.AiOrchestrationProperties;
import com.mbworldwideapps.aiorchestration.core.telemetry.AiMetrics;
import com.mbworldwideapps.aiorchestration.core.pii.PiiScrubber;
import com.mbworldwideapps.aiorchestration.core.policy.PolicyEngine;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CuratedMemoryLoaderTest {

    @TempDir
    Path tempDir;

    @Test
    void loadsGlobalMemoryAndReplaySkipsUnchangedFile() throws Exception {
        Path global = tempDir.resolve("global");
        Files.createDirectories(global);
        Files.writeString(global.resolve("service-naming-001.yml"), """
                id: service-naming-001
                type: rule
                title: Service naming
                text: "Services must follow acme-svc-<domain>-<service>."
                tags: ["service-naming", "java"]
                confidence: 1.0
                owner: platform
                last_verified_at: "2026-05-20T00:00:00Z"
                priority: 10
                token_cap: 50
                """);

        TestFixture fixture = fixture(global, tempDir.resolve("project"));

        CuratedMemoryLoadResult first = fixture.loader.loadAll();
        CuratedMemoryLoadResult second = fixture.loader.loadAll();

        assertThat(first.scanned()).isEqualTo(1);
        assertThat(first.loaded()).isEqualTo(1);
        assertThat(first.rejected()).isZero();
        assertThat(second.skipped()).isEqualTo(1);
        assertThat(fixture.repository.list(MemoryScope.GLOBAL, MemoryStatus.ACTIVE, null)).hasSize(1);
        MemoryItem item = fixture.repository.list(MemoryScope.GLOBAL, MemoryStatus.ACTIVE, null).getFirst();
        assertThat(item.sourceType()).isEqualTo(MemorySourceType.MANUAL);
        assertThat(item.status()).isEqualTo(MemoryStatus.ACTIVE);
        assertThat(item.sourceRef()).isEqualTo("curated:global:global:service-naming-001");
        assertThat(item.metadata()).containsKeys("contentHash", "priority", "tokenCap", "tokenEstimate");
    }

    @Test
    void changedCuratedFileUpdatesExistingMemory() throws Exception {
        Path global = tempDir.resolve("global");
        Files.createDirectories(global);
        Path file = global.resolve("logger-rule-001.yml");
        Files.writeString(file, yaml("Use acme-logger."));
        TestFixture fixture = fixture(global, tempDir.resolve("project"));

        CuratedMemoryLoadResult first = fixture.loader.loadAll();
        UUID memoryId = fixture.repository.items.values().iterator().next().id();
        Files.writeString(file, yaml("Use acme-logger for audit/info/error logging."));
        CuratedMemoryLoadResult second = fixture.loader.loadAll();

        assertThat(first.loaded()).isEqualTo(1);
        assertThat(second.updated()).isEqualTo(1);
        assertThat(fixture.repository.items).hasSize(1);
        assertThat(fixture.repository.items.get(memoryId).text()).contains("audit/info/error");
        assertThat(fixture.repository.events).extracting(MemoryEvent::eventType)
                .containsExactly(MemoryEventType.CREATED, MemoryEventType.UPDATED);
    }

    @Test
    void enabledRuleAuthorityMakesCuratedRuleAPendingCandidateAtTheLoaderBoundary() throws Exception {
        Path global = tempDir.resolve("global");
        Files.createDirectories(global);
        Files.writeString(global.resolve("controller-rule.yml"), yaml("Controllers must stay thin."));
        TestFixture fixture = fixture(global, tempDir.resolve("project"), new SimpleMeterRegistry(), true);

        CuratedMemoryLoadResult result = fixture.loader.loadAll();

        assertThat(result.loaded()).isEqualTo(1);
        assertThat(fixture.repository.items.values()).singleElement()
                .extracting(MemoryItem::status)
                .isEqualTo(MemoryStatus.PENDING_REVIEW);
        assertThat(fixture.repository.list(MemoryScope.GLOBAL, MemoryStatus.ACTIVE, null)).isEmpty();
    }

    @Test
    void loadsProjectMemoryWithConfiguredProjectKey() throws Exception {
        Path global = tempDir.resolve("global");
        Path project = tempDir.resolve("project");
        Files.createDirectories(project);
        Files.writeString(project.resolve("repository-pattern-001.yml"), """
                id: repository-pattern-001
                type: decision
                title: Repository pattern
                text: "Project data access must extend AcmeBaseRepository."
                tags: ["repository", "java"]
                confidence: 1.0
                owner: platform
                """);
        TestFixture fixture = fixture(global, project);

        CuratedMemoryLoadResult result = fixture.loader.loadAll();

        assertThat(result.loaded()).isEqualTo(1);
        List<MemoryItem> projectItems = fixture.repository.list(MemoryScope.PROJECT, MemoryStatus.ACTIVE,
                "AI_ORCHESTRATION");
        assertThat(projectItems).hasSize(1);
        assertThat(projectItems.getFirst().sourceRef())
                .isEqualTo("curated:project:AI_ORCHESTRATION:repository-pattern-001");
    }


    @Test
    void invalidMemoryFileIsRejectedAndMetricIsIncremented() throws Exception {
        Path global = tempDir.resolve("global");
        Files.createDirectories(global);
        Files.writeString(global.resolve("invalid.yml"), """
                id: invalid-001
                type: wrong_enum
                title: Invalid
                text: "Invalid type."
                """);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TestFixture fixture = fixture(global, tempDir.resolve("project"), registry);

        CuratedMemoryLoadResult result = fixture.loader.loadAll();

        assertThat(result.rejected()).isEqualTo(1);
        assertThat(result.files().getFirst().reason()).contains("validation-error");
        assertThat(registry.counter("ai_orchestration_memory_load_rejects_total", "reason", "validation-error").count())
                .isEqualTo(1.0);
    }

    @Test
    void tokenCapRejectsOversizedCuratedMemory() throws Exception {
        Path global = tempDir.resolve("global");
        Files.createDirectories(global);
        Files.writeString(global.resolve("too-long.yml"), """
                id: too-long
                type: rule
                title: Too long
                text: "one two three four five"
                token_cap: 3
                """);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TestFixture fixture = fixture(global, tempDir.resolve("project"), registry);

        CuratedMemoryLoadResult result = fixture.loader.loadAll();

        assertThat(result.rejected()).isEqualTo(1);
        assertThat(result.files().getFirst().reason()).contains("text exceeds token cap");
        assertThat(registry.counter("ai_orchestration_memory_load_rejects_total", "reason", "validation-error")
                .count()).isEqualTo(1.0);
    }

    private static String yaml(String text) {
        return """
                id: logger-rule-001
                type: rule
                title: Logger rule
                text: "%s"
                tags: ["logging", "java"]
                confidence: 1.0
                owner: platform
                last_verified_at: "2026-05-20T00:00:00Z"
                priority: 20
                token_cap: 80
                """.formatted(text);
    }

    private static TestFixture fixture(Path globalPath, Path projectPath) {
        return fixture(globalPath, projectPath, new SimpleMeterRegistry());
    }

    private static TestFixture fixture(Path globalPath, Path projectPath, SimpleMeterRegistry registry) {
        return fixture(globalPath, projectPath, registry, false);
    }

    private static TestFixture fixture(Path globalPath, Path projectPath, SimpleMeterRegistry registry,
            boolean rulesEnabled) {
        FakeMemoryRepository repository = new FakeMemoryRepository();
        MemoryService memoryService = MemoryServiceTestFixture.create(repository, properties(globalPath, projectPath),
                event -> { }, PolicyEngine.allowAll(), new PiiScrubber(),
                MemoryServiceTestFixture.noOpVectorIndex(), MemoryServiceTestFixture.rules(rulesEnabled),
                ignored -> false);
        CuratedMemoryLoader loader = new CuratedMemoryLoader(properties(globalPath, projectPath), memoryService,
                new AiMetrics(registry));
        return new TestFixture(loader, repository);
    }

    private static AiOrchestrationProperties properties(Path globalPath, Path projectPath) {
        return new AiOrchestrationProperties(
                1200,
                160,
                5,
                "logs/test-memory-loader.jsonl",
                384,
                "qwen3:8b",
                new AiOrchestrationProperties.Generation(false, "local-qwen", true, 8000L),
                new AiOrchestrationProperties.Qdrant(true),
                new AiOrchestrationProperties.Ollama(false),
                new AiOrchestrationProperties.Acl("config/user-groups.yml", 300000L),
                new AiOrchestrationProperties.Reranker(true, "noop", 20, 2000L, 60, null),
                null,
                new AiOrchestrationProperties.Memory("memory_episodic_test_384", 2000, 0.3,
                        globalPath.toString(), projectPath.toString(), "AI_ORCHESTRATION", false),
                365);
    }

    private record TestFixture(CuratedMemoryLoader loader, FakeMemoryRepository repository) {
    }

    private static final class FakeMemoryRepository implements MemoryRepository {
        private final Map<UUID, MemoryItem> items = new HashMap<>();
        private final List<MemoryEvent> events = new ArrayList<>();

        @Override
        public MemoryItem save(MemoryItem item) {
            items.put(item.id(), item);
            return item;
        }

        @Override
        public Optional<MemoryItem> findById(UUID id) {
            return Optional.ofNullable(items.get(id));
        }

        @Override
        public Optional<MemoryItem> findBySourceRef(String sourceRef) {
            return items.values().stream()
                    .filter(item -> sourceRef.equals(item.sourceRef()))
                    .findFirst();
        }

        @Override
        public List<MemoryItem> list(MemoryScope scope, MemoryStatus status, String projectKey) {
            return items.values().stream()
                    .filter(item -> scope == null || item.scope() == scope)
                    .filter(item -> status == null || item.status() == status)
                    .filter(item -> projectKey == null || projectKey.equals(item.projectKey()))
                    .toList();
        }

        @Override
        public MemoryItem update(MemoryItem item) {
            items.put(item.id(), item);
            return item;
        }

        @Override
        public boolean updateStatus(UUID id, MemoryStatus status) {
            MemoryItem item = items.get(id);
            if (item == null) {
                return false;
            }
            items.put(id, new MemoryItem(item.id(), item.vectorId(), item.scope(), item.projectKey(),
                    item.memoryType(), item.summary(), item.text(), item.tags(), item.confidence(), status,
                    item.sourceType(), item.sourceRef(), item.owner(), item.metadata(), item.createdAt(),
                    Instant.now(), item.lastUsedAt(), item.lastVerifiedAt(), item.expiresAt()));
            return true;
        }

        @Override
        public void insertEvent(MemoryEvent event) {
            events.add(event);
        }

        @Override
        public List<MemoryEvent> eventsForMemory(UUID memoryId) {
            return events.stream()
                    .filter(event -> memoryId.equals(event.memoryId()))
                    .toList();
        }

        @Override
        public void insertReviewQueue(ReviewQueueItem item) {
        }

        @Override
        public List<ReviewQueueItem> reviewQueueForMemory(UUID memoryId) {
            return List.of();
        }
    }
}
