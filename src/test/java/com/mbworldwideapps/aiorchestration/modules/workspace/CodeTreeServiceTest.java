package com.mbworldwideapps.aiorchestration.modules.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import com.mbworldwideapps.aiorchestration.modules.rules.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class CodeTreeServiceTest {
    @Container
    static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:17-alpine");

    JdbcTemplate jdbc;
    CodeTreeService tree;
    UUID owner;
    UUID getPetByName;
    UUID getPetById;

    @BeforeEach
    void setup() {
        var ds = new DriverManagerDataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword());
        var flyway = Flyway.configure().dataSource(ds).locations("classpath:db/migration").cleanDisabled(false).load();
        flyway.clean();
        flyway.migrate();
        jdbc = new JdbcTemplate(ds);
        tree = new CodeTreeService(jdbc);
        UUID ownerFile = file("src/main/java/p/owner/Owner.java");
        UUID controllerFile = file("src/main/java/p/owner/OwnerController.java");
        file("src/main/resources/messages/messages.properties");
        owner = symbol(ownerFile, "class", "Owner", "p.owner.Owner", "", 10);
        getPetByName = symbol(ownerFile, "method", "getPet", "p.owner.Owner#getPet", "getPet(String)", 20);
        getPetById = symbol(ownerFile, "method", "getPet", "p.owner.Owner#getPet", "getPet(Integer)", 30);
        UUID controller = symbol(controllerFile, "class", "OwnerController", "p.owner.OwnerController", "", 5);
        UUID show = symbol(controllerFile, "method", "show", "p.owner.OwnerController#show", "show(int)", 9);
        jdbc.update("INSERT INTO code_edges(id,project_key,source_symbol_id,target_symbol_id,edge_type,resolution,"
                + "confidence) VALUES (?,?,?,?,'CALLS','RESOLVED',1.0)", UUID.randomUUID(), "P", show, getPetById);
        memory("File memory", "{\"items\":[{\"kind\":\"file\",\"ref\":\"src/main/java/p/owner/Owner.java\"}]}");
        memory("Overload memory", "{\"items\":[{\"kind\":\"symbol\",\"ref\":\"p.owner.Owner#getPet(String)\","
                + "\"path\":\"src/main/java/p/owner/Owner.java\"}]}");
        rule("Never log personal data in owner code.", "path_glob", "src/main/java/p/owner/**");
    }

    @Test
    void opensLayerByLayerFromProjectToOverloadedMembers() {
        var root = tree.children("P", "", null);
        assertThat(root.node().kind()).isEqualTo("project");
        assertThat(root.children()).extracting(CodeTreeService.Node::name)
                .containsExactly("p.owner", "src/main/resources/messages");

        var pkg = root.children().getFirst();
        assertThat(pkg.path()).isEqualTo("src/main/java/p/owner");
        assertThat(pkg.memoryCount()).isEqualTo(2);
        assertThat(pkg.ruleCount()).isEqualTo(1);

        var classes = tree.children("P", pkg.id(), null).children();
        assertThat(classes).extracting(CodeTreeService.Node::name).containsExactly("Owner", "OwnerController");
        var ownerNode = classes.getFirst();
        assertThat(ownerNode.kind()).isEqualTo("class");
        assertThat(ownerNode.childCount()).isEqualTo(2);
        assertThat(ownerNode.memoryCount()).isEqualTo(2);

        var members = tree.children("P", owner.toString(), null).children();
        assertThat(members).extracting(CodeTreeService.Node::name)
                .containsExactly("getPet(String)", "getPet(Integer)");
        assertThat(members).extracting(CodeTreeService.Node::memoryCount).containsExactly(1, 0);

        var files = tree.children("P", "pkg:src/main/resources/messages", null).children();
        assertThat(files).singleElement().satisfies(f -> {
            assertThat(f.kind()).isEqualTo("file");
            assertThat(f.id()).isEqualTo("file:src/main/resources/messages/messages.properties");
        });
    }

    @Test
    void attachedItemsAndSelectedSymbolEdges() {
        var onOverload = tree.attached("P", getPetByName.toString());
        assertThat(onOverload.memories()).extracting(CodeTreeService.Attached::summary)
                .containsExactly("Overload memory");
        assertThat(tree.attached("P", getPetById.toString()).memories()).isEmpty();

        var onPackage = tree.attached("P", "pkg:src/main/java/p/owner");
        assertThat(onPackage.rules()).singleElement().satisfies(r -> {
            assertThat(r.summary()).startsWith("Never log");
            assertThat(r.target()).isEqualTo("src/main/java/p/owner/**");
        });

        var symbolView = tree.attachedToSymbols("P", List.of(getPetById));
        assertThat(symbolView.memories()).extracting(CodeTreeService.Attached::summary)
                .containsExactly("File memory");

        var edges = tree.edges("P", owner.toString());
        assertThat(edges.edges()).singleElement().satisfies(e -> {
            assertThat(e.target()).isEqualTo(getPetById.toString());
            assertThat(e.edgeType()).isEqualTo("CALLS");
        });
        assertThat(edges.nodes()).extracting(CodeTreeService.Node::name).contains("getPet(Integer)", "show(int)");
    }

    @Test
    void linksAggregateCodeEdgesToTheOpenLevel() {
        assertThat(tree.links("P", "").links()).isEmpty(); // the only call stays inside one package
        var classLevel = tree.links("P", "pkg:src/main/java/p/owner").links();
        assertThat(classLevel).singleElement().satisfies(l -> {
            assertThat(java.util.Set.of(l.source(), l.target())).contains(owner.toString());
            assertThat(l.weight()).isEqualTo(1);
        });
        assertThat(tree.links("P", owner.toString()).links()).isEmpty(); // no call between Owner's own members
    }

    @Test
    void searchFindsClosedNodesWithThePathToOpen() {
        var hits = tree.search("P", "getpet", 10).hits();
        assertThat(hits).extracting(h -> h.node().name()).containsExactly("getPet(Integer)", "getPet(String)");
        assertThat(hits.getFirst().path()).containsExactly("pkg:src/main/java/p/owner", owner.toString());

        var cls = tree.search("P", "Owner", 10).hits();
        assertThat(cls.getFirst().node().name()).isEqualTo("Owner");
        assertThat(cls.getFirst().path()).containsExactly("pkg:src/main/java/p/owner");
        assertThat(cls).extracting(h -> h.node().name()).contains("OwnerController", "p.owner");

        assertThat(tree.search("P", "messages.prop", 10).hits()).singleElement()
                .satisfies(h -> assertThat(h.path()).containsExactly("pkg:src/main/resources/messages"));
        assertThat(tree.search("P", "  ", 10).hits()).isEmpty();
    }

    @Test
    void sameClassFqnUnderTwoSourceRootsKeepsMembersApart() {
        UUID testFile = file("src/test/java/p/owner/Owner.java");
        UUID testOwner = symbol(testFile, "class", "Owner", "p.owner.Owner", "", 3);
        symbol(testFile, "method", "fixture", "p.owner.Owner#fixture", "fixture()", 4);

        var packages = tree.children("P", "", null).children();
        assertThat(packages).extracting(CodeTreeService.Node::name)
                .contains("p.owner · src/main", "p.owner · src/test");

        assertThat(tree.children("P", owner.toString(), null).children()).extracting(CodeTreeService.Node::name)
                .containsExactly("getPet(String)", "getPet(Integer)");
        assertThat(tree.children("P", testOwner.toString(), null).children()).extracting(CodeTreeService.Node::name)
                .containsExactly("fixture()");
        // the overload memory names src/main's Owner.java: it is not shown on the test class
        assertThat(tree.attached("P", testOwner.toString()).memories()).isEmpty();
        assertThat(tree.edges("P", testOwner.toString()).edges()).isEmpty();
    }

    private UUID file(String path) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO code_files(id,project_key,file_path,content_hash,language) VALUES (?,?,?,?,?)",
                id, "P", path, "h" + id, path.endsWith(".java") ? "java" : "properties");
        return id;
    }

    private UUID symbol(UUID file, String kind, String name, String fqn, String signature, int line) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO code_symbols(id,project_key,file_id,symbol_kind,name,fqn,signature,start_line,"
                + "content_hash) VALUES (?,?,?,?,?,?,?,?,?)", id, "P", file, kind, name, fqn, signature, line, "h" + id);
        return id;
    }

    private void memory(String summary, String locators) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO memory_items(id,vector_id,scope,project_key,memory_type,summary,text,confidence,status,"
                + "source_type,metadata) VALUES (?,?,'project','P','decision',?,?,0.9,'active','mcp_external',"
                + "jsonb_build_object('codeLocators', ?::jsonb))", id, id, summary, summary + " text", locators);
    }

    private void rule(String statement, String kind, String key) {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var promotion = new RulePromotionService(new JdbcRuleRepository(jdbc, mapper),
                org.mockito.Mockito.mock(com.mbworldwideapps.aiorchestration.modules.memoryai.RuleMemoryPromotionPort.class),
                new com.mbworldwideapps.aiorchestration.config.RulesProperties(true, 20, 256, 4096, 200), mapper,
                List.of());
        var authoring = new RuleAuthoringService(new JdbcRuleAuthoringDraftRepository(jdbc), promotion, mapper);
        var tx = new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource()));
        jdbc.update("INSERT INTO scanner_project_roots(project_key,root_path) VALUES ('P','/p') ON CONFLICT DO NOTHING");
        var request = new DirectRuleDraftRequest(statement, null, RuleEnforcement.INSTRUCTION, false, null, null,
                List.of(new RulePromotionCandidate.TargetBindingRequest(BindingKind.from(kind), key)),
                List.of(), List.of());
        var draft = authoring.draft("P", request, "test");
        var preview = authoring.preview(draft.draftId(), "P");
        tx.execute(s -> authoring.promote(new DirectRulePromotionRequest(draft.draftId(), draft.projectKey(),
                draft.candidateHash(), preview.approvalContentHash(), preview.confirmationCardHash(),
                preview.workflowContractVersion(), "TEST approval in disposable PostgreSQL only", "test:1", true, 1),
                "test"));
    }
}
