package com.mbworldwideapps.aiorchestration.core.diff;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.eclipse.jgit.patch.CombinedFileHeader;
import org.eclipse.jgit.patch.FileHeader;
import org.eclipse.jgit.patch.FormatError;
import org.eclipse.jgit.patch.HunkHeader;
import org.eclipse.jgit.patch.Patch;
import org.springframework.stereotype.Component;

@Component
public class UnifiedDiffParser {

    public static final int MAX_FILES = 200;
    public static final int MAX_HUNKS = 2_000;
    public static final int MAX_ADDED_LINES = 20_000;

    private static final byte[] NO_NEWLINE_MARKER =
            "\\ No newline at end of file".getBytes(StandardCharsets.US_ASCII);

    public ParseResult parse(String rawDiff) {
        RawParseResult rawResult = parseRaw(rawDiff);
        if (rawResult instanceof RawParseResult.Failure failure) {
            return new ParseResult.Failure(failure.reason(), failure.detail());
        }
        Patch patch = ((RawParseResult.Success) rawResult).patch();
        List<ParsedFile> files = new ArrayList<>();
        for (FileHeader fileHeader : patch.getFiles()) {
            List<DiffHunk> hunks = new ArrayList<>();
            for (HunkHeader hunkHeader : fileHeader.getHunks()) {
                hunks.add(new DiffHunk(
                        hunkHeader.getOldImage().getStartLine(),
                        hunkHeader.getOldImage().getLineCount(),
                        hunkHeader.getNewStartLine(),
                        hunkHeader.getNewLineCount()));
            }
            files.add(new ParsedFile(path(fileHeader), hunks));
        }
        return new ParseResult.Success(new ParsedPatch(files));
    }

    /**
     * Parses added source lines from standard two-way unified diff hunks. Unlike
     * prefix-based scanning, file headers are interpreted only by JGit and can
     * therefore never be spoofed by source content beginning with {@code ++}.
     */
    public AddedLinesResult extractAddedLines(String rawDiff) {
        return extractAddedLines(rawDiff, null);
    }

    /**
     * Extracts added lines only for the supplied repository-relative paths.
     * Unsupported files outside that scope are deliberately ignored; an
     * applicable file instead carries its own fail-closed status.
     */
    public AddedLinesResult extractAddedLines(String rawDiff, Set<String> applicablePaths) {
        RawParseResult rawResult = parseRaw(rawDiff);
        if (rawResult instanceof RawParseResult.Failure failure) {
            return new AddedLinesResult.Failure(failure.reason(), failure.detail());
        }

        Patch patch = ((RawParseResult.Success) rawResult).patch();
        Set<String> scope = applicablePaths == null ? null : Set.copyOf(applicablePaths);
        List<AddedFile> files = new ArrayList<>();
        Map<String, Integer> nextOccurrenceByPath = new HashMap<>();
        int selectedFiles = 0;
        int selectedHunks = 0;
        int selectedAddedLines = 0;
        for (FileHeader fileHeader : patch.getFiles()) {
            String filePath = path(fileHeader);
            if (scope != null && !scope.contains(filePath)) {
                continue;
            }
            if (++selectedFiles > MAX_FILES) {
                return limitExceeded("diff exceeds the applicable file limit of " + MAX_FILES);
            }

            // A rename/copy can move unchanged forbidden content into the rule's
            // scope. Added-line inspection is therefore insufficient even when
            // the metadata record also carries a small hunk.
            if (requiresFinalContent(fileHeader)) {
                files.add(new AddedFile(
                        filePath,
                        FileStatus.FINAL_CONTENT_REQUIRED,
                        List.of(),
                        "rename or copy requires the final file content"));
                continue;
            }

            if (fileHeader instanceof CombinedFileHeader
                    || fileHeader.getPatchType() != FileHeader.PatchType.UNIFIED) {
                files.add(new AddedFile(
                        filePath,
                        FileStatus.UNSUPPORTED,
                        List.of(),
                        "only standard text unified diffs are supported"));
                continue;
            }

            List<? extends HunkHeader> hunks = fileHeader.getHunks();
            if (hunks.size() > MAX_HUNKS - selectedHunks) {
                return limitExceeded("diff exceeds the applicable hunk limit of " + MAX_HUNKS);
            }
            selectedHunks += hunks.size();
            if (hunks.isEmpty()) {
                if (fileHeader.hasMetaDataChanges()) {
                    files.add(new AddedFile(filePath, FileStatus.EVALUATED, List.of(), ""));
                } else {
                    files.add(new AddedFile(
                            filePath,
                            FileStatus.MALFORMED,
                            List.of(),
                            "text diff contains no hunks"));
                }
                continue;
            }

            List<AddedLine> addedLines = new ArrayList<>();
            FileStatus status = FileStatus.EVALUATED;
            String detail = "";
            for (HunkHeader hunk : hunks) {
                ExtractionResult extraction = extractHunk(
                        filePath,
                        hunk,
                        nextOccurrenceByPath,
                        addedLines,
                        MAX_ADDED_LINES - selectedAddedLines);
                if (extraction instanceof ExtractionResult.Failure failure) {
                    if (failure.status() == FileStatus.LIMIT_EXCEEDED) {
                        return limitExceeded(failure.detail());
                    }
                    status = failure.status();
                    detail = failure.detail();
                    addedLines = List.of();
                    break;
                }
                selectedAddedLines += ((ExtractionResult.Success) extraction).addedLines();
            }
            files.add(new AddedFile(filePath, status, addedLines, detail));
        }
        return new AddedLinesResult.Success(files);
    }

