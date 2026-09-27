package com.mbworldwideapps.aiorchestration.modules.memoryai;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Component;

@Component
public class MemorySignalDetector {

    private static final List<String> EXPLICIT = List.of(
            "bunu hatirla",
            "bunu hatırla",
            "sunu ogren",
            "şunu öğren",
            "bunu kaydet",
            "remember this");
    private static final List<String> CORRECTION = List.of(
            "hayir",
            "hayır",
            "yanlis",
            "yanlış",
            "boyle degil",
            "böyle değil",
            "bizde soyle",
            "bizde şöyle",
            "biz boyle",
            "biz böyle",
            "biz kullaniyoruz",
            "biz kullanıyoruz");
    private static final List<String> APPROVAL = List.of(
            "tam boyle",
            "tam böyle",
            "dogru",
            "doğru",
            "perfect",
            "aynen");

    public MemorySignalDetection detect(String text) {
        String normalized = normalize(text);
        return firstMatch(normalized, EXPLICIT, MemorySignalType.EXPLICIT, 0.7)
                .or(() -> firstMatch(normalized, CORRECTION, MemorySignalType.CORRECTION, 0.55))
                .or(() -> firstMatch(normalized, APPROVAL, MemorySignalType.APPROVAL, 0.5))
                .orElse(new MemorySignalDetection(MemorySignalType.NONE, null, 0.0));
    }

    static String normalize(String text) {
        if (text == null) {
            return "";
        }
        String asciiFriendly = Normalizer.normalize(text, Normalizer.Form.NFKC)
                .replace('İ', 'i')
                .replace('I', 'i');
        return asciiFriendly.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    private static java.util.Optional<MemorySignalDetection> firstMatch(
            String normalized, List<String> phrases, MemorySignalType type, double strength) {
        return phrases.stream()
                .filter(normalized::contains)
                .findFirst()
                .map(phrase -> new MemorySignalDetection(type, phrase, strength));
    }
}
