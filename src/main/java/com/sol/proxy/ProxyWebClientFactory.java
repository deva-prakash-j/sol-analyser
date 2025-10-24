package com.sol.proxy;

import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import reactor.netty.http.client.HttpClient;
import reactor.netty.transport.ProxyProvider;
import reactor.netty.resources.ConnectionProvider;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;

import com.sol.bean.ProxyIdentity;
import com.sol.bean.ProxyPoolProperties;
import java.time.Duration;

public class ProxyWebClientFactory {

    public static WebClient build(ProxyIdentity id, ProxyPoolProperties props) {
        // Dedicated pool per proxy to prevent cross-proxy reuse
        ConnectionProvider pool = ConnectionProvider.builder("pool-" + id.username())
                .maxConnections(props.getPerProxyMaxConnections())
                .pendingAcquireMaxCount(1000)
                .pendingAcquireTimeout(Duration.ofSeconds(30))
                .maxIdleTime(Duration.ofMinutes(100))
                .maxLifeTime(Duration.ofHours(7))
                .evictInBackground(Duration.ofMinutes(5))
                .lifo()
                .metrics(props.isEnableMetrics())
                .build();

        HttpClient http = HttpClient.create(pool)
                .compress(true)
                .followRedirect(true)
                .keepAlive(true)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, props.getConnectTimeoutMs())
                .responseTimeout(Duration.ofMillis(props.getResponseTimeoutMs()))
                .doOnConnected(conn -> conn
                        .addHandlerLast(new ReadTimeoutHandler(props.getReadTimeoutSec()))
                        .addHandlerLast(new WriteTimeoutHandler(props.getWriteTimeoutSec())))
                .metrics(props.isEnableMetrics(), s -> {
                    // tag connections by proxy user for observability
                    return s.replace('.', '_') + "_proxy_" + id.username();
                })
                .proxy(spec -> spec
                        .type(ProxyProvider.Proxy.HTTP)
                        .host(id.host())
                        .port(id.port())
                        .username(id.username())
                        .password(p -> id.password()));

        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(http))
                .exchangeStrategies(ExchangeStrategies.builder()
                        .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(1024 * 1024 * 10)) // 10MB
                        .build())
                .build();
    }
}
