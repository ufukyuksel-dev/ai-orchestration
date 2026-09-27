package com.mbworldwideapps.aiorchestration.modules.memoryai;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

@Component
public class EpisodicCandidateClassifier {

    public EpisodicCandidateClassification classify(String text, MemorySignalDetection detection, String projectKey) {
        String normalized = MemorySignalDetector.normalize(text);
        int tokenEstimate = tokenEstimate(text);
        if (detection.signalType() == MemorySignalType.NONE) {
            return reject("no-memory-signal", tokenEstimate);
        }
        int minimumTokens = detection.signalType() == MemorySignalType.EXPLICIT ? 4 : 5;
        if (tokenEstimate < minimumTokens) {
            return reject("insufficient-context", tokenEstimate);
        }

        MemoryScope scope = projectKey == null || projectKey.isBlank() ? MemoryScope.EPISODIC : MemoryScope.PROJECT;
        MemoryType memoryType = memoryType(normalized, detection.signalType());
        double confidence = Math.min(0.79, detection.signalStrength() + generalityBoost(normalized));
        String summary = summary(text, memoryType);
        List<String> tags = tags(normalized, detection.signalType(), scope);
        return new EpisodicCandidateClassification(true, null, scope, memoryType, confidence, summary, tags,
                tokenEstimate, "heuristic-signal-" + detection.signalType().value());
    }

    private static EpisodicCandidateClassification reject(String reason, int tokenEstimate) {
        return new EpisodicCandidateClassification(false, reason, null, null, 0.0, null, List.of(), tokenEstimate,
                "heuristic-reject-" + reason);
    }

    private static MemoryType memoryType(String normalized, MemorySignalType signalType) {
        if (normalized.contains("yasak") || normalized.contains("zorunlu") || normalized.contains("must")
                || normalized.contains("forbidden") || normalized.contains("kural")) {
            return MemoryType.RULE;
        }
        return switch (signalType) {
            case CORRECTION -> MemoryType.CORRECTION;
            case APPROVAL -> MemoryType.DECISION;
            case EXPLICIT -> MemoryType.RULE;
            case NONE -> MemoryType.PREFERENCE;
        };
    }

    private static double generalityBoost(String normalized) {
        double boost = 0.0;
        if (normalized.contains("biz") || normalized.contains("projede") || normalized.contains("servis")
                || normalized.contains("repository") || normalized.contains("logger") || normalized.contains("moneytl")) {
            boost += 0.08;
        }
        if (normalized.contains("zorunlu") || normalized.contains("yasak") || normalized.contains("kullan")) {
            boost += 0.06;
        }
        return boost;
    }

    private static String summary(String text, MemoryType memoryType) {
        String normalized = text.trim().replaceAll("\\s+", " ");
        int maxLength = Math.min(normalized.length(), 90);
        String prefix = switch (memoryType) {
            case RULE -> "Captured rule";
            case CORRECTION -> "Captured correction";
            case DECISION -> "Captured decision";
            case PREFERENCE -> "Captured preference";
            case ANTI_PATTERN -> "Captured anti-pattern";
            case DISCOVERY -> "Captured discovery";
        };
        return prefix + ": " + normalized.substring(0, maxLength);
    }

    private static List<String> tags(String normalized, MemorySignalType signalType, MemoryScope scope) {
        List<String> tags = new ArrayList<>();
        tags.add("captured");
        tags.add(signalType.value());
        tags.add(scope.value());
        if (normalized.contains("logger") || normalized.contains("log")) {
            tags.add("logging");
        }
        if (normalized.contains("repository")) {
            tags.add("repository");
        }
        if (normalized.contains("moneytl") || normalized.contains("money")) {
            tags.add("money");
        }
        return tags.stream().distinct().toList();
    }

    private static int tokenEstimate(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        return text.trim().split("\\s+").length;
    }
}
