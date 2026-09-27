package com.mbworldwideapps.aiorchestration.modules.mcp.server;

import com.mbworldwideapps.aiorchestration.config.JobMemoryProperties;
import com.mbworldwideapps.aiorchestration.modules.scanner.ScannerPayloadRedactor;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Points;
import io.qdrant.client.grpc.Collections;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.ai.document.Document;
import org.springframework.ai.model.ollama.autoconfigure.OllamaConnectionProperties;
import org.springframework.ai.ollama.OllamaEmbeddingModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaEmbeddingOptions;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.qdrant.QdrantVectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;

@Component
@ConditionalOnProperty(prefix = "ai-orchestration.mcp", name = "enabled", havingValue = "true", matchIfMissing = true)
public class JobMemoryMcpTool {
    private final JobMemoryProperties properties;
    private final VectorStore store;
    private final QdrantClient client;
    private final ScannerPayloadRedactor redactor;
    private final McpAuditLogger audit;

    @Autowired
    public JobMemoryMcpTool(QdrantClient client, OllamaConnectionProperties connection,
            ScannerPayloadRedactor redactor, McpAuditLogger audit, JobMemoryProperties properties) throws Exception {
        this.properties = properties;
        this.client = client;
        this.redactor = redactor;
        this.audit = audit;
        var embedding = OllamaEmbeddingModel.builder()
                .ollamaApi(OllamaApi.builder().baseUrl(connection.getBaseUrl()).build())
                .defaultOptions(OllamaEmbeddingOptions.builder().model(properties.model())
                        .dimensions(properties.dimensions()).build()).build();
        var vectors = QdrantVectorStore.builder(client, embedding)
                .collectionName(properties.collectionName()).initializeSchema(true).build();
        vectors.afterPropertiesSet();
        ensureTextIndexes(client, properties.collectionName());
        this.store = vectors;
    }

    public static void ensureTextIndexes(QdrantClient client, String collection) throws Exception {
        for (String field : List.of("title", "summary")) {
            client.createPayloadIndexAsync(collection, field,
                    Collections.PayloadSchemaType.Text,
                    Collections.PayloadIndexParams.newBuilder().setTextIndexParams(
                            Collections.TextIndexParams.newBuilder()
                                    .setTokenizer(Collections.TokenizerType.Prefix)
                                    .setLowercase(true).setMinTokenLen(1).setMaxTokenLen(256)).build(),
                    true, null, null).get(30, TimeUnit.SECONDS);
        }
    }

    JobMemoryMcpTool(VectorStore store, QdrantClient client, ScannerPayloadRedactor redactor, McpAuditLogger audit) {
        this.properties = new JobMemoryProperties(null, null, null);
        this.store = store;
        this.client = client;
        this.redactor = redactor;
        this.audit = audit;
    }

    public record Saved(boolean saved, String jobId, String title, String summary, String updatedAt, boolean redacted) {}
    public record Match(String jobId, String title, String summary, Double score, String updatedAt) {}
    public record Checkpoint(boolean found, String jobId, String title, String summary, String content, String updatedAt) {}

    @McpTool(name = "job_memory.save", description = "Save a named job for later (only when the user asks to save this job).")
    public Saved save(
            @McpToolParam(description = "Short job title; max512 UTF-8 bytes") String title,
            @McpToolParam(description = "Searchable synopsis with project names, topic, decisions and current stage; max8KiB UTF-8. Only title and summary are embedded.") String summary,
            @McpToolParam(description = "Complete standalone handoff: goal, repositories, artifacts, verified outcomes, pending work and actual approval provenance; max256KiB UTF-8") String content,
            @McpToolParam(description = "Optional canonical UUID: replace if present in storage, create with that id if absent; omit to generate a new UUID", required = false) String jobId) {
        var context = McpClientContextHolder.require();
        var started = Instant.now();
        authorize(context, "memory.write", "job_memory.save", started);
        try {
            String id = jobId == null ? UUID.randomUUID().toString() : id(jobId);
            String cleanTitle = clean(title, 512, "title");
            String cleanSummary = clean(summary, 8192, "summary");
            String cleanContent = clean(content, 262144, "content");
            String updatedAt = Instant.now().toString();
            // One point upsert keeps searchable text and full handoff in the same snapshot.
            store.add(List.of(new Document(id, cleanTitle + "\n" + cleanSummary,
                    Map.of("title", cleanTitle, "summary", cleanSummary, "content", cleanContent,
                            "updatedAt", updatedAt))));
            audit.log(context, "job_memory.save", null, 1, started, "success");
            return new Saved(true, id, cleanTitle, cleanSummary, updatedAt,
                    !title.equals(cleanTitle) || !summary.equals(cleanSummary) || !content.equals(cleanContent));
        } catch (IllegalArgumentException e) {
            audit.log(context, "job_memory.save", null, 0, started, "invalid");
            throw e;
        } catch (RuntimeException e) {
            audit.log(context, "job_memory.save", null, 0, started, "error");
            throw new IllegalStateException("Job memory save failed");
        }
    }

