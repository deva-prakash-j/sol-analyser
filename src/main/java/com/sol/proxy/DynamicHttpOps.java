package com.sol.proxy;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.net.ConnectException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

@Service
@RequiredArgsConstructor
@Slf4j
public class DynamicHttpOps {

    private final OculusProxyProvider proxyProvider;
    private final DynamicProxyClientFactory clientFactory;
    private static final int MAX_RETRIES = 3;

    /**
     * Execute POST requests with dynamic proxy sessions
     * Fetches fresh proxies for each batch of requests
     * @param requestFullProxyPool If true, fetches 1000 proxies for maximum performance (use for expensive calls like getTransaction)
     */
    public <T> List<T> postJsonBatch(String baseUrl, String path, List<Object> bodies, 
                                     Class<T> responseType, Map<String, String> headers, boolean requestFullProxyPool) {
        
        int batchSize = Math.min(bodies.size(), 1000); // Max 1000 per batch
        
        // Determine how many proxies to request based on the flag
        int proxyCount = requestFullProxyPool ? 1000 : batchSize;
        
        // Fetch fresh proxy sessions
        List<String> proxySessions = proxyProvider.fetchProxySessions(proxyCount);
        List<WebClient> clients = clientFactory.createClients(proxySessions, proxyProvider);
        
        log.info("Processing batch of {} requests with {} fresh proxy sessions (full pool: {})", 
                bodies.size(), clients.size(), requestFullProxyPool);
        
        AtomicInteger clientIndex = new AtomicInteger(0);
        
        return reactor.core.publisher.Flux.fromIterable(bodies)
                .flatMap(body -> {
                    // Round-robin through available clients
                    int idx = clientIndex.getAndIncrement() % clients.size();
                    WebClient client = clients.get(idx);
                    
                    return executePostWithRetry(client, baseUrl, path, body, responseType, headers);
                }, batchSize) // Concurrency = batch size
                .collectList()
                .block();
    }
    
    /**
     * Execute POST requests with dynamic proxy sessions (uses exact count, not full pool)
     */
    public <T> List<T> postJsonBatch(String baseUrl, String path, List<Object> bodies, 
                                     Class<T> responseType, Map<String, String> headers) {
        return postJsonBatch(baseUrl, path, bodies, responseType, headers, false);
    }

    /**
     * Execute single POST request with retry and fresh proxy on each retry
     */
    private <T> Mono<T> executePostWithRetry(WebClient client, String baseUrl, String path, 
                                             Object body, Class<T> responseType, Map<String, String> headers) {
        
        Retry retry = Retry
                .backoff(MAX_RETRIES, Duration.ofSeconds(1))
                .maxBackoff(Duration.ofSeconds(10))
                .jitter(0.5)
                .filter(this::isRetryable)
                .doBeforeRetry(signal -> {
                    log.warn("Retrying request (attempt {}/{}): {}", 
                            signal.totalRetries() + 1, MAX_RETRIES, 
                            signal.failure().getMessage());
                });

        return Mono.defer(() -> 
                // Reduced delay - proxies are now pre-verified with unique IPs
                Mono.delay(Duration.ofMillis(50))
                        .then(client.post()
                                .uri(baseUrl + path)
                                .headers(h -> headers.forEach(h::add))
                                .bodyValue(body)
                                .retrieve()
                                .bodyToMono(responseType))
                        .doOnError(error -> log.debug("POST request failed for {}{}: {}", 
                                baseUrl, path, error.getMessage()))
                        .onErrorResume(error -> {
                            if (error instanceof WebClientResponseException) {
                                WebClientResponseException wcre = (WebClientResponseException) error;
                                if (wcre.getStatusCode().value() == 404 || 
                                    wcre.getStatusCode().value() == 500) {
                                    // Return empty for certain errors (transaction not found, etc.)
                                    log.debug("Returning empty response for error: {}", error.getMessage());
                                    return Mono.empty();
                                }
                            }
                            return Mono.error(error); // Propagate error for retry
                        })
        )
        .retryWhen(retry)
        .onErrorResume(error -> {
            // After all retries exhausted, log and return empty instead of throwing
            // This prevents "onErrorDropped" exceptions in the reactive chain
            log.warn("Request failed after {} retries, returning empty result: {}", 
                    MAX_RETRIES, error.getMessage());
            return Mono.empty();
        });
    }

    private boolean isRetryable(Throwable t) {
        // Retry on network errors and timeouts
        if (t instanceof TimeoutException
                || t instanceof ConnectException
                || t instanceof reactor.netty.http.client.PrematureCloseException
                || t instanceof io.netty.handler.proxy.ProxyConnectException
                || t instanceof io.netty.handler.ssl.SslHandshakeTimeoutException
                || t.getClass().getName().contains("ReadTimeoutException")
                || t.getClass().getName().contains("IOException")
                || t.getClass().getName().contains("WebClientRequestException")) {
            return true;
        }
        
        // Retry on specific HTTP error codes: 429 (rate limit), 500, 502, 503, 504
        if (t instanceof WebClientResponseException) {
            WebClientResponseException wcre = (WebClientResponseException) t;
            int status = wcre.getStatusCode().value();
            return status == 429 || status == 500 || status == 502 || status == 503 || status == 504;
        }
        
        return false;
    }

