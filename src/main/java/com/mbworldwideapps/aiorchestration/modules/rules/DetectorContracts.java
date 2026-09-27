package com.mbworldwideapps.aiorchestration.modules.rules;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Set;

/** Builds a contract hash from capabilities and the exact implementation bytes. */
final class DetectorContracts {

    private DetectorContracts() {
    }

    static DetectorContract bind(String contractId, Set<BindingKind> promotableBindingKinds,
            Set<BindingKind> requiredInputKinds, Class<?>... implementationTypes) {
        MessageDigest digest = sha256();
        update(digest, contractId);
        promotableBindingKinds.stream().sorted(Comparator.comparing(BindingKind::value))
                .forEach(kind -> update(digest, "promotable:" + kind.value()));
        requiredInputKinds.stream().sorted(Comparator.comparing(BindingKind::value))
                .forEach(kind -> update(digest, "required:" + kind.value()));
        if (implementationTypes == null || implementationTypes.length == 0) {
            throw new IllegalArgumentException("detector implementation types are required");
        }
        for (Class<?> implementationType : implementationTypes) {
            update(digest, implementationType.getName());
            updateClassBytes(digest, implementationType);
            Package ownerPackage = implementationType.getPackage();
            update(digest, ownerPackage == null ? "" : String.valueOf(ownerPackage.getImplementationVersion()));
        }
        return new DetectorContract(contractId, promotableBindingKinds, requiredInputKinds,
                HexFormat.of().formatHex(digest.digest()));
    }

    private static void updateClassBytes(MessageDigest digest, Class<?> type) {
        String resource = "/" + type.getName().replace('.', '/') + ".class";
        try (InputStream input = type.getResourceAsStream(resource)) {
            if (input == null) {
                throw new IllegalStateException("detector implementation bytecode is unavailable: " + type.getName());
            }
            byte[] buffer = new byte[8_192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        } catch (IOException e) {
            throw new IllegalStateException("cannot fingerprint detector implementation: " + type.getName(), e);
        }
    }

    private static void update(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
        digest.update((byte) ':');
        digest.update(bytes);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
