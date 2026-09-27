package com.mbworldwideapps.aiorchestration.modules.memoryai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.mbworldwideapps.aiorchestration.config.AiOrchestrationProperties;
import com.mbworldwideapps.aiorchestration.config.RulesProperties;
import org.junit.jupiter.api.Test;

class MemoryRetrievalServiceTest {

    @Test
    void previewsExposeOnlyBoundedDeclaredLocatorsAndTolerateInvalidMetadata() {
        List<MemoryCodeLocator> locators = java.util.stream.IntStream.range(0, 6)
                .mapToObj(i -> new MemoryCodeLocator(MemoryCodeLocatorKind.SYMBOL,
                        "com.example.Example#method" + i, null, null)).toList();
        Map<String, Object> metadata = MemoryCodeLocatorMetadata.replace(Map.of("privateNote", "not returned"),
                locators, MemoryScope.PROJECT, "AI_ORCHESTRATION");
        MemoryItem valid = withMetadata(item(MemoryScope.PROJECT, "AI_ORCHESTRATION", MemoryStatus.ACTIVE,
                "Verified method contract", 1.0), metadata);
        MemoryItem malformed = withMetadata(item(MemoryScope.PROJECT, "AI_ORCHESTRATION", MemoryStatus.ACTIVE,
                "Legacy useful fact", 1.0), Map.of("codeLocators", "private invalid envelope"));
        MemoryItem global = withMetadata(item(MemoryScope.GLOBAL, null, MemoryStatus.ACTIVE,
                "Global useful fact", 1.0), metadata);
        FakeMemoryRepository repository = new FakeMemoryRepository();
        repository.put(valid, malformed, global);
        FakeScoredMemoryVectorIndex vectors = new FakeScoredMemoryVectorIndex(repository);
        vectors.scoreBy(valid, 0.9).scoreBy(malformed, 0.8).scoreBy(global, 0.7);
        List<MemorySearchHit> hits = new MemoryRetrievalService(repository, vectors, properties(2000))
                .searchPreviews("contract", "AI_ORCHESTRATION", null, 3);
        assertThat(hits).hasSize(3);
        MemorySearchHit hit = hits.stream().filter(h -> h.memoryId().equals(valid.id())).findFirst().orElseThrow();
        assertThat(hit.declaredCodeLocators()).containsExactlyElementsOf(locators.subList(0, 4));
        assertThat(hit.omittedCodeLocators()).isEqualTo(2);
        assertThat(hit.warnings()).isEmpty();
        assertThat(hit.tokenEstimate()).isGreaterThan(
                MemoryTextPreview.estimateTokens(hit.summary() + " " + hit.excerpt()));
        assertThat(hits.stream().filter(h -> !h.memoryId().equals(valid.id())).toList())
                .allSatisfy(h -> {
                    assertThat(h.declaredCodeLocators()).isEmpty();
                    assertThat(h.warnings()).containsExactly("invalid_code_locators_omitted");
                    assertThat(h.excerpt()).isNotBlank();
                });
    }

    private static MemoryItem withMetadata(MemoryItem item, Map<String, Object> metadata) {
        return new MemoryItem(item.id(), item.vectorId(), item.scope(), item.projectKey(), item.memoryType(),
                item.summary(), item.text(), item.tags(), item.confidence(), item.status(), item.sourceType(),
                item.sourceRef(), item.owner(), metadata, item.createdAt(), item.updatedAt(), item.lastUsedAt(),
                item.lastVerifiedAt(), item.expiresAt());
    }

