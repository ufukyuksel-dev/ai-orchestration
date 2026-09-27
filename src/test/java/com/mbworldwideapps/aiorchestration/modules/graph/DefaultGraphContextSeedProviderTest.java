package com.mbworldwideapps.aiorchestration.modules.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.mbworldwideapps.aiorchestration.modules.graph.GraphContextRetrieveResponse.GraphWarning;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryItem;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryRankingProperties;
import com.mbworldwideapps.aiorchestration.modules.memoryai.RuleMemoryInjectionFilter;
import com.mbworldwideapps.aiorchestration.modules.memoryai.RuleMemoryLinkLookup;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryRepository;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryScope;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemorySourceType;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryStatus;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryType;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryVectorIndex;
import com.mbworldwideapps.aiorchestration.modules.memoryai.ScoredMemoryRef;
import com.mbworldwideapps.aiorchestration.modules.scanner.CodeBaselineRepository;
import com.mbworldwideapps.aiorchestration.modules.scanner.CodeBaselineVectorIndex;
import com.mbworldwideapps.aiorchestration.modules.scanner.CodeCapsuleLinkTargetRecord;
import com.mbworldwideapps.aiorchestration.modules.scanner.CodeFileRecord;
import com.mbworldwideapps.aiorchestration.modules.scanner.CodeSymbolRecord;
import com.mbworldwideapps.aiorchestration.modules.scanner.ScoredCodeCapsuleRef;
import com.mbworldwideapps.aiorchestration.config.RulesProperties;
import org.junit.jupiter.api.Test;

class DefaultGraphContextSeedProviderTest {

    @Test
    void reportsCodeAndMemoryPostgresFallbackSeparately() {
        CodeBaselineVectorIndex codeIndex = mock(CodeBaselineVectorIndex.class);
        CodeBaselineRepository codeRepository = mock(CodeBaselineRepository.class);
        MemoryVectorIndex memoryIndex = mock(MemoryVectorIndex.class);
        MemoryRepository memoryRepository = mock(MemoryRepository.class);
        when(codeIndex.search(eq("scanner"), anyInt(), eq("PROJECT")))
                .thenThrow(new IllegalStateException("qdrant"));
        when(codeRepository.searchCapsulesText(eq("PROJECT"), eq("scanner"), eq(5))).thenReturn(List.of());
        when(memoryIndex.search(eq("scanner"), eq(5), any())).thenThrow(new IllegalStateException("qdrant"));
        when(memoryRepository.searchForAutomaticInjection(eq("scanner"), anyInt())).thenReturn(List.of());
        DefaultGraphContextSeedProvider provider = new DefaultGraphContextSeedProvider(codeIndex, codeRepository,
                memoryIndex, memoryRepository);

        GraphContextSeedResult result = provider.seeds("scanner", "PROJECT", 5);

        assertThat(result.codePostgresFallbackUsed()).isTrue();
        assertThat(result.memoryPostgresFallbackUsed()).isTrue();
        assertThat(result.postgresFallbackUsed()).isTrue();
        assertThat(result.warnings()).extracting(GraphWarning::code)
                .contains("code_seed_unavailable", "code_postgres_fallback_used",
                        "memory_seed_unavailable", "memory_postgres_fallback_used");
    }

