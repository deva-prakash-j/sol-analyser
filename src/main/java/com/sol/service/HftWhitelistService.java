package com.sol.service;

import com.sol.util.NormalizeTransaction;
import com.sol.util.WalletMetricsCalculator;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Whitelist service for known legitimate HFT patterns and infrastructure
 * Helps reduce false positives by identifying known good actors
 */
@Service
@RequiredArgsConstructor
public class HftWhitelistService {
    private static final Logger log = LoggerFactory.getLogger(HftWhitelistService.class);
    
    // Known legitimate HFT program IDs (can be configured externally)
    private static final Set<String> KNOWN_HFT_PROGRAMS = Set.of(
        // Add known legitimate HFT program IDs here
        "legitimate_hft_program_1",
        "institutional_trading_program_2",
        "market_maker_program_3"
    );
    
    // Known legitimate wallet patterns (can be machine learned over time)
    private static final Set<String> WHITELISTED_WALLET_PATTERNS = Set.of(
        // Patterns identified through manual review and feedback
    );
    
    @Builder
    @Data
    public static class WhitelistAnalysis {
        private boolean isWhitelisted;
        private List<String> whitelistReasons;
        private WhitelistCategory category;
        private double confidenceScore; // 0-100
        private Map<String, Object> diagnostics;
    }
    
    public enum WhitelistCategory {
        KNOWN_INFRASTRUCTURE,
        INSTITUTIONAL_PATTERNS,
        REGULATORY_COMPLIANCE,
        MANUAL_REVIEW_APPROVED,
        MACHINE_LEARNING_APPROVED,
        NOT_WHITELISTED
    }
    
    /**
     * Check if a wallet should be whitelisted based on various criteria
     */
    public WhitelistAnalysis analyzeForWhitelist(
            String walletAddress,
            List<NormalizeTransaction.Row> transactions,
            WalletMetricsCalculator.WalletMetrics metrics,
            LegitimateHftDetectionService.HftAnalysisResult hftAnalysis) {
        
        List<String> whitelistReasons = new ArrayList<>();
        Map<String, Object> diagnostics = new HashMap<>();
        double confidenceScore = 0.0;
        WhitelistCategory category = WhitelistCategory.NOT_WHITELISTED;
        
        // Check 1: Known HFT infrastructure
        if (usesKnownHftInfrastructure(transactions)) {
            whitelistReasons.add("Uses known legitimate HFT infrastructure");
            confidenceScore += 40.0;
            category = WhitelistCategory.KNOWN_INFRASTRUCTURE;
        }
        
        // Check 2: Institutional-grade risk management
        if (hasInstitutionalRiskManagement(metrics)) {
            whitelistReasons.add("Demonstrates institutional-grade risk management");
            confidenceScore += 25.0;
            if (category == WhitelistCategory.NOT_WHITELISTED) {
                category = WhitelistCategory.INSTITUTIONAL_PATTERNS;
            }
        }
        
        // Check 3: Regulatory compliance patterns
        if (showsRegulatoryCompliance(transactions, metrics)) {
            whitelistReasons.add("Shows regulatory compliance patterns");
            confidenceScore += 20.0;
            if (category == WhitelistCategory.NOT_WHITELISTED) {
                category = WhitelistCategory.REGULATORY_COMPLIANCE;
            }
        }
        
        // Check 4: Professional trading characteristics
        if (hasProfessionalTradingCharacteristics(hftAnalysis, metrics)) {
            whitelistReasons.add("Exhibits professional trading characteristics");
            confidenceScore += 15.0;
            if (category == WhitelistCategory.NOT_WHITELISTED) {
                category = WhitelistCategory.INSTITUTIONAL_PATTERNS;
            }
        }
        
        // Check 5: Manual review approved patterns
        if (WHITELISTED_WALLET_PATTERNS.contains(walletAddress)) {
            whitelistReasons.add("Previously approved through manual review");
            confidenceScore += 50.0;
            category = WhitelistCategory.MANUAL_REVIEW_APPROVED;
        }
        
        // Check 6: Consistent long-term performance
        if (hasConsistentLongTermPerformance(metrics)) {
            whitelistReasons.add("Demonstrates consistent long-term performance");
            confidenceScore += 10.0;
        }
        
        // Check 7: Advanced risk metrics
        if (hasAdvancedRiskMetrics(metrics)) {
            whitelistReasons.add("Uses advanced risk management techniques");
            confidenceScore += 10.0;
        }
        
        boolean isWhitelisted = confidenceScore >= 70.0 && !whitelistReasons.isEmpty();
        
        diagnostics.put("confidenceScore", confidenceScore);
        diagnostics.put("walletAddress", walletAddress);
        diagnostics.put("totalTransactions", transactions.size());
        
        if (isWhitelisted) {
            log.info("Wallet {} whitelisted with confidence {}: {}", 
                walletAddress, confidenceScore, String.join(", ", whitelistReasons));
        }
        
        return WhitelistAnalysis.builder()
            .isWhitelisted(isWhitelisted)
            .whitelistReasons(whitelistReasons)
            .category(category)
            .confidenceScore(confidenceScore)
            .diagnostics(diagnostics)
            .build();
    }
    
