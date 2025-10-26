package com.sol.service;

import com.sol.util.WalletMetricsCalculator.WalletMetrics;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * 3-Stage Wallet Scoring Engine for Copy Trading
 * 
 * Stage 1: Hard Filters (90% rejection) - Eliminates wallets that don't meet minimum standards
 * Stage 2: Composite Scoring (0-100) - Multi-factor weighted scoring system
 * Stage 3: Confidence Tiers (S+ to F) - Classification for copy trading decisions
 * 
 * Target: Identify wallets with 80%+ win rate potential for copy trading
 */
@Service
@RequiredArgsConstructor
public class WalletScoringEngine {
    private static final Logger log = LoggerFactory.getLogger(WalletScoringEngine.class);
    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final int SCALE = 4;
    
    private final WalletValidationService validationService;

    /**
     * Confidence tier classification for copy trading
     */
    public enum ConfidenceTier {
        S_PLUS("S+", "Elite Alpha - Immediate copy", 90, 100),
        S("S", "Strong Alpha - High confidence copy", 80, 89),
        A("A", "Good Alpha - Moderate confidence copy", 70, 79),
        B("B", "Acceptable - Low confidence copy", 60, 69),
        C("C", "Marginal - Monitor only", 50, 59),
        D("D", "Weak - Do not copy", 40, 49),
        F("F", "Failed - Avoid", 0, 39);

        public final String label;
        public final String description;
        public final int minScore;
        public final int maxScore;

        ConfidenceTier(String label, String description, int minScore, int maxScore) {
            this.label = label;
            this.description = description;
            this.minScore = minScore;
            this.maxScore = maxScore;
        }

        public static ConfidenceTier fromScore(int score) {
            for (ConfidenceTier tier : values()) {
                if (score >= tier.minScore && score <= tier.maxScore) {
                    return tier;
                }
            }
            return F; // default to lowest tier
        }
    }

    /**
     * Comprehensive scoring result with detailed breakdown
     */
    public record ScoringResult(
            String walletAddress,
            boolean passedHardFilters,
            List<String> failedFilters,
            int compositeScore,
            ConfidenceTier tier,
            Map<String, Double> categoryScores,
            Map<String, String> redFlags,
            WalletMetrics metrics,
            String recommendation
    ) {
        public boolean isCopyTradingCandidate() {
            return passedHardFilters && compositeScore >= 60; // B tier or above
        }

        public boolean isEliteCandidate() {
            return passedHardFilters && tier == ConfidenceTier.S_PLUS;
        }
    }

    /**
     * Main scoring entry point with advanced validation (including priority fee analysis)
     */
    public ScoringResult scoreWallet(String walletAddress, WalletMetrics metrics, 
                                     List<com.sol.util.NormalizeTransaction.Row> rows,
                                     List<com.sol.dto.Transaction> transactions,
                                     int totalSignatures) {
        if (metrics == null) {
            return createFailedResult(walletAddress, null, List.of("Null metrics"));
        }

        try {
            // Stage 0: Advanced Validation (bot detection, wash trading, account age, signature limit, MEV, priority fees)
            WalletValidationService.ValidationResult validation = 
                validationService.validateWallet(rows, transactions, totalSignatures, metrics);
            
            if (!validation.passed()) {
                return createFailedResult(walletAddress, metrics, validation.failures());
            }
            
            // Stage 1: Hard Filters
            List<String> failedFilters = applyHardFilters(metrics);
            if (!failedFilters.isEmpty()) {
                return createFailedResult(walletAddress, metrics, failedFilters);
            }

            // Stage 2: Composite Scoring
            Map<String, Double> categoryScores = calculateCategoryScores(metrics);
            int compositeScore = calculateCompositeScore(categoryScores);

            // Stage 3: Confidence Tier
            ConfidenceTier tier = ConfidenceTier.fromScore(compositeScore);

            // Red Flag Analysis
            Map<String, String> redFlags = detectRedFlags(metrics);

            // Generate Recommendation
            String recommendation = generateRecommendation(tier, redFlags, metrics);

            return new ScoringResult(
                    walletAddress,
                    true,
                    List.of(),
                    compositeScore,
                    tier,
                    categoryScores,
                    redFlags,
                    metrics,
                    recommendation
            );

        } catch (Exception e) {
            log.error("Error scoring wallet {}: {}", walletAddress, e.getMessage(), e);
            return createFailedResult(walletAddress, metrics, 
                List.of("Scoring error: " + e.getMessage()));
        }
    }
    
