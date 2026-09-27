package com.mbworldwideapps.aiorchestration.modules.rules;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.mbworldwideapps.aiorchestration.config.RulesProperties;
import com.mbworldwideapps.aiorchestration.modules.memoryai.RuleMemoryPromotionPort;
import com.mbworldwideapps.aiorchestration.modules.plancompiler.PlanCheckerContract;
import com.mbworldwideapps.aiorchestration.modules.plancompiler.PlanPathGlobMatcher;
import com.mbworldwideapps.aiorchestration.modules.plancompiler.PlanRuleCheckerRegistry;
import com.mbworldwideapps.aiorchestration.modules.plancompiler.PlanSymbolGlobMatcher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only domain service that activates, versions, or deprecates rules. Every
 * mutation is bound to a deterministic server preview and explicit human
 * evidence; a caller-provided boolean alone is never authority.
 */
@Service
public class RulePromotionService {

    public static final String WORKFLOW_CONTRACT_VERSION = "rule-authority/v2";
    private static final int MAX_SELECTOR_GROUPS = 256;
    private static final int MAX_SELECTOR_PREDICATES = 256;
    private static final int MAX_CHECKS = 256;

    private final RuleRepository repository;
    private final RuleMemoryPromotionPort promotionPort;
    private final RulesProperties properties;
    private final ObjectMapper canonicalMapper;
    private final Map<String, RuleDetector> detectorsByType;
    private final PlanRuleCheckerRegistry planCheckerRegistry;

    @Autowired
    public RulePromotionService(RuleRepository repository, RuleMemoryPromotionPort promotionPort,
            RulesProperties properties, ObjectMapper objectMapper, List<RuleDetector> detectors,
            PlanRuleCheckerRegistry planCheckerRegistry) {
        this.repository = repository;
        this.promotionPort = promotionPort;
        this.properties = properties;
        this.canonicalMapper = objectMapper.copy()
                .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
        this.detectorsByType = detectors.stream()
                .collect(Collectors.toUnmodifiableMap(RuleDetector::type, Function.identity()));
        this.planCheckerRegistry = Objects.requireNonNull(planCheckerRegistry, "PLAN checker registry");
    }

    /** Backward-compatible construction surface for existing tests and local adapters. */
    public RulePromotionService(RuleRepository repository, RuleMemoryPromotionPort promotionPort,
            RulesProperties properties, ObjectMapper objectMapper, List<RuleDetector> detectors) {
        this(repository, promotionPort, properties, objectMapper, detectors, new PlanRuleCheckerRegistry());
    }

    /** Produces the exact canonical payload and complete human-readable card. */
    public RulePromotionPreview preview(RulePromotionCandidate candidate) {
        return previewForMutation(candidate, Map.of("operation", "create"));
    }

    /** Preview bound to one existing logical rule and its exact current version. */
    public RulePromotionPreview previewVersion(UUID ruleId, int expectedCurrentVersion,
            String expectedCurrentContentHash, RulePromotionCandidate candidate) {
        if (ruleId == null || expectedCurrentVersion < 1 || !isSha256(expectedCurrentContentHash)) {
            throw new IllegalArgumentException("ruleId, positive expected version and current content hash are required");
        }
        Map<String, Object> mutation = new LinkedHashMap<>();
        mutation.put("expectedCurrentContentHash", expectedCurrentContentHash.trim());
        mutation.put("expectedCurrentVersion", expectedCurrentVersion);
        mutation.put("operation", "version_update");
        mutation.put("ruleId", ruleId.toString());
        return previewForMutation(candidate, mutation);
    }

    private RulePromotionPreview previewForMutation(RulePromotionCandidate candidate,
            Map<String, Object> mutation) {
        ValidatedCandidate validated = validateAndNormalize(candidate);
        String versionContentHash = sha256(writeCanonicalJson(versionPayload(validated)));
        Map<String, Object> approvalPayload = promotionApprovalPayload(validated, versionContentHash, mutation);
        String approvalContentHash = sha256(writeCanonicalJson(approvalPayload));
        String confirmationCard = writeConfirmationCard(approvalPayload);
        return new RulePromotionPreview(WORKFLOW_CONTRACT_VERSION, versionContentHash,
                approvalContentHash, confirmationCard, sha256(confirmationCard));
    }

