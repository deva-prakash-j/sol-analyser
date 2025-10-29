package com.sol.demo;

import com.sol.service.*;
import com.sol.util.NormalizeTransaction;
import com.sol.util.WalletMetricsCalculator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.*;

/**
 * Demonstration of the enhanced HFT false positive detection system
 */
@Component
@RequiredArgsConstructor
public class EnhancedHftDetectionDemo {
    
    private final LegitimateHftDetectionService legitimateHftDetectionService;
    private final DynamicThresholdService dynamicThresholdService;
    private final HftWhitelistService hftWhitelistService;
    private final ValidationFeedbackService validationFeedbackService;
    
    /**
     * Demonstrates the enhanced validation process
     */
    public void demonstrateEnhancedValidation() {
        System.out.println("=== Enhanced HFT Detection System Demo ===\n");
        
        // Create sample transaction data for different trader types
        List<NormalizeTransaction.Row> legitimateHftTrades = createLegitimateHftSample();
        List<NormalizeTransaction.Row> botTrades = createBotSample();
        List<NormalizeTransaction.Row> normalTrades = createNormalTraderSample();
        
        WalletMetricsCalculator.WalletMetrics legitMetrics = createSampleMetrics(true, false);
        WalletMetricsCalculator.WalletMetrics botMetrics = createSampleMetrics(false, true);
        WalletMetricsCalculator.WalletMetrics normalMetrics = createSampleMetrics(false, false);
        
        // Test 1: Legitimate HFT trader
        System.out.println("1. Testing Legitimate HFT Trader:");
        analyzeTrader("Legitimate HFT", legitimateHftTrades, legitMetrics);
        
        // Test 2: Bot trader
        System.out.println("2. Testing Bot Trader:");
        analyzeTrader("Bot", botTrades, botMetrics);
        
        // Test 3: Normal trader
        System.out.println("3. Testing Normal Trader:");
        analyzeTrader("Normal", normalTrades, normalMetrics);
        
        // Show feedback system capabilities
        demonstrateFeedbackSystem();
        
        System.out.println("=== Demo Complete ===");
    }
    
    private void analyzeTrader(String traderType, List<NormalizeTransaction.Row> transactions, 
                              WalletMetricsCalculator.WalletMetrics metrics) {
        
        System.out.println("   Trader Type: " + traderType);
        System.out.println("   Transactions: " + transactions.size());
        
        // Step 1: HFT Analysis
        LegitimateHftDetectionService.HftAnalysisResult hftAnalysis = 
            legitimateHftDetectionService.analyzeTrader(transactions);
        
        System.out.println("   HFT Classification: " + hftAnalysis.getClassification());
        System.out.println("   Legitimacy Score: " + String.format("%.1f", hftAnalysis.getLegitimacyScore()));
        
        // Step 2: Profile Creation
        DynamicThresholdService.WalletProfile profile = 
            dynamicThresholdService.createWalletProfile(hftAnalysis, metrics);
        
        System.out.println("   Trader Profile: " + profile.getProfile());
        
        // Step 3: Dynamic Thresholds
        DynamicThresholdService.ValidationThresholds thresholds = 
            dynamicThresholdService.getAdjustedThresholds(profile);
        
        System.out.println("   Bot Detection Threshold: " + thresholds.getBotDetectionThreshold());
        System.out.println("   Max Trades/Day: " + thresholds.getMaxTradesPerDay());
        
        // Step 4: Whitelist Check
        HftWhitelistService.WhitelistAnalysis whitelistResult = 
            hftWhitelistService.analyzeForWhitelist("demo_wallet", transactions, metrics, hftAnalysis);
        
        System.out.println("   Whitelisted: " + whitelistResult.isWhitelisted());
        if (whitelistResult.isWhitelisted()) {
            System.out.println("   Whitelist Reasons: " + String.join(", ", whitelistResult.getWhitelistReasons()));
        }
        
        // Step 5: Show legitimacy indicators
        if (!hftAnalysis.getLegitimacyIndicators().isEmpty()) {
            System.out.println("   Legitimacy Indicators:");
            hftAnalysis.getLegitimacyIndicators().forEach(indicator -> 
                System.out.println("     + " + indicator));
        }
        
        if (!hftAnalysis.getBotIndicators().isEmpty()) {
            System.out.println("   Bot Indicators:");
            hftAnalysis.getBotIndicators().forEach(indicator -> 
                System.out.println("     - " + indicator));
        }
        
        System.out.println();
    }
    
