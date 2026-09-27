package com.mbworldwideapps.aiorchestration.cli;

import java.nio.file.Path;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mbworldwideapps.aiorchestration.eval.judge.JudgeCalibrationRunner;
import com.mbworldwideapps.aiorchestration.eval.memory.MemoryEvalRunner;
import com.mbworldwideapps.aiorchestration.eval.memory.MemoryEvalVariant;
import com.mbworldwideapps.aiorchestration.modules.memoryai.EditMemoryRequest;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryReviewService;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryScope;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryService;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryStatus;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryVectorIndexBackfill;
import com.mbworldwideapps.aiorchestration.modules.memoryai.PromoteMemoryRequest;
import com.mbworldwideapps.aiorchestration.modules.scanner.CodeBaselineRepository;
import com.mbworldwideapps.aiorchestration.modules.scanner.ScanCodebaseRequest;
import com.mbworldwideapps.aiorchestration.modules.scanner.ScannerService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

@Component
public class Phase0CliRunner implements ApplicationRunner {

    private final MemoryEvalRunner memoryEvalRunner;
    private final JudgeCalibrationRunner judgeCalibrationRunner;
    private final MemoryService memoryService;
    private final MemoryReviewService memoryReviewService;
    private final MemoryVectorIndexBackfill memoryVectorIndexBackfill;
    private final ScannerService scannerService;
    private final CodeBaselineRepository codeBaselineRepository;
    private final ObjectMapper objectMapper;
    private final ConfigurableApplicationContext context;