    /** Creates a new logical rule at immutable version 1. */
    @Transactional
    RuleVersion promote(PromotionRequest request) {
        requireAuthorityEnabled();
        PreparedPromotion prepared = prepareCreate(request);
        List<String> affectedProjects = affectedProjects(prepared.candidate().projectKey());
        repository.lockEffectiveSequences(affectedProjects);
        RuleVersion replay = replayedPromotion(prepared.preview().approvalContentHash());
        if (replay != null) {
            return replay;
        }
        prepareOriginMemory(prepared);
        enforceActiveGlobLimit(prepared, null);
        UUID ruleId = UUID.randomUUID();
        Instant now = Instant.now();
        RuleVersion version = buildVersion(ruleId, 1, prepared, now);

        repository.insertDefinition(new RuleDefinition(ruleId, prepared.candidate().originMemoryId(),
                prepared.candidate().projectKey(), 1, RuleStatus.ACTIVE, now, now));
        repository.insertVersion(version);
        repository.insertBindings(rebind(prepared.bindings(), ruleId, 1));
        repository.insertSelectorGroups(rebindSelectors(prepared.selectorGroups(), ruleId, 1, now));
        repository.insertCheckDefinitions(rebindChecks(prepared.checks(), ruleId, 1, now));
        repository.insertScopeAssignments(List.of(RuleScopeAssignment.forProjectKey(
                UUID.randomUUID(), ruleId, 1, prepared.candidate().projectKey(), now)));
        repository.insertLifecycleEvent(lifecycleEvent(version, RuleLifecycleAction.PROMOTED, null,
                prepared.approval(), now));
        repository.bumpEffectiveSequences(prepared.candidate().projectKey() == null, affectedProjects);
        activateOriginMemory(prepared, ruleId, 1);
        return version;
    }

    /**
     * Appends a new immutable version and advances the definition pointer by
     * compare-and-set. A concurrent winner makes this transaction roll back.
     */
    @Transactional
    RuleVersion promoteVersion(UUID ruleId, int expectedCurrentVersion,
            String expectedCurrentContentHash, PromotionRequest request) {
        requireAuthorityEnabled();
        if (ruleId == null || expectedCurrentVersion < 1 || !isSha256(expectedCurrentContentHash)) {
            throw new IllegalArgumentException("ruleId, positive expected version and current content hash are required");
        }
        PreparedPromotion prepared = prepareVersion(ruleId, expectedCurrentVersion,
                expectedCurrentContentHash, request);
        List<String> affectedProjects = affectedProjects(prepared.candidate().projectKey());
        repository.lockEffectiveSequences(affectedProjects);
        RuleVersion replay = replayedPromotion(prepared.preview().approvalContentHash());
        if (replay != null) {
            if (!replay.ruleId().equals(ruleId) || replay.version() != expectedCurrentVersion + 1) {
                throw new IllegalStateException("promotion approval replay resolved to unexpected rule version");
            }
            return replay;
        }
        RuleDefinition definition = repository.findDefinition(ruleId)
                .orElseThrow(() -> new IllegalArgumentException("rule not found: " + ruleId));
        if (definition.status() != RuleStatus.ACTIVE || definition.currentVersion() != expectedCurrentVersion) {
            throw new IllegalStateException("stale current rule version");
        }
        RuleVersion current = repository.findVersion(ruleId, expectedCurrentVersion)
                .orElseThrow(() -> new IllegalStateException("current rule version row is missing"));
        if (!constantTimeEquals(current.contentHash(), expectedCurrentContentHash)) {
            throw new IllegalStateException("stale current rule content hash");
        }

        validateVersionOriginAndScope(definition, prepared.candidate());
        prepareOriginMemory(prepared);
        enforceActiveGlobLimit(prepared, ruleId);
        int nextVersion = Math.addExact(expectedCurrentVersion, 1);
        Instant now = Instant.now();
        RuleVersion version = buildVersion(ruleId, nextVersion, prepared, now);
        repository.insertVersion(version);
        repository.insertBindings(rebind(prepared.bindings(), ruleId, nextVersion));
        repository.insertSelectorGroups(rebindSelectors(prepared.selectorGroups(), ruleId, nextVersion, now));
        repository.insertCheckDefinitions(rebindChecks(prepared.checks(), ruleId, nextVersion, now));
        repository.insertScopeAssignments(List.of(RuleScopeAssignment.forProjectKey(
                UUID.randomUUID(), ruleId, nextVersion, prepared.candidate().projectKey(), now)));
        boolean advanced = repository.advanceDefinition(ruleId, expectedCurrentVersion, nextVersion,
                prepared.candidate().originMemoryId(), definition.projectKey(), RuleStatus.ACTIVE);
        if (!advanced) {
            throw new IllegalStateException("concurrent rule update detected");
        }
        repository.insertLifecycleEvent(lifecycleEvent(version, RuleLifecycleAction.PROMOTED, null,
                prepared.approval(), now));
        repository.bumpEffectiveSequences(prepared.candidate().projectKey() == null, affectedProjects);
        activateOriginMemory(prepared, ruleId, nextVersion);
        return version;
    }

