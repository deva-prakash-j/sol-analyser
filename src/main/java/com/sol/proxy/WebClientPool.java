package com.sol.proxy;

import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;
import reactor.netty.transport.ProxyProvider;

import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Manages WebClient instances with proper resource cleanup to prevent memory leaks
 * Uses connection pooling and automatic disposal of unused clients
 */
@Component
@Slf4j
public class WebClientPool {

    // 10MB buffer limit for large Solana RPC responses
    private static final int MAX_BUFFER_SIZE = 10 * 1024 * 1024; // 10MB
    
    // Connection pool configuration
    private static final int MAX_CONNECTIONS = 500; // Per pool
    private static final int PENDING_ACQUIRE_TIMEOUT = 45; // seconds
    private static final int MAX_IDLE_TIME = 20; // seconds
    private static final int MAX_LIFE_TIME = 60; // seconds
    
    // Track active clients for cleanup
    private final ConcurrentHashMap<String, WebClientWrapper> activeClients = new ConcurrentHashMap<>();
    private final AtomicLong clientIdGenerator = new AtomicLong(0);
    
    // Remove shared connection provider - create individual ones for proper cleanup
    
    public WebClientPool() {
        log.info("Initialized WebClientPool with individual connection providers for proper cleanup");
    }

    /**
     * Create a managed WebClient with automatic cleanup tracking
     */
    public ManagedWebClient createManagedClient(ProxyCredentials credentials) {
        String clientId = "client-" + clientIdGenerator.incrementAndGet();
        
        // Create individual ConnectionProvider for this proxy to ensure proper cleanup
        ConnectionProvider individualProvider = ConnectionProvider.builder("proxy-" + clientId)
                .maxConnections(50) // Smaller pool per proxy
                .pendingAcquireTimeout(Duration.ofSeconds(PENDING_ACQUIRE_TIMEOUT))
                .maxIdleTime(Duration.ofSeconds(MAX_IDLE_TIME))
                .maxLifeTime(Duration.ofSeconds(MAX_LIFE_TIME))
                .evictInBackground(Duration.ofSeconds(10)) // More frequent cleanup
                .build();
        
        HttpClient httpClient = HttpClient.create(individualProvider)
                .proxy(proxy -> proxy
                        .type(ProxyProvider.Proxy.HTTP)
                        .host(credentials.getHost())
                        .port(credentials.getPort())
                        .username(credentials.getUsername())
                        .password(u -> credentials.getPassword())
                        .connectTimeoutMillis(30000))  // 30 second connect timeout for proxy
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 30000)  // 30 second overall connect
                .responseTimeout(Duration.ofSeconds(60))  // 60 second response timeout
                .doOnConnected(conn -> conn
                        .addHandlerLast(new ReadTimeoutHandler(60, TimeUnit.SECONDS))  // 60 second read
                        .addHandlerLast(new WriteTimeoutHandler(30, TimeUnit.SECONDS)));  // 30 second write

