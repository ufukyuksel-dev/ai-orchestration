package com.mbworldwideapps.aiorchestration.modules.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;

import com.mbworldwideapps.aiorchestration.core.diff.UnifiedDiffParser;
import org.junit.jupiter.api.Test;

class DiffRegexRuleDetectorTest {

    private final DiffRegexRuleDetector detector = new DiffRegexRuleDetector(new UnifiedDiffParser());

    private static final String CONTROLLER_PATH = "src/main/java/com/acme/AController.java";
    private static final String DIFF = """
            diff --git a/%1$s b/%1$s
            index 1111111..2222222 100644
            --- a/%1$s
            +++ b/%1$s
            @@ -1,1 +1,4 @@
            -class LegacyController {}
            +@RestController
            +public class AController {
            +    @Autowired private OrderRepository orderRepository;
            +}
            """.formatted(CONTROLLER_PATH);

    @Test
    void reportsMatchWithHashedExcerptNotRawCode() {
        DetectorResult result = evaluate(DIFF, "@Autowired\\s+private", CONTROLLER_PATH);

        assertThat(result.status()).isEqualTo(DetectorResult.Status.MATCHED);
        assertThat(result.findings()).hasSize(1);
        DetectorFinding finding = result.findings().get(0);
        assertThat(finding.targetHint()).isEqualTo(CONTROLLER_PATH);
        assertThat(finding.excerptHash()).hasSize(64).doesNotContain("Autowired");
        assertThat(finding.findingHash()).hasSize(64);
    }

    @Test
    void reportsCleanWhenNoLineMatches() {
        DetectorResult result = evaluate(DIFF, "jdbcTemplate\\.execute", CONTROLLER_PATH);

        assertThat(result.status()).isEqualTo(DetectorResult.Status.CLEAN);
        assertThat(result.findings()).isEmpty();
    }

    @Test
    void matchesOnlyAddedLinesAndTreatsDeletedFilesAsClean() {
        String replacement = """
                diff --git a/src/main/java/com/acme/A.java b/src/main/java/com/acme/A.java
                index 1111111..2222222 100644
                --- a/src/main/java/com/acme/A.java
                +++ b/src/main/java/com/acme/A.java
                @@ -1,1 +1,1 @@
                -    @Autowired private OrderRepository legacy;
                +    private final OrderRepository orderRepository;
                """;
        String deletion = """
                diff --git a/src/main/java/com/acme/A.java b/src/main/java/com/acme/A.java
                deleted file mode 100644
                index 1111111..0000000
                --- a/src/main/java/com/acme/A.java
                +++ /dev/null
                @@ -1,1 +0,0 @@
                -    @Autowired private OrderRepository legacy;
                """;

        assertThat(evaluate(replacement, "@Autowired\\s+private", "src/main/java/com/acme/A.java").status())
                .isEqualTo(DetectorResult.Status.CLEAN);
        assertThat(evaluate(deletion, "@Autowired\\s+private", "src/main/java/com/acme/A.java").status())
                .isEqualTo(DetectorResult.Status.CLEAN);
    }

    @Test
    void parsesHunksSoAddedContentCannotMasqueradeAsAFileHeader() {
        String diff = newFilePatch("A.java", "++ b/Fake.java", "danger", "++danger");

        DetectorResult result = evaluate(diff, "danger", "A.java");

        assertThat(result.status()).isEqualTo(DetectorResult.Status.MATCHED);
        assertThat(result.findings()).hasSize(2);
        assertThat(result.findings()).extracting(DetectorFinding::targetHint).containsOnly("A.java");
    }

    @Test
    void canonicalizesCrLfForAnchoredMatchingAndHashes() {
        String lf = newFilePatch("A.java", "danger");
        String crlf = lf.replace("\n", "\r\n");

        DetectorResult lfResult = evaluate(lf, "danger$", "A.java");
        DetectorResult crlfResult = evaluate(crlf, "danger$", "A.java");

        assertThat(crlfResult.status()).isEqualTo(DetectorResult.Status.MATCHED);
        assertThat(crlfResult.findings()).isEqualTo(lfResult.findings());
    }

