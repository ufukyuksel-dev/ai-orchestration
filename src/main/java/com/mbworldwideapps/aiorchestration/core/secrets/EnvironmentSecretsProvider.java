package com.mbworldwideapps.aiorchestration.core.secrets;

import java.util.Optional;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class EnvironmentSecretsProvider implements SecretsProvider {

    private static final String ENV_PREFIX = "env:";

    private final Environment environment;

    public EnvironmentSecretsProvider(Environment environment) {
        this.environment = environment;
    }

    @Override
    public Optional<String> getSecret(String path) {
        if (path == null || path.isBlank()) {
            return Optional.empty();
        }
        if (path.startsWith(ENV_PREFIX)) {
            return Optional.ofNullable(environment.getProperty(path.substring(ENV_PREFIX.length())));
        }
        return Optional.empty();
    }
}
