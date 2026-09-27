package com.mbworldwideapps.aiorchestration.core.security;

import java.net.InetAddress;
import java.net.UnknownHostException;

public final class LoopbackAddressMatcher {

    private LoopbackAddressMatcher() {
    }

    public static boolean isLoopback(String address) {
        String normalized = normalize(address);
        if (normalized == null) {
            return false;
        }
        if (normalized.startsWith("::ffff:")) {
            return isLoopback(normalized.substring("::ffff:".length()));
        }
        try {
            return InetAddress.getByName(normalized).isLoopbackAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }

    private static String normalize(String address) {
        if (address == null || address.isBlank()) {
            return null;
        }
        String normalized = address.trim();
        if (normalized.startsWith("[") && normalized.endsWith("]")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        int zoneIndex = normalized.indexOf('%');
        if (zoneIndex > 0) {
            normalized = normalized.substring(0, zoneIndex);
        }
        return normalized.isBlank() ? null : normalized;
    }
}
