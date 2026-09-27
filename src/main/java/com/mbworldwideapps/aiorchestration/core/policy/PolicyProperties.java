package com.mbworldwideapps.aiorchestration.core.policy;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ai-orchestration.policy")
public record PolicyProperties(
        boolean enabled,
        boolean adminTokenRequired,
        String adminToken,
        List<String> sensitiveTerms) {

    public PolicyProperties {
        if (adminToken == null) {
            adminToken = "";
        }
        if (sensitiveTerms == null || sensitiveTerms.isEmpty()) {
            sensitiveTerms = List.of("ACME-LOAN-001", "internal-prod-svc");
        } else {
            sensitiveTerms = sensitiveTerms.stream()
                    .filter(term -> term != null && !term.isBlank())
                    .map(String::trim)
                    .distinct()
                    .toList();
        }
    }
}