    /**
     * Backward compatible scoring entry point (without priority fee analysis)
     */
    public ScoringResult scoreWallet(String walletAddress, WalletMetrics metrics, 
                                     List<com.sol.util.NormalizeTransaction.Row> rows,
                                     int totalSignatures) {
        return scoreWallet(walletAddress, metrics, rows, null, totalSignatures);
    }
    
    /**
     * Main scoring entry point (without advanced validation)
     * Use this when rows are not available
     */
    public ScoringResult scoreWallet(String walletAddress, WalletMetrics metrics) {
        if (metrics == null) {
            return createFailedResult(walletAddress, null, List.of("Null metrics"));
        }

        try {
            // Stage 1: Hard Filters
            List<String> failedFilters = applyHardFilters(metrics);
            if (!failedFilters.isEmpty()) {
                return createFailedResult(walletAddress, metrics, failedFilters);
            }

            // Stage 2: Composite Scoring
            Map<String, Double> categoryScores = calculateCategoryScores(metrics);
            int compositeScore = calculateCompositeScore(categoryScores);

            // Stage 3: Confidence Tier
            ConfidenceTier tier = ConfidenceTier.fromScore(compositeScore);

            // Red Flag Analysis
            Map<String, String> redFlags = detectRedFlags(metrics);

            // Generate Recommendation
            String recommendation = generateRecommendation(tier, redFlags, metrics);

            return new ScoringResult(
                    walletAddress,
                    true,
                    List.of(),
                    compositeScore,
                    tier,
                    categoryScores,
                    redFlags,
                    metrics,
                    recommendation
            );

        } catch (Exception e) {
            log.error("Error scoring wallet {}: {}", walletAddress, e.getMessage(), e);
            return createFailedResult(walletAddress, metrics, List.of("Scoring error: " + e.getMessage()));
        }
    }

    // ==================== STAGE 1: HARD FILTERS ====================

    /**
     * Hard filters eliminate 90% of wallets that don't meet minimum standards
     * These are non-negotiable requirements for copy trading consideration
     */
    private List<String> applyHardFilters(WalletMetrics m) {
        List<String> failed = new ArrayList<>();

        // Minimum sample size (avoid statistical noise)
        if (m.totalTrades() < 50) {
            failed.add("Insufficient trades: " + m.totalTrades() + " (min: 50)");
        }
        if (m.daysCount() < 14) {
            failed.add("Insufficient history: " + m.daysCount() + " days (min: 14)");
        }

        // Profitability requirements
        if (m.totalRealizedUsd().compareTo(ZERO) <= 0) {
            failed.add("Not profitable: $" + m.totalRealizedUsd());
        }
        if (m.profitFactor().compareTo(BigDecimal.valueOf(1.2)) < 0) {
            failed.add("Low profit factor: " + m.profitFactor() + " (min: 1.2)");
        }

        // Win rate minimums (critical for copy trading)
        if (m.tradeWinRate() < 45.0) {
            failed.add("Low trade win rate: " + String.format("%.1f%%", m.tradeWinRate()) + " (min: 45%)");
        }
        if (m.dailyWinRatePct() < 40.0) {
            failed.add("Low daily win rate: " + String.format("%.1f%%", m.dailyWinRatePct()) + " (min: 40%)");
        }

        // Risk management checks
        if (m.maxDrawdownUsd().compareTo(m.totalRealizedUsd().abs()) > 0) {
            failed.add("Drawdown exceeds profit (poor risk management)");
        }
        if (m.maxConsecutiveLosses() > 15) {
            failed.add("Excessive losing streak: " + m.maxConsecutiveLosses() + " (max: 15)");
        }

        // Volume requirements (avoid low-liquidity traders)
        if (m.totalVolumeUsd().compareTo(BigDecimal.valueOf(10000)) < 0) {
            failed.add("Insufficient volume: $" + m.totalVolumeUsd() + " (min: $10,000)");
        }

        // Recent activity check (ensure wallet is still active)
        if (m.last30DaysTrades() < 5) {
            failed.add("Inactive wallet: " + m.last30DaysTrades() + " trades in last 30 days (min: 5)");
        }
        if (m.last30DaysPnl().compareTo(m.totalRealizedUsd().multiply(BigDecimal.valueOf(-0.5))) < 0) {
            failed.add("Severe recent decline (lost >50% of total profit in last 30 days)");
        }

        return failed;
    }

