package com.mbworldwideapps.aiorchestration.modules.scanner;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.qdrant.client.QdrantClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.ai.vectorstore.qdrant.QdrantVectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "ai-orchestration.scanner.vector-index", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public class QdrantCodeBaselineVectorIndex implements CodeBaselineVectorIndex {

    private static final Logger log = LoggerFactory.getLogger(QdrantCodeBaselineVectorIndex.class);

    private final QdrantVectorStore vectorStore;
    private final boolean available;

    public QdrantCodeBaselineVectorIndex(QdrantClient qdrantClient, EmbeddingModel embeddingModel,
            ScannerProperties properties) {
        QdrantVectorStore candidate = QdrantVectorStore.builder(qdrantClient, embeddingModel)
                .collectionName(properties.baselineCollectionName())
                .initializeSchema(true)
                .build();
        boolean initialized = false;
        try {
            candidate.afterPropertiesSet();
            initialized = true;
        } catch (Exception e) {
            log.warn("Code baseline vector index unavailable; scans will keep Postgres capsules and write diagnostics",
                    e);
        }
        this.vectorStore = initialized ? candidate : null;
        this.available = initialized;
    }

    @Override
    public void upsert(CodeSemanticCapsuleRecord capsule) {
        if (!available) {
            throw new IllegalStateException("Code baseline vector index is unavailable");
        }
        vectorStore.add(List.of(new Document(capsule.id().toString(), text(capsule), metadata(capsule))));
    }

    @Override
    public void deleteAll(List<UUID> capsuleIds) {
        if (!available || capsuleIds == null || capsuleIds.isEmpty()) {
            return;
        }
        List<String> ids = capsuleIds.stream().distinct().map(UUID::toString).toList();
        for (int from = 0; from < ids.size(); from += 256) {
            int to = Math.min(ids.size(), from + 256);
            vectorStore.delete(ids.subList(from, to));
        }
    }

    @Override
    public List<ScoredCodeCapsuleRef> search(String query, int topK, String projectKey) {
        if (!available || topK <= 0) {
            return List.of();
        }
        var filter = new FilterExpressionBuilder().eq("project_key", projectKey).build();
        List<Document> documents = vectorStore.similaritySearch(SearchRequest.builder()
                .query(query == null ? "" : query)
                .topK(Math.max(topK * 4, topK + 8))
                .similarityThreshold(0.0)
                .filterExpression(filter)
                .build());
        if (documents == null || documents.isEmpty()) {
            return List.of();
        }
        return documents.stream()
                .map(QdrantCodeBaselineVectorIndex::toRef)
                .filter(ref -> ref != null)
                .limit(topK)
                .toList();
    }

    private static ScoredCodeCapsuleRef toRef(Document document) {
        Object capsuleId = document.getMetadata().get("capsule_id");
        if (capsuleId == null) {
            return null;
        }
        try {
            double score = document.getScore() == null ? 0.0 : document.getScore();
            return new ScoredCodeCapsuleRef(UUID.fromString(String.valueOf(capsuleId)), score);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String text(CodeSemanticCapsuleRecord capsule) {
        return (capsule.summary() == null ? "" : capsule.summary()) + "\n\n"
                + (capsule.text() == null ? "" : capsule.text());
    }

    private static Map<String, Object> metadata(CodeSemanticCapsuleRecord capsule) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("capsule_id", capsule.id().toString());
        metadata.put("project_key", capsule.projectKey());
        metadata.put("capsule_kind", capsule.capsuleKind());
        metadata.put("provider", capsule.provider());
        metadata.put("semantic_model", capsule.semanticModel());
        metadata.put("prompt_version", capsule.promptVersion());
        metadata.put("data_egress", capsule.dataEgress());
        metadata.put("confidence", capsule.confidence());
        metadata.put("indexed_at", Instant.now().toString());
        if (capsule.symbolId() != null) {
            metadata.put("symbol_id", capsule.symbolId().toString());
        }
        if (capsule.fileId() != null) {
            metadata.put("file_id", capsule.fileId().toString());
        }
        Object filePath = capsule.evidence().get("filePath");
        if (filePath != null) {
            metadata.put("file_path", String.valueOf(filePath));
        }
        return metadata;
    }
}