    @Test
    void zeroCapsulesUsesMatchingPostgresSymbolAndFileAndExcludesForeignProject() {
        CodeBaselineVectorIndex codeIndex = mock(CodeBaselineVectorIndex.class);
        CodeBaselineRepository codeRepository = mock(CodeBaselineRepository.class);
        MemoryVectorIndex memoryIndex = mock(MemoryVectorIndex.class);
        MemoryRepository memoryRepository = mock(MemoryRepository.class);
        UUID runId = UUID.randomUUID();
        UUID projectFileId = UUID.randomUUID();
        UUID foreignFileId = UUID.randomUUID();
        CodeFileRecord projectFile = new CodeFileRecord(projectFileId, "PROJECT",
                "src/main/java/example/ContextGraphMcpTool.java", "hash", "java", runId, Map.of());
        CodeFileRecord foreignFile = new CodeFileRecord(foreignFileId, "FOREIGN",
                "src/main/java/example/ContextGraphMcpTool.java", "hash", "java", runId, Map.of());
        CodeSymbolRecord projectSymbol = symbol(UUID.randomUUID(), "PROJECT", projectFileId,
                "ContextGraphMcpTool", "example.ContextGraphMcpTool");
        CodeSymbolRecord foreignSymbol = symbol(UUID.randomUUID(), "FOREIGN", foreignFileId,
                "ContextGraphMcpTool", "foreign.ContextGraphMcpTool");
        when(codeIndex.search(eq("ContextGraphMcpTool scope audit"), anyInt(), eq("PROJECT")))
                .thenReturn(List.of());
        when(codeRepository.searchCapsulesText("PROJECT", "ContextGraphMcpTool scope audit", 3))
                .thenReturn(List.of());
        when(codeRepository.findFilesForProject("PROJECT", null, 5_000))
                .thenReturn(List.of(projectFile, foreignFile));
        when(codeRepository.findSymbolsForProject("PROJECT", null, 5_000))
                .thenReturn(List.of(projectSymbol, foreignSymbol));
        when(memoryIndex.search(any(), anyInt(), any())).thenReturn(List.of());
        DefaultGraphContextSeedProvider provider = new DefaultGraphContextSeedProvider(codeIndex, codeRepository,
                memoryIndex, memoryRepository);

        GraphContextSeedResult result = provider.seeds("ContextGraphMcpTool scope audit", "PROJECT", 3);

        assertThat(result.seeds()).anySatisfy(seed -> {
            assertThat(seed.kind()).isEqualTo(GraphContextSeedKind.CODE_SYMBOL);
            assertThat(seed.key()).isEqualTo(projectSymbol.id().toString());
            assertThat(seed.projectKey()).isEqualTo("PROJECT");
        });
        assertThat(result.seeds()).anyMatch(seed -> seed.kind() == GraphContextSeedKind.CODE_FILE
                && seed.key().equals(projectFileId.toString()));
        assertThat(result.seeds()).noneMatch(seed -> "FOREIGN".equals(seed.projectKey())
                || seed.key().equals(foreignSymbol.id().toString()) || seed.key().equals(foreignFileId.toString()));
        assertThat(result.warnings()).extracting(GraphWarning::code)
                .contains("code_structural_postgres_fallback_used");
    }

    @Test
    void staleVectorRefsAreOversampledSoLaterCurrentCapsuleCanSeed() {
        CodeBaselineVectorIndex codeIndex = mock(CodeBaselineVectorIndex.class);
        CodeBaselineRepository codeRepository = mock(CodeBaselineRepository.class);
        MemoryVectorIndex memoryIndex = mock(MemoryVectorIndex.class);
        MemoryRepository memoryRepository = mock(MemoryRepository.class);
        UUID staleCapsuleId = UUID.randomUUID();
        UUID currentCapsuleId = UUID.randomUUID();
        UUID symbolId = UUID.randomUUID();
        UUID fileId = UUID.randomUUID();
        CodeCapsuleLinkTargetRecord target = new CodeCapsuleLinkTargetRecord(currentCapsuleId, "PROJECT",
                symbolId, fileId, symbolId.toString(), "class", "hash", "local-qwen", "model", "v1",
                UUID.randomUUID(), Map.of());
        when(codeIndex.search("scanner", 9, "PROJECT")).thenReturn(List.of(
                new ScoredCodeCapsuleRef(staleCapsuleId, 0.99),
                new ScoredCodeCapsuleRef(currentCapsuleId, 0.88)));
        when(codeRepository.findCapsuleLinkTargetById(staleCapsuleId)).thenReturn(Optional.empty());
        when(codeRepository.findCapsuleLinkTargetById(currentCapsuleId)).thenReturn(Optional.of(target));
        when(codeRepository.findCurrentCapsuleLinkTarget("PROJECT", symbolId.toString(), "class"))
                .thenReturn(Optional.of(target));
        when(codeRepository.findCapsuleById(currentCapsuleId)).thenReturn(Optional.empty());
        when(memoryIndex.search(any(), anyInt(), any())).thenReturn(List.of());
        DefaultGraphContextSeedProvider provider = new DefaultGraphContextSeedProvider(codeIndex, codeRepository,
                memoryIndex, memoryRepository);

        GraphContextSeedResult result = provider.seeds("scanner", "PROJECT", 1);

        verify(codeIndex).search("scanner", 9, "PROJECT");
        verify(codeIndex).deleteAll(List.of(staleCapsuleId));
        assertThat(result.seeds()).anyMatch(seed -> seed.kind() == GraphContextSeedKind.CODE_CAPSULE
                && seed.capsuleId().equals(currentCapsuleId));
        assertThat(result.warnings()).extracting(GraphWarning::code)
                .contains("code_stale_vector_refs_skipped", "code_stale_vector_refs_repaired");
    }

