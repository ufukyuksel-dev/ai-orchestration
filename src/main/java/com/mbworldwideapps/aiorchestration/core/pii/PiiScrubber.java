package com.mbworldwideapps.aiorchestration.core.pii;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

@Component
public class PiiScrubber {

    private final List<PiiRule> rules = List.of(
            new PiiRule("tckn", Pattern.compile("\\b[1-9][0-9]{10}\\b")),
            new PiiRule("iban", Pattern.compile("\\bTR[0-9]{2}[0-9A-Z]{22}\\b", Pattern.CASE_INSENSITIVE)),
            new PiiRule("email", Pattern.compile("\\b[\\w.%+-]+@[\\w.-]+\\.[A-Za-z]{2,}\\b")),
            new PiiRule("phone", Pattern.compile("\\b(?:\\+90|0)?5[0-9]{9}\\b")),
            new PiiRule("payment-card", Pattern.compile("\\b(?:\\d[ -]?){13,19}\\b")));

    public boolean containsPii(String text) {
        return firstMatchReason(text).isPresent();
    }

    public Optional<String> firstMatchReason(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        return rules.stream()
                .filter(rule -> rule.matches(text))
                .map(PiiRule::reason)
                .findFirst();
    }

    public String mask(String text) {
        if (text == null) {
            return "";
        }
        String masked = text;
        for (PiiRule rule : rules) {
            masked = rule.mask(masked);
        }
        return masked;
    }

    private record PiiRule(String reason, Pattern pattern) {
        boolean matches(String text) {
            var matcher = pattern.matcher(text);
            while (matcher.find()) {
                if (!"payment-card".equals(reason)) {
                    return true;
                }
                String digits = matcher.group().replaceAll("[^0-9]", "");
                if (digits.length() >= 13 && digits.length() <= 19 && luhnValid(digits)) {
                    return true;
                }
            }
            return false;
        }

        String mask(String text) {
            if (!"payment-card".equals(reason)) {
                return pattern.matcher(text).replaceAll("[REDACTED]");
            }
            var matcher = pattern.matcher(text);
            StringBuilder masked = new StringBuilder();
            while (matcher.find()) {
                String candidate = matcher.group();
                String digits = candidate.replaceAll("[^0-9]", "");
                String replacement = digits.length() >= 13 && digits.length() <= 19 && luhnValid(digits)
                        ? "[REDACTED]"
                        : candidate;
                matcher.appendReplacement(masked, replacement);
            }
            matcher.appendTail(masked);
            return masked.toString();
        }

        private static boolean luhnValid(String digits) {
            int sum = 0;
            boolean doubleDigit = false;
            for (int index = digits.length() - 1; index >= 0; index--) {
                int value = Character.digit(digits.charAt(index), 10);
                if (value < 0) {
                    return false;
                }
                if (doubleDigit) {
                    value *= 2;
                    if (value > 9) {
                        value -= 9;
                    }
                }
                sum += value;
                doubleDigit = !doubleDigit;
            }
            return sum % 10 == 0;
        }
    }
}