    // ==================== STAGE 2: COMPOSITE SCORING ====================

    /**
     * Calculate weighted scores across 6 major categories
     * Each category contributes to the final 0-100 composite score
     */
    private Map<String, Double> calculateCategoryScores(WalletMetrics m) {
        Map<String, Double> scores = new LinkedHashMap<>();

        scores.put("profitability", scoreProfitability(m));      // 25% weight
        scores.put("consistency", scoreConsistency(m));          // 20% weight
        scores.put("riskManagement", scoreRiskManagement(m));    // 20% weight
        scores.put("recentPerformance", scoreRecentPerformance(m)); // 15% weight
        scores.put("tradeExecution", scoreTradeExecution(m));    // 10% weight
        scores.put("activityLevel", scoreActivityLevel(m));      // 10% weight

        return scores;
    }

    /**
     * Profitability Score (0-100) - 25% weight
     * Focus: Absolute and relative returns
     */
    private double scoreProfitability(WalletMetrics m) {
        double score = 0.0;

        // Profit Factor (0-30 points)
        BigDecimal pf = m.profitFactor();
        if (pf.compareTo(BigDecimal.valueOf(3.0)) >= 0) score += 30;
        else if (pf.compareTo(BigDecimal.valueOf(2.0)) >= 0) score += 25;
        else if (pf.compareTo(BigDecimal.valueOf(1.5)) >= 0) score += 20;
        else if (pf.compareTo(BigDecimal.valueOf(1.2)) >= 0) score += 15;
        else score += 10;

        // PnL % (0-25 points)
        BigDecimal pnlPct = m.pnlPct().multiply(BigDecimal.valueOf(100));
        if (pnlPct.compareTo(BigDecimal.valueOf(20)) >= 0) score += 25;
        else if (pnlPct.compareTo(BigDecimal.valueOf(10)) >= 0) score += 20;
        else if (pnlPct.compareTo(BigDecimal.valueOf(5)) >= 0) score += 15;
        else if (pnlPct.compareTo(BigDecimal.valueOf(2)) >= 0) score += 10;
        else score += 5;

        // Absolute Profit (0-15 points) - scaled logarithmically
        BigDecimal totalPnl = m.totalRealizedUsd();
        if (totalPnl.compareTo(BigDecimal.valueOf(100000)) >= 0) score += 15;
        else if (totalPnl.compareTo(BigDecimal.valueOf(50000)) >= 0) score += 12;
        else if (totalPnl.compareTo(BigDecimal.valueOf(10000)) >= 0) score += 10;
        else if (totalPnl.compareTo(BigDecimal.valueOf(5000)) >= 0) score += 7;
        else score += 5;

        // Win/Loss Ratio (0-15 points)
        BigDecimal wlr = m.winLossRatio();
        if (wlr.compareTo(BigDecimal.valueOf(2.5)) >= 0) score += 15;
        else if (wlr.compareTo(BigDecimal.valueOf(2.0)) >= 0) score += 12;
        else if (wlr.compareTo(BigDecimal.valueOf(1.5)) >= 0) score += 10;
        else if (wlr.compareTo(BigDecimal.valueOf(1.0)) >= 0) score += 7;
        else score += 3;

        // Monthly Win Rate (0-15 points)
        if (m.monthlyWinRate() >= 80) score += 15;
        else if (m.monthlyWinRate() >= 70) score += 12;
        else if (m.monthlyWinRate() >= 60) score += 10;
        else if (m.monthlyWinRate() >= 50) score += 7;
        else score += 3;

        return Math.min(100, score);
    }

