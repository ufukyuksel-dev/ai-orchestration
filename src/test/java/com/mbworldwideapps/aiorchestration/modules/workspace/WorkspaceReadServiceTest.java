package com.mbworldwideapps.aiorchestration.modules.workspace;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mbworldwideapps.aiorchestration.config.JobMemoryProperties;
import com.mbworldwideapps.aiorchestration.modules.memoryai.RuleMemoryLinkLookup;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.*;

@Testcontainers
class WorkspaceReadServiceTest {
    @Container static PostgreSQLContainer<?> db = new PostgreSQLContainer<>("postgres:17-alpine");
    static JdbcTemplate jdbc;
    WorkspaceReadService service;
    RuleMemoryLinkLookup links;

    @BeforeAll
    static void migrate() {
        var ds = new DriverManagerDataSource(db.getJdbcUrl(), db.getUsername(), db.getPassword());
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(ds);
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() {
        links = mock(RuleMemoryLinkLookup.class);
        service =
                new WorkspaceReadService(
                        jdbc,
                        new ObjectMapper(),
                        links,
                        mock(ObjectProvider.class),
                        new JobMemoryProperties(null, null, null));
    }

    UUID memory(String project, String summary, String type) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO memory_items"
                    + " (id,vector_id,scope,project_key,memory_type,summary,text,confidence,status,source_type,owner)"
                    + " VALUES (?,?,'project',?,?,?,? ,0.8,'active','manual','test')",
                id,
                UUID.randomUUID(),
                project,
                type,
                summary,
                summary + " full content");
        return id;
    }

    @Test
    void keysetPaginationIsCompleteAndProjectBounded() {
        String project = "PAGE_" + UUID.randomUUID();
        Set<String> expected = new HashSet<>();
        for (int i = 0; i < 5; i++)
            expected.add("memory:" + memory(project, "Memory " + i, "decision"));
        memory(project + "_OTHER", "Other", "decision");
        String cursor = null;
        List<String> actual = new ArrayList<>();
        do {
            var page = service.list("memory", project, "", "", cursor, 2);
            actual.addAll(page.items().stream().map(WorkspaceReadService.Item::id).toList());
            cursor = page.nextCursor();
        } while (cursor != null);
        assertThat(actual).hasSize(5).containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    void emptyProjectsAndLiteralWildcardSearchDoNotInventMatches() {
        String project = "SEARCH_" + UUID.randomUUID();
        memory(project, "100% coverage", "decision");
        memory(project, "Other", "decision");
        assertThat(service.list("memory", project, "%", "", null, 10).items()).hasSize(1);
        assertThat(service.list("memory", "ABSENT", "", "", null, 10).items()).isEmpty();
        assertThatThrownBy(() -> service.list("memory", project, "", "", null, 101))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.list("DROP TABLE memory_items", project, "", "", null, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void linkedRuleMemoryIsReadOnlyButOrdinaryMemoryIsEditable() {
        UUID rule = memory("EDIT", "Immutable rule", "rule"),
                normal = memory("EDIT", "Editable", "decision");
        when(links.hasLinkedDefinition(rule)).thenReturn(true);
        assertThat(service.get("memory", rule.toString()).editableFields()).isEmpty();
        assertThat(service.get("memory", rule.toString()).blockedReason()).contains("rule's history");
        assertThat(service.get("memory", normal.toString()).editableFields())
                .containsExactly("summary", "text", "tags");
    }

    @Test
    void layeredMemoryUsesWholeProjectCountsAndSourceGroupsWithoutGlobalLeakage() {
        String project = "LAYER_" + UUID.randomUUID();
        UUID direct = memory(project, "Layer fixture direct", "decision");
        for (int i = 0; i < 4; i++) {
            UUID id = memory(project, "Layer fixture source " + i, "decision");
            jdbc.update("UPDATE memory_items SET source_ref='src/Repository.java' WHERE id=?", id);
        }
        List<String> uniqueIds = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            UUID id = memory(project, "Layer fixture unique " + i, "decision");
            jdbc.update("UPDATE memory_items SET source_ref=? WHERE id=?", "unique:" + i, id);
            uniqueIds.add("memory:" + id);
            UUID other = memory(project + "_OTHER", "Other project unique " + i, "decision");
            jdbc.update("UPDATE memory_items SET source_ref=? WHERE id=?", "unique:" + i, other);
        }
        UUID global = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO memory_items"
                    + " (id,vector_id,scope,memory_type,summary,text,confidence,status,source_type,owner)"
                    + " VALUES (?,?,'global','decision','Layer fixture"
                    + " global','global',1,'active','manual','test')",
                global,
                UUID.randomUUID());
        var root = service.hierarchy("memory", "", "", "Layer fixture", "", null, 100).items();
        assertThat(root).anyMatch(n -> n.id().equals("memory:" + global));
        assertThat(root)
                .filteredOn(n -> project.equals(n.project()))
                .singleElement()
                .satisfies(n -> assertThat(n.info().get("count").toString()).isEqualTo("8"));
        var projectNodes = service.hierarchy("memory", project, "", "", "", null, 100).items();
        assertThat(projectNodes).hasSize(5).noneMatch(n -> n.project() == null);
        assertThat(
                        projectNodes.stream()
                                .filter(n -> n.kind().equals("memory"))
                                .map(WorkspaceReadService.Item::id))
                .containsAll(uniqueIds);
        var group =
                projectNodes.stream()
                        .filter(n -> n.kind().equals("scope-group"))
                        .findFirst()
                        .orElseThrow();
        assertThat(group.title()).isEqualTo("Kaynak · src/");
        assertThat(
                        service.hierarchy(
                                        "memory",
                                        project,
                                        group.info().get("group").toString(),
                                        "source 0",
                                        "",
                                        null,
                                        20)
                                .items())
                .hasSize(1);
        assertThat(service.hierarchy("memory", project, "", "source 0", "", null, 20).items())
                .singleElement()
                .satisfies(n -> assertThat(n.kind()).isEqualTo("memory"));
        List<String> ids = new ArrayList<>();
        String cursor = null;
        do {
            var page =
                    service.hierarchy(
                            "memory",
                            project,
                            group.info().get("group").toString(),
                            "",
                            "",
                            cursor,
                            2);
            ids.addAll(page.items().stream().map(WorkspaceReadService.Item::id).toList());
            cursor = page.nextCursor();
        } while (cursor != null);
        assertThat(ids).hasSize(4).doesNotHaveDuplicates().doesNotContain("memory:" + direct);
        assertThat(service.hierarchy("memory", project, "", "absent", "", null, 20).items())
                .isEmpty();
        assertThat(service.hierarchy("rule", project, "", "", "", null, 20).items()).isEmpty();
    }

    @Test
    void memorySourcesGroupDirectoriesAndCuratedQueuesWithoutMergingArbitraryNamespaces() {
        String project = "SOURCE_" + UUID.randomUUID();
        for (String ref :
                List.of(
                        "src/A.java",
                        "src/B.java",
                        "auto-curated:queue:12:0",
                        "auto-curated:queue:25:1",
                        "manual:one",
                        "manual:two")) {
            UUID id = memory(project, ref, "decision");
            jdbc.update("UPDATE memory_items SET source_ref=? WHERE id=?", ref, id);
        }
        var nodes = service.hierarchy("memory", project, "", "", "", null, 20).items();
        assertThat(nodes).hasSize(4);
        assertThat(
                        nodes.stream()
                                .filter(n -> n.kind().equals("scope-group"))
                                .map(WorkspaceReadService.Item::title))
                .containsExactlyInAnyOrder("Kaynak · src/", "Kaynak · auto-curated:queue");
        assertThat(
                        nodes.stream()
                                .filter(n -> n.kind().equals("memory"))
                                .map(WorkspaceReadService.Item::title))
                .containsExactlyInAnyOrder("manual:one", "manual:two");
    }

    @Test
    @SuppressWarnings("unchecked")
    void codeUniversePagesSymbolsAndKeepsUnresolvedEdgesExplicit() {
        String project = "UNIVERSE_TEST";
        UUID file = UUID.randomUUID(), a = UUID.randomUUID(), b = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO code_files(id,project_key,file_path,content_hash,language) VALUES"
                    + " (?,?, 'module/src/Main.java','hash','java')",
                file,
                project);
        for (UUID id : List.of(a, b))
            jdbc.update(
                    "INSERT INTO"
                        + " code_symbols(id,project_key,file_id,symbol_kind,name,fqn,content_hash)"
                        + " VALUES (?,?,?,'method',?,?,'hash')",
                    id,
                    project,
                    file,
                    id.toString(),
                    id.toString());
        jdbc.update(
                "INSERT INTO"
                    + " code_edges(id,project_key,source_symbol_id,target_ref,edge_type,resolution,confidence)"
                    + " VALUES (?,?,?,'external.method','CALLS','name-only',0.45)",
                UUID.randomUUID(),
                project,
                a);
        var first = service.codeGraph(project, "nodes", null, 1);
        assertThat((List<?>) first.get("items")).hasSize(1);
        var second = service.codeGraph(project, "nodes", first.get("nextCursor").toString(), 1);
        assertThat(second.get("nextCursor")).isNull();
        assertThat(second.get("items")).isNotEqualTo(first.get("items"));
        var edge =
                ((List<Map<String, Object>>)
                                service.codeGraph(project, "edges", null, 10).get("items"))
                        .getFirst();
        assertThat(edge.get("target_symbol_id")).isNull();
        assertThat(edge.get("target_ref")).isEqualTo("external.method");
        assertThat((List<?>) service.codeGraph("OTHER", "nodes", null, 10).get("items")).isEmpty();
        assertThatThrownBy(() -> service.codeGraph(project, "nodes", null, 5001))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void explicitLocatorsAndMemoryRelationsAreVisibleWithoutAGraphDatabase() throws Exception {
        String project = "EXPLICIT_LINKS";
        UUID id = memory(project, "directory memory", "decision");
        UUID other = memory(project, "follow-up memory", "decision");
        var locator = new com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryCodeLocator(
                com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryCodeLocatorKind.DIRECTORY, "a/b", null, null);
        var metadata = com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryCodeLocatorMetadata.replace(Map.of(),
                List.of(locator), com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryScope.PROJECT, project);
        jdbc.update("UPDATE memory_items SET metadata=?::jsonb WHERE id=?",
                new ObjectMapper().writeValueAsString(metadata), id);
        UUID reference = UUID.randomUUID();
        jdbc.update("INSERT INTO reference_items(id,relative_path,parent_path,kind,status)"
                + " VALUES (?,'shared/notes','shared','directory','current')", reference);
        jdbc.update("INSERT INTO memory_relations(id,project_key,source_memory_id,reference_id,relationship_type,"
                + "provenance,confidence,explanation,source_hash,status) VALUES (?,?,?,?,'references','explicit',1,"
                + "'notes',?,'active')", UUID.randomUUID(), project, id, reference, "a".repeat(64));
        jdbc.update("INSERT INTO memory_relations(id,project_key,source_memory_id,target_memory_id,"
                + "relationship_type,provenance,confidence,explanation,source_hash,target_hash,status)"
                + " VALUES (?,?,?,?,'extends','explicit',1,'builds on it',?,?,'active')",
                UUID.randomUUID(), project, other, id, "b".repeat(64), "c".repeat(64));
        var baseline = mock(com.mbworldwideapps.aiorchestration.modules.scanner.CodeBaselineRepository.class);
        when(baseline.directoryExists(project, "a/b")).thenReturn(true);
        var reader = new WorkspaceReadService(jdbc, new ObjectMapper(), links, mock(ObjectProvider.class),
                new JobMemoryProperties(null, null, null));
        reader.setCodeLinks(
                new com.mbworldwideapps.aiorchestration.modules.memoryai.JdbcMemoryRepository(jdbc, new ObjectMapper(),
                        new com.mbworldwideapps.aiorchestration.config.RulesProperties(true, 20, 256, 4096, 200)),
                new com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryCodeLinkResolver(
                        com.mbworldwideapps.aiorchestration.config.MemoryCodeLinkProperties.defaults(), baseline,
                        mock(com.mbworldwideapps.aiorchestration.modules.scanner.CodeBaselineVectorIndex.class)));

        var result = reader.neighbors("memory", id.toString(), null, false, 50);

        assertThat(result.nodes()).filteredOn(n -> n.id().equals("directory:a/b"))
                .singleElement().satisfies(n -> assertThat(n.title()).isEqualTo("a/b"));
        assertThat(result.links()).anySatisfy(e -> {
            assertThat(e.target()).isEqualTo("directory:a/b");
            assertThat(e.label()).isEqualTo("MENTIONS");
        });
        assertThat(result.nodes()).anySatisfy(n -> {
            assertThat(n.id()).isEqualTo("reference:" + reference);
            assertThat(n.title()).isEqualTo("shared/notes");
        });
        assertThat(result.links()).anySatisfy(e -> {
            assertThat(e.source()).isEqualTo("memory:" + other);
            assertThat(e.target()).isEqualTo("memory:" + id);
            assertThat(e.label()).isEqualTo("MEMORY_EXTENDS");
        });
        assertThat(result.nodes()).extracting(WorkspaceReadService.Item::id).contains("memory:" + other);
        for (var edge : result.links())
            assertThat(result.nodes()).extracting(WorkspaceReadService.Item::id)
                    .contains(edge.source(), edge.target());
    }

    @Test
    void everyCanonicalSourceQueryExecutesAgainstRealSchema() {
        for (String kind :
                List.of(
                        "memory",
                        "rule",
                        "scan",
                        "file",
                        "symbol",
                        "capsule",
                        "checkpoint"))
            assertThatCode(() -> service.list(kind, "", "", "", null, 1))
                    .as(kind)
                    .doesNotThrowAnyException();
        assertThatCode(() -> service.projects()).doesNotThrowAnyException();
    }

    @Test
    void learnedKnowledgeLinksOnlyToExactlyResolvedCodeAnchorsInBothDirections() {
        String project = "LEARNED_EDGES";
        UUID file = UUID.randomUUID(), method = UUID.randomUUID(), overload = UUID.randomUUID();
        jdbc.update("INSERT INTO code_files(id,project_key,file_path,content_hash,language) VALUES"
                + " (?,?,'src/main/java/demo/OwnerController.java','hash','java')", file, project);
        jdbc.update("INSERT INTO code_symbols(id,project_key,file_id,symbol_kind,name,fqn,signature,content_hash)"
                + " VALUES (?,?,?,'method','find','demo.OwnerController#find','find(int,Owner)','h')",
                method, project, file);
        jdbc.update("INSERT INTO code_symbols(id,project_key,file_id,symbol_kind,name,fqn,signature,content_hash)"
                + " VALUES (?,?,?,'method','find','demo.OwnerController#find','find(String)','h')",
                overload, project, file);
        UUID learned = memory(project, "owner search redirect", "discovery");
        UUID unresolved = memory(project, "not yet resolved", "discovery");
        for (UUID id : List.of(learned, unresolved))
            jdbc.update("INSERT INTO memory_learning_profiles(memory_id,project_key,discovery_kind,origin,"
                    + "verification_level,usefulness_state,canonical_hash,producer_runtime,producer_contract_version,"
                    + "capture_coverage,semantic_identity,content_revision,evidence_revision) VALUES (?,?,"
                    + "'debug_finding','runtime_capture','SUPPORTED','ACTIVE',?,'claude_code','agent-learning/v2',"
                    + "'server_observed',?,?,?)", id, project, id.toString().replace("-", "").repeat(2),
                    new StringBuilder(id.toString().replace("-", "")).reverse().toString().repeat(2),
                    "c".repeat(64), "d".repeat(64));
        jdbc.update("INSERT INTO memory_navigation_anchors(id,memory_id,locator_index,canonical_ref,anchor_role,"
                + "priority,symbol_key,resolution_state,locator_kind) VALUES (?,?,0,"
                + "'src/main/java/demo/OwnerController.java','primary_change_point',1,"
                + "'demo.OwnerController#find(int,Owner)','RESOLVED','symbol')", UUID.randomUUID(), learned);
        jdbc.update("INSERT INTO memory_navigation_anchors(id,memory_id,locator_index,canonical_ref,anchor_role,"
                + "priority,symbol_key,resolution_state,locator_kind) VALUES (?,?,1,"
                + "'src/main/java/demo/OwnerController.java','supporting',2,null,'RESOLVED','file')",
                UUID.randomUUID(), learned);
        jdbc.update("INSERT INTO memory_navigation_anchors(id,memory_id,locator_index,canonical_ref,anchor_role,"
                + "priority,symbol_key,resolution_state,locator_kind) VALUES (?,?,0,"
                + "'src/main/java/demo/OwnerController.java','supporting',1,"
                + "'demo.OwnerController#find(int,Owner)','MISSING','symbol')", UUID.randomUUID(), unresolved);
        int index = 1;
        for (String state : List.of("MISSING", "AMBIGUOUS"))
            jdbc.update("INSERT INTO memory_navigation_anchors(id,memory_id,locator_index,canonical_ref,anchor_role,"
                    + "priority,symbol_key,resolution_state,locator_kind) VALUES (?,?,?,"
                    + "'src/main/java/demo/OwnerController.java','supporting',2,null,?,'file')",
                    UUID.randomUUID(), unresolved, index++, state);

        var fromMemory = service.neighbors("memory", learned.toString(), null, false, 50);
        assertThat(fromMemory.links()).extracting(WorkspaceReadService.Link::target)
                .contains("symbol:" + method, "file:" + file)
                .doesNotContain("symbol:" + overload);
        assertThat(fromMemory.links()).allSatisfy(link -> assertThat(link.inferred()).isFalse());

        var fromSymbol = service.neighbors("symbol", method.toString(), null, false, 50);
        assertThat(fromSymbol.links()).extracting(WorkspaceReadService.Link::target)
                .contains("memory:" + learned).doesNotContain("memory:" + unresolved);
        assertThat(service.neighbors("memory", unresolved.toString(), null, false, 50).links())
                .extracting(WorkspaceReadService.Link::target)
                .noneMatch(t -> t.startsWith("symbol:") || t.startsWith("file:"));
        assertThat(service.neighbors("file", file.toString(), null, false, 50).links())
                .extracting(WorkspaceReadService.Link::target)
                .contains("memory:" + learned).doesNotContain("memory:" + unresolved);
    }

    @Test
    void memoryListFiltersByScopeAndExactTagAndEventsAreNewestFirst() {
        String project = "FILTER_PROJECT";
        UUID tagged = memory(project, "tagged fact", "decision");
        UUID other = memory(project, "other fact", "decision");
        jdbc.update("UPDATE memory_items SET tags='[\"billing\",\"kiosk\"]'::jsonb WHERE id=?", tagged);
        jdbc.update("UPDATE memory_items SET tags='[\"billing-old\"]'::jsonb WHERE id=?", other);
        UUID global = UUID.randomUUID();
        jdbc.update("INSERT INTO memory_items (id,vector_id,scope,project_key,memory_type,summary,text,confidence,status,"
                + "source_type,owner) VALUES (?,?,'global',NULL,'decision','global fact','global fact text',0.8,'active',"
                + "'manual','test')", global, UUID.randomUUID());

        try {
        var byTag = service.list("memory", project, "", "", "", "", "billing", null, 50);
        assertThat(byTag.items()).extracting(WorkspaceReadService.Item::id).containsExactly("memory:" + tagged);
        var globalOnly = service.list("memory", project, "", "", "", "global", "", null, 50);
        assertThat(globalOnly.items()).extracting(WorkspaceReadService.Item::id).containsExactly("memory:" + global);
        var projectOnly = service.list("memory", project, "", "", "", "project", "", null, 50);
        assertThat(projectOnly.items()).extracting(WorkspaceReadService.Item::id)
                .containsExactlyInAnyOrder("memory:" + tagged, "memory:" + other);

        jdbc.update("INSERT INTO memory_events(id,memory_id,event_type,metadata,created_at) VALUES"
                + " (?,?,'created','{}'::jsonb,now()-interval '1 hour'),(?,?,'updated','{\"action\":\"edit\"}'::jsonb,now())",
                UUID.randomUUID(), tagged, UUID.randomUUID(), tagged);
        var events = service.memoryEvents(tagged.toString(), 10);
        assertThat(events).extracting(e -> e.get("type")).containsExactly("updated", "created");
        assertThat(((java.util.Map<?, ?>) events.getFirst().get("metadata")).get("action")).isEqualTo("edit");
        } finally {
            // Global rows are visible to every project's list; do not leak into sibling tests on the shared DB.
            jdbc.update("DELETE FROM memory_items WHERE id=?", global);
        }
    }
}