    private static ExtractionResult extractHunk(String filePath, HunkHeader hunk,
            Map<String, Integer> nextOccurrenceByPath, List<AddedLine> addedLines,
            int remainingAddedLines) {
        byte[] buffer = hunk.getBuffer();
        int end = hunk.getEndOffset();
        int lineStart = nextLineStart(buffer, hunk.getStartOffset(), end);
        if (lineStart < 0) {
            return malformed("hunk header has no body");
        }

        int oldLines = 0;
        int newLines = 0;
        int extractedAddedLines = 0;
        int newLineNumber = hunk.getNewStartLine();
        while (lineStart < end) {
            int lineEnd = lineEnd(buffer, lineStart, end);
            byte marker = buffer[lineStart];
            switch (marker) {
                case '+' -> {
                    if (extractedAddedLines >= remainingAddedLines) {
                        return new ExtractionResult.Failure(
                                FileStatus.LIMIT_EXCEEDED,
                                "diff exceeds the applicable added-line limit of " + MAX_ADDED_LINES);
                    }
                    String content = new String(buffer, lineStart + 1,
                            lineEnd - lineStart - 1, StandardCharsets.UTF_8);
                    int occurrence = nextOccurrenceByPath.getOrDefault(filePath, 0);
                    addedLines.add(new AddedLine(filePath, newLineNumber, occurrence, content));
                    nextOccurrenceByPath.put(filePath, occurrence + 1);
                    extractedAddedLines++;
                    newLineNumber++;
                    newLines++;
                }
                case '-' -> oldLines++;
                case ' ' -> {
                    oldLines++;
                    newLines++;
                    newLineNumber++;
                }
                case '\\' -> {
                    if (!matches(buffer, lineStart, lineEnd, NO_NEWLINE_MARKER)) {
                        return malformed("hunk contains an invalid no-newline marker");
                    }
                }
                case '\n' -> {
                    // JGit accepts an unprefixed blank context line for compatibility.
                    oldLines++;
                    newLines++;
                    newLineNumber++;
                }
                default -> {
                    return malformed("hunk contains an unsupported line marker");
                }
            }
            lineStart = lineEnd < end && buffer[lineEnd] == '\n' ? lineEnd + 1 : end;
        }

        if (oldLines != hunk.getOldImage().getLineCount() || newLines != hunk.getNewLineCount()) {
            return malformed("hunk line counts do not match its header");
        }
        return new ExtractionResult.Success(extractedAddedLines);
    }

    private static ExtractionResult.Failure malformed(String detail) {
        return new ExtractionResult.Failure(FileStatus.MALFORMED, detail);
    }

    private static AddedLinesResult.Failure limitExceeded(String detail) {
        return new AddedLinesResult.Failure(FailureReason.LIMIT_EXCEEDED, detail);
    }

    private static boolean matches(byte[] buffer, int start, int end, byte[] expected) {
        if (end - start != expected.length) {
            return false;
        }
        for (int i = 0; i < expected.length; i++) {
            if (buffer[start + i] != expected[i]) {
                return false;
            }
        }
        return true;
    }