    public RuleDeprecationPreview previewDeprecation(UUID ruleId, int expectedCurrentVersion,
            String expectedCurrentContentHash, String reason) {
        if (ruleId == null || expectedCurrentVersion < 1 || !isSha256(expectedCurrentContentHash)
                || isBlank(reason)) {
            throw new IllegalArgumentException("rule, current version/hash and deprecation reason are required");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("action", RuleLifecycleAction.DEPRECATED.value());
        payload.put("expectedCurrentContentHash", expectedCurrentContentHash.trim());
        payload.put("expectedCurrentVersion", expectedCurrentVersion);
        payload.put("reason", reason.trim());
        payload.put("ruleId", ruleId.toString());
        payload.put("workflowContractVersion", WORKFLOW_CONTRACT_VERSION);
        String approvalHash = sha256(writeCanonicalJson(payload));
        String card = writeConfirmationCard(payload);
        return new RuleDeprecationPreview(WORKFLOW_CONTRACT_VERSION, approvalHash, card, sha256(card));
    }

    @Transactional
    RuleDefinition deprecate(RuleDeprecationRequest request) {
        requireAuthorityEnabled();
        if (request == null) {
            throw new IllegalArgumentException("deprecation request is required");
        }
        RuleHumanApprovalEvidence approval = validateHumanEvidence(request.approval());
        requireCurrentContract(request.workflowContractVersion());
        RuleDeprecationPreview actual = previewDeprecation(request.ruleId(), request.expectedCurrentVersion(),
                request.expectedCurrentContentHash(), request.reason());
        requireHash("deprecation approval content", actual.approvalContentHash(),
                request.expectedApprovalContentHash());
        requireHash("deprecation confirmation card", actual.confirmationCardHash(),
                request.expectedConfirmationCardHash());
        RuleDefinition observedDefinition = repository.findDefinition(request.ruleId())
                .orElseThrow(() -> new IllegalArgumentException("rule not found: " + request.ruleId()));
        List<String> affectedProjects = affectedProjects(observedDefinition.projectKey());
        repository.lockEffectiveSequences(affectedProjects);
        RuleLifecycleEvent replay = repository.findLifecycleEvent(
                RuleLifecycleAction.DEPRECATED, actual.approvalContentHash()).orElse(null);
        if (replay != null) {
            if (!replay.ruleId().equals(request.ruleId())
                    || replay.ruleVersion() != request.expectedCurrentVersion()) {
                throw new IllegalStateException("deprecation approval replay resolved to unexpected rule version");
            }
            return repository.findDefinition(request.ruleId())
                    .orElseThrow(() -> new IllegalStateException("deprecated rule definition is missing"));
        }
        RuleDefinition definition = repository.findDefinition(request.ruleId())
                .orElseThrow(() -> new IllegalArgumentException("rule not found: " + request.ruleId()));
        if (definition.status() != RuleStatus.ACTIVE
                || definition.currentVersion() != request.expectedCurrentVersion()) {
            throw new IllegalStateException("stale current rule version");
        }
        RuleVersion current = repository.findVersion(request.ruleId(), request.expectedCurrentVersion())
                .orElseThrow(() -> new IllegalStateException("current rule version row is missing"));
        if (!constantTimeEquals(current.contentHash(), request.expectedCurrentContentHash())) {
            throw new IllegalStateException("stale current rule content hash");
        }
        if (!repository.deprecateDefinition(request.ruleId(), request.expectedCurrentVersion())) {
            throw new IllegalStateException("concurrent rule update detected");
        }
        Instant now = Instant.now();
        repository.insertLifecycleEvent(new RuleLifecycleEvent(UUID.randomUUID(), request.ruleId(),
                request.expectedCurrentVersion(), RuleLifecycleAction.DEPRECATED, approval.approvedBy().trim(),
                approval.humanTurnRef().trim(), sha256(approval.humanRawText()), request.reason().trim(),
                actual.approvalContentHash(), now));
        repository.bumpEffectiveSequences(definition.projectKey() == null, affectedProjects);
        return new RuleDefinition(definition.id(), definition.originMemoryId(), definition.projectKey(),
                definition.currentVersion(), RuleStatus.DEPRECATED, definition.createdAt(), now);
    }

    private void requireAuthorityEnabled() {
        if (!properties.enabled()) {
            throw new IllegalStateException("rule authority is disabled");
        }
    }

    private List<String> affectedProjects(String projectKey) {
        return projectKey == null ? List.of() : List.of(projectKey);
    }

    private PreparedPromotion prepareCreate(PromotionRequest request) {
        RulePromotionCandidate candidate = previewRequiredCandidate(request);
        return prepare(request, preview(candidate));
    }

    private RuleVersion replayedPromotion(String approvalContentHash) {
        RuleLifecycleEvent event = repository.findLifecycleEvent(
                RuleLifecycleAction.PROMOTED, approvalContentHash).orElse(null);
        if (event == null) {
            return null;
        }
        return repository.findVersion(event.ruleId(), event.ruleVersion())
                .orElseThrow(() -> new IllegalStateException("promoted rule version is missing"));
    }

    private PreparedPromotion prepareVersion(UUID ruleId, int expectedCurrentVersion,
            String expectedCurrentContentHash, PromotionRequest request) {
        RulePromotionCandidate candidate = previewRequiredCandidate(request);
        return prepare(request, previewVersion(ruleId, expectedCurrentVersion,
                expectedCurrentContentHash, candidate));
    }

    private RulePromotionCandidate previewRequiredCandidate(PromotionRequest request) {
        if (request == null || request.candidate() == null) {
            throw new IllegalArgumentException("promotion request and candidate are required");
        }
        return request.candidate();
    }

    private PreparedPromotion prepare(PromotionRequest request, RulePromotionPreview actual) {
        requireCurrentContract(request.workflowContractVersion());
        RuleHumanApprovalEvidence approval = validateHumanEvidence(request.approval());
        ValidatedCandidate candidate = validateAndNormalize(request.candidate());
        requireHash("approval content", actual.approvalContentHash(), request.expectedApprovalContentHash());
        requireHash("confirmation card", actual.confirmationCardHash(), request.expectedConfirmationCardHash());
        return new PreparedPromotion(candidate.candidate(), candidate.bindings(), candidate.selectorGroups(),
                candidate.checks(),
                candidate.detectorContract() == null ? null : candidate.detectorContract().contractHash(),
                actual, approval);
    }

    private ValidatedCandidate validateAndNormalize(RulePromotionCandidate raw) {
        if (raw == null || isBlank(raw.statement()) || raw.enforcement() == null || raw.provenance() == null) {
            throw new IllegalArgumentException("statement, enforcement and provenance are required");
        }
        RulePromotionCandidate candidate = new RulePromotionCandidate(raw.originMemoryId(),
                blankToNull(raw.expectedOriginContentHash()), blankToNull(raw.projectKey()), raw.statement().trim(),
                blankToNull(raw.rationale()), raw.enforcement(), raw.appliesAll(), blankToNull(raw.detectorType()),
                raw.detectorConfig(), raw.targets(), raw.selectorGroups(), raw.checks(), raw.provenance());
        validateInstruction(candidate);
        validateOrigin(candidate);
        List<RuleTargetBinding> bindings = validateAndBuildBindings(candidate);
        List<RuleSelectorGroup> selectorGroups = validateSelectors(candidate.selectorGroups());
        List<ValidatedCheck> checks = validateChecks(candidate.checks());
        DetectorContract detectorContract = validateDetector(candidate, bindings);
        if (candidate.enforcement() == RuleEnforcement.GATE && bindings.isEmpty() && selectorGroups.isEmpty()) {
            throw new IllegalArgumentException("gate rules require at least one explicit target binding or selector");
        }
        return new ValidatedCandidate(candidate, bindings, selectorGroups, checks, detectorContract);
    }

    /** Package-owned canonicalization hook for the audited direct-authoring facade. */
    RulePromotionCandidate canonicalCandidate(RulePromotionCandidate candidate) {
        return validateAndNormalize(candidate).candidate();
    }

    private void validateInstruction(RulePromotionCandidate candidate) {
        if (candidate.enforcement() != RuleEnforcement.INSTRUCTION) {
            return;
        }
        if (candidate.provenance() != RuleProvenance.DIRECT_HUMAN_POLICY
                || candidate.detectorType() != null
                || (candidate.detectorConfig() != null && !candidate.detectorConfig().isEmpty())
                || !candidate.selectorGroups().isEmpty() || !candidate.checks().isEmpty()) {
            throw new IllegalArgumentException("instructions require direct human policy without detectors, selectors or checks");
        }
        if (candidate.statement().getBytes(StandardCharsets.UTF_8).length > 16_384
                || (candidate.rationale() != null
                    && candidate.rationale().getBytes(StandardCharsets.UTF_8).length > 4_096)) {
            throw new IllegalArgumentException("instruction statement/rationale exceeds UTF-8 byte limit (16384/4096)");
        }
        if (candidate.appliesAll()) {
            if (!candidate.targets().isEmpty()) {
                throw new IllegalArgumentException("all-project/global instructions cannot have targets");
            }
        } else {
            if (candidate.projectKey() == null || candidate.targets().isEmpty()) {
                throw new IllegalArgumentException("module instructions require a project and directory targets");
            }
            // Delivered by path: a directory (dir/**, prefix match) or one file (exact match). A symbol binding only
            // names the method/class the rule is about; it always travels with its file.
            boolean hasPath = false;
            for (RulePromotionCandidate.TargetBindingRequest target : candidate.targets()) {
                if (target == null || target.targetKey() == null) {
                    throw new IllegalArgumentException("module instruction targets must be directory/** or file bindings");
                }
                if (target.kind() == BindingKind.PATH_GLOB && target.targetKey().endsWith("/**")) {
                    InstructionModulePaths.requireDirectory(
                            target.targetKey().substring(0, target.targetKey().length() - 3));
                    hasPath = true;
                } else if (target.kind() == BindingKind.FILE) {
                    InstructionModulePaths.requireDirectory(target.targetKey());
                    hasPath = true;
                } else if (target.kind() != BindingKind.SYMBOL) {
                    throw new IllegalArgumentException("module instruction targets must be directory/** or file bindings");
                }
            }
            if (!hasPath) {
                throw new IllegalArgumentException("a symbol-bound instruction also needs its file binding");
            }
        }
    }

    private void validateOrigin(RulePromotionCandidate candidate) {
        boolean direct = candidate.provenance() == RuleProvenance.DIRECT_HUMAN_POLICY;
        if (direct && (candidate.originMemoryId() != null || candidate.expectedOriginContentHash() != null)) {
            throw new IllegalArgumentException("direct human policy must not carry origin memory evidence");
        }
        if (!direct && candidate.originMemoryId() == null) {
            throw new IllegalArgumentException("memory-backed promotion requires originMemoryId");
        }
        if (candidate.originMemoryId() != null && !isSha256(candidate.expectedOriginContentHash())) {
            throw new IllegalArgumentException("memory-backed promotion requires a SHA-256 origin content hash");
        }
    }

    private List<RuleTargetBinding> validateAndBuildBindings(RulePromotionCandidate candidate) {
        if (candidate.targets().size() > properties.maxBindingsPerRule()) {
            throw new IllegalArgumentException("rule exceeds binding limit of " + properties.maxBindingsPerRule());
        }
        List<RuleTargetBinding> bindings = new ArrayList<>();
        for (RulePromotionCandidate.TargetBindingRequest target : candidate.targets()) {
            if (target == null || target.kind() == null || isBlank(target.targetKey())) {
                throw new IllegalArgumentException("binding requires kind and targetKey");
            }
            String key = target.targetKey();
            validateTargetKey(target.kind(), key);
            if (target.kind() == BindingKind.PATH_GLOB) {
                PlanPathGlobMatcher.matches(key, "validation-probe.java");
            } else if (target.kind() == BindingKind.FILE) {
                PlanPathGlobMatcher.matches(key, key);
            }
            if (target.kind() == BindingKind.PATH_GLOB && key.length() > properties.maxGlobLength()) {
                throw new IllegalArgumentException("glob binding exceeds length limit of " + properties.maxGlobLength());
            }
            bindings.add(new RuleTargetBinding(UUID.randomUUID(), null, 0, target.kind(), key, null));
        }
        long distinct = bindings.stream().map(binding -> binding.kind().value() + "\u0000" + binding.targetKey())
                .distinct().count();
        if (distinct != bindings.size()) {
            throw new IllegalArgumentException("duplicate target bindings are not allowed");
        }
        if (candidate.enforcement() == RuleEnforcement.GATE) {
            if (candidate.appliesAll()) {
                throw new IllegalArgumentException("gate rules cannot be applies-all");
            }
        }
        return bindings;
    }

    private List<RuleSelectorGroup> validateSelectors(List<RuleSelectorGroup> rawGroups) {
        if (rawGroups.size() > MAX_SELECTOR_GROUPS) {
            throw new IllegalArgumentException("rule exceeds selector group limit of " + MAX_SELECTOR_GROUPS);
        }
        long predicateCount = rawGroups.stream()
                .filter(java.util.Objects::nonNull)
                .mapToLong(group -> group.predicates().size())
                .sum();
        if (predicateCount > MAX_SELECTOR_PREDICATES) {
            throw new IllegalArgumentException(
                    "rule exceeds total selector predicate limit of " + MAX_SELECTOR_PREDICATES);
        }
        List<RuleSelectorGroup> groups = rawGroups.stream()
                .map(group -> {
                    if (group == null) {
                        throw new IllegalArgumentException("selector groups cannot contain null");
                    }
                    for (RuleSelectorPredicate predicate : group.predicates()) {
                        if (predicate.field() == SelectorField.SYMBOL
                                && predicate.operator() == SelectorOperator.GLOB) {
                            for (String pattern : predicate.values()) {
                                PlanSymbolGlobMatcher.matches(pattern, "validation.ProbeController");
                            }
                        }
                        if (predicate.field() == SelectorField.PATH
                                && predicate.operator() != SelectorOperator.PRESENT) {
                            for (String pattern : predicate.values()) {
                                if (predicate.operator() == SelectorOperator.GLOB) {
                                    PlanPathGlobMatcher.matches(pattern, "validation-probe.java");
                                } else {
                                    PlanPathGlobMatcher.matches(pattern, pattern);
                                }
                            }
                        }
                    }
                    return group;
                })
                .sorted(Comparator.comparing(RuleSelectorGroup::groupKey))
                .toList();
        if (groups.stream().map(RuleSelectorGroup::groupKey).distinct().count() != groups.size()) {
            throw new IllegalArgumentException("duplicate selector group keys are not allowed");
        }
        return groups;
    }

    private List<ValidatedCheck> validateChecks(List<RuleCheckSpec> rawChecks) {
        if (rawChecks.size() > MAX_CHECKS) {
            throw new IllegalArgumentException("rule exceeds check limit of " + MAX_CHECKS);
        }
        List<ValidatedCheck> checks = rawChecks.stream().map(check -> {
            if (check == null || check.phase() != RuleCheckPhase.PLAN) {
                throw new IllegalArgumentException("only PLAN checks can be promoted in authority v2");
            }
            RuleCheckSpec normalized = planCheckerRegistry.validateForPromotion(check);
            PlanCheckerContract contract = planCheckerRegistry.contract(normalized.checkerType())
                    .orElseThrow(() -> new IllegalArgumentException(
                            "unknown PLAN checker: " + normalized.checkerType()));
            if (normalized.severity() == RuleCheckSeverity.GATE && !contract.deterministic()) {
                throw new IllegalArgumentException("PLAN gate checks require a deterministic checker");
            }
            return new ValidatedCheck(normalized, contract);
        }).sorted(Comparator
                .comparing((ValidatedCheck check) -> check.spec().phase().value())
                .thenComparing(check -> check.spec().checkerType())
                .thenComparing(check -> check.spec().severity().value())
                .thenComparing(check -> writeCanonicalJson(check.spec().config())))
                .toList();
        long distinct = checks.stream().map(this::canonicalCheck).distinct().count();
        if (distinct != checks.size()) {
            throw new IllegalArgumentException("duplicate rule checks are not allowed");
        }
        return checks;
    }

    private DetectorContract validateDetector(RulePromotionCandidate candidate,
            List<RuleTargetBinding> bindings) {
        if (candidate.enforcement() == RuleEnforcement.GATE && candidate.detectorType() == null) {
            throw new IllegalArgumentException("gate rules require a deterministic detector");
        }
        if (candidate.detectorType() == null) {
            if (candidate.detectorConfig() != null && !candidate.detectorConfig().isEmpty()) {
                throw new IllegalArgumentException("detector config requires a detector type");
            }
            return null;
        }
        RuleDetector detector = detectorsByType.get(candidate.detectorType());
        if (detector == null) {
            throw new IllegalArgumentException("unknown detector type: " + candidate.detectorType());
        }
        if (candidate.detectorConfig() != null
                && writeCanonicalJson(candidate.detectorConfig()).getBytes(StandardCharsets.UTF_8).length
                        > properties.maxDetectorConfigBytes()) {
            throw new IllegalArgumentException(
                    "detector config exceeds " + properties.maxDetectorConfigBytes() + " bytes");
        }
        detector.validateConfig(candidate.detectorConfig());
        DetectorContract contract = detector.contract();
        boolean unsupportedBinding = bindings.stream()
                .anyMatch(binding -> !contract.promotableBindingKinds().contains(binding.kind()));
        if (unsupportedBinding) {
            throw new IllegalArgumentException("detector " + candidate.detectorType()
                    + " does not support one or more target binding kinds");
        }
        return contract;
    }

    private void validateTargetKey(BindingKind kind, String key) {
        int utf8Length = key.getBytes(StandardCharsets.UTF_8).length;
        if (utf8Length > 4_096) {
            throw new IllegalArgumentException("target binding exceeds 4096 UTF-8 bytes");
        }
        if (key.codePoints().anyMatch(codePoint -> codePoint == 0 || Character.isISOControl(codePoint))) {
            throw new IllegalArgumentException("target binding contains a control character");
        }
        if (kind != BindingKind.FILE && kind != BindingKind.PATH_GLOB) {
            return;
        }
        if (!key.equals(key.trim()) || key.startsWith("/") || key.startsWith("\\")
                || key.matches("^[A-Za-z]:.*")) {
            throw new IllegalArgumentException("file target must be repository-relative");
        }
        if (key.indexOf('\\') >= 0) {
            throw new IllegalArgumentException("file target must use canonical forward slashes");
        }
        for (String segment : key.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                throw new IllegalArgumentException("file target contains a non-canonical path segment");
            }
        }
    }

