package com.mbworldwideapps.aiorchestration.modules.mcp.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryCodeLocatorKind;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryCodeLocator;
import com.mbworldwideapps.aiorchestration.modules.scanner.CodeSymbolRecord;
import com.mbworldwideapps.aiorchestration.modules.scanner.CodeFileRecord;
import com.mbworldwideapps.aiorchestration.modules.scanner.CodeBaselineRepository;
import java.util.concurrent.atomic.AtomicReference;

import com.mbworldwideapps.aiorchestration.config.AiOrchestrationProperties;
import com.mbworldwideapps.aiorchestration.config.LocalTrustProperties;
import com.mbworldwideapps.aiorchestration.modules.agentlearning.AgentLearningService;
import com.mbworldwideapps.aiorchestration.core.pii.PiiScrubber;
import com.mbworldwideapps.aiorchestration.modules.llmgateway.SourceSnippet;
import com.mbworldwideapps.aiorchestration.modules.llmgateway.ProviderOverrideSanitizer;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryInlineApprovalCard;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryInlineApprovalPendingResponse;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryInlineApprovalProxy;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryItem;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryRetrievalService;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryReviewService;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemorySearchHit;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryScope;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryService;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemorySourceType;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryStatus;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryType;
import com.mbworldwideapps.aiorchestration.modules.scanner.ProjectWorkspace;
import com.mbworldwideapps.aiorchestration.modules.scanner.ProjectWorkspaceResolutionException;
import com.mbworldwideapps.aiorchestration.modules.scanner.ProjectWorkspaceResolver;
import com.mbworldwideapps.aiorchestration.modules.scanner.ScanCodebaseRequest;
import com.mbworldwideapps.aiorchestration.modules.scanner.ScanCodebaseResponse;
import com.mbworldwideapps.aiorchestration.modules.scanner.ScannerAsyncService;
import com.mbworldwideapps.aiorchestration.modules.scanner.ScannerService;
import com.mbworldwideapps.aiorchestration.modules.scanner.ScannerScanCancelResponse;
import com.mbworldwideapps.aiorchestration.modules.scanner.ScannerScanStartResponse;
import com.mbworldwideapps.aiorchestration.modules.scanner.ScannerScanStatusResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springaicommunity.mcp.method.tool.utils.JsonSchemaGenerator;

class McpServerIT {

    @TempDir
    Path tempDir;

    private final InMemoryAccessLogRepository accessLogRepository = new InMemoryAccessLogRepository();
    private final AiOrchestrationProperties properties = properties();
    private final McpAuditLogger auditLogger = new McpAuditLogger(accessLogRepository, properties);

    @AfterEach
    void clearContext() {
        McpClientContextHolder.clear();
    }

    @Test
    void canonicalMemorySearchRemainsDiscoverableAfterHostNameNormalization() {
        // Exact failed catalog query from the completed Codex benchmark task.
        java.util.regex.Pattern query = java.util.regex.Pattern.compile("memory__.*search|memory\\.search",
                java.util.regex.Pattern.CASE_INSENSITIVE);
        var discovered = java.util.Arrays.stream(MemoryMcpTool.class.getDeclaredMethods())
                .map(method -> method.getAnnotation(org.springaicommunity.mcp.annotation.McpTool.class))
                .filter(java.util.Objects::nonNull)
                .filter(tool -> query.matcher("mcp__ai_orchestration__" + tool.name().replace('.', '_')
                        + " " + tool.description()).find())
                .map(org.springaicommunity.mcp.annotation.McpTool::name).toList();
        assertThat(discovered).contains("memory.search");
    }

    @Test
    void plainClientCatalogContainsNoAgentLearningOperations() {
        var discovered = java.util.Arrays.stream(AgentLearningMcpTool.class.getDeclaredMethods())
                .map(method -> method.getAnnotation(org.springaicommunity.mcp.annotation.McpTool.class))
                .filter(java.util.Objects::nonNull)
                .map(org.springaicommunity.mcp.annotation.McpTool::name)
                .toList();

        assertThat(discovered).isEmpty();
    }

    @Test
    void shouldRequireBearerToken() throws Exception {
        McpAuthenticationFilter filter = new McpAuthenticationFilter(new InMemoryApiKeyRepository(), auditLogger);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/mcp/sse");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(accessLogRepository.entries).hasSize(1);
        assertThat(accessLogRepository.entries.getFirst().decision()).isEqualTo("denied_auth");
    }

    @Test
    void shouldRejectInvalidApiKey() throws Exception {
        McpAuthenticationFilter filter = new McpAuthenticationFilter(new InMemoryApiKeyRepository(), auditLogger);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/mcp/sse");
        request.addHeader("Authorization", "Bearer missing");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(accessLogRepository.entries.getFirst().decision()).isEqualTo("denied_auth");
    }

    @Test
    void shouldAllowLocalTrustFromLoopbackWithoutBearer() throws Exception {
        McpAuthenticationFilter filter = localTrustFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/mcp");
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-AI-Orch-Client", "codex local");
        request.addHeader("X-AI-Orch-Context-Key", "workspace-session-key");
        request.addHeader("X-Forwarded-For", "203.0.113.10");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<McpClientContext> captured = new AtomicReference<>();

        filter.doFilter(request, response, (servletRequest, servletResponse) ->
                captured.set(McpClientContextHolder.require()));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(captured.get()).isNotNull();
        assertThat(captured.get().projectKey()).isEqualTo("PROJECT_A");
        assertThat(captured.get().clientId()).isEqualTo("codex-local");
        assertThat(captured.get().keyPrefix()).isEqualTo("local");
        assertThat(captured.get().scopes()).containsExactly("memory.read", "knowledge.read");
        assertThat(captured.get().sessionScopeHash())
                .isEqualTo(McpAuditLogger.sha256Hex("codex-local:workspace-session-key"));
        assertThat(McpClientContextHolder.get()).isNull();
    }

    @Test
    void missingContextAndTransportHeadersExposeUnavailableSessionScope() throws Exception {
        McpAuthenticationFilter filter = localTrustFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/mcp/sse");
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-AI-Orch-Client", "codex local");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<McpClientContext> captured = new AtomicReference<>();

        filter.doFilter(request, response, (servletRequest, servletResponse) ->
                captured.set(McpClientContextHolder.require()));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(captured.get().sessionScopeHash()).isEqualTo(McpClientContext.SESSION_SCOPE_UNAVAILABLE);
        assertThat(captured.get().hasSessionScope()).isFalse();
    }

