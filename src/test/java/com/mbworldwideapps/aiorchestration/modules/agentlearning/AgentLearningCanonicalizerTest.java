package com.mbworldwideapps.aiorchestration.modules.agentlearning;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mbworldwideapps.aiorchestration.modules.agentlearning.AgentLearningModels.Anchor;
import com.mbworldwideapps.aiorchestration.modules.agentlearning.AgentLearningModels.Candidate;
import com.mbworldwideapps.aiorchestration.modules.agentlearning.AgentLearningModels.Observation;
import com.mbworldwideapps.aiorchestration.modules.agentlearning.AgentLearningModels.Target;
import org.junit.jupiter.api.Test;

class AgentLearningCanonicalizerTest {

    private final AgentLearningCanonicalizer canonicalizer = new AgentLearningCanonicalizer(new ObjectMapper());

    @Test
    void randomObservationIdsAndSetOrderingDoNotChangeAnyIdentity() {
        UUID context = UUID.randomUUID();
        UUID snapshot = UUID.randomUUID();
        Target service = new Target("t1", "src/PaymentService.java", "PaymentService#pay", "supporting",
                "a".repeat(64), 10, 20);
        Target test = new Target("t2", "src/PaymentServiceTest.java", "PaymentServiceTest#fallback", "validation",
                "b".repeat(64), 30, 40);
        Candidate first = candidate(List.of(new Anchor("t2", "validation"), new Anchor("t1", "supporting")),
                List.of("payment", "routing"));
        Candidate reordered = candidate(List.of(new Anchor("t1", "supporting"), new Anchor("t2", "validation")),
                List.of("routing", "payment"));

        List<Observation> evidenceA = List.of(
                observation(UUID.randomUUID(), context, snapshot, "t2", "src/PaymentServiceTest.java", "2"),
                observation(UUID.randomUUID(), context, snapshot, "t1", "src/PaymentService.java", "1"));
        List<Observation> evidenceB = List.of(
                observation(UUID.randomUUID(), context, snapshot, "t1", "src/PaymentService.java", "1"),
                observation(UUID.randomUUID(), context, snapshot, "t2", "src/PaymentServiceTest.java", "2"));

        assertThat(canonicalizer.identity(first, List.of(service, test), evidenceA))
                .isEqualTo(canonicalizer.identity(reordered, List.of(test, service), evidenceB));
    }

    @Test
    void sourceRevisionChangesEvidenceRevisionButNotSemanticIdentity() {
        Target target = new Target("t1", "src/PaymentService.java", "PaymentService#pay", "supporting",
                "a".repeat(64), 10, 20);
        Candidate candidate = candidate(List.of(new Anchor("t1", "supporting")), List.of("routing"));
        UUID context = UUID.randomUUID();
        UUID snapshot = UUID.randomUUID();
        var before = canonicalizer.identity(candidate, List.of(target),
                List.of(observation(UUID.randomUUID(), context, snapshot, "t1", target.relativePath(), "1")));
        var after = canonicalizer.identity(candidate, List.of(target),
                List.of(observation(UUID.randomUUID(), context, snapshot, "t1", target.relativePath(), "9")));

        assertThat(after.semanticIdentity()).isEqualTo(before.semanticIdentity());
        assertThat(after.contentRevision()).isEqualTo(before.contentRevision());
        assertThat(after.evidenceRevision()).isNotEqualTo(before.evidenceRevision());
    }

    @Test
    void changedExplanationIsAContentRevisionOfTheSameSemanticLearning() {
        Target target = new Target("t1", "src/PaymentService.java", "PaymentService#pay", "supporting",
                "a".repeat(64), 10, 20);
        Candidate before = candidate(List.of(new Anchor("t1", "supporting")), List.of("routing"));
        Candidate after = new Candidate(before.kind(), before.summary(), "A more precise fallback explanation.",
                before.appliesWhen(), before.limitations(), before.anchors(), before.evidenceIds(),
                before.reusableFor());
        Observation evidence = observation(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "t1",
                target.relativePath(), "1");

        var first = canonicalizer.identity(before, List.of(target), List.of(evidence));
        var second = canonicalizer.identity(after, List.of(target), List.of(evidence));

        assertThat(second.semanticIdentity()).isEqualTo(first.semanticIdentity());
        assertThat(second.contentRevision()).isNotEqualTo(first.contentRevision());
    }

    private static Candidate candidate(List<Anchor> anchors, List<String> reusableFor) {
        return new Candidate("behavior", "Fallback route", "Fallback uses the secondary route.",
                List.of("QR flow", "active card"), List.of("legacy excluded"), anchors,
                List.of(UUID.randomUUID()), reusableFor);
    }

    private static Observation observation(UUID id, UUID context, UUID snapshot, String target, String path,
            String hashPrefix) {
        return new Observation(id, context, target, "source_read", path, hashPrefix.repeat(64),
                "f".repeat(64), snapshot, 10, "server_observed", Instant.parse("2026-01-01T00:00:00Z"));
    }
}