    private RuleHumanApprovalEvidence validateHumanEvidence(RuleHumanApprovalEvidence approval) {
        if (approval == null || isBlank(approval.approvedBy()) || isBlank(approval.humanTurnRef())
                || isBlank(approval.humanRawText()) || !approval.aiInterpretedAsApproval()) {
            throw new IllegalArgumentException("explicit human approval evidence is required");
        }
        if (!Double.isFinite(approval.agentConfidence()) || approval.agentConfidence() < 0.0
                || approval.agentConfidence() > 1.0) {
            throw new IllegalArgumentException("agentConfidence must be between 0 and 1");
        }
        return approval;
    }

    private void validateVersionOriginAndScope(RuleDefinition definition, RulePromotionCandidate candidate) {
        if (!Objects.equals(definition.projectKey(), candidate.projectKey())) {
            throw new IllegalArgumentException("rule scope cannot change during version promotion");
        }
        if (definition.originMemoryId() == null && candidate.originMemoryId() != null) {
            throw new IllegalArgumentException("direct rule cannot acquire an origin memory");
        }
        if (definition.originMemoryId() != null && candidate.originMemoryId() == null) {
            throw new IllegalArgumentException("memory-backed rule cannot become direct-human");
        }
    }

    private void enforceActiveGlobLimit(PreparedPromotion prepared, UUID excludedRuleId) {
        boolean hasGlob = prepared.bindings().stream()
                .anyMatch(binding -> binding.kind() == BindingKind.PATH_GLOB)
                || prepared.selectorGroups().stream().flatMap(group -> group.predicates().stream())
                        .anyMatch(predicate -> predicate.operator() == SelectorOperator.GLOB);
        if (!hasGlob) {
            return;
        }
        int projectedActiveGlobRules = repository.maxProjectedActiveGlobRules(
                prepared.candidate().projectKey(), excludedRuleId);
        if (projectedActiveGlobRules > properties.maxActiveGlobRulesPerProject()) {
            throw new IllegalStateException("active glob rule limit of "
                    + properties.maxActiveGlobRulesPerProject() + " reached for project scope");
        }
    }

