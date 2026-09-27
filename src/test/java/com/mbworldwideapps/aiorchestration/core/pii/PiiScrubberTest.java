package com.mbworldwideapps.aiorchestration.core.pii;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PiiScrubberTest {

    private final PiiScrubber piiScrubber = new PiiScrubber();

    @Test
    void detectsAndMasksKnownPiiPatterns() {
        String text = "User 10000000146 has iban TR330006100519786457841326 and mail a@b.com. "
                + "Phone 05551234567 and card 4111 1111 1111 1111.";

        assertThat(piiScrubber.containsPii(text)).isTrue();
        assertThat(piiScrubber.mask(text))
                .doesNotContain("10000000146")
                .doesNotContain("TR330006100519786457841326")
                .doesNotContain("a@b.com")
                .doesNotContain("05551234567")
                .doesNotContain("4111 1111 1111 1111");
    }

    @Test
    void reportsFirstKnownPiiReason() {
        assertThat(piiScrubber.firstMatchReason("TCKN 10000000146")).contains("tckn");
        assertThat(piiScrubber.firstMatchReason("IBAN TR330006100519786457841326")).contains("iban");
        assertThat(piiScrubber.firstMatchReason("Email dev@example.com")).contains("email");
        assertThat(piiScrubber.firstMatchReason("Telefon 05551234567")).contains("phone");
        assertThat(piiScrubber.firstMatchReason("Kart 4111 1111 1111 1111")).contains("payment-card");
        assertThat(piiScrubber.firstMatchReason("No private data here")).isEmpty();
    }
}
