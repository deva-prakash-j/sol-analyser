package com.sol.service;

import com.sol.util.NormalizeTransaction.Row;
import com.sol.util.NormalizeTransaction.Side;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.stream.Collectors;

/**
 * MEV (Maximal Extractable Value) Detection Service
 * Identifies wallets that profit from transaction ordering, front-running, and arbitrage
 * These strategies cannot be replicated by regular copy traders
 */
@Service
@Slf4j
public class MevDetectionService {
    
    // MEV detection thresholds
    private static final int MIN_SAME_BLOCK_ROUNDTRIPS = 5;
    private static final int MIN_SANDWICH_ATTACKS = 3;
    private static final double MIN_ULTRA_SHORT_RATIO = 0.30; // 30% of trades
    private static final double MIN_TRADES_PER_BLOCK = 2.0;
    private static final double MEV_SCORE_THRESHOLD = 50.0;
    private static final long ULTRA_SHORT_SECONDS = 60; // 1 minute
    
    public record MevAnalysis(
        boolean isMevBot,
        List<String> mevPatterns,
        double mevScore,
        Map<String, Object> diagnostics
    ) {}
    
    /**
     * Comprehensive MEV activity analysis
     */
    public MevAnalysis analyzeMevActivity(List<Row> rows) {
        if (rows == null || rows.isEmpty()) {
            return new MevAnalysis(false, List.of(), 0.0, Map.of());
        }
        
        List<String> patterns = new ArrayList<>();
        Map<String, Object> diagnostics = new HashMap<>();
        double mevScore = 0.0;
        
        // 1. Same-block round-trip detection (arbitrage/MEV)
        int sameBlockRoundTrips = detectSameBlockRoundTrips(rows);
        diagnostics.put("sameBlockRoundTrips", sameBlockRoundTrips);
        
        if (sameBlockRoundTrips > MIN_SAME_BLOCK_ROUNDTRIPS) {
            patterns.add(String.format(
                "Same-block round-trips: %d (MEV/arbitrage pattern)",
                sameBlockRoundTrips
            ));
            mevScore += Math.min(40.0, sameBlockRoundTrips * 2.0);
        }
        
        // 2. Sandwich attack detection (front-running)
        int suspiciousSandwiches = detectSandwichAttacks(rows);
        diagnostics.put("sandwichAttacks", suspiciousSandwiches);
        
        if (suspiciousSandwiches > 0) {
            patterns.add(String.format(
                "Sandwich attacks detected: %d (front-running pattern)",
                suspiciousSandwiches
            ));
            mevScore += suspiciousSandwiches * 15.0;
        }
        
        // 3. Ultra-short holding times (<1 minute)
        int ultraShortHolds = detectUltraShortHolds(rows);
        double shortHoldRatio = (double) ultraShortHolds / rows.size();
        diagnostics.put("ultraShortHolds", ultraShortHolds);
        diagnostics.put("ultraShortRatio", String.format("%.1f%%", shortHoldRatio * 100));
        
        if (shortHoldRatio > MIN_ULTRA_SHORT_RATIO) {
            patterns.add(String.format(
                "Ultra-short holds: %d trades < 1 min (%.1f%% of total - HFT pattern)",
                ultraShortHolds,
                shortHoldRatio * 100
            ));
            mevScore += shortHoldRatio * 30.0;
        }
        
        // 4. High trade density per block (MEV infrastructure)
        Map<Long, Long> tradesPerBlock = rows.stream()
            .filter(r -> r.blockSlot() != null)
            .collect(Collectors.groupingBy(
                Row::blockSlot,
                Collectors.counting()
            ));
        
        double avgTradesPerBlock = tradesPerBlock.isEmpty() 
            ? 0.0 
            : (double) rows.size() / tradesPerBlock.size();
        
        diagnostics.put("uniqueBlocks", tradesPerBlock.size());
        diagnostics.put("avgTradesPerBlock", String.format("%.2f", avgTradesPerBlock));
        
        if (avgTradesPerBlock > MIN_TRADES_PER_BLOCK) {
            patterns.add(String.format(
                "High trade density: %.1f trades/block (MEV infrastructure)",
                avgTradesPerBlock
            ));
            mevScore += Math.min(20.0, (avgTradesPerBlock - 2.0) * 10.0);
        }
        
        // 5. Multiple DEX interactions (cross-DEX arbitrage)
        int crossDexArbitrage = detectCrossDexArbitrage(rows);
        diagnostics.put("crossDexArbitrage", crossDexArbitrage);
        
        if (crossDexArbitrage > 0) {
            patterns.add(String.format(
                "Cross-DEX arbitrage: %d occurrences (requires MEV infrastructure)",
                crossDexArbitrage
            ));
            mevScore += crossDexArbitrage * 5.0;
        }
        
        // Cap MEV score at 100
        mevScore = Math.min(100.0, mevScore);
        diagnostics.put("mevScore", String.format("%.1f", mevScore));
        
        // Determine if wallet is MEV bot
        boolean isMevBot = mevScore >= MEV_SCORE_THRESHOLD 
            || sameBlockRoundTrips > MIN_SAME_BLOCK_ROUNDTRIPS * 2
            || suspiciousSandwiches >= MIN_SANDWICH_ATTACKS;
        
        if (isMevBot) {
            log.debug("MEV bot detected - Score: {}, Patterns: {}", mevScore, patterns.size());
        }
        
        return new MevAnalysis(isMevBot, patterns, mevScore, diagnostics);
    }
    
