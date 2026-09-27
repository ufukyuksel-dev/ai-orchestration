package com.mbworldwideapps.aiorchestration.modules.memoryai;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.mbworldwideapps.aiorchestration.config.AiOrchestrationProperties;
import com.mbworldwideapps.aiorchestration.core.telemetry.AiMetrics;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class CuratedMemoryLoader {

    private static final Logger log = LoggerFactory.getLogger(CuratedMemoryLoader.class);

    private final AiOrchestrationProperties properties;
    private final MemoryService memoryService;
    private final AiMetrics aiMetrics;
    private final ObjectMapper yamlMapper;

    public CuratedMemoryLoader(AiOrchestrationProperties properties, MemoryService memoryService, AiMetrics aiMetrics) {
        this.properties = properties;
        this.memoryService = memoryService;
        this.aiMetrics = aiMetrics;
        this.yamlMapper = new ObjectMapper(new YAMLFactory())
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .findAndRegisterModules();
    }

    @PostConstruct
    void loadOnStartup() {
        if (!properties.memory().loadOnStartup()) {
            return;
        }
        CuratedMemoryLoadResult result = loadAll();
        log.info("Curated memory startup load finished: scanned={}, loaded={}, updated={}, skipped={}, rejected={}",
                result.scanned(), result.loaded(), result.updated(), result.skipped(), result.rejected());
    }

    public CuratedMemoryLoadResult loadAll() {
        return CuratedMemoryLoadResult.combine(List.of(
                loadDirectory(Path.of(properties.memory().globalPath()), MemoryScope.GLOBAL, null),
                loadDirectory(Path.of(properties.memory().projectPath()), MemoryScope.PROJECT,
                        properties.memory().projectKey())));
    }

    CuratedMemoryLoadResult loadDirectory(Path directory, MemoryScope scope, String projectKey) {
        if (!Files.exists(directory)) {
            return CuratedMemoryLoadResult.empty();
        }
        if (!Files.isDirectory(directory)) {
            aiMetrics.recordMemoryLoadReject("load-io-error");
            return new CuratedMemoryLoadResult(1, 0, 0, 0, 1,
                    List.of(new CuratedMemoryFileResult(directory.toString(), null, scope.value(), "rejected",
                            "path is not a directory", null)));
        }

        List<CuratedMemoryFileResult> files = new ArrayList<>();
        int loaded = 0;
        int updated = 0;
        int skipped = 0;
        int rejected = 0;
        try (var stream = Files.list(directory)) {
            for (Path file : stream.filter(Files::isRegularFile).sorted().toList()) {
                if (!isYaml(file)) {
                    rejected++;
                    aiMetrics.recordMemoryLoadReject("unsupported-file");
                    files.add(new CuratedMemoryFileResult(file.toString(), null, scope.value(), "rejected",
                            "unsupported file extension", null));
                    continue;
                }
                CuratedMemoryFileResult result = loadFile(file, scope, projectKey);
                files.add(result);
                switch (result.action()) {
                    case "loaded" -> loaded++;
                    case "updated" -> updated++;
                    case "skipped" -> skipped++;
                    default -> rejected++;
                }
            }
        } catch (IOException e) {
            aiMetrics.recordMemoryLoadReject("load-io-error");
            log.warn("Cannot list curated memory directory {}", directory, e);
            return new CuratedMemoryLoadResult(files.size() + 1, loaded, updated, skipped, rejected + 1, files);
        }
        return new CuratedMemoryLoadResult(files.size(), loaded, updated, skipped, rejected, List.copyOf(files));
    }

    private CuratedMemoryFileResult loadFile(Path file, MemoryScope scope, String configuredProjectKey) {
        CuratedMemoryDocument document;
        try {
            document = yamlMapper.readValue(file.toFile(), CuratedMemoryDocument.class);
        } catch (Exception e) {
            aiMetrics.recordMemoryLoadReject("parse-error");
            log.warn("Cannot parse curated memory file {}: {}", file, e.getClass().getSimpleName());
            return new CuratedMemoryFileResult(file.toString(), null, scope.value(), "rejected", "parse-error", null);
        }

        List<String> validationErrors = validate(document, scope, configuredProjectKey);
        if (!validationErrors.isEmpty()) {
            aiMetrics.recordMemoryLoadReject("validation-error");
            log.warn("Invalid curated memory file {}: {}", file, validationErrors);
            return new CuratedMemoryFileResult(file.toString(), document.id(), scope.value(), "rejected",
                    "validation-error: " + String.join(", ", validationErrors), null);
        }

        MemoryType memoryType = memoryType(document.type());
        String projectKey = scope == MemoryScope.PROJECT
                ? firstNonBlank(document.projectKey(), configuredProjectKey)
                : null;
        int tokenEstimate = tokenEstimate(document.text());
        int effectiveTokenCap = effectiveTokenCap(document.tokenCap());
        String contentHash = contentHash(document, scope, projectKey, effectiveTokenCap, tokenEstimate);
        String sourceRef = "curated:%s:%s:%s".formatted(
                scope.value(),
                projectKey == null ? "global" : projectKey,
                document.id().trim());
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source", "curated-yaml");
        metadata.put("curatedId", document.id().trim());
        metadata.put("contentHash", contentHash);
        metadata.put("path", file.toString());
        metadata.put("priority", document.priority() == null ? 100 : document.priority());
        metadata.put("tokenCap", effectiveTokenCap);
        metadata.put("tokenEstimate", tokenEstimate);

        CreateMemoryRequest request = new CreateMemoryRequest(
                scope,
                projectKey,
                memoryType,
                document.title(),
                document.text(),
                document.tags(),
                document.confidence() == null ? 1.0 : document.confidence(),
                MemoryStatus.ACTIVE,
                MemorySourceType.MANUAL,
                sourceRef,
                firstNonBlank(document.owner(), "curated"),
                metadata,
                document.lastVerifiedAt(),
                document.expiresAt());
        CuratedMemoryUpsertResult result = memoryService.upsertCurated(request, contentHash);
        return new CuratedMemoryFileResult(file.toString(), document.id().trim(), scope.value(),
                actionName(result.action()), null, result.item().id());
    }

    private List<String> validate(CuratedMemoryDocument document, MemoryScope scope, String configuredProjectKey) {
        List<String> errors = new ArrayList<>();
        if (document.id() == null || document.id().isBlank()) {
            errors.add("id is required");
        }
        if (document.type() == null || document.type().isBlank()) {
            errors.add("type is required");
        } else {
            try {
                MemoryType.from(document.type());
            } catch (IllegalArgumentException e) {
                errors.add("type is unsupported");
            }
        }
        if (document.title() == null || document.title().isBlank()) {
            errors.add("title is required");
        }
        if (document.text() == null || document.text().isBlank()) {
            errors.add("text is required");
        }
        if (document.confidence() != null && (document.confidence() < 0.0 || document.confidence() > 1.0)) {
            errors.add("confidence must be between 0.0 and 1.0");
        }
        if (document.priority() != null && document.priority() < 0) {
            errors.add("priority must be >= 0");
        }
        if (document.tokenCap() != null && document.tokenCap() <= 0) {
            errors.add("token_cap must be > 0");
        }
        if (document.tags() != null && document.tags().stream().anyMatch(tag -> tag == null || tag.isBlank())) {
            errors.add("tags cannot contain blank values");
        }
        if (scope == MemoryScope.PROJECT && firstNonBlank(document.projectKey(), configuredProjectKey) == null) {
            errors.add("project_key is required for project memory");
        }
        if (document.text() != null && tokenEstimate(document.text()) > effectiveTokenCap(document.tokenCap())) {
            errors.add("text exceeds token cap");
        }
        return errors;
    }

    private int effectiveTokenCap(Integer documentTokenCap) {
        int configuredCap = properties.memory().contextTokenCap();
        if (documentTokenCap == null || documentTokenCap <= 0) {
            return configuredCap;
        }
        return Math.min(documentTokenCap, configuredCap);
    }

    private static boolean isYaml(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".yml") || name.endsWith(".yaml");
    }

    private static int tokenEstimate(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        return text.trim().split("\\s+").length;
    }

    private static String actionName(CuratedMemoryUpsertAction action) {
        return switch (action) {
            case LOADED -> "loaded";
            case UPDATED -> "updated";
            case SKIPPED -> "skipped";
        };
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first.trim();
        }
        return second == null || second.isBlank() ? null : second.trim();
    }

    private static String contentHash(CuratedMemoryDocument document, MemoryScope scope, String projectKey,
            int tokenCap, int tokenEstimate) {
        String canonical = String.join("|",
                scope.value(),
                projectKey == null ? "" : projectKey,
                document.id().trim(),
                memoryType(document.type()).value(),
                document.title().trim(),
                document.text().trim(),
                document.tags() == null ? "" : String.join(",", document.tags().stream()
                        .map(String::trim)
                        .map(tag -> tag.toLowerCase(Locale.ROOT))
                        .sorted()
                        .toList()),
                String.valueOf(document.confidence() == null ? 1.0 : document.confidence()),
                firstNonBlank(document.owner(), "curated"),
                document.lastVerifiedAt() == null ? "" : document.lastVerifiedAt().toString(),
                document.expiresAt() == null ? "" : document.expiresAt().toString(),
                String.valueOf(document.priority() == null ? 100 : document.priority()),
                String.valueOf(tokenCap),
                String.valueOf(tokenEstimate));
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    record CuratedMemoryDocument(
            String id,
            String type,
            String title,
            String text,
            List<String> tags,
            Double confidence,
            String owner,
            Instant lastVerifiedAt,
            Instant expiresAt,
            Integer priority,
            Integer tokenCap,
            String projectKey) {
    }

    private static MemoryType memoryType(String type) {
        return MemoryType.from(type);
    }
}