    @Test
    void shouldGrantAutoActiveScopeOnlyWhenLocalTrustFlagIsEnabled() throws Exception {
        LocalTrustProperties localTrust = new LocalTrustProperties(true, "PROJECT_A", "local-client",
                List.of("memory.read"), true);
        McpAuthenticationFilter filter = new McpAuthenticationFilter(new InMemoryApiKeyRepository(), auditLogger,
                localTrust);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/mcp");
        request.setRemoteAddr("127.0.0.1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<McpClientContext> captured = new AtomicReference<>();

        filter.doFilter(request, response, (servletRequest, servletResponse) ->
                captured.set(McpClientContextHolder.require()));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(captured.get().scopes()).containsExactly("memory.read", "memory.auto_active_write");
    }

    @Test
    void shouldRejectLocalTrustFromNonLoopbackRemoteAddress() throws Exception {
        McpAuthenticationFilter filter = localTrustFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/mcp");
        request.setRemoteAddr("10.0.0.5");
        request.addHeader("X-Forwarded-For", "127.0.0.1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("local_trust_requires_loopback");
        assertThat(accessLogRepository.entries).hasSize(1);
        assertThat(accessLogRepository.entries.getFirst().decision()).isEqualTo("denied_localhost");
        assertThat(accessLogRepository.entries.getFirst().metadata()).containsEntry("remoteAddress", "10.0.0.5");
    }

    @Test
    void shouldAllowLocalTrustFromIpv6Loopback() throws Exception {
        McpAuthenticationFilter filter = localTrustFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/mcp");
        request.setRemoteAddr("::1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<McpClientContext> captured = new AtomicReference<>();

        filter.doFilter(request, response, (servletRequest, servletResponse) ->
                captured.set(McpClientContextHolder.require()));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(captured.get().clientId()).isEqualTo("local-client");
    }

    @Test
    void shouldRunMemorySearchWithValidKey() {
        McpClientContextHolder.set(context("PROJECT_A"));
        MemoryRetrievalService retrievalService = mock(MemoryRetrievalService.class);
        MemoryService memoryService = mock(MemoryService.class);
        when(retrievalService.searchPreviews("logger", "PROJECT_A", null, 5))
                .thenReturn(List.of(memorySearchHit(1, "compact logger rule")));
        MemoryMcpTool tool = memoryTool(retrievalService, memoryService);

        MemorySearchResponse response = tool.search("logger", 5);

        assertThat(response.projectKey()).isEqualTo("PROJECT_A");
        assertThat(response.items()).hasSize(1);
        assertThat(response.items().getFirst().excerpt()).isEqualTo("compact logger rule");
        assertThat(response.totalTokenEstimate()).isEqualTo(response.items().getFirst().tokenEstimate());
        assertThat(accessLogRepository.entries.getFirst().decision()).isEqualTo("success");
        assertThat(accessLogRepository.entries.getFirst().toolName()).isEqualTo("memory.search");
    }

    @Test
    void shouldReturnEmptyForUnknownProject() {
        McpClientContextHolder.set(context("PROJECT_A"));
        MemoryRetrievalService retrievalService = mock(MemoryRetrievalService.class);
        MemoryService memoryService = mock(MemoryService.class);
        when(retrievalService.searchPreviews("project b only", "PROJECT_A", null, 5))
                .thenReturn(List.of());
        MemoryMcpTool tool = memoryTool(retrievalService, memoryService);

        MemorySearchResponse response = tool.search("project b only", 5);

        assertThat(response.projectKey()).isEqualTo("PROJECT_A");
        assertThat(response.items()).isEmpty();
        assertThat(accessLogRepository.entries.getFirst().decision()).isEqualTo("success");
        assertThat(accessLogRepository.entries.getFirst().resultCount()).isZero();
    }

    @Test
    void shouldForwardBoundedSearchDetailAndAuditInvalidBudget() {
        McpClientContextHolder.set(context("PROJECT_A"));
        MemoryRetrievalService retrieval = mock(MemoryRetrievalService.class);
        when(retrieval.searchPreviews("fact", "PROJECT_A", null, 3, 1024))
                .thenReturn(List.of(memorySearchHit(1, "complete narrow fact")));
        MemoryMcpTool tool = memoryTool(retrieval, mock(MemoryService.class));
        assertThat(tool.search("fact", 3, null, "PROJECT_A", 1024).items()).hasSize(1);
        verify(retrieval).searchPreviews("fact", "PROJECT_A", null, 3, 1024);
        assertThat(accessLogRepository.entries.getFirst().decision()).isEqualTo("success");
        // Out-of-range budgets are clamped rather than rejected, so agents never spend a retry on them.
        when(retrieval.searchPreviews("fact", "PROJECT_A", null, 3, 128))
                .thenReturn(List.of(memorySearchHit(1, "complete narrow fact")));
        assertThat(tool.search("fact", 3, null, "PROJECT_A", 127).items()).hasSize(1);
        assertThat(tool.search("fact", 3, null, "PROJECT_A", 1025).items()).hasSize(1);
        verify(retrieval).searchPreviews("fact", "PROJECT_A", null, 3, 128);
        verify(retrieval, org.mockito.Mockito.times(2)).searchPreviews("fact", "PROJECT_A", null, 3, 1024);
        assertThat(accessLogRepository.entries.getLast().decision()).isEqualTo("success");
        assertThatThrownBy(() -> tool.search("fact", 3, null, "PROJECT_B", 1024))
                .isInstanceOf(McpAccessException.class);
        assertThat(accessLogRepository.entries.getLast().decision()).isEqualTo("denied_scope");
    }

    @Test
    void localSearchResolvesProjectFromRootPathAndWarnsWhenNeitherIsGiven() {
        McpClientContextHolder.set(new McpClientContext("AI_ORCHESTRATION", "claude-code", "local",
                List.of("memory.read", "scanner.scan")));
        MemoryRetrievalService retrieval = mock(MemoryRetrievalService.class);
        when(retrieval.searchPreviews("fact", "PETCLINIC", null, 3))
                .thenReturn(List.of(memorySearchHit(1, "petclinic fact")));
        var relations = mock(com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryRelationService.class);
        when(relations.linkedReferences(any(), any())).thenReturn(
                new com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryRelationService.ReferenceLinks(List.of(), false));
        MemoryMcpTool tool = new MemoryMcpTool(retrieval, mock(MemoryService.class), mock(MemoryReviewService.class),
                new PiiScrubber(), auditLogger, properties,
                mock(com.mbworldwideapps.aiorchestration.modules.scanner.CodeBaselineRepository.class), relations);
        ScannerMcpTool scanner = mock(ScannerMcpTool.class);
        when(scanner.resolveProject("/work/petclinic", null)).thenReturn(
                new ScannerProjectResolveResponse("/work/petclinic", "PETCLINIC", "AI_ORCHESTRATION", true, true, "fp", null));
        @SuppressWarnings("unchecked")
        org.springframework.beans.factory.ObjectProvider<ScannerMcpTool> provider =
                mock(org.springframework.beans.factory.ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(scanner);
        tool.setScannerProvider(provider);

        MemorySearchResponse byRoot = (MemorySearchResponse) tool.search("fact", 3, null, null, null, "legacy",
                "/work/petclinic");
        assertThat(byRoot.projectKey()).isEqualTo("PETCLINIC");
        assertThat(byRoot.items()).hasSize(1);
        assertThat(byRoot.hint()).isNull();

        MemorySearchResponse bare = (MemorySearchResponse) tool.search("fact", 3, null, null, null, "legacy", null);
        assertThat(bare.projectKey()).isEqualTo("AI_ORCHESTRATION");
        assertThat(bare.hint()).contains("rootPath=<current working directory>");
    }

    @Test
    void shouldReturnCompactAgentSearchViewWithoutChangingLegacyDefault() throws Exception {
        McpClientContextHolder.set(context("PROJECT_A"));
        MemoryRetrievalService retrieval = mock(MemoryRetrievalService.class);
        MemorySearchHit hit = memorySearchHit(1, "complete atomic discovery text");
        when(retrieval.searchPreviews("fact", "PROJECT_A", null, 5, 1024)).thenReturn(List.of(hit));
        MemoryMcpTool tool = memoryTool(retrieval, mock(MemoryService.class));

        Object result = tool.search("fact", 50, null, "PROJECT_A", null, "agent");

        assertThat(result).isInstanceOf(MemoryAgentViewResponse.Search.class);
        MemoryAgentViewResponse.Search response = (MemoryAgentViewResponse.Search) result;
        assertThat(response.schemaVersion()).isEqualTo(1);
        assertThat(response.projectKey()).isEqualTo("PROJECT_A");
        assertThat(response.knowledge()).singleElement().satisfies(item -> {
            assertThat(item.id()).isEqualTo(hit.memoryId());
            assertThat(item.text()).isEqualTo("complete atomic discovery text");
            assertThat(item.contentComplete()).isTrue();
            assertThat(item.status()).isEqualTo("active");
        });
        assertThat(response.wordEstimate()).isPositive();
        byte[] serialized = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(response);
        assertThat(serialized.length).isLessThan(4096);
        assertThat(new String(serialized, java.nio.charset.StandardCharsets.UTF_8))
                .doesNotContain("vectorId", "citationId", "semanticScore", "lastVerifiedAt");
        verify(retrieval).searchPreviews("fact", "PROJECT_A", null, 5, 1024);
    }

    @Test
    void shouldHonorSmallerExplicitAgentExcerptBudget() {
        McpClientContextHolder.set(context("PROJECT_A"));
        MemoryRetrievalService retrieval = mock(MemoryRetrievalService.class);
        when(retrieval.searchPreviews("fact", "PROJECT_A", null, 3, 256)).thenReturn(List.of());
        MemoryMcpTool tool = memoryTool(retrieval, mock(MemoryService.class));

        tool.search("fact", 3, null, "PROJECT_A", 256, "agent");

        verify(retrieval).searchPreviews("fact", "PROJECT_A", null, 3, 256);
    }

    @Test
    void shouldPinEmptyAdvertisedSchemaForDynamicMemoryViews() throws Exception {
        var searchMethod = MemoryMcpTool.class.getMethod("search", String.class, Integer.class, String.class,
                String.class, Integer.class, String.class);
        var getMethod = MemoryMcpTool.class.getMethod("get", String.class, String.class);
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();

        for (var method : List.of(searchMethod, getMethod)) {
            assertThat(method.getReturnType()).isEqualTo(Object.class);
            var schema = mapper.readTree(JsonSchemaGenerator.generateFromType(method.getGenericReturnType()));
            assertThat(schema.size()).as(method.getName() + " advertised outputSchema field count").isEqualTo(1);
            assertThat(schema.path("$schema").asText())
                    .isEqualTo("https://json-schema.org/draft/2020-12/schema");
        }
    }

    @Test
    void shouldReturnCompactAgentGetViewAndRejectUnknownView() {
        McpClientContextHolder.set(context("PROJECT_A"));
        UUID id = UUID.randomUUID();
        MemoryService memoryService = mock(MemoryService.class);
        when(memoryService.findById(id)).thenReturn(memoryItem(id, MemoryScope.PROJECT, "PROJECT_A"));
        MemoryMcpTool tool = memoryTool(mock(MemoryRetrievalService.class), memoryService);

        Object result = tool.get(id.toString(), "agent");

        assertThat(result).isInstanceOf(MemoryAgentViewResponse.Get.class);
        MemoryAgentViewResponse.Get response = (MemoryAgentViewResponse.Get) result;
        assertThat(response.knowledge().id()).isEqualTo(id);
        assertThat(response.knowledge().text()).isEqualTo("text");
        assertThat(response.knowledge().contentComplete()).isTrue();
        assertThat(response.scope()).isEqualTo("project");
        assertThat(response.projectKey()).isEqualTo("PROJECT_A");
        assertThatThrownBy(() -> tool.get(id.toString(), "compact-ish"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("view");
    }

    @Test
    void shouldBoundExplicitDebugViewsWhileLeavingOmittedViewLegacy() {
        McpClientContextHolder.set(context("PROJECT_A"));
        MemoryRetrievalService retrieval = mock(MemoryRetrievalService.class);
        when(retrieval.searchPreviews("fact", "PROJECT_A", null, 10, 1024)).thenReturn(List.of());
        UUID id = UUID.randomUUID();
        String longText = "x".repeat(2_000);
        MemoryItem item = new MemoryItem(id, UUID.nameUUIDFromBytes(id.toString().getBytes()),
                MemoryScope.PROJECT, "PROJECT_A", MemoryType.RULE, "s".repeat(400), longText,
                java.util.stream.IntStream.range(0, 20).mapToObj(i -> "tag-" + i).toList(),
                0.9, MemoryStatus.ACTIVE, MemorySourceType.MANUAL, "r".repeat(400), "test",
                Map.of("large", "m".repeat(4_000)), Instant.now(), Instant.now(), null, Instant.now(), null);
        MemoryService memoryService = mock(MemoryService.class);
        when(memoryService.findById(id)).thenReturn(item);
        MemoryMcpTool tool = memoryTool(retrieval, memoryService);

        Object search = tool.search("fact", 50, null, "PROJECT_A", null, "debug");
        Object get = tool.get(id.toString(), "debug");

        assertThat(search).isInstanceOf(MemorySearchResponse.class);
        verify(retrieval).searchPreviews("fact", "PROJECT_A", null, 10, 1024);
        assertThat(get).isInstanceOf(MemoryAgentViewResponse.DebugGet.class);
        MemoryAgentViewResponse.DebugGet debug = (MemoryAgentViewResponse.DebugGet) get;
        assertThat(debug.knowledge().text()).hasSize(1024).endsWith("...");
        assertThat(debug.knowledge().contentComplete()).isFalse();
        assertThat(debug.summary()).hasSize(240);
        assertThat(debug.tags()).hasSize(8);
        assertThat(debug.sourceRef()).hasSize(240);
        assertThat(tool.get(id.toString())).isSameAs(item);
    }

    @Test
    void shouldKeepDiscoveryWritesBehindMemoryLearnAdmission() {
        McpClientContextHolder.set(context("PROJECT_A", "memory.write"));
        MemoryMcpTool tool = memoryTool(mock(MemoryRetrievalService.class), mock(MemoryService.class));

        assertThatThrownBy(() -> tool.write(
                "A reusable discovery about the codebase.",
                "Reusable discovery",
                "discovery",
                List.of("learning"),
                "fixture:discovery",
                "project",
                null,
                "PROJECT_A",
                List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("memory.learn");
    }

    @Test
    void shouldRejectDetailedSearchWithoutReadScope() {
        McpClientContextHolder.set(context("PROJECT_A", "knowledge.read"));
        MemoryMcpTool tool = memoryTool(mock(MemoryRetrievalService.class), mock(MemoryService.class));
        assertThatThrownBy(() -> tool.search("fact", 3, null, "PROJECT_A", 1024))
                .isInstanceOf(McpAccessException.class);
        assertThat(accessLogRepository.entries.getFirst().decision()).isEqualTo("denied_scope");
    }

    @Test
    void shouldClampTopKToMaxCap() {
        McpClientContextHolder.set(context("PROJECT_A"));
        MemoryRetrievalService retrievalService = mock(MemoryRetrievalService.class);
        MemoryService memoryService = mock(MemoryService.class);
        List<MemorySearchHit> items = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            items.add(memorySearchHit(i, "text " + i));
        }
        when(retrievalService.searchPreviews("many", "PROJECT_A", null, 50)).thenReturn(items);
        MemoryMcpTool tool = memoryTool(retrievalService, memoryService);

        MemorySearchResponse response = tool.search("many", 999);

        assertThat(response.projectKey()).isEqualTo("PROJECT_A");
        assertThat(response.items()).hasSize(50);
        verify(retrievalService).searchPreviews("many", "PROJECT_A", null, 50);
    }

    @Test
    void shouldEnforceProjectScopeOnMemoryGet() {
        McpClientContextHolder.set(context("PROJECT_A"));
        UUID id = UUID.randomUUID();
        MemoryService memoryService = mock(MemoryService.class);
        when(memoryService.findById(id)).thenReturn(memoryItem(id, MemoryScope.PROJECT, "PROJECT_B"));
        MemoryMcpTool tool = memoryTool(mock(MemoryRetrievalService.class), memoryService);

        assertThatThrownBy(() -> tool.get(id.toString()))
                .isInstanceOf(McpAccessException.class);
        assertThat(accessLogRepository.entries.getFirst().decision()).isEqualTo("denied_scope");
    }

    @Test
    void shouldRunScannerScanWithProviderOverrideWhenScopeAllowed() {
        McpClientContextHolder.set(context("PROJECT_A", "scanner.scan", "provider.codex"));
        ScannerService scannerService = mock(ScannerService.class);
        when(scannerService.scan(any())).thenReturn(new ScanCodebaseResponse(".", "PROJECT_A",
                1, 1, 0, 0, 0, 0.0, 10L, List.of()));
        ScannerMcpTool tool = new ScannerMcpTool(scannerService, auditLogger, new ProviderOverrideSanitizer(),
                unboundWorkspaceResolver());

        ScanCodebaseResponse response = tool.scan(".", true, "codex", "gpt-5", false);

        assertThat(response.projectKey()).isEqualTo("PROJECT_A");
        ArgumentCaptor<ScanCodebaseRequest> captor = ArgumentCaptor.forClass(ScanCodebaseRequest.class);
        verify(scannerService).scan(captor.capture());
        assertThat(captor.getValue().projectKey()).isEqualTo("PROJECT_A");
        assertThat(captor.getValue().providerOverride()).isEqualTo("codex");
        assertThat(captor.getValue().semanticModel()).isEqualTo("gpt-5");
        assertThat(captor.getValue().forceReindexEnabled()).isFalse();
        assertThat(accessLogRepository.entries.getFirst().metadata()).containsEntry("providerOverride", "codex");
    }

    @Test
    void localScannerScanDerivesProjectKeyFromRootPath() {
        McpClientContextHolder.set(new McpClientContext("AI_ORCHESTRATION", "codex", "local",
                List.of("scanner.scan", "provider.codex")));
        ScannerService scannerService = mock(ScannerService.class);
        when(scannerService.scan(any())).thenAnswer(invocation -> {
            ScanCodebaseRequest request = invocation.getArgument(0);
            return new ScanCodebaseResponse(request.rootPath(), request.projectKey(), 1, 1, 0, 0, 0, 0.0, 10L,
                    List.of());
        });
        ScannerMcpTool tool = new ScannerMcpTool(scannerService, auditLogger, new ProviderOverrideSanitizer(),
                unboundWorkspaceResolver());

        ScanCodebaseResponse response = tool.scan(
                "/home/dev/projects/MobileAppBackend/mobil-mobileapp-be",
                false, "codex", "gpt-5", false);

        assertThat(response.projectKey()).matches("MOBIL_MOBILEAPP_BE_[0-9A-F]{8}");
        ArgumentCaptor<ScanCodebaseRequest> captor = ArgumentCaptor.forClass(ScanCodebaseRequest.class);
        verify(scannerService).scan(captor.capture());
        assertThat(captor.getValue().projectKey()).matches("MOBIL_MOBILEAPP_BE_[0-9A-F]{8}");
        assertThat(accessLogRepository.entries.getFirst().metadata().get("projectKey"))
                .asString()
                .matches("MOBIL_MOBILEAPP_BE_[0-9A-F]{8}");
    }

    @Test
    void localScannerProjectResolveRegistersDerivedProjectKeyWithoutScan() throws Exception {
        Path freshRoot = Files.createDirectories(tempDir.resolve("fresh-project")).toRealPath();
        McpClientContextHolder.set(new McpClientContext("AI_ORCHESTRATION", "codex", "local",
                List.of("scanner.scan")));
        ScannerService scannerService = mock(ScannerService.class);
        ProjectWorkspaceResolver resolver = unboundWorkspaceResolver();
        when(resolver.registerRoot(any(String.class), any(Path.class))).thenAnswer(invocation ->
                new ProjectWorkspace(invocation.getArgument(0), invocation.getArgument(1), "a".repeat(64)));
        when(resolver.resolve(any(String.class), eq("a".repeat(64)))).thenAnswer(invocation ->
                new ProjectWorkspace(invocation.getArgument(0), freshRoot, "a".repeat(64)));
        AgentLearningService learningService = mock(AgentLearningService.class);
        UUID workspaceBindingId = UUID.randomUUID();
        when(learningService.enabled()).thenReturn(true);
        when(learningService.issueWorkspaceBinding(any(), eq("codex"), any())).thenReturn(workspaceBindingId);
        ScannerMcpTool tool = new ScannerMcpTool(scannerService, null, auditLogger,
                new ProviderOverrideSanitizer(), resolver, learningService);

        ScannerProjectResolveResponse response = tool.resolveProject(
                freshRoot.toString(), null);

        assertThat(response.projectKey()).matches("FRESH_PROJECT_[0-9A-F]{8}");
        assertThat(response.callerProjectKey()).isEqualTo("AI_ORCHESTRATION");
        assertThat(response.localTrust()).isTrue();
        assertThat(response.bindingVerified()).isTrue();
        assertThat(response.repositoryFingerprint()).isEqualTo("a".repeat(64));
        assertThat(response.workspaceBindingId()).isEqualTo(workspaceBindingId);
        verify(scannerService, org.mockito.Mockito.never()).scan(any());
        assertThat(accessLogRepository.entries.getFirst().toolName()).isEqualTo("scanner.project.resolve");
    }

    @Test
    void scannerProjectResolveRemainsAvailableWhenOptionalLearningBindingFails() throws Exception {
        Path freshRoot = Files.createDirectories(tempDir.resolve("learning-binding-failure")).toRealPath();
        McpClientContextHolder.set(new McpClientContext("AI_ORCHESTRATION", "codex", "local",
                List.of("scanner.scan")));
        ProjectWorkspace workspace = new ProjectWorkspace("BOUND_PROJECT", freshRoot, "a".repeat(64));
        ProjectWorkspaceResolver resolver = mock(ProjectWorkspaceResolver.class);
        when(resolver.resolveByPath(freshRoot)).thenReturn(workspace);
        when(resolver.resolve("BOUND_PROJECT", workspace.repositoryFingerprint())).thenReturn(workspace);
        AgentLearningService learningService = mock(AgentLearningService.class);
        when(learningService.enabled()).thenReturn(true);
        when(learningService.issueWorkspaceBinding(any(), eq("codex"), any()))
                .thenThrow(new IllegalStateException("learning storage unavailable"));
        ScannerMcpTool tool = new ScannerMcpTool(mock(ScannerService.class), null, auditLogger,
                new ProviderOverrideSanitizer(), resolver, learningService);

        ScannerProjectResolveResponse response = tool.resolveProject(freshRoot.toString(), null);

        assertThat(response.projectKey()).isEqualTo("BOUND_PROJECT");
        assertThat(response.bindingVerified()).isTrue();
        assertThat(response.workspaceBindingId()).isNull();
        assertThat(accessLogRepository.entries.getFirst().metadata())
                .containsEntry("workspaceBindingIssued", false);
    }

    @Test
    void localScannerProjectResolveReturnsPersistedVerifiedBinding() throws Exception {
        McpClientContextHolder.set(new McpClientContext("AI_ORCHESTRATION", "codex", "local",
                List.of("scanner.scan")));
        Path repositoryRoot = Files.createDirectories(tempDir.resolve("bound-repository")).toRealPath();
        ProjectWorkspace workspace = new ProjectWorkspace("BOUND_PROJECT", repositoryRoot, "a".repeat(64));
        ProjectWorkspaceResolver resolver = mock(ProjectWorkspaceResolver.class);
        when(resolver.resolveByPath(repositoryRoot)).thenReturn(workspace);
        ScannerService scannerService = mock(ScannerService.class);
        ScannerMcpTool tool = new ScannerMcpTool(scannerService, auditLogger, new ProviderOverrideSanitizer(),
                resolver);

        ScannerProjectResolveResponse response = tool.resolveProject(repositoryRoot.toString(), null);

        assertThat(response.rootPath()).isEqualTo(repositoryRoot.toString());
        assertThat(response.projectKey()).isEqualTo("BOUND_PROJECT");
        assertThat(response.bindingVerified()).isTrue();
        assertThat(response.repositoryFingerprint()).isEqualTo("a".repeat(64));
        verify(scannerService, org.mockito.Mockito.never()).scan(any());
        assertThat(accessLogRepository.entries.getFirst().metadata())
                .containsEntry("bindingVerified", true)
                .containsEntry("projectKey", "BOUND_PROJECT");
    }

    @Test
    void bearerResolveDoesNotRegisterUnknownRoots() throws Exception {
        Path freshRoot = Files.createDirectories(tempDir.resolve("bearer-fresh")).toRealPath();
        McpClientContextHolder.set(context("PROJECT_A", "scanner.scan"));
        ProjectWorkspaceResolver resolver = unboundWorkspaceResolver();
        ScannerMcpTool tool = new ScannerMcpTool(mock(ScannerService.class), auditLogger,
                new ProviderOverrideSanitizer(), resolver);
        assertThat(tool.resolveProject(freshRoot.toString(), null).bindingVerified()).isFalse();
        verify(resolver, org.mockito.Mockito.never()).registerRoot(any(), any());
    }

    @Test
    void bearerScannerProjectResolveRejectsDifferentProjectKey() {
        McpClientContextHolder.set(context("PROJECT_A", "scanner.scan"));
        ScannerMcpTool tool = new ScannerMcpTool(mock(ScannerService.class), auditLogger,
                new ProviderOverrideSanitizer(), unboundWorkspaceResolver());

        assertThatThrownBy(() -> tool.resolveProject(".", "PROJECT_B"))
                .isInstanceOf(McpAccessException.class)
                .hasMessageContaining("different project");
    }

    @Test
    void shouldStartAsyncScannerScanWithProviderOverrideWhenScopeAllowed() {
        McpClientContextHolder.set(context("PROJECT_A", "scanner.scan", "provider.codex"));
        ScannerAsyncService asyncService = mock(ScannerAsyncService.class);
        UUID scanRunId = UUID.randomUUID();
        when(asyncService.start(any())).thenReturn(new ScannerScanStartResponse(scanRunId, "queued", "/repo",
                "PROJECT_A", false, "codex", "gpt-5", false, "queued"));
        ScannerMcpTool tool = new ScannerMcpTool(mock(ScannerService.class), asyncService, auditLogger,
                new ProviderOverrideSanitizer(), unboundWorkspaceResolver());

        ScannerScanStartResponse response = tool.start("/repo", false, "codex", "gpt-5", false);

        assertThat(response.scanRunId()).isEqualTo(scanRunId);
        assertThat(response.status()).isEqualTo("queued");
        ArgumentCaptor<ScanCodebaseRequest> captor = ArgumentCaptor.forClass(ScanCodebaseRequest.class);
        verify(asyncService).start(captor.capture());
        assertThat(captor.getValue().projectKey()).isEqualTo("PROJECT_A");
        assertThat(captor.getValue().providerOverride()).isEqualTo("codex");
        assertThat(accessLogRepository.entries.getFirst().toolName()).isEqualTo("scanner.scan.start");
    }

    @Test
    void shouldReturnAsyncScannerStatus() {
        McpClientContextHolder.set(context("PROJECT_A", "scanner.scan"));
        ScannerAsyncService asyncService = mock(ScannerAsyncService.class);
        UUID scanRunId = UUID.randomUUID();
        when(asyncService.status("PROJECT_A", scanRunId)).thenReturn(Optional.of(new ScannerScanStatusResponse(
                scanRunId, "running", "/repo", "PROJECT_A", "codex", "gpt-5", true,
                Instant.now(), null, 10, 2, 0, 0, 0, false, false, Map.of("async", true))));
        ScannerMcpTool tool = new ScannerMcpTool(mock(ScannerService.class), asyncService, auditLogger,
                new ProviderOverrideSanitizer(), unboundWorkspaceResolver());

        ScannerScanStatusResponse response = tool.status(scanRunId.toString());

        assertThat(response.status()).isEqualTo("running");
        assertThat(response.done()).isFalse();
        assertThat(accessLogRepository.entries.getFirst().toolName()).isEqualTo("scanner.scan.status");
    }

    @Test
    void shouldRejectAsyncScannerStatusForOtherProject() {
        McpClientContextHolder.set(context("PROJECT_B", "scanner.scan"));
        ScannerAsyncService asyncService = mock(ScannerAsyncService.class);
        UUID scanRunId = UUID.randomUUID();
        when(asyncService.status("PROJECT_B", scanRunId)).thenReturn(Optional.empty());
        ScannerMcpTool tool = new ScannerMcpTool(mock(ScannerService.class), asyncService, auditLogger,
                new ProviderOverrideSanitizer(), unboundWorkspaceResolver());

        assertThatThrownBy(() -> tool.status(scanRunId.toString()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scanRunId not found");
        assertThat(accessLogRepository.entries.getFirst().toolName()).isEqualTo("scanner.scan.status");
    }

    @Test
    void shouldRejectAsyncScannerResultForOtherProject() {
        McpClientContextHolder.set(context("PROJECT_B", "scanner.scan"));
        ScannerAsyncService asyncService = mock(ScannerAsyncService.class);
        UUID scanRunId = UUID.randomUUID();
        when(asyncService.result("PROJECT_B", scanRunId, 25)).thenReturn(Optional.empty());
        ScannerMcpTool tool = new ScannerMcpTool(mock(ScannerService.class), asyncService, auditLogger,
                new ProviderOverrideSanitizer(), unboundWorkspaceResolver());

        assertThatThrownBy(() -> tool.result(scanRunId.toString(), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scanRunId not found");
    }

    @Test
    void shouldRejectAsyncScannerDiagnosticsForOtherProject() {
        McpClientContextHolder.set(context("PROJECT_B", "scanner.scan"));
        ScannerAsyncService asyncService = mock(ScannerAsyncService.class);
        UUID scanRunId = UUID.randomUUID();
        when(asyncService.status("PROJECT_B", scanRunId)).thenReturn(Optional.empty());
        ScannerMcpTool tool = new ScannerMcpTool(mock(ScannerService.class), asyncService, auditLogger,
                new ProviderOverrideSanitizer(), unboundWorkspaceResolver());

        assertThatThrownBy(() -> tool.diagnostics(scanRunId.toString(), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scanRunId not found");
    }

    @Test
    void shouldCancelAsyncScannerRun() {
        McpClientContextHolder.set(context("PROJECT_A", "scanner.scan"));
        ScannerAsyncService asyncService = mock(ScannerAsyncService.class);
        UUID scanRunId = UUID.randomUUID();
        when(asyncService.cancel("PROJECT_A", scanRunId, "user requested"))
                .thenReturn(Optional.of(new ScannerScanCancelResponse(scanRunId, "cancelled", true,
                        "Scan cancellation requested.")));
        ScannerMcpTool tool = new ScannerMcpTool(mock(ScannerService.class), asyncService, auditLogger,
                new ProviderOverrideSanitizer(), unboundWorkspaceResolver());

        ScannerScanCancelResponse response = tool.cancel(scanRunId.toString(), "user requested");

        assertThat(response.cancelled()).isTrue();
        assertThat(response.status()).isEqualTo("cancelled");
        assertThat(accessLogRepository.entries.getFirst().toolName()).isEqualTo("scanner.scan.cancel");
    }

    @Test
    void shouldRejectScannerScanWithoutScope() {
        McpClientContextHolder.set(context("PROJECT_A", "knowledge.read"));
        ScannerMcpTool tool = new ScannerMcpTool(mock(ScannerService.class), auditLogger,
                new ProviderOverrideSanitizer(), unboundWorkspaceResolver());

        assertThatThrownBy(() -> tool.scan(".", false, null, null, null))
                .isInstanceOf(McpAccessException.class)
                .hasMessageContaining("scanner.scan");
    }

    @Test
    void bearerScannerScanRejectsDifferentProjectKey() {
        McpClientContextHolder.set(context("PROJECT_A", "scanner.scan"));
        ScannerMcpTool tool = new ScannerMcpTool(mock(ScannerService.class), auditLogger,
                new ProviderOverrideSanitizer(), unboundWorkspaceResolver());

        assertThatThrownBy(() -> tool.scan(".", "PROJECT_B", false, null, null, null))
                .isInstanceOf(McpAccessException.class)
                .hasMessageContaining("different project");
    }

    @Test
    void bearerScannerCancelRejectsDifferentProjectKey() {
        McpClientContextHolder.set(context("PROJECT_A", "scanner.scan"));
        ScannerMcpTool tool = new ScannerMcpTool(mock(ScannerService.class), mock(ScannerAsyncService.class),
                auditLogger, new ProviderOverrideSanitizer(), unboundWorkspaceResolver());

        assertThatThrownBy(() -> tool.cancel(UUID.randomUUID().toString(), "PROJECT_B", "wrong project"))
                .isInstanceOf(McpAccessException.class)
                .hasMessageContaining("different project");
    }

    @Test
    void bearerMemorySearchRejectsDifferentProjectKey() {
        McpClientContextHolder.set(context("PROJECT_A", "memory.read"));
        MemoryMcpTool tool = memoryTool(mock(MemoryRetrievalService.class), mock(MemoryService.class));

        assertThatThrownBy(() -> tool.search("logger", 5, null, "PROJECT_B"))
                .isInstanceOf(McpAccessException.class)
                .hasMessageContaining("different project");
        assertThat(accessLogRepository.entries.getFirst().decision()).isEqualTo("denied_scope");
    }

    @Test
    void shouldAuditProviderOverrideOnMemorySearchWithoutChangingRetrieval() {
        McpClientContextHolder.set(context("PROJECT_A", "memory.read", "provider.claude"));
        MemoryRetrievalService retrievalService = mock(MemoryRetrievalService.class);
        when(retrievalService.searchPreviews("logger", "PROJECT_A", null, 5)).thenReturn(List.of());
        MemoryMcpTool tool = memoryTool(retrievalService, mock(MemoryService.class));

        MemorySearchResponse response = tool.search("logger", 5, "claude");

        assertThat(response.projectKey()).isEqualTo("PROJECT_A");
        assertThat(response.items()).isEmpty();
        verify(retrievalService).searchPreviews("logger", "PROJECT_A", null, 5);
        assertThat(accessLogRepository.entries.getFirst().metadata()).containsEntry("providerOverride", "claude");
    }

    @Test
    void shouldHashQueryInAuditLog() {
        McpClientContextHolder.set(context("PROJECT_A"));
        MemoryRetrievalService retrievalService = mock(MemoryRetrievalService.class);
        when(retrievalService.searchPreviews(eq("secret query"), eq("PROJECT_A"), eq(null), eq(5)))
                .thenReturn(List.of());
        MemoryMcpTool tool = memoryTool(retrievalService, mock(MemoryService.class));

        tool.search("secret query", 5);

        McpAccessLogEntry entry = accessLogRepository.entries.getFirst();
        assertThat(entry.queryHash()).isEqualTo(McpAuditLogger.sha256Hex("secret query"));
        assertThat(entry.toString()).doesNotContain("secret query");
    }

    @Test
    void shouldDisableMcpWhenConfigOff() {
        new ApplicationContextRunner()
                .withUserConfiguration(DisabledMcpConfig.class)
                .withPropertyValues("ai-orchestration.mcp.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(FilterRegistrationBean.class));
    }

    private static McpClientContext context(String projectKey) {
        return context(projectKey, "memory.read", "knowledge.read", "transcript.write");
    }

    private static McpClientContext context(String projectKey, String... scopes) {
        return new McpClientContext(projectKey, "self-pipeline", "mcp_abcd", List.of(scopes));
    }

    private static ProjectWorkspaceResolver unboundWorkspaceResolver() {
        ProjectWorkspaceResolver resolver = mock(ProjectWorkspaceResolver.class);
        when(resolver.resolveByPath(any(Path.class)))
                .thenThrow(new ProjectWorkspaceResolutionException("unknown_project_path:test"));
        return resolver;
    }

    private McpAuthenticationFilter localTrustFilter() {
        LocalTrustProperties localTrust = new LocalTrustProperties(true, "PROJECT_A", "local-client",
                List.of("memory.read", "knowledge.read"));
        return new McpAuthenticationFilter(new InMemoryApiKeyRepository(), auditLogger, localTrust);
    }

    private MemoryMcpTool memoryTool(MemoryRetrievalService retrievalService, MemoryService memoryService) {
        return new MemoryMcpTool(retrievalService, memoryService, mock(MemoryReviewService.class), new PiiScrubber(),
                auditLogger, properties, mock(com.mbworldwideapps.aiorchestration.modules.scanner.CodeBaselineRepository.class), mock(com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryRelationService.class, org.mockito.Answers.RETURNS_DEEP_STUBS));
    }

    @Test
    void searchLocationsAreScopedDeduplicatedAndBounded() {
        McpClientContextHolder.set(context("PROJECT_A", "memory.read", "codebase.read"));
        var retrieval = mock(MemoryRetrievalService.class);
        var baseline = mock(CodeBaselineRepository.class);
        List<UUID> ids = java.util.stream.IntStream.range(0, 10).mapToObj(i -> UUID.randomUUID()).toList();
        UUID file = UUID.randomUUID(), scan = UUID.randomUUID();
        var hits = List.of(locatorHit("project", "PROJECT_A", ids.subList(0, 4)),
                locatorHit("project", "PROJECT_A", ids.subList(3, 7)),
                locatorHit("project", "PROJECT_A", ids.subList(6, 10)));
        when(retrieval.searchPreviews("fact", "PROJECT_A", null, 3)).thenReturn(hits);
        var selected = new java.util.LinkedHashSet<>(ids.subList(0, 8));
        when(baseline.findSymbolsByIds("PROJECT_A", selected, 8)).thenReturn(
                ids.subList(0, 8).reversed().stream().map(id -> new CodeSymbolRecord(id, "PROJECT_A", file,
                        "METHOD", "get", "Example#get", "get()", null, 127, 147, "hash", scan, Map.of())).toList());
        when(baseline.findFileById("PROJECT_A", file)).thenReturn(java.util.Optional.of(
                new CodeFileRecord(file, "PROJECT_A", "src/Example.java", "hash", "java", scan, Map.of())));
        var tool = new MemoryMcpTool(retrieval, mock(MemoryService.class), mock(MemoryReviewService.class),
                new PiiScrubber(), auditLogger, properties, baseline, mock(com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryRelationService.class, org.mockito.Answers.RETURNS_DEEP_STUBS));
        var result = tool.search("fact", 3);
        assertThat(result.scannedSymbols()).extracting(MemorySearchResponse.ScannedSymbol::symbolId)
                .containsExactlyElementsOf(ids.subList(0, 8));
        assertThat(result.omittedScannedSymbols()).isEqualTo(2);
        assertThat(result.scannedSymbols().getFirst()).isEqualTo(
                new MemorySearchResponse.ScannedSymbol(ids.getFirst(), "Example#get", "src/Example.java", 127, 147, scan));
        assertThat(result.totalTokenEstimate()).isGreaterThan(hits.stream().mapToLong(MemorySearchHit::tokenEstimate).sum());
        verify(baseline).findFileById("PROJECT_A", file);
        verify(baseline).findSymbolsByIds("PROJECT_A", selected, 8);
        org.mockito.Mockito.verifyNoMoreInteractions(baseline);
    }

    @Test
    void searchWithoutCodeReadNeverConsultsScanner() {
        McpClientContextHolder.set(context("PROJECT_A", "memory.read"));
        var retrieval = mock(MemoryRetrievalService.class);
        var baseline = mock(CodeBaselineRepository.class);
        when(retrieval.searchPreviews("fact", "PROJECT_A", null, 3))
                .thenReturn(List.of(locatorHit("project", "PROJECT_A", List.of(UUID.randomUUID()))));
        var tool = new MemoryMcpTool(retrieval, mock(MemoryService.class), mock(MemoryReviewService.class),
                new PiiScrubber(), auditLogger, properties, baseline, mock(com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryRelationService.class, org.mockito.Answers.RETURNS_DEEP_STUBS));
        assertThat(tool.search("fact", 3).scannedSymbols()).isEmpty();
        org.mockito.Mockito.verifyNoInteractions(baseline);
    }

    @Test
    void searchLocationsExcludeForeignMissingAndGlobalTargets() {
        McpClientContextHolder.set(context("PROJECT_A", "memory.read", "codebase.read"));
        var retrieval = mock(MemoryRetrievalService.class);
        var baseline = mock(CodeBaselineRepository.class);
        UUID foreign = UUID.randomUUID(), missing = UUID.randomUUID(), wrongFile = UUID.randomUUID();
        UUID file = UUID.randomUUID(), absent = UUID.randomUUID(), scan = UUID.randomUUID();
        var selected = java.util.Set.of(foreign, missing, wrongFile);
        when(retrieval.searchPreviews("fact", "PROJECT_A", null, 3)).thenReturn(List.of(
                locatorHit("project", "PROJECT_A", List.of(foreign, missing, wrongFile)),
                locatorHit("global", null, List.of(UUID.randomUUID())),
                locatorHit("project", "PROJECT_B", List.of(UUID.randomUUID()))));
        when(baseline.findSymbolsByIds("PROJECT_A", selected, 8)).thenReturn(List.of(
                new CodeSymbolRecord(foreign, "PROJECT_B", file, "METHOD", "get", "Secret#get", "", null, 1, 2, "h", scan, Map.of()),
                new CodeSymbolRecord(missing, "PROJECT_A", absent, "METHOD", "get", "Gone#get", "", null, 1, 2, "h", scan, Map.of()),
                new CodeSymbolRecord(wrongFile, "PROJECT_A", file, "METHOD", "get", "Wrong#get", "", null, 1, 2, "h", scan, Map.of())));
        when(baseline.findFileById("PROJECT_A", absent)).thenReturn(java.util.Optional.empty());
        when(baseline.findFileById("PROJECT_A", file)).thenReturn(java.util.Optional.of(
                new CodeFileRecord(file, "PROJECT_B", "secret/file.java", "h", "java", scan, Map.of())));
        var tool = new MemoryMcpTool(retrieval, mock(MemoryService.class), mock(MemoryReviewService.class),
                new PiiScrubber(), auditLogger, properties, baseline, mock(com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryRelationService.class, org.mockito.Answers.RETURNS_DEEP_STUBS));
        var result = tool.search("fact", 3);
        assertThat(result.scannedSymbols()).isEmpty();
        assertThat(result.omittedScannedSymbols()).isEqualTo(3);
        verify(baseline).findSymbolsByIds("PROJECT_A", selected, 8);
        verify(baseline).findFileById("PROJECT_A", absent);
        verify(baseline).findFileById("PROJECT_A", file);
        org.mockito.Mockito.verifyNoMoreInteractions(baseline);
    }

    @Test
    void referenceSearchHintsRequireLocalTrustButNotCodeRead() {
        var retrieval = mock(MemoryRetrievalService.class);
        var baseline = mock(CodeBaselineRepository.class);
        var relations = mock(com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryRelationService.class);
        var hit = locatorHit("project", "PROJECT_A", List.of());
        when(retrieval.searchPreviews("fact", "PROJECT_A", null, 3)).thenReturn(List.of(hit, locatorHit("global", null, List.of()),
                locatorHit("project", "PROJECT_B", List.of())));
        var tool = new MemoryMcpTool(retrieval, mock(MemoryService.class), mock(MemoryReviewService.class),
                new PiiScrubber(), auditLogger, properties, baseline, relations);
        McpClientContextHolder.set(context("PROJECT_A", "memory.read", "codebase.read"));
        assertThat(tool.search("fact", 3).linkedReferences()).isEmpty();
        org.mockito.Mockito.verifyNoInteractions(relations);
        var link = new com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryRelationService.ReferenceLink(
                hit.memoryId(), UUID.randomUUID(), "topic/procedure.md", "file", "current", false, null, null);
        when(relations.linkedReferences("PROJECT_A", List.of(hit.memoryId()))).thenReturn(
                new com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryRelationService.ReferenceLinks(List.of(link), false));
        McpClientContextHolder.set(new McpClientContext("PROJECT_A", "local-client", "local", List.of("memory.read")));
        var result = tool.search("fact", 3);
        assertThat(result.linkedReferences()).containsExactly(link);
        assertThat(result.moreLinkedReferences()).isFalse();
        assertThat(result.totalTokenEstimate()).isGreaterThan(hit.tokenEstimate());
        verify(relations).linkedReferences("PROJECT_A", List.of(hit.memoryId()));
        org.mockito.Mockito.verifyNoInteractions(baseline);
    }

    private static MemorySearchHit locatorHit(String scope, String project, List<UUID> ids) {
        UUID id = UUID.randomUUID();
        return new MemorySearchHit(id, id, "memory-" + id, scope, project, "decision", "fact", "detail",
                List.of(), 1, false, 10, 1, Instant.now(), "active",
                ids.stream().map(ref -> new MemoryCodeLocator(MemoryCodeLocatorKind.SYMBOL, ref.toString(), null, null)).toList(),
                0, List.of());
    }

    private static MemorySearchHit memorySearchHit(int index, String excerpt) {
        UUID id = UUID.randomUUID();
        int tokenEstimate = Math.max(1, ("summary " + excerpt).trim().split("\\s+").length);
        return new MemorySearchHit(id, UUID.nameUUIDFromBytes(id.toString().getBytes()), "memory-" + index,
                MemoryScope.GLOBAL.value(), null, MemoryType.RULE.value(), "summary", excerpt, List.of("test"),
                0.9, false, tokenEstimate, 0.9, Instant.now(), "active", List.of(), 0, List.of());
    }

    private static MemoryItem memoryItem(UUID id, MemoryScope scope, String projectKey) {
        return new MemoryItem(id, UUID.nameUUIDFromBytes(id.toString().getBytes()), scope, projectKey,
                MemoryType.RULE, "summary", "text", List.of(), 0.9, MemoryStatus.ACTIVE, MemorySourceType.MANUAL,
                "test:" + id, "test", Map.of(), Instant.now(), Instant.now(), null, Instant.now(), null);
    }

    private static SourceSnippet snippet(String chunkId) {
        return new SourceSnippet(chunkId, "title", "docs/source.md", "snippet", 1.0, "fresh", Instant.now());
    }

    private static AiOrchestrationProperties properties() {
        return new AiOrchestrationProperties(
                1200,
                160,
                5,
                "logs/test-mcp.jsonl",
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

    private static final class InMemoryApiKeyRepository implements McpApiKeyRepository {
        private final Map<String, McpApiKey> keys = new java.util.HashMap<>();

        @Override
        public Optional<McpApiKey> findActiveByHash(String apiKeyHash) {
            return Optional.ofNullable(keys.get(apiKeyHash));
        }

        @Override
        public void updateLastUsed(String apiKeyHash) {
        }

        @Override
        public McpApiKey save(McpApiKey apiKey) {
            keys.put(apiKey.apiKeyHash(), apiKey);
            return apiKey;
        }
    }

    @Configuration
    @Import(McpServerConfiguration.class)
    @EnableConfigurationProperties(AiOrchestrationProperties.class)
    static class DisabledMcpConfig {

        @Bean
        McpAuthenticationFilter mcpAuthenticationFilter() {
            return mock(McpAuthenticationFilter.class);
        }
    }
}