    private RuleVersion buildVersion(UUID ruleId, int version, PreparedPromotion prepared, Instant now) {
        RulePromotionCandidate candidate = prepared.candidate();
        RuleHumanApprovalEvidence approval = prepared.approval();
        return new RuleVersion(ruleId, version, candidate.statement(), candidate.rationale(), candidate.enforcement(),
                candidate.appliesAll(), candidate.detectorType(), candidate.detectorConfig(),
                prepared.detectorContractHash(), prepared.preview().versionContentHash(), candidate.originMemoryId(),
                candidate.expectedOriginContentHash(),
                prepared.preview().approvalContentHash(), prepared.preview().confirmationCardHash(),
                sha256(approval.humanRawText()), WORKFLOW_CONTRACT_VERSION, candidate.provenance(),
                approval.approvedBy().trim(), now, approval.humanTurnRef().trim(), now);
    }

    private RuleLifecycleEvent lifecycleEvent(RuleVersion version, RuleLifecycleAction action, String reason,
            RuleHumanApprovalEvidence approval, Instant now) {
        return new RuleLifecycleEvent(UUID.randomUUID(), version.ruleId(), version.version(), action,
                approval.approvedBy().trim(), approval.humanTurnRef().trim(), version.humanRawTextHash(), reason,
                version.approvalContentHash(), now);
    }

