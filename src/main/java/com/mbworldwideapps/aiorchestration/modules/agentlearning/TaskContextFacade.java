package com.mbworldwideapps.aiorchestration.modules.agentlearning;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.mbworldwideapps.aiorchestration.modules.agentlearning.AgentLearningModels.DiscoveryRoute;
import com.mbworldwideapps.aiorchestration.modules.agentlearning.AgentLearningModels.Knowledge;
import com.mbworldwideapps.aiorchestration.modules.agentlearning.AgentLearningModels.SuggestedTarget;
import com.mbworldwideapps.aiorchestration.modules.agentlearning.AgentLearningModels.SuggestedReference;
import com.mbworldwideapps.aiorchestration.modules.agentlearning.AgentLearningModels.TaskContextPlan;
import com.mbworldwideapps.aiorchestration.modules.context.LearningContextItem;
import com.mbworldwideapps.aiorchestration.modules.context.LearningContextRequest;
import com.mbworldwideapps.aiorchestration.modules.context.LearningContextResponse;
import com.mbworldwideapps.aiorchestration.modules.context.LearningContextService;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryContextItem;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryType;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryRelationService;
import com.mbworldwideapps.aiorchestration.modules.references.ReferenceService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Bounded F5 adapter around the existing learning-context retrieval engine.
 * It converts selected memories/capsules into the smaller context.resolve
 * domain without exposing raw graph payloads or starting a repository scan.
 */
@Service
public class TaskContextFacade {

    private static final int MAX_KNOWLEDGE = 3;
    private static final int MAX_TARGETS = 3;
    private static final int MAX_REFERENCES = 2;

    private final LearningContextService contexts;
    private final AgentLearningRepository repository;
    private final MemoryRelationService relations;
    private final ReferenceService references;

    public TaskContextFacade(LearningContextService contexts, AgentLearningRepository repository) {
        this(contexts, repository, null, null);
    }

    @Autowired
    public TaskContextFacade(LearningContextService contexts, AgentLearningRepository repository,
            MemoryRelationService relations, ReferenceService references) {
        this.contexts = contexts;
        this.repository = repository;
        this.relations = relations;
        this.references = references;
    }

    public TaskContextPlan resolve(String query, String projectKey, String intent, int tokenBudget) {
        return resolve(query, projectKey, intent, tokenBudget, false);
    }