    @Test
    void staleVectorRepairRetriesWideEnoughToAvoidPostgresFallbackOnFirstQuery() {
        CodeBaselineVectorIndex codeIndex = mock(CodeBaselineVectorIndex.class);
        CodeBaselineRepository codeRepository = mock(CodeBaselineRepository.class);
        MemoryVectorIndex memoryIndex = mock(MemoryVectorIndex.class);
        MemoryRepository memoryRepository = mock(MemoryRepository.class);
        List<UUID> staleIds = java.util.stream.IntStream.range(0, 18)
                .mapToObj(ignored -> UUID.randomUUID())
                .toList();
        List<ScoredCodeCapsuleRef> staleRefs = staleIds.stream()
                .map(id -> new ScoredCodeCapsuleRef(id, 0.99))
                .toList();
        List<ScoredCodeCapsuleRef> currentRefs = new ArrayList<>();
        for (int index = 0; index < 5; index++) {
            UUID capsuleId = UUID.randomUUID();
            UUID symbolId = UUID.randomUUID();
            UUID fileId = UUID.randomUUID();
            CodeCapsuleLinkTargetRecord target = new CodeCapsuleLinkTargetRecord(capsuleId, "PROJECT",
                    symbolId, fileId, symbolId.toString(), "class", "hash-" + index, "local-qwen", "model",
                    "v1", UUID.randomUUID(), Map.of());
            currentRefs.add(new ScoredCodeCapsuleRef(capsuleId, 0.80 - (index * 0.01)));
            when(codeRepository.findCapsuleLinkTargetById(capsuleId)).thenReturn(Optional.of(target));
            when(codeRepository.findCurrentCapsuleLinkTarget("PROJECT", symbolId.toString(), "class"))
                    .thenReturn(Optional.of(target));
            when(codeRepository.findCapsuleById(capsuleId)).thenReturn(Optional.empty());
        }
        staleIds.forEach(id -> when(codeRepository.findCapsuleLinkTargetById(id)).thenReturn(Optional.empty()));
        when(codeIndex.search("scanner", 20, "PROJECT")).thenReturn(staleRefs);
        when(codeIndex.search("scanner", 80, "PROJECT")).thenReturn(currentRefs);
        when(memoryIndex.search(any(), anyInt(), any())).thenReturn(List.of());
        DefaultGraphContextSeedProvider provider = new DefaultGraphContextSeedProvider(codeIndex, codeRepository,
                memoryIndex, memoryRepository);

        GraphContextSeedResult result = provider.seeds("scanner", "PROJECT", 5);

        assertThat(result.codePostgresFallbackUsed()).isFalse();
        assertThat(result.seeds()).filteredOn(GraphContextSeed::isCode).hasSize(5);
        verify(codeIndex).deleteAll(staleIds);
        verify(codeIndex).search("scanner", 80, "PROJECT");
    }

