package com.mbworldwideapps.aiorchestration.modules.scanner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DefaultUnresolvedSymbolScanSchedulerTest {

    @Test
    void queuesOneFullProjectStructuralScanWithoutProviderEgress() {
        ScannerAsyncService scanner = mock(ScannerAsyncService.class);
        when(scanner.start(any())).thenReturn(new ScannerScanStartResponse(
                UUID.randomUUID(), "queued", "/repo", "PROJECT", false, null, null, false, "queued"));
        DefaultUnresolvedSymbolScanScheduler scheduler = new DefaultUnresolvedSymbolScanScheduler(scanner);

        scheduler.schedule("PROJECT", Path.of("/repo"), List.of("src/example/Route.java"));

        ArgumentCaptor<ScanCodebaseRequest> request = ArgumentCaptor.forClass(ScanCodebaseRequest.class);
        verify(scanner).start(request.capture());
        assertThat(request.getValue().rootPath()).isEqualTo("/repo");
        assertThat(request.getValue().projectKey()).isEqualTo("PROJECT");
        assertThat(request.getValue().force()).isFalse();
        assertThat(request.getValue().providerOverride()).isNull();
        assertThat(request.getValue().semanticEnabled()).isFalse();
        assertThat(request.getValue().maxSemanticFiles()).isZero();
        assertThat(request.getValue().maxSemanticSymbols()).isZero();
        assertThat(request.getValue().maxSemanticFlows()).isZero();
        assertThat(request.getValue().includePaths()).isNull();
    }

    @Test
    void skipsEmptyUnresolvedPathSet() {
        ScannerAsyncService scanner = mock(ScannerAsyncService.class);
        DefaultUnresolvedSymbolScanScheduler scheduler = new DefaultUnresolvedSymbolScanScheduler(scanner);

        scheduler.schedule("PROJECT", Path.of("/repo"), List.of());

        verify(scanner, never()).start(any());
    }
}
