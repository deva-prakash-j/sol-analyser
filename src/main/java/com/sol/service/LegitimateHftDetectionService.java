package com.sol.service;

import com.sol.util.NormalizeTransaction;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Enhanced service to distinguish between legitimate high-frequency traders
 * and automated bots to reduce false positives
 */
@Service
@RequiredArgsConstructor
public class LegitimateHftDetectionService {
    private static final Logger log = LoggerFactory.getLogger(LegitimateHftDetectionService.class);
    
    // HFT classification thresholds
    private static final double HFT_TRADES_PER_DAY_THRESHOLD = 50.0;
    private static final double HFT_AVG_HOLDING_HOURS_THRESHOLD = 2.0;
    
    public enum HftClassification {
        LEGITIMATE_HFT,
        BOT_TRADING,
        NORMAL_TRADER
    }
    
    @Builder
    @Data
    public static class HftPatterns {
        private double positionSizingVariability;
        private double executionLatencyDistribution;
        private double marketAdaptationScore;
        private double timeframeDistribution;
        private double executionPerfectionScore;
        private double humanErrorRate;
        private double learningPatternScore;
        private double emotionalTradingScore;
    }
    
    @Builder
    @Data
    public static class HftAnalysisResult {
        private HftClassification classification;
        private double legitimacyScore; // 0-100
        private List<String> legitimacyIndicators;
        private List<String> botIndicators;
        private HftPatterns patterns;
        private Map<String, Object> diagnostics;
    }
    
    public HftAnalysisResult analyzeTrader(List<NormalizeTransaction.Row> transactions) {
        if (transactions.isEmpty()) {
            return HftAnalysisResult.builder()
                .classification(HftClassification.NORMAL_TRADER)
                .legitimacyScore(50.0)
                .legitimacyIndicators(List.of())
                .botIndicators(List.of())
                .patterns(HftPatterns.builder().build())
                .diagnostics(Map.of())
                .build();
        }
        
        var patterns = analyzeHftPatterns(transactions);
        var classification = classifyTrader(transactions, patterns);
        var legitimacyScore = calculateLegitimacyScore(patterns);
        var legitimacyIndicators = new ArrayList<String>();
        var botIndicators = new ArrayList<String>();
        var diagnostics = new HashMap<String, Object>();
        
        // Collect indicators based on patterns
        collectIndicators(patterns, legitimacyIndicators, botIndicators);
        
        // Add diagnostics
        diagnostics.put("tradesPerDay", calculateTradesPerDay(transactions));
        diagnostics.put("avgHoldingHours", calculateAvgHoldingHours(transactions));
        diagnostics.put("legitimacyScore", legitimacyScore);
        
        return HftAnalysisResult.builder()
            .classification(classification)
            .legitimacyScore(legitimacyScore)
            .legitimacyIndicators(legitimacyIndicators)
            .botIndicators(botIndicators)
            .patterns(patterns)
            .diagnostics(diagnostics)
            .build();
    }
    
    private HftPatterns analyzeHftPatterns(List<NormalizeTransaction.Row> transactions) {
        return HftPatterns.builder()
            .positionSizingVariability(calculatePositionSizingVariability(transactions))
            .executionLatencyDistribution(analyzeExecutionLatency(transactions))
            .marketAdaptationScore(calculateMarketAdaptation(transactions))
            .timeframeDistribution(analyzeTimeframeDistribution(transactions))
            .executionPerfectionScore(calculateExecutionPerfection(transactions))
            .humanErrorRate(calculateHumanErrorRate(transactions))
            .learningPatternScore(analyzeLearningPatterns(transactions))
            .emotionalTradingScore(analyzeEmotionalPatterns(transactions))
            .build();
    }
    
    private HftClassification classifyTrader(List<NormalizeTransaction.Row> transactions, HftPatterns patterns) {
        double tradesPerDay = calculateTradesPerDay(transactions);
        double avgHoldingHours = calculateAvgHoldingHours(transactions);
        
        // Check if high-frequency trader
        if (tradesPerDay < HFT_TRADES_PER_DAY_THRESHOLD || avgHoldingHours > HFT_AVG_HOLDING_HOURS_THRESHOLD) {
            return HftClassification.NORMAL_TRADER;
        }
        
        // For HFT, check if legitimate or bot
        if (isLegitimateHft(patterns)) {
            return HftClassification.LEGITIMATE_HFT;
        } else {
            return HftClassification.BOT_TRADING;
        }
    }
    
