package com.mbworldwideapps.aiorchestration.modules.mcp.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
import java.util.UUID;

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
        when(retrieval.retrieveForInjection("add a vets csv export", "P", null)).thenReturn(
                new com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryContextResponse(
                        List.of(feature), List.of(), 1, 30, java.util.Map.of(), 0, 0, 0L));
        when(retrieval.retrieveForInjection(AgentSessionMcpTool.VERIFY_QUERY, "P", null)).thenReturn(
                new com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryContextResponse(
                        List.of(verify, feature), List.of(), 2, 30, java.util.Map.of(), 0, 0, 0L));
        tool.setSessionSupport(retrieval, null, emptyProvider(), null, 0.0);

        var response = tool.bootstrap("/repo", "add a vets csv export", null, null, null, null);

        assertThat(response.memory()).extracting(AgentSessionMcpTool.Card::summary)
                .containsExactly("csv export of owners", "run the tests with JAVA_HOME set to JDK 21");
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
            assertThat(item.route()).isEqualTo("memory.write");
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
            assertThat(item.status()).isEqualTo("duplicate_not_saved");
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
    void cardsWhoseLocatorsCannotBeResolvedYetAreKeptWithDeclaredFileLinks() {
        // An unscanned project: the capture path resolves nothing, so the card must not be lost.
        McpClientContextHolder.set(context(List.of("scanner.scan", "memory.write")));
        resolves();
        when(learningProvider.getIfAvailable()).thenReturn(learning);
        when(learning.capture(any())).thenReturn(new CompactLearnReceipt("REJECTED", 0, 0, 0, 1, 0, 0, 0));
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
        verify(memory).write(any(), any(), any(), any(), any(), any(), any(), any(),
                org.mockito.ArgumentMatchers.argThat(locators -> locators != null && locators.size() == 1
                        && locators.get(0).ref().equals("pom.xml")));
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
