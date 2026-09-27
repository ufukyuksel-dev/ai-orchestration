package com.mbworldwideapps.aiorchestration.modules.rules;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record RuleVersion(
        UUID ruleId,
        int version,
        String statement,
        String rationale,
        RuleEnforcement enforcement,
        boolean appliesAll,
        String detectorType,
        Map<String, Object> detectorConfig,
        String detectorContractHash,
        String contentHash,
        UUID originMemoryId,
        String originContentHash,
        String approvalContentHash,
        String confirmationCardHash,
        String humanRawTextHash,
        String workflowContractVersion,
        RuleProvenance originProvenance,
        String approvedBy,
        Instant approvedAt,
        String humanTurnRef,
        Instant createdAt) {

    public RuleVersion {
        detectorConfig = RuleImmutableValues.immutableMap(detectorConfig);
        if ((detectorType == null) != (detectorContractHash == null)) {
            throw new IllegalArgumentException("detector type and contract hash must be present together");
        }
        if (detectorContractHash != null && !detectorContractHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("detector contract hash must be a lowercase SHA-256 value");
        }
        if ((originMemoryId == null) != (originContentHash == null)) {
            throw new IllegalArgumentException("origin memory id and content hash must be present together");
        }
        if (originProvenance == RuleProvenance.DIRECT_HUMAN_POLICY && originMemoryId != null) {
            throw new IllegalArgumentException("direct human policy must not carry origin memory evidence");
        }
    }
}
