package com.mbworldwideapps.aiorchestration.modules.memoryai;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class MemoryTypeTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void discoveryRoundTripsThroughValueAndJson() throws Exception {
        assertThat(MemoryType.from(" DISCOVERY ")).isEqualTo(MemoryType.DISCOVERY);
        assertThat(MemoryType.DISCOVERY.value()).isEqualTo("discovery");
        assertThat(mapper.writeValueAsString(MemoryType.DISCOVERY)).isEqualTo("\"discovery\"");
        assertThat(mapper.readValue("\"discovery\"", MemoryType.class)).isEqualTo(MemoryType.DISCOVERY);
    }
}
