package com.mbworldwideapps.aiorchestration.modules.rules;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class RuleImmutableValuesTest {

    @Test
    void rejectsCyclesBeforeSerialization() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("cycle", config);

        assertThatThrownBy(() -> RuleImmutableValues.immutableMap(config))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cycles");
    }

    @Test
    void rejectsExcessiveDepthBeforeRecursiveCopyCanOverflow() {
        List<Object> root = new ArrayList<>();
        List<Object> cursor = root;
        for (int depth = 0; depth < 40; depth++) {
            List<Object> nested = new ArrayList<>();
            cursor.add(nested);
            cursor = nested;
        }

        assertThatThrownBy(() -> RuleImmutableValues.immutableMap(Map.of("nested", root)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("depth limit");
    }

    @Test
    void rejectsNonFiniteNumbersAndOversizedMaterialization() {
        assertThatThrownBy(() -> RuleImmutableValues.immutableMap(Map.of("value", Double.NaN)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("finite");
        assertThatThrownBy(() -> RuleImmutableValues.immutableMap(Map.of("value", "x".repeat(20_000))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("materialization limit");
    }
}
