package com.mbworldwideapps.aiorchestration.modules.scanner;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class ScannerSourceDecoderTest {

    @Test
    void keepsValidUtf8WithoutFallback() {
        ScannerSourceDecoder.DecodedSource decoded = ScannerSourceDecoder.decode(
                "class Ödeme {}".getBytes(StandardCharsets.UTF_8));

        assertThat(decoded.content()).isEqualTo("class Ödeme {}");
        assertThat(decoded.charsetName()).isEqualTo("UTF-8");
        assertThat(decoded.fallbackUsed()).isFalse();
    }

    @Test
    void decodesLegacyTurkishJavaInsteadOfRejectingTheFile() {
        Charset windows1254 = Charset.forName("windows-1254");
        ScannerSourceDecoder.DecodedSource decoded = ScannerSourceDecoder.decode(
                "class İşlem { String açıklama = \"doğru\"; }".getBytes(windows1254));

        assertThat(decoded.content()).contains("İşlem", "açıklama", "doğru");
        assertThat(decoded.charsetName()).isEqualTo("windows-1254");
        assertThat(decoded.fallbackUsed()).isTrue();
    }
}
