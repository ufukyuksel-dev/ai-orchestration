package com.mbworldwideapps.aiorchestration.modules.rules;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.nio.charset.StandardCharsets;

/** Deep immutable copies for JSON-shaped rule configuration values. */
final class RuleImmutableValues {

    private static final int MAX_DEPTH = 32;
    private static final int MAX_NODES = 2_048;
    private static final int MAX_UTF8_BYTES = 16_384;

    private RuleImmutableValues() {
    }

    static Map<String, Object> immutableMap(Map<String, Object> source) {
        if (source == null) {
            return null;
        }
        return immutableMap(source, 0, new IdentityHashMap<>(), new Budget());
    }

    private static Map<String, Object> immutableMap(Map<?, ?> source, int depth,
            IdentityHashMap<Object, Boolean> active, Budget budget) {
        enterContainer(source, depth, active, budget);
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (!(entry.getKey() instanceof String key) || key.isBlank()) {
                throw new IllegalArgumentException("rule JSON object keys must be non-blank strings");
            }
            budget.addString(key);
            copy.put(key, immutableValue(entry.getValue(), depth + 1, active, budget));
        }
        active.remove(source);
        return Collections.unmodifiableMap(copy);
    }

    private static Object immutableValue(Object value, int depth,
            IdentityHashMap<Object, Boolean> active, Budget budget) {
        budget.addNode();
        if (value instanceof Map<?, ?> map) {
            return immutableMap(map, depth, active, budget);
        }
        if (value instanceof List<?> list) {
            enterContainer(list, depth, active, budget);
            List<Object> copy = new ArrayList<>(list.size());
            for (Object item : list) {
                copy.add(immutableValue(item, depth + 1, active, budget));
            }
            active.remove(list);
            return Collections.unmodifiableList(copy);
        }
        if (value instanceof String string) {
            budget.addString(string);
            return value;
        }
        if (value instanceof Float number && !Float.isFinite(number)) {
            throw new IllegalArgumentException("rule detector configuration numbers must be finite");
        }
        if (value instanceof Double number && !Double.isFinite(number)) {
            throw new IllegalArgumentException("rule detector configuration numbers must be finite");
        }
        if (value == null || value instanceof Boolean
                || value instanceof Byte || value instanceof Short || value instanceof Integer
                || value instanceof Long || value instanceof Float || value instanceof Double
                || value instanceof java.math.BigInteger || value instanceof java.math.BigDecimal) {
            if (value != null) {
                budget.addString(value.toString());
            }
            return value;
        }
        throw new IllegalArgumentException(
                "rule detector configuration must contain only JSON-shaped immutable values");
    }

    private static void enterContainer(Object value, int depth,
            IdentityHashMap<Object, Boolean> active, Budget budget) {
        if (depth > MAX_DEPTH) {
            throw new IllegalArgumentException("rule detector configuration exceeds depth limit of " + MAX_DEPTH);
        }
        budget.addNode();
        if (active.put(value, Boolean.TRUE) != null) {
            throw new IllegalArgumentException("rule detector configuration must not contain cycles");
        }
    }

    private static final class Budget {
        private int nodes;
        private int utf8Bytes;

        void addNode() {
            nodes = Math.addExact(nodes, 1);
            if (nodes > MAX_NODES) {
                throw new IllegalArgumentException(
                        "rule detector configuration exceeds node limit of " + MAX_NODES);
            }
        }

        void addString(String value) {
            utf8Bytes = Math.addExact(utf8Bytes, value.getBytes(StandardCharsets.UTF_8).length);
            if (utf8Bytes > MAX_UTF8_BYTES) {
                throw new IllegalArgumentException(
                        "rule detector configuration exceeds materialization limit of " + MAX_UTF8_BYTES
                                + " bytes");
            }
        }
    }
}
