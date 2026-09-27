package com.mbworldwideapps.aiorchestration.modules.memoryai;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Canonical versioned codec for MemoryAI-to-Scanner locators stored in the existing memory metadata JSONB. */
public final class MemoryCodeLocatorMetadata {

    public static final String METADATA_KEY = "codeLocators";
    public static final int SCHEMA_VERSION = 1;
    private static final int MAX_LOCATORS = 32;

    private MemoryCodeLocatorMetadata() {
    }

    /**
     * Replaces the locator envelope when {@code requested} is non-null. A null list preserves the existing envelope;
     * an empty list stores an explicit empty envelope so legacy metadata cannot silently recreate cleared links.
     */
    public static Map<String, Object> replace(Map<String, Object> metadata, List<MemoryCodeLocator> requested,
            MemoryScope scope, String projectKey) {
        Map<String, Object> base = metadata == null ? Map.of() : metadata;
        if (requested == null) {
            validateScope(read(base), scope, projectKey);
            return Map.copyOf(base);
        }
        List<MemoryCodeLocator> normalized = normalize(requested);
        validateScope(new Decoded(true, normalized), scope, projectKey);
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("version", SCHEMA_VERSION);
        envelope.put("items", normalized.stream().map(MemoryCodeLocatorMetadata::row).toList());
        Map<String, Object> merged = new LinkedHashMap<>(base);
        merged.put(METADATA_KEY, Map.copyOf(envelope));
        return Map.copyOf(merged);
    }

    public static Decoded read(Map<String, Object> metadata) {
        if (metadata == null || !metadata.containsKey(METADATA_KEY)) {
            return Decoded.absent();
        }
        Object rawEnvelope = metadata.get(METADATA_KEY);
        if (!(rawEnvelope instanceof Map<?, ?> envelope)) {
            throw new IllegalArgumentException("memory code locator envelope must be an object");
        }
        Object rawVersion = envelope.get("version");
        if (!(rawVersion instanceof Number version) || version.intValue() != SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported memory code locator schema version: " + rawVersion);
        }
        Object rawItems = envelope.get("items");
        if (!(rawItems instanceof List<?> items)) {
            throw new IllegalArgumentException("memory code locator envelope items must be an array");
        }
        if (items.size() > MAX_LOCATORS) {
            throw new IllegalArgumentException("memory code locator count exceeds " + MAX_LOCATORS);
        }
        List<MemoryCodeLocator> decoded = new ArrayList<>(items.size());
        for (Object rawItem : items) {
            if (!(rawItem instanceof Map<?, ?> item)) {
                throw new IllegalArgumentException("memory code locator item must be an object");
            }
            decoded.add(new MemoryCodeLocator(
                    MemoryCodeLocatorKind.from(string(item.get("kind"))),
                    string(item.get("ref")),
                    string(item.get("capsuleKind")),
                    MemoryCodeLocatorRelationship.from(string(item.get("relationship"))),
                    string(item.get("path")),
                    string(item.get("role"))));
        }
        return new Decoded(true, normalize(decoded));
    }

    public static List<MemoryCodeLocator> normalize(List<MemoryCodeLocator> requested) {
        if (requested == null) {
            return List.of();
        }
        if (requested.size() > MAX_LOCATORS) {
            throw new IllegalArgumentException("memory code locator count exceeds " + MAX_LOCATORS);
        }
        LinkedHashSet<MemoryCodeLocator> unique = new LinkedHashSet<>();
        for (MemoryCodeLocator locator : requested) {
            if (locator == null) {
                throw new IllegalArgumentException("memory code locator must not be null");
            }
            unique.add(locator);
        }
        return List.copyOf(unique);
    }

    private static void validateScope(Decoded decoded, MemoryScope scope, String projectKey) {
        if (!decoded.present() || decoded.items().isEmpty()) {
            return;
        }
        if (scope != MemoryScope.PROJECT || projectKey == null || projectKey.isBlank()) {
            throw new IllegalArgumentException("memory code locators require project scope and projectKey");
        }
    }

    private static Map<String, Object> row(MemoryCodeLocator locator) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("kind", locator.kind().value());
        row.put("ref", locator.ref());
        if (locator.capsuleKind() != null) {
            row.put("capsuleKind", locator.capsuleKind());
        }
        if (locator.relationship() != null) {
            row.put("relationship", locator.relationship().value());
        }
        if (locator.path() != null) {
            row.put("path", locator.path());
        }
        if (locator.role() != null) {
            row.put("role", locator.role());
        }
        return Map.copyOf(row);
    }

    private static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    public record Decoded(boolean present, List<MemoryCodeLocator> items) {
        public Decoded {
            items = items == null ? List.of() : List.copyOf(items);
        }

        static Decoded absent() {
            return new Decoded(false, List.of());
        }
    }
}