    @McpTool(name = "job_memory.search", description = "Find saved jobs by what the user remembers about them.")
    public List<Match> search(
            @McpToolParam(description = "User's job recall clue, including topic/project if known; max8KiB UTF-8") String query,
            @McpToolParam(description = "Maximum candidates, 1..10; default3", required = false) Integer topK) {
        var context = McpClientContextHolder.require();
        var started = Instant.now();
        authorize(context, "memory.read", "job_memory.search", started);
        try {
            int limit = topK == null ? 3 : topK;
            if (limit < 1 || limit > 10) throw new IllegalArgumentException("topK must be 1..10");
            var documents = store.similaritySearch(SearchRequest.builder().query(clean(query, 8192, "query"))
                    .topK(limit).similarityThreshold(properties.similarityThreshold()).build());
            var matches = documents.stream().limit(limit).map(d -> new Match(d.getId(),
                    text(d.getMetadata(), "title", 512), text(d.getMetadata(), "summary", 8192),
                    d.getScore(), text(d.getMetadata(), "updatedAt", 128))).toList();
            audit.log(context, "job_memory.search", null, matches.size(), started, "success");
            return matches;
        } catch (IllegalArgumentException e) {
            audit.log(context, "job_memory.search", null, 0, started, "invalid");
            throw e;
        } catch (RuntimeException e) {
            audit.log(context, "job_memory.search", null, 0, started, "error");
            throw new IllegalStateException("Job memory search failed");
        }
    }

    @McpTool(name = "job_memory.get", description = "Read one saved job by id.")
    public Checkpoint get(@McpToolParam(description = "Exact job UUID returned by save or search") String jobId) {
        var context = McpClientContextHolder.require();
        var started = Instant.now();
        authorize(context, "memory.read", "job_memory.get", started);
        try {
            String key = id(jobId);
            var points = client.retrieveAsync(properties.collectionName(),
                    Points.PointId.newBuilder().setUuid(key).build(), true, false, null).get(15, TimeUnit.SECONDS);
            Checkpoint result;
            if (points.isEmpty()) {
                result = new Checkpoint(false, key, null, null, null, null);
            } else {
                var fields = points.getFirst().getPayloadMap();
                result = new Checkpoint(true, key, field(fields, "title", 512), field(fields, "summary", 8192),
                        field(fields, "content", 262144), field(fields, "updatedAt", 128));
            }
            audit.log(context, "job_memory.get", null, result.found() ? 1 : 0, started, "success");
            return result;
        } catch (IllegalArgumentException e) {
            audit.log(context, "job_memory.get", null, 0, started, "invalid");
            throw e;
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            audit.log(context, "job_memory.get", null, 0, started, "error");
            throw new IllegalStateException("Job memory read failed");
        }
    }

    private String clean(String value, int max, String field) {
        validate(value, max, field);
        String cleaned = redactor.redact(value);
        validate(cleaned, max, field);
        return cleaned;
    }

    private static void validate(String value, int max, String field) {
        if (value == null || value.isBlank() || value.getBytes(StandardCharsets.UTF_8).length > max) {
            throw new IllegalArgumentException(field + " must be nonblank and at most " + max + " bytes UTF-8");
        }
    }

    private static String id(String value) {
        if (value == null || !value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {
            throw new IllegalArgumentException("jobId must be a canonical UUID");
        }
        return UUID.fromString(value).toString();
    }

    private static String text(Map<String, Object> fields, String key, int max) {
        Object value = fields.get(key);
        if (!(value instanceof String string)) throw new IllegalStateException("Invalid job record");
        validate(string, max, key);
        return string;
    }

    private static String field(Map<String, io.qdrant.client.grpc.JsonWithInt.Value> fields, String key, int max) {
        var value = fields.get(key);
        if (value == null || !value.hasStringValue()) throw new IllegalStateException("Invalid job record");
        validate(value.getStringValue(), max, key);
        return value.getStringValue();
    }

    private void authorize(McpClientContext context, String scope, String tool, Instant started) {
        if (!McpProjectKeys.isLocalTrust(context) || !context.hasScope(scope)) {
            audit.log(context, tool, null, 0, started, "denied_scope");
            throw new McpAccessException("Job memory requires local-trust access and " + scope);
        }
    }
}