    /**
     * Consistency Score (0-100) - 20% weight
     * Focus: Reliability and predictability
     */
    private double scoreConsistency(WalletMetrics m) {
        double score = 0.0;

        // Trade Win Rate (0-30 points)
        if (m.tradeWinRate() >= 70) score += 30;
        else if (m.tradeWinRate() >= 60) score += 25;
        else if (m.tradeWinRate() >= 55) score += 20;
        else if (m.tradeWinRate() >= 50) score += 15;
        else score += 10;

        // Daily Win Rate (0-25 points)
        if (m.dailyWinRatePct() >= 70) score += 25;
        else if (m.dailyWinRatePct() >= 60) score += 20;
        else if (m.dailyWinRatePct() >= 55) score += 15;
        else if (m.dailyWinRatePct() >= 50) score += 10;
        else score += 5;

        // Winning Streak vs Losing Streak Ratio (0-20 points)
        if (m.maxConsecutiveWins() > 0 && m.maxConsecutiveLosses() > 0) {
            double streakRatio = (double) m.maxConsecutiveWins() / m.maxConsecutiveLosses();
            if (streakRatio >= 2.0) score += 20;
            else if (streakRatio >= 1.5) score += 15;
            else if (streakRatio >= 1.0) score += 10;
            else score += 5;
        } else {
            score += 10; // neutral if no data
        }

        // Volatility (lower is better) (0-25 points)
        double stdDevDaily = m.stdDevDailyReturns();
        if (stdDevDaily < 50) score += 25;
        else if (stdDevDaily < 100) score += 20;
        else if (stdDevDaily < 200) score += 15;
        else if (stdDevDaily < 500) score += 10;
        else score += 5;

        return Math.min(100, score);
    }

    /**
     * Risk Management Score (0-100) - 20% weight
     * Focus: Capital preservation and drawdown control
     */
    private double scoreRiskManagement(WalletMetrics m) {
        double score = 0.0;

        // Recovery Factor (0-30 points)
        BigDecimal rf = m.recoveryFactor();
        if (rf.compareTo(BigDecimal.valueOf(5.0)) >= 0) score += 30;
        else if (rf.compareTo(BigDecimal.valueOf(3.0)) >= 0) score += 25;
        else if (rf.compareTo(BigDecimal.valueOf(2.0)) >= 0) score += 20;
        else if (rf.compareTo(BigDecimal.valueOf(1.0)) >= 0) score += 15;
        else score += 5;

        // Sharpe-like Ratio (0-25 points)
        if (m.sharpeLikeDaily() >= 2.0) score += 25;
        else if (m.sharpeLikeDaily() >= 1.5) score += 20;
        else if (m.sharpeLikeDaily() >= 1.0) score += 15;
        else if (m.sharpeLikeDaily() >= 0.5) score += 10;
        else score += 5;

        // Calmar Ratio (0-20 points)
        BigDecimal calmar = m.calmarRatio();
        if (calmar.compareTo(BigDecimal.valueOf(3.0)) >= 0) score += 20;
        else if (calmar.compareTo(BigDecimal.valueOf(2.0)) >= 0) score += 15;
        else if (calmar.compareTo(BigDecimal.valueOf(1.0)) >= 0) score += 10;
        else score += 5;

        // Max Consecutive Losses (0-15 points, fewer is better)
        if (m.maxConsecutiveLosses() <= 3) score += 15;
        else if (m.maxConsecutiveLosses() <= 5) score += 12;
        else if (m.maxConsecutiveLosses() <= 8) score += 9;
        else if (m.maxConsecutiveLosses() <= 10) score += 6;
        else score += 3;

        // Longest Drawdown Days (0-10 points, shorter is better)
        if (m.longestDrawdownDays() <= 3) score += 10;
        else if (m.longestDrawdownDays() <= 7) score += 8;
        else if (m.longestDrawdownDays() <= 14) score += 6;
        else if (m.longestDrawdownDays() <= 30) score += 4;
        else score += 2;

        return Math.min(100, score);
    }