    private boolean isLegitimateHft(HftPatterns patterns) {
        // Legitimate HFT characteristics:
        // 1. Variable position sizing (not identical across all trades)
        // 2. Shows market adaptation over time
        // 3. Has reasonable execution latency variance
        // 4. Diversifies across timeframes
        // 5. Not perfect execution (humans make mistakes)
        // 6. Shows learning patterns
        // 7. Has some emotional trading patterns
        
        int legitimacyPoints = 0;
        
        if (patterns.getPositionSizingVariability() > 0.3) legitimacyPoints++; // Variable sizing
        if (patterns.getMarketAdaptationScore() > 60) legitimacyPoints++; // Market adaptation
        if (patterns.getExecutionLatencyDistribution() > 0.4) legitimacyPoints++; // Natural latency variance
        if (patterns.getTimeframeDistribution() > 0.5) legitimacyPoints++; // Timeframe diversity
        if (patterns.getExecutionPerfectionScore() < 90) legitimacyPoints++; // Not perfect execution
        if (patterns.getHumanErrorRate() > 0.05) legitimacyPoints++; // Makes mistakes
        if (patterns.getLearningPatternScore() > 40) legitimacyPoints++; // Shows learning
        if (patterns.getEmotionalTradingScore() > 30) legitimacyPoints++; // Emotional elements
        
        // Need at least 5 out of 8 legitimacy indicators
        return legitimacyPoints >= 5;
    }
    
    private double calculatePositionSizingVariability(List<NormalizeTransaction.Row> transactions) {
        List<BigDecimal> sizes = transactions.stream()
            .map(NormalizeTransaction.Row::notionalUsd)
            .filter(Objects::nonNull)
            .collect(Collectors.toList());
        
        if (sizes.size() < 3) return 0.0;
        
        // Calculate coefficient of variation
        double mean = sizes.stream()
            .mapToDouble(BigDecimal::doubleValue)
            .average()
            .orElse(0.0);
        
        double variance = sizes.stream()
            .mapToDouble(size -> Math.pow(size.doubleValue() - mean, 2))
            .average()
            .orElse(0.0);
        
        double stdDev = Math.sqrt(variance);
        return mean > 0 ? stdDev / mean : 0.0; // Coefficient of variation
    }
    
    private double analyzeExecutionLatency(List<NormalizeTransaction.Row> transactions) {
        // Analyze time intervals between consecutive trades
        List<Long> intervals = new ArrayList<>();
        
        for (int i = 1; i < transactions.size(); i++) {
            Long prevTime = transactions.get(i - 1).blockTime();
            Long currTime = transactions.get(i).blockTime();
            
            if (prevTime != null && currTime != null) {
                intervals.add(currTime - prevTime);
            }
        }
        
        if (intervals.size() < 3) return 0.0;
        
        // Calculate coefficient of variation for intervals
        double mean = intervals.stream().mapToDouble(Long::doubleValue).average().orElse(0.0);
        double variance = intervals.stream()
            .mapToDouble(interval -> Math.pow(interval - mean, 2))
            .average()
            .orElse(0.0);
        
        double stdDev = Math.sqrt(variance);
        return mean > 0 ? stdDev / mean : 0.0;
    }
    
    private double calculateMarketAdaptation(List<NormalizeTransaction.Row> transactions) {
        // Analyze if trading patterns change over time (market adaptation)
        if (transactions.size() < 20) return 0.0;
        
        // Split transactions into early and late periods
        int midPoint = transactions.size() / 2;
        List<NormalizeTransaction.Row> earlyTrades = transactions.subList(0, midPoint);
        List<NormalizeTransaction.Row> lateTrades = transactions.subList(midPoint, transactions.size());
        
        // Compare trading characteristics between periods
        double earlyAvgSize = earlyTrades.stream()
            .mapToDouble(t -> t.notionalUsd().doubleValue())
            .average()
            .orElse(0.0);
        
        double lateAvgSize = lateTrades.stream()
            .mapToDouble(t -> t.notionalUsd().doubleValue())
            .average()
            .orElse(0.0);
        
        // Calculate adaptation score based on changes
        double sizeChange = Math.abs(lateAvgSize - earlyAvgSize) / Math.max(earlyAvgSize, lateAvgSize);
        
        // Score adaptation (changes suggest human learning/adaptation)
        return Math.min(100.0, sizeChange * 100);
    }
    
