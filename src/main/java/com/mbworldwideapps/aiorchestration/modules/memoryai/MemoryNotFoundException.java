package com.mbworldwideapps.aiorchestration.modules.memoryai;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.NOT_FOUND)
public class MemoryNotFoundException extends RuntimeException {

    public MemoryNotFoundException(UUID id) {
        super("Memory item not found: " + id);
    }
}
