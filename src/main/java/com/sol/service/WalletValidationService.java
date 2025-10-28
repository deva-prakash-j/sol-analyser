package com.sol.service;

import com.sol.dto.Transaction;
import com.sol.util.NormalizeTransaction;
import com.sol.util.WalletMetricsCalculator;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Comprehensive wallet validation service
 * Performs 6-layer validation before scoring:
 * 1. Signature limit check
 * 2. Bot detection (composite scoring)
 * 3. Wash trading detection
 * 4. Account age validation
 * 5. MEV detection
 * 6. Priority fee analysis
 */
@Service
@RequiredArgsConstructor
public class WalletValidationService {
    private static final Logger log = LoggerFactory.getLogger(WalletValidationService.class);
    
    private final MevDetectionService mevDetectionService;
    private final PriorityFeeAnalysisService priorityFeeService;
    
    // Signature limit
    private static final int MAX_TOTAL_SIGNATURES = 30_000;
    
    // Bot detection thresholds
    private static final double MAX_TRADES_PER_DAY = 50.0;
    private static final double MIN_AVG_HOLDING_HOURS = 1.0;
    private static final double SUSPICIOUS_WIN_RATE = 95.0;
    private static final int MAX_SAME_BLOCK_TRADES = 5;
    private static final int MIN_TOKENS_WITH_IDENTICAL_SIZING = 5; // Must be across 5+ tokens
    private static final double MIN_IDENTICAL_SIZE_PERCENTAGE = 0.7; // 70% must be identical
    private static final int BOT_SCORE_THRESHOLD = 60; // 0-100 scale
    
    // Wash trading thresholds
    private static final int MAX_ROUND_TRIP_SECONDS = 120; // 2 minutes
    private static final int MAX_ROUND_TRIPS = 5;
    private static final int MAX_SAME_BLOCK_ROUND_TRIPS = 3;
    private static final double MAX_IDENTICAL_SIZE_PERCENTAGE = 0.5;
    
    // Account age requirements
    private static final long MIN_ACCOUNT_AGE_DAYS = 90;
    private static final long MIN_MONTHS_TRADED = 3;
    private static final long MAX_DAYS_SINCE_LAST_TRADE = 30;
    private static final int MIN_TOTAL_TRADES = 20;
    
    public record ValidationResult(
        boolean passed,
        List<String> failures,
        Map<String, Object> diagnostics
    ) {}
    