    private boolean usesKnownHftInfrastructure(List<NormalizeTransaction.Row> transactions) {
        // Check for known HFT program signatures in transactions
        // This would require analyzing transaction instruction data
        
        // For now, check for patterns that suggest professional infrastructure:
        // 1. Consistent low-latency execution
        // 2. Use of specific DEX programs known to be used by professionals
        // 3. Transaction patterns that match known market makers
        
        return checkConsistentLowLatency(transactions) ||
               checkProfessionalDexUsage(transactions) ||
               checkMarketMakerPatterns(transactions);
    }
    
    private boolean checkConsistentLowLatency(List<NormalizeTransaction.Row> transactions) {
        // Check for consistently low and uniform latency patterns
        // Professional HFT systems have very consistent timing
        
        List<Long> blockSlots = transactions.stream()
            .map(NormalizeTransaction.Row::blockSlot)
            .filter(Objects::nonNull)
            .sorted()
            .collect(Collectors.toList());
        
        if (blockSlots.size() < 10) return false;
        
        // Check for patterns where many trades happen in consecutive blocks
        int consecutiveBlockTrades = 0;
        for (int i = 1; i < blockSlots.size(); i++) {
            if (blockSlots.get(i) - blockSlots.get(i-1) <= 2) { // Within 2 blocks
                consecutiveBlockTrades++;
            }
        }
        
        // Professional systems often trade in very close block proximity
        return consecutiveBlockTrades > blockSlots.size() * 0.7; // 70% of trades in consecutive blocks
    }
    
    private boolean checkProfessionalDexUsage(List<NormalizeTransaction.Row> transactions) {
        // Check if predominantly uses professional-grade DEXs
        // Professional traders often use specific DEXs that offer better execution
        
        // Since Row doesn't have dex() method, we'll use a simplified approach
        // In a real implementation, this would require additional transaction data
        
        // For now, assume professional usage if trading frequency suggests it
        return transactions.size() > 100; // Simplified check
    }
    
    private boolean checkMarketMakerPatterns(List<NormalizeTransaction.Row> transactions) {
        // Check for market maker characteristics:
        // 1. Provides liquidity on both sides
        // 2. Small spreads
        // 3. High frequency but small position sizes relative to volume
        
        Map<String, List<NormalizeTransaction.Row>> byToken = transactions.stream()
            .collect(Collectors.groupingBy(NormalizeTransaction.Row::baseMint));
        
        int tokensWithBothSides = 0;
        for (List<NormalizeTransaction.Row> tokenTrades : byToken.values()) {
            boolean hasBuys = tokenTrades.stream().anyMatch(t -> t.side() == NormalizeTransaction.Side.BUY);
            boolean hasSells = tokenTrades.stream().anyMatch(t -> t.side() == NormalizeTransaction.Side.SELL);
            
            if (hasBuys && hasSells) {
                tokensWithBothSides++;
            }
        }
        
        // Market makers trade both sides of most tokens
        return tokensWithBothSides > byToken.size() * 0.8; // 80% of tokens have both buy/sell
    }
    
