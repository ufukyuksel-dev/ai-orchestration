package com.mbworldwideapps.aiorchestration.modules.scanner;

import java.util.List;
import java.util.UUID;

public record ScanCodebaseRequest(
        String rootPath,
        String projectKey,
        Boolean force,
        String providerOverride,
        String semanticModel,
        Boolean forceReindex,
        UUID scanRunId,
        List<String> includePaths,
        List<String> excludePaths,
        Integer maxSemanticFiles,
        Integer maxSemanticSymbols,
        Integer maxSemanticFlows,
        Boolean semanticEnabled) {

    public ScanCodebaseRequest {
        requireNonNegative(maxSemanticFiles, "maxSemanticFiles");
        requireNonNegative(maxSemanticSymbols, "maxSemanticSymbols");
        requireNonNegative(maxSemanticFlows, "maxSemanticFlows");
    }

    public ScanCodebaseRequest(String rootPath, String projectKey, Boolean force) {
        this(rootPath, projectKey, force, null, null, null);
    }

    public ScanCodebaseRequest(String rootPath, String projectKey, Boolean force, String providerOverride,
            String semanticModel) {
        this(rootPath, projectKey, force, providerOverride, semanticModel, null);
    }

    public ScanCodebaseRequest(String rootPath, String projectKey, Boolean force, String providerOverride,
            String semanticModel, Boolean forceReindex) {
        this(rootPath, projectKey, force, providerOverride, semanticModel, forceReindex, null,
                null, null, null, null, null);
    }

    public ScanCodebaseRequest(String rootPath, String projectKey, Boolean force, String providerOverride,
            String semanticModel, Boolean forceReindex, UUID scanRunId) {
        this(rootPath, projectKey, force, providerOverride, semanticModel, forceReindex, scanRunId,
                null, null, null, null, null);
    }

    // 12-arg convenience preserves existing callers; semanticEnabled defaults to null (governed by config).
    public ScanCodebaseRequest(String rootPath, String projectKey, Boolean force, String providerOverride,
            String semanticModel, Boolean forceReindex, UUID scanRunId, List<String> includePaths,
            List<String> excludePaths, Integer maxSemanticFiles, Integer maxSemanticSymbols,
            Integer maxSemanticFlows) {
        this(rootPath, projectKey, force, providerOverride, semanticModel, forceReindex, scanRunId,
                includePaths, excludePaths, maxSemanticFiles, maxSemanticSymbols, maxSemanticFlows, null);
    }

    public boolean forceReindexEnabled() {
        return Boolean.TRUE.equals(forceReindex);
    }

    private static void requireNonNegative(Integer value, String name) {
        if (value != null && value < 0) {
            throw new IllegalArgumentException(name + " must be >= 0 when provided, was " + value);
        }
    }
}