    private double analyzeTimeframeDistribution(List<NormalizeTransaction.Row> transactions) {
        // Analyze distribution of holding periods
        Map<String, Integer> timeframeBuckets = new HashMap<>();
        
        for (int i = 0; i < transactions.size() - 1; i++) {
            var current = transactions.get(i);
            var next = transactions.get(i + 1);
            
            if (current.blockTime() != null && next.blockTime() != null &&
                current.baseMint().equals(next.baseMint()) &&
                current.side() != next.side()) {
                
                long holdingSeconds = next.blockTime() - current.blockTime();
                String bucket = categorizeHoldingPeriod(holdingSeconds);
                timeframeBuckets.merge(bucket, 1, Integer::sum);
            }
        }
        
        // Calculate distribution entropy (higher = more diverse)
        int totalHoldings = timeframeBuckets.values().stream().mapToInt(Integer::intValue).sum();
        if (totalHoldings == 0) return 0.0;
        
        double entropy = 0.0;
        for (int count : timeframeBuckets.values()) {
            double probability = (double) count / totalHoldings;
            if (probability > 0) {
                entropy -= probability * Math.log(probability) / Math.log(2);
            }
        }
        
        // Normalize entropy to 0-1 scale
        return Math.min(1.0, entropy / 3.0); // Max theoretical entropy for ~8 buckets
    }
    
    private String categorizeHoldingPeriod(long seconds) {
        if (seconds < 60) return "sub-minute";
        if (seconds < 300) return "1-5min";
        if (seconds < 3600) return "5min-1hr";
        if (seconds < 86400) return "1-24hr";
        if (seconds < 604800) return "1-7days";
        return "7days+";
    }
    
    private double calculateExecutionPerfection(List<NormalizeTransaction.Row> transactions) {
        // Analyze how "perfect" the execution timing is
        // Perfect bots never make suboptimal entries/exits
        
        int perfectExecutions = 0;
        int totalExecutions = 0;
        
        // Group by token and analyze entries/exits
        Map<String, List<NormalizeTransaction.Row>> byToken = transactions.stream()
            .collect(Collectors.groupingBy(NormalizeTransaction.Row::baseMint));
        
        for (List<NormalizeTransaction.Row> tokenTrades : byToken.values()) {
            tokenTrades.sort(Comparator.comparing(NormalizeTransaction.Row::blockTime));
            
            for (int i = 0; i < tokenTrades.size() - 1; i++) {
                var entry = tokenTrades.get(i);
                var exit = tokenTrades.get(i + 1);
                
                if (entry.side() == NormalizeTransaction.Side.BUY &&
                    exit.side() == NormalizeTransaction.Side.SELL) {
                    
                    totalExecutions++;
                    
                    // Check if execution was "perfect" (highly suspicious)
                    if (isPerfectExecution(entry, exit)) {
                        perfectExecutions++;
                    }
                }
            }
        }
        
        return totalExecutions > 0 ? (perfectExecutions * 100.0 / totalExecutions) : 0.0;
    }
    