    private void demonstrateFeedbackSystem() {
        System.out.println("4. Feedback System Capabilities:");
        
        // Simulate feedback data
        ValidationFeedbackService.ActualPerformance goodPerformance = 
            ValidationFeedbackService.ActualPerformance.builder()
                .wasActuallyGood(true)
                .actualWinRate(75.0)
                .actualSharpeRatio(2.1)
                .actualReturnPercentage(45.0)
                .confirmedAsLegitimateTrader(true)
                .performanceNotes("Consistently profitable over 6 months")
                .build();
        
        System.out.println("   Feedback tracking enables:");
        System.out.println("     • False positive rate monitoring");
        System.out.println("     • Threshold optimization");
        System.out.println("     • Continuous model improvement");
        System.out.println("     • Profile-specific accuracy tracking");
        
        // Show current feedback summary
        ValidationFeedbackService.FeedbackSummary summary = validationFeedbackService.getFeedbackSummary();
        System.out.println("   Current Statistics:");
        System.out.println("     Total Validations: " + summary.getTotalValidations());
        System.out.println("     Accuracy: " + String.format("%.1f%%", summary.getAccuracy() * 100));
        System.out.println();
    }
    
    private List<NormalizeTransaction.Row> createLegitimateHftSample() {
        List<NormalizeTransaction.Row> trades = new ArrayList<>();
        long baseTime = System.currentTimeMillis() / 1000;
        
        // Legitimate HFT characteristics:
        // - High frequency but variable timing
        // - Variable position sizes
        // - Some losses (human-like errors)
        // - Multiple tokens
        
        String[] tokens = {"token1", "token2", "token3", "token4"};
        Random random = new Random();
        
        for (int i = 0; i < 150; i++) {
            String token = tokens[random.nextInt(tokens.length)];
            NormalizeTransaction.Side side = random.nextBoolean() ? 
                NormalizeTransaction.Side.BUY : NormalizeTransaction.Side.SELL;
            
            // Variable position sizes (legitimacy indicator)
            BigDecimal positionSize = BigDecimal.valueOf(1000 + random.nextInt(5000));
            
            // Variable timing (legitimacy indicator)
            long timeOffset = i * (60 + random.nextInt(300)); // 1-6 minutes apart
            
            trades.add(new NormalizeTransaction.Row(
                "sig_" + i,
                "demo_wallet",
                baseTime + timeOffset,
                1000L + i,
                "USDC",
                token,
                BigDecimal.valueOf(100),
                positionSize,
                side,
                BigDecimal.valueOf(0.001 + random.nextDouble() * 0.01),
                BigDecimal.valueOf(0.001 + random.nextDouble() * 0.01),
                positionSize,
                BigDecimal.valueOf(0.0005),
                BigDecimal.valueOf(0.5)
            ));
        }
        
        return trades;
    }
    
    private List<NormalizeTransaction.Row> createBotSample() {
        List<NormalizeTransaction.Row> trades = new ArrayList<>();
        long baseTime = System.currentTimeMillis() / 1000;
        
        // Bot characteristics:
        // - Very high frequency
        // - Identical position sizes
        // - Perfect timing
        // - Same block trades
        
        for (int i = 0; i < 200; i++) {
            NormalizeTransaction.Side side = (i % 2 == 0) ? 
                NormalizeTransaction.Side.BUY : NormalizeTransaction.Side.SELL;
            
            // Identical position sizes (bot indicator)
            BigDecimal positionSize = BigDecimal.valueOf(1000);
            
            // Very consistent timing (bot indicator)
            long timeOffset = i * 30; // Every 30 seconds
            
            trades.add(new NormalizeTransaction.Row(
                "sig_" + i,
                "bot_wallet",
                baseTime + timeOffset,
                1000L + (i / 5), // Multiple trades per block
                "USDC",
                "token1",
                BigDecimal.valueOf(100),
                positionSize,
                side,
                BigDecimal.valueOf(0.001),
                BigDecimal.valueOf(0.001),
                positionSize,
                BigDecimal.valueOf(0.0005),
                BigDecimal.valueOf(0.5)
            ));
        }
        
        return trades;
    }
    
