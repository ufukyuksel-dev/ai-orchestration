package com.mbworldwideapps.aiorchestration.modules.scanner;

import java.util.UUID;


public final class GraphProjectionKeys {

    private GraphProjectionKeys() {
    }

    public static String codeReferenceKey(String projectKey, String edgeType, String targetRefOrSymbolId) {
        return blank(projectKey) + "|" + blank(edgeType) + "|" + blank(targetRefOrSymbolId);
    }

    public static String codeReferenceKey(String projectKey, String edgeType, CodeEdgeRecord edge) {
        String target = edge.targetRef() == null || edge.targetRef().isBlank()
                ? uuid(edge.targetSymbolId())
                : edge.targetRef();
        return codeReferenceKey(projectKey, edgeType, target);
    }

    private static String uuid(UUID value) {
        return value == null ? null : value.toString();
    }

    private static String blank(String value) {
        return value == null ? "" : value;
    }
}
