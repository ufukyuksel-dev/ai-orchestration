package com.mbworldwideapps.aiorchestration.modules.rules;

import java.util.Map;

/**
 * SPI for deterministic gate detectors. Implementations must be linear-time on
 * their input; promotion validates configs via {@link #validateConfig(Map)} so a
 * broken detector config is rejected before a rule version can ever gate.
 */
public interface RuleDetector {

    String type();

    DetectorContract contract();

    DetectorResult evaluate(DetectorInput input);

    /**
     * @throws IllegalArgumentException when the config cannot be compiled or
     *         violates size limits; promotion aborts in that case.
     */
    void validateConfig(Map<String, Object> config);
}
