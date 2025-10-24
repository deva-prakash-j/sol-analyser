package com.sol.proxy;

import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.function.BiFunction;

@Service
public class BatchProcessor {

    private final ProxyPool pool;

    public BatchProcessor(ProxyPool pool) {
        this.pool = pool;
    }

    /**
     * @param baseUrl       e.g. "https://api.example.com"
     * @param inputs        list of inputs to process
     * @param laneSize      e.g. 40 (how many in-flight per batch)
     * @param op            function(WebClient, I) -> Mono<O> (your actual call)
     */
    public <I, O> Flux<O> processInLanes(
            String baseUrl,
            List<I> inputs,
            int laneSize,
            BiFunction<WebClient, I, Mono<O>> op
    ) {
        // Cut inputs into batches of size laneSize; process batches sequentially
        return Flux.fromIterable(inputs)
                .buffer(laneSize)
                .concatMap(batch -> {
                    List<WebClient> clients = pool.slice(baseUrl, batch.size()); // distinct proxies for this batch
                    return Flux.range(0, batch.size())
                            .flatMap(i -> op.apply(clients.get(i), batch.get(i)), /*concurrency*/ batch.size());
                });
    }
}

