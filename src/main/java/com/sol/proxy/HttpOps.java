package com.sol.proxy;


import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.Exceptions;
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

    public HttpOps(ProxyPool pool) {
        this.pool = pool;
        // 3 attempts, exponential with jitter; only retry on transient statuses
        this.retry = Retry
                .backoff(3, Duration.ofMillis(3000))
                .maxBackoff(Duration.ofSeconds(5))
                .jitter(0.5)
                .filter(this::isRetryable);
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
        WebClient client = pool.next(baseUrl);
        return client.get()
                .uri(baseUrl + path)                 // absolute URI; no baseUrl on builder needed
                .headers(h -> headers.forEach(h::add))
                .retrieve()
                .bodyToMono(String.class)
                .retryWhen(retry);
    }

    public <T> Mono<T> postJsonOnce(String baseUrl, String path, Object body, Class<T> type, Map<String, String> headers) {
        WebClient client = pool.next(baseUrl);
        return client.post()
                .uri(baseUrl + path)
                .headers(h -> headers.forEach(h::add))
                .bodyValue(body)
                .retrieve()
                .bodyToMono(type)
                .retryWhen(retry);
    }

    public <T> Mono<T> postJsonOnce(WebClient client, String baseUrl, String path, Object body, Class<T> type, Map<String, String> headers) {
        return client.post()
                .uri(baseUrl + path)
                .headers(h -> headers.forEach(h::add))
                .bodyValue(body)
                .retrieve()
                .bodyToMono(type)
                .doOnError(err -> log.warn("error: {}", err))
                .retryWhen(retry);
    }
}
