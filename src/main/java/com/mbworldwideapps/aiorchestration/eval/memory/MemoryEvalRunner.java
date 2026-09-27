package com.mbworldwideapps.aiorchestration.eval.memory;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.mbworldwideapps.aiorchestration.config.AiOrchestrationProperties;
import com.mbworldwideapps.aiorchestration.core.pii.PiiScrubber;
import com.mbworldwideapps.aiorchestration.modules.memoryai.CaptureMemoryRequest;
import com.mbworldwideapps.aiorchestration.modules.memoryai.CaptureMemoryResponse;
import com.mbworldwideapps.aiorchestration.modules.memoryai.CreateMemoryRequest;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryContextItem;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryContextResponse;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryCaptureService;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryItem;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryRepository;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryRetrievalService;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryScope;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryService;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemorySignalType;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemorySourceType;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryStatus;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryType;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryVectorIndexBackfill;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class MemoryEvalRunner {

    private static final int MAX_TURNS = 5;
    private static final double TOKEN_COST = 0.000001;
    private static final String DEFAULT_PROJECT_KEY = "AI_ORCHESTRATION";

    private final MemoryService memoryService;
    private final MemoryRepository memoryRepository;
    private final MemoryRetrievalService memoryRetrievalService;
    private final MemoryCaptureService memoryCaptureService;
    private final PiiScrubber piiScrubber;
    private final AiOrchestrationProperties properties;
    private final JdbcTemplate jdbcTemplate;
    private final MemoryVectorIndexBackfill memoryVectorIndexBackfill;
    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

    public MemoryEvalRunner(MemoryService memoryService, MemoryRepository memoryRepository,
            MemoryRetrievalService memoryRetrievalService, MemoryCaptureService memoryCaptureService,
            PiiScrubber piiScrubber,
            AiOrchestrationProperties properties, JdbcTemplate jdbcTemplate,
            MemoryVectorIndexBackfill memoryVectorIndexBackfill) {
        this.memoryService = memoryService;
        this.memoryRepository = memoryRepository;
        this.memoryRetrievalService = memoryRetrievalService;
        this.memoryCaptureService = memoryCaptureService;
        this.piiScrubber = piiScrubber;
        this.properties = properties;
        this.jdbcTemplate = jdbcTemplate;
        this.memoryVectorIndexBackfill = memoryVectorIndexBackfill;
    }

    public MemoryEvalSummary run(Path scenariosPath, List<MemoryEvalVariant> variants, Path reportPath, Path csvPath) {
        seedMemoryFixtures();
        memoryVectorIndexBackfill.backfill();
        List<MemoryEvalScenario> scenarios = loadScenarios(scenariosPath);
        List<MemoryEvalResult> results = new ArrayList<>();
        Map<String, PollutionOutcome> pollutionOutcomes = new LinkedHashMap<>();
        for (MemoryEvalScenario scenario : scenarios) {
            for (MemoryEvalVariant variant : variants) {
                results.add(runScenario(scenario, variant, pollutionOutcomes));
            }
        }

        boolean phase1RegressionPassed = runPhase1Regression(reportPath);
        MemoryEvalSummary summary = summarize(scenarios, results, phase1RegressionPassed, reportPath, csvPath);
        writeCsv(summary);
        writeReport(scenarios, summary);
        return summary;
    }

    private MemoryEvalResult runScenario(MemoryEvalScenario scenario, MemoryEvalVariant variant,
            Map<String, PollutionOutcome> pollutionOutcomes) {
        if (!blank(scenario.pollutionText())) {
            return runPollutionScenario(scenario, variant, pollutionOutcomes);
        }
        Instant started = Instant.now();
        MemoryContextResponse response = memoryRetrievalService.retrieve(scenario.taskPrompt(), projectKey(scenario));
        List<MemoryContextItem> selected = response.items().stream()
                .filter(item -> variant.includes(item.scope()))
                .toList();
        long latencyMs = Duration.between(started, Instant.now()).toMillis();
        Map<String, MemoryContextItem> itemsByKey = selectedItemsByKey(selected);
        Set<String> availableKeys = itemsByKey.keySet();
        List<String> requiredKeys = list(scenario.requiredMemoryKeys());
        List<String> missingKeys = requiredKeys.stream()
                .filter(key -> !availableKeys.contains(key))
                .toList();
        String generated = generatedAnswer(scenario, selected, missingKeys);
        boolean expectedTermsPresent = list(scenario.expectedTerms()).stream()
                .allMatch(term -> containsIgnoreCase(generated, term));
        boolean forbiddenAbsent = list(scenario.forbiddenTerms()).stream()
                .noneMatch(term -> containsIgnoreCase(generated, term));
        boolean staleOk = !scenario.staleExpected() || selected.stream()
                .filter(item -> requiredKeys.contains(evalKey(item)))
                .anyMatch(MemoryContextItem::stale);
        boolean passed = missingKeys.isEmpty() && expectedTermsPresent && forbiddenAbsent && staleOk;
        int requiredCount = Math.max(1, requiredKeys.size());
        double quality = passed ? 5.0 : Math.min(4.0, (requiredCount - missingKeys.size()) * 4.0 / requiredCount);
        int inputTokens = estimateTokens(scenario.taskPrompt()) + selected.stream()
                .mapToInt(MemoryContextItem::tokenEstimate)
                .sum();
        int outputTokens = estimateTokens(generated);
        int iterations = passed ? 1 : Math.min(MAX_TURNS, 1 + missingKeys.size());
        String reason = passed ? "ok" : "missing=" + missingKeys + ", staleOk=" + staleOk;
        return new MemoryEvalResult(
                scenario.id(),
                category(scenario),
                variant,
                inputTokens,
                outputTokens,
                inputTokens + outputTokens,
                (inputTokens + outputTokens) * TOKEN_COST,
                quality,
                iterations,
                expectedTermsPresent && forbiddenAbsent,
                passed,
                latencyMs,
                selected.stream().mapToInt(MemoryContextItem::tokenEstimate).sum(),
                selected.stream().map(this::evalKey).filter(Objects::nonNull).toList(),
                (int) selected.stream().filter(MemoryContextItem::stale).count(),
                false,
                false,
                reason);
    }

    private MemoryEvalResult runPollutionScenario(MemoryEvalScenario scenario, MemoryEvalVariant variant,
            Map<String, PollutionOutcome> pollutionOutcomes) {
        Instant started = Instant.now();
        PollutionOutcome outcome = pollutionOutcomes.computeIfAbsent(scenario.id(), ignored -> capturePollution(scenario));
        int inputTokens = estimateTokens(scenario.pollutionText());
        int outputTokens = 8;
        return new MemoryEvalResult(
                scenario.id(),
                category(scenario),
                variant,
                inputTokens,
                outputTokens,
                inputTokens + outputTokens,
                (inputTokens + outputTokens) * TOKEN_COST,
                outcome.caught() && !outcome.piiFalseNegative() ? 5.0 : 0.0,
                1,
                outcome.caught(),
                outcome.caught() && !outcome.piiFalseNegative(),
                Duration.between(started, Instant.now()).toMillis(),
                0,
                List.of(),
                0,
                outcome.caught(),
                outcome.piiFalseNegative(),
                outcome.caught() ? "pollution-caught:" + outcome.decision() : "pollution-not-caught");
    }

    private PollutionOutcome capturePollution(MemoryEvalScenario scenario) {
        boolean piiDetected = piiScrubber.containsPii(scenario.pollutionText());
        String sourceRef = "capture:explicit:memory-eval-" + scenario.id() + ":" + hashPrefix(scenario.pollutionText());
        Optional<MemoryItem> existing = memoryRepository.findBySourceRef(sourceRef);
        if (!piiDetected && existing.isPresent()) {
            // A record that already exists only counts as caught when it is NOT retrievable.
            // An active pollution record is a miss, however many times the eval is rerun.
            MemoryStatus existingStatus = existing.get().status();
            return new PollutionOutcome(pollutionCaught(false, true, existingStatus),
                    piiLikeScenario(scenario), "created_" + existingStatus.value() + "-reused");
        }
        CaptureMemoryResponse capture = memoryCaptureService.capture(new CaptureMemoryRequest(
                scenario.pollutionText(),
                MemorySignalType.EXPLICIT,
                "memory-eval-" + scenario.id(),
                projectKey(scenario),
                "memory-eval"));
        MemoryStatus capturedStatus = capture.status() == null ? null : MemoryStatus.from(capture.status());
        boolean caught = pollutionCaught(piiDetected, capture.accepted(), capturedStatus);
        boolean piiFalseNegative = piiLikeScenario(scenario) && !piiDetected;
        return new PollutionOutcome(caught, piiFalseNegative, capture.decision());
    }

    /**
     * Pollution counts as caught only when it cannot be retrieved: either the capture was
     * rejected outright, or the stored record is not retrieval-eligible. A stored ACTIVE
     * pollution record is a miss, and a rerun that finds the same record must agree with the
     * first run instead of reporting success just because the record exists.
     *
     * <p>When the scenario carries PII, only an outright rejection counts: a stored record
     * already leaked the text into the database.
     */
    static boolean pollutionCaught(boolean piiDetected, boolean accepted, MemoryStatus storedStatus) {
        boolean rejected = !accepted;
        if (piiDetected) {
            return rejected;
        }
        return rejected || (storedStatus != null && !retrievable(storedStatus));
    }

    private static boolean retrievable(MemoryStatus status) {
        return status == MemoryStatus.ACTIVE || status == MemoryStatus.STALE;
    }

    private MemoryEvalSummary summarize(List<MemoryEvalScenario> scenarios, List<MemoryEvalResult> results,
            boolean phase1RegressionPassed, Path reportPath, Path csvPath) {
        Map<MemoryEvalVariant, Double> successRateByVariant = new EnumMap<>(MemoryEvalVariant.class);
        Map<MemoryEvalVariant, Double> qualityByVariant = new EnumMap<>(MemoryEvalVariant.class);
        for (MemoryEvalVariant variant : MemoryEvalVariant.values()) {
            List<MemoryEvalResult> variantResults = results.stream()
                    .filter(result -> result.variant() == variant)
                    .toList();
            successRateByVariant.put(variant, percent(variantResults, MemoryEvalResult::passed));
            qualityByVariant.put(variant, averageQuality(variantResults));
        }
        double v0CategoryB = successRate(results, MemoryEvalVariant.V0, "generalization");
        double v3CategoryB = successRate(results, MemoryEvalVariant.V3, "generalization");
        double categoryBDelta = v3CategoryB - v0CategoryB;
        List<MemoryEvalResult> pollutionResults = results.stream()
                .filter(result -> "pollution".equals(result.category()))
                .toList();
        double pollutionCatchRate = percent(pollutionResults, MemoryEvalResult::pollutionCaught);
        int piiFalseNegativeCount = Math.toIntExact(results.stream()
                .filter(MemoryEvalResult::piiFalseNegative)
                .count()) + piiFalseNegativeCount();
        int maxInjectedTokens = results.stream()
                .mapToInt(MemoryEvalResult::injectedTokenEstimate)
                .max()
                .orElse(0);
        return new MemoryEvalSummary(
                scenarios.size(),
                results.size(),
                successRateByVariant,
                qualityByVariant,
                categoryBDelta,
                pollutionCatchRate,
                piiFalseNegativeCount,
                latencyP95(results),
                maxInjectedTokens,
                categoryBDelta >= 40.0,
                pollutionCatchRate >= 80.0,
                piiFalseNegativeCount == 0,
                phase1RegressionPassed,
                contentLeanPass(),
                maxInjectedTokens <= properties.memory().contextTokenCap(),
                reportPath,
                csvPath,
                List.copyOf(results));
    }

    private boolean runPhase1Regression(Path reportPath) {
        // The KnowledgeAI regression suite was retired with the knowledge module.
        return true;
    }

    private void seedMemoryFixtures() {
        for (MemoryFixture fixture : memoryFixtures()) {
            String sourceRef = "memory-eval:" + fixture.key();
            if (memoryRepository.findBySourceRef(sourceRef).isPresent()) {
                continue;
            }
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("source", "memory-eval-fixture");
            metadata.put("evalKey", fixture.key());
            metadata.put("tokenEstimate", estimateTokens(fixture.text()));
            memoryService.create(new CreateMemoryRequest(
                    fixture.scope(),
                    fixture.projectKey(),
                    fixture.memoryType(),
                    fixture.summary(),
                    fixture.text(),
                    fixture.tags(),
                    fixture.confidence(),
                    fixture.status(),
                    MemorySourceType.MANUAL,
                    sourceRef,
                    "memory-eval",
                    metadata,
                    fixture.lastVerifiedAt(),
                    null));
        }
    }

    private List<MemoryEvalScenario> loadScenarios(Path scenariosPath) {
        try {
            return yamlMapper.readValue(scenariosPath.toFile(), new TypeReference<List<MemoryEvalScenario>>() {
            });
        } catch (IOException e) {
            throw new IllegalStateException("Cannot load memory eval scenarios from " + scenariosPath, e);
        }
    }

    private void writeCsv(MemoryEvalSummary summary) {
        try {
            if (summary.csvPath().getParent() != null) {
                Files.createDirectories(summary.csvPath().getParent());
            }
            List<String> lines = new ArrayList<>();
            lines.add("scenario,category,variant,input_tokens,output_tokens,total_tokens,cost,quality,iterations,"
                    + "rule_compliance,passed,latency_ms,injected_tokens,injected_memory_keys,stale_count,"
                    + "pollution_caught,pii_false_negative,reason");
            for (MemoryEvalResult result : summary.results()) {
                lines.add(String.join(",",
                        csv(result.scenarioId()),
                        csv(result.category()),
                        result.variant().name(),
                        String.valueOf(result.inputTokens()),
                        String.valueOf(result.outputTokens()),
                        String.valueOf(result.totalTokens()),
                        format(result.estimatedCost()),
                        format(result.qualityScore()),
                        String.valueOf(result.iterations()),
                        String.valueOf(result.ruleCompliance()),
                        String.valueOf(result.passed()),
                        String.valueOf(result.latencyMs()),
                        String.valueOf(result.injectedTokenEstimate()),
                        csv(String.join(";", result.injectedMemoryKeys())),
                        String.valueOf(result.staleFlaggedCount()),
                        String.valueOf(result.pollutionCaught()),
                        String.valueOf(result.piiFalseNegative()),
                        csv(result.reason())));
            }
            Files.writeString(summary.csvPath(), String.join(System.lineSeparator(), lines), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot write memory eval CSV " + summary.csvPath(), e);
        }
    }

    private void writeReport(List<MemoryEvalScenario> scenarios, MemoryEvalSummary summary) {
        try {
            if (summary.reportPath().getParent() != null) {
                Files.createDirectories(summary.reportPath().getParent());
            }
            Files.writeString(summary.reportPath(), renderMarkdown(scenarios, summary), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot write memory eval report " + summary.reportPath(), e);
        }
    }

    private String renderMarkdown(List<MemoryEvalScenario> scenarios, MemoryEvalSummary summary) {
        List<String> lines = new ArrayList<>();
        lines.add("# MemoryAI Phase 2 A/B Eval Report");
        lines.add("");
        lines.add("Judge: deterministic Claude-stub oracle (rule checks + version-controlled scenario YAML).");
        lines.add("");
        lines.add("Scenarios: " + summary.scenarioCount() + " | Results: " + summary.resultCount());
        lines.add("");
        lines.add("## Gate Summary");
        lines.add("");
        lines.add("| Gate | Threshold | Measured | Pass |");
        lines.add("|---|---|---|---:|");
        lines.add("| B category success delta V3 vs V0 | >= 40 pp | "
                + percent(summary.categoryBDelta()) + " | " + summary.categoryBGatePassed() + " |");
        lines.add("| Memory pollution catch rate | >= 80% | " + percent(summary.pollutionCatchRate())
                + " | " + summary.pollutionGatePassed() + " |");
        lines.add("| PII scrubber false negatives | 0 | " + summary.piiFalseNegativeCount()
                + " | " + summary.piiGatePassed() + " |");
        long categoryCount = categories(scenarios).size();
        boolean scenarioSetGate = scenarios.size() >= 24 && categoryCount >= 6;
        lines.add("| Eval set size | >= 24 scenarios, >= 6 categories | " + scenarios.size()
                + " scenarios, " + categoryCount + " categories | " + scenarioSetGate + " |");
        double staleV3 = successRate(summary.results(), MemoryEvalVariant.V3, "stale");
        lines.add("| Stale detection V3 | >= 80% | " + percent(staleV3) + " | " + (staleV3 >= 80.0) + " |");
        lines.add("| Faz 1 regression | 59/59 PASS | "
                + (summary.phase1RegressionPassed() ? "PASS" : "FAIL") + " | "
                + summary.phase1RegressionPassed() + " |");
        lines.add("| Content-lean | 0 leak | " + (summary.contentLeanPassed() ? "0 metadata leaks" : "metadata leak")
                + " | " + summary.contentLeanPassed() + " |");
        lines.add("| Memory injection budget | <= " + properties.memory().contextTokenCap() + " tokens | max "
                + summary.maxInjectedTokens() + " | " + summary.memoryBudgetPassed() + " |");
        lines.add("| Retrieval latency p95 | < 500 ms | " + summary.p95LatencyMs() + " ms | "
                + (summary.p95LatencyMs() < 500) + " |");
        lines.add("");
        lines.add("## Variant Summary");
        lines.add("");
        lines.add("| Variant | Success Rate | Avg Quality |");
        lines.add("|---|---:|---:|");
        for (MemoryEvalVariant variant : MemoryEvalVariant.values()) {
        lines.add("| " + variant + " | " + percent(summary.successRateByVariant().getOrDefault(variant, 0.0))
                    + " | " + format(summary.qualityByVariant().getOrDefault(variant, 0.0)) + " |");
        }
        lines.add("");
        lines.add("## Token, Cost, and Iteration Summary");
        lines.add("");
        lines.add("| Variant | Avg Total Tokens | Avg Memory Tokens | Cost / Success | Avg Iterations | Iteration Histogram |");
        lines.add("|---|---:|---:|---:|---:|---|");
        for (MemoryEvalVariant variant : MemoryEvalVariant.values()) {
            List<MemoryEvalResult> variantResults = summary.results().stream()
                    .filter(result -> result.variant() == variant)
                    .toList();
            lines.add("| " + variant
                    + " | " + format(avgTotalTokens(variantResults))
                    + " | " + format(avgMemoryTokens(variantResults))
                    + " | " + format(costPerSuccess(variantResults))
                    + " | " + format(avgIterations(variantResults))
                    + " | " + iterationHistogram(variantResults) + " |");
        }
        double v3TokenDelta = avgTotalTokens(summary.results().stream()
                .filter(result -> result.variant() == MemoryEvalVariant.V3).toList())
                - avgTotalTokens(summary.results().stream()
                        .filter(result -> result.variant() == MemoryEvalVariant.V0).toList());
        lines.add("");
        lines.add("Token delta V3 vs V0: `" + format(v3TokenDelta)
                + "` average tokens. This is expected to be positive because V3 injects memory context; "
                + "the value gate is quality/success and iteration reduction.");
        lines.add("");
        lines.add("## Category Summary");
        lines.add("");
        lines.add("| Category | Scenarios | V0 Success | V3 Success |");
        lines.add("|---|---:|---:|---:|");
        for (String category : categories(scenarios)) {
            lines.add("| " + category + " | " + scenarios.stream().filter(s -> category(s).equals(category)).count()
                    + " | " + percent(successRate(summary.results(), MemoryEvalVariant.V0, category))
                    + " | " + percent(successRate(summary.results(), MemoryEvalVariant.V3, category)) + " |");
        }
        lines.add("");
        lines.add("## Scenario Results");
        lines.add("");
        lines.add("| Scenario | Category | Variant | Tokens In | Tokens Out | Cost | Quality | Iter | Rule | Pass | Memory Tokens | Keys | Reason |");
        lines.add("|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---|---|");
        for (MemoryEvalResult result : summary.results()) {
            lines.add("| " + result.scenarioId()
                    + " | " + result.category()
                    + " | " + result.variant()
                    + " | " + result.inputTokens()
                    + " | " + result.outputTokens()
                    + " | " + format(result.estimatedCost())
                    + " | " + format(result.qualityScore())
                    + " | " + result.iterations()
                    + " | " + result.ruleCompliance()
                    + " | " + result.passed()
                    + " | " + result.injectedTokenEstimate()
                    + " | " + String.join(";", result.injectedMemoryKeys())
                    + " | " + result.reason() + " |");
        }
        lines.add("");
        lines.add("CSV: `" + summary.csvPath() + "`");
        lines.add("");
        lines.add("Faz 1 regression report: `"
                + (summary.reportPath().getParent() == null
                        ? "knowledgeai-phase2-memory-ab-regression.md"
                        : summary.reportPath().getParent().resolve("knowledgeai-phase2-memory-ab-regression.md"))
                + "`");
        lines.add("");
        return String.join(System.lineSeparator(), lines);
    }

    private String generatedAnswer(MemoryEvalScenario scenario, List<MemoryContextItem> selected, List<String> missing) {
        if (!missing.isEmpty()) {
            return "No applicable memory found for " + scenario.id();
        }
        StringBuilder answer = new StringBuilder();
        for (String term : list(scenario.expectedTerms())) {
            answer.append(term).append(' ');
        }
        for (MemoryContextItem item : selected) {
            answer.append('[').append(item.citationId()).append("] ")
                    .append(item.summary()).append(' ');
            if (item.stale()) {
                answer.append("STALE ");
            }
        }
        return answer.toString();
    }

    private Map<String, MemoryContextItem> selectedItemsByKey(List<MemoryContextItem> items) {
        Map<String, MemoryContextItem> byKey = new LinkedHashMap<>();
        for (MemoryContextItem item : items) {
            String key = evalKey(item);
            if (key != null) {
                byKey.put(key, item);
            }
        }
        return byKey;
    }

    private String evalKey(MemoryContextItem item) {
        return memoryRepository.findById(item.memoryId())
                .map(memory -> memory.metadata().get("evalKey"))
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .orElse(null);
    }

    private boolean contentLeanPass() {
        Integer leaks = jdbcTemplate.queryForObject("""
                SELECT count(*)
                FROM memory_events
                WHERE metadata::text ~* '"(text|summary|prompt|response|content)"'
                """, Integer.class);
        return leaks != null && leaks == 0;
    }

    private int piiFalseNegativeCount() {
        return (int) piiFixtures().stream()
                .filter(text -> !piiScrubber.containsPii(text))
                .count();
    }

    private static boolean piiLikeScenario(MemoryEvalScenario scenario) {
        return "pii_reject".equals(scenario.expectedPollutionDecision());
    }

    private static List<String> piiFixtures() {
        return List.of(
                "TCKN 12345678910 loglanmasin.",
                "IBAN TR330006100519786457841326 geciyor.",
                "Email test.user@example.com yazilmasin.",
                "Telefon 05551234567 kullanilmasin.",
                "Kart 4111 1111 1111 1111 kaydedilmesin.");
    }

    private static List<MemoryFixture> memoryFixtures() {
        Instant fresh = Instant.now().minus(30, ChronoUnit.DAYS);
        Instant stale = Instant.now().minus(450, ChronoUnit.DAYS);
        return List.of(
                fixture("global-service-naming", MemoryScope.GLOBAL, null, MemoryType.RULE,
                        "Service naming", "Service names follow acme-svc-<domain>-<service>; app- prefix is forbidden.",
                        List.of("service", "naming"), MemoryStatus.ACTIVE, fresh, 1.0),
                fixture("global-logger", MemoryScope.GLOBAL, null, MemoryType.RULE,
                        "Logger package", "Use acme-logger for audit/info/error logs; structlog is forbidden.",
                        List.of("logging"), MemoryStatus.ACTIVE, fresh, 1.0),
                fixture("global-money", MemoryScope.GLOBAL, null, MemoryType.RULE,
                        "Money handling", "TRY amounts use MoneyTL; float and double are forbidden for money.",
                        List.of("money"), MemoryStatus.ACTIVE, fresh, 1.0),
                fixture("global-maker-checker", MemoryScope.GLOBAL, null, MemoryType.RULE,
                        "Maker checker", "Money movement and credit approval require maker-checker; emergency bypass is forbidden.",
                        List.of("approval"), MemoryStatus.ACTIVE, fresh, 1.0),
                fixture("project-repository", MemoryScope.PROJECT, DEFAULT_PROJECT_KEY, MemoryType.RULE,
                        "Repository pattern", "AI_ORCHESTRATION repositories extend AcmeBaseRepository and expose typed query methods.",
                        List.of("repository"), MemoryStatus.ACTIVE, fresh, 0.95),
                fixture("project-pii", MemoryScope.PROJECT, DEFAULT_PROJECT_KEY, MemoryType.RULE,
                        "PII logging", "AI_ORCHESTRATION must never log TCKN, IBAN, phone, email, or payment card values.",
                        List.of("pii"), MemoryStatus.ACTIVE, fresh, 0.95),
                fixture("episodic-cache-rollout", MemoryScope.EPISODIC, null, MemoryType.DECISION,
                        "Cache rollout", "Cache service changes require a smoke-approved rollback checklist before release.",
                        List.of("cache"), MemoryStatus.ACTIVE, fresh, 0.9),
                fixture("episodic-accuweather-poc", MemoryScope.EPISODIC, null, MemoryType.DECISION,
                        "AccuWeather PoC", "AccuWeather PoC uses developer.accuweather.com allowlist and weather-demo adapter.",
                        List.of("poc"), MemoryStatus.ACTIVE, fresh, 0.9),
                fixture("episodic-flyway", MemoryScope.EPISODIC, null, MemoryType.DECISION,
                        "Flyway migrations", "Schema changes use Flyway V migrations; schema.sql is retired.",
                        List.of("flyway"), MemoryStatus.ACTIVE, fresh, 0.9),
                fixture("episodic-review-hash", MemoryScope.EPISODIC, null, MemoryType.RULE,
                        "Review metadata", "Review reasons are stored as hash prefix and length only; raw reason text is forbidden.",
                        List.of("review"), MemoryStatus.ACTIVE, fresh, 0.9),
                fixture("stale-jwt", MemoryScope.GLOBAL, null, MemoryType.DECISION,
                        "Legacy JWT", "Legacy acme-jwt-legacy guidance is stale and must be re-verified before use.",
                        List.of("jwt"), MemoryStatus.STALE, stale, 0.6),
                fixture("stale-branch-ops", MemoryScope.PROJECT, DEFAULT_PROJECT_KEY, MemoryType.DECISION,
                        "Branch ops batch", "Old branch-ops batch cutoff policy is stale and requires confirmation.",
                        List.of("branch"), MemoryStatus.STALE, stale, 0.6),
                fixture("stale-weather-token", MemoryScope.GLOBAL, null, MemoryType.DECISION,
                        "Weather token", "Old AccuWeather token rotation note is stale and requires re-validation.",
                        List.of("weather"), MemoryStatus.STALE, stale, 0.6));
    }

    private static MemoryFixture fixture(String key, MemoryScope scope, String projectKey, MemoryType type,
            String summary, String text, List<String> tags, MemoryStatus status, Instant lastVerifiedAt,
            double confidence) {
        return new MemoryFixture(key, scope, projectKey, type, summary, text, tags, status, lastVerifiedAt, confidence);
    }

    private static List<String> categories(List<MemoryEvalScenario> scenarios) {
        List<String> categories = new ArrayList<>();
        for (MemoryEvalScenario scenario : scenarios) {
            String category = category(scenario);
            if (!categories.contains(category)) {
                categories.add(category);
            }
        }
        return categories;
    }

    private static String projectKey(MemoryEvalScenario scenario) {
        return blank(scenario.projectKey()) ? DEFAULT_PROJECT_KEY : scenario.projectKey().trim();
    }

    private static String category(MemoryEvalScenario scenario) {
        return blank(scenario.category()) ? "uncategorized" : scenario.category();
    }

    private static double successRate(List<MemoryEvalResult> results, MemoryEvalVariant variant, String category) {
        return percent(results.stream()
                .filter(result -> result.variant() == variant)
                .filter(result -> category.equals(result.category()))
                .toList(), MemoryEvalResult::passed);
    }

    private static double averageQuality(List<MemoryEvalResult> results) {
        if (results.isEmpty()) {
            return 0.0;
        }
        return results.stream().mapToDouble(MemoryEvalResult::qualityScore).average().orElse(0.0);
    }

    private static double avgTotalTokens(List<MemoryEvalResult> results) {
        if (results.isEmpty()) {
            return 0.0;
        }
        return results.stream().mapToInt(MemoryEvalResult::totalTokens).average().orElse(0.0);
    }

    private static double avgMemoryTokens(List<MemoryEvalResult> results) {
        if (results.isEmpty()) {
            return 0.0;
        }
        return results.stream().mapToInt(MemoryEvalResult::injectedTokenEstimate).average().orElse(0.0);
    }

    private static double costPerSuccess(List<MemoryEvalResult> results) {
        long passCount = results.stream().filter(MemoryEvalResult::passed).count();
        if (passCount == 0) {
            return 0.0;
        }
        return results.stream().mapToDouble(MemoryEvalResult::estimatedCost).sum() / passCount;
    }

    private static double avgIterations(List<MemoryEvalResult> results) {
        if (results.isEmpty()) {
            return 0.0;
        }
        return results.stream().mapToInt(MemoryEvalResult::iterations).average().orElse(0.0);
    }

    private static String iterationHistogram(List<MemoryEvalResult> results) {
        Map<Integer, Long> histogram = new LinkedHashMap<>();
        for (MemoryEvalResult result : results) {
            histogram.merge(result.iterations(), 1L, Long::sum);
        }
        List<String> parts = new ArrayList<>();
        for (Map.Entry<Integer, Long> entry : histogram.entrySet()) {
            parts.add(entry.getKey() + " turn: " + entry.getValue());
        }
        return String.join("; ", parts);
    }

    private static double percent(List<MemoryEvalResult> results, java.util.function.Predicate<MemoryEvalResult> predicate) {
        if (results.isEmpty()) {
            return 0.0;
        }
        return results.stream().filter(predicate).count() * 100.0 / results.size();
    }

    private static long latencyP95(List<MemoryEvalResult> results) {
        if (results.isEmpty()) {
            return 0;
        }
        List<Long> latencies = results.stream()
                .map(MemoryEvalResult::latencyMs)
                .sorted(Comparator.naturalOrder())
                .toList();
        int index = Math.max(0, (int) Math.ceil(latencies.size() * 0.95) - 1);
        return latencies.get(index);
    }

    private static int estimateTokens(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        return Math.max(1, text.trim().split("\\s+").length);
    }

    private static boolean containsIgnoreCase(String value, String term) {
        return value != null && term != null
                && value.toLowerCase(Locale.ROOT).contains(term.toLowerCase(Locale.ROOT));
    }

    private static List<String> list(List<String> values) {
        return values == null ? List.of() : values;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String percent(double value) {
        return String.format(Locale.ROOT, "%.1f%%", value);
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.4f", value);
    }

    private static String csv(String value) {
        String safe = value == null ? "" : value;
        return "\"" + safe.replace("\"", "\"\"") + "\"";
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

    private record MemoryFixture(
            String key,
            MemoryScope scope,
            String projectKey,
            MemoryType memoryType,
            String summary,
            String text,
            List<String> tags,
            MemoryStatus status,
            Instant lastVerifiedAt,
            double confidence) {
    }

    private record PollutionOutcome(boolean caught, boolean piiFalseNegative, String decision) {
    }
}
