package com.mbworldwideapps.aiorchestration.modules.memoryai;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/memory")
public class MemoryReviewController {

    private final MemoryReviewService memoryReviewService;

    public MemoryReviewController(MemoryReviewService memoryReviewService) {
        this.memoryReviewService = memoryReviewService;
    }

    @GetMapping("/review-queue")
    public List<ReviewQueueItem> reviewQueue(@RequestParam(defaultValue = "open") String status) {
        return memoryReviewService.listReviewQueue(ReviewStatus.from(status));
    }

    @PostMapping("/{id}/approve")
    public MemoryItem approve(@PathVariable UUID id, @RequestBody(required = false) ReviewDecisionRequest request) {
        ReviewDecisionRequest safeRequest = request == null ? new ReviewDecisionRequest(null, null) : request;
        return memoryReviewService.approve(id, safeRequest.actor(), safeRequest.reason());
    }

    @PostMapping("/{id}/reject")
    public MemoryItem reject(@PathVariable UUID id, @RequestBody(required = false) ReviewDecisionRequest request) {
        ReviewDecisionRequest safeRequest = request == null ? new ReviewDecisionRequest(null, null) : request;
        return memoryReviewService.reject(id, safeRequest.actor(), safeRequest.reason());
    }

    @PostMapping("/{id}/edit")
    public MemoryItem edit(@PathVariable UUID id, @Valid @RequestBody EditMemoryRequest request) {
        return memoryReviewService.edit(id, request);
    }

    @PostMapping("/{id}/archive")
    public MemoryItem archive(@PathVariable UUID id, @RequestBody(required = false) ReviewDecisionRequest request) {
        ReviewDecisionRequest safeRequest = request == null ? new ReviewDecisionRequest(null, null) : request;
        return memoryReviewService.archive(id, safeRequest.actor(), safeRequest.reason());
    }

    @PostMapping("/{id}/promote")
    public MemoryItem promote(@PathVariable UUID id, @Valid @RequestBody PromoteMemoryRequest request) {
        return memoryReviewService.promote(id, request);
    }

    @GetMapping("/search")
    public List<MemoryItem> search(@RequestParam String query,
            @RequestParam(defaultValue = "20") int limit) {
        return memoryReviewService.search(query, limit);
    }
}
