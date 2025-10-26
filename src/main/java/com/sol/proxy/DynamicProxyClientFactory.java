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
import reactor.netty.transport.ProxyProvider;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Component
@Slf4j
public class DynamicProxyClientFactory {

    // 10MB buffer limit for large Solana RPC responses
    private static final int MAX_BUFFER_SIZE = 10 * 1024 * 1024; // 10MB

    /**
     * Create WebClient with specific proxy credentials
     * Uses generous timeouts to handle slow proxy connections to Solana RPC
     * Configures 10MB buffer limit for large responses
     */
    public WebClient createClient(ProxyCredentials credentials) {
        HttpClient httpClient = HttpClient.create()
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

        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .exchangeStrategies(strategies)
                .build();
    }

    /**
     * Create multiple WebClients for a list of proxy strings
     */
    public List<WebClient> createClients(List<String> proxyStrings, OculusProxyProvider proxyProvider) {
        return proxyStrings.stream()
                .map(proxyProvider::parseProxyString)
                .map(this::createClient)
                .collect(Collectors.toList());
    }
}