    @Test
    void malformedBinaryAndNonEmptyWhitespaceAreInconclusive() {
        String binary = """
                diff --git a/A.class b/A.class
                index 1111111..2222222 100644
                Binary files a/A.class and b/A.class differ
                """;

        assertThat(evaluate("not a unified diff", "danger", "A.java").status())
                .isEqualTo(DetectorResult.Status.INCONCLUSIVE);
        assertThat(evaluate(binary, "danger", "A.class").status())
                .isEqualTo(DetectorResult.Status.INCONCLUSIVE);
        assertThat(evaluate("   \t\n", "danger", "A.java").status())
                .isEqualTo(DetectorResult.Status.INCONCLUSIVE);
    }

    @Test
    void oversizedAsciiWhitespaceAndMultibyteDiffsAreInconclusive() {
        String oversizedWhitespace = " ".repeat(DiffRegexRuleDetector.MAX_DIFF_BYTES + 1);
        String multibyte = newFilePatch("A.java", "€".repeat(400_000));

        assertThat(multibyte.length()).isLessThan(DiffRegexRuleDetector.MAX_DIFF_BYTES);
        assertThat(multibyte.getBytes(StandardCharsets.UTF_8).length)
                .isGreaterThan(DiffRegexRuleDetector.MAX_DIFF_BYTES);
        assertThat(evaluate(oversizedWhitespace, "danger", "A.java").status())
                .isEqualTo(DetectorResult.Status.INCONCLUSIVE);
        assertThat(evaluate(multibyte, "danger", "A.java").status())
                .isEqualTo(DetectorResult.Status.INCONCLUSIVE);
    }

    @Test
    void acceptsAValidDiffAtTheExactUtf8ByteLimit() {
        String prefix = newFilePatch("A.java", "BOUNDARY");
        String exactLimit = prefix.substring(0, prefix.length() - 1)
                + "x".repeat(DiffRegexRuleDetector.MAX_DIFF_BYTES
                        - prefix.getBytes(StandardCharsets.UTF_8).length)
                + "\n";

        assertThat(exactLimit.getBytes(StandardCharsets.UTF_8)).hasSize(DiffRegexRuleDetector.MAX_DIFF_BYTES);
        assertThat(evaluate(exactLimit, "^BOUNDARY", "A.java").status())
                .isEqualTo(DetectorResult.Status.MATCHED);
    }

    @Test
    void evaluatesOnlyFilesApplicableToThisRule() {
        String diff = newFilePatch("A.java", "safe") + newFilePatch("B.java", "danger");

        DetectorResult result = evaluate(diff, "danger", "A.java");

        assertThat(result.status()).isEqualTo(DetectorResult.Status.CLEAN);
        assertThat(result.findings()).isEmpty();
    }

    @Test
    void keepsRepositoryPathsLosslessAndDoesNotConflateRealAPrefix() {
        String diff = newFilePatch("a/Foo.java", "danger") + newFilePatch("Foo.java", "safe");

        DetectorResult prefixed = evaluate(diff, "danger", "a/Foo.java");
        DetectorResult root = evaluate(diff, "danger", "Foo.java");

        assertThat(UnifiedDiffParser.normalizePath("a/Foo.java")).isEqualTo("a/Foo.java");
        assertThat(UnifiedDiffParser.normalizePath("trailing-space.java ")).isEqualTo("trailing-space.java ");
        assertThat(prefixed.status()).isEqualTo(DetectorResult.Status.MATCHED);
        assertThat(prefixed.findings()).extracting(DetectorFinding::targetHint).containsOnly("a/Foo.java");
        assertThat(root.status()).isEqualTo(DetectorResult.Status.CLEAN);
    }

    @Test
    void outOfScopeBinaryFileDoesNotPoisonApplicableTextEvaluation() {
        String binary = """
                diff --git a/B.class b/B.class
                index 1111111..2222222 100644
                Binary files a/B.class and b/B.class differ
                """;

        DetectorResult result = evaluate(newFilePatch("A.java", "danger") + binary, "danger", "A.java");

        assertThat(result.status()).isEqualTo(DetectorResult.Status.MATCHED);
        assertThat(result.findings()).extracting(DetectorFinding::targetHint).containsOnly("A.java");
    }