    private List<NormalizeTransaction.Row> createNormalTraderSample() {
        List<NormalizeTransaction.Row> trades = new ArrayList<>();
        long baseTime = System.currentTimeMillis() / 1000;
        Random random = new Random();
        
        // Normal trader characteristics:
        // - Moderate frequency
        // - Human-like timing
        // - Varied position sizes
        // - Weekend breaks
        
        for (int i = 0; i < 50; i++) {
            NormalizeTransaction.Side side = random.nextBoolean() ? 
                NormalizeTransaction.Side.BUY : NormalizeTransaction.Side.SELL;
            
            // Human-like position sizes
            BigDecimal positionSize = BigDecimal.valueOf(500 + random.nextInt(3000));
            
            // Human-like timing with breaks
            long timeOffset = i * (3600 + random.nextInt(7200)); // 1-3 hours apart
            
            trades.add(new NormalizeTransaction.Row(
                "sig_" + i,
                "normal_wallet",
                baseTime + timeOffset,
                1000L + i * 10,
                "USDC",
                "token" + (random.nextInt(3) + 1),
                BigDecimal.valueOf(100),
                positionSize,
                side,
                BigDecimal.valueOf(0.001 + random.nextDouble() * 0.005),
                BigDecimal.valueOf(0.001 + random.nextDouble() * 0.005),
                positionSize,
                BigDecimal.valueOf(0.0005),
                BigDecimal.valueOf(0.5)
            ));
        }
        
        return trades;
    }
    
