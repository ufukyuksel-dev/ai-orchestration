package com.mbworldwideapps.aiorchestration.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

@ConfigurationProperties(prefix = "ai-orchestration.cli")
public record CliProperties(long chainAwaitMs) {

    @ConstructorBinding
    public CliProperties {
        if (chainAwaitMs <= 0) {
            chainAwaitMs = 600_000L;
        }
    }
}
