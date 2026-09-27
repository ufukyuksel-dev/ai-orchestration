package com.mbworldwideapps.aiorchestration.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("ai-orchestration.job-memory")
public record JobMemoryProperties(String model, Integer dimensions, Double similarityThreshold) {
    public JobMemoryProperties {
        model = model == null ? "bge-m3" : model;
        dimensions = dimensions == null ? 1024 : dimensions;
        similarityThreshold = similarityThreshold == null ? 0.0 : similarityThreshold;
        if (!model.matches("[a-zA-Z0-9._:-]{1,80}") || dimensions < 1 || dimensions > 65536
                || !Double.isFinite(similarityThreshold) || similarityThreshold < 0 || similarityThreshold > 1) {
            throw new IllegalArgumentException("Invalid job-memory configuration");
        }
    }

    public String collectionName() {
        return "job_memory_" + model + "_" + dimensions;
    }
}