    private void activateOriginMemory(PreparedPromotion prepared, UUID ruleId, int version) {
        if (prepared.candidate().originMemoryId() != null) {
            promotionPort.activatePromotedRule(prepared.candidate().originMemoryId(), ruleId, version,
                    prepared.candidate().expectedOriginContentHash(), prepared.preview().approvalContentHash(),
                    prepared.preview().confirmationCardHash());
        }
    }

    private void prepareOriginMemory(PreparedPromotion prepared) {
        if (prepared.candidate().originMemoryId() != null) {
            promotionPort.preparePromotionOrigin(prepared.candidate().originMemoryId(),
                    prepared.candidate().expectedOriginContentHash());
        }
    }

    private Map<String, Object> versionPayload(ValidatedCandidate validated) {
        RulePromotionCandidate candidate = validated.candidate();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("appliesAll", candidate.appliesAll());
        payload.put("detectorConfig", candidate.detectorConfig());
        payload.put("detectorContractHash", validated.detectorContract() == null
                ? null
                : validated.detectorContract().contractHash());
        payload.put("detectorType", candidate.detectorType());
        payload.put("enforcement", candidate.enforcement().value());
        payload.put("rationale", candidate.rationale());
        payload.put("checks", canonicalChecks(validated.checks()));
        payload.put("selectors", canonicalSelectors(validated.selectorGroups()));
        payload.put("statement", candidate.statement());
        payload.put("targets", canonicalBindings(validated.bindings()));
        return payload;
    }

