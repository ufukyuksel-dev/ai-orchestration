package com.mbworldwideapps.aiorchestration.modules.scanner;

import java.util.List;

/**
 * Scan-time importance heuristic for {@link SemanticSelectionMode#SYMBOL_FIRST} candidate selection.
 *
 * <p>Pure function of scan-time-available signals only (name/annotations/endpoint/outbound calls). No
 * fan-in/out — that needs the fully-written edge graph and stays in the post-scan importance scorer.
 * Scanner-neutral: no dependency on the {@code architecture} module.
 *
 * <p>Bias: we skip ONLY what is clearly trivial. An unannotated, call-less method is low-value only when
 * it has a clear accessor shape ({@code getX}/{@code setX}/{@code isX}); a plain business method such as
 * {@code validateLimit}, {@code calculateFee}, or {@code buildMessage} is NOT an accessor and stays
 * high, so ambiguous business logic is never dropped.
 */
final class ScanTimeSymbolImportance {

    private ScanTimeSymbolImportance() {
    }

    /** Types (class/interface) are structural anchors — always high-value. */
    static boolean isHighValueType() {
        return true;
    }

    /**
     * High-value iff the method carries any annotation, exposes an endpoint, or makes at least one
     * outbound call; otherwise low-value only for a clear accessor shape.
     */
    static boolean isHighValueMethod(String methodName, List<String> annotations, boolean exposesEndpoint,
            List<String> calls) {
        if ((annotations != null && !annotations.isEmpty())
                || exposesEndpoint
                || (calls != null && !calls.isEmpty())) {
            return true;
        }
        return !isAccessorShape(methodName);
    }

    /** A clear getter/setter/is accessor name shape, e.g. {@code getName}, {@code setName}, {@code isActive}. */
    static boolean isAccessorShape(String methodName) {
        if (methodName == null) {
            return false;
        }
        return prefixedAccessor(methodName, "get")
                || prefixedAccessor(methodName, "set")
                || prefixedAccessor(methodName, "is");
    }

    private static boolean prefixedAccessor(String name, String prefix) {
        return name.length() > prefix.length()
                && name.startsWith(prefix)
                && Character.isUpperCase(name.charAt(prefix.length()));
    }
}
