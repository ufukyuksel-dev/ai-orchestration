package com.mbworldwideapps.aiorchestration.modules.scanner;

/**
 * Which parsed symbols become semantic (LLM) capsule candidates during a scan.
 *
 * <ul>
 *   <li>{@link #ALL} — today's behavior: every eligible class/method is reserved up to the configured
 *       caps.</li>
 *   <li>{@link #SYMBOL_FIRST} — only high-value anchors are reserved for an LLM capsule; low-value
 *       (trivial accessor / annotation-less, call-less) methods are skipped. Gate logic lands in a
 *       later PR-2 slice; this enum is the config surface.</li>
 * </ul>
 */
public enum SemanticSelectionMode {
    ALL,
    SYMBOL_FIRST
}