    private boolean hasInstitutionalRiskManagement(WalletMetricsCalculator.WalletMetrics metrics) {
        // Institutional characteristics:
        // 1. Excellent Sharpe ratio
        // 2. Controlled maximum drawdown
        // 3. Consistent performance across timeframes
        // 4. High Calmar ratio
        
        double sharpeRatio = metrics.sharpeLikeDaily(); // Using sharpeLikeDaily instead of sharpeRatio
        BigDecimal calmarRatio = metrics.calmarRatio();
        BigDecimal maxDrawdown = metrics.maxDrawdownUsd(); // Using maxDrawdownUsd instead of maxDrawdownPercentage
        double winRate = metrics.tradeWinRate();
        
        return sharpeRatio > 2.0 && // Excellent risk-adjusted returns
               calmarRatio != null && calmarRatio.compareTo(BigDecimal.valueOf(1.5)) > 0 && // Good drawdown-adjusted returns
               maxDrawdown != null && maxDrawdown.compareTo(BigDecimal.valueOf(200_000)) < 0 && // Controlled drawdown <$200K
               winRate > 60.0 && winRate < 90.0; // Realistic win rate
    }
    
    private boolean showsRegulatoryCompliance(List<NormalizeTransaction.Row> transactions, 
                                            WalletMetricsCalculator.WalletMetrics metrics) {
        // Regulatory compliance indicators:
        // 1. Reasonable trading hours (not 24/7 bot-like)
        // 2. Position size limits
        // 3. No wash trading patterns
        // 4. Proper risk controls
        
        return hasReasonableTradingHours(transactions) &&
               hasPositionSizeLimits(transactions) &&
               hasProperRiskControls(metrics);
    }
    
    private boolean hasReasonableTradingHours(List<NormalizeTransaction.Row> transactions) {
        // Check for human-like trading hours
        Map<Integer, Integer> hourlyActivity = new HashMap<>();
        
        for (var transaction : transactions) {
            if (transaction.blockTime() != null) {
                int hour = (int) ((transaction.blockTime() / 3600) % 24);
                hourlyActivity.merge(hour, 1, Integer::sum);
            }
        }
        
        if (hourlyActivity.isEmpty()) return false;
        
        // Check for reduced activity during typical rest hours (e.g., 2-6 AM UTC)
        int restHourActivity = hourlyActivity.getOrDefault(2, 0) + 
                              hourlyActivity.getOrDefault(3, 0) + 
                              hourlyActivity.getOrDefault(4, 0) + 
                              hourlyActivity.getOrDefault(5, 0);
        
        int totalActivity = hourlyActivity.values().stream().mapToInt(Integer::intValue).sum();
        
        // Rest hours should have <20% of total activity
        return totalActivity > 0 && (double) restHourActivity / totalActivity < 0.2;
    }
    
    private boolean hasPositionSizeLimits(List<NormalizeTransaction.Row> transactions) {
        // Check for reasonable position size distribution
        List<Double> positionSizes = transactions.stream()
            .map(t -> t.notionalUsd().doubleValue())
            .sorted()
            .collect(Collectors.toList());
        
        if (positionSizes.size() < 10) return false;
        
        // Check that position sizes are not all identical (sign of limits/controls)
        double p25 = positionSizes.get(positionSizes.size() / 4);
        double p75 = positionSizes.get(3 * positionSizes.size() / 4);
        double p95 = positionSizes.get(19 * positionSizes.size() / 20);
        
        // Good distribution with reasonable max size limits
        return p75 / p25 > 1.5 && p95 / p75 < 5.0; // Varied sizes but not extreme outliers
    }
    
