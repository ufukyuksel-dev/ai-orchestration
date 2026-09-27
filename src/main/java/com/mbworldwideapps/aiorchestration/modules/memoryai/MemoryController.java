package com.mbworldwideapps.aiorchestration.modules.memoryai;

import java.util.List;
import java.util.Map;
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
@RequestMapping("/api/memory")
public class MemoryController {

    private final MemoryService memoryService;
    private final CuratedMemoryLoader curatedMemoryLoader;
    private final MemoryCaptureService memoryCaptureService;
    private final MemoryRetrievalService memoryRetrievalService;

    public MemoryController(MemoryService memoryService, CuratedMemoryLoader curatedMemoryLoader,
            MemoryCaptureService memoryCaptureService, MemoryRetrievalService memoryRetrievalService) {
        this.memoryService = memoryService;
        this.curatedMemoryLoader = curatedMemoryLoader;
        this.memoryCaptureService = memoryCaptureService;
        this.memoryRetrievalService = memoryRetrievalService;
    }

    @GetMapping("/rules")
    public Map<String, Object> rules() {
        return Map.of(
                "status", "phase0-seed",
                "rules", List.of(
                        "Service names follow acme-svc-<domain>-<service>.",
                        "Use acme-logger audit/info/error methods.",
                        "Use MoneyTL value object for TRY amounts; float is forbidden."));
    }

    @PostMapping
    public MemoryItem create(@Valid @RequestBody CreateMemoryRequest request) {
        return memoryService.create(request);
    }

    @GetMapping("/{id}")
    public MemoryItem findById(@PathVariable UUID id) {
        return memoryService.findById(id);
    }

    @GetMapping
    public List<MemoryItem> list(
            @RequestParam(required = false) String scope,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String projectKey) {
        return memoryService.list(
                scope == null ? null : MemoryScope.from(scope),
                status == null ? null : MemoryStatus.from(status),
                projectKey);
    }

    @PostMapping("/{id}/status")
    public MemoryItem updateStatus(@PathVariable UUID id, @Valid @RequestBody MemoryStatusUpdateRequest request) {
        return memoryService.updateStatus(id, request.status(), request.actor(), request.reason());
    }

    @PostMapping("/reload")
    public CuratedMemoryLoadResult reloadCuratedMemory() {
        return curatedMemoryLoader.loadAll();
    }

    @PostMapping("/capture")
    public CaptureMemoryResponse capture(@Valid @RequestBody CaptureMemoryRequest request) {
        return memoryCaptureService.capture(request);
    }

    @GetMapping("/retrieve")
    public MemoryContextResponse retrieve(
            @RequestParam String query,
            @RequestParam(required = false) String projectKey,
            @RequestParam(required = false) String userId) {
        return memoryRetrievalService.retrieve(query, projectKey, userId);
    }

    @GetMapping("/{id}/events")
    public List<MemoryEvent> events(@PathVariable UUID id) {
        return memoryService.eventsForMemory(id);
    }

    @GetMapping("/{id}/review-queue")
    public List<ReviewQueueItem> reviewQueue(@PathVariable UUID id) {
        return memoryService.reviewQueueForMemory(id);
    }

    @GetMapping("/config")
    public MemoryConfigResponse config() {
        var config = memoryService.memoryConfig();
        return new MemoryConfigResponse(config.episodicCollectionName(), config.contextTokenCap(),
                config.defaultConfidence());
    }
}