    /**
     * Comprehensive wallet validation with priority fee analysis
     */
    public ValidationResult validateWallet(
            List<NormalizeTransaction.Row> rows,
            List<Transaction> transactions,
            int totalSignatures,
            WalletMetricsCalculator.WalletMetrics metrics) {
        
        List<String> failures = new ArrayList<>();
        Map<String, Object> diagnostics = new HashMap<>();
        
        // 1. Check total signatures limit
        if (totalSignatures > MAX_TOTAL_SIGNATURES) {
            failures.add(String.format(
                "Excessive activity: %d signatures > %d (likely bot/spam wallet)",
                totalSignatures, MAX_TOTAL_SIGNATURES
            ));
            diagnostics.put("totalSignatures", totalSignatures);
        }
        
        // 2. Bot detection
        BotDetectionResult botResult = detectBot(rows, metrics);
        if (!botResult.isHuman()) {
            failures.addAll(botResult.reasons());
            diagnostics.putAll(botResult.metrics());
        }
        
        // 3. Wash trading detection
        WashTradingResult washResult = detectWashTrading(rows);
        if (washResult.isSuspicious()) {
            failures.addAll(washResult.reasons());
            diagnostics.putAll(washResult.metrics());
        }
        
        // 4. Account age validation
        AccountAgeResult ageResult = validateAccountAge(rows);
        if (!ageResult.meetsRequirements()) {
            failures.addAll(ageResult.reasons());
            diagnostics.putAll(ageResult.metrics());
        }
        
        // 5. MEV detection (front-running, arbitrage, sandwich attacks)
        MevDetectionService.MevAnalysis mevResult = mevDetectionService.analyzeMevActivity(rows);
        if (mevResult.isMevBot()) {
            failures.add(String.format(
                "MEV bot detected (score: %.1f/100): %s",
                mevResult.mevScore(),
                String.join(", ", mevResult.mevPatterns())
            ));
            diagnostics.putAll(mevResult.diagnostics());
        }
        
        // 6. Priority fee analysis (MEV infrastructure detection)
        if (transactions != null && !transactions.isEmpty()) {
            PriorityFeeAnalysisService.PriorityFeeAnalysis feeResult = 
                priorityFeeService.analyzePriorityFees(transactions);
            
            if (feeResult.likelyMevBot()) {
                failures.add(String.format(
                    "MEV infrastructure detected: %s",
                    String.join(", ", feeResult.patterns())
                ));
                diagnostics.put("avgPriorityFee", feeResult.avgFee());
                diagnostics.put("maxPriorityFee", feeResult.maxFee());
                diagnostics.put("elevatedFeePercentage", String.format("%.1f%%", feeResult.percentageElevatedFees()));
            } else if (feeResult.hasElevatedFees()) {
                // Log as warning but don't fail - might just use priority occasionally
                log.debug("Elevated priority fees detected: avg {} lamports ({}% of txs)", 
                    feeResult.avgFee(), 
                    String.format("%.1f", feeResult.percentageElevatedFees()));
                diagnostics.put("priorityFeeWarning", String.format("%.1f%% elevated fees", feeResult.percentageElevatedFees()));
            }
        }
        
        boolean passed = failures.isEmpty();
        
        if (!passed) {
            log.debug("Wallet validation failed: {}", String.join("; ", failures));
        }
        
        return new ValidationResult(passed, failures, diagnostics);
    }
    
    /**
     * Comprehensive wallet validation (backward compatible - without priority fee analysis)
     */
    public ValidationResult validateWallet(
            List<NormalizeTransaction.Row> rows,
            int totalSignatures,
            WalletMetricsCalculator.WalletMetrics metrics) {
        
        return validateWallet(rows, null, totalSignatures, metrics);
    }
    
    /**
     * Bot detection based on trading patterns
     */
    private record BotDetectionResult(
        boolean isHuman,
        List<String> reasons,
        Map<String, Object> metrics
    ) {}
    
