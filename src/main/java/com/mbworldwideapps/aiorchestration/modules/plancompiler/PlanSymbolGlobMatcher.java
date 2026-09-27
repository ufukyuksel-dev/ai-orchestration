package com.mbworldwideapps.aiorchestration.modules.plancompiler;

import java.text.Normalizer;

/** Whole-symbol, case-sensitive glob. Only '*' is special; repeated stars are equivalent. */
public final class PlanSymbolGlobMatcher {

    private PlanSymbolGlobMatcher() {
    }

    public static boolean matches(String rawPattern, String rawSymbol) {
        String pattern = canonical(rawPattern, 256, true);
        String symbol = canonical(rawSymbol, 4096, false);
        // One row per pattern character: bounded O(pattern * symbol) time, O(symbol) space.
        boolean[] previous = new boolean[symbol.length() + 1];
        previous[0] = true;
        for (int i = 0; i < pattern.length(); i++) {
            char token = pattern.charAt(i);
            boolean[] current = new boolean[symbol.length() + 1];
            current[0] = token == '*' && previous[0];
            for (int j = 1; j <= symbol.length(); j++) {
                current[j] = token == '*'
                        ? previous[j] || current[j - 1]
                        : token == symbol.charAt(j - 1) && previous[j - 1];
            }
            previous = current;
        }
        return previous[symbol.length()];
    }

    private static String canonical(String raw, int maxChars, boolean pattern) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("symbol glob input is required");
        }
        String value = Normalizer.normalize(raw, Normalizer.Form.NFC);
        if (!value.equals(value.trim()) || value.length() > maxChars
                || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("symbol glob input is not canonical or exceeds its bound");
        }
        if (!pattern && value.indexOf('*') >= 0) {
            throw new IllegalArgumentException("target symbol must not contain wildcards");
        }
        return value;
    }
}
