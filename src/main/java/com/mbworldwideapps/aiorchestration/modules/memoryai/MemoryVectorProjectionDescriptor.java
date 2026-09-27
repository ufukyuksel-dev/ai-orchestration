package com.mbworldwideapps.aiorchestration.modules.memoryai;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import org.springframework.stereotype.Component;

@Component
class MemoryVectorProjectionDescriptor {

    private final MemoryEmbeddingContract embeddings;
    private final MemoryRetrievalTextBuilder textBuilder;

    MemoryVectorProjectionDescriptor(MemoryEmbeddingContract embeddings, MemoryRetrievalTextBuilder textBuilder) {
        this.embeddings = embeddings;
        this.textBuilder = textBuilder;
    }

    Descriptor describe(MemoryItem item) {
        MemoryEmbeddingFingerprint fingerprint = embeddings.activeFingerprint();
        String retrievalText = textBuilder.build(item, fingerprint.retrievalTextSchemaVersion());
        String projectionIdentity = "memory-vector-projection-v1\n"
                + field("vectorId", item.vectorId().toString())
                + field("scope", item.scope().value())
                + field("projectKey", item.projectKey())
                + field("memoryType", item.memoryType().value())
                + field("status", item.status().value())
                + field("eligibility", indexable(item) ? "indexable" : "not-indexable")
                + field("ruleAuthorityLinked", Boolean.toString(item.memoryType() == MemoryType.RULE
                        && item.metadata().containsKey("promotedRuleId")));
        return new Descriptor(
                sha256(retrievalText),
                sha256(projectionIdentity),
                fingerprint.value(),
                indexable(item));
    }

    private static boolean indexable(MemoryItem item) {
        return item.status() == MemoryStatus.ACTIVE || item.status() == MemoryStatus.STALE;
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

    record Descriptor(String retrievalTextHash, String projectionHash,
            String embeddingFingerprint, boolean indexable) {
    }
}
