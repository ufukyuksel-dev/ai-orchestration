package com.mbworldwideapps.aiorchestration.modules.mcp.server;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.validation.constraints.Size;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Memory for one step of a task, asked by the hooks (fail-open on their side): before or after the first build/test
 * of a request and on a new failure. Same selection as the task's cards at session start, restricted to the step's
 * card kinds and without the cards this session was already shown. The answer carries the outcome: {@code empty}
 * only when no memory of those kinds matches (the hooks then stop looking memory up for the request), {@code found}
 * when there are matches, even if all were shown before; {@code skipped} when memory was not searched (injection off,
 * no card kinds); a failed retrieval is an HTTP error, never {@code empty}.
 */
@RestController
@RequestMapping("/terminal/api/step-cards")
@ConditionalOnProperty(prefix = "ai-orchestration.mcp", name = "enabled", havingValue = "true", matchIfMissing = true)
public class StepCardController {

    static final Set<String> KINDS = Set.of("behavior", "navigation", "procedure", "field_mapping", "debug_finding",
            "change_point", "test_address");

    private final ScannerMcpTool scanner;
    private final AgentSessionMcpTool session;

    public StepCardController(ScannerMcpTool scanner, AgentSessionMcpTool session) {
        this.scanner = scanner;
        this.session = session;
    }

    @GetMapping
    public AgentSessionMcpTool.StepCards cards(@RequestParam @Size(max = 4096) String cwd,
            @RequestParam @Size(max = 2000) String query, @RequestParam @Size(max = 200) String kinds,
            @RequestParam(defaultValue = "") @Size(max = 4000) String exclude) {
        Set<String> wanted = split(kinds).stream().filter(KINDS::contains).collect(Collectors.toSet());
        if (wanted.isEmpty()) return new AgentSessionMcpTool.StepCards("skipped", List.of());
        String projectKey = scanner.resolveProject(cwd, null).projectKey();
        return session.stepCards(query, projectKey, wanted, Set.copyOf(split(exclude)));
    }

    private static List<String> split(String csv) {
        return Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
}