    @Test
    void hybridKeepsLexicalCodeAndMemoryLanesWhenHighScoreVectorsFillSemanticLane() {
        CodeBaselineVectorIndex codeIndex = mock(CodeBaselineVectorIndex.class);
        CodeBaselineRepository codeRepository = mock(CodeBaselineRepository.class);
        MemoryVectorIndex memoryIndex = mock(MemoryVectorIndex.class);
        MemoryRepository memoryRepository = mock(MemoryRepository.class);
        UUID fileId = UUID.randomUUID();
        UUID symbolId = UUID.randomUUID();
        CodeFileRecord exactFile = new CodeFileRecord(fileId, "PROJECT",
                "src/main/java/example/ContextGraphMcpTool.java", "hash", "java", UUID.randomUUID(), Map.of());
        CodeSymbolRecord exactSymbol = symbol(symbolId, "PROJECT", fileId,
                "ContextGraphMcpTool", "example.ContextGraphMcpTool");
        List<ScoredCodeCapsuleRef> vectorRefs = new ArrayList<>();
        for (int index = 0; index < 2; index++) {
            UUID capsuleId = UUID.randomUUID();
            UUID vectorSymbolId = UUID.randomUUID();
            CodeCapsuleLinkTargetRecord target = new CodeCapsuleLinkTargetRecord(capsuleId, "PROJECT",
                    vectorSymbolId, UUID.randomUUID(), vectorSymbolId.toString(), "method", "hash-" + index,
                    "local-qwen", "model", "v1", UUID.randomUUID(), Map.of());
            vectorRefs.add(new ScoredCodeCapsuleRef(capsuleId, 0.99 - (index * 0.01)));
            when(codeRepository.findCapsuleLinkTargetById(capsuleId)).thenReturn(Optional.of(target));
            when(codeRepository.findCurrentCapsuleLinkTarget("PROJECT", vectorSymbolId.toString(), "method"))
                    .thenReturn(Optional.of(target));
            when(codeRepository.findCapsuleById(capsuleId)).thenReturn(Optional.empty());
        }
        when(codeIndex.search("context graph retrieve MCP tool scope ve audit nerede", 10, "PROJECT"))
                .thenReturn(vectorRefs);
        when(codeRepository.findFilesForProject("PROJECT", null, 5_000)).thenReturn(List.of(exactFile));
        when(codeRepository.findSymbolsForProject("PROJECT", null, 5_000)).thenReturn(List.of(exactSymbol));
        MemoryItem relevantMemory = memory(UUID.randomUUID(), "PROJECT");
        when(memoryIndex.search(eq("context graph retrieve MCP tool scope ve audit nerede"), eq(2), any()))
                .thenReturn(List.of(new ScoredMemoryRef(relevantMemory, 0.91)));
        DefaultGraphContextSeedProvider provider = new DefaultGraphContextSeedProvider(codeIndex, codeRepository,
                memoryIndex, memoryRepository);

        GraphContextSeedResult result = provider.seeds(
                "context graph retrieve MCP tool scope ve audit nerede", "PROJECT", 2, true, true);

        assertThat(result.codePostgresFallbackUsed()).isFalse();
        assertThat(result.seeds()).filteredOn(GraphContextSeed::isCode).hasSize(2);
        GraphContextSeed lexicalSeed = result.seeds().stream()
                .filter(seed -> seed.kind() == GraphContextSeedKind.CODE_SYMBOL)
                .filter(seed -> seed.key().equals(symbolId.toString()))
                .findFirst()
                .orElseThrow();
        assertThat(lexicalSeed.metadata()).containsEntry("filePath", exactFile.filePath())
                .containsEntry(GraphContextSeed.METADATA_RETRIEVAL_LANE,
                        GraphContextSeed.LANE_POSTGRES_LEXICAL);
        assertThat(result.seeds()).anyMatch(seed -> seed.kind() == GraphContextSeedKind.MEMORY
                && seed.key().equals(relevantMemory.id().toString()));
        assertThat(result.warnings()).extracting(GraphWarning::code)
                .contains("code_structural_postgres_supplement_used")
                .doesNotContain("code_structural_postgres_fallback_used");
    }

