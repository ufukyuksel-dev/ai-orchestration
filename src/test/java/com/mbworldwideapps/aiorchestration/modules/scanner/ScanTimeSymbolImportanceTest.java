package com.mbworldwideapps.aiorchestration.modules.scanner;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class ScanTimeSymbolImportanceTest {

    @Test
    void typesAreAlwaysHighValue() {
        assertThat(ScanTimeSymbolImportance.isHighValueType()).isTrue();
    }

    @Test
    void annotationTakesPriorityRegardlessOfName() {
        // Annotation wins even for an accessor-shaped name (@Scheduled/@KafkaListener/mapping on getX).
        assertThat(ScanTimeSymbolImportance.isHighValueMethod("getReport", List.of("Scheduled"), false, List.of()))
                .isTrue();
    }

    @Test
    void endpointMethodIsHighValue() {
        assertThat(ScanTimeSymbolImportance.isHighValueMethod("handle", List.of(), true, List.of())).isTrue();
    }

    @Test
    void methodWithOutboundCallIsHighValue() {
        assertThat(ScanTimeSymbolImportance.isHighValueMethod("approve", List.of(), false, List.of("serviceApprove")))
                .isTrue();
    }

    @Test
    void plainBusinessMethodStaysHighValue() {
        // No annotation, no outbound call, but NOT an accessor shape -> must not be skipped.
        assertThat(ScanTimeSymbolImportance.isHighValueMethod("validateLimit", List.of(), false, List.of())).isTrue();
        assertThat(ScanTimeSymbolImportance.isHighValueMethod("calculateFee", List.of(), false, List.of())).isTrue();
        assertThat(ScanTimeSymbolImportance.isHighValueMethod("buildMessage", List.of(), false, List.of())).isTrue();
    }

    @Test
    void clearAccessorShapeIsLowValueOnlyWhenUnannotatedAndCallLess() {
        assertThat(ScanTimeSymbolImportance.isHighValueMethod("getName", List.of(), false, List.of())).isFalse();
        assertThat(ScanTimeSymbolImportance.isHighValueMethod("setName", List.of(), false, List.of())).isFalse();
        assertThat(ScanTimeSymbolImportance.isHighValueMethod("isActive", List.of(), false, List.of())).isFalse();
        // "get" alone (no capitalized suffix) is not an accessor shape -> stays high.
        assertThat(ScanTimeSymbolImportance.isHighValueMethod("get", List.of(), false, List.of())).isTrue();
    }
}
