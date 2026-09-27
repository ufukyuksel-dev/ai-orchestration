package com.mbworldwideapps.aiorchestration.modules.plancompiler;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.mbworldwideapps.aiorchestration.modules.plancompiler.AnalystPlanSchemaV2.Intent;
import com.mbworldwideapps.aiorchestration.modules.plancompiler.AnalystPlanSchemaV2.IntentKind;
import com.mbworldwideapps.aiorchestration.modules.plancompiler.AnalystPlanSchemaV2.SourceSet;
import com.mbworldwideapps.aiorchestration.modules.plancompiler.AnalystPlanSchemaV2.Target;
import com.mbworldwideapps.aiorchestration.modules.plancompiler.AnalystPlanSchemaV2.ValidationItem;
import com.mbworldwideapps.aiorchestration.modules.plancompiler.AnalystPlanSchemaV2.ValidationKind;
import com.mbworldwideapps.aiorchestration.modules.rules.RuleCheckSpec;

final class StandardPlanRuleCheckers {

    private StandardPlanRuleCheckers() {
    }

    static List<PlanRuleChecker> all() {
        return List.of(
                new ForbidIntentChecker(),
                new RequireIntentChecker(),
                new ForbidDependencyChecker(),
                new RequireTestKindChecker(),
                new ForbidLayerEdgeChecker(),
                new RequireTargetRoleChecker(),
                new PathConventionChecker(),
                new SemanticPlanReviewChecker());
    }

    private abstract static class BaseChecker implements PlanRuleChecker {
        private final PlanCheckerContract contract;

        BaseChecker(String type, boolean deterministic) {
            this.contract = new PlanCheckerContract(type, "1", "1", deterministic);
        }

        @Override
        public final PlanCheckerContract contract() {
            return contract;
        }

        PlanRuleCheckResult result(
                PlanRuleCheckStatus status, String code, String message,
                Collection<String> targetIds, EvidenceTrust... additionalTrust) {
            Set<EvidenceTrust> trust = new LinkedHashSet<>();
            trust.add(EvidenceTrust.RULE_DERIVED);
            for (EvidenceTrust evidenceTrust : additionalTrust) {
                trust.add(evidenceTrust);
            }
            return new PlanRuleCheckResult(status, code, message,
                    targetIds.stream().distinct().sorted().toList(), trust);
        }

        PlanRuleCheckResult compatible(String code, PlanCheckContext context, EvidenceTrust... trust) {
            return result(PlanRuleCheckStatus.COMPATIBLE, code, "plan evidence satisfies the rule check",
                    context.applicableTargetIds(), trust);
        }
    }

    private static final class ForbidIntentChecker extends BaseChecker {
        ForbidIntentChecker() {
            super("forbid_intent", true);
        }

        @Override
        public Map<String, Object> validateConfig(Map<String, Object> config) {
            Config.requireOnly(config, "intentKinds");
            return Map.of("intentKinds", Config.enumNames(config, "intentKinds", IntentKind.class, false));
        }

        @Override
        public PlanRuleCheckResult evaluate(RuleCheckSpec check, PlanCheckContext context) {
            Set<String> forbidden = Set.copyOf(Config.strings(check.config(), "intentKinds", false));
            TargetIntentEvidence evidence = targetIntents(context);
            if (!evidence.missingTargets().isEmpty()) {
                return result(PlanRuleCheckStatus.INCOMPLETE_EVIDENCE, "INTENT_EVIDENCE_MISSING",
                        "required target intent evidence is missing", evidence.missingTargets(),
                        EvidenceTrust.SELF_REPORTED);
            }
            List<String> conflicts = evidence.byTarget().entrySet().stream()
                    .filter(entry -> entry.getValue().stream()
                            .map(intent -> intent.kind().name()).anyMatch(forbidden::contains))
                    .map(Map.Entry::getKey).toList();
            return conflicts.isEmpty()
                    ? compatible("FORBIDDEN_INTENT_ABSENT", context, EvidenceTrust.SELF_REPORTED)
                    : result(PlanRuleCheckStatus.CONFLICT, "FORBIDDEN_INTENT_PRESENT",
                            "plan contains an intent forbidden by the rule", conflicts,
                            EvidenceTrust.SELF_REPORTED);
        }
    }

