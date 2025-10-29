package com.sol.service;

import com.sol.util.WalletMetricsCalculator;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Dynamic threshold adjustment service that modifies validation criteria
 * based on trader profile to reduce false positives for legitimate HFT traders
 */
@Service
@RequiredArgsConstructor
public class DynamicThresholdService {
    
    private final LegitimateHftDetectionService hftDetectionService;
    
    public enum TraderProfile {
        NORMAL_TRADER,
        LEGITIMATE_HFT,
        INSTITUTIONAL_TRADER,
        SUSPECTED_BOT
    }
    
    @Builder(toBuilder = true)
    @Data
    public static class ValidationThresholds {
        // Bot detection thresholds
        private double botDetectionThreshold;
        private double maxTradesPerDay;
        private double minAvgHoldingHours;
        private double suspiciousWinRate;
        private int maxSameBlockTrades;
        
        // MEV detection thresholds
        private double mevDetectionThreshold;
        private double sandwichAttackThreshold;
        private double arbitrageThreshold;
        private double sameBlockTradeRateThreshold;
        
        // Wash trading thresholds
        private double washTradingThreshold;
        private int maxRoundTripSeconds;
        private int maxRoundTrips;
        private int maxSameBlockRoundTrips;
        
        // Other validation thresholds
        private int consecutiveLossThreshold;
        private double minTradeInterval; // minutes
        private int maxDailyTrades;
        private double identicalSizingThreshold;
        
        public static ValidationThresholds getDefault() {
            return ValidationThresholds.builder()
                .botDetectionThreshold(60.0)
                .maxTradesPerDay(50.0)
                .minAvgHoldingHours(1.0)
                .suspiciousWinRate(95.0)
                .maxSameBlockTrades(5)
                .mevDetectionThreshold(40.0) // Reduced from 60.0 to reduce false positives
                .sandwichAttackThreshold(70.0)
                .arbitrageThreshold(70.0)
                .sameBlockTradeRateThreshold(0.5)
                .washTradingThreshold(75.0)
                .maxRoundTripSeconds(120)
                .maxRoundTrips(5)
                .maxSameBlockRoundTrips(3)
                .consecutiveLossThreshold(5)
                .minTradeInterval(5.0)
                .maxDailyTrades(50)
                .identicalSizingThreshold(0.7)
                .build();
        }
    }
    
    @Builder
    @Data
    public static class WalletProfile {
        private TraderProfile profile;
        private boolean isHighFrequencyTrader;
        private boolean isInstitutionalTrader;
        private boolean hasWhitelistPattern;
        private double legitimacyScore;
        private Map<String, Object> profileMetrics;
    }
    
    /**
     * Analyze wallet profile and determine appropriate validation thresholds
     */
    public ValidationThresholds getAdjustedThresholds(WalletProfile profile) {
        ValidationThresholds baseThresholds = ValidationThresholds.getDefault();
        
        switch (profile.getProfile()) {
            case LEGITIMATE_HFT:
                return adjustForLegitimateHft(baseThresholds, profile);
            case INSTITUTIONAL_TRADER:
                return adjustForInstitutional(baseThresholds, profile);
            case SUSPECTED_BOT:
                return adjustForSuspectedBot(baseThresholds, profile);
            case NORMAL_TRADER:
            default:
                return baseThresholds;
        }
    }
    
    /**
     * Create wallet profile from analysis results
     */
    public WalletProfile createWalletProfile(
            LegitimateHftDetectionService.HftAnalysisResult hftAnalysis,
            WalletMetricsCalculator.WalletMetrics metrics) {
        
        TraderProfile profile = determineTraderProfile(hftAnalysis, metrics);
        boolean isHft = hftAnalysis.getClassification() == LegitimateHftDetectionService.HftClassification.LEGITIMATE_HFT ||
                       hftAnalysis.getClassification() == LegitimateHftDetectionService.HftClassification.BOT_TRADING;
        
        return WalletProfile.builder()
            .profile(profile)
            .isHighFrequencyTrader(isHft)
            .isInstitutionalTrader(isInstitutionalTrader(metrics))
            .hasWhitelistPattern(hasWhitelistPattern(hftAnalysis))
            .legitimacyScore(hftAnalysis.getLegitimacyScore())
            .profileMetrics(hftAnalysis.getDiagnostics())
            .build();
    }
    
