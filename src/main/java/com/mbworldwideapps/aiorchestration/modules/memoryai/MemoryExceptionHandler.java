package com.mbworldwideapps.aiorchestration.modules.memoryai;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = {MemoryController.class, MemoryReviewController.class})
public class MemoryExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", "bad_request", "message", exception.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> validation(MethodArgumentNotValidException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", "validation_failed", "message", "Invalid memory request"));
    }

    @ExceptionHandler(MemoryPolicyViolationException.class)
    public ResponseEntity<Map<String, String>> policyViolation(MemoryPolicyViolationException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", "policy_rejected", "reason", exception.reason()));
    }

    @ExceptionHandler(MemoryProjectionQueueFullException.class)
    public ResponseEntity<Map<String, String>> projectionQueueFull(MemoryProjectionQueueFullException exception) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of(
                        "error", "projection_queue_full",
                        "message", exception.getMessage(),
                        "retryable", "true"));
    }
}
