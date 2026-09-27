package com.mbworldwideapps.aiorchestration.modules.workspace;

import java.util.Locale;

import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Text the panel shows (errors, labels) in the language the panel asked for: header {@code X-Workspace-Lang}
 * ({@code en} | {@code tr}). English without the header and outside a web request; agent-facing texts stay English.
 */
public final class PanelText {
    public static final String HEADER = "X-Workspace-Lang";

    private PanelText() {}

    public static String t(String en, String tr) {
        return turkish() ? tr : en;
    }

    static boolean turkish() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) return false;
        String lang = attributes.getRequest().getHeader(HEADER);
        return lang != null && lang.trim().toLowerCase(Locale.ROOT).startsWith("tr");
    }
}
