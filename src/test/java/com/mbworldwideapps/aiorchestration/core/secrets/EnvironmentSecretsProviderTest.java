package com.mbworldwideapps.aiorchestration.core.secrets;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class EnvironmentSecretsProviderTest {

    @Test
    void resolvesEnvStyleSecretReferencesOnly() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("AI_ORCH_TEST_SECRET", "secret-value");
        EnvironmentSecretsProvider provider = new EnvironmentSecretsProvider(environment);

        assertThat(provider.getSecret("env:AI_ORCH_TEST_SECRET")).contains("secret-value");
        assertThat(provider.getSecret("vault:secret/data/ai-orchestration")).isEmpty();
        assertThat(provider.getSecret("")).isEmpty();
    }
}
