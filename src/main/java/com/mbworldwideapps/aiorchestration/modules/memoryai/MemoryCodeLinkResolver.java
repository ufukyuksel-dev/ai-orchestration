package com.mbworldwideapps.aiorchestration.modules.memoryai;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.mbworldwideapps.aiorchestration.config.MemoryCodeLinkProperties;
import com.mbworldwideapps.aiorchestration.modules.scanner.CodeBaselineRepository;
import com.mbworldwideapps.aiorchestration.modules.scanner.CodeBaselineVectorIndex;
import com.mbworldwideapps.aiorchestration.modules.scanner.CodeCapsuleLinkTargetRecord;
import com.mbworldwideapps.aiorchestration.modules.scanner.CodeFileRecord;
import com.mbworldwideapps.aiorchestration.modules.scanner.CodeSymbolRecord;
import com.mbworldwideapps.aiorchestration.modules.scanner.ScoredCodeCapsuleRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class MemoryCodeLinkResolver {

    private static final Logger log = LoggerFactory.getLogger(MemoryCodeLinkResolver.class);
    private static final String SCANNER_FLOW_INSIGHT_PREFIX = "scanner-flow-insight:";

    private final MemoryCodeLinkProperties properties;
    private final CodeBaselineRepository codeBaselineRepository;
    private final CodeBaselineVectorIndex codeBaselineVectorIndex;

    public MemoryCodeLinkResolver(MemoryCodeLinkProperties properties, CodeBaselineRepository codeBaselineRepository,
            CodeBaselineVectorIndex codeBaselineVectorIndex) {
        this.properties = properties;
        this.codeBaselineRepository = codeBaselineRepository;
        this.codeBaselineVectorIndex = codeBaselineVectorIndex;
    }

    public MemoryCodeLinkResolveResult resolve(MemoryItem item) {
        if (item == null) {
            return MemoryCodeLinkResolveResult.empty();
        }
        Collector collector = new Collector();
        if (properties.deterministicLinksEnabled()) {
            resolveDeterministic(item, collector);
        }
        if (properties.similarityBackfillEnabled()) {
            resolveSimilarity(item, collector);
        }
        return collector.result();
    }

    private void resolveDeterministic(MemoryItem item, Collector collector) {
        Map<String, Object> metadata = metadata(item);
        MemoryCodeLocatorMetadata.Decoded typedLocators;
        try {
            typedLocators = MemoryCodeLocatorMetadata.read(metadata);
        } catch (IllegalArgumentException e) {
            collector.unresolvedSourceRefs++;
            log.warn("Invalid typed memory-code locator metadata ignored: memoryId={} reason={}", item.id(),
                    e.getMessage());
            return;
        }
        if (typedLocators.present()) {
            resolveTypedLocators(item, typedLocators.items(), collector);
            return;
        }
        Optional<CodeCapsuleLinkTargetRecord> scannerCapsule = resolveScannerInsight(item, collector);
        scannerCapsule.ifPresent(capsule -> addCapsuleLinks(item, capsule, collector));
        resolveFileMetadata(item, metadata, scannerCapsule.orElse(null), collector);
        resolveSymbolMetadata(item, metadata, scannerCapsule.orElse(null), collector);
    }

    private void resolveTypedLocators(MemoryItem item, List<MemoryCodeLocator> locators, Collector collector) {
        String projectKey = trim(item.projectKey());
        if (projectKey.isBlank()) {
            if (!locators.isEmpty()) {
                collector.unresolvedSourceRefs += locators.size();
                collector.missingTypedTargets += locators.size();
            }
            return;
        }
        for (MemoryCodeLocator locator : locators) {
            switch (locator.kind()) {
                case DIRECTORY -> resolveTypedDirectory(item, projectKey, locator, collector);
                case FILE -> resolveTypedFile(item, projectKey, locator, collector);
                case SYMBOL -> resolveTypedSymbol(item, projectKey, locator, collector);
                case CAPSULE -> resolveTypedCapsule(item, projectKey, locator, collector);
            }
        }
    }

    private void resolveTypedCapsule(MemoryItem item, String projectKey, MemoryCodeLocator locator,
            Collector collector) {
        Optional<CodeCapsuleLinkTargetRecord> target = codeBaselineRepository.findCurrentCapsuleLinkTarget(projectKey,
                locator.ref(), locator.capsuleKind()).filter(row -> projectKey.equals(row.projectKey()));
        if (target.isEmpty()) {
            collector.unresolvedSourceRefs++;
            collector.missingTypedTargets++;
            return;
        }
        CodeCapsuleLinkTargetRecord capsule = target.get();
        String capsuleLogicalKey = capsuleLogicalKey(projectKey, capsule.targetKey(), capsule.capsuleKind());
        Map<String, Object> evidence = typedEvidence(locator);
        evidence.put("capsuleLogicalKey", capsuleLogicalKey);
        putIfPresent(evidence, "outputHash", capsule.outputHash());
        collector.add(new MemoryCodeLinkRecord(
                edgeId(item.id(), relationshipType(item, locator), "capsule", capsuleLogicalKey, "typed_locator"),
                item.id(), projectKey, relationshipType(item, locator), MemoryCodeTargetKind.CAPSULE, null,
                capsuleLogicalKey, null, null, "typed_locator",
                Math.min(item.confidence(), capsuleConfidence(capsule)), "metadata", evidence,
                capsule.outputHash(), false, 1.0));
    }

    private void resolveTypedDirectory(MemoryItem item, String projectKey, MemoryCodeLocator locator,
            Collector collector) {
        if (!codeBaselineRepository.directoryExists(projectKey, locator.ref())) {
            collector.missingFiles++;
            collector.missingTypedTargets++;
            return;
        }
        collector.add(new MemoryCodeLinkRecord(
                edgeId(item.id(), MemoryCodeRelationshipType.MENTIONS, "directory", locator.ref(), "typed_locator"),
                item.id(), projectKey, MemoryCodeRelationshipType.MENTIONS, MemoryCodeTargetKind.DIRECTORY,
                locator.ref(), null, null, null, "typed_locator", item.confidence(), "metadata",
                typedEvidence(locator), null, false, 0.7));
    }

    private void resolveTypedFile(MemoryItem item, String projectKey, MemoryCodeLocator locator,
            Collector collector) {
        Optional<CodeFileRecord> target = codeBaselineRepository.findFileByPath(projectKey, locator.ref())
                .filter(row -> projectKey.equals(row.projectKey()));
        if (target.isEmpty()) {
            collector.missingFiles++;
            collector.missingTypedTargets++;
            return;
        }
        CodeFileRecord file = target.get();
        MemoryCodeRelationshipType relationshipType = relationshipType(item, locator);
        collector.add(new MemoryCodeLinkRecord(
                edgeId(item.id(), relationshipType, "file", string(file.id()), "typed_locator"),
                item.id(), projectKey, relationshipType, MemoryCodeTargetKind.FILE, null, null, file.id(), null,
                "typed_locator", item.confidence(), "metadata", codeEvidence(typedEvidence(locator), file.filePath(), file.contentHash()), null, false, 0.7));
    }

    private void resolveTypedSymbol(MemoryItem item, String projectKey, MemoryCodeLocator locator,
            Collector collector) {
        Optional<CodeSymbolRecord> target = resolveSymbolId(projectKey, locator.ref());
        if (target.isEmpty()) {
            target = resolveExactSymbolRef(projectKey, locator.ref());
        }
        if (target.isEmpty()) {
            collector.missingSymbols++;
            collector.missingTypedTargets++;
            return;
        }
        CodeSymbolRecord symbol = target.get();
        MemoryCodeRelationshipType relationshipType = relationshipType(item, locator);
        collector.add(new MemoryCodeLinkRecord(
                edgeId(item.id(), relationshipType, "symbol", string(symbol.id()), "typed_locator"),
                item.id(), projectKey, relationshipType, MemoryCodeTargetKind.SYMBOL, null, null, null, symbol.id(),
                "typed_locator", item.confidence(), "metadata", codeEvidence(typedEvidence(locator), symbolRef(symbol), symbol.contentHash()), null, false,
                relationshipType == MemoryCodeRelationshipType.CONSTRAINS ? 0.8 : 0.6));
    }

    private static MemoryCodeRelationshipType relationshipType(MemoryItem item, MemoryCodeLocator locator) {
        MemoryCodeLocatorRelationship relationship = locator.effectiveRelationship(item.memoryType());
        return switch (relationship) {
            case EVIDENCES -> MemoryCodeRelationshipType.EVIDENCES;
            case MENTIONS -> MemoryCodeRelationshipType.MENTIONS;
            case CONSTRAINS -> MemoryCodeRelationshipType.CONSTRAINS;
        };
    }

    private static Map<String, Object> typedEvidence(MemoryCodeLocator locator) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("source", "typed_locator");
        evidence.put("kind", locator.kind().value());
        evidence.put("ref", locator.ref());
        putIfPresent(evidence, "capsuleKind", locator.capsuleKind());
        putIfPresent(evidence, "relationship",
                locator.relationship() == null ? null : locator.relationship().value());
        return evidence;
    }

    private static Map<String,Object> codeEvidence(Map<String,Object> evidence, String ref, String hash) {
        Map<String,Object> result = new LinkedHashMap<>(evidence);
        putIfPresent(result, "canonicalRef", ref); putIfPresent(result, "targetContentHash", hash);
        return result;
    }

    private static String symbolRef(CodeSymbolRecord symbol) {
        return trim(symbol.fqn()) + "|" + trim(symbol.signature());
    }

    private Optional<CodeCapsuleLinkTargetRecord> resolveScannerInsight(MemoryItem item, Collector collector) {
        String sourceRef = trim(item.sourceRef());
        if (!sourceRef.startsWith(SCANNER_FLOW_INSIGHT_PREFIX)) {
            return Optional.empty();
        }
        String rawId = sourceRef.substring(SCANNER_FLOW_INSIGHT_PREFIX.length()).trim();
        UUID capsuleId;
        try {
            capsuleId = UUID.fromString(rawId);
        } catch (IllegalArgumentException e) {
            collector.unresolvedSourceRefs++;
            return Optional.empty();
        }
        Optional<CodeCapsuleLinkTargetRecord> physical =
                codeBaselineRepository.findCapsuleLinkTargetById(capsuleId);
        if (physical.isEmpty()) {
            collector.unresolvedSourceRefs++;
            return Optional.empty();
        }
        CodeCapsuleLinkTargetRecord row = physical.get();
        String memoryProjectKey = trim(item.projectKey());
        if (!memoryProjectKey.isBlank() && !memoryProjectKey.equals(trim(row.projectKey()))) {
            collector.unresolvedSourceRefs++;
            return Optional.empty();
        }
        Optional<CodeCapsuleLinkTargetRecord> current = codeBaselineRepository.findCurrentCapsuleLinkTarget(
                row.projectKey(), row.targetKey(), row.capsuleKind());
        if (current.isEmpty()) {
            collector.unresolvedSourceRefs++;
            return Optional.empty();
        }
        return current;
    }

    private void addCapsuleLinks(MemoryItem item, CodeCapsuleLinkTargetRecord capsule, Collector collector) {
        String capsuleLogicalKey = capsuleLogicalKey(capsule.projectKey(), capsule.targetKey(), capsule.capsuleKind());
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("sourceRef", item.sourceRef());
        evidence.put("capsuleId", string(capsule.id()));
        evidence.put("capsuleLogicalKey", capsuleLogicalKey);
        evidence.put("targetKey", capsule.targetKey());
        evidence.put("capsuleKind", capsule.capsuleKind());
        putIfPresent(evidence, "filePath", capsule.evidence().get("filePath"));

        collector.add(new MemoryCodeLinkRecord(
                edgeId(item.id(), MemoryCodeRelationshipType.EVIDENCES, "capsule", capsuleLogicalKey,
                        "source_ref"),
                item.id(),
                capsule.projectKey(),
                MemoryCodeRelationshipType.EVIDENCES,
                MemoryCodeTargetKind.CAPSULE, null,
                capsuleLogicalKey,
                null,
                null,
                "source_ref",
                Math.min(item.confidence(), capsuleConfidence(capsule)),
                "scanner",
                evidence,
                capsule.outputHash(),
                false,
                1.0));
    }

    private void resolveFileMetadata(MemoryItem item, Map<String, Object> metadata,
            CodeCapsuleLinkTargetRecord scannerCapsule, Collector collector) {
        String projectKey = projectKey(item, scannerCapsule);
        UUID fileId = scannerCapsule == null ? null : scannerCapsule.fileId();
        String filePath = stringValue(metadata.get("filePath"));
        if (filePath.isBlank() && scannerCapsule != null) {
            filePath = stringValue(scannerCapsule.evidence().get("filePath"));
        }
        if (fileId == null && !filePath.isBlank() && !projectKey.isBlank()) {
            Optional<CodeFileRecord> file = codeBaselineRepository.findFileByPath(projectKey, filePath);
            if (file.isPresent()) {
                fileId = file.get().id();
            } else {
                collector.missingFiles++;
            }
        }
        if (fileId == null) {
            return;
        }
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("source", scannerCapsule == null ? "metadata" : "scanner");
        putIfPresent(evidence, "filePath", filePath);
        putIfPresent(evidence, "sourceRef", item.sourceRef());
        Optional<CodeFileRecord> currentFile = codeBaselineRepository.findFileById(projectKey, fileId);
        if (currentFile.isPresent()) evidence.putAll(codeEvidence(Map.of(), currentFile.get().filePath(), currentFile.get().contentHash()));
        collector.add(new MemoryCodeLinkRecord(
                edgeId(item.id(), MemoryCodeRelationshipType.MENTIONS, "file", string(fileId), "metadata:filePath"),
                item.id(),
                projectKey,
                MemoryCodeRelationshipType.MENTIONS,
                MemoryCodeTargetKind.FILE, null,
                null,
                fileId,
                null,
                "metadata",
                item.confidence(),
                scannerCapsule == null ? "metadata" : "scanner",
                evidence,
                null,
                false,
                0.7));
    }

    private void resolveSymbolMetadata(MemoryItem item, Map<String, Object> metadata,
            CodeCapsuleLinkTargetRecord scannerCapsule, Collector collector) {
        String projectKey = projectKey(item, scannerCapsule);
        if (projectKey.isBlank()) {
            return;
        }
        CodeSymbolRecord symbol = null;
        String symbolIdValue = stringValue(metadata.get("symbolId"));
        if (!symbolIdValue.isBlank()) {
            symbol = resolveSymbolId(projectKey, symbolIdValue).orElse(null);
            if (symbol == null) {
                collector.missingSymbols++;
            }
        }
        String symbolRef = stringValue(metadata.get("symbolRef"));
        if (symbol == null && !symbolRef.isBlank()) {
            symbol = resolveExactSymbolRef(projectKey, symbolRef).orElse(null);
            if (symbol == null) {
                collector.missingSymbols++;
            }
        }
        if (symbol == null && scannerCapsule != null && scannerCapsule.symbolId() != null) {
            symbol = codeBaselineRepository.findSymbolById(scannerCapsule.symbolId())
                    .filter(candidate -> projectKey.equals(candidate.projectKey()))
                    .orElse(null);
            if (symbol == null) {
                collector.missingSymbols++;
            }
        }
        if (symbol == null) {
            return;
        }
        MemoryCodeRelationshipType relationshipType = symbolRelationshipType(item, metadata);
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("source", scannerCapsule == null ? "metadata" : "scanner");
        putIfPresent(evidence, "symbolRef", symbolRef);
        putIfPresent(evidence, "symbolId", string(symbol.id()));
        putIfPresent(evidence, "sourceRef", item.sourceRef());
        evidence.putAll(codeEvidence(Map.of(), symbolRef(symbol), symbol.contentHash()));
        collector.add(new MemoryCodeLinkRecord(
                edgeId(item.id(), relationshipType, "symbol", string(symbol.id()), "metadata:symbolRef"),
                item.id(),
                projectKey,
                relationshipType,
                MemoryCodeTargetKind.SYMBOL, null,
                null,
                null,
                symbol.id(),
                "metadata",
                item.confidence(),
                scannerCapsule == null ? "metadata" : "scanner",
                evidence,
                null,
                false,
                relationshipType == MemoryCodeRelationshipType.CONSTRAINS ? 0.8 : 0.6));
    }

    private void resolveSimilarity(MemoryItem item, Collector collector) {
        String projectKey = trim(item.projectKey());
        if (projectKey.isBlank()) {
            return;
        }
        String query = similarityQuery(item);
        if (query.isBlank()) {
            return;
        }
        List<ScoredCodeCapsuleRef> candidates;
        try {
            int requested = Math.max(properties.similarityMaxLinksPerMemory() * 4,
                    properties.similarityMaxLinksPerMemory() + 8);
            candidates = codeBaselineVectorIndex.search(query, requested, projectKey);
        } catch (RuntimeException e) {
            collector.similarityFailures++;
            log.warn("Memory-code similarity seed failed: memoryId={} projectKey={}", item.id(), projectKey, e);
            return;
        }
        int added = 0;
        for (ScoredCodeCapsuleRef candidate : candidates) {
            if (added >= properties.similarityMaxLinksPerMemory()) {
                return;
            }
            collector.similarityCandidates++;
            if (candidate.similarityScore() < properties.similarityMinScore()) {
                continue;
            }
            Optional<CodeCapsuleLinkTargetRecord> physical =
                    codeBaselineRepository.findCapsuleLinkTargetById(candidate.capsuleId());
            if (physical.isEmpty()) {
                collector.unresolvedQdrantCandidates++;
                continue;
            }
            CodeCapsuleLinkTargetRecord row = physical.get();
            Optional<CodeCapsuleLinkTargetRecord> current = codeBaselineRepository.findCurrentCapsuleLinkTarget(
                    row.projectKey(), row.targetKey(), row.capsuleKind());
            if (current.isEmpty()) {
                collector.unresolvedQdrantCandidates++;
                continue;
            }
            CodeCapsuleLinkTargetRecord capsule = current.get();
            String capsuleLogicalKey = capsuleLogicalKey(capsule.projectKey(), capsule.targetKey(),
                    capsule.capsuleKind());
            Map<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("source", "qdrant_search");
            evidence.put("capsuleId", string(candidate.capsuleId()));
            evidence.put("capsuleLogicalKey", capsuleLogicalKey);
            evidence.put("score", candidate.similarityScore());
            evidence.put("minScore", properties.similarityMinScore());
            evidence.put("querySource", "memory_summary");
            collector.add(new MemoryCodeLinkRecord(
                    edgeId(item.id(), MemoryCodeRelationshipType.RELATED_TO, "capsule", capsuleLogicalKey,
                            "similarity_backfill"),
                    item.id(),
                    capsule.projectKey(),
                    MemoryCodeRelationshipType.RELATED_TO,
                    MemoryCodeTargetKind.CAPSULE, null,
                    capsuleLogicalKey,
                    null,
                    null,
                    "similarity_backfill",
                    candidate.similarityScore(),
                    "qdrant",
                    evidence,
                    capsule.outputHash(),
                    false,
                    0.2));
            added++;
        }
    }

    private Optional<CodeSymbolRecord> resolveSymbolId(String projectKey, String value) {
        try {
            UUID symbolId = UUID.fromString(value);
            return codeBaselineRepository.findSymbolById(symbolId)
                    .filter(symbol -> projectKey.equals(symbol.projectKey()));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private Optional<CodeSymbolRecord> resolveExactSymbolRef(String projectKey, String symbolRef) {
        List<CodeSymbolRecord> exact = codeBaselineRepository.findSymbolsByRef(projectKey, symbolRef, 5).stream()
                .filter(symbol -> projectKey.equals(symbol.projectKey()))
                .filter(symbol -> exactSymbolMatch(symbol, symbolRef))
                .toList();
        return exact.size() == 1 ? Optional.of(exact.getFirst()) : Optional.empty();
    }

    private static boolean exactSymbolMatch(CodeSymbolRecord symbol, String ref) {
        String value = trim(ref);
        return value.equals(string(symbol.id()))
                || value.equals(trim(symbol.fqn()))
                || value.equals(trim(symbol.signature()));
    }

    private static MemoryCodeRelationshipType symbolRelationshipType(MemoryItem item, Map<String, Object> metadata) {
        String explicit = stringValue(metadata.get("relationshipKind")).toLowerCase(Locale.ROOT);
        if ("constrains".equals(explicit) || "constraint".equals(explicit)) {
            return MemoryCodeRelationshipType.CONSTRAINS;
        }
        if (item.memoryType() == MemoryType.DECISION || item.memoryType() == MemoryType.RULE) {
            return MemoryCodeRelationshipType.CONSTRAINS;
        }
        return MemoryCodeRelationshipType.MENTIONS;
    }

    private static String projectKey(MemoryItem item, CodeCapsuleLinkTargetRecord capsule) {
        String memoryProjectKey = trim(item.projectKey());
        if (!memoryProjectKey.isBlank()) {
            return memoryProjectKey;
        }
        return capsule == null ? "" : trim(capsule.projectKey());
    }

    private static String similarityQuery(MemoryItem item) {
        String summary = trim(item.summary());
        if (!summary.isBlank()) {
            return summary;
        }
        String text = trim(item.text());
        return text.length() > 2000 ? text.substring(0, 2000) : text;
    }

    private static Map<String, Object> metadata(MemoryItem item) {
        return item.metadata() == null ? Map.of() : item.metadata();
    }

    private static double capsuleConfidence(CodeCapsuleLinkTargetRecord capsule) {
        Object confidence = capsule.evidence().get("confidence");
        if (confidence instanceof Number number) {
            return number.doubleValue();
        }
        return 1.0;
    }

    private static String capsuleLogicalKey(String projectKey, String targetKey, String capsuleKind) {
        return trim(projectKey) + "|" + trim(targetKey) + "|" + trim(capsuleKind);
    }

    private static String edgeId(UUID memoryId, MemoryCodeRelationshipType type, String targetKind, String target,
            String resolution) {
        return "memory-code|" + memoryId + "|" + type.name() + "|" + targetKind + "|" + trim(target)
                + "|" + trim(resolution);
    }

    private static void putIfPresent(Map<String, Object> evidence, String key, Object value) {
        if (value != null && !String.valueOf(value).isBlank()) {
            evidence.put(key, String.valueOf(value));
        }
    }

    private static String string(UUID value) {
        return value == null ? "" : value.toString();
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static final class Collector {
        private final Map<String, MemoryCodeLinkRecord> links = new LinkedHashMap<>();
        private int unresolvedSourceRefs;
        private int missingFiles;
        private int missingSymbols;
        private int missingTypedTargets;
        private int unresolvedQdrantCandidates;
        private int similarityCandidates;
        private int similarityFailures;

        void add(MemoryCodeLinkRecord link) {
            links.putIfAbsent(link.edgeId(), link);
        }

        MemoryCodeLinkResolveResult result() {
            return new MemoryCodeLinkResolveResult(new ArrayList<>(links.values()), unresolvedSourceRefs,
                    missingFiles, missingSymbols, unresolvedQdrantCandidates, similarityCandidates,
                    similarityFailures, missingTypedTargets);
        }
    }
}
