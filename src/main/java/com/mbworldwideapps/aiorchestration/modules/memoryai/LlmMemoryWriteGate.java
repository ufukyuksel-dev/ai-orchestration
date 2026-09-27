package com.mbworldwideapps.aiorchestration.modules.memoryai;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import com.mbworldwideapps.aiorchestration.config.MemoryWriteGateProperties;
import com.mbworldwideapps.aiorchestration.core.policy.PolicyDecision;
import com.mbworldwideapps.aiorchestration.core.policy.PolicyEngine;
import com.mbworldwideapps.aiorchestration.modules.llmgateway.GenerationRequest;
import com.mbworldwideapps.aiorchestration.modules.llmgateway.GenerationResponse;
import com.mbworldwideapps.aiorchestration.modules.llmgateway.LLMGateway;
import com.mbworldwideapps.aiorchestration.modules.llmgateway.LLMProviderTimeoutException;
import com.mbworldwideapps.aiorchestration.modules.mcp.server.McpAccessException;
import com.mbworldwideapps.aiorchestration.modules.mcp.server.McpClientContext;
import org.springframework.stereotype.Component;

@Component
public class LlmMemoryWriteGate implements MemoryWriteGate {

    static final String ROLE = "memory-write-gate";
    static final String REASON_DEGRADED = "gate_degraded";
    static final String HEURISTIC_PROVIDER = "heuristic";
    static final double NEAR_SIMILARITY = 0.92;
    static final String REASON_LOW_CONFIDENCE = "low_confidence";
    static final String REASON_MEMORY_TOO_LARGE = "memory_too_large";
    static final String REASON_MULTI_FACT_MEMORY = "multi_fact_memory";
    static final String REASON_TRUST_DOWNGRADE = "trust_downgrade";
    private static final Pattern BULLET_LINE = Pattern.compile("^\\s*(?:[-*]|\\d+[.)])\\s+.+$");
    private static final Pattern FILE_REF = Pattern.compile("\\b[\\w./-]+\\.(?:java|kt|ts|tsx|js|py|md|yml|yaml|json)\\b");

    private final LLMGateway llmGateway;
    private final MemoryWriteGateParser parser;
    private final MemoryWriteGateProviderResolver providerResolver;
    private final MemoryWriteGateProperties properties;
    private final MemoryRetrievalService memoryRetrievalService;
    private final MemoryContextBuilder memoryContextBuilder;
    private final PolicyEngine policyEngine;

    public LlmMemoryWriteGate(LLMGateway llmGateway, MemoryWriteGateParser parser,
            MemoryWriteGateProviderResolver providerResolver, MemoryWriteGateProperties properties,
            MemoryRetrievalService memoryRetrievalService, MemoryContextBuilder memoryContextBuilder,
            PolicyEngine policyEngine) {
        this.llmGateway = llmGateway;
        this.parser = parser;
        this.providerResolver = providerResolver;
        this.properties = properties;
        this.memoryRetrievalService = memoryRetrievalService;
        this.memoryContextBuilder = memoryContextBuilder;
        this.policyEngine = policyEngine;
    }

    @Override
    public GateDecision evaluate(CreateMemoryRequest request, GateContext context) {
        McpClientContext mcpContext = context == null ? null : context.mcpClientContext();
        // Provider resolution is local-only but also enforces provider.* scopes and
        // validates override values. Run it before every early verdict so an invalid
        // or unauthorized override can never be persisted as gate metadata.
        String provider = providerResolver.resolve(mcpContext, context == null ? null : context.providerOverride());
        PolicyDecision contentDecision = policyEngine.evaluateMemoryContent(policyText(request));
        if (!contentDecision.allowed()) {
            return new GateDecision(Verdict.REJECTED, contentDecision.reason(),
                    "This memory contains sensitive content and cannot be stored as-is.",
                    List.of("Remove or generalize sensitive terms before resubmitting."), 1.0,
                    Map.of("policyReason", contentDecision.reason()));
        }
        GateDecision atomGuard = atomGuard(request);
        if (atomGuard != null) {
            return atomGuard;
        }

        if (HEURISTIC_PROVIDER.equals(provider)) {
            return heuristicDecision(request);
        }
        try {
            MemoryContextResponse similarMemory = similarMemory(request);
            String memoryBlock = similarMemory.items().isEmpty()
                    ? "Similar-memory retrieval completed successfully: 0 eligible similar memories were returned."
                    : memoryContextBuilder.build(similarMemory);
            GenerationResponse response = llmGateway.generate(new GenerationRequest(
                    prompt(request),
                    request.projectKey(),
                    List.of(),
                    "fresh",
                    provider,
                    memoryBlock,
                    similarMemory.injectedMemoryCount(),
                    ROLE));
            MemoryWriteGateParser.ParseResult parseResult = parser.parse(response.answer());
            if (parseResult instanceof MemoryWriteGateParser.ParseResult.Failure failure) {
                return degradedDecision("parse_" + failure.reason().name().toLowerCase(), provider,
                        failure.detail());
            }
            GateDecision decision = ((MemoryWriteGateParser.ParseResult.Success) parseResult).decision();
            return enforcePostParseGuards(decision, mcpContext, provider);
        } catch (McpAccessException e) {
            throw e;
        } catch (LLMProviderTimeoutException e) {
            return degradedDecision("timeout", e.providerId(), e.getClass().getSimpleName());
        } catch (RuntimeException e) {
            return degradedDecision("runtime_error", null, e.getClass().getSimpleName());
        }
    }

