package com.mbworldwideapps.aiorchestration.modules.memoryai;

import java.util.Optional;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

@Component
public class MemoryPolicyEvaluator {

    private static final Pattern API_KEY_PATTERN = Pattern.compile(
            "(?i)(api[_-]?key|access[_-]?token|secret[_-]?key)\\s*[:=]\\s*['\"]?[A-Za-z0-9_.-]{16,}");
    private static final Pattern BEARER_PATTERN = Pattern.compile("(?i)Bearer\\s+[A-Za-z0-9_.-]{20,}");
    private static final Pattern INTERNAL_URL_PATTERN = Pattern.compile(
            "(?i)https?://[^/\\s]*(internal|vault|admin|prod)[^/\\s]*");

    public Optional<String> evaluate(AutoCuratedMemoryCandidate candidate) {
        String haystack = candidate.summary() + " " + candidate.text();
        if (API_KEY_PATTERN.matcher(haystack).find()) {
            return Optional.of("api_key_pattern");
        }
        if (BEARER_PATTERN.matcher(haystack).find()) {
            return Optional.of("bearer_token_pattern");
        }
        if (INTERNAL_URL_PATTERN.matcher(haystack).find()) {
            return Optional.of("internal_url_pattern");
        }
        return Optional.empty();
    }

    public String mask(String text) {
        if (text == null) {
            return "";
        }
        String masked = API_KEY_PATTERN.matcher(text).replaceAll("$1=[REDACTED]");
        masked = BEARER_PATTERN.matcher(masked).replaceAll("Bearer [REDACTED]");
        return INTERNAL_URL_PATTERN.matcher(masked).replaceAll("https://[REDACTED]");
    }
}
