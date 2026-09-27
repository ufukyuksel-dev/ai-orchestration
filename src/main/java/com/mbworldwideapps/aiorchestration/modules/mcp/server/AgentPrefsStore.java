package com.mbworldwideapps.aiorchestration.modules.mcp.server;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Small per-user preference file (default ~/.config/ai-orch/prefs.json). Holds the answer to the one question
 * agents ask at session start: whether to load the rules, and whether to stop asking.
 */
@Component
public class AgentPrefsStore {

    static final String RULES_AUTOLOAD = "rulesAutoload";

    private final Path file;
    private final ObjectMapper json = new ObjectMapper();

    public AgentPrefsStore(@Value("${ai-orchestration.agent.prefs-file:${user.home}/.config/ai-orch/prefs.json}")
            String file) {
        this.file = Path.of(file);
    }

    /** "always", "never" or null when the user has not chosen yet. */
    public synchronized String rulesAutoload() {
        Object value = read().get(RULES_AUTOLOAD);
        return value == null ? null : value.toString();
    }

    public synchronized void rememberRulesAutoload(boolean load) {
        Map<String, Object> prefs = read();
        prefs.put(RULES_AUTOLOAD, load ? "always" : "never");
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            json.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), prefs);
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new IllegalStateException("could not save agent preferences", e);
        }
    }

    private Map<String, Object> read() {
        if (!Files.isRegularFile(file)) {
            return new LinkedHashMap<>();
        }
        try {
            return new LinkedHashMap<>(json.readValue(file.toFile(), new TypeReference<Map<String, Object>>() { }));
        } catch (IOException e) {
            return new LinkedHashMap<>();
        }
    }
}
