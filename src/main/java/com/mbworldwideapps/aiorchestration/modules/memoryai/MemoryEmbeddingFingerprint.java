package com.mbworldwideapps.aiorchestration.modules.memoryai;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import org.springframework.ai.embedding.EmbeddingModel;

public record MemoryEmbeddingFingerprint(
        String provider,
        String modelIdentity,
        String modelRevision,
        String runtimeModelClass,
        int dimensions,
        String normalization,
        String retrievalTextSchemaVersion,
        String value) {

    static MemoryEmbeddingFingerprint active(EmbeddingModel model, MemoryEmbeddingProperties properties) {
        return create(model, properties.provider(), properties.modelIdentity(), properties.modelRevision(),
                properties.normalization(), properties.retrievalTextSchemaVersion());
    }

    static MemoryEmbeddingFingerprint shadow(EmbeddingModel model, MemoryEmbeddingProperties properties) {
        return create(model, properties.shadowProvider(), properties.shadowModelIdentity(),
                properties.shadowModelRevision(), properties.shadowNormalization(),
                properties.shadowRetrievalTextSchemaVersion());
    }

    static MemoryEmbeddingFingerprint create(EmbeddingModel model, String provider, String modelIdentity,
            String modelRevision, String normalization, String retrievalTextSchemaVersion) {
        if (model == null) {
            throw new IllegalArgumentException("embedding model is required");
        }
        int dimensions = model.dimensions();
        if (dimensions <= 0) {
            throw new IllegalStateException("embedding model reported invalid dimensions: " + dimensions);
        }
        String runtimeClass = model.getClass().getName();
        String canonical = "memory-embedding-fingerprint-v1\n"
                + field("provider", provider)
                + field("modelIdentity", modelIdentity)
                + field("modelRevision", modelRevision)
                + field("runtimeModelClass", runtimeClass)
                + field("dimensions", Integer.toString(dimensions))
                + field("normalization", normalization)
                + field("retrievalTextSchemaVersion", retrievalTextSchemaVersion);
        return new MemoryEmbeddingFingerprint(provider, modelIdentity, modelRevision, runtimeClass, dimensions,
                normalization, retrievalTextSchemaVersion, sha256(canonical));
    }

    private static String field(String name, String value) {
        String safe = value == null ? "" : value;
        return name + ":" + safe.length() + ":" + safe + "\n";
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
