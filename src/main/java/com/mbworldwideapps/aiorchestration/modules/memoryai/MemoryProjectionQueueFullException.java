package com.mbworldwideapps.aiorchestration.modules.memoryai;

public class MemoryProjectionQueueFullException extends RuntimeException {

    public MemoryProjectionQueueFullException(int capacity) {
        super("MEMORY_PROJECTION_QUEUE_FULL: capacity=" + capacity);
    }
}