    private TraderProfile determineTraderProfile(
            LegitimateHftDetectionService.HftAnalysisResult hftAnalysis,
            WalletMetricsCalculator.WalletMetrics metrics) {
        
        // Check for suspected bot first
        if (hftAnalysis.getClassification() == LegitimateHftDetectionService.HftClassification.BOT_TRADING ||
            hftAnalysis.getLegitimacyScore() < 30) {
            return TraderProfile.SUSPECTED_BOT;
        }
        
        // Check for legitimate HFT
        if (hftAnalysis.getClassification() == LegitimateHftDetectionService.HftClassification.LEGITIMATE_HFT) {
            return TraderProfile.LEGITIMATE_HFT;
        }
        
        // Check for institutional trader
        if (isInstitutionalTrader(metrics)) {
            return TraderProfile.INSTITUTIONAL_TRADER;
        }
        
        return TraderProfile.NORMAL_TRADER;
    }
    
    private boolean isInstitutionalTrader(WalletMetricsCalculator.WalletMetrics metrics) {
        // Institutional trader characteristics:
        // - High total volume
        // - Consistent performance
        // - Good risk management
        // - Large position sizes
        
        BigDecimal totalVolume = metrics.totalVolumeUsd();
        BigDecimal avgPosition = metrics.avgTradeSize(); // Using avgTradeSize instead of avgPositionSizeUsd
        Double sharpeRatio = metrics.sharpeLikeDaily(); // Using sharpeLikeDaily instead of sharpeRatio
        BigDecimal maxDrawdown = metrics.maxDrawdownUsd(); // This returns BigDecimal
        
        return totalVolume != null && totalVolume.compareTo(BigDecimal.valueOf(1_000_000)) > 0 && // >$1M volume
               avgPosition != null && avgPosition.compareTo(BigDecimal.valueOf(10_000)) > 0 && // >$10K avg position
               sharpeRatio != null && sharpeRatio > 1.5 && // Good risk-adjusted returns
               maxDrawdown != null && maxDrawdown.compareTo(BigDecimal.valueOf(300_000)) < 0; // Controlled drawdown <$300K
    }
    
    private boolean hasWhitelistPattern(LegitimateHftDetectionService.HftAnalysisResult hftAnalysis) {
        // Check for known legitimate patterns
        return hftAnalysis.getLegitimacyIndicators().size() >= 4 && // Multiple legitimacy indicators
               hftAnalysis.getBotIndicators().size() <= 1 && // Few bot indicators
               hftAnalysis.getLegitimacyScore() > 70; // High legitimacy score
    }
    
    private ValidationThresholds adjustForLegitimateHft(ValidationThresholds base, WalletProfile profile) {
        return base.toBuilder()
            // More lenient bot detection for legitimate HFT
            .botDetectionThreshold(85.0) // Raised from 60
            .maxTradesPerDay(500.0) // Raised from 50
            .minAvgHoldingHours(0.25) // Lowered from 1.0 (15 minutes)
            .suspiciousWinRate(98.0) // Raised from 95%
            .maxSameBlockTrades(15) // Raised from 5
            
            // More lenient MEV detection
            .mevDetectionThreshold(80.0) // Raised from 60
            .sandwichAttackThreshold(90.0) // Raised from 70
            .arbitrageThreshold(85.0) // Raised from 70
            .sameBlockTradeRateThreshold(0.8) // Raised from 0.5
            
            // More lenient wash trading detection
            .washTradingThreshold(90.0) // Raised from 75
            .maxRoundTripSeconds(300) // Raised from 120 (5 minutes)
            .maxRoundTrips(15) // Raised from 5
            .maxSameBlockRoundTrips(8) // Raised from 3
            
            // Adjust other thresholds
            .consecutiveLossThreshold(10) // Raised from 5
            .minTradeInterval(0.1) // Lowered from 5 minutes (6 seconds)
            .maxDailyTrades(1000) // Raised from 50
            .identicalSizingThreshold(0.9) // Raised from 0.7
            .build();
    }
    
