package com.mbworldwideapps.aiorchestration.modules.plancompiler;

import java.text.Normalizer;

/** Bounded path-segment-aware glob contract. `*` is local; `**` is recursive. */
public final class PlanPathGlobMatcher {

    public static final String CONTRACT_VERSION = "path-glob/v1";
    private static final int MAX_PATTERN_CHARS = 256;
    private static final int MAX_PATH_CHARS = 1_024;
    private static final int MAX_SEGMENTS = 128;

    private PlanPathGlobMatcher() {
    }

    public static boolean matches(String rawPattern, String rawPath) {
        String pattern = canonical(rawPattern, MAX_PATTERN_CHARS, true);
        String path = canonical(rawPath, MAX_PATH_CHARS, false);
        String[] patternSegments = pattern.split("/", -1);
        String[] pathSegments = path.split("/", -1);
        if (patternSegments.length > MAX_SEGMENTS || pathSegments.length > MAX_SEGMENTS) {
            throw new IllegalArgumentException("path glob exceeds segment limit of " + MAX_SEGMENTS);
        }
        Boolean[][] memo = new Boolean[patternSegments.length + 1][pathSegments.length + 1];
        return match(patternSegments, 0, pathSegments, 0, memo);
    }

    private static boolean match(String[] pattern, int patternIndex,
            String[] path, int pathIndex, Boolean[][] memo) {
        if (memo[patternIndex][pathIndex] != null) {
            return memo[patternIndex][pathIndex];
        }
        boolean result;
        if (patternIndex == pattern.length) {
            result = pathIndex == path.length;
        } else if (pattern[patternIndex].equals("**")) {
            result = match(pattern, patternIndex + 1, path, pathIndex, memo)
                    || (pathIndex < path.length
                            && match(pattern, patternIndex, path, pathIndex + 1, memo));
        } else {
            result = pathIndex < path.length
                    && matchSegment(pattern[patternIndex], path[pathIndex])
                    && match(pattern, patternIndex + 1, path, pathIndex + 1, memo);
        }
        memo[patternIndex][pathIndex] = result;
        return result;
    }

    private static boolean matchSegment(String pattern, String value) {
        boolean[][] matches = new boolean[pattern.length() + 1][value.length() + 1];
        matches[0][0] = true;
        for (int patternIndex = 1; patternIndex <= pattern.length(); patternIndex++) {
            char token = pattern.charAt(patternIndex - 1);
            if (token == '*') {
                matches[patternIndex][0] = matches[patternIndex - 1][0];
            }
            for (int valueIndex = 1; valueIndex <= value.length(); valueIndex++) {
                matches[patternIndex][valueIndex] = token == '*'
                        ? matches[patternIndex - 1][valueIndex]
                                || matches[patternIndex][valueIndex - 1]
                        : token == value.charAt(valueIndex - 1)
                                && matches[patternIndex - 1][valueIndex - 1];
            }
        }
        return matches[pattern.length()][value.length()];
    }

    private static String canonical(String raw, int maxChars, boolean pattern) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException((pattern ? "path glob" : "path") + " is required");
        }
        String value = Normalizer.normalize(raw, Normalizer.Form.NFC);
        if (!value.equals(value.trim()) || value.length() > maxChars || value.startsWith("/")
                || value.indexOf('\\') >= 0 || value.matches("^[A-Za-z]:.*")
                || value.codePoints().anyMatch(codePoint -> codePoint == 0 || Character.isISOControl(codePoint))) {
            throw new IllegalArgumentException("path glob input is not canonical or exceeds its bound");
        }
        for (String segment : value.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                throw new IllegalArgumentException("path glob input contains a non-canonical segment");
            }
            if (pattern && segment.contains("**") && !segment.equals("**")) {
                throw new IllegalArgumentException("recursive ** must occupy a complete path segment");
            }
            if (!pattern && segment.indexOf('*') >= 0) {
                throw new IllegalArgumentException("target path must not contain wildcards");
            }
        }
        return value;
    }
}
