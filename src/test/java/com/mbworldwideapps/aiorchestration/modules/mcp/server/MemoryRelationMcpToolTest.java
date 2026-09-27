package com.mbworldwideapps.aiorchestration.modules.mcp.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mbworldwideapps.aiorchestration.config.ReferenceProperties;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryRelationService;
import com.mbworldwideapps.aiorchestration.modules.references.ReferenceService;
import com.mbworldwideapps.aiorchestration.modules.scanner.ScannerPayloadRedactor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.springaicommunity.mcp.annotation.McpTool;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@Testcontainers
class MemoryRelationMcpToolTest {
    @Container static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:17-alpine");
    @TempDir Path temp;
    JdbcTemplate jdbc;
    MemoryRelationService service;
    MemoryRelationMcpTool tool;
    McpAuditLogger audit;
    ScannerPayloadRedactor redactor;
    ReferenceService references;
    final UUID low = UUID.fromString("00000000-0000-0000-0000-000000000001");
    final UUID high = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");

    @BeforeEach void setup() throws Exception {
        var ds = new DriverManagerDataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword());
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("DROP TABLE IF EXISTS memory_relations, reference_items, memory_items CASCADE");
        for (String migration : List.of("V2__memory_items.sql", "V53__reference_items.sql",
                "V54__memory_relations.sql", "V58__reference_sections.sql"))
            jdbc.execute(Files.readString(Path.of("src/main/resources/db/migration", migration)));
        redactor = mock(ScannerPayloadRedactor.class);
        when(redactor.redact(anyString())).thenAnswer(i -> i.getArgument(0));
        var manager = new DataSourceTransactionManager(ds);
        references = new ReferenceService(new ReferenceProperties(temp.resolve("references").toString()),
                jdbc, redactor, manager, event -> {});
        service = new MemoryRelationService(jdbc, redactor, manager, event -> {}, references);
        audit = mock(McpAuditLogger.class);
        tool = new MemoryRelationMcpTool(service, audit);
        context("P", "local", List.of("memory.write"));
        memory(low, "P", "active"); memory(high, "P", "active");
    }
    @AfterEach void cleanup() { McpClientContextHolder.clear(); }
    void context(String project, String key, List<String> scopes) {
        McpClientContextHolder.set(new McpClientContext(project, "test", key, scopes));
    }
    void memory(UUID id, String project, String state) {
        jdbc.update("""
                INSERT INTO memory_items(id,vector_id,scope,project_key,memory_type,summary,text,confidence,status,source_type)
                VALUES (?,?,'project',?,'decision',?,'detail',1,?,'manual')
                """, id, UUID.randomUUID(), project, id.toString(), state);
    }
    UUID reference(String kind) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO reference_items(id,relative_path,parent_path,kind,status,content_hash) VALUES (?,?,'',?,'current',?)",
                id, "private-procedure-" + id, kind, kind.equals("file") ? "a".repeat(64) : null);
        return id;
    }
    MemoryRelationService.Saved write(UUID source, UUID target, String type) {
        return tool.write("P", source.toString(), "memory", target.toString(), type, "evidence");
    }

    @Test void referenceHintsAreProjectScopedBoundedAndMarkDrift() {
        UUID file = reference("file"), directory = reference("directory");
        service.write("P", low, "reference", file, "references", "detail");
        service.write("P", low, "reference", directory, "references", "folder");
        var initial = service.linkedReferences("P", List.of(low));
        assertThat(initial.items()).hasSize(2);
        assertThat(initial.items()).allMatch(x -> !x.evidenceStale());
        assertThat(initial.items()).extracting(MemoryRelationService.ReferenceLink::kind).containsExactlyInAnyOrder("file", "directory");
        assertThat(service.linkedReferences("OTHER", List.of(low)).items()).isEmpty();
        jdbc.update("UPDATE memory_items SET text='changed' WHERE id=?", low);
        assertThat(service.linkedReferences("P", List.of(low)).items()).allMatch(MemoryRelationService.ReferenceLink::evidenceStale);
        jdbc.update("UPDATE reference_items SET status='missing' WHERE id=?", file);
        assertThat(service.linkedReferences("P", List.of(low)).items()).anyMatch(x -> x.catalogStatus().equals("missing"));
        for (int i=0;i<6;i++) service.write("P", low, "reference", reference("file"), "references", "more");
        var capped = service.linkedReferences("P", List.of(low));
        assertThat(capped.items()).hasSize(6); assertThat(capped.hasMore()).isTrue();
        assertThat(service.linkedReferences("P", List.of(low)).items()).isEqualTo(capped.items());
        jdbc.update("UPDATE memory_items SET expires_at=now()-interval '1 second' WHERE id=?", low);
        assertThat(service.linkedReferences("P", List.of(low)).items()).isEmpty();
        jdbc.update("UPDATE memory_items SET expires_at=NULL,status='archived' WHERE id=?", low);
        assertThat(service.linkedReferences("P", List.of(low)).items()).isEmpty();
    }

    @Test void sectionLinkRequiresCurrentHashAndOnlyThatProjectsDiscoveryBecomesStaleOnDrift() throws Exception {
        var saved = references.write("procedure.md", "# Deploy\nstep one\n# Rollback\nstep two\n", null);
        var section = references.sections("procedure.md").sections().getFirst();
        var legacy = tool.write("P", low.toString(), "reference", saved.item().id().toString(), "references",
                "deploy procedure");
        var linked = tool.write("P", low.toString(), "reference", saved.item().id().toString(), "references",
                "deploy procedure", section.key(), saved.item().hash());

        assertThat(linked.created()).isFalse();
        assertThat(linked.refreshed()).isTrue();
        assertThat(linked.relation().id()).isEqualTo(legacy.relation().id());
        assertThat(linked.relation().referenceSectionKey()).isEqualTo(section.key());
        assertThat(linked.relation().referenceContentHash()).isEqualTo(saved.item().hash());
        assertThat(service.linkedReferences("P", List.of(low)).items()).singleElement().satisfies(item -> {
            assertThat(item.sectionKey()).isEqualTo(section.key());
            assertThat(item.contentHash()).isEqualTo(saved.item().hash());
            assertThat(item.evidenceStale()).isFalse();
        });
        assertThat(service.linkedReferences("OTHER", List.of(low)).items()).isEmpty();
        assertThat(tool.write("P", low.toString(), "reference", saved.item().id().toString(), "references",
                "deploy procedure").relation().referenceSectionKey()).isEqualTo(section.key());

        Files.writeString(temp.resolve("references/procedure.md"), "# Deploy\nchanged\n# Rollback\nstep two\n");
        assertThatThrownBy(() -> tool.write("P", high.toString(), "reference", saved.item().id().toString(),
                "references", "deploy procedure", section.key(), saved.item().hash()))
                .hasMessage("REFERENCE_CONTENT_CHANGED");
        assertThat(service.linkedReferences("P", List.of(low)).items()).singleElement()
                .extracting(MemoryRelationService.ReferenceLink::evidenceStale).isEqualTo(true);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM memory_relations WHERE source_memory_id=?",
                Integer.class, high)).isZero();

        var refreshedSection = references.sections("procedure.md");
        var newDeploy = refreshedSection.sections().getFirst();
        var rollback = refreshedSection.sections().get(1);
        var refreshed = tool.write("P", low.toString(), "reference", saved.item().id().toString(), "references",
                "deploy procedure", newDeploy.key(), refreshedSection.item().hash());
        assertThat(refreshed.created()).isFalse();
        assertThat(refreshed.refreshed()).isTrue();
        assertThat(refreshed.relation().id()).isEqualTo(linked.relation().id());
        assertThat(refreshed.relation().referenceContentHash()).isEqualTo(refreshedSection.item().hash());
        assertThat(service.linkedReferences("P", List.of(low)).items()).singleElement().satisfies(item -> {
            assertThat(item.sectionKey()).isEqualTo(newDeploy.key());
            assertThat(item.contentHash()).isEqualTo(refreshedSection.item().hash());
            assertThat(item.evidenceStale()).isFalse();
        });
        assertThatThrownBy(() -> tool.write("P", low.toString(), "reference", saved.item().id().toString(),
                "references", "deploy procedure", rollback.key(), refreshedSection.item().hash()))
                .hasMessageContaining("Relationship conflict");
        assertThat(service.linkedReferences("P", List.of(low)).items()).singleElement()
                .extracting(MemoryRelationService.ReferenceLink::sectionKey).isEqualTo(newDeploy.key());
    }

    @Test void symmetricEdgesUseUnsignedUuidOrderAndOneIdentityWhileDirectedEdgesKeepDirection() {
        for (String type : List.of("related_to", "alternative_to")) {
            var first = write(high, low, type);
            var second = write(low, high, type);
            assertThat(first.created()).isTrue(); assertThat(second.created()).isFalse();
            assertThat(second.relation().id()).isEqualTo(first.relation().id());
            assertThat(first.relation().sourceId()).isEqualTo(low);
            assertThat(first.relation().sourceHash()).isEqualTo(MemoryRelationService.contentHash(low.toString(), "detail"));
        }
        assertThat(write(high, low, "depends_on").relation().id()).isNotEqualTo(write(low, high, "depends_on").relation().id());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM memory_relations", Integer.class)).isEqualTo(4);
    }

    @Test void concurrentReverseWritesReturnSameCanonicalId() throws Exception {
        try (var executor = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            List<Future<MemoryRelationService.Saved>> results = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                boolean reverse = i == 1;
                results.add(executor.submit(() -> {
                    start.await();
                    return service.write("P", reverse ? high : low, "memory", reverse ? low : high, "related_to", "same");
                }));
            }
            start.countDown();
            var a = results.get(0).get(10, TimeUnit.SECONDS); var b = results.get(1).get(10, TimeUnit.SECONDS);
            assertThat(a.relation().id()).isEqualTo(b.relation().id());
            assertThat(a.created()).isNotEqualTo(b.created());
        }
    }

    @Test void fileAndDirectoryAreSharedIdentitiesWithoutReturningPathsAndMissingTargetsFail() throws Exception {
        UUID other = UUID.randomUUID(); memory(other, "OTHER", "active");
        for (String kind : List.of("file", "directory")) {
            UUID ref = reference(kind);
            var saved = tool.write("P", low.toString(), "reference", ref.toString(), "references", "evidence");
            var shared = tool.write("OTHER", other.toString(), "reference", ref.toString(), "references", "evidence");
            assertThat(shared.relation().targetId()).isEqualTo(saved.relation().targetId());
            assertThat(shared.relation().id()).isNotEqualTo(saved.relation().id());
            assertThat(new ObjectMapper().writeValueAsString(saved)).doesNotContain("private-procedure", "relative_path");
            if (kind.equals("directory")) assertThat(saved.relation().targetHash()).isNull();
            jdbc.update("UPDATE reference_items SET status='missing' WHERE id=?", ref);
            assertThatThrownBy(() -> tool.write("P", high.toString(), "reference", ref.toString(), "references", "evidence"))
                    .hasMessageContaining("unavailable");
        }
    }

    @Test void rejectsCrossProjectPendingExpiredGlobalSelfAndDanglingWithoutChangingMemoryStatus() {
        UUID other = UUID.randomUUID(); memory(other, "OTHER", "active");
        assertThatThrownBy(() -> write(low, other, "extends")).hasMessageContaining("not eligible");
        assertThatThrownBy(() -> write(low, low, "extends")).hasMessageContaining("Self");
        assertThatThrownBy(() -> write(low, UUID.randomUUID(), "extends")).hasMessageContaining("not eligible");
        for (String status : List.of("pending_review", "rejected", "archived")) {
            jdbc.update("UPDATE memory_items SET status=? WHERE id=?", status, high);
            assertThatThrownBy(() -> write(low, high, "extends")).hasMessageContaining("not eligible");
            assertThat(jdbc.queryForObject("SELECT status FROM memory_items WHERE id=?", String.class, high)).isEqualTo(status);
        }
        jdbc.update("UPDATE memory_items SET status='active',expires_at=now()-interval '1 second' WHERE id=?", high);
        assertThatThrownBy(() -> write(low, high, "extends")).hasMessageContaining("not eligible");
        jdbc.update("UPDATE memory_items SET expires_at=null,scope='global' WHERE id=?", high);
        assertThatThrownBy(() -> write(low, high, "extends")).hasMessageContaining("not eligible");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM memory_relations", Integer.class)).isZero();
    }

    @Test void explicitExplanationIsProtectedWhileIdenticalReassertionRefreshesHashes() {
        var original = write(low, high, "extends");
        assertThatThrownBy(() -> tool.write("P", low.toString(), "memory", high.toString(), "extends", "different"))
                .hasMessageContaining("conflict");
        jdbc.update("UPDATE memory_items SET text='changed' WHERE id=?", high);
        var refreshed = write(low, high, "extends");
        assertThat(refreshed.refreshed()).isTrue();
        assertThat(refreshed.created()).isFalse();
        assertThat(refreshed.relation().id()).isEqualTo(original.relation().id());
        assertThat(refreshed.relation().targetHash()).isEqualTo(MemoryRelationService.contentHash(high.toString(), "changed"));
        assertThat(write(low, high, "extends").refreshed()).isFalse();
        assertThat(jdbc.queryForObject("SELECT explanation FROM memory_relations", String.class)).isEqualTo("evidence");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM memory_relations", Integer.class)).isEqualTo(1);
    }

    @Test void explicitWriteUpgradesJudgeEvidenceAndPreservesIdentityAndCreationTime() {
        var original = write(low, high, "related_to");
        jdbc.update("""
                UPDATE memory_relations SET provenance='judge',confidence=0.9,explanation='judge evidence',
                    created_at='2025-01-01T00:00:00Z',updated_at='2025-01-02T00:00:00Z' WHERE id=?
                """, original.relation().id());
        var created = jdbc.queryForObject("SELECT created_at FROM memory_relations", java.sql.Timestamp.class);
        jdbc.update("UPDATE memory_items SET text='changed',status='stale' WHERE id=?", high);
        when(redactor.redact("new secret evidence")).thenReturn("new [REDACTED] evidence");
        // Reverse caller order must still promote the one canonical symmetric identity.
        var result = tool.write("P", high.toString(), "memory", low.toString(), "related_to", "new secret evidence");
        assertThat(result.created()).isFalse();
        assertThat(result.refreshed()).isTrue();
        assertThat(result.relation().id()).isEqualTo(original.relation().id());
        assertThat(result.relation().sourceId()).isEqualTo(low);
        assertThat(result.relation().provenance()).isEqualTo("explicit");
        assertThat(result.relation().confidence()).isEqualTo(1);
        assertThat(result.relation().explanation()).isEqualTo("new [REDACTED] evidence");
        assertThat(result.relation().status()).isEqualTo("stale");
        assertThat(result.relation().sourceHash()).isEqualTo(MemoryRelationService.contentHash(low.toString(), "detail"));
        assertThat(result.relation().targetHash()).isEqualTo(MemoryRelationService.contentHash(high.toString(), "changed"));
        assertThat(jdbc.queryForObject("SELECT created_at FROM memory_relations", java.sql.Timestamp.class)).isEqualTo(created);
        assertThat(jdbc.queryForObject("SELECT updated_at > '2025-01-02T00:00:00Z' FROM memory_relations", Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM memory_relations", Integer.class)).isEqualTo(1);
        var beforeRetry = jdbc.queryForMap("SELECT * FROM memory_relations");
        assertThat(tool.write("P", low.toString(), "memory", high.toString(), "related_to", "new secret evidence").refreshed()).isFalse();
        assertThatThrownBy(() -> write(low, high, "related_to")).hasMessageContaining("conflict");
        assertThat(jdbc.queryForMap("SELECT * FROM memory_relations")).isEqualTo(beforeRetry);
    }

    @Test void promotionReportsRefreshedEvenWhenHashesStatusAndExplanationAlreadyMatch() {
        var original = write(low, high, "extends");
        jdbc.update("UPDATE memory_relations SET provenance='judge' WHERE id=?", original.relation().id());
        var result = write(low, high, "extends");
        assertThat(result.created()).isFalse();
        assertThat(result.refreshed()).isTrue();
        assertThat(result.relation().id()).isEqualTo(original.relation().id());
        assertThat(result.relation().provenance()).isEqualTo("explicit");
        assertThat(write(low, high, "extends").refreshed()).isFalse();
    }

    @Test void redactsBoundsAndRetainsStaleEvidenceWithoutAcceptingSupersedes() {
        when(redactor.redact("secret")).thenReturn("[REDACTED]");
        jdbc.update("UPDATE memory_items SET status='stale' WHERE id=?", high);
        var saved = tool.write("P", low.toString(), "memory", high.toString(), "extends", "secret");
        assertThat(saved.relation().explanation()).isEqualTo("[REDACTED]");
        assertThat(saved.relation().status()).isEqualTo("stale");
        for (String text : List.of("", "x".repeat(2049)))
            assertThatThrownBy(() -> tool.write("P", low.toString(), "memory", high.toString(), "causes", text))
                    .hasMessageContaining("2048");
        assertThatThrownBy(() -> write(low, high, "supersedes")).hasMessageContaining("review lifecycle");
        assertThat(jdbc.queryForObject("SELECT status FROM memory_items WHERE id=?", String.class, high)).isEqualTo("stale");
    }

    @Test void databaseEnforcesIdentityTargetShapeAndCanonicalOrder() {
        var saved = write(low, high, "related_to");
        assertThatThrownBy(() -> jdbc.update("UPDATE memory_relations SET source_memory_id=?,target_memory_id=? WHERE id=?",
                high, low, saved.relation().id())).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE memory_relations SET reference_id=?", reference("file")))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE memory_relations SET relationship_type='references'"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO memory_relations SELECT ?,project_key,source_memory_id,target_memory_id,reference_id,relationship_type,provenance,confidence,explanation,source_hash,target_hash,status,created_at,updated_at FROM memory_relations",
                UUID.randomUUID())).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test void bearerCanWriteWithinProjectButCannotProbeSharedReferencesAndAllOutcomesAreAudited() throws Exception {
        context("P", "bearer", List.of("memory.write"));
        assertThat(write(low, high, "related_to").created()).isTrue();
        assertThatThrownBy(() -> tool.write("OTHER", low.toString(), "memory", high.toString(), "causes", "evidence"))
                .isInstanceOf(McpAccessException.class);
        assertThatThrownBy(() -> tool.write("P", low.toString(), "reference", "not-even-a-uuid", "references", "evidence"))
                .isInstanceOf(McpAccessException.class).hasMessageNotContaining("private-procedure");
        context("P", "local", List.of());
        assertThatThrownBy(() -> write(low, high, "causes")).isInstanceOf(McpAccessException.class);
        context("P", "local", List.of("memory.write"));
        assertThatThrownBy(() -> write(low, low, "causes")).isInstanceOf(IllegalArgumentException.class);
        verify(audit).log(any(), eq("memory.relation.write"), isNull(), eq(1), any(Instant.class), eq("success"));
        verify(audit, times(3)).log(any(), eq("memory.relation.write"), isNull(), eq(0), any(Instant.class), eq("denied_scope"));
        verify(audit).log(any(), eq("memory.relation.write"), isNull(), eq(0), any(Instant.class), eq("error"));
        assertThat(MemoryRelationMcpTool.class.getMethod("write", String.class, String.class, String.class,
                String.class, String.class, String.class, String.class, String.class)
                .getAnnotation(McpTool.class).name()).isEqualTo("memory.relation.write");
    }
}