    private boolean hasProperRiskControls(WalletMetricsCalculator.WalletMetrics metrics) {
        // Risk control indicators
        BigDecimal maxDrawdown = metrics.maxDrawdownUsd();
        int longestDrawdownDays = metrics.longestDrawdownDays(); // Using longestDrawdownDays instead of avgDrawdownDays
        int maxConsecutiveLosses = metrics.maxConsecutiveLosses();
        
        return maxDrawdown != null && maxDrawdown.compareTo(BigDecimal.valueOf(250_000)) < 0 && // Controlled maximum drawdown <$250K
               longestDrawdownDays < 30 && // Quick recovery <30 days
               maxConsecutiveLosses < 8; // Limited consecutive losses
    }
    
    private boolean hasProfessionalTradingCharacteristics(
            LegitimateHftDetectionService.HftAnalysisResult hftAnalysis,
            WalletMetricsCalculator.WalletMetrics metrics) {
        
        // Professional characteristics:
        // 1. High legitimacy score from HFT analysis
        // 2. Multiple legitimacy indicators
        // 3. Good diversification
        // 4. Consistent performance
        
        boolean highLegitimacyScore = hftAnalysis.getLegitimacyScore() > 75.0;
        boolean multipleLegitimacyIndicators = hftAnalysis.getLegitimacyIndicators().size() >= 4;
        boolean fewBotIndicators = hftAnalysis.getBotIndicators().size() <= 1;
        
        // Use totalTrades as a proxy for diversification since uniqueTokensTraded doesn't exist
        int totalTrades = metrics.totalTrades();
        boolean goodDiversification = totalTrades >= 50; // Assuming diversification if many trades
        
        return highLegitimacyScore && multipleLegitimacyIndicators && fewBotIndicators && goodDiversification;
    }
    
    private boolean hasConsistentLongTermPerformance(WalletMetricsCalculator.WalletMetrics metrics) {
        // Long-term consistency indicators
        BigDecimal pnlPct = metrics.pnlPct(); // Using pnlPct instead of totalReturnPercentage
        double sharpeRatio = metrics.sharpeLikeDaily(); // Using sharpeLikeDaily instead of sharpeRatio
        BigDecimal profitFactor = metrics.profitFactor();
        
        return pnlPct != null && pnlPct.compareTo(BigDecimal.valueOf(0.2)) > 0 && // >20% returns
               sharpeRatio > 1.0 && // Good risk-adjusted returns
               profitFactor != null && profitFactor.compareTo(BigDecimal.valueOf(1.5)) > 0; // Profitable trading
    }
    
    private boolean hasAdvancedRiskMetrics(WalletMetricsCalculator.WalletMetrics metrics) {
        // Advanced risk management indicators
        BigDecimal calmarRatio = metrics.calmarRatio();
        BigDecimal recoveryFactor = metrics.recoveryFactor();
        double volatility = metrics.stdDevDailyReturns(); // Using stdDevDailyReturns as proxy for volatility
        
        return calmarRatio != null && calmarRatio.compareTo(BigDecimal.valueOf(1.0)) > 0 && // Good drawdown management
               recoveryFactor != null && recoveryFactor.compareTo(BigDecimal.valueOf(2.0)) > 0 && // Good recovery ability
               volatility < 0.5; // Controlled volatility (daily std dev)
    }
    
    /**
     * Add a wallet to the whitelist (for manual approval process)
     */
    public void addToWhitelist(String walletAddress, String reason) {
        // In a real implementation, this would persist to database
        log.info("Adding wallet {} to whitelist: {}", walletAddress, reason);
        // WHITELISTED_WALLET_PATTERNS.add(walletAddress); // Would need to be mutable
    }
    
    /**
     * Remove a wallet from the whitelist
     */
    public void removeFromWhitelist(String walletAddress, String reason) {
        // In a real implementation, this would remove from database
        log.info("Removing wallet {} from whitelist: {}", walletAddress, reason);
        // WHITELISTED_WALLET_PATTERNS.remove(walletAddress); // Would need to be mutable
    }
    
    /**
     * Get whitelist statistics for monitoring
     */
    public Map<String, Object> getWhitelistStatistics() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("knownHftPrograms", KNOWN_HFT_PROGRAMS.size());
        stats.put("whitelistedWallets", WHITELISTED_WALLET_PATTERNS.size());
        return stats;
    }
}