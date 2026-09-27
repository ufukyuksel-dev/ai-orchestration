package com.mbworldwideapps.aiorchestration.modules.memoryai;

import com.mbworldwideapps.aiorchestration.modules.llmgateway.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mbworldwideapps.aiorchestration.config.MemoryRelationJudgeProperties;
import com.mbworldwideapps.aiorchestration.modules.scanner.ScannerPayloadRedactor;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.event.TransactionalEventListenerFactory;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@Testcontainers
class MemoryRelationJudgeWorkerTest {
    @Container static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:17-alpine");
    JdbcTemplate jdbc;
    JdbcMemoryRepository memories;
    DataSourceTransactionManager manager;
    MemoryVectorIndex vectors;
    LLMGateway gateway;
    RuleMemoryInjectionFilter rules;
    MemoryRelationJudgmentStore store;
    AnnotationConfigApplicationContext context;
    final UUID source = UUID.randomUUID(), target = UUID.randomUUID();
    final List<CountDownLatch> releases = new ArrayList<>();

    @BeforeEach void setup() {
        var ds = new DriverManagerDataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword());
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(ds); jdbc.execute("TRUNCATE memory_items CASCADE");
        memories = new JdbcMemoryRepository(jdbc, new ObjectMapper());
        manager = new DataSourceTransactionManager(ds);
        vectors = mock(MemoryVectorIndex.class); gateway = mock(LLMGateway.class);
        rules = mock(RuleMemoryInjectionFilter.class);
        when(rules.eligibleForAutomaticInjection(any(), any())).thenReturn(true);
        memory(source); memory(target);
        when(vectors.search(anyString(), eq(6), any())).thenAnswer(i -> List.of(new ScoredMemoryRef(item(target), .9)));
        when(gateway.generate(any())).thenReturn(answer("extends", .95, false));
    }
    @AfterEach void close() {
        releases.forEach(CountDownLatch::countDown);
        if (context != null) context.close();
    }
    void start(boolean enabled, int capacity, Duration timeout) {
        context = new AnnotationConfigApplicationContext();
        context.registerBean("transactionalEventListenerFactory", TransactionalEventListenerFactory.class);
        var redactor = mock(ScannerPayloadRedactor.class);
        when(redactor.redact(anyString())).thenAnswer(i -> ((String) i.getArgument(0)).replace("secret", "[REDACTED]"));
        var contract = new MemoryRelationJudgeContract(redactor);
        store = new MemoryRelationJudgmentStore(jdbc, memories, rules, contract, new ObjectMapper(), context, manager);
        context.registerBean(MemoryVectorIndexingListener.class, () -> new MemoryVectorIndexingListener(vectors));
        context.registerBean(MemoryRelationJudgeWorker.class, () -> new MemoryRelationJudgeWorker(memories, vectors, rules,
                contract, store, redactor, gateway, new MemoryRelationJudgeProperties(enabled, capacity, timeout, .85)));
        context.refresh();
    }
    void start() { start(true, 16, Duration.ofSeconds(2)); }
    void memory(UUID id) {
        jdbc.update("""
                INSERT INTO memory_items(id,vector_id,scope,project_key,memory_type,summary,text,confidence,status,source_type)
                VALUES (?,?,'project','P','decision',?,'detail',1,'active','manual')
                """, id, UUID.randomUUID(), id.toString());
    }
    MemoryItem item(UUID id) { return memories.findById(id).orElseThrow(); }
    void emit(UUID id) { context.publishEvent(new MemoryItemChangedEvent(item(id))); }
    void commit() { new TransactionTemplate(manager).executeWithoutResult(tx -> emit(source)); }
    int count(String table) { return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class); }
    void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
        assertThat(condition.getAsBoolean()).isTrue();
    }
    GenerationResponse answer(String type, double confidence, boolean degraded) {
        String text = "{\"related\":" + !type.equals("unrelated") + ",\"relationshipType\":\"" + type
                + "\",\"confidence\":" + confidence + ",\"explanation\":\"evidence\"}";
        return new GenerationResponse(text, "stub", 1, degraded, DegradedReason.PROVIDER_ERROR, 0,0,0,0,0);
    }
    Map<String,Object> last() { return memories.eventsForMemory(source).getLast().metadata(); }
    MemoryRelationJudgmentStore.Guard guard() {
        return new MemoryRelationJudgmentStore.Guard("P", source, target,
                MemoryRelationService.contentHash(item(source).summary(), item(source).text()),
                MemoryRelationService.contentHash(item(target).summary(), item(target).text()), MemoryRelationJudgeContract.RUBRIC_VERSION);
    }

    @Test void transactionAndEmbeddingOrderFirstActivationAndCompletedGuard() throws Exception {
        start();
        jdbc.update("UPDATE memory_items SET status='pending_review' WHERE id=?", source);
        commit();
        verify(vectors).delete(any());
        verifyNoInteractions(gateway);
        jdbc.update("UPDATE memory_items SET status='active' WHERE id=?", source);
        new TransactionTemplate(manager).executeWithoutResult(tx -> {
            emit(source);
            verify(vectors).upsert(any());
            verifyNoInteractions(gateway);
        });
        await(() -> count("memory_relations") == 1);
        assertThat(store.alreadyEvaluated(guard())).isTrue();
        var order = inOrder(vectors, gateway);
        order.verify(vectors).upsert(any()); order.verify(vectors).search(anyString(), eq(6), any());
        order.verify(gateway).generate(any());
        commit();
        verify(vectors, timeout(3000).times(2)).search(anyString(), eq(6), any());
        verify(gateway, after(100).times(1)).generate(any());
    }

    @Test void rollbackEmbeddingFailureAndNontransactionalEventDoNotRunJudge() {
        start();
        new TransactionTemplate(manager).executeWithoutResult(tx -> { emit(source); tx.setRollbackOnly(); });
        emit(source); // No fallback execution outside a transaction.
        doThrow(new IllegalStateException("embedding failed")).when(vectors).upsert(any());
        assertThatThrownBy(this::commit).isInstanceOf(IllegalStateException.class);
        verify(gateway, after(150).never()).generate(any());
        verify(vectors, never()).search(anyString(), anyInt(), any());
        assertThat(count("memory_events")).isZero();
    }

    @Test void takesAtMostFiveNonSelfCandidatesAndUsesAutoRedactedFreshSqlPayload() throws Exception {
        List<ScoredMemoryRef> candidates = new ArrayList<>();
        candidates.add(new ScoredMemoryRef(item(source), 1));
        for (int i=0; i<6; i++) {
            UUID id = UUID.randomUUID(); memory(id); candidates.add(new ScoredMemoryRef(item(id), .9-i*.01));
        }
        jdbc.update("UPDATE memory_items SET text='source secret' WHERE id=?", source);
        when(vectors.search(anyString(), eq(6), any())).thenAnswer(invocation -> {
            assertThat((String) invocation.getArgument(0)).contains("[REDACTED]").doesNotContain("secret");
            MemoryEligibilityFilter filter = invocation.getArgument(2);
            assertThat(filter.projectKey()).isEqualTo("P");
            assertThat(filter.includeGlobal()).isFalse(); assertThat(filter.includeEpisodic()).isFalse();
            assertThat(filter.excludePromotedRuleOrigins()).isTrue();
            jdbc.update("UPDATE memory_items SET text='fresh secret'");
            // Keep the source hash stable during candidate discovery.
            jdbc.update("UPDATE memory_items SET text='source secret' WHERE id=?", source);
            return candidates;
        });
        when(gateway.generate(any())).thenAnswer(invocation -> {
            GenerationRequest request = invocation.getArgument(0);
            assertThat(request.requestedProvider()).isNull();
            assertThat(request.role()).isEqualTo("memory-relation-judge");
            assertThat(request.sources()).isEmpty();
            assertThat(request.question()).contains("fresh [REDACTED]").doesNotContain("secret");
            return answer("extends", .95, false);
        });
        start(); commit(); await(() -> count("memory_events") == 5);
        assertThat(count("memory_relations")).isEqualTo(5);
        verify(gateway, times(5)).generate(any());
    }

    @Test void rejectsStaleVectorEligibilityAndDuplicateCandidatesBeforeProvider() throws Exception {
        List<ScoredMemoryRef> candidates = new ArrayList<>();
        for (String mutation : List.of("project_key='OTHER'", "scope='global'", "status='pending_review'", "expires_at=now()-interval '1 second'")) {
            UUID id=UUID.randomUUID(); memory(id); candidates.add(new ScoredMemoryRef(item(id), .99));
            jdbc.update("UPDATE memory_items SET " + mutation + " WHERE id=?", id);
        }
        candidates.add(new ScoredMemoryRef(item(target), .9)); candidates.add(new ScoredMemoryRef(item(target), .8));
        when(vectors.search(anyString(), eq(6), any())).thenReturn(candidates);
        start(); commit(); await(() -> count("memory_events") == 1);
        assertThat(count("memory_relations")).isEqualTo(1);
        verify(gateway, times(1)).generate(any());
    }

    @Test void deterministicProviderFixturesHaveZeroForbiddenEdgesAndFailuresRemainRetryable() throws Exception {
        start();
        for (String kind : List.of("degraded", "exception", "malformed", "unrelated", "low", "supersedes")) {
            jdbc.update("DELETE FROM memory_events");
            reset(gateway);
            when(gateway.generate(any())).thenAnswer(i -> switch (kind) {
                case "exception" -> throw new IllegalStateException("provider stub");
                case "malformed" -> new GenerationResponse("{}", "stub", 0, false, DegradedReason.NONE,0,0,0,0,0);
                case "degraded" -> answer("extends", .95, true);
                case "low" -> answer("extends", .1, false);
                default -> answer(kind, .95, false);
            });
            commit(); await(() -> count("memory_events") == 1);
            assertThat(count("memory_relations")).isZero();
            boolean failure = Set.of("degraded", "exception", "malformed").contains(kind);
            assertThat(store.alreadyEvaluated(guard())).isEqualTo(!failure);
            if (failure) assertThat(last()).containsEntry("action", "FAILED").containsEntry("completed", false);
        }
        assertThat(count("memory_review_queue")).isEqualTo(1);
    }

    @Test void deadlineIgnoresLateResponseAndPermitsLaterAttempt() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1); releases.add(release);
        var exited = new CountDownLatch(1);
        when(gateway.generate(any())).thenAnswer(i -> {
            entered.countDown();
            boolean done=false;
            while (!done) try { release.await(); done=true; } catch (InterruptedException ignored) { }
            exited.countDown();
            return answer("extends", .95, false);
        });
        start(true, 4, Duration.ofMillis(100)); commit();
        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
        await(() -> count("memory_events") == 1);
        assertThat(last()).containsEntry("reason", "TIMEOUT").containsEntry("completed", false);
        assertThat(count("memory_relations")).isZero();
        assertThat(store.alreadyEvaluated(guard())).isFalse();
        release.countDown(); assertThat(exited.await(2, TimeUnit.SECONDS)).isTrue();
        when(gateway.generate(any())).thenReturn(answer("extends", .95, false));
        commit(); await(() -> count("memory_events") == 2);
        assertThat(count("memory_relations")).isEqualTo(1);
    }

    @Test void queueOverflowIsBoundedAndLoggedWithoutFailingCommittedMemoryWrite() throws Exception {
        var entered=new CountDownLatch(1); var release=new CountDownLatch(1); releases.add(release);
        when(vectors.search(anyString(), eq(6), any())).thenAnswer(i -> {
            entered.countDown(); release.await(); return List.of();
        });
        var logger=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(MemoryRelationJudgeWorker.class);
        var appender=new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start(); logger.addAppender(appender);
        try {
            start(true, 1, Duration.ofSeconds(1)); commit();
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            commit(); commit();
            assertThat(appender.list).anySatisfy(event -> assertThat(event.getFormattedMessage()).contains("queue rejected").contains("queueSize=1"));
            release.countDown();
            verify(vectors, timeout(3000).times(2)).search(anyString(), eq(6), any());
            verifyNoInteractions(gateway);
        } finally { logger.detachAppender(appender); appender.stop(); }
    }

    @Test void disabledWorkerDoesNotSearchOrCallProvider() {
        start(false, 1, Duration.ofSeconds(1)); commit();
        verify(vectors).upsert(any());
        verify(vectors, never()).search(anyString(), anyInt(), any());
        verifyNoInteractions(gateway);
    }

    @Test void memoryChangedDuringProviderCallCannotAcquireAnEdge() throws Exception {
        when(gateway.generate(any())).thenAnswer(i -> {
            jdbc.update("UPDATE memory_items SET text='changed during generation' WHERE id=?", source);
            return answer("extends", .95, false);
        });
        start(); commit(); await(() -> count("memory_events") == 1);
        assertThat(count("memory_relations")).isZero();
        assertThat(last()).containsEntry("action", "FAILED").containsEntry("reason", "INPUT_CHANGED")
                .containsEntry("completed", false);
    }

    @Test void providerIgnoringCancellationCannotCreateUnboundedCallsOrLateEdges() throws Exception {
        var entered=new CountDownLatch(1); var release=new CountDownLatch(1); releases.add(release);
        when(gateway.generate(any())).thenAnswer(i -> {
            entered.countDown();
            boolean done=false;
            while (!done) try { release.await(); done=true; } catch (InterruptedException ignored) { }
            return answer("extends", .95, false);
        });
        start(true, 4, Duration.ofMillis(100)); commit();
        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
        await(() -> count("memory_events") == 1);
        commit(); await(() -> count("memory_events") == 2);
        assertThat(last()).containsEntry("reason", "TIMEOUT");
        commit(); await(() -> count("memory_events") == 3);
        assertThat(last()).containsEntry("reason", "PROVIDER_ERROR");
        verify(gateway, times(1)).generate(any());
        release.countDown();
        verify(gateway, after(100).times(1)).generate(any());
        assertThat(count("memory_relations")).isZero();
    }
}