    /**
     * Detect same-block round-trip trades (buy and sell in same block)
     * Strong indicator of arbitrage/MEV activity
     */
    private int detectSameBlockRoundTrips(List<Row> rows) {
        Map<Long, List<Row>> byBlock = rows.stream()
            .filter(r -> r.blockSlot() != null)
            .collect(Collectors.groupingBy(Row::blockSlot));
        
        int roundTrips = 0;
        
        for (List<Row> blockTrades : byBlock.values()) {
            if (blockTrades.size() < 2) continue;
            
            // Group by token within block
            Map<String, List<Row>> byToken = blockTrades.stream()
                .collect(Collectors.groupingBy(Row::baseMint));
            
            for (List<Row> tokenTrades : byToken.values()) {
                boolean hasBuy = tokenTrades.stream()
                    .anyMatch(r -> r.side() == Side.BUY);
                boolean hasSell = tokenTrades.stream()
                    .anyMatch(r -> r.side() == Side.SELL);
                
                if (hasBuy && hasSell) {
                    roundTrips++;
                }
            }
        }
        
        return roundTrips;
    }
    
    /**
     * Detect sandwich attacks (buy → sell with matching quantities)
     * Definitive MEV front-running pattern
     */
    private int detectSandwichAttacks(List<Row> rows) {
        Map<Long, List<Row>> byBlock = rows.stream()
            .filter(r -> r.blockSlot() != null)
            .collect(Collectors.groupingBy(Row::blockSlot));
        
        int sandwiches = 0;
        
        for (List<Row> blockTrades : byBlock.values()) {
            if (blockTrades.size() < 2) continue;
            
            Map<String, List<Row>> byToken = blockTrades.stream()
                .collect(Collectors.groupingBy(Row::baseMint));
            
            for (List<Row> tokenTrades : byToken.values()) {
                if (tokenTrades.size() < 2) continue;
                
                // Sort by signature to get transaction order within block
                tokenTrades.sort(Comparator.comparing(Row::signature));
                
                // Check for buy followed by sell with similar quantities
                for (int i = 0; i < tokenTrades.size() - 1; i++) {
                    Row first = tokenTrades.get(i);
                    
                    for (int j = i + 1; j < tokenTrades.size(); j++) {
                        Row second = tokenTrades.get(j);
                        
                        // Check if buy → sell pattern
                        if (first.side() == Side.BUY && second.side() == Side.SELL) {
                            BigDecimal buyAmt = first.baseAmount();
                            BigDecimal sellAmt = second.baseAmount();
                            
                            if (buyAmt.compareTo(BigDecimal.ZERO) == 0) continue;
                            
                            // Check if quantities match within 5% (sandwich pattern)
                            BigDecimal diff = buyAmt.subtract(sellAmt).abs();
                            BigDecimal tolerance = buyAmt.multiply(new BigDecimal("0.05"));
                            
                            if (diff.compareTo(tolerance) <= 0) {
                                sandwiches++;
                                break;
                            }
                        }
                    }
                }
            }
        }
        
        return sandwiches;
    }
    
    /**
     * Detect ultra-short holding periods (<1 minute)
     * Indicates HFT/MEV activity impossible for human traders
     */
    private int detectUltraShortHolds(List<Row> rows) {
        Map<String, List<Row>> byToken = rows.stream()
            .collect(Collectors.groupingBy(Row::baseMint));
        
        int ultraShort = 0;
        
        for (List<Row> tokenTrades : byToken.values()) {
            tokenTrades.sort(Comparator.comparing(Row::blockTime));
            
            for (int i = 0; i < tokenTrades.size() - 1; i++) {
                Row current = tokenTrades.get(i);
                Row next = tokenTrades.get(i + 1);
                
                if (current.blockTime() == null || next.blockTime() == null) continue;
                
                // Check for buy → sell pattern
                if (current.side() == Side.BUY && next.side() == Side.SELL) {
                    long holdTime = next.blockTime() - current.blockTime();
                    
                    if (holdTime < ULTRA_SHORT_SECONDS) {
                        ultraShort++;
                    }
                }
            }
        }
        
        return ultraShort;
    }
    
    /**
     * Detect cross-DEX arbitrage opportunities
     * Requires specialized infrastructure and sub-second execution
     */
    private int detectCrossDexArbitrage(List<Row> rows) {
        Map<String, List<Row>> byToken = rows.stream()
            .collect(Collectors.groupingBy(Row::baseMint));
        
        int arbitrageCount = 0;
        
        for (List<Row> tokenTrades : byToken.values()) {
            if (tokenTrades.size() < 2) continue;
            
            tokenTrades.sort(Comparator.comparing(Row::blockTime));
            
            // Look for rapid buy/sell cycles that are likely arbitrage
            for (int i = 0; i < tokenTrades.size() - 1; i++) {
                Row trade1 = tokenTrades.get(i);
                Row trade2 = tokenTrades.get(i + 1);
                
                if (trade1.blockTime() == null || trade2.blockTime() == null) continue;
                
                // Opposite sides within 10 seconds with similar sizes
                long timeDiff = Math.abs(trade2.blockTime() - trade1.blockTime());
                boolean oppositeSides = trade1.side() != trade2.side();
                
                if (timeDiff <= 10 && oppositeSides) {
                    // Check if sizes are similar (within 10%)
                    BigDecimal size1 = trade1.notionalUsd();
                    BigDecimal size2 = trade2.notionalUsd();
                    
                    if (size1.compareTo(BigDecimal.ZERO) > 0) {
                        BigDecimal ratio = size2.divide(size1, 4, RoundingMode.HALF_UP);
                        
                        // Sizes within 10% of each other = likely arbitrage
                        if (ratio.compareTo(new BigDecimal("0.90")) >= 0 
                            && ratio.compareTo(new BigDecimal("1.10")) <= 0) {
                            arbitrageCount++;
                        }
                    }
                }
            }
        }
        
        return arbitrageCount;
    }
}
