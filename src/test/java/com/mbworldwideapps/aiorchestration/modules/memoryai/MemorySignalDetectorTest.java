package com.mbworldwideapps.aiorchestration.modules.memoryai;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MemorySignalDetectorTest {

    private final MemorySignalDetector detector = new MemorySignalDetector();

    @Test
    void detectsTurkishCorrectionApprovalAndExplicitSignals() {
        assertThat(detector.detect("Hayır, bizde böyle değil.").signalType())
                .isEqualTo(MemorySignalType.CORRECTION);
        assertThat(detector.detect("Tam böyle, doğru.").signalType())
                .isEqualTo(MemorySignalType.APPROVAL);
        assertThat(detector.detect("Bunu hatırla: MoneyTL zorunlu.").signalType())
                .isEqualTo(MemorySignalType.EXPLICIT);
    }

    @Test
    void normalizesTurkishCapitalI() {
        assertThat(MemorySignalDetector.normalize("BUNU HATIRLA")).contains("bunu hatirla");
    }
}
