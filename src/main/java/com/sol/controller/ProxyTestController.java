package com.sol.controller;

import com.sol.proxy.DatabaseProxyProvider;
import com.sol.repository.ProxyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Admin endpoint for testing and monitoring proxy database functionality
 */
@RestController
@RequestMapping("/api/admin/proxy")
@RequiredArgsConstructor
@Slf4j
public class ProxyTestController {

    private final ProxyRepository proxyRepository;
    private final DatabaseProxyProvider proxyProvider;

    /**
     * Test endpoint to verify proxy database connectivity and data
     */
    @GetMapping("/test")
    public ResponseEntity<Map<String, Object>> testProxyDatabase() {
        try {
            Map<String, Object> response = new HashMap<>();
            
            // Check total proxy count
            long totalProxies = proxyRepository.count();
            response.put("totalProxies", totalProxies);
            
            if (totalProxies == 0) {
                response.put("status", "error");
                response.put("message", "No proxy records found in database");
                return ResponseEntity.status(500).body(response);
            }
            
            // Test fetching a small batch
            List<String> testSessions = proxyProvider.fetchProxySessions(5);
            response.put("sampleCount", testSessions.size());
            response.put("sampleSessions", testSessions.stream()
                    .map(session -> {
                        // Mask sensitive data for security
                        String[] parts = session.split(":");
                        if (parts.length >= 4) {
                            return parts[0] + ":" + parts[1] + ":***:***";
                        }
                        return "Invalid format";
                    })
                    .toList());
            
            response.put("status", "success");
            response.put("message", "Proxy database is working correctly");
            response.put("timestamp", System.currentTimeMillis());
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            log.error("Error testing proxy database", e);
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("status", "error");
            errorResponse.put("message", e.getMessage());
            errorResponse.put("timestamp", System.currentTimeMillis());
            
            return ResponseEntity.status(500).body(errorResponse);
        }
    }

    /**
     * Test batch fetching functionality
     */
    @GetMapping("/test-batch")
    public ResponseEntity<Map<String, Object>> testBatchFetching(
            @RequestParam(defaultValue = "10") int batchSize) {
        try {
            Map<String, Object> response = new HashMap<>();
            
            // Test multiple batch fetches
            List<String> batch1 = proxyProvider.fetchNextBatchSessions(batchSize);
            List<String> batch2 = proxyProvider.fetchNextBatchSessions(batchSize);
            
            response.put("batch1Size", batch1.size());
            response.put("batch2Size", batch2.size());
            response.put("batchesAreDifferent", !batch1.equals(batch2));
            
            // Show first session from each batch (masked)
            if (!batch1.isEmpty()) {
                String[] parts1 = batch1.get(0).split(":");
                response.put("batch1FirstSession", parts1.length >= 4 ? 
                        parts1[0] + ":" + parts1[1] + ":***:***" : "Invalid format");
            }
            
            if (!batch2.isEmpty()) {
                String[] parts2 = batch2.get(0).split(":");
                response.put("batch2FirstSession", parts2.length >= 4 ? 
                        parts2[0] + ":" + parts2[1] + ":***:***" : "Invalid format");
            }
            
            response.put("status", "success");
            response.put("message", "Batch fetching test completed");
            response.put("timestamp", System.currentTimeMillis());
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            log.error("Error testing batch proxy fetching", e);
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("status", "error");
            errorResponse.put("message", e.getMessage());
            errorResponse.put("timestamp", System.currentTimeMillis());
            
            return ResponseEntity.status(500).body(errorResponse);
        }
    }
}