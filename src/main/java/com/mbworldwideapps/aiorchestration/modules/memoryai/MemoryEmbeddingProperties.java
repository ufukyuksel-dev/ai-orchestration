package com.mbworldwideapps.aiorchestration.modules.memoryai;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

@ConfigurationProperties(prefix = "ai-orchestration.memory.embedding")
public record MemoryEmbeddingProperties(
        String provider,
        String modelIdentity,
        String modelRevision,
        String normalization,
        String retrievalTextSchemaVersion,
        String expectedIndexFingerprint,
        MemoryShadowMode shadowMode,
        String shadowCollectionName,
        String shadowProvider,
        String shadowModelIdentity,
        String shadowModelRevision,
        String shadowNormalization,
        String shadowRetrievalTextSchemaVersion,
        String shadowExpectedIndexFingerprint) {

    @ConstructorBinding
    public MemoryEmbeddingProperties {
        provider = valueOr(provider, "internal");
        modelIdentity = valueOr(modelIdentity, "hashing-sha256-char4");
        modelRevision = valueOr(modelRevision, "v1");
        normalization = valueOr(normalization, "nfkc-lower-root-token-v1");
        retrievalTextSchemaVersion = valueOr(retrievalTextSchemaVersion, "legacy-v1");
        expectedIndexFingerprint = trim(expectedIndexFingerprint);
        shadowMode = shadowMode == null ? MemoryShadowMode.OFF : shadowMode;
        shadowCollectionName = trim(shadowCollectionName);
        shadowProvider = valueOr(shadowProvider, provider);
        shadowModelIdentity = valueOr(shadowModelIdentity, modelIdentity);
        shadowModelRevision = valueOr(shadowModelRevision, modelRevision);
        shadowNormalization = valueOr(shadowNormalization, normalization);
        shadowRetrievalTextSchemaVersion = valueOr(shadowRetrievalTextSchemaVersion, "discovery-v2");
        shadowExpectedIndexFingerprint = trim(shadowExpectedIndexFingerprint);
        if (shadowMode != MemoryShadowMode.OFF && shadowCollectionName.isBlank()) {
            throw new IllegalArgumentException("shadowCollectionName is required when memory shadow mode is enabled");
        }
        if (shadowMode != MemoryShadowMode.OFF && shadowExpectedIndexFingerprint.isBlank()) {
            throw new IllegalArgumentException(
                    "shadowExpectedIndexFingerprint is required when memory shadow mode is enabled");
        }
    }

    public static MemoryEmbeddingProperties defaults() {
        return new MemoryEmbeddingProperties(null, null, null, null, null, null, null,
                null, null, null, null, null, null, null);
    }

    public boolean shadowUsesDifferentModel() {
        return !provider.equals(shadowProvider)
                || !modelIdentity.equals(shadowModelIdentity)
                || !modelRevision.equals(shadowModelRevision);
    }

    private static String valueOr(String value, String fallback) {
        String trimmed = trim(value);
        return trimmed.isBlank() ? fallback : trimmed;
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
