package com.mbworldwideapps.aiorchestration.modules.scanner;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScannerBaselineFreshnessTest {

    @TempDir
    Path tempDir;

    private final ScannerBaselineFreshness freshness = ScannerBaselineFreshness.defaults();

    @Test
    void ignoresEveryDefaultExcludedDirectoryAndUnsupportedFiles() throws Exception {
        for (String excluded : new String[] {
                ".git", "target", "node_modules", "output", "logs", "models", "artifacts"
        }) {
            Path generated = tempDir.resolve(excluded).resolve("Changed.java");
            Files.createDirectories(generated.getParent());
            Files.writeString(generated, "class Changed {}");
        }
        Files.writeString(tempDir.resolve("README.md"), "changed after scan");
        Instant completedAt = Instant.now().minusSeconds(60);

        assertThat(freshness.newestMtime(tempDir.toString())).isNull();
        assertThat(freshness.isStale(run(completedAt))).isFalse();
    }

    @Test
    void newerJavaSourceMakesBaselineStale() throws Exception {
        Path source = tempDir.resolve("src/main/java/com/example/App.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "class App {}");
        Instant completedAt = Instant.now().minusSeconds(60);

        assertThat(freshness.isStale(run(completedAt))).isTrue();
    }

    @Test
    void newerSupportedBuildDescriptorMakesBaselineStale() throws Exception {
        Path pom = tempDir.resolve("pom.xml");
        Files.writeString(pom, "<project/>");
        Instant completedAt = Instant.now().minusSeconds(60);
        Files.setLastModifiedTime(pom, FileTime.from(Instant.now()));

        assertThat(freshness.isStale(run(completedAt))).isTrue();
    }

    @Test
    void oversizedSupportedSourceIsIgnoredLikeScannerDoes() throws Exception {
        ScannerBaselineFreshness smallFileFreshness = new ScannerBaselineFreshness(
                new ScannerProperties(null, null, null, 10, 0.6));
        Path source = tempDir.resolve("src/main/java/com/example/Oversized.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "class OversizedSource {}");
        Instant completedAt = Instant.now().minusSeconds(60);

        assertThat(smallFileFreshness.newestMtime(tempDir.toString())).isNull();
        assertThat(smallFileFreshness.isStale(run(completedAt))).isFalse();
    }

    private CodeScanRunRecord run(Instant completedAt) {
        return new CodeScanRunRecord(UUID.randomUUID(), "PROJECT_A", tempDir.toString(), "completed",
                completedAt.minusSeconds(10), completedAt, Map.of());
    }
}