    /**
     * Recent Performance Score (0-100) - 15% weight
     * Focus: Current form and momentum (critical for copy trading timing)
     */
    private double scoreRecentPerformance(WalletMetrics m) {
        double score = 0.0;

        // Last 30 Days PnL (0-35 points)
        BigDecimal last30 = m.last30DaysPnl();
        BigDecimal totalPnl = m.totalRealizedUsd();
        if (totalPnl.compareTo(ZERO) > 0) {
            BigDecimal recentRatio = last30.divide(totalPnl, SCALE, RoundingMode.HALF_UP);
            if (recentRatio.compareTo(BigDecimal.valueOf(0.3)) >= 0) score += 35; // very strong recent performance
            else if (recentRatio.compareTo(BigDecimal.valueOf(0.15)) >= 0) score += 28;
            else if (recentRatio.compareTo(BigDecimal.valueOf(0.05)) >= 0) score += 20;
            else if (recentRatio.compareTo(ZERO) >= 0) score += 15;
            else if (recentRatio.compareTo(BigDecimal.valueOf(-0.1)) >= 0) score += 8;
            else score += 3; // negative recent performance
        }

        // Last 30 Days Win Rate (0-30 points)
        if (m.last30DaysWinRate() >= 70) score += 30;
        else if (m.last30DaysWinRate() >= 60) score += 24;
        else if (m.last30DaysWinRate() >= 55) score += 18;
        else if (m.last30DaysWinRate() >= 50) score += 12;
        else score += 6;

        // Last 7 Days PnL (0-20 points) - very recent momentum
        if (m.last7DaysPnl().compareTo(ZERO) > 0) score += 20;
        else if (m.last7DaysPnl().compareTo(totalPnl.multiply(BigDecimal.valueOf(-0.05))) >= 0) score += 12; // small loss ok
        else score += 5;

        // Last 7 Days Win Rate (0-15 points)
        if (m.last7DaysWinRate() >= 70) score += 15;
        else if (m.last7DaysWinRate() >= 60) score += 12;
        else if (m.last7DaysWinRate() >= 50) score += 8;
        else score += 4;

        return Math.min(100, score);
    }

    /**
     * Trade Execution Score (0-100) - 10% weight
     * Focus: Position sizing and trade management
     */
    private double scoreTradeExecution(WalletMetrics m) {
        double score = 0.0;

        // Average Trade Size vs Total Volume (0-30 points)
        BigDecimal avgSize = m.avgTradeSize();
        BigDecimal totalVol = m.totalVolumeUsd();
        if (totalVol.compareTo(ZERO) > 0) {
            BigDecimal sizeRatio = avgSize.divide(totalVol, SCALE, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(m.totalTrades()));
            // Prefer consistent position sizing
            if (sizeRatio.compareTo(BigDecimal.ONE) >= 0 && sizeRatio.compareTo(BigDecimal.valueOf(2)) <= 0) {
                score += 30; // very consistent
            } else {
                score += 20;
            }
        }

        // Holding Period Analysis (0-25 points)
        double avgHold = m.avgHoldingHoursWeighted();
        if (avgHold >= 4 && avgHold <= 72) score += 25; // 4-72 hours is ideal for swing trading
        else if (avgHold >= 1 && avgHold <= 168) score += 20; // 1 hour to 1 week is acceptable
        else score += 10;

        // Winner vs Loser Hold Time (0-25 points) - cut losses, let winners run
        if (m.avgHoldingHoursWinners() > 0 && m.avgHoldingHoursLosers() > 0) {
            double holdRatio = m.avgHoldingHoursWinners() / m.avgHoldingHoursLosers();
            if (holdRatio >= 1.5) score += 25; // holds winners longer
            else if (holdRatio >= 1.0) score += 20;
            else if (holdRatio >= 0.7) score += 15; // holds losers too long
            else score += 10;
        } else {
            score += 15; // neutral
        }

        // Fee Efficiency (0-20 points) - lower is better
        BigDecimal feeRatio = m.feeRatio().multiply(BigDecimal.valueOf(100));
        if (feeRatio.compareTo(BigDecimal.valueOf(0.5)) <= 0) score += 20;
        else if (feeRatio.compareTo(BigDecimal.ONE) <= 0) score += 15;
        else if (feeRatio.compareTo(BigDecimal.valueOf(2)) <= 0) score += 10;
        else score += 5;

        return Math.min(100, score);
    }

