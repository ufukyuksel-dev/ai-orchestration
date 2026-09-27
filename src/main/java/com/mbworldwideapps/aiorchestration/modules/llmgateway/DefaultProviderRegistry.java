package com.mbworldwideapps.aiorchestration.modules.llmgateway;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.function.Function;

import com.mbworldwideapps.aiorchestration.config.AiOrchestrationProperties;
import com.mbworldwideapps.aiorchestration.modules.scanner.ScannerProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class DefaultProviderRegistry implements ProviderRegistry {

    private final Map<String, LLMProvider> providers;

    @Autowired
    public DefaultProviderRegistry(List<LLMProvider> providers, AiOrchestrationProperties properties,
            ScannerProperties scannerProperties,
            @Qualifier("llmGatewayExecutorService") ExecutorService executorService,
            @Qualifier("scannerLlmExecutorService") ExecutorService scannerExecutorService) {
        Map<String, LLMProvider> indexed = new LinkedHashMap<>();
        // Scanner-role LLM calls run on a dedicated bounded executor so a scan's semantic grind does not
        // contend with other calls on the shared gateway pool.
        Function<GenerationRequest, ExecutorService> executorResolver = request -> {
            String role = request == null ? null : request.role();
            return isScannerRole(role) ? scannerExecutorService : executorService;
        };
        for (LLMProvider provider : providers) {
            indexed.put(provider.id(),
                    new TimeBoxedLLMProvider(provider,
                            request -> timeoutFor(request, properties, scannerProperties),
                            executorResolver));
        }
        this.providers = Map.copyOf(indexed);
    }

    DefaultProviderRegistry(List<LLMProvider> providers, AiOrchestrationProperties properties,
            ExecutorService executorService) {
        this(providers, properties, null, executorService, executorService);
    }

    @Override
    public LLMProvider get(String providerId) {
        LLMProvider provider = providers.get(providerId);
        if (provider == null) {
            throw new IllegalArgumentException("Unsupported LLM provider: " + providerId
                    + ". Registered providers: " + providers.keySet());
        }
        return provider;
    }

    @Override
    public Set<String> providerIds() {
        return providers.keySet();
    }

    private static long timeoutFor(GenerationRequest request, AiOrchestrationProperties properties,
            ScannerProperties scannerProperties) {
        if (isScannerRole(request.role()) && scannerProperties != null) {
            return scannerProperties.semantic().timeoutMs();
        }
        return properties.generation().timeoutMs();
    }

    private static boolean isScannerRole(String role) {
        return role != null && (role.equals("scanner") || role.startsWith("scanner-"));
    }
}