    private static GateDecision atomGuard(CreateMemoryRequest request) {
        if (request.sourceType() != MemorySourceType.MCP_EXTERNAL) {
            return null;
        }
        String summary = MemoryTextPreview.normalize(request.summary());
        String text = MemoryTextPreview.normalize(request.text());
        // Oversized and multi-fact memories are rejected so they must be split. There is no
        // pending state, so the only way to keep an atom limit meaningful is to refuse the write.
        if (summary.length() > MemoryAtomLimits.SUMMARY_MAX_CHARS
                || text.length() > MemoryAtomLimits.TEXT_MAX_CHARS) {
            return new GateDecision(Verdict.REJECTED, REASON_MEMORY_TOO_LARGE,
                    "Please split this into smaller atomic memory items.",
                    List.of("Memory text must be one durable atom and stay under "
                            + MemoryAtomLimits.TEXT_MAX_CHARS + " characters."),
                    1.0,
                    Map.of(
                            "summaryLength", summary.length(),
                            "textLength", text.length(),
                            "summaryMaxChars", MemoryAtomLimits.SUMMARY_MAX_CHARS,
                            "textMaxChars", MemoryAtomLimits.TEXT_MAX_CHARS));
        }
        if (looksMultiFact(request.text())) {
            return new GateDecision(Verdict.REJECTED, REASON_MULTI_FACT_MEMORY,
                    "Please split this into one memory per fact and resubmit.",
                    List.of("Memory appears to contain multiple facts or file references."),
                    1.0,
                    Map.of("atomLimit", MemoryAtomLimits.TEXT_MAX_CHARS));
        }
        return null;
    }

    private static boolean looksMultiFact(String rawText) {
        if (rawText == null || rawText.isBlank()) {
            return false;
        }
        long bulletLines = rawText.lines()
                .filter(line -> BULLET_LINE.matcher(line).matches())
                .count();
        if (bulletLines >= 3) {
            return true;
        }
        long paragraphs = java.util.Arrays.stream(rawText.split("\\R\\s*\\R"))
                .filter(value -> !value.isBlank())
                .count();
        if (paragraphs >= 3) {
            return true;
        }
        long fileRefs = FILE_REF.matcher(rawText).results().count();
        return fileRefs >= 3;
    }

    private GateDecision enforcePostParseGuards(GateDecision decision, McpClientContext context, String provider) {
        Map<String, Object> metadata = new LinkedHashMap<>(decision.debugMetadata());
        metadata.put("provider", provider);
        // Low confidence and untrusted clients used to park the write for human approval.
        // That state is gone: the verdict stands and the signal is kept as metadata only.
        if (decision.verdict() == Verdict.AUTO_ACTIVE && decision.confidence() < properties.autoActiveConfidence()) {
            metadata.put("belowAutoActiveConfidenceFloor", true);
            metadata.put("autoActiveConfidenceFloor", properties.autoActiveConfidence());
        }
        if (decision.verdict() == Verdict.AUTO_ACTIVE && !isTrustedForAutoActive(context)) {
            metadata.put("untrustedClient", true);
            metadata.put("clientId", context == null ? null : context.clientId());
        }
        return new GateDecision(decision.verdict(), decision.gateReason(), decision.suggestedQuestion(),
                decision.contextHints(), decision.confidence(), metadata);
    }

    private boolean isTrustedForAutoActive(McpClientContext context) {
        return context != null
                && (context.isTrusted(properties.trustedClientIds()) || context.hasScope("memory.auto_active_write"));
    }

