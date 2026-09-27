package com.mbworldwideapps.aiorchestration.modules.scanner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class CodeFlowSeedBuilderTest {

    private static final String PROJECT = "MOBILEAPP";
    private static final UUID RUN_ID = id("run");

    @Test
    void ranksEndpointIntegrationCacheAndDomainSeedsAndSkipsDtoOnlySymbols() {
        CodeBaselineRepository repository = mock(CodeBaselineRepository.class);
        CodeSymbolRecord endpointMethod = method("CampaignController#getCampaigns", "getCampaigns",
                "controller");
        CodeSymbolRecord feignClient = type("CampaignServiceClient", "CampaignServiceClient",
                "interface", "interface");
        CodeSymbolRecord redisService = type("RedisCampaignCacheService", "RedisCampaignCacheService",
                "class", "service");
        CodeSymbolRecord domainService = type("CampaignService", "CampaignService", "class", "service");
        CodeSymbolRecord dto = type("CampaignDto", "CampaignDto", "class", "component");
        when(repository.findSymbolsForRun(eq(PROJECT), eq(RUN_ID), anyInt()))
                .thenReturn(List.of(dto, domainService, redisService, feignClient, endpointMethod));
        when(repository.findEdgesFrom(eq(PROJECT), eq(endpointMethod.id()), anySet(), anyInt()))
                .thenReturn(List.of(
                        edge(endpointMethod, "EXPOSES_ENDPOINT", "GET /api/campaigns"),
                        edge(endpointMethod, "ANNOTATED_WITH", "GetMapping"),
                        edge(endpointMethod, "CALLS", "campaignService.getCampaigns"),
                        edge(endpointMethod, "CALLS", "CampaignResponse.from")));
        when(repository.findEdgesFrom(eq(PROJECT), eq(feignClient.id()), anySet(), anyInt()))
                .thenReturn(List.of(edge(feignClient, "ANNOTATED_WITH", "FeignClient")));
        when(repository.findEdgesFrom(eq(PROJECT), eq(redisService.id()), anySet(), anyInt()))
                .thenReturn(List.of(
                        edge(redisService, "ANNOTATED_WITH", "Service"),
                        edge(redisService, "INJECTS", "RedisTemplate"),
                        edge(redisService, "CALLS", "opsForValue")));
        when(repository.findEdgesFrom(eq(PROJECT), eq(domainService.id()), anySet(), anyInt()))
                .thenReturn(List.of(
                        edge(domainService, "ANNOTATED_WITH", "Service"),
                        edge(domainService, "INJECTS", "CampaignCommonService"),
                        edge(domainService, "INJECTS", "RedisCampaignCacheService"),
                        edge(domainService, "CALLS", "loadCustomerCampaigns"),
                        edge(domainService, "CALLS", "filterByGroup"),
                        edge(domainService, "CALLS", "enrichBrands"),
                        edge(domainService, "CALLS", "mapResponse")));
        when(repository.findEdgesFrom(eq(PROJECT), eq(dto.id()), anySet(), anyInt()))
                .thenReturn(List.of(edge(dto, "ANNOTATED_WITH", "Data")));

        List<CodeFlowSeed> seeds = new CodeFlowSeedBuilder(repository).build(PROJECT, RUN_ID, 10);

        assertThat(seeds).extracting(CodeFlowSeed::capsuleKind)
                .containsExactly(CodeFlowSeedBuilder.ENDPOINT_FLOW, CodeFlowSeedBuilder.INTEGRATION_FLOW,
                        CodeFlowSeedBuilder.CACHE_FLOW, CodeFlowSeedBuilder.DOMAIN_FLOW);
        CodeFlowSeed endpoint = seeds.getFirst();
        assertThat(endpoint.title()).isEqualTo("GET /api/campaigns -> getCampaigns");
        assertThat(endpoint.endpoint()).isEqualTo("GET /api/campaigns");
        assertThat(endpoint.triggerKind()).isEqualTo("http_endpoint");
        assertThat(endpoint.callRefs()).contains("campaignService.getCampaigns", "CampaignResponse.from");
        assertThat(seeds).noneMatch(seed -> seed.entryRef().contains("CampaignDto"));
    }

    @Test
    void returnsEmptyForMissingProjectOrRunAndHonorsLimit() {
        CodeBaselineRepository repository = mock(CodeBaselineRepository.class);
        CodeSymbolRecord first = type("FirstClient", "FirstClient", "interface", "interface");
        CodeSymbolRecord second = type("SecondClient", "SecondClient", "interface", "interface");
        when(repository.findSymbolsForRun(eq(PROJECT), eq(RUN_ID), anyInt()))
                .thenReturn(List.of(first, second));
        when(repository.findEdgesFrom(eq(PROJECT), eq(first.id()), anySet(), anyInt()))
                .thenReturn(List.of(edge(first, "ANNOTATED_WITH", "FeignClient")));
        when(repository.findEdgesFrom(eq(PROJECT), eq(second.id()), anySet(), anyInt()))
                .thenReturn(List.of(edge(second, "ANNOTATED_WITH", "FeignClient")));
        CodeFlowSeedBuilder builder = new CodeFlowSeedBuilder(repository);

        assertThat(builder.build("", RUN_ID, 10)).isEmpty();
        assertThat(builder.build(PROJECT, null, 10)).isEmpty();
        assertThat(builder.build(PROJECT, RUN_ID, 1)).hasSize(1);
    }

    private static CodeSymbolRecord type(String fqnSuffix, String name, String kind, String role) {
        return symbol(fqnSuffix, name, kind, role, "");
    }

    private static CodeSymbolRecord method(String fqnSuffix, String name, String role) {
        return symbol(fqnSuffix, name, "method", role, name + "()");
    }

    private static CodeSymbolRecord symbol(String fqnSuffix, String name, String kind, String role, String signature) {
        return new CodeSymbolRecord(id("symbol|" + fqnSuffix), PROJECT, id("file|" + fqnSuffix), kind, name,
                "com.example." + fqnSuffix, signature, role, 10, 20, "hash", RUN_ID, Map.of());
    }

    private static CodeEdgeRecord edge(CodeSymbolRecord source, String edgeType, String targetRef) {
        return new CodeEdgeRecord(id("edge|" + source.name() + "|" + edgeType + "|" + targetRef), PROJECT,
                source.id(), null, targetRef, edgeType, "syntactic", 0.8, RUN_ID,
                Map.of("filePath", "src/main/java/" + source.name() + ".java"));
    }

    private static UUID id(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
    }
}
