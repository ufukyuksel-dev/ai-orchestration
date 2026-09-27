package com.mbworldwideapps.aiorchestration.modules.scanner;

import java.util.HashSet;
import java.util.Set;

import com.mbworldwideapps.aiorchestration.core.pii.PiiScrubber;
import com.mbworldwideapps.aiorchestration.core.policy.PolicyProperties;
import com.mbworldwideapps.aiorchestration.core.security.SecretScanFinding;
import com.mbworldwideapps.aiorchestration.core.security.SecretScanService;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryPolicyEvaluator;
import org.springframework.stereotype.Component;

@Component
public class ScannerPayloadRedactor {

    private final PiiScrubber piiScrubber;
    private final SecretScanService secretScanService;
    private final MemoryPolicyEvaluator memoryPolicyEvaluator;
    private final PolicyProperties policyProperties;

    public ScannerPayloadRedactor(PiiScrubber piiScrubber, SecretScanService secretScanService,
            MemoryPolicyEvaluator memoryPolicyEvaluator, PolicyProperties policyProperties) {
        this.piiScrubber = piiScrubber;
        this.secretScanService = secretScanService;
        this.memoryPolicyEvaluator = memoryPolicyEvaluator;
        this.policyProperties = policyProperties;
    }

    public String redact(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String redacted = redactSecretLines(text);
        redacted = piiScrubber.mask(redacted);
        redacted = memoryPolicyEvaluator.mask(redacted);
        for (String term : policyProperties.sensitiveTerms()) {
            if (term != null && !term.isBlank()) {
                redacted = redacted.replace(term, "[REDACTED]");
            }
        }
        return redacted;
    }

    private String redactSecretLines(String text) {
        Set<Integer> secretLines = new HashSet<>();
        for (SecretScanFinding finding : secretScanService.scan(text)) {
            secretLines.add(finding.lineNumber());
        }
        if (secretLines.isEmpty()) {
            return text;
        }
        String[] lines = text.split("\\R", -1);
        for (int index = 0; index < lines.length; index++) {
            if (secretLines.contains(index + 1)) {
                lines[index] = "[REDACTED_SECRET_LINE]";
            }
        }
        return String.join("\n", lines);
    }
}
