package com.mbworldwideapps.aiorchestration.modules.mcp.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.mbworldwideapps.aiorchestration.config.AiOrchestrationProperties;
import com.mbworldwideapps.aiorchestration.core.pii.PiiScrubber;
import com.mbworldwideapps.aiorchestration.core.policy.DefaultPolicyEngine;
import com.mbworldwideapps.aiorchestration.core.policy.PolicyProperties;
import com.mbworldwideapps.aiorchestration.modules.memoryai.CreateMemoryRequest;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryContextResponse;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryCodeLocator;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryCodeLocatorKind;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryCodeLocatorMetadata;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryCodeLocatorRelationship;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryEvent;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryEventType;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryInlineApprovalDecisionResponse;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryInlineApprovalPendingResponse;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryInlineApprovalProxy;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryItem;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryRepository;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryRetrievalService;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryReviewService;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryReviewServiceTestFixture;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryScope;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryService;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryServiceTestFixture;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemorySourceType;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryStatus;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryType;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryWriteGate;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryVectorIndex;
import com.mbworldwideapps.aiorchestration.modules.memoryai.ReviewQueueItem;
import com.mbworldwideapps.aiorchestration.modules.memoryai.ReviewStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class McpWriteToolsIT {

    private final InMemoryAccessLogRepository accessLogRepository = new InMemoryAccessLogRepository();
    private final AiOrchestrationProperties properties = properties();
    private final McpAuditLogger auditLogger = new McpAuditLogger(accessLogRepository, properties);

    @AfterEach
    void clearContext() {
        McpClientContextHolder.clear();
    }

    @Test
    void shouldStoreMemoryWhenNoGateIsConfiguredInsteadOfParkingItForReview() {
        InMemoryMemoryRepository repository = new InMemoryMemoryRepository();
        MemoryMcpTool tool = memoryTool(repository, MemoryWriteGate.alwaysActive());
        McpClientContextHolder.set(writeContext("PROJECT_A"));

        MemoryWriteResponse response = tool.write("Prefer explicit MCP approvals", "MCP approval rule",
                "rule", List.of("mcp"), null);

        assertThat(response.memoryId()).isNotNull();
        assertThat(response.status()).isEqualTo("active");
        assertThat(response.gateVerdict()).isEqualTo("AUTO_ACTIVE");
        assertThat(response.confirmationRequired()).isFalse();
        assertThat(repository.reviewQueue).isEmpty();
        assertThat(repository.items.get(response.memoryId()).status()).isEqualTo(MemoryStatus.ACTIVE);
        assertThat(response.scope()).isEqualTo("project");
        assertThat(response.projectKey()).isEqualTo("PROJECT_A");
        assertThat(accessLogRepository.entries.getFirst().decision()).isEqualTo("success");
    }

    @Test
    void untrustedClientWriteIsStoredActiveWithoutATrustDowngrade() {
        InMemoryMemoryRepository repository = new InMemoryMemoryRepository();
        MemoryMcpTool tool = memoryTool(repository, autoActiveGate());
        McpClientContextHolder.set(writeContext("PROJECT_A"));

        MemoryWriteResponse response = tool.write("Untrusted clients still store memory",
                "Untrusted write", "decision", List.of(), null);

        assertThat(response.status()).isEqualTo("active");
        assertThat(response.confirmationRequired()).isFalse();
        assertThat(repository.items.get(response.memoryId()).metadata())
                .doesNotContainKey("trustDowngrade");
    }

    @Test
    void correctedProposalCanBeResubmittedWithoutLeavingCandidates() {
        InMemoryMemoryRepository repository = new InMemoryMemoryRepository();
        MemoryWriteGate gate = (request, context) -> request.text().contains("corrected")
                ? autoActiveGate().evaluate(request, context)
                : new MemoryWriteGate.GateDecision(MemoryWriteGate.Verdict.REJECTED,
                        "multi_fact_memory", "Split the proposal", List.of("Keep one fact"), 1.0, Map.of());
        MemoryMcpTool tool = memoryTool(repository, gate);
        McpClientContextHolder.set(writeContext("PROJECT_A"));

        MemoryWriteResponse first = tool.write("Original proposal", "Proposal", "decision", List.of(), null);
        assertThat(first.memoryId()).isNull();
        assertThat(first.contextHints()).containsExactly("Keep one fact");
        assertThat(first.suggestedQuestion()).isEqualTo("Split the proposal");
        assertThat(repository.items).isEmpty();
        assertThat(repository.events).isEmpty();
        assertThat(repository.reviewQueue).isEmpty();

        MemoryWriteResponse corrected = tool.write("One corrected fact", "Corrected", "decision", List.of(), null);
        assertThat(corrected.status()).isEqualTo("active");
        assertThat(repository.items).containsOnlyKeys(corrected.memoryId());
        assertThat(repository.events).hasSize(1);
        assertThat(repository.reviewQueue).isEmpty();
    }

    @Test
    void shouldWriteGlobalMemoryWhenAgentSelectsGlobalScope() {
        InMemoryMemoryRepository repository = new InMemoryMemoryRepository();
        MemoryMcpTool tool = memoryTool(repository);
        McpClientContextHolder.set(writeContext("PROJECT_A"));

        MemoryWriteResponse response = tool.write("Hexagonal architecture rule applies across projects",
                "Global architecture rule", "rule", List.of("architecture"), null, "global");

        MemoryItem stored = repository.items.get(response.memoryId());
        assertThat(stored.scope()).isEqualTo(MemoryScope.GLOBAL);
        assertThat(stored.projectKey()).isNull();
        assertThat(response.scope()).isEqualTo("global");
        assertThat(response.projectKey()).isNull();
        assertThat(stored.metadata())
                .containsEntry("scopeDecisionMode", "agent_selected")
                .containsEntry("scopeDecisionReason", "scope parameter");
    }

    @Test
    void shouldWriteProjectMemoryWithProviderOverride() {
        InMemoryMemoryRepository repository = new InMemoryMemoryRepository();
        AtomicReference<String> seenProviderOverride = new AtomicReference<>();
        MemoryWriteGate gate = (request, context) -> {
            seenProviderOverride.set(context.providerOverride());
            return new MemoryWriteGate.GateDecision(MemoryWriteGate.Verdict.REJECTED,
                    "test_gate", "confirm", List.of(), 0.7, Map.of());
        };
        MemoryMcpTool tool = memoryTool(repository, gate);
        McpClientContextHolder.set(writeContext("PROJECT_A"));

        MemoryWriteResponse response = tool.write("MobileApp backend scan succeeded",
                "MobileApp scan succeeded", "decision", List.of("mobileapp", "scan"),
                "/home/dev/projects/MobileAppBackend/mobil-mobileapp-be",
                "project", "codex");

        assertThat(seenProviderOverride.get()).isEqualTo("codex");
        assertThat(response.memoryId()).isNull();
        assertThat(repository.items).isEmpty();
        assertThat(response.scope()).isEqualTo("project");
        assertThat(response.projectKey()).isEqualTo("PROJECT_A");
        assertThat(response.gateReason()).isEqualTo("test_gate");
    }

    @Test
    void shouldWriteVersionedTypedCodeLocatorsWithoutLosingGateMetadata() {
        InMemoryMemoryRepository repository = new InMemoryMemoryRepository();
        MemoryMcpTool tool = memoryTool(repository);
        McpClientContextHolder.set(writeContext("PROJECT_A"));
        List<MemoryCodeLocator> locators = List.of(
                new MemoryCodeLocator(MemoryCodeLocatorKind.FILE, "src/main/java/PaymentService.java", null,
                        MemoryCodeLocatorRelationship.MENTIONS),
                new MemoryCodeLocator(MemoryCodeLocatorKind.SYMBOL, "com.acme.PaymentService#pay", null,
                        MemoryCodeLocatorRelationship.CONSTRAINS),
                new MemoryCodeLocator(MemoryCodeLocatorKind.CAPSULE, "stable-payment-flow", "domain_flow",
                        MemoryCodeLocatorRelationship.EVIDENCES));

        MemoryWriteResponse response = tool.write("Payment flow follows the approved retry rule",
                "Payment retry rule", "decision", List.of("payment"), "ticket:PAY-42", "project", null,
                "PROJECT_A", locators);

        MemoryItem stored = repository.items.get(response.memoryId());
        assertThat(MemoryCodeLocatorMetadata.read(stored.metadata()).items()).containsExactlyElementsOf(locators);
        assertThat(stored.metadata())
                .containsEntry("gateVerdict", "AUTO_ACTIVE")
                .containsEntry("mcpClientId", "self-pipeline");
    }

    @Test
    void shouldRejectTypedCodeLocatorsForGlobalMemory() {
        InMemoryMemoryRepository repository = new InMemoryMemoryRepository();
        MemoryMcpTool tool = memoryTool(repository);
        McpClientContextHolder.set(writeContext("PROJECT_A"));
        List<MemoryCodeLocator> locators = List.of(new MemoryCodeLocator(MemoryCodeLocatorKind.FILE,
                "src/main/java/PaymentService.java", null, null));

        assertThatThrownBy(() -> tool.write("Reusable architecture rule", "Global architecture rule", "rule",
                List.of("architecture"), null, "global", null, null, locators))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("project scope");
        assertThat(repository.items).isEmpty();
    }

    @Test
    void bearerMemoryWriteRejectsDifferentProjectKey() {
        InMemoryMemoryRepository repository = new InMemoryMemoryRepository();
        MemoryMcpTool tool = memoryTool(repository);
        McpClientContextHolder.set(writeContext("PROJECT_A"));

        assertThatThrownBy(() -> tool.write("MobileApp backend scan succeeded",
                "MobileApp scan succeeded", "decision", List.of("mobileapp", "scan"),
                "/home/dev/projects/MobileAppBackend/mobil-mobileapp-be",
                "project", null, "PROJECT_B"))
                .isInstanceOf(McpAccessException.class)
                .hasMessageContaining("different project");
        assertThat(repository.items).isEmpty();
    }

    @Test
    void localMemoryWriteInfersProjectKeyFromSourceRef() {
        InMemoryMemoryRepository repository = new InMemoryMemoryRepository();
        MemoryMcpTool tool = memoryTool(repository);
        McpClientContextHolder.set(new McpClientContext("AI_ORCHESTRATION", "codex", "local",
                List.of("memory.write", "provider.codex")));

        MemoryWriteResponse response = tool.write("MobileApp backend scan succeeded",
                "MobileApp scan succeeded", "decision", List.of("mobileapp", "scan"),
                "/home/dev/projects/MobileAppBackend/mobil-mobileapp-be",
                "project", "codex");

        MemoryItem stored = repository.items.get(response.memoryId());
        assertThat(stored.scope()).isEqualTo(MemoryScope.PROJECT);
        assertThat(stored.projectKey()).matches("MOBIL_MOBILEAPP_BE_[0-9A-F]{8}");
        assertThat(response.projectKey()).matches("MOBIL_MOBILEAPP_BE_[0-9A-F]{8}");
    }

    @Test
    void localMemoryWriteRejectsFileSourceRefWithoutExplicitProjectKey() {
        InMemoryMemoryRepository repository = new InMemoryMemoryRepository();
        MemoryMcpTool tool = memoryTool(repository);
        McpClientContextHolder.set(new McpClientContext("AI_ORCHESTRATION", "codex", "local",
                List.of("memory.write", "provider.codex")));

        assertThatThrownBy(() -> tool.write("participationText is set by CampaignModelConverterService",
                "Campaign participationText note", "decision", List.of("campaign"),
                "src/main/java/com/acme/digital/mobileapp/domain/campaign/CampaignModelConverterService.java",
                "project", "codex"))
                .isInstanceOf(McpAccessException.class)
                .hasMessageContaining("explicit projectKey");
        assertThat(repository.items).isEmpty();
    }

    @Test
    void localMemoryWriteAcceptsFileSourceRefWithExplicitProjectKey() {
        InMemoryMemoryRepository repository = new InMemoryMemoryRepository();
        MemoryMcpTool tool = memoryTool(repository);
        McpClientContextHolder.set(new McpClientContext("AI_ORCHESTRATION", "codex", "local",
                List.of("memory.write", "provider.codex")));

        MemoryWriteResponse response = tool.write("participationText is set by CampaignModelConverterService",
                "Campaign participationText note", "decision", List.of("campaign"),
                "src/main/java/com/acme/digital/mobileapp/domain/campaign/CampaignModelConverterService.java",
                "project", "codex", "mobil-mobileapp-be");

        MemoryItem stored = repository.items.get(response.memoryId());
        assertThat(stored.scope()).isEqualTo(MemoryScope.PROJECT);
        assertThat(stored.projectKey()).isEqualTo("MOBIL_MOBILEAPP_BE");
        assertThat(response.projectKey()).isEqualTo("MOBIL_MOBILEAPP_BE");
    }

    @Test
    void writeAuditNamesTheTargetProjectNotTheCallersDefaultForSavedAndRejectedWrites() {
        InMemoryMemoryRepository repository = new InMemoryMemoryRepository();
        McpClientContext caller = new McpClientContext("AI_ORCHESTRATION", "panel", "local",
                List.of("memory.write"));
        McpClientContextHolder.set(caller);
        MemoryWriteResponse saved = memoryTool(repository).write("Invoices are archived after 400 days",
                "Invoice retention", "decision", List.of("retention"), null, "project", null, "panel-target");
        assertThat(saved.projectKey()).isEqualTo("PANEL_TARGET");

        memoryTool(repository, rejectedGate("duplicate_memory")).write("Invoices are archived after 400 days",
                "Invoice retention again", "decision", List.of("retention"), null, "project", null, "panel-target");

        assertThat(accessLogRepository.entries).hasSize(2);
        assertThat(accessLogRepository.entries).extracting(McpAccessLogEntry::projectKey)
                .containsExactly("PANEL_TARGET", "PANEL_TARGET");
        assertThat(accessLogRepository.entries.get(1).metadata()).containsEntry("outcome", "not_saved");
        assertThat(accessLogRepository.entries).extracting(McpAccessLogEntry::clientId)
                .containsOnly("panel");
    }

    @Test
    void shouldInferGlobalScopeForGeneralArchitectureRuleWhenScopeIsMissing() {
        InMemoryMemoryRepository repository = new InMemoryMemoryRepository();
        MemoryMcpTool tool = memoryTool(repository);
        McpClientContextHolder.set(writeContext("PROJECT_A"));

        MemoryWriteResponse response = tool.write(
                "Architecture rule: write ArchUnit tests for hexagonal boundaries and keep domain clean.",
                "Reusable hexagonal architecture rule", "rule", List.of("architecture", "archunit"), null);

        MemoryItem stored = repository.items.get(response.memoryId());
        assertThat(stored.scope()).isEqualTo(MemoryScope.GLOBAL);
        assertThat(stored.projectKey()).isNull();
        assertThat(stored.metadata()).containsEntry("scopeDecisionMode", "server_classified");
    }

    @Test
    void shouldWriteActiveMemoryWhenGateReturnsAutoActive() {
        InMemoryMemoryRepository repository = new InMemoryMemoryRepository();
        MemoryMcpTool tool = memoryTool(repository, autoActiveGate());
        McpClientContextHolder.set(writeContext("PROJECT_A"));

        MemoryWriteResponse response = tool.write("Use ArchUnit for hexagonal boundaries",
                "ArchUnit architecture rule", "rule", List.of("architecture"), null, "global");

        MemoryItem stored = repository.items.get(response.memoryId());
        assertThat(stored.status()).isEqualTo(MemoryStatus.ACTIVE);
        assertThat(response.status()).isEqualTo("active");
        assertThat(response.gateVerdict()).isEqualTo("AUTO_ACTIVE");
        assertThat(response.confirmationRequired()).isFalse();
        assertThat(stored.metadata())
                .containsEntry("gateVerdict", "AUTO_ACTIVE")
                .containsEntry("confirmationRequired", false);
    }

    @Test
    void ruleAuthorityRejectsMcpAutoActiveRuleWithoutPersistingIt() {
        InMemoryMemoryRepository repository = new InMemoryMemoryRepository();
        MemoryService guarded = MemoryServiceTestFixture.create(repository, properties, event -> {
        }, new DefaultPolicyEngine(new PolicyProperties(true, true, "admin-token", List.of())),
                new PiiScrubber(), null, MemoryServiceTestFixture.rules(true));
        MemoryMcpTool tool = memoryTool(repository, guarded, autoActiveGate());
        McpClientContextHolder.set(writeContext("PROJECT_A"));

        assertThatThrownBy(() -> tool.write("Use ArchUnit for hexagonal boundaries",
                "ArchUnit architecture rule", "rule", List.of("architecture"), null, "global"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rules.promote");

        assertThat(repository.items).isEmpty();
        assertThat(accessLogRepository.entries.getFirst().decision()).isEqualTo("error");
    }

    @Test
    void shouldAuditAutoActiveViaWhenScopeAllowsAutoActive() {
        InMemoryMemoryRepository repository = new InMemoryMemoryRepository();
        MemoryMcpTool tool = memoryTool(repository, autoActiveGate());
        McpClientContextHolder.set(new McpClientContext("PROJECT_A", "codex", "local",
                List.of("memory.write", "memory.auto_active_write")));

        MemoryWriteResponse response = tool.write("Use ArchUnit for hexagonal boundaries",
                "ArchUnit architecture rule", "rule", List.of("architecture"), null, "global");

        MemoryItem stored = repository.items.get(response.memoryId());
        assertThat(stored.status()).isEqualTo(MemoryStatus.ACTIVE);
        assertThat(stored.metadata()).containsEntry("autoActiveVia", "local_trust_scope");
        assertThat(accessLogRepository.entries.getFirst().metadata())
                .containsEntry("autoActiveVia", "local_trust_scope");
    }

    @Test
    void shouldDiscardMemoryWhenGateReturnsRejected() {
        InMemoryMemoryRepository repository = new InMemoryMemoryRepository();
        MemoryMcpTool tool = memoryTool(repository, rejectedGate("unsafe_content"));
        McpClientContextHolder.set(writeContext("PROJECT_A"));

        MemoryWriteResponse response = tool.write("bad memory", "Rejected memory", "rule", List.of("test"), null);

        assertThat(response.memoryId()).isNull();
        assertThat(repository.items).isEmpty();
        assertThat(repository.events).isEmpty();
        assertThat(repository.reviewQueue).isEmpty();
        assertThat(response.status()).isEqualTo("not_saved");
        assertThat(response.gateVerdict()).isEqualTo("REJECTED");
        assertThat(response.confirmationRequired()).isFalse();
        assertThat(response.gateReason()).isEqualTo("unsafe_content");
    }

    @Test
    void shouldDiscardSensitiveRejectedMemory() {
        InMemoryMemoryRepository repository = new InMemoryMemoryRepository();
        MemoryMcpTool tool = memoryTool(repository, rejectedGate("sensitive-term"));
        McpClientContextHolder.set(writeContext("PROJECT_A"));

        MemoryWriteResponse response = tool.write("internal-prod-svc must never be logged",
                "internal-prod-svc secret", "rule", List.of("security"), null);

        assertThat(response.memoryId()).isNull();
        assertThat(repository.items).isEmpty();
        assertThat(repository.events).isEmpty();
        assertThat(repository.reviewQueue).isEmpty();
        assertThat(response.gateReason()).isEqualTo("sensitive-term");
        assertThat(accessLogRepository.entries.toString()).doesNotContain("internal-prod-svc");
    }

    @Test
    void shouldRejectWriteWithReadOnlyKey() {
        MemoryMcpTool tool = memoryTool(new InMemoryMemoryRepository());
        McpClientContextHolder.set(readOnlyContext("PROJECT_A"));

        assertThatThrownBy(() -> tool.write("content", "summary", "rule", List.of(), null))
                .isInstanceOf(McpAccessException.class);

        assertThat(accessLogRepository.entries.getFirst().toolName()).isEqualTo("memory.write");
        assertThat(accessLogRepository.entries.getFirst().decision()).isEqualTo("denied_scope");
    }

    @Test
    void shouldUpdateMemoryContentAndProjectKeyWithWriteScope() {
        InMemoryMemoryRepository repository = new InMemoryMemoryRepository();
        MemoryService memoryService = memoryService(repository);
        MemoryItem active = memoryService.create(createRequest("AI_ORCHESTRATION_4DEB9118",
                "Campaign participationText null root-cause notes", MemoryStatus.ACTIVE));
        MemoryMcpTool tool = memoryTool(repository, memoryService);
        McpClientContextHolder.set(new McpClientContext("AI_ORCHESTRATION", "codex", "local",
                List.of("memory.write")));

        MemoryUpdateResponse response = tool.update(active.id().toString(),
                "Campaign participationText root cause",
                "participationText is derived from campaignEnrollmentChannel in the MobileApp campaign flow.",
                List.of("campaign", "mobileapp"),
                0.93,
                "project",
                "mobil-mobileapp-be-e45b732a",
                "CampaignModelConverterService.java; CampaignService.java",
                "Correct project key after MobileApp scan.");

        MemoryItem updated = repository.items.get(active.id());
        assertThat(response.memoryId()).isEqualTo(active.id());
        assertThat(updated.projectKey()).isEqualTo("MOBIL_MOBILEAPP_BE_E45B732A");
        assertThat(updated.summary()).isEqualTo("Campaign participationText root cause");
        assertThat(updated.text()).contains("campaignEnrollmentChannel");
        assertThat(updated.vectorId()).isNotEqualTo(active.vectorId());
        assertThat(repository.eventsForMemory(active.id()).getLast().eventType()).isEqualTo(MemoryEventType.UPDATED);
        assertThat(repository.eventsForMemory(active.id()).getLast().metadata())
                .containsEntry("projectKeyChanged", true)
                .containsEntry("textHashChanged", true);
        assertThat(accessLogRepository.entries.getLast().toolName()).isEqualTo("memory.update");
        assertThat(accessLogRepository.entries.getLast().decision()).isEqualTo("success");
    }

    @Test
    void memoryUpdatePreservesReplacesAndExplicitlyClearsTypedCodeLocators() {
        InMemoryMemoryRepository repository = new InMemoryMemoryRepository();
        MemoryService memoryService = memoryService(repository);
        MemoryMcpTool tool = memoryTool(repository, memoryService);
        McpClientContextHolder.set(writeContext("PROJECT_A"));
        List<MemoryCodeLocator> original = List.of(new MemoryCodeLocator(MemoryCodeLocatorKind.FILE,
                "src/main/java/PaymentService.java", null, null));
        MemoryWriteResponse written = tool.write("Payment flow decision", "Payment decision", "decision",
                List.of("payment"), "ticket:PAY-42", "project", null, "PROJECT_A", original);

        tool.update(written.memoryId().toString(), "Payment decision v2", null, null, null,
                null, null, null, null, "Preserve locators while clarifying summary");
        assertThat(MemoryCodeLocatorMetadata.read(repository.items.get(written.memoryId()).metadata()).items())
                .containsExactlyElementsOf(original);

        List<MemoryCodeLocator> replacement = List.of(new MemoryCodeLocator(MemoryCodeLocatorKind.SYMBOL,
                "com.acme.PaymentService#pay", null, MemoryCodeLocatorRelationship.CONSTRAINS));
        tool.update(written.memoryId().toString(), null, null, null, null,
                null, null, null, replacement, "Target the exact method");
        assertThat(MemoryCodeLocatorMetadata.read(repository.items.get(written.memoryId()).metadata()).items())
                .containsExactlyElementsOf(replacement);

        tool.update(written.memoryId().toString(), null, null, null, null,
                null, null, null, List.of(), "Clear obsolete code binding");
        MemoryCodeLocatorMetadata.Decoded cleared = MemoryCodeLocatorMetadata.read(
                repository.items.get(written.memoryId()).metadata());
        assertThat(cleared.present()).isTrue();
        assertThat(cleared.items()).isEmpty();
    }

    @Test
    void memoryUpdateRequiresReason() {
        InMemoryMemoryRepository repository = new InMemoryMemoryRepository();
        MemoryService memoryService = memoryService(repository);
        MemoryItem active = memoryService.create(createRequest("PROJECT_A", "update me", MemoryStatus.ACTIVE));
        MemoryMcpTool tool = memoryTool(repository, memoryService);
        McpClientContextHolder.set(writeContext("PROJECT_A"));

        assertThatThrownBy(() -> tool.update(active.id().toString(),
                "Updated summary", null, null, null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reason is required");
    }

    @Test
    void bearerMemoryUpdateRejectsDifferentProjectKey() {
        InMemoryMemoryRepository repository = new InMemoryMemoryRepository();
        MemoryService memoryService = memoryService(repository);
        MemoryItem active = memoryService.create(createRequest("PROJECT_A", "update me", MemoryStatus.ACTIVE));
        MemoryMcpTool tool = memoryTool(repository, memoryService);
        McpClientContextHolder.set(writeContext("PROJECT_A"));

        assertThatThrownBy(() -> tool.update(active.id().toString(),
                null, null, null, null, "project", "PROJECT_B", null, "Move to wrong project"))
                .isInstanceOf(McpAccessException.class)
                .hasMessageContaining("different project");
        assertThat(repository.items.get(active.id()).projectKey()).isEqualTo("PROJECT_A");
    }

    @Test
    void shouldArchiveMemoryWithWriteKey() {
        InMemoryMemoryRepository repository = new InMemoryMemoryRepository();
        MemoryService memoryService = memoryService(repository);
        MemoryItem active = memoryService.create(createRequest("PROJECT_A", "archive me", MemoryStatus.ACTIVE));
        MemoryMcpTool tool = memoryTool(repository, memoryService);
        McpClientContextHolder.set(writeContext("PROJECT_A"));

        MemoryDeleteResponse response = tool.delete(active.id().toString(), "archive", "duplicate memory",
                null, null, null);

        assertThat(response.mode()).isEqualTo("archive");
        assertThat(response.status()).isEqualTo("archived");
        assertThat(repository.items.get(active.id()).status()).isEqualTo(MemoryStatus.ARCHIVED);
        McpAccessLogEntry entry = accessLogRepository.entries.getFirst();
        assertThat(entry.toolName()).isEqualTo("memory.delete");
        assertThat(entry.decision()).isEqualTo("success");
        assertThat(entry.metadata())
                .containsEntry("mode", "archive")
                .containsEntry("previousStatus", "active")
                .containsEntry("newStatus", "archived");
    }

    @Test
    void shouldArchiveGlobalMemoryWithWriteKey() {
        InMemoryMemoryRepository repository = new InMemoryMemoryRepository();
        MemoryService memoryService = memoryService(repository);
        MemoryItem global = memoryService.create(createGlobalRequest("archive global"));
        MemoryMcpTool tool = memoryTool(repository, memoryService);
        McpClientContextHolder.set(writeContext("PROJECT_A"));

        MemoryDeleteResponse response = tool.delete(global.id().toString(), "archive", "global duplicate",
                null, null, null);

        assertThat(response.status()).isEqualTo("archived");
        assertThat(repository.items.get(global.id()).scope()).isEqualTo(MemoryScope.GLOBAL);
        assertThat(repository.items.get(global.id()).status()).isEqualTo(MemoryStatus.ARCHIVED);
        assertThat(accessLogRepository.entries.getFirst().metadata())
                .containsEntry("mode", "archive")
                .containsEntry("scope", "global");
    }

    @Test
    void shouldHardDeleteMemoryWithHumanConfirmationAndScrubbedAudit() {
        InMemoryMemoryRepository repository = new InMemoryMemoryRepository();
        MemoryService memoryService = memoryService(repository);
        MemoryItem pending = memoryService.create(createGlobalRequest("delete me permanently"));
        MemoryMcpTool tool = memoryTool(repository, memoryService);
        McpClientContextHolder.set(writeContext("PROJECT_A"));

        MemoryDeleteResponse response = tool.delete(pending.id().toString(), "hard_delete",
                "obsolete duplicate", true, "turn-delete-1",
                "kalıcı sil, email dev@example.com");

        assertThat(response.mode()).isEqualTo("hard_delete");
        assertThat(response.status()).isEqualTo("deleted");
        assertThat(repository.items).doesNotContainKey(pending.id());
        assertThat(repository.eventsForMemory(pending.id())).isEmpty();
        assertThat(repository.reviewQueueForMemory(pending.id())).isEmpty();
        McpAccessLogEntry entry = accessLogRepository.entries.getFirst();
        assertThat(entry.toolName()).isEqualTo("memory.delete");
        assertThat(entry.decision()).isEqualTo("success");
        assertThat(entry.metadata())
                .containsEntry("mode", "hard_delete")
                .containsEntry("previousStatus", "pending_review")
                .containsEntry("scope", "global")
                .containsEntry("humanConfirmed", true)
                .containsEntry("humanTurnRef", "turn-delete-1")
                .containsEntry("humanRawTextScrubbed", "kalıcı sil, email [REDACTED]")
                .containsEntry("humanRawTextHash", sha256("kalıcı sil, email dev@example.com"))
                .containsEntry("humanRawTextLength", "kalıcı sil, email dev@example.com".length());
        assertThat(entry.metadata()).doesNotContainValue("kalıcı sil, email dev@example.com");
    }

    @Test
    void shouldRejectHardDeleteWithoutHumanConfirmationFields() {
        InMemoryMemoryRepository repository = new InMemoryMemoryRepository();
        MemoryService memoryService = memoryService(repository);
        MemoryItem pending = memoryService.create(createGlobalRequest("missing hard delete confirmation"));
        MemoryMcpTool tool = memoryTool(repository, memoryService);
        McpClientContextHolder.set(writeContext("PROJECT_A"));

        assertThatThrownBy(() -> tool.delete(pending.id().toString(), "hard_delete",
                "obsolete duplicate", false, "turn-delete-1", "kalıcı sil"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("humanConfirmed=true");
        assertThatThrownBy(() -> tool.delete(pending.id().toString(), "hard_delete",
                "obsolete duplicate", true, " ", "kalıcı sil"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("humanTurnRef");
        assertThatThrownBy(() -> tool.delete(pending.id().toString(), "hard_delete",
                "obsolete duplicate", true, "turn-delete-1", " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("humanRawText");

        assertThat(repository.items).containsKey(pending.id());
        assertThat(accessLogRepository.entries).allMatch(entry -> "error".equals(entry.decision()));
    }

    @Test
    void shouldRejectDeleteWithInvalidModeOrBlankReason() {
        InMemoryMemoryRepository repository = new InMemoryMemoryRepository();
        MemoryService memoryService = memoryService(repository);
        MemoryItem pending = memoryService.create(createGlobalRequest("invalid delete input"));
        MemoryMcpTool tool = memoryTool(repository, memoryService);
        McpClientContextHolder.set(writeContext("PROJECT_A"));

        assertThatThrownBy(() -> tool.delete(pending.id().toString(), "poof", "obsolete", null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mode must be 'archive' or 'hard_delete'");
        assertThatThrownBy(() -> tool.delete(pending.id().toString(), "archive", " ", null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reason is required");

        assertThat(repository.items).containsKey(pending.id());
        assertThat(accessLogRepository.entries).allMatch(entry -> "error".equals(entry.decision()));
    }

    @Test
    void shouldRejectDeleteWithReadOnlyKey() {
        InMemoryMemoryRepository repository = new InMemoryMemoryRepository();
        MemoryItem active = memoryService(repository).create(createRequest("PROJECT_A", "read only delete",
                MemoryStatus.ACTIVE));
        MemoryMcpTool tool = memoryTool(repository);
        McpClientContextHolder.set(readOnlyContext("PROJECT_A"));

        assertThatThrownBy(() -> tool.delete(active.id().toString(), "archive", "duplicate", null, null, null))
                .isInstanceOf(McpAccessException.class)
                .hasMessageContaining("memory.delete");

        assertThat(repository.items.get(active.id()).status()).isEqualTo(MemoryStatus.ACTIVE);
        assertThat(accessLogRepository.entries.getFirst().toolName()).isEqualTo("memory.delete");
        assertThat(accessLogRepository.entries.getFirst().decision()).isEqualTo("denied_scope");
    }

    @Test
    void shouldRejectCrossProjectDelete() {
        InMemoryMemoryRepository repository = new InMemoryMemoryRepository();
        MemoryItem active = memoryService(repository).create(createRequest("PROJECT_B", "other project delete",
                MemoryStatus.ACTIVE));
        MemoryMcpTool tool = memoryTool(repository);
        McpClientContextHolder.set(writeContext("PROJECT_A"));

        assertThatThrownBy(() -> tool.delete(active.id().toString(), "archive", "duplicate", null, null, null))
                .isInstanceOf(McpAccessException.class);

        assertThat(repository.items.get(active.id()).status()).isEqualTo(MemoryStatus.ACTIVE);
        assertThat(accessLogRepository.entries.getFirst().decision()).isEqualTo("denied_scope");
    }

    @Test
    void shouldApproveMemoryAsAdmin() {
        InMemoryMemoryRepository repository = new InMemoryMemoryRepository();
        MemoryService memoryService = memoryService(repository);
        MemoryItem pending = memoryService.create(createRequest("PROJECT_A", "pending approve"));
        MemoryMcpTool tool = memoryTool(repository, memoryService);
        McpClientContextHolder.set(adminContext("PROJECT_A"));

        MemoryApproveResponse response = tool.approve(pending.id().toString(), "approve", "looks good");

        assertThat(response.status()).isEqualTo("active");
        assertThat(repository.items.get(pending.id()).status()).isEqualTo(MemoryStatus.ACTIVE);
        McpAccessLogEntry entry = accessLogRepository.entries.getFirst();
        assertThat(entry.decision()).isEqualTo("success");
        assertThat(entry.metadata())
                .containsEntry("newStatus", "active")
                .containsEntry("bypass", true);
    }

    @Test
    void shouldRejectAdminApproveWithoutBypassReason() {
        InMemoryMemoryRepository repository = new InMemoryMemoryRepository();
        MemoryService memoryService = memoryService(repository);
        MemoryItem pending = memoryService.create(createRequest("PROJECT_A", "pending approve no reason"));
        MemoryMcpTool tool = memoryTool(repository, memoryService);
        McpClientContextHolder.set(adminContext("PROJECT_A"));

        assertThatThrownBy(() -> tool.approve(pending.id().toString(), "approve", " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reason is required");

        assertThat(repository.items.get(pending.id()).status()).isEqualTo(MemoryStatus.PENDING_REVIEW);
        assertThat(accessLogRepository.entries.getFirst().decision()).isEqualTo("error");
    }

    @Test
    void shouldRejectApproveWithoutAdminScope() {
        InMemoryMemoryRepository repository = new InMemoryMemoryRepository();
        MemoryItem pending = memoryService(repository).create(createRequest("PROJECT_A", "pending approve"));
        MemoryMcpTool tool = memoryTool(repository);
        McpClientContextHolder.set(writeContext("PROJECT_A"));

        assertThatThrownBy(() -> tool.approve(pending.id().toString(), "approve", "nope"))
                .isInstanceOf(McpAccessException.class);

        assertThat(accessLogRepository.entries.getFirst().decision()).isEqualTo("denied_scope");
    }

    @Test
    void shouldRejectCrossProjectApprove() {
        InMemoryMemoryRepository repository = new InMemoryMemoryRepository();
        MemoryItem pending = memoryService(repository).create(createRequest("PROJECT_B", "project b memory"));
        MemoryMcpTool tool = memoryTool(repository);
        McpClientContextHolder.set(adminContext("PROJECT_A"));

        assertThatThrownBy(() -> tool.approve(pending.id().toString(), "approve", "wrong project"))
                .isInstanceOf(McpAccessException.class);

        assertThat(repository.items.get(pending.id()).status()).isEqualTo(MemoryStatus.PENDING_REVIEW);
        assertThat(accessLogRepository.entries.getFirst().decision()).isEqualTo("denied_scope");
    }

    @Test
    void shouldListPendingMemoriesAsAdmin() {
        InMemoryMemoryRepository repository = new InMemoryMemoryRepository();
        MemoryService memoryService = memoryService(repository);
        for (int i = 0; i < 5; i++) {
            memoryService.create(createRequest("PROJECT_A", "pending " + i));
        }
        memoryService.create(createRequest("PROJECT_B", "other project"));
        MemoryMcpTool tool = memoryTool(repository, memoryService);
        McpClientContextHolder.set(adminContext("PROJECT_A"));

        MemoryReviewPendingResponse response = tool.reviewPending("PROJECT_A", 2, 1);

        assertThat(response.items()).hasSize(2);
        assertThat(response.limit()).isEqualTo(2);
        assertThat(response.offset()).isEqualTo(1);
        assertThat(response.items()).allMatch(item -> "PROJECT_A".equals(item.projectKey()));
        assertThat(accessLogRepository.entries.getFirst().decision()).isEqualTo("success");
    }

    @Test
    void shouldRejectReviewPendingWithoutAdminScope() {
        MemoryMcpTool tool = memoryTool(new InMemoryMemoryRepository());
        McpClientContextHolder.set(writeContext("PROJECT_A"));

        assertThatThrownBy(() -> tool.reviewPending("PROJECT_A", 10, 0))
                .isInstanceOf(McpAccessException.class);

        assertThat(accessLogRepository.entries.getFirst().decision()).isEqualTo("denied_scope");
    }

    @Test
    void shouldListPendingApprovalCardsWithHumanConfirmedScope() {
        InMemoryMemoryRepository repository = new InMemoryMemoryRepository();
        MemoryService memoryService = memoryService(repository);
        MemoryItem projectMemory = memoryService.create(createRequest("PROJECT_A", "architecture rule"));
        MemoryItem globalMemory = memoryService.create(createGlobalRequest("global architecture rule"));
        memoryService.create(createRequest("PROJECT_B", "other project"));
        MemoryReviewMcpTool tool = reviewTool(repository, memoryService);
        McpClientContextHolder.set(writeContext("PROJECT_A"));

        MemoryInlineApprovalPendingResponse response = tool.pending(10, 0);

        assertThat(response.items()).hasSize(2);
        assertThat(response.count()).isEqualTo(2);
        assertThat(response.items()).extracting(item -> item.memoryId())
                .containsExactlyInAnyOrder(projectMemory.id(), globalMemory.id());
        assertThat(response.items()).extracting(item -> item.projectKey())
                .containsExactlyInAnyOrder("PROJECT_A", null);
        assertThat(response.items()).extracting(item -> item.scope())
                .containsExactlyInAnyOrder("project", "global");
        McpAccessLogEntry accessLog = accessLogRepository.entries.getFirst();
        assertThat(accessLog.toolName()).isEqualTo("memory.pending");
        assertThat(accessLog.decision()).isEqualTo("success");
        assertThat(accessLog.metadata()).containsEntry("count", 2);
    }

    @Test
    void shouldReturnEmptyPendingApprovalCardsWithHumanConfirmedScope() {
        InMemoryMemoryRepository repository = new InMemoryMemoryRepository();
        MemoryService memoryService = memoryService(repository);
        memoryService.create(createRequest("PROJECT_B", "other project"));
        MemoryReviewMcpTool tool = reviewTool(repository, memoryService);
        McpClientContextHolder.set(writeContext("PROJECT_A"));

        MemoryInlineApprovalPendingResponse response = tool.pending(10, 0);

        assertThat(response.items()).isEmpty();
        assertThat(response.count()).isZero();
        McpAccessLogEntry accessLog = accessLogRepository.entries.getFirst();
        assertThat(accessLog.toolName()).isEqualTo("memory.pending");
        assertThat(accessLog.decision()).isEqualTo("success");
        assertThat(accessLog.metadata()).containsEntry("count", 0);
    }

    @Test
    void shouldRejectPendingApprovalCardsWithoutHumanConfirmedScope() {
        InMemoryMemoryRepository repository = new InMemoryMemoryRepository();
        MemoryReviewMcpTool tool = reviewTool(repository, memoryService(repository));
        McpClientContextHolder.set(readOnlyContext("PROJECT_A"));

        assertThatThrownBy(() -> tool.pending(10, 0))
                .isInstanceOf(McpAccessException.class)
                .hasMessageContaining("memory.pending");

        assertThat(accessLogRepository.entries.getFirst().toolName()).isEqualTo("memory.pending");
        assertThat(accessLogRepository.entries.getFirst().decision()).isEqualTo("denied_scope");
    }

    @Test
    void shouldScrubPiiOnDirectWrite() {
        InMemoryMemoryRepository repository = new InMemoryMemoryRepository();
        MemoryMcpTool tool = memoryTool(repository);
        McpClientContextHolder.set(writeContext("PROJECT_A"));

        MemoryWriteResponse response = tool.write("contact dev@example.com before release",
                "contact memory", "decision", List.of("contact"), "external-ticket-1");

        MemoryItem stored = repository.items.get(response.memoryId());
        assertThat(stored.text()).contains("[REDACTED]").doesNotContain("dev@example.com");
        assertThat(stored.sourceRef()).isEqualTo("external-ticket-1");
    }

    @Test
    void shouldDecideHumanConfirmedApprovalWithWriteKeyScope() {
        InMemoryMemoryRepository repository = new InMemoryMemoryRepository();
        MemoryService memoryService = memoryService(repository);
        MemoryItem pending = memoryService.create(createRequest("PROJECT_A", "human confirmed"));
        MemoryReviewMcpTool tool = reviewTool(repository, memoryService);
        McpClientContextHolder.set(writeContext("PROJECT_A"));

        MemoryInlineApprovalDecisionResponse response =
                tool.decide(pending.id().toString(), "approve", "turn-123", null, "onaylandı");

        assertThat(response.decision()).isEqualTo("approve");
        assertThat(response.actor()).isEqualTo("mcp:self-pipeline:turn:turn-123");
        assertThat(repository.items.get(pending.id()).status()).isEqualTo(MemoryStatus.ACTIVE);
        MemoryEvent statusEvent = repository.eventsForMemory(pending.id()).getLast();
        assertThat(statusEvent.metadata())
                .containsEntry("humanConfirmed", true)
                .containsEntry("humanTurnRef", "turn-123")
                .containsEntry("actor", "mcp:self-pipeline:turn:turn-123");
        McpAccessLogEntry accessLog = accessLogRepository.entries.getFirst();
        assertThat(accessLog.decision()).isEqualTo("success");
        assertThat(accessLog.metadata())
                .containsEntry("humanConfirmed", true)
                .containsEntry("humanTurnRef", "turn-123")
                .containsEntry("deprecated", true);
    }

    @Test
    void shouldRejectHumanConfirmedDecisionWithoutTurnRef() {
        InMemoryMemoryRepository repository = new InMemoryMemoryRepository();
        MemoryService memoryService = memoryService(repository);
        MemoryItem pending = memoryService.create(createRequest("PROJECT_A", "missing turn"));
        MemoryReviewMcpTool tool = reviewTool(repository, memoryService);
        McpClientContextHolder.set(writeContext("PROJECT_A"));

        assertThatThrownBy(() -> tool.decide(pending.id().toString(), "approve", " ", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("human_turn_ref_required");

        assertThat(repository.items.get(pending.id()).status()).isEqualTo(MemoryStatus.PENDING_REVIEW);
        assertThat(accessLogRepository.entries.getFirst().decision()).isEqualTo("denied");
    }

    @Test
    void shouldKeepLegacyDecisionSynonymsOnDeprecatedReviewDecide() {
        InMemoryMemoryRepository repository = new InMemoryMemoryRepository();
        MemoryService memoryService = memoryService(repository);
        MemoryItem pending = memoryService.create(createRequest("PROJECT_A", "legacy synonym"));
        MemoryReviewMcpTool tool = reviewTool(repository, memoryService);
        McpClientContextHolder.set(writeContext("PROJECT_A"));

        MemoryInlineApprovalDecisionResponse response =
                tool.decide(pending.id().toString(), "onayla", "turn-legacy-1", null, "legacy ok");

        assertThat(response.decision()).isEqualTo("approve");
        assertThat(repository.items.get(pending.id()).status()).isEqualTo(MemoryStatus.ACTIVE);
        McpAccessLogEntry accessLog = accessLogRepository.entries.getFirst();
        assertThat(accessLog.toolName()).isEqualTo("memory.review.decide");
        assertThat(accessLog.metadata()).containsEntry("deprecated", true);
    }

    @Test
    void shouldRejectHumanConfirmedDecisionWithoutScope() {
        InMemoryMemoryRepository repository = new InMemoryMemoryRepository();
        MemoryService memoryService = memoryService(repository);
        MemoryItem pending = memoryService.create(createRequest("PROJECT_A", "no scope"));
        MemoryReviewMcpTool tool = reviewTool(repository, memoryService);
        McpClientContextHolder.set(readOnlyContext("PROJECT_A"));

        assertThatThrownBy(() -> tool.decide(pending.id().toString(), "approve", "turn-123", null, null))
                .isInstanceOf(McpAccessException.class)
                .hasMessageContaining("memory.human_confirmed_approve");

        assertThat(repository.items.get(pending.id()).status()).isEqualTo(MemoryStatus.PENDING_REVIEW);
        assertThat(accessLogRepository.entries.getFirst().decision()).isEqualTo("denied_scope");
    }

    @Test
    void shouldEditViaHumanConfirmedReviewDecision() {
        InMemoryMemoryRepository repository = new InMemoryMemoryRepository();
        MemoryService memoryService = memoryService(repository);
        MemoryItem pending = memoryService.create(createRequest("PROJECT_A", "edit me"));
        MemoryReviewMcpTool tool = reviewTool(repository, memoryService);
        McpClientContextHolder.set(writeContext("PROJECT_A"));

        MemoryInlineApprovalDecisionResponse response = tool.decide(pending.id().toString(), "edit", "turn-456",
                "Edited memory content", "düzeltildi");

        assertThat(response.decision()).isEqualTo("edit");
        assertThat(repository.items.get(pending.id()).status()).isEqualTo(MemoryStatus.REJECTED);
        assertThat(response.replacement()).isNotNull();
        assertThat(response.replacement().status()).isEqualTo(MemoryStatus.ACTIVE);
        assertThat(response.replacement().text()).isEqualTo("Edited memory content");
        assertThat(response.replacement().metadata())
                .containsEntry("editedFrom", pending.id().toString())
                .containsEntry("humanConfirmed", true)
                .containsEntry("humanTurnRef", "turn-456");
        assertThat(repository.eventsForMemory(pending.id()).getLast().metadata())
                .containsEntry("supersededBy", response.replacement().id().toString())
                .containsEntry("humanConfirmed", true);
    }

    @Test
    void shouldConfirmPendingMemoryApprovalWithAgentInterpretedIntent() {
        InMemoryMemoryRepository repository = new InMemoryMemoryRepository();
        MemoryService memoryService = memoryService(repository);
        MemoryReviewMcpTool reviewTool = reviewTool(repository, memoryService);
        McpClientContextHolder.set(writeContext("PROJECT_A"));
        // Existing candidates from independent review workflows remain confirmable.
        MemoryItem pending = memoryService.create(createRequest("PROJECT_A", "Hexagonal ports rule"));
        MemoryWriteResponse writeResponse = new MemoryWriteResponse(pending.id(), "pending_review", null);

        var response = reviewTool.confirm(writeResponse.memoryId().toString(), "approve", true,
                "ok, contact dev@example.com", 0.91, "turn-approve-1", null, "approved by human");

        assertThat(response.status()).isEqualTo("active");
        assertThat(response.decision()).isEqualTo("approve");
        assertThat(response.aiInterpretedAsApproval()).isTrue();
        assertThat(repository.items.get(writeResponse.memoryId()).status()).isEqualTo(MemoryStatus.ACTIVE);
        MemoryEvent event = repository.eventsForMemory(writeResponse.memoryId()).getLast();
        assertThat(event.metadata())
                .containsEntry("humanConfirmed", true)
                .containsEntry("humanTurnRef", "turn-approve-1")
                .containsEntry("aiInterpretedAsApproval", true)
                .containsEntry("agentConfidence", 0.91)
                .containsEntry("humanRawTextScrubbed", "ok, contact [REDACTED]")
                .containsEntry("humanRawTextHash", sha256("ok, contact dev@example.com"))
                .containsEntry("humanRawTextLength", "ok, contact dev@example.com".length());
        assertThat(event.metadata()).doesNotContainValue("ok, contact dev@example.com");
        McpAccessLogEntry accessLog = accessLogRepository.entries.getLast();
        assertThat(accessLog.toolName()).isEqualTo("memory.confirm");
        assertThat(accessLog.decision()).isEqualTo("success");
        assertThat(accessLog.metadata()).containsEntry("humanRawTextScrubbed", "ok, contact [REDACTED]");
        assertThat(accessLog.metadata()).doesNotContainValue("ok, contact dev@example.com");
    }

    @Test
    void shouldConfirmPendingMemoryRejectionWithAgentInterpretedIntent() {
        InMemoryMemoryRepository repository = new InMemoryMemoryRepository();
        MemoryService memoryService = memoryService(repository);
        MemoryItem pending = memoryService.create(createRequest("PROJECT_A", "reject candidate"));
        MemoryReviewMcpTool tool = reviewTool(repository, memoryService);
        McpClientContextHolder.set(writeContext("PROJECT_A"));

        var response = tool.confirm(pending.id().toString(), "reject", false, "hayır", 0.88,
                "turn-reject-1", null, "not useful");

        assertThat(response.status()).isEqualTo("rejected");
        assertThat(response.decision()).isEqualTo("reject");
        assertThat(response.aiInterpretedAsApproval()).isFalse();
        assertThat(repository.items.get(pending.id()).status()).isEqualTo(MemoryStatus.REJECTED);
    }

    @Test
    void shouldConfirmPendingMemoryEditWithReplacementActive() {
        InMemoryMemoryRepository repository = new InMemoryMemoryRepository();
        MemoryService memoryService = memoryService(repository);
        MemoryItem pending = memoryService.create(createRequest("PROJECT_A", "edit candidate"));
        MemoryReviewMcpTool tool = reviewTool(repository, memoryService);
        McpClientContextHolder.set(writeContext("PROJECT_A"));

        var response = tool.confirm(pending.id().toString(), "edit", true, "düzeltip kaydet", 0.93,
                "turn-edit-1", "Edited memory through memory.confirm", "human edited");

        assertThat(response.status()).isEqualTo("rejected");
        assertThat(repository.items.get(pending.id()).status()).isEqualTo(MemoryStatus.REJECTED);
        assertThat(response.replacement()).isNotNull();
        assertThat(response.replacement().status()).isEqualTo(MemoryStatus.ACTIVE);
        assertThat(response.replacement().text()).isEqualTo("Edited memory through memory.confirm");
        assertThat(response.replacement().metadata())
                .containsEntry("editedFrom", pending.id().toString())
                .containsEntry("humanConfirmed", true)
                .containsEntry("humanTurnRef", "turn-edit-1")
                .containsEntry("aiInterpretedAsApproval", true)
                .containsEntry("agentConfidence", 0.93);
        assertThat(repository.eventsForMemory(pending.id()).getLast().metadata())
                .containsEntry("supersededBy", response.replacement().id().toString())
                .containsEntry("humanRawTextHash", sha256("düzeltip kaydet"));
    }

    @Test
    void shouldRejectConfirmWhenAgentConfidenceIsBelowFloor() {
        InMemoryMemoryRepository repository = new InMemoryMemoryRepository();
        MemoryService memoryService = memoryService(repository);
        MemoryItem pending = memoryService.create(createRequest("PROJECT_A", "low confidence"));
        MemoryReviewMcpTool tool = reviewTool(repository, memoryService);
        McpClientContextHolder.set(writeContext("PROJECT_A"));

        assertThatThrownBy(() -> tool.confirm(pending.id().toString(), "approve", true, "ok", 0.5,
                "turn-low-confidence", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("low_agent_confidence");

        assertThat(repository.items.get(pending.id()).status()).isEqualTo(MemoryStatus.PENDING_REVIEW);
        assertThat(repository.eventsForMemory(pending.id()).getLast().metadata())
                .containsEntry("decision", "denied")
                .containsEntry("lowAgentConfidence", true);
        assertThat(accessLogRepository.entries.getLast().decision()).isEqualTo("denied");
    }

    @Test
    void shouldRejectConfirmForAlreadyActiveMemoryWithoutStateChange() {
        InMemoryMemoryRepository repository = new InMemoryMemoryRepository();
        MemoryService memoryService = memoryService(repository);
        MemoryItem active = memoryService.create(createRequest("PROJECT_A", "already active", MemoryStatus.ACTIVE));
        MemoryReviewMcpTool tool = reviewTool(repository, memoryService);
        McpClientContextHolder.set(writeContext("PROJECT_A"));

        assertThatThrownBy(() -> tool.confirm(active.id().toString(), "approve", true, "ok", 0.9,
                "turn-active", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("memory_already_active");

        assertThat(repository.items.get(active.id()).status()).isEqualTo(MemoryStatus.ACTIVE);
        assertThat(repository.eventsForMemory(active.id()).getLast().metadata())
                .containsEntry("decision", "denied")
                .containsEntry("reason", "memory_already_active");
    }

    @Test
    void shouldRejectConfirmForAlreadyRejectedMemoryWithoutStateChange() {
        InMemoryMemoryRepository repository = new InMemoryMemoryRepository();
        MemoryService memoryService = memoryService(repository);
        MemoryItem rejected = memoryService.create(createRequest("PROJECT_A", "already rejected",
                MemoryStatus.REJECTED));
        MemoryReviewMcpTool tool = reviewTool(repository, memoryService);
        McpClientContextHolder.set(writeContext("PROJECT_A"));

        assertThatThrownBy(() -> tool.confirm(rejected.id().toString(), "reject", false, "no", 0.9,
                "turn-rejected", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("memory_already_rejected");

        assertThat(repository.items.get(rejected.id()).status()).isEqualTo(MemoryStatus.REJECTED);
    }

    @Test
    void shouldRejectConfirmForArchivedMemoryWithoutStateChange() {
        InMemoryMemoryRepository repository = new InMemoryMemoryRepository();
        MemoryService memoryService = memoryService(repository);
        MemoryItem archived = memoryService.create(createRequest("PROJECT_A", "archived", MemoryStatus.ARCHIVED));
        MemoryReviewMcpTool tool = reviewTool(repository, memoryService);
        McpClientContextHolder.set(writeContext("PROJECT_A"));

        assertThatThrownBy(() -> tool.confirm(archived.id().toString(), "reject", false, "no", 0.9,
                "turn-archived", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("memory_archived");

        assertThat(repository.items.get(archived.id()).status()).isEqualTo(MemoryStatus.ARCHIVED);
    }

    @Test
    void shouldRejectConfirmDecisionIntentMismatch() {
        InMemoryMemoryRepository repository = new InMemoryMemoryRepository();
        MemoryService memoryService = memoryService(repository);
        MemoryItem pending = memoryService.create(createRequest("PROJECT_A", "mismatch"));
        MemoryReviewMcpTool tool = reviewTool(repository, memoryService);
        McpClientContextHolder.set(writeContext("PROJECT_A"));

        assertThatThrownBy(() -> tool.confirm(pending.id().toString(), "approve", false, "no", 0.9,
                "turn-mismatch", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("decision_intent_mismatch");

        assertThat(repository.items.get(pending.id()).status()).isEqualTo(MemoryStatus.PENDING_REVIEW);
    }

    @Test
    void shouldRejectConfirmWithoutHumanTurnRef() {
        InMemoryMemoryRepository repository = new InMemoryMemoryRepository();
        MemoryService memoryService = memoryService(repository);
        MemoryItem pending = memoryService.create(createRequest("PROJECT_A", "missing turn confirm"));
        MemoryReviewMcpTool tool = reviewTool(repository, memoryService);
        McpClientContextHolder.set(writeContext("PROJECT_A"));

        assertThatThrownBy(() -> tool.confirm(pending.id().toString(), "approve", true, "ok", 0.9,
                " ", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("human_turn_ref_required");

        assertThat(repository.items.get(pending.id()).status()).isEqualTo(MemoryStatus.PENDING_REVIEW);
    }

    @Test
    void shouldRejectCrossProjectConfirmForProjectMemory() {
        InMemoryMemoryRepository repository = new InMemoryMemoryRepository();
        MemoryService memoryService = memoryService(repository);
        MemoryItem pending = memoryService.create(createRequest("PROJECT_B", "project b confirm"));
        MemoryReviewMcpTool tool = reviewTool(repository, memoryService);
        McpClientContextHolder.set(writeContext("PROJECT_A"));

        assertThatThrownBy(() -> tool.confirm(pending.id().toString(), "approve", true, "ok", 0.9,
                "turn-cross-project", null, null))
                .isInstanceOf(McpAccessException.class);

        assertThat(repository.items.get(pending.id()).status()).isEqualTo(MemoryStatus.PENDING_REVIEW);
        assertThat(accessLogRepository.entries.getLast().decision()).isEqualTo("denied_scope");
    }

    @Test
    void shouldAllowCrossProjectConfirmForGlobalMemory() {
        InMemoryMemoryRepository repository = new InMemoryMemoryRepository();
        MemoryService memoryService = memoryService(repository);
        MemoryItem pending = memoryService.create(createGlobalRequest("global pending confirm"));
        MemoryReviewMcpTool tool = reviewTool(repository, memoryService);
        McpClientContextHolder.set(writeContext("PROJECT_A"));

        var response = tool.confirm(pending.id().toString(), "approve", true, "ok", 0.9,
                "turn-global", null, null);

        assertThat(response.status()).isEqualTo("active");
        assertThat(repository.items.get(pending.id()).status()).isEqualTo(MemoryStatus.ACTIVE);
    }

    private MemoryMcpTool memoryTool(InMemoryMemoryRepository repository) {
        return memoryTool(repository, memoryService(repository));
    }

    private MemoryMcpTool memoryTool(InMemoryMemoryRepository repository, MemoryWriteGate memoryWriteGate) {
        return memoryTool(repository, memoryService(repository), memoryWriteGate);
    }

    private MemoryMcpTool memoryTool(InMemoryMemoryRepository repository, MemoryService memoryService) {
        return memoryTool(repository, memoryService, autoActiveGate());
    }

    private MemoryMcpTool memoryTool(InMemoryMemoryRepository repository, MemoryService memoryService,
            MemoryWriteGate memoryWriteGate) {
        MemoryReviewService reviewService = MemoryReviewServiceTestFixture.create(
                repository, mock(MemoryVectorIndex.class), event -> {
        });
        return new MemoryMcpTool(mock(MemoryRetrievalService.class), memoryService, reviewService, new PiiScrubber(),
                auditLogger, properties, memoryWriteGate, mock(com.mbworldwideapps.aiorchestration.modules.scanner.CodeBaselineRepository.class), mock(com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryRelationService.class, org.mockito.Answers.RETURNS_DEEP_STUBS));
    }

    private MemoryReviewMcpTool reviewTool(InMemoryMemoryRepository repository, MemoryService memoryService) {
        MemoryReviewService reviewService = MemoryReviewServiceTestFixture.create(
                repository, mock(MemoryVectorIndex.class), event -> {
        });
        return new MemoryReviewMcpTool(memoryService, reviewService,
                new MemoryInlineApprovalProxy(memoryService, reviewService), auditLogger);
    }

    private MemoryService memoryService(InMemoryMemoryRepository repository) {
        return MemoryServiceTestFixture.create(
                repository,
                properties,
                event -> {
                },
                new DefaultPolicyEngine(new PolicyProperties(true, true, "admin-token", List.of())),
                new PiiScrubber());
    }

    private static CreateMemoryRequest createRequest(String projectKey, String summary) {
        return createRequest(projectKey, summary, MemoryStatus.PENDING_REVIEW);
    }

    private static CreateMemoryRequest createRequest(String projectKey, String summary, MemoryStatus status) {
        return new CreateMemoryRequest(
                MemoryScope.PROJECT,
                projectKey,
                MemoryType.RULE,
                summary,
                "text for " + summary,
                List.of("test"),
                0.9,
                status,
                MemorySourceType.MANUAL,
                "test:" + UUID.randomUUID(),
                "test",
                Map.of(),
                Instant.now(),
                null);
    }

    private static CreateMemoryRequest createGlobalRequest(String summary) {
        return new CreateMemoryRequest(
                MemoryScope.GLOBAL,
                null,
                MemoryType.RULE,
                summary,
                "text for " + summary,
                List.of("test"),
                0.9,
                MemoryStatus.PENDING_REVIEW,
                MemorySourceType.MANUAL,
                "test:" + UUID.randomUUID(),
                "test",
                Map.of(),
                Instant.now(),
                null);
    }

    private static McpClientContext readOnlyContext(String projectKey) {
        return context(projectKey, List.of("memory.read", "knowledge.read", "transcript.write"));
    }

    private static McpClientContext writeContext(String projectKey) {
        return context(projectKey, List.of("memory.read", "knowledge.read", "transcript.write",
                "memory.write", "memory.pending", "memory.human_confirmed_approve", "memory.delete",
                "knowledge.ingest"));
    }

    private static McpClientContext adminContext(String projectKey) {
        return context(projectKey, List.of("memory.read", "knowledge.read", "transcript.write",
                "memory.write", "memory.pending", "memory.delete", "knowledge.ingest", "memory.approve",
                "memory.admin"));
    }

    private static McpClientContext context(String projectKey, List<String> scopes) {
        return new McpClientContext(projectKey, "self-pipeline", "mcp_abcd", scopes);
    }

    private static MemoryWriteGate autoActiveGate() {
        return (request, context) -> new MemoryWriteGate.GateDecision(MemoryWriteGate.Verdict.AUTO_ACTIVE,
                "clear_rule", null, List.of("safe"), 0.92, Map.of());
    }

    private static MemoryWriteGate rejectedGate(String reason) {
        return (request, context) -> new MemoryWriteGate.GateDecision(MemoryWriteGate.Verdict.REJECTED,
                reason, null, List.of(), 1.0, Map.of());
    }

    private static AiOrchestrationProperties properties() {
        return new AiOrchestrationProperties(
                1200,
                160,
                5,
                "logs/test-mcp-g2.jsonl",
                384,
                "qwen3:8b",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                new AiOrchestrationProperties.Mcp(true, "sse", "/mcp", true, 5, 50, 10_000L),
                365);
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static final class InMemoryAccessLogRepository implements McpAccessLogRepository {
        private final List<McpAccessLogEntry> entries = new ArrayList<>();

        @Override
        public void insert(McpAccessLogEntry entry) {
            entries.add(entry);
        }

        @Override
        public List<McpAccessLogEntry> list() {
            return entries;
        }
    }

    private static final class InMemoryMemoryRepository implements MemoryRepository {
        private final Map<UUID, MemoryItem> items = new LinkedHashMap<>();
        private final List<MemoryEvent> events = new ArrayList<>();
        private final List<ReviewQueueItem> reviewQueue = new ArrayList<>();

        @Override
        public MemoryItem save(MemoryItem item) {
            items.put(item.id(), item);
            return item;
        }

        @Override
        public Optional<MemoryItem> findById(UUID id) {
            return Optional.ofNullable(items.get(id));
        }

        @Override
        public Optional<MemoryItem> findBySourceRef(String sourceRef) {
            return items.values().stream()
                    .filter(item -> sourceRef != null && sourceRef.equals(item.sourceRef()))
                    .findFirst();
        }

        @Override
        public List<MemoryItem> list(MemoryScope scope, MemoryStatus status, String projectKey) {
            return items.values().stream()
                    .filter(item -> scope == null || item.scope() == scope)
                    .filter(item -> status == null || item.status() == status)
                    .filter(item -> projectKey == null || projectKey.equals(item.projectKey()))
                    .toList();
        }

        @Override
        public MemoryItem update(MemoryItem item) {
            items.put(item.id(), item);
            return item;
        }

        @Override
        public boolean updateStatus(UUID id, MemoryStatus status) {
            MemoryItem item = items.get(id);
            if (item == null) {
                return false;
            }
            items.put(id, new MemoryItem(item.id(), item.vectorId(), item.scope(), item.projectKey(),
                    item.memoryType(), item.summary(), item.text(), item.tags(), item.confidence(), status,
                    item.sourceType(), item.sourceRef(), item.owner(), item.metadata(), item.createdAt(),
                    Instant.now(), item.lastUsedAt(), item.lastVerifiedAt(), item.expiresAt()));
            return true;
        }

        @Override
        public void insertEvent(MemoryEvent event) {
            events.add(event);
        }

        @Override
        public List<MemoryEvent> eventsForMemory(UUID memoryId) {
            return events.stream().filter(event -> event.memoryId().equals(memoryId)).toList();
        }

        @Override
        public void insertReviewQueue(ReviewQueueItem item) {
            reviewQueue.add(item);
        }

        @Override
        public List<ReviewQueueItem> reviewQueueForMemory(UUID memoryId) {
            return reviewQueue.stream().filter(item -> item.candidateMemoryId().equals(memoryId)).toList();
        }

        @Override
        public int updateReviewQueueStatus(UUID memoryId, ReviewStatus expectedStatus, ReviewStatus status,
                Instant reviewedAt, Map<String, Object> metadata) {
            int updated = 0;
            for (int i = 0; i < reviewQueue.size(); i++) {
                ReviewQueueItem item = reviewQueue.get(i);
                if (item.candidateMemoryId().equals(memoryId) && item.status() == expectedStatus) {
                    reviewQueue.set(i, new ReviewQueueItem(item.id(), item.candidateMemoryId(), item.reason(), status,
                            metadata, item.createdAt(), reviewedAt));
                    updated++;
                }
            }
            return updated;
        }

        @Override
        public void deleteById(UUID id) {
            items.remove(id);
            events.removeIf(event -> id.equals(event.memoryId()));
            reviewQueue.removeIf(item -> id.equals(item.candidateMemoryId()));
        }
    }
}
