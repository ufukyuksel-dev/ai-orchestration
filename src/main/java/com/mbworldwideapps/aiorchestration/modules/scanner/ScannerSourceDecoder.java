package com.mbworldwideapps.aiorchestration.modules.scanner;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** Decodes source text strictly as UTF-8, with a bounded legacy fallback for older Java repositories. */
final class ScannerSourceDecoder {

    private static final Charset LEGACY_CHARSET = Charset.forName("windows-1254");

    private ScannerSourceDecoder() {
    }

    static DecodedSource decode(byte[] bytes) {
        byte[] source = bytes == null ? new byte[0] : bytes;
        try {
            String content = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(source))
                    .toString();
            return new DecodedSource(content, StandardCharsets.UTF_8.name(), false);
        } catch (CharacterCodingException ignored) {
            return new DecodedSource(new String(source, LEGACY_CHARSET), LEGACY_CHARSET.name(), true);
        }
    }

    record DecodedSource(String content, String charsetName, boolean fallbackUsed) {
    }
}
