package com.mbworldwideapps.aiorchestration.modules.memoryai;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MemoryInlineApprovalProxy {

    private final MemoryService memoryService;
    private final MemoryReviewService memoryReviewService;

    public MemoryInlineApprovalProxy(MemoryService memoryService, MemoryReviewService memoryReviewService) {
        this.memoryService = memoryService;
        this.memoryReviewService = memoryReviewService;
    }

    public MemoryInlineApprovalPendingResponse pending(String projectKey, Integer limit, Integer offset) {
        int effectiveLimit = limit == null || limit <= 0 ? 10 : Math.min(limit, 25);
        int effectiveOffset = offset == null || offset < 0 ? 0 : offset;
        String normalizedProjectKey = blankToNull(projectKey);
        List<MemoryItem> allPending = java.util.stream.Stream.concat(
                        memoryService.list(MemoryScope.GLOBAL, MemoryStatus.PENDING_REVIEW, null).stream(),
                        memoryService.list(MemoryScope.PROJECT, MemoryStatus.PENDING_REVIEW, normalizedProjectKey)
                                .stream())
                .sorted(Comparator.comparing(MemoryItem::updatedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(MemoryItem::createdAt, Comparator.nullsLast(Comparator.naturalOrder()))
                        .reversed())
                .toList();
        List<MemoryInlineApprovalCard> items = allPending.stream()
                .skip(effectiveOffset)
                .limit(effectiveLimit)
                .map(item -> MemoryInlineApprovalCard.from(item, pendingReason(item)))
                .toList();
        return new MemoryInlineApprovalPendingResponse(items, allPending.size(), effectiveLimit, effectiveOffset);
    }

    @Transactional
    public MemoryInlineApprovalDecisionResponse decide(UUID memoryId, MemoryInlineApprovalDecisionRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("approval decision request is required");
        }
        String decision = normalizeDecision(request.decision());
        String actor = actor(request.actor());
        String reason = blankToNull(request.reason()) == null ? "inline human approval" : request.reason().trim();
        return switch (decision) {
            case "approve" -> approve(memoryId, actor, reason, request.humanTurnRef());
            case "reject" -> reject(memoryId, actor, reason, request.humanTurnRef());
            case "edit" -> edit(memoryId, request, actor, reason);
            default -> throw new IllegalArgumentException("decision must be approve, reject, or edit");
        };
    }

    private MemoryInlineApprovalDecisionResponse approve(UUID memoryId, String actor, String reason,
            String humanTurnRef) {
        MemoryItem approved = memoryReviewService.approveHumanConfirmed(memoryId, actor, reason, humanTurnRef);
        return new MemoryInlineApprovalDecisionResponse(memoryId, "approve", approved, null, actor);
    }

    private MemoryInlineApprovalDecisionResponse reject(UUID memoryId, String actor, String reason,
            String humanTurnRef) {
        MemoryItem rejected = memoryReviewService.rejectHumanConfirmed(memoryId, actor, reason, humanTurnRef);
        return new MemoryInlineApprovalDecisionResponse(memoryId, "reject", rejected, null, actor);
    }

    private MemoryInlineApprovalDecisionResponse edit(UUID memoryId, MemoryInlineApprovalDecisionRequest request,
            String actor, String reason) {
        MemoryItem original = memoryService.findById(memoryId);
        if (original.status() != MemoryStatus.PENDING_REVIEW) {
            throw new IllegalArgumentException("Only pending_review memory can be edited through inline approval");
        }

        String summary = blankToNull(request.summary()) == null ? original.summary() : request.summary().trim();
        String text = blankToNull(request.text()) == null ? original.text() : request.text().trim();
        List<String> tags = request.tags() == null ? original.tags() : request.tags();
        Double confidence = request.confidence() == null ? original.confidence() : request.confidence();
        if (confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("confidence must be between 0 and 1");
        }
        if (summary.isBlank() || text.isBlank()) {
            throw new IllegalArgumentException("edited summary and text cannot be blank");
        }

        Map<String, Object> metadata =
                new LinkedHashMap<>(original.metadata() == null ? Map.of() : original.metadata());
        metadata.put("editedFrom", original.id().toString());
        metadata.put("editedBy", actor);
        metadata.put("humanConfirmed", true);
        if (blankToNull(request.humanTurnRef()) != null) {
            metadata.put("humanTurnRef", request.humanTurnRef().trim());
        }
        metadata.put("originalSourceRef", original.sourceRef());
        metadata.put("originalConfidence", original.confidence());
        metadata.put("confidence", confidence);

        MemoryItem edited = memoryService.create(new CreateMemoryRequest(
                original.scope(),
                original.projectKey(),
                original.memoryType(),
                summary,
                text,
                tags,
                confidence,
                MemoryStatus.PENDING_REVIEW,
                original.sourceType(),
                "inline-edit:%s:%s".formatted(original.id(), UUID.randomUUID()),
                actor,
                metadata,
                Instant.now(),
                original.expiresAt()));

        memoryReviewService.rejectForEdit(original.id(), actor, reason, edited.id(), request.humanTurnRef());
        MemoryItem approved = memoryReviewService.approveAfterHumanEdit(edited.id(), actor, reason, original.id(),
                request.humanTurnRef());
        MemoryItem rejectedOriginal = memoryService.findById(original.id());
        return new MemoryInlineApprovalDecisionResponse(original.id(), "edit", rejectedOriginal, approved, actor);
    }

    private String pendingReason(MemoryItem item) {
        Map<String, Object> metadata = item.metadata() == null ? Map.of() : item.metadata();
        Object gateReason = metadata.get("gateReason");
        if (gateReason instanceof String reason && !reason.isBlank()) {
            return reason;
        }
        return memoryService.reviewQueueForMemory(item.id()).stream()
                .filter(queueItem -> queueItem.status() == ReviewStatus.OPEN)
                .findFirst()
                .map(queueItem -> queueItem.reason().value())
                .orElse("pending_review");
    }

    private static String normalizeDecision(String decision) {
        String normalized = blankToNull(decision) == null ? "" : decision.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "approve", "approved", "onayla", "onay" -> "approve";
            case "reject", "rejected", "reddet", "red" -> "reject";
            case "edit", "duzelt", "düzelt", "düzenle", "duzenle", "düzeltip kaydet", "duzeltip kaydet" -> "edit";
            default -> normalized;
        };
    }

    private static String actor(String actor) {
        return blankToNull(actor) == null ? "human:inline-approval" : actor.trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
