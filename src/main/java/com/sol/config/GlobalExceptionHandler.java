package com.sol.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

import java.time.Instant;
import java.util.Map;

/**
 * Global exception handler for the application
 */
@ControllerAdvice
@Slf4j
public class GlobalExceptionHandler {
    
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleException(Exception e) {
        log.error("Unhandled exception: ", e);
        
        return ResponseEntity.status(500).body(Map.of(
            "error", "Internal server error",
            "message", e.getMessage(),
            "timestamp", Instant.now().toString()
        ));
    }
    
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("Invalid argument: {}", e.getMessage());
        
        return ResponseEntity.status(400).body(Map.of(
            "error", "Bad request",
            "message", e.getMessage(),
            "timestamp", Instant.now().toString()
        ));
    }
    
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalState(IllegalStateException e) {
        log.warn("Invalid state: {}", e.getMessage());
        
        return ResponseEntity.status(409).body(Map.of(
            "error", "Conflict",
            "message", e.getMessage(),
            "timestamp", Instant.now().toString()
        ));
    }
}