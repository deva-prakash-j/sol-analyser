package com.sol.proxy;


import com.sol.exception.SolanaRpcException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.net.ConnectException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeoutException;

@Service
@Slf4j
public class HttpOps {

    private final ProxyPool pool;
    private final ProxyHealthTracker healthTracker;
    private final Retry retry;
    private static final int MAX_RETRIES = 3;

    public HttpOps(ProxyPool pool, ProxyHealthTracker healthTracker) {
        this.pool = pool;
        this.healthTracker = healthTracker;
        // Backoff optimized for proxy rotation: each retry uses a different proxy
        this.retry = Retry
                .backoff(MAX_RETRIES, Duration.ofSeconds(1))
                .maxBackoff(Duration.ofSeconds(10))
                .jitter(0.5)
                .filter(this::isRetryable)
                .doBeforeRetry(signal -> {
                    log.warn("Retrying request with new proxy (attempt {}/{}): {}", 
                            signal.totalRetries() + 1, MAX_RETRIES, 
                            signal.failure().getMessage());
                })
                .onRetryExhaustedThrow((retryBackoffSpec, retrySignal) -> {
                    throw new SolanaRpcException(
                            "Request failed after " + MAX_RETRIES + " attempts", 
                            retrySignal.failure()
                    );
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

    public Mono<String> getOnce(String baseUrl, String path, Map<String, String> headers) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return Mono.error(new IllegalArgumentException("Base URL cannot be null or empty"));
        }
        
        WebClient client = pool.next(baseUrl);
        int proxyIndex = pool.getLastProxyIndex(baseUrl);
        long startTime = System.currentTimeMillis();
        
        return client.get()
                .uri(baseUrl + path)                 // absolute URI; no baseUrl on builder needed
                .headers(h -> headers.forEach(h::add))
                .retrieve()
                .bodyToMono(String.class)
                .doOnSuccess(response -> {
                    long responseTime = System.currentTimeMillis() - startTime;
                    healthTracker.recordSuccess(proxyIndex, responseTime);
                })
                .doOnError(error -> {
                    healthTracker.recordFailure(proxyIndex);
                    log.error("GET request failed for {}{}: {}", baseUrl, path, error.getMessage());
                })
                .onErrorMap(error -> {
                    if (!(error instanceof SolanaRpcException)) {
                        return new SolanaRpcException("GET request failed", error);
                    }
                    return error;
                })
                .retryWhen(retry);
    }

    public <T> Mono<T> postJsonOnce(String baseUrl, String path, Object body, Class<T> type, Map<String, String> headers) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return Mono.error(new IllegalArgumentException("Base URL cannot be null or empty"));
        }
        if (body == null) {
            return Mono.error(new IllegalArgumentException("Request body cannot be null"));
        }
        if (type == null) {
            return Mono.error(new IllegalArgumentException("Response type cannot be null"));
        }
        
        return Mono.defer(() -> {
            WebClient client = pool.next(baseUrl);
            int proxyIndex = pool.getLastProxyIndex(baseUrl);
            long startTime = System.currentTimeMillis();
            
            // Add delay BEFORE making the request
            return Mono.delay(Duration.ofMillis(200))
                    .then(client.post()
                            .uri(baseUrl + path)
                            .headers(h -> headers.forEach(h::add))
                            .bodyValue(body)
                            .retrieve()
                            .bodyToMono(type))
                    .doOnSuccess(response -> {
                        long responseTime = System.currentTimeMillis() - startTime;
                        healthTracker.recordSuccess(proxyIndex, responseTime);
                    })
                    .doOnError(error -> {
                        healthTracker.recordFailure(proxyIndex);
                        log.error("POST request failed for {}{}: {}", baseUrl, path, error.getMessage());
                    })
                    .onErrorMap(error -> {
                        if (!(error instanceof SolanaRpcException)) {
                            return new SolanaRpcException("POST request failed", error);
                        }
                        return error;
                    });
        })
        .retryWhen(retry);
    }

    public <T> Mono<T> postJsonOnce(WebClient client, String baseUrl, String path, Object body, Class<T> type, Map<String, String> headers) {
        if (client == null) {
            return Mono.error(new IllegalArgumentException("WebClient cannot be null"));
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            return Mono.error(new IllegalArgumentException("Base URL cannot be null or empty"));
        }
        if (body == null) {
            return Mono.error(new IllegalArgumentException("Request body cannot be null"));
        }
        if (type == null) {
            return Mono.error(new IllegalArgumentException("Response type cannot be null"));
        }
        
        return client.post()
                .uri(baseUrl + path)
                .headers(h -> headers.forEach(h::add))
                .bodyValue(body)
                .retrieve()
                .bodyToMono(type)
                .doOnError(error -> log.error("POST request failed for {}{}: {}", 
                        baseUrl, path, error.getMessage()))
                .onErrorMap(error -> {
                    if (!(error instanceof SolanaRpcException)) {
                        return new SolanaRpcException("POST request failed", error);
                    }
                    return error;
                })
                .retryWhen(retry);
    }
}
