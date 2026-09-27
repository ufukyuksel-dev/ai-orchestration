package com.mbworldwideapps.aiorchestration.modules.mcp.server;

import com.mbworldwideapps.aiorchestration.modules.scanner.ScannerPayloadRedactor;
import com.mbworldwideapps.aiorchestration.config.PersonalMemoryProperties;
import io.qdrant.client.QdrantClient;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
public class PersonalMemoryMcpTool {
    private final PersonalMemoryProperties properties;
    private final VectorStore store;
    private final ScannerPayloadRedactor redactor;
    private final McpAuditLogger audit;

    @Autowired
    public PersonalMemoryMcpTool(QdrantClient client, OllamaConnectionProperties connection,
            ScannerPayloadRedactor redactor, McpAuditLogger audit, PersonalMemoryProperties properties) throws Exception {
        this.properties = properties;
        // Fixed semantic model/collection: never mix hashing vectors with semantic vectors.
        var embedding = OllamaEmbeddingModel.builder()
                .ollamaApi(OllamaApi.builder().baseUrl(connection.getBaseUrl()).build())
                .defaultOptions(OllamaEmbeddingOptions.builder().model(properties.model()).dimensions(properties.dimensions()).build())
                .build();
        var vectors = QdrantVectorStore.builder(client, embedding)
                .collectionName(properties.collectionName()).initializeSchema(true).build();
        vectors.afterPropertiesSet();
        this.store = vectors;
        this.redactor = redactor;
        this.audit = audit;
    }

    PersonalMemoryMcpTool(VectorStore store, ScannerPayloadRedactor redactor, McpAuditLogger audit) {
        this.properties = new PersonalMemoryProperties(null,null,null,null);
        this.store = store;
        this.redactor = redactor;
        this.audit = audit;
    }

    public record Saved(String id, String content, String savedAt) {}
    public record Match(String id, String content, Double score, Object savedAt) {}

    @McpTool(name = "personal_memory.save", description = "Save a personal, project-independent fact only when the user explicitly asks you to remember it.")
    public Saved save(@McpToolParam(description = "Explicitly requested fact to remember, within the configured UTF-8 size limit (default 8KiB). No credentials.") String content) {
        var context = McpClientContextHolder.require();
        var started = Instant.now();
        authorize(context, "memory.write", "personal_memory.save", started);
        validate(content, 3, context, "personal_memory.save", started);
        try {
            String text = clean(content);
            String id = UUID.randomUUID().toString();
            String savedAt = Instant.now().toString();
            store.add(List.of(new Document(id, text, Map.of("savedAt", savedAt))));
            audit.log(context, "personal_memory.save", null, 1, started, "success");
            return new Saved(id, text, savedAt);
        } catch (RuntimeException e) {
            audit.log(context, "personal_memory.save", null, 0, started, "error");
            throw new IllegalStateException("Personal memory save failed");
        }
    }

    @McpTool(name = "personal_memory.search", description = "Search the user's personal facts (only when the user asks about their own preferences).")
    public List<Match> search(@McpToolParam(description = "The user's recall question, within the configured UTF-8 size limit (default 8KiB)") String query,
            @McpToolParam(description = "Maximum matches, 1 to 10", required = false) Integer topK) {
        var context = McpClientContextHolder.require();
        var started = Instant.now();
        authorize(context, "memory.read", "personal_memory.search", started);
        int limit = topK == null ? 3 : topK;
        validate(query, limit, context, "personal_memory.search", started);
        try {
            var results = store.similaritySearch(SearchRequest.builder().query(clean(query))
                    .topK(limit).similarityThreshold(properties.similarityThreshold()).build());
            var matches = results.stream().map(d -> new Match(d.getId(), d.getText(), d.getScore(),
                    d.getMetadata().get("savedAt"))).toList();
            audit.log(context, "personal_memory.search", null, matches.size(), started, "success");
            return matches;
        } catch (RuntimeException e) {
            audit.log(context, "personal_memory.search", null, 0, started, "error");
            throw new IllegalStateException("Personal memory search failed");
        }
    }

    private void validate(String value, int topK, McpClientContext context, String tool, Instant started) {
        String error = value == null || value.isBlank() ? "Text must be nonblank"
                : value.getBytes(StandardCharsets.UTF_8).length > properties.maxContentBytes()
                ? "Text must be at most " + properties.maxContentBytes() + " bytes UTF-8"
                : topK < 1 || topK > 10 ? "topK must be 1..10" : null;
        if (error != null) {
            audit.log(context, tool, null, 0, started, "invalid");
            throw new IllegalArgumentException(error);
        }
    }

    private String clean(String value) {
        String text = redactor.redact(value);
        if (text.isBlank()) throw new IllegalArgumentException("No text remains after redaction");
        return text;
    }

    private void authorize(McpClientContext context, String scope, String tool, Instant started) {
        if (!McpProjectKeys.isLocalTrust(context) || !context.hasScope(scope)) {
            audit.log(context, tool, null, 0, started, "denied_scope");
            throw new McpAccessException("Personal memory requires local-trust access and " + scope);
        }
    }
}
