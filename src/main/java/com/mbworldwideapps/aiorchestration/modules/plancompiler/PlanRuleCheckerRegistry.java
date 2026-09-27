package com.mbworldwideapps.aiorchestration.modules.plancompiler;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.mbworldwideapps.aiorchestration.modules.rules.RuleCheckPhase;
import com.mbworldwideapps.aiorchestration.modules.rules.RuleCheckSpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public final class PlanRuleCheckerRegistry {

    private final Map<String, PlanRuleChecker> checkers;

    @Autowired
    public PlanRuleCheckerRegistry() {
        this(StandardPlanRuleCheckers.all());
    }

    public PlanRuleCheckerRegistry(List<PlanRuleChecker> checkers) {
        if (checkers == null || checkers.isEmpty()) {
            throw new IllegalArgumentException("at least one PLAN checker is required");
        }
        Map<String, PlanRuleChecker> indexed = new LinkedHashMap<>();
        for (PlanRuleChecker checker : checkers) {
            if (checker == null || indexed.putIfAbsent(checker.contract().type(), checker) != null) {
                throw new IllegalArgumentException("duplicate PLAN checker implementation");
            }
        }
        this.checkers = Map.copyOf(indexed);
    }

    public Optional<PlanCheckerContract> contract(String checkerType) {
        PlanRuleChecker checker = checkers.get(checkerType);
        return checker == null ? Optional.empty() : Optional.of(checker.contract());
    }

    public RuleCheckSpec validateForPromotion(RuleCheckSpec check) {
        if (check == null || check.phase() != RuleCheckPhase.PLAN) {
            throw new IllegalArgumentException("PLAN checker registry accepts only PLAN checks");
        }
        PlanRuleChecker checker = checkers.get(check.checkerType());
        if (checker == null) {
            throw new IllegalArgumentException("unknown PLAN checker: " + check.checkerType());
        }
        return new RuleCheckSpec(check.phase(), check.checkerType(), check.severity(),
                checker.validateConfig(check.config()));
    }

    public PlanRuleCheckResult evaluate(RuleCheckSpec check, PlanCheckContext context) {
        if (check == null || context == null) {
            throw new IllegalArgumentException("checker evaluation input is required");
        }
        PlanRuleChecker checker = checkers.get(check.checkerType());
        if (checker == null) {
            return new PlanRuleCheckResult(
                    PlanRuleCheckStatus.UNAVAILABLE,
                    "UNKNOWN_CHECKER",
                    "stored PLAN checker is not registered: " + check.checkerType(),
                    context.applicableTargetIds().stream().sorted().toList(),
                    Set.of(EvidenceTrust.RULE_DERIVED));
        }
        try {
            RuleCheckSpec normalized = new RuleCheckSpec(
                    check.phase(), check.checkerType(), check.severity(),
                    checker.validateConfig(check.config()));
            return checker.evaluate(normalized, context);
        } catch (IllegalArgumentException exception) {
            return new PlanRuleCheckResult(
                    PlanRuleCheckStatus.UNAVAILABLE,
                    "CHECKER_CONFIG_INVALID",
                    "stored PLAN checker configuration is invalid",
                    context.applicableTargetIds().stream().sorted().toList(),
                    Set.of(EvidenceTrust.RULE_DERIVED));
        }
    }

    public List<PlanCheckerContract> contracts() {
        return checkers.values().stream().map(PlanRuleChecker::contract)
                .sorted(java.util.Comparator.comparing(PlanCheckerContract::type)).toList();
    }
}