    public Phase0CliRunner(MemoryEvalRunner memoryEvalRunner, JudgeCalibrationRunner judgeCalibrationRunner,
            MemoryService memoryService, MemoryReviewService memoryReviewService, ScannerService scannerService,
            CodeBaselineRepository codeBaselineRepository, ObjectMapper objectMapper,
            MemoryVectorIndexBackfill memoryVectorIndexBackfill, ConfigurableApplicationContext context) {
        this.memoryEvalRunner = memoryEvalRunner;
        this.judgeCalibrationRunner = judgeCalibrationRunner;
        this.memoryService = memoryService;
        this.memoryReviewService = memoryReviewService;
        this.memoryVectorIndexBackfill = memoryVectorIndexBackfill;
        this.scannerService = scannerService;
        this.codeBaselineRepository = codeBaselineRepository;
        this.objectMapper = objectMapper;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (!args.containsOption("cmd")) {
            return;
        }
        String command = args.getOptionValues("cmd").get(0);
        switch (command) {
            case "memory-list" -> {
                String scope = optional(args, "scope", null);
                String status = optional(args, "status", null);
                String projectKey = optional(args, "projectKey", null);
                var items = memoryService.list(
                        scope == null ? null : MemoryScope.from(scope),
                        status == null ? null : MemoryStatus.from(status),
                        projectKey);
                System.out.println(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(items));
            }
            case "memory-show" -> {
                java.util.UUID id = java.util.UUID.fromString(required(args, "id"));
                java.util.Map<String, Object> output = new java.util.LinkedHashMap<>();
                output.put("memory", memoryService.findById(id));
                output.put("events", memoryService.eventsForMemory(id));
                output.put("reviewQueue", memoryService.reviewQueueForMemory(id));
                System.out.println(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(output));
            }
            case "memory-approve" -> {
                var item = memoryReviewService.approve(java.util.UUID.fromString(required(args, "id")),
                        optional(args, "actor", "cli"), optional(args, "reason", null));
                System.out.println(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(item));
            }
            case "memory-reject" -> {
                var item = memoryReviewService.reject(java.util.UUID.fromString(required(args, "id")),
                        optional(args, "actor", "cli"), optional(args, "reason", null));
                System.out.println(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(item));
            }
            case "memory-edit" -> {
                var item = memoryReviewService.edit(java.util.UUID.fromString(required(args, "id")),
                        new EditMemoryRequest(
                                optional(args, "summary", null),
                                optional(args, "text", null),
                                splitCsv(optional(args, "tags", null)),
                                optionalDouble(args, "confidence", null),
                                optional(args, "actor", "cli"),
                                optional(args, "reason", null)));
                System.out.println(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(item));
            }
            case "memory-archive" -> {
                var item = memoryReviewService.archive(java.util.UUID.fromString(required(args, "id")),
                        optional(args, "actor", "cli"), optional(args, "reason", null));
                System.out.println(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(item));
            }
            case "memory-promote" -> {
                var item = memoryReviewService.promote(java.util.UUID.fromString(required(args, "id")),
                        new PromoteMemoryRequest(
                                MemoryScope.from(required(args, "scope")),
                                optional(args, "projectKey", null),
                                optional(args, "actor", "cli"),
                                optional(args, "reason", null)));
                System.out.println(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(item));
            }
            case "memory-search" -> {
                var items = memoryReviewService.search(required(args, "query"), optionalInt(args, "limit", 20));
                System.out.println(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(items));
            }
            case "memory-eval" -> {
                Path suite = Path.of(optional(args, "suite", "eval/memoryai/scenarios.yml"));
                Path report = Path.of(optional(args, "report", "output/reports/memoryai-phase2-ab-eval.md"));
                Path csv = Path.of(optional(args, "csv", "output/reports/memoryai-phase2-ab-comparison.csv"));
                var variants = parseVariants(optional(args, "variants", "V0,V1,V2,V3"));
                var summary = memoryEvalRunner.run(suite, variants, report, csv);
                System.out.println(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(summary));
                System.out.println("Report: " + report.toAbsolutePath());
                System.out.println("CSV: " + csv.toAbsolutePath());
            }
            case "judge-calibrate" -> {
                Path suite = Path.of(optional(args, "suite", "eval/judge/calibration-set.yml"));
                Path report = Path.of(optional(args, "report", "output/reports/phase4-f4.1b-judge-calibration.md"));
                var summary = judgeCalibrationRunner.run(suite,
                        splitCsv(optional(args, "judges", "literal-match,semantic,hybrid")), report);
                System.out.println(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(summary));
                System.out.println("Report: " + report.toAbsolutePath());
            }
            case "scan-codebase" -> {
                boolean progress = Boolean.parseBoolean(optional(args, "progress", "true"));
                double progressIntervalSeconds = optionalDouble(args, "progressIntervalSeconds", 7.0);
                if (progressIntervalSeconds <= 0) {
                    throw new IllegalArgumentException(
                            "--progressIntervalSeconds must be > 0, was " + progressIntervalSeconds);
                }
                // Pre-generate the run id so the polling thread can read this run's live metadata while the
                // worker scans; scan() honors a non-null scanRunId instead of allocating its own.
                java.util.UUID scanRunId = java.util.UUID.randomUUID();
                ScanCodebaseRequest request = new ScanCodebaseRequest(
                        required(args, "root"),
                        optional(args, "projectKey", null),
                        Boolean.parseBoolean(optional(args, "force", "false")),
                        optional(args, "providerOverride", null),
                        optional(args, "semanticModel", null),
                        Boolean.parseBoolean(optional(args, "forceReindex", "false")),
                        scanRunId,
                        splitCsv(optional(args, "includePaths", null)),
                        splitCsv(optional(args, "excludePaths", null)),
                        optionalInt(args, "maxSemanticFiles", null),
                        optionalInt(args, "maxSemanticSymbols", null),
                        optionalInt(args, "maxSemanticFlows", null),
                        optional(args, "semanticEnabled", null) == null
                                ? null : Boolean.valueOf(optional(args, "semanticEnabled", null)));
                CliScanProgressRunner progressRunner = new CliScanProgressRunner(
                        progress, (long) (progressIntervalSeconds * 1000), System.err);
                // Progress lines -> stderr; the result JSON below is the sole stdout payload and is printed
                // only on success (a worker failure rethrows out of run(), skipping the print, non-zero exit).
                var response = progressRunner.run(
                        () -> scannerService.scan(request),
                        () -> codeBaselineRepository.findRun(scanRunId));
                System.out.println(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(response));
            }
            default -> throw new IllegalArgumentException("Unsupported --cmd: " + command);
        }
        SpringApplicationExit.exit(context);
    }

    private static String required(ApplicationArguments args, String key) {
        if (!args.containsOption(key) || args.getOptionValues(key).isEmpty()) {
            throw new IllegalArgumentException("Missing --" + key);
        }
        return args.getOptionValues(key).get(0);
    }

    private static String optional(ApplicationArguments args, String key, String defaultValue) {
        if (!args.containsOption(key) || args.getOptionValues(key).isEmpty()) {
            return defaultValue;
        }
        return args.getOptionValues(key).get(0);
    }

    private static Integer optionalInt(ApplicationArguments args, String key, Integer defaultValue) {
        String value = optional(args, key, null);
        return value == null ? defaultValue : Integer.parseInt(value);
    }

    private static Double optionalDouble(ApplicationArguments args, String key, Double defaultValue) {
        String value = optional(args, key, null);
        return value == null ? defaultValue : Double.parseDouble(value);
    }

    private static java.util.List<String> splitCsv(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return java.util.Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(item -> !item.isBlank())
                .toList();
    }

    private static java.util.List<MemoryEvalVariant> parseVariants(String value) {
        return java.util.Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(item -> !item.isBlank())
                .map(String::toUpperCase)
                .map(MemoryEvalVariant::valueOf)
                .toList();
    }

    static final class SpringApplicationExit {
        private SpringApplicationExit() {
        }

        static void exit(ConfigurableApplicationContext context) {
            int code = org.springframework.boot.SpringApplication.exit(context, () -> 0);
            System.exit(code);
        }
    }
}
