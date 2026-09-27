package com.mbworldwideapps.aiorchestration.modules.mcp.server;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryCodeLocator;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryRelationService;

/** Compact model-visible projections; storage, audit, and vector metadata remain in the omitted legacy view. */
public final class MemoryAgentViewResponse {

    private MemoryAgentViewResponse() {
    }

    public record Knowledge(
            UUID id,
            String type,
            String text,
            boolean contentComplete,
            String status,
            boolean stale,
            List<MemoryCodeLocator> targets,
            List<String> warnings) {

        public Knowledge {
            targets = targets == null ? List.of() : List.copyOf(targets);
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }
    }

    public record Search(
            int schemaVersion,
            String projectKey,
            List<Knowledge> knowledge,
            List<MemorySearchResponse.ScannedSymbol> scannedSymbols,
            int omittedScannedSymbols,
            List<MemoryRelationService.ReferenceLink> linkedReferences,
            boolean moreLinkedReferences,
            long wordEstimate,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
            String hint) {

        public Search(int schemaVersion, String projectKey, List<Knowledge> knowledge,
                List<MemorySearchResponse.ScannedSymbol> scannedSymbols, int omittedScannedSymbols,
                List<MemoryRelationService.ReferenceLink> linkedReferences, boolean moreLinkedReferences,
                long wordEstimate) {
            this(schemaVersion, projectKey, knowledge, scannedSymbols, omittedScannedSymbols, linkedReferences,
                    moreLinkedReferences, wordEstimate, null);
        }

        public Search {
            knowledge = knowledge == null ? List.of() : List.copyOf(knowledge);
            scannedSymbols = scannedSymbols == null ? List.of() : List.copyOf(scannedSymbols);
            linkedReferences = linkedReferences == null ? List.of() : List.copyOf(linkedReferences);
        }
    }

    public record Get(
            int schemaVersion,
            Knowledge knowledge,
            String scope,
            String projectKey,
            long wordEstimate) {
    }

    /** Bounded opt-in diagnostics. The omitted view remains the unchanged legacy full record. */
    public record DebugGet(
            int schemaVersion,
            Knowledge knowledge,
            String scope,
            String projectKey,
            String summary,
            List<String> tags,
            double confidence,
            String sourceType,
            String sourceRef,
            Instant updatedAt,
            long wordEstimate) {

        public DebugGet {
            tags = tags == null ? List.of() : List.copyOf(tags);
        }
    }
}