    /**
     * Activity Level Score (0-100) - 10% weight
     * Focus: Trading frequency and sample size
     */
    private double scoreActivityLevel(WalletMetrics m) {
        double score = 0.0;

        // Total Trades (0-35 points)
        if (m.totalTrades() >= 500) score += 35;
        else if (m.totalTrades() >= 200) score += 30;
        else if (m.totalTrades() >= 100) score += 25;
        else if (m.totalTrades() >= 50) score += 20;
        else score += 10;

        // Days Count (0-30 points)
        if (m.daysCount() >= 60) score += 30;
        else if (m.daysCount() >= 30) score += 25;
        else if (m.daysCount() >= 14) score += 20;
        else score += 10;

        // Trades Per Day (0-20 points) - moderate frequency preferred
        if (m.tradesPerDay() >= 3 && m.tradesPerDay() <= 20) score += 20; // ideal range
        else if (m.tradesPerDay() >= 1 && m.tradesPerDay() <= 30) score += 15;
        else if (m.tradesPerDay() >= 0.5) score += 10;
        else score += 5;

        // Recent Activity (0-15 points)
        if (m.last30DaysTrades() >= 30) score += 15;
        else if (m.last30DaysTrades() >= 20) score += 12;
        else if (m.last30DaysTrades() >= 10) score += 9;
        else if (m.last30DaysTrades() >= 5) score += 6;
        else score += 3;

        return Math.min(100, score);
    }

    /**
     * Calculate weighted composite score (0-100)
     */
    private int calculateCompositeScore(Map<String, Double> categoryScores) {
        double weighted = 0.0;
        weighted += categoryScores.getOrDefault("profitability", 0.0) * 0.25;
        weighted += categoryScores.getOrDefault("consistency", 0.0) * 0.20;
        weighted += categoryScores.getOrDefault("riskManagement", 0.0) * 0.20;
        weighted += categoryScores.getOrDefault("recentPerformance", 0.0) * 0.15;
        weighted += categoryScores.getOrDefault("tradeExecution", 0.0) * 0.10;
        weighted += categoryScores.getOrDefault("activityLevel", 0.0) * 0.10;

        return Math.max(0, Math.min(100, (int) Math.round(weighted)));
    }

    // ==================== STAGE 3: RED FLAG DETECTION ====================

    /**
     * Detect concerning patterns that may indicate risk
     */
    private Map<String, String> detectRedFlags(WalletMetrics m) {
        Map<String, String> flags = new LinkedHashMap<>();

        // Recent performance decline
        if (m.last30DaysPnl().compareTo(ZERO) < 0 && 
            m.totalRealizedUsd().compareTo(ZERO) > 0) {
            BigDecimal declineRatio = m.last30DaysPnl().divide(m.totalRealizedUsd(), SCALE, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100));
            if (declineRatio.compareTo(BigDecimal.valueOf(-20)) < 0) {
                flags.put("SEVERE_DECLINE", "Lost " + declineRatio.abs() + "% of total profit in last 30 days");
            } else if (declineRatio.compareTo(BigDecimal.valueOf(-10)) < 0) {
                flags.put("RECENT_DECLINE", "Lost " + declineRatio.abs() + "% in last 30 days");
            }
        }

        // Excessive losing streaks
        if (m.maxConsecutiveLosses() >= 10) {
            flags.put("LONG_LOSING_STREAK", m.maxConsecutiveLosses() + " consecutive losses");
        }

        // High volatility
        if (m.stdDevDailyReturns() > 1000) {
            flags.put("HIGH_VOLATILITY", "Very inconsistent daily returns");
        }

        // Large single loss vs average
        if (m.avgLosingTrade().compareTo(ZERO) < 0) {
            BigDecimal lossRatio = m.largestLossUsd().divide(m.avgLosingTrade(), SCALE, RoundingMode.HALF_UP);
            if (lossRatio.abs().compareTo(BigDecimal.valueOf(10)) > 0) {
                flags.put("OUTLIER_LOSS", "Largest loss is " + lossRatio.abs() + "x average loss");
            }
        }

        // Poor win/loss ratio
        if (m.winLossRatio().compareTo(BigDecimal.valueOf(0.8)) < 0) {
            flags.put("LOW_WIN_LOSS_RATIO", "Avg win smaller than avg loss: " + m.winLossRatio());
        }

        // Holding losers too long
        if (m.avgHoldingHoursLosers() > 0 && m.avgHoldingHoursWinners() > 0) {
            double holdRatio = m.avgHoldingHoursWinners() / m.avgHoldingHoursLosers();
            if (holdRatio < 0.5) {
                flags.put("HOLDS_LOSERS", "Holds losing positions " + 
                    String.format("%.1f", 1.0/holdRatio) + "x longer than winners");
            }
        }

