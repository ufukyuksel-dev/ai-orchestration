package com.mbworldwideapps.aiorchestration.modules.memoryai;

import java.util.Objects;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Component;

@Component
public class MemoryEmbeddingContract {

    private final MemoryEmbeddingProperties properties;
    private final MemoryEmbeddingFingerprint active;

    public MemoryEmbeddingContract(EmbeddingModel embeddingModel, MemoryEmbeddingProperties properties) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.active = MemoryEmbeddingFingerprint.active(embeddingModel, properties);
    }

    public MemoryEmbeddingFingerprint activeFingerprint() {
        return active;
    }

    public Compatibility activeCompatibility() {
        return compatibility(properties.expectedIndexFingerprint(), active.value());
    }

    public MemoryEmbeddingFingerprint shadowFingerprint(EmbeddingModel shadowModel) {
        return MemoryEmbeddingFingerprint.shadow(shadowModel, properties);
    }

    public Compatibility shadowCompatibility(EmbeddingModel shadowModel) {
        return compatibility(properties.shadowExpectedIndexFingerprint(), shadowFingerprint(shadowModel).value());
    }

    public void assertActiveCompatible() {
        assertCompatible("active", properties.expectedIndexFingerprint(), active.value());
    }

    public void assertShadowCompatible(EmbeddingModel shadowModel) {
        assertCompatible("shadow", properties.shadowExpectedIndexFingerprint(), shadowFingerprint(shadowModel).value());
    }

    public boolean activeFingerprintVerified() {
        return activeCompatibility() == Compatibility.MATCH;
    }

    public String expectedActiveFingerprint() {
        return properties.expectedIndexFingerprint();
    }

    public MemoryEmbeddingProperties properties() {
        return properties;
    }

    private static Compatibility compatibility(String expected, String actual) {
        if (expected == null || expected.isBlank()) {
            return Compatibility.UNVERIFIED;
        }
        return expected.equals(actual) ? Compatibility.MATCH : Compatibility.MISMATCH;
    }

    private static void assertCompatible(String lane, String expected, String actual) {
        if (expected != null && !expected.isBlank() && !expected.equals(actual)) {
            throw new MemoryEmbeddingMismatchException(lane, expected, actual);
        }
    }

    public enum Compatibility {
        MATCH,
        MISMATCH,
        UNVERIFIED
    }
}
