package com.sol.service;

import com.sol.dto.Transaction;
import com.sol.util.NormalizeTransaction;
import com.sol.util.WalletMetricsCalculator;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

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
    private final LegitimateHftDetectionService legitimateHftDetectionService;
    private final DynamicThresholdService dynamicThresholdService;
    private final HftWhitelistService hftWhitelistService;
    
    // Signature limit
    private static final int MAX_TOTAL_SIGNATURES = 60_000;
    
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
    private static final long MIN_ACCOUNT_AGE_DAYS = 60; // Reduced from 90 days based on validation results
    private static final long MIN_MONTHS_TRADED = 3;
    private static final long MAX_DAYS_SINCE_LAST_TRADE = 30;
    private static final int MIN_TOTAL_TRADES = 20;
    
    public record ValidationResult(
        boolean passed,
        List<String> failures,
        Map<String, Object> diagnostics
    ) {}
    
    private record BotDetectionResult(
        boolean isHuman,
        List<String> reasons,
        Map<String, Object> metrics
    ) {}
    
    private record WashTradingResult(
        boolean isSuspicious,
        List<String> reasons,
        Map<String, Object> metrics
    ) {}
    
    /**
     * Enhanced wallet validation with HFT-aware detection
     */
    public ValidationResult validateWallet(
            List<NormalizeTransaction.Row> rows,
            List<Transaction> transactions,
            int totalSignatures,
            WalletMetricsCalculator.WalletMetrics metrics) {
        
        List<String> failures = new ArrayList<>();
        Map<String, Object> diagnostics = new HashMap<>();
        
        // Step 1: Analyze trader profile and HFT characteristics
        LegitimateHftDetectionService.HftAnalysisResult hftAnalysis = 
            legitimateHftDetectionService.analyzeTrader(rows);
        
        DynamicThresholdService.WalletProfile walletProfile = 
            dynamicThresholdService.createWalletProfile(hftAnalysis, metrics);
        
        DynamicThresholdService.ValidationThresholds thresholds = 
            dynamicThresholdService.getAdjustedThresholds(walletProfile);
        
        // Step 2: Check whitelist first
        HftWhitelistService.WhitelistAnalysis whitelistAnalysis = 
            hftWhitelistService.analyzeForWhitelist("", rows, metrics, hftAnalysis);
        
        if (whitelistAnalysis.isWhitelisted()) {
            log.info("Wallet whitelisted: {} (confidence: {})", 
                String.join(", ", whitelistAnalysis.getWhitelistReasons()),
                whitelistAnalysis.getConfidenceScore());
            
            diagnostics.put("whitelisted", true);
            diagnostics.put("whitelistCategory", whitelistAnalysis.getCategory());
            diagnostics.put("whitelistReasons", whitelistAnalysis.getWhitelistReasons());
            diagnostics.putAll(hftAnalysis.getDiagnostics());
            
            return new ValidationResult(true, List.of(), diagnostics);
        }
        
        // Step 3: Apply enhanced validation with dynamic thresholds
        log.debug("Applying enhanced validation with profile: {} (legitimacy score: {})", 
            walletProfile.getProfile(), hftAnalysis.getLegitimacyScore());
        log.debug("Threshold adjustments: {}", 
            dynamicThresholdService.getThresholdExplanation(walletProfile, thresholds));
        
        // 1. Check total signatures limit
        if (totalSignatures > MAX_TOTAL_SIGNATURES) {
            failures.add(String.format(
                "Excessive activity: %d signatures > %d (likely bot/spam wallet)",
                totalSignatures, MAX_TOTAL_SIGNATURES
            ));
            diagnostics.put("totalSignatures", totalSignatures);
        }
        
        // 2. Enhanced bot detection with dynamic thresholds
        BotDetectionResult botResult = detectBotEnhanced(rows, metrics, thresholds, hftAnalysis);
        if (!botResult.isHuman()) {
            failures.addAll(botResult.reasons());
            diagnostics.putAll(botResult.metrics());
        }
        
        // 3. Enhanced wash trading detection
        WashTradingResult washResult = detectWashTradingEnhanced(rows, thresholds);
        if (washResult.isSuspicious()) {
            failures.addAll(washResult.reasons());
            diagnostics.putAll(washResult.metrics());
        }
        
        // 4. Account age validation (unchanged)
        AccountAgeResult ageResult = validateAccountAge(rows);
        if (!ageResult.meetsRequirements()) {
            failures.addAll(ageResult.reasons());
            diagnostics.putAll(ageResult.metrics());
        }
        
        // 5. Enhanced MEV detection with HFT exemptions
        MevDetectionService.MevAnalysis mevResult = analyzeMevWithHftExemptions(rows, thresholds, hftAnalysis);
        if (mevResult.isMevBot()) {
            failures.add(String.format(
                "MEV bot detected (score: %.1f/100): %s",
                mevResult.mevScore(),
                String.join(", ", mevResult.mevPatterns())
            ));
            diagnostics.putAll(mevResult.diagnostics());
        }
        
        // 6. Enhanced priority fee analysis
        if (transactions != null && !transactions.isEmpty()) {
            PriorityFeeAnalysisService.PriorityFeeAnalysis feeResult = 
                priorityFeeService.analyzePriorityFees(transactions);
            
            // Apply HFT-aware thresholds for priority fee analysis
            boolean isSuspicious = walletProfile.getProfile() == DynamicThresholdService.TraderProfile.LEGITIMATE_HFT 
                ? feeResult.avgFee() > 100000 // More lenient for legitimate HFT (100k lamports)
                : feeResult.likelyMevBot(); // Standard check for others
            
            if (isSuspicious) {
                failures.add(String.format(
                    "MEV infrastructure detected: %s",
                    String.join(", ", feeResult.patterns())
                ));
                diagnostics.put("avgPriorityFee", feeResult.avgFee());
                diagnostics.put("maxPriorityFee", feeResult.maxFee());
                diagnostics.put("elevatedFeePercentage", String.format("%.1f%%", feeResult.percentageElevatedFees()));
            } else if (feeResult.hasElevatedFees()) {
                log.debug("Elevated priority fees detected but within thresholds: avg {} lamports ({}% of txs)", 
                    feeResult.avgFee(), 
                    String.format("%.1f", feeResult.percentageElevatedFees()));
                diagnostics.put("priorityFeeWarning", String.format("%.1f%% elevated fees", feeResult.percentageElevatedFees()));
            }
        }
        
        boolean passed = failures.isEmpty();
        
        // Add HFT analysis to diagnostics
        diagnostics.put("hftClassification", hftAnalysis.getClassification());
        diagnostics.put("legitimacyScore", hftAnalysis.getLegitimacyScore());
        diagnostics.put("legitimacyIndicators", hftAnalysis.getLegitimacyIndicators());
        diagnostics.put("botIndicators", hftAnalysis.getBotIndicators());
        diagnostics.put("traderProfile", walletProfile.getProfile());
        diagnostics.putAll(hftAnalysis.getDiagnostics());
        
        if (!passed) {
            log.debug("Wallet validation failed: {}", String.join("; ", failures));
        } else {
            log.debug("Wallet validation passed with profile: {} (legitimacy: {})", 
                walletProfile.getProfile(), hftAnalysis.getLegitimacyScore());
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
     * Enhanced bot detection with dynamic thresholds and HFT-awareness
     */
    private BotDetectionResult detectBotEnhanced(
            List<NormalizeTransaction.Row> rows,
            WalletMetricsCalculator.WalletMetrics metrics,
            DynamicThresholdService.ValidationThresholds thresholds,
            LegitimateHftDetectionService.HftAnalysisResult hftAnalysis) {
        
        List<String> reasons = new ArrayList<>();
        Map<String, Object> botMetrics = new HashMap<>();
        int botScore = 0; // Composite score: 0-100
        
        if (rows.isEmpty()) {
            return new BotDetectionResult(true, List.of(), Map.of());
        }
        
        // If already classified as legitimate HFT, use higher thresholds
        boolean isLegitimateHft = hftAnalysis.getClassification() == 
            LegitimateHftDetectionService.HftClassification.LEGITIMATE_HFT;
        
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
        botMetrics.put("isLegitimateHft", isLegitimateHft);
        
        // Scoring Pattern 1: Trade frequency (max 40 points)
        if (tradesPerDay > thresholds.getMaxTradesPerDay() * 4) {
            botScore += 40;
            reasons.add(String.format("Extreme trade frequency: %.1f trades/day (threshold: %.1f)", 
                tradesPerDay, thresholds.getMaxTradesPerDay()));
        } else if (tradesPerDay > thresholds.getMaxTradesPerDay() * 2) {
            botScore += 30;
            reasons.add(String.format("Very high trade frequency: %.1f trades/day", tradesPerDay));
        } else if (tradesPerDay > thresholds.getMaxTradesPerDay()) {
            botScore += isLegitimateHft ? 10 : 20; // More lenient for legitimate HFT
            reasons.add(String.format("High trade frequency: %.1f trades/day", tradesPerDay));
        }
        
        // Scoring Pattern 2: Holding time (max 30 points)
        Double avgHoldingHours = metrics.avgHoldingHoursWeighted();
        if (avgHoldingHours != null) {
            if (avgHoldingHours < thresholds.getMinAvgHoldingHours() / 10) { // Very short
                botScore += 30;
                reasons.add(String.format("Ultra-short holding: %.2f hours avg", avgHoldingHours));
            } else if (avgHoldingHours < thresholds.getMinAvgHoldingHours()) {
                botScore += isLegitimateHft ? 5 : 15; // More lenient for legitimate HFT
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
            } else if (winRate > thresholds.getSuspiciousWinRate()) {
                botScore += isLegitimateHft ? 5 : 10; // More lenient for legitimate HFT
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
            .filter(count -> count > thresholds.getMaxSameBlockTrades())
            .count();
        
        double sameBlockPercentage = rows.size() > 0 ? (blocksWithManyTrades * 100.0 / rows.size()) : 0;
        
        if (sameBlockPercentage > 20) {
            botScore += isLegitimateHft ? 15 : 30; // More lenient for legitimate HFT
            reasons.add(String.format("Frequent same-block trading: %.1f%% of trades", sameBlockPercentage));
        } else if (blocksWithManyTrades > 0) {
            botScore += isLegitimateHft ? 0 : 10; // Ignore for legitimate HFT
            if (!isLegitimateHft) {
                reasons.add(String.format("Some same-block trading: %d blocks", blocksWithManyTrades));
            }
        }
        botMetrics.put("blocksWithManyTrades", blocksWithManyTrades);
        
        // Scoring Pattern 5: Identical sizing ACROSS MULTIPLE TOKENS (max 15 points)
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
            
            if (maxIdenticalCount >= 20 && identicalPercentage > thresholds.getIdenticalSizingThreshold()) {
                tokensWithIdenticalSizing++;
            }
        }
        
        if (tokensWithIdenticalSizing >= 10) {
            botScore += isLegitimateHft ? 5 : 15; // More lenient for legitimate HFT
            reasons.add(String.format("Identical sizing across %d tokens", tokensWithIdenticalSizing));
            botMetrics.put("tokensWithIdenticalSizing", tokensWithIdenticalSizing);
        } else if (tokensWithIdenticalSizing >= MIN_TOKENS_WITH_IDENTICAL_SIZING) {
            botScore += isLegitimateHft ? 0 : 5; // Ignore for legitimate HFT
            if (!isLegitimateHft) {
                reasons.add(String.format("Consistent sizing across %d tokens", tokensWithIdenticalSizing));
            }
            botMetrics.put("tokensWithIdenticalSizing", tokensWithIdenticalSizing);
        }
        
        // Apply HFT legitimacy score adjustments
        if (isLegitimateHft) {
            double legitimacyAdjustment = (hftAnalysis.getLegitimacyScore() - 50) / 5; // -10 to +10 points
            botScore -= (int) legitimacyAdjustment;
            botMetrics.put("legitimacyAdjustment", legitimacyAdjustment);
        }
        
        botMetrics.put("botScore", botScore);
        botMetrics.put("threshold", thresholds.getBotDetectionThreshold());
        
        // Final determination: Use dynamic threshold
        boolean isHuman = botScore < thresholds.getBotDetectionThreshold();
        
        if (!isHuman) {
            reasons.add(0, String.format("Bot score: %d/100 (threshold: %.1f)", 
                botScore, thresholds.getBotDetectionThreshold()));
        }
        
        return new BotDetectionResult(isHuman, reasons, botMetrics);
    }
    
    /**
     * Enhanced wash trading detection with dynamic thresholds
     */
    private WashTradingResult detectWashTradingEnhanced(
            List<NormalizeTransaction.Row> rows,
            DynamicThresholdService.ValidationThresholds thresholds) {
        
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
            
            // Check for round-trip trades with dynamic thresholds
            for (int i = 0; i < tokenRows.size() - 1; i++) {
                NormalizeTransaction.Row current = tokenRows.get(i);
                NormalizeTransaction.Row next = tokenRows.get(i + 1);
                
                if (current.side() == NormalizeTransaction.Side.BUY && 
                    next.side() == NormalizeTransaction.Side.SELL) {
                    
                    long timeDiff = next.blockTime() - current.blockTime();
                    if (timeDiff < thresholds.getMaxRoundTripSeconds()) {
                        totalRoundTrips++;
                        
                        // Check if same block
                        if (current.blockSlot() != null && current.blockSlot().equals(next.blockSlot())) {
                            sameBlockRoundTrips++;
                        }
                    }
                }
            }
        }
        
        washMetrics.put("totalRoundTrips", totalRoundTrips);
        washMetrics.put("sameBlockRoundTrips", sameBlockRoundTrips);
        washMetrics.put("thresholdRoundTrips", thresholds.getMaxRoundTrips());
        washMetrics.put("thresholdSameBlockRoundTrips", thresholds.getMaxSameBlockRoundTrips());
        
        boolean isSuspicious = false;
        
        if (totalRoundTrips > thresholds.getMaxRoundTrips()) {
            isSuspicious = true;
            reasons.add(String.format("Excessive round-trip trades: %d (threshold: %d)", 
                totalRoundTrips, thresholds.getMaxRoundTrips()));
        }
        
        if (sameBlockRoundTrips > thresholds.getMaxSameBlockRoundTrips()) {
            isSuspicious = true;
            reasons.add(String.format("Same-block round-trips: %d (threshold: %d)", 
                sameBlockRoundTrips, thresholds.getMaxSameBlockRoundTrips()));
        }
        
        return new WashTradingResult(isSuspicious, reasons, washMetrics);
    }
    
    /**
     * Enhanced MEV detection with HFT exemptions
     */
    private MevDetectionService.MevAnalysis analyzeMevWithHftExemptions(
            List<NormalizeTransaction.Row> rows,
            DynamicThresholdService.ValidationThresholds thresholds,
            LegitimateHftDetectionService.HftAnalysisResult hftAnalysis) {
        
        // Get standard MEV analysis
        MevDetectionService.MevAnalysis standardResult = mevDetectionService.analyzeMevActivity(rows);
        
        // Apply HFT exemptions if legitimate HFT detected
        if (hftAnalysis.getClassification() == LegitimateHftDetectionService.HftClassification.LEGITIMATE_HFT) {
            return applyHftExemptions(standardResult, thresholds, hftAnalysis);
        }
        
        return standardResult;
    }
    
    private MevDetectionService.MevAnalysis applyHftExemptions(
            MevDetectionService.MevAnalysis result,
            DynamicThresholdService.ValidationThresholds thresholds,
            LegitimateHftDetectionService.HftAnalysisResult hftAnalysis) {
        
        // Create a modified analysis with higher thresholds for legitimate HFT
        boolean isMevBot = false;
        List<String> patterns = new ArrayList<>();
        Map<String, Object> diagnostics = new HashMap<>(result.diagnostics());
        
        // Apply higher thresholds for MEV detection
        double adjustedMevScore = result.mevScore() * (hftAnalysis.getLegitimacyScore() / 100.0);
        
        if (adjustedMevScore > thresholds.getMevDetectionThreshold()) {
            isMevBot = true;
            patterns.addAll(result.mevPatterns());
            patterns.add("MEV score adjusted for legitimate HFT");
        }
        
        diagnostics.put("originalMevScore", result.mevScore());
        diagnostics.put("adjustedMevScore", adjustedMevScore);
        diagnostics.put("hftLegitimacyScore", hftAnalysis.getLegitimacyScore());
        
        return new MevDetectionService.MevAnalysis(
            isMevBot,
            patterns,
            adjustedMevScore,
            diagnostics
        );
    }
    
    /**
     * Original bot detection (keeping for backward compatibility)
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
