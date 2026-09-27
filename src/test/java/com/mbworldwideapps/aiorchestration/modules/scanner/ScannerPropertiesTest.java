package com.mbworldwideapps.aiorchestration.modules.scanner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/**
 * Real @ConfigurationProperties binding for scanner.semantic.selection-mode: default, explicit, and
 * fail-fast on invalid — not just the record/enum contracts.
 */
class ScannerPropertiesTest {

    @Test
    void unsetSelectionModeDefaultsToAll() {
        assertThat(bindSemantic(Map.of()).selectionMode()).isEqualTo(SemanticSelectionMode.ALL);
    }

    @Test
    void explicitAllBindsToAll() {
        assertThat(bindSemantic(Map.of("selection-mode", "all")).selectionMode())
                .isEqualTo(SemanticSelectionMode.ALL);
    }

    @Test
    void explicitSymbolFirstBindsWithRelaxedCasing() {
        assertThat(bindSemantic(Map.of("selection-mode", "symbol-first")).selectionMode())
                .isEqualTo(SemanticSelectionMode.SYMBOL_FIRST);
    }

    @Test
    void invalidSelectionModeFailsFast() {
        assertThatThrownBy(() -> bindSemantic(Map.of("selection-mode", "sideways")));
    }

    private static ScannerProperties.Semantic bindSemantic(Map<String, Object> properties) {
        return new Binder(new MapConfigurationPropertySource(properties))
                .bind("", Bindable.of(ScannerProperties.Semantic.class))
                .orElseGet(ScannerProperties.Semantic::defaults);
    }
}
