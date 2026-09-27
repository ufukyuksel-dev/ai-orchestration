package com.mbworldwideapps.aiorchestration.modules.workspace;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mbworldwideapps.aiorchestration.config.JobMemoryProperties;
import com.mbworldwideapps.aiorchestration.modules.mcp.server.JobMemoryMcpTool;
import com.mbworldwideapps.aiorchestration.modules.memoryai.RuleMemoryLinkLookup;

import io.qdrant.client.*;
import io.qdrant.client.grpc.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.*;

@Testcontainers
class WorkspaceSavedSearchTest {
    @Container
    static GenericContainer<?> db =
            new GenericContainer<>("qdrant/qdrant:v1.13.4").withExposedPorts(6334);

    @Test
    @SuppressWarnings("unchecked")
    void existingAndNewJobsMatchCaseInsensitivelyWithPrefixesAndStablePages() throws Exception {
        var properties = new JobMemoryProperties("workspace-test", 2, 0.0);
        try (var client =
                new QdrantClient(
                        QdrantGrpcClient.newBuilder(db.getHost(), db.getMappedPort(6334), false)
                                .build())) {
            String collection = properties.collectionName();
            client.createCollectionAsync(
                            collection,
                            io.qdrant.client.grpc.Collections.VectorParams.newBuilder()
                                    .setSize(2)
                                    .setDistance(io.qdrant.client.grpc.Collections.Distance.Cosine)
                                    .build())
                    .get();
            client.upsertAsync(
                            collection,
                            List.of(
                                    point(
                                            "00000000-0000-0000-0000-000000000001",
                                            "Redis incident",
                                            "Cache latency")))
                    .get();
            JobMemoryMcpTool.ensureTextIndexes(client, collection);
            JobMemoryMcpTool.ensureTextIndexes(client, collection); // startup is repeatable
            client.upsertAsync(
                            collection,
                            List.of(
                                    point(
                                            "00000000-0000-0000-0000-000000000002",
                                            "Tracking",
                                            "REDIS gecikmesi")))
                    .get();
            ObjectProvider<QdrantClient> provider = mock(ObjectProvider.class);
            when(provider.getObject()).thenReturn(client);
            var service =
                    new WorkspaceReadService(
                            mock(JdbcTemplate.class),
                            new ObjectMapper(),
                            mock(RuleMemoryLinkLookup.class),
                            provider,
                            properties);
            var expected = service.list("saved", null, "Redis", null, null, 20).items();
            assertThat(expected).hasSize(2);
            for (String query : List.of("redis", "REDIS", "red")) {
                assertThat(service.list("saved", null, query, null, null, 20).items())
                        .isEqualTo(expected);
                var first = service.list("saved", null, query, null, null, 1);
                var second = service.list("saved", null, query, null, first.nextCursor(), 1);
                assertThat(first.items()).containsExactly(expected.get(0));
                assertThat(second.items()).containsExactly(expected.get(1));
            }
            assertThat(service.list("saved", null, "gecikm", null, null, 20).items()).hasSize(1);
            assertThat(service.list("saved", null, "unmatched", null, null, 20).items()).isEmpty();
        }
    }

    private static Points.PointStruct point(String id, String title, String summary) {
        return Points.PointStruct.newBuilder()
                .setId(Points.PointId.newBuilder().setUuid(id))
                .setVectors(
                        Points.Vectors.newBuilder()
                                .setVector(Points.Vector.newBuilder().addData(1).addData(0)))
                .putPayload("title", JsonWithInt.Value.newBuilder().setStringValue(title).build())
                .putPayload(
                        "summary", JsonWithInt.Value.newBuilder().setStringValue(summary).build())
                .putPayload(
                        "updatedAt",
                        JsonWithInt.Value.newBuilder()
                                .setStringValue("2026-09-10T00:00:00Z")
                                .build())
                .build();
    }
}
