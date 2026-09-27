package com.mbworldwideapps.aiorchestration.modules.agentlearning;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mbworldwideapps.aiorchestration.modules.agentlearning.AgentLearningModels.LearningBatch;
import com.mbworldwideapps.aiorchestration.modules.agentlearning.AgentLearningModels.SemanticCandidate;
import org.springframework.stereotype.Component;

/** Internal semantic validator derived from the packaged automatic-capture schema bounds. */
@Component
public final class AgentLearningContract {

    public static final String RESOURCE = "contracts/agent-learning/learning-candidate.schema.json";
    private static final Pattern TARGET_REF = Pattern.compile("^t[1-9][0-9]*$");
    private static final Pattern KNOWLEDGE_REF = Pattern.compile("^k[1-9][0-9]*$");

    private final Set<String> kinds;
    private final int minLearnings;
    private final int maxLearnings;
    private final int maxAnchors;
    private final int maxEvidence;
    private final int maxShortList;
    private final int maxSummary;
    private final int maxContent;
    private final int maxReference;

    public AgentLearningContract(ObjectMapper mapper) {
        JsonNode schema = readSchema(mapper);
        JsonNode learning = schema.path("$defs").path("learning").path("properties");
        this.kinds = jsonStrings(learning.path("kind").path("enum"));
        JsonNode learnings = schema.path("properties").path("learningCandidates");
        this.minLearnings = learnings.path("minItems").asInt();
        this.maxLearnings = learnings.path("maxItems").asInt();
        this.maxAnchors = learning.path("locators").path("maxItems").asInt();
        this.maxEvidence = this.maxAnchors;
        this.maxShortList = schema.path("$defs").path("boundedList").path("maxItems").asInt();
        this.maxSummary = learning.path("summary").path("maxLength").asInt();
        this.maxContent = learning.path("content").path("maxLength").asInt();
        this.maxReference = 4000;
        if (kinds.isEmpty() || minLearnings < 0 || maxLearnings < minLearnings) {
            throw new IllegalStateException("Canonical learning schema is incomplete");
        }
    }

    public LearningBatch validate(LearningBatch batch) {
        if (batch == null || batch.learnings().size() < minLearnings || batch.learnings().size() > maxLearnings) {
            throw new IllegalArgumentException("learnings must contain " + minLearnings + " to "
                    + maxLearnings + " items");
        }
        for (int index = 0; index < batch.learnings().size(); index++) validate(batch.learnings().get(index), index);
        return batch;
    }

    private void validate(SemanticCandidate candidate, int index) {
        if (candidate == null) fail(index, "must be an object");
        if (!kinds.contains(candidate.kind())) fail(index, "kind is unsupported");
        bounded(candidate.summary(), maxSummary, index, "summary");
        bounded(candidate.content(), maxContent, index, "content");
        refs(candidate.anchors(), 1, maxAnchors, TARGET_REF, index, "anchors");
        refs(candidate.evidence(), 1, maxEvidence, TARGET_REF, index, "evidence");
        shortList(candidate.appliesWhen(), index, "appliesWhen");
        shortList(candidate.limitations(), index, "limitations");
        shortList(candidate.reusableFor(), index, "reusableFor");
        if (candidate.correctionRef() != null && !KNOWLEDGE_REF.matcher(candidate.correctionRef()).matches()) {
            fail(index, "correctionRef must be a context-local kN ref");
        }
        if (candidate.reference() != null) {
            bounded(candidate.reference().details(), maxReference, index, "reference.details");
            if (candidate.reference().details().getBytes(StandardCharsets.UTF_8).length > maxReference) {
                fail(index, "reference.details exceeds UTF-8 bound");
            }
        }
    }

    private void shortList(List<String> values, int index, String field) {
        if (values.size() > maxShortList || new LinkedHashSet<>(values).size() != values.size()) {
            fail(index, field + " must contain at most " + maxShortList + " unique items");
        }
        values.forEach(value -> bounded(value, 160, index, field));
    }

    private static void refs(List<String> values, int min, int max, Pattern pattern, int index, String field) {
        if (values.size() < min || values.size() > max || new LinkedHashSet<>(values).size() != values.size()
                || values.stream().anyMatch(value -> value == null || !pattern.matcher(value).matches())) {
            fail(index, field + " must contain " + min + " to " + max + " unique context-local refs");
        }
    }

    private static void bounded(String value, int max, int index, String field) {
        if (value == null || value.isBlank() || value.length() > max
                || value.codePoints().anyMatch(codePoint -> Character.isISOControl(codePoint)
                        && codePoint != '\n' && codePoint != '\r' && codePoint != '\t')) {
            fail(index, field + " must be non-blank and at most " + max + " characters");
        }
    }

    private static void fail(int index, String message) {
        throw new IllegalArgumentException("learning " + index + " " + message);
    }

    private static JsonNode readSchema(ObjectMapper mapper) {
        ClassLoader loader = AgentLearningContract.class.getClassLoader();
        try (InputStream stream = loader.getResourceAsStream(RESOURCE)) {
            if (stream == null) throw new IllegalStateException("Canonical learning schema is not packaged");
            return mapper.readTree(stream);
        } catch (IOException exception) {
            throw new IllegalStateException("Canonical learning schema cannot be read", exception);
        }
    }

    private static Set<String> jsonStrings(JsonNode array) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        array.forEach(value -> values.add(value.asText()));
        return Set.copyOf(values);
    }
}
