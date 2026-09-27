package com.mbworldwideapps.aiorchestration.modules.memoryai;

public final class MemoryTextPreview {

    private static final String ELLIPSIS = "...";

    private MemoryTextPreview() {
    }

    public static String excerpt(String value) {
        String normalized = normalize(value);
        if (normalized.length() <= MemoryAtomLimits.EXCERPT_MAX_CHARS) {
            return normalized;
        }
        int sentenceEnd = firstSentenceEnd(normalized);
        if (sentenceEnd > 0 && sentenceEnd <= MemoryAtomLimits.EXCERPT_MAX_CHARS) {
            return normalized.substring(0, sentenceEnd).trim();
        }
        return truncate(normalized, MemoryAtomLimits.EXCERPT_MAX_CHARS);
    }

    /**
     * Compact text used for runtime prompt injection: summary plus a bounded excerpt of the full text.
     * Keeps injected memory small and its token estimate honest, while the full text stays on the item
     * for citation/audit. Mirrors the {@code summary + " " + excerpt} shape used by search previews.
     */
    public static String promptText(String summary, String text) {
        String normalizedSummary = normalize(summary);
        String excerpt = excerpt(text);
        if (excerpt.isBlank()) {
            return normalizedSummary;
        }
        if (normalizedSummary.isBlank()) {
            return excerpt;
        }
        if (excerpt.regionMatches(true, 0, normalizedSummary, 0, normalizedSummary.length())) {
            // Excerpt already begins with the summary; avoid duplicating it in the prompt.
            return excerpt;
        }
        return normalizedSummary + ": " + excerpt;
    }

    public static String truncate(String value, int maxChars) {
        String normalized = normalize(value);
        if (maxChars <= 0) {
            return "";
        }
        if (normalized.length() <= maxChars) {
            return normalized;
        }
        if (maxChars <= ELLIPSIS.length()) {
            return normalized.substring(0, maxChars);
        }
        return normalized.substring(0, maxChars - ELLIPSIS.length()).trim() + ELLIPSIS;
    }

    public static int estimateTokens(String value) {
        String normalized = normalize(value);
        if (normalized.isBlank()) {
            return 0;
        }
        return Math.max(1, normalized.split("\\s+").length);
    }

    public static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().replaceAll("\\s+", " ");
    }

    private static int firstSentenceEnd(String value) {
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if ((current == '.' || current == '!' || current == '?')
                    && (i + 1 == value.length() || Character.isWhitespace(value.charAt(i + 1)))) {
                return i + 1;
            }
        }
        return -1;
    }
}
