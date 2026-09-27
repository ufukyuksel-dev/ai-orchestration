package com.mbworldwideapps.aiorchestration.eval.judge;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.mbworldwideapps.aiorchestration.core.judge.JudgeRequest;
import com.mbworldwideapps.aiorchestration.core.judge.JudgeResult;
import com.mbworldwideapps.aiorchestration.core.judge.JudgeRubric;
import org.springframework.stereotype.Component;

@Component
public class JudgeCalibrationRunner {

    private static final double HYBRID_AGREEMENT_THRESHOLD = 85.0;
    private static final String HYBRID_ID = "hybrid";

    private final Map<String, JudgeRubric> judges;
    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

    public JudgeCalibrationRunner(List<JudgeRubric> judges) {
        this.judges = judges.stream()
                .collect(Collectors.toMap(JudgeRubric::id, Function.identity(), (left, right) -> left,
                        LinkedHashMap::new));
    }

    public JudgeCalibrationSummary run(Path suitePath, List<String> judgeIds, Path reportPath) {
        List<JudgeCalibrationScenario> scenarios = loadScenarios(suitePath);
        List<JudgeRubric> selectedJudges = selectJudges(judgeIds);
        List<JudgeCalibrationResult> results = new ArrayList<>();
        for (JudgeCalibrationScenario scenario : scenarios) {
            for (JudgeRubric judge : selectedJudges) {
                results.add(runScenario(scenario, judge));
            }
        }
        Map<String, Double> agreement = agreementByJudge(results);
        Map<String, JudgeCalibrationSummary.ConfusionMatrix> confusion = confusionByJudge(results);
        boolean hybridGate = agreement.getOrDefault(HYBRID_ID, 0.0) >= HYBRID_AGREEMENT_THRESHOLD;
        JudgeCalibrationSummary summary = new JudgeCalibrationSummary(
                scenarios.size(),
                results.size(),
                agreement,
                confusion,
                hybridGate,
                results,
                reportPath);
        writeReport(summary);
        return summary;
    }

    private JudgeCalibrationResult runScenario(JudgeCalibrationScenario scenario, JudgeRubric judge) {
        JudgeResult result = judge.judge(new JudgeRequest(
                scenario.id(),
                scenario.answer(),
                scenario.expectedConcepts(),
                scenario.requiredLiterals(),
                scenario.forbiddenTerms(),
                scenario.sourceChunkIds(),
                null));
        boolean agreed = result.passed() == scenario.expectedPass();
        return new JudgeCalibrationResult(
                scenario.id(),
                scenario.category(),
                judge.id(),
                scenario.expectedPass(),
                result.passed(),
                agreed,
                result.score(),
                String.join(",", result.reasons()));
    }

    private List<JudgeRubric> selectJudges(List<String> judgeIds) {
        List<String> ids = judgeIds == null || judgeIds.isEmpty()
                ? List.of("literal-match", "semantic", HYBRID_ID)
                : judgeIds;
        return ids.stream()
                .map(id -> {
                    JudgeRubric judge = judges.get(id);
                    if (judge == null) {
                        throw new IllegalArgumentException("Unsupported judge id: " + id + ". Available: "
                                + judges.keySet());
                    }
                    return judge;
                })
                .toList();
    }

    private List<JudgeCalibrationScenario> loadScenarios(Path suitePath) {
        try {
            return yamlMapper.readValue(suitePath.toFile(), new TypeReference<>() {
            });
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read judge calibration suite: " + suitePath, e);
        }
    }

    private Map<String, Double> agreementByJudge(List<JudgeCalibrationResult> results) {
        Map<String, Double> agreement = new LinkedHashMap<>();
        results.stream().map(JudgeCalibrationResult::judgeId).distinct().forEach(judgeId -> {
            List<JudgeCalibrationResult> judgeResults = results.stream()
                    .filter(result -> result.judgeId().equals(judgeId))
                    .toList();
            long agreed = judgeResults.stream().filter(JudgeCalibrationResult::agreed).count();
            agreement.put(judgeId, judgeResults.isEmpty() ? 0.0 : agreed * 100.0 / judgeResults.size());
        });
        return agreement;
    }

    private Map<String, JudgeCalibrationSummary.ConfusionMatrix> confusionByJudge(
            List<JudgeCalibrationResult> results) {
        Map<String, JudgeCalibrationSummary.ConfusionMatrix> confusion = new LinkedHashMap<>();
        results.stream().map(JudgeCalibrationResult::judgeId).distinct().forEach(judgeId -> {
            List<JudgeCalibrationResult> judgeResults = results.stream()
                    .filter(result -> result.judgeId().equals(judgeId))
                    .toList();
            int tp = (int) judgeResults.stream().filter(result -> result.expectedPass() && result.actualPass()).count();
            int tn = (int) judgeResults.stream().filter(result -> !result.expectedPass() && !result.actualPass()).count();
            int fp = (int) judgeResults.stream().filter(result -> !result.expectedPass() && result.actualPass()).count();
            int fn = (int) judgeResults.stream().filter(result -> result.expectedPass() && !result.actualPass()).count();
            confusion.put(judgeId, new JudgeCalibrationSummary.ConfusionMatrix(tp, tn, fp, fn));
        });
        return confusion;
    }

    private void writeReport(JudgeCalibrationSummary summary) {
        try {
            if (summary.reportPath().getParent() != null) {
                Files.createDirectories(summary.reportPath().getParent());
            }
            Files.writeString(summary.reportPath(), renderMarkdown(summary), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot write judge calibration report", e);
        }
    }

    private String renderMarkdown(JudgeCalibrationSummary summary) {
        List<String> lines = new ArrayList<>();
        lines.add("# Phase 4 F4.1.b Judge Calibration");
        lines.add("");
        lines.add("Scenario count: " + summary.scenarioCount());
        lines.add("Result count: " + summary.resultCount());
        lines.add("Hybrid gate: " + (summary.hybridAgreementGatePassed() ? "PASS" : "FAIL")
                + " (threshold >= " + HYBRID_AGREEMENT_THRESHOLD + "%)");
        lines.add("Drift rule: alert when agreement drops by more than 5 percentage points from the last approved baseline.");
        lines.add("");
        lines.add("## Agreement");
        lines.add("");
        lines.add("| Judge | Agreement | TP | TN | FP | FN |");
        lines.add("|---|---:|---:|---:|---:|---:|");
        for (var entry : summary.agreementByJudge().entrySet()) {
            JudgeCalibrationSummary.ConfusionMatrix matrix = summary.confusionByJudge().get(entry.getKey());
            lines.add("| %s | %.1f%% | %d | %d | %d | %d |".formatted(entry.getKey(), entry.getValue(),
                    matrix.truePositive(), matrix.trueNegative(), matrix.falsePositive(), matrix.falseNegative()));
        }
        lines.add("");
        lines.add("## Scenario Results");
        lines.add("");
        lines.add("| Scenario | Category | Judge | Expected | Actual | Agreed | Score | Reason |");
        lines.add("|---|---|---|---:|---:|---:|---:|---|");
        for (JudgeCalibrationResult result : summary.results()) {
            lines.add("| %s | %s | %s | %s | %s | %s | %d | %s |".formatted(
                    result.scenarioId(),
                    result.category(),
                    result.judgeId(),
                    result.expectedPass(),
                    result.actualPass(),
                    result.agreed(),
                    result.score(),
                    result.reason() == null || result.reason().isBlank() ? "ok" : result.reason()));
        }
        return String.join(System.lineSeparator(), lines) + System.lineSeparator();
    }
}
