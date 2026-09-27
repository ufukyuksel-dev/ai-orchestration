package com.mbworldwideapps.aiorchestration.modules.scanner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CodeFlowContextAssemblerTest {

    private static final String PROJECT = "MOBILEAPP";
    private static final UUID RUN_ID = id("run");

    @TempDir
    Path tempDir;

    @Test
    void assemblesBoundedFlowContextFromEndpointSeed() throws Exception {
        Path sourceRoot = tempDir.resolve("repo");
        Files.createDirectories(sourceRoot.resolve("src/main/java/com/example"));
        Files.writeString(sourceRoot.resolve("src/main/java/com/example/CampaignController.java"), """
                package com.example;
                class CampaignController {
                  CampaignResponse getCampaigns(CustomerCampaignRequest request) {
                    return campaignService.getCampaigns(request);
                  }
                }
                """);
        Files.writeString(sourceRoot.resolve("src/main/java/com/example/CampaignService.java"), """
                package com.example;
                class CampaignService {
                  CampaignResponse getCampaigns(CustomerCampaignRequest request) {
                    var cached = cache.get(CUSTOMER_CAMPAIGN_CACHE);
                    return client.loadCampaigns(request);
                  }
                }
                """);
        CodeBaselineRepository repository = mock(CodeBaselineRepository.class);
        CodeSymbolRecord controllerMethod = method("CampaignController#getCampaigns", "getCampaigns",
                "controller", id("file|controller"), "CampaignResponse", "getCampaigns(CustomerCampaignRequest)");
        CodeSymbolRecord serviceClass = type("CampaignService", "CampaignService", "service", id("file|service"));
        CodeSymbolRecord serviceMethod = method("CampaignService#getCampaigns", "getCampaigns", "method",
                id("file|service"), "CampaignResponse", "getCampaigns(CustomerCampaignRequest)");
        CodeSymbolRecord cacheClass = type("RedisCampaignCacheService", "RedisCampaignCacheService", "service",
                id("file|cache"));
        CodeSymbolRecord clientClass = type("CampaignServiceClient", "CampaignServiceClient", "interface",
                id("file|client"));
        CodeSymbolRecord repositoryClass = type("CampaignRepository", "CampaignRepository", "repository",
                id("file|repository"));
        when(repository.findSymbolById(eq(controllerMethod.id()))).thenReturn(Optional.of(controllerMethod));
        when(repository.findSymbolById(eq(serviceClass.id()))).thenReturn(Optional.of(serviceClass));
        when(repository.findSymbolById(eq(serviceMethod.id()))).thenReturn(Optional.of(serviceMethod));
        when(repository.findSymbolById(eq(cacheClass.id()))).thenReturn(Optional.of(cacheClass));
        when(repository.findSymbolById(eq(clientClass.id()))).thenReturn(Optional.of(clientClass));
        when(repository.findSymbolById(eq(repositoryClass.id()))).thenReturn(Optional.of(repositoryClass));
        when(repository.findFileById(eq(PROJECT), eq(controllerMethod.fileId())))
                .thenReturn(Optional.of(file(controllerMethod.fileId(), "src/main/java/com/example/CampaignController.java")));
        when(repository.findFileById(eq(PROJECT), eq(serviceClass.fileId())))
                .thenReturn(Optional.of(file(serviceClass.fileId(), "src/main/java/com/example/CampaignService.java")));
        when(repository.findFileById(eq(PROJECT), eq(serviceMethod.fileId())))
                .thenReturn(Optional.of(file(serviceMethod.fileId(), "src/main/java/com/example/CampaignService.java")));
        when(repository.findFileById(eq(PROJECT), eq(cacheClass.fileId())))
                .thenReturn(Optional.of(file(cacheClass.fileId(), "src/main/java/com/example/CampaignService.java")));
        when(repository.findFileById(eq(PROJECT), eq(clientClass.fileId())))
                .thenReturn(Optional.of(file(clientClass.fileId(), "src/main/java/com/example/CampaignService.java")));
        when(repository.findFileById(eq(PROJECT), eq(repositoryClass.fileId())))
                .thenReturn(Optional.of(file(repositoryClass.fileId(), "src/main/java/com/example/CampaignService.java")));
        when(repository.findEdgesFrom(eq(PROJECT), eq(controllerMethod.id()), anySet(), anyInt()))
                .thenReturn(List.of(
                        edge(controllerMethod, serviceClass.id(), "INJECTS", "CampaignService"),
                        edge(controllerMethod, serviceMethod.id(), "CALLS", "getCampaigns"),
                        edge(controllerMethod, null, "EXPOSES_ENDPOINT", "GET /api/campaigns")));
        when(repository.findEdgesFrom(eq(PROJECT), eq(serviceClass.id()), anySet(), anyInt()))
                .thenReturn(List.of());
        when(repository.findEdgesFrom(eq(PROJECT), eq(serviceMethod.id()), anySet(), anyInt()))
                .thenReturn(List.of(
                        edge(serviceMethod, cacheClass.id(), "INJECTS", "RedisCampaignCacheService"),
                        edge(serviceMethod, clientClass.id(), "INJECTS", "CampaignServiceClient"),
                        edge(serviceMethod, repositoryClass.id(), "INJECTS", "CampaignRepository"),
                        edge(serviceMethod, null, "CALLS", "CUSTOMER_CAMPAIGN_CACHE"),
                        edge(serviceMethod, null, "CALLS", "CampaignResponse.from")));
        when(repository.findEdgesFrom(eq(PROJECT), eq(cacheClass.id()), anySet(), anyInt()))
                .thenReturn(List.of(edge(cacheClass, null, "INJECTS", "RedisTemplate")));
        when(repository.findEdgesFrom(eq(PROJECT), eq(clientClass.id()), anySet(), anyInt()))
                .thenReturn(List.of(edge(clientClass, null, "ANNOTATED_WITH", "FeignClient")));
        when(repository.findEdgesFrom(eq(PROJECT), eq(repositoryClass.id()), anySet(), anyInt()))
                .thenReturn(List.of());
        CodeFlowSeed seed = new CodeFlowSeed(PROJECT, RUN_ID, controllerMethod.id(),
                CodeFlowSeedBuilder.ENDPOINT_FLOW, "http_endpoint", "GET /api/campaigns -> getCampaigns",
                controllerMethod.fqn(), "src/main/java/com/example/CampaignController.java", "GET /api/campaigns",
                List.of("GetMapping"), List.of("CampaignService"), List.of("getCampaigns"), 100.0, Map.of());

        CodeFlowContext context = new CodeFlowContextAssembler(repository)
                .assemble(seed, sourceRoot.toString(), 2, 8, 40, 4);

        assertThat(context.symbols()).extracting(CodeFlowContextSymbol::name)
                .contains("getCampaigns", "CampaignService", "RedisCampaignCacheService", "CampaignServiceClient",
                        "CampaignRepository");
        assertThat(context.services()).anyMatch(value -> value.endsWith("CampaignService"));
        assertThat(context.externalClients()).anyMatch(value -> value.endsWith("CampaignServiceClient"));
        assertThat(context.repositories()).anyMatch(value -> value.endsWith("CampaignRepository"));
        assertThat(context.cacheKeys()).contains("CUSTOMER_CAMPAIGN_CACHE");
        assertThat(context.requestModels()).contains("CustomerCampaignRequest");
        assertThat(context.responseModels()).contains("CampaignResponse");
        assertThat(context.snippets()).isNotEmpty();
        assertThat(context.snippets().getFirst().text()).contains("campaignService.getCampaigns");
        assertThat(context.truncated()).isFalse();
    }

    @Test
    void deduplicatesControllerClassSeedWhenHandlerMethodSeedExists() {
        CodeFlowContextAssembler assembler = new CodeFlowContextAssembler(mock(CodeBaselineRepository.class));
        CodeFlowSeed classSeed = seed(id("class"), "endpoint_flow", "controller",
                "com.example.CampaignController", "ANY /api");
        CodeFlowSeed methodSeed = seed(id("method"), "endpoint_flow", "http_endpoint",
                "com.example.CampaignController#getCampaigns", "GET /api/campaigns");
        CodeFlowSeed clientSeed = seed(id("client"), "integration_flow", "feign_client",
                "com.example.CampaignClient", "");

        assertThat(assembler.deduplicateEndpointSeeds(List.of(classSeed, methodSeed, clientSeed)))
                .extracting(CodeFlowSeed::entryRef)
                .containsExactly(methodSeed.entryRef(), clientSeed.entryRef());
    }

    private static CodeFlowSeed seed(UUID symbolId, String capsuleKind, String triggerKind, String entryRef,
            String endpoint) {
        return new CodeFlowSeed(PROJECT, RUN_ID, symbolId, capsuleKind, triggerKind, entryRef, entryRef, "", endpoint,
                List.of(), List.of(), List.of(), 1.0, Map.of());
    }

    private static CodeSymbolRecord type(String fqnSuffix, String name, String role, UUID fileId) {
        return symbol(fqnSuffix, name, "class", role, fileId, "", "");
    }

    private static CodeSymbolRecord method(String fqnSuffix, String name, String role, UUID fileId, String returnType,
            String signature) {
        return symbol(fqnSuffix, name, "method", role, fileId, signature, returnType);
    }

    private static CodeSymbolRecord symbol(String fqnSuffix, String name, String kind, String role, UUID fileId,
            String signature, String returnType) {
        return new CodeSymbolRecord(id("symbol|" + fqnSuffix), PROJECT, fileId, kind, name,
                "com.example." + fqnSuffix, signature, role, 2, 6, "hash", RUN_ID,
                returnType.isBlank() ? Map.of() : Map.of("returnType", returnType, "annotations", List.of()));
    }

    private static CodeEdgeRecord edge(CodeSymbolRecord source, UUID targetSymbolId, String edgeType, String targetRef) {
        return new CodeEdgeRecord(id("edge|" + source.name() + "|" + edgeType + "|" + targetRef), PROJECT,
                source.id(), targetSymbolId, targetRef, edgeType, "syntactic", 0.8, RUN_ID,
                Map.of("filePath", "src/main/java/com/example/" + source.name() + ".java"));
    }

    private static CodeFileRecord file(UUID fileId, String filePath) {
        return new CodeFileRecord(fileId, PROJECT, filePath, "hash", "java", RUN_ID, Map.of());
    }

    private static UUID id(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
    }
}