    private static final class RequireIntentChecker extends BaseChecker {
        RequireIntentChecker() {
            super("require_intent", true);
        }

        @Override
        public Map<String, Object> validateConfig(Map<String, Object> config) {
            Config.requireOnly(config, "requiredAnyOf", "whenAnyOf");
            Map<String, Object> normalized = new LinkedHashMap<>();
            normalized.put("requiredAnyOf", Config.enumNames(config, "requiredAnyOf", IntentKind.class, false));
            normalized.put("whenAnyOf", Config.enumNames(config, "whenAnyOf", IntentKind.class, true));
            return Map.copyOf(normalized);
        }

        @Override
        public PlanRuleCheckResult evaluate(RuleCheckSpec check, PlanCheckContext context) {
            Set<String> required = Set.copyOf(Config.strings(check.config(), "requiredAnyOf", false));
            Set<String> triggers = Set.copyOf(Config.strings(check.config(), "whenAnyOf", true));
            TargetIntentEvidence evidence = targetIntents(context);
            if (!evidence.missingTargets().isEmpty()) {
                return result(PlanRuleCheckStatus.INCOMPLETE_EVIDENCE, "INTENT_EVIDENCE_MISSING",
                        "required target intent evidence is missing", evidence.missingTargets(),
                        EvidenceTrust.SELF_REPORTED);
            }
            List<String> applicable = new ArrayList<>();
            List<String> conflicts = new ArrayList<>();
            evidence.byTarget().forEach((targetId, intents) -> {
                Set<String> kinds = intents.stream().map(intent -> intent.kind().name())
                        .collect(java.util.stream.Collectors.toSet());
                if (triggers.isEmpty() || kinds.stream().anyMatch(triggers::contains)) {
                    applicable.add(targetId);
                    if (kinds.stream().noneMatch(required::contains)) {
                        conflicts.add(targetId);
                    }
                }
            });
            if (applicable.isEmpty()) {
                return result(PlanRuleCheckStatus.NOT_APPLICABLE, "REQUIRED_INTENT_TRIGGER_ABSENT",
                        "conditional required intent trigger is absent", List.of(),
                        EvidenceTrust.SELF_REPORTED);
            }
            return conflicts.isEmpty()
                    ? compatible("REQUIRED_INTENT_PRESENT", context, EvidenceTrust.SELF_REPORTED)
                    : result(PlanRuleCheckStatus.CONFLICT, "REQUIRED_INTENT_MISSING",
                            "plan target is missing a required intent", conflicts,
                            EvidenceTrust.SELF_REPORTED);
        }
    }

    private static final class ForbidDependencyChecker extends BaseChecker {
        ForbidDependencyChecker() {
            super("forbid_dependency", true);
        }

        @Override
        public Map<String, Object> validateConfig(Map<String, Object> config) {
            Config.requireOnly(config, "dependencies");
            return Map.of("dependencies", Config.strings(config, "dependencies", false));
        }

        @Override
        public PlanRuleCheckResult evaluate(RuleCheckSpec check, PlanCheckContext context) {
            Set<String> forbidden = Set.copyOf(Config.strings(check.config(), "dependencies", false));
            TargetIntentEvidence evidence = targetIntents(context);
            if (!evidence.missingTargets().isEmpty()) {
                return result(PlanRuleCheckStatus.INCOMPLETE_EVIDENCE, "DEPENDENCY_EVIDENCE_MISSING",
                        "target dependency evidence is missing", evidence.missingTargets(),
                        EvidenceTrust.SELF_REPORTED);
            }
            List<String> conflicts = evidence.byTarget().entrySet().stream()
                    .filter(entry -> entry.getValue().stream().flatMap(intent -> intent.dependencies().stream())
                            .anyMatch(forbidden::contains))
                    .map(Map.Entry::getKey).toList();
            return conflicts.isEmpty()
                    ? compatible("FORBIDDEN_DEPENDENCY_ABSENT", context, EvidenceTrust.SELF_REPORTED)
                    : result(PlanRuleCheckStatus.CONFLICT, "FORBIDDEN_DEPENDENCY_PRESENT",
                            "plan contains a dependency forbidden by the rule", conflicts,
                            EvidenceTrust.SELF_REPORTED);
        }
    }

