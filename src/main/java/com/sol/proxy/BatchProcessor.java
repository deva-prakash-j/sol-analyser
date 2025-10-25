package com.sol.proxy;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.function.BiFunction;

@Service
@Slf4j
public class BatchProcessor {

    private final ProxyPool pool;
    private final ProxyHealthTracker healthTracker;
    
    // Adaptive lane sizing to respect Solana RPC rate limits
    private static final int MIN_LANES = 10;
    private static final int MAX_LANES = 100;
    private static final int BATCH_SIZE_DIVISOR = 5;

    public BatchProcessor(ProxyPool pool, ProxyHealthTracker healthTracker) {
        this.pool = pool;
        this.healthTracker = healthTracker;
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
                    // Use health-aware proxy selection for better reliability
                    List<WebClient> clients = pool.sliceHealthAware(baseUrl, batch.size(), healthTracker);
                    return Flux.range(0, batch.size())
                            .flatMap(i -> op.apply(clients.get(i), batch.get(i)), /*concurrency*/ batch.size());
                });
    }
    
    /**
     * Adaptive lane sizing based on batch size and Solana RPC constraints
     * Smaller batches = fewer lanes to avoid overwhelming rate limits
     * Larger batches = more lanes but capped at MAX_LANES
     */
    public <I, O> Flux<O> processInAdaptiveLanes(
            String baseUrl,
            List<I> inputs,
            BiFunction<WebClient, I, Mono<O>> op
    ) {
        int batchSize = inputs.size();
        // Adaptive: min 10, max 100, or batchSize/5
        int lanes = Math.max(MIN_LANES, Math.min(MAX_LANES, batchSize / BATCH_SIZE_DIVISOR));
        
        log.info("Processing {} items with {} adaptive lanes (health-aware proxy selection)", 
                batchSize, lanes);
        
        long startTime = System.currentTimeMillis();
        
        return Flux.fromIterable(inputs)
                .buffer(lanes)
                .concatMap(batch -> {
                    // Use health-aware proxy selection
                    List<WebClient> clients = pool.sliceHealthAware(baseUrl, batch.size(), healthTracker);
                    return Flux.range(0, batch.size())
                            .flatMap(i -> op.apply(clients.get(i), batch.get(i)), /*concurrency*/ batch.size());
                })
                .doOnComplete(() -> {
                    long elapsed = System.currentTimeMillis() - startTime;
                    double throughput = batchSize * 1000.0 / elapsed;
                    log.info("Batch complete: {} items in {}ms ({:.1f} req/s) - Health: {}", 
                            batchSize, elapsed, throughput, 
                            healthTracker.getSummary(pool.size()));
                });
    }
}

