package com.mbworldwideapps.aiorchestration.modules.llmgateway;

import java.time.Duration;
import java.time.Instant;

import com.mbworldwideapps.aiorchestration.modules.llmgateway.SourceSnippet;
import org.springframework.stereotype.Component;

@Component
public class ClaudeProviderStub implements LLMProvider {

    private static final String PROVIDER_ID = "claude-stub";

    @Override
    public String id() {
        return PROVIDER_ID;
    }

    @Override
    public GenerationResponse generate(GenerationRequest request) {
        Instant started = Instant.now();
        String answer = answer(request);
        return new GenerationResponse(answer, PROVIDER_ID, Duration.between(started, Instant.now()).toMillis(),
                false, DegradedReason.NONE,
                TokenEstimator.estimate(request.question()) + TokenEstimator.estimate(answer),
                TokenEstimator.estimate(answer),
                request.injectedMemoryCount(),
                request.sources().size(),
                TokenEstimator.estimateInjected(request));
    }

    private String answer(GenerationRequest request) {
        if (request.sources().isEmpty()) {
            return "Bu soru icin izinli kaynaklarda yeterli baglam bulunamadi.";
        }
        StringBuilder builder = new StringBuilder();
        builder.append("Kaynakli cevap:\n");
        for (SourceSnippet source : request.sources()) {
            builder.append("- ").append(source.snippet()).append(" [").append(source.chunkId()).append("]\n");
        }
        if ("stale".equals(request.freshness())) {
            builder.append("Tazelik notu: Bu cevap stale kaynak iceriyor; guncel karar oncesi kaynak yenilenmeli.\n");
        }
        builder.append("Kaynaklar:\n");
        for (SourceSnippet source : request.sources()) {
            builder.append("- [").append(source.chunkId()).append("] ")
                    .append(source.title()).append(" (").append(source.sourceUrl()).append(")\n");
        }
        return builder.toString().trim();
    }

}
