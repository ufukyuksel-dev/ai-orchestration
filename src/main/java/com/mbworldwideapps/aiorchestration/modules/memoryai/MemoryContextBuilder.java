package com.mbworldwideapps.aiorchestration.modules.memoryai;

import org.springframework.stereotype.Component;

@Component
public class MemoryContextBuilder {

    public String build(MemoryContextResponse context) {
        if (context == null || context.items().isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        builder.append("=== Active rules from memory ===\n");
        for (MemoryContextItem item : context.items()) {
            builder.append("[")
                    .append(item.citationId())
                    .append("] (confidence=")
                    .append(String.format(java.util.Locale.ROOT, "%.2f", item.confidence()))
                    .append(item.stale() ? ", stale=true" : "")
                    .append(") ")
                    .append(item.promptText().replaceAll("\\s+", " ").trim())
                    .append('\n');
        }
        builder.append("=== End rules ===");
        return builder.toString();
    }
}
