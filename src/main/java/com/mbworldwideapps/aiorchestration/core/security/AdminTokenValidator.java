package com.mbworldwideapps.aiorchestration.core.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import com.mbworldwideapps.aiorchestration.core.policy.PolicyProperties;
import com.mbworldwideapps.aiorchestration.modules.mcp.server.McpAccessException;
import org.springframework.stereotype.Component;

@Component
public class AdminTokenValidator {

    private final PolicyProperties properties;

    public AdminTokenValidator(PolicyProperties properties) {
        this.properties = properties;
    }

    public void require(String providedToken) {
        if (properties.adminToken().isBlank()) {
            throw new McpAccessException("Admin token is not configured");
        }
        if (!constantTimeEquals(properties.adminToken(), providedToken)) {
            throw new McpAccessException("Invalid admin token");
        }
    }

    private static boolean constantTimeEquals(String expected, String provided) {
        if (provided == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8));
    }
}