    private ValidationThresholds adjustForInstitutional(ValidationThresholds base, WalletProfile profile) {
        return base.toBuilder()
            // Slightly more lenient for institutional traders
            .botDetectionThreshold(75.0) // Raised from 60
            .maxTradesPerDay(200.0) // Raised from 50
            .minAvgHoldingHours(0.5) // Lowered from 1.0
            .suspiciousWinRate(97.0) // Raised from 95%
            .maxSameBlockTrades(10) // Raised from 5
            
            .mevDetectionThreshold(70.0) // Raised from 60
            .sandwichAttackThreshold(80.0) // Raised from 70
            .arbitrageThreshold(75.0) // Raised from 70
            .sameBlockTradeRateThreshold(0.7) // Raised from 0.5
            
            .washTradingThreshold(85.0) // Raised from 75
            .maxRoundTripSeconds(180) // Raised from 120
            .maxRoundTrips(10) // Raised from 5
            .maxSameBlockRoundTrips(5) // Raised from 3
            
            .consecutiveLossThreshold(8) // Raised from 5
            .minTradeInterval(1.0) // Lowered from 5 minutes
            .maxDailyTrades(200) // Raised from 50
            .identicalSizingThreshold(0.8) // Raised from 0.7
            .build();
    }
    
    private ValidationThresholds adjustForSuspectedBot(ValidationThresholds base, WalletProfile profile) {
        return base.toBuilder()
            // Stricter thresholds for suspected bots
            .botDetectionThreshold(40.0) // Lowered from 60
            .maxTradesPerDay(20.0) // Lowered from 50
            .minAvgHoldingHours(2.0) // Raised from 1.0
            .suspiciousWinRate(90.0) // Lowered from 95%
            .maxSameBlockTrades(2) // Lowered from 5
            
            .mevDetectionThreshold(40.0) // Lowered from 60
            .sandwichAttackThreshold(50.0) // Lowered from 70
            .arbitrageThreshold(50.0) // Lowered from 70
            .sameBlockTradeRateThreshold(0.2) // Lowered from 0.5
            
            .washTradingThreshold(50.0) // Lowered from 75
            .maxRoundTripSeconds(60) // Lowered from 120
            .maxRoundTrips(2) // Lowered from 5
            .maxSameBlockRoundTrips(1) // Lowered from 3
            
            .consecutiveLossThreshold(3) // Lowered from 5
            .minTradeInterval(10.0) // Raised from 5 minutes
            .maxDailyTrades(20) // Lowered from 50
            .identicalSizingThreshold(0.5) // Lowered from 0.7
            .build();
    }
    
    /**
     * Get threshold explanation for logging/debugging
     */
    public String getThresholdExplanation(WalletProfile profile, ValidationThresholds thresholds) {
        StringBuilder explanation = new StringBuilder();
        explanation.append("Applied profile: ").append(profile.getProfile()).append("\n");
        
        if (profile.isHighFrequencyTrader()) {
            explanation.append("- HFT adjustments: Higher trade frequency tolerance, lower holding time requirements\n");
        }
        
        if (profile.isInstitutionalTrader()) {
            explanation.append("- Institutional adjustments: Higher volume thresholds, better risk management expected\n");
        }
        
        if (profile.isHasWhitelistPattern()) {
            explanation.append("- Whitelist patterns detected: Multiple legitimacy indicators present\n");
        }
        
        explanation.append("Key thresholds: ")
            .append("Bot detection: ").append(thresholds.getBotDetectionThreshold())
            .append(", Max trades/day: ").append(thresholds.getMaxTradesPerDay())
            .append(", MEV threshold: ").append(thresholds.getMevDetectionThreshold());
        
        return explanation.toString();
    }
}