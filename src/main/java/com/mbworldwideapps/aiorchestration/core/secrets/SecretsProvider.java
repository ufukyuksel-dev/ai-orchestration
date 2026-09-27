package com.mbworldwideapps.aiorchestration.core.secrets;

import java.util.Optional;

public interface SecretsProvider {

    Optional<String> getSecret(String path);
}
