package com.mbworldwideapps.aiorchestration.modules.memoryai;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class MemoryTextPreviewTest {
    @Test
    void technicalDotsDoNotDiscardTheFactAfterTheFileOrVersion() {
        for (String subject : new String[] {"django/utils/text.py", "example.com", "v5.2.1", "org.example.Parser"}) {
            String first = subject + " lowercases names before validation.";
            String preview = MemoryTextPreview.excerpt(first + " Additional context ".repeat(40));
            assertThat(preview).isEqualTo(first);
        }
    }

    @Test
    void realSentenceBoundaryAndHardLengthLimitRemainBounded() {
        assertThat(MemoryTextPreview.excerpt("First fact! " + "more ".repeat(100))).isEqualTo("First fact!");
        String value = "django/utils/text.py " + "detail ".repeat(100);
        assertThat(MemoryTextPreview.excerpt(value)).startsWith("django/utils/text.py ")
                .hasSizeLessThanOrEqualTo(MemoryAtomLimits.EXCERPT_MAX_CHARS).endsWith("...");
    }
}
