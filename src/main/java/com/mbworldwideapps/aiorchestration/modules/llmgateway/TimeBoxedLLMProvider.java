package com.mbworldwideapps.aiorchestration.modules.llmgateway;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

public class TimeBoxedLLMProvider implements LLMProvider {

    private final LLMProvider delegate;
    private final Function<GenerationRequest, Long> timeoutResolver;
    private final Function<GenerationRequest, ExecutorService> executorResolver;

    public TimeBoxedLLMProvider(LLMProvider delegate, long timeoutMs, ExecutorService executorService) {
        this(delegate, request -> timeoutMs, executorService);
    }

    public TimeBoxedLLMProvider(LLMProvider delegate, Function<GenerationRequest, Long> timeoutResolver,
            ExecutorService executorService) {
        this(delegate, timeoutResolver, request -> executorService);
    }

    public TimeBoxedLLMProvider(LLMProvider delegate, Function<GenerationRequest, Long> timeoutResolver,
            Function<GenerationRequest, ExecutorService> executorResolver) {
        this.delegate = delegate;
        this.timeoutResolver = timeoutResolver;
        this.executorResolver = executorResolver;
    }

    @Override
    public String id() {
        return delegate.id();
    }

    @Override
    public GenerationResponse generate(GenerationRequest request) {
        Long resolvedTimeoutMs = timeoutResolver.apply(request);
        long timeoutMs = resolvedTimeoutMs == null || resolvedTimeoutMs <= 0 ? 8000L : resolvedTimeoutMs;
        ExecutorService executorService = executorResolver.apply(request);
        Future<GenerationResponse> future = executorService.submit(() -> delegate.generate(request));
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new LLMProviderTimeoutException(delegate.id(), timeoutMs, e);
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new LLMProviderTimeoutException(delegate.id(), timeoutMs, e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("LLM provider failed: " + delegate.id(), cause);
        }
    }
}
