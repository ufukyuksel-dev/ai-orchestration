package com.mbworldwideapps.aiorchestration.modules.mcp.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.mbworldwideapps.aiorchestration.modules.agentlearning.AgentLearningModels;
import com.mbworldwideapps.aiorchestration.modules.agentlearning.AgentLearningModels.CaptureCandidate;
import com.mbworldwideapps.aiorchestration.modules.agentlearning.AgentLearningModels.CaptureLocator;
import com.mbworldwideapps.aiorchestration.modules.agentlearning.AgentLearningModels.CompactLearnReceipt;
import com.mbworldwideapps.aiorchestration.modules.rules.InstructionReadService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class AgentSessionMcpToolTest {

    private final ScannerMcpTool scanner = mock(ScannerMcpTool.class);
    private final InstructionReadService rules = mock(InstructionReadService.class);
    private final MemoryMcpTool memory = mock(MemoryMcpTool.class);
    private final AgentLearningMcpTool learning = mock(AgentLearningMcpTool.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<AgentLearningMcpTool> learningProvider = mock(ObjectProvider.class);
    private final McpAuditLogger audit = mock(McpAuditLogger.class);
    private final AgentSessionMcpTool tool = new AgentSessionMcpTool(scanner, rules, memory, learningProvider, audit);
    private final UUID binding = UUID.randomUUID();

    @AfterEach
    void clear() {
        McpClientContextHolder.clear();
    }

    @Test
    void bootstrapResolvesAndLoadsEffectiveRulesInOneCall() {
        McpClientContextHolder.set(context(List.of("scanner.scan", "rules.read", "memory.write")));
        resolves();
        var instruction = new InstructionReadService.Instruction(UUID.randomUUID(), 2, "PROJECT", true,
                "Use constructor injection.", null, List.of(), "alex", "turn-1", "hash");
        when(rules.readPage("P", null)).thenReturn(new InstructionReadService.Page(
                new InstructionReadService.Result("P", "effective", 7, 3, true, List.of(instruction)), 0, 1, null));

        var response = tool.bootstrap("/repo", null);

        assertThat(response.projectKey()).isEqualTo("P");
        assertThat(response.rulesLoaded()).isTrue();
        assertThat(response.rulesError()).isNull();
        assertThat(response.nextCursor()).isNull();
        assertThat(response.rules()).containsExactly(
                new AgentSessionMcpTool.Rule("PROJECT", "Use constructor injection.", null, List.of()));
        assertThat(response.rulesEtag()).hasSize(16);
        verify(audit).log(any(), eq("session.bootstrap"), anyString(), eq(1), anyLong(), eq("success"), anyMap(),
                isNull());
    }

    @Test
    void bootstrapPagesLargeRuleSetsAndOnlyTheLastPageCountsAsLoaded() {
        McpClientContextHolder.set(context(List.of("scanner.scan", "rules.read")));
        resolves();
        var first = new InstructionReadService.Instruction(UUID.randomUUID(), 1, "GLOBAL_STRICT", true,
                "Rule one.", null, List.of(), "alex", "t", "h");
        var last = new InstructionReadService.Instruction(UUID.randomUUID(), 1, "PROJECT", true,
                "Rule two.", null, List.of(), "alex", "t", "h");
        when(rules.readPage("P", null)).thenReturn(new InstructionReadService.Page(
                new InstructionReadService.Result("P", "effective", 1, 1, false, List.of(first)), 0, 2, "c1"));
        when(rules.readPage("P", "c1")).thenReturn(new InstructionReadService.Page(
                new InstructionReadService.Result("P", "effective", 1, 1, true, List.of(last)), 1, 2, null));

        var page1 = tool.bootstrap("/repo", null);
        var page2 = tool.bootstrap("/repo", "c1");

        assertThat(page1.rulesLoaded()).isFalse();
        assertThat(page1.nextCursor()).isEqualTo("c1");
        assertThat(page1.rulesEtag()).isNull();
        assertThat(page2.rulesLoaded()).isTrue();
        assertThat(page2.rules()).extracting(AgentSessionMcpTool.Rule::statement).containsExactly("Rule two.");
    }

    @Test
    void bootstrapNeverReportsRulesAsLoadedOnCapacityOverflowOrMissingScope() {
        McpClientContextHolder.set(context(List.of("scanner.scan", "rules.read")));
        resolves();
        when(rules.readPage("P", null)).thenThrow(
                new InstructionReadService.CapacityExceeded("count", List.of(), -1));

        var response = tool.bootstrap("/repo", null);

        assertThat(response.projectKey()).isEqualTo("P");
        assertThat(response.rulesLoaded()).isFalse();
        assertThat(response.rulesError()).startsWith("CAPACITY_EXCEEDED");
        assertThat(response.rules()).isEmpty();

        McpClientContextHolder.set(context(List.of("scanner.scan")));
        var denied = tool.bootstrap("/repo", null);
        assertThat(denied.rulesLoaded()).isFalse();
        assertThat(denied.rulesError()).isEqualTo("RULES_SCOPE_DENIED");
    }

    @Test
    void pathBoundRulesComeInlineWhenSmallAndAsAnIndexWhenLarge(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) {
        McpClientContextHolder.set(context(List.of("scanner.scan", "rules.read")));
        resolves();
        var project = new InstructionReadService.Instruction(UUID.randomUUID(), 1, "PROJECT", true,
                "Use constructor injection.", null, List.of(), "alex", "t", "h");
        var bound = new InstructionReadService.Instruction(UUID.randomUUID(), 1, "MODULE", false,
                null, null, List.of("src/Owner.java"), "alex", "t", "h");
        when(rules.readPage("P", null)).thenReturn(new InstructionReadService.Page(
                new InstructionReadService.Result("P", "effective", 4, 2, true, List.of(project, bound)), 0, 1, null));
        var loaded = new InstructionReadService.Instruction(bound.ruleId(), 1, "MODULE", true,
                "Never log personal data here.", null, List.of("src/Owner.java"), "alex", "t", "h");
        when(rules.read("P", "module", List.of("src/Owner.java"))).thenReturn(
                new InstructionReadService.Result("P", "module", 4, 2, true, List.of(loaded)));
        when(rules.boundPaths("P")).thenReturn(List.of("src/Owner.java"));

        var small = tool.bootstrap("/repo", null);
        assertThat(small.rules()).extracting(AgentSessionMcpTool.Rule::scope).containsExactly("PROJECT");
        assertThat(small.pathRules()).containsExactly(
                new AgentSessionMcpTool.PathRule(List.of("src/Owner.java"), "Never log personal data here."));
        assertThat(small.pathIndex()).isEmpty();
        assertThat(tool.bootstrap("/repo", null, null, null, small.rulesEtag(), null).pathRules()).isEmpty();

        var big = new InstructionReadService.Instruction(bound.ruleId(), 1, "MODULE", true,
                "x".repeat(AgentSessionMcpTool.MAX_INLINE_PATH_RULES), null, List.of("src/Owner.java"), "alex", "t",
                "h");
        when(rules.read("P", "module", List.of("src/Owner.java"))).thenReturn(
                new InstructionReadService.Result("P", "module", 4, 2, true, List.of(big)));
        var large = tool.bootstrap("/repo", null);
        assertThat(large.pathRules()).isEmpty();
        assertThat(large.pathIndex()).containsExactly("src/Owner.java");

        AgentPrefsStore prefs = new AgentPrefsStore(dir.resolve("prefs.json").toString());
        tool.setSessionSupport(null, null, emptyProvider(), prefs, 0.0);
        var declined = tool.bootstrap("/repo", null, false, null, null, null);
        assertThat(declined.pathRules()).isEmpty();
        assertThat(declined.pathIndex()).isEmpty();
        assertThat(declined.rules()).isEmpty();
    }

    @Test
    void pagedStartupStillHandsOverEveryBoundPathOnTheLastPage() {
        McpClientContextHolder.set(context(List.of("scanner.scan", "rules.read")));
        resolves();
        var first = new InstructionReadService.Instruction(UUID.randomUUID(), 1, "MODULE", false, null, null,
                List.of("a/One.java"), "alex", "t", "h");
        var last = new InstructionReadService.Instruction(UUID.randomUUID(), 1, "MODULE", false, null, null,
                List.of("b"), "alex", "t", "h");
        when(rules.readPage("P", null)).thenReturn(new InstructionReadService.Page(
                new InstructionReadService.Result("P", "effective", 4, 2, true, List.of(first)), 0, 2, "c1"));
        when(rules.readPage("P", "c1")).thenReturn(new InstructionReadService.Page(
                new InstructionReadService.Result("P", "effective", 4, 2, true, List.of(last)), 1, 2, null));
        when(rules.boundPaths("P")).thenReturn(List.of("a/One.java", "b"));
        when(rules.read("P", "module", List.of("a/One.java", "b"))).thenReturn(new InstructionReadService.Result(
                "P", "module", 4, 2, true, List.of(
                        new InstructionReadService.Instruction(first.ruleId(), 1, "MODULE", true, "x".repeat(900),
                                null, List.of("a/One.java"), "alex", "t", "h"),
                        new InstructionReadService.Instruction(last.ruleId(), 1, "MODULE", true, "y".repeat(900),
                                null, List.of("b"), "alex", "t", "h"))));

        var page1 = tool.bootstrap("/repo", null);
        assertThat(page1.nextCursor()).isEqualTo("c1");
        assertThat(page1.pathIndex()).isEmpty();
        var page2 = tool.bootstrap("/repo", null, null, null, null, "c1");
        assertThat(page2.rulesLoaded()).isTrue();
        assertThat(page2.pathIndex()).containsExactly("a/One.java", "b");
    }

    @Test
    void unchangedRulesAreNotResentToAnAgentThatAlreadyHoldsThem() {
        McpClientContextHolder.set(context(List.of("scanner.scan", "rules.read")));
        resolves();
        var instruction = new InstructionReadService.Instruction(UUID.randomUUID(), 1, "GLOBAL_STRICT", true,
                "Never log secrets.", null, List.of(), "alex", "t", "h");
        when(rules.readPage("P", null)).thenReturn(new InstructionReadService.Page(
                new InstructionReadService.Result("P", "effective", 4, 2, true, List.of(instruction)), 0, 1, null));

        var first = tool.bootstrap("/repo", null);
        var again = tool.bootstrap("/repo", null, null, null, first.rulesEtag(), null);
        var stale = tool.bootstrap("/repo", null, null, null, "0000000000000000", null);

        assertThat(again.rulesUnchanged()).isTrue();
        assertThat(again.rulesLoaded()).isTrue();
        assertThat(again.rules()).isEmpty();
        assertThat(stale.rulesUnchanged()).isNull();
        assertThat(stale.rules()).hasSize(1);
    }

    @Test
    void asksOnceWhetherToLoadRulesAndRemembersAlways(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) {
        McpClientContextHolder.set(context(List.of("scanner.scan", "rules.read")));
        resolves();
        var instruction = new InstructionReadService.Instruction(UUID.randomUUID(), 1, "GLOBAL_STRICT", true,
                "Never log secrets.", null, List.of(), "alex", "t", "h");
        when(rules.readPage("P", null)).thenReturn(new InstructionReadService.Page(
                new InstructionReadService.Result("P", "effective", 4, 2, true, List.of(instruction)), 0, 1, null));
        AgentPrefsStore prefs = new AgentPrefsStore(dir.resolve("prefs.json").toString());
        tool.setSessionSupport(null, null, emptyProvider(), prefs, 0.5);

        var undecided = tool.bootstrap("/repo", null);
        assertThat(undecided.askUser()).contains("always");
        assertThat(undecided.rulesLoaded()).isFalse();
        assertThat(undecided.rules()).isEmpty();

        var answered = tool.bootstrap("/repo", null, true, true, null, null);
        assertThat(answered.rulesLoaded()).isTrue();
        assertThat(prefs.rulesAutoload()).isEqualTo("always");

        var nextSession = tool.bootstrap("/repo", null);
        assertThat(nextSession.askUser()).isNull();
        assertThat(nextSession.rules()).hasSize(1);

        tool.bootstrap("/repo", null, false, true, null, null);
        var declined = tool.bootstrap("/repo", null);
        assertThat(declined.askUser()).isNull();
        assertThat(declined.rulesError()).isEqualTo("RULES_SKIPPED_BY_USER");
    }

    @Test
    void cardsAreTheBestMatchesOnlyAndComeBestFirst() {
        McpClientContextHolder.set(context(List.of("scanner.scan")));
        resolves();
        var retrieval = mock(com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryRetrievalService.class);
        var close = card("close", 0.57);
        var best = card("best", 0.60);
        var far = card("far", 0.45);
        when(retrieval.retrieveForInjection("add a field", "P", null)).thenReturn(
                new com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryContextResponse(
                        List.of(far, close, best), List.of(), 3, 30, java.util.Map.of(), 0, 0, 0L));
        tool.setSessionSupport(retrieval, null, emptyProvider(), null, 0.0);

        var response = tool.bootstrap("/repo", "add a field", null, null, null, null);

        assertThat(response.memory()).extracting(AgentSessionMcpTool.Card::summary).containsExactly("best", "close");
    }

    @Test
    void theCardOnHowToVerifyAChangeComesAfterTheTasksOwnMatches() {
        McpClientContextHolder.set(context(List.of("scanner.scan")));
        resolves();
        var retrieval = mock(com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryRetrievalService.class);
        var feature = card("csv export of owners", 0.60);
        var verify = card("run the tests with JAVA_HOME set to JDK 21", 0.58);
        var memories = mock(com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryRepository.class);
        when(memories.findById(verify.memoryId())).thenReturn(java.util.Optional.of(
                storedCard(verify, List.of("learned", "procedure"))));
        when(memories.findById(feature.memoryId())).thenReturn(java.util.Optional.of(
                storedCard(feature, List.of("discovery", "change_point"))));
        when(retrieval.retrieveForInjection("add a vets csv export", "P", null)).thenReturn(
                new com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryContextResponse(
                        List.of(feature), List.of(), 1, 30, java.util.Map.of(), 0, 0, 0L));
        when(retrieval.retrieveForInjection(AgentSessionMcpTool.VERIFY_QUERY, "P", null)).thenReturn(
                new com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryContextResponse(
                        List.of(verify, feature), List.of(), 2, 30, java.util.Map.of(), 0, 0, 0L));
        tool.setSessionSupport(retrieval, memories, emptyProvider(), null, 0.0);

        var response = tool.bootstrap("/repo", "add a vets csv export", null, null, null, null);

        assertThat(response.memory()).extracting(AgentSessionMcpTool.Card::summary)
                .containsExactly("csv export of owners", "run the tests with JAVA_HOME set to JDK 21");
    }

    @Test
    void aStaleCardNamesTheMissingCodeRefs() {
        McpClientContextHolder.set(context(List.of("scanner.scan")));
        resolves();
        var retrieval = mock(com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryRetrievalService.class);
        var fresh = card("fresh", 0.60);
        UUID id = UUID.randomUUID();
        var stale = new com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryContextItem(id, id, "c",
                com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryScope.PROJECT, "P",
                com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryType.DECISION, "stale", "stale text",
                null, 0.9, true, 10, 0.60, java.time.Instant.now());
        when(retrieval.retrieveForInjection("add a field", "P", null)).thenReturn(
                new com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryContextResponse(
                        List.of(fresh, stale), List.of(), 2, 30, java.util.Map.of(), 0, 0, 0L));
        var lifecycle = mock(com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryLifecycleService.class);
        when(lifecycle.missingRefs(id)).thenReturn(List.of("p.Gone#run"));
        tool.setSessionSupport(retrieval, null, emptyProvider(), null, 0.0);
        tool.setLifecycle(lifecycle);

        var response = tool.bootstrap("/repo", "add a field", null, null, null, null);

        assertThat(response.memory()).filteredOn(c -> c.summary().equals("stale")).singleElement()
                .satisfies(c -> { assertThat(c.stale()).isTrue(); assertThat(c.missingRefs()).containsExactly("p.Gone#run"); });
        assertThat(response.memory()).filteredOn(c -> c.summary().equals("fresh")).singleElement()
                .satisfies(c -> assertThat(c.missingRefs()).isEmpty());
    }

    @Test
    void unrelatedTaskDoesNotReceiveStrongNonProcedureVerificationMatches() {
        McpClientContextHolder.set(context(List.of("scanner.scan")));
        resolves();
        var retrieval = mock(com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryRetrievalService.class);
        var unrelated = card("csv implementation details", 0.90);
        var memories = mock(com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryRepository.class);
        when(memories.findById(unrelated.memoryId())).thenReturn(java.util.Optional.of(
                storedCard(unrelated, List.of("discovery", "behavior"))));
        when(retrieval.retrieveForInjection("rename a stylesheet", "P", null)).thenReturn(
                com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryContextResponse.empty());
        when(retrieval.retrieveForInjection(AgentSessionMcpTool.VERIFY_QUERY, "P", null)).thenReturn(
                contextCards(List.of(unrelated)));
        tool.setSessionSupport(retrieval, memories, emptyProvider(), null, 0.45);

        assertThat(tool.bootstrap("/repo", "rename a stylesheet", null, null, null, null).memory()).isEmpty();
    }

    @Test
    void procedureFillerIsFilteredBeforeTheRelativeBand() {
        McpClientContextHolder.set(context(List.of("scanner.scan")));
        resolves();
        var retrieval = mock(com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryRetrievalService.class);
        var unrelated = card("csv implementation details", 0.90);
        var procedure = card("run the unit tests with JAVA_HOME set", 0.65);
        var memories = mock(com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryRepository.class);
        when(memories.findById(unrelated.memoryId())).thenReturn(java.util.Optional.of(
                storedCard(unrelated, List.of("discovery", "behavior"))));
        when(memories.findById(procedure.memoryId())).thenReturn(java.util.Optional.of(
                storedCard(procedure, List.of("learned", "procedure"))));
        when(retrieval.retrieveForInjection("rename a stylesheet", "P", null)).thenReturn(
                com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryContextResponse.empty());
        when(retrieval.retrieveForInjection(AgentSessionMcpTool.VERIFY_QUERY, "P", null)).thenReturn(
                contextCards(List.of(unrelated, procedure)));
        tool.setSessionSupport(retrieval, memories, emptyProvider(), null, 0.55);

        assertThat(tool.bootstrap("/repo", "rename a stylesheet", null, null, null, null).memory())
                .extracting(AgentSessionMcpTool.Card::summary).containsExactly(procedure.summary());
    }

    @Test
    void absoluteFloorRejectsEvenTheBestTaskMatch() {
        McpClientContextHolder.set(context(List.of("scanner.scan")));
        resolves();
        var retrieval = mock(com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryRetrievalService.class);
        when(retrieval.retrieveForInjection("unrelated task", "P", null)).thenReturn(
                contextCards(List.of(card("weak match", 0.60))));
        when(retrieval.retrieveForInjection(AgentSessionMcpTool.VERIFY_QUERY, "P", null)).thenReturn(
                com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryContextResponse.empty());
        tool.setSessionSupport(retrieval, null, emptyProvider(), null, 0.65);

        assertThat(tool.bootstrap("/repo", "unrelated task", null, null, null, null).memory()).isEmpty();
    }

    @Test
    void stepCardsAskRetrievalForTheStepsKindsOnlyAndSkipCardsAlreadyShown() {
        var retrieval = mock(com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryRetrievalService.class);
        var changePoint = card("controller pattern", 0.92);
        var procedure = card("run the tests with the boot 4 imports", 0.70);
        var shown = card("formatter before tests", 0.80);
        var stored = java.util.Map.of(
                changePoint, storedCard(changePoint, List.of("discovery", "change_point")),
                procedure, storedCard(procedure, List.of(), java.util.Map.of("discoveryKind", "procedure")),
                shown, storedCard(shown, List.of("discovery", "procedure")));
        stepAnswers(retrieval, "./mvnw test", List.of(changePoint, shown, procedure), stored);
        when(retrieval.injectionEnabled()).thenReturn(true);
        tool.setSessionSupport(retrieval, null, emptyProvider(), null, 0.65);

        var step = tool.stepCards("./mvnw test", "P", Set.of("procedure", "test_address"),
                Set.of(shown.memoryId().toString()));
        // the change_point (0.92) is not even a candidate: it would have set the band at 0.82 and pushed 0.70 out;
        // the card shown before (0.80) is left out before packing, so it cannot push 0.70 out either
        assertThat(step.outcome()).isEqualTo("found");
        assertThat(step.cards()).extracting(AgentSessionMcpTool.Card::summary).containsExactly(procedure.summary());
    }

    @Test
    void aStepLookupIsEmptyOnlyWhenRetrievalFoundNoMatch() {
        var retrieval = mock(com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryRetrievalService.class);
        var shown = card("formatter before tests", 0.80);
        when(retrieval.retrieveForStep(eq("./mvnw test"), eq("P"), any(), any(), anyDouble())).thenReturn(
                new com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryRetrievalService.StepRetrieval(
                        contextCards(List.of()), true));
        when(retrieval.retrieveForStep(eq("go test ./..."), eq("P"), any(), any(), anyDouble())).thenReturn(
                new com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryRetrievalService.StepRetrieval(
                        contextCards(List.of()), false));
        when(retrieval.retrieveForStep(eq("broken"), eq("P"), any(), any(), anyDouble()))
                .thenThrow(new IllegalStateException("index down"));
        when(retrieval.injectionEnabled()).thenReturn(true);
        tool.setSessionSupport(retrieval, null, emptyProvider(), null, 0.65);

        // matches that were all shown before or did not fit the budget: found, no cards
        var allShown = tool.stepCards("./mvnw test", "P", Set.of("procedure"), Set.of(shown.memoryId().toString()));
        assertThat(allShown.outcome()).isEqualTo("found");
        assertThat(allShown.cards()).isEmpty();
        assertThat(tool.stepCards("go test ./...", "P", Set.of("procedure"), Set.of()).outcome()).isEqualTo("empty");
        assertThatThrownBy(() -> tool.stepCards("broken", "P", Set.of("procedure"), Set.of()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aStepLookupWithInjectionOffIsSkippedNotEmpty() {
        var retrieval = mock(com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryRetrievalService.class);
        when(retrieval.injectionEnabled()).thenReturn(false);
        tool.setSessionSupport(retrieval, null, emptyProvider(), null, 0.65);

        // memory was not searched, so nothing may conclude that it has nothing (no search gets closed)
        assertThat(tool.stepCards("./mvnw test", "P", Set.of("procedure"), Set.of()).outcome()).isEqualTo("skipped");
        verify(retrieval, org.mockito.Mockito.never()).retrieveForStep(any(), any(), any(), any(), anyDouble());
    }

    @Test
    void aSymbolLocatorShowsWhereItIsNowAndAMissingMethodShowsOnlyItsClassLabelledAsAClass(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path root) throws Exception {
        java.nio.file.Files.createDirectories(root.resolve("app"));
        byte[] source = "class User:\n    def to_dict(self): pass\nclass Post:\n    pass\n".getBytes();
        java.nio.file.Files.write(root.resolve("app/models.py"), source);
        String hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(source));
        var baseline = mock(com.mbworldwideapps.aiorchestration.modules.scanner.CodeBaselineRepository.class);
        java.util.UUID fileId = java.util.UUID.randomUUID();
        java.util.UUID runId = java.util.UUID.randomUUID();
        var file = new com.mbworldwideapps.aiorchestration.modules.scanner.CodeFileRecord(fileId, "P", "app/models.py",
                hash, "python", runId, java.util.Map.of());
        when(baseline.findFileById("P", fileId)).thenReturn(java.util.Optional.of(file));
        when(baseline.findRun("P", runId)).thenReturn(java.util.Optional.of(
                new com.mbworldwideapps.aiorchestration.modules.scanner.CodeScanRunRecord(runId, "P", root.toString(),
                        "completed", java.time.Instant.now(), java.time.Instant.now(), java.util.Map.of())));
        when(baseline.findSymbolsByRef(eq("P"), eq("app.models.User#to_dict"), anyInt())).thenReturn(List.of(
                symbol(fileId, "method", "app.models.User#to_dict", 231, 250)));
        when(baseline.findSymbolsByRef(eq("P"), eq("app.models.Post#to_dict"), anyInt())).thenReturn(List.of());
        when(baseline.findSymbolsByRef(eq("P"), eq("app.models.Post"), anyInt())).thenReturn(List.of(
                symbol(fileId, "class", "app.models.Post", 287, 300)));
        tool.setCodeBaseline(baseline);

        assertThat(tool.currentLocation("P", "app.models.User#to_dict", "app/models.py"))
                .isEqualTo("app/models.py:231-250 app.models.User#to_dict (method)");
        // the follow-up's code has no Post#to_dict: never shown as present, only its class, labelled as a class
        assertThat(tool.currentLocation("P", "app.models.Post#to_dict", "app/models.py"))
                .isEqualTo("app/models.py:287-300 app.models.Post (class; to_dict is not in the current code)");
        // another file than the declared one: not this symbol
        assertThat(tool.currentLocation("P", "app.models.User#to_dict", "app/other.py")).isNull();
        // the file changed since the scan: the line numbers are not current, nothing is said
        java.nio.file.Files.writeString(root.resolve("app/models.py"), "# edited\n" + new String(source));
        assertThat(tool.currentLocation("P", "app.models.User#to_dict", "app/models.py")).isNull();
        assertThat(tool.currentLocation("P", "app.models.Post#to_dict", "app/models.py")).isNull();
    }

    @Test
    void aCardBringsTheCurrentLinesOfItsSymbolAndTheOutlineOfItsFileOnlyWhileTheFileIsTheScannedOne(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path root) throws Exception {
        McpClientContextHolder.set(context(List.of("scanner.scan")));
        resolves();
        java.nio.file.Files.createDirectories(root.resolve("app/api"));
        byte[] models = "class User:\n    def to_dict(self):\n        return {'id': self.id}\nclass Post:\n    pass\n"
                .getBytes();
        byte[] init = "from flask import Blueprint\nbp = Blueprint('api', __name__)\ndef register():\n    pass\n".getBytes();
        java.nio.file.Files.write(root.resolve("app/models.py"), models);
        java.nio.file.Files.write(root.resolve("app/api/__init__.py"), init);
        var baseline = mock(com.mbworldwideapps.aiorchestration.modules.scanner.CodeBaselineRepository.class);
        java.util.UUID runId = java.util.UUID.randomUUID();
        when(baseline.findRun("P", runId)).thenReturn(java.util.Optional.of(
                new com.mbworldwideapps.aiorchestration.modules.scanner.CodeScanRunRecord(runId, "P", root.toString(),
                        "completed", java.time.Instant.now(), java.time.Instant.now(), java.util.Map.of())));
        java.util.UUID modelsId = java.util.UUID.randomUUID();
        java.util.UUID initId = java.util.UUID.randomUUID();
        var modelsFile = new com.mbworldwideapps.aiorchestration.modules.scanner.CodeFileRecord(modelsId, "P",
                "app/models.py", sha256(models), "python", runId, java.util.Map.of());
        var initFile = new com.mbworldwideapps.aiorchestration.modules.scanner.CodeFileRecord(initId, "P",
                "app/api/__init__.py", sha256(init), "python", runId, java.util.Map.of());
        when(baseline.findFileById("P", modelsId)).thenReturn(java.util.Optional.of(modelsFile));
        when(baseline.findFileByPath("P", "app/api/__init__.py")).thenReturn(java.util.Optional.of(initFile));
        when(baseline.findSymbolsByRef(eq("P"), eq("app.models.User#to_dict"), anyInt())).thenReturn(List.of(
                symbol(modelsId, "method", "app.models.User#to_dict", 2, 3)));
        // the scanner's real Python forms: a top-level function is `module#name` (kind function), a method
        // `module.Class#name` (kind method)
        when(baseline.findSymbolsForFile(eq("P"), eq(initId), anyInt())).thenReturn(List.of(
                symbol(initId, "class", "app.api.Blueprint2", 2, 2), symbol(initId, "function", "app.api#register", 3, 4),
                symbol(initId, "method", "app.api.Blueprint2#m", 9, 9),
                symbol(initId, "property", "app.api.Blueprint2#p", 10, 10)));
        tool.setCodeBaseline(baseline);
        var retrieval = mock(com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryRetrievalService.class);
        var recipe = card("add an API route", 0.90);
        var memories = mock(com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryRepository.class);
        var locators = com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryCodeLocatorMetadata.replace(
                java.util.Map.of(), List.of(
                        new com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryCodeLocator(
                                com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryCodeLocatorKind.SYMBOL,
                                "app.models.User#to_dict", null, null, "app/models.py", "supporting"),
                        new com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryCodeLocator(
                                com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryCodeLocatorKind.FILE,
                                "app/api/__init__.py", null, null, null, "primary_change_point")),
                com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryScope.PROJECT, "P");
        when(memories.findById(recipe.memoryId())).thenReturn(java.util.Optional.of(
                storedCard(recipe, List.of("learned", "procedure"), locators)));
        when(retrieval.retrieveForInjection(any(), eq("P"), isNull())).thenReturn(contextCards(List.of(recipe)));
        tool.setSessionSupport(retrieval, memories, emptyProvider(), null, 0.65);

        var card = tool.bootstrap("/repo", "add a posts endpoint", null, null, null, null).memory().get(0);

        assertThat(card.files()).contains("app/models.py:2-3 app.models.User#to_dict (method)", "app/api/__init__.py");
        assertThat(card.code()).isEqualTo("""
                Current code, app/models.py:2-3 app.models.User#to_dict (method):
                2│     def to_dict(self):
                3│         return {'id': self.id}
                Current outline, app/api/__init__.py:
                  2-2 class Blueprint2
                  3-4 function register""");
        // a changed file: neither its lines nor its outline are shown as current
        java.nio.file.Files.writeString(root.resolve("app/api/__init__.py"), "# edited\n" + new String(init));
        java.nio.file.Files.writeString(root.resolve("app/models.py"), "# edited\n" + new String(models));
        assertThat(tool.bootstrap("/repo", "add a posts endpoint", null, null, null, null).memory().get(0).code())
                .isNull();
    }

    @Test
    void aRefWrittenWithPartOfItsModulePathResolvesOnlyToTheOneMatchingSymbolOfItsFile(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path root) throws Exception {
        java.nio.file.Files.createDirectories(root.resolve("app"));
        byte[] source = "class Post:\n    def to_dict(self):\n        return {}\n".getBytes();
        java.nio.file.Files.write(root.resolve("app/models.py"), source);
        var baseline = mock(com.mbworldwideapps.aiorchestration.modules.scanner.CodeBaselineRepository.class);
        java.util.UUID fileId = java.util.UUID.randomUUID();
        java.util.UUID runId = java.util.UUID.randomUUID();
        var file = new com.mbworldwideapps.aiorchestration.modules.scanner.CodeFileRecord(fileId, "P", "app/models.py",
                sha256(source), "python", runId, java.util.Map.of());
        when(baseline.findFileById("P", fileId)).thenReturn(java.util.Optional.of(file));
        when(baseline.findFileByPath("P", "app/models.py")).thenReturn(java.util.Optional.of(file));
        when(baseline.findRun("P", runId)).thenReturn(java.util.Optional.of(
                new com.mbworldwideapps.aiorchestration.modules.scanner.CodeScanRunRecord(runId, "P", root.toString(),
                        "completed", java.time.Instant.now(), java.time.Instant.now(), java.util.Map.of())));
        when(baseline.findSymbolsByRef(eq("P"), any(), anyInt())).thenReturn(List.of());  // no exact name
        var toDict = symbol(fileId, "method", "app.models.Post#to_dict", 2, 3);
        when(baseline.findSymbolsForFile(eq("P"), eq(fileId), anyInt())).thenReturn(List.of(
                symbol(fileId, "class", "app.models.Post", 1, 3), toDict));
        tool.setCodeBaseline(baseline);

        assertThat(tool.currentLocation("P", "models.Post#to_dict", "app/models.py"))
                .isEqualTo("app/models.py:2-3 models.Post#to_dict (method)");
        assertThat(tool.currentLocation("P", "Post#to_dict", "app/models.py"))
                .isEqualTo("app/models.py:2-3 Post#to_dict (method)");
        // a name that only ends like it ("xmodels.Post#to_dict") or another file: nothing
        assertThat(tool.currentLocation("P", "dels.Post#to_dict", "app/models.py")).isNull();
        when(baseline.findFileByPath("P", "app/other.py")).thenReturn(java.util.Optional.empty());
        assertThat(tool.currentLocation("P", "models.Post#to_dict", "app/other.py")).isNull();
        // two symbols of the file end with the ref: ambiguous, nothing
        when(baseline.findSymbolsForFile(eq("P"), eq(fileId), anyInt())).thenReturn(List.of(toDict,
                symbol(fileId, "method", "app.legacy.models.Post#to_dict", 5, 6)));
        assertThat(tool.currentLocation("P", "models.Post#to_dict", "app/models.py")).isNull();
    }

    @Test
    void aMemberRefWithItsParameterListFindsTheScannedMember() throws Exception {
        java.nio.file.Path root = java.nio.file.Files.createTempDirectory("kt-params");
        java.nio.file.Files.createDirectories(root.resolve("data"));
        byte[] source = "class Repo {\n  fun save(l: Lang, s: Int) {}\n  fun save(s: Int) {}\n  fun load() {}\n}\n"
                .getBytes();
        java.nio.file.Files.write(root.resolve("data/Repo.kt"), source);
        var baseline = mock(com.mbworldwideapps.aiorchestration.modules.scanner.CodeBaselineRepository.class);
        java.util.UUID fileId = java.util.UUID.randomUUID();
        java.util.UUID runId = java.util.UUID.randomUUID();
        var file = new com.mbworldwideapps.aiorchestration.modules.scanner.CodeFileRecord(fileId, "P", "data/Repo.kt",
                sha256(source), "kotlin", runId, java.util.Map.of());
        when(baseline.findFileById("P", fileId)).thenReturn(java.util.Optional.of(file));
        when(baseline.findFileByPath("P", "data/Repo.kt")).thenReturn(java.util.Optional.of(file));
        when(baseline.findRun("P", runId)).thenReturn(java.util.Optional.of(
                new com.mbworldwideapps.aiorchestration.modules.scanner.CodeScanRunRecord(runId, "P", root.toString(),
                        "completed", java.time.Instant.now(), java.time.Instant.now(), java.util.Map.of())));
        var save2 = withSignature(symbol(fileId, "method", "p.Repo#save", 2, 2), "save(Lang, Int)");
        var save1 = withSignature(symbol(fileId, "method", "p.Repo#save", 3, 3), "save(Int)");
        var load = withSignature(symbol(fileId, "method", "p.Repo#load", 4, 4), "load()");
        when(baseline.findSymbolsByRef(eq("P"), any(), anyInt())).thenReturn(List.of());
        when(baseline.findSymbolsByRef(eq("P"), eq("p.Repo#save"), anyInt())).thenReturn(List.of(save2, save1));
        when(baseline.findSymbolsByRef(eq("P"), eq("p.Repo#load"), anyInt())).thenReturn(List.of(load));
        tool.setCodeBaseline(baseline);

        assertThat(tool.currentLocation("P", "p.Repo#save(Lang,Int)", "data/Repo.kt"))
                .isEqualTo("data/Repo.kt:2-2 p.Repo#save(Lang,Int) (method)");
        assertThat(tool.currentLocation("P", "p.Repo#save(Int)", "data/Repo.kt"))
                .isEqualTo("data/Repo.kt:3-3 p.Repo#save(Int) (method)");
        // a differently written parameter list still finds the only member of that name
        assertThat(tool.currentLocation("P", "p.Repo#load(Unit)", "data/Repo.kt"))
                .isEqualTo("data/Repo.kt:4-4 p.Repo#load(Unit) (method)");
        // overloads and no parameter list that matches: not guessed
        assertThat(tool.currentLocation("P", "p.Repo#save(String)", "data/Repo.kt")).isNull();
        assertThat(AgentSessionMcpTool.withoutParameters("a.B#c(D)")).isEqualTo("a.B#c");
        assertThat(AgentSessionMcpTool.withoutParameters("a.B(x)")).isEqualTo("a.B(x)");
    }

    private static com.mbworldwideapps.aiorchestration.modules.scanner.CodeSymbolRecord withSignature(
            com.mbworldwideapps.aiorchestration.modules.scanner.CodeSymbolRecord s, String signature) {
        return new com.mbworldwideapps.aiorchestration.modules.scanner.CodeSymbolRecord(s.id(), s.projectKey(),
                s.fileId(), s.symbolKind(), s.name(), s.fqn(), signature, s.role(), s.startLine(), s.endLine(),
                s.contentHash(), s.scanRunId(), s.metadata());
    }

    @Test
    void aCutExcerptSaysWhichLinesItShowsAndWhereTheSymbolEnds() {
        List<String> lines = java.util.stream.IntStream.rangeClosed(1, 100).mapToObj(i -> "line " + i).toList();
        String cut = AgentSessionMcpTool.excerpt("a.py:5-90 f (function)", 5, 90, lines, 10_000);
        assertThat(cut).contains("5│ line 5\n", "44│ line 44\n").doesNotContain("45│")
                .endsWith("… cut: lines 5-44 shown; the symbol ends at line 90\n");
        String small = AgentSessionMcpTool.excerpt("a.py:5-90 f (function)", 5, 90, lines, 150);
        assertThat(small).contains("5│ line 5").contains("… cut: lines 5-");
        assertThat(small.length()).isLessThanOrEqualTo(150);
        assertThat(AgentSessionMcpTool.excerpt("a.py:5-90 f (function)", 5, 90, lines, 40)).isNull();
        assertThat(AgentSessionMcpTool.excerpt("a.py:5-6 f (function)", 5, 6, lines, 10_000))
                .doesNotContain("cut");
    }

    private static String sha256(byte[] bytes) throws Exception {
        return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static com.mbworldwideapps.aiorchestration.modules.scanner.CodeSymbolRecord symbol(java.util.UUID fileId,
            String kind, String fqn, int start, int end) {
        return new com.mbworldwideapps.aiorchestration.modules.scanner.CodeSymbolRecord(java.util.UUID.randomUUID(), "P",
                fileId, kind, fqn.replaceAll(".*[#.]", ""), fqn, "", kind, start, end, "h", null, java.util.Map.of());
    }

    /** Retrieval as the service does it: admit (kinds) and show (not shown before) applied to the candidates. */
    @SuppressWarnings("unchecked")
    private static void stepAnswers(com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryRetrievalService retrieval,
            String query, List<com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryContextItem> candidates,
            java.util.Map<com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryContextItem,
                    com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryItem> stored) {
        when(retrieval.retrieveForStep(eq(query), eq("P"), any(), any(), anyDouble())).thenAnswer(call -> {
            java.util.function.Predicate<com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryItem> admit =
                    call.getArgument(2);
            java.util.function.Predicate<com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryItem> show =
                    call.getArgument(3);
            var admitted = candidates.stream().filter(c -> admit.test(stored.get(c))).toList();
            return new com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryRetrievalService.StepRetrieval(
                    contextCards(admitted.stream().filter(c -> show.test(stored.get(c))).toList()),
                    !admitted.isEmpty());
        });
    }

    @Test
    void aTaskMatchBetweenThePointerFloorAndTheCardFloorGoesOutAsOnePointerWithoutText() {
        McpClientContextHolder.set(context(List.of("scanner.scan")));
        resolves();
        var retrieval = mock(com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryRetrievalService.class);
        var full = card("csv export pattern", 0.70);
        var near = card("csv helper quoting", 0.63);
        var nearer = card("owner csv controller", 0.62);
        var weak = card("unrelated page", 0.55);
        when(retrieval.retrieveForInjection("export vets as csv", "P", null)).thenReturn(
                contextCards(List.of(full, near, nearer, weak)));
        when(retrieval.retrieveForInjection(AgentSessionMcpTool.VERIFY_QUERY, "P", null)).thenReturn(
                com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryContextResponse.empty());
        tool.setSessionSupport(retrieval, null, emptyProvider(), null, 0.65);
        tool.setCardPointerMinScore(0.60);

        var memory = tool.bootstrap("/repo", "export vets as csv", null, null, null, null).memory();
        assertThat(memory).extracting(AgentSessionMcpTool.Card::summary)
                .containsExactly("csv export pattern", "csv helper quoting");  // one pointer, the best below the floor
        assertThat(memory.get(0).pointer()).isNull();
        assertThat(memory.get(1).pointer()).isTrue();
        assertThat(memory.get(1).text()).isNull();
    }

    @Test
    void withoutAFullCardOnlyThePointerGoesOutAndNothingBelowThePointerFloor() {
        McpClientContextHolder.set(context(List.of("scanner.scan")));
        resolves();
        var retrieval = mock(com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryRetrievalService.class);
        when(retrieval.retrieveForInjection("rename a column", "P", null)).thenReturn(
                contextCards(List.of(card("migration naming", 0.61))));
        when(retrieval.retrieveForInjection("unrelated task", "P", null)).thenReturn(
                contextCards(List.of(card("weak match", 0.59))));
        when(retrieval.retrieveForInjection(AgentSessionMcpTool.VERIFY_QUERY, "P", null)).thenReturn(
                com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryContextResponse.empty());
        tool.setSessionSupport(retrieval, null, emptyProvider(), null, 0.65);
        tool.setCardPointerMinScore(0.60);

        assertThat(tool.bootstrap("/repo", "rename a column", null, null, null, null).memory())
                .singleElement().satisfies(c -> assertThat(c.pointer()).isTrue());
        assertThat(tool.bootstrap("/repo", "unrelated task", null, null, null, null).memory()).isEmpty();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void testAddressFillerSurvivesAStrongerChangePointMatch(boolean kindInMetadata) {
        McpClientContextHolder.set(context(List.of("scanner.scan")));
        resolves();
        var retrieval = mock(com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryRetrievalService.class);
        var changePoint = card("implementation entry point", 0.90);
        var testAddress = card("tests covering stylesheet changes", 0.65);
        var memories = mock(com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryRepository.class);
        when(memories.findById(changePoint.memoryId())).thenReturn(java.util.Optional.of(
                storedCard(changePoint, List.of("discovery", "change_point"))));
        when(memories.findById(testAddress.memoryId())).thenReturn(java.util.Optional.of(
                storedCard(testAddress, kindInMetadata ? List.of() : List.of("discovery", "test_address"),
                        kindInMetadata ? java.util.Map.of("discoveryKind", "test_address") : java.util.Map.of())));
        when(retrieval.retrieveForInjection("rename a stylesheet", "P", null)).thenReturn(
                com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryContextResponse.empty());
        when(retrieval.retrieveForInjection(AgentSessionMcpTool.VERIFY_QUERY, "P", null)).thenReturn(
                contextCards(List.of(changePoint, testAddress)));
        tool.setSessionSupport(retrieval, memories, emptyProvider(), null, 0.65);

        assertThat(tool.bootstrap("/repo", "rename a stylesheet", null, null, null, null).memory())
                .extracting(AgentSessionMcpTool.Card::summary).containsExactly(testAddress.summary());
    }

    private static com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryContextResponse contextCards(
            List<com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryContextItem> items) {
        return new com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryContextResponse(
                items, List.of(), items.size(), 30, java.util.Map.of(), 0, 0, 0L);
    }

    private static com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryItem storedCard(
            com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryContextItem item, List<String> tags) {
        return storedCard(item, tags, java.util.Map.of());
    }

    private static com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryItem storedCard(
            com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryContextItem item, List<String> tags,
            java.util.Map<String, Object> metadata) {
        return new com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryItem(
                item.memoryId(), item.vectorId(), item.scope(), item.projectKey(), item.memoryType(),
                item.summary(), item.text(), tags, item.confidence(),
                com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryStatus.ACTIVE,
                com.mbworldwideapps.aiorchestration.modules.memoryai.MemorySourceType.MCP_EXTERNAL,
                "memory.learn", "test", metadata, java.time.Instant.now(), java.time.Instant.now(),
                null, java.time.Instant.now(), null);
    }

    private static com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryContextItem card(String summary,
            double score) {
        UUID id = UUID.randomUUID();
        return new com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryContextItem(id, id, "c",
                com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryScope.PROJECT, "P",
                com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryType.DECISION, summary, summary + " text",
                null, 0.9, false, 10, score, java.time.Instant.now());
    }

    @SuppressWarnings("unchecked")
    private static org.springframework.beans.factory.ObjectProvider<
            com.mbworldwideapps.aiorchestration.modules.agentlearning.AgentLearningRepository> emptyProvider() {
        return mock(org.springframework.beans.factory.ObjectProvider.class);
    }

    @Test
    void overLongCardsAreTrimmedInsteadOfRejected() {
        String longText = "word ".repeat(200);
        String clipped = AgentSessionMcpTool.clip(longText, AgentSessionMcpTool.MAX_CONTENT);
        assertThat(clipped.length()).isLessThanOrEqualTo(AgentSessionMcpTool.MAX_CONTENT);
        assertThat(clipped).endsWith("…");
        assertThat(AgentSessionMcpTool.clip("short", 700)).isEqualTo("short");
    }

    @Test
    void looselyWrittenKindsAreMappedInsteadOfRejected() {
        assertThat(AgentSessionMcpTool.kindOf("Procedure")).isEqualTo("procedure");
        assertThat(AgentSessionMcpTool.kindOf("debug-finding")).isEqualTo("debug_finding");
        assertThat(AgentSessionMcpTool.kindOf("pitfall")).isEqualTo("debug_finding");
        assertThat(AgentSessionMcpTool.kindOf("behavior")).isEqualTo("behavior");
        assertThat(AgentSessionMcpTool.kindOf("Change Point")).isEqualTo("change_point");
        assertThat(AgentSessionMcpTool.kindOf("1")).isEqualTo("procedure");
        assertThat(AgentSessionMcpTool.kindOf(null)).isEqualTo("procedure");
    }

    @Test
    void bootstrapRejectsRelativeRootAndAuditsError() {
        McpClientContextHolder.set(context(List.of("scanner.scan", "rules.read")));

        assertThatThrownBy(() -> tool.bootstrap(".", null)).isInstanceOf(IllegalArgumentException.class);

        verify(scanner, never()).resolveProject(any(), any());
        verify(audit).log(any(), eq("session.bootstrap"), anyString(), eq(0), anyLong(), eq("error"), anyMap(),
                eq("IllegalArgumentException"));
    }

    @Test
    void learnRequiresMemoryWriteScopeAndAuditsDenial() {
        McpClientContextHolder.set(context(List.of("scanner.scan", "rules.read")));

        assertThatThrownBy(() -> tool.learn("/repo", List.of(decision("d")), null))
                .isInstanceOf(McpAccessException.class);

        verify(memory, never()).write(any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(audit).log(any(), eq("memory.learn"), anyString(), eq(0), anyLong(), eq("denied_scope"), anyMap(),
                eq("McpAccessException"));
    }

    @Test
    void learnRejectsMoreThanThreeCandidates() {
        McpClientContextHolder.set(context(List.of("scanner.scan", "memory.write")));

        assertThatThrownBy(() -> tool.learn("/repo",
                List.of(decision("a"), decision("b"), decision("c"), decision("d")), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void learnWritesLocatorFreeDecisionThroughGatedMemoryWrite() {
        McpClientContextHolder.set(context(List.of("scanner.scan", "memory.write")));
        resolves();
        UUID id = UUID.randomUUID();
        when(memory.write(eq("content d"), eq("summary d"), eq("decision"), any(), eq("memory.learn"),
                eq("project"), isNull(), eq("P"), isNull())).thenReturn(new MemoryWriteResponse(id, "active", "src"));

        var response = tool.learn("/repo", List.of(decision("d")), null);

        assertThat(response.status()).isEqualTo("ok");
        assertThat(response.created()).isEqualTo(1);
        assertThat(response.items()).singleElement().satisfies(item -> {
            assertThat(item.index()).isZero();
            assertThat(item.outcome()).isEqualTo("created");
            assertThat(item.memoryId()).isEqualTo(id.toString());
        });
        verify(learning, never()).capture(any());
    }

    @Test
    void learnReportsGateDuplicateAsNotSavedWithoutClaimingAnId() {
        McpClientContextHolder.set(context(List.of("scanner.scan", "memory.write")));
        resolves();
        when(memory.write(any(), any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(
                new MemoryWriteResponse(null, "not_saved", "src", "project", "P", "REJECTED", false,
                        "duplicate_memory", null, List.of("already covered")));

        var response = tool.learn("/repo", List.of(decision("d")), null);

        assertThat(response.created()).isZero();
        assertThat(response.reused()).isZero();
        assertThat(response.duplicates()).isEqualTo(1);
        assertThat(response.rejected()).isZero();
        assertThat(response.status()).isEqualTo("ok");
        assertThat(response.items()).singleElement().satisfies(item -> {
            assertThat(item.outcome()).isEqualTo("duplicate_not_saved");
            assertThat(item.memoryId()).isNull();
            assertThat(item.reason()).isEqualTo("duplicate_memory");
        });
    }

    @Test
    void learnRejectsLocatorFreeCodeFactWithoutWriting() {
        McpClientContextHolder.set(context(List.of("scanner.scan", "memory.write")));
        resolves();
        CaptureCandidate codeFact = new CaptureCandidate("behavior", "s", "c", List.of(), List.of(), List.of(),
                List.of());

        var response = tool.learn("/repo", List.of(codeFact), null);

        assertThat(response.status()).isEqualTo("rejected");
        assertThat(response.items()).singleElement()
                .satisfies(item -> assertThat(item.reason()).startsWith("LOCATOR_REQUIRED"));
        verify(memory, never()).write(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void learnRoutesAnchoredCandidatesToSharedCaptureWithSessionRunId() {
        McpClientContextHolder.set(context(List.of("scanner.scan", "memory.write", "codebase.read")));
        resolves();
        when(learningProvider.getIfAvailable()).thenReturn(learning);
        when(learning.capture(any())).thenReturn(new CompactLearnReceipt("ACCEPTED", 0, 0, 1, 0, 0, 0, 0));
        CaptureCandidate anchored = new CaptureCandidate("behavior", "Route", "Route is chosen here.",
                List.of(new CaptureLocator("symbol", "com.acme.Route#select", null, "src/Route.java", null)),
                List.of(), List.of(), List.of());

        var response = tool.learn("/repo", List.of(anchored), null);

        assertThat(response.reused()).isEqualTo(1);
        assertThat(response.created()).isZero();
        verify(learning).capture(org.mockito.ArgumentMatchers.argThat(command ->
                command.projectKey().equals("P")
                        && command.workspaceBindingId().equals(binding.toString())
                        && command.taskRunId().startsWith("mcp-")
                        && command.learningCandidates().equals(List.of(anchored))));
    }

    @Test
    void learnReportsEachInputCandidateInInputOrderAndCaptureCountsOnce() throws Exception {
        McpClientContextHolder.set(context(List.of("scanner.scan", "memory.write", "codebase.read")));
        resolves();
        when(learningProvider.getIfAvailable()).thenReturn(learning);
        when(learning.capture(any())).thenReturn(new CompactLearnReceipt("ACCEPTED", 1, 0, 0, 1, 0, 0, 0,
                List.of(new AgentLearningModels.LearnItem(0, "Route", "created", null, List.of("com.acme.Route#select")),
                        new AgentLearningModels.LearnItem(1, "Gone", "rejected", "LOCATOR_UNRESOLVED:src/Gone.java",
                                List.of()))));
        UUID id = UUID.randomUUID();
        when(memory.write(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new MemoryWriteResponse(id, "active", "src"));
        CaptureCandidate route = new CaptureCandidate("behavior", "Route", "Route is chosen here.",
                List.of(new CaptureLocator("symbol", "com.acme.Route#select", null, "src/Route.java", null)),
                List.of(), List.of(), List.of());
        CaptureCandidate gone = new CaptureCandidate("behavior", "Gone", "Gone is gone.",
                List.of(new CaptureLocator("file", "src/Gone.java", null, null, null)),
                List.of(), List.of(), List.of());

        var response = tool.learn("/repo", List.of(route, decision("d"), gone), null);

        assertThat(response.status()).isEqualTo("partial");
        assertThat(response.created()).isEqualTo(2);
        assertThat(response.rejected()).isEqualTo(1);
        assertThat(response.items()).extracting(AgentSessionMcpTool.LearnedItem::index,
                AgentSessionMcpTool.LearnedItem::outcome, AgentSessionMcpTool.LearnedItem::reason)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(0, "created", null),
                        org.assertj.core.groups.Tuple.tuple(1, "created", null),
                        org.assertj.core.groups.Tuple.tuple(2, "rejected", "LOCATOR_UNRESOLVED:src/Gone.java"));
        assertThat(response.items().get(0).unresolvedLocators()).containsExactly("com.acme.Route#select");
        assertThat(response.capture().items()).isEmpty();
        String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(response);
        assertThat(json).contains("\"index\":0", "\"unresolvedLocators\":[\"com.acme.Route#select\"]")
                .doesNotContain("\"unresolvedLocators\":[]", "\"items\":[]");
    }

    @Test
    void cardsWhoseLocatorsCannotBeResolvedYetAreKeptWithDeclaredFileLinks() {
        // An unscanned project: the capture path resolves nothing, so the card must not be lost.
        McpClientContextHolder.set(context(List.of("scanner.scan", "memory.write")));
        resolves();
        when(learningProvider.getIfAvailable()).thenReturn(learning);
        when(learning.capture(any())).thenReturn(new CompactLearnReceipt("REJECTED", 0, 0, 0, 1, 0, 0, 0,
                List.of(new AgentLearningModels.LearnItem(0, "Running the tests", "rejected",
                        "LOCATOR_UNRESOLVED:com.acme.App#main", List.of()))));
        UUID id = UUID.randomUUID();
        when(memory.write(eq("Run ./mvnw test."), eq("Running the tests"), eq("decision"), any(),
                eq("memory.learn"), eq("project"), isNull(), eq("P"), any()))
                .thenReturn(new MemoryWriteResponse(id, "active", "src"));
        CaptureCandidate card = new CaptureCandidate("procedure", "Running the tests", "Run ./mvnw test.",
                List.of(new CaptureLocator("file", "pom.xml", null, null, null),
                        new CaptureLocator("symbol", "com.acme.App#main", null, null, null)),
                List.of(), List.of(), List.of());

        var response = tool.learn("/repo", List.of(card), null);

        assertThat(response.status()).isEqualTo("ok");
        assertThat(response.created()).isEqualTo(1);
        assertThat(response.capture()).isNull();
        assertThat(response.items()).singleElement().satisfies(item -> {
            assertThat(item.outcome()).isEqualTo("created");
            assertThat(item.unresolvedLocators()).containsExactly("com.acme.App#main");
        });
        verify(memory).write(any(), any(), any(), any(), any(), any(), any(), any(),
                org.mockito.ArgumentMatchers.argThat(locators -> locators != null && locators.size() == 1
                        && locators.get(0).ref().equals("pom.xml")));
    }

    @Test
    void anUnknownCorrectionStaysRejectedAndIsNeverWrittenAsANewCard() {
        McpClientContextHolder.set(context(List.of("scanner.scan", "memory.write")));
        resolves();
        when(learningProvider.getIfAvailable()).thenReturn(learning);
        when(learning.capture(any())).thenReturn(new CompactLearnReceipt("REJECTED", 0, 0, 0, 1, 0, 0, 0,
                List.of(new AgentLearningModels.LearnItem(0, "Corrected route", "rejected",
                        "CORRECTION_UNKNOWN", List.of()))));
        CaptureCandidate correction = new CaptureCandidate("behavior", "Corrected route", "c",
                List.of(new CaptureLocator("file", "src/Route.java", null, null, null)),
                List.of(), List.of(), List.of());

        var response = tool.learn("/repo", List.of(correction), null);

        assertThat(response.status()).isEqualTo("rejected");
        assertThat(response.created()).isZero();
        assertThat(response.rejected()).isEqualTo(1);
        assertThat(response.items()).singleElement().satisfies(item -> {
            assertThat(item.outcome()).isEqualTo("rejected");
            assertThat(item.reason()).isEqualTo("CORRECTION_UNKNOWN");
        });
        verify(memory, never()).write(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void allRejectedBatchFallsBackOnlyForUnresolvedLocatorsAndKeepsOtherRejections() {
        McpClientContextHolder.set(context(List.of("scanner.scan", "memory.write")));
        resolves();
        when(learningProvider.getIfAvailable()).thenReturn(learning);
        when(learning.capture(any())).thenReturn(new CompactLearnReceipt("REJECTED", 0, 0, 0, 2, 0, 0, 0,
                List.of(new AgentLearningModels.LearnItem(0, "Running the tests", "rejected",
                                "LOCATOR_UNRESOLVED:com.acme.App#main", List.of()),
                        new AgentLearningModels.LearnItem(1, "Corrected route", "rejected",
                                "CORRECTION_UNKNOWN", List.of()))));
        when(memory.write(eq("Run ./mvnw test."), eq("Running the tests"), eq("decision"), any(),
                eq("memory.learn"), eq("project"), isNull(), eq("P"), any()))
                .thenReturn(new MemoryWriteResponse(UUID.randomUUID(), "active", "src"));
        CaptureCandidate unresolved = new CaptureCandidate("procedure", "Running the tests", "Run ./mvnw test.",
                List.of(new CaptureLocator("file", "pom.xml", null, null, null),
                        new CaptureLocator("symbol", "com.acme.App#main", null, null, null)),
                List.of(), List.of(), List.of());
        CaptureCandidate correction = new CaptureCandidate("behavior", "Corrected route", "c",
                List.of(new CaptureLocator("file", "src/Route.java", null, null, null)),
                List.of(), List.of(), List.of());

        var response = tool.learn("/repo", List.of(unresolved, correction), null);

        assertThat(response.status()).isEqualTo("partial");
        assertThat(response.created()).isEqualTo(1);
        assertThat(response.rejected()).isEqualTo(1);
        assertThat(response.items()).hasSize(2);
        assertThat(response.items().get(0)).satisfies(item -> {
            assertThat(item.index()).isZero();
            assertThat(item.outcome()).isEqualTo("created");
            assertThat(item.unresolvedLocators()).containsExactly("com.acme.App#main");
        });
        assertThat(response.items().get(1)).satisfies(item -> {
            assertThat(item.index()).isEqualTo(1);
            assertThat(item.outcome()).isEqualTo("rejected");
            assertThat(item.reason()).isEqualTo("CORRECTION_UNKNOWN");
        });
        verify(memory).write(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void learnReportsDisabledLearningInsteadOfDroppingAnchoredCandidates() {
        McpClientContextHolder.set(context(List.of("scanner.scan", "memory.write")));
        resolves();
        when(learningProvider.getIfAvailable()).thenReturn(null);
        CaptureCandidate anchored = new CaptureCandidate("behavior", "Route", "c",
                List.of(new CaptureLocator("file", "src/Route.java", null, null, null)),
                List.of(), List.of(), List.of());

        var response = tool.learn("/repo", List.of(anchored), null);

        assertThat(response.status()).isEqualTo("rejected");
        assertThat(response.items()).singleElement()
                .satisfies(item -> assertThat(item.reason()).isEqualTo("LEARNING_DISABLED"));
    }

    @Test
    void localTrustCallersMayPassANestedWorkingDirectory(@org.junit.jupiter.api.io.TempDir java.nio.file.Path repo)
            throws Exception {
        java.nio.file.Files.createDirectory(repo.resolve(".git"));
        java.nio.file.Path nested = java.nio.file.Files.createDirectories(repo.resolve("src/main"));
        McpClientContext local = context(List.of("scanner.scan"));
        McpClientContext bearer = new McpClientContext("P", "claude-code", "abcd", List.of("scanner.scan"),
                McpAuditLogger.sha256Hex("claude-code:s"));

        assertThat(AgentSessionMcpTool.gitRoot(local, nested.toString())).isEqualTo(repo.toString());
        assertThat(AgentSessionMcpTool.gitRoot(bearer, nested.toString())).isEqualTo(nested.toString());
        assertThat(AgentSessionMcpTool.gitRoot(local, "/definitely/not/a/repo")).isEqualTo("/definitely/not/a/repo");
    }

    @Test
    void lenientLocatorsFixHarmlessAgentVariantsInsteadOfRejecting() {
        CaptureCandidate sent = new CaptureCandidate("behavior", "s", "c", List.of(
                new CaptureLocator("file", "OwnerController.processFindForm", null,
                        "src/main/java/demo/OwnerController.java", "implementation"),
                new CaptureLocator("symbol", "demo.OwnerController#processFindForm", null,
                        "src/main/java/demo/OwnerController.java", "Primary_Change_Point")),
                List.of(), List.of(), List.of());

        CaptureCandidate fixed = AgentSessionMcpTool.lenient(sent);

        assertThat(fixed.locators().get(0)).isEqualTo(
                new CaptureLocator("file", "src/main/java/demo/OwnerController.java", null, null, null));
        assertThat(fixed.locators().get(1).role()).isEqualTo("primary_change_point");
        assertThat(fixed.locators().get(1).path()).isEqualTo("src/main/java/demo/OwnerController.java");
    }

    @Test
    void onlyTheThreeCoreAgentToolsAreAlwaysLoaded() throws Exception {
        java.util.Set<String> alwaysLoaded = new java.util.TreeSet<>();
        for (Class<?> type : List.of(AgentSessionMcpTool.class, MemoryMcpTool.class, RulesMcpTool.class,
                ScannerMcpTool.class, ReferenceMcpTool.class, MemoryReviewMcpTool.class)) {
            for (var method : type.getDeclaredMethods()) {
                var annotation = method.getAnnotation(org.springaicommunity.mcp.annotation.McpTool.class);
                if (annotation != null && annotation.metaProvider() == AlwaysLoadToolMeta.class) {
                    alwaysLoaded.add(annotation.name());
                }
            }
        }
        assertThat(alwaysLoaded).containsExactly("memory.learn", "memory.search", "session.bootstrap");
        assertThat(new AlwaysLoadToolMeta().getMeta()).containsEntry("anthropic/alwaysLoad", true);
    }

    private void resolves() {
        when(scanner.resolveProject("/repo", null)).thenReturn(
                new ScannerProjectResolveResponse("/repo", "P", "P", true, true, "fp", binding));
    }

    private static CaptureCandidate decision(String suffix) {
        return new CaptureCandidate("decision", "summary " + suffix, "content " + suffix, List.of(), List.of(),
                List.of(), List.of());
    }

    private static McpClientContext context(List<String> scopes) {
        return new McpClientContext("P", "claude-code", "local", scopes,
                McpAuditLogger.sha256Hex("claude-code:session-1"));
    }
}