    private BotDetectionResult detectBot(
            List<NormalizeTransaction.Row> rows,
            WalletMetricsCalculator.WalletMetrics metrics) {
        
        List<String> reasons = new ArrayList<>();
        Map<String, Object> botMetrics = new HashMap<>();
        int botScore = 0; // Composite score: 0-100
        
        if (rows.isEmpty()) {
            return new BotDetectionResult(true, List.of(), Map.of());
        }
        
        // Calculate trades per day
        long firstTrade = rows.stream()
            .map(NormalizeTransaction.Row::blockTime)
            .filter(Objects::nonNull)
            .min(Long::compareTo)
            .orElse(0L);
        
        long lastTrade = rows.stream()
            .map(NormalizeTransaction.Row::blockTime)
            .filter(Objects::nonNull)
            .max(Long::compareTo)
            .orElse(0L);
        
        double daysBetween = (lastTrade - firstTrade) / 86400.0;
        double tradesPerDay = daysBetween > 0 ? rows.size() / daysBetween : 0;
        
        botMetrics.put("tradesPerDay", String.format("%.1f", tradesPerDay));
        
        // Scoring Pattern 1: Trade frequency (max 40 points)
        if (tradesPerDay > 200) {
            botScore += 40;
            reasons.add(String.format("Extreme trade frequency: %.1f trades/day", tradesPerDay));
        } else if (tradesPerDay > 100) {
            botScore += 30;
            reasons.add(String.format("Very high trade frequency: %.1f trades/day", tradesPerDay));
        } else if (tradesPerDay > MAX_TRADES_PER_DAY) {
            botScore += 20;
            reasons.add(String.format("High trade frequency: %.1f trades/day", tradesPerDay));
        }
        
        // Scoring Pattern 2: Holding time (max 30 points)
        Double avgHoldingHours = metrics.avgHoldingHoursWeighted();
        if (avgHoldingHours != null) {
            if (avgHoldingHours < 0.1) { // <6 minutes
                botScore += 30;
                reasons.add(String.format("Ultra-short holding: %.2f hours avg", avgHoldingHours));
            } else if (avgHoldingHours < MIN_AVG_HOLDING_HOURS) {
                botScore += 15;
                reasons.add(String.format("Short holding periods: %.2f hours avg", avgHoldingHours));
            }
            botMetrics.put("avgHoldingHours", String.format("%.2f", avgHoldingHours));
        }
        
        // Scoring Pattern 3: Win rate (max 20 points)
        Double winRate = metrics.tradeWinRate();
        if (winRate != null) {
            if (winRate > 98) {
                botScore += 20;
                reasons.add(String.format("Unrealistic win rate: %.1f%%", winRate));
            } else if (winRate > SUSPICIOUS_WIN_RATE) {
                botScore += 10;
                reasons.add(String.format("Suspiciously high win rate: %.1f%%", winRate));
            }
            botMetrics.put("winRate", String.format("%.1f%%", winRate));
        }
        
        // Scoring Pattern 4: Same-block trading (max 30 points)
        Map<Long, Long> tradesPerBlock = rows.stream()
            .filter(r -> r.blockSlot() != null)
            .collect(Collectors.groupingBy(
                NormalizeTransaction.Row::blockSlot,
                Collectors.counting()
            ));
        
        long blocksWithManyTrades = tradesPerBlock.values().stream()
            .filter(count -> count > MAX_SAME_BLOCK_TRADES)
            .count();
        
        double sameBlockPercentage = rows.size() > 0 ? (blocksWithManyTrades * 100.0 / rows.size()) : 0;
        
        if (sameBlockPercentage > 20) {
            botScore += 30;
            reasons.add(String.format("Frequent same-block trading: %.1f%% of trades", sameBlockPercentage));
        } else if (blocksWithManyTrades > 0) {
            botScore += 10;
            reasons.add(String.format("Some same-block trading: %d blocks", blocksWithManyTrades));
        }
        botMetrics.put("blocksWithManyTrades", blocksWithManyTrades);
        
        // Scoring Pattern 5: Identical sizing ACROSS MULTIPLE TOKENS (max 15 points)
        // Key insight: Humans use consistent position sizing (good risk mgmt)
        // Bots use identical sizing across MANY different tokens (programmatic)
        Map<String, Map<String, Long>> tradeSizesByToken = rows.stream()
            .collect(Collectors.groupingBy(
                NormalizeTransaction.Row::baseMint,
                Collectors.groupingBy(
                    r -> r.notionalUsd().setScale(0, RoundingMode.HALF_UP).toString(),
                    Collectors.counting()
                )
            ));
        
        int tokensWithIdenticalSizing = 0;
        for (Map.Entry<String, Map<String, Long>> tokenEntry : tradeSizesByToken.entrySet()) {
            Map<String, Long> sizes = tokenEntry.getValue();
            long totalTrades = sizes.values().stream().mapToLong(Long::longValue).sum();
            long maxIdenticalCount = sizes.values().stream().max(Long::compareTo).orElse(0L);
            
            double identicalPercentage = totalTrades > 0 ? (maxIdenticalCount * 1.0 / totalTrades) : 0;
            
            // Only flag if >70% of trades for this token are identical size AND sufficient volume
            if (maxIdenticalCount >= 20 && identicalPercentage > MIN_IDENTICAL_SIZE_PERCENTAGE) {
                tokensWithIdenticalSizing++;
            }
        }
        
        // Only suspicious if identical sizing appears across MANY tokens
        if (tokensWithIdenticalSizing >= 10) {
            botScore += 15;
            reasons.add(String.format("Identical sizing across %d tokens (programmatic)", tokensWithIdenticalSizing));
            botMetrics.put("tokensWithIdenticalSizing", tokensWithIdenticalSizing);
        } else if (tokensWithIdenticalSizing >= MIN_TOKENS_WITH_IDENTICAL_SIZING) {
            botScore += 5;
            reasons.add(String.format("Consistent sizing across %d tokens", tokensWithIdenticalSizing));
            botMetrics.put("tokensWithIdenticalSizing", tokensWithIdenticalSizing);
        }
        // Note: 1-4 tokens with identical sizing = IGNORED (normal risk management)
        
        botMetrics.put("botScore", botScore);
        
        // Final determination: Reject only if composite score is high
        boolean isHuman = botScore < BOT_SCORE_THRESHOLD;
        
        if (!isHuman) {
            reasons.add(0, String.format("Bot score: %d/100 (threshold: %d)", botScore, BOT_SCORE_THRESHOLD));
        }
        
        return new BotDetectionResult(isHuman, reasons, botMetrics);
    }
    
