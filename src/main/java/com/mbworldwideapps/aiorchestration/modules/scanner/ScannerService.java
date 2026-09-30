package com.mbworldwideapps.aiorchestration.modules.scanner;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseProblemException;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.resolution.types.ResolvedType;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JarTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import com.mbworldwideapps.aiorchestration.core.pii.PiiScrubber;
import com.mbworldwideapps.aiorchestration.modules.llmgateway.SourceSnippet;
import com.mbworldwideapps.aiorchestration.modules.llmgateway.DegradedReason;
import com.mbworldwideapps.aiorchestration.modules.llmgateway.GenerationRequest;
import com.mbworldwideapps.aiorchestration.modules.llmgateway.GenerationResponse;
import com.mbworldwideapps.aiorchestration.modules.llmgateway.LLMGateway;
import com.mbworldwideapps.aiorchestration.modules.llmgateway.LLMProviderTimeoutException;
import com.mbworldwideapps.aiorchestration.modules.llmgateway.ProviderOverrideSanitizer;
import com.mbworldwideapps.aiorchestration.modules.llmgateway.TokenEstimator;
import com.mbworldwideapps.aiorchestration.modules.mcp.server.McpAccessException;
import com.mbworldwideapps.aiorchestration.modules.mcp.server.McpAuditLogger;
import com.mbworldwideapps.aiorchestration.modules.mcp.server.McpClientContext;
import com.mbworldwideapps.aiorchestration.modules.mcp.server.McpClientContextHolder;
import com.mbworldwideapps.aiorchestration.modules.memoryai.CreateMemoryRequest;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryCodeLocator;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryCodeLocatorKind;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryCodeLocatorMetadata;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryCodeLocatorRelationship;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryItem;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryCodeLinkProjectionScheduler;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryRepository;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryScope;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryService;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemorySourceType;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryStatus;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryType;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryWriteGate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ScannerService implements ScannerAgentExecutor {

    private static final Logger log = LoggerFactory.getLogger(ScannerService.class);
    private static final Set<String> EXTERNAL_PROVIDERS = Set.of("claude", "codex", "ide-bridge");
    private static final int MAX_FORCE_REINDEX_CAPSULES = 20_000;
    private static final int MAX_INSIGHT_MEMORIES_PER_RUN = 3;
    private static final String SCANNER_INSIGHTS_WRITE_MEMORY_SCOPE = "scanner.insights.write-memory";
    private static final String FLOW_PROMPT_VERSION = "scanner-flow-v1";
    private static final Pattern PYTHON_CLASS = Pattern.compile("^(\\s*)class\\s+([A-Za-z_][A-Za-z0-9_]*)\\b.*:\\s*(?:#.*)?$");
    private static final Pattern PYTHON_FUNCTION = Pattern.compile(
            "^(\\s*)(?:async\\s+)?def\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*\\(");
    private static final ParserConfiguration D1_PARSER_CONFIGURATION = new ParserConfiguration()
            .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final ScannerProperties properties;
    private final ScannerFileStateRepository scannerFileStateRepository;
    private final MemoryService memoryService;
    private final MemoryRepository memoryRepository;
    private final CodeBaselineRepository codeBaselineRepository;
    private final LLMGateway llmGateway;
    private final ProviderOverrideSanitizer providerOverrideSanitizer;
    private final ScannerPayloadRedactor payloadRedactor;
    private final McpAuditLogger auditLogger;
    private final ScanRunStatusWriter scanRunStatusWriter;
    private final CodeBaselineVectorIndex codeBaselineVectorIndex;
    private final MemoryWriteGate memoryWriteGate;
    private final CodeFlowSeedBuilder codeFlowSeedBuilder;
    private final CodeFlowContextAssembler codeFlowContextAssembler;
    private final TransactionTemplate resolvedEdgeTransaction;
    private final ScannerProjectRootRegistry scannerProjectRootRegistry;
    private MemoryCodeLinkProjectionScheduler memoryCodeLinkScheduler;

    @Autowired
    public ScannerService(ScannerProperties properties, ScannerFileStateRepository scannerFileStateRepository,
            MemoryService memoryService, MemoryRepository memoryRepository, PiiScrubber piiScrubber,
            CodeBaselineRepository codeBaselineRepository, LLMGateway llmGateway,
            ProviderOverrideSanitizer providerOverrideSanitizer, ScannerPayloadRedactor payloadRedactor,
            McpAuditLogger auditLogger, ScanRunStatusWriter scanRunStatusWriter,
            CodeBaselineVectorIndex codeBaselineVectorIndex,
            ObjectProvider<MemoryWriteGate> memoryWriteGateProvider,
            ObjectProvider<PlatformTransactionManager> transactionManagerProvider,
            ObjectProvider<ScannerProjectRootRegistry> scannerProjectRootRegistryProvider) {
        this.properties = properties;
        this.scannerFileStateRepository = scannerFileStateRepository;
        this.memoryService = memoryService;
        this.memoryRepository = memoryRepository;
        this.codeBaselineRepository = codeBaselineRepository;
        this.llmGateway = llmGateway;
        this.providerOverrideSanitizer = providerOverrideSanitizer;
        this.payloadRedactor = payloadRedactor;
        this.auditLogger = auditLogger;
        this.scanRunStatusWriter = scanRunStatusWriter == null
                ? new ScanRunStatusWriter(codeBaselineRepository)
                : scanRunStatusWriter;
        this.codeBaselineVectorIndex = codeBaselineVectorIndex == null
                ? new NoOpCodeBaselineVectorIndex()
                : codeBaselineVectorIndex;
        this.memoryWriteGate = memoryWriteGateProvider == null
                ? MemoryWriteGate.alwaysActive()
                : memoryWriteGateProvider.getIfAvailable(MemoryWriteGate::alwaysActive);
        this.scannerProjectRootRegistry = scannerProjectRootRegistryProvider == null
                ? ScannerProjectRootRegistry.noop()
                : scannerProjectRootRegistryProvider.getIfAvailable(ScannerProjectRootRegistry::noop);
        this.codeFlowSeedBuilder = new CodeFlowSeedBuilder(codeBaselineRepository);
        this.codeFlowContextAssembler = new CodeFlowContextAssembler(codeBaselineRepository);
        PlatformTransactionManager transactionManager = transactionManagerProvider == null
                ? null
                : transactionManagerProvider.getIfAvailable();
        if (transactionManager == null) {
            this.resolvedEdgeTransaction = null;
        } else {
            this.resolvedEdgeTransaction = new TransactionTemplate(transactionManager);
            this.resolvedEdgeTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        }
    }

    public ScannerService(ScannerProperties properties, ScannerFileStateRepository scannerFileStateRepository,
            MemoryService memoryService, MemoryRepository memoryRepository, PiiScrubber piiScrubber,
            CodeBaselineRepository codeBaselineRepository, LLMGateway llmGateway,
            ProviderOverrideSanitizer providerOverrideSanitizer, ScannerPayloadRedactor payloadRedactor,
            McpAuditLogger auditLogger, ScanRunStatusWriter scanRunStatusWriter,
            CodeBaselineVectorIndex codeBaselineVectorIndex) {
        this(properties, scannerFileStateRepository, memoryService, memoryRepository, piiScrubber,
                codeBaselineRepository, llmGateway, providerOverrideSanitizer, payloadRedactor, auditLogger,
                scanRunStatusWriter, codeBaselineVectorIndex, null, null, null);
    }


    public ScannerService(ScannerProperties properties, ScannerFileStateRepository scannerFileStateRepository,
            MemoryService memoryService, MemoryRepository memoryRepository, PiiScrubber piiScrubber,
            CodeBaselineRepository codeBaselineRepository, LLMGateway llmGateway,
            ProviderOverrideSanitizer providerOverrideSanitizer, ScannerPayloadRedactor payloadRedactor,
            McpAuditLogger auditLogger) {
        this(properties, scannerFileStateRepository, memoryService, memoryRepository, piiScrubber,
                codeBaselineRepository, llmGateway, providerOverrideSanitizer, payloadRedactor, auditLogger, null,
                null);
    }

    public ScannerService(ScannerProperties properties, ScannerFileStateRepository scannerFileStateRepository,
            MemoryService memoryService, MemoryRepository memoryRepository, PiiScrubber piiScrubber) {
        this(properties, scannerFileStateRepository, memoryService, memoryRepository, piiScrubber,
                new NoOpCodeBaselineRepository(), null, new ProviderOverrideSanitizer(), null, null);
    }

    @Override
    // Orchestrator calls scanner from a transaction; D2 REQUIRES_NEW must read committed D1 graph rows.
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ScanCodebaseResponse scan(ScanCodebaseRequest request) {
        Instant started = Instant.now();
        Path root = normalizeRoot(request.rootPath());
        String projectKey = request.projectKey() == null || request.projectKey().isBlank()
                ? properties.projectKey()
                : request.projectKey().trim();
        boolean force = Boolean.TRUE.equals(request.force());
        UUID scanRunId = request.scanRunId() == null ? UUID.randomUUID() : request.scanRunId();
        SemanticRun semantic = semanticRun(request, scanRunId);
        ScanScope scope = ScanScope.from(request, root);
        SemanticScanBudget semanticBudget = SemanticScanBudget.from(request, properties.semantic());
        List<Path> files = discoverFiles(root, scope);
        Set<String> discoveredRelativePaths = files.stream()
                .map(file -> root.relativize(file).toString())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Map<String, Object> startMetadata = new LinkedHashMap<>();
        startMetadata.put("filesDiscovered", files.size());
        startMetadata.put("semanticEnabled", semantic.enabled());
        startMetadata.put("allowedProviders", properties.semantic().allowedProviders());
        startMetadata.put("resolvedEdgesEnabled", properties.resolvedEdges().enabled());
        startMetadata.putAll(scope.metadata());
        startMetadata.putAll(semanticBudget.metadata());
        codeBaselineRepository.startRun(scanRunId, projectKey, root.toString(), semantic.provider(),
                semantic.model(), semantic.promptVersion(), semantic.dataEgress(),
                startMetadata);
        int scanned = 0;
        int skipped = 0;
        int rejected = 0;
        int semanticFiles = 0;
        int reindexedCapsules = 0;
        int resolvedEdges = 0;
        int flowCapsules = 0;
        int semanticDisabledPreservedUnchanged = 0;
        List<UUID> memoryIds = new ArrayList<>();
        Set<UUID> obsoleteCapsuleVectorIds = new LinkedHashSet<>();
        ScannerRootPreparation rootPreparation = ScannerRootPreparation.clean(projectKey, root.toString());
        try {
            rootPreparation = scannerProjectRootRegistry.prepare(projectKey, root.toString());
            obsoleteCapsuleVectorIds.addAll(rootPreparation.removedCapsuleIds());
            removeObsoleteCapsuleVectors(projectKey, scanRunId, rootPreparation.removedCapsuleIds(),
                    "foreign_root_cleanup");
            for (Path file : files) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new IllegalStateException("scanner_interrupted");
                }
                String relativePath = root.relativize(file).toString();
                try {
                    byte[] bytes = Files.readAllBytes(file);
                    String contentHash = sha256(bytes);
                    boolean unchanged = scannerFileStateRepository.find(projectKey, relativePath)
                            .filter(state -> contentHash.equals(state.contentHash()))
                            .isPresent();
                    if (shouldSkipUnchangedFile(force, unchanged, semantic, request)) {
                        if (semanticDisabledPreservationSkip(unchanged, semantic)
                                && (force || semanticOverrideRequested(request))) {
                            semanticDisabledPreservedUnchanged++;
                        }
                        skipped++;
                        continue;
                    }
                    ScannerSourceDecoder.DecodedSource decoded = ScannerSourceDecoder.decode(bytes);
                    String content = decoded.content();
                    if (decoded.fallbackUsed()) {
                        writeDiagnostic(scanRunId, projectKey, relativePath, null, "WARN",
                                "scanner_charset_fallback",
                                "Source was not valid UTF-8; decoded with " + decoded.charsetName(),
                                Map.of("charset", decoded.charsetName()));
                    }
                    UUID fileId = fileId(projectKey, relativePath);
                    List<UUID> replacedCapsuleIds = codeBaselineRepository.findCapsuleIdsForFile(projectKey,
                            fileId);
                    codeBaselineRepository.deleteFileFacts(projectKey, fileId);
                    obsoleteCapsuleVectorIds.addAll(replacedCapsuleIds);
                    removeObsoleteCapsuleVectors(projectKey, scanRunId, replacedCapsuleIds,
                            "file_fact_replacement");
                    upsertCodeFile(scanRunId, projectKey, relativePath, contentHash, language(file));
                    boolean semanticForFile = semantic.enabled()
                            && hasSemanticCapsules(file)
                            && semanticBudget.canScanFile(semanticFiles);
                    scanSupportedFile(root, file, content, contentHash, projectKey, scanRunId, semantic,
                            semanticForFile, semanticBudget);
                    if (semanticForFile) {
                        semanticFiles++;
                    }
                    scannerFileStateRepository.upsert(new ScannerFileState(projectKey, relativePath, contentHash,
                            List.of(), Instant.now()));
                    scanned++;
                    if (scanned % 10 == 0) {
                        codeBaselineRepository.updateRunProgress(scanRunId, scanned,
                                Map.of("phase", "scanning", "semanticFilesScanned", semanticFiles));
                    }
                } catch (IOException exception) {
                    writeDiagnostic(scanRunId, projectKey, relativePath, null, "WARN", "scanner_io_error",
                            exception.getMessage(), Map.of("exception", exception.getClass().getSimpleName()));
                    rejected++;
                } catch (RuntimeException exception) {
                    writeDiagnostic(scanRunId, projectKey, relativePath, null, "ERROR", "scanner_file_error",
                            exception.getMessage(), Map.of("exception", exception.getClass().getSimpleName(),
                                    "semanticProvider", semantic.provider(), "dataEgress", semantic.dataEgress()));
                    rejected++;
                }
            }
            if (semanticDisabledPreservedUnchanged > 0) {
                String message = ("Semantic-disabled scanner scan preserved %d unchanged files without structural rebuild; "
                        + "run semantic-enabled or clear the baseline for a full rebuild")
                                .formatted(semanticDisabledPreservedUnchanged);
                log.warn("{} projectKey={} scanRunId={} force={} semanticOverrideRequested={}", message, projectKey,
                        scanRunId, force, semanticOverrideRequested(request));
                writeDiagnostic(scanRunId, projectKey, "", null, "WARN",
                        "scanner_force_semantic_disabled_skipped_unchanged", message,
                        Map.of("skippedUnchangedFiles", semanticDisabledPreservedUnchanged,
                                "force", force,
                                "semanticOverrideRequested", semanticOverrideRequested(request),
                                "semanticEnabled", false,
                                "capsulesPreserved", true));
            }
            if (request.forceReindexEnabled()) {
                reindexedCapsules = reindexCapsules(projectKey, root.toString(), scanRunId);
            }
            resolvedEdges = resolveEdgesAfterPrimaryScan(root, files, projectKey, scanRunId);
            int maxFlowSeeds = effectiveMaxFlowCapsules(request);
            List<CodeSemanticCapsuleRecord> writtenFlowCapsules = writeFlowCapsules(root, projectKey, scanRunId,
                    semantic, maxFlowSeeds, semanticBudget);
            flowCapsules = writtenFlowCapsules.size();
            writeInsightMemories(writtenFlowCapsules, semantic, memoryIds);
            boolean staleSweepSkipped = scope.scoped();
            if (staleSweepSkipped) {
                log.info("Scoped scanner scan skipped stale-file sweep: projectKey={} scanRunId={} includePaths={} excludePaths={}",
                        projectKey, scanRunId, scope.includePaths(), scope.excludePaths());
            } else {
                if (discoveredRelativePaths.isEmpty()) {
                    log.warn("Scanner discovered zero supported files for root={} projectKey={}; stale sweep will clear baseline rows for that root",
                            root, projectKey);
                    writeDiagnostic(scanRunId, projectKey, "", null, "WARN", "scanner_empty_discovered_sweep",
                            "Scanner discovered zero supported files before stale-file sweep",
                            Map.of("rootPath", root.toString(), "supportedExtensions",
                                    properties.supportedExtensions()));
                }
                List<UUID> staleFileCapsuleIds = codeBaselineRepository.findCapsuleIdsForRootNotIn(projectKey,
                        root.toString(), discoveredRelativePaths);
                codeBaselineRepository.deleteFilesForRootNotIn(projectKey, root.toString(), discoveredRelativePaths);
                obsoleteCapsuleVectorIds.addAll(staleFileCapsuleIds);
                removeObsoleteCapsuleVectors(projectKey, scanRunId, staleFileCapsuleIds,
                        "same_root_stale_file_sweep");
            }
            double skipRate = files.isEmpty() ? 0.0 : (double) skipped / (double) files.size();
            ScanCodebaseResponse response = new ScanCodebaseResponse(
                    root.toString(),
                    projectKey,
                    files.size(),
                    scanned,
                    skipped,
                    rejected,
                    memoryIds.size(),
                    skipRate,
                    Duration.between(started, Instant.now()).toMillis(),
                    List.copyOf(memoryIds),
                    scanRunId);
            Map<String, Object> completionMetadata = new LinkedHashMap<>();
            completionMetadata.put("filesDiscovered", response.filesDiscovered());
            completionMetadata.put("semanticFiles", semanticFiles);
            completionMetadata.put("semanticSelectionMode", properties.semantic().selectionMode().name());
            // semanticCandidatesSkippedLowValue is the real count from the budget snapshot (putAll below).
            completionMetadata.put("semanticSymbols", semanticBudget.symbolsUsed());
            completionMetadata.put("reindexedCapsules", reindexedCapsules);
            completionMetadata.put("resolvedEdges", resolvedEdges);
            completionMetadata.put("flowCapsules", flowCapsules);
            completionMetadata.put("effectiveMaxSemanticFlows", maxFlowSeeds);
            completionMetadata.put("semanticDisabledPreservedUnchanged", semanticDisabledPreservedUnchanged);
            completionMetadata.put("resolvedEdgesEnabled", properties.resolvedEdges().enabled());
            completionMetadata.put("staleSweepSkipped", staleSweepSkipped);
            completionMetadata.put("memoryIds", memoryIds.stream().map(UUID::toString).toList());
            completionMetadata.put("projectRoot", rootPreparation.rootPath());
            completionMetadata.put("foreignRootFilesDeleted", rootPreparation.foreignFilesDeleted());
            completionMetadata.put("foreignRootFileStatesDeleted", rootPreparation.foreignFileStatesDeleted());
            completionMetadata.put("orphanedSemanticAnchorsStaled",
                    rootPreparation.orphanedSemanticAnchorsStaled());
            completionMetadata.put("obsoleteCapsuleVectorsRequested", obsoleteCapsuleVectorIds.size());
            completionMetadata.putAll(scope.metadata());
            completionMetadata.putAll(semanticBudget.metadata());
            codeBaselineRepository.completeRun(scanRunId, "completed", response.filesDiscovered(),
                    response.filesScanned(), response.filesSkipped(), response.filesRejected(),
                    response.candidatesCreated(), completionMetadata);
            recheckMemoryCodeLinks(projectKey);
            return response;
        } catch (RuntimeException e) {
            scanRunStatusWriter.completeFailedRun(scanRunId, files.size(), scanned, skipped, rejected,
                    memoryIds.size(), Map.of("error", e.getClass().getSimpleName()));
            throw e;
        }
    }

    /** Optional: re-resolves memory→code links after a scan so memories whose code changed are marked stale. */
    @Autowired(required = false)
    void setMemoryCodeLinkScheduler(MemoryCodeLinkProjectionScheduler memoryCodeLinkScheduler) {
        this.memoryCodeLinkScheduler = memoryCodeLinkScheduler;
    }

    private void recheckMemoryCodeLinks(String projectKey) {
        if (memoryCodeLinkScheduler == null) {
            return;
        }
        try {
            memoryCodeLinkScheduler.linkAll(projectKey);
        } catch (RuntimeException e) {
            log.warn("Memory code-link recheck enqueue failed after completed scan: projectKey={}", projectKey, e);
        }
    }

    private void removeObsoleteCapsuleVectors(String projectKey, UUID scanRunId, List<UUID> capsuleIds,
            String cleanupPhase) {
        if (capsuleIds == null || capsuleIds.isEmpty()) {
            return;
        }
        try {
            codeBaselineVectorIndex.deleteAll(capsuleIds);
        } catch (RuntimeException e) {
            log.warn("Obsolete code capsule vector cleanup degraded: projectKey={} scanRunId={} phase={} "
                    + "capsuleCount={}", projectKey, scanRunId, cleanupPhase, capsuleIds.size(), e);
            writeDiagnostic(scanRunId, projectKey, "", null, "WARN", "scanner_vector_cleanup_degraded",
                    "Postgres capsule facts were removed but obsolete code vectors could not be deleted",
                    Map.of("capsuleCount", capsuleIds.size(),
                            "cleanupPhase", cleanupPhase,
                            "exception", e.getClass().getSimpleName()));
        }
    }

    private void scanSupportedFile(Path root, Path file, String content, String contentHash,
            String projectKey, UUID scanRunId, SemanticRun semantic, boolean semanticForFile,
            SemanticScanBudget semanticBudget) {
        String fileName = file.getFileName().toString();
        if (fileName.equals("pom.xml")) {
            return;
        }
        if (fileName.endsWith(".py")) {
            scanPythonFile(root, file, content, contentHash, projectKey, scanRunId);
            return;
        }
        Optional<KotlinSwiftParser.Language> mobileLanguage = KotlinSwiftParser.Language.of(fileName);
        if (mobileLanguage.isPresent()) {
            scanKotlinSwiftFile(root, file, content, contentHash, projectKey, scanRunId, mobileLanguage.get(),
                    semantic, semanticForFile, semanticBudget);
            return;
        }
        if (!fileName.endsWith(".java")) {
            return;
        }
        scanJavaFile(root, file, content, contentHash, projectKey, scanRunId, semantic, semanticForFile,
                semanticBudget);
    }

    private void scanPythonFile(Path root, Path file, String content, String contentHash,
            String projectKey, UUID scanRunId) {
        String relativePath = root.relativize(file).toString().replace(File.separatorChar, '/');
        UUID fileId = fileId(projectKey, relativePath);
        String moduleName = pythonModuleName(relativePath);
        String[] lines = content.split("\\R", -1);
        List<PythonClassScope> classes = new ArrayList<>();
        for (int index = 0; index < lines.length; index++) {
            String line = lines[index];
            if (line.isBlank() || line.stripLeading().startsWith("#")) {
                continue;
            }
            int indent = pythonIndent(line);
            while (!classes.isEmpty() && indent <= classes.getLast().indent()) {
                classes.removeLast();
            }
            Matcher classMatcher = PYTHON_CLASS.matcher(line);
            if (classMatcher.matches()) {
                String name = classMatcher.group(2);
                String owner = classes.isEmpty() ? moduleName : classes.getLast().fqn();
                String fqn = owner.isBlank() ? name : owner + "." + name;
                UUID symbolId = symbolId(projectKey, relativePath, "class", fqn, "");
                codeBaselineRepository.upsertSymbol(new CodeSymbolRecord(
                        symbolId, projectKey, fileId, "class", name, fqn, "", "class",
                        index + 1, pythonBlockEnd(lines, index, indent), contentHash, scanRunId,
                        Map.of("language", "python", "moduleName", moduleName)));
                if (!classes.isEmpty()) {
                    upsertDeclaresEdge(projectKey, scanRunId, relativePath,
                            classes.getLast().symbolId(), symbolId);
                }
                classes.add(new PythonClassScope(fqn, indent, symbolId));
                continue;
            }
            Matcher functionMatcher = PYTHON_FUNCTION.matcher(line);
            if (!functionMatcher.find()) {
                continue;
            }
            String name = functionMatcher.group(2);
            PythonClassScope owner = classes.isEmpty() ? null : classes.getLast();
            String fqn = owner == null
                    ? (moduleName.isBlank() ? name : moduleName + "#" + name)
                    : owner.fqn() + "#" + name;
            String kind = owner == null ? "function" : "method";
            UUID symbolId = symbolId(projectKey, relativePath, kind, fqn, "");
            codeBaselineRepository.upsertSymbol(new CodeSymbolRecord(
                    symbolId, projectKey, fileId, kind, name, fqn, "", kind,
                    index + 1, pythonBlockEnd(lines, index, indent), contentHash, scanRunId,
                    Map.of("language", "python", "moduleName", moduleName)));
            if (owner != null) {
                upsertDeclaresEdge(projectKey, scanRunId, relativePath, owner.symbolId(), symbolId);
            }
        }
    }

    private void upsertDeclaresEdge(String projectKey, UUID scanRunId, String relativePath,
            UUID ownerSymbolId, UUID declaredSymbolId) {
        codeBaselineRepository.upsertEdge(new CodeEdgeRecord(
                edgeId(projectKey, ownerSymbolId, "DECLARES", declaredSymbolId.toString()),
                projectKey, ownerSymbolId, declaredSymbolId, declaredSymbolId.toString(),
                "DECLARES", "syntactic", 1.0, scanRunId, Map.of("filePath", relativePath)));
    }

    private static int pythonIndent(String line) {
        int index = 0;
        int columns = 0;
        while (index < line.length()) {
            char value = line.charAt(index++);
            if (value == ' ') {
                columns++;
            } else if (value == '\t') {
                columns += 4 - (columns % 4);
            } else {
                break;
            }
        }
        return columns;
    }

    private static int pythonBlockEnd(String[] lines, int declarationIndex, int declarationIndent) {
        int headerEnd = pythonHeaderEnd(lines, declarationIndex);
        int end = headerEnd + 1;
        for (int index = headerEnd + 1; index < lines.length; index++) {
            String line = lines[index];
            if (line.isBlank() || line.stripLeading().startsWith("#")) {
                continue;
            }
            if (pythonIndent(line) <= declarationIndent) {
                break;
            }
            end = index + 1;
        }
        return end;
    }

    private static int pythonHeaderEnd(String[] lines, int declarationIndex) {
        int parentheses = 0;
        for (int index = declarationIndex; index < lines.length; index++) {
            String line = lines[index];
            for (int offset = 0; offset < line.length(); offset++) {
                char value = line.charAt(offset);
                if (value == '(' || value == '[' || value == '{') {
                    parentheses++;
                } else if ((value == ')' || value == ']' || value == '}') && parentheses > 0) {
                    parentheses--;
                }
            }
            if (parentheses == 0 && line.stripTrailing().endsWith(":")) {
                return index;
            }
        }
        return declarationIndex;
    }

    private static String pythonModuleName(String relativePath) {
        String normalized = relativePath.replace('\\', '/');
        if (normalized.endsWith(".py")) {
            normalized = normalized.substring(0, normalized.length() - 3);
        }
        if (normalized.startsWith("src/")) {
            normalized = normalized.substring(4);
        }
        if (normalized.endsWith("/__init__")) {
            normalized = normalized.substring(0, normalized.length() - "/__init__".length());
        } else if (normalized.equals("__init__")) {
            normalized = "";
        }
        return normalized.replace('/', '.');
    }

    private record PythonClassScope(String fqn, int indent, UUID symbolId) { }

    private static boolean hasSemanticCapsules(Path file) {
        String fileName = file.getFileName().toString();
        return fileName.endsWith(".java") || KotlinSwiftParser.Language.of(fileName).isPresent();
    }

    /** Kotlin/Swift: the same graph facts and capsules as a Java type, from the structural parser. */
    private void scanKotlinSwiftFile(Path root, Path file, String content, String contentHash,
            String projectKey, UUID scanRunId, KotlinSwiftParser.Language language, SemanticRun semantic,
            boolean semanticForFile, SemanticScanBudget semanticBudget) {
        String relativePath = root.relativize(file).toString();
        UUID fileId = fileId(projectKey, relativePath);
        KotlinSwiftParser.ParsedFile parsed = KotlinSwiftParser.parse(language, content);
        String[] lines = content.split("\\R", -1);
        // Symbols first, edges after: a Swift extension may precede its type in the file, and an edge needs
        // both ends stored.
        Map<String, UUID> typeIds = new LinkedHashMap<>();
        List<CodeSymbolRecord> symbols = new ArrayList<>();
        for (KotlinSwiftParser.Declaration declaration : parsed.declarations()) {
            UUID symbolId = mobileSymbolId(projectKey, relativePath, declaration);
            if (declaration.isType()) {
                typeIds.putIfAbsent(declaration.fqn(), symbolId);
            }
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("language", language.id);
            metadata.put("annotations", declaration.annotations());
            if (declaration.isType()) {
                metadata.put("packageName", parsed.packageName());
                metadata.put("supertypes", declaration.supertypes());
            } else {
                metadata.put("returnType", declaration.returnType());
                if (!declaration.receiverType().isBlank()) {
                    metadata.put("receiverType", declaration.receiverType());
                }
                if (!declaration.httpCall().isBlank()) {
                    metadata.put("httpCall", declaration.httpCall());
                }
            }
            CodeSymbolRecord symbol = new CodeSymbolRecord(symbolId, projectKey, fileId, declaration.kind(),
                    declaration.name(), declaration.fqn(), declaration.signature(), declaration.role(),
                    declaration.startLine(), declaration.endLine(), contentHash, scanRunId, metadata);
            codeBaselineRepository.upsertSymbol(symbol);
            symbols.add(symbol);
        }
        Map<String, Integer> methodBudgets = new LinkedHashMap<>();
        for (int index = 0; index < symbols.size(); index++) {
            KotlinSwiftParser.Declaration declaration = parsed.declarations().get(index);
            CodeSymbolRecord symbol = symbols.get(index);
            UUID symbolId = symbol.id();
            UUID ownerId = typeIds.get(declaration.ownerFqn());
            if (ownerId != null && !ownerId.equals(symbolId)) {
                upsertDeclaresEdge(projectKey, scanRunId, relativePath, ownerId, symbolId);
            }
            for (String annotation : declaration.annotations()) {
                upsertEdge(scanRunId, projectKey, symbolId, "ANNOTATED_WITH", annotation, 0.95,
                        Map.of("filePath", relativePath, "line", declaration.startLine()));
            }
            String snippet = snippet(String.join("\n", java.util.Arrays.asList(lines).subList(
                    Math.min(lines.length, declaration.startLine() - 1),
                    Math.min(lines.length, declaration.endLine()))));
            if (declaration.isType()) {
                List<String> injectedTypes = mobileInjectedTypes(declaration);
                for (String injectedType : injectedTypes) {
                    upsertEdge(scanRunId, projectKey, symbolId, "INJECTS", injectedType, 0.65,
                            Map.of("filePath", relativePath));
                }
                if (reserveSemanticTarget(semanticForFile, semanticBudget)) {
                    List<String> memberNames = parsed.declarations().stream()
                            .filter(member -> declaration.fqn().equals(member.ownerFqn()))
                            .map(KotlinSwiftParser.Declaration::name)
                            .distinct()
                            .limit(8)
                            .toList();
                    maybeWriteSemanticCapsule(true, semantic, new SemanticTarget(symbolId, fileId, projectKey,
                            "class", declaration.name(), declaration.fqn(), relativePath, declaration.startLine(),
                            declaration.endLine(), mobileTypeFacts(symbol, language, memberNames, declaration,
                                    injectedTypes), snippet), semanticBudget);
                }
                continue;
            }
            // Name-only like Java D1: method-style calls; constructors and composables resolve in the D2 pass.
            List<String> calls = declaration.calls().stream()
                    .map(KotlinSwiftParser.Call::name)
                    .filter(name -> Character.isLowerCase(name.charAt(0)))
                    .distinct()
                    .limit(40)
                    .toList();
            for (String call : calls) {
                upsertEdge(scanRunId, projectKey, symbolId, "CALLS", call, 0.45,
                        Map.of("filePath", relativePath, "resolution", "name-only"));
            }
            if (semanticForFile) {
                SemanticTarget target = new SemanticTarget(symbolId, fileId, projectKey, "method", declaration.name(),
                        declaration.fqn(), relativePath, declaration.startLine(), declaration.endLine(),
                        mobileFunctionFacts(symbol, declaration, calls), snippet);
                int methodBudget = methodBudgets.getOrDefault(declaration.ownerFqn(), 0);
                if (!methodHighValue(declaration.name(), declaration.annotations(), calls)) {
                    semanticBudget.recordSkippedLowValue();
                    writeExtractiveDefaultCapsule(semantic, target, semanticBudget);
                } else if (methodBudget < properties.semantic().maxMethodsPerFile()
                        && reserveSemanticTarget(semanticForFile, semanticBudget)) {
                    maybeWriteSemanticCapsule(true, semantic, target, semanticBudget);
                    methodBudgets.put(declaration.ownerFqn(), methodBudget + 1);
                }
            }
        }
    }

    private static UUID mobileSymbolId(String projectKey, String relativePath,
            KotlinSwiftParser.Declaration declaration) {
        return symbolId(projectKey, relativePath, declaration.kind(), declaration.fqn(), declaration.signature());
    }

    private static List<String> mobileInjectedTypes(KotlinSwiftParser.Declaration declaration) {
        return declaration.dependencies().stream()
                .filter(KotlinSwiftParser.Dependency::injected)
                .map(KotlinSwiftParser.Dependency::type)
                .filter(KotlinSwiftParser::projectTypeCandidate)
                .distinct()
                .toList();
    }

    private static String mobileTypeFacts(CodeSymbolRecord symbol, KotlinSwiftParser.Language language,
            List<String> memberNames, KotlinSwiftParser.Declaration declaration, List<String> injectedTypes) {
        return """
                role=%s
                fqn=%s
                language=%s
                annotations=%s
                members=%s
                supertypes=%s
                injectedTypes=%s
                """.formatted(symbol.role(), symbol.fqn(), language.id, declaration.annotations(), memberNames,
                declaration.supertypes(), injectedTypes).trim();
    }

    private static String mobileFunctionFacts(CodeSymbolRecord symbol, KotlinSwiftParser.Declaration declaration,
            List<String> calls) {
        String facts = """
                fqn=%s
                signature=%s
                returnType=%s
                annotations=%s
                calls=%s
                sideEffectHints=%s
                """.formatted(symbol.fqn(), symbol.signature(), declaration.returnType(), declaration.annotations(),
                calls, sideEffectHints(calls)).trim();
        return declaration.httpCall().isBlank() ? facts : facts + "\nhttpCall=" + declaration.httpCall();
    }

    private void scanJavaFile(Path root, Path file, String content, String contentHash,
            String projectKey, UUID scanRunId, SemanticRun semantic, boolean semanticForFile,
            SemanticScanBudget semanticBudget) {
        CompilationUnit compilationUnit = parseD1CompilationUnit(content);
        String relativePath = root.relativize(file).toString();
        UUID fileId = fileId(projectKey, relativePath);
        String packageName = compilationUnit.getPackageDeclaration()
                .map(declaration -> declaration.getNameAsString())
                .orElse("");
        compilationUnit.getTypes().stream()
                .filter(ClassOrInterfaceDeclaration.class::isInstance)
                .map(ClassOrInterfaceDeclaration.class::cast)
                .forEach(type -> scanType(type, packageName, "", fileId, relativePath, contentHash, projectKey,
                        scanRunId, semantic, semanticForFile, semanticBudget));
    }

    private void scanType(ClassOrInterfaceDeclaration type, String packageName, String ownerName, UUID fileId,
            String relativePath, String contentHash, String projectKey, UUID scanRunId, SemanticRun semantic,
            boolean semanticForFile, SemanticScanBudget semanticBudget) {
        String role = classify(type);
        String className = type.getNameAsString();
        String nestedName = ownerName.isBlank() ? className : ownerName + "." + className;
        String fqn = packageName.isBlank() ? nestedName : packageName + "." + nestedName;
        UUID classSymbolId = symbolId(projectKey, relativePath, "class", fqn, "");
        CodeSymbolRecord classSymbol = new CodeSymbolRecord(
                classSymbolId,
                projectKey,
                fileId,
                type.isInterface() ? "interface" : "class",
                className,
                fqn,
                "",
                role,
                startLine(type),
                endLine(type),
                contentHash,
                scanRunId,
                Map.of("annotations", annotationNames(type), "packageName", packageName));
        codeBaselineRepository.upsertSymbol(classSymbol);
        List<String> methodNames = type.getMethods().stream()
                .map(MethodDeclaration::getNameAsString)
                .limit(8)
                .toList();
        String endpointPrefix = endpointPrefix(type);
        for (AnnotationExpr annotation : type.getAnnotations()) {
            String annotationName = annotation.getNameAsString();
            upsertEdge(scanRunId, projectKey, classSymbolId, "ANNOTATED_WITH", annotationName, 0.95,
                    Map.of("filePath", relativePath, "line", nullToZero(startLine(type))));
            endpointRef(annotation).ifPresent(endpoint -> upsertEdge(scanRunId, projectKey, classSymbolId,
                    "EXPOSES_ENDPOINT", endpoint, 0.7, Map.of("filePath", relativePath)));
        }
        for (String injectedType : injectedTypes(type)) {
            upsertEdge(scanRunId, projectKey, classSymbolId, "INJECTS", injectedType, 0.65,
                    Map.of("filePath", relativePath));
        }
        if (reserveSemanticTarget(semanticForFile, semanticBudget)) {
            maybeWriteSemanticCapsule(true, semantic, new SemanticTarget(
                    classSymbolId, fileId, projectKey, "class", className, fqn, relativePath, startLine(type),
                    endLine(type), classFacts(classSymbol, methodNames, type), snippet(type.toString())),
                    semanticBudget);
        }
        int methodBudget = 0;
        for (MethodDeclaration method : type.getMethods()) {
            String signature = signature(method);
            String methodFqn = fqn + "#" + method.getNameAsString();
            UUID methodSymbolId = symbolId(projectKey, relativePath, "method", methodFqn, signature);
            CodeSymbolRecord methodSymbol = new CodeSymbolRecord(
                    methodSymbolId,
                    projectKey,
                    fileId,
                    "method",
                    method.getNameAsString(),
                    methodFqn,
                    signature,
                    "method",
                    startLine(method),
                    endLine(method),
                    contentHash,
                    scanRunId,
                    Map.of("annotations", annotationNames(method), "returnType", method.getTypeAsString()));
            codeBaselineRepository.upsertSymbol(methodSymbol);
            codeBaselineRepository.upsertEdge(new CodeEdgeRecord(
                    edgeId(projectKey, classSymbolId, "DECLARES", methodSymbolId.toString()),
                    projectKey,
                    classSymbolId,
                    methodSymbolId,
                    methodSymbolId.toString(),
                    "DECLARES",
                    "syntactic",
                    1.0,
                    scanRunId,
                    Map.of("filePath", relativePath)));
            for (AnnotationExpr annotation : method.getAnnotations()) {
                String annotationName = annotation.getNameAsString();
                upsertEdge(scanRunId, projectKey, methodSymbolId, "ANNOTATED_WITH", annotationName, 0.95,
                        Map.of("filePath", relativePath, "line", nullToZero(startLine(method))));
                endpointRef(annotation, endpointPrefix).ifPresent(endpoint -> upsertEdge(scanRunId, projectKey,
                        methodSymbolId, "EXPOSES_ENDPOINT", endpoint, 0.7, Map.of("filePath", relativePath)));
            }
            List<String> calls = methodCalls(method);
            for (String call : calls) {
                upsertEdge(scanRunId, projectKey, methodSymbolId, "CALLS", call, 0.45,
                        Map.of("filePath", relativePath, "resolution", "name-only"));
            }
            if (semanticForFile) {
                SemanticTarget methodTarget = new SemanticTarget(
                        methodSymbolId, fileId, projectKey, "method", method.getNameAsString(), methodFqn,
                        relativePath, startLine(method), endLine(method), methodFacts(methodSymbol, calls, method),
                        snippet(method.toString()));
                if (!methodHighValue(method, calls)) {
                    // SYMBOL_FIRST low-value: extractive-default, INDEPENDENT of the reserved-path
                    // maxMethodsPerFile budget (order-independent coverage) — no reserve, no methodBudget++.
                    semanticBudget.recordSkippedLowValue();
                    writeExtractiveDefaultCapsule(semantic, methodTarget, semanticBudget);
                } else if (methodBudget < properties.semantic().maxMethodsPerFile()
                        && reserveSemanticTarget(semanticForFile, semanticBudget)) {
                    maybeWriteSemanticCapsule(true, semantic, methodTarget, semanticBudget);
                    methodBudget++;
                }
            }
        }
        for (ClassOrInterfaceDeclaration nested : nestedTypes(type)) {
            scanType(nested, packageName, nestedName, fileId, relativePath, contentHash, projectKey, scanRunId,
                    semantic, semanticForFile, semanticBudget);
        }
    }

    private static boolean reserveSemanticTarget(boolean semanticForFile, SemanticScanBudget semanticBudget) {
        return semanticForFile && semanticBudget.tryReserveSymbol();
    }

    // SYMBOL_FIRST gate — runs BEFORE reserveSemanticTarget so a skipped low-value method never consumes
    // symbolsUsed / maxSemanticSymbols. ALL keeps every candidate; SYMBOL_FIRST keeps only high-value
    // methods and records each low-value skip.
    private boolean methodHighValue(MethodDeclaration method, List<String> calls) {
        return methodHighValue(method.getNameAsString(), annotationNames(method), calls);
    }

    private boolean methodHighValue(String name, List<String> annotations, List<String> calls) {
        if (properties.semantic().selectionMode() == SemanticSelectionMode.ALL) {
            return true;
        }
        // exposesEndpoint=false on purpose: endpoint methods are annotated (@GetMapping/@PostMapping/…),
        // and the annotation names already contain those, so annotation presence is the production
        // endpoint signal — a separate boolean would be redundant here.
        return ScanTimeSymbolImportance.isHighValueMethod(name, annotations, false, calls);
    }

    private void maybeWriteSemanticCapsule(boolean enabled, SemanticRun semantic, SemanticTarget target,
            SemanticScanBudget budget) {
        if (!enabled) {
            return;
        }
        String input = semanticInput(target, semantic);
        String inputHash = sha256((semantic.promptVersion() + "\n" + input).getBytes(StandardCharsets.UTF_8));
        String targetKey = target.symbolId().toString();
        if (codeBaselineRepository.capsuleExists(target.projectKey(), targetKey, target.kind(), semantic.provider(),
                semantic.model(), semantic.promptVersion(), inputHash)) {
            budget.recordReused();
            codeBaselineRepository.updateRunProgress(semantic.scanRunId(), null,
                    budget.heartbeatMetadata("semantic-symbols", target.filePath(), target.kind(), target.displayName()));
            return;
        }
        String text;
        String capsuleProvider = semantic.provider();
        boolean capsuleDataEgress = semantic.dataEgress();
        Map<String, Object> fallbackEvidence = Map.of();
        try {
            if (semantic.provider().equals("extractive")) {
                // Configured extractive mode (the default without a local LLM): structural summary, no LLM call.
                text = extractiveSummary(target);
            } else {
                budget.recordLlmAttempt();
                // Pre-call heartbeat: prove liveness during the long LLM call (currentSymbol + attempt visible
                // before llmSummary returns; no capsule written yet).
                codeBaselineRepository.updateRunProgress(semantic.scanRunId(), null,
                        budget.heartbeatMetadata("semantic-symbols", target.filePath(), target.kind(), target.displayName()));
                text = llmSummary(semantic, input, target);
            }
        } catch (LLMProviderTimeoutException | RejectedExecutionException e) {
            // F5: runtime provider failure/timeout/rejection -> extractive fallback with EXTRACTIVE provenance
            // (never a fake provider=codex capsule, so capsuleExists stays a miss and a later scan retries codex).
            budget.recordExtractiveFallback();
            codeBaselineRepository.updateRunProgress(semantic.scanRunId(), null,
                    budget.heartbeatMetadata("semantic-symbols", target.filePath(), target.kind(), target.displayName()));
            text = extractiveSummary(target);
            capsuleProvider = "extractive";
            capsuleDataEgress = false;
            fallbackEvidence = Map.of("requestedProvider", semantic.provider(),
                    "fallbackReason", e.getClass().getSimpleName());
            writeDiagnostic(semantic.scanRunId(), target.projectKey(), target.filePath(), target.symbolId(),
                    "WARN", "scanner_semantic_fallback",
                    "Runtime provider failure; wrote extractive fallback capsule to preserve coverage and allow retry",
                    Map.of("requestedProvider", semantic.provider(), "fallbackReason", e.getClass().getSimpleName(),
                            "capsuleKind", target.kind()));
        } catch (RuntimeException e) {
            budget.recordSemanticError();
            writeDiagnostic(semantic.scanRunId(), target.projectKey(), target.filePath(), target.symbolId(),
                    "ERROR", "scanner_semantic_error", e.getMessage(),
                    Map.of("exception", e.getClass().getSimpleName(), "semanticProvider", semantic.provider(),
                            "dataEgress", semantic.dataEgress(), "capsuleKind", target.kind()));
            codeBaselineRepository.updateRunProgress(semantic.scanRunId(), null,
                    budget.heartbeatMetadata("semantic-symbols", target.filePath(), target.kind(), target.displayName()));
            // Keep code search useful when the provider is unavailable: fall back to an extractive capsule
            // (EXTRACTIVE provenance, so a later scan with a working provider still retries the LLM).
            text = extractiveSummary(target);
            capsuleProvider = "extractive";
            capsuleDataEgress = false;
            fallbackEvidence = Map.of("requestedProvider", semantic.provider(),
                    "fallbackReason", e.getClass().getSimpleName());
        }
        String summary = firstLine(text);
        Map<String, Object> evidence = new LinkedHashMap<>(Map.of("filePath", target.filePath(),
                "lineStart", nullToZero(target.startLine()), "lineEnd", nullToZero(target.endLine()),
                "inputTokenEstimate", TokenEstimator.estimate(input)));
        evidence.putAll(fallbackEvidence);
        CodeSemanticCapsuleRecord capsule = new CodeSemanticCapsuleRecord(
                capsuleId(target.projectKey(), targetKey, target.kind(), capsuleProvider, semantic.model(),
                        semantic.promptVersion(), inputHash),
                target.projectKey(),
                target.symbolId(),
                target.fileId(),
                target.kind(),
                summary,
                text,
                capsuleProvider,
                semantic.model(),
                semantic.promptVersion(),
                inputHash,
                sha256(text.getBytes(StandardCharsets.UTF_8)),
                capsuleDataEgress,
                capsuleProvider.equals("extractive") ? 0.55 : 0.72,
                semantic.scanRunId(),
                evidence);
        codeBaselineRepository.upsertCapsule(capsule);
        budget.recordCapsuleWritten();
        indexCapsuleBestEffort(capsule, target.filePath(), target.symbolId(), capsule.scanRunId());
        codeBaselineRepository.updateRunProgress(semantic.scanRunId(), null,
                budget.heartbeatMetadata("semantic-symbols", target.filePath(), target.kind(), target.displayName()));
        if (fallbackEvidence.isEmpty() && semantic.dataEgress()) {
            auditExternalEgress(semantic, target, inputHash);
        }
    }

    // PR-3: SYMBOL_FIRST skipped-anchor coverage. Writes a provider="extractive" capsule with NO LLM call
    // and NO reserveSemanticTarget/budget consumption, so retrieval substrate keeps text for low-value
    // methods. Idempotent via capsuleExists(..., "extractive", ...).
    private void writeExtractiveDefaultCapsule(SemanticRun semantic, SemanticTarget target,
            SemanticScanBudget budget) {
        if (!semantic.enabled()) {
            return;
        }
        String input = semanticInput(target, semantic);
        String inputHash = sha256((semantic.promptVersion() + "\n" + input).getBytes(StandardCharsets.UTF_8));
        String targetKey = target.symbolId().toString();
        if (codeBaselineRepository.capsuleExists(target.projectKey(), targetKey, target.kind(), "extractive",
                semantic.model(), semantic.promptVersion(), inputHash)) {
            return;
        }
        String text = extractiveSummary(target);
        String summary = firstLine(text);
        Map<String, Object> evidence = new LinkedHashMap<>(Map.of("filePath", target.filePath(),
                "lineStart", nullToZero(target.startLine()), "lineEnd", nullToZero(target.endLine()),
                "inputTokenEstimate", TokenEstimator.estimate(input), "selection", "symbol-first-skipped"));
        CodeSemanticCapsuleRecord capsule = new CodeSemanticCapsuleRecord(
                capsuleId(target.projectKey(), targetKey, target.kind(), "extractive", semantic.model(),
                        semantic.promptVersion(), inputHash),
                target.projectKey(), target.symbolId(), target.fileId(), target.kind(), summary, text,
                "extractive", semantic.model(), semantic.promptVersion(), inputHash,
                sha256(text.getBytes(StandardCharsets.UTF_8)), false, 0.55, semantic.scanRunId(), evidence);
        codeBaselineRepository.upsertCapsule(capsule);
        budget.recordExtractiveDefault();
        indexCapsuleBestEffort(capsule, target.filePath(), target.symbolId(), capsule.scanRunId());
    }

    private int reindexCapsules(String projectKey, String rootPath, UUID scanRunId) {
        int attempts = 0;
        for (CodeSemanticCapsuleRecord capsule : codeBaselineRepository.findCapsulesForRoot(projectKey, rootPath,
                MAX_FORCE_REINDEX_CAPSULES)) {
            attempts++;
            indexCapsuleBestEffort(capsule, stringEvidence(capsule, "filePath"), capsule.symbolId(), scanRunId);
        }
        return attempts;
    }

    private int effectiveMaxFlowCapsules(ScanCodebaseRequest request) {
        int cap = properties.semantic().maxFlowCapsulesPerRun();
        // null maxSemanticFlows = unset -> configured default (or symbol-cap fallback); an explicit value
        // (including 0 = no flow capsules) is honored. Negative is rejected at request construction.
        if (request != null && request.maxSemanticFlows() != null) {
            cap = Math.min(cap, request.maxSemanticFlows());
        } else if (request != null && request.maxSemanticSymbols() != null && request.maxSemanticSymbols() > 0) {
            cap = Math.min(cap, request.maxSemanticSymbols());
        }
        return Math.max(0, cap);
    }

    private List<CodeSemanticCapsuleRecord> writeFlowCapsules(Path root, String projectKey, UUID scanRunId,
            SemanticRun semantic, int maxFlowSeeds, SemanticScanBudget budget) {
        if (!semantic.enabled() || maxFlowSeeds <= 0) {
            return List.of();
        }
        List<CodeSemanticCapsuleRecord> written = new ArrayList<>();
        List<CodeFlowSeed> seeds = codeFlowContextAssembler.deduplicateEndpointSeeds(
                codeFlowSeedBuilder.build(projectKey, scanRunId, maxFlowSeeds));
        for (CodeFlowSeed seed : seeds) {
            if (Thread.currentThread().isInterrupted()) {
                throw new IllegalStateException("scanner_interrupted");
            }
            try {
                maybeWriteFlowCapsule(root, seed, semantic, budget).ifPresent(written::add);
            } catch (RuntimeException e) {
                writeDiagnostic(scanRunId, projectKey, seed.filePath(), seed.symbolId(),
                        "ERROR", "scanner_flow_semantic_error", e.getMessage(),
                        Map.of("exception", e.getClass().getSimpleName(), "semanticProvider", semantic.provider(),
                                "semanticModel", semantic.model(), "dataEgress", semantic.dataEgress(),
                                "capsuleKind", seed.capsuleKind(), "entryRef", seed.entryRef()));
            }
            codeBaselineRepository.updateRunProgress(scanRunId, null,
                    budget.flowHeartbeatMetadata(written.size(), maxFlowSeeds));
        }
        return List.copyOf(written);
    }

    private Optional<CodeSemanticCapsuleRecord> maybeWriteFlowCapsule(Path root, CodeFlowSeed seed,
            SemanticRun semantic, SemanticScanBudget budget) {
        CodeFlowContext context = codeFlowContextAssembler.assemble(seed, root.toString());
        if (context.symbols().isEmpty()) {
            writeDiagnostic(semantic.scanRunId(), seed.projectKey(), seed.filePath(), seed.symbolId(),
                    "WARN", "scanner_flow_context_empty", "No flow context could be assembled for seed",
                    Map.of("capsuleKind", seed.capsuleKind(), "entryRef", seed.entryRef()));
            return Optional.empty();
        }
        String promptVersion = flowPromptVersion(semantic);
        String input = flowInput(context);
        String inputHash = sha256((promptVersion + "\n" + input).getBytes(StandardCharsets.UTF_8));
        String targetKey = seed.symbolId().toString();
        if (codeBaselineRepository.capsuleExists(seed.projectKey(), targetKey, seed.capsuleKind(),
                semantic.provider(), semantic.model(), promptVersion, inputHash)) {
            return Optional.empty();
        }
        // Eligible flow-summary attempt: only counted after empty-context and cache-hit early-returns above.
        budget.recordFlowAttempt();
        FlowSummary flowSummary;
        String capsuleProvider = semantic.provider();
        boolean capsuleDataEgress = semantic.dataEgress();
        double capsuleConfidence = semantic.provider().equals("extractive") ? 0.58 : 0.78;
        Map<String, Object> fallbackEvidence = Map.of();
        try {
            flowSummary = flowSummary(semantic, input, context);
        } catch (LLMProviderTimeoutException | RejectedExecutionException e) {
            budget.recordFlowFallback();
            // F5: runtime provider failure/timeout/rejection -> extractive fallback with EXTRACTIVE provenance.
            // Never store a fake provider=codex capsule; capsuleExists(...codex...) must stay a miss so a later
            // scan retries the real provider. Config/security errors are NOT caught here and still propagate.
            flowSummary = extractiveFlowSummary(context);
            capsuleProvider = "extractive";
            capsuleDataEgress = false;
            capsuleConfidence = 0.58;
            fallbackEvidence = Map.of("requestedProvider", semantic.provider(),
                    "fallbackReason", e.getClass().getSimpleName());
            writeDiagnostic(semantic.scanRunId(), seed.projectKey(), seed.filePath(), seed.symbolId(),
                    "WARN", "scanner_flow_semantic_fallback",
                    "Runtime provider failure; wrote extractive fallback capsule to preserve coverage and allow retry",
                    Map.of("requestedProvider", semantic.provider(), "fallbackReason", e.getClass().getSimpleName(),
                            "capsuleKind", seed.capsuleKind(), "entryRef", seed.entryRef()));
        }
        UUID fileId = codeBaselineRepository.findSymbolById(seed.symbolId())
                .filter(symbol -> seed.projectKey().equals(symbol.projectKey()))
                .map(CodeSymbolRecord::fileId)
                .orElse(null);
        String filePath = flowFilePath(context);
        Map<String, Object> evidence = new LinkedHashMap<>(flowEvidence(context, flowSummary, input));
        evidence.putAll(fallbackEvidence);
        CodeSemanticCapsuleRecord capsule = new CodeSemanticCapsuleRecord(
                capsuleId(seed.projectKey(), targetKey, seed.capsuleKind(), capsuleProvider, semantic.model(),
                        promptVersion, inputHash),
                seed.projectKey(),
                seed.symbolId(),
                fileId,
                seed.capsuleKind(),
                flowSummary.summary(),
                flowSummary.text(),
                capsuleProvider,
                semantic.model(),
                promptVersion,
                inputHash,
                sha256(flowSummary.text().getBytes(StandardCharsets.UTF_8)),
                capsuleDataEgress,
                capsuleConfidence,
                semantic.scanRunId(),
                evidence);
        codeBaselineRepository.upsertCapsule(capsule);
        budget.recordFlowCompleted();
        indexCapsuleBestEffort(capsule, filePath, seed.symbolId(), capsule.scanRunId());
        if (fallbackEvidence.isEmpty() && semantic.dataEgress()) {
            auditExternalFlowEgress(semantic, seed, inputHash);
        }
        return Optional.of(capsule);
    }

    private void writeInsightMemories(List<CodeSemanticCapsuleRecord> flowCapsules, SemanticRun semantic,
            List<UUID> memoryIds) {
        McpClientContext context = McpClientContextHolder.get();
        if (context == null || !context.hasScope(SCANNER_INSIGHTS_WRITE_MEMORY_SCOPE)
                || flowCapsules == null || flowCapsules.isEmpty()) {
            return;
        }
        int written = 0;
        for (CodeSemanticCapsuleRecord capsule : topInsightCapsules(flowCapsules)) {
            if (written >= MAX_INSIGHT_MEMORIES_PER_RUN) {
                return;
            }
            String sourceRef = "scanner-flow-insight:" + capsule.id();
            if (memoryRepository.findBySourceRef(sourceRef).isPresent()) {
                continue;
            }
            try {
                CreateMemoryRequest gateRequest = insightMemoryRequest(capsule, sourceRef, MemoryStatus.ACTIVE,
                        null, context);
                MemoryWriteGate.GateDecision gateDecision = memoryWriteGate.evaluate(gateRequest,
                        new MemoryWriteGate.GateContext(context, semantic.provider()));
                CreateMemoryRequest createRequest = insightMemoryRequest(capsule, sourceRef,
                        memoryStatusFor(gateDecision.verdict()), gateDecision, context);
                MemoryItem item = memoryService.create(createRequest);
                memoryIds.add(item.id());
                written++;
            } catch (RuntimeException e) {
                writeDiagnostic(semantic.scanRunId(), capsule.projectKey(), stringEvidence(capsule, "filePath"),
                        capsule.symbolId(), "WARN", "scanner_insight_memory_write_failed", e.getMessage(),
                        Map.of("exception", e.getClass().getSimpleName(), "capsuleId", capsule.id().toString(),
                                "capsuleKind", capsule.capsuleKind()));
            }
        }
    }

    private static List<CodeSemanticCapsuleRecord> topInsightCapsules(List<CodeSemanticCapsuleRecord> capsules) {
        return capsules.stream()
                .filter(capsule -> flowCapsuleKind(capsule.capsuleKind()))
                .filter(ScannerService::productionFlowCapsule)
                .sorted(java.util.Comparator
                        .comparingInt((CodeSemanticCapsuleRecord capsule) ->
                                CodeFlowSeedBuilder.PROJECT_INSIGHT.equals(capsule.capsuleKind()) ? 0 : 1)
                        .thenComparing(java.util.Comparator.comparingDouble(CodeSemanticCapsuleRecord::confidence)
                                .reversed())
                        .thenComparing(CodeSemanticCapsuleRecord::summary))
                .limit(MAX_INSIGHT_MEMORIES_PER_RUN)
                .toList();
    }

    private static boolean productionFlowCapsule(CodeSemanticCapsuleRecord capsule) {
        String filePath = stringEvidence(capsule, "filePath").replace('\\', '/');
        if (filePath.contains("/src/test/") || filePath.startsWith("src/test/")) {
            return false;
        }
        String entryRef = stringEvidence(capsule, "entryRef").toLowerCase(Locale.ROOT);
        return !entryRef.endsWith("test") && !entryRef.contains("test#");
    }

    private CreateMemoryRequest insightMemoryRequest(CodeSemanticCapsuleRecord capsule, String sourceRef,
            MemoryStatus status, MemoryWriteGate.GateDecision gateDecision, McpClientContext context) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("scanner", "flow-insight");
        metadata.put("capsuleId", capsule.id().toString());
        metadata.put("capsuleKind", capsule.capsuleKind());
        metadata.put("targetKey", capsule.targetKey());
        metadata.put("promptVersion", capsule.promptVersion());
        metadata.put("provider", capsule.provider());
        metadata.put("semanticModel", capsule.semanticModel());
        metadata.put("dataEgress", capsule.dataEgress());
        metadata.put("mcpClientId", context.clientId());
        metadata.put("mcpKeyPrefix", context.keyPrefix());
        String filePath = stringEvidence(capsule, "filePath");
        if (!filePath.isBlank()) {
            metadata.put("filePath", filePath);
        }
        if (capsule.symbolId() != null) {
            metadata.put("symbolId", capsule.symbolId().toString());
        }
        if (gateDecision != null) {
            metadata.put("gateVerdict", gateDecision.verdict().name());
            metadata.put("gateReason", gateDecision.gateReason());
            metadata.put("gateConfidence", gateDecision.confidence());
            metadata.put("confirmationRequired", false);
            if (gateDecision.suggestedQuestion() != null) {
                metadata.put("suggestedQuestion", gateDecision.suggestedQuestion());
            }
            if (!gateDecision.contextHints().isEmpty()) {
                metadata.put("contextHints", gateDecision.contextHints());
            }
            if (!gateDecision.debugMetadata().isEmpty()) {
                metadata.put("gateDebug", gateDecision.debugMetadata());
            }
            if (gateDecision.verdict() == MemoryWriteGate.Verdict.AUTO_ACTIVE
                    && "local".equals(context.keyPrefix())
                    && context.hasScope("memory.auto_active_write")) {
                metadata.put("autoActiveVia", "local_trust_scope");
            }
        }
        List<MemoryCodeLocator> codeLocators = new ArrayList<>();
        if (!capsule.targetKey().isBlank()) {
            codeLocators.add(new MemoryCodeLocator(MemoryCodeLocatorKind.CAPSULE, capsule.targetKey(),
                    capsule.capsuleKind(), MemoryCodeLocatorRelationship.EVIDENCES));
        }
        if (!filePath.isBlank()) {
            codeLocators.add(new MemoryCodeLocator(MemoryCodeLocatorKind.FILE, filePath, null,
                    MemoryCodeLocatorRelationship.MENTIONS));
        }
        if (capsule.symbolId() != null) {
            codeLocators.add(new MemoryCodeLocator(MemoryCodeLocatorKind.SYMBOL, capsule.symbolId().toString(), null,
                    MemoryCodeLocatorRelationship.CONSTRAINS));
        }
        Map<String, Object> storedMetadata = MemoryCodeLocatorMetadata.replace(metadata, codeLocators,
                MemoryScope.PROJECT, capsule.projectKey());
        return new CreateMemoryRequest(
                MemoryScope.PROJECT,
                capsule.projectKey(),
                MemoryType.DECISION,
                "Code flow insight: " + capsule.summary(),
                capsule.text(),
                List.of("scanner", "code-flow", capsule.capsuleKind()),
                gateDecision == null ? capsule.confidence() : gateDecision.confidence(),
                status,
                MemorySourceType.SCANNED,
                sourceRef,
                "scanner",
                storedMetadata,
                Instant.now(),
                null);
    }

    private static CompilationUnit parseD1CompilationUnit(String content) {
        ParseResult<CompilationUnit> result = new JavaParser(D1_PARSER_CONFIGURATION).parse(content);
        return result.getResult().orElseThrow(() -> new ParseProblemException(result.getProblems()));
    }

    private int resolveEdges(Path root, List<Path> files, String projectKey, UUID scanRunId) {
        if (!properties.resolvedEdges().enabled()) {
            return 0;
        }
        return resolveJavaEdges(root, files, projectKey, scanRunId)
                + resolveKotlinSwiftEdges(root, files, projectKey, scanRunId);
    }

    private int resolveJavaEdges(Path root, List<Path> files, String projectKey, UUID scanRunId) {
        ScannerProperties.ResolvedEdges resolvedEdges = properties.resolvedEdges();
        List<Path> javaFiles = files.stream()
                .filter(file -> file.getFileName().toString().endsWith(".java"))
                .limit(resolvedEdges.maxFilesPerRun())
                .toList();
        if (javaFiles.isEmpty()) {
            return 0;
        }
        JavaParser parser;
        try {
            parser = resolvedEdgeParser(root, javaFiles);
        } catch (RuntimeException e) {
            writeDiagnostic(scanRunId, projectKey, "", null, "WARN", "resolved_edges_classpath_failed",
                    e.getMessage(), Map.of("exception", e.getClass().getSimpleName()));
            return 0;
        }
        int resolved = 0;
        for (Path file : javaFiles) {
            String relativePath = root.relativize(file).toString();
            try {
                ParseResult<CompilationUnit> result = parser.parse(file.toFile());
                if (result.getResult().isEmpty()) {
                    writeDiagnostic(scanRunId, projectKey, relativePath, null, "WARN",
                            "resolved_edges_parse_failed", "JavaParser returned no compilation unit",
                            Map.of("problems", result.getProblems().toString()));
                    continue;
                }
                resolved += resolveFileEdges(root, file, result.getResult().get(), projectKey, scanRunId);
            } catch (FileNotFoundException e) {
                writeDiagnostic(scanRunId, projectKey, relativePath, null, "WARN", "resolved_edges_file_missing",
                        e.getMessage(), Map.of("exception", e.getClass().getSimpleName()));
            } catch (RuntimeException e) {
                writeDiagnostic(scanRunId, projectKey, relativePath, null, "WARN", "resolved_edges_file_failed",
                        e.getMessage(), Map.of("exception", e.getClass().getSimpleName()));
            }
        }
        return resolved;
    }

    /**
     * Kotlin/Swift have no type solver here, so call sites are linked by name: the receiver's declared type
     * (parameter, local or property), the enclosing type for bare calls, or a unique project function/type.
     * Unknown or ambiguous targets stay name-only D1 edges.
     */
    private int resolveKotlinSwiftEdges(Path root, List<Path> files, String projectKey, UUID scanRunId) {
        List<Path> sources = files.stream()
                .filter(file -> KotlinSwiftParser.Language.of(file.getFileName().toString()).isPresent())
                .limit(properties.resolvedEdges().maxFilesPerRun())
                .toList();
        NameLookup lookup = new NameLookup(projectKey);
        int resolved = 0;
        for (Path file : sources) {
            String relativePath = root.relativize(file).toString();
            try {
                KotlinSwiftParser.Language language = KotlinSwiftParser.Language.of(file.getFileName().toString())
                        .orElseThrow();
                String content = ScannerSourceDecoder.decode(Files.readAllBytes(file)).content();
                KotlinSwiftParser.ParsedFile parsed = KotlinSwiftParser.parse(language, content);
                Map<String, Map<String, String>> fieldTypes = new LinkedHashMap<>();
                for (KotlinSwiftParser.Declaration declaration : parsed.declarations()) {
                    if (declaration.isType()) {
                        Map<String, String> fields = fieldTypes.computeIfAbsent(declaration.fqn(),
                                key -> new LinkedHashMap<>());
                        declaration.dependencies().forEach(dependency -> fields.putIfAbsent(dependency.name(),
                                dependency.type()));
                    }
                }
                for (KotlinSwiftParser.Declaration declaration : parsed.declarations()) {
                    UUID sourceId = mobileSymbolId(projectKey, relativePath, declaration);
                    if (codeBaselineRepository.findSymbolById(sourceId).isEmpty()) {
                        continue;
                    }
                    if (declaration.isType()) {
                        for (String injectedType : mobileInjectedTypes(declaration)) {
                            Optional<CodeSymbolRecord> target = lookup.type(injectedType);
                            if (target.isPresent()) {
                                upsertResolvedEdge(scanRunId, projectKey, sourceId, target.get(), "INJECTS", 0.8,
                                        "name_match", Map.of("filePath", relativePath, "declaredType", injectedType));
                                resolved++;
                            }
                        }
                        continue;
                    }
                    Map<String, String> fields = fieldTypes.getOrDefault(declaration.ownerFqn(), Map.of());
                    for (KotlinSwiftParser.Call call : declaration.calls()) {
                        Optional<CodeSymbolRecord> target = lookup.call(call, declaration, fields);
                        if (target.isPresent()) {
                            upsertResolvedEdge(scanRunId, projectKey, sourceId, target.get(), "CALLS", 0.8,
                                    "name_match", Map.of("filePath", relativePath, "call", call.name(),
                                            "resolvedFqn", target.get().fqn()));
                            resolved++;
                        }
                    }
                }
            } catch (IOException | RuntimeException e) {
                writeDiagnostic(scanRunId, projectKey, relativePath, null, "WARN", "resolved_edges_file_failed",
                        e.getMessage(), Map.of("exception", e.getClass().getSimpleName()));
            }
        }
        return resolved;
    }

    /** Project-symbol lookups by name for Kotlin/Swift call sites, cached for one resolve pass. */
    private final class NameLookup {
        private static final Set<String> TYPE_KINDS = Set.of("class", "interface", "object", "enum", "struct");
        private static final Set<String> MEMBER_KINDS = Set.of("method", "property");

        private final String projectKey;
        private final Map<String, List<CodeSymbolRecord>> byRef = new java.util.HashMap<>();

        NameLookup(String projectKey) {
            this.projectKey = projectKey;
        }

        Optional<CodeSymbolRecord> call(KotlinSwiftParser.Call call, KotlinSwiftParser.Declaration caller,
                Map<String, String> fields) {
            String name = call.name();
            String receiver = call.receiver();
            if (receiver == null || receiver.equals("this") || receiver.equals("self")) {
                if (receiver == null && Character.isUpperCase(name.charAt(0))) {
                    // A composable function, else a constructor / SwiftUI view initializer.
                    return function(name, "").or(() -> type(name));
                }
                Optional<CodeSymbolRecord> member = caller.ownerFqn().isBlank()
                        ? Optional.empty()
                        : member(caller.ownerFqn(), name);
                return member.isPresent() || receiver != null ? member : function(name, "");
            }
            if (receiver.isEmpty() || receiver.equals("super")) {
                return Optional.empty();
            }
            String typeName = caller.localTypes().getOrDefault(receiver, fields.get(receiver));
            if (typeName == null && Character.isUpperCase(receiver.charAt(0))) {
                typeName = receiver; // static or companion call: `Foo.create()`
            }
            if (typeName == null) {
                return Optional.empty();
            }
            String receiverType = typeName;
            return type(typeName).flatMap(type -> member(type.fqn(), name)
                    .or(() -> member(type.fqn() + ".Companion", name))
                    .or(() -> function(name, receiverType)));
        }

        /** The one project type with this simple name; none when absent or ambiguous. */
        Optional<CodeSymbolRecord> type(String simpleName) {
            List<CodeSymbolRecord> matches = ref(simpleName).stream()
                    .filter(symbol -> TYPE_KINDS.contains(symbol.symbolKind()) && simpleName.equals(symbol.name()))
                    .toList();
            return matches.stream().map(CodeSymbolRecord::fqn).distinct().count() == 1
                    ? Optional.of(matches.getFirst())
                    : Optional.empty();
        }

        private Optional<CodeSymbolRecord> member(String ownerFqn, String name) {
            return ref(ownerFqn + "#" + name).stream()
                    .filter(symbol -> MEMBER_KINDS.contains(symbol.symbolKind()))
                    .findFirst();
        }

        /** A unique top-level function (with this extension receiver type, "" for none). */
        private Optional<CodeSymbolRecord> function(String name, String receiverType) {
            List<CodeSymbolRecord> matches = ref(name).stream()
                    .filter(symbol -> "function".equals(symbol.symbolKind()) && name.equals(symbol.name()))
                    .filter(symbol -> receiverType.equals(String.valueOf(
                            symbol.metadata().getOrDefault("receiverType", ""))))
                    .toList();
            return matches.size() == 1 ? Optional.of(matches.getFirst()) : Optional.empty();
        }

        private List<CodeSymbolRecord> ref(String ref) {
            return byRef.computeIfAbsent(ref, key -> codeBaselineRepository.findSymbolsByRef(projectKey, key, 20));
        }
    }

    private int resolveEdgesAfterPrimaryScan(Path root, List<Path> files, String projectKey, UUID scanRunId) {
        if (!properties.resolvedEdges().enabled()) {
            return 0;
        }
        try {
            if (resolvedEdgeTransaction == null) {
                return resolveEdges(root, files, projectKey, scanRunId);
            }
            Integer resolved = resolvedEdgeTransaction.execute(status -> resolveEdges(root, files, projectKey,
                    scanRunId));
            return resolved == null ? 0 : resolved;
        } catch (Throwable throwable) {
            writeResolvedEdgeFailureDiagnostic(scanRunId, projectKey, throwable);
            return 0;
        }
    }

    private void writeResolvedEdgeFailureDiagnostic(UUID scanRunId, String projectKey, Throwable throwable) {
        try {
            String message = throwable.getMessage() == null || throwable.getMessage().isBlank()
                    ? throwable.getClass().getSimpleName()
                    : throwable.getMessage();
            writeDiagnostic(scanRunId, projectKey, "", null, "WARN", "resolved_edges_failed", message,
                    Map.of("exception", throwable.getClass().getSimpleName()));
        } catch (Throwable ignored) {
            // D2 is optional enrichment; diagnostic failure must not convert it into a scanner failure.
        }
    }

    private JavaParser resolvedEdgeParser(Path root, List<Path> javaFiles) {
        CombinedTypeSolver solver = new CombinedTypeSolver();
        solver.add(new ReflectionTypeSolver());
        for (Path sourceRoot : sourceRoots(root, javaFiles)) {
            try {
                solver.add(new JavaParserTypeSolver(sourceRoot));
            } catch (RuntimeException ignored) {
                // A bad source root should not prevent using other roots or reflection.
            }
        }
        int jars = 0;
        String classPath = System.getProperty("java.class.path", "");
        // In Spring Boot fat jars this may only expose the launcher jar; source roots do the real local work.
        for (String entry : classPath.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
            if (jars >= properties.resolvedEdges().maxClasspathJars()) {
                break;
            }
            if (entry == null || entry.isBlank() || !entry.endsWith(".jar")) {
                continue;
            }
            Path jar = Path.of(entry);
            if (!Files.isRegularFile(jar)) {
                continue;
            }
            try {
                solver.add(new JarTypeSolver(jar.toString()));
                jars++;
            } catch (IOException | RuntimeException ignored) {
                // Missing/corrupt jars are best-effort inputs; source-root resolution still proceeds.
            }
        }
        ParserConfiguration configuration = new ParserConfiguration()
                .setSymbolResolver(new JavaSymbolSolver(solver));
        return new JavaParser(configuration);
    }

    private static List<Path> sourceRoots(Path root, List<Path> javaFiles) {
        LinkedHashSet<Path> roots = new LinkedHashSet<>();
        for (Path candidate : List.of(root.resolve("src/main/java"), root.resolve("src/test/java"), root)) {
            if (Files.isDirectory(candidate)) {
                roots.add(candidate);
            }
        }
        for (Path file : javaFiles) {
            Path parent = file.getParent();
            while (parent != null && parent.startsWith(root)) {
                if (parent.endsWith(Path.of("src/main/java")) || parent.endsWith(Path.of("src/test/java"))) {
                    roots.add(parent);
                    break;
                }
                parent = parent.getParent();
            }
        }
        return List.copyOf(roots);
    }

    private int resolveFileEdges(Path root, Path file, CompilationUnit compilationUnit, String projectKey,
            UUID scanRunId) {
        String relativePath = root.relativize(file).toString();
        String packageName = compilationUnit.getPackageDeclaration()
                .map(declaration -> declaration.getNameAsString())
                .orElse("");
        int resolved = 0;
        for (var typeDeclaration : compilationUnit.getTypes()) {
            if (typeDeclaration instanceof ClassOrInterfaceDeclaration type) {
                resolved += resolveTypeEdges(type, packageName, "", relativePath, projectKey, scanRunId);
            }
        }
        return resolved;
    }

    private int resolveTypeEdges(ClassOrInterfaceDeclaration type, String packageName, String ownerName,
            String relativePath, String projectKey, UUID scanRunId) {
        String className = type.getNameAsString();
        String nestedName = ownerName.isBlank() ? className : ownerName + "." + className;
        String classFqn = packageName.isBlank() ? nestedName : packageName + "." + nestedName;
        UUID classSymbolId = symbolId(projectKey, relativePath, type.isInterface() ? "interface" : "class",
                classFqn, "");
        int resolved = 0;
        if (codeBaselineRepository.findSymbolById(classSymbolId).isPresent()) {
            resolved += resolveInjectionEdges(type, classSymbolId, relativePath, projectKey, scanRunId);
        }
        for (MethodDeclaration method : type.getMethods()) {
            String methodFqn = classFqn + "#" + method.getNameAsString();
            UUID methodSymbolId = symbolId(projectKey, relativePath, "method", methodFqn, signature(method));
            if (codeBaselineRepository.findSymbolById(methodSymbolId).isEmpty()) {
                continue;
            }
            resolved += resolveMethodCallEdges(method, methodSymbolId, relativePath, projectKey, scanRunId);
        }
        for (ClassOrInterfaceDeclaration nested : nestedTypes(type)) {
            resolved += resolveTypeEdges(nested, packageName, nestedName, relativePath, projectKey, scanRunId);
        }
        return resolved;
    }

    private int resolveMethodCallEdges(MethodDeclaration method, UUID sourceSymbolId, String relativePath,
            String projectKey, UUID scanRunId) {
        int resolved = 0;
        boolean diagnosticWritten = false;
        for (MethodCallExpr call : method.findAll(MethodCallExpr.class)) {
            try {
                ResolvedMethodDeclaration resolvedMethod = call.resolve();
                Optional<CodeSymbolRecord> target = findResolvedMethodTarget(projectKey, resolvedMethod);
                if (target.isEmpty()) {
                    continue;
                }
                upsertResolvedEdge(scanRunId, projectKey, sourceSymbolId, target.get(), "CALLS", 0.92,
                        Map.of("filePath", relativePath, "resolvedFqn", methodFqn(resolvedMethod),
                                "resolvedSignature", resolvedMethod.getSignature(),
                                "line", nullToZero(startLine(call))));
                resolved++;
            } catch (RuntimeException e) {
                if (!diagnosticWritten) {
                    writeDiagnostic(scanRunId, projectKey, relativePath, sourceSymbolId, "WARN",
                            "resolved_edges_symbol_failed", e.getMessage(),
                            Map.of("exception", e.getClass().getSimpleName(), "method", method.getNameAsString()));
                    diagnosticWritten = true;
                }
            }
        }
        return resolved;
    }

    private int resolveInjectionEdges(ClassOrInterfaceDeclaration type, UUID sourceSymbolId, String relativePath,
            String projectKey, UUID scanRunId) {
        int resolved = 0;
        for (FieldDeclaration field : type.getFields()) {
            if (!field.getAnnotations().stream().anyMatch(annotation -> annotation.getNameAsString().equals("Autowired"))) {
                continue;
            }
            Optional<CodeSymbolRecord> target = resolveTypeTarget(projectKey, field.getElementType());
            if (target.isPresent()) {
                upsertResolvedEdge(scanRunId, projectKey, sourceSymbolId, target.get(), "INJECTS", 0.88,
                        Map.of("filePath", relativePath, "source", "field",
                                "declaredType", field.getElementType().toString()));
                resolved++;
            }
        }
        for (ConstructorDeclaration constructor : type.getConstructors()) {
            if (type.getConstructors().size() > 1 && constructor.getAnnotations().stream()
                    .noneMatch(annotation -> annotation.getNameAsString().equals("Autowired"))) {
                continue;
            }
            for (var parameter : constructor.getParameters()) {
                Optional<CodeSymbolRecord> target = resolveTypeTarget(projectKey, parameter.getType());
                if (target.isPresent()) {
                    upsertResolvedEdge(scanRunId, projectKey, sourceSymbolId, target.get(), "INJECTS", 0.88,
                            Map.of("filePath", relativePath, "source", "constructor",
                                    "declaredType", parameter.getTypeAsString()));
                    resolved++;
                }
            }
        }
        return resolved;
    }

    private Optional<CodeSymbolRecord> findResolvedMethodTarget(String projectKey, ResolvedMethodDeclaration method) {
        String targetFqn = methodFqn(method);
        List<CodeSymbolRecord> matches = codeBaselineRepository.findSymbolsByRef(projectKey, targetFqn, 20).stream()
                .filter(symbol -> "method".equals(symbol.symbolKind()))
                .toList();
        if (matches.isEmpty()) {
            return Optional.empty();
        }
        String resolvedSignature = method.getSignature();
        List<CodeSymbolRecord> signatureMatches = matches.stream()
                .filter(symbol -> signaturesMatch(symbol.signature(), resolvedSignature))
                .toList();
        if (signatureMatches.size() == 1) {
            return Optional.of(signatureMatches.getFirst());
        }
        return matches.size() == 1 ? Optional.of(matches.getFirst()) : Optional.empty();
    }

    private Optional<CodeSymbolRecord> resolveTypeTarget(String projectKey, Type type) {
        try {
            ResolvedType resolvedType = type.resolve();
            if (!resolvedType.isReferenceType()) {
                return Optional.empty();
            }
            String qualifiedName = resolvedType.asReferenceType().getQualifiedName();
            if (qualifiedName == null || qualifiedName.isBlank()) {
                return Optional.empty();
            }
            return codeBaselineRepository.findSymbolsByRef(projectKey, qualifiedName, 10).stream()
                    .filter(symbol -> "class".equals(symbol.symbolKind()) || "interface".equals(symbol.symbolKind()))
                    .findFirst();
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private void upsertResolvedEdge(UUID scanRunId, String projectKey, UUID sourceSymbolId,
            CodeSymbolRecord target, String edgeType, double confidence, Map<String, Object> evidence) {
        upsertResolvedEdge(scanRunId, projectKey, sourceSymbolId, target, edgeType, confidence, "resolved", evidence);
    }

    private void upsertResolvedEdge(UUID scanRunId, String projectKey, UUID sourceSymbolId,
            CodeSymbolRecord target, String edgeType, double confidence, String resolution,
            Map<String, Object> evidence) {
        String targetRef = target.id().toString();
        codeBaselineRepository.upsertEdge(new CodeEdgeRecord(
                edgeId(projectKey, sourceSymbolId, edgeType, targetRef),
                projectKey,
                sourceSymbolId,
                target.id(),
                targetRef,
                edgeType,
                resolution,
                confidence,
                scanRunId,
                evidence));
    }

    private void indexCapsuleBestEffort(CodeSemanticCapsuleRecord capsule, String filePath, UUID symbolId,
            UUID diagnosticScanRunId) {
        try {
            codeBaselineVectorIndex.upsert(capsule);
        } catch (RuntimeException e) {
            writeDiagnostic(diagnosticScanRunId, capsule.projectKey(), filePath, symbolId,
                    "WARN", "code_baseline_index_failed", e.getMessage(),
                    Map.of("exception", e.getClass().getSimpleName(), "capsuleId", capsule.id().toString(),
                            "symbolId", capsule.symbolId() == null ? "" : capsule.symbolId().toString()));
        }
    }

    private String llmSummary(SemanticRun semantic, String input, SemanticTarget target) {
        if (llmGateway == null) {
            return extractiveSummary(target);
        }
        try {
            GenerationResponse response = llmGateway.generate(new GenerationRequest(
                    input,
                    target.projectKey(),
                    List.of(new SourceSnippet(target.symbolId().toString(), target.displayName(), target.filePath(),
                            input, 1.0, "fresh", Instant.now())),
                    "fresh",
                    semantic.provider(),
                    "",
                    0,
                    "scanner"));
            if (response.degraded() || response.degradedReason() != DegradedReason.NONE
                    || response.answer() == null || response.answer().isBlank()) {
                return extractiveSummary(target);
            }
            return response.answer().trim();
        } catch (RuntimeException e) {
            if (semantic.dataEgress()) {
                throw e;
            }
            return extractiveSummary(target);
        }
    }

    private void auditExternalEgress(SemanticRun semantic, SemanticTarget target, String inputHash) {
        if (auditLogger == null) {
            return;
        }
        McpClientContext context = McpClientContextHolder.get();
        auditLogger.log(context, "scanner.semantic", inputHash, 1, 0L, "success",
                Map.of("dataEgress", true, "provider", semantic.provider(), "semanticModel", semantic.model(),
                        "scanRunId", semantic.scanRunId().toString(), "projectKey", target.projectKey(),
                        "filePath", target.filePath()),
                null);
    }

    private void auditExternalFlowEgress(SemanticRun semantic, CodeFlowSeed seed, String inputHash) {
        if (auditLogger == null) {
            return;
        }
        McpClientContext context = McpClientContextHolder.get();
        auditLogger.log(context, "scanner.flow_semantic", inputHash, 1, 0L, "success",
                Map.of("dataEgress", true, "provider", semantic.provider(), "semanticModel", semantic.model(),
                        "scanRunId", semantic.scanRunId().toString(), "projectKey", seed.projectKey(),
                        "capsuleKind", seed.capsuleKind(), "entryRef", seed.entryRef()),
                null);
    }

    private String flowInput(CodeFlowContext context) {
        CodeFlowSeed seed = context.seed();
        StringBuilder builder = new StringBuilder();
        builder.append("You are analyzing a software flow for future codebase diagnosis.\n");
        builder.append("Return only compact JSON with these fields: summary, trigger, steps, cacheKeys, ");
        builder.append("externalClients, repositories, requestModels, responseModels, branches, failurePoints, ");
        builder.append("evidence, narrative.\n");
        builder.append("Do not invent behavior. Use only the provided structural facts and snippets.\n");
        builder.append("Prefer useful human-level flow analysis over class counts.\n\n");
        builder.append("Flow seed:\n");
        builder.append("kind=").append(seed.capsuleKind()).append('\n');
        builder.append("triggerKind=").append(seed.triggerKind()).append('\n');
        builder.append("title=").append(seed.title()).append('\n');
        builder.append("entryRef=").append(seed.entryRef()).append('\n');
        builder.append("endpoint=").append(seed.endpoint()).append("\n\n");
        builder.append("Collected facts:\n");
        builder.append("services=").append(context.services()).append('\n');
        builder.append("externalClients=").append(context.externalClients()).append('\n');
        builder.append("repositories=").append(context.repositories()).append('\n');
        builder.append("cacheKeys=").append(context.cacheKeys()).append('\n');
        builder.append("requestModels=").append(context.requestModels()).append('\n');
        builder.append("responseModels=").append(context.responseModels()).append('\n');
        builder.append("warnings=").append(context.warnings()).append("\n\n");
        builder.append("Symbols:\n");
        for (CodeFlowContextSymbol symbol : context.symbols()) {
            builder.append("- id=").append(symbol.symbolId())
                    .append(" kind=").append(symbol.symbolKind())
                    .append(" role=").append(symbol.role())
                    .append(" name=").append(symbol.name())
                    .append(" fqn=").append(symbol.fqn())
                    .append(" signature=").append(symbol.signature())
                    .append(" file=").append(symbol.filePath())
                    .append(':').append(nullToZero(symbol.startLine()))
                    .append('-').append(nullToZero(symbol.endLine()))
                    .append(" reason=").append(symbol.reason())
                    .append(" annotations=").append(symbol.annotations())
                    .append('\n');
        }
        builder.append("\nEdges:\n");
        Map<UUID, String> namesById = new LinkedHashMap<>();
        for (CodeFlowContextSymbol symbol : context.symbols()) {
            namesById.put(symbol.symbolId(), symbol.fqn().isBlank() ? symbol.name() : symbol.fqn());
        }
        for (CodeFlowContextEdge edge : context.edges()) {
            builder.append("- ").append(namesById.getOrDefault(edge.sourceSymbolId(), edge.sourceSymbolId().toString()))
                    .append(" --").append(edge.edgeType()).append('/')
                    .append(edge.resolution()).append("--> ");
            if (edge.targetSymbolId() != null) {
                builder.append(namesById.getOrDefault(edge.targetSymbolId(), edge.targetSymbolId().toString()));
            } else {
                builder.append(edge.targetRef());
            }
            builder.append(" confidence=").append(edge.confidence()).append('\n');
        }
        builder.append("\nSnippets:\n");
        for (CodeFlowSnippet snippet : context.snippets()) {
            builder.append("### ").append(snippet.title())
                    .append(" (").append(snippet.filePath()).append(':')
                    .append(nullToZero(snippet.startLine())).append('-')
                    .append(nullToZero(snippet.endLine())).append(")\n")
                    .append(snippet.text()).append("\n\n");
        }
        return truncateToTokens(redact(builder.toString()), properties.semantic().maxPromptTokens());
    }

    private FlowSummary flowSummary(SemanticRun semantic, String input, CodeFlowContext context) {
        if (semantic.provider().equals("extractive") || llmGateway == null) {
            return extractiveFlowSummary(context);
        }
        try {
            GenerationResponse response = llmGateway.generate(new GenerationRequest(
                    input,
                    context.seed().projectKey(),
                    List.of(new SourceSnippet(context.seed().symbolId().toString(), context.seed().title(),
                            flowFilePath(context), input, 1.0, "fresh", Instant.now())),
                    "fresh",
                    semantic.provider(),
                    "",
                    0,
                    "scanner-flow"));
            if (response.degraded() || response.degradedReason() != DegradedReason.NONE
                    || response.answer() == null || response.answer().isBlank()) {
                return extractiveFlowSummary(context);
            }
            return parseFlowSummary(response.answer(), context);
        } catch (RuntimeException e) {
            if (semantic.dataEgress()) {
                throw e;
            }
            return extractiveFlowSummary(context);
        }
    }

    private static FlowSummary parseFlowSummary(String answer, CodeFlowContext context) {
        String raw = stripJsonFence(answer);
        try {
            JsonNode json = OBJECT_MAPPER.readTree(raw);
            String summary = jsonText(json, "summary", firstLine(raw));
            String narrative = jsonText(json, "narrative", summary);
            return new FlowSummary(summary, renderFlowText(summary, jsonText(json, "trigger", context.seed().title()),
                    jsonList(json, "steps"), jsonList(json, "cacheKeys"), jsonList(json, "externalClients"),
                    jsonList(json, "repositories"), jsonList(json, "branches"), jsonList(json, "failurePoints"),
                    jsonList(json, "evidence"), narrative),
                    jsonList(json, "steps"),
                    jsonList(json, "cacheKeys"),
                    jsonList(json, "externalClients"),
                    jsonList(json, "repositories"),
                    jsonList(json, "requestModels"),
                    jsonList(json, "responseModels"),
                    jsonList(json, "branches"),
                    jsonList(json, "failurePoints"),
                    jsonList(json, "evidence"));
        } catch (JsonProcessingException | IllegalArgumentException e) {
            FlowSummary fallback = extractiveFlowSummary(context);
            String summary = firstLine(raw);
            return new FlowSummary(summary, raw.isBlank() ? fallback.text() : raw, fallback.steps(),
                    fallback.cacheKeys(), fallback.externalClients(), fallback.repositories(),
                    fallback.requestModels(), fallback.responseModels(), fallback.branches(),
                    fallback.failurePoints(), fallback.evidence());
        }
    }

    private static FlowSummary extractiveFlowSummary(CodeFlowContext context) {
        CodeFlowSeed seed = context.seed();
        List<String> steps = context.symbols().stream()
                .map(symbol -> (symbol.role() == null || symbol.role().isBlank() ? symbol.symbolKind() : symbol.role())
                        + " " + (symbol.fqn().isBlank() ? symbol.name() : symbol.fqn()))
                .limit(10)
                .toList();
        List<String> evidence = context.symbols().stream()
                .filter(symbol -> symbol.filePath() != null && !symbol.filePath().isBlank())
                .map(symbol -> symbol.filePath() + ":" + nullToZero(symbol.startLine())
                        + "-" + nullToZero(symbol.endLine()))
                .distinct()
                .limit(12)
                .toList();
        String summary = seed.title().isBlank()
                ? "Flow analysis for " + seed.entryRef()
                : seed.title();
        String text = renderFlowText(summary, seed.endpoint().isBlank() ? seed.triggerKind() : seed.endpoint(),
                steps, context.cacheKeys(), context.externalClients(), context.repositories(), List.of(), List.of(),
                evidence, "Extractive flow assembled from D1/D2 graph facts and bounded source snippets.");
        return new FlowSummary(summary, text, steps, context.cacheKeys(), context.externalClients(),
                context.repositories(), context.requestModels(), context.responseModels(), List.of(), List.of(),
                evidence);
    }

    private static String renderFlowText(String summary, String trigger, List<String> steps, List<String> cacheKeys,
            List<String> externalClients, List<String> repositories, List<String> branches,
            List<String> failurePoints, List<String> evidence, String narrative) {
        return """
                Summary: %s
                Trigger: %s
                Steps:
                %s
                Cache keys: %s
                External clients: %s
                Repositories: %s
                Branches/fallbacks: %s
                Failure points: %s
                Evidence: %s

                Narrative:
                %s
                """.formatted(summary, trigger, bulletList(steps), cacheKeys, externalClients, repositories,
                branches, failurePoints, evidence, narrative).trim();
    }

    private Map<String, Object> flowEvidence(CodeFlowContext context, FlowSummary summary, String input) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        CodeFlowSeed seed = context.seed();
        evidence.put("filePath", flowFilePath(context));
        evidence.put("entryRef", seed.entryRef());
        evidence.put("triggerKind", seed.triggerKind());
        evidence.put("endpoint", seed.endpoint());
        evidence.put("seedScore", seed.score());
        context.symbols().stream().findFirst().ifPresent(symbol -> {
            evidence.put("lineStart", nullToZero(symbol.startLine()));
            evidence.put("lineEnd", nullToZero(symbol.endLine()));
        });
        evidence.put("contextTruncated", context.truncated());
        evidence.put("warnings", context.warnings());
        evidence.put("services", context.services());
        evidence.put("externalClients", summary.externalClientsOr(context.externalClients()));
        evidence.put("repositories", summary.repositoriesOr(context.repositories()));
        evidence.put("cacheKeys", summary.cacheKeysOr(context.cacheKeys()));
        evidence.put("requestModels", summary.requestModelsOr(context.requestModels()));
        evidence.put("responseModels", summary.responseModelsOr(context.responseModels()));
        evidence.put("steps", summary.steps());
        evidence.put("branches", summary.branches());
        evidence.put("failurePoints", summary.failurePoints());
        evidence.put("evidence", summary.evidence());
        evidence.put("symbolIds", context.symbols().stream()
                .map(symbol -> symbol.symbolId().toString())
                .limit(20)
                .toList());
        evidence.put("inputTokenEstimate", TokenEstimator.estimate(input));
        return evidence;
    }

    private String semanticInput(SemanticTarget target, SemanticRun semantic) {
        StringBuilder builder = new StringBuilder();
        builder.append("Summarize this code symbol for future codebase diagnosis.\n");
        builder.append("Symbol: ").append(target.displayName()).append('\n');
        builder.append("Kind: ").append(target.kind()).append('\n');
        builder.append("File: ").append(target.filePath()).append('\n');
        builder.append("Lines: ").append(nullToZero(target.startLine())).append('-')
                .append(nullToZero(target.endLine())).append("\n\n");
        builder.append("Structural facts:\n").append(target.facts()).append("\n\n");
        builder.append("Masked snippet:\n").append(redact(target.snippet()));
        return truncateToTokens(builder.toString(), properties.semantic().maxPromptTokens());
    }

    private String redact(String text) {
        if (payloadRedactor == null) {
            return text == null ? "" : text;
        }
        return payloadRedactor.redact(text);
    }

    private static String extractiveSummary(SemanticTarget target) {
        return """
                Purpose: %s %s is declared in %s.
                Dependencies: see structural facts for calls, annotations, injections, and endpoint mappings.
                Side effects: unknown unless write/delete/update calls appear in structural facts.
                Evidence: %s:%d-%d.
                Facts:
                %s
                """.formatted(target.kind(), target.displayName(), target.filePath(), target.filePath(),
                nullToZero(target.startLine()), nullToZero(target.endLine()), target.facts()).trim();
    }

    private SemanticRun semanticRun(ScanCodebaseRequest request, UUID scanRunId) {
        ScannerProperties.Semantic semantic = properties.semantic();
        String provider = request.providerOverride() == null || request.providerOverride().isBlank()
                ? semantic.provider()
                : request.providerOverride();
        McpClientContext context = McpClientContextHolder.get();
        if (provider == null || !"extractive".equalsIgnoreCase(provider.trim())) {
            provider = context == null
                    ? providerOverrideSanitizer.sanitize(provider).orElse("local-qwen")
                    : providerOverrideSanitizer.sanitize(provider, context).orElse("local-qwen");
        } else {
            // Extractive capsules are structural summaries built without any LLM call.
            provider = "extractive";
        }
        // Effective semantic enablement = configured enabled AND not explicitly disabled per request.
        // A request-level semanticEnabled=false forces a structural-only run (no symbol/flow LLM capsules).
        boolean effectiveEnabled = semantic.enabled() && !Boolean.FALSE.equals(request.semanticEnabled());
        if (!effectiveEnabled) {
            provider = "extractive";
        }
        if (!semantic.allowedProviders().contains(provider) && !provider.equals("extractive")) {
            throw new IllegalArgumentException("Scanner semantic provider is not allowed: " + provider);
        }
        boolean dataEgress = EXTERNAL_PROVIDERS.contains(provider);
        if (dataEgress && !semantic.allowExternal()) {
            throw new IllegalArgumentException("External scanner semantic provider requires scanner.semantic.allow-external=true");
        }
        String model = request.semanticModel() == null || request.semanticModel().isBlank()
                ? semantic.model()
                : request.semanticModel().trim();
        return new SemanticRun(effectiveEnabled, provider, model, semantic.promptVersion(), dataEgress, scanRunId);
    }

    private static boolean semanticOverrideRequested(ScanCodebaseRequest request) {
        return (request.providerOverride() != null && !request.providerOverride().isBlank())
                || (request.semanticModel() != null && !request.semanticModel().isBlank());
    }

    private static boolean shouldSkipUnchangedFile(boolean force, boolean unchanged, SemanticRun semantic,
            ScanCodebaseRequest request) {
        if (!unchanged) {
            return false;
        }
        if (semanticDisabledPreservationSkip(unchanged, semantic)) {
            return true;
        }
        if (semanticOverrideRequested(request)) {
            return false;
        }
        if (!force) {
            return true;
        }
        return false;
    }

    private static boolean semanticDisabledPreservationSkip(boolean unchanged, SemanticRun semantic) {
        return unchanged && (semantic == null || !semantic.enabled());
    }

    private void upsertCodeFile(UUID scanRunId, String projectKey, String relativePath, String contentHash,
            String language) {
        codeBaselineRepository.upsertFile(new CodeFileRecord(
                fileId(projectKey, relativePath),
                projectKey,
                relativePath,
                contentHash,
                language,
                scanRunId,
                Map.of("scanner", "java-semantic-v1")));
    }

    private void upsertEdge(UUID scanRunId, String projectKey, UUID sourceSymbolId, String edgeType, String targetRef,
            double confidence, Map<String, Object> evidence) {
        codeBaselineRepository.upsertEdge(new CodeEdgeRecord(
                edgeId(projectKey, sourceSymbolId, edgeType, targetRef),
                projectKey,
                sourceSymbolId,
                null,
                targetRef,
                edgeType,
                "syntactic",
                confidence,
                scanRunId,
                evidence));
    }

    private void writeDiagnostic(UUID scanRunId, String projectKey, String relativePath, UUID symbolId,
            String severity, String code, String message, Map<String, Object> metadata) {
        codeBaselineRepository.insertDiagnostic(new CodeDiagnosticRecord(
                UUID.randomUUID(),
                projectKey,
                scanRunId,
                severity,
                code,
                message == null || message.isBlank() ? code : message,
                relativePath,
                symbolId,
                metadata == null ? Map.of() : metadata));
    }

    private List<Path> discoverFiles(Path root, ScanScope scope) {
        try (Stream<Path> stream = Files.walk(root)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> isInsideRoot(root, path))
                    .filter(this::isSupported)
                    .filter(path -> {
                        try {
                            return Files.size(path) <= properties.maxFileBytes();
                        } catch (IOException e) {
                            return false;
                        }
                    })
                    .filter(path -> !isExcluded(root, path))
                    .filter(path -> scope.matches(root, path))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot scan root path: " + root, e);
        }
    }

    private boolean isInsideRoot(Path root, Path path) {
        try {
            return path.toRealPath().startsWith(root);
        } catch (IOException e) {
            return false;
        }
    }

    private boolean isSupported(Path path) {
        String fileName = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return properties.supportedExtensions().stream()
                .anyMatch(extension -> extension.startsWith(".")
                        ? fileName.endsWith(extension)
                        : fileName.equals(extension));
    }

    private boolean isExcluded(Path root, Path path) {
        Path relative = root.relativize(path);
        for (Path part : relative) {
            if (properties.excludedDirectories().contains(part.toString())) {
                return true;
            }
        }
        return false;
    }

    private record ScanScope(List<String> includePaths, List<String> excludePaths) {

        static ScanScope from(ScanCodebaseRequest request, Path root) {
            return new ScanScope(normalizeScopePaths(request.includePaths(), root),
                    normalizeScopePaths(request.excludePaths(), root));
        }

        boolean scoped() {
            return !includePaths.isEmpty() || !excludePaths.isEmpty();
        }

        boolean matches(Path root, Path path) {
            String relative = normalizeRelativePath(root.relativize(path).toString());
            boolean included = includePaths.isEmpty()
                    || includePaths.stream().anyMatch(scope -> scopeMatches(relative, scope));
            if (!included) {
                return false;
            }
            return excludePaths.stream().noneMatch(scope -> scopeMatches(relative, scope));
        }

        Map<String, Object> metadata() {
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("scopedScan", scoped());
            if (!includePaths.isEmpty()) {
                metadata.put("includePaths", includePaths);
            }
            if (!excludePaths.isEmpty()) {
                metadata.put("excludePaths", excludePaths);
            }
            return metadata;
        }

        private static List<String> normalizeScopePaths(List<String> paths, Path root) {
            if (paths == null || paths.isEmpty()) {
                return List.of();
            }
            List<String> normalized = new ArrayList<>();
            for (String raw : paths) {
                if (raw == null || raw.isBlank()) {
                    continue;
                }
                String value = normalizeScopePath(raw, root);
                while (value.endsWith("/") && value.length() > 1) {
                    value = value.substring(0, value.length() - 1);
                }
                if (value.isBlank() || ".".equals(value)) {
                    continue;
                }
                if (hasParentTraversal(value)) {
                    throw new IllegalArgumentException("Scanner scope paths must stay under the scan root: " + raw);
                }
                if (!normalized.contains(value)) {
                    normalized.add(value);
                }
            }
            return List.copyOf(normalized);
        }

        private static String normalizeScopePath(String raw, Path root) {
            Path input = Path.of(raw.trim());
            if (input.isAbsolute()) {
                Path normalized = input.toAbsolutePath().normalize();
                try {
                    normalized = normalized.toRealPath();
                } catch (IOException e) {
                    throw new IllegalArgumentException("Cannot resolve scanner scope path: " + raw, e);
                }
                if (!normalized.startsWith(root)) {
                    throw new IllegalArgumentException("Scanner scope paths must stay under the scan root: " + raw);
                }
                return normalizeRelativePath(root.relativize(normalized).toString());
            }
            String value = normalizeRelativePath(raw.trim());
            while (value.startsWith("./")) {
                value = value.substring(2);
            }
            return value;
        }

        private static boolean hasParentTraversal(String value) {
            for (String part : value.split("/")) {
                if ("..".equals(part)) {
                    return true;
                }
            }
            return false;
        }

        private static boolean scopeMatches(String relative, String scope) {
            return relative.equals(scope) || relative.startsWith(scope + "/");
        }
    }

    private static final class SemanticScanBudget {
        private final int maxFiles;
        private final int maxSymbols;
        private int symbolsUsed;
        private int llmSummariesAttempted;
        private int semanticCapsulesWritten;
        private int semanticCapsulesReused;
        private int extractiveFallbacks;
        private int semanticErrors;
        private int flowCapsulesAttempted;
        private int flowCapsulesCompleted;
        private int flowCapsulesFallbacks;
        private int semanticCandidatesSkippedLowValue;
        private int extractiveDefaultCapsules;

        private SemanticScanBudget(int maxFiles, int maxSymbols) {
            this.maxFiles = maxFiles;
            this.maxSymbols = maxSymbols;
        }

        static SemanticScanBudget from(ScanCodebaseRequest request, ScannerProperties.Semantic semantic) {
            // null cap = unset -> configured default; an explicit cap (including 0 = structural-only) is
            // honored. Negative caps are rejected at ScanCodebaseRequest construction.
            int maxFiles = semantic.maxFilesPerRun();
            if (request.maxSemanticFiles() != null) {
                maxFiles = Math.min(maxFiles, request.maxSemanticFiles());
            }
            int maxSymbols = Integer.MAX_VALUE;
            if (request.maxSemanticSymbols() != null) {
                maxSymbols = request.maxSemanticSymbols();
            }
            return new SemanticScanBudget(maxFiles, maxSymbols);
        }

        boolean canScanFile(int semanticFiles) {
            return semanticFiles < maxFiles && symbolsUsed < maxSymbols;
        }

        boolean tryReserveSymbol() {
            if (symbolsUsed >= maxSymbols) {
                return false;
            }
            symbolsUsed++;
            return true;
        }

        int symbolsUsed() {
            return symbolsUsed;
        }

        void recordLlmAttempt() {
            llmSummariesAttempted++;
        }

        void recordCapsuleWritten() {
            semanticCapsulesWritten++;
        }

        void recordReused() {
            semanticCapsulesReused++;
        }

        void recordExtractiveFallback() {
            extractiveFallbacks++;
        }

        void recordSemanticError() {
            semanticErrors++;
        }

        void recordFlowAttempt() {
            flowCapsulesAttempted++;
        }

        void recordFlowCompleted() {
            flowCapsulesCompleted++;
        }

        void recordFlowFallback() {
            flowCapsulesFallbacks++;
        }

        void recordSkippedLowValue() {
            semanticCandidatesSkippedLowValue++;
        }

        void recordExtractiveDefault() {
            extractiveDefaultCapsules++;
        }

        Map<String, Object> metadata() {
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("maxSemanticFiles", maxFiles);
            if (maxSymbols != Integer.MAX_VALUE) {
                metadata.put("maxSemanticSymbols", maxSymbols);
            }
            putCounters(metadata);
            return metadata;
        }

        private void putCounters(Map<String, Object> metadata) {
            metadata.put("semanticCandidatesSelected", symbolsUsed);
            metadata.put("llmSummariesAttempted", llmSummariesAttempted);
            metadata.put("semanticCapsulesWritten", semanticCapsulesWritten);
            metadata.put("semanticCapsulesReused", semanticCapsulesReused);
            metadata.put("extractiveFallbacks", extractiveFallbacks);
            metadata.put("semanticErrors", semanticErrors);
            metadata.put("flowCapsulesAttempted", flowCapsulesAttempted);
            metadata.put("flowCapsulesCompleted", flowCapsulesCompleted);
            metadata.put("flowCapsulesFallbacks", flowCapsulesFallbacks);
            metadata.put("semanticCandidatesSkippedLowValue", semanticCandidatesSkippedLowValue);
            metadata.put("extractiveDefaultCapsules", extractiveDefaultCapsules);
        }

        // Single source of truth for progress snapshots, so heartbeat and completion metadata never diverge.
        Map<String, Object> heartbeatMetadata(String phase, String currentFile, String currentSymbolKind,
                String currentSymbolFqn) {
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("phase", phase);
            metadata.put("currentFile", currentFile);
            metadata.put("currentSymbolKind", currentSymbolKind);
            metadata.put("currentSymbolFqn", currentSymbolFqn);
            metadata.put("lastProgressAt", Instant.now().toString());
            putCounters(metadata);
            return metadata;
        }

        // Flow progress snapshot: preserves existing flowCapsulesWritten/Target and adds the shared counters.
        Map<String, Object> flowHeartbeatMetadata(int flowCapsulesWritten, int flowCapsulesTarget) {
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("phase", "flow-capsules");
            metadata.put("flowCapsulesWritten", flowCapsulesWritten);
            metadata.put("flowCapsulesTarget", flowCapsulesTarget);
            metadata.put("lastProgressAt", Instant.now().toString());
            putCounters(metadata);
            return metadata;
        }
    }

    private static String normalizeRelativePath(String value) {
        return value.replace('\\', '/');
    }

    private Path normalizeRoot(String rootPath) {
        Path root = rootPath == null || rootPath.isBlank() ? Path.of(".") : Path.of(rootPath.trim());
        Path normalized = root.toAbsolutePath().normalize();
        if (!Files.isDirectory(normalized)) {
            throw new IllegalArgumentException("Scan root must be a directory: " + normalized);
        }
        try {
            return normalized.toRealPath();
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot resolve scan root path: " + normalized, e);
        }
    }

    private static String classify(ClassOrInterfaceDeclaration type) {
        String name = type.getNameAsString();
        boolean restController = type.getAnnotations().stream()
                .anyMatch(annotation -> annotation.getNameAsString().equals("RestController"));
        if (restController || name.endsWith("Controller")) {
            return "controller";
        }
        if (name.endsWith("Service")) {
            return "service";
        }
        if (name.endsWith("Repository")) {
            return "repository";
        }
        return type.isInterface() ? "interface" : "component";
    }

    private static List<String> annotationNames(Node node) {
        List<AnnotationExpr> annotations;
        if (node instanceof ClassOrInterfaceDeclaration type) {
            annotations = type.getAnnotations();
        } else if (node instanceof MethodDeclaration method) {
            annotations = method.getAnnotations();
        } else {
            annotations = List.of();
        }
        return annotations.stream()
                .map(annotation -> annotation.getNameAsString())
                .distinct()
                .toList();
    }

    private static Optional<String> endpointRef(AnnotationExpr annotation) {
        return endpointRef(annotation, "");
    }

    private static Optional<String> endpointRef(AnnotationExpr annotation, String pathPrefix) {
        String annotationName = simpleAnnotationName(annotation.getNameAsString());
        String httpMethod = switch (annotationName) {
            case "GetMapping" -> "GET";
            case "PostMapping" -> "POST";
            case "PutMapping" -> "PUT";
            case "DeleteMapping" -> "DELETE";
            case "PatchMapping" -> "PATCH";
            case "RequestMapping" -> requestMappingMethod(annotation).orElse("ANY");
            default -> "";
        };
        if (httpMethod.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(httpMethod + " " + normalizeEndpointPath(joinEndpointPaths(pathPrefix,
                mappingPath(annotation).orElse(""))));
    }

    private static String endpointPrefix(ClassOrInterfaceDeclaration type) {
        return type.getAnnotations().stream()
                .filter(annotation -> simpleAnnotationName(annotation.getNameAsString()).equals("RequestMapping"))
                .findFirst()
                .flatMap(ScannerService::mappingPath)
                .orElse("");
    }

    private static Optional<String> mappingPath(AnnotationExpr annotation) {
        if (annotation instanceof SingleMemberAnnotationExpr singleMember) {
            return firstExpressionValue(singleMember.getMemberValue());
        }
        if (annotation instanceof NormalAnnotationExpr normalAnnotation) {
            return normalAnnotation.getPairs().stream()
                    .filter(pair -> pair.getNameAsString().equals("value") || pair.getNameAsString().equals("path"))
                    .findFirst()
                    .flatMap(pair -> firstExpressionValue(pair.getValue()));
        }
        return Optional.empty();
    }

    private static Optional<String> requestMappingMethod(AnnotationExpr annotation) {
        if (!(annotation instanceof NormalAnnotationExpr normalAnnotation)) {
            return Optional.empty();
        }
        return normalAnnotation.getPairs().stream()
                .filter(pair -> pair.getNameAsString().equals("method"))
                .findFirst()
                .flatMap(pair -> firstExpressionValue(pair.getValue()))
                .map(value -> {
                    String upper = value.toUpperCase(Locale.ROOT);
                    for (String candidate : List.of("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS")) {
                        if (upper.contains(candidate)) {
                            return candidate;
                        }
                    }
                    return value;
                });
    }

    private static Optional<String> firstExpressionValue(Expression expression) {
        if (expression instanceof ArrayInitializerExpr arrayInitializer) {
            return arrayInitializer.getValues().stream()
                    .findFirst()
                    .flatMap(ScannerService::firstExpressionValue);
        }
        if (expression.isStringLiteralExpr()) {
            return Optional.of(expression.asStringLiteralExpr().asString());
        }
        return Optional.of(expression.toString());
    }

    private static String normalizeEndpointPath(String path) {
        String normalized = path == null ? "" : path.trim();
        if (normalized.isBlank()) {
            return "/";
        }
        return normalized.startsWith("/") ? normalized : "/" + normalized;
    }

    private static String joinEndpointPaths(String prefix, String path) {
        String left = stripEndpointSlashes(prefix);
        String right = stripEndpointSlashes(path);
        if (left.isBlank()) {
            return right;
        }
        if (right.isBlank()) {
            return left;
        }
        return left + "/" + right;
    }

    private static String stripEndpointSlashes(String value) {
        String stripped = value == null ? "" : value.trim();
        while (stripped.startsWith("/")) {
            stripped = stripped.substring(1);
        }
        while (stripped.endsWith("/")) {
            stripped = stripped.substring(0, stripped.length() - 1);
        }
        return stripped;
    }

    private static String simpleAnnotationName(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(dot + 1);
    }

    private static List<ClassOrInterfaceDeclaration> nestedTypes(ClassOrInterfaceDeclaration type) {
        List<ClassOrInterfaceDeclaration> nested = new ArrayList<>();
        for (BodyDeclaration<?> member : type.getMembers()) {
            if (member instanceof ClassOrInterfaceDeclaration nestedType) {
                nested.add(nestedType);
            }
        }
        return nested;
    }

    private static List<String> methodCalls(MethodDeclaration method) {
        return method.findAll(MethodCallExpr.class).stream()
                .map(MethodCallExpr::getNameAsString)
                .filter(value -> value != null && !value.isBlank())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet<String>::new))
                .stream()
                .limit(40)
                .toList();
    }

    private static List<String> injectedTypes(ClassOrInterfaceDeclaration type) {
        List<String> injected = new ArrayList<>();
        // D1 is a syntactic pass: direct fields and constructors only, without Lombok or symbol resolution.
        for (FieldDeclaration field : type.getFields()) {
            if (field.getAnnotations().stream().anyMatch(annotation -> annotation.getNameAsString().equals("Autowired"))) {
                field.getVariables().forEach(variable -> injected.add(variable.getTypeAsString()));
            }
        }
        type.getConstructors().stream()
                .filter(constructor -> type.getConstructors().size() == 1 || constructor.getAnnotations().stream()
                        .anyMatch(annotation -> annotation.getNameAsString().equals("Autowired")))
                .forEach(constructor -> constructor.getParameters()
                        .forEach(parameter -> injected.add(parameter.getTypeAsString())));
        return injected.stream().distinct().toList();
    }

    private static String classFacts(CodeSymbolRecord symbol, List<String> methodNames,
            ClassOrInterfaceDeclaration type) {
        return """
                role=%s
                fqn=%s
                annotations=%s
                methods=%s
                extends=%s
                implements=%s
                injectedTypes=%s
                """.formatted(symbol.role(), symbol.fqn(), annotationNames(type), methodNames,
                type.getExtendedTypes().stream().map(Object::toString).toList(),
                type.getImplementedTypes().stream().map(Object::toString).toList(),
                injectedTypes(type)).trim();
    }

    private static String methodFacts(CodeSymbolRecord symbol, List<String> calls, MethodDeclaration method) {
        return """
                fqn=%s
                signature=%s
                returnType=%s
                annotations=%s
                calls=%s
                sideEffectHints=%s
                """.formatted(symbol.fqn(), symbol.signature(), method.getTypeAsString(), annotationNames(method),
                calls, sideEffectHints(calls)).trim();
    }

    private static List<String> sideEffectHints(List<String> calls) {
        return calls.stream()
                .filter(call -> {
                    String lower = call.toLowerCase(Locale.ROOT);
                    return lower.contains("save") || lower.contains("delete") || lower.contains("update")
                            || lower.contains("insert") || lower.contains("publish") || lower.contains("send");
                })
                .toList();
    }

    private static String signature(MethodDeclaration method) {
        String params = method.getParameters().stream()
                .map(parameter -> parameter.getTypeAsString())
                .reduce((left, right) -> left + "," + right)
                .orElse("");
        return method.getNameAsString() + "(" + params + ")";
    }

    private static String methodFqn(ResolvedMethodDeclaration method) {
        return method.declaringType().getQualifiedName() + "#" + method.getName();
    }

    private static boolean signaturesMatch(String storedSignature, String resolvedSignature) {
        if (storedSignature == null || resolvedSignature == null) {
            return false;
        }
        return storedSignature.equals(resolvedSignature)
                || storedSignature.equals(simplifySignature(resolvedSignature));
    }

    private static String simplifySignature(String signature) {
        int left = signature.indexOf('(');
        int right = signature.lastIndexOf(')');
        if (left < 0 || right < left) {
            return signature;
        }
        String params = signature.substring(left + 1, right);
        if (params.isBlank()) {
            return signature.substring(0, left) + "()";
        }
        String simplifiedParams = java.util.Arrays.stream(params.split(","))
                .map(String::trim)
                .map(ScannerService::simpleTypeName)
                .reduce((leftParam, rightParam) -> leftParam + "," + rightParam)
                .orElse("");
        return signature.substring(0, left) + "(" + simplifiedParams + ")";
    }

    private static String simpleTypeName(String typeName) {
        String text = typeName.replace("...", "[]");
        int generic = text.indexOf('<');
        String suffix = generic >= 0 ? text.substring(generic) : "";
        String base = generic >= 0 ? text.substring(0, generic) : text;
        int array = base.indexOf('[');
        String arraySuffix = array >= 0 ? base.substring(array) : "";
        base = array >= 0 ? base.substring(0, array) : base;
        int dot = base.lastIndexOf('.');
        return (dot >= 0 ? base.substring(dot + 1) : base) + arraySuffix + simplifyGenericSuffix(suffix);
    }

    private static String simplifyGenericSuffix(String suffix) {
        if (suffix.isBlank()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        StringBuilder token = new StringBuilder();
        for (char ch : suffix.toCharArray()) {
            if (Character.isJavaIdentifierPart(ch) || ch == '.') {
                token.append(ch);
            } else {
                if (token.length() > 0) {
                    builder.append(simpleTypeName(token.toString()));
                    token.setLength(0);
                }
                builder.append(ch);
            }
        }
        if (token.length() > 0) {
            builder.append(simpleTypeName(token.toString()));
        }
        return builder.toString();
    }

    private static Integer startLine(Node node) {
        return node.getRange().map(range -> range.begin.line).orElse(null);
    }

    private static Integer endLine(Node node) {
        return node.getRange().map(range -> range.end.line).orElse(null);
    }

    private static int nullToZero(Integer value) {
        return value == null ? 0 : value;
    }

    private static String language(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".java")) {
            return "java";
        }
        if (name.endsWith(".py")) {
            return "python";
        }
        Optional<KotlinSwiftParser.Language> mobileLanguage = KotlinSwiftParser.Language.of(name);
        if (mobileLanguage.isPresent()) {
            return mobileLanguage.get().id;
        }
        if (name.equals("pom.xml")) {
            return "maven";
        }
        if (name.equals("package.json")) {
            return "node";
        }
        return "unknown";
    }

    private static String snippet(String value) {
        return value == null ? "" : value.substring(0, Math.min(value.length(), 2500));
    }

    private static String stringEvidence(CodeSemanticCapsuleRecord capsule, String key) {
        Object value = capsule.evidence().get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private static String truncateToTokens(String value, int maxTokens) {
        if (TokenEstimator.estimate(value) <= maxTokens) {
            return value;
        }
        int maxChars = Math.max(500, maxTokens * 4);
        return value.substring(0, Math.min(value.length(), maxChars));
    }

    private static String firstLine(String value) {
        String text = value == null ? "" : value.trim();
        if (text.isBlank()) {
            return "Scanner semantic summary";
        }
        String first = text.lines().findFirst().orElse(text).trim();
        return first.length() <= 220 ? first : first.substring(0, 220);
    }

    private static String flowPromptVersion(SemanticRun semantic) {
        return semantic.promptVersion() + ":" + FLOW_PROMPT_VERSION;
    }

    private static String flowFilePath(CodeFlowContext context) {
        if (context == null || context.seed() == null) {
            return "";
        }
        if (context.seed().filePath() != null && !context.seed().filePath().isBlank()) {
            return context.seed().filePath();
        }
        return context.symbols().stream()
                .map(CodeFlowContextSymbol::filePath)
                .filter(value -> value != null && !value.isBlank())
                .findFirst()
                .orElse("");
    }

    private static String stripJsonFence(String value) {
        String text = value == null ? "" : value.trim();
        if (!text.startsWith("```")) {
            return text;
        }
        int firstLineEnd = text.indexOf('\n');
        if (firstLineEnd < 0) {
            return text;
        }
        int fenceEnd = text.lastIndexOf("```");
        if (fenceEnd <= firstLineEnd) {
            return text.substring(firstLineEnd + 1).trim();
        }
        return text.substring(firstLineEnd + 1, fenceEnd).trim();
    }

    private static String jsonText(JsonNode json, String field, String fallback) {
        JsonNode value = json == null ? null : json.get(field);
        if (value == null || value.isNull()) {
            return fallback == null ? "" : fallback;
        }
        if (value.isTextual()) {
            String text = value.asText();
            return text == null || text.isBlank() ? (fallback == null ? "" : fallback) : text.trim();
        }
        String text = value.toString();
        return text.isBlank() ? (fallback == null ? "" : fallback) : text;
    }

    private static List<String> jsonList(JsonNode json, String field) {
        JsonNode value = json == null ? null : json.get(field);
        if (value == null || value.isNull()) {
            return List.of();
        }
        if (value.isArray()) {
            List<String> values = new ArrayList<>();
            for (JsonNode item : value) {
                String text = item.isTextual() ? item.asText() : item.toString();
                if (text != null && !text.isBlank()) {
                    values.add(text.trim());
                }
            }
            return List.copyOf(values);
        }
        if (value.isTextual() && !value.asText().isBlank()) {
            return List.of(value.asText().trim());
        }
        return List.of();
    }

    private static String bulletList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "- unknown";
        }
        return values.stream()
                .map(value -> "- " + value)
                .reduce((left, right) -> left + "\n" + right)
                .orElse("- unknown");
    }

    private static boolean flowCapsuleKind(String capsuleKind) {
        return CodeFlowSeedBuilder.ENDPOINT_FLOW.equals(capsuleKind)
                || CodeFlowSeedBuilder.DOMAIN_FLOW.equals(capsuleKind)
                || CodeFlowSeedBuilder.INTEGRATION_FLOW.equals(capsuleKind)
                || CodeFlowSeedBuilder.CACHE_FLOW.equals(capsuleKind)
                || CodeFlowSeedBuilder.PROJECT_INSIGHT.equals(capsuleKind);
    }

    private static MemoryStatus memoryStatusFor(MemoryWriteGate.Verdict verdict) {
        return switch (verdict) {
            case AUTO_ACTIVE -> MemoryStatus.ACTIVE;
            case REJECTED -> MemoryStatus.REJECTED;
        };
    }

    private static UUID fileId(String projectKey, String relativePath) {
        return deterministicUuid("file|" + projectKey + "|" + relativePath.replace('\\', '/'));
    }

    private static UUID symbolId(String projectKey, String relativePath, String kind, String name, String signature) {
        return deterministicUuid("symbol|" + projectKey + "|" + relativePath.replace('\\', '/') + "|" + kind + "|"
                + name + "|" + (signature == null ? "" : signature));
    }

    private static UUID edgeId(String projectKey, UUID sourceSymbolId, String edgeType, String targetRef) {
        return deterministicUuid("edge|" + projectKey + "|" + sourceSymbolId + "|" + edgeType + "|"
                + (targetRef == null ? "" : targetRef));
    }

    private static UUID capsuleId(String projectKey, String targetKey, String kind, String provider, String model,
            String promptVersion, String inputHash) {
        return deterministicUuid("capsule|" + projectKey + "|" + targetKey + "|" + kind + "|" + provider + "|"
                + model + "|" + promptVersion + "|" + inputHash);
    }

    private static UUID deterministicUuid(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private record SemanticRun(
            boolean enabled,
            String provider,
            String model,
            String promptVersion,
            boolean dataEgress,
            UUID scanRunId) {
    }

    private record FlowSummary(
            String summary,
            String text,
            List<String> steps,
            List<String> cacheKeys,
            List<String> externalClients,
            List<String> repositories,
            List<String> requestModels,
            List<String> responseModels,
            List<String> branches,
            List<String> failurePoints,
            List<String> evidence) {

        private FlowSummary {
            summary = summary == null || summary.isBlank() ? "Scanner flow summary" : summary.trim();
            text = text == null || text.isBlank() ? summary : text.trim();
            steps = steps == null ? List.of() : List.copyOf(steps);
            cacheKeys = cacheKeys == null ? List.of() : List.copyOf(cacheKeys);
            externalClients = externalClients == null ? List.of() : List.copyOf(externalClients);
            repositories = repositories == null ? List.of() : List.copyOf(repositories);
            requestModels = requestModels == null ? List.of() : List.copyOf(requestModels);
            responseModels = responseModels == null ? List.of() : List.copyOf(responseModels);
            branches = branches == null ? List.of() : List.copyOf(branches);
            failurePoints = failurePoints == null ? List.of() : List.copyOf(failurePoints);
            evidence = evidence == null ? List.of() : List.copyOf(evidence);
        }

        private List<String> cacheKeysOr(List<String> fallback) {
            return cacheKeys.isEmpty() ? fallback : cacheKeys;
        }

        private List<String> externalClientsOr(List<String> fallback) {
            return externalClients.isEmpty() ? fallback : externalClients;
        }

        private List<String> repositoriesOr(List<String> fallback) {
            return repositories.isEmpty() ? fallback : repositories;
        }

        private List<String> requestModelsOr(List<String> fallback) {
            return requestModels.isEmpty() ? fallback : requestModels;
        }

        private List<String> responseModelsOr(List<String> fallback) {
            return responseModels.isEmpty() ? fallback : responseModels;
        }

    }

    private record SemanticTarget(
            UUID symbolId,
            UUID fileId,
            String projectKey,
            String kind,
            String name,
            String displayName,
            String filePath,
            Integer startLine,
            Integer endLine,
            String facts,
            String snippet) {

    }

}