    /**
     * LLM-free gate (the default without a local model): policy and atom guards already ran. Only a proposal whose
     * normalized text already exists in the same project is a duplicate. A near neighbour by embedding similarity
     * with different text is kept: high similarity is not equivalence (another test command or a new exception
     * for the same file would otherwise be lost).
     */
    private GateDecision heuristicDecision(CreateMemoryRequest request) {
        try {
            String proposed = normalized(request.text());
            MemoryContextItem nearest = null;
            for (MemoryContextItem item : similarMemory(request).items()) {
                boolean sameScope = item.projectKey() == null || item.projectKey().equals(request.projectKey());
                if (!sameScope) continue;
                if (proposed.equals(normalized(item.text()))) {
                    return new GateDecision(Verdict.REJECTED, "duplicate_memory",
                            "The same memory already exists.", List.of(), 1.0,
                            Map.of("provider", HEURISTIC_PROVIDER, "duplicateOf", item.memoryId().toString()));
                }
                if (item.semanticScore() >= NEAR_SIMILARITY && (nearest == null
                        || item.semanticScore() > nearest.semanticScore())) {
                    nearest = item;
                }
            }
            if (nearest != null) {
                return new GateDecision(Verdict.AUTO_ACTIVE, "heuristic_near_duplicate_kept", null, List.of(), 0.9,
                        Map.of("provider", HEURISTIC_PROVIDER, "similarTo", nearest.memoryId().toString()));
            }
            return new GateDecision(Verdict.AUTO_ACTIVE, "heuristic_no_duplicate", null, List.of(), 0.9,
                    Map.of("provider", HEURISTIC_PROVIDER));
        } catch (RuntimeException e) {
            return degradedDecision("heuristic_error", HEURISTIC_PROVIDER, e.getClass().getSimpleName());
        }
    }

    private static String normalized(String text) {
        return text == null ? "" : text.strip().replaceAll("\\s+", " ").toLowerCase(java.util.Locale.ROOT);
    }

    private MemoryContextResponse similarMemory(CreateMemoryRequest request) {
        MemoryContextResponse response = memoryRetrievalService.retrieve(
                request.summary() + "\n" + request.text(), request.projectKey(), null);
        if (response.items().size() <= 5) {
            return response;
        }
        List<MemoryContextItem> items = response.items().stream().limit(5).toList();
        return new MemoryContextResponse(
                items,
                items.stream().map(MemoryContextItem::memoryId).toList(),
                items.size(),
                items.stream().mapToInt(MemoryContextItem::tokenEstimate).sum(),
                response.injectedScopes(),
                response.staleFlaggedCount(),
                response.conflictFlaggedCount(),
                response.latencyMs());
    }

    private GateDecision degradedDecision(String detail, String provider, String errorClass) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("degraded", true);
        metadata.put("detail", detail);
        if (provider != null && !provider.isBlank()) {
            metadata.put("provider", provider);
        }
        if (errorClass != null && !errorClass.isBlank()) {
            metadata.put("errorClass", errorClass);
        }
        // Fail open: a local provider outage must not silently block every memory write.
        return new GateDecision(Verdict.AUTO_ACTIVE, REASON_DEGRADED, null, List.of(), 0.0, metadata);
    }

    private static String prompt(CreateMemoryRequest request) {
        return """
                You are the AI Orchestration memory write gate.
                Classify only the supplied proposal and comparison context. Do not call tools or search external sources.
                Decide whether the proposed memory is stored immediately or rejected. There is no
                human-approval state: every proposal you do not reject becomes active at once.

                Return only one JSON object with this exact shape:
                {
                  "verdict": "AUTO_ACTIVE|REJECTED",
                  "gateReason": "short_machine_reason",
                  "suggestedQuestion": "short instruction telling the author how to fix a rejected proposal",
                  "contextHints": ["short reason or duplicate signal"],
                  "confidence": 0.0,
                  "debugMetadata": {}
                }

                Rules:
                - AUTO_ACTIVE for reusable, non-sensitive NEW information with no conflict in similar memory.
                  Prefer AUTO_ACTIVE when unsure: ambiguity, subjective preference or unclear scope is not a
                  reason to reject, because nothing is parked for review any more.
                - If an existing memory already covers the same fact or procedure and the proposal adds no substantive information,
                  return REJECTED with gateReason="duplicate_memory". Paraphrasing or a different title is not new information.
                - Mere similarity is not duplication: allow distinct facts that share a topic. Compare meaning and scope.
                - An existing equivalent memory is a reason NOT to create a new active record, not confirmation to approve it.
                - REJECTED for unsafe, secret-like, malicious, or nonsensical content, for duplicates, and for
                  proposals that carry several facts at once and must be split.
                - Confidence must reflect your certainty in the verdict.

                Proposed memory:
                scope=%s
                projectKey=%s
                type=%s
                summary=%s
                tags=%s
                text=%s

                Similar memory is supplied once in the separate memory context block.
                """.formatted(
                request.scope().value(),
                request.projectKey() == null ? "" : request.projectKey(),
                request.memoryType().value(),
                request.summary(),
                request.tags(),
                request.text());
    }

    private static String policyText(CreateMemoryRequest request) {
        return request.summary() + "\n" + request.text();
    }
}
