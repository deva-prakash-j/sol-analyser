package com.sol.controller;

import com.sol.proxy.DynamicProxyClientFactory;
import com.sol.proxy.WebClientPool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * Monitoring endpoint for WebClient pool health and resource usage
 */
@RestController
@RequestMapping("/api/admin/pool")
@RequiredArgsConstructor
@Slf4j
public class WebClientPoolController {

    private final DynamicProxyClientFactory clientFactory;

    /**
     * Get WebClient pool statistics for monitoring memory usage
     */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getPoolStats() {
        try {
            WebClientPool.PoolStats stats = clientFactory.getPoolStats();
            
            Map<String, Object> response = new HashMap<>();
            response.put("activeClients", stats.getActiveClients());
            response.put("maxConnections", stats.getMaxConnections());
            response.put("connectionProviderInfo", stats.getConnectionProviderInfo());
            response.put("timestamp", System.currentTimeMillis());
            
            // Memory information
            Runtime runtime = Runtime.getRuntime();
            long maxMemory = runtime.maxMemory();
            long totalMemory = runtime.totalMemory();
            long freeMemory = runtime.freeMemory();
            long usedMemory = totalMemory - freeMemory;
            
            Map<String, Object> memoryInfo = new HashMap<>();
            memoryInfo.put("maxMemoryMB", maxMemory / (1024 * 1024));
            memoryInfo.put("totalMemoryMB", totalMemory / (1024 * 1024));
            memoryInfo.put("usedMemoryMB", usedMemory / (1024 * 1024));
            memoryInfo.put("freeMemoryMB", freeMemory / (1024 * 1024));
            memoryInfo.put("memoryUsagePercent", (double) usedMemory / maxMemory * 100);
            
            response.put("memory", memoryInfo);
            response.put("status", "healthy");
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            log.error("Error retrieving pool stats", e);
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("status", "error");
            errorResponse.put("message", e.getMessage());
            errorResponse.put("timestamp", System.currentTimeMillis());
            
            return ResponseEntity.status(500).body(errorResponse);
        }
    }

    /**
     * Force garbage collection and return updated stats (use sparingly)
     */
    @GetMapping("/gc")
    public ResponseEntity<Map<String, Object>> forceGarbageCollection() {
        try {
            log.info("Manual garbage collection requested via admin endpoint");
            
            // Get memory before GC
            Runtime runtime = Runtime.getRuntime();
            long memoryBeforeGC = runtime.totalMemory() - runtime.freeMemory();
            
            // Force GC
            System.gc();
            
            // Wait a bit for GC to complete
            Thread.sleep(100);
            
            // Get memory after GC
            long memoryAfterGC = runtime.totalMemory() - runtime.freeMemory();
            long memoryFreed = memoryBeforeGC - memoryAfterGC;
            
            Map<String, Object> response = new HashMap<>();
            response.put("memoryBeforeGC_MB", memoryBeforeGC / (1024 * 1024));
            response.put("memoryAfterGC_MB", memoryAfterGC / (1024 * 1024));
            response.put("memoryFreed_MB", memoryFreed / (1024 * 1024));
            response.put("gcExecuted", true);
            response.put("timestamp", System.currentTimeMillis());
            
            // Include current pool stats
            WebClientPool.PoolStats stats = clientFactory.getPoolStats();
            response.put("activeClients", stats.getActiveClients());
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            log.error("Error during manual garbage collection", e);
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("status", "error");
            errorResponse.put("message", e.getMessage());
            errorResponse.put("timestamp", System.currentTimeMillis());
            
            return ResponseEntity.status(500).body(errorResponse);
        }
    }
}