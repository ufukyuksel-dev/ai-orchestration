package com.mbworldwideapps.aiorchestration.modules.memoryai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mbworldwideapps.aiorchestration.modules.scanner.ScannerPayloadRedactor;
import java.util.*;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryRelationJudgmentStore.*;

@Testcontainers
class MemoryRelationJudgmentStoreTest {
    @Container static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:17-alpine");
    final UUID a = UUID.fromString("00000000-0000-0000-0000-000000000001");
    final UUID b = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");
    JdbcTemplate jdbc;
    JdbcMemoryRepository memories;
    MemoryRelationJudgmentStore store;
    MemoryRelationService explicit;
    RuleMemoryInjectionFilter rules;
    final List<Object> published = new CopyOnWriteArrayList<>();

    @BeforeEach void setup() {
        var ds = new DriverManagerDataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword());
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("TRUNCATE memory_items CASCADE");
        memories = new JdbcMemoryRepository(jdbc, new ObjectMapper());
        rules = mock(RuleMemoryInjectionFilter.class);
        when(rules.eligibleForAutomaticInjection(any(), any())).thenReturn(true);
        var redactor = mock(ScannerPayloadRedactor.class);
        when(redactor.redact(anyString())).thenAnswer(i -> ((String) i.getArgument(0)).replace("secret", "[REDACTED]"));
        var manager = new DataSourceTransactionManager(ds);
        store = new MemoryRelationJudgmentStore(jdbc, memories, rules, new MemoryRelationJudgeContract(redactor),
                new ObjectMapper(), published::add, manager);
        explicit = new MemoryRelationService(jdbc, redactor, manager, published::add);
        for (UUID id : List.of(a, b)) jdbc.update("""
                INSERT INTO memory_items(id,vector_id,scope,project_key,memory_type,summary,text,confidence,status,source_type)
                VALUES (?,?,'project','P','decision',?,'detail',1,'active','manual')
                """, id, UUID.randomUUID(), id.toString());
    }
    Guard guard(UUID source, UUID target, String rubric) {
        var s = memories.findById(source).orElseThrow(); var t = memories.findById(target).orElseThrow();
        return new Guard("P", source, target, MemoryRelationService.contentHash(s.summary(), s.text()),
                MemoryRelationService.contentHash(t.summary(), t.text()), rubric);
    }
    Guard guard() { return guard(a, b, MemoryRelationJudgeContract.RUBRIC_VERSION); }
    String response(String type, double confidence) {
        return "{\"related\":" + !type.equals("unrelated") + ",\"relationshipType\":\"" + type
                + "\",\"confidence\":" + confidence + ",\"explanation\":\"secret evidence\"}";
    }
    Result complete(Guard g, String type) { return store.complete(g, response(type, .95), .85); }
    Map<String, Object> row(UUID id) { return jdbc.queryForMap("SELECT * FROM memory_relations WHERE id=?", id); }
    Map<String, Object> last() { return memories.eventsForMemory(a).getLast().metadata(); }
    int count(String table) { return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class); }

    @Test void refreshesJudgeOnlyWithCurrentHashesAndKeepsIdentityHistoryAndCreationTime() {
        Guard firstGuard = guard();
        var first = complete(firstGuard, "extends");
        assertThat(first.action()).isEqualTo(Action.EDGE);
        var before = row(first.relationId());
        assertThat(before).containsEntry("provenance", "judge").containsEntry("explanation", "[REDACTED] evidence");
        jdbc.update("UPDATE memory_items SET text='edited' WHERE id=?", a);
        var current = guard();
        assertThat(store.alreadyEvaluated(current)).isFalse();
        var result = complete(current, "extends");
        assertThat(result).isEqualTo(new Result(Action.EDGE, first.relationId(), true, "refreshed"));
        assertThat(row(result.relationId())).containsEntry("source_hash", current.sourceHash())
                .containsEntry("created_at", before.get("created_at")).containsEntry("status", "active");
        assertThat(count("memory_relations")).isEqualTo(1);
        assertThat(memories.eventsForMemory(a).getFirst().metadata()).containsEntry("sourceHash", firstGuard.sourceHash());
        assertThat(last()).containsEntry("candidateId", b.toString()).containsEntry("relationId", result.relationId().toString())
                .containsEntry("action", "EDGE").containsEntry("relationshipType", "extends").containsEntry("confidence", .95)
                .containsEntry("rubricVersion", current.rubricVersion()).containsEntry("targetHash", current.targetHash());
        assertThat(published).hasSize(2);
    }

    @Test void explicitUpgradeBlocksLaterJudgeAndPreservesEveryExplicitColumn() {
        var judged = complete(guard(), "extends");
        var upgraded = explicit.write("P", a, "memory", b, "extends", "human evidence");
        assertThat(upgraded.relation().id()).isEqualTo(judged.relationId());
        var before = row(judged.relationId());
        var result = complete(guard(), "extends");
        assertThat(result.action()).isEqualTo(Action.SKIPPED_EXPLICIT);
        assertThat(result.changed()).isFalse();
        assertThat(row(judged.relationId())).isEqualTo(before);
        assertThat(count("memory_relations")).isEqualTo(1);
        assertThat(last()).containsEntry("action", "SKIPPED_EXPLICIT");
    }

    @Test void concurrentSameGuardAppendsTwoEventsButMutatesOneRelationOnly() throws Exception {
        var start = new CountDownLatch(1);
        var g = guard();
        try (var pool = Executors.newFixedThreadPool(2)) {
            List<Future<Result>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) futures.add(pool.submit(() -> { start.await(); return complete(g, "extends"); }));
            start.countDown();
            var one = futures.getFirst().get(10, TimeUnit.SECONDS); var two = futures.getLast().get(10, TimeUnit.SECONDS);
            assertThat(one.relationId()).isEqualTo(two.relationId());
            assertThat(one.changed()).isNotEqualTo(two.changed());
            assertThat(count("memory_events")).isEqualTo(2);
            assertThat(count("memory_relations")).isEqualTo(1);
            assertThat(published).hasSize(1);
            var before = row(one.relationId());
            assertThat(store.alreadyEvaluated(g)).isTrue();
            assertThat(complete(g, "causes").changed()).isFalse();
            assertThat(row(one.relationId())).isEqualTo(before);
            assertThat(last()).containsEntry("relationshipType", "extends").containsEntry("reason", "already_evaluated");
        }
    }

    @Test void symmetricCanonicalHashesAreSwappedTogetherButGuardRemainsDirectional() {
        var g = guard(b, a, "v1");
        var result = complete(g, "alternative_to");
        assertThat(row(result.relationId())).containsEntry("source_memory_id", a).containsEntry("target_memory_id", b)
                .containsEntry("source_hash", g.targetHash()).containsEntry("target_hash", g.sourceHash());
        assertThat(store.alreadyEvaluated(g)).isTrue();
        assertThat(store.alreadyEvaluated(guard(a, b, "v1"))).isFalse();
    }

    @Test void freshNegativeVerdictRetiresJudgeEvidenceWithoutTouchingExplicitRowsOrMemoryStatus() {
        var old = complete(guard(), "extends");
        var human = explicit.write("P", a, "memory", b, "depends_on", "human");
        var humanBefore = row(human.relation().id());
        var result = complete(guard(a, b, "v2"), "unrelated");
        assertThat(result.action()).isEqualTo(Action.UNRELATED);
        assertThat(row(old.relationId())).containsEntry("status", "stale");
        assertThat(row(human.relation().id())).isEqualTo(humanBefore);
        assertThat(jdbc.queryForList("SELECT status FROM memory_items", String.class)).containsOnly("active");
        assertThat(count("memory_relations")).isEqualTo(2);
    }

    @Test void typeChangeRetiresOldTypeAndStaleCandidateNeverProducesActiveEvidence() {
        var old = complete(guard(), "extends");
        jdbc.update("UPDATE memory_items SET text='edited',status='stale' WHERE id=?", b);
        var result = complete(guard(), "causes");
        assertThat(result.action()).isEqualTo(Action.EDGE);
        assertThat(row(old.relationId())).containsEntry("status", "stale");
        assertThat(row(result.relationId())).containsEntry("status", "stale").containsEntry("relationship_type", "causes");
    }

    @Test void freshTypeThatAlreadyHasExplicitEvidenceStillRetiresPreviousJudgeType() {
        var old = complete(guard(), "extends");
        var human = explicit.write("P", a, "memory", b, "causes", "human");
        var before = row(human.relation().id());
        var result = complete(guard(a, b, "v2"), "causes");
        assertThat(result.action()).isEqualTo(Action.SKIPPED_EXPLICIT);
        assertThat(row(human.relation().id())).isEqualTo(before);
        assertThat(row(old.relationId())).containsEntry("status", "stale");
    }

    @Test void supersedesCreatesOnlyOneReviewProposalAndNeverChangesLifecycle() {
        var g = guard();
        assertThat(complete(g, "supersedes").action()).isEqualTo(Action.REVIEW);
        complete(g, "supersedes");
        jdbc.update("UPDATE memory_items SET text='edited replacement' WHERE id=?", a);
        assertThat(complete(guard(), "supersedes").action()).isEqualTo(Action.REVIEW);
        assertThat(count("memory_relations")).isZero();
        assertThat(count("memory_review_queue")).isEqualTo(1);
        var review = memories.reviewQueueForMemory(b).getFirst();
        assertThat(review.status()).isEqualTo(ReviewStatus.OPEN);
        assertThat(review.metadata()).containsEntry("replacementMemoryId", a.toString()).doesNotContainKey("supersededBy");
        assertThat(jdbc.queryForList("SELECT status FROM memory_items", String.class)).containsOnly("active");
        jdbc.update("UPDATE memory_review_queue SET status='rejected' WHERE candidate_memory_id=?", b);
        assertThat(complete(guard(a, b, "new-rubric"), "supersedes").action()).isEqualTo(Action.REVIEW);
        assertThat(count("memory_review_queue")).isEqualTo(2);
    }

    @Test void transientFailuresPreserveEvidenceAndLeaveSameAndNewRubricGuardsRetryable() {
        var initial = complete(guard(), "extends");
        for (Failure failure : List.of(Failure.TIMEOUT, Failure.DEGRADED, Failure.PROVIDER_ERROR, Failure.MALFORMED)) {
            for (Guard g : List.of(guard(), guard(a, b, "new-" + failure.name()))) {
                var before = row(initial.relationId());
                int publications = published.size();
                var failed = store.failed(g, failure);
                assertThat(failed).isEqualTo(new Result(Action.FAILED, null, false, failure.name()));
                assertThat(row(initial.relationId())).isEqualTo(before).containsEntry("status", "active");
                assertThat(count("memory_relations")).isEqualTo(1);
                assertThat(published).hasSize(publications);
                assertThat(last()).containsEntry("completed", false).containsEntry("action", "FAILED");
                assertThat(store.alreadyEvaluated(g)).isFalse();
                var retried = complete(g, "extends");
                assertThat(retried).isEqualTo(new Result(Action.EDGE, initial.relationId(), true, "refreshed"));
                assertThat(store.alreadyEvaluated(g)).isTrue();
            }
        }
    }

    @Test void malformedCompleteDoesNotHideFailureBehindPreviousEdgeOrRetireEvidence() {
        var g = guard();
        var initial = complete(g, "extends");
        var before = row(initial.relationId());
        var result = store.complete(g, "not JSON", .85);
        assertThat(result).isEqualTo(new Result(Action.FAILED, null, false, "MALFORMED"));
        assertThat(row(initial.relationId())).isEqualTo(before);
        assertThat(store.alreadyEvaluated(g)).isFalse();
        assertThat(last()).containsEntry("completed", false);
        assertThat(complete(g, "extends").reason()).isEqualTo("refreshed");
    }

    @Test void lowConfidenceIsStillACompletedSemanticVerdictThatRetiresOldEvidence() {
        var initial = complete(guard(), "extends");
        var g = guard(a, b, "new-rubric");
        assertThat(store.complete(g, response("extends", .1), .85).action()).isEqualTo(Action.LOW_CONFIDENCE);
        assertThat(row(initial.relationId())).containsEntry("status", "stale");
        assertThat(store.alreadyEvaluated(g)).isTrue();
        assertThat(last()).containsEntry("completed", true);
    }

    @Test void externalFailureApiRejectsInternalEligibilityReasonsWithoutWrites() {
        for (Failure failure : List.of(Failure.INPUT_CHANGED, Failure.INELIGIBLE, Failure.SOURCE_MISSING))
            assertThatThrownBy(() -> store.failed(guard(), failure)).isInstanceOf(IllegalArgumentException.class);
        assertThat(count("memory_events")).isZero();
        assertThat(count("memory_relations")).isZero();
    }

    @Test void forbiddenEdgeFixturesPersistNoEdges() {
        for (String output : List.of("bad json", "{}", response("unrelated", 1), response("extends", .1), response("supersedes", .1))) {
            jdbc.update("DELETE FROM memory_events");
            assertThat(store.complete(guard(), output, .85).action()).isIn(Action.FAILED, Action.UNRELATED, Action.LOW_CONFIDENCE);
            assertThat(count("memory_relations")).isZero();
        }
        for (Failure failure : List.of(Failure.TIMEOUT, Failure.DEGRADED, Failure.PROVIDER_ERROR)) {
            jdbc.update("DELETE FROM memory_events");
            assertThat(store.failed(guard(), failure).action()).isEqualTo(Action.FAILED);
            assertThat(last()).containsEntry("reason", failure.name());
            assertThat(count("memory_relations")).isZero();
        }
        jdbc.update("DELETE FROM memory_events");
        assertThat(complete(guard(a, a, "v1"), "extends").action()).isEqualTo(Action.FAILED);
        for (String update : List.of("project_key='OTHER'", "scope='global'", "status='pending_review'", "status='rejected'",
                "status='archived'", "expires_at=now()-interval '1 second'")) {
            jdbc.update("UPDATE memory_items SET project_key='P',scope='project',status='active',expires_at=null WHERE id=?", b);
            var g = guard();
            jdbc.update("UPDATE memory_items SET " + update + " WHERE id=?", b);
            assertThat(complete(g, "extends").action()).isEqualTo(Action.FAILED);
        }
        assertThat(count("memory_relations")).isZero();
        assertThat(count("memory_review_queue")).isZero();
    }

    @Test void staleInputAndFirstActivationDoNotPoisonTheGuard() {
        var old = guard();
        jdbc.update("UPDATE memory_items SET text='edited' WHERE id=?", a);
        assertThat(complete(old, "extends").reason()).isEqualTo("INPUT_CHANGED");
        assertThat(store.alreadyEvaluated(old)).isFalse();
        var active = guard();
        jdbc.update("UPDATE memory_items SET status='pending_review' WHERE id=?", a);
        assertThat(complete(active, "extends").reason()).isEqualTo("INELIGIBLE");
        jdbc.update("UPDATE memory_items SET status='active' WHERE id=?", a);
        assertThat(store.alreadyEvaluated(active)).isFalse();
        assertThat(complete(active, "extends").action()).isEqualTo(Action.EDGE);
    }

    @Test void missingAndRuleAuthorityEndpointsCannotProduceEdges() {
        var g = guard();
        when(rules.eligibleForAutomaticInjection(eq(b), any())).thenReturn(false);
        assertThat(complete(g, "extends").action()).isEqualTo(Action.FAILED);
        when(rules.eligibleForAutomaticInjection(eq(b), any())).thenReturn(true);
        jdbc.update("DELETE FROM memory_items WHERE id=?", b);
        assertThat(complete(g, "extends").action()).isEqualTo(Action.FAILED);
        jdbc.update("DELETE FROM memory_items WHERE id=?", a);
        assertThat(complete(g, "extends").reason()).isEqualTo("SOURCE_MISSING");
        assertThat(count("memory_relations")).isZero();
    }

    @Test void historicalRelationFromAnotherProjectIsNotRefreshedOrReportedAsAnEdge() {
        var old = complete(guard(), "extends");
        jdbc.update("UPDATE memory_relations SET project_key='OLD_PROJECT' WHERE id=?", old.relationId());
        var before = row(old.relationId());
        assertThat(complete(guard(a, b, "v2"), "extends").action()).isEqualTo(Action.FAILED);
        assertThat(row(old.relationId())).isEqualTo(before);
        assertThat(last()).containsEntry("action", "FAILED").containsEntry("completed", false);
    }

    @Test void eventWriteFailureRollsBackRelationMutation() {
        jdbc.execute("ALTER TABLE memory_events ADD CONSTRAINT test_reject_judge CHECK (event_type <> 'relation_judged') NOT VALID");
        try {
            assertThatThrownBy(() -> complete(guard(), "extends"))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThat(count("memory_relations")).isZero();
            assertThat(count("memory_events")).isZero();
        } finally {
            jdbc.execute("ALTER TABLE memory_events DROP CONSTRAINT test_reject_judge");
        }
    }
}
