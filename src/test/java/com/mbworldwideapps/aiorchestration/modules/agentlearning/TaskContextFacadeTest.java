package com.mbworldwideapps.aiorchestration.modules.agentlearning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.mbworldwideapps.aiorchestration.modules.agentlearning.AgentLearningModels.DiscoveryRoute;
import com.mbworldwideapps.aiorchestration.modules.agentlearning.AgentLearningModels.LearnedAnchor;
import com.mbworldwideapps.aiorchestration.modules.context.LearningContextItem;
import com.mbworldwideapps.aiorchestration.modules.context.LearningContextRequest;
import com.mbworldwideapps.aiorchestration.modules.context.LearningContextResponse;
import com.mbworldwideapps.aiorchestration.modules.context.LearningContextService;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryContextItem;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryContextResponse;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryScope;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryType;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryRelationService;
import com.mbworldwideapps.aiorchestration.modules.references.ReferenceService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class TaskContextFacadeTest {

    private final LearningContextService contexts = mock(LearningContextService.class);
    private final AgentLearningRepository repository = mock(AgentLearningRepository.class);
    private final TaskContextFacade facade = new TaskContextFacade(contexts, repository);

    @Test
    void projectsSelectedDiscoveryAndItsEvidenceBoundAnchor() {
        UUID memoryId = UUID.randomUUID();
        MemoryContextItem item = discovery(memoryId, "Fallback requires the flag and an empty primary result.");
        when(contexts.retrieveExplicit(any())).thenReturn(response(
                List.of(new LearningContextItem("memory", memoryId.toString(), "fallback", "project:" + memoryId,
                        item.text(), 0.9, 30, Map.of())), memory(item), List.of(), Map.of()));
        when(repository.findDiscoveryRoutes("P", List.of(memoryId), 3)).thenReturn(List.of(
                new DiscoveryRoute(memoryId, "fallback", item.text(), "SUPPORTED", false,
                        4L, "b".repeat(64), List.of(
                        new LearnedAnchor("src/CardResolver.java", "demo.CardResolver#resolve", "primary_change_point",
                                1, "a".repeat(64), "RESOLVED")))));

        var plan = facade.resolve("change fallback condition", "P", "change", 1200);

        assertThat(plan.knowledge()).singleElement().satisfies(knowledge -> {
            assertThat(knowledge.id()).isEqualTo(memoryId);
            assertThat(knowledge.text()).contains("empty primary result");
            assertThat(knowledge.verification()).isEqualTo("SUPPORTED");
            assertThat(knowledge.learningRevision()).isEqualTo(4L);
            assertThat(knowledge.canonicalHash()).isEqualTo("b".repeat(64));
        });
        assertThat(plan.targets()).singleElement().satisfies(target -> {
            assertThat(target.relativePath()).isEqualTo("src/CardResolver.java");
            assertThat(target.expectedHash()).isEqualTo("a".repeat(64));
            assertThat(target.sourceMemoryId()).isEqualTo(memoryId);
        });
        assertThat(plan.gaps()).isEmpty();
        ArgumentCaptor<LearningContextRequest> request = ArgumentCaptor.forClass(LearningContextRequest.class);
        verify(contexts).retrieveExplicit(request.capture());
        assertThat(request.getValue().contextMode()).isNull();
        assertThat(request.getValue().topK()).isEqualTo(3);
    }

    @Test
    void usesOnlyCompactExistingLocatorAsBoundedFallback() {
        LearningContextItem capsule = new LearningContextItem("graph_path", "p1", "PaymentFlow",
                "src/PaymentFlow.java:10-40", "bounded structural evidence", 0.8, 24, Map.of());
        when(contexts.retrieveExplicit(any())).thenReturn(response(
                List.of(capsule), MemoryContextResponse.empty(), List.of(), Map.of("droppedByBudgetCount", 0)));
        when(repository.findDiscoveryRoutes(eq("P"), eq(List.of()), eq(3))).thenReturn(List.of());

        var plan = facade.resolve("debug payment flow", "P", "debug", 800);

        assertThat(plan.knowledge()).isEmpty();
        assertThat(plan.targets()).singleElement().satisfies(target -> {
            assertThat(target.relativePath()).isEqualTo("src/PaymentFlow.java");
            assertThat(target.role()).isEqualTo("supporting");
            assertThat(target.expectedHash()).isNull();
            assertThat(target.sourceMemoryId()).isNull();
            assertThat(target.startLine()).isEqualTo(10);
            assertThat(target.endLine()).isEqualTo(40);
        });
        assertThat(plan.gaps()).contains("no_relevant_discovery");
    }

    @Test
    void distinguishesUnavailableRetrievalFromARealMiss() {
        when(contexts.retrieveExplicit(any())).thenReturn(response(
                List.of(), MemoryContextResponse.empty(), List.of("graph_context_unavailable:timeout"), Map.of()));

        var plan = facade.resolve("debug payment", "P", "debug", 800);

        assertThat(plan.retrievalUnavailable()).isTrue();
        assertThat(plan.gaps()).containsExactly("no_relevant_discovery", "no_focused_target");
    }

    @Test
    void keepsSqlDiscoveryNavigationWhenGraphIsUnavailable() {
        UUID memoryId = UUID.randomUUID();
        MemoryContextItem item = discovery(memoryId, "The bounded SQL mapping remains usable without graph data.");
        when(contexts.retrieveExplicit(any())).thenReturn(response(
                List.of(new LearningContextItem("memory", memoryId.toString(), "sql route",
                        "project:" + memoryId, item.text(), 0.9, 24, Map.of())),
                memory(item), List.of("graph_context_unavailable:timeout"), Map.of()));
        when(repository.findDiscoveryRoutes("P", List.of(memoryId), 3)).thenReturn(List.of(
                new DiscoveryRoute(memoryId, "sql route", item.text(), "SUPPORTED", false, List.of(
                        new LearnedAnchor("src/SqlRoute.java", null, "primary_change_point",
                                1, "b".repeat(64), "RESOLVED")))));

        var plan = facade.resolve("change sql route", "P", "change", 800);

        assertThat(plan.retrievalUnavailable()).isFalse();
        assertThat(plan.knowledge()).singleElement().extracting(knowledge -> knowledge.id())
                .isEqualTo(memoryId);
        assertThat(plan.targets()).singleElement().extracting(target -> target.relativePath())
                .isEqualTo("src/SqlRoute.java");
        assertThat(plan.gaps()).isEmpty();
    }

    @Test
    void excludesUnresolvedAnchorsAndCountsThemIndependentlyOfFallbackTargets() {
        UUID memoryId = UUID.randomUUID();
        MemoryContextItem item = discovery(memoryId, "The historical route remains useful as bounded knowledge.");
        when(contexts.retrieveExplicit(any())).thenReturn(response(
                List.of(
                        new LearningContextItem("memory", memoryId.toString(), "historical route",
                                "project:" + memoryId, item.text(), 0.9, 24, Map.of()),
                        new LearningContextItem("graph_path", "p2", "Current route",
                                "src/CurrentRoute.java:5-20", "bounded structural evidence", 0.8, 24, Map.of())),
                memory(item), List.of(), Map.of()));
        when(repository.findDiscoveryRoutes("P", List.of(memoryId), 3)).thenReturn(List.of(
                new DiscoveryRoute(memoryId, "historical route", item.text(), "SUPPORTED", false, List.of(
                        new LearnedAnchor("src/Ambiguous.java", null, "primary_change_point",
                                1, "a".repeat(64), "AMBIGUOUS"),
                        new LearnedAnchor("src/Missing.java", null, "supporting",
                                2, "b".repeat(64), "MISSING")))));

        var plan = facade.resolve("change historical route", "P", "change", 800);

        assertThat(plan.knowledge()).singleElement().extracting(knowledge -> knowledge.id())
                .isEqualTo(memoryId);
        assertThat(plan.targets()).singleElement().satisfies(target -> {
            assertThat(target.relativePath()).isEqualTo("src/CurrentRoute.java");
            assertThat(target.role()).isEqualTo("supporting");
        });
        assertThat(plan.omittedTargets()).isEqualTo(2);
        assertThat(plan.gaps()).isEmpty();
    }

    @Test
    void keepsDirectoryAnchorsAsNavigationMetadataWithoutOpeningThemAsSourceRanges() {
        UUID memoryId = UUID.randomUUID();
        MemoryContextItem item = discovery(memoryId, "The parser package and exact parse method are reusable.");
        when(contexts.retrieveExplicit(any())).thenReturn(response(
                List.of(new LearningContextItem("memory", memoryId.toString(), "parser route",
                        "project:" + memoryId, item.text(), 0.9, 24, Map.of())),
                memory(item), List.of(), Map.of()));
        when(repository.findDiscoveryRoutes("P", List.of(memoryId), 3)).thenReturn(List.of(
                new DiscoveryRoute(memoryId, "parser route", item.text(), "SUPPORTED", false, 1L,
                        "a".repeat(64), List.of(
                        new LearnedAnchor("modules/parser", null, "directory", "supporting", 1,
                                "b".repeat(64), "RESOLVED"),
                        new LearnedAnchor("src/Parser.java", "com.acme.Parser#parse(String)", "symbol",
                                "primary_change_point", 2, "c".repeat(64), "RESOLVED")))));

        var plan = facade.resolve("change parser", "P", "change", 800);

        assertThat(plan.targets()).singleElement().satisfies(target -> {
            assertThat(target.relativePath()).isEqualTo("src/Parser.java");
            assertThat(target.symbolRef()).isEqualTo("com.acme.Parser#parse(String)");
        });
        assertThat(plan.omittedTargets()).isEqualTo(1);
    }

    @Test
    void appliesTheResolveBudgetToWholeKnowledgeAtoms() {
        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        MemoryContextItem first = discovery(firstId, "a".repeat(700));
        MemoryContextItem second = discovery(secondId, "b".repeat(700));
        when(contexts.retrieveExplicit(any())).thenReturn(response(
                List.of(
                        new LearningContextItem("memory", firstId.toString(), "first", "project:" + firstId,
                                first.text(), 0.9, 30, Map.of()),
                        new LearningContextItem("memory", secondId.toString(), "second", "project:" + secondId,
                                second.text(), 0.8, 30, Map.of())),
                memory(List.of(first, second)), List.of(), Map.of()));
        when(repository.findDiscoveryRoutes("P", List.of(firstId, secondId), 3)).thenReturn(List.of(
                new DiscoveryRoute(firstId, "first", first.text(), "SUPPORTED", false, List.of()),
                new DiscoveryRoute(secondId, "second", second.text(), "SUPPORTED", false, List.of())));

        var plan = facade.resolve("change bounded route", "P", "change", 128);

        assertThat(plan.knowledge()).isEmpty();
        assertThat(plan.omittedKnowledge()).isEqualTo(2);
        assertThat(plan.gaps()).contains("knowledge_omitted_by_budget");
    }

    @Test
    void surfacesLifecycleStaleMemoryEvenWhenItsAnchorHashHasNotChanged() {
        UUID memoryId = UUID.randomUUID();
        MemoryContextItem item = discovery(memoryId, "This navigation hint was marked stale by lifecycle.");
        when(contexts.retrieveExplicit(any())).thenReturn(response(
                List.of(new LearningContextItem("memory", memoryId.toString(), "stale route",
                        "project:" + memoryId, item.text(), 0.9, 24, Map.of())),
                memory(item), List.of(), Map.of()));
        when(repository.findDiscoveryRoutes("P", List.of(memoryId), 3)).thenReturn(List.of(
                new DiscoveryRoute(memoryId, "stale route", item.text(), "SUPPORTED", true, List.of(
                        new LearnedAnchor("src/StaleRoute.java", null, "primary_change_point",
                                1, "c".repeat(64), "RESOLVED")))));

        var plan = facade.resolve("change stale route", "P", "change", 800);

        assertThat(plan.knowledge()).singleElement().extracting(knowledge -> knowledge.freshness())
                .isEqualTo("STALE");
        assertThat(plan.targets()).singleElement();
    }

    @Test
    void resolvesOnlySectionLinkedToSelectedProjectDiscoveryAndRevalidatesItsHash() {
        UUID memoryId = UUID.randomUUID();
        UUID referenceId = UUID.randomUUID();
        MemoryContextItem item = discovery(memoryId, "The deployment discovery links one bounded procedure section.");
        when(contexts.retrieveExplicit(any())).thenReturn(response(
                List.of(new LearningContextItem("memory", memoryId.toString(), "deploy route",
                        "project:" + memoryId, item.text(), 0.9, 24, Map.of())),
                memory(item), List.of(), Map.of()));
        when(repository.findDiscoveryRoutes("P", List.of(memoryId), 3)).thenReturn(List.of(
                new DiscoveryRoute(memoryId, "deploy route", item.text(), "SUPPORTED", false, List.of())));
        MemoryRelationService relations = mock(MemoryRelationService.class);
        ReferenceService references = mock(ReferenceService.class);
        var link = new MemoryRelationService.ReferenceLink(memoryId, referenceId, "ops.md", "file", "current",
                false, "a".repeat(64), "deploy~1");
        when(relations.linkedReferences("P", List.of(memoryId)))
                .thenReturn(new MemoryRelationService.ReferenceLinks(List.of(link), false));
        when(references.readSection(referenceId, "a".repeat(64), "deploy~1", 4)).thenReturn(
                new ReferenceService.SectionRead(
                        new ReferenceService.Item(referenceId, "ops.md", "file", "a".repeat(64), "current"),
                        "deploy~1", "Deploy", "# De", false, "b".repeat(64), false));
        TaskContextFacade referenceFacade = new TaskContextFacade(contexts, repository, relations, references);

        var plan = referenceFacade.resolve("change deploy route", "P", "change", 800, true);

        assertThat(plan.references()).singleElement().satisfies(reference -> {
            assertThat(reference.referenceId()).isEqualTo(referenceId);
            assertThat(reference.sectionKey()).isEqualTo("deploy~1");
            assertThat(reference.sourceMemoryId()).isEqualTo(memoryId);
            assertThat(reference.sourceCheck()).isEqualTo("UNCHANGED");
        });
        verify(relations).linkedReferences("P", List.of(memoryId));
        verify(references).readSection(referenceId, "a".repeat(64), "deploy~1", 4);
    }

    @Test
    void doesNotExpandLongReferenceWhenReferenceModeIsDisabled() {
        UUID memoryId = UUID.randomUUID();
        MemoryContextItem item = discovery(memoryId, "Short discovery is sufficient for this task.");
        when(contexts.retrieveExplicit(any())).thenReturn(response(
                List.of(new LearningContextItem("memory", memoryId.toString(), "short route",
                        "project:" + memoryId, item.text(), 0.9, 24, Map.of())),
                memory(item), List.of(), Map.of()));
        when(repository.findDiscoveryRoutes("P", List.of(memoryId), 3)).thenReturn(List.of(
                new DiscoveryRoute(memoryId, "short route", item.text(), "SUPPORTED", false, List.of())));
        MemoryRelationService relations = mock(MemoryRelationService.class);
        ReferenceService references = mock(ReferenceService.class);
        TaskContextFacade referenceFacade = new TaskContextFacade(contexts, repository, relations, references);

        var plan = referenceFacade.resolve("locate route", "P", "locate", 800);

        assertThat(plan.references()).isEmpty();
        verifyNoInteractions(relations, references);
    }

    private static MemoryContextItem discovery(UUID id, String text) {
        return new MemoryContextItem(id, UUID.randomUUID(), "project:" + id, MemoryScope.PROJECT, "P",
                MemoryType.DISCOVERY, "fallback", text, 0.9, false, 30, 0.91, Instant.now());
    }

    private static MemoryContextResponse memory(MemoryContextItem item) {
        return memory(List.of(item));
    }

    private static MemoryContextResponse memory(List<MemoryContextItem> items) {
        return new MemoryContextResponse(items, items.stream().map(MemoryContextItem::memoryId).toList(),
                items.size(), items.stream().mapToInt(MemoryContextItem::tokenEstimate).sum(),
                Map.of("user", 0, "global", 0, "project", items.size(), "episodic", 0), 0, 0, 0L);
    }

    private static LearningContextResponse response(List<LearningContextItem> items, MemoryContextResponse memory,
            List<String> warnings, Map<String, Object> metadata) {
        return new LearningContextResponse("P", items, memory, null, 80,
                false, false, false, false, false, warnings, metadata);
    }
}