    @Test
    void qdrantSeedModeDoesNotRunHealthyPostgresStructuralSupplement() {
        CodeBaselineVectorIndex codeIndex = mock(CodeBaselineVectorIndex.class);
        CodeBaselineRepository codeRepository = mock(CodeBaselineRepository.class);
        MemoryVectorIndex memoryIndex = mock(MemoryVectorIndex.class);
        MemoryRepository memoryRepository = mock(MemoryRepository.class);
        UUID capsuleId = UUID.randomUUID();
        UUID symbolId = UUID.randomUUID();
        CodeCapsuleLinkTargetRecord target = new CodeCapsuleLinkTargetRecord(capsuleId, "PROJECT",
                symbolId, UUID.randomUUID(), symbolId.toString(), "method", "hash", "local-qwen", "model",
                "v1", UUID.randomUUID(), Map.of());
        when(codeIndex.search("scanner", 10, "PROJECT"))
                .thenReturn(List.of(new ScoredCodeCapsuleRef(capsuleId, 0.88)));
        when(codeRepository.findCapsuleLinkTargetById(capsuleId)).thenReturn(Optional.of(target));
        when(codeRepository.findCurrentCapsuleLinkTarget("PROJECT", symbolId.toString(), "method"))
                .thenReturn(Optional.of(target));
        when(codeRepository.findCapsuleById(capsuleId)).thenReturn(Optional.empty());
        DefaultGraphContextSeedProvider provider = new DefaultGraphContextSeedProvider(codeIndex, codeRepository,
                memoryIndex, memoryRepository);

        GraphContextSeedResult result = provider.seeds("scanner", "PROJECT", 2, false, false);

        assertThat(result.seeds()).singleElement().satisfies(seed ->
                assertThat(seed.metadata()).containsEntry(GraphContextSeed.METADATA_RETRIEVAL_LANE,
                        "qdrant-code-semantic"));
        assertThat(result.warnings()).extracting(GraphWarning::code)
                .doesNotContain("code_structural_postgres_supplement_used");
        verifyNoInteractions(memoryIndex, memoryRepository);
        org.mockito.Mockito.verify(codeRepository, org.mockito.Mockito.never())
                .findFilesForProject(any(), any(), anyInt());
        org.mockito.Mockito.verify(codeRepository, org.mockito.Mockito.never())
                .findSymbolsForProject(any(), any(), anyInt());
    }

    @Test
    void structuralFallbackAbstainsWhenThereIsNoLexicalMatch() {
        CodeBaselineVectorIndex codeIndex = mock(CodeBaselineVectorIndex.class);
        CodeBaselineRepository codeRepository = mock(CodeBaselineRepository.class);
        MemoryVectorIndex memoryIndex = mock(MemoryVectorIndex.class);
        MemoryRepository memoryRepository = mock(MemoryRepository.class);
        UUID fileId = UUID.randomUUID();
        when(codeIndex.search(eq("ContextGraphMcpTool"), anyInt(), eq("PROJECT"))).thenReturn(List.of());
        when(codeRepository.searchCapsulesText("PROJECT", "ContextGraphMcpTool", 2)).thenReturn(List.of());
        when(codeRepository.findFilesForProject("PROJECT", null, 5_000)).thenReturn(List.of(
                new CodeFileRecord(fileId, "PROJECT", "src/main/java/example/BillingController.java", "hash",
                        "java", UUID.randomUUID(), Map.of())));
        when(codeRepository.findSymbolsForProject("PROJECT", null, 5_000)).thenReturn(List.of(
                symbol(UUID.randomUUID(), "PROJECT", fileId, "BillingController", "example.BillingController")));
        when(memoryIndex.search(any(), anyInt(), any())).thenReturn(List.of());
        DefaultGraphContextSeedProvider provider = new DefaultGraphContextSeedProvider(codeIndex, codeRepository,
                memoryIndex, memoryRepository);

        GraphContextSeedResult result = provider.seeds("ContextGraphMcpTool", "PROJECT", 2);

        assertThat(result.seeds()).noneMatch(GraphContextSeed::isCode);
    }

