package com.mbworldwideapps.aiorchestration.core.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LoopbackAddressMatcherTest {

    @Test
    void acceptsLoopbackAddresses() {
        assertThat(LoopbackAddressMatcher.isLoopback("127.0.0.1")).isTrue();
        assertThat(LoopbackAddressMatcher.isLoopback("::1")).isTrue();
        assertThat(LoopbackAddressMatcher.isLoopback("[::1]")).isTrue();
        assertThat(LoopbackAddressMatcher.isLoopback("::ffff:127.0.0.1")).isTrue();
    }

    @Test
    void rejectsWildcardAndNonLoopbackAddresses() {
        assertThat(LoopbackAddressMatcher.isLoopback(null)).isFalse();
        assertThat(LoopbackAddressMatcher.isLoopback("")).isFalse();
        assertThat(LoopbackAddressMatcher.isLoopback("0.0.0.0")).isFalse();
        assertThat(LoopbackAddressMatcher.isLoopback("::")).isFalse();
        assertThat(LoopbackAddressMatcher.isLoopback("10.0.0.5")).isFalse();
    }
}