    public TaskContextPlan resolve(String query, String projectKey, String intent, int tokenBudget,
            boolean includeReferences) {
        LearningContextResponse response = contexts.retrieveExplicit(new LearningContextRequest(
                query, projectKey, null, intent, 3, 1, tokenBudget,
                true, true, "hybrid", false, false, null));

        List<MemoryContextItem> selectedDiscoveries = response.memoryContext().items().stream()
                .filter(item -> item.memoryType() == MemoryType.DISCOVERY)
                .limit(MAX_KNOWLEDGE)
                .toList();
        List<UUID> selectedIds = selectedDiscoveries.stream().map(MemoryContextItem::memoryId).toList();
        List<DiscoveryRoute> routes = selectedIds.isEmpty()
                ? List.of()
                : repository.findDiscoveryRoutes(projectKey, selectedIds, MAX_KNOWLEDGE);
        if (routes == null) routes = List.of();
        Map<UUID, DiscoveryRoute> routesById = new LinkedHashMap<>();
        routes.forEach(route -> routesById.put(route.memoryId(), route));

        List<Knowledge> knowledge = new ArrayList<>();
        List<SuggestedTarget> targets = new ArrayList<>();
        List<SuggestedReference> referenceTargets = new ArrayList<>();
        Set<String> targetKeys = new LinkedHashSet<>();
        int remainingKnowledgeTokens = tokenBudget;
        int omittedKnowledge = 0;
        int learnedAnchorCount = 0;
        int selectedLearnedAnchorCount = 0;
        for (MemoryContextItem item : selectedDiscoveries) {
            DiscoveryRoute route = routesById.get(item.memoryId());
            if (route == null) {
                omittedKnowledge++;
                continue;
            }
            String text = route.text() == null || route.text().isBlank() ? route.summary() : route.text().trim();
            int textTokens = estimateProjectionTokens(text);
            if (textTokens <= remainingKnowledgeTokens) {
                knowledge.add(new Knowledge(route.memoryId(), "discovery", text, true,
                        blankDefault(route.verification(), "UNVERIFIED"), route.stale() ? "STALE" : "UNKNOWN",
                        route.learningRevision(), route.canonicalHash()));
                remainingKnowledgeTokens -= textTokens;
            } else {
                omittedKnowledge++;
            }
            for (var anchor : route.anchors()) {
                learnedAnchorCount++;
                if ("directory".equalsIgnoreCase(anchor.locatorKind())
                        || !"RESOLVED".equalsIgnoreCase(anchor.resolutionState())
                        || targets.size() >= MAX_TARGETS
                        || !targetKeys.add(anchor.locatorKind() + ":" + anchor.relativePath() + ":" + anchor.symbolRef())) {
                    continue;
                }
                targets.add(new SuggestedTarget(anchor.relativePath(), anchor.symbolRef(),
                        normalizeRole(anchor.role()), anchor.expectedHash(), route.memoryId(), null, null));
                selectedLearnedAnchorCount++;
            }
        }

        // Use the existing graph/capsule/semantic selection only for missing
        // addresses. The facade consumes its compact locator, never the raw graph.
        if (targets.size() < MAX_TARGETS) {
            for (LearningContextItem item : response.items()) {
                if (targets.size() >= MAX_TARGETS || "memory".equals(item.kind())) continue;
                LocatorRange locator = locatorRange(item.locator());
                String path = locator.path();
                if (path.isBlank() || !targetKeys.add(path)) continue;
                targets.add(new SuggestedTarget(path, null, "supporting", null, null,
                        locator.startLine(), locator.endLine()));
            }
        }

        if (includeReferences && relations != null && references != null && !selectedIds.isEmpty()) {
            for (var link : relations.linkedReferences(projectKey, selectedIds).items()) {
                if (referenceTargets.size() >= MAX_REFERENCES || link.sectionKey() == null
                        || link.contentHash() == null || !"file".equals(link.kind())) {
                    continue;
                }
                String sourceCheck;
                try {
                    references.readSection(link.referenceId(), link.contentHash(), link.sectionKey(), 4);
                    sourceCheck = link.evidenceStale() ? "CHANGED" : "UNCHANGED";
                } catch (IllegalArgumentException unavailable) {
                    sourceCheck = switch (unavailable.getMessage() == null ? "" : unavailable.getMessage()) {
                        case "REFERENCE_CONTENT_CHANGED" -> "CHANGED";
                        case "REFERENCE_MISSING", "REFERENCE_SECTION_MISSING" -> "MISSING";
                        default -> "UNKNOWN";
                    };
                }
                referenceTargets.add(new SuggestedReference(link.referenceId(), link.relativePath(),
                        link.contentHash(), link.sectionKey(), link.memoryId(), sourceCheck));
            }
        }

        List<String> gaps = new ArrayList<>();
        if (knowledge.isEmpty()) {
            gaps.add(selectedDiscoveries.isEmpty()
                    ? "no_relevant_discovery"
                    : omittedKnowledge > 0 ? "knowledge_omitted_by_budget" : "no_usable_discovery");
        }
        if (targets.isEmpty()) gaps.add("no_focused_target");
        boolean unavailable = response.warnings().stream().anyMatch(TaskContextFacade::unavailableWarning)
                && knowledge.isEmpty() && targets.isEmpty();
        int omittedTargets = Math.max(0, learnedAnchorCount - selectedLearnedAnchorCount);
        return new TaskContextPlan(knowledge, targets, referenceTargets, gaps, omittedKnowledge,
                omittedTargets, unavailable);
    }

    public ReferenceService.SectionRead openReference(UUID referenceId, String contentHash,
            String sectionKey, int maxBytes) {
        if (references == null) throw new IllegalStateException("Reference section resolver unavailable");
        return references.readSection(referenceId, contentHash, sectionKey, maxBytes);
    }

    private static int estimateProjectionTokens(String text) {
        if (text == null || text.isBlank()) return 0;
        int codePoints = text.codePointCount(0, text.length());
        return Math.max(1, (codePoints + 3) / 4);
    }

    private static boolean unavailableWarning(String warning) {
        return warning != null && (warning.contains("_unavailable") || warning.contains("_degraded"));
    }

    static String locatorPath(String locator) {
        return locatorRange(locator).path();
    }

    private static LocatorRange locatorRange(String locator) {
        if (locator == null || locator.isBlank()) return new LocatorRange("", null, null);
        String value = locator.trim().replace('\\', '/');
        var matcher = java.util.regex.Pattern.compile("^(.*):(\\d+)(?:-(\\d+))?$").matcher(value);
        if (!matcher.matches()) return new LocatorRange(value, null, null);
        int start = Integer.parseInt(matcher.group(2));
        int end = matcher.group(3) == null ? start : Integer.parseInt(matcher.group(3));
        if (start < 1 || end < start) return new LocatorRange(matcher.group(1), null, null);
        return new LocatorRange(matcher.group(1), start, end);
    }

    private static String normalizeRole(String role) {
        if (role == null || role.isBlank()) return "supporting";
        String normalized = role.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "entry", "entry_point" -> "entry_point";
            case "primary_change_point", "authoritative_mapping", "configuration", "validation" -> normalized;
            default -> "supporting";
        };
    }

    private static String blankDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private record LocatorRange(String path, Integer startLine, Integer endLine) {
    }
}