    /**
     * Wash trading detection
     */
    private record WashTradingResult(
        boolean isSuspicious,
        List<String> reasons,
        Map<String, Object> metrics
    ) {}
    
    private WashTradingResult detectWashTrading(List<NormalizeTransaction.Row> rows) {
        List<String> reasons = new ArrayList<>();
        Map<String, Object> washMetrics = new HashMap<>();
        
        if (rows.isEmpty()) {
            return new WashTradingResult(false, List.of(), Map.of());
        }
        
        // Group by token
        Map<String, List<NormalizeTransaction.Row>> rowsByToken = rows.stream()
            .collect(Collectors.groupingBy(NormalizeTransaction.Row::baseMint));
        
        int totalRoundTrips = 0;
        int sameBlockRoundTrips = 0;
        
        for (Map.Entry<String, List<NormalizeTransaction.Row>> entry : rowsByToken.entrySet()) {
            List<NormalizeTransaction.Row> tokenRows = entry.getValue();
            tokenRows.sort(Comparator.comparing(NormalizeTransaction.Row::blockTime));
            
            // Check 1: Quick round-trip trades (buy then sell within 5 minutes)
            for (int i = 0; i < tokenRows.size() - 1; i++) {
                NormalizeTransaction.Row current = tokenRows.get(i);
                NormalizeTransaction.Row next = tokenRows.get(i + 1);
                
                if (current.side() == NormalizeTransaction.Side.BUY && 
                    next.side() == NormalizeTransaction.Side.SELL) {
                    
                    long timeDiff = next.blockTime() - current.blockTime();
                    if (timeDiff < MAX_ROUND_TRIP_SECONDS) {
                        totalRoundTrips++;
                        
                        // Check if same block
                        if (Objects.equals(current.blockSlot(), next.blockSlot())) {
                            sameBlockRoundTrips++;
                        }
                    }
                }
            }
        }
        
        washMetrics.put("roundTripTrades", totalRoundTrips);
        washMetrics.put("sameBlockRoundTrips", sameBlockRoundTrips);
        
        if (totalRoundTrips > MAX_ROUND_TRIPS) {
            reasons.add(String.format(
                "Wash trading: %d quick round-trip trades (buy then sell <2min)",
                totalRoundTrips
            ));
        }
        
        if (sameBlockRoundTrips > MAX_SAME_BLOCK_ROUND_TRIPS) {
            reasons.add(String.format(
                "Wash trading: %d same-block round-trip trades",
                sameBlockRoundTrips
            ));
        }
        
        // Check 3: Identical trade sizes (might indicate self-trading)
        Map<String, Long> tradeSizeFrequency = rows.stream()
            .map(r -> r.notionalUsd().setScale(0, RoundingMode.HALF_UP).toString())
            .collect(Collectors.groupingBy(
                size -> size,
                Collectors.counting()
            ));
        
        long maxIdenticalSizes = tradeSizeFrequency.values().stream()
            .max(Long::compareTo)
            .orElse(0L);
        
        double identicalSizePercentage = rows.size() > 0 ? (maxIdenticalSizes * 1.0 / rows.size()) : 0;
        
        if (identicalSizePercentage > MAX_IDENTICAL_SIZE_PERCENTAGE) {
            reasons.add(String.format(
                "Wash trading: %.1f%% trades have identical size",
                identicalSizePercentage * 100
            ));
            washMetrics.put("identicalSizePercentage", String.format("%.1f%%", identicalSizePercentage * 100));
        }
        
        boolean isSuspicious = !reasons.isEmpty();
        return new WashTradingResult(isSuspicious, reasons, washMetrics);
    }
    
