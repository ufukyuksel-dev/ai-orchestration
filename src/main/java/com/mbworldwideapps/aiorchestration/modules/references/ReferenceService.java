package com.mbworldwideapps.aiorchestration.modules.references;

import com.mbworldwideapps.aiorchestration.config.ReferenceProperties;
import com.mbworldwideapps.aiorchestration.modules.scanner.ScannerPayloadRedactor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.*;

/** One local-user reference root; project ownership belongs to memory links, not files. */
@Service
public class ReferenceService {
    private static final int MAX_FILE_BYTES = 262144;
    private static final LinkOption NOFOLLOW = LinkOption.NOFOLLOW_LINKS;
    public record Changed(UUID referenceId) {}
    private final org.springframework.context.ApplicationEventPublisher events;
    private final Path root;
    private final JdbcTemplate jdbc;
    private final ScannerPayloadRedactor redactor;
    private final TransactionTemplate transactions;

    public record Item(UUID id, String path, String kind, String hash, String status) {}
    public record Saved(Item item, boolean created, boolean redacted) {}
    public record Read(Item item, String text, Integer nextOffset, boolean redacted) {}
    public record Page(List<Item> items, String nextCursor, List<String> warnings) {}
    public record Section(String key, String title, List<String> hierarchy, int occurrence,
            int startByte, int endByte) {
        public Section {
            hierarchy = hierarchy == null ? List.of() : List.copyOf(hierarchy);
        }
    }
    public record SectionIndex(Item item, List<Section> sections) {
        public SectionIndex {
            sections = sections == null ? List.of() : List.copyOf(sections);
        }
    }
    public record SectionRead(Item item, String sectionKey, String title, String text,
            boolean contentComplete, String visibleHash, boolean redacted) {}

    public ReferenceService(ReferenceProperties properties, JdbcTemplate jdbc,
            ScannerPayloadRedactor redactor, PlatformTransactionManager manager, org.springframework.context.ApplicationEventPublisher events) {
        this.events = events;
        this.jdbc = jdbc;
        this.redactor = redactor;
        this.transactions = new TransactionTemplate(manager);
        try {
            Path configured = Path.of(properties.rootPath()).toAbsolutePath().normalize();
            if (Files.isSymbolicLink(configured)) throw new IllegalArgumentException("Reference root cannot be a symlink");
            Files.createDirectories(configured);
            this.root = configured.toRealPath();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot initialize reference root", e);
        }
    }

    public Saved mkdir(String relativePath) {
        String path = canonical(relativePath, false);
        return locked(() -> {
            Path target = checked(path);
            boolean created = !Files.exists(target, NOFOLLOW);
            if (!created && !Files.isDirectory(target, NOFOLLOW)) {
                throw new IllegalArgumentException("Reference path is not a directory");
            }
            createParents(target);
            return new Saved(index(path, "directory", null, "current"), created, false);
        });
    }

