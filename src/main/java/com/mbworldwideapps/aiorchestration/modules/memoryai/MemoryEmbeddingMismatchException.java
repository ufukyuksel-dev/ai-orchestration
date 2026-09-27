package com.mbworldwideapps.aiorchestration.modules.memoryai;

public class MemoryEmbeddingMismatchException extends IllegalStateException {

    public MemoryEmbeddingMismatchException(String lane, String expected, String actual) {
        super("EMBEDDING_MISMATCH lane=" + lane + " expected=" + expected + " actual=" + actual);
    }
}
