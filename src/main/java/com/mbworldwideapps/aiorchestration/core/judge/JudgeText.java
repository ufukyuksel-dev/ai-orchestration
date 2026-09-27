package com.mbworldwideapps.aiorchestration.core.judge;

import java.text.Normalizer;
import java.util.Locale;

final class JudgeText {

    private JudgeText() {
    }

    static boolean contains(String text, String needle) {
        if (needle == null || needle.isBlank()) {
            return true;
        }
        return normalize(text).contains(normalize(needle));
    }

    static String normalize(String text) {
        if (text == null) {
            return "";
        }
        String normalized = text.replace('İ', 'i').replace('I', 'ı').toLowerCase(Locale.forLanguageTag("tr"));
        return Normalizer.normalize(normalized, Normalizer.Form.NFKC)
                .replaceAll("\\s+", " ")
                .trim();
    }
}