    public Saved write(String relativePath, String content, String expectedHash) {
        String path = canonical(relativePath, false);
        if (content == null || content.length() > MAX_FILE_BYTES) {
            throw new IllegalArgumentException("Reference content is required and bounded to256KiB");
        }
        byte[] original = utf8(content);
        if (original.length > MAX_FILE_BYTES) throw new IllegalArgumentException("Reference content exceeds256KiB");
        String safe = redactor.redact(content);
        byte[] bytes = utf8(safe);
        if (bytes.length > MAX_FILE_BYTES) throw new IllegalArgumentException("Redacted reference exceeds256KiB");
        if (expectedHash != null && !expectedHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("expectedHash must be a SHA-256 hash");
        }
        return locked(() -> {
            Path target = checked(path);
            boolean created = !Files.exists(target, NOFOLLOW);
            String newHash = hash(bytes);
            if (!created) {
                String current = hash(readBytes(target));
                if (current.equals(newHash)) return new Saved(index(path, "file", current, "current"), false,
                        !safe.equals(content));
                if (!current.equals(expectedHash)) throw new IllegalArgumentException("Reference hash conflict");
            } else if (expectedHash != null) {
                throw new IllegalArgumentException("Reference hash conflict: file is missing");
            }
            createParents(target.getParent());
            checked(path);
            Path temporary = Files.createTempFile(target.getParent(), ".reference-", ".tmp");
            try {
                Files.write(temporary, bytes, StandardOpenOption.TRUNCATE_EXISTING);
                checked(path);
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temporary);
            }
            return new Saved(index(path, "file", newHash, "current"), created, !safe.equals(content));
        });
    }

    public Read read(String relativePath, Integer offsetBytes, Integer maxBytes) {
        String path = canonical(relativePath, false);
        int offset = offsetBytes == null ? 0 : offsetBytes;
        int limit = maxBytes == null ? 16384 : maxBytes;
        if (offset < 0 || limit < 4 || limit > 65536) throw new IllegalArgumentException("Invalid read bounds");
        return locked(() -> {
            Path target = checked(path);
            if (!Files.exists(target, NOFOLLOW)) {
                Item missing = existing(path).orElseThrow(() -> new IllegalArgumentException("Reference not found"));
                Item marked = index(path, missing.kind(), missing.hash(), "missing");
                return new Read(marked, "", null, false);
            }
            byte[] data = readBytes(target);
            String currentHash = hash(data);
            Optional<Item> old = existing(path);
            String status = old.filter(i -> i.hash() != null && !currentHash.equals(i.hash())).isPresent()
                    ? "stale" : "current";
            // Keep the last verified hash when external edits are detected; reads do not approve drift.
            Item item = index(path, "file", status.equals("stale") ? old.orElseThrow().hash() : currentHash, status);
            String raw = decode(data);
            String safe = redactor.redact(raw);
            byte[] output = utf8(safe);
            if (offset > output.length || (offset < output.length && continuation(output[offset]))) {
                throw new IllegalArgumentException("Offset must be on a UTF-8 boundary");
            }
            int end = Math.min(output.length, offset + limit);
            while (end < output.length && continuation(output[end])) end--;
            return new Read(new Item(item.id(), path, "file", currentHash, status),
                    decode(Arrays.copyOfRange(output, offset, end)), end < output.length ? end : null, !raw.equals(safe));
        });
    }

    /** Resolve Markdown headings from current bytes; offsets never become durable addresses. */
    public SectionIndex sections(String relativePath) {
        String path = canonical(relativePath, false);
        requireMarkdown(path);
        return locked(() -> {
            CurrentFile current = currentFile(path);
            return new SectionIndex(current.item(), markdownSections(current.bytes()));
        });
    }

    /**
     * Read one section by stable key only when the caller's whole-file hash is
     * still current. The returned projection is redacted and UTF-8 bounded.
     */
    public SectionRead readSection(UUID referenceId, String expectedContentHash, String sectionKey,
            Integer requestedMaxBytes) {
        if (referenceId == null) throw new IllegalArgumentException("referenceId is required");
        if (expectedContentHash == null || !expectedContentHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("expectedContentHash must be a SHA-256 hash");
        }
        String key = boundedSectionKey(sectionKey);
        int maxBytes = requestedMaxBytes == null ? 8192 : requestedMaxBytes;
        if (maxBytes < 4 || maxBytes > 65536) throw new IllegalArgumentException("Invalid section read bound");
        SectionAttempt attempt = locked(() -> {
            Item catalog = existing(referenceId)
                    .orElseThrow(() -> new IllegalArgumentException("Reference not found"));
            requireMarkdown(catalog.path());
            Path target = checked(catalog.path());
            if (!Files.exists(target, NOFOLLOW)) {
                index(catalog.path(), catalog.kind(), catalog.hash(), "missing");
                return new SectionAttempt(null, "REFERENCE_MISSING");
            }
            byte[] bytes = readBytes(target);
            String currentHash = hash(bytes);
            if (!currentHash.equals(expectedContentHash)) {
                index(catalog.path(), "file", catalog.hash(), "stale");
                return new SectionAttempt(null, "REFERENCE_CONTENT_CHANGED");
            }
            Item current = index(catalog.path(), "file", currentHash, "current");
            Optional<Section> resolved = markdownSections(bytes).stream()
                    .filter(candidate -> candidate.key().equals(key)).findFirst();
            if (resolved.isEmpty()) return new SectionAttempt(null, "REFERENCE_SECTION_MISSING");
            Section section = resolved.orElseThrow();
            String raw = decode(Arrays.copyOfRange(bytes, section.startByte(), section.endByte()));
            String safe = redactor.redact(raw);
            byte[] visible = utf8(safe);
            int end = Math.min(visible.length, maxBytes);
            while (end < visible.length && continuation(visible[end])) end--;
            String text = decode(Arrays.copyOfRange(visible, 0, end));
            return new SectionAttempt(new SectionRead(current, section.key(), section.title(), text,
                    end == visible.length, hash(visible), !raw.equals(safe)), null);
        });
        if (attempt.error() != null) throw new IllegalArgumentException(attempt.error());
        return attempt.read();
    }

    public Page list(String directory, String cursor, Integer requestedLimit) {
        String dir = canonical(directory == null ? "" : directory, true);
        int limit = requestedLimit == null ? 25 : requestedLimit;
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("List limit must be1..100");
        String after = cursor == null ? "" : decodeCursor(cursor, dir);
        return locked(() -> {
            Path folder = checked(dir);
            if (!Files.isDirectory(folder, NOFOLLOW)) throw new IllegalArgumentException("Reference directory not found");
            List<String> paths = new ArrayList<>();
            List<String> warnings = new ArrayList<>();
            int inspected = 0;
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(folder)) {
                for (Path entry : entries) {
                    if (++inspected > 1000) throw new IllegalArgumentException("Directory exceeds1000-entry discovery bound");
                    if (Files.isSymbolicLink(entry) || (!Files.isRegularFile(entry, NOFOLLOW)
                            && !Files.isDirectory(entry, NOFOLLOW))) {
                        if (warnings.isEmpty()) warnings.add("Some unsupported, symbolic-link or noncanonical entries were omitted");
                        continue;
                    }
                    String path;
                    try {
                        path = canonical(root.relativize(entry).toString(), false);
                    } catch (IllegalArgumentException invalidName) {
                        if (warnings.isEmpty()) warnings.add("Some unsupported, symbolic-link or noncanonical entries were omitted");
                        continue;
                    }
                    paths.add(path);
                    String kind = Files.isDirectory(entry, NOFOLLOW) ? "directory" : "file";
                    Optional<Item> old = existing(path);
                    index(path, kind, kind.equals("file") ? old.map(Item::hash).orElse(null) : null,
                            kind.equals("directory") ? "current"
                                    : old.filter(i -> i.status().equals("stale")).isPresent() ? "stale" : "unverified");
                }
            }
            // Known absent entries remain visible; never delete their reference identity.
            jdbc.query("UPDATE reference_items SET status='missing',updated_at=now() WHERE parent_path=? "
                    + "AND status <> 'missing' AND NOT (relative_path = ANY (?)) RETURNING id",
                    rs -> { events.publishEvent(new Changed(rs.getObject(1, UUID.class))); }, dir, paths.toArray(String[]::new));
            List<Item> rows = jdbc.query("SELECT * FROM reference_items WHERE parent_path=? AND relative_path>? "
                            + "ORDER BY relative_path LIMIT ?", (rs, n) -> new Item(rs.getObject("id", UUID.class),
                            rs.getString("relative_path"), rs.getString("kind"), rs.getString("content_hash"),
                            rs.getString("status")), dir, after, limit + 1);
            boolean more = rows.size() > limit;
            List<Item> page = List.copyOf(rows.subList(0, Math.min(rows.size(), limit)));
            return new Page(page, more ? encodeCursor(dir, page.getLast().path()) : null, List.copyOf(warnings));
        });
    }

    private Item index(String path, String kind, String hash, String status) {
        Item result = jdbc.queryForObject("""
                INSERT INTO reference_items(id,relative_path,parent_path,kind,content_hash,status)
                VALUES (?,?,?,?,?,?) ON CONFLICT(relative_path) DO UPDATE SET
                    kind=EXCLUDED.kind, content_hash=EXCLUDED.content_hash, status=EXCLUDED.status,updated_at=now()
                RETURNING id,relative_path,kind,content_hash,status
                """, (rs, n) -> new Item(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getString(5)), UUID.randomUUID(), path, parent(path), kind, hash, status);
        events.publishEvent(new Changed(result.id()));
        return result;
    }

    private Optional<Item> existing(String path) {
        return jdbc.query("SELECT id,relative_path,kind,content_hash,status FROM reference_items WHERE relative_path=?",
                (rs, n) -> new Item(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5)), path).stream().findFirst();
    }

    private Optional<Item> existing(UUID id) {
        return jdbc.query("SELECT id,relative_path,kind,content_hash,status FROM reference_items WHERE id=?",
                (rs, n) -> new Item(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5)), id).stream().findFirst();
    }

    private CurrentFile currentFile(String path) throws IOException {
        Path target = checked(path);
        if (!Files.exists(target, NOFOLLOW)) {
            Item missing = existing(path).orElseThrow(() -> new IllegalArgumentException("Reference not found"));
            index(path, missing.kind(), missing.hash(), "missing");
            throw new IllegalArgumentException("REFERENCE_MISSING");
        }
        byte[] bytes = readBytes(target);
        String currentHash = hash(bytes);
        Optional<Item> old = existing(path);
        String status = old.filter(item -> item.hash() != null && !currentHash.equals(item.hash())).isPresent()
                ? "stale" : "current";
        Item indexed = index(path, "file", status.equals("stale") ? old.orElseThrow().hash() : currentHash, status);
        return new CurrentFile(new Item(indexed.id(), path, "file", currentHash, status), bytes);
    }

    static List<Section> markdownSections(byte[] bytes) throws CharacterCodingException {
        String text = decode(bytes);
        List<Heading> headings = new ArrayList<>();
        List<String> hierarchy = new ArrayList<>();
        Map<String, Integer> occurrences = new HashMap<>();
        int byteOffset = 0;
        Character fence = null;
        int fenceWidth = 0;
        for (String lineWithEnding : text.split("(?<=\\n)", -1)) {
            String line = lineWithEnding.endsWith("\n")
                    ? lineWithEnding.substring(0, lineWithEnding.length() - 1) : lineWithEnding;
            if (line.endsWith("\r")) line = line.substring(0, line.length() - 1);
            String trimmed = line.stripLeading();
            FenceMarker marker = fenceMarker(trimmed);
            if (marker != null) {
                if (fence == null) {
                    fence = marker.character();
                    fenceWidth = marker.width();
                } else if (marker.character() == fence && marker.width() >= fenceWidth) {
                    fence = null;
                    fenceWidth = 0;
                }
            } else if (fence == null) {
                java.util.regex.Matcher match = java.util.regex.Pattern
                        .compile("^(#{1,6})[ \\t]+(.+?)\\s*$").matcher(line);
                if (match.matches()) {
                    int level = match.group(1).length();
                    String title = match.group(2).replaceFirst("[ \\t]+#+[ \\t]*$", "").trim();
                    if (!title.isEmpty()) {
                        while (hierarchy.size() >= level) hierarchy.removeLast();
                        while (hierarchy.size() < level - 1) hierarchy.add("_");
                        String slug = slug(title);
                        String parent = String.join("/", hierarchy);
                        String occurrenceKey = parent + "\u0000" + level + "\u0000" + slug;
                        int occurrence = occurrences.merge(occurrenceKey, 1, Integer::sum);
                        String segment = slug + "~" + occurrence;
                        hierarchy.add(segment);
                        headings.add(new Heading(level, title, List.copyOf(hierarchy), occurrence, byteOffset));
                    }
                }
            }
            byteOffset += utf8(lineWithEnding).length;
        }
        List<Section> sections = new ArrayList<>();
        for (int index = 0; index < headings.size(); index++) {
            Heading heading = headings.get(index);
            int end = bytes.length;
            for (int next = index + 1; next < headings.size(); next++) {
                if (headings.get(next).level() <= heading.level()) {
                    end = headings.get(next).startByte();
                    break;
                }
            }
            sections.add(new Section(String.join("/", heading.hierarchy()), heading.title(),
                    heading.hierarchy(), heading.occurrence(), heading.startByte(), end));
        }
        return List.copyOf(sections);
    }

    private static FenceMarker fenceMarker(String line) {
        if (line.isEmpty() || (line.charAt(0) != '`' && line.charAt(0) != '~')) return null;
        char character = line.charAt(0);
        int width = 0;
        while (width < line.length() && line.charAt(width) == character) width++;
        return width >= 3 ? new FenceMarker(character, width) : null;
    }

    private static String slug(String title) {
        String normalized = Normalizer.normalize(title, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        String slug = normalized.replaceAll("[^\\p{L}\\p{N}]+", "-").replaceAll("^-|-$", "");
        if (slug.isEmpty()) slug = "section";
        return slug.length() <= 120 ? slug : slug.substring(0, 120);
    }

    private static String boundedSectionKey(String key) {
        if (key == null || key.isBlank() || key.length() > 1024
                || key.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("sectionKey must contain 1..1024 safe characters");
        }
        return key.trim();
    }

    private static void requireMarkdown(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".md") && !lower.endsWith(".markdown")) {
            throw new IllegalArgumentException("Section addressing requires Markdown");
        }
    }

    private <T> T locked(IoTask<T> action) {
        return transactions.execute(tx -> {
            jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", rs -> {}, root.toString());
            try { return action.run(); }
            catch (IOException e) { throw new IllegalStateException("Reference I/O failed", e); }
        });
    }

    private Path checked(String relative) {
        if (Files.isSymbolicLink(root) || !Files.isDirectory(root, NOFOLLOW)) {
            throw new IllegalArgumentException("Reference root changed");
        }
        Path current = root;
        if (!relative.isEmpty()) for (String segment : relative.split("/")) {
            current = current.resolve(segment);
            if (Files.isSymbolicLink(current)) throw new IllegalArgumentException("Reference symlinks are not allowed");
        }
        return current;
    }

    private void createParents(Path directory) throws IOException {
        Path current = root;
        for (Path part : root.relativize(directory)) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) throw new IllegalArgumentException("Reference symlinks are not allowed");
            Files.createDirectories(current);
            if (!Files.isDirectory(current, NOFOLLOW)) throw new IllegalArgumentException("Expected reference directory");
        }
    }

    private static byte[] readBytes(Path file) throws IOException {
        if (!Files.isRegularFile(file, NOFOLLOW)) throw new IllegalArgumentException("Reference is not a regular file");
        try (var stream = Files.newInputStream(file, NOFOLLOW)) {
            byte[] bytes = stream.readNBytes(MAX_FILE_BYTES + 1);
            if (bytes.length > MAX_FILE_BYTES) throw new IllegalArgumentException("Reference file exceeds256KiB");
            return bytes;
        }
    }

    private static String canonical(String path, boolean allowRoot) {
        if (path == null || (!allowRoot && path.isEmpty()) || path.length() > 1024
                || utf8(path).length > 1024 || !Normalizer.isNormalized(path, Normalizer.Form.NFC)
                || !path.equals(path.trim()) || path.startsWith("/") || path.contains("\\") || path.contains(":")
                || path.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Reference path must be a bounded canonical relative path");
        }
        if (allowRoot && path.isEmpty()) return path;
        String[] parts = path.split("/", -1);
        if (parts.length > 32 || Arrays.stream(parts).anyMatch(p -> p.isEmpty() || p.equals(".") || p.equals(".."))) {
            throw new IllegalArgumentException("Reference path contains invalid segments");
        }
        return path;
    }

    private static String parent(String path) { int slash = path.lastIndexOf('/'); return slash < 0 ? "" : path.substring(0, slash); }
    private static boolean continuation(byte b) { return (b & 0xc0) == 0x80; }
    private static String encodeCursor(String dir, String path) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(utf8(dir + "\n" + path));
    }
    private static String decodeCursor(String cursor, String dir) {
        if (cursor.length() > 3000) throw new IllegalArgumentException("Invalid reference cursor");
        try {
            String[] parts = decode(Base64.getUrlDecoder().decode(cursor)).split("\n", -1);
            if (parts.length != 2 || !parts[0].equals(dir) || !parent(parts[1]).equals(dir)) {
                throw new IllegalArgumentException("Reference cursor belongs to another directory");
            }
            return canonical(parts[1], false);
        } catch (IOException e) { throw new IllegalArgumentException("Invalid reference cursor", e); }
    }
    private static byte[] utf8(String text) {
        if (text == null) throw new IllegalArgumentException("Reference text is required");
        try {
            ByteBuffer buffer = StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                    .encode(java.nio.CharBuffer.wrap(text));
            byte[] bytes = new byte[buffer.remaining()]; buffer.get(bytes); return bytes;
        } catch (CharacterCodingException e) { throw new IllegalArgumentException("Invalid UTF-8 text", e); }
    }
    private static String decode(byte[] bytes) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    }
    private static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private record CurrentFile(Item item, byte[] bytes) {}
    private record SectionAttempt(SectionRead read, String error) {}
    private record Heading(int level, String title, List<String> hierarchy, int occurrence, int startByte) {}
    private record FenceMarker(char character, int width) {}
    @FunctionalInterface private interface IoTask<T> { T run() throws IOException; }
}
