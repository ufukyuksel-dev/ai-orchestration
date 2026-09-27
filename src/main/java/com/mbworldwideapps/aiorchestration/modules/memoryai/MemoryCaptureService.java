package com.mbworldwideapps.aiorchestration.modules.memoryai;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

import com.mbworldwideapps.aiorchestration.core.pii.PiiScrubber;
import com.mbworldwideapps.aiorchestration.core.policy.PolicyDecision;
import com.mbworldwideapps.aiorchestration.core.policy.PolicyEngine;
import com.mbworldwideapps.aiorchestration.core.telemetry.AiMetrics;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class MemoryCaptureService {

    private final PiiScrubber piiScrubber;
    private final MemorySignalDetector signalDetector;
    private final EpisodicCandidateClassifier classifier;
    private final MemoryService memoryService;
    private final AiMetrics aiMetrics;
    private final PolicyEngine policyEngine;
    private final RuleMemoryActivationPolicy ruleActivationPolicy;

    public MemoryCaptureService(PiiScrubber piiScrubber, MemorySignalDetector signalDetector,
            EpisodicCandidateClassifier classifier, MemoryService memoryService, AiMetrics aiMetrics) {
        this(piiScrubber, signalDetector, classifier, memoryService, aiMetrics, PolicyEngine.allowAll(),
                RuleMemoryActivationPolicy.authorityEnabled());
    }

    public MemoryCaptureService(PiiScrubber piiScrubber, MemorySignalDetector signalDetector,
            EpisodicCandidateClassifier classifier, MemoryService memoryService, AiMetrics aiMetrics,
            PolicyEngine policyEngine) {
        this(piiScrubber, signalDetector, classifier, memoryService, aiMetrics, policyEngine,
                RuleMemoryActivationPolicy.authorityEnabled());
    }

    @Autowired
    public MemoryCaptureService(PiiScrubber piiScrubber, MemorySignalDetector signalDetector,
            EpisodicCandidateClassifier classifier, MemoryService memoryService, AiMetrics aiMetrics,
            PolicyEngine policyEngine, RuleMemoryActivationPolicy ruleActivationPolicy) {
        this.piiScrubber = piiScrubber;
        this.signalDetector = signalDetector;
        this.classifier = classifier;
        this.memoryService = memoryService;
        this.aiMetrics = aiMetrics;
        this.policyEngine = policyEngine;
        this.ruleActivationPolicy = ruleActivationPolicy;
    }

    public CaptureMemoryResponse capture(CaptureMemoryRequest request) {
        String textHashPrefix = hashPrefix(request.text());
        var piiReason = piiScrubber.firstMatchReason(request.text());
        if (piiReason.isPresent()) {
            aiMetrics.recordMemoryPiiReject(piiReason.get());
            return new CaptureMemoryResponse(false, "rejected", "pii_detected", null, null, null, 0.0,
                    piiReason.get(), textHashPrefix);
        }

        PolicyDecision contentDecision = policyEngine.evaluateMemoryContent(request.text());
        if (!contentDecision.allowed()) {
            aiMetrics.recordMemoryPolicyReject(contentDecision.reason());
            return new CaptureMemoryResponse(false, "rejected", "sensitive_policy", null, null, null, 0.0,
                    null, textHashPrefix);
        }

        MemorySignalDetection detection = request.signalType() == null
                ? signalDetector.detect(request.text())
                : new MemorySignalDetection(request.signalType(), "request-override", signalStrength(request.signalType()));
        EpisodicCandidateClassification classification = classifier.classify(
                request.text(), detection, blankToNull(request.projectKey()));
        if (!classification.accepted()) {
            return new CaptureMemoryResponse(false, "rejected", classification.rejectReason(),
                    detection.signalType().value(), null, null, 0.0, null, textHashPrefix);
        }

        // No pending state for ordinary memory, but rule authority still owns RULE activation:
        // an accepted RULE candidate stays a rules.promote candidate instead of failing the write.
        MemoryStatus captureStatus = ruleActivationPolicy.coerceCandidateStatus(
                classification.memoryType(), MemoryStatus.ACTIVE);
        MemoryItem item = memoryService.create(new CreateMemoryRequest(
                classification.scope(),
                blankToNull(request.projectKey()),
                classification.memoryType(),
                classification.summary(),
                request.text(),
                classification.tags(),
                classification.confidence(),
                captureStatus,
                sourceType(detection.signalType()),
                sourceRef(detection.signalType(), request.sessionId(), textHashPrefix),
                blankToNull(request.userId()) == null ? "capture" : request.userId().trim(),
                metadata(detection, classification, request, textHashPrefix),
                Instant.now(),
                null));

        return new CaptureMemoryResponse(true, "created_" + item.status().value(), null,
                detection.signalType().value(), item.id(), item.status().value(), item.confidence(), null,
                textHashPrefix);
    }

    private static Map<String, Object> metadata(MemorySignalDetection detection,
            EpisodicCandidateClassification classification, CaptureMemoryRequest request, String textHashPrefix) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source", "capture-api");
        metadata.put("signalType", detection.signalType().value());
        metadata.put("matchedPhrasePresent", detection.matchedPhrase() != null);
        metadata.put("signalStrength", detection.signalStrength());
        metadata.put("classifierReason", classification.classifierReason());
        metadata.put("tokenEstimate", classification.tokenEstimate());
        metadata.put("sessionIdPresent", blankToNull(request.sessionId()) != null);
        metadata.put("projectKeyPresent", blankToNull(request.projectKey()) != null);
        metadata.put("textHashPrefix", textHashPrefix);
        metadata.put("textLength", request.text() == null ? 0 : request.text().length());
        metadata.put("piiChecked", true);
        return metadata;
    }

    private static MemorySourceType sourceType(MemorySignalType signalType) {
        return switch (signalType) {
            case CORRECTION -> MemorySourceType.CORRECTION_SIGNAL;
            case APPROVAL -> MemorySourceType.APPROVAL_SIGNAL;
            case EXPLICIT -> MemorySourceType.SESSION_SUMMARY;
            case NONE -> MemorySourceType.SESSION_SUMMARY;
        };
    }

    private static String sourceRef(MemorySignalType signalType, String sessionId, String textHashPrefix) {
        String session = blankToNull(sessionId) == null ? "no-session" : sessionId.trim();
        return "capture:%s:%s:%s".formatted(signalType.value(), session, textHashPrefix);
    }

    private static double signalStrength(MemorySignalType signalType) {
        return switch (signalType) {
            case EXPLICIT -> 0.7;
            case CORRECTION -> 0.55;
            case APPROVAL -> 0.5;
            case NONE -> 0.0;
        };
    }

    private static String hashPrefix(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((text == null ? "" : text).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
