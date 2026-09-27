package com.mbworldwideapps.aiorchestration.modules.memoryai;

public enum MemoryShadowMode {
    OFF,
    WRITE_ONLY,
    DUAL_READ,
    SHADOW_ONLY;

    public boolean writesShadow() {
        return this != OFF;
    }

    public boolean readsShadow() {
        return this == DUAL_READ || this == SHADOW_ONLY;
    }
}
