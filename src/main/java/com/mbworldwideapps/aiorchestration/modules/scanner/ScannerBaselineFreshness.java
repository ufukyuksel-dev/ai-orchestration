package com.mbworldwideapps.aiorchestration.modules.scanner;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

import org.springframework.stereotype.Component;

/**
 * Computes baseline freshness over the same file types and directory boundary as
 * the scanner. Generated output, logs, model data, and unsupported files must not
 * invalidate an otherwise current code baseline.
 */
@Component
public final class ScannerBaselineFreshness {

    private final Set<String> supportedExtensions;
    private final Set<String> excludedDirectories;
    private final long maxFileBytes;

    public ScannerBaselineFreshness(ScannerProperties properties) {
        this.supportedExtensions = normalized(properties.supportedExtensions(), true);
        this.excludedDirectories = normalized(properties.excludedDirectories(), false);
        this.maxFileBytes = properties.maxFileBytes();
    }

    public static ScannerBaselineFreshness defaults() {
        ScannerProperties defaults = new ScannerProperties(null, null, null, 0L, 0.0);
        return new ScannerBaselineFreshness(defaults);
    }

    public boolean isStale(CodeScanRunRecord run) {
        Instant newestMtime = newestMtime(run.rootPath());
        return newestMtime != null
                && run.completedAt() != null
                && newestMtime.isAfter(run.completedAt());
    }

    public Instant newestMtime(String rootPath) {
        if (rootPath == null || rootPath.isBlank()) {
            return null;
        }
        Path root = Path.of(rootPath);
        if (!Files.isDirectory(root)) {
            return null;
        }
        NewestModifiedFile visitor = new NewestModifiedFile(root);
        try {
            Files.walkFileTree(root, visitor);
            return visitor.newest;
        } catch (IOException e) {
            return null;
        }
    }

    private boolean isSupported(Path path) {
        String fileName = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return supportedExtensions.stream()
                .anyMatch(extension -> extension.startsWith(".")
                        ? fileName.endsWith(extension)
                        : fileName.equals(extension));
    }

    private boolean isExcluded(Path root, Path path) {
        Path relative = root.relativize(path);
        for (Path part : relative) {
            if (excludedDirectories.contains(part.toString())) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> normalized(Iterable<String> values, boolean lowercase) {
        Set<String> normalized = new LinkedHashSet<>();
        if (values == null) {
            return normalized;
        }
        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }
            String trimmed = value.trim();
            normalized.add(lowercase ? trimmed.toLowerCase(Locale.ROOT) : trimmed);
        }
        return Set.copyOf(normalized);
    }

    private final class NewestModifiedFile extends SimpleFileVisitor<Path> {

        private final Path root;
        private Instant newest;

        private NewestModifiedFile(Path root) {
            this.root = root;
        }

        @Override
        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
            return !dir.equals(root) && isExcluded(root, dir)
                    ? FileVisitResult.SKIP_SUBTREE
                    : FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
            if (attrs.isRegularFile() && attrs.size() <= maxFileBytes
                    && !isExcluded(root, file) && isSupported(file)) {
                Instant modifiedAt = attrs.lastModifiedTime().toInstant();
                if (newest == null || modifiedAt.isAfter(newest)) {
                    newest = modifiedAt;
                }
            }
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFileFailed(Path file, IOException exc) {
            return FileVisitResult.CONTINUE;
        }
    }
}
