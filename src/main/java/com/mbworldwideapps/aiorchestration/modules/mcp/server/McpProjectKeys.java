package com.mbworldwideapps.aiorchestration.modules.mcp.server;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;

final class McpProjectKeys {

    private McpProjectKeys() {
    }

    static String effective(McpClientContext context, String requestedProjectKey) {
        String requested = blankToNull(requestedProjectKey);
        String effective = requested == null
                ? context.projectKey()
                : isLocalTrust(context) ? normalize(requested) : requested;
        if (!isLocalTrust(context) && !effective.equals(context.projectKey())) {
            throw new McpAccessException("Cannot access a different project");
        }
        return effective;
    }

    static String forScan(McpClientContext context, String requestedProjectKey, String rootPath) {
        if (blankToNull(requestedProjectKey) != null) {
            return effective(context, requestedProjectKey);
        }
        if (!isLocalTrust(context)) {
            return context.projectKey();
        }
        return inferFromPath(blankToNull(rootPath) == null ? "." : rootPath, false)
                .map(projectKey -> contextProjectKeyIfSameSlug(context, projectKey))
                .orElseGet(() -> normalize(context.projectKey()));
    }

    static String forMemoryWrite(McpClientContext context, String requestedProjectKey, String sourceRef) {
        if (blankToNull(requestedProjectKey) != null) {
            return effective(context, requestedProjectKey);
        }
        if (!isLocalTrust(context)) {
            return context.projectKey();
        }
        if (requiresExplicitProjectKeyForMemorySource(sourceRef)) {
            throw new McpAccessException("Project memory writes with a file/path sourceRef require explicit "
                    + "projectKey or an absolute repository-root sourceRef; pass the projectKey returned by scanner.scan.start/result");
        }
        if (canInferProjectKeyFromMemorySource(sourceRef)) {
            return inferFromPath(sourceRef, true)
                    .map(projectKey -> contextProjectKeyIfSameSlug(context, projectKey))
                    .orElseGet(() -> normalize(context.projectKey()));
        }
        return normalize(context.projectKey());
    }

    static boolean canAccess(McpClientContext context, String projectKey) {
        return projectKey == null || projectKey.isBlank()
                || projectKey.equals(context.projectKey())
                || isLocalTrust(context);
    }

    static boolean isLocalTrust(McpClientContext context) {
        return context != null && "local".equals(context.keyPrefix());
    }

    static boolean requiresExplicitProjectKeyForMemorySource(String sourceRef) {
        String firstRef = firstSourceReference(sourceRef);
        if (firstRef == null) {
            return false;
        }
        boolean absolute;
        try {
            absolute = Path.of(firstRef).isAbsolute();
        } catch (RuntimeException ignored) {
            return false;
        }
        String normalized = firstRef.replace('\\', '/').toLowerCase(Locale.ROOT);
        boolean fileRef = normalized.matches(".*\\.(java|kt|kts|groovy|scala|xml|yml|yaml|properties|json|toml|gradle|md|ts|tsx|js|jsx|py|sql)$");
        return fileRef || (!absolute && normalized.contains("/"));
    }

    private static boolean canInferProjectKeyFromMemorySource(String sourceRef) {
        String firstRef = firstSourceReference(sourceRef);
        if (firstRef == null) {
            return false;
        }
        try {
            if (!Path.of(firstRef).isAbsolute()) {
                return false;
            }
        } catch (RuntimeException ignored) {
            return false;
        }
        String normalized = firstRef.replace('\\', '/').toLowerCase(Locale.ROOT);
        return !normalized.matches(".*\\.(java|kt|kts|groovy|scala|xml|yml|yaml|properties|json|toml|gradle|md|ts|tsx|js|jsx|py|sql)$");
    }

    static String normalize(String value) {
        String normalized = blankToNull(value);
        if (normalized == null) {
            return "AI_ORCHESTRATION";
        }
        normalized = transliterate(normalized).trim()
                .replaceAll("[^A-Za-z0-9]+", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_+|_+$", "")
                .toUpperCase(Locale.ROOT);
        return normalized.isBlank() ? "AI_ORCHESTRATION" : normalized;
    }

    private static Optional<String> inferFromPath(String pathValue, boolean fileMayBeReferenced) {
        String value = blankToNull(pathValue);
        if (value == null) {
            return Optional.empty();
        }
        String firstRef = firstSourceReference(value);
        if (firstRef == null) {
            return Optional.empty();
        }
        try {
            Path path = Path.of(firstRef).toAbsolutePath().normalize();
            Path name = path.getFileName();
            if (name == null) {
                return Optional.empty();
            }
            String leaf = name.toString();
            if (fileMayBeReferenced && leaf.contains(".") && path.getParent() != null
                    && path.getParent().getFileName() != null) {
                leaf = path.getParent().getFileName().toString();
            }
            return Optional.of(normalize(leaf) + "_" + shortHash(path.toString()));
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
    }

    private static String contextProjectKeyIfSameSlug(McpClientContext context, String derivedProjectKey) {
        String contextProjectKey = normalize(context.projectKey());
        String currentWorkingDirectoryKey = contextProjectKey + "_"
                + shortHash(Path.of("").toAbsolutePath().normalize().toString());
        return currentWorkingDirectoryKey.equals(derivedProjectKey) ? context.projectKey() : derivedProjectKey;
    }

    private static String transliterate(String value) {
        String turkish = value
                .replace('ı', 'i')
                .replace('İ', 'I')
                .replace('ğ', 'g')
                .replace('Ğ', 'G')
                .replace('ü', 'u')
                .replace('Ü', 'U')
                .replace('ş', 's')
                .replace('Ş', 'S')
                .replace('ö', 'o')
                .replace('Ö', 'O')
                .replace('ç', 'c')
                .replace('Ç', 'C');
        return Normalizer.normalize(turkish, Normalizer.Form.NFKD)
                .replaceAll("\\p{M}+", "");
    }

    private static String shortHash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 4).toUpperCase(Locale.ROOT);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static String firstSourceReference(String value) {
        String normalized = blankToNull(value);
        if (normalized == null) {
            return null;
        }
        String firstRef = normalized.split("[;\\n]", 2)[0].trim();
        return firstRef.isBlank() ? null : firstRef;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
