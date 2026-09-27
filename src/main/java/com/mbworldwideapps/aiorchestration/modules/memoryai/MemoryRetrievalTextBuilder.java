package com.mbworldwideapps.aiorchestration.modules.memoryai;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

import org.springframework.stereotype.Component;

@Component
public class MemoryRetrievalTextBuilder {

    private static final int MAX_CHARS = 8_192;
    private static final int MAX_TAGS = 8;

    public String build(MemoryItem item, String schemaVersion) {
        if (item == null) {
            return "";
        }
        if (schemaVersion == null || schemaVersion.isBlank() || "legacy-v1".equals(schemaVersion)) {
            return bounded(item.text());
        }
        if (!"discovery-v2".equals(schemaVersion)) {
            throw new IllegalArgumentException("unsupported memory retrieval text schema: " + schemaVersion);
        }
        StringBuilder out = new StringBuilder();
        appendDistinct(out, item.summary(), Set.of());
        Set<String> seen = new LinkedHashSet<>();
        if (item.summary() != null) {
            seen.add(item.summary().strip().toLowerCase(Locale.ROOT));
        }
        appendDistinct(out, item.text(), seen);
        int added = 0;
        for (String tag : item.tags()) {
            if (added >= MAX_TAGS) {
                break;
            }
            String normalized = tag == null ? "" : tag.strip();
            if (!normalized.isBlank() && seen.add(normalized.toLowerCase(Locale.ROOT))) {
                if (out.length() > 0) {
                    out.append('\n');
                }
                out.append("tag: ").append(normalized);
                added++;
            }
        }
        return bounded(out.toString());
    }

    private static void appendDistinct(StringBuilder out, String value, Set<String> seen) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isBlank() || seen.contains(normalized.toLowerCase(Locale.ROOT))) {
            return;
        }
        if (out.length() > 0) {
            out.append('\n');
        }
        out.append(normalized);
    }

    private static String bounded(String value) {
        String safe = value == null ? "" : value;
        return safe.length() <= MAX_CHARS ? safe : safe.substring(0, MAX_CHARS);
    }
}