    @Test
    void memoryVectorScoreBelowInjectionFloorIsExcludedWithoutPostgresBackfill() {
        CodeBaselineVectorIndex codeIndex = mock(CodeBaselineVectorIndex.class);
        CodeBaselineRepository codeRepository = mock(CodeBaselineRepository.class);
        MemoryVectorIndex memoryIndex = mock(MemoryVectorIndex.class);
        MemoryRepository memoryRepository = mock(MemoryRepository.class);
        MemoryItem lowScore = memory(UUID.randomUUID(), "PROJECT");
        when(codeIndex.search(eq("scanner"), anyInt(), eq("PROJECT"))).thenReturn(List.of());
        when(codeRepository.searchCapsulesText("PROJECT", "scanner", 2)).thenReturn(List.of());
        when(memoryIndex.search(eq("scanner"), eq(2), any()))
                .thenReturn(List.of(new ScoredMemoryRef(lowScore, 0.49)));
        MemoryRankingProperties ranking = new MemoryRankingProperties(null, null, null, null, null, 0.50, null);
        DefaultGraphContextSeedProvider provider = new DefaultGraphContextSeedProvider(codeIndex, codeRepository,
                memoryIndex, memoryRepository, ranking);

        GraphContextSeedResult result = provider.seeds("scanner", "PROJECT", 2);

        assertThat(result.seeds()).noneMatch(GraphContextSeed::isMemory);
        assertThat(result.warnings()).extracting(GraphWarning::code)
                .contains("memory_seed_abstained_no_relevant_match");
        verifyNoInteractions(memoryRepository);
    }

    @Test
    void codeOnlyModeDoesNotQueryMemoryIndexOrRepository() {
        CodeBaselineVectorIndex codeIndex = mock(CodeBaselineVectorIndex.class);
        CodeBaselineRepository codeRepository = mock(CodeBaselineRepository.class);
        MemoryVectorIndex memoryIndex = mock(MemoryVectorIndex.class);
        MemoryRepository memoryRepository = mock(MemoryRepository.class);
        when(codeIndex.search("scanner", 2, "PROJECT")).thenReturn(List.of());
        when(codeRepository.searchCapsulesText("PROJECT", "scanner", 2)).thenReturn(List.of());
        DefaultGraphContextSeedProvider provider = new DefaultGraphContextSeedProvider(codeIndex, codeRepository,
                memoryIndex, memoryRepository);

        GraphContextSeedResult result = provider.seeds("scanner", "PROJECT", 2, false);

        assertThat(result.memorySeedCount()).isZero();
        assertThat(result.memoryPostgresFallbackUsed()).isFalse();
        verifyNoInteractions(memoryIndex, memoryRepository);
    }

