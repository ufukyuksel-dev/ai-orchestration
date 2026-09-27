package com.mbworldwideapps.aiorchestration.modules.mcp.server;

import com.mbworldwideapps.aiorchestration.config.ReferenceProperties;
import com.mbworldwideapps.aiorchestration.modules.references.ReferenceService;
import com.mbworldwideapps.aiorchestration.modules.scanner.ScannerPayloadRedactor;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.springaicommunity.mcp.annotation.McpTool;

import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@Testcontainers
class ReferenceMcpToolTest {
    @Container static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:17-alpine");
    @TempDir Path temp;
    ReferenceService service;
    ReferenceMcpTool tool;
    JdbcTemplate jdbc;
    ScannerPayloadRedactor redactor;
    DataSourceTransactionManager manager;
    McpAuditLogger audit;
    Path root;

    @BeforeEach void setup() throws Exception {
        var ds = new DriverManagerDataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword());
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("DROP TABLE IF EXISTS reference_items");
        jdbc.execute(Files.readString(Path.of("src/main/resources/db/migration/V53__reference_items.sql")));
        manager = new DataSourceTransactionManager(ds);
        redactor = mock(ScannerPayloadRedactor.class);
        when(redactor.redact(anyString())).thenAnswer(i -> i.getArgument(0));
        audit = mock(McpAuditLogger.class);
        root = temp.resolve("ai-references");
        service = new ReferenceService(new ReferenceProperties(root.toString()), jdbc, redactor, manager, event -> {});
        tool = new ReferenceMcpTool(service, audit);
        local("PROJECT_A");
    }
    private void local(String project) {
        McpClientContextHolder.set(new McpClientContext(project, "test", "local", List.of("memory.read", "memory.write")));
    }
    @AfterEach void cleanup() { McpClientContextHolder.clear(); }

    @Test void sharedRootAndStableFileAndDirectoryIdentitiesSurviveAnotherProjectAndInstance() {
        assertThat(root).isDirectory();
        var dir = tool.mkdir("Pilotlama");
        assertThat(dir.created()).isTrue();
        assertThat(tool.mkdir("Pilotlama").item().id()).isEqualTo(dir.item().id());
        var file = tool.write("Pilotlama/sql.txt", "SELECT pilot_id", null);
        local("PROJECT_B");
        assertThat(tool.read("Pilotlama/sql.txt", null, null).item().id()).isEqualTo(file.item().id());
        var other = new ReferenceService(new ReferenceProperties(root.toString()), jdbc, redactor, manager, event -> {});
        assertThat(other.read("Pilotlama/sql.txt", null, null).text()).isEqualTo("SELECT pilot_id");
        assertThat(root.resolve("PROJECT_A")).doesNotExist();
        assertThat(tool.list("", null, 100).items()).extracting(ReferenceService.Item::kind).containsExactly("directory");
    }

    @Test void requiresCurrentHashAndSerializesConcurrentInstances() throws Exception {
        var saved = tool.write("notes.txt", "initial", null);
        assertThatThrownBy(() -> tool.write("notes.txt", "wrong", null)).hasMessageContaining("hash conflict");
        var other = new ReferenceService(new ReferenceProperties(root.toString()), jdbc, redactor, manager, event -> {});
        try (var executor = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                int n = i;
                results.add(executor.submit(() -> {
                    start.await();
                    try {
                        (n == 0 ? service : other).write("notes.txt", "update" + n, saved.item().hash());
                        return true;
                    } catch (IllegalArgumentException expected) { return false; }
                }));
            }
            start.countDown();
            assertThat(List.of(results.get(0).get(10, TimeUnit.SECONDS), results.get(1).get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reference_items", Integer.class)).isEqualTo(1);
    }

    @Test void rejectsTraversalSymlinksAndNonFilesWithoutChangingOutsideContent() throws Exception {
        Path outside = temp.resolve("outside.txt"); Files.writeString(outside, "outside");
        Files.createSymbolicLink(root.resolve("link"), outside);
        Files.createSymbolicLink(root.resolve("dirlink"), temp);
        for (String path : List.of("../outside.txt", "/etc/passwd", "a/../b", "a//b", "a/./b", "a\\b", "a/", "bad\nfile")) {
            assertThatThrownBy(() -> tool.write(path, "bad", null)).isInstanceOf(IllegalArgumentException.class);
        }
        for (String path : List.of("link", "dirlink/outside.txt")) {
            assertThatThrownBy(() -> tool.read(path, null, null)).hasMessageContaining("symlink");
            assertThatThrownBy(() -> tool.write(path, "bad", null)).hasMessageContaining("symlink");
        }
        assertThatThrownBy(() -> tool.mkdir("dirlink/child")).hasMessageContaining("symlink");
        tool.mkdir("folder");
        assertThatThrownBy(() -> tool.write("folder", "bad", null)).hasMessageContaining("regular file");
        assertThat(Files.readString(outside)).isEqualTo("outside");
        assertThat(tool.list("", null, 100).warnings()).isNotEmpty();
    }

    @Test void readsUtf8PagesRedactsBeforePagingAndReportsExternalDriftAndMissingFiles() throws Exception {
        var saved = tool.write("notes.txt", "éé😀尾", null);
        var first = tool.read("notes.txt", 0, 5);
        assertThat(first.text()).isEqualTo("éé");
        assertThat(first.nextOffset()).isEqualTo(4);
        assertThat(tool.read("notes.txt", first.nextOffset(), 4).text()).isEqualTo("😀");
        assertThatThrownBy(() -> tool.read("notes.txt", 1, 4)).hasMessageContaining("UTF-8 boundary");
        Files.writeString(root.resolve("notes.txt"), "changed");
        assertThat(tool.read("notes.txt", null, null).item().status()).isEqualTo("stale");
        assertThat(tool.read("notes.txt", null, null).item().hash()).isNotEqualTo(saved.item().hash());
        Files.delete(root.resolve("notes.txt"));
        assertThat(tool.read("notes.txt", null, null).item().status()).isEqualTo("missing");
        assertThat(tool.list("", null, 10).items()).extracting(ReferenceService.Item::status).contains("missing");
        when(redactor.redact("secret-value")).thenReturn("[REDACTED]");
        assertThat(tool.write("safe.txt", "secret-value", null).redacted()).isTrue();
        assertThat(Files.readString(root.resolve("safe.txt"))).isEqualTo("[REDACTED]");
    }

    @Test void listsBoundedPagesAndBindsCursorToDirectory() throws Exception {
        tool.mkdir("empty");
        Files.writeString(root.resolve("a.txt"), "external");
        tool.write("b.txt", "b", null);
        var page = tool.list("", null, 1);
        assertThat(page.items()).hasSize(1);
        assertThat(page.nextCursor()).isNotNull();
        assertThat(tool.list("", page.nextCursor(), 1).items().getFirst().path()).isEqualTo("b.txt");
        assertThatThrownBy(() -> tool.list("empty", page.nextCursor(), 1)).hasMessageContaining("another directory");
        assertThat(tool.read("a.txt", null, null).text()).isEqualTo("external");
        assertThatThrownBy(() -> tool.write("huge.txt", "é".repeat(131073), null)).hasMessageContaining("256KiB");
        assertThatThrownBy(() -> tool.list("", null, 101)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void markdownSectionsIgnoreFencesAndDisambiguateDuplicateHeadingsByHierarchyAndOccurrence() {
        var saved = tool.write("guide.md", """
                # Parent
                intro
                ## Step
                first
                ## Step
                second
                ```markdown
                # Not a heading
                ```
                # Other
                ## Step
                third
                """, null);

        var index = service.sections("guide.md");

        assertThat(index.item().id()).isEqualTo(saved.item().id());
        assertThat(index.sections()).extracting(ReferenceService.Section::key).containsExactly(
                "parent~1", "parent~1/step~1", "parent~1/step~2", "other~1", "other~1/step~1");
        assertThat(index.sections()).extracting(ReferenceService.Section::title)
                .doesNotContain("Not a heading");
        assertThat(index.sections().get(2).occurrence()).isEqualTo(2);
    }

    @Test void sectionReadIsHashBoundRedactedUtf8BoundedAndNeverFallsThroughToAnotherSection() throws Exception {
        Files.writeString(root.resolve("external.md"), "# İstenen\nsecret-éé😀\n# Başka\ndo-not-return\n");
        var index = service.sections("external.md");
        var wanted = index.sections().getFirst();
        when(redactor.redact(anyString())).thenAnswer(invocation ->
                ((String) invocation.getArgument(0)).replace("secret", "[REDACTED]"));

        var bounded = service.readSection(index.item().id(), index.item().hash(), wanted.key(), 16);

        assertThat(bounded.text()).doesNotContain("do-not-return");
        assertThat(bounded.redacted()).isTrue();
        assertThat(bounded.contentComplete()).isFalse();
        assertThat(bounded.visibleHash()).hasSize(64).isNotEqualTo(index.item().hash());
        assertThat(bounded.text().getBytes(java.nio.charset.StandardCharsets.UTF_8).length).isLessThanOrEqualTo(16);
        Files.writeString(root.resolve("external.md"), "# İstenen\nchanged\n");
        assertThatThrownBy(() -> service.readSection(index.item().id(), index.item().hash(), wanted.key(), 64))
                .hasMessage("REFERENCE_CONTENT_CHANGED");
        assertThat(jdbc.queryForObject("SELECT status FROM reference_items WHERE id=?", String.class,
                index.item().id())).isEqualTo("stale");
    }

    @Test void unsupportedDiskNamesDoNotHideValidOrMissingEntriesAndMkdirRejectsFiles() throws Exception {
        var retained = tool.write("missing.txt", "before", null);
        Files.delete(root.resolve("missing.txt"));
        Files.writeString(root.resolve("normal.txt"), "normal");
        Files.writeString(root.resolve("e\u0301.txt"), "NFD name");
        Files.writeString(root.resolve("bad:name.txt"), "colon name");
        var page = tool.list("", null, 100);
        assertThat(page.items()).extracting(ReferenceService.Item::path)
                .contains("normal.txt", "missing.txt").doesNotContain("e\u0301.txt", "bad:name.txt");
        assertThat(page.warnings()).hasSize(1);
        assertThat(page.items()).filteredOn(i -> i.id().equals(retained.item().id()))
                .extracting(ReferenceService.Item::status).containsExactly("missing");
        assertThatThrownBy(() -> tool.read("e\u0301.txt", null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tool.mkdir("normal.txt"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not a directory");
        assertThat(Files.readString(root.resolve("normal.txt"))).isEqualTo("normal");
    }

    @Test void limitsDiscoveryAndRedactsAnExternalFileBeforeReturningAnyPage() throws Exception {
        Files.writeString(root.resolve("external.txt"), "secret-value");
        when(redactor.redact("secret-value")).thenReturn("[REDACTED]");
        var first = tool.read("external.txt", 0, 4);
        assertThat(first.text()).isEqualTo("[RED");
        assertThat(first.redacted()).isTrue();
        assertThat(tool.read("external.txt", first.nextOffset(), 64).text()).isEqualTo("ACTED]");
        tool.mkdir("many");
        for (int i = 0; i < 1001; i++) Files.createFile(root.resolve("many/" + i));
        assertThatThrownBy(() -> tool.list("many", null, 10)).hasMessageContaining("1000-entry");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reference_items WHERE parent_path='many'", Integer.class)).isZero();
        verify(audit).log(any(), eq("reference.list"), isNull(), eq(0), any(Instant.class), eq("error"));
    }

    @Test void deniesAllBearerAndMissingScopeCallsBeforeFilesystemOrCatalogAccessAndAudits() {
        for (var context : List.of(new McpClientContext("P", "remote", "key", List.of("memory.read", "memory.write")),
                new McpClientContext("P", "local", "local", List.of()))) {
            McpClientContextHolder.set(context);
            assertThatThrownBy(() -> tool.mkdir("no")).isInstanceOf(McpAccessException.class);
            assertThatThrownBy(() -> tool.write("no", "secret", null)).isInstanceOf(McpAccessException.class);
            assertThatThrownBy(() -> tool.read("no", null, null)).isInstanceOf(McpAccessException.class);
            assertThatThrownBy(() -> tool.list("", null, 25)).isInstanceOf(McpAccessException.class);
            for (String name : List.of("mkdir", "write", "read", "list")) {
                verify(audit).log(eq(context), eq("reference." + name), isNull(), eq(0), any(Instant.class), eq("denied_scope"));
            }
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reference_items", Integer.class)).isZero();
        assertThat(root.resolve("no")).doesNotExist();
        assertThat(Arrays.stream(ReferenceMcpTool.class.getDeclaredMethods()).filter(m -> m.isAnnotationPresent(McpTool.class))
                .map(m -> m.getAnnotation(McpTool.class).name()).toList())
                .containsExactlyInAnyOrder("reference.mkdir", "reference.write", "reference.read", "reference.list");
    }
}