    private static final class RequireTestKindChecker extends BaseChecker {
        RequireTestKindChecker() {
            super("require_test_kind", true);
        }

        @Override
        public Map<String, Object> validateConfig(Map<String, Object> config) {
            Config.requireOnly(config, "validationKinds", "requireTestTarget");
            return Map.of(
                    "validationKinds", Config.enumNames(config, "validationKinds", ValidationKind.class, false),
                    "requireTestTarget", Config.bool(config, "requireTestTarget"));
        }

        @Override
        public PlanRuleCheckResult evaluate(RuleCheckSpec check, PlanCheckContext context) {
            Set<String> kinds = Set.copyOf(Config.strings(check.config(), "validationKinds", false));
            boolean requireTestTarget = Config.bool(check.config(), "requireTestTarget");
            Map<String, Target> allTargets = context.plan().targets().stream()
                    .collect(java.util.stream.Collectors.toMap(Target::targetId, target -> target));
            List<Target> production = context.applicableTargets().stream()
                    .filter(target -> target.sourceSet() != SourceSet.TEST
                            && target.sourceSet() != SourceSet.GENERATED)
                    .toList();
            if (production.isEmpty()) {
                return result(PlanRuleCheckStatus.NOT_APPLICABLE, "NO_PRODUCTION_TARGET",
                        "test-kind rule has no production target", List.of(), EvidenceTrust.SELF_REPORTED);
            }
            List<String> conflicts = new ArrayList<>();
            for (Target target : production) {
                List<ValidationItem> matching = context.plan().validationPlan().stream()
                        .filter(validation -> validation.targetIds().contains(target.targetId()))
                        .filter(validation -> kinds.contains(validation.kind().name()))
                        .toList();
                boolean hasRelatedTest = !requireTestTarget || matching.stream()
                        .flatMap(validation -> validation.targetIds().stream())
                        .map(allTargets::get)
                        .filter(java.util.Objects::nonNull)
                        .anyMatch(related -> related.sourceSet() == SourceSet.TEST);
                if (matching.isEmpty() || !hasRelatedTest) {
                    conflicts.add(target.targetId());
                }
            }
            return conflicts.isEmpty()
                    ? compatible("REQUIRED_TEST_KIND_PRESENT", context, EvidenceTrust.SELF_REPORTED)
                    : result(PlanRuleCheckStatus.CONFLICT, "REQUIRED_TEST_KIND_MISSING",
                            "plan lacks the required validation or related test target", conflicts,
                            EvidenceTrust.SELF_REPORTED);
        }
    }

    private static final class ForbidLayerEdgeChecker extends BaseChecker {
        ForbidLayerEdgeChecker() {
            super("forbid_layer_edge", true);
        }

        @Override
        public Map<String, Object> validateConfig(Map<String, Object> config) {
            Config.requireOnly(config, "fromLayers", "toLayers");
            return Map.of(
                    "fromLayers", Config.strings(config, "fromLayers", false),
                    "toLayers", Config.strings(config, "toLayers", false));
        }

        @Override
        public PlanRuleCheckResult evaluate(RuleCheckSpec check, PlanCheckContext context) {
            if (!context.evidence().layerEvidenceComplete()) {
                return result(PlanRuleCheckStatus.INCOMPLETE_EVIDENCE, "LAYER_EVIDENCE_MISSING",
                        "repository layer-edge evidence is unavailable", context.applicableTargetIds(),
                        EvidenceTrust.REPOSITORY_DERIVED);
            }
            Set<String> from = Set.copyOf(Config.strings(check.config(), "fromLayers", false));
            Set<String> to = Set.copyOf(Config.strings(check.config(), "toLayers", false));
            List<String> conflicts = context.evidence().layerEdges().stream()
                    .filter(edge -> context.applicableTargetIds().contains(edge.targetId()))
                    .filter(edge -> from.contains(edge.fromLayer()) && to.contains(edge.toLayer()))
                    .map(LayerEdgeEvidence::targetId).distinct().toList();
            return conflicts.isEmpty()
                    ? compatible("FORBIDDEN_LAYER_EDGE_ABSENT", context, EvidenceTrust.REPOSITORY_DERIVED)
                    : result(PlanRuleCheckStatus.CONFLICT, "FORBIDDEN_LAYER_EDGE_PRESENT",
                            "repository evidence contains a forbidden layer edge", conflicts,
                            EvidenceTrust.REPOSITORY_DERIVED);
        }
    }

