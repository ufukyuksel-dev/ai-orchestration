package com.mbworldwideapps.aiorchestration.modules.plancompiler;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public record PlanCheckerContract(
        String type,
        String implementationVersion,
        String configSchemaVersion,
        boolean deterministic) {

    public PlanCheckerContract {
        if (type == null || type.isBlank() || implementationVersion == null || implementationVersion.isBlank()
                || configSchemaVersion == null || configSchemaVersion.isBlank()) {
            throw new IllegalArgumentException("complete PLAN checker contract is required");
        }
    }

    public String contractHash() {
        String material = "plan-checker-contract/v1\0" + type + "\0" + implementationVersion
                + "\0" + configSchemaVersion + "\0" + deterministic;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