    @Test
    void optionalSearchDetailKeepsEligibilityAndDefaultPacking() {
        FakeMemoryRepository repository = new FakeMemoryRepository();
        String text = "Verified behavior. " + "bounded detail ".repeat(90);
        MemoryItem active = item(MemoryScope.PROJECT, "AI_ORCHESTRATION", MemoryStatus.ACTIVE, text, 1.0);
        MemoryItem pending = item(MemoryScope.PROJECT, "AI_ORCHESTRATION", MemoryStatus.PENDING_REVIEW, text, 1.0);
        MemoryItem other = item(MemoryScope.PROJECT, "OTHER", MemoryStatus.ACTIVE, text, 1.0);
        repository.put(active, pending, other);
        FakeScoredMemoryVectorIndex vectors = new FakeScoredMemoryVectorIndex(repository);
        vectors.scoreBy(active, 0.9).scoreBy(pending, 0.99).scoreBy(other, 0.99);
        MemoryRetrievalService service = new MemoryRetrievalService(repository, vectors, properties(8));

        var compact = service.searchPreviews("behavior", "AI_ORCHESTRATION", null, 3);
        var detailed = service.searchPreviews("behavior", "AI_ORCHESTRATION", null, 3, 1024);
        assertThat(detailed).extracting(MemorySearchHit::memoryId).containsExactly(active.id());
        assertThat(detailed.getFirst().status()).isEqualTo("active");
        assertThat(compact.getFirst().excerpt()).isEqualTo("Verified behavior.");
        assertThat(detailed.getFirst().excerpt()).hasSize(1024).endsWith("...");
        assertThat(detailed.getFirst().tokenEstimate()).isEqualTo(MemoryTextPreview.estimateTokens(
                active.summary() + " " + detailed.getFirst().excerpt()));
        assertThat(service.searchPreviews("behavior", "AI_ORCHESTRATION", null, 3)).isEqualTo(compact);
        for (int invalid : new int[] {0, 127, 1025, Integer.MAX_VALUE}) {
            assertThatThrownBy(() -> service.searchPreviews("behavior", "AI_ORCHESTRATION", null, 3, invalid))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void retrievesOnlyEligibleStatusesAndProjectScope() {
        FakeMemoryRepository repository = new FakeMemoryRepository();
        MemoryItem global = item(MemoryScope.GLOBAL, null, MemoryStatus.ACTIVE,
                "Service names follow acme-svc-domain-service.", 1.0);
        MemoryItem project = item(MemoryScope.PROJECT, "AI_ORCHESTRATION", MemoryStatus.ACTIVE,
                "Project repositories extend AcmeBaseRepository.", 0.9);
        MemoryItem pending = item(MemoryScope.PROJECT, "AI_ORCHESTRATION", MemoryStatus.PENDING_REVIEW,
                "Pending rule must not inject.", 1.0);
        MemoryItem otherProject = item(MemoryScope.PROJECT, "OTHER", MemoryStatus.ACTIVE,
                "Other project rule must not inject.", 1.0);
        repository.put(global, project, pending, otherProject);
        FakeScoredMemoryVectorIndex vectorIndex = new FakeScoredMemoryVectorIndex(repository);
        vectorIndex.scoreBy(global, 0.7).scoreBy(project, 0.6).scoreBy(pending, 0.9).scoreBy(otherProject, 0.8);
        MemoryRetrievalService service = new MemoryRetrievalService(repository, vectorIndex, properties(2000));

        MemoryContextResponse response = service.retrieve("repository service naming", "AI_ORCHESTRATION");

        assertThat(response.injectedMemoryIds()).contains(global.id(), project.id());
        assertThat(response.injectedMemoryIds()).doesNotContain(pending.id(), otherProject.id());
        assertThat(response.injectedScopes()).containsEntry("global", 1).containsEntry("project", 1);
        assertThat(response.injectedTokenEstimate()).isLessThanOrEqualTo(2000);
        assertThat(response.items()).filteredOn(item -> item.memoryId().equals(project.id()))
                .extracting(MemoryContextItem::sourceRef)
                .containsExactly(project.sourceRef());
    }

    @Test
    void episodicMemoriesAreEligibleWhenStatusMatches() {
        FakeMemoryRepository repository = new FakeMemoryRepository();
        MemoryItem active = item(MemoryScope.EPISODIC, null, MemoryStatus.ACTIVE,
                "User prefers concise implementation plans.", 0.7);
        MemoryItem pending = item(MemoryScope.EPISODIC, null, MemoryStatus.PENDING_REVIEW,
                "Pending episodic must not inject.", 0.9);
        repository.put(active, pending);
        FakeScoredMemoryVectorIndex vectorIndex = new FakeScoredMemoryVectorIndex(repository);
        vectorIndex.scoreBy(active, 0.8).scoreBy(pending, 0.9);
        MemoryRetrievalService service = new MemoryRetrievalService(repository, vectorIndex, properties(2000));

        MemoryContextResponse response = service.retrieve("concise plans", "AI_ORCHESTRATION");

        assertThat(response.injectedMemoryIds()).containsExactly(active.id());
        assertThat(response.injectedScopes()).containsEntry("episodic", 1);
    }

    @Test
    void retrievesUserScopeOnlyForMatchingUserId() {
        FakeMemoryRepository repository = new FakeMemoryRepository();
        MemoryItem user = item(MemoryScope.USER, "user:alex", MemoryStatus.ACTIVE,
                "The user prefers terse implementation updates.", 1.0);
        MemoryItem otherUser = item(MemoryScope.USER, "user:ayse", MemoryStatus.ACTIVE,
                "Ayse prefers detailed summaries.", 1.0);
        repository.put(user, otherUser);
        FakeScoredMemoryVectorIndex vectorIndex = new FakeScoredMemoryVectorIndex(repository);
        vectorIndex.scoreBy(user, 0.7).scoreBy(otherUser, 0.8);
        MemoryRetrievalService service = new MemoryRetrievalService(repository, vectorIndex, properties(2000));

        MemoryContextResponse response = service.retrieve("implementation updates", "AI_ORCHESTRATION", "alex");

        assertThat(response.injectedMemoryIds()).contains(user.id());
        assertThat(response.injectedMemoryIds()).doesNotContain(otherUser.id());
        assertThat(response.injectedScopes()).containsEntry("user", 1);
    }

    @Test
    void userScopeExcludedWhenUserIdIsNull() {
        FakeMemoryRepository repository = new FakeMemoryRepository();
        MemoryItem user = item(MemoryScope.USER, "user:alex", MemoryStatus.ACTIVE,
                "User personal preference.", 1.0);
        repository.put(user);
        FakeScoredMemoryVectorIndex vectorIndex = new FakeScoredMemoryVectorIndex(repository);
        vectorIndex.scoreBy(user, 0.99);
        MemoryRetrievalService service = new MemoryRetrievalService(repository, vectorIndex, properties(2000));

        MemoryContextResponse response = service.retrieve("query", "AI_ORCHESTRATION");

        assertThat(response.injectedMemoryIds()).doesNotContain(user.id());
        assertThat(response.injectedScopes()).containsEntry("user", 0);
    }

    @Test
    void globalRequiresNullProjectKey() {
        FakeMemoryRepository repository = new FakeMemoryRepository();
        MemoryItem properGlobal = item(MemoryScope.GLOBAL, null, MemoryStatus.ACTIVE,
                "Real global rule.", 1.0);
        MemoryItem misclassifiedGlobal = item(MemoryScope.GLOBAL, "AI_ORCHESTRATION", MemoryStatus.ACTIVE,
                "Misclassified global rule with project key.", 1.0);
        repository.put(properGlobal, misclassifiedGlobal);
        FakeScoredMemoryVectorIndex vectorIndex = new FakeScoredMemoryVectorIndex(repository);
        vectorIndex.scoreBy(properGlobal, 0.5).scoreBy(misclassifiedGlobal, 0.6);
        MemoryRetrievalService service = new MemoryRetrievalService(repository, vectorIndex, properties(2000));

        MemoryContextResponse response = service.retrieve("rule", "AI_ORCHESTRATION");

        assertThat(response.injectedMemoryIds()).containsExactly(properGlobal.id());
    }

    @Test
    void flagsOldVerifiedMemoryAsStaleAndHonorsHardTokenCap() {
        FakeMemoryRepository repository = new FakeMemoryRepository();
        MemoryItem stale = item(MemoryScope.GLOBAL, null, MemoryStatus.ACTIVE,
                "Old verified rule should be marked stale.",
                1.0,
                Instant.parse("2024-01-01T00:00:00Z"));
        MemoryItem tooLarge = item(MemoryScope.GLOBAL, null, MemoryStatus.ACTIVE,
                "one two three four five six seven eight nine ten eleven twelve", 0.9);
        repository.put(stale, tooLarge);
        FakeScoredMemoryVectorIndex vectorIndex = new FakeScoredMemoryVectorIndex(repository);
        vectorIndex.scoreBy(stale, 0.7).scoreBy(tooLarge, 0.6);
        MemoryRetrievalService service = new MemoryRetrievalService(repository, vectorIndex, properties(8));

        MemoryContextResponse response = service.retrieve("rule", null);

        assertThat(response.injectedMemoryIds()).containsExactly(stale.id());
        assertThat(response.staleFlaggedCount()).isEqualTo(1);
        assertThat(response.injectedTokenEstimate()).isLessThanOrEqualTo(8);
    }

    @Test
    void searchPreviewsReturnLongMemoriesWithoutInjectionBudgetPacking() {
        FakeMemoryRepository repository = new FakeMemoryRepository();
        MemoryItem stale = item(MemoryScope.GLOBAL, null, MemoryStatus.ACTIVE,
                "Old verified rule should be marked stale.",
                1.0,
                Instant.parse("2024-01-01T00:00:00Z"));
        String longText = "A very long but relevant memory atom candidate. " + "detail ".repeat(120);
        MemoryItem tooLargeForInjection = item(MemoryScope.GLOBAL, null, MemoryStatus.ACTIVE, longText, 0.9);
        repository.put(stale, tooLargeForInjection);
        FakeScoredMemoryVectorIndex vectorIndex = new FakeScoredMemoryVectorIndex(repository);
        vectorIndex.scoreBy(stale, 0.7).scoreBy(tooLargeForInjection, 0.9);
        MemoryRetrievalService service = new MemoryRetrievalService(repository, vectorIndex, properties(8));

        MemoryContextResponse injected = service.retrieve("relevant memory", null);
        List<MemorySearchHit> previews = service.searchPreviews("relevant memory", null, null, 5);

        assertThat(injected.injectedMemoryIds()).doesNotContain(tooLargeForInjection.id());
        assertThat(previews).extracting(MemorySearchHit::memoryId).contains(tooLargeForInjection.id());
        MemorySearchHit hit = previews.stream()
                .filter(item -> item.memoryId().equals(tooLargeForInjection.id()))
                .findFirst()
                .orElseThrow();
        assertThat(hit.excerpt()).hasSizeLessThanOrEqualTo(MemoryAtomLimits.EXCERPT_MAX_CHARS);
        assertThat(hit.tokenEstimate()).isEqualTo(MemoryTextPreview.estimateTokens(hit.summary() + " " + hit.excerpt()));
        assertThat(hit.tokenEstimate()).isLessThan(MemoryTextPreview.estimateTokens(longText));
        assertThat(previews.stream().filter(value -> value.memoryId().equals(stale.id())).findFirst().orElseThrow())
                .satisfies(value -> {
                    assertThat(value.stale()).isTrue();
                    assertThat(value.status()).isEqualTo("active");
                });
    }

    @Test
    void contextBuilderRendersStructuredBlock() {
        MemoryItem memory = item(MemoryScope.GLOBAL, null, MemoryStatus.ACTIVE,
                "Service names follow acme-svc-domain-service.", 1.0);
        MemoryContextItem item = new MemoryContextItem(memory.id(), memory.vectorId(), "global:" + memory.id(),
                memory.scope(), memory.projectKey(), memory.memoryType(), memory.summary(), memory.text(),
                memory.confidence(), false, 5, 0.0, memory.lastVerifiedAt());
        MemoryContextResponse context = new MemoryContextResponse(List.of(item), List.of(memory.id()), 1, 5,
                Map.of("global", 1, "project", 0, "episodic", 0), 0, 0, 1);

        String block = new MemoryContextBuilder().build(context);

        assertThat(block).contains("=== Active rules from memory ===")
                .contains("[global:" + memory.id() + "]")
                .contains("acme-svc-domain-service")
                .contains("=== End rules ===");
    }

    @Test
    void injectionUsesCompactPromptTextWhileKeepingFullTextAvailable() {
        String longText = "Use ArchUnit to enforce hexagonal boundaries in every module. " + "detail ".repeat(120);
        MemoryContextItem item = new MemoryContextItem(UUID.randomUUID(), UUID.randomUUID(), "global:rules",
                MemoryScope.GLOBAL, null, MemoryType.RULE, "Architecture rule", longText,
                0.95, false, 5, 0.0, Instant.now());

        // Full text stays available for citation/audit; prompt text is compact and bounded.
        assertThat(item.text()).isEqualTo(longText);
        assertThat(item.promptText())
                .isNotBlank()
                .hasSizeLessThanOrEqualTo(MemoryAtomLimits.SUMMARY_MAX_CHARS + MemoryAtomLimits.EXCERPT_MAX_CHARS + 2);
        assertThat(item.promptText().length()).isLessThan(longText.length());

        MemoryContextResponse context = new MemoryContextResponse(List.of(item), List.of(item.memoryId()), 1,
                item.tokenEstimate(), Map.of("global", 1, "project", 0, "episodic", 0), 0, 0, 1);
        String block = new MemoryContextBuilder().build(context);

        // The builder renders compact prompt text, not the full memory text.
        assertThat(block).contains("[global:rules]").contains("Architecture rule");
        assertThat(block).doesNotContain(longText);
        assertThat(block.length()).isLessThan(longText.length());
    }

    @Test
    void packInjectsLongMemoryWhenCompactPromptTextFitsHardCap() {
        FakeMemoryRepository repository = new FakeMemoryRepository();
        String longText = "Rule one is important. " + "extra ".repeat(60);
        MemoryItem longMemory = item(MemoryScope.GLOBAL, null, MemoryStatus.ACTIVE, longText, 0.9);
        repository.put(longMemory);
        FakeScoredMemoryVectorIndex vectorIndex = new FakeScoredMemoryVectorIndex(repository);
        vectorIndex.scoreBy(longMemory, 0.9);
        // hardCap sits above the compact promptText estimate but below the full-text estimate:
        // old full-text budgeting would have dropped this memory; Phase 1 injects it compactly.
        int hardCap = 20;
        assertThat(MemoryTextPreview.estimateTokens(longText)).isGreaterThan(hardCap);
        MemoryRetrievalService service = new MemoryRetrievalService(repository, vectorIndex, properties(hardCap));

        MemoryContextResponse response = service.retrieve("rule", null);

        assertThat(response.injectedMemoryIds()).contains(longMemory.id());
        MemoryContextItem injected = response.items().stream()
                .filter(candidate -> candidate.memoryId().equals(longMemory.id()))
                .findFirst()
                .orElseThrow();
        assertThat(injected.text()).isEqualTo(longText);
        assertThat(injected.promptText().length()).isLessThan(longText.length());
        assertThat(injected.tokenEstimate())
                .isEqualTo(MemoryTextPreview.estimateTokens(injected.promptText()));
        assertThat(response.injectedTokenEstimate()).isEqualTo(injected.tokenEstimate());
        assertThat(response.injectedTokenEstimate()).isLessThanOrEqualTo(hardCap);
    }

    @Test
    void lowSimilarityMemoryIsGatedFromInjectionButStaysInSearch() {
        FakeMemoryRepository repository = new FakeMemoryRepository();
        MemoryItem related = item(MemoryScope.GLOBAL, null, MemoryStatus.ACTIVE,
                "Strongly related rule.", 0.6);
        MemoryItem weaklyRelated = item(MemoryScope.GLOBAL, null, MemoryStatus.ACTIVE,
                "High confidence but weakly related rule.", 1.0);
        repository.put(related, weaklyRelated);
        FakeScoredMemoryVectorIndex vectorIndex = new FakeScoredMemoryVectorIndex(repository);
        vectorIndex.scoreBy(related, 0.50).scoreBy(weaklyRelated, 0.10);
        // Keep explicit search permissive while automatic injection uses a stricter threshold.
        MemoryRankingProperties strictRanking = new MemoryRankingProperties(
                null, null, null, null, null, 0.35, 0.0);
        MemoryRetrievalService service =
                new MemoryRetrievalService(repository, vectorIndex, properties(2000), strictRanking);

        MemoryContextResponse injected = service.retrieve("rule", null);
        List<MemorySearchHit> previews = service.searchPreviews("rule", null, null, 5);

        // Injection gate keeps the related memory but drops the low-similarity one despite max confidence.
        assertThat(injected.injectedMemoryIds()).contains(related.id()).doesNotContain(weaklyRelated.id());
        assertThat(injected.relevanceFilteredCount()).isGreaterThanOrEqualTo(1);
        // This test configures memory.search permissively and still surfaces the low-similarity candidate.
        assertThat(previews).extracting(MemorySearchHit::memoryId).contains(weaklyRelated.id());
    }

    @Test
    void discoveryCostMetadataCannotOverrideRelevanceGate() {
        FakeMemoryRepository repository = new FakeMemoryRepository();
        MemoryItem base = item(MemoryScope.PROJECT, "AI_ORCHESTRATION", MemoryStatus.ACTIVE,
                "Expensive but irrelevant discovery.", 1.0);
        MemoryItem expensive = new MemoryItem(base.id(), base.vectorId(), base.scope(), base.projectKey(),
                MemoryType.DISCOVERY, base.summary(), base.text(), base.tags(), base.confidence(), base.status(),
                base.sourceType(), base.sourceRef(), base.owner(), Map.of("discoveryToolCalls", 5000,
                        "discoveryModelTokens", 2_000_000), base.createdAt(), base.updatedAt(), base.lastUsedAt(),
                base.lastVerifiedAt(), base.expiresAt());
        repository.put(expensive);
        FakeScoredMemoryVectorIndex vectorIndex = new FakeScoredMemoryVectorIndex(repository);
        vectorIndex.scoreBy(expensive, 0.05);
        MemoryRetrievalService service = new MemoryRetrievalService(repository, vectorIndex, properties(2000),
                MemoryRankingProperties.defaults());

        MemoryContextResponse injected = service.retrieve("unrelated task", "AI_ORCHESTRATION");

        assertThat(injected.injectedMemoryIds()).doesNotContain(expensive.id());
        assertThat(injected.relevanceFilteredCount()).isEqualTo(1);
    }

    @Test
    void promotedRuleOriginsAreBatchDeduplicatedFromInjectionButRemainExplicitlySearchable() {
        FakeMemoryRepository repository = new FakeMemoryRepository();
        MemoryItem promotedOrigin = item(MemoryScope.PROJECT, "AI_ORCHESTRATION", MemoryStatus.ACTIVE,
                "Approved controller rule origin.", 1.0);
        MemoryItem ordinary = item(MemoryScope.PROJECT, "AI_ORCHESTRATION", MemoryStatus.ACTIVE,
                "Ordinary project decision.", 1.0);
        repository.put(promotedOrigin, ordinary);
        FakeScoredMemoryVectorIndex vectorIndex = new FakeScoredMemoryVectorIndex(repository);
        vectorIndex.scoreBy(promotedOrigin, 0.9).scoreBy(ordinary, 0.8);
        int[] batchCalls = {0};
        RuleMemoryLinkLookup links = new RuleMemoryLinkLookup() {
            @Override
            public boolean hasLinkedDefinition(UUID memoryId) {
                throw new AssertionError("injection dedupe must not issue one lookup per memory");
            }

            @Override
            public Set<UUID> linkedMemoryIds(java.util.Collection<UUID> memoryIds) {
                batchCalls[0]++;
                assertThat(memoryIds).containsExactly(promotedOrigin.id(), ordinary.id());
                return Set.of(promotedOrigin.id());
            }
        };
        MemoryRetrievalService service = new MemoryRetrievalService(repository, vectorIndex, properties(2000),
                MemoryRankingProperties.defaults(),
                new RuleMemoryInjectionFilter(ruleProperties(true), links));

        MemoryContextResponse injected = service.retrieve("controller rule", "AI_ORCHESTRATION");
        List<MemorySearchHit> searched = service.searchPreviews(
                "controller rule", "AI_ORCHESTRATION", null, 5);

        assertThat(injected.injectedMemoryIds()).containsExactly(ordinary.id());
        assertThat(searched).extracting(MemorySearchHit::memoryId).contains(promotedOrigin.id(), ordinary.id());
        assertThat(batchCalls[0]).isEqualTo(1);
    }

    @Test
    void disablingRuleAuthorityRestoresPromotedOriginToLegacyAutomaticInjection() {
        FakeMemoryRepository repository = new FakeMemoryRepository();
        MemoryItem promotedOrigin = item(MemoryScope.PROJECT, "AI_ORCHESTRATION", MemoryStatus.ACTIVE,
                "Controller rule origin.", 1.0);
        repository.put(promotedOrigin);
        FakeScoredMemoryVectorIndex vectorIndex = new FakeScoredMemoryVectorIndex(repository);
        vectorIndex.scoreBy(promotedOrigin, 0.95);
        RuleMemoryLinkLookup links = ignored -> true;
        MemoryRetrievalService service = new MemoryRetrievalService(repository, vectorIndex, properties(2000),
                MemoryRankingProperties.defaults(),
                new RuleMemoryInjectionFilter(ruleProperties(false), links));

        assertThat(service.retrieve("controller rule", "AI_ORCHESTRATION").injectedMemoryIds())
                .containsExactly(promotedOrigin.id());
    }

    @Test
    void defaultRankingKeepsCalibratedLowScoreMemory() {
        FakeMemoryRepository repository = new FakeMemoryRepository();
        MemoryItem lowScore = item(MemoryScope.GLOBAL, null, MemoryStatus.ACTIVE,
                "Relevant memory with a realistic low local-embedding score.", 0.8);
        repository.put(lowScore);
        FakeScoredMemoryVectorIndex vectorIndex = new FakeScoredMemoryVectorIndex(repository);
        vectorIndex.scoreBy(lowScore, 0.12);
        MemoryRetrievalService service = new MemoryRetrievalService(repository, vectorIndex, properties(2000));

        MemoryContextResponse injected = service.retrieve("query", null);

        assertThat(injected.injectedMemoryIds()).contains(lowScore.id());
        assertThat(injected.relevanceFilteredCount()).isZero();
    }

    private static RulesProperties ruleProperties(boolean enabled) {
        return new RulesProperties(enabled, 20, 256, 4096, 200);
    }

    @Test
    void defaultRankingFiltersNearZeroNoise() {
        FakeMemoryRepository repository = new FakeMemoryRepository();
        MemoryItem noise = item(MemoryScope.GLOBAL, null, MemoryStatus.ACTIVE,
                "Unrelated near-zero similarity memory.", 0.8);
        repository.put(noise);
        FakeScoredMemoryVectorIndex vectorIndex = new FakeScoredMemoryVectorIndex(repository);
        vectorIndex.scoreBy(noise, 0.05);
        MemoryRetrievalService service = new MemoryRetrievalService(repository, vectorIndex, properties(2000),
                MemoryRankingProperties.defaults());

        MemoryContextResponse injected = service.retrieve("query", null);

        assertThat(injected.injectedMemoryIds()).doesNotContain(noise.id());
        assertThat(injected.relevanceFilteredCount()).isEqualTo(1);
    }

    @Test
    void automaticInjectionDisabledSkipsIndexButExplicitSearchStillWorks() {
        FakeMemoryRepository repository = new FakeMemoryRepository();
        MemoryItem memory = item(MemoryScope.GLOBAL, null, MemoryStatus.ACTIVE,
                "Explicitly requested project rule.", 0.8);
        repository.put(memory);
        FakeScoredMemoryVectorIndex vectorIndex = new FakeScoredMemoryVectorIndex(repository);
        vectorIndex.scoreBy(memory, 0.80);
        MemoryRetrievalService service = new MemoryRetrievalService(repository, vectorIndex, properties(2000));

        assertThat(service.retrieveForInjection("project rule", null).items()).isEmpty();
        assertThat(vectorIndex.searchCalls).isZero();

        assertThat(service.searchPreviews("project rule", null, null, 3))
                .extracting(MemorySearchHit::memoryId)
                .containsExactly(memory.id());
        assertThat(vectorIndex.searchCalls).isEqualTo(1);
    }

    private static MemoryItem item(MemoryScope scope, String projectKey, MemoryStatus status, String text,
            double confidence) {
        return item(scope, projectKey, status, text, confidence, Instant.parse("2026-05-20T00:00:00Z"));
    }

    private static MemoryItem item(MemoryScope scope, String projectKey, MemoryStatus status, String text,
            double confidence, Instant lastVerifiedAt) {
        UUID id = UUID.randomUUID();
        return new MemoryItem(id, UUID.nameUUIDFromBytes(id.toString().getBytes()), scope, projectKey,
                MemoryType.RULE, "Summary", text, List.of("test"), confidence, status, MemorySourceType.MANUAL,
                "test:" + id, "test", Map.of(), Instant.now(), Instant.now(), null, lastVerifiedAt, null);
    }

    private static AiOrchestrationProperties properties(int hardCap) {
        return new AiOrchestrationProperties(
                1200,
                160,
                5,
                "logs/test-memory-retrieval.jsonl",
                384,
                "qwen3:8b",
                new AiOrchestrationProperties.Generation(false, "local-qwen", true, 8000L),
                new AiOrchestrationProperties.Qdrant(true),
                new AiOrchestrationProperties.Ollama(false),
                new AiOrchestrationProperties.Acl("config/user-groups.yml", 300000L),
                new AiOrchestrationProperties.Reranker(true, "noop", 20, 2000L, 60, null),
                null,
                new AiOrchestrationProperties.Memory("memory_episodic_test_384", hardCap, 0.3,
                        "config/memory/global", ".ai_orch/memory", "AI_ORCHESTRATION", false,
                        false, hardCap, hardCap, hardCap, 5, 365),
                365);
    }

    private static final class FakeMemoryRepository implements MemoryRepository {
        private final Map<UUID, MemoryItem> items = new LinkedHashMap<>();

        void put(MemoryItem... values) {
            for (MemoryItem value : values) {
                items.put(value.id(), value);
            }
        }

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
            return items.values().stream().filter(item -> sourceRef.equals(item.sourceRef())).findFirst();
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
        public List<MemoryItem> listByStatuses(Set<MemoryStatus> statuses) {
            if (statuses == null || statuses.isEmpty()) {
                return List.of();
            }
            return items.values().stream()
                    .filter(item -> statuses.contains(item.status()))
                    .toList();
        }

        @Override
        public MemoryItem update(MemoryItem item) {
            items.put(item.id(), item);
            return item;
        }

        @Override
        public boolean updateStatus(UUID id, MemoryStatus status) {
            return false;
        }

        @Override
        public void insertEvent(MemoryEvent event) {
        }

        @Override
        public List<MemoryEvent> eventsForMemory(UUID memoryId) {
            return List.of();
        }

        @Override
        public void insertReviewQueue(ReviewQueueItem item) {
        }

        @Override
        public List<ReviewQueueItem> reviewQueueForMemory(UUID memoryId) {
            return List.of();
        }
    }

    private static final class FakeScoredMemoryVectorIndex implements MemoryVectorIndex {
        private final MemoryRepository repository;
        private final Map<UUID, Double> scores = new HashMap<>();
        private int searchCalls;

        FakeScoredMemoryVectorIndex(MemoryRepository repository) {
            this.repository = repository;
        }

        FakeScoredMemoryVectorIndex scoreBy(MemoryItem item, double score) {
            scores.put(item.id(), score);
            return this;
        }

        @Override
        public void upsert(MemoryItem item) {
        }

        @Override
        public void delete(MemoryItem item) {
        }

        @Override
        public List<ScoredMemoryRef> search(String query, int topK, MemoryEligibilityFilter filter) {
            searchCalls++;
            List<MemoryItem> indexed = repository.listByStatuses(filter.statuses());
            List<ScoredMemoryRef> matched = new ArrayList<>();
            for (MemoryItem item : indexed) {
                if (!filter.accepts(item)) {
                    continue;
                }
                double score = scores.getOrDefault(item.id(), 0.0);
                matched.add(new ScoredMemoryRef(item, score));
            }
            matched.sort((a, b) -> Double.compare(b.similarityScore(), a.similarityScore()));
            return matched.size() > topK ? matched.subList(0, topK) : matched;
        }
    }
}