    @Test
    void promotedRuleOriginsAreExcludedFromVectorAndPostgresGraphSeedLanes() {
        CodeBaselineVectorIndex codeIndex = mock(CodeBaselineVectorIndex.class);
        CodeBaselineRepository codeRepository = mock(CodeBaselineRepository.class);
        MemoryVectorIndex memoryIndex = mock(MemoryVectorIndex.class);
        MemoryRepository memoryRepository = mock(MemoryRepository.class);
        MemoryItem promoted = memory(UUID.randomUUID(), "PROJECT");
        MemoryItem ordinary = memory(UUID.randomUUID(), "PROJECT");
        when(codeIndex.search(any(), anyInt(), eq("PROJECT"))).thenReturn(List.of());
        when(codeRepository.searchCapsulesText(eq("PROJECT"), any(), anyInt())).thenReturn(List.of());
        when(memoryIndex.search(eq("vector"), eq(12), any())).thenReturn(List.of(
                new ScoredMemoryRef(promoted, 0.95), new ScoredMemoryRef(ordinary, 0.90)));
        when(memoryIndex.search(eq("postgres"), eq(12), any())).thenThrow(new IllegalStateException("qdrant"));
        when(memoryRepository.searchForAutomaticInjection(eq("postgres"), anyInt()))
                .thenReturn(List.of(promoted, ordinary));
        RuleMemoryLinkLookup links = promoted.id()::equals;
        RuleMemoryInjectionFilter filter = new RuleMemoryInjectionFilter(
                new RulesProperties(true, 20, 256, 4096, 200), links);
        DefaultGraphContextSeedProvider provider = new DefaultGraphContextSeedProvider(
                codeIndex, codeRepository, memoryIndex, memoryRepository,
                MemoryRankingProperties.defaults(), filter);

        GraphContextSeedResult vector = provider.seeds("vector", "PROJECT", 3);
        GraphContextSeedResult postgres = provider.seeds("postgres", "PROJECT", 3);

        assertThat(vector.seeds()).filteredOn(GraphContextSeed::isMemory)
                .extracting(GraphContextSeed::key)
                .containsExactly(ordinary.id().toString());
        assertThat(postgres.seeds()).filteredOn(GraphContextSeed::isMemory)
                .extracting(GraphContextSeed::key)
                .containsExactly(ordinary.id().toString());
    }

    @Test
    void disablingRuleAuthorityRestoresPromotedOriginToLegacyGraphSeedLane() {
        CodeBaselineVectorIndex codeIndex = mock(CodeBaselineVectorIndex.class);
        CodeBaselineRepository codeRepository = mock(CodeBaselineRepository.class);
        MemoryVectorIndex memoryIndex = mock(MemoryVectorIndex.class);
        MemoryRepository memoryRepository = mock(MemoryRepository.class);
        MemoryItem promoted = memory(UUID.randomUUID(), "PROJECT");
        when(codeIndex.search(any(), anyInt(), eq("PROJECT"))).thenReturn(List.of());
        when(codeRepository.searchCapsulesText(eq("PROJECT"), any(), anyInt())).thenReturn(List.of());
        when(memoryIndex.search(eq("controller"), eq(2), any()))
                .thenReturn(List.of(new ScoredMemoryRef(promoted, 0.95)));
        RuleMemoryInjectionFilter filter = new RuleMemoryInjectionFilter(
                new RulesProperties(false, 20, 256, 4096, 200), ignored -> true);
        DefaultGraphContextSeedProvider provider = new DefaultGraphContextSeedProvider(
                codeIndex, codeRepository, memoryIndex, memoryRepository,
                MemoryRankingProperties.defaults(), filter);

        assertThat(provider.seeds("controller", "PROJECT", 2).seeds())
                .filteredOn(GraphContextSeed::isMemory)
                .extracting(GraphContextSeed::key)
                .containsExactly(promoted.id().toString());
    }

    private static CodeSymbolRecord symbol(UUID id, String projectKey, UUID fileId, String name, String fqn) {
        return new CodeSymbolRecord(id, projectKey, fileId, "class", name, fqn, name + "()", "service",
                10, 40, "hash", UUID.randomUUID(), Map.of());
    }

    private static MemoryItem memory(UUID id, String projectKey) {
        Instant now = Instant.now();
        return new MemoryItem(id, UUID.randomUUID(), MemoryScope.PROJECT, projectKey, MemoryType.RULE,
                "Scanner rule", "Keep scanner results project scoped", List.of("scanner"), 0.9,
                MemoryStatus.ACTIVE, MemorySourceType.MANUAL, "manual:test", "owner", Map.of(), now, now,
                null, now, null);
    }
}
