package com.mbworldwideapps.aiorchestration.modules.llmgateway;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class LLMGatewayConfig {

    @Bean(destroyMethod = "shutdownNow")
    ExecutorService llmGatewayExecutorService() {
        AtomicInteger count = new AtomicInteger();
        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable, "llm-gateway-" + count.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        return Executors.newFixedThreadPool(2, threadFactory);
    }

    @Bean(destroyMethod = "shutdownNow")
    ExecutorService scannerLlmExecutorService() {
        AtomicInteger count = new AtomicInteger();
        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable, "scanner-llm-" + count.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        return Executors.newFixedThreadPool(2, threadFactory);
    }
}