    private WalletMetricsCalculator.WalletMetrics createSampleMetrics(boolean isLegitHft, boolean isBot) {
        if (isLegitHft) {
            return new WalletMetricsCalculator.WalletMetrics(
                BigDecimal.valueOf(500_000), // totalVolumeUsd
                BigDecimal.valueOf(45_000),  // totalRealizedUsd
                BigDecimal.valueOf(2_500),   // totalFeesUsd
                BigDecimal.valueOf(0.09),    // pnlPct
                BigDecimal.valueOf(2.5),     // profitFactor
                BigDecimal.valueOf(0.005),   // feeRatio
                BigDecimal.valueOf(15_000),  // maxDrawdownUsd
                2.1,                         // sharpeLikeDaily
                0.5,                         // avgHoldingHoursWeighted
                90,                          // daysCount
                75.0,                        // dailyWinRatePct
                BigDecimal.valueOf(3.0),     // recoveryFactor
                3,                           // maxConsecutiveLosses
                8,                           // maxConsecutiveWins
                BigDecimal.valueOf(500),     // avgWinningTrade
                BigDecimal.valueOf(-200),    // avgLosingTrade
                BigDecimal.valueOf(2.5),     // winLossRatio
                BigDecimal.valueOf(2.8),     // calmarRatio
                1.8,                         // sortinoRatio
                BigDecimal.valueOf(5_000),   // largestWinUsd
                BigDecimal.valueOf(-2_000),  // largestLossUsd
                80.0,                        // monthlyWinRate
                5,                           // longestDrawdownDays
                0.15,                        // stdDevDailyReturns
                0.25,                        // stdDevMonthlyReturns
                150,                         // totalTrades
                112,                         // winningTrades
                38,                          // losingTrades
                74.7,                        // tradeWinRate
                1.67,                        // tradesPerDay
                BigDecimal.valueOf(3_333),   // avgTradeSize
                BigDecimal.valueOf(2_500),   // medianTradeSize
                BigDecimal.valueOf(8_500),   // last7DaysPnl
                BigDecimal.valueOf(15_000),  // last30DaysPnl
                78.0,                        // last7DaysWinRate
                76.0,                        // last30DaysWinRate
                45,                          // last30DaysTrades
                0.6,                         // avgHoldingHoursWinners
                0.4,                         // avgHoldingHoursLosers
                BigDecimal.valueOf(52_500),  // grossProfitUsd
                BigDecimal.valueOf(-7_500)   // grossLossUsd
            );
        } else if (isBot) {
            return new WalletMetricsCalculator.WalletMetrics(
                BigDecimal.valueOf(800_000), // totalVolumeUsd
                BigDecimal.valueOf(75_000),  // totalRealizedUsd
                BigDecimal.valueOf(400),     // totalFeesUsd (very low - MEV bot)
                BigDecimal.valueOf(0.094),   // pnlPct
                BigDecimal.valueOf(15.0),    // profitFactor (unrealistically high)
                BigDecimal.valueOf(0.0005),  // feeRatio (very low)
                BigDecimal.valueOf(1_000),   // maxDrawdownUsd (unrealistically low)
                8.5,                         // sharpeLikeDaily (unrealistically high)
                0.05,                        // avgHoldingHoursWeighted (ultra-short)
                60,                          // daysCount
                98.5,                        // dailyWinRatePct (unrealistically high)
                BigDecimal.valueOf(75.0),    // recoveryFactor (unrealistically high)
                0,                           // maxConsecutiveLosses (perfect)
                200,                         // maxConsecutiveWins (unrealistic)
                BigDecimal.valueOf(375),     // avgWinningTrade
                BigDecimal.valueOf(-25),     // avgLosingTrade (tiny losses)
                BigDecimal.valueOf(15.0),    // winLossRatio (unrealistic)
                BigDecimal.valueOf(75.0),    // calmarRatio (unrealistic)
                12.0,                        // sortinoRatio (unrealistic)
                BigDecimal.valueOf(2_000),   // largestWinUsd
                BigDecimal.valueOf(-50),     // largestLossUsd (tiny)
                100.0,                       // monthlyWinRate (perfect)
                0,                           // longestDrawdownDays (perfect)
                0.02,                        // stdDevDailyReturns (unrealistically low)
                0.03,                        // stdDevMonthlyReturns (unrealistically low)
                400,                         // totalTrades
                394,                         // winningTrades
                6,                           // losingTrades
                98.5,                        // tradeWinRate (unrealistic)
                6.67,                        // tradesPerDay (very high)
                BigDecimal.valueOf(2_000),   // avgTradeSize
                BigDecimal.valueOf(2_000),   // medianTradeSize (identical - bot indicator)
                BigDecimal.valueOf(12_000),  // last7DaysPnl
                BigDecimal.valueOf(25_000),  // last30DaysPnl
                100.0,                       // last7DaysWinRate (perfect)
                99.0,                        // last30DaysWinRate (nearly perfect)
                200,                         // last30DaysTrades
                0.05,                        // avgHoldingHoursWinners (ultra-short)
                0.03,                        // avgHoldingHoursLosers (ultra-short)
                BigDecimal.valueOf(75_150),  // grossProfitUsd
                BigDecimal.valueOf(-150)     // grossLossUsd (tiny)
            );
        } else {
            // Normal trader
            return new WalletMetricsCalculator.WalletMetrics(
                BigDecimal.valueOf(125_000), // totalVolumeUsd
                BigDecimal.valueOf(8_500),   // totalRealizedUsd
                BigDecimal.valueOf(750),     // totalFeesUsd
                BigDecimal.valueOf(0.068),   // pnlPct
                BigDecimal.valueOf(1.4),     // profitFactor
                BigDecimal.valueOf(0.006),   // feeRatio
                BigDecimal.valueOf(8_000),   // maxDrawdownUsd
                1.2,                         // sharpeLikeDaily
                8.5,                         // avgHoldingHoursWeighted
                180,                         // daysCount
                62.0,                        // dailyWinRatePct
                BigDecimal.valueOf(1.06),    // recoveryFactor
                4,                           // maxConsecutiveLosses
                6,                           // maxConsecutiveWins
                BigDecimal.valueOf(400),     // avgWinningTrade
                BigDecimal.valueOf(-300),    // avgLosingTrade
                BigDecimal.valueOf(1.33),    // winLossRatio
                BigDecimal.valueOf(1.06),    // calmarRatio
                0.9,                         // sortinoRatio
                BigDecimal.valueOf(2_500),   // largestWinUsd
                BigDecimal.valueOf(-1_800),  // largestLossUsd
                66.7,                        // monthlyWinRate
                15,                          // longestDrawdownDays
                0.35,                        // stdDevDailyReturns
                0.45,                        // stdDevMonthlyReturns
                50,                          // totalTrades
                32,                          // winningTrades
                18,                          // losingTrades
                64.0,                        // tradeWinRate
                0.28,                        // tradesPerDay
                BigDecimal.valueOf(2_500),   // avgTradeSize
                BigDecimal.valueOf(2_000),   // medianTradeSize
                BigDecimal.valueOf(1_200),   // last7DaysPnl
                BigDecimal.valueOf(2_800),   // last30DaysPnl
                71.0,                        // last7DaysWinRate
                68.0,                        // last30DaysWinRate
                15,                          // last30DaysTrades
                10.2,                        // avgHoldingHoursWinners
                6.8,                         // avgHoldingHoursLosers
                BigDecimal.valueOf(13_800),  // grossProfitUsd
                BigDecimal.valueOf(-5_300)   // grossLossUsd
            );
        }
    }
}