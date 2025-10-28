package com.sol.proxy;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Factory for creating managed WebClients with automatic resource cleanup
 * Uses WebClientPool to prevent memory leaks from thousands of client instances
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DynamicProxyClientFactory {

    private final WebClientPool webClientPool;

    /**
     * Create managed WebClient with specific proxy credentials
     * Uses WebClientPool for proper resource management and connection pooling
     */
    public WebClientPool.ManagedWebClient createManagedClient(ProxyCredentials credentials) {
        return webClientPool.createManagedClient(credentials);
    }

    /**
     * Create multiple managed WebClients for a list of proxy strings
     * Returns managed clients that must be properly disposed after use
     */
    public List<WebClientPool.ManagedWebClient> createManagedClients(List<String> proxyStrings, DatabaseProxyProvider proxyProvider) {
        List<WebClientPool.ManagedWebClient> clients = proxyStrings.stream()
                .map(proxyProvider::parseProxyString)
                .map(this::createManagedClient)
                .collect(Collectors.toList());
        
        log.debug("Created {} managed WebClient instances", clients.size());
        return clients;
    }

    /**
     * Create multiple managed WebClients for a list of proxy strings (legacy compatibility)
     */
    public List<WebClientPool.ManagedWebClient> createManagedClients(List<String> proxyStrings, OculusProxyProvider proxyProvider) {
        List<WebClientPool.ManagedWebClient> clients = proxyStrings.stream()
                .map(proxyProvider::parseProxyString)
                .map(this::createManagedClient)
                .collect(Collectors.toList());
        
        log.debug("Created {} managed WebClient instances (legacy)", clients.size());
        return clients;
    }

    /**
     * Get pool statistics for monitoring
     */
    public WebClientPool.PoolStats getPoolStats() {
        return webClientPool.getPoolStats();
    }

    /**
     * Get the underlying WebClientPool for direct access
     */
    public WebClientPool getWebClientPool() {
        return webClientPool;
    }
}