    /**
     * Account age validation
     */
    private record AccountAgeResult(
        boolean meetsRequirements,
        List<String> reasons,
        Map<String, Object> metrics
    ) {}
    
    private AccountAgeResult validateAccountAge(List<NormalizeTransaction.Row> rows) {
        List<String> reasons = new ArrayList<>();
        Map<String, Object> ageMetrics = new HashMap<>();
        
        if (rows.isEmpty()) {
            reasons.add("No transaction history");
            return new AccountAgeResult(false, reasons, Map.of());
        }
        
        long now = Instant.now().getEpochSecond();
        
        long firstTrade = rows.stream()
            .map(NormalizeTransaction.Row::blockTime)
            .filter(Objects::nonNull)
            .min(Long::compareTo)
            .orElse(now);
        
        long lastTrade = rows.stream()
            .map(NormalizeTransaction.Row::blockTime)
            .filter(Objects::nonNull)
            .max(Long::compareTo)
            .orElse(0L);
        
        long accountAgeDays = (now - firstTrade) / 86400;
        long daysSinceLastTrade = (now - lastTrade) / 86400;
        
        ageMetrics.put("accountAgeDays", accountAgeDays);
        ageMetrics.put("daysSinceLastTrade", daysSinceLastTrade);
        ageMetrics.put("totalTrades", rows.size());
        
        // Requirement 1: Account must be at least 90 days old
        if (accountAgeDays < MIN_ACCOUNT_AGE_DAYS) {
            reasons.add(String.format(
                "Account too young: %d days < %d days minimum",
                accountAgeDays, MIN_ACCOUNT_AGE_DAYS
            ));
        }
        
        // Requirement 2: Must have traded across multiple months
        Set<String> monthsTraded = rows.stream()
            .map(r -> {
                long timestamp = r.blockTime();
                Instant instant = Instant.ofEpochSecond(timestamp);
                return instant.toString().substring(0, 7); // YYYY-MM
            })
            .collect(Collectors.toSet());
        
        ageMetrics.put("monthsTraded", monthsTraded.size());
        
        if (monthsTraded.size() < MIN_MONTHS_TRADED) {
            reasons.add(String.format(
                "Insufficient trading history: %d months < %d months minimum",
                monthsTraded.size(), MIN_MONTHS_TRADED
            ));
        }
        
        // Requirement 3: Must have recent activity
        if (daysSinceLastTrade > MAX_DAYS_SINCE_LAST_TRADE) {
            reasons.add(String.format(
                "Inactive wallet: %d days since last trade (>%d days)",
                daysSinceLastTrade, MAX_DAYS_SINCE_LAST_TRADE
            ));
        }
        
        // Requirement 4: Minimum trade count
        if (rows.size() < MIN_TOTAL_TRADES) {
            reasons.add(String.format(
                "Insufficient trades: %d < %d minimum",
                rows.size(), MIN_TOTAL_TRADES
            ));
        }
        
        boolean meetsRequirements = reasons.isEmpty();
        return new AccountAgeResult(meetsRequirements, reasons, ageMetrics);
    }
}