    private boolean isPerfectExecution(NormalizeTransaction.Row entry, NormalizeTransaction.Row exit) {
        // Define "perfect" execution criteria (suspicious if too many)
        if (entry.blockTime() == null || exit.blockTime() == null) return false;
        
        long holdingTime = exit.blockTime() - entry.blockTime();
        
        // Perfect if: same block execution with profit
        if (entry.blockSlot() != null && entry.blockSlot().equals(exit.blockSlot())) {
            return exit.priceUsdPerBase().compareTo(entry.priceUsdPerBase()) > 0; // Profitable same-block trade
        }
        
        // Perfect if: very short holding with >5% profit
        if (holdingTime < 60) { // Less than 1 minute
            BigDecimal profitPercent = exit.priceUsdPerBase()
                .subtract(entry.priceUsdPerBase())
                .divide(entry.priceUsdPerBase(), 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
            
            return profitPercent.compareTo(BigDecimal.valueOf(5)) > 0;
        }
        
        return false;
    }
    
    private double calculateHumanErrorRate(List<NormalizeTransaction.Row> transactions) {
        // Analyze patterns that suggest human errors/suboptimal decisions
        int errors = 0;
        int totalDecisions = 0;
        
        // Look for common human error patterns
        Map<String, List<NormalizeTransaction.Row>> byToken = transactions.stream()
            .collect(Collectors.groupingBy(NormalizeTransaction.Row::baseMint));
        
        for (List<NormalizeTransaction.Row> tokenTrades : byToken.values()) {
            tokenTrades.sort(Comparator.comparing(NormalizeTransaction.Row::blockTime));
            
            for (int i = 0; i < tokenTrades.size() - 1; i++) {
                var trade1 = tokenTrades.get(i);
                var trade2 = tokenTrades.get(i + 1);
                
                totalDecisions++;
                
                // Error pattern 1: Buying high, selling low
                if (trade1.side() == NormalizeTransaction.Side.BUY &&
                    trade2.side() == NormalizeTransaction.Side.SELL &&
                    trade2.priceUsdPerBase().compareTo(trade1.priceUsdPerBase()) < 0) {
                    errors++;
                }
                
                // Error pattern 2: Inconsistent profit taking
                if (i < tokenTrades.size() - 2) {
                    var trade3 = tokenTrades.get(i + 2);
                    if (isProfitTakingInconsistency(trade1, trade2, trade3)) {
                        errors++;
                    }
                }
            }
        }
        
        return totalDecisions > 0 ? (errors * 1.0 / totalDecisions) : 0.0;
    }
    
    private boolean isProfitTakingInconsistency(NormalizeTransaction.Row t1, 
                                              NormalizeTransaction.Row t2, 
                                              NormalizeTransaction.Row t3) {
        // Look for patterns like: sell for small profit, then price goes much higher
        if (t1.side() == NormalizeTransaction.Side.BUY &&
            t2.side() == NormalizeTransaction.Side.SELL &&
            t3.side() == NormalizeTransaction.Side.BUY) {
            
            BigDecimal profit1 = t2.priceUsdPerBase().subtract(t1.priceUsdPerBase()).divide(t1.priceUsdPerBase(), 4, RoundingMode.HALF_UP);
            BigDecimal missedProfit = t3.priceUsdPerBase().subtract(t2.priceUsdPerBase()).divide(t2.priceUsdPerBase(), 4, RoundingMode.HALF_UP);
            
            // Small profit followed by large missed opportunity
            return profit1.compareTo(BigDecimal.valueOf(0.02)) < 0 && // <2% profit
                   missedProfit.compareTo(BigDecimal.valueOf(0.1)) > 0; // >10% missed
        }
        
        return false;
    }
    
    private double analyzeLearningPatterns(List<NormalizeTransaction.Row> transactions) {
        // Analyze if performance improves over time (learning)
        if (transactions.size() < 30) return 0.0;
        
        // Split into thirds and compare performance
        int third = transactions.size() / 3;
        List<NormalizeTransaction.Row> early = transactions.subList(0, third);
        List<NormalizeTransaction.Row> middle = transactions.subList(third, 2 * third);
        List<NormalizeTransaction.Row> late = transactions.subList(2 * third, transactions.size());
        
        double earlyPerformance = calculatePeriodPerformance(early);
        double middlePerformance = calculatePeriodPerformance(middle);
        double latePerformance = calculatePeriodPerformance(late);
        
        // Learning score: improvement over time
        double improvement1 = middlePerformance - earlyPerformance;
        double improvement2 = latePerformance - middlePerformance;
        
        return Math.max(0, Math.min(100, (improvement1 + improvement2) * 50));
    }
    
    private double calculatePeriodPerformance(List<NormalizeTransaction.Row> transactions) {
        // Simple win rate calculation for the period
        Map<String, List<NormalizeTransaction.Row>> byToken = transactions.stream()
            .collect(Collectors.groupingBy(NormalizeTransaction.Row::baseMint));
        
        int wins = 0;
        int total = 0;
        
        for (List<NormalizeTransaction.Row> tokenTrades : byToken.values()) {
            tokenTrades.sort(Comparator.comparing(NormalizeTransaction.Row::blockTime));
            
            for (int i = 0; i < tokenTrades.size() - 1; i++) {
                var entry = tokenTrades.get(i);
                var exit = tokenTrades.get(i + 1);
                
                if (entry.side() == NormalizeTransaction.Side.BUY &&
                    exit.side() == NormalizeTransaction.Side.SELL) {
                    total++;
                    if (exit.priceUsdPerBase().compareTo(entry.priceUsdPerBase()) > 0) {
                        wins++;
                    }
                }
            }
        }
        
        return total > 0 ? (wins * 100.0 / total) : 50.0;
    }
    
    private double analyzeEmotionalPatterns(List<NormalizeTransaction.Row> transactions) {
        // Look for emotional trading patterns (FOMO, panic selling, etc.)
        double emotionalScore = 0.0;
        
        // Pattern 1: Weekend/holiday activity reduction
        emotionalScore += analyzeTimeBasedPatterns(transactions);
        
        // Pattern 2: Panic selling patterns
        emotionalScore += analyzePanicPatterns(transactions);
        
        // Pattern 3: FOMO buying patterns
        emotionalScore += analyzefomoPatterns(transactions);
        
        return Math.min(100.0, emotionalScore);
    }
    
    private double analyzeTimeBasedPatterns(List<NormalizeTransaction.Row> transactions) {
        Map<DayOfWeek, Integer> dayCount = new HashMap<>();
        
        for (var transaction : transactions) {
            if (transaction.blockTime() != null) {
                LocalDateTime dateTime = LocalDateTime.ofInstant(
                    Instant.ofEpochSecond(transaction.blockTime()), 
                    ZoneOffset.UTC
                );
                dayCount.merge(dateTime.getDayOfWeek(), 1, Integer::sum);
            }
        }
        
        int weekdayTrades = Arrays.stream(DayOfWeek.values())
            .filter(day -> day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY)
            .mapToInt(day -> dayCount.getOrDefault(day, 0))
            .sum();
        
        int weekendTrades = dayCount.getOrDefault(DayOfWeek.SATURDAY, 0) + 
                           dayCount.getOrDefault(DayOfWeek.SUNDAY, 0);
        
        // Humans typically trade less on weekends
        if (weekdayTrades > 0) {
            double weekendRatio = (double) weekendTrades / (weekdayTrades + weekendTrades);
            return weekendRatio < 0.2 ? 30.0 : 0.0; // <20% weekend trading is human-like
        }
        
        return 0.0;
    }
    
    private double analyzePanicPatterns(List<NormalizeTransaction.Row> transactions) {
        // Look for quick sells at losses (panic selling)
        int panicSells = 0;
        int totalSells = 0;
        
        Map<String, List<NormalizeTransaction.Row>> byToken = transactions.stream()
            .collect(Collectors.groupingBy(NormalizeTransaction.Row::baseMint));
        
        for (List<NormalizeTransaction.Row> tokenTrades : byToken.values()) {
            tokenTrades.sort(Comparator.comparing(NormalizeTransaction.Row::blockTime));
            
            for (int i = 0; i < tokenTrades.size() - 1; i++) {
                var buy = tokenTrades.get(i);
                var sell = tokenTrades.get(i + 1);
                
                if (buy.side() == NormalizeTransaction.Side.BUY &&
                    sell.side() == NormalizeTransaction.Side.SELL) {
                    
                    totalSells++;
                    
                    // Quick sell at loss = panic
                    long holdingTime = sell.blockTime() - buy.blockTime();
                    boolean isLoss = sell.priceUsdPerBase().compareTo(buy.priceUsdPerBase()) < 0;
                    
                    if (holdingTime < 3600 && isLoss) { // <1 hour loss
                        panicSells++;
                    }
                }
            }
        }
        
        // Some panic selling is human-like
        if (totalSells > 0) {
            double panicRate = (double) panicSells / totalSells;
            return panicRate > 0.05 && panicRate < 0.3 ? 25.0 : 0.0; // 5-30% panic rate is human
        }
        
        return 0.0;
    }
    
    private double analyzefomoPatterns(List<NormalizeTransaction.Row> transactions) {
        // Look for FOMO buying (buying after significant price increases)
        int fomoBuys = 0;
        int totalBuys = 0;
        
        for (var transaction : transactions) {
            if (transaction.side() == NormalizeTransaction.Side.BUY) {
                totalBuys++;
                
                // This is simplified - would need historical price data for better analysis
                // For now, just check if buying at higher prices than previous sells
                if (isFomoBuy(transaction, transactions)) {
                    fomoBuys++;
                }
            }
        }
        
        if (totalBuys > 0) {
            double fomoRate = (double) fomoBuys / totalBuys;
            return fomoRate > 0.1 && fomoRate < 0.4 ? 20.0 : 0.0; // 10-40% FOMO rate is human
        }
        
        return 0.0;
    }
    
    private boolean isFomoBuy(NormalizeTransaction.Row buyTrade, List<NormalizeTransaction.Row> allTrades) {
        // Look for previous sells of same token at lower price
        return allTrades.stream()
            .filter(t -> t.baseMint().equals(buyTrade.baseMint()))
            .filter(t -> t.side() == NormalizeTransaction.Side.SELL)
            .filter(t -> t.blockTime() < buyTrade.blockTime())
            .anyMatch(t -> t.priceUsdPerBase().compareTo(buyTrade.priceUsdPerBase().multiply(BigDecimal.valueOf(0.9))) < 0);
    }
    
    private double calculateLegitimacyScore(HftPatterns patterns) {
        double score = 50.0; // Base score
        
        // Positive indicators (increase legitimacy)
        score += patterns.getPositionSizingVariability() * 20; // 0-20 points
        score += patterns.getMarketAdaptationScore() * 0.2; // 0-20 points
        score += patterns.getHumanErrorRate() * 100; // 0-10 points (errors are human)
        score += patterns.getLearningPatternScore() * 0.2; // 0-20 points
        score += patterns.getEmotionalTradingScore() * 0.2; // 0-20 points
        
        // Negative indicators (decrease legitimacy)
        score -= patterns.getExecutionPerfectionScore() * 0.3; // 0-30 points penalty
        score -= Math.max(0, 1.0 - patterns.getExecutionLatencyDistribution()) * 20; // 0-20 penalty
        
        return Math.max(0.0, Math.min(100.0, score));
    }
    
    private void collectIndicators(HftPatterns patterns, List<String> legitimacyIndicators, List<String> botIndicators) {
        // Legitimacy indicators
        if (patterns.getPositionSizingVariability() > 0.3) {
            legitimacyIndicators.add("Variable position sizing suggests human risk management");
        }
        if (patterns.getMarketAdaptationScore() > 60) {
            legitimacyIndicators.add("Shows market adaptation over time");
        }
        if (patterns.getHumanErrorRate() > 0.05) {
            legitimacyIndicators.add("Makes occasional suboptimal decisions (human-like)");
        }
        if (patterns.getLearningPatternScore() > 40) {
            legitimacyIndicators.add("Performance improves over time (learning pattern)");
        }
        if (patterns.getEmotionalTradingScore() > 30) {
            legitimacyIndicators.add("Shows emotional trading patterns");
        }
        if (patterns.getExecutionLatencyDistribution() > 0.4) {
            legitimacyIndicators.add("Natural execution timing variance");
        }
        
        // Bot indicators
        if (patterns.getExecutionPerfectionScore() > 90) {
            botIndicators.add("Suspiciously perfect execution timing");
        }
        if (patterns.getPositionSizingVariability() < 0.1) {
            botIndicators.add("Identical position sizing across trades");
        }
        if (patterns.getHumanErrorRate() < 0.01) {
            botIndicators.add("No suboptimal decisions detected");
        }
        if (patterns.getExecutionLatencyDistribution() < 0.2) {
            botIndicators.add("Consistent execution intervals");
        }
        if (patterns.getEmotionalTradingScore() < 10) {
            botIndicators.add("No emotional trading patterns detected");
        }
    }
    
    private double calculateTradesPerDay(List<NormalizeTransaction.Row> transactions) {
        if (transactions.isEmpty()) return 0.0;
        
        long firstTrade = transactions.stream()
            .map(NormalizeTransaction.Row::blockTime)
            .filter(Objects::nonNull)
            .min(Long::compareTo)
            .orElse(0L);
        
        long lastTrade = transactions.stream()
            .map(NormalizeTransaction.Row::blockTime)
            .filter(Objects::nonNull)
            .max(Long::compareTo)
            .orElse(0L);
        
        double daysBetween = (lastTrade - firstTrade) / 86400.0;
        return daysBetween > 0 ? transactions.size() / daysBetween : 0;
    }
    
    private double calculateAvgHoldingHours(List<NormalizeTransaction.Row> transactions) {
        List<Long> holdingPeriods = new ArrayList<>();
        
        Map<String, List<NormalizeTransaction.Row>> byToken = transactions.stream()
            .collect(Collectors.groupingBy(NormalizeTransaction.Row::baseMint));
        
        for (List<NormalizeTransaction.Row> tokenTrades : byToken.values()) {
            tokenTrades.sort(Comparator.comparing(NormalizeTransaction.Row::blockTime));
            
            for (int i = 0; i < tokenTrades.size() - 1; i++) {
                var current = tokenTrades.get(i);
                var next = tokenTrades.get(i + 1);
                
                if (current.side() == NormalizeTransaction.Side.BUY &&
                    next.side() == NormalizeTransaction.Side.SELL &&
                    current.blockTime() != null && next.blockTime() != null) {
                    
                    holdingPeriods.add(next.blockTime() - current.blockTime());
                }
            }
        }
        
        return holdingPeriods.stream()
            .mapToDouble(period -> period / 3600.0) // Convert to hours
            .average()
            .orElse(0.0);
    }
}