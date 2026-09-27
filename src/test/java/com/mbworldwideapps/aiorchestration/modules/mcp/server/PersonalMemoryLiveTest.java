package com.mbworldwideapps.aiorchestration.modules.mcp.server;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.mbworldwideapps.aiorchestration.modules.scanner.ScannerPayloadRedactor;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import io.qdrant.client.grpc.Points;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.model.ollama.autoconfigure.OllamaConnectionProperties;

@EnabledIfEnvironmentVariable(named = "PERSONAL_MEMORY_LIVE_TEST", matches = "true")
class PersonalMemoryLiveTest {
    @Test void realSemanticRecallPersistsAcrossToolInstances() throws Exception {
        var redactor = mock(ScannerPayloadRedactor.class);
        when(redactor.redact(anyString())).thenAnswer(i -> i.getArgument(0));
        var audit = mock(McpAuditLogger.class);
        var properties = new com.mbworldwideapps.aiorchestration.config.PersonalMemoryProperties(null,null,null,null);
        var connection = new OllamaConnectionProperties();
        connection.setBaseUrl("http://localhost:11434");
        try (var client = new QdrantClient(QdrantGrpcClient.newBuilder("localhost",6334,false).build())) {
            String id = null;
            try {
                McpClientContextHolder.set(new McpClientContext("PROJECT_A","codex","local",List.of("memory.write")));
                var writer = new PersonalMemoryMcpTool(client,connection,redactor,audit,properties);
                id = writer.save("DOĞRULAMA KAYDI: MB Worldwide şirketinin resmi internet sitesi https://mbworldwideapps.com adresidir.").id();
                McpClientContextHolder.set(new McpClientContext("PROJECT_B","claude","local",List.of("memory.read")));
                var reader = new PersonalMemoryMcpTool(client,connection,redactor,audit,properties);
                var matches = reader.search("MB Worldwide işletmemizin web sayfasının adresini hatırlat",3);
                String savedId = id;
                assertThat(matches).anySatisfy(m -> {
                    assertThat(m.id()).isEqualTo(savedId);
                    assertThat(m.content()).contains("https://mbworldwideapps.com");
                    assertThat(m.score()).isGreaterThanOrEqualTo(0.5);
                });
                System.out.println("SEMANTIC_RECALL_PASS scores=" + matches.stream().map(PersonalMemoryMcpTool.Match::score).toList());
            } finally {
                if (id != null) client.deleteAsync(properties.collectionName(),List.of(Points.PointId.newBuilder().setUuid(id).build())).get();
                McpClientContextHolder.clear();
            }
        }
    }
}
