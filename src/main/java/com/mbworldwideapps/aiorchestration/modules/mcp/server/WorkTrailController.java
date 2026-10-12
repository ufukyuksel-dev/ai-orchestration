package com.mbworldwideapps.aiorchestration.modules.mcp.server;

import java.util.List;
import java.util.UUID;

import com.mbworldwideapps.aiorchestration.modules.trails.WorkTrail;
import com.mbworldwideapps.aiorchestration.modules.trails.WorkTrail.Check;
import com.mbworldwideapps.aiorchestration.modules.trails.WorkTrail.Locator;
import com.mbworldwideapps.aiorchestration.modules.trails.WorkTrailRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Capture endpoint the hooks' Stop step calls once per request (fail-open on the hook side). The project comes from
 * the caller's working directory exactly as for scanner.project.resolve; the trail is telemetry, not memory.
 */
@RestController
@RequestMapping("/terminal/api/trails")
@ConditionalOnProperty(prefix = "ai-orchestration.mcp", name = "enabled", havingValue = "true", matchIfMissing = true)
public class WorkTrailController {

    static final int MAX_LOCATORS = 20;
    static final int MAX_CHECKS = 10;
    static final int MAX_TEXT = 4000;  // captured text of an edited definition (the hook sends <= 25 lines)

    private final ScannerMcpTool scanner;
    private final WorkTrailRepository trails;

    public WorkTrailController(ScannerMcpTool scanner, WorkTrailRepository trails) {
        this.scanner = scanner;
        this.trails = trails;
    }

    public record CaptureRequest(@NotBlank @Size(max = 4096) String cwd,
            @NotBlank @Size(max = 200) String trailKey,
            @Size(max = 200) String codeFingerprint,
            @NotBlank @Size(max = 2000) String requestText,
            @Size(max = MAX_LOCATORS) List<@Valid LocatorBody> edited,
            @Size(max = MAX_LOCATORS) List<@Valid LocatorBody> cited,
            @Size(max = MAX_LOCATORS) List<@Valid LocatorBody> supportingReads,
            @Size(max = MAX_CHECKS) List<@Valid CheckBody> checks,
            @Size(max = 20) List<@Size(max = 64) String> factIds) {
    }

    public record LocatorBody(@NotBlank @Size(max = 1024) String path, @Size(max = 300) String symbol,
            @Size(max = 32) String kind, @Size(max = 32) String range, @PositiveOrZero Integer line,
            @Size(max = MAX_TEXT) String text, @Size(max = 200) String tree, @Size(max = 64) String hash) {
    }

    public record CheckBody(@NotBlank @Size(max = 2000) String command, @Size(max = 2000) String requested,
            @Size(max = 4096) String cwd, @Size(max = 200) String tree, @Size(max = 500) String scope,
            @NotBlank @Size(max = 16) String status, @Size(max = 16) String testEvidence) {
    }

    public record CaptureResponse(UUID id, String projectKey) {
    }

    @PostMapping
    public CaptureResponse capture(@Valid @RequestBody CaptureRequest request,
            @RequestHeader(value = "X-AI-Orch-Client", required = false) String client) {
        ScannerProjectResolveResponse project = scanner.resolveProject(request.cwd(), null);
        WorkTrail trail = new WorkTrail(null, project.projectKey(), request.trailKey(), client,
                request.codeFingerprint(), request.requestText(), locators(request.edited()),
                locators(request.cited()), locators(request.supportingReads()), checks(request.checks()),
                request.factIds() == null ? List.of() : List.copyOf(request.factIds()), null, null);
        return new CaptureResponse(trails.upsert(trail), project.projectKey());
    }

    /** The project's newest trails (bounded), for retrieval in the prompt hook. Telemetry, never served as facts. */
    @GetMapping("/recent")
    public List<WorkTrail> recent(@RequestParam @Size(max = 4096) String cwd,
            @RequestParam(defaultValue = "100") int limit) {
        ScannerProjectResolveResponse project = scanner.resolveProject(cwd, null);
        return trails.recent(project.projectKey(), Math.max(1, Math.min(limit, WorkTrailRepository.KEEP_PER_PROJECT)));
    }

    private static List<Locator> locators(List<LocatorBody> body) {
        return body == null ? List.of() : body.stream()
                .map(l -> new Locator(l.path(), l.symbol() == null ? "" : l.symbol(), l.kind() == null ? "" : l.kind(),
                        l.range(), l.line(), l.text(), l.tree(), l.hash()))
                .toList();
    }

    private static List<Check> checks(List<CheckBody> body) {
        return body == null ? List.of() : body.stream()
                .map(c -> new Check(c.command(), c.requested(), c.cwd(), c.tree(), c.scope(), c.status(),
                        c.testEvidence()))
                .toList();
    }
}
