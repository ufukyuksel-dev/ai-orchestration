package com.mbworldwideapps.aiorchestration.config;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("ai-orchestration.references")
public record ReferenceProperties(String rootPath) {
    public ReferenceProperties {
        rootPath = rootPath == null ? Path.of(System.getProperty("user.home"), "Projects", "ai-references").toString()
                : rootPath;
        if (rootPath.isBlank() || !Path.of(rootPath).isAbsolute()) {
            throw new IllegalArgumentException("Reference root must be an absolute path");
        }
    }
}
