package com.mbworldwideapps.aiorchestration.modules.plancompiler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.mbworldwideapps.aiorchestration.modules.plancompiler.AnalystPlanSchemaV2.ArtifactKind;
import com.mbworldwideapps.aiorchestration.modules.plancompiler.AnalystPlanSchemaV2.IntentKind;
import com.mbworldwideapps.aiorchestration.modules.plancompiler.AnalystPlanSchemaV2.Language;
import com.mbworldwideapps.aiorchestration.modules.plancompiler.AnalystPlanSchemaV2.Operation;
import com.mbworldwideapps.aiorchestration.modules.plancompiler.AnalystPlanSchemaV2.ScannerEvidenceStatus;
import com.mbworldwideapps.aiorchestration.modules.plancompiler.AnalystPlanSchemaV2.SourceSet;
import com.mbworldwideapps.aiorchestration.modules.plancompiler.AnalystPlanSchemaV2.ValidationKind;
import com.mbworldwideapps.aiorchestration.modules.rules.RuleCheckPhase;
import com.mbworldwideapps.aiorchestration.modules.rules.RuleCheckSeverity;
import com.mbworldwideapps.aiorchestration.modules.rules.RuleCheckSpec;
import org.junit.jupiter.api.Test;

class PlanRuleCheckerRegistryTest {

    private final PlanRuleCheckerRegistry registry = new PlanRuleCheckerRegistry();
    private final AnalystPlanSchemaV2 plan = referencePlan();

    @Test
    void intentAndDependencyCheckersApplyPerSelectedTarget() {
        PlanCheckContext context = context(PlanEvidenceIndex.empty());

        assertThat(evaluate("forbid_intent", Map.of(
                "intentKinds", List.of("BUSINESS_DECISION")), context).status())
                .isEqualTo(PlanRuleCheckStatus.CONFLICT);
        assertThat(evaluate("require_intent", Map.of(
                "requiredAnyOf", List.of("DELEGATE_TO_SERVICE")), context).status())
                .isEqualTo(PlanRuleCheckStatus.CONFLICT);
        assertThat(evaluate("require_intent", Map.of(
                "requiredAnyOf", List.of("BUSINESS_DECISION")), context).status())
                .isEqualTo(PlanRuleCheckStatus.COMPATIBLE);
        assertThat(evaluate("forbid_dependency", Map.of(
                "dependencies", List.of("repository.account")), context).status())
                .isEqualTo(PlanRuleCheckStatus.CONFLICT);
    }

    @Test
    void missingSelfReportedIntentEvidenceNeverSilentlyPassesARequiredChecker() {
        PlanCheckContext noIntentTarget = new PlanCheckContext(plan, Set.of("target-test"), PlanEvidenceIndex.empty());

        PlanRuleCheckResult result = evaluate("forbid_intent", Map.of(
                "intentKinds", List.of("BUSINESS_DECISION")), noIntentTarget);

        assertThat(result.status()).isEqualTo(PlanRuleCheckStatus.INCOMPLETE_EVIDENCE);
        assertThat(result.code()).isEqualTo("INTENT_EVIDENCE_MISSING");
    }

    @Test
    void requiredTestKindChecksValidationAndRelatedTestTargetCoverage() {
        PlanCheckContext context = context(PlanEvidenceIndex.empty());

        assertThat(evaluate("require_test_kind", Map.of(
                "validationKinds", List.of("UNIT_TEST"), "requireTestTarget", true), context).status())
                .isEqualTo(PlanRuleCheckStatus.COMPATIBLE);
        assertThat(evaluate("require_test_kind", Map.of(
                "validationKinds", List.of("CONCURRENCY_TEST"), "requireTestTarget", true), context).status())
                .isEqualTo(PlanRuleCheckStatus.CONFLICT);
    }