    private static final class RequireTargetRoleChecker extends BaseChecker {
        RequireTargetRoleChecker() {
            super("require_target_role", true);
        }

        @Override
        public Map<String, Object> validateConfig(Map<String, Object> config) {
            Config.requireOnly(config, "roles");
            return Map.of("roles", Config.strings(config, "roles", false));
        }

        @Override
        public PlanRuleCheckResult evaluate(RuleCheckSpec check, PlanCheckContext context) {
            Set<String> required = Set.copyOf(Config.strings(check.config(), "roles", false));
            List<String> mismatches = context.applicableTargetIds().stream()
                    .filter(context.evidence().tagEvidenceMismatches()::containsKey).sorted().toList();
            if (!mismatches.isEmpty()) {
                return result(PlanRuleCheckStatus.CONFLICT, "TAG_EVIDENCE_MISMATCH",
                        "repository role evidence contradicts the plan role hint", mismatches,
                        EvidenceTrust.SELF_REPORTED, EvidenceTrust.REPOSITORY_DERIVED);
            }
            List<String> missing = context.applicableTargets().stream()
                    .filter(target -> target.roleHints().isEmpty()).map(Target::targetId).toList();
            if (!missing.isEmpty()) {
                return result(PlanRuleCheckStatus.INCOMPLETE_EVIDENCE, "ROLE_EVIDENCE_MISSING",
                        "target role evidence is missing", missing, EvidenceTrust.SELF_REPORTED);
            }
            List<String> conflicts = context.applicableTargets().stream()
                    .filter(target -> target.roleHints().stream().noneMatch(required::contains))
                    .map(Target::targetId).toList();
            if (!conflicts.isEmpty()) {
                return result(PlanRuleCheckStatus.CONFLICT, "REQUIRED_TARGET_ROLE_MISSING",
                        "target does not declare a required role", conflicts, EvidenceTrust.SELF_REPORTED);
            }
            return compatible("REQUIRED_TARGET_ROLE_PRESENT", context, EvidenceTrust.SELF_REPORTED);
        }
    }

    private static final class PathConventionChecker extends BaseChecker {
        PathConventionChecker() {
            super("path_convention", true);
        }

        @Override
        public Map<String, Object> validateConfig(Map<String, Object> config) {
            Config.requireOnly(config, "allowedGlobs", "forbiddenGlobs");
            List<String> allowed = Config.strings(config, "allowedGlobs", true);
            List<String> forbidden = Config.strings(config, "forbiddenGlobs", true);
            if (allowed.isEmpty() && forbidden.isEmpty()) {
                throw new IllegalArgumentException("path convention requires an allowed or forbidden glob");
            }
            java.util.stream.Stream.concat(allowed.stream(), forbidden.stream())
                    .forEach(glob -> PlanPathGlobMatcher.matches(glob, "probe.java"));
            return Map.of("allowedGlobs", allowed, "forbiddenGlobs", forbidden);
        }

        @Override
        public PlanRuleCheckResult evaluate(RuleCheckSpec check, PlanCheckContext context) {
            List<String> allowed = Config.strings(check.config(), "allowedGlobs", true);
            List<String> forbidden = Config.strings(check.config(), "forbiddenGlobs", true);
            List<String> conflicts = context.applicableTargets().stream()
                    .filter(target -> forbidden.stream()
                            .anyMatch(glob -> PlanPathGlobMatcher.matches(glob, target.repoRelativePath()))
                            || (!allowed.isEmpty() && allowed.stream()
                                    .noneMatch(glob -> PlanPathGlobMatcher.matches(glob, target.repoRelativePath()))))
                    .map(Target::targetId).toList();
            return conflicts.isEmpty()
                    ? compatible("PATH_CONVENTION_SATISFIED", context, EvidenceTrust.SELF_REPORTED)
                    : result(PlanRuleCheckStatus.CONFLICT, "PATH_CONVENTION_VIOLATED",
                            "planned destination path violates the rule convention", conflicts,
                            EvidenceTrust.SELF_REPORTED);
        }
    }