    @Test
    void applicableMetadataOnlyRenameOrCopyIsInconclusiveWithoutFinalBlob() {
        String rename = """
                diff --git a/Old.java b/New.java
                similarity index 100%
                rename from Old.java
                rename to New.java
                """;
        String copy = """
                diff --git a/Old.java b/Copy.java
                similarity index 100%
                copy from Old.java
                copy to Copy.java
                """;

        assertThat(evaluate(rename, "danger", "New.java").status())
                .isEqualTo(DetectorResult.Status.INCONCLUSIVE);
        assertThat(evaluate(copy, "danger", "Copy.java").status())
                .isEqualTo(DetectorResult.Status.INCONCLUSIVE);
        assertThat(evaluate(rename, "danger", "Unrelated.java").status())
                .isEqualTo(DetectorResult.Status.CLEAN);
    }

    @Test
    void applicableRenameWithAVisibleHunkStillRequiresTheWholeFinalBlob() {
        String renameWithHunk = """
                diff --git a/Old.java b/New.java
                similarity index 90%
                rename from Old.java
                rename to New.java
                index 1111111..2222222 100644
                --- a/Old.java
                +++ b/New.java
                @@ -1 +1 @@
                -safeOldLine
                +safeNewLine
                """;

        assertThat(evaluate(renameWithHunk, "danger", "New.java").status())
                .isEqualTo(DetectorResult.Status.INCONCLUSIVE);
    }

