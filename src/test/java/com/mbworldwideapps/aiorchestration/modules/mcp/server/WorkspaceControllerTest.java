package com.mbworldwideapps.aiorchestration.modules.mcp.server;

import static org.assertj.core.api.Assertions.assertThat;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.mbworldwideapps.aiorchestration.config.LocalTrustProperties;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryLifecycleService;
import com.mbworldwideapps.aiorchestration.modules.rules.WorkspaceRuleRevisionService;
import com.mbworldwideapps.aiorchestration.modules.workspace.WorkspaceReadService;

import org.junit.jupiter.api.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

class WorkspaceControllerTest {
    MockMvc mvc;
    WorkspaceReadService reads;
    MemoryMcpTool memory;
    McpAuditLogger audit;
    WorkspaceController controller;
    MemoryLifecycleService lifecycle;
    MemoryReviewMcpTool review;
    RulesMcpTool rules;
    ReferenceMcpTool referenceTool;

    @BeforeEach
    void setup() {
        reads = mock(WorkspaceReadService.class);
        memory = mock(MemoryMcpTool.class);
        audit = mock(McpAuditLogger.class);
        lifecycle = mock(MemoryLifecycleService.class);
        review = mock(MemoryReviewMcpTool.class);
        rules = mock(RulesMcpTool.class);
        referenceTool = mock(ReferenceMcpTool.class);
        controller =
                new WorkspaceController(
                        reads,
                        memory,
                        mock(WorkspaceRuleRevisionService.class),
                        audit,
                        lifecycle,
                        mock(com.mbworldwideapps.aiorchestration.modules.references.ReferenceService.class),
                        review,
                        rules,
                        referenceTool);
        var filter =
                new McpAuthenticationFilter(
                        mock(McpApiKeyRepository.class),
                        audit,
                        new LocalTrustProperties(true, "TEST", "workspace-test", null));
        mvc = MockMvcBuilders.standaloneSetup(controller).addFilters(filter).build();
        when(reads.projects()).thenReturn(List.of("TEST"));
    }

    MockHttpServletRequestBuilder local(MockHttpServletRequestBuilder b) {
        return b.header("X-Workspace-Request", "1")
                .with(
                        r -> {
                            r.setRemoteAddr("127.0.0.1");
                            r.setServerName("localhost");
                            return r;
                        });
    }

