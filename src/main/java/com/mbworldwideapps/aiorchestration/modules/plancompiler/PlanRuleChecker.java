package com.mbworldwideapps.aiorchestration.modules.plancompiler;

import java.util.Map;

import com.mbworldwideapps.aiorchestration.modules.rules.RuleCheckSpec;

public interface PlanRuleChecker {

    PlanCheckerContract contract();

    Map<String, Object> validateConfig(Map<String, Object> config);

    PlanRuleCheckResult evaluate(RuleCheckSpec check, PlanCheckContext context);
}