    private Map<String, Object> promotionApprovalPayload(ValidatedCandidate validated, String versionContentHash,
            Map<String, Object> mutation) {
        RulePromotionCandidate candidate = validated.candidate();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("action", RuleLifecycleAction.PROMOTED.value());
        payload.put("mutation", mutation);
        payload.put("originContentHash", candidate.expectedOriginContentHash());
        payload.put("originMemoryId", candidate.originMemoryId() == null ? null : candidate.originMemoryId().toString());
        payload.put("projectKey", candidate.projectKey());
        payload.put("provenance", candidate.provenance().value());
        payload.put("versionContent", versionPayload(validated));
        payload.put("versionContentHash", versionContentHash);
        payload.put("workflowContractVersion", WORKFLOW_CONTRACT_VERSION);
        return payload;
    }

    private List<Map<String, String>> canonicalBindings(List<RuleTargetBinding> bindings) {
        return bindings.stream()
                .sorted(Comparator.comparing((RuleTargetBinding binding) -> binding.kind().value())
                        .thenComparing(RuleTargetBinding::targetKey))
                .map(binding -> Map.of("kind", binding.kind().value(), "targetKey", binding.targetKey()))
                .toList();
    }

    private List<Map<String, Object>> canonicalSelectors(List<RuleSelectorGroup> groups) {
        return groups.stream().sorted(Comparator.comparing(RuleSelectorGroup::groupKey))
                .map(group -> {
                    Map<String, Object> value = new LinkedHashMap<>();
                    value.put("groupKey", group.groupKey());
                    value.put("predicates", group.predicates().stream().map(predicate -> {
                        Map<String, Object> predicateValue = new LinkedHashMap<>();
                        predicateValue.put("field", predicate.field().value());
                        predicateValue.put("operator", predicate.operator().value());
                        predicateValue.put("polarity", predicate.polarity().value());
                        predicateValue.put("values", predicate.values());
                        return predicateValue;
                    }).toList());
                    return value;
                }).toList();
    }