    private static boolean requiresFinalContent(FileHeader fileHeader) {
        return fileHeader.getChangeType() == org.eclipse.jgit.diff.DiffEntry.ChangeType.RENAME
                || fileHeader.getChangeType() == org.eclipse.jgit.diff.DiffEntry.ChangeType.COPY;
    }

    private static int nextLineStart(byte[] buffer, int start, int end) {
        int headerEnd = lineEnd(buffer, start, end);
        if (headerEnd >= end || buffer[headerEnd] != '\n') {
            return -1;
        }
        return headerEnd + 1;
    }

    private static int lineEnd(byte[] buffer, int start, int end) {
        int cursor = start;
        while (cursor < end && buffer[cursor] != '\n') {
            cursor++;
        }
        return cursor;
    }

    private static RawParseResult parseRaw(String rawDiff) {
        if (rawDiff == null || rawDiff.isBlank()) {
            return new RawParseResult.Failure(FailureReason.EMPTY, "diff is empty");
        }
        String canonicalDiff = rawDiff.replace("\r\n", "\n");
        Patch patch = new Patch();
        try {
            patch.parse(new ByteArrayInputStream(canonicalDiff.getBytes(StandardCharsets.UTF_8)));
        } catch (IOException e) {
            return new RawParseResult.Failure(FailureReason.IO_ERROR, e.getMessage());
        }
        if (!patch.getErrors().isEmpty()) {
            String detail = patch.getErrors().stream()
                    .map(FormatError::getMessage)
                    .collect(Collectors.joining("; "));
            return new RawParseResult.Failure(FailureReason.MALFORMED, detail);
        }
        if (patch.getFiles().isEmpty()) {
            return new RawParseResult.Failure(FailureReason.EMPTY, "diff contains no files");
        }
        return new RawParseResult.Success(patch);
    }

    private static String path(FileHeader fileHeader) {
        String path = fileHeader.getNewPath();
        if (path == null || "/dev/null".equals(path)) {
            path = fileHeader.getOldPath();
        }
        return normalizePath(path);
    }

    public static String normalizePath(String path) {
        // JGit has already removed the transport-only a/ and b/ prefixes and
        // decoded quoted Git paths. The remaining value is the repository path;
        // changing slashes, whitespace, or another leading a/ would be lossy.
        return path == null ? "" : path;
    }

    public sealed interface ParseResult permits ParseResult.Success, ParseResult.Failure {
        record Success(ParsedPatch patch) implements ParseResult {
        }

        record Failure(FailureReason reason, String detail) implements ParseResult {
        }
    }

    public sealed interface AddedLinesResult permits AddedLinesResult.Success, AddedLinesResult.Failure {
        record Success(List<AddedFile> files) implements AddedLinesResult {
            public Success {
                files = files == null ? List.of() : List.copyOf(files);
            }
        }

        record Failure(FailureReason reason, String detail) implements AddedLinesResult {
        }
    }

    public record AddedFile(String path, FileStatus status, List<AddedLine> lines, String detail) {
        public AddedFile {
            path = path == null ? "" : path;
            status = status == null ? FileStatus.MALFORMED : status;
            lines = lines == null ? List.of() : List.copyOf(lines);
            detail = detail == null ? "" : detail;
        }
    }

    public record AddedLine(String path, int newLineNumber, int occurrence, String content) {
    }

    public enum FileStatus {
        EVALUATED,
        UNSUPPORTED,
        FINAL_CONTENT_REQUIRED,
        MALFORMED,
        LIMIT_EXCEEDED
    }

    public enum FailureReason {
        EMPTY,
        MALFORMED,
        IO_ERROR,
        UNSUPPORTED,
        LIMIT_EXCEEDED
    }

    private sealed interface RawParseResult permits RawParseResult.Success, RawParseResult.Failure {
        record Success(Patch patch) implements RawParseResult {
        }

        record Failure(FailureReason reason, String detail) implements RawParseResult {
        }
    }

    private sealed interface ExtractionResult permits ExtractionResult.Success, ExtractionResult.Failure {
        record Success(int addedLines) implements ExtractionResult {
        }

        record Failure(FileStatus status, String detail) implements ExtractionResult {
        }
    }
}
