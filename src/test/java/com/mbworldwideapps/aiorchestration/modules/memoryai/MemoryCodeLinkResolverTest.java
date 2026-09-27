package com.mbworldwideapps.aiorchestration.modules.memoryai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.mbworldwideapps.aiorchestration.config.MemoryCodeLinkProperties;
import com.mbworldwideapps.aiorchestration.modules.scanner.CodeBaselineRepository;
import com.mbworldwideapps.aiorchestration.modules.scanner.CodeBaselineVectorIndex;
import com.mbworldwideapps.aiorchestration.modules.scanner.CodeCapsuleLinkTargetRecord;
import com.mbworldwideapps.aiorchestration.modules.scanner.CodeFileRecord;
import com.mbworldwideapps.aiorchestration.modules.scanner.CodeSymbolRecord;
import org.junit.jupiter.api.Test;

class MemoryCodeLinkResolverTest {

    @Test
    void directoryResolvesOneTargetWithoutEnumeratingFilesOrSymbols() {
        var repository = mock(CodeBaselineRepository.class);
        var vectors = mock(CodeBaselineVectorIndex.class);
        when(repository.directoryExists("AI_ORCHESTRATION", "a/b")).thenReturn(true);
        var locator = new MemoryCodeLocator(MemoryCodeLocatorKind.DIRECTORY, "a/b/", null, null);
        var memory = item(MemoryType.DECISION, "test", MemoryCodeLocatorMetadata.replace(Map.of(), List.of(locator),
                MemoryScope.PROJECT, "AI_ORCHESTRATION"));
        var resolver = new MemoryCodeLinkResolver(properties(false), repository, vectors);
        assertThat(resolver.resolve(memory).links()).singleElement().satisfies(link -> {
            assertThat(link.targetKind()).isEqualTo(MemoryCodeTargetKind.DIRECTORY);
            assertThat(link.directoryPath()).isEqualTo("a/b");
            assertThat(link.fileId()).isNull();
            assertThat(link.symbolId()).isNull();
        });
        when(repository.directoryExists("AI_ORCHESTRATION", "a/b")).thenReturn(false);
        assertThat(resolver.resolve(memory).links()).isEmpty();
        org.mockito.Mockito.verify(repository, org.mockito.Mockito.times(2)).directoryExists("AI_ORCHESTRATION", "a/b");
        org.mockito.Mockito.verifyNoMoreInteractions(repository);
        verifyNoInteractions(vectors);
    }

    @Test
    void typedStableCapsuleLocatorResolvesWithoutPhysicalCapsuleId() {
        CodeBaselineRepository repository = mock(CodeBaselineRepository.class);
        CodeBaselineVectorIndex vectorIndex = mock(CodeBaselineVectorIndex.class);
        UUID fileId = UUID.randomUUID();
        UUID symbolId = UUID.randomUUID();
        CodeCapsuleLinkTargetRecord current = capsule(UUID.randomUUID(), fileId, symbolId, "new-hash");
        when(repository.findCurrentCapsuleLinkTarget("AI_ORCHESTRATION", "flow:PaymentService#pay",
                "project_insight")).thenReturn(Optional.of(current));
        Map<String, Object> metadata = MemoryCodeLocatorMetadata.replace(Map.of(), List.of(new MemoryCodeLocator(
                MemoryCodeLocatorKind.CAPSULE, "flow:PaymentService#pay", "project_insight",
                MemoryCodeLocatorRelationship.EVIDENCES)), MemoryScope.PROJECT, "AI_ORCHESTRATION");

        MemoryCodeLinkResolveResult result = new MemoryCodeLinkResolver(properties(false), repository, vectorIndex)
                .resolve(item(MemoryType.DECISION, "mcp-external:test", metadata));

        assertThat(result.links()).singleElement().satisfies(link -> {
            assertThat(link.targetKind()).isEqualTo(MemoryCodeTargetKind.CAPSULE);
            assertThat(link.capsuleLogicalKey())
                    .isEqualTo("AI_ORCHESTRATION|flow:PaymentService#pay|project_insight");
            assertThat(link.resolution()).isEqualTo("typed_locator");
        });
        assertThat(result.unresolvedSourceRefs()).isZero();
        verifyNoInteractions(vectorIndex);
    }

