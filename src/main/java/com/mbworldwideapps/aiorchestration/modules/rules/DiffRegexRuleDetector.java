package com.mbworldwideapps.aiorchestration.modules.rules;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.google.re2j.Pattern;
import com.google.re2j.PatternSyntaxException;
import com.mbworldwideapps.aiorchestration.core.diff.UnifiedDiffParser;
import org.springframework.stereotype.Component;

/**
 * Gate detector matching a RE2 pattern against the ADDED lines of a unified
 * diff. RE2/J guarantees linear-time matching, so a hostile or unlucky pattern
 * cannot stall the gate. Oversized input yields INCONCLUSIVE (fail-closed at
 * the gate), never a silent CLEAN.
 */
@Component
public class DiffRegexRuleDetector implements RuleDetector {

    public static final String TYPE = "diff_regex";

    static final int MAX_PATTERN_LENGTH = 500;
    static final int MAX_DIFF_BYTES = 1_048_576;
    static final int MAX_FINDINGS = 1_000;

    private static final Set<String> CONFIG_KEYS = Set.of("pattern");
    private static final DetectorContract CONTRACT = DetectorContracts.bind(
            "diff_regex/re2j-added-lines/v1",
            Set.of(BindingKind.FILE, BindingKind.PATH_GLOB),
            Set.of(BindingKind.FILE),
            DiffRegexRuleDetector.class,
            UnifiedDiffParser.class,
            Pattern.class);

    private final UnifiedDiffParser diffParser;

    public DiffRegexRuleDetector(UnifiedDiffParser diffParser) {
        this.diffParser = java.util.Objects.requireNonNull(diffParser, "diffParser");
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public DetectorContract contract() {
        return CONTRACT;
    }

    @Override
    public DetectorResult evaluate(DetectorInput input) {
        if (input == null || input.diffText() == null) {
            return DetectorResult.inconclusive();
        }
        String diff = input.diffText();
        if (exceedsDiffLimit(diff)) {
            return DetectorResult.inconclusive();
        }
        if (diff.isEmpty()) {
            return DetectorResult.clean();
        }
        if (diff.isBlank()) {
            return DetectorResult.inconclusive();
        }

        Pattern pattern = compile(requiredPattern(input.config()));
        Set<String> applicableFiles = applicableFiles(input.changedTargets());
        if (applicableFiles.isEmpty()) {
            return DetectorResult.inconclusive();
        }

        UnifiedDiffParser.AddedLinesResult parsed = diffParser.extractAddedLines(diff, applicableFiles);
        if (parsed instanceof UnifiedDiffParser.AddedLinesResult.Failure) {
            return DetectorResult.inconclusive();
        }

        List<DetectorFinding> findings = new ArrayList<>();
        List<UnifiedDiffParser.AddedFile> files =
                ((UnifiedDiffParser.AddedLinesResult.Success) parsed).files();
        for (UnifiedDiffParser.AddedFile file : files) {
            if (file.status() != UnifiedDiffParser.FileStatus.EVALUATED) {
                return DetectorResult.inconclusive();
            }
            for (UnifiedDiffParser.AddedLine addedLine : file.lines()) {
                if (pattern.matcher(addedLine.content()).find()) {
                    if (findings.size() >= MAX_FINDINGS) {
                        return DetectorResult.inconclusive();
                    }
                    String excerptHash = sha256(addedLine.content());
                    findings.add(new DetectorFinding(
                            addedLine.path(),
                            excerptHash,
                            "added line matches forbidden pattern",
                            findingHash(addedLine, pattern.pattern(), excerptHash)));
                }
            }
        }
        if (findings.isEmpty()) {
            return DetectorResult.clean();
        }
        return new DetectorResult(DetectorResult.Status.MATCHED, List.copyOf(findings));
    }

    private static boolean exceedsDiffLimit(String diff) {
        // Every UTF-16 code unit needs at least one UTF-8 byte. This guard keeps
        // arbitrarily large input from being copied before it can be rejected.
        if (diff.length() > MAX_DIFF_BYTES) {
            return true;
        }
        return diff.getBytes(StandardCharsets.UTF_8).length > MAX_DIFF_BYTES;
    }

    private static Set<String> applicableFiles(Set<TargetRef> changedTargets) {
        if (changedTargets == null) {
            return Set.of();
        }
        return changedTargets.stream()
                .filter(target -> target != null && target.kind() == BindingKind.FILE)
                .map(TargetRef::key)
                .map(UnifiedDiffParser::normalizePath)
                .filter(path -> !path.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public void validateConfig(Map<String, Object> config) {
        compile(requiredPattern(config));
    }

    private Pattern compile(String rawPattern) {
        try {
            Pattern pattern = Pattern.compile(rawPattern);
            if (pattern.matcher("").find()) {
                throw new IllegalArgumentException("pattern must not match empty input");
            }
            return pattern;
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException("pattern is not RE2-compatible: " + e.getMessage(), e);
        }
    }

    private String requiredPattern(Map<String, Object> config) {
        if (config == null || !config.keySet().equals(CONFIG_KEYS)) {
            throw new IllegalArgumentException("detector config must contain exactly 'pattern'");
        }
        Object value = config.get("pattern");
        if (!(value instanceof String pattern) || pattern.isBlank()) {
            throw new IllegalArgumentException("detector config requires a non-blank 'pattern'");
        }
        if (pattern.length() > MAX_PATTERN_LENGTH) {
            throw new IllegalArgumentException("pattern exceeds " + MAX_PATTERN_LENGTH + " characters");
        }
        return pattern;
    }

    private static String findingHash(UnifiedDiffParser.AddedLine line, String pattern, String excerptHash) {
        StringBuilder identity = new StringBuilder();
        appendField(identity, TYPE);
        appendField(identity, line.path());
        appendField(identity, Integer.toString(line.newLineNumber()));
        appendField(identity, Integer.toString(line.occurrence()));
        appendField(identity, pattern);
        appendField(identity, excerptHash);
        return sha256(identity.toString());
    }

    private static void appendField(StringBuilder target, String value) {
        target.append(value.length()).append(':').append(value);
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