    /**
     * Execute large batch POST requests with region-based session pooling and reuse
     * Optimized for very large operations (20K+ requests) - fetches sessions once, reuses across batches
     * 
     * @param baseUrl Base URL for requests
     * @param path Path to append to base URL
     * @param bodies List of request bodies
     * @param responseType Response class type
     * @param headers Additional headers
     * @param batchSize Number of concurrent requests per batch
     * @param batchDelaySeconds Delay between batches in seconds
     * @return List of responses
     */
    public <T> List<T> postJsonBatchWithSessionReuse(String baseUrl, String path, List<Object> bodies, 
                                                      Class<T> responseType, Map<String, String> headers,
                                                      int batchSize, int batchDelaySeconds) {
        
        if (bodies.isEmpty()) {
            return List.of();
        }

        // Calculate target sessions based on request count
        // For small batches (<100): use exact count with 20% buffer
        // For medium batches (100-1000): use the count
        // For large batches (>1000): cap at 1000 for maximum IP diversity
        int targetSessions;
        if (bodies.size() < 100) {
            targetSessions = Math.min(100, (int)(bodies.size() * 1.2)); // 20% buffer, max 100
        } else if (bodies.size() <= 1000) {
            targetSessions = bodies.size(); // Use exact count
        } else {
            targetSessions = 1000; // Cap at 1000 for very large batches
        }
        
        log.info("Fetching {} unique proxy sessions by region for {} requests (ratio: 1:{})", 
                targetSessions, bodies.size(), bodies.size() / (double)targetSessions);
        
        List<String> proxySessions = proxyProvider.fetchSessionsByRegion(targetSessions);
        List<WebClient> clients = clientFactory.createClients(proxySessions, proxyProvider);
        
        int actualBatchSize = Math.min(batchSize, clients.size());
        int totalBatches = (int) Math.ceil((double) bodies.size() / actualBatchSize);
        
        log.info("Processing {} requests in {} batches of {} with {} proxy sessions ({}s delay between batches)", 
                bodies.size(), totalBatches, actualBatchSize, clients.size(), batchDelaySeconds);
        
        List<T> allResults = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger processedCount = new AtomicInteger(0);
        
        try {
            // Process in batches
            for (int batchIdx = 0; batchIdx < totalBatches; batchIdx++) {
                int startIdx = batchIdx * actualBatchSize;
                int endIdx = Math.min(startIdx + actualBatchSize, bodies.size());
                List<Object> batchBodies = bodies.subList(startIdx, endIdx);
                
                log.info("Batch {}/{}: Processing requests {} to {} ({} requests)", 
                        batchIdx + 1, totalBatches, startIdx + 1, endIdx, batchBodies.size());
                
                // Process batch with round-robin client selection
                AtomicInteger clientIndex = new AtomicInteger(0);
                List<T> batchResults = reactor.core.publisher.Flux.fromIterable(batchBodies)
                        .flatMap(body -> {
                            int idx = clientIndex.getAndIncrement() % clients.size();
                            WebClient client = clients.get(idx);
                            
                            return executePostWithRetry(client, baseUrl, path, body, responseType, headers)
                                    .doOnSuccess(result -> {
                                        int completed = processedCount.incrementAndGet();
                                        if (completed % 100 == 0) {
                                            log.debug("Progress: {}/{} requests completed", completed, bodies.size());
                                        }
                                    });
                        }, actualBatchSize) // Concurrency = batch size
                        .collectList()
                        .block();
                
                if (batchResults != null) {
                    allResults.addAll(batchResults);
                }
                
                log.info("Batch {}/{} complete: {} results ({}/{} total)", 
                        batchIdx + 1, totalBatches, batchResults != null ? batchResults.size() : 0,
                        allResults.size(), bodies.size());
                
                // Wait between batches (except for last batch)
                if (batchIdx < totalBatches - 1) {
                    log.info("Waiting {}s before next batch...", batchDelaySeconds);
                    Thread.sleep(batchDelaySeconds * 1000L);
                }
            }
            
            log.info("✓ All batches complete: {}/{} successful responses", allResults.size(), bodies.size());
            return allResults;
            
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Batch processing interrupted", e);
            throw new RuntimeException("Batch processing interrupted", e);
        } finally {
            // Important: Dispose all WebClients to free resources
            log.info("Disposing {} WebClient instances", clients.size());
            clients.forEach(client -> {
                try {
                    // WebClient doesn't have explicit close, but we can help GC by clearing the reference
                    // The underlying connection pool will be cleaned up by reactor-netty
                } catch (Exception e) {
                    log.warn("Error during client cleanup: {}", e.getMessage());
                }
            });
        }
    }
}