    @Test
    void typedFileAndSymbolLocatorsResolveExactProjectTargets() {
        CodeBaselineRepository repository = mock(CodeBaselineRepository.class);
        CodeBaselineVectorIndex vectorIndex = mock(CodeBaselineVectorIndex.class);
        UUID fileId = UUID.randomUUID();
        UUID symbolId = UUID.randomUUID();
        String filePath = "src/main/java/PaymentService.java";
        when(repository.findFileByPath("AI_ORCHESTRATION", filePath)).thenReturn(Optional.of(new CodeFileRecord(
                fileId, "AI_ORCHESTRATION", filePath, "hash", "java", UUID.randomUUID(), Map.of())));
        when(repository.findSymbolById(symbolId)).thenReturn(Optional.of(symbol(symbolId, fileId)));
        Map<String, Object> metadata = MemoryCodeLocatorMetadata.replace(Map.of(), List.of(
                new MemoryCodeLocator(MemoryCodeLocatorKind.FILE, filePath, null, null),
                new MemoryCodeLocator(MemoryCodeLocatorKind.SYMBOL, symbolId.toString(), null,
                        MemoryCodeLocatorRelationship.CONSTRAINS)), MemoryScope.PROJECT, "AI_ORCHESTRATION");

        MemoryCodeLinkResolveResult result = new MemoryCodeLinkResolver(properties(false), repository, vectorIndex)
                .resolve(item(MemoryType.DECISION, "mcp-external:test", metadata));

        assertThat(result.links()).hasSize(2);
        assertThat(result.links()).anySatisfy(link -> {
            assertThat(link.targetKind()).isEqualTo(MemoryCodeTargetKind.FILE);
            assertThat(link.fileId()).isEqualTo(fileId);
        });
        assertThat(result.links()).anySatisfy(link -> {
            assertThat(link.targetKind()).isEqualTo(MemoryCodeTargetKind.SYMBOL);
            assertThat(link.symbolId()).isEqualTo(symbolId);
            assertThat(link.relationshipType()).isEqualTo(MemoryCodeRelationshipType.CONSTRAINS);
        });
    }

    @Test
    void explicitEmptyTypedLocatorEnvelopeSuppressesLegacyMetadataFallback() {
        CodeBaselineRepository repository = mock(CodeBaselineRepository.class);
        CodeBaselineVectorIndex vectorIndex = mock(CodeBaselineVectorIndex.class);
        Map<String, Object> metadata = MemoryCodeLocatorMetadata.replace(
                Map.of("filePath", "src/main/java/Legacy.java", "symbolRef", "Legacy"), List.of(),
                MemoryScope.PROJECT, "AI_ORCHESTRATION");

        MemoryCodeLinkResolveResult result = new MemoryCodeLinkResolver(properties(false), repository, vectorIndex)
                .resolve(item(MemoryType.DECISION, "mcp-external:test", metadata));

        assertThat(result.links()).isEmpty();
        verifyNoInteractions(repository, vectorIndex);
    }

    @Test
    void scannerInsightSourceRefResolvesToCurrentCapsuleAndDeterministicEdges() {
        CodeBaselineRepository repository = mock(CodeBaselineRepository.class);
        CodeBaselineVectorIndex vectorIndex = mock(CodeBaselineVectorIndex.class);
        UUID oldCapsuleId = UUID.randomUUID();
        UUID currentCapsuleId = UUID.randomUUID();
        UUID fileId = UUID.randomUUID();
        UUID symbolId = UUID.randomUUID();
        CodeCapsuleLinkTargetRecord oldCapsule = capsule(oldCapsuleId, fileId, symbolId, "old-hash");
        CodeCapsuleLinkTargetRecord currentCapsule = capsule(currentCapsuleId, fileId, symbolId, "new-hash");
        when(repository.findCapsuleLinkTargetById(oldCapsuleId)).thenReturn(Optional.of(oldCapsule));
        when(repository.findCurrentCapsuleLinkTarget("AI_ORCHESTRATION", "flow:PaymentService#pay",
                "project_insight")).thenReturn(Optional.of(currentCapsule));
        when(repository.findSymbolById(symbolId)).thenReturn(Optional.of(symbol(symbolId, fileId)));

        MemoryItem item = item(MemoryType.DECISION, "scanner-flow-insight:" + oldCapsuleId, Map.of());
        MemoryCodeLinkResolveResult result = new MemoryCodeLinkResolver(properties(false), repository, vectorIndex)
                .resolve(item);

        assertThat(result.unresolvedSourceRefs()).isZero();
        assertThat(result.links()).hasSize(3);
        assertThat(result.links()).anySatisfy(link -> {
            assertThat(link.relationshipType()).isEqualTo(MemoryCodeRelationshipType.EVIDENCES);
            assertThat(link.targetKind()).isEqualTo(MemoryCodeTargetKind.CAPSULE);
            assertThat(link.capsuleLogicalKey())
                    .isEqualTo("AI_ORCHESTRATION|flow:PaymentService#pay|project_insight");
            assertThat(link.outputHash()).isEqualTo("new-hash");
        });
        assertThat(result.links()).anySatisfy(link -> {
            assertThat(link.relationshipType()).isEqualTo(MemoryCodeRelationshipType.MENTIONS);
            assertThat(link.targetKind()).isEqualTo(MemoryCodeTargetKind.FILE);
            assertThat(link.fileId()).isEqualTo(fileId);
        });
        assertThat(result.links()).anySatisfy(link -> {
            assertThat(link.relationshipType()).isEqualTo(MemoryCodeRelationshipType.CONSTRAINS);
            assertThat(link.targetKind()).isEqualTo(MemoryCodeTargetKind.SYMBOL);
            assertThat(link.symbolId()).isEqualTo(symbolId);
        });
        verifyNoInteractions(vectorIndex);
    }

    @Test
    void missingLegacyCapsuleRowIsUnresolvedAndDoesNotCreateEdge() {
        CodeBaselineRepository repository = mock(CodeBaselineRepository.class);
        CodeBaselineVectorIndex vectorIndex = mock(CodeBaselineVectorIndex.class);
        UUID oldCapsuleId = UUID.randomUUID();
        when(repository.findCapsuleLinkTargetById(oldCapsuleId)).thenReturn(Optional.empty());

        MemoryItem item = item(MemoryType.DECISION, "scanner-flow-insight:" + oldCapsuleId, Map.of());
        MemoryCodeLinkResolveResult result = new MemoryCodeLinkResolver(properties(false), repository, vectorIndex)
                .resolve(item);

        assertThat(result.links()).isEmpty();
        assertThat(result.unresolvedSourceRefs()).isEqualTo(1);
    }

    @Test
    void legacyCapsuleFromDifferentProjectIsRejectedBeforeCurrentTargetLookup() {
        CodeBaselineRepository repository = mock(CodeBaselineRepository.class);
        CodeBaselineVectorIndex vectorIndex = mock(CodeBaselineVectorIndex.class);
        UUID oldCapsuleId = UUID.randomUUID();
        UUID fileId = UUID.randomUUID();
        UUID symbolId = UUID.randomUUID();
        CodeCapsuleLinkTargetRecord otherProject = new CodeCapsuleLinkTargetRecord(oldCapsuleId, "OTHER_PROJECT",
                symbolId, fileId, "flow:PaymentService#pay", "project_insight", "hash", "local-qwen",
                "qwen3:8b", "scanner-v1", UUID.randomUUID(), Map.of());
        when(repository.findCapsuleLinkTargetById(oldCapsuleId)).thenReturn(Optional.of(otherProject));

        MemoryCodeLinkResolveResult result = new MemoryCodeLinkResolver(properties(false), repository, vectorIndex)
                .resolve(item(MemoryType.DECISION, "scanner-flow-insight:" + oldCapsuleId, Map.of()));

        assertThat(result.links()).isEmpty();
        assertThat(result.unresolvedSourceRefs()).isEqualTo(1);
    }

    @Test
    void ambiguousSymbolRefDoesNotCreateWeakNameOnlyEdge() {
        CodeBaselineRepository repository = mock(CodeBaselineRepository.class);
        CodeBaselineVectorIndex vectorIndex = mock(CodeBaselineVectorIndex.class);
        UUID fileId = UUID.randomUUID();
        when(repository.findSymbolsByRef(eq("AI_ORCHESTRATION"), eq("PaymentService"), anyInt()))
                .thenReturn(List.of(symbol(UUID.randomUUID(), fileId), symbol(UUID.randomUUID(), fileId)));

        MemoryItem item = item(MemoryType.PREFERENCE, "manual:1", Map.of("symbolRef", "PaymentService"));
        MemoryCodeLinkResolveResult result = new MemoryCodeLinkResolver(properties(false), repository, vectorIndex)
                .resolve(item);

        assertThat(result.links()).isEmpty();
        assertThat(result.missingSymbols()).isEqualTo(1);
    }

    private static CodeCapsuleLinkTargetRecord capsule(UUID id, UUID fileId, UUID symbolId, String outputHash) {
        return new CodeCapsuleLinkTargetRecord(id, "AI_ORCHESTRATION", symbolId, fileId,
                "flow:PaymentService#pay", "project_insight", outputHash, "local-qwen", "qwen3:8b",
                "scanner-v1", UUID.randomUUID(), Map.of("filePath", "src/main/java/PaymentService.java"));
    }

    private static CodeSymbolRecord symbol(UUID symbolId, UUID fileId) {
        return new CodeSymbolRecord(symbolId, "AI_ORCHESTRATION", fileId, "method", "pay",
                "com.acme.PaymentService#pay", "pay(String)", "service", 10, 20, "hash", UUID.randomUUID(),
                Map.of());
    }

    private static MemoryItem item(MemoryType type, String sourceRef, Map<String, Object> metadata) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.parse("2026-05-20T08:00:00Z");
        return new MemoryItem(id, UUID.randomUUID(), MemoryScope.PROJECT, "AI_ORCHESTRATION", type,
                "Payment flow insight", "Raw text is not projected", List.of("scanner"), 0.8,
                MemoryStatus.ACTIVE, MemorySourceType.SCANNED, sourceRef, "scanner", metadata, now, now,
                null, now, null);
    }

    private static MemoryCodeLinkProperties properties(boolean similarityEnabled) {
        return new MemoryCodeLinkProperties(true, true, similarityEnabled, 0.72, 5, 500, 20);
    }
}
