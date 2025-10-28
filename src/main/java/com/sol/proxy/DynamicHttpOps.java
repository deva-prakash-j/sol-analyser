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
import java.util.concurrent.atomic.AtomicReference;

@Service
@RequiredArgsConstructor
@Slf4j
public class DynamicHttpOps {

    private final DatabaseProxyProvider proxyProvider;
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
        List<WebClientPool.ManagedWebClient> managedClients = clientFactory.createManagedClients(proxySessions, proxyProvider);
        
        log.info("Processing batch of {} requests with {} fresh managed proxy sessions (full pool: {})", 
                bodies.size(), managedClients.size(), requestFullProxyPool);
        
        try {
            AtomicInteger clientIndex = new AtomicInteger(0);
            
            List<T> results = reactor.core.publisher.Flux.fromIterable(bodies)
                    .flatMap(body -> {
                        // Round-robin through available clients
                        int idx = clientIndex.getAndIncrement() % managedClients.size();
                        WebClient client = managedClients.get(idx).getWebClient();
                        
                        return executePostWithRetry(client, baseUrl, path, body, responseType, headers);
                    }, batchSize) // Concurrency = batch size
                    .collectList()
                    .block();
                    
            return results != null ? results : List.of();
            
        } finally {
            // Always dispose managed clients to prevent memory leaks
            clientFactory.getWebClientPool().disposeClients(managedClients);
            log.debug("Disposed {} managed clients after batch processing", managedClients.size());
        }
    }
    
    /**
     * Execute POST requests with dynamic proxy sessions (uses exact count, not full pool)
     */
    public <T> List<T> postJsonBatch(String baseUrl, String path, List<Object> bodies, 
                                     Class<T> responseType, Map<String, String> headers) {
        return postJsonBatch(baseUrl, path, bodies, responseType, headers, false);
    }

    /**
     * Execute GET requests with dynamic proxy sessions
     * Designed for simple API calls like Jupiter price fetching
     */
    public <T> List<T> getBatch(String baseUrl, String path, List<String> queryParams, 
                                Class<T> responseType, Map<String, String> headers) {
        
        if (queryParams.isEmpty()) {
            return List.of();
        }
        
        int batchSize = Math.min(queryParams.size(), 100); // Smaller batch for GET requests
        
        // Fetch proxy sessions
        List<String> proxySessions = proxyProvider.fetchProxySessions(batchSize);
        List<WebClientPool.ManagedWebClient> managedClients = clientFactory.createManagedClients(proxySessions, proxyProvider);
        
        log.info("Processing GET batch of {} requests with {} managed proxy sessions", 
                queryParams.size(), managedClients.size());
        
        try {
            AtomicInteger clientIndex = new AtomicInteger(0);
            
            List<T> results = reactor.core.publisher.Flux.fromIterable(queryParams)
                    .flatMap(query -> {
                        // Round-robin through available clients
                        int idx = clientIndex.getAndIncrement() % managedClients.size();
                        WebClient client = managedClients.get(idx).getWebClient();
                        
                        return executeGetWithRetry(client, baseUrl, path, query, responseType, headers);
                    }, batchSize) // Concurrency = batch size
                    .collectList()
                    .block();
                    
            return results != null ? results : List.of();
            
        } finally {
            // Always dispose managed clients to prevent memory leaks
            clientFactory.getWebClientPool().disposeClients(managedClients);
            log.debug("Disposed {} managed clients after GET batch processing", managedClients.size());
        }
    }

    /**
     * Execute single GET request with retry and session rotation for 429 errors
     */
    private <T> Mono<T> executeGetWithRetry(WebClient client, String baseUrl, String path, 
                                            String queryParams, Class<T> responseType, Map<String, String> headers) {
        
        AtomicReference<WebClient> currentClient = new AtomicReference<>(client);
        AtomicReference<WebClientPool.ManagedWebClient> rotatedClient = new AtomicReference<>(null);
        
        Retry retry = Retry
                .backoff(MAX_RETRIES, Duration.ofSeconds(1))
                .maxBackoff(Duration.ofSeconds(10))
                .jitter(0.5)
                .filter(this::isRetryable)
                .doBeforeRetry(signal -> {
                    Throwable failure = signal.failure();
                    log.warn("Retrying GET request (attempt {}/{}): {}", 
                            signal.totalRetries() + 1, MAX_RETRIES, 
                            failure.getMessage());
                    
                    // For 429 errors, rotate to a new session
                    if (failure instanceof WebClientResponseException) {
                        WebClientResponseException wcre = (WebClientResponseException) failure;
                        if (wcre.getStatusCode().value() == 429) {
                            log.info("429 rate limit detected on GET request, rotating to new proxy session");
                            try {
                                // Dispose previous rotated client if exists
                                WebClientPool.ManagedWebClient previousRotated = rotatedClient.get();
                                if (previousRotated != null) {
                                    previousRotated.dispose();
                                    log.debug("Disposed previous rotated GET client: {}", previousRotated.getClientId());
                                }
                                
                                // Fetch a single new session for rotation
                                List<String> newSessions = proxyProvider.fetchProxySessions(1);
                                if (!newSessions.isEmpty()) {
                                    List<WebClientPool.ManagedWebClient> newManagedClients = clientFactory.createManagedClients(newSessions, proxyProvider);
                                    if (!newManagedClients.isEmpty()) {
                                        WebClientPool.ManagedWebClient newManagedClient = newManagedClients.get(0);
                                        WebClient newClient = newManagedClient.getWebClient();
                                        currentClient.set(newClient);
                                        rotatedClient.set(newManagedClient); // Track for disposal
                                        log.info("Successfully rotated to new proxy session for GET: {} (client: {})", 
                                                newSessions.get(0).substring(0, Math.min(50, newSessions.get(0).length())),
                                                newManagedClient.getClientId());
                                    }
                                }
                            } catch (Exception e) {
                                log.warn("Failed to rotate proxy session for GET request: {}", e.getMessage());
                            }
                        }
                    }
                });

        return Mono.defer(() -> {
            // Get the current client each time defer is executed (important for rotation)
            WebClient clientToUse = currentClient.get();
            
            return Mono.delay(Duration.ofMillis(50))
                    .then(clientToUse.get()
                            .uri(baseUrl + path + queryParams)
                            .headers(h -> headers.forEach(h::add))
                            .retrieve()
                            .bodyToMono(responseType))
                    .doOnError(error -> log.debug("GET request failed for {}{}{}: {}", 
                            baseUrl, path, queryParams, error.getMessage()))
                    .onErrorResume(error -> {
                        if (error instanceof WebClientResponseException) {
                            WebClientResponseException wcre = (WebClientResponseException) error;
                            if (wcre.getStatusCode().value() == 404) {
                                // Return empty for 404 errors
                                log.debug("Returning empty response for 404 error: {}", error.getMessage());
                                return Mono.empty();
                            }
                        }
                        return Mono.error(error); // Propagate error for retry
                    });
        })
        .retryWhen(retry)
        .doFinally(signalType -> {
            // Clean up any rotated client when the request completes (success or failure)
            WebClientPool.ManagedWebClient finalRotated = rotatedClient.get();
            if (finalRotated != null) {
                finalRotated.dispose();
                log.debug("Disposed rotated GET client on request completion: {}", finalRotated.getClientId());
            }
        })
        .onErrorResume(error -> {
            // After all retries exhausted, log and return empty instead of throwing
            log.warn("GET request failed after {} retries, returning empty result: {}", 
                    MAX_RETRIES, error.getMessage());
            return Mono.empty();
        });
    }

    /**
     * Execute single POST request with retry and session rotation for 429 errors
     */
    private <T> Mono<T> executePostWithRetry(WebClient client, String baseUrl, String path, 
                                             Object body, Class<T> responseType, Map<String, String> headers) {
        
        AtomicReference<WebClient> currentClient = new AtomicReference<>(client);
        AtomicReference<WebClientPool.ManagedWebClient> rotatedClient = new AtomicReference<>(null);
        
        Retry retry = Retry
                .backoff(MAX_RETRIES, Duration.ofSeconds(1))
                .maxBackoff(Duration.ofSeconds(10))
                .jitter(0.5)
                .filter(this::isRetryable)
                .doBeforeRetry(signal -> {
                    Throwable failure = signal.failure();
                    log.warn("Retrying request (attempt {}/{}): {}", 
                            signal.totalRetries() + 1, MAX_RETRIES, 
                            failure.getMessage());
                    
                    // For 429 errors, rotate to a new session
                    if (failure instanceof WebClientResponseException) {
                        WebClientResponseException wcre = (WebClientResponseException) failure;
                        if (wcre.getStatusCode().value() == 429) {
                            log.info("429 rate limit detected, rotating to new proxy session");
                            try {
                                // Dispose previous rotated client if exists
                                WebClientPool.ManagedWebClient previousRotated = rotatedClient.get();
                                if (previousRotated != null) {
                                    previousRotated.dispose();
                                    log.debug("Disposed previous rotated client: {}", previousRotated.getClientId());
                                }
                                
                                // Fetch a single new session for rotation
                                List<String> newSessions = proxyProvider.fetchProxySessions(1);
                                if (!newSessions.isEmpty()) {
                                    List<WebClientPool.ManagedWebClient> newManagedClients = clientFactory.createManagedClients(newSessions, proxyProvider);
                                    if (!newManagedClients.isEmpty()) {
                                        WebClientPool.ManagedWebClient newManagedClient = newManagedClients.get(0);
                                        WebClient newClient = newManagedClient.getWebClient();
                                        currentClient.set(newClient);
                                        rotatedClient.set(newManagedClient); // Track for disposal
                                        log.info("Successfully rotated to new proxy session: {} (client: {})", 
                                                newSessions.get(0).substring(0, Math.min(50, newSessions.get(0).length())),
                                                newManagedClient.getClientId());
                                    }
                                }
                            } catch (Exception e) {
                                log.warn("Failed to rotate proxy session: {}", e.getMessage());
                            }
                        }
                    }
                });

        return Mono.defer(() -> {
            // Get the current client each time defer is executed (important for rotation)
            WebClient clientToUse = currentClient.get();
            
            return Mono.delay(Duration.ofMillis(50))
                    .then(clientToUse.post()
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
                    });
        })
        .retryWhen(retry)
        .doFinally(signalType -> {
            // Clean up any rotated client when the request completes (success or failure)
            WebClientPool.ManagedWebClient finalRotated = rotatedClient.get();
            if (finalRotated != null) {
                finalRotated.dispose();
                log.debug("Disposed rotated client on request completion: {}", finalRotated.getClientId());
            }
        })
        .onErrorResume(error -> {
            // After all retries exhausted, log and return empty instead of throwing
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
     * Execute large batch POST requests with database session management
     * Updated logic: For batches >1000, fetch 1000 sessions per batch with 10sec wait time
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

        // Reset batch tracking for new operation
        proxyProvider.resetBatchTracking();
        
        // Determine sessions needed per batch
        int sessionsPerBatch;
        if (bodies.size() <= 1000) {
            // Small batch: use exact count
            sessionsPerBatch = bodies.size();
        } else {
            // Large batch: use 1000 sessions per batch
            sessionsPerBatch = 1000;
        }

        int actualBatchSize = Math.min(batchSize, sessionsPerBatch);
        int totalBatches = (int) Math.ceil((double) bodies.size() / actualBatchSize);
        
        // Use 10 second delay for large batches as requested
        int actualDelaySeconds = bodies.size() > 1000 ? 10 : batchDelaySeconds;
        
        log.info("Processing {} requests in {} batches of {} with {} sessions per batch ({}s delay between batches)", 
                bodies.size(), totalBatches, actualBatchSize, sessionsPerBatch, actualDelaySeconds);
        
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
                
                // Fetch sessions for this batch
                List<String> proxySessions = proxyProvider.fetchNextBatchSessions(sessionsPerBatch);
                List<WebClientPool.ManagedWebClient> managedClients = clientFactory.createManagedClients(proxySessions, proxyProvider);
                
                log.info("Batch {}/{}: Using {} managed proxy sessions", batchIdx + 1, totalBatches, managedClients.size());
                
                List<T> batchResults = null;
                try {
                    // Process batch with round-robin client selection
                    AtomicInteger clientIndex = new AtomicInteger(0);
                    batchResults = reactor.core.publisher.Flux.fromIterable(batchBodies)
                            .flatMap(body -> {
                                int idx = clientIndex.getAndIncrement() % managedClients.size();
                                WebClient client = managedClients.get(idx).getWebClient();
                                
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
                } finally {
                    // Always dispose managed clients from this batch to free resources
                    clientFactory.getWebClientPool().disposeClients(managedClients);
                    log.debug("Disposed {} managed WebClient instances from batch {}", managedClients.size(), batchIdx + 1);
                }
                
                if (batchResults != null) {
                    allResults.addAll(batchResults);
                }
                
                log.info("Batch {}/{} complete: {} results ({}/{} total)", 
                        batchIdx + 1, totalBatches, batchResults != null ? batchResults.size() : 0,
                        allResults.size(), bodies.size());
                
                // Wait between batches (except for last batch)
                if (batchIdx < totalBatches - 1) {
                    log.info("Waiting {}s before next batch...", actualDelaySeconds);
                    Thread.sleep(actualDelaySeconds * 1000L);
                }
            }
            
            log.info("✓ All batches complete: {}/{} successful responses", allResults.size(), bodies.size());
            return allResults;
            
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Batch processing interrupted", e);
            throw new RuntimeException("Batch processing interrupted", e);
        }
    }
}
