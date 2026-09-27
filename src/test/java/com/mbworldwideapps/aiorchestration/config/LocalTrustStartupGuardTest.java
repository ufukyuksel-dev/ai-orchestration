package com.mbworldwideapps.aiorchestration.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class LocalTrustStartupGuardTest {

    @Test
    void allowsLocalTrustOnlyWithLoopbackBind() {
        LocalTrustStartupGuard guard = new LocalTrustStartupGuard(enabledLocalTrust(),
                new MockEnvironment().withProperty("server.address", "127.0.0.1"));

        assertThatCode(guard::afterPropertiesSet).doesNotThrowAnyException();
    }

    @Test
    void blocksLocalTrustWithProdProfile() {
        MockEnvironment environment = new MockEnvironment().withProperty("server.address", "127.0.0.1");
        environment.setActiveProfiles("prod");
        LocalTrustStartupGuard guard = new LocalTrustStartupGuard(enabledLocalTrust(), environment);

        assertThatThrownBy(guard::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("forbidden");
    }

    @Test
    void blocksLocalTrustWithLinuxOnPremProfile() {
        MockEnvironment environment = new MockEnvironment().withProperty("server.address", "127.0.0.1");
        environment.setActiveProfiles("linux-on-prem");
        LocalTrustStartupGuard guard = new LocalTrustStartupGuard(enabledLocalTrust(), environment);

        assertThatThrownBy(guard::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("forbidden");
    }

    @Test
    void blocksLocalTrustWithoutExplicitServerAddress() {
        LocalTrustStartupGuard guard = new LocalTrustStartupGuard(enabledLocalTrust(), new MockEnvironment());

        assertThatThrownBy(guard::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("requires server.address");
    }

    @Test
    void blocksLocalTrustWithWildcardBind() {
        LocalTrustStartupGuard guard = new LocalTrustStartupGuard(enabledLocalTrust(),
                new MockEnvironment().withProperty("server.address", "0.0.0.0"));

        assertThatThrownBy(guard::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("wildcard");
    }

    @Test
    void blocksLocalTrustWithNonLoopbackBind() {
        LocalTrustStartupGuard guard = new LocalTrustStartupGuard(enabledLocalTrust(),
                new MockEnvironment().withProperty("server.address", "10.0.0.5"));

        assertThatThrownBy(guard::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("requires loopback");
    }

    @Test
    void ignoresServerAddressWhenLocalTrustDisabled() {
        LocalTrustStartupGuard guard = new LocalTrustStartupGuard(LocalTrustProperties.disabled(),
                new MockEnvironment());

        assertThatCode(guard::afterPropertiesSet).doesNotThrowAnyException();
    }

    private static LocalTrustProperties enabledLocalTrust() {
        return new LocalTrustProperties(true, "PROJECT_A", "local-client", List.of("memory.read"));
    }
}
