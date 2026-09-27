package com.mbworldwideapps.aiorchestration.modules.plancompiler;

import java.util.List;
import java.util.UUID;
import java.util.ArrayList;
import java.util.Collections;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Authoritative, project-referenced machine plan consumed by the plan compiler.
 * V1 remains a separate compatibility contract.
 */
public record AnalystPlanSchemaV2(
        @Min(2) @Max(2) int schemaVersion,
        @NotNull UUID planId,
        @Min(1) int revision,
        @NotBlank @Size(max = 4_000) String goal,
        @NotEmpty @Size(max = 10) List<@NotNull @Valid Project> projects,
        @NotEmpty @Size(max = 100) List<@NotNull @Valid Target> targets,
        @NotNull @Size(max = 200) List<@NotNull @Valid Intent> intents,
        @NotEmpty @Size(max = 100) List<@NotNull @Valid Step> steps,
        @NotNull @Size(max = 50) List<@NotNull @Valid Coordination> coordination,
        @NotEmpty @Size(max = 100) List<@NotNull @Valid ValidationItem> validationPlan,
        @NotNull @Size(max = 50) List<@NotNull @Valid Risk> risks,
        @NotNull @Size(max = 100) List<@NotNull @Valid Citation> citations,
        @DecimalMin("0.0") @DecimalMax("1.0") double confidence) {

    public AnalystPlanSchemaV2 {
        projects = immutable(projects);
        targets = immutable(targets);
        intents = immutable(intents);
        steps = immutable(steps);
        coordination = immutable(coordination);
        validationPlan = immutable(validationPlan);
        risks = immutable(risks);
        citations = immutable(citations);
    }

    public record Project(
            @NotBlank @Pattern(regexp = ID_PATTERN) String projectRef,
            @NotBlank @Pattern(regexp = PROJECT_KEY_PATTERN) String projectKey,
            @NotNull @Valid Baseline baseline) {
    }

    public record Baseline(
            @NotBlank @Pattern(regexp = SHA_256_PATTERN) String repositoryFingerprint,
            @NotBlank @Pattern(regexp = GIT_OBJECT_PATTERN) String headCommit,
            @NotBlank @Pattern(regexp = SHA_256_PATTERN) String dirtyStateHash,
            @Size(max = 128) @Pattern(regexp = "(?s)\\S(?:.*\\S)?") String scannerRevision,
            @NotNull ScannerEvidenceStatus scannerEvidenceStatus) {
    }

    public record Target(
            @NotBlank @Pattern(regexp = ID_PATTERN) String targetId,
            @NotBlank @Pattern(regexp = ID_PATTERN) String projectRef,
            @NotNull Operation operation,
            @NotBlank @Size(max = 1_024) String repoRelativePath,
            @Size(max = 1_024) String previousPath,
            @Size(max = 1_024) @Pattern(regexp = "(?s)\\S(?:.*\\S)?") String proposedSymbol,
            @NotNull Language language,
            @NotNull SourceSet sourceSet,
            @NotNull ArtifactKind artifactKind,
            boolean generated,
            @NotNull @Size(max = 20) List<@NotBlank @Size(max = 256)
                    @Pattern(regexp = "(?s)\\S(?:.*\\S)?") String> roleHints,
            @NotNull @Size(max = 20) List<@NotBlank @Size(max = 512)
                    @Pattern(regexp = "(?s)\\S(?:.*\\S)?") String> annotationHints,
            @NotNull @Size(max = 50) List<@NotBlank @Pattern(regexp = ID_PATTERN) String> intentIds,
            @DecimalMin("0.0") @DecimalMax("1.0") double confidence,
            @NotNull @Size(max = 30) List<@NotBlank @Size(max = 1_000)
                    @Pattern(regexp = "(?s)\\S(?:.*\\S)?") String> evidence) {

        public Target {
            roleHints = immutable(roleHints);
            annotationHints = immutable(annotationHints);
            intentIds = immutable(intentIds);
            evidence = immutable(evidence);
        }
    }

    public record Intent(
            @NotBlank @Pattern(regexp = ID_PATTERN) String intentId,
            @NotNull IntentKind kind,
            @NotBlank @Size(max = 2_000) String description,
            @NotEmpty @Size(max = 100) List<@NotBlank @Pattern(regexp = ID_PATTERN) String> targetIds,
            @NotNull @Size(max = 100) List<@NotBlank @Size(max = 1_024)
                    @Pattern(regexp = "(?s)\\S(?:.*\\S)?") String> dependencies) {

        public Intent {
            targetIds = immutable(targetIds);
            dependencies = immutable(dependencies);
        }
    }

    public record Step(
            @NotBlank @Pattern(regexp = ID_PATTERN) String stepId,
            @NotBlank @Size(max = 2_000) String description,
            @NotEmpty @Size(max = 100) List<@NotBlank @Pattern(regexp = ID_PATTERN) String> targetIds,
            @NotNull @Size(max = 100) List<@NotBlank @Pattern(regexp = ID_PATTERN) String> intentIds) {

        public Step {
            targetIds = immutable(targetIds);
            intentIds = immutable(intentIds);
        }
    }

    public record Coordination(
            @NotBlank @Pattern(regexp = ID_PATTERN) String coordinationId,
            @NotEmpty @Size(max = 100) List<@NotBlank @Pattern(regexp = ID_PATTERN) String> producerTargetIds,
            @NotEmpty @Size(max = 100) List<@NotBlank @Pattern(regexp = ID_PATTERN) String> consumerTargetIds,
            @NotNull ContractKind contractKind,
            @NotNull CompatibilityStrategy compatibilityStrategy,
            @NotNull @Size(max = 100) List<@NotNull @Valid OrderEdge> applyBefore,
            @NotNull @Size(max = 100) List<@NotNull @Valid OrderEdge> deployBefore,
            boolean manualDeployment,
            @NotEmpty @Size(max = 100) List<@NotBlank @Pattern(regexp = ID_PATTERN) String> validationIds,
            @NotBlank @Size(max = 2_000) String rollout,
            @NotBlank @Size(max = 2_000) String rollback) {

        public Coordination {
            producerTargetIds = immutable(producerTargetIds);
            consumerTargetIds = immutable(consumerTargetIds);
            applyBefore = immutable(applyBefore);
            deployBefore = immutable(deployBefore);
            validationIds = immutable(validationIds);
        }
    }

    public record OrderEdge(
            @NotBlank @Pattern(regexp = ID_PATTERN) String fromTargetId,
            @NotBlank @Pattern(regexp = ID_PATTERN) String toTargetId) {
    }

    public record ValidationItem(
            @NotBlank @Pattern(regexp = ID_PATTERN) String validationId,
            @NotNull ValidationKind kind,
            @NotEmpty @Size(max = 100) List<@NotBlank @Pattern(regexp = ID_PATTERN) String> targetIds,
            @NotBlank @Size(max = 2_000) String description) {

        public ValidationItem {
            targetIds = immutable(targetIds);
        }
    }

    public record Risk(
            @NotBlank @Pattern(regexp = ID_PATTERN) String riskId,
            @NotBlank @Size(max = 2_000) String description,
            @NotNull RiskSeverity severity,
            @NotBlank @Size(max = 2_000) String mitigation) {
    }

    public record Citation(
            @NotBlank @Pattern(regexp = ID_PATTERN) String citationId,
            @NotNull CitationType type,
            @NotBlank @Size(max = 1_024) String referenceId) {
    }

    public enum ScannerEvidenceStatus {
        PRESENT,
        MISSING,
        STALE
    }

    public enum Operation {
        CREATE,
        MODIFY,
        DELETE,
        RENAME,
        MOVE
    }

    public enum Language {
        JAVA,
        KOTLIN,
        JAVASCRIPT,
        TYPESCRIPT,
        PYTHON,
        SQL,
        YAML,
        JSON,
        XML,
        MARKDOWN,
        SHELL,
        OTHER
    }

    public enum SourceSet {
        MAIN,
        TEST,
        GENERATED,
        UNKNOWN
    }

    public enum ArtifactKind {
        CODE,
        RESOURCE,
        CONFIG,
        MIGRATION,
        DOCUMENTATION,
        BUILD
    }

    public enum IntentKind {
        HTTP_MAPPING,
        INPUT_VALIDATION,
        DELEGATE_TO_SERVICE,
        BUSINESS_DECISION,
        DOMAIN_CALCULATION,
        PERSISTENCE,
        TRANSACTION,
        READ_MODIFY_WRITE,
        SHARED_STATE_MUTATION,
        ASYNC_CONCURRENCY,
        TRANSACTION_BOUNDARY,
        TEST_BEHAVIOR,
        CONFIG_CHANGE,
        SCHEMA_CHANGE,
        API_CONTRACT_CHANGE,
        OTHER
    }

    public enum ContractKind {
        API,
        SCHEMA,
        EVENT,
        DATA,
        BUILD,
        OTHER
    }

    public enum CompatibilityStrategy {
        CONSUMER_FIRST,
        PRODUCER_FIRST,
        EXPAND_CONTRACT,
        MANUAL_ONLY
    }

    public enum ValidationKind {
        UNIT_TEST,
        INTEGRATION_TEST,
        CONTRACT_TEST,
        E2E_TEST,
        CONCURRENCY_TEST,
        MIGRATION_TEST,
        STATIC_ANALYSIS,
        BUILD,
        MANUAL
    }

    public enum RiskSeverity {
        LOW,
        MEDIUM,
        HIGH
    }

    public enum CitationType {
        KNOWLEDGE,
        MEMORY,
        BASELINE,
        HUMAN
    }

    private static final String ID_PATTERN = "[A-Za-z0-9][A-Za-z0-9._:-]{0,127}";
    private static final String PROJECT_KEY_PATTERN = "[A-Z0-9][A-Z0-9_-]{0,127}";
    private static final String SHA_256_PATTERN = "[0-9a-f]{64}";
    private static final String GIT_OBJECT_PATTERN = "[0-9a-f]{40}|[0-9a-f]{64}";

    private static <T> List<T> immutable(List<T> values) {
        return values == null ? null : Collections.unmodifiableList(new ArrayList<>(values));
    }
}
