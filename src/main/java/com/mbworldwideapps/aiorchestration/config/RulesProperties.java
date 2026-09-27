package com.mbworldwideapps.aiorchestration.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Rule authority is enabled by default. RULE memories become ACTIVE through
 * the verified promotion port; an explicit enabled=false disables authority.
 */
@Validated
@ConfigurationProperties(prefix = "ai-orchestration.rules")
public record RulesProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("20") @Min(1) @Max(20) int maxBindingsPerRule,
        @DefaultValue("256") @Min(1) @Max(256) int maxGlobLength,
        @DefaultValue("4096") @Min(64) @Max(4096) int maxDetectorConfigBytes,
        @DefaultValue("200") @Min(1) @Max(200) int maxActiveGlobRulesPerProject) {
}