    private List<Map<String, Object>> canonicalChecks(List<ValidatedCheck> checks) {
        return checks.stream().map(this::canonicalCheck).toList();
    }

    private Map<String, Object> canonicalCheck(ValidatedCheck check) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("checkerContractHash", check.contract().contractHash());
        value.put("checkerType", check.spec().checkerType());
        value.put("config", check.spec().config());
        value.put("configSchemaVersion", check.contract().configSchemaVersion());
        value.put("implementationVersion", check.contract().implementationVersion());
        value.put("phase", check.spec().phase().value());
        value.put("severity", check.spec().severity().value());
        return value;
    }

    private List<RuleTargetBinding> rebind(List<RuleTargetBinding> bindings, UUID ruleId, int version) {
        return bindings.stream()
                .sorted(Comparator.comparing((RuleTargetBinding binding) -> binding.kind().value())
                        .thenComparing(RuleTargetBinding::targetKey))
                .map(binding -> new RuleTargetBinding(binding.id(), ruleId, version, binding.kind(),
                        binding.targetKey(), null))
                .toList();
    }

    private List<RuleSelectorGroupDefinition> rebindSelectors(
            List<RuleSelectorGroup> groups, UUID ruleId, int version, Instant now) {
        List<RuleSelectorGroupDefinition> definitions = new ArrayList<>(groups.size());
        for (int groupOrdinal = 0; groupOrdinal < groups.size(); groupOrdinal++) {
            RuleSelectorGroup group = groups.get(groupOrdinal);
            UUID groupId = UUID.randomUUID();
            List<RuleSelectorPredicateDefinition> predicates = new ArrayList<>(group.predicates().size());
            for (int predicateOrdinal = 0; predicateOrdinal < group.predicates().size(); predicateOrdinal++) {
                RuleSelectorPredicate predicate = group.predicates().get(predicateOrdinal);
                predicates.add(new RuleSelectorPredicateDefinition(UUID.randomUUID(), groupId,
                        predicate.polarity(), predicate.field(), predicate.operator(), predicate.values(),
                        predicateOrdinal, now));
            }
            definitions.add(new RuleSelectorGroupDefinition(groupId, ruleId, version, group.groupKey(),
                    groupOrdinal, predicates, now));
        }
        return List.copyOf(definitions);
    }

    private List<RuleCheckDefinition> rebindChecks(
            List<ValidatedCheck> checks, UUID ruleId, int version, Instant now) {
        List<RuleCheckDefinition> definitions = new ArrayList<>(checks.size());
        for (int ordinal = 0; ordinal < checks.size(); ordinal++) {
            ValidatedCheck check = checks.get(ordinal);
            definitions.add(new RuleCheckDefinition(UUID.randomUUID(), ruleId, version,
                    check.spec().phase(), check.spec().checkerType(), check.contract().implementationVersion(),
                    check.contract().configSchemaVersion(), check.spec().severity(), check.spec().config(),
                    check.contract().contractHash(), ordinal, now));
        }
        return List.copyOf(definitions);
    }

    private String writeCanonicalJson(Object value) {
        try {
            return canonicalMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("rule payload is not serializable", e);
        }
    }

    private String writeConfirmationCard(Object value) {
        try {
            return canonicalMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("rule confirmation card is not renderable", e);
        }
    }

    private void requireCurrentContract(String value) {
        if (!WORKFLOW_CONTRACT_VERSION.equals(value)) {
            throw new IllegalStateException("workflow contract mismatch");
        }
    }

    private void requireHash(String label, String actual, String expected) {
        if (!isSha256(expected) || !constantTimeEquals(actual, expected)) {
            throw new IllegalStateException(label + " changed or is invalid");
        }
    }

    private static boolean constantTimeEquals(String actual, String expected) {
        if (actual == null || expected == null) {
            return false;
        }
        return MessageDigest.isEqual(actual.getBytes(StandardCharsets.US_ASCII),
                expected.getBytes(StandardCharsets.US_ASCII));
    }

    private static boolean isSha256(String value) {
        return value != null && value.matches("[0-9a-fA-F]{64}");
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String blankToNull(String value) {
        return isBlank(value) ? null : value.trim();
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private record ValidatedCheck(RuleCheckSpec spec, PlanCheckerContract contract) {
    }

    private record ValidatedCandidate(RulePromotionCandidate candidate, List<RuleTargetBinding> bindings,
            List<RuleSelectorGroup> selectorGroups, List<ValidatedCheck> checks,
            DetectorContract detectorContract) {
    }

    private record PreparedPromotion(RulePromotionCandidate candidate, List<RuleTargetBinding> bindings,
            List<RuleSelectorGroup> selectorGroups, List<ValidatedCheck> checks, String detectorContractHash,
            RulePromotionPreview preview, RuleHumanApprovalEvidence approval) {
    }
}