    private static final class SemanticPlanReviewChecker extends BaseChecker {
        SemanticPlanReviewChecker() {
            super("semantic_plan_review", false);
        }

        @Override
        public Map<String, Object> validateConfig(Map<String, Object> config) {
            Config.requireOnly(config, "contractId", "criteria");
            return Map.of(
                    "contractId", Config.string(config, "contractId"),
                    "criteria", Config.strings(config, "criteria", false));
        }

        @Override
        public PlanRuleCheckResult evaluate(RuleCheckSpec check, PlanCheckContext context) {
            return result(PlanRuleCheckStatus.UNAVAILABLE, "SEMANTIC_PROVIDER_UNAVAILABLE",
                    "semantic PLAN review provider is not connected in this deterministic phase",
                    context.applicableTargetIds(), EvidenceTrust.MODEL_INFERRED);
        }
    }

    private static TargetIntentEvidence targetIntents(PlanCheckContext context) {
        Map<String, Intent> intents = context.intentsById();
        Map<String, List<Intent>> byTarget = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        for (Target target : context.applicableTargets()) {
            List<Intent> resolved = target.intentIds().stream().map(intents::get)
                    .filter(java.util.Objects::nonNull).toList();
            if (target.intentIds().isEmpty() || resolved.size() != target.intentIds().size()) {
                missing.add(target.targetId());
            } else {
                byTarget.put(target.targetId(), resolved);
            }
        }
        return new TargetIntentEvidence(Map.copyOf(byTarget), List.copyOf(missing));
    }

    private record TargetIntentEvidence(Map<String, List<Intent>> byTarget, List<String> missingTargets) {
    }

    private static final class Config {
        private Config() {
        }

        static void requireOnly(Map<String, Object> config, String... allowedKeys) {
            Set<String> allowed = Set.of(allowedKeys);
            List<String> unknown = config.keySet().stream().filter(key -> !allowed.contains(key)).sorted().toList();
            if (!unknown.isEmpty()) {
                throw new IllegalArgumentException("unknown config key: " + unknown.getFirst());
            }
        }

        static <E extends Enum<E>> List<String> enumNames(
                Map<String, Object> config, String key, Class<E> enumType, boolean allowEmpty) {
            List<String> values = strings(config, key, allowEmpty);
            for (String value : values) {
                try {
                    Enum.valueOf(enumType, value);
                } catch (IllegalArgumentException exception) {
                    throw new IllegalArgumentException("unsupported " + key + " value: " + value, exception);
                }
            }
            return values;
        }

        static List<String> strings(Map<String, Object> config, String key, boolean allowEmpty) {
            Object raw = config.get(key);
            if (raw == null && allowEmpty) {
                return List.of();
            }
            if (!(raw instanceof List<?> list)) {
                throw new IllegalArgumentException(key + " must be an array");
            }
            List<String> values = list.stream().map(value -> {
                if (!(value instanceof String string) || string.isBlank() || string.length() > 256) {
                    throw new IllegalArgumentException(key + " values must be bounded non-blank strings");
                }
                return string;
            }).distinct().sorted(Comparator.naturalOrder()).toList();
            if (values.size() != list.size()) {
                throw new IllegalArgumentException(key + " contains duplicate values");
            }
            if (!allowEmpty && values.isEmpty()) {
                throw new IllegalArgumentException(key + " must not be empty");
            }
            if (values.size() > 32) {
                throw new IllegalArgumentException(key + " exceeds 32 values");
            }
            return values;
        }

        static boolean bool(Map<String, Object> config, String key) {
            if (!(config.get(key) instanceof Boolean value)) {
                throw new IllegalArgumentException(key + " must be a boolean");
            }
            return value;
        }

        static String string(Map<String, Object> config, String key) {
            if (!(config.get(key) instanceof String value) || value.isBlank() || value.length() > 256) {
                throw new IllegalArgumentException(key + " must be a bounded non-blank string");
            }
            return value;
        }
    }
}
