package com.mbworldwideapps.aiorchestration.eval.memory;

import static org.assertj.core.api.Assertions.assertThat;

import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryStatus;
import org.junit.jupiter.api.Test;

/**
 * Pollution scoring must not depend on the removed pending state, and a rerun that finds an
 * already stored record must reach the same verdict as the run that stored it.
 */
class MemoryEvalRunnerPollutionTest {

    @Test
    void rejectedCaptureIsCaught() {
        assertThat(MemoryEvalRunner.pollutionCaught(false, false, null)).isTrue();
        assertThat(MemoryEvalRunner.pollutionCaught(true, false, null)).isTrue();
    }

    @Test
    void storedActivePollutionIsAMissOnFirstRunAndOnRerun() {
        boolean firstRun = MemoryEvalRunner.pollutionCaught(false, true, MemoryStatus.ACTIVE);
        boolean rerun = MemoryEvalRunner.pollutionCaught(false, true, MemoryStatus.ACTIVE);

        assertThat(firstRun).isFalse();
        assertThat(rerun).isEqualTo(firstRun);
    }

    @Test
    void storedPendingPollutionIsCaughtOnFirstRunAndOnRerun() {
        boolean firstRun = MemoryEvalRunner.pollutionCaught(false, true, MemoryStatus.PENDING_REVIEW);
        boolean rerun = MemoryEvalRunner.pollutionCaught(false, true, MemoryStatus.PENDING_REVIEW);

        assertThat(firstRun).isTrue();
        assertThat(rerun).isEqualTo(firstRun);
    }

    @Test
    void staleRecordsAreRetrievableAndThereforeNotCaught() {
        assertThat(MemoryEvalRunner.pollutionCaught(false, true, MemoryStatus.STALE)).isFalse();
    }

    @Test
    void piiScenarioIsOnlyCaughtByAnOutrightRejection() {
        assertThat(MemoryEvalRunner.pollutionCaught(true, true, MemoryStatus.PENDING_REVIEW)).isFalse();
        assertThat(MemoryEvalRunner.pollutionCaught(true, true, MemoryStatus.ACTIVE)).isFalse();
        assertThat(MemoryEvalRunner.pollutionCaught(true, false, null)).isTrue();
    }
}