    @Test
    void acceptsOnlyTheExactPatternConfigSchemaAndRejectsEmptyMatches() {
        assertThatThrownBy(() -> detector.validateConfig(Map.of(
                "pattern", "danger", "flags", java.util.List.of("CASE_INSENSITIVE"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly 'pattern'");
        assertThatThrownBy(() -> detector.validateConfig(Map.of("pattern", "^")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("empty input");
        assertThatThrownBy(() -> detector.validateConfig(Map.of("pattern", "a*")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("empty input");
    }

    @Test
    void validatesTheExactNoNewlineMarker() {
        String standard = newFilePatch("A.java", "danger") + "\\ No newline at end of file\n";
        String fake = newFilePatch("A.java", "danger") + "\\ attacker-controlled marker\n";

        assertThat(evaluate(standard, "danger", "A.java").status())
                .isEqualTo(DetectorResult.Status.MATCHED);
        assertThat(evaluate(fake, "danger", "A.java").status())
                .isEqualTo(DetectorResult.Status.INCONCLUSIVE);
    }

    @Test
    void parserBoundsApplicableAddedLinesAndDetectorBoundsFindings() {
        String tooManyLines = patchWithAddedLines("A.java", UnifiedDiffParser.MAX_ADDED_LINES + 1, "safe");
        String tooManyFindings = patchWithAddedLines(
                "A.java", DiffRegexRuleDetector.MAX_FINDINGS + 1, "danger");

        assertThat(evaluate(tooManyLines, "danger", "A.java").status())
                .isEqualTo(DetectorResult.Status.INCONCLUSIVE);
        assertThat(evaluate(tooManyFindings, "danger", "A.java").status())
                .isEqualTo(DetectorResult.Status.INCONCLUSIVE);
    }

    @Test
    void parserBoundsFileAndHunkMetadataAllocations() {
        UnifiedDiffParser parser = new UnifiedDiffParser();

        UnifiedDiffParser.AddedLinesResult files = parser.extractAddedLines(
                metadataOnlyFiles(UnifiedDiffParser.MAX_FILES + 1));
        UnifiedDiffParser.AddedLinesResult hunks = parser.extractAddedLines(
                patchWithHunks("A.java", UnifiedDiffParser.MAX_HUNKS + 1));

        assertThat(files).isInstanceOf(UnifiedDiffParser.AddedLinesResult.Failure.class);
        assertThat(((UnifiedDiffParser.AddedLinesResult.Failure) files).reason())
                .isEqualTo(UnifiedDiffParser.FailureReason.LIMIT_EXCEEDED);
        assertThat(hunks).isInstanceOf(UnifiedDiffParser.AddedLinesResult.Failure.class);
        assertThat(((UnifiedDiffParser.AddedLinesResult.Failure) hunks).reason())
                .isEqualTo(UnifiedDiffParser.FailureReason.LIMIT_EXCEEDED);
    }

    @Test
    void missingFileScopeIsInconclusiveInsteadOfScanningTheWholePatch() {
        DetectorResult result = detector.evaluate(new DetectorInput(
                newFilePatch("A.java", "danger"),
                Set.of(new TargetRef(BindingKind.SYMBOL, "com.acme.A#run")),
                Map.of("pattern", "danger")));

        assertThat(result.status()).isEqualTo(DetectorResult.Status.INCONCLUSIVE);
    }

    @Test
    void findingHashesAreOccurrenceUniqueAndStable() {
        String diff = newFilePatch("A.java", "danger", "danger");

        DetectorResult first = evaluate(diff, "danger", "A.java");
        DetectorResult second = evaluate(diff, "danger", "A.java");

        assertThat(first.findings()).hasSize(2);
        assertThat(first.findings()).extracting(DetectorFinding::findingHash).doesNotHaveDuplicates();
        assertThat(second.findings()).isEqualTo(first.findings());
    }

    @Test
    void oversizedDiffIsInconclusiveNotSilentlyClean() {
        String hugeDiff = newFilePatch("src/A.java", "x".repeat(1_100_000));

        DetectorResult result = evaluate(hugeDiff, "anything", "src/A.java");

        assertThat(result.status()).isEqualTo(DetectorResult.Status.INCONCLUSIVE);
    }

    @Test
    void validateRejectsBacktrackingOnlySyntaxAndOversizedPattern() {
        assertThatThrownBy(() -> detector.validateConfig(Map.of("pattern", "(?<name>a)\\k<name>")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> detector.validateConfig(Map.of("pattern", "a".repeat(501))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("500");
        assertThatThrownBy(() -> detector.validateConfig(Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pattern");
    }

    @Test
    void validateAcceptsRe2CompatiblePattern() {
        detector.validateConfig(Map.of("pattern", "@Transactional.*public\\s+\\w+"));
    }

    private DetectorResult evaluate(String diff, String pattern, String... applicableFiles) {
        Set<TargetRef> targets = Arrays.stream(applicableFiles)
                .map(path -> new TargetRef(BindingKind.FILE, path))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        return detector.evaluate(new DetectorInput(diff, targets, Map.of("pattern", pattern)));
    }

    private static String newFilePatch(String path, String... lines) {
        String additions = Arrays.stream(lines)
                .map(line -> "+" + line)
                .collect(java.util.stream.Collectors.joining("\n"));
        return """
                diff --git a/%1$s b/%1$s
                new file mode 100644
                index 0000000..1111111
                --- /dev/null
                +++ b/%1$s
                @@ -0,0 +1,%2$d @@
                %3$s
                """.formatted(path, lines.length, additions);
    }

    private static String patchWithAddedLines(String path, int count, String content) {
        return """
                diff --git a/%1$s b/%1$s
                new file mode 100644
                index 0000000..1111111
                --- /dev/null
                +++ b/%1$s
                @@ -0,0 +1,%2$d @@
                """.formatted(path, count) + ("+" + content + "\n").repeat(count);
    }

    private static String metadataOnlyFiles(int count) {
        StringBuilder diff = new StringBuilder();
        for (int i = 0; i < count; i++) {
            diff.append("diff --git a/F").append(i).append(" b/F").append(i).append('\n')
                    .append("new file mode 100644\n")
                    .append("index 0000000..e69de29\n");
        }
        return diff.toString();
    }

    private static String patchWithHunks(String path, int count) {
        StringBuilder diff = new StringBuilder()
                .append("diff --git a/").append(path).append(" b/").append(path).append('\n')
                .append("index 1111111..2222222 100644\n")
                .append("--- a/").append(path).append('\n')
                .append("+++ b/").append(path).append('\n');
        for (int i = 1; i <= count; i++) {
            diff.append("@@ -").append(i).append(",1 +").append(i).append(",1 @@\n")
                    .append("-old\n")
                    .append("+safe\n");
        }
        return diff.toString();
    }
}
