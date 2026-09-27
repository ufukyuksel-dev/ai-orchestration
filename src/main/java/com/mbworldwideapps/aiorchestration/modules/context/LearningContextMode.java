package com.mbworldwideapps.aiorchestration.modules.context;

import java.util.Locale;

/** Explicit source contract for one learning-context request. */
public enum LearningContextMode {
    NONE(false, false),
    SCANNER_ONLY(true, false),
    MEMORY_ONLY(false, true),
    COMBINED(true, true);

    private final boolean scanner;
    private final boolean memory;

    LearningContextMode(boolean scanner, boolean memory) {
        this.scanner = scanner;
        this.memory = memory;
    }

    public boolean includesScanner() {
        return scanner;
    }

    public boolean includesMemory() {
        return memory;
    }

    public static LearningContextMode from(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        return switch (normalized) {
            case "NO_CONTEXT" -> NONE;
            case "GRAPH_CONTEXT", "CODE_ONLY" -> SCANNER_ONLY;
            case "GRAPH_PLUS_MEMORY", "SCANNER_PLUS_MEMORY", "COMBINED_GRAPH" -> COMBINED;
            default -> valueOf(normalized);
        };
    }
}
