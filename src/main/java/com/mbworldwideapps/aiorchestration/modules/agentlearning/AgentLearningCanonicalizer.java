package com.mbworldwideapps.aiorchestration.modules.agentlearning;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.mbworldwideapps.aiorchestration.modules.agentlearning.AgentLearningModels.Candidate;
import com.mbworldwideapps.aiorchestration.modules.agentlearning.AgentLearningModels.Observation;
import com.mbworldwideapps.aiorchestration.modules.agentlearning.AgentLearningModels.Target;

/**
 * Produces the three independent identities used by the learning pipeline.
 * Random observation IDs, insertion order and current source revisions never
 * participate in semantic identity.
 */
public final class AgentLearningCanonicalizer {

    private final ObjectMapper mapper;

    public AgentLearningCanonicalizer(ObjectMapper objectMapper) {
        this.mapper = objectMapper.copy()
                .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    }

    public Identity identity(Candidate candidate, List<Target> targets, List<Observation> evidence) {
        Map<String, Target> targetsByRef = targets.stream()
                .collect(java.util.stream.Collectors.toMap(Target::targetId, value -> value));

        List<Map<String, String>> anchors = candidate.anchors().stream()
                .map(anchor -> {
                    Target target = targetsByRef.get(anchor.targetId());
                    if (target == null) throw new IllegalArgumentException("ANCHOR_SCOPE_DENIED");
                    Map<String, String> value = new LinkedHashMap<>();
                    value.put("kind", normalizeNullable(target.locatorKind()));
                    value.put("path", target.relativePath());
                    value.put("symbol", normalizeNullable(target.symbolRef()));
                    value.put("role", anchor.role().trim().toLowerCase(Locale.ROOT));
                    return value;
                })
                .distinct()
                .sorted(Comparator.comparing(value -> canonical(value)))
                .toList();

        Map<String, Object> semantic = new LinkedHashMap<>();
        semantic.put("contract", "agent-learning/semantic-v2");
        semantic.put("kind", normalize(candidate.kind()));
        semantic.put("summary", normalize(candidate.summary()));
        semantic.put("reusableFor", normalizedSet(candidate.reusableFor()));
        semantic.put("anchors", anchors);

        Map<String, Object> content = new LinkedHashMap<>(semantic);
        content.put("content", normalize(candidate.content()));
        content.put("appliesWhen", normalizedSet(candidate.appliesWhen()));
        content.put("limitations", normalizedSet(candidate.limitations()));
        content.put("correction", candidate.correctionTargetId() == null ? "" : "correction");

        Map<String, Object> exactContent = new LinkedHashMap<>();
        exactContent.put("contract", "agent-learning/exact-content-v1");
        exactContent.put("kind", normalize(candidate.kind()));
        exactContent.put("content", normalize(candidate.content()));
        exactContent.put("anchors", anchors);

        List<Map<String, String>> durableEvidence = evidence.stream()
                .map(item -> {
                    Map<String, String> value = new LinkedHashMap<>();
                    value.put("resourceRef", normalize(item.resourceRef()));
                    value.put("sourceHash", item.rawContentHash());
                    value.put("provenance", normalize(item.provenance()));
                    return value;
                })
                .distinct()
                .sorted(Comparator.comparing(value -> canonical(value)))
                .toList();

        return new Identity(hash(canonical(semantic)), hash(canonical(content)), hash(canonical(durableEvidence)),
                hash(canonical(exactContent)));
    }

    private static List<String> normalizedSet(List<String> values) {
        return values.stream().map(AgentLearningCanonicalizer::normalize).distinct().sorted().toList();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.strip().replaceAll("\\s+", " ");
    }

    private static String normalizeNullable(String value) {
        return value == null ? "" : normalize(value);
    }

    private String canonical(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Unable to canonicalize learning", exception);
        }
    }

    private static String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    public record Identity(String semanticIdentity, String contentRevision, String evidenceRevision,
            String exactContentIdentity) {
    }
}
