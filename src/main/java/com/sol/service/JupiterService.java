package com.sol.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sol.proxy.DynamicHttpOps;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Service for fetching current token prices from Jupiter API
 * Used to calculate unrealized PnL for open positions
 * Now uses database-stored proxy sessions for better rate limit handling
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class JupiterService {
    
    private final DynamicHttpOps dynamicHttpOps;
    private final ObjectMapper objectMapper;
    
    private static final String JUPITER_PRICE_API = "https://lite-api.jup.ag/price/v3";
    private static final int MAX_TOKENS_PER_REQUEST = 50;
    
    /**
     * Price data from Jupiter API
     */
    public record TokenPrice(
        String mint,
        BigDecimal usdPrice,
        Long blockId,
        Integer decimals,
        Double priceChange24h,
        boolean priceFound
    ) {}
    
    /**
     * Fetch current prices for multiple tokens (batched in groups of 50)
     * Returns map of mint -> price (null if price not found)
     */
    public Map<String, TokenPrice> getCurrentPrices(List<String> mints) {
        if (mints == null || mints.isEmpty()) {
            return Collections.emptyMap();
        }
        
        // Remove duplicates
        List<String> uniqueMints = mints.stream().distinct().collect(Collectors.toList());
        
        // Batch into groups of 50
        List<List<String>> batches = partition(uniqueMints, MAX_TOKENS_PER_REQUEST);
        
        // Fetch all batches in parallel
        Map<String, TokenPrice> allPrices = Flux.fromIterable(batches)
                .flatMap(this::fetchBatchPrices)
                .flatMapIterable(list -> list)  // Flatten List<List<TokenPrice>> to Flux<TokenPrice>
                .collectMap(TokenPrice::mint)
                .block();
        
        if (allPrices == null) {
            log.error("Failed to fetch prices from Jupiter API");
            return Collections.emptyMap();
        }
        
        // Add missing tokens with $0 price
        for (String mint : uniqueMints) {
            if (!allPrices.containsKey(mint) || !allPrices.get(mint).priceFound()) {
                allPrices.put(mint, new TokenPrice(mint, BigDecimal.ZERO, null, null, null, false));
            }
        }
        
        return allPrices;
    }
    
    /**
     * Fetch prices for a single batch (up to 50 tokens)
     */
    private Mono<List<TokenPrice>> fetchBatchPrices(List<String> mints) {
        if (mints.isEmpty()) {
            return Mono.just(Collections.emptyList());
        }
        
        // Build query string: ids=mint1,mint2,mint3
        String idsParam = String.join(",", mints);
        String queryParams = "?ids=" + idsParam;
        
        return Mono.fromCallable(() -> {
                    try {
                        // Use DynamicHttpOps with proxy support for GET requests
                        List<String> responses = dynamicHttpOps.getBatch(
                                JUPITER_PRICE_API, "", 
                                List.of(queryParams), 
                                String.class, 
                                Map.of());
                        
                        if (responses != null && !responses.isEmpty() && responses.get(0) != null) {
                            return parsePriceResponse(responses.get(0), mints);
                        } else {
                            log.warn("Received null/empty response from Jupiter API for mints: {}", mints);
                            return Collections.<TokenPrice>emptyList();
                        }
                    } catch (Exception e) {
                        log.error("Failed to fetch prices from Jupiter API: {}", e.getMessage());
                        throw e;
                    }
                })
                .onErrorResume(error -> {
                    log.error("Failed to fetch prices from Jupiter: {}", error.getMessage());
                    // Return empty prices for this batch
                    return Mono.just(mints.stream()
                        .map(mint -> new TokenPrice(mint, BigDecimal.ZERO, null, null, null, false))
                        .collect(Collectors.toList()));
                });
    }
    
    /**
     * Parse Jupiter API response
     */
    private List<TokenPrice> parsePriceResponse(String json, List<String> requestedMints) throws Exception {
        JsonNode root = objectMapper.readTree(json);
        List<TokenPrice> prices = new ArrayList<>();
        
        for (String mint : requestedMints) {
            if (root.has(mint)) {
                JsonNode priceNode = root.get(mint);
                
                BigDecimal usdPrice = priceNode.has("usdPrice") 
                    ? new BigDecimal(priceNode.get("usdPrice").asText()) 
                    : BigDecimal.ZERO;
                
                Long blockId = priceNode.has("blockId") 
                    ? priceNode.get("blockId").asLong() 
                    : null;
                
                Integer decimals = priceNode.has("decimals") 
                    ? priceNode.get("decimals").asInt() 
                    : null;
                
                Double priceChange24h = priceNode.has("priceChange24h") 
                    ? priceNode.get("priceChange24h").asDouble() 
                    : null;
                
                prices.add(new TokenPrice(mint, usdPrice, blockId, decimals, priceChange24h, true));
            } else {
                prices.add(new TokenPrice(mint, BigDecimal.ZERO, null, null, null, false));
            }
        }
        
        return prices;
    }
    
    /**
     * Partition a list into batches of specified size
     */
    private <T> List<List<T>> partition(List<T> list, int batchSize) {
        List<List<T>> batches = new ArrayList<>();
        for (int i = 0; i < list.size(); i += batchSize) {
            batches.add(list.subList(i, Math.min(i + batchSize, list.size())));
        }
        return batches;
    }
    
    /**
     * Get single token price (convenience method)
     */
    public TokenPrice getCurrentPrice(String mint) {
        Map<String, TokenPrice> prices = getCurrentPrices(List.of(mint));
        return prices.getOrDefault(mint, new TokenPrice(mint, BigDecimal.ZERO, null, null, null, false));
    }
}
