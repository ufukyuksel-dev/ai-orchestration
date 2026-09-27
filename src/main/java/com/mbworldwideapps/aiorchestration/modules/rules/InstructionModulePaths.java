package com.mbworldwideapps.aiorchestration.modules.rules;

import java.text.Normalizer;

/** Literal repository-relative module directories, never patterns or absolute paths. */
final class InstructionModulePaths {
    private InstructionModulePaths() { }

    static String requireDirectory(String value) {
        if (value == null || value.isBlank() || value.length() > 253
                || !value.equals(value.trim()) || !Normalizer.isNormalized(value, Normalizer.Form.NFC)
                || value.chars().anyMatch(c -> "\\*?[]{}:".indexOf(c) >= 0)
                || value.codePoints().anyMatch(c -> c == 0 || Character.isISOControl(c))) {
            throw new IllegalArgumentException("module path must be a canonical literal relative directory (max 253 chars)");
        }
        String[] segments = value.split("/", -1);
        if (segments.length > 127) {
            throw new IllegalArgumentException("module path exceeds segment limit");
        }
        for (String segment : segments) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                throw new IllegalArgumentException("module path contains an empty or dot segment");
            }
        }
        return value;
    }
}
