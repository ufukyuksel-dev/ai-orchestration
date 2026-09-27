package com.mbworldwideapps.aiorchestration.modules.memoryai;

import java.util.regex.Pattern;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Stable, project-local pointer from one memory to a Scanner code target.
 *
 * <p>FILE refs are repository-relative paths, SYMBOL refs are exact Scanner UUID/FQN/signatures, and CAPSULE refs
 * are stable Scanner target keys accompanied by a capsule kind. Physical capsule row ids intentionally do not belong
 * in this contract because they can change across semantic rescans.</p>
 */
public record MemoryCodeLocator(
        MemoryCodeLocatorKind kind,
        String ref,
        @JsonProperty(required = false) String capsuleKind,
        @JsonProperty(required = false) MemoryCodeLocatorRelationship relationship,
        @JsonProperty(required = false) String path,
        @JsonProperty(required = false) String role) {

    private static final int MAX_REF_LENGTH = 512;
    private static final Pattern CAPSULE_KIND = Pattern.compile("[A-Za-z0-9._:-]{1,80}");
    private static final Pattern ROLE = Pattern.compile("[a-z][a-z0-9_]{0,79}");

    public MemoryCodeLocator(MemoryCodeLocatorKind kind, String ref, String capsuleKind,
            MemoryCodeLocatorRelationship relationship) {
        this(kind, ref, capsuleKind, relationship, null, null);
    }

    public MemoryCodeLocator {
        if (kind == null) {
            throw new IllegalArgumentException("memory code locator kind is required");
        }
        ref = normalizeRef(kind, ref);
        capsuleKind = blankToNull(capsuleKind);
        path = blankToNull(path);
        role = blankToNull(role);
        if (path != null) {
            path = normalizeRef(MemoryCodeLocatorKind.FILE, path);
        }
        if (role != null && !ROLE.matcher(role).matches()) {
            throw new IllegalArgumentException("memory code locator role has an invalid format");
        }
        if (capsuleKind != null && !CAPSULE_KIND.matcher(capsuleKind).matches()) {
            throw new IllegalArgumentException("memory code locator capsuleKind has an invalid format");
        }
        switch (kind) {
            case FILE, DIRECTORY -> {
                if (capsuleKind != null) {
                    throw new IllegalArgumentException("file/directory locator must not declare capsuleKind");
                }
                requireRelationship(kind, relationship, MemoryCodeLocatorRelationship.MENTIONS);
                if (path != null && !path.equals(ref)) {
                    throw new IllegalArgumentException("file/directory locator path must equal ref");
                }
            }
            case SYMBOL -> {
                if (capsuleKind != null) {
                    throw new IllegalArgumentException("symbol locator must not declare capsuleKind");
                }
                if (relationship != null
                        && relationship != MemoryCodeLocatorRelationship.MENTIONS
                        && relationship != MemoryCodeLocatorRelationship.CONSTRAINS) {
                    throw new IllegalArgumentException("symbol locator relationship must be mentions or constrains");
                }
            }
            case CAPSULE -> {
                if (capsuleKind == null) {
                    throw new IllegalArgumentException("capsule locator requires capsuleKind");
                }
                requireRelationship(kind, relationship, MemoryCodeLocatorRelationship.EVIDENCES);
                if (path != null || role != null) {
                    throw new IllegalArgumentException("capsule locator must not declare path or role");
                }
            }
        }
    }

    public MemoryCodeLocatorRelationship effectiveRelationship(MemoryType memoryType) {
        if (relationship != null) {
            return relationship;
        }
        return switch (kind) {
            case FILE, DIRECTORY -> MemoryCodeLocatorRelationship.MENTIONS;
            case CAPSULE -> MemoryCodeLocatorRelationship.EVIDENCES;
            case SYMBOL -> memoryType == MemoryType.DECISION || memoryType == MemoryType.RULE
                    ? MemoryCodeLocatorRelationship.CONSTRAINS
                    : MemoryCodeLocatorRelationship.MENTIONS;
        };
    }

    private static void requireRelationship(MemoryCodeLocatorKind kind,
            MemoryCodeLocatorRelationship actual, MemoryCodeLocatorRelationship expected) {
        if (actual != null && actual != expected) {
            throw new IllegalArgumentException(kind.value() + " locator relationship must be " + expected.value());
        }
    }

    private static String normalizeRef(MemoryCodeLocatorKind kind, String value) {
        String normalized = blankToNull(value);
        if (normalized == null) {
            throw new IllegalArgumentException("memory code locator ref is required");
        }
        if (normalized.length() > MAX_REF_LENGTH || containsControlCharacter(normalized)) {
            throw new IllegalArgumentException("memory code locator ref is invalid or too long");
        }
        if (kind == MemoryCodeLocatorKind.DIRECTORY) {
            if (!java.text.Normalizer.isNormalized(normalized, java.text.Normalizer.Form.NFC)
                    || normalized.contains("\\") || normalized.contains(":")) {
                throw new IllegalArgumentException("directory locator ref must be canonical");
            }
            if (normalized.endsWith("/")) normalized = normalized.substring(0, normalized.length() - 1);
            for (String segment : normalized.split("/", -1)) {
                if (segment.isBlank() || segment.equals(".") || segment.equals(".."))
                    throw new IllegalArgumentException("directory locator ref must be a canonical relative path");
            }
            return normalized;
        }
        if (kind != MemoryCodeLocatorKind.FILE) {
            return normalized;
        }
        normalized = normalized.replace('\\', '/');
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        if (normalized.isBlank() || normalized.startsWith("/") || normalized.matches("^[A-Za-z]:/.*")
                || normalized.contains("://")) {
            throw new IllegalArgumentException("file locator ref must be a repository-relative path");
        }
        for (String segment : normalized.split("/")) {
            if (segment.equals("..")) {
                throw new IllegalArgumentException("file locator ref must not contain parent traversal");
            }
        }
        return normalized;
    }

    private static boolean containsControlCharacter(String value) {
        return value.chars().anyMatch(character -> Character.isISOControl(character));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