        // Configure exchange strategies with increased buffer size
        ExchangeStrategies strategies = ExchangeStrategies.builder()
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(MAX_BUFFER_SIZE))
                .build();

        WebClient webClient = WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .exchangeStrategies(strategies)
                .build();
        
        // Wrap with management capabilities - store the connection provider for disposal
        WebClientWrapper wrapper = new WebClientWrapper(clientId, webClient, httpClient, individualProvider);
        activeClients.put(clientId, wrapper);
        
        log.debug("Created managed WebClient {} for proxy {}:{} with individual connection provider (total active: {})", 
                clientId, credentials.getHost(), credentials.getPort(), activeClients.size());
        
        return new ManagedWebClient(clientId, webClient, this);
    }

    /**
     * Dispose of a specific managed client
     */
    public void disposeClient(String clientId) {
        WebClientWrapper wrapper = activeClients.remove(clientId);
        if (wrapper != null) {
            try {
                wrapper.dispose();
                log.debug("Disposed WebClient {} (remaining active: {})", clientId, activeClients.size());
            } catch (Exception e) {
                log.warn("Error disposing WebClient {}: {}", clientId, e.getMessage());
            }
        }
    }

    /**
     * Dispose of multiple managed clients
     */
    public void disposeClients(List<ManagedWebClient> clients) {
        if (clients == null || clients.isEmpty()) {
            return;
        }
        
        log.debug("Disposing {} WebClient instances", clients.size());
        clients.forEach(ManagedWebClient::dispose);
        
        // Force garbage collection hint after disposing large batches
        if (clients.size() > 100) {
            System.gc();
            log.debug("Suggested garbage collection after disposing {} clients", clients.size());
        }
    }

    /**
     * Get current pool statistics
     */
    public PoolStats getPoolStats() {
        return new PoolStats(
                activeClients.size(),
                MAX_CONNECTIONS,
                "Individual connection providers per client"
        );
    }

    /**
     * Cleanup all resources on shutdown
     */
    @PreDestroy
    public void shutdown() {
        log.info("Shutting down WebClientPool ({} active clients)", activeClients.size());
        
        // Dispose all active clients and their individual connection providers
        activeClients.values().forEach(wrapper -> {
            try {
                wrapper.dispose();
            } catch (Exception e) {
                log.warn("Error disposing client during shutdown: {}", e.getMessage());
            }
        });
        activeClients.clear();
        
        log.info("WebClientPool shutdown complete");
    }

    /**
     * Wrapper for WebClient with disposal tracking
     */
    private static class WebClientWrapper {
        private final String id;
        private final ConnectionProvider connectionProvider;
        private volatile boolean disposed = false;

        public WebClientWrapper(String id, WebClient webClient, HttpClient httpClient, ConnectionProvider connectionProvider) {
            this.id = id;
            this.connectionProvider = connectionProvider;
        }

        public void dispose() {
            if (!disposed) {
                disposed = true;
                try {
                    log.debug("Disposing WebClient wrapper {} with individual connection provider", id);
                    
                    // Dispose individual connection provider to clean up connection pools
                    if (connectionProvider != null) {
                        connectionProvider.dispose();
                        log.debug("Disposed individual connection provider for WebClient {}", id);
                    }
                } catch (Exception e) {
                    log.warn("Error during resource cleanup for WebClient {}: {}", id, e.getMessage());
                }
            }
        }
    }

    /**
     * Managed WebClient that automatically cleans up resources
     */
    public static class ManagedWebClient {
        private final String clientId;
        private final WebClient webClient;
        private final WebClientPool pool;
        private volatile boolean disposed = false;

        public ManagedWebClient(String clientId, WebClient webClient, WebClientPool pool) {
            this.clientId = clientId;
            this.webClient = webClient;
            this.pool = pool;
        }

        public WebClient getWebClient() {
            if (disposed) {
                throw new IllegalStateException("WebClient " + clientId + " has been disposed");
            }
            return webClient;
        }

        public void dispose() {
            if (!disposed) {
                disposed = true;
                pool.disposeClient(clientId);
            }
        }

        public String getClientId() {
            return clientId;
        }
    }

    /**
     * Pool statistics for monitoring
     */
    public static class PoolStats {
        private final int activeClients;
        private final int maxConnections;
        private final String connectionProviderInfo;

        public PoolStats(int activeClients, int maxConnections, String connectionProviderInfo) {
            this.activeClients = activeClients;
            this.maxConnections = maxConnections;
            this.connectionProviderInfo = connectionProviderInfo;
        }

        public int getActiveClients() { return activeClients; }
        public int getMaxConnections() { return maxConnections; }
        public String getConnectionProviderInfo() { return connectionProviderInfo; }

        @Override
        public String toString() {
            return String.format("PoolStats{activeClients=%d, maxConnections=%d}", 
                    activeClients, maxConnections);
        }
    }
}