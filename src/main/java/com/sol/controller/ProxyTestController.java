package com.sol.controller;

import com.sol.proxy.DatabaseProxyProvider;
import com.sol.proxy.DynamicProxyClientFactory;
import com.sol.repository.ProxyRepository;
import com.zaxxer.hikari.HikariDataSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
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
    private final DynamicProxyClientFactory clientFactory;
    
    @Autowired
    private DataSource dataSource;

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

    /**
     * Memory monitoring endpoint to track heap usage and potential leaks
     */
    @GetMapping("/memory")
    public ResponseEntity<Map<String, Object>> getMemoryStats() {
        try {
            Runtime runtime = Runtime.getRuntime();
            MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
            MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();
            MemoryUsage nonHeapUsage = memoryBean.getNonHeapMemoryUsage();
            
            Map<String, Object> memory = new HashMap<>();
            
            // Runtime memory info
            memory.put("runtime_totalMemory_MB", runtime.totalMemory() / 1024 / 1024);
            memory.put("runtime_freeMemory_MB", runtime.freeMemory() / 1024 / 1024);
            memory.put("runtime_usedMemory_MB", (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024);
            memory.put("runtime_maxMemory_MB", runtime.maxMemory() / 1024 / 1024);
            
            // Memory pool info
            memory.put("heap_used_MB", heapUsage.getUsed() / 1024 / 1024);
            memory.put("heap_committed_MB", heapUsage.getCommitted() / 1024 / 1024);
            memory.put("heap_max_MB", heapUsage.getMax() / 1024 / 1024);
            memory.put("nonHeap_used_MB", nonHeapUsage.getUsed() / 1024 / 1024);
            memory.put("nonHeap_committed_MB", nonHeapUsage.getCommitted() / 1024 / 1024);
            
            // Memory usage percentage
            double usagePercent = (double) heapUsage.getUsed() / heapUsage.getMax() * 100;
            memory.put("heap_usage_percent", Math.round(usagePercent * 100.0) / 100.0);
            
            // Force garbage collection for testing (before measurement)
            long beforeGC = heapUsage.getUsed();
            System.gc();
            Thread.sleep(100); // Give GC time to run
            
            MemoryUsage afterGcUsage = memoryBean.getHeapMemoryUsage();
            memory.put("beforeGC_used_MB", beforeGC / 1024 / 1024);
            memory.put("afterGC_used_MB", afterGcUsage.getUsed() / 1024 / 1024);
            memory.put("gcReclaimed_MB", (beforeGC - afterGcUsage.getUsed()) / 1024 / 1024);
            
            memory.put("status", "success");
            memory.put("timestamp", System.currentTimeMillis());
            
            return ResponseEntity.ok(memory);
            
        } catch (Exception e) {
            log.error("Error getting memory stats", e);
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("status", "error");
            errorResponse.put("message", e.getMessage());
            return ResponseEntity.status(500).body(errorResponse);
        }
    }

    /**
     * Database connection pool monitoring endpoint
     */
    @GetMapping("/connections")
    public ResponseEntity<Map<String, Object>> getConnectionStats() {
        try {
            Map<String, Object> stats = new HashMap<>();
            
            // Add HikariCP monitoring if using HikariCP
            try {
                if (dataSource instanceof HikariDataSource) {
                    HikariDataSource ds = (HikariDataSource) dataSource;
                    stats.put("pool_type", "HikariCP");
                    stats.put("activeConnections", ds.getHikariPoolMXBean().getActiveConnections());
                    stats.put("idleConnections", ds.getHikariPoolMXBean().getIdleConnections());
                    stats.put("totalConnections", ds.getHikariPoolMXBean().getTotalConnections());
                    stats.put("threadsAwaitingConnection", ds.getHikariPoolMXBean().getThreadsAwaitingConnection());
                    stats.put("maximumPoolSize", ds.getMaximumPoolSize());
                    stats.put("minimumIdle", ds.getMinimumIdle());
                    stats.put("connectionTimeout", ds.getConnectionTimeout());
                    stats.put("idleTimeout", ds.getIdleTimeout());
                    stats.put("maxLifetime", ds.getMaxLifetime());
                } else {
                    stats.put("pool_type", "Unknown - " + dataSource.getClass().getSimpleName());
                    stats.put("dataSourceClass", dataSource.getClass().getName());
                }
            } catch (Exception e) {
                stats.put("connection_pool_error", "Unable to get connection pool stats: " + e.getMessage());
            }
            
            // Test database connectivity
            try {
                long startTime = System.currentTimeMillis();
                long proxyCount = proxyRepository.count();
                long queryTime = System.currentTimeMillis() - startTime;
                
                stats.put("database_connectivity", "OK");
                stats.put("proxy_count", proxyCount);
                stats.put("query_time_ms", queryTime);
            } catch (Exception e) {
                stats.put("database_connectivity", "ERROR: " + e.getMessage());
            }
            
            stats.put("status", "success");
            stats.put("timestamp", System.currentTimeMillis());
            
            return ResponseEntity.ok(stats);
            
        } catch (Exception e) {
            log.error("Error getting connection stats", e);
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("status", "error");
            errorResponse.put("message", e.getMessage());
            return ResponseEntity.status(500).body(errorResponse);
        }
    }

    /**
     * Force garbage collection and return memory stats (for testing)
     */
    @GetMapping("/gc")
    public ResponseEntity<Map<String, Object>> forceGarbageCollection() {
        try {
            MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
            MemoryUsage beforeGc = memoryBean.getHeapMemoryUsage();
            
            // Force GC
            System.gc();
            Thread.sleep(500); // Give GC more time
            
            MemoryUsage afterGc = memoryBean.getHeapMemoryUsage();
            
            Map<String, Object> result = new HashMap<>();
            result.put("beforeGC_used_MB", beforeGc.getUsed() / 1024 / 1024);
            result.put("afterGC_used_MB", afterGc.getUsed() / 1024 / 1024);
            result.put("memoryReclaimed_MB", (beforeGc.getUsed() - afterGc.getUsed()) / 1024 / 1024);
            result.put("status", "success");
            result.put("message", "Garbage collection completed");
            result.put("timestamp", System.currentTimeMillis());
            
            return ResponseEntity.ok(result);
            
        } catch (Exception e) {
            log.error("Error during garbage collection", e);
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("status", "error");
            errorResponse.put("message", e.getMessage());
            return ResponseEntity.status(500).body(errorResponse);
        }
    }

    /**
     * WebClient pool monitoring endpoint to track active clients and potential leaks
     */
    @GetMapping("/webclient-pool")
    public ResponseEntity<Map<String, Object>> getWebClientPoolStats() {
        try {
            Map<String, Object> stats = new HashMap<>();
            
            // Get pool statistics
            var poolStats = clientFactory.getPoolStats();
            stats.put("activeClients", poolStats.getActiveClients());
            stats.put("maxConnections", poolStats.getMaxConnections());
            stats.put("connectionProviderInfo", poolStats.getConnectionProviderInfo());
            
            // Memory info for context
            Runtime runtime = Runtime.getRuntime();
            stats.put("runtime_usedMemory_MB", (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024);
            
            stats.put("status", "success");
            stats.put("timestamp", System.currentTimeMillis());
            
            return ResponseEntity.ok(stats);
            
        } catch (Exception e) {
            log.error("Error getting WebClient pool stats", e);
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("status", "error");
            errorResponse.put("message", e.getMessage());
            return ResponseEntity.status(500).body(errorResponse);
        }
    }
}