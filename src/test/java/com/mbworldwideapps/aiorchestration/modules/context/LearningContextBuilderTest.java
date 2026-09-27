package com.mbworldwideapps.aiorchestration.modules.context;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import com.mbworldwideapps.aiorchestration.core.pii.PiiScrubber;
import com.mbworldwideapps.aiorchestration.core.policy.PolicyProperties;
import com.mbworldwideapps.aiorchestration.core.security.SecretScanService;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryContextResponse;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryPolicyEvaluator;
import com.mbworldwideapps.aiorchestration.modules.scanner.ScannerPayloadRedactor;
import org.junit.jupiter.api.Test;

class LearningContextBuilderTest {

    @Test
    void finalPromptRedactionIsIdempotentAndKeepsLocatorsStable() {
        ScannerPayloadRedactor redactor = new ScannerPayloadRedactor(new PiiScrubber(), new SecretScanService(),
                new MemoryPolicyEvaluator(), new PolicyProperties(true, false, "",
                List.of("ACME-LOAN-001", "internal-prod-svc")));
        LearningContextBuilder builder = new LearningContextBuilder(redactor);
        LearningContextItem item = new LearningContextItem("graph_node", "node-1",
                "owner test@example.com", "src/main/java/App.java:42",
                "password=supersecret123\nACME-LOAN-001 handled by internal-prod-svc", 0.9, 12, Map.of());
        LearningContextResponse response = new LearningContextResponse("PROJECT", List.of(item),
                MemoryContextResponse.empty(), null, 12, false, true, List.of(), Map.of());

        String rendered = builder.buildPromptBlock(response);
        String rerendered = redactor.redact(rendered);

        assertThat(rendered).isEqualTo(rerendered);
        assertThat(rendered).contains("src/main/java/App.java:42", "[REDACTED_SECRET_LINE]");
        assertThat(rendered).doesNotContain("node-1", "score=", "tokens=");
        assertThat(rendered).doesNotContain("test@example.com", "supersecret123", "ACME-LOAN-001",
                "internal-prod-svc");
    }
}
