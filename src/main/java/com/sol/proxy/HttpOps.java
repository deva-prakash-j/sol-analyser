package com.sol.proxy;


import com.sol.exception.SolanaRpcException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
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
    private final Retry retry;
    private static final int MAX_RETRIES = 3;

    public HttpOps(ProxyPool pool) {
        this.pool = pool;
        // 3 attempts, exponential with jitter; only retry on transient statuses
        this.retry = Retry
                .backoff(MAX_RETRIES, Duration.ofMillis(3000))
                .maxBackoff(Duration.ofSeconds(5))
                .jitter(0.5)
                .filter(this::isRetryable)
                .doBeforeRetry(signal -> {
                    log.warn("Retrying request (attempt {}/{}): {}", 
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
        return ((t instanceof TimeoutException)
                || (t instanceof ConnectException)
                || (t instanceof reactor.netty.http.client.PrematureCloseException)
                || (t instanceof io.netty.handler.proxy.ProxyConnectException)
                || (t instanceof io.netty.handler.ssl.SslHandshakeTimeoutException)
                || (t.getClass().getName().contains("ReadTimeoutException"))
                || (t.getClass().getName().contains("IOException"))
                || (t.getClass().getName().contains("WebClientRequestException"))
                || (t.getClass().getName().contains("WebClientResponseException")
                && !(t.getMessage() != null && t.getMessage().contains("4xx"))));
    }

    public Mono<String> getOnce(String baseUrl, String path, Map<String, String> headers) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return Mono.error(new IllegalArgumentException("Base URL cannot be null or empty"));
        }
        
        WebClient client = pool.next(baseUrl);
        return client.get()
                .uri(baseUrl + path)                 // absolute URI; no baseUrl on builder needed
                .headers(h -> headers.forEach(h::add))
                .retrieve()
                .bodyToMono(String.class)
                .doOnError(error -> log.error("GET request failed for {}{}: {}", 
                        baseUrl, path, error.getMessage()))
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
        
        WebClient client = pool.next(baseUrl);
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