    @Test
    void layerCheckerDistinguishesConflictFromUnavailableRepositoryEvidence() {
        PlanEvidenceIndex withEdge = new PlanEvidenceIndex(
                Map.of(),
                List.of(new LayerEdgeEvidence("target-prod", "web", "data")),
                true,
                Map.of());
        Map<String, Object> config = Map.of(
                "fromLayers", List.of("web"), "toLayers", List.of("data"));

        assertThat(evaluate("forbid_layer_edge", config, context(withEdge)).status())
                .isEqualTo(PlanRuleCheckStatus.CONFLICT);
        assertThat(evaluate("forbid_layer_edge", config, context(PlanEvidenceIndex.empty())).status())
                .isEqualTo(PlanRuleCheckStatus.INCOMPLETE_EVIDENCE);
    }

    @Test
    void roleCheckerPreservesSelfReportTrustAndFailsOnRepositoryContradiction() {
        Map<String, Object> config = Map.of("roles", List.of("web.http-controller"));
        PlanRuleCheckResult selfReported = evaluate(
                "require_target_role", config, context(PlanEvidenceIndex.empty()));
        assertThat(selfReported.status()).isEqualTo(PlanRuleCheckStatus.COMPATIBLE);
        assertThat(selfReported.evidenceTrust()).contains(EvidenceTrust.SELF_REPORTED)
                .doesNotContain(EvidenceTrust.AST_VERIFIED);

        PlanEvidenceIndex contradicted = new PlanEvidenceIndex(
                Map.of("target-prod", Set.of("application.service")), List.of(), false,
                Map.of("target-prod", "repository role contradicts plan role hint"));
        PlanRuleCheckResult mismatch = evaluate(
                "require_target_role", config, context(contradicted));
        assertThat(mismatch.status()).isEqualTo(PlanRuleCheckStatus.CONFLICT);
        assertThat(mismatch.code()).isEqualTo("TAG_EVIDENCE_MISMATCH");
    }

    @Test
    void pathConventionUsesTheSameBoundedGlobContract() {
        PlanCheckContext context = context(PlanEvidenceIndex.empty());

        assertThat(evaluate("path_convention", Map.of(
                "allowedGlobs", List.of("**/*Controller.java"),
                "forbiddenGlobs", List.of("**/internal/**")), context).status())
                .isEqualTo(PlanRuleCheckStatus.COMPATIBLE);
        assertThat(evaluate("path_convention", Map.of(
                "allowedGlobs", List.of("**/*.java"),
                "forbiddenGlobs", List.of("**/*Controller.java")), context).status())
                .isEqualTo(PlanRuleCheckStatus.CONFLICT);
    }

    @Test
    void semanticAndUnknownCheckersFailClosedInsteadOfBecomingNoMatch() {
        PlanRuleCheckResult semantic = evaluate("semantic_plan_review", Map.of(
                "contractId", "controller-business-logic/v1",
                "criteria", List.of("business decisions must be delegated")),
                context(PlanEvidenceIndex.empty()));
        assertThat(semantic.status()).isEqualTo(PlanRuleCheckStatus.UNAVAILABLE);

        RuleCheckSpec unknown = spec("future_checker", Map.of());
        PlanRuleCheckResult unknownResult = registry.evaluate(unknown, context(PlanEvidenceIndex.empty()));
        assertThat(unknownResult.status()).isEqualTo(PlanRuleCheckStatus.UNAVAILABLE);
        assertThat(unknownResult.code()).isEqualTo("UNKNOWN_CHECKER");
        assertThatThrownBy(() -> registry.validateForPromotion(unknown))
                .hasMessageContaining("unknown PLAN checker");
    }

