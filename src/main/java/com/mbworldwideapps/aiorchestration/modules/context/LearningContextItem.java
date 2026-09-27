package com.mbworldwideapps.aiorchestration.modules.context;

import java.util.Map;

public record LearningContextItem(
        String kind,
        String id,
        String title,
        String locator,
        String text,
        double score,
        int tokenEstimate,
        Map<String, Object> metadata) {

    public LearningContextItem {
        kind = blankToEmpty(kind);
        id = blankToEmpty(id);
        title = blankToEmpty(title);
        locator = blankToEmpty(locator);
        text = blankToEmpty(text);
        tokenEstimate = Math.max(0, tokenEstimate);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    private static String blankToEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}
