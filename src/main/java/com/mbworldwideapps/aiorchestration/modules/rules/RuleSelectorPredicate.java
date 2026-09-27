package com.mbworldwideapps.aiorchestration.modules.rules;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

public record RuleSelectorPredicate(
        SelectorPolarity polarity,
        SelectorField field,
        SelectorOperator operator,
        List<String> values) {

    public static final int MAX_VALUES = 32;
    public static final int MAX_VALUE_CHARS = 256;

    public RuleSelectorPredicate {
        Objects.requireNonNull(polarity, "selector polarity");
        Objects.requireNonNull(field, "selector field");
        Objects.requireNonNull(operator, "selector operator");
        if (values == null) {
            throw new IllegalArgumentException("selector values are required");
        }
        values = values.stream().map(value -> canonicalValue(field, value)).sorted().toList();
        if (values.stream().distinct().count() != values.size()) {
            throw new IllegalArgumentException("duplicate selector values are not allowed");
        }
        if (operator == SelectorOperator.PRESENT && !values.isEmpty()) {
            throw new IllegalArgumentException("PRESENT requires no values");
        }
        if (operator == SelectorOperator.EQUALS && values.size() != 1) {
            throw new IllegalArgumentException("EQUALS requires exactly one value");
        }
        if ((operator == SelectorOperator.IN || operator == SelectorOperator.GLOB)
                && (values.isEmpty() || values.size() > MAX_VALUES)) {
            throw new IllegalArgumentException(operator + " requires between 1 and " + MAX_VALUES + " values");
        }
        if (operator == SelectorOperator.GLOB && field != SelectorField.PATH && field != SelectorField.SYMBOL) {
            throw new IllegalArgumentException("GLOB is supported only for PATH and SYMBOL selectors");
        }
        if (field == SelectorField.GENERATED && operator != SelectorOperator.PRESENT
                && values.stream().anyMatch(value -> !value.equals("true") && !value.equals("false"))) {
            throw new IllegalArgumentException("GENERATED selector values must be true or false");
        }
    }

    private static String canonicalValue(SelectorField field, String value) {
        if (value == null) {
            throw new IllegalArgumentException("selector values cannot be null");
        }
        requireWellFormed(value);
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFC).trim();
        if (normalized.isBlank() || normalized.length() > MAX_VALUE_CHARS
                || normalized.getBytes(StandardCharsets.UTF_8).length > 1_024) {
            throw new IllegalArgumentException("selector values must be non-blank and bounded");
        }
        if (field == SelectorField.ANNOTATION) {
            if (normalized.startsWith("@")
                    || !normalized.matches("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)+")) {
                throw new IllegalArgumentException("ANNOTATION selector requires a fully-qualified type name");
            }
            return normalized;
        }
        if (field == SelectorField.GENERATED) {
            return normalized.toLowerCase(Locale.ROOT);
        }
        Set<String> accepted = switch (field) {
            case SOURCE_SET -> Set.of("MAIN", "TEST", "GENERATED", "UNKNOWN");
            case ARTIFACT_KIND -> Set.of("CODE", "RESOURCE", "CONFIG", "MIGRATION", "DOCUMENTATION", "BUILD");
            case LANGUAGE -> Set.of("JAVA", "KOTLIN", "JAVASCRIPT", "TYPESCRIPT", "PYTHON", "SQL", "YAML",
                    "JSON", "XML", "MARKDOWN", "SHELL", "OTHER");
            case OPERATION -> Set.of("CREATE", "MODIFY", "DELETE", "RENAME", "MOVE");
            case INTENT_KIND -> Set.of("HTTP_MAPPING", "INPUT_VALIDATION", "DELEGATE_TO_SERVICE",
                    "BUSINESS_DECISION", "DOMAIN_CALCULATION", "PERSISTENCE", "TRANSACTION",
                    "READ_MODIFY_WRITE", "SHARED_STATE_MUTATION", "ASYNC_CONCURRENCY",
                    "TRANSACTION_BOUNDARY", "TEST_BEHAVIOR", "CONFIG_CHANGE", "SCHEMA_CHANGE",
                    "API_CONTRACT_CHANGE", "OTHER");
            default -> Set.of();
        };
        if (!accepted.isEmpty()) {
            String enumValue = normalized.toUpperCase(Locale.ROOT);
            if (!accepted.contains(enumValue)) {
                throw new IllegalArgumentException("unsupported " + field.value() + " selector value: " + normalized);
            }
            return enumValue;
        }
        return normalized;
    }

    private static void requireWellFormed(String value) {
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (index + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    throw new IllegalArgumentException("selector value contains an unpaired surrogate");
                }
                index++;
            } else if (Character.isLowSurrogate(current)) {
                throw new IllegalArgumentException("selector value contains an unpaired surrogate");
            }
        }
    }
}
