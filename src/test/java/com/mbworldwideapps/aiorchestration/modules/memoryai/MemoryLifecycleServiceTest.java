package com.mbworldwideapps.aiorchestration.modules.memoryai;

import com.mbworldwideapps.aiorchestration.config.MemoryCodeLinkProperties;
import com.mbworldwideapps.aiorchestration.modules.graph.*;
import com.mbworldwideapps.aiorchestration.modules.scanner.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@Testcontainers
class MemoryLifecycleServiceTest {
    @Container static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:17-alpine");
    JdbcTemplate jdbc;
    JdbcMemoryRepository memories;
    CodeBaselineRepository baseline;
    RuleMemoryLinkLookup rules;
    RuleMemoryActivationPolicy activation;
    ScannerPayloadRedactor redactor;
    MemoryCodeLinkResolver resolver;
    MemoryLifecycleService lifecycle;
    MemoryCodeLinkProjectionService projection;
    DataSourceTransactionManager manager;
    final List<Object> published = new ArrayList<>();
    final UUID id = UUID.randomUUID();
    final ObjectMapper json = new ObjectMapper();
    final MemoryCodeLinkProperties properties = MemoryCodeLinkProperties.defaults();

    @BeforeEach void setup() throws Exception {
        var ds = new DriverManagerDataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword());
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(ds); jdbc.execute("TRUNCATE memory_items CASCADE");
        memories = new JdbcMemoryRepository(jdbc, json); manager = new DataSourceTransactionManager(ds);
        baseline = mock(CodeBaselineRepository.class); rules = mock(RuleMemoryLinkLookup.class);
        activation = mock(RuleMemoryActivationPolicy.class); redactor = mock(ScannerPayloadRedactor.class);
        when(redactor.redact(anyString())).thenAnswer(i -> i.getArgument(0));
        when(baseline.latestCompletedRun("P")).thenAnswer(i -> Optional.of(new CodeScanRunRecord(
                UUID.randomUUID(), "P", "/P", "completed", Instant.now(), Instant.now(), Map.of())));
        file("h1");
        resolver = new MemoryCodeLinkResolver(properties, baseline, mock(CodeBaselineVectorIndex.class));
        lifecycle = service(published::add);
        projection = new MemoryCodeLinkProjectionService(properties, memories, resolver, lifecycle);
        jdbc.update("""
                INSERT INTO memory_items(id,vector_id,scope,project_key,memory_type,summary,text,confidence,status,source_type)
                VALUES (?,?,'project','P','decision','summary','text',1,'active','manual')
                """, id, UUID.randomUUID());
        locators(new MemoryCodeLocator(MemoryCodeLocatorKind.FILE, "a/A.java", null, null));
    }
    MemoryLifecycleService service(ApplicationEventPublisher publisher) {
        return new MemoryLifecycleService(jdbc, memories, baseline, rules, publisher, json, manager, resolver, activation, redactor, properties);
    }
    MemoryItem item() { return memories.findById(id).orElseThrow(); }
    int events() { return memories.eventsForMemory(id).size(); }
    int reviews() { return jdbc.queryForObject("SELECT count(*) FROM memory_review_queue", Integer.class); }
    void file(String hash) {
        when(baseline.findFileByPath("P", "a/A.java")).thenReturn(Optional.of(new CodeFileRecord(
                UUID.randomUUID(), "P", "a/A.java", hash, "java", UUID.randomUUID(), Map.of())));
    }
    void locators(MemoryCodeLocator... locators) throws Exception {
        jdbc.update("UPDATE memory_items SET metadata=?::jsonb WHERE id=?", json.writeValueAsString(
                MemoryCodeLocatorMetadata.replace(Map.of(), List.of(locators), MemoryScope.PROJECT, "P")), id);
    }

    @Test void twoLinkAllRunsAndNewTargetUuidsWithSameContentProduceNoNewEvents() {
        assertThat(projection.linkAll("P").available()).isTrue();
        String first = lifecycle.snapshot(resolver.resolve(item())).fingerprint();
        assertThat(events()).isEqualTo(1); assertThat(reviews()).isZero();
        file("h1"); // Different file UUID and scanRun UUID; latest completed run UUID also changes per call.
        assertThat(projection.linkAll("P").available()).isTrue();
        assertThat(lifecycle.snapshot(resolver.resolve(item())).fingerprint()).isEqualTo(first);
        assertThat(events()).isEqualTo(1); assertThat(item().status()).isEqualTo(MemoryStatus.ACTIVE);
        assertThat(published).isEmpty();
    }

    @Test void changedCodeHashMarksStaleAndOpensOneReviewWithoutDeletingMemory() {
        projection.linkMemory(id); file("h2"); projection.linkMemory(id);
        assertThat(item().status()).isEqualTo(MemoryStatus.STALE);
        assertThat(events()).isEqualTo(3); assertThat(reviews()).isEqualTo(1);
        assertThat(memories.eventsForMemory(id)).anySatisfy(event -> {
            assertThat(event.eventType()).isEqualTo(MemoryEventType.STATUS_CHANGED);
            assertThat(event.metadata()).containsEntry("oldStatus", "active").containsEntry("newStatus", "stale")
                    .containsEntry("actor", "system:code-evidence");
        });
        assertThat(memories.reviewQueueForMemory(id).getFirst().metadata()).containsEntry("kind", "code_drift");
        assertThat(memories.reviewQueueForMemory(id).getFirst().status()).isEqualTo(ReviewStatus.OPEN);
        assertThat(published).hasSize(1);
        projection.linkAll("P"); assertThat(events()).isEqualTo(3);
        file("h3"); projection.linkMemory(id);
        assertThat(events()).isEqualTo(4); assertThat(reviews()).isEqualTo(1);
        assertThat(published).hasSize(1); // No recursive same-status event loop.
    }

    @Test void directoryLossAndInitiallyMissingExplicitTargetRequireReview() throws Exception {
        locators(new MemoryCodeLocator(MemoryCodeLocatorKind.DIRECTORY, "a/b/", null, null));
        when(baseline.directoryExists("P", "a/b")).thenReturn(true);
        projection.linkMemory(id);
        when(baseline.directoryExists("P", "a/b")).thenReturn(false);
        projection.linkMemory(id);
        assertThat(item().status()).isEqualTo(MemoryStatus.STALE);
        assertThat(reviews()).isEqualTo(1);
        assertThat(memories.eventsForMemory(id)).anySatisfy(event -> assertThat(event.metadata()).containsEntry("missingTargets", 1));
        jdbc.update("DELETE FROM memory_events WHERE memory_id=?", id);
        jdbc.update("DELETE FROM memory_review_queue WHERE candidate_memory_id=?", id);
        jdbc.update("UPDATE memory_items SET status='active' WHERE id=?", id);
        projection.linkMemory(id);
        assertThat(item().status()).isEqualTo(MemoryStatus.STALE); assertThat(reviews()).isEqualTo(1);
    }

    @Test void initiallyUnresolvedLegacyFileHintDoesNotRequireReview() {
        jdbc.update("UPDATE memory_items SET metadata='{\"filePath\":\"missing.java\"}'::jsonb WHERE id=?", id);
        var resolved = resolver.resolve(item());
        assertThat(resolved.missingFiles()).isEqualTo(1);
        assertThat(resolved.missingTypedTargets()).isZero();
        projection.linkAll("P");
        assertThat(item().status()).isEqualTo(MemoryStatus.ACTIVE);
        assertThat(reviews()).isZero(); assertThat(events()).isZero();
    }

    @Test void previouslyResolvedLegacyFileHintDisappearingStillRequiresReview() {
        jdbc.update("UPDATE memory_items SET metadata='{\"filePath\":\"a/A.java\"}'::jsonb WHERE id=?", id);
        var target = baseline.findFileByPath("P", "a/A.java").orElseThrow();
        when(baseline.findFileById("P", target.id())).thenReturn(Optional.of(target));
        projection.linkAll("P");
        assertThat(events()).isEqualTo(1);
        assertThat(item().status()).isEqualTo(MemoryStatus.ACTIVE);
        when(baseline.findFileByPath("P", "a/A.java")).thenReturn(Optional.empty());
        assertThat(resolver.resolve(item()).missingTypedTargets()).isZero();
        projection.linkAll("P");
        assertThat(item().status()).isEqualTo(MemoryStatus.STALE);
        assertThat(reviews()).isEqualTo(1); assertThat(events()).isEqualTo(3);
        projection.linkAll("P");
        assertThat(reviews()).isEqualTo(1); assertThat(events()).isEqualTo(3);
    }

    @Test void symbolFingerprintUsesCanonicalRefAndHashNotScannerIds() throws Exception {
        locators(new MemoryCodeLocator(MemoryCodeLocatorKind.SYMBOL, "p.A.f", null, null));
        for (int i=0; i<2; i++) {
            when(baseline.findSymbolsByRef("P", "p.A.f", 5)).thenReturn(List.of(new CodeSymbolRecord(UUID.randomUUID(), "P",
                    UUID.randomUUID(), "method", "f", "p.A.f", "f()", "service", 1,2,"same-body",UUID.randomUUID(),Map.of())));
            projection.linkAll("P");
        }
        assertThat(events()).isEqualTo(1);
        assertThat(item().status()).isEqualTo(MemoryStatus.ACTIVE);
    }

    @Test void absentBaselineAndUnknownHashDoNotInventDrift() {
        when(baseline.latestCompletedRun("P")).thenReturn(Optional.empty());
        projection.linkMemory(id); assertThat(events()).isZero();
        when(baseline.latestCompletedRun("P")).thenReturn(Optional.of(new CodeScanRunRecord(UUID.randomUUID(), "P", "/P", "completed", Instant.now(),Instant.now(),Map.of())));
        file(null); projection.linkMemory(id); assertThat(events()).isZero();
        assertThat(item().status()).isEqualTo(MemoryStatus.ACTIVE); assertThat(reviews()).isZero();
    }

    @Test void concurrentMemoryContentOrLocatorChangeRejectsOldObservation() throws Exception {
        MemoryItem old = item(); var resolved = resolver.resolve(old);
        jdbc.update("UPDATE memory_items SET text='changed' WHERE id=?", id);
        assertThat(lifecycle.observe(old, resolved)).isEqualTo(MemoryLifecycleService.Observation.CONFLICT);
        old = item(); resolved = resolver.resolve(old);
        locators(new MemoryCodeLocator(MemoryCodeLocatorKind.DIRECTORY, "other", null, null));
        assertThat(lifecycle.observe(old, resolved)).isEqualTo(MemoryLifecycleService.Observation.CONFLICT);
        assertThat(events()).isZero(); assertThat(reviews()).isZero();
    }

    @Test void ruleAuthorityAndNoncurrentMemoryAreNotAutomaticallyMutated() {
        jdbc.update("UPDATE memory_items SET memory_type='rule' WHERE id=?", id);
        when(rules.hasLinkedDefinition(id)).thenReturn(true);
        projection.linkMemory(id); assertThat(events()).isZero();
        jdbc.update("UPDATE memory_items SET memory_type='decision',status='rejected' WHERE id=?", id);
        projection.linkMemory(id); assertThat(events()).isZero(); assertThat(reviews()).isZero();
    }

    @Test void staleStatusQueueAndEventsRollbackTogetherIfSynchronousPublicationFails() {
        projection.linkMemory(id); file("h2");
        var failing = service(event -> { throw new IllegalStateException("vector write failed"); });
        assertThatThrownBy(() -> failing.observe(item(), resolver.resolve(item()))).isInstanceOf(IllegalStateException.class);
        assertThat(item().status()).isEqualTo(MemoryStatus.ACTIVE);
        assertThat(events()).isEqualTo(1); assertThat(reviews()).isZero();
    }
    MemoryLifecycleService.Selection selection(MemoryLifecycleService.Decision decision, UUID replacement, UUID proposal) {
        return new MemoryLifecycleService.Selection(decision, "P", id, replacement, proposal);
    }
    MemoryLifecycleService.Approval approval(MemoryLifecycleService.Preview preview) {
        return new MemoryLifecycleService.Approval(preview.selection(), preview.previewHash(), true,
                "Reviewed change", "Checked current code", "Approve the displayed decision", "test-human-turn");
    }
    void stale() { jdbc.update("UPDATE memory_items SET status='stale' WHERE id=?", id); }
    UUID replacement() {
        UUID next = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO memory_items(id,vector_id,scope,project_key,memory_type,summary,text,confidence,status,source_type)
                VALUES (?,?,'project','P','decision','new summary','new text',1,'active','manual')
                """, next, UUID.randomUUID());
        return next;
    }
    UUID proposal(UUID next) {
        UUID proposal = UUID.randomUUID();
        MemoryItem newItem = memories.findById(next).orElseThrow();
        memories.insertReviewQueue(new ReviewQueueItem(proposal, id, ReviewReason.CONFLICT, ReviewStatus.OPEN, Map.of(
                "kind", "memory_relation_supersedes_proposal", "replacementMemoryId", next.toString(),
                "sourceHash", MemoryRelationService.contentHash(newItem.summary(), newItem.text()),
                "targetHash", MemoryRelationService.contentHash(item().summary(), item().text())), Instant.now(), null));
        return proposal;
    }

    @Test void revalidateAcceptsFreshEvidenceAndDoesNotImmediatelyMarkStaleAgain() {
        projection.linkAll("P"); file("h2"); projection.linkAll("P");
        var preview = lifecycle.preview(selection(MemoryLifecycleService.Decision.REVALIDATE, null, null));
        int before = events();
        var result = lifecycle.decide(approval(preview), "workspace:test");
        assertThat(result.status()).isEqualTo(MemoryStatus.ACTIVE);
        assertThat(item().lastVerifiedAt()).isNotNull();
        assertThat(memories.reviewQueueForMemory(id).getFirst().status()).isEqualTo(ReviewStatus.APPROVED);
        assertThat(events()).isEqualTo(before + 2);
        projection.linkAll("P"); assertThat(events()).isEqualTo(before + 2);
        assertThat(item().status()).isEqualTo(MemoryStatus.ACTIVE);
        verify(activation, atLeastOnce()).assertActivationAllowed(MemoryType.DECISION, MemoryStatus.STALE, MemoryStatus.ACTIVE);
    }

    @Test void revalidateRequiresHumanEvidenceExactPreviewAndCurrentCode() throws Exception {
        stale();
        var preview = lifecycle.preview(selection(MemoryLifecycleService.Decision.REVALIDATE, null, null));
        var valid = approval(preview);
        assertThatThrownBy(() -> lifecycle.decide(new MemoryLifecycleService.Approval(valid.selection(),valid.previewHash(),false,
                valid.reason(),valid.evidence(),valid.humanRawText(),valid.humanTurnRef()), "workspace:test")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> lifecycle.decide(new MemoryLifecycleService.Approval(valid.selection(),valid.previewHash(),true,
                valid.reason()," ",valid.humanRawText(),valid.humanTurnRef()), "workspace:test")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> lifecycle.decide(new MemoryLifecycleService.Approval(valid.selection(),null,true,
                valid.reason(),valid.evidence(),valid.humanRawText(),valid.humanTurnRef()), "workspace:test")).isInstanceOf(IllegalArgumentException.class);
        file("h2");
        assertThatThrownBy(() -> lifecycle.decide(valid,"workspace:test")).isInstanceOf(IllegalStateException.class).hasMessageContaining("preview changed");
        when(baseline.findFileByPath("P","a/A.java")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> lifecycle.preview(valid.selection())).isInstanceOf(IllegalStateException.class).hasMessageContaining("target is missing");
        jdbc.update("UPDATE memory_items SET metadata='{\"codeLocators\":{\"schemaVersion\":999}}'::jsonb WHERE id=?", id);
        assertThatThrownBy(() -> lifecycle.preview(valid.selection())).isInstanceOf(IllegalArgumentException.class);
        assertThat(item().status()).isEqualTo(MemoryStatus.STALE); assertThat(events()).isZero(); assertThat(reviews()).isZero();
    }

    @Test void invalidateActiveAndStaleUseExistingVectorDeletionListener() {
        MemoryVectorIndex vector = mock(MemoryVectorIndex.class);
        var listener = new MemoryVectorIndexingListener(vector);
        var decisions = service(event -> listener.onMemoryChanged((MemoryItemChangedEvent) event));
        for (String current : List.of("active", "stale")) {
            jdbc.update("UPDATE memory_items SET status=? WHERE id=?",current,id);
            var preview = decisions.preview(selection(MemoryLifecycleService.Decision.INVALIDATE,null,null));
            decisions.decide(approval(preview), "workspace:test");
            assertThat(item().status()).isEqualTo(MemoryStatus.REJECTED);
        }
        verify(vector,times(2)).delete(argThat(i -> i.id().equals(id) && i.status() == MemoryStatus.REJECTED));
        verify(vector,never()).upsert(any());
        assertThat(memories.eventsForMemory(id)).allSatisfy(e -> assertThat(e.eventType()).isEqualTo(MemoryEventType.REJECTED));
    }

    @Test void changedMemoryOrLocatorsCannotUseOldApproval() throws Exception {
        var preview = lifecycle.preview(selection(MemoryLifecycleService.Decision.INVALIDATE,null,null));
        var contentApproval = approval(preview);
        jdbc.update("UPDATE memory_items SET text='changed' WHERE id=?",id);
        assertThatThrownBy(() -> lifecycle.decide(contentApproval,"workspace:test")).isInstanceOf(IllegalStateException.class);
        preview = lifecycle.preview(selection(MemoryLifecycleService.Decision.INVALIDATE,null,null));
        var locatorApproval = approval(preview);
        locators(new MemoryCodeLocator(MemoryCodeLocatorKind.DIRECTORY,"a/b",null,null));
        assertThatThrownBy(() -> lifecycle.decide(locatorApproval,"workspace:test")).isInstanceOf(IllegalStateException.class);
        assertThat(events()).isZero(); assertThat(item().status()).isEqualTo(MemoryStatus.ACTIVE);
    }

    @Test void directHumanSupersessionCreatesAndApprovesOneProposalAndOnlyAuthoritativeEvent() {
        UUID next = replacement();
        var preview = lifecycle.preview(selection(MemoryLifecycleService.Decision.SUPERSEDE,next,null));
        var result = lifecycle.decide(approval(preview),"workspace:test");
        assertThat(result.status()).isEqualTo(MemoryStatus.ARCHIVED);
        assertThat(result.proposalId()).isNotNull();
        assertThat(memories.findById(next).orElseThrow().status()).isEqualTo(MemoryStatus.ACTIVE);
        assertThat(reviews()).isEqualTo(1);
        assertThat(memories.reviewQueueForMemory(id).getFirst().status()).isEqualTo(ReviewStatus.APPROVED);
        assertThat(memories.reviewQueueForMemory(id).getFirst().metadata()).containsEntry("origin","human");
        assertThat(memories.eventsForMemory(id)).singleElement().satisfies(e -> {
            assertThat(e.eventType()).isEqualTo(MemoryEventType.ARCHIVED);
            assertThat(e.metadata()).containsEntry("action","supersede").containsEntry("supersededBy",next.toString())
                    .containsEntry("humanTurnRef","test-human-turn").containsEntry("humanConfirmed",true);
        });
        assertThat(item().metadata()).doesNotContainKey("supersededBy");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM memory_relations",Integer.class)).isZero();
        assertThatThrownBy(() -> lifecycle.decide(approval(preview),"workspace:test")).isInstanceOf(IllegalStateException.class);
        assertThat(events()).isEqualTo(1); assertThat(reviews()).isEqualTo(1);
    }

    @Test void proposalOnlySelectionApprovesExistingProposalButRejectsStaleEvidence() {
        UUID next = replacement(), proposal = proposal(next);
        var selection = new MemoryLifecycleService.Selection(MemoryLifecycleService.Decision.SUPERSEDE,"P",null,null,proposal);
        var preview = lifecycle.preview(selection);
        jdbc.update("UPDATE memory_review_queue SET metadata=metadata || '{\"note\":\"changed\"}'::jsonb WHERE id=?",proposal);
        assertThatThrownBy(() -> lifecycle.decide(approval(preview),"workspace:test")).isInstanceOf(IllegalStateException.class);
        var refreshed = lifecycle.preview(selection);
        var result = lifecycle.decide(approval(refreshed),"workspace:test");
        assertThat(result.proposalId()).isEqualTo(proposal); assertThat(reviews()).isEqualTo(1);
        assertThat(result.status()).isEqualTo(MemoryStatus.ARCHIVED);
    }

    @Test void changedReplacementOrProposalStatusPreventsSupersession() {
        UUID next = replacement(), proposal = proposal(next);
        var selection = selection(MemoryLifecycleService.Decision.SUPERSEDE,next,proposal);
        var preview = lifecycle.preview(selection);
        jdbc.update("UPDATE memory_items SET text='changed replacement' WHERE id=?",next);
        assertThatThrownBy(() -> lifecycle.decide(approval(preview),"workspace:test")).isInstanceOf(IllegalStateException.class);
        jdbc.update("UPDATE memory_items SET text='new text' WHERE id=?",next);
        jdbc.update("UPDATE memory_review_queue SET status='rejected' WHERE id=?",proposal);
        assertThatThrownBy(() -> lifecycle.decide(approval(preview),"workspace:test")).isInstanceOf(IllegalStateException.class);
        assertThat(events()).isZero(); assertThat(item().status()).isEqualTo(MemoryStatus.ACTIVE);
    }

    @Test void supersessionRejectsCrossProjectGlobalExpiredSelfAndInactiveTargets() {
        UUID next = replacement();
        assertThatThrownBy(() -> lifecycle.preview(selection(MemoryLifecycleService.Decision.SUPERSEDE,id,null))).isInstanceOf(IllegalArgumentException.class);
        for (String change : List.of("project_key='OTHER'", "scope='global',project_key=null", "expires_at=now()-interval '1 day'", "status='stale'")) {
            jdbc.update("UPDATE memory_items SET " + change + " WHERE id=?",next);
            assertThatThrownBy(() -> lifecycle.preview(selection(MemoryLifecycleService.Decision.SUPERSEDE,next,null))).isInstanceOfAny(NoSuchElementException.class,IllegalStateException.class);
            jdbc.update("UPDATE memory_items SET project_key='P',scope='project',expires_at=null,status='active' WHERE id=?",next);
        }
        assertThat(events()).isZero(); assertThat(reviews()).isZero();
    }

    @Test void linkedRuleAuthorityRejectsAllDecisionsIncludingReplacement() {
        UUID next = replacement();
        jdbc.update("UPDATE memory_items SET memory_type='rule',status='stale' WHERE id=?",id);
        when(rules.hasLinkedDefinition(id)).thenReturn(true);
        for (var decision : MemoryLifecycleService.Decision.values())
            assertThatThrownBy(() -> lifecycle.preview(selection(decision,decision == MemoryLifecycleService.Decision.SUPERSEDE ? next : null,null)))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("rule authority");
        jdbc.update("UPDATE memory_items SET memory_type='decision' WHERE id=?",id);
        jdbc.update("UPDATE memory_items SET memory_type='rule' WHERE id=?",next);
        when(rules.hasLinkedDefinition(next)).thenReturn(true);
        assertThatThrownBy(() -> lifecycle.preview(selection(MemoryLifecycleService.Decision.SUPERSEDE,next,null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("rule authority");
        doThrow(new IllegalArgumentException("activation blocked")).when(activation).assertActivationAllowed(any(),any(),any());
        assertThatThrownBy(() -> lifecycle.preview(selection(MemoryLifecycleService.Decision.REVALIDATE,null,null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("activation blocked");
        assertThat(events()).isZero(); assertThat(reviews()).isZero();
    }

    @Test void failedPublicationRollsBackSupersessionAndNewHumanProposal() {
        UUID next = replacement();
        var failing = service(event -> { throw new IllegalStateException("vector delete failed"); });
        var preview = failing.preview(selection(MemoryLifecycleService.Decision.SUPERSEDE,next,null));
        assertThatThrownBy(() -> failing.decide(approval(preview),"workspace:test")).isInstanceOf(IllegalStateException.class);
        assertThat(item().status()).isEqualTo(MemoryStatus.ACTIVE);
        assertThat(events()).isZero(); assertThat(reviews()).isZero();
    }

    @Test void auditTextIsRedactedBeforePersistenceAndUnconfirmedRequestCannotWrite() {
        when(redactor.redact("Approve secret")).thenReturn("Approve [REDACTED]");
        var preview = lifecycle.preview(selection(MemoryLifecycleService.Decision.INVALIDATE,null,null));
        lifecycle.decide(new MemoryLifecycleService.Approval(preview.selection(),preview.previewHash(),true,
                "reason",null,"Approve secret","test-turn"),"workspace:test");
        assertThat(memories.eventsForMemory(id)).singleElement().satisfies(e -> {
            assertThat(e.metadata()).containsEntry("humanRawTextScrubbed","Approve [REDACTED]");
            assertThat(e.metadata().get("humanRawTextHash").toString()).hasSize(64);
            assertThat(e.metadata().toString()).doesNotContain("Approve secret");
        });
    }

    @Test void disabledDeterministicEvidenceCannotRevalidateBoundMemory() {
        stale();
        var disabled = new MemoryLifecycleService(jdbc,memories,baseline,rules,published::add,json,manager,
                resolver,activation,redactor,new MemoryCodeLinkProperties(true, false, false, 0.72, 5, 500, 20));
        assertThatThrownBy(() -> disabled.preview(selection(MemoryLifecycleService.Decision.REVALIDATE,null,null)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("disabled");
        assertThat(events()).isZero(); assertThat(item().status()).isEqualTo(MemoryStatus.STALE);
    }

    @Test void pendingSourceCannotUseAnyLifecycleDecision() {
        UUID next = replacement();
        jdbc.update("UPDATE memory_items SET status='pending_review' WHERE id=?",id);
        for (var decision : MemoryLifecycleService.Decision.values())
            assertThatThrownBy(() -> lifecycle.preview(selection(decision,decision == MemoryLifecycleService.Decision.SUPERSEDE ? next : null,null)))
                    .isInstanceOf(IllegalStateException.class);
        assertThat(events()).isZero(); assertThat(reviews()).isZero();
    }

    @Test void concurrentApprovalsProduceOnlyOneSupersession() throws Exception {
        UUID next = replacement();
        var approval = approval(lifecycle.preview(selection(MemoryLifecycleService.Decision.SUPERSEDE,next,null)));
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Boolean> call = () -> {
                start.await();
                try { lifecycle.decide(approval,"workspace:test"); return true; }
                catch (IllegalStateException e) { return false; }
            };
            var first = pool.submit(call); var second = pool.submit(call); start.countDown();
            assertThat(List.of(first.get(10,java.util.concurrent.TimeUnit.SECONDS),second.get(10,java.util.concurrent.TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true,false);
        }
        assertThat(events()).isEqualTo(1); assertThat(reviews()).isEqualTo(1);
        assertThat(item().status()).isEqualTo(MemoryStatus.ARCHIVED);
    }

}
