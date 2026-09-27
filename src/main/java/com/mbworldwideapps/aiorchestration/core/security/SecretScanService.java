package com.mbworldwideapps.aiorchestration.core.security;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

@Service
public class SecretScanService {

    private static final Pattern KEY_VALUE_SECRET = Pattern.compile(
            "(?i)(password|api[_-]?key|admin[_-]?token|secret|token)\\s*[:=]\\s*[\"']?([^\\s\"'${}#]{8,})");
    private static final Pattern HIGH_ENTROPY = Pattern.compile("\\b[A-Za-z0-9+/=_-]{32,}\\b");

    public List<SecretScanFinding> scan(String content) {
        if (content == null || content.isBlank()) {
            return List.of();
        }
        List<SecretScanFinding> findings = new ArrayList<>();
        String[] lines = content.split("\\R", -1);
        for (int index = 0; index < lines.length; index++) {
            String line = lines[index];
            if (KEY_VALUE_SECRET.matcher(line).find()) {
                findings.add(new SecretScanFinding("secret-key-value", index + 1));
                continue;
            }
            var matcher = HIGH_ENTROPY.matcher(line);
            while (matcher.find()) {
                String candidate = matcher.group();
                if (looksLikePath(candidate)) {
                    continue;
                }
                if (entropy(candidate) >= 4.2) {
                    findings.add(new SecretScanFinding("high-entropy-token", index + 1));
                    break;
                }
            }
        }
        return findings;
    }

    public boolean containsSecret(String content) {
        return !scan(content).isEmpty();
    }

    private static boolean looksLikePath(String candidate) {
        int separators = 0;
        for (char ch : candidate.toCharArray()) {
            if (ch == '/' || ch == '\\') {
                separators++;
            }
        }
        return separators >= 2;
    }

    private static double entropy(String value) {
        int[] frequencies = new int[128];
        int length = 0;
        for (char ch : value.toCharArray()) {
            if (ch < frequencies.length) {
                frequencies[ch]++;
                length++;
            }
        }
        if (length == 0) {
            return 0.0;
        }
        double entropy = 0.0;
        for (int frequency : frequencies) {
            if (frequency == 0) {
                continue;
            }
            double probability = (double) frequency / length;
            entropy -= probability * (Math.log(probability) / Math.log(2));
        }
        return entropy;
    }
}
