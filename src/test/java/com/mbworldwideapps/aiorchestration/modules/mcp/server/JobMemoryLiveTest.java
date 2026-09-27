package com.mbworldwideapps.aiorchestration.modules.mcp.server;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.mbworldwideapps.aiorchestration.config.JobMemoryProperties;
import com.mbworldwideapps.aiorchestration.modules.scanner.ScannerPayloadRedactor;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import io.qdrant.client.grpc.Points;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.model.ollama.autoconfigure.OllamaConnectionProperties;

@EnabledIfEnvironmentVariable(named = "JOB_MEMORY_LIVE_TEST", matches = "true")
class JobMemoryLiveTest {
    @Test void realTurkishCluesFindJobsThenExactGetReturnsFullUpdatedHandoff() throws Exception {
        var redactor = mock(ScannerPayloadRedactor.class);
        when(redactor.redact(anyString())).thenAnswer(i -> i.getArgument(0));
        var audit = mock(McpAuditLogger.class);
        var properties = new JobMemoryProperties(null, null, null);
        var connection = new OllamaConnectionProperties();
        connection.setBaseUrl("http://localhost:11434");
        String[][] fixtures = {
                {"DOĞRULAMA: Tracking Redis gecikmesi", "Mobil backend tracking listesinde Redis GET ve SET beklemesi, Dynatrace incelemesi, büyük cache verisi ve JVM suspension araştırması.", "Kampanya takibindeki önbellek okumalarının uzamasını araştırdığımız iş"},
                {"DOĞRULAMA: Android mağaza yayını", "Kelime oyununun Google Play sürümü, Fastlane imzalama, mağaza açıklamaları ve ekran görüntüleri yayın hazırlığı.", "Oyunu Play Store'a gönderirken imza ve mağaza görselleriyle uğraştığımız çalışma"},
                {"DOĞRULAMA: Project resolve dizin kontrolü", "AI Orchestration projectKey sabit kalırken Git çalışma dizini taşınması, path allowlist kaldırılması ve otomatik scan talimatlarının kapatılması.", "Proje yolunu değiştirince reddeden kontrolü ve kendiliğinden taramayı kaldırdığımız iş"}
        };
        List<String> ids = new ArrayList<>();
        try (var client = new QdrantClient(QdrantGrpcClient.newBuilder("localhost", 6334, false).build())) {
            try {
                McpClientContextHolder.set(new McpClientContext("A", "codex", "local", List.of("memory.write", "memory.read")));
                var writer = new JobMemoryMcpTool(client, connection, redactor, audit, properties);
                for (String[] f : fixtures) ids.add(writer.save(f[0], f[1], "Tam devir notu\n" + "ayrıntı\n".repeat(2000), null).jobId());
                var reader = new JobMemoryMcpTool(client, connection, redactor, audit, properties);
                for (int i = 0; i < fixtures.length; i++) {
                    var matches = reader.search(fixtures[i][2], 3);
                    String expectedId = ids.get(i);
                    assertThat(matches).isNotEmpty();
                    assertThat(matches.getFirst().jobId()).isEqualTo(expectedId);
                    assertThat(reader.get(expectedId).content()).isEqualTo("Tam devir notu\n" + "ayrıntı\n".repeat(2000));
                    System.out.println("JOB_RECALL_SMOKE query=" + i + " expectedRank="
                            + (java.util.stream.IntStream.range(0, matches.size()).filter(n -> matches.get(n).jobId().equals(expectedId)).findFirst().orElse(-1) + 1)
                            + " scores=" + matches.stream().map(JobMemoryMcpTool.Match::score).toList());
                }
                writer.save(fixtures[0][0], fixtures[0][1], "Güncel sonuç ve sonraki adım", ids.getFirst());
                assertThat(reader.get(ids.getFirst()).content()).isEqualTo("Güncel sonuç ve sonraki adım");
                assertThat(reader.get(ids.get(1)).content()).startsWith("Tam devir notu");
                System.out.println("JOB_EXACT_GET_AND_UPDATE_PASS");
            } finally {
                if (!ids.isEmpty()) client.deleteAsync(properties.collectionName(), ids.stream()
                        .map(id -> Points.PointId.newBuilder().setUuid(id).build()).toList()).get();
                McpClientContextHolder.clear();
            }
        }
    }
}
