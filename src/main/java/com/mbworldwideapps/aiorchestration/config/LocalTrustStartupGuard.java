package com.mbworldwideapps.aiorchestration.config;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import com.mbworldwideapps.aiorchestration.core.security.LoopbackAddressMatcher;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class LocalTrustStartupGuard implements InitializingBean {

    private final LocalTrustProperties properties;
    private final Environment environment;

    public LocalTrustStartupGuard(LocalTrustProperties properties, Environment environment) {
        this.properties = properties;
        this.environment = environment;
    }

    @Override
    public void afterPropertiesSet() {
        if (!properties.enabled()) {
            return;
        }
        Set<String> activeProfiles = Arrays.stream(environment.getActiveProfiles())
                .map(String::trim)
                .collect(Collectors.toUnmodifiableSet());
        if (activeProfiles.contains("prod") || activeProfiles.contains("linux-on-prem")) {
            throw new IllegalStateException(
                    "local-trust.enabled=true is forbidden with prod or linux-on-prem profiles");
        }
        String serverAddress = environment.getProperty("server.address");
        if (serverAddress == null || serverAddress.isBlank()) {
            throw new IllegalStateException(
                    "local-trust.enabled=true requires server.address to be an explicit loopback address");
        }
        String normalized = serverAddress.trim();
        if ("0.0.0.0".equals(normalized) || "::".equals(normalized)) {
            throw new IllegalStateException("local-trust.enabled=true cannot bind to wildcard server.address");
        }
        if (!LoopbackAddressMatcher.isLoopback(normalized)) {
            throw new IllegalStateException(
                    "local-trust.enabled=true requires loopback server.address, got: " + normalized);
        }
    }
}
