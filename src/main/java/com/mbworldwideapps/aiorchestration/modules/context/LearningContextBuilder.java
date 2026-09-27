package com.mbworldwideapps.aiorchestration.modules.context;

import java.util.List;

import com.mbworldwideapps.aiorchestration.modules.llmgateway.TokenEstimator;
import com.mbworldwideapps.aiorchestration.modules.scanner.ScannerPayloadRedactor;
import org.springframework.stereotype.Component;

@Component
public class LearningContextBuilder {

    private static final String HEADER = "Learning context:\n";

    private final ScannerPayloadRedactor redactor;

    public LearningContextBuilder(ScannerPayloadRedactor redactor) {
        this.redactor = redactor;
    }

    public String buildPromptBlock(LearningContextResponse response) {
        if (response == null || response.items().isEmpty()) {
            return "";
        }
        return redactor.redact(render(response.items()));
    }

    static int estimateItemTokens(String kind, String title, String locator, String text) {
        return TokenEstimator.estimate(renderItem(kind, title, locator, text));
    }

    static int estimatePromptTokens(List<LearningContextItem> items) {
        return items == null || items.isEmpty() ? 0 : TokenEstimator.estimate(render(items));
    }

    private static String render(List<LearningContextItem> items) {
        StringBuilder block = new StringBuilder(HEADER);
        for (LearningContextItem item : items) {
            block.append(renderItem(item.kind(), item.title(), item.locator(), item.text()));
        }
        return block.toString().trim();
    }

    private static String renderItem(String kind, String title, String locator, String text) {
        StringBuilder block = new StringBuilder("- [").append(blank(kind, "context")).append(']');
        if (title != null && !title.isBlank()) {
            block.append(' ').append(title.trim());
        }
        if (locator != null && !locator.isBlank()) {
            block.append(" @ ").append(locator.trim());
        }
        block.append('\n');
        if (text != null && !text.isBlank()) {
            block.append("  ").append(text.trim().replace("\n", "\n  ")).append('\n');
        }
        return block.toString();
    }

    private static String blank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