    @Test
    void realAuthenticationFilterCreatesAndClearsContext() throws Exception {
        mvc.perform(local(get("/workspace/api/projects")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projects[0]").value("TEST"))
                .andExpect(header().string("Cache-Control", "no-store"));
        org.assertj.core.api.Assertions.assertThat(McpClientContextHolder.get()).isNull();
    }

    @Test
    void rejectsCrossOriginRebindingRemoteAndMissingHeader() throws Exception {
        mvc.perform(local(get("/workspace/api/projects")).header("Origin", "https://evil.example"))
                .andExpect(status().isForbidden());
        mvc.perform(
                        local(get("/workspace/api/projects"))
                                .with(
                                        r -> {
                                            r.setServerName("evil.example");
                                            return r;
                                        }))
                .andExpect(status().isForbidden());
        mvc.perform(
                        local(get("/workspace/api/projects"))
                                .with(
                                        r -> {
                                            r.setRemoteAddr("192.0.2.1");
                                            return r;
                                        }))
                .andExpect(status().isForbidden());
        mvc.perform(
                        get("/workspace/api/projects")
                                .with(
                                        r -> {
                                            r.setRemoteAddr("127.0.0.1");
                                            return r;
                                        }))
                .andExpect(status().isForbidden());
        verifyNoInteractions(reads);
    }

    @Test
    void missingWorkspaceHeaderAuditsAuthenticationDenial() throws Exception {
        mvc.perform(get("/workspace/api/projects").with(r -> {
            r.setRemoteAddr("127.0.0.1");
            r.setServerName("localhost");
            return r;
        })).andExpect(status().isForbidden());
        verify(audit).log(any(), eq("workspace.access"), isNull(), eq(0), any(), eq("denied_auth"));
        verifyNoInteractions(reads);
    }

    @Test
    void missingScopeAuditsOnlyScopeDenial() {
        McpClientContextHolder.set(new McpClientContext("TEST", "test", "test", List.of()));
        try {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> controller.projects())
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
            verify(audit).log(any(), eq("workspace.access"), isNull(), eq(0), any(), eq("denied_scope"));
            verifyNoMoreInteractions(audit);
            verifyNoInteractions(reads);
        } finally {
            McpClientContextHolder.clear();
        }
    }

    @Test
    void memoryWritesDelegateToExistingToolWithValidatedFields() throws Exception {
        mvc.perform(
                        local(post("/workspace/api/memory/123"))
                                .contentType("application/json")
                                .content(
                                        "{\"summary\":\"Updated\",\"text\":\"Content\",\"tags\":[\"test\"],\"reason\":\"Correction\"}"))
                .andExpect(status().isOk());
        verify(memory)
                .update(
                        "123",
                        "Updated",
                        "Content",
                        List.of("test"),
                        null,
                        null,
                        null,
                        null,
                        null,
                        "Correction");
    }

    @Test
    void missingReasonCannotWrite() throws Exception {
        mvc.perform(
                        local(post("/workspace/api/memory/123"))
                                .contentType("application/json")
                                .content("{\"summary\":\"Updated\",\"text\":\"Content\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(memory);
    }

    @Test
    void panelErrorsFollowTheWorkspaceLanguageHeader() throws Exception {
        String longQuery = "x".repeat(2049);
        mvc.perform(local(get("/workspace/api/items")).param("kind", "memory").param("query", longQuery))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Filter is too long"));
        mvc.perform(local(get("/workspace/api/items")).param("kind", "memory").param("query", longQuery)
                        .header("X-Workspace-Lang", "tr"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Filtre çok uzun"));
        verifyNoInteractions(reads);
    }

    String lifecycleRequest() {
        return "{\"decision\":\"INVALIDATE\",\"projectKey\":\"TEST\",\"memoryId\":\"00000000-0000-0000-0000-000000000001\"}";
    }

    @Test void lifecyclePreviewAndDecisionAreLocalAuditedAndUseServerActor() throws Exception {
        mvc.perform(local(post("/workspace/api/memory/lifecycle/preview"))
                .contentType("application/json").content(lifecycleRequest())).andExpect(status().isOk());
        verify(lifecycle).preview(argThat(s -> s.decision() == MemoryLifecycleService.Decision.INVALIDATE && s.projectKey().equals("TEST")));
        verify(audit).log(any(),eq("workspace.memory.lifecycle.preview"),isNull(),eq(1),any(),eq("success"));
        mvc.perform(local(post("/workspace/api/memory/lifecycle/decide"))
                .contentType("application/json").content("{\"selection\":" + lifecycleRequest()
                        + ",\"previewHash\":\"" + "a".repeat(64) + "\",\"humanConfirmed\":true,\"reason\":\"reviewed\",\"humanRawText\":\"Approve\",\"humanTurnRef\":\"test-turn\"}"))
                .andExpect(status().isOk());
        verify(lifecycle).decide(argThat(a -> a.humanConfirmed() && a.humanTurnRef().equals("test-turn")),eq("workspace:workspace-test"));
        verify(audit).log(any(),eq("workspace.memory.lifecycle.decide"),isNull(),eq(1),any(),eq("success"));
    }

    @Test void lifecycleRejectsOriginRemoteAndBearerBeforeService() throws Exception {
        for (String endpoint : List.of("preview", "decide")) {
            mvc.perform(local(post("/workspace/api/memory/lifecycle/"+endpoint)).header("Origin","https://evil.example")
                    .contentType("application/json").content("{}")).andExpect(status().isForbidden());
            mvc.perform(local(post("/workspace/api/memory/lifecycle/"+endpoint)).with(r -> { r.setRemoteAddr("192.0.2.1"); return r; })
                    .contentType("application/json").content("{}")).andExpect(status().isForbidden());
            var keys = mock(McpApiKeyRepository.class);
            when(keys.findActiveByHash(anyString())).thenReturn(java.util.Optional.of(new McpApiKey(java.util.UUID.randomUUID(),
                    "TEST","bearer-client","hash","key-prefix",LocalTrustProperties.DEFAULT_LOCAL_SCOPES,java.time.Instant.now(),null,null)));
            var bearerMvc = MockMvcBuilders.standaloneSetup(controller)
                    .addFilters(new McpAuthenticationFilter(keys,audit,LocalTrustProperties.disabled())).build();
            bearerMvc.perform(local(post("/workspace/api/memory/lifecycle/"+endpoint)).header("Authorization","Bearer valid-test-token")
                    .contentType("application/json").content("{}")).andExpect(status().isForbidden());
        }
        verifyNoInteractions(lifecycle);
    }

    @Test void lifecycleMissingWriteScopeAndServiceConflictAreAudited() throws Exception {
        McpClientContextHolder.set(new McpClientContext("TEST","test","test",List.of("memory.read","codebase.read")));
        try {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> controller.lifecycleDecide(null))
                    .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
            verifyNoInteractions(lifecycle);
            verify(audit).log(any(),eq("workspace.access"),isNull(),eq(0),any(),eq("denied_scope"));
        } finally { McpClientContextHolder.clear(); }
        when(lifecycle.preview(any())).thenThrow(new IllegalStateException("stale preview"));
        mvc.perform(local(post("/workspace/api/memory/lifecycle/preview"))
                .contentType("application/json").content(lifecycleRequest())).andExpect(status().isConflict());
        verify(audit).log(any(),eq("workspace.memory.lifecycle.preview"),isNull(),eq(0),any(),eq("error"));
    }

    @Test void codeMemoryOverlayUsesLocalGuardScopesAndAudit() throws Exception {
        when(reads.codeMemory("TEST",null)).thenReturn(java.util.Map.of("nodes",List.of(),"links",List.of(),"warnings",List.of()));
        mvc.perform(local(get("/workspace/api/code-memory?project=TEST"))).andExpect(status().isOk()).andExpect(jsonPath("$.nodes").isArray());
        verify(audit).log(any(),eq("workspace.code-memory"),isNull(),eq(1),any(),eq("success"));
        clearInvocations(reads);
        mvc.perform(local(get("/workspace/api/code-memory?project=TEST")).header("Origin","https://evil.example")).andExpect(status().isForbidden());
        verifyNoInteractions(reads);
        McpClientContextHolder.set(new McpClientContext("TEST","test","test",List.of("codebase.read")));
        try { org.assertj.core.api.Assertions.assertThatThrownBy(() -> controller.codeMemory("TEST",null))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class); }
        finally { McpClientContextHolder.clear(); }
    }


    @Test
    void panelCreatesMemoryThroughGatedWriteAndReturnsVerdict() throws Exception {
        when(memory.write(eq("A fact."), eq("Fact"), eq("decision"), eq(List.of("t")), eq("workspace-ui"),
                eq("project"), isNull(), eq("TEST"), isNull()))
                .thenReturn(new MemoryWriteResponse(null, "not_saved", "workspace-ui", "project", "TEST",
                        "REJECTED", false, "duplicate_memory", null, List.of()));
        mvc.perform(local(post("/workspace/api/memory")).contentType("application/json")
                        .content("{\"summary\":\"Fact\",\"content\":\"A fact.\",\"tags\":[\"t\"],\"project\":\"TEST\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("not_saved"))
                .andExpect(jsonPath("$.gateReason").value("duplicate_memory"));
    }

    @Test
    void archiveNeedsNoConfirmationButHardDeleteRequiresTypedHumanText() throws Exception {
        String id = java.util.UUID.randomUUID().toString();
        mvc.perform(local(post("/workspace/api/memory/" + id + "/delete")).contentType("application/json")
                        .content("{\"mode\":\"archive\",\"reason\":\"stale\"}"))
                .andExpect(status().isOk());
        verify(memory).delete(id, "archive", "stale", null, null, null);

        mvc.perform(local(post("/workspace/api/memory/" + id + "/delete")).contentType("application/json")
                        .content("{\"mode\":\"hard_delete\",\"reason\":\"wrong\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(local(post("/workspace/api/memory/" + id + "/delete")).contentType("application/json")
                        .content("{\"mode\":\"hard_delete\",\"reason\":\"wrong\",\"humanConfirmed\":true,\"humanRawText\":\" \"}"))
                .andExpect(status().isBadRequest());
        verify(memory, never()).delete(eq(id), eq("hard_delete"), any(), any(), any(), any());

        mvc.perform(local(post("/workspace/api/memory/" + id + "/delete")).contentType("application/json")
                        .content("{\"mode\":\"hard_delete\",\"reason\":\"wrong\",\"humanConfirmed\":true,"
                                + "\"humanRawText\":\"" + id.substring(0, 8) + "\"}"))
                .andExpect(status().isOk());
        verify(memory).delete(eq(id), eq("hard_delete"), eq("wrong"), eq(true),
                org.mockito.ArgumentMatchers.startsWith("workspace-ui:"), eq(id.substring(0, 8)));

        mvc.perform(local(post("/workspace/api/memory/" + id + "/delete")).contentType("application/json")
                        .content("{\"mode\":\"purge\",\"reason\":\"x\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void confirmPassesTheHumanClickAsTheDecision() throws Exception {
        String id = java.util.UUID.randomUUID().toString();
        mvc.perform(local(post("/workspace/api/memory/" + id + "/confirm")).contentType("application/json")
                        .content("{\"decision\":\"reject\",\"note\":\"not true\",\"project\":\"TEST\"}"))
                .andExpect(status().isOk());
        verify(review).confirm(eq(id), eq("reject"), eq(false), eq("Panel: Reddet — not true"), eq(1.0),
                org.mockito.ArgumentMatchers.startsWith("workspace-ui:"), isNull(), eq("not true"), eq("TEST"));
        mvc.perform(local(post("/workspace/api/memory/" + id + "/confirm")).contentType("application/json")
                        .content("{\"decision\":\"edit\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void ruleDraftBuildsTypedInstructionCandidatePerScope() throws Exception {
        mvc.perform(local(post("/workspace/api/rules/draft")).contentType("application/json")
                        .content("{\"scope\":\"module\",\"project\":\"TEST\",\"statement\":\"Use DTOs.\","
                                + "\"moduleGlobs\":[\"core/**\",\" \"]}"))
                .andExpect(status().isOk());
        verify(rules).draft(eq("TEST"), argThat(json -> json.contains("\"appliesAll\":false")
                && json.contains("\"targetKey\":\"core/**\"") && json.contains("\"enforcement\":\"instruction\"")));

        mvc.perform(local(post("/workspace/api/rules/draft")).contentType("application/json")
                        .content("{\"scope\":\"global\",\"project\":\"TEST\",\"statement\":\"Be brief.\"}"))
                .andExpect(status().isOk());
        verify(rules).draft(isNull(), argThat(json -> json.contains("\"appliesAll\":true")));

        mvc.perform(local(post("/workspace/api/rules/draft")).contentType("application/json")
                        .content("{\"scope\":\"module\",\"project\":\"TEST\",\"statement\":\"x\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void nodeRulesBindTheFileAndNameTheExactOverload() throws Exception {
        mvc.perform(local(post("/workspace/api/rules/draft")).contentType("application/json")
                        .content("{\"scope\":\"node\",\"project\":\"TEST\",\"statement\":\"No PII in logs.\","
                                + "\"nodeTarget\":{\"kind\":\"member\",\"path\":\"src/Owner.java\","
                                + "\"fqn\":\"p.Owner#getPet\",\"signature\":\"getPet(String)\"}}"))
                .andExpect(status().isOk());
        verify(rules).draft(eq("TEST"), argThat(json -> json.contains("\"kind\":\"file\"")
                && json.contains("\"targetKey\":\"src/Owner.java\"")
                && json.contains("\"targetKey\":\"p.Owner#getPet(String)\"")));

        mvc.perform(local(post("/workspace/api/rules/draft")).contentType("application/json")
                        .content("{\"scope\":\"node\",\"project\":\"TEST\",\"statement\":\"Keep it small.\","
                                + "\"nodeTarget\":{\"kind\":\"package\",\"path\":\"src/owner\"}}"))
                .andExpect(status().isOk());
        verify(rules).draft(eq("TEST"), argThat(json -> json.contains("\"targetKey\":\"src/owner/**\"")));
        assertThat(WorkspaceController.symbolKey("p.Owner", null)).isEqualTo("p.Owner");
    }

    @Test
    void rulePromotionEchoesServerHashesVerbatim() throws Exception {
        mvc.perform(local(post("/workspace/api/rules/draft/promote")).contentType("application/json")
                        .content("{\"draftId\":\"d1\",\"project\":\"TEST\",\"candidateHash\":\"c\","
                                + "\"approvalContentHash\":\"a\",\"confirmationCardHash\":\"k\","
                                + "\"workflowContractVersion\":\"v1\",\"humanRawText\":\"Onaylıyorum\"}"))
                .andExpect(status().isOk());
        verify(rules).promote(eq("d1"), eq("TEST"), eq("c"), eq("a"), eq("k"), eq("v1"), eq("Onaylıyorum"),
                org.mockito.ArgumentMatchers.startsWith("workspace-ui:"), eq(true), eq(1.0));
    }

    @Test
    void referenceWritesDelegateWithExpectedHash() throws Exception {
        mvc.perform(local(post("/workspace/api/references/write")).contentType("application/json")
                        .content("{\"relativePath\":\"a/b.md\",\"content\":\"x\",\"expectedHash\":\"h\"}"))
                .andExpect(status().isOk());
        verify(referenceTool).write("a/b.md", "x", "h");
        mvc.perform(local(post("/workspace/api/references/mkdir")).contentType("application/json")
                        .content("{\"relativePath\":\"a\"}"))
                .andExpect(status().isOk());
        verify(referenceTool).mkdir("a");
    }

    @Test
    void newWriteEndpointsStayBehindTheWorkspaceGuard() throws Exception {
        mvc.perform(post("/workspace/api/memory").contentType("application/json")
                        .content("{\"summary\":\"s\",\"content\":\"c\"}")
                        .with(r -> { r.setRemoteAddr("127.0.0.1"); r.setServerName("localhost"); return r; }))
                .andExpect(status().isForbidden());
        mvc.perform(local(post("/workspace/api/memory")).header("Origin", "https://evil.example")
                        .contentType("application/json").content("{\"summary\":\"s\",\"content\":\"c\"}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(memory);
    }

    @Test
    void statsAndHealthAreReadable() throws Exception {
        when(reads.stats("TEST")).thenReturn(java.util.Map.of("symbols", 3L));
        mvc.perform(local(get("/workspace/api/stats").param("project", "TEST")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.symbols").value(3));
        mvc.perform(local(get("/workspace/api/health"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void memoryEventsAndFiltersAreReadableThroughTheGuard() throws Exception {
        String id = java.util.UUID.randomUUID().toString();
        when(reads.memoryEvents(id, 30)).thenReturn(List.of(java.util.Map.of("type", "created")));
        mvc.perform(local(get("/workspace/api/memory/" + id + "/events")))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].type").value("created"));
        mvc.perform(local(get("/workspace/api/items").param("kind", "memory").param("scope", "global").param("tag", "x")))
                .andExpect(status().isOk());
        verify(reads).list("memory", "", "", "", "", "global", "x", null, 50);
    }
}
