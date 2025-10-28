package com.sol.proxy;

import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tracks health metrics for each proxy to enable intelligent routing
 * Helps avoid dead/slow proxies and implements circuit breaker pattern
 * DISABLED: No longer used with database-based proxy management
 */
// @Component - Removed: No longer needed with DatabaseProxyProvider
@Slf4j
public class ProxyHealthTracker {
    
    private static final int FAILURE_THRESHOLD = 5; // Mark unhealthy after 5 consecutive failures
    private static final long RECOVERY_WINDOW_MS = 60_000; // Try recovery after 1 min
    private static final int CIRCUIT_OPEN_THRESHOLD = 10; // Open circuit after 10 consecutive failures
    
    private final Map<Integer, ProxyHealth> healthMap = new ConcurrentHashMap<>();
    
    /**
     * Health metrics for a single proxy
     */
    public static class ProxyHealth {
        private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
        private final AtomicInteger totalRequests = new AtomicInteger(0);
        private final AtomicInteger successfulRequests = new AtomicInteger(0);
        private final AtomicLong lastFailureTime = new AtomicLong(0);
        private final AtomicLong totalResponseTime = new AtomicLong(0);
        private volatile boolean circuitOpen = false;
        
        public void recordSuccess(long responseTimeMs) {
            totalRequests.incrementAndGet();
            successfulRequests.incrementAndGet();
            consecutiveFailures.set(0);
            totalResponseTime.addAndGet(responseTimeMs);
            circuitOpen = false;
        }
        
        public void recordFailure() {
            totalRequests.incrementAndGet();
            int failures = consecutiveFailures.incrementAndGet();
            lastFailureTime.set(System.currentTimeMillis());
            
            if (failures >= CIRCUIT_OPEN_THRESHOLD) {
                circuitOpen = true;
            }
        }
        
        public boolean isHealthy() {
            // Circuit breaker check
            if (circuitOpen) {
                long timeSinceFailure = System.currentTimeMillis() - lastFailureTime.get();
                if (timeSinceFailure < RECOVERY_WINDOW_MS) {
                    return false; // Still in cooldown
                }
                // Try recovery (half-open state)
                log.info("🔄 Attempting recovery for proxy with {} consecutive failures", 
                        consecutiveFailures.get());
                circuitOpen = false;
                consecutiveFailures.set(0);
            }
            
            return consecutiveFailures.get() < FAILURE_THRESHOLD;
        }
        
        public double getSuccessRate() {
            int total = totalRequests.get();
            return total == 0 ? 1.0 : (double) successfulRequests.get() / total;
        }
        
        public double getAvgResponseTimeMs() {
            int successful = successfulRequests.get();
            return successful == 0 ? 0 : (double) totalResponseTime.get() / successful;
        }
        
        public int getConsecutiveFailures() {
            return consecutiveFailures.get();
        }
        
        public boolean isCircuitOpen() {
            return circuitOpen;
        }
        
        public int getTotalRequests() {
            return totalRequests.get();
        }
        
        public int getSuccessfulRequests() {
            return successfulRequests.get();
        }
    }
    
    public void recordSuccess(int proxyIndex, long responseTimeMs) {
        ProxyHealth health = healthMap.computeIfAbsent(proxyIndex, k -> new ProxyHealth());
        health.recordSuccess(responseTimeMs);
        
        // Log recovery if proxy was previously failing
        if (health.getConsecutiveFailures() == 0 && health.getTotalRequests() > 10) {
            log.debug("✓ Proxy {} recovered - Success rate: {:.1f}%", 
                    proxyIndex, health.getSuccessRate() * 100);
        }
    }
    
    public void recordFailure(int proxyIndex) {
        ProxyHealth health = healthMap.computeIfAbsent(proxyIndex, k -> new ProxyHealth());
        health.recordFailure();
        
        int failures = health.getConsecutiveFailures();
        if (failures == FAILURE_THRESHOLD) {
            log.warn("⚠️ Proxy {} marked UNHEALTHY after {} consecutive failures", 
                    proxyIndex, failures);
        } else if (health.isCircuitOpen()) {
            log.error("🔴 Circuit breaker OPEN for proxy {} after {} consecutive failures", 
                    proxyIndex, failures);
        }
    }
    
    public boolean isProxyHealthy(int proxyIndex) {
        ProxyHealth health = healthMap.get(proxyIndex);
        return health == null || health.isHealthy();
    }
    
    public ProxyHealth getHealth(int proxyIndex) {
        return healthMap.computeIfAbsent(proxyIndex, k -> new ProxyHealth());
    }
    
    public Map<Integer, ProxyHealth> getAllHealth() {
        return Map.copyOf(healthMap);
    }
    
    /**
     * Get next healthy proxy index with intelligent fallback
     * Avoids proxies with open circuits or high failure rates
     */
    public int selectHealthyProxy(int proxyCount, int preferredIndex) {
        // First try: use preferred proxy if healthy
        if (isProxyHealthy(preferredIndex)) {
            return preferredIndex;
        }
        
        // Second try: find next healthy proxy (round-robin)
        for (int attempt = 1; attempt < proxyCount; attempt++) {
            int candidate = (preferredIndex + attempt) % proxyCount;
            if (isProxyHealthy(candidate)) {
                log.debug("🔄 Proxy {} unhealthy, using proxy {} instead", 
                        preferredIndex, candidate);
                return candidate;
            }
        }
        
        // Last resort: return preferred (all proxies unhealthy - let retry logic handle it)
        log.warn("⚠️ No healthy proxies found! Using proxy {} anyway (all {} proxies unhealthy)", 
                preferredIndex, proxyCount);
        return preferredIndex;
    }
    
    /**
     * Get list of all healthy proxy indices (for batch processing)
     */
    public List<Integer> getHealthyProxyIndices() {
        return healthMap.entrySet().stream()
                .filter(entry -> entry.getValue().isHealthy())
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
    }
    
    /**
     * Get health statistics summary
     */
    public HealthSummary getSummary(int proxyCount) {
        int healthy = 0;
        int unhealthy = 0;
        int circuitOpen = 0;
        long totalRequests = 0;
        long successfulRequests = 0;
        
        for (int i = 0; i < proxyCount; i++) {
            ProxyHealth health = healthMap.get(i);
            if (health != null) {
                if (health.isHealthy()) healthy++;
                else unhealthy++;
                if (health.isCircuitOpen()) circuitOpen++;
                totalRequests += health.getTotalRequests();
                successfulRequests += health.getSuccessfulRequests();
            } else {
                healthy++; // Unused proxies considered healthy
            }
        }
        
        double overallSuccessRate = totalRequests == 0 ? 100.0 : 
                (double) successfulRequests / totalRequests * 100;
        
        return new HealthSummary(proxyCount, healthy, unhealthy, circuitOpen, 
                totalRequests, overallSuccessRate);
    }
    
    public record HealthSummary(
            int totalProxies,
            int healthyProxies,
            int unhealthyProxies,
            int circuitOpenProxies,
            long totalRequests,
            double overallSuccessRate
    ) {}
}
