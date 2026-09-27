package com.mbworldwideapps.aiorchestration.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("ai-orchestration.personal-memory")
public record PersonalMemoryProperties(String model, Integer dimensions, Double similarityThreshold, Integer maxContentBytes) {
    public PersonalMemoryProperties {
        model = model == null ? "bge-m3" : model;
        dimensions = dimensions == null ? 1024 : dimensions;
        similarityThreshold = similarityThreshold == null ? 0.5 : similarityThreshold;
        maxContentBytes = maxContentBytes == null ? 8192 : maxContentBytes;
        if (!model.matches("[a-zA-Z0-9._:-]{1,80}") || dimensions < 1 || dimensions > 65536
                || !Double.isFinite(similarityThreshold) || similarityThreshold < 0 || similarityThreshold > 1
                || maxContentBytes < 1 || maxContentBytes > 65536)
            throw new IllegalArgumentException("Invalid personal-memory configuration");
    }
    public String collectionName() {
        return "personal_memory_" + model + "_" + dimensions;
    }
}