        // Low sample size warning
        if (m.totalTrades() < 100) {
            flags.put("SMALL_SAMPLE", "Only " + m.totalTrades() + " trades - limited statistical confidence");
        }

        // Inactive recently
        if (m.last30DaysTrades() < 10) {
            flags.put("LOW_ACTIVITY", "Only " + m.last30DaysTrades() + " trades in last 30 days");
        }

        return flags;
    }

    // ==================== RECOMMENDATION ENGINE ====================

    /**
     * Generate actionable recommendation based on scoring
     */
    private String generateRecommendation(ConfidenceTier tier, Map<String, String> redFlags, WalletMetrics m) {
        StringBuilder rec = new StringBuilder();

        switch (tier) {
            case S_PLUS -> {
                rec.append("🔥 ELITE ALPHA WALLET - Copy immediately with confidence. ");
                rec.append("This wallet demonstrates exceptional risk-adjusted returns, ");
                rec.append("consistent performance, and strong recent momentum. ");
                if (m.tradeWinRate() >= 70) {
                    rec.append("Trade win rate of ").append(String.format("%.1f%%", m.tradeWinRate()))
                       .append(" is outstanding. ");
                }
            }
            case S -> {
                rec.append("⭐ STRONG ALPHA WALLET - High confidence copy candidate. ");
                rec.append("Solid track record with good risk management. ");
                if (m.last30DaysPnl().compareTo(ZERO) > 0) {
                    rec.append("Recent 30-day performance is positive ($")
                       .append(m.last30DaysPnl()).append("). ");
                }
            }
            case A -> {
                rec.append("✓ GOOD ALPHA WALLET - Moderate confidence copy candidate. ");
                rec.append("Consider with position sizing limits. ");
            }
            case B -> {
                rec.append("⚠️ ACCEPTABLE - Low confidence copy. ");
                rec.append("Use small position size and monitor closely. ");
            }
            case C -> {
                rec.append("⚠️ MARGINAL - Monitor only, do not copy. ");
                rec.append("Wait for improvement in performance metrics. ");
            }
            case D, F -> {
                rec.append("❌ DO NOT COPY - Insufficient performance. ");
                rec.append("Does not meet minimum standards for copy trading. ");
            }
        }

        // Add red flag warnings
        if (!redFlags.isEmpty()) {
            rec.append("\n\n⚠️ Red Flags:\n");
            redFlags.forEach((key, msg) -> rec.append("  • ").append(msg).append("\n"));
        }

        // Add key metrics summary
        rec.append("\n📊 Key Metrics:\n");
        rec.append("  • Profit Factor: ").append(m.profitFactor()).append("\n");
        rec.append("  • Trade Win Rate: ").append(String.format("%.1f%%", m.tradeWinRate())).append("\n");
        rec.append("  • Daily Win Rate: ").append(String.format("%.1f%%", m.dailyWinRatePct())).append("\n");
        rec.append("  • Total Realized: $").append(m.totalRealizedUsd()).append("\n");
        rec.append("  • Last 30D PnL: $").append(m.last30DaysPnl()).append("\n");
        rec.append("  • Max Drawdown: $").append(m.maxDrawdownUsd()).append("\n");
        rec.append("  • Total Trades: ").append(m.totalTrades()).append("\n");

        return rec.toString();
    }

    /**
     * Helper to create failed result
     */
    private ScoringResult createFailedResult(String walletAddress, WalletMetrics metrics, List<String> failedFilters) {
        return new ScoringResult(
                walletAddress,
                false,
                failedFilters,
                0,
                ConfidenceTier.F,
                Map.of(),
                Map.of(),
                metrics,
                "❌ Failed hard filters. Does not meet minimum requirements for copy trading consideration."
        );
    }

    /**
     * Batch score multiple wallets and return sorted by composite score
     */
    public List<ScoringResult> scoreAndRankWallets(Map<String, WalletMetrics> walletMetrics) {
        return walletMetrics.entrySet().stream()
                .map(e -> scoreWallet(e.getKey(), e.getValue()))
                .filter(ScoringResult::passedHardFilters)
                .sorted((a, b) -> Integer.compare(b.compositeScore(), a.compositeScore()))
                .toList();
    }
}
