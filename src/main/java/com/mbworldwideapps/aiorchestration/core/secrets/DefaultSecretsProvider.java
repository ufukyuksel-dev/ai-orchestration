package com.mbworldwideapps.aiorchestration.core.secrets;

import java.util.Optional;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

@Primary
@Component
public class DefaultSecretsProvider implements SecretsProvider {

    private final EnvironmentSecretsProvider environmentSecretsProvider;
    private final VaultSecretsProvider vaultSecretsProvider;

    public DefaultSecretsProvider(EnvironmentSecretsProvider environmentSecretsProvider,
            VaultSecretsProvider vaultSecretsProvider) {
        this.environmentSecretsProvider = environmentSecretsProvider;
        this.vaultSecretsProvider = vaultSecretsProvider;
    }

    @Override
    public Optional<String> getSecret(String path) {
        if (path == null || path.isBlank()) {
            return Optional.empty();
        }
        if (path.startsWith("vault:")) {
            return vaultSecretsProvider.getSecret(path);
        }
        return environmentSecretsProvider.getSecret(path);
    }
}
