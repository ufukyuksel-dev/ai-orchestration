package com.mbworldwideapps.aiorchestration.modules.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

class PanelTextTest {

    @AfterEach
    void clear() {
        RequestContextHolder.resetRequestAttributes();
    }

    private static void request(String lang) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (lang != null) request.addHeader(PanelText.HEADER, lang);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @Test
    void englishOutsideARequest() {
        assertThat(PanelText.t("Record not found", "Kayıt bulunamadı")).isEqualTo("Record not found");
    }

    @Test
    void englishWithoutTheHeader() {
        request(null);
        assertThat(PanelText.t("Record not found", "Kayıt bulunamadı")).isEqualTo("Record not found");
    }

    @Test
    void turkishWhenThePanelAsksForTr() {
        request("tr");
        assertThat(PanelText.t("Record not found", "Kayıt bulunamadı")).isEqualTo("Kayıt bulunamadı");
        request(" TR-tr ");
        assertThat(PanelText.t("Record not found", "Kayıt bulunamadı")).isEqualTo("Kayıt bulunamadı");
    }

    @Test
    void englishForEnAndUnknownLanguages() {
        request("en");
        assertThat(PanelText.t("Record not found", "Kayıt bulunamadı")).isEqualTo("Record not found");
        request("de");
        assertThat(PanelText.t("Record not found", "Kayıt bulunamadı")).isEqualTo("Record not found");
    }
}
