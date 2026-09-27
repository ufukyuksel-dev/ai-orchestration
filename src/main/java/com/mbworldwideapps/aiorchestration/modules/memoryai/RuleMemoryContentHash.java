package com.mbworldwideapps.aiorchestration.modules.memoryai;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Canonical hashes shared by rule-memory approval and activation checks. */
final class RuleMemoryContentHash {

    private RuleMemoryContentHash() {
    }

    static String compute(String summary, String text) {
        String normalizedSummary = normalize(summary);
        String normalizedText = normalize(text);
        return sha256(lengthPrefixed(normalizedSummary) + lengthPrefixed(normalizedText));
    }

    static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private static String lengthPrefixed(String value) {
        return value.length() + ":" + value;
    }
}
