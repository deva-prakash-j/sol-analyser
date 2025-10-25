package com.sol.proxy;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * REST API for monitoring proxy health metrics
 * Provides real-time visibility into proxy performance and circuit breaker status
 */
@RestController
@RequestMapping("/api/admin/proxies")
@RequiredArgsConstructor
@Slf4j
public class ProxyHealthController {
    
    private final ProxyHealthTracker healthTracker;
    private final ProxyPool proxyPool;
    
    /**
     * Get health summary for all proxies
     */
    @GetMapping("/health")
    public ResponseEntity<HealthOverview> getProxyHealth() {
        int proxyCount = proxyPool.size();
        ProxyHealthTracker.HealthSummary summary = healthTracker.getSummary(proxyCount);
        
        Map<Integer, ProxyMetrics> proxyDetails = new HashMap<>();
        for (int i = 0; i < proxyCount; i++) {
            ProxyHealthTracker.ProxyHealth health = healthTracker.getHealth(i);
            proxyDetails.put(i, new ProxyMetrics(
                    i,
                    health.isHealthy(),
                    health.isCircuitOpen(),
                    health.getConsecutiveFailures(),
                    health.getTotalRequests(),
                    health.getSuccessfulRequests(),
                    health.getSuccessRate() * 100,
                    health.getAvgResponseTimeMs()
            ));
        }
        
        HealthOverview overview = new HealthOverview(
                summary.totalProxies(),
                summary.healthyProxies(),
                summary.unhealthyProxies(),
                summary.circuitOpenProxies(),
                summary.totalRequests(),
                summary.overallSuccessRate(),
                proxyDetails
        );
        
        return ResponseEntity.ok(overview);
    }
    
    /**
     * Get health details for a specific proxy
     */
    @GetMapping("/health/{proxyIndex}")
    public ResponseEntity<ProxyMetrics> getProxyHealth(@PathVariable int proxyIndex) {
        if (proxyIndex < 0 || proxyIndex >= proxyPool.size()) {
            return ResponseEntity.badRequest().build();
        }
        
        ProxyHealthTracker.ProxyHealth health = healthTracker.getHealth(proxyIndex);
        ProxyMetrics metrics = new ProxyMetrics(
                proxyIndex,
                health.isHealthy(),
                health.isCircuitOpen(),
                health.getConsecutiveFailures(),
                health.getTotalRequests(),
                health.getSuccessfulRequests(),
                health.getSuccessRate() * 100,
                health.getAvgResponseTimeMs()
        );
        
        return ResponseEntity.ok(metrics);
    }
    
    /**
     * Get list of healthy proxy indices
     */
    @GetMapping("/healthy")
    public ResponseEntity<HealthyProxiesResponse> getHealthyProxies() {
        List<Integer> healthyIndices = healthTracker.getHealthyProxyIndices();
        return ResponseEntity.ok(new HealthyProxiesResponse(
                healthyIndices.size(),
                healthyIndices
        ));
    }
    
    /**
     * Response models
     */
    public record HealthOverview(
            int totalProxies,
            int healthyProxies,
            int unhealthyProxies,
            int circuitOpenProxies,
            long totalRequests,
            double overallSuccessRate,
            Map<Integer, ProxyMetrics> proxyDetails
    ) {}
    
    public record ProxyMetrics(
            int proxyIndex,
            boolean healthy,
            boolean circuitOpen,
            int consecutiveFailures,
            int totalRequests,
            int successfulRequests,
            double successRate,
            double avgResponseTimeMs
    ) {}
    
    public record HealthyProxiesResponse(
            int count,
            List<Integer> healthyProxyIndices
    ) {}
}
