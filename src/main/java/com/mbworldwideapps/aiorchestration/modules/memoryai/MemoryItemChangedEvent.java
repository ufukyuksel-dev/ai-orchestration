package com.mbworldwideapps.aiorchestration.modules.memoryai;

public record MemoryItemChangedEvent(MemoryItem item, Origin origin, MemoryItem previousItem) {

    public MemoryItemChangedEvent(MemoryItem item) {
        this(item, Origin.NORMAL, null);
    }

    public MemoryItemChangedEvent(MemoryItem item, Origin origin) {
        this(item, origin, null);
    }

    public MemoryItemChangedEvent(MemoryItem item, MemoryItem previousItem) {
        this(item, Origin.NORMAL, previousItem);
    }

    public enum Origin {
        NORMAL,
        EXTERNAL_EDIT,
        LEARNING_CORRECTION
    }
}