    @Test
    void configSchemasRejectUnknownKeysAndRegistryRejectsDuplicateImplementations() {
        assertThatThrownBy(() -> registry.validateForPromotion(spec(
                "forbid_intent", Map.of("intentKinds", List.of("BUSINESS_DECISION"), "typo", true))))
                .hasMessageContaining("unknown config key");
        PlanRuleChecker duplicate = new PlanRuleChecker() {
            @Override
            public PlanCheckerContract contract() {
                return registry.contract("forbid_intent").orElseThrow();
            }

            @Override
            public Map<String, Object> validateConfig(Map<String, Object> config) {
                return config;
            }

            @Override
            public PlanRuleCheckResult evaluate(RuleCheckSpec check, PlanCheckContext context) {
                throw new UnsupportedOperationException();
            }
        };
        assertThatThrownBy(() -> new PlanRuleCheckerRegistry(List.of(duplicate, duplicate)))
                .hasMessageContaining("duplicate PLAN checker");
    }

    @Test
    void planResultsCannotClaimDiffAstOrTestVerification() {
        assertThatThrownBy(() -> new PlanRuleCheckResult(
                PlanRuleCheckStatus.COMPATIBLE, "BAD_TRUST", "bad trust", List.of("target-prod"),
                Set.of(EvidenceTrust.AST_VERIFIED)))
                .hasMessageContaining("plan checker cannot claim final-code evidence");
    }

    private PlanRuleCheckResult evaluate(
            String checkerType, Map<String, Object> config, PlanCheckContext context) {
        RuleCheckSpec spec = spec(checkerType, config);
        registry.validateForPromotion(spec);
        return registry.evaluate(spec, context);
    }

    private static RuleCheckSpec spec(String checkerType, Map<String, Object> config) {
        return new RuleCheckSpec(RuleCheckPhase.PLAN, checkerType, RuleCheckSeverity.REQUIRED, config);
    }

    private PlanCheckContext context(PlanEvidenceIndex evidence) {
        return new PlanCheckContext(plan, Set.of("target-prod"), evidence);
    }

    private static AnalystPlanSchemaV2 referencePlan() {
        AnalystPlanSchemaV2.Project project = new AnalystPlanSchemaV2.Project(
                "project-card", "HCE_CMS",
                new AnalystPlanSchemaV2.Baseline(
                        "a".repeat(64), "b".repeat(40), "c".repeat(64), "scan-1",
                        ScannerEvidenceStatus.PRESENT));
        AnalystPlanSchemaV2.Target production = new AnalystPlanSchemaV2.Target(
                "target-prod", "project-card", Operation.CREATE,
                "src/main/java/com/acme/AccountController.java", null, "AccountController",
                Language.JAVA, SourceSet.MAIN, ArtifactKind.CODE, false,
                List.of("web.http-controller"), List.of("org.springframework.web.bind.annotation.RestController"),
                List.of("intent-business"), 0.9, List.of("requested controller"));
        AnalystPlanSchemaV2.Target test = new AnalystPlanSchemaV2.Target(
                "target-test", "project-card", Operation.CREATE,
                "src/test/java/com/acme/AccountControllerTest.java", null, "AccountControllerTest",
                Language.JAVA, SourceSet.TEST, ArtifactKind.CODE, false,
                List.of("test.unit"), List.of(), List.of(), 0.9, List.of("planned unit test"));
        AnalystPlanSchemaV2.Intent intent = new AnalystPlanSchemaV2.Intent(
                "intent-business", IntentKind.BUSINESS_DECISION, "calculate account eligibility",
                List.of("target-prod"), List.of("repository.account"));
        AnalystPlanSchemaV2.Step step = new AnalystPlanSchemaV2.Step(
                "step-1", "add controller", List.of("target-prod"), List.of("intent-business"));
        AnalystPlanSchemaV2.ValidationItem validation = new AnalystPlanSchemaV2.ValidationItem(
                "validation-unit", ValidationKind.UNIT_TEST,
                List.of("target-prod", "target-test"), "controller delegation unit test");
        return new AnalystPlanSchemaV2(
                2, UUID.randomUUID(), 1, "Add account endpoint", List.of(project),
                List.of(production, test), List.of(intent), List.of(step), List.of(),
                List.of(validation), List.of(), List.of(), 0.9);
    }
}
