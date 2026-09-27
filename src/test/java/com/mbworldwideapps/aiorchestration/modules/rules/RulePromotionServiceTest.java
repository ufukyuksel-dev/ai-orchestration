package com.mbworldwideapps.aiorchestration.modules.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mbworldwideapps.aiorchestration.config.RulesProperties;
import com.mbworldwideapps.aiorchestration.core.diff.UnifiedDiffParser;
import com.mbworldwideapps.aiorchestration.modules.memoryai.RuleMemoryPromotionPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class RulePromotionServiceTest {

    private RuleRepository repository;
    private RuleMemoryPromotionPort promotionPort;
    private RulePromotionService service;

    @BeforeEach
    void setUp() {
        repository = mock(RuleRepository.class);
        promotionPort = mock(RuleMemoryPromotionPort.class);
        service = new RulePromotionService(repository, promotionPort,
                new RulesProperties(true, 20, 256, 4096, 200), new ObjectMapper(),
                List.of(new DiffRegexRuleDetector(new UnifiedDiffParser())));
    }

    @Test
    void symbolGlobCannotBypassInstructionRestrictionsOrActiveGlobLimit() {
        RuleSelectorGroup selector = selectorGroup("controllers", new RuleSelectorPredicate(
                SelectorPolarity.INCLUDE, SelectorField.SYMBOL, SelectorOperator.GLOB, List.of("**Controller")));
        RulePromotionCandidate candidate = semanticCandidate(List.of(selector), List.of());
        RulePromotionPreview preview = service.preview(candidate);
        when(repository.maxProjectedActiveGlobRules("AI_ORCHESTRATION", null)).thenReturn(201);
        assertThatThrownBy(() -> service.promote(request(candidate, preview)))
                .hasMessageContaining("active glob rule limit");
        RulePromotionCandidate instruction = new RulePromotionCandidate(null, null, "AI_ORCHESTRATION",
                "Controllers stay thin", null, RuleEnforcement.INSTRUCTION, false, null, null,
                List.of(), List.of(selector), List.of(), RuleProvenance.DIRECT_HUMAN_POLICY);
        assertThatThrownBy(() -> service.preview(instruction)).isInstanceOf(IllegalArgumentException.class);
        RulePromotionCandidate invalid = semanticCandidate(List.of(selectorGroup("invalid",
                new RuleSelectorPredicate(SelectorPolarity.INCLUDE, SelectorField.SYMBOL,
                        SelectorOperator.GLOB, List.of("bad\n*")))), List.of());
        assertThatThrownBy(() -> service.preview(invalid)).hasMessageContaining("symbol glob input");
        verify(repository, never()).insertDefinition(any());
    }

    @Test
    void previewIsCanonicalAcrossBindingOrderAndBindsDetectorContract() {
        RulePromotionCandidate first = gateCandidate(Map.of("pattern", "forbidden"), List.of(
                target(BindingKind.FILE, "src/B.java"),
                target(BindingKind.FILE, "src/A.java")));
        RulePromotionCandidate second = gateCandidate(Map.of("pattern", "forbidden"), List.of(
                target(BindingKind.FILE, "src/A.java"),
                target(BindingKind.FILE, "src/B.java")));

        RulePromotionPreview firstPreview = service.preview(first);
        RulePromotionPreview secondPreview = service.preview(second);

        assertThat(firstPreview.versionContentHash()).isEqualTo(secondPreview.versionContentHash());
        assertThat(firstPreview.approvalContentHash()).isEqualTo(secondPreview.approvalContentHash());
        assertThat(firstPreview.confirmationCardHash()).isEqualTo(secondPreview.confirmationCardHash());
        String contractHash = new DiffRegexRuleDetector(new UnifiedDiffParser()).contract().contractHash();
        assertThat(firstPreview.confirmationCard())
                .contains("src/A.java", "src/B.java", "forbidden", contractHash);
    }

    @Test
    void previewCanonicallyBindsSelectorsChecksAndCheckerContracts() {
        RuleSelectorGroup controllerGroup = selectorGroup("controllers",
                new RuleSelectorPredicate(SelectorPolarity.INCLUDE, SelectorField.PATH,
                        SelectorOperator.GLOB, List.of("**/*Controller.java")),
                new RuleSelectorPredicate(SelectorPolarity.EXCLUDE, SelectorField.SOURCE_SET,
                        SelectorOperator.EQUALS, List.of("TEST")));
        RuleSelectorGroup javaGroup = selectorGroup("java",
                new RuleSelectorPredicate(SelectorPolarity.INCLUDE, SelectorField.LANGUAGE,
                        SelectorOperator.EQUALS, List.of("JAVA")));
        RuleCheckSpec forbidBusinessLogic = new RuleCheckSpec(RuleCheckPhase.PLAN, "forbid_intent",
                RuleCheckSeverity.REQUIRED, Map.of("intentKinds", List.of("BUSINESS_DECISION")));
        RuleCheckSpec requireDelegation = new RuleCheckSpec(RuleCheckPhase.PLAN, "require_intent",
                RuleCheckSeverity.REQUIRED, Map.of(
                        "requiredAnyOf", List.of("DELEGATE_TO_SERVICE"),
                        "whenAnyOf", List.of("HTTP_MAPPING")));

        RulePromotionCandidate first = semanticCandidate(
                List.of(controllerGroup, javaGroup), List.of(forbidBusinessLogic, requireDelegation));
        RulePromotionCandidate reordered = semanticCandidate(
                List.of(javaGroup, controllerGroup), List.of(requireDelegation, forbidBusinessLogic));

        RulePromotionPreview firstPreview = service.preview(first);
        RulePromotionPreview reorderedPreview = service.preview(reordered);

        assertThat(firstPreview.versionContentHash()).isEqualTo(reorderedPreview.versionContentHash());
        assertThat(firstPreview.approvalContentHash()).isEqualTo(reorderedPreview.approvalContentHash());
        assertThat(firstPreview.confirmationCard())
                .contains("selectors", "checks", "controllers", "forbid_intent", "implementationVersion")
                .containsPattern("[0-9a-f]{64}");
        assertThat(firstPreview.workflowContractVersion()).isEqualTo("rule-authority/v2");
    }

    @Test
    void previewHashChangesWhenSelectorOrCheckChanges() {
        RulePromotionCandidate baseline = semanticCandidate(
                List.of(selectorGroup("controllers",
                        new RuleSelectorPredicate(SelectorPolarity.INCLUDE, SelectorField.PATH,
                                SelectorOperator.GLOB, List.of("**/*Controller.java")))),
                List.of(new RuleCheckSpec(RuleCheckPhase.PLAN, "forbid_intent",
                        RuleCheckSeverity.REQUIRED, Map.of("intentKinds", List.of("BUSINESS_DECISION")))));
        RulePromotionCandidate changedSelector = semanticCandidate(
                List.of(selectorGroup("services",
                        new RuleSelectorPredicate(SelectorPolarity.INCLUDE, SelectorField.PATH,
                                SelectorOperator.GLOB, List.of("**/*Service.java")))),
                baseline.checks());
        RulePromotionCandidate changedCheck = semanticCandidate(
                baseline.selectorGroups(),
                List.of(new RuleCheckSpec(RuleCheckPhase.PLAN, "forbid_intent",
                        RuleCheckSeverity.REQUIRED, Map.of("intentKinds", List.of("DOMAIN_CALCULATION")))));

        assertThat(service.preview(baseline).versionContentHash())
                .isNotEqualTo(service.preview(changedSelector).versionContentHash())
                .isNotEqualTo(service.preview(changedCheck).versionContentHash());
    }

    @Test
    void rejectsSelectorPayloadWhoseTotalPredicateCountExceedsTheAuthorityBound() {
        List<RuleSelectorPredicate> firstHalf = java.util.stream.IntStream.range(0, 128)
                .mapToObj(index -> new RuleSelectorPredicate(
                        SelectorPolarity.INCLUDE, SelectorField.SYMBOL, SelectorOperator.EQUALS,
                        List.of("com.acme.First" + index)))
                .toList();
        List<RuleSelectorPredicate> secondHalf = java.util.stream.IntStream.range(0, 129)
                .mapToObj(index -> new RuleSelectorPredicate(
                        SelectorPolarity.INCLUDE, SelectorField.SYMBOL, SelectorOperator.EQUALS,
                        List.of("com.acme.Second" + index)))
                .toList();
        RulePromotionCandidate oversized = semanticCandidate(
                List.of(new RuleSelectorGroup("first", firstHalf),
                        new RuleSelectorGroup("second", secondHalf)),
                List.of());

        assertThatThrownBy(() -> service.preview(oversized))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("total selector predicate limit of 256");
    }

    @Test
    void selectorTargetedGateDoesNotRequireLegacyBindingButStillRequiresCodeDetector() {
        RuleSelectorGroup selector = selectorGroup("controllers",
                new RuleSelectorPredicate(SelectorPolarity.INCLUDE, SelectorField.PATH,
                        SelectorOperator.GLOB, List.of("**/*Controller.java")));
        RulePromotionCandidate selectorGate = new RulePromotionCandidate(null, null, "AI_ORCHESTRATION",
                "Forbidden pattern must not be added", "Deterministic code gate",
                RuleEnforcement.GATE, false, DiffRegexRuleDetector.TYPE, Map.of("pattern", "danger"),
                List.of(), List.of(selector), List.of(), RuleProvenance.DIRECT_HUMAN_POLICY);
        RulePromotionCandidate semanticOnlyGate = new RulePromotionCandidate(null, null, "AI_ORCHESTRATION",
                "Business logic must stay out of controllers", null,
                RuleEnforcement.GATE, false, null, null, List.of(), List.of(selector),
                List.of(new RuleCheckSpec(RuleCheckPhase.PLAN, "semantic_plan_review",
                        RuleCheckSeverity.GATE, Map.of(
                                "contractId", "thin-controller/v1",
                                "criteria", List.of("keep controller thin")))),
                RuleProvenance.DIRECT_HUMAN_POLICY);

        assertThat(service.preview(selectorGate).versionContentHash()).hasSize(64);
        assertThatThrownBy(() -> service.preview(semanticOnlyGate))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("deterministic checker");
    }

    @Test
    void promotionPersistsSelectorsChecksAndScopeWithTheImmutableVersion() {
        RulePromotionCandidate candidate = semanticCandidate(
                List.of(selectorGroup("controllers",
                        new RuleSelectorPredicate(SelectorPolarity.INCLUDE, SelectorField.ROLE,
                                SelectorOperator.EQUALS, List.of("CONTROLLER")))),
                List.of(new RuleCheckSpec(RuleCheckPhase.PLAN, "forbid_intent",
                        RuleCheckSeverity.REQUIRED, Map.of("intentKinds", List.of("BUSINESS_DECISION")))));
        RulePromotionPreview preview = service.preview(candidate);

        RuleVersion promoted = service.promote(request(candidate, preview));

        verify(repository).insertSelectorGroups(org.mockito.ArgumentMatchers.argThat(groups ->
                groups.size() == 1 && groups.getFirst().ruleId().equals(promoted.ruleId())
                        && groups.getFirst().ruleVersion() == 1));
        verify(repository).insertCheckDefinitions(org.mockito.ArgumentMatchers.argThat(checks ->
                checks.size() == 1 && checks.getFirst().checkerType().equals("forbid_intent")
                        && checks.getFirst().checkerContractHash().matches("[0-9a-f]{64}")));
        verify(repository).insertScopeAssignments(org.mockito.ArgumentMatchers.argThat(scopes ->
                scopes.size() == 1 && scopes.getFirst().scopeType() == RuleScopeType.PROJECT
                        && scopes.getFirst().projectKey().equals("AI_ORCHESTRATION")));
        verify(repository).lockEffectiveSequences(List.of("AI_ORCHESTRATION"));
        verify(repository).bumpEffectiveSequences(false, List.of("AI_ORCHESTRATION"));
    }

    @Test
    void globalPromotionBumpsOnlyGlobalReachSequence() {
        RulePromotionCandidate candidate = new RulePromotionCandidate(null, null, null,
                "All controllers must stay thin", "Global architecture policy",
                RuleEnforcement.CONTEXT, false, null, null,
                List.of(target(BindingKind.FILE, "src/Controller.java")),
                RuleProvenance.DIRECT_HUMAN_POLICY);
        RulePromotionPreview preview = service.preview(candidate);

        service.promote(request(candidate, preview));

        verify(repository).lockEffectiveSequences(List.of());
        verify(repository).bumpEffectiveSequences(true, List.of());
    }

    @Test
    void detectorPromotionRejectsUnknownConfigUnsupportedBindingAndUnsafeFileTargets() {
        assertThatThrownBy(() -> service.preview(gateCandidate(
                Map.of("pattern", "danger", "flags", List.of("CASE_INSENSITIVE")),
                List.of(target(BindingKind.FILE, "A.java")))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly 'pattern'");
        assertThatThrownBy(() -> service.preview(gateCandidate(
                Map.of("pattern", "danger"), List.of(target(BindingKind.SYMBOL, "A#method")))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not support");
        for (String unsafe : List.of("/tmp/A.java", "../A.java", "src\\A.java", "src/./A.java")) {
            assertThatThrownBy(() -> service.preview(gateCandidate(
                    Map.of("pattern", "danger"), List.of(target(BindingKind.FILE, unsafe)))))
                    .as("unsafe target %s", unsafe)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void canonicalHashDoesNotCollideOnDelimiterShapedTargets() {
        RulePromotionCandidate oneTarget = contextCandidate(List.of(
                target(BindingKind.FILE, "a,file:b")));
        RulePromotionCandidate twoTargets = contextCandidate(List.of(
                target(BindingKind.FILE, "a"),
                target(BindingKind.FILE, "b")));

        assertThat(service.preview(oneTarget).versionContentHash())
                .isNotEqualTo(service.preview(twoTargets).versionContentHash());
    }

    @Test
    void candidateRejectsNonJsonDetectorValuesBeforePreview() {
        assertThatThrownBy(() -> gateCandidate(
                Map.of("pattern", "danger", "mutable", new StringBuilder("x")),
                List.of(target(BindingKind.FILE, "A.java"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("JSON-shaped");
    }

    @Test
    void promotionRejectsAnyCandidateChangeAfterPreview() {
        RulePromotionCandidate approved = contextCandidate(List.of(target(BindingKind.FILE, "A.java")));
        RulePromotionPreview preview = service.preview(approved);
        RulePromotionCandidate tampered = new RulePromotionCandidate(
                approved.originMemoryId(), approved.expectedOriginContentHash(), approved.projectKey(),
                "A different executable rule", approved.rationale(), RuleEnforcement.GATE, false,
                DiffRegexRuleDetector.TYPE, Map.of("pattern", "danger"),
                List.of(target(BindingKind.FILE, "B.java")), approved.provenance());

        assertThatThrownBy(() -> service.promote(request(tampered, preview)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("approval content changed");
        verifyNoInteractions(repository, promotionPort);
    }

    @Test
    void promotionRejectsSelectorOrCheckTamperingAfterPreview() {
        RulePromotionCandidate approved = semanticCandidate(
                List.of(selectorGroup("controllers",
                        new RuleSelectorPredicate(SelectorPolarity.INCLUDE, SelectorField.PATH,
                                SelectorOperator.GLOB, List.of("**/*Controller.java")))),
                List.of(new RuleCheckSpec(RuleCheckPhase.PLAN, "forbid_intent",
                        RuleCheckSeverity.REQUIRED, Map.of("intentKinds", List.of("BUSINESS_DECISION")))));
        RulePromotionPreview preview = service.preview(approved);
        RulePromotionCandidate changedSelector = semanticCandidate(
                List.of(selectorGroup("services",
                        new RuleSelectorPredicate(SelectorPolarity.INCLUDE, SelectorField.PATH,
                                SelectorOperator.GLOB, List.of("**/*Service.java")))),
                approved.checks());
        RulePromotionCandidate changedCheck = semanticCandidate(approved.selectorGroups(),
                List.of(new RuleCheckSpec(RuleCheckPhase.PLAN, "forbid_intent",
                        RuleCheckSeverity.REQUIRED, Map.of("intentKinds", List.of("DOMAIN_CALCULATION")))));

        assertThatThrownBy(() -> service.promote(request(changedSelector, preview)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("approval content changed");
        assertThatThrownBy(() -> service.promote(request(changedCheck, preview)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("approval content changed");
        verifyNoInteractions(repository, promotionPort);
    }

    @Test
    void promotionRejectsConfirmationCardOrWorkflowReplay() {
        RulePromotionCandidate candidate = contextCandidate(List.of(target(BindingKind.FILE, "A.java")));
        RulePromotionPreview preview = service.preview(candidate);

        PromotionRequest wrongCard = new PromotionRequest(candidate, preview.approvalContentHash(),
                "wrong-card-hash", preview.workflowContractVersion(), approval());
        assertThatThrownBy(() -> service.promote(wrongCard))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("confirmation card changed");

        PromotionRequest wrongContract = new PromotionRequest(candidate, preview.approvalContentHash(),
                preview.confirmationCardHash(), "obsolete-contract", approval());
        assertThatThrownBy(() -> service.promote(wrongContract))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("workflow contract");
        verifyNoInteractions(repository, promotionPort);
    }

    @Test
    void promotionRejectsMissingExplicitHumanApprovalBeforePersistence() {
        RulePromotionCandidate candidate = contextCandidate(List.of(target(BindingKind.FILE, "A.java")));
        RulePromotionPreview preview = service.preview(candidate);
        PromotionRequest request = new PromotionRequest(candidate, preview.approvalContentHash(),
                preview.confirmationCardHash(), preview.workflowContractVersion(),
                new RuleHumanApprovalEvidence("alex", "turn-42", "maybe", false, 0.99));

        assertThatThrownBy(() -> service.promote(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("explicit human approval");
        verifyNoInteractions(repository, promotionPort);
    }

    @Test
    void rolloutFlagAllowsPreviewButRejectsEveryMutation() {
        RulePromotionService disabled = new RulePromotionService(repository, promotionPort,
                new RulesProperties(false, 20, 256, 4096, 200), new ObjectMapper(),
                List.of(new DiffRegexRuleDetector(new UnifiedDiffParser())));
        RulePromotionCandidate candidate = contextCandidate(List.of(target(BindingKind.FILE, "A.java")));
        RulePromotionPreview preview = disabled.preview(candidate);

        assertThat(preview.approvalContentHash()).hasSize(64);
        assertThatThrownBy(() -> disabled.promote(request(candidate, preview)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("disabled");
        verifyNoInteractions(repository, promotionPort);
    }

    @Test
    void previewRejectsGateWithoutDeterministicTargetedDetector() {
        RulePromotionCandidate noDetector = new RulePromotionCandidate(null, null, "AI_ORCHESTRATION",
                "No business logic", null, RuleEnforcement.GATE, false, null, null,
                List.of(target(BindingKind.FILE, "AController.java")), RuleProvenance.DIRECT_HUMAN_POLICY);
        RulePromotionCandidate noBinding = new RulePromotionCandidate(null, null, "AI_ORCHESTRATION",
                "No business logic", null, RuleEnforcement.GATE, false, DiffRegexRuleDetector.TYPE,
                Map.of("pattern", "danger"), List.of(), RuleProvenance.DIRECT_HUMAN_POLICY);
        RulePromotionCandidate appliesAll = new RulePromotionCandidate(null, null, "AI_ORCHESTRATION",
                "No business logic", null, RuleEnforcement.GATE, true, DiffRegexRuleDetector.TYPE,
                Map.of("pattern", "danger"), List.of(target(BindingKind.FILE, "AController.java")),
                RuleProvenance.DIRECT_HUMAN_POLICY);

        assertThatThrownBy(() -> service.preview(noDetector))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("deterministic detector");
        assertThatThrownBy(() -> service.preview(noBinding))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("explicit target");
        assertThatThrownBy(() -> service.preview(appliesAll))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("applies-all");
        verifyNoInteractions(repository, promotionPort);
    }

    @Test
    void previewRejectsUnknownOrNonRe2DetectorConfiguration() {
        RulePromotionCandidate unknown = new RulePromotionCandidate(null, null, "AI_ORCHESTRATION",
                "No business logic", null, RuleEnforcement.GATE, false, "unknown_detector",
                Map.of("pattern", "danger"), List.of(target(BindingKind.FILE, "AController.java")),
                RuleProvenance.DIRECT_HUMAN_POLICY);
        RulePromotionCandidate backtrackingOnly = gateCandidate(
                Map.of("pattern", "(?<name>a)\\k<name>"),
                List.of(target(BindingKind.FILE, "AController.java")));

        assertThatThrownBy(() -> service.preview(unknown))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown detector");
        assertThatThrownBy(() -> service.preview(backtrackingOnly))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("RE2-compatible");
        verifyNoInteractions(repository, promotionPort);
    }

    @Test
    void directHumanPromotionWritesNoMemoryAndPersistsApprovalHashes() {
        RulePromotionCandidate candidate = contextCandidate(List.of(target(BindingKind.FILE, "A.java")));
        RulePromotionPreview preview = service.preview(candidate);

        RuleVersion promoted = service.promote(request(candidate, preview));

        assertThat(promoted.originMemoryId()).isNull();
        assertThat(promoted.originContentHash()).isNull();
        assertThat(promoted.approvalContentHash()).isEqualTo(preview.approvalContentHash());
        assertThat(promoted.confirmationCardHash()).isEqualTo(preview.confirmationCardHash());
        assertThat(promoted.humanRawTextHash()).hasSize(64);
        assertThat(promoted.workflowContractVersion()).isEqualTo(RulePromotionService.WORKFLOW_CONTRACT_VERSION);
        verify(promotionPort, never()).activatePromotedRule(any(), any(), anyInt(),
                any(), any(), any());
        verify(repository).insertLifecycleEvent(any(RuleLifecycleEvent.class));
    }

    @Test
    void exactPromotionReplayReturnsTheExistingImmutableVersionWithoutSecondMutation() {
        RulePromotionCandidate candidate = contextCandidate(List.of(target(BindingKind.FILE, "A.java")));
        RulePromotionPreview preview = service.preview(candidate);
        RuleVersion existing = storedVersion(UUID.randomUUID(), 1, preview.versionContentHash());
        RuleLifecycleEvent lifecycle = new RuleLifecycleEvent(UUID.randomUUID(), existing.ruleId(), 1,
                RuleLifecycleAction.PROMOTED, "alex", "turn-1", "a".repeat(64), null,
                preview.approvalContentHash(), Instant.now());
        when(repository.findLifecycleEvent(
                RuleLifecycleAction.PROMOTED, preview.approvalContentHash()))
                .thenReturn(java.util.Optional.of(lifecycle));
        when(repository.findVersion(existing.ruleId(), 1)).thenReturn(java.util.Optional.of(existing));

        RuleVersion replayed = service.promote(request(candidate, preview));

        assertThat(replayed).isSameAs(existing);
        verify(repository).lockEffectiveSequences(List.of("AI_ORCHESTRATION"));
        verify(repository, never()).insertDefinition(any());
        verifyNoInteractions(promotionPort);
    }

    @Test
    void memoryBackedPromotionCallsVerifiedPortWithExactTuple() {
        UUID memoryId = UUID.randomUUID();
        String originHash = "a".repeat(64);
        RulePromotionCandidate candidate = new RulePromotionCandidate(memoryId, originHash,
                "AI_ORCHESTRATION", "Controllers must stay thin", "Boundary only",
                RuleEnforcement.CONTEXT, false, null, null,
                List.of(target(BindingKind.PATH_GLOB, "**/*Controller.java")), RuleProvenance.HUMAN);
        RulePromotionPreview preview = service.preview(candidate);

        RuleVersion promoted = service.promote(request(candidate, preview));

        assertThat(promoted.originMemoryId()).isEqualTo(memoryId);
        verify(promotionPort).activatePromotedRule(memoryId, promoted.ruleId(), 1, originHash,
                preview.approvalContentHash(), preview.confirmationCardHash());
    }

    @Test
    void rejectsGlobPromotionAtThePerProjectActiveLimit() {
        RulePromotionCandidate candidate = contextCandidate(
                List.of(target(BindingKind.PATH_GLOB, "**/*Controller.java")));
        RulePromotionPreview preview = service.preview(candidate);
        when(repository.maxProjectedActiveGlobRules("AI_ORCHESTRATION", null)).thenReturn(201);

        assertThatThrownBy(() -> service.promote(request(candidate, preview)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("active glob rule limit");

        verify(repository).lockEffectiveSequences(List.of("AI_ORCHESTRATION"));
        verify(repository).maxProjectedActiveGlobRules("AI_ORCHESTRATION", null);
        verify(repository, never()).insertDefinition(any());
    }

    @Test
    void nextVersionRetainsItsExactRevisionMemoryOrigin() {
        UUID ruleId = UUID.randomUUID();
        UUID firstMemoryId = UUID.randomUUID();
        UUID revisionMemoryId = UUID.randomUUID();
        String firstOriginHash = "a".repeat(64);
        String revisionOriginHash = "b".repeat(64);
        RuleDefinition definition = new RuleDefinition(ruleId, firstMemoryId, "AI_ORCHESTRATION", 1,
                RuleStatus.ACTIVE, Instant.now(), Instant.now());
        RuleVersion current = storedMemoryVersion(
                ruleId, 1, currentHash(), firstMemoryId, firstOriginHash);
        when(repository.findDefinition(ruleId)).thenReturn(java.util.Optional.of(definition));
        when(repository.findVersion(ruleId, 1)).thenReturn(java.util.Optional.of(current));
        when(repository.advanceDefinition(ruleId, 1, 2, revisionMemoryId,
                "AI_ORCHESTRATION", RuleStatus.ACTIVE)).thenReturn(true);
        RulePromotionCandidate revision = new RulePromotionCandidate(revisionMemoryId, revisionOriginHash,
                "AI_ORCHESTRATION", "Controllers must delegate all business logic", "Revision evidence",
                RuleEnforcement.CONTEXT, false, null, null,
                List.of(target(BindingKind.FILE, "A.java")), RuleProvenance.HUMAN);
        RulePromotionPreview preview = service.previewVersion(ruleId, 1, currentHash(), revision);

        RuleVersion next = service.promoteVersion(ruleId, 1, currentHash(), request(revision, preview));

        assertThat(next.originMemoryId()).isEqualTo(revisionMemoryId);
        assertThat(next.originContentHash()).isEqualTo(revisionOriginHash);
        verify(repository).advanceDefinition(ruleId, 1, 2, revisionMemoryId,
                "AI_ORCHESTRATION", RuleStatus.ACTIVE);
        verify(promotionPort).activatePromotedRule(revisionMemoryId, ruleId, 2, revisionOriginHash,
                preview.approvalContentHash(), preview.confirmationCardHash());
    }

    @Test
    void promotesNextVersionWithCompareAndSetAndRejectsStaleCurrentHash() {
        UUID ruleId = UUID.randomUUID();
        RuleDefinition definition = new RuleDefinition(ruleId, null, "AI_ORCHESTRATION", 1,
                RuleStatus.ACTIVE, Instant.now(), Instant.now());
        RuleVersion current = storedVersion(ruleId, 1, currentHash());
        when(repository.findDefinition(ruleId)).thenReturn(java.util.Optional.of(definition));
        when(repository.findVersion(ruleId, 1)).thenReturn(java.util.Optional.of(current));
        when(repository.advanceDefinition(ruleId, 1, 2, null, "AI_ORCHESTRATION", RuleStatus.ACTIVE))
                .thenReturn(true);
        RulePromotionCandidate candidate = contextCandidate(List.of(target(BindingKind.FILE, "A.java")));
        RulePromotionPreview preview = service.previewVersion(ruleId, 1, currentHash(), candidate);

        RuleVersion next = service.promoteVersion(ruleId, 1, currentHash(), request(candidate, preview));

        assertThat(next.version()).isEqualTo(2);
        verify(repository).advanceDefinition(ruleId, 1, 2, null, "AI_ORCHESTRATION", RuleStatus.ACTIVE);
        verify(repository).lockEffectiveSequences(List.of("AI_ORCHESTRATION"));

        String staleHash = "e".repeat(64);
        RulePromotionPreview stalePreview = service.previewVersion(ruleId, 1, staleHash, candidate);
        assertThatThrownBy(() -> service.promoteVersion(ruleId, 1, staleHash,
                request(candidate, stalePreview)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("stale current rule content");
    }

    @Test
    void directRuleCannotAcquireMemoryOriginOnLaterVersion() {
        UUID ruleId = UUID.randomUUID();
        RuleDefinition definition = new RuleDefinition(ruleId, null, "AI_ORCHESTRATION", 1,
                RuleStatus.ACTIVE, Instant.now(), Instant.now());
        when(repository.findDefinition(ruleId)).thenReturn(java.util.Optional.of(definition));
        when(repository.findVersion(ruleId, 1)).thenReturn(java.util.Optional.of(storedVersion(ruleId, 1,
                currentHash())));
        RulePromotionCandidate memoryCandidate = new RulePromotionCandidate(UUID.randomUUID(), "b".repeat(64),
                "AI_ORCHESTRATION", "Statement", null, RuleEnforcement.CONTEXT, false,
                null, null, List.of(target(BindingKind.FILE, "A.java")), RuleProvenance.HUMAN);
        RulePromotionPreview preview = service.previewVersion(ruleId, 1, currentHash(), memoryCandidate);

        assertThatThrownBy(() -> service.promoteVersion(ruleId, 1, currentHash(),
                request(memoryCandidate, preview)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("direct rule cannot acquire an origin memory");
    }

    @Test
    void deprecatesWithExactCurrentVersionHashAndHumanCard() {
        UUID ruleId = UUID.randomUUID();
        RuleDefinition definition = new RuleDefinition(ruleId, null, "AI_ORCHESTRATION", 2,
                RuleStatus.ACTIVE, Instant.now(), Instant.now());
        RuleVersion current = storedVersion(ruleId, 2, currentHash());
        when(repository.findDefinition(ruleId)).thenReturn(java.util.Optional.of(definition));
        when(repository.findVersion(ruleId, 2)).thenReturn(java.util.Optional.of(current));
        when(repository.deprecateDefinition(ruleId, 2)).thenReturn(true);
        RuleDeprecationPreview preview = service.previewDeprecation(ruleId, 2, currentHash(),
                "Superseded by architecture policy");
        RuleDeprecationRequest request = new RuleDeprecationRequest(ruleId, 2, currentHash(),
                "Superseded by architecture policy", preview.approvalContentHash(),
                preview.confirmationCardHash(), preview.workflowContractVersion(), approval());

        RuleDefinition deprecated = service.deprecate(request);

        assertThat(deprecated.status()).isEqualTo(RuleStatus.DEPRECATED);
        verify(repository).deprecateDefinition(ruleId, 2);
        ArgumentCaptor<RuleLifecycleEvent> event = ArgumentCaptor.forClass(RuleLifecycleEvent.class);
        verify(repository).insertLifecycleEvent(event.capture());
        assertThat(event.getValue().action()).isEqualTo(RuleLifecycleAction.DEPRECATED);
        assertThat(event.getValue().reason()).isEqualTo("Superseded by architecture policy");
    }

    private PromotionRequest request(RulePromotionCandidate candidate, RulePromotionPreview preview) {
        return new PromotionRequest(candidate, preview.approvalContentHash(), preview.confirmationCardHash(),
                preview.workflowContractVersion(), approval());
    }

    private RuleHumanApprovalEvidence approval() {
        return new RuleHumanApprovalEvidence("alex", "turn-42", "Bu exact kuralı onaylıyorum", true, 0.99);
    }

    private RulePromotionCandidate contextCandidate(List<RulePromotionCandidate.TargetBindingRequest> targets) {
        return new RulePromotionCandidate(null, null, "AI_ORCHESTRATION",
                "Controllers must not contain business logic", "Keep adapters thin",
                RuleEnforcement.CONTEXT, false, null, null, targets, RuleProvenance.DIRECT_HUMAN_POLICY);
    }

    private RulePromotionCandidate gateCandidate(Map<String, Object> config,
            List<RulePromotionCandidate.TargetBindingRequest> targets) {
        return new RulePromotionCandidate(null, null, "AI_ORCHESTRATION",
                "Forbidden pattern must not be added", "Deterministic code gate",
                RuleEnforcement.GATE, false, DiffRegexRuleDetector.TYPE, config, targets,
                RuleProvenance.DIRECT_HUMAN_POLICY);
    }

    private RulePromotionCandidate semanticCandidate(List<RuleSelectorGroup> selectorGroups,
            List<RuleCheckSpec> checks) {
        return new RulePromotionCandidate(null, null, "AI_ORCHESTRATION",
                "Controllers must not contain business logic", "Keep adapters thin",
                RuleEnforcement.CONTEXT, false, null, null, List.of(), selectorGroups, checks,
                RuleProvenance.DIRECT_HUMAN_POLICY);
    }

    private RuleSelectorGroup selectorGroup(String key, RuleSelectorPredicate... predicates) {
        return new RuleSelectorGroup(key, List.of(predicates));
    }

    private RulePromotionCandidate.TargetBindingRequest target(BindingKind kind, String key) {
        return new RulePromotionCandidate.TargetBindingRequest(kind, key);
    }

    private RuleVersion storedVersion(UUID ruleId, int version, String contentHash) {
        return new RuleVersion(ruleId, version, "old statement", null, RuleEnforcement.CONTEXT,
                false, null, null, null, contentHash, null, null, "approval-hash", "card-hash",
                "human-hash", RulePromotionService.WORKFLOW_CONTRACT_VERSION,
                RuleProvenance.DIRECT_HUMAN_POLICY, "alex", Instant.now(), "turn-1", Instant.now());
    }

    private RuleVersion storedMemoryVersion(UUID ruleId, int version, String contentHash,
            UUID originMemoryId, String originContentHash) {
        return new RuleVersion(ruleId, version, "old statement", null, RuleEnforcement.CONTEXT,
                false, null, null, null, contentHash, originMemoryId, originContentHash,
                "approval-hash", "card-hash", "human-hash",
                RulePromotionService.WORKFLOW_CONTRACT_VERSION,
                RuleProvenance.HUMAN, "alex", Instant.now(), "turn-1", Instant.now());
    }

    private String currentHash() {
        return "c".repeat(64);
    }
}
