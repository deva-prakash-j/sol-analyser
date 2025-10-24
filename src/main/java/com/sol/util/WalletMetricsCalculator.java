package com.sol.util;

import com.sol.service.PnlEngine;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

public class WalletMetricsCalculator {

    /**
     * Extended wallet metrics for comprehensive scoring and copy trading analysis
     */
    public record WalletMetrics(
            // === Original Core Metrics ===
            BigDecimal totalVolumeUsd,
            BigDecimal totalRealizedUsd,
            BigDecimal totalFeesUsd,
            BigDecimal pnlPct,              // totalRealized / totalVolume
            BigDecimal profitFactor,        // grossProfit / grossLoss
            BigDecimal feeRatio,            // totalFees / totalVolume
            BigDecimal maxDrawdownUsd,      // from cumulative daily PnL
            double sharpeLikeDaily,         // mean(daily pnl) / std(daily pnl)
            double avgHoldingHoursWeighted, // qty-weighted across positions
            int daysCount,
            double dailyWinRatePct,         // % of days with pnl > 0
            
            // === Risk Metrics ===
            BigDecimal recoveryFactor,      // totalRealized / maxDrawdown (higher is better)
            int maxConsecutiveLosses,       // longest losing streak
            int maxConsecutiveWins,         // longest winning streak
            BigDecimal avgWinningTrade,     // average profitable trade size
            BigDecimal avgLosingTrade,      // average losing trade size
            BigDecimal winLossRatio,        // avgWin / avgLoss
            BigDecimal calmarRatio,         // annualReturn / maxDrawdown
            double sortinoRatio,            // return / downsideDeviation
            BigDecimal largestWinUsd,       // biggest single win
            BigDecimal largestLossUsd,      // biggest single loss
            
            // === Consistency Metrics ===
            double monthlyWinRate,          // % profitable months
            int longestDrawdownDays,        // max days in drawdown
            double stdDevDailyReturns,      // volatility of daily returns
            double stdDevMonthlyReturns,    // volatility of monthly returns
            int totalTrades,                // total number of trades
            int winningTrades,              // number of profitable trades
            int losingTrades,               // number of losing trades
            double tradeWinRate,            // % of profitable trades
            
            // === Activity Metrics ===
            double tradesPerDay,            // avg trades per day
            BigDecimal avgTradeSize,        // avg notional per trade
            BigDecimal medianTradeSize,     // median notional per trade
            
            // === Recent Performance (Critical for Copy Trading) ===
            BigDecimal last7DaysPnl,        // PnL last 7 days
            BigDecimal last30DaysPnl,       // PnL last 30 days
            double last7DaysWinRate,        // win rate last 7 days
            double last30DaysWinRate,       // win rate last 30 days
            int last30DaysTrades,           // trade count last 30 days
            
            // === Position Management ===
            double avgHoldingHoursWinners,  // avg hold time for winners
            double avgHoldingHoursLosers,   // avg hold time for losers
            BigDecimal grossProfitUsd,      // total gross profit
            BigDecimal grossLossUsd         // total gross loss
    ) {}

    private static final MathContext MC = new MathContext(18, RoundingMode.HALF_UP);
    private static final int SCALE = 8;
    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final BigDecimal TEN = BigDecimal.TEN;

    public static WalletMetrics fromResult(PnlEngine.Result r) {
        if (r == null || r.tradePnls == null || r.tradePnls.isEmpty()) {
            return createEmptyMetrics();
        }

        // === Basic Totals ===
        BigDecimal totalVol = r.positions.values().stream()
                .map(p -> p.volumeUsd == null ? ZERO : p.volumeUsd)
                .reduce(ZERO, BigDecimal::add);

        BigDecimal totalFees = r.totalFees == null ? ZERO : r.totalFees;
        BigDecimal totalRealized = r.totalRealized == null ? ZERO : r.totalRealized;

        BigDecimal grossProfit = r.positions.values().stream()
                .map(p -> p.grossProfitUsd == null ? ZERO : p.grossProfitUsd)
                .reduce(ZERO, BigDecimal::add);

        BigDecimal grossLoss = r.positions.values().stream()
                .map(p -> p.grossLossUsd == null ? ZERO : p.grossLossUsd)
                .reduce(ZERO, BigDecimal::add);

        // === Ratios ===
        BigDecimal pnlPct = totalVol.signum() == 0 ? ZERO
                : totalRealized.divide(totalVol, SCALE, RoundingMode.HALF_UP);

        BigDecimal profitFactor = grossLoss.signum() == 0
                ? (grossProfit.signum() == 0 ? ZERO : TEN) // cap when no losses
                : grossProfit.divide(grossLoss, SCALE, RoundingMode.HALF_UP);

        BigDecimal feeRatio = totalVol.signum() == 0 ? ZERO
                : totalFees.divide(totalVol, SCALE, RoundingMode.HALF_UP);

        // === Daily Series Stats ===
        var daily = new ArrayList<>(r.realizedPnlByDay.entrySet());
        daily.sort(Map.Entry.comparingByKey());
        int nDays = daily.size();

        double[] dailyVals = daily.stream().mapToDouble(e -> e.getValue().doubleValue()).toArray();
        double dailyMean = mean(dailyVals);
        double dailyStd = stddev(dailyVals, dailyMean);
        double sharpeLikeDaily = (dailyStd == 0.0) ? 0.0 : (dailyMean / dailyStd);

        long winDays = Arrays.stream(dailyVals).filter(v -> v > 0.0).count();
        double dailyWinRatePct = (nDays == 0) ? 0.0 : (100.0 * winDays / nDays);

        BigDecimal maxDD = maxDrawdownUsd(daily);
        int longestDrawdownDays = calculateLongestDrawdownDays(daily);

        // === Trade-Level Stats ===
        List<BigDecimal> tradePnls = r.tradePnls;
        int totalTrades = tradePnls.size();
        
        List<BigDecimal> winningTrades = tradePnls.stream()
                .filter(pnl -> pnl.compareTo(ZERO) > 0)
                .collect(Collectors.toList());
        
        List<BigDecimal> losingTrades = tradePnls.stream()
                .filter(pnl -> pnl.compareTo(ZERO) < 0)
                .collect(Collectors.toList());

        int numWinningTrades = winningTrades.size();
        int numLosingTrades = losingTrades.size();
        double tradeWinRate = totalTrades == 0 ? 0.0 : (100.0 * numWinningTrades / totalTrades);

        BigDecimal avgWinningTrade = winningTrades.isEmpty() ? ZERO
                : winningTrades.stream().reduce(ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(winningTrades.size()), SCALE, RoundingMode.HALF_UP);

        BigDecimal avgLosingTrade = losingTrades.isEmpty() ? ZERO
                : losingTrades.stream().reduce(ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(losingTrades.size()), SCALE, RoundingMode.HALF_UP);

        BigDecimal winLossRatio = avgLosingTrade.abs().signum() == 0 ? ZERO
                : avgWinningTrade.divide(avgLosingTrade.abs(), SCALE, RoundingMode.HALF_UP);

        // === Streaks ===
        int[] streaks = calculateStreaks(tradePnls);
        int maxConsecutiveWins = streaks[0];
        int maxConsecutiveLosses = streaks[1];

        // === Largest Win/Loss ===
        BigDecimal largestWin = tradePnls.stream().max(BigDecimal::compareTo).orElse(ZERO);
        BigDecimal largestLoss = tradePnls.stream().min(BigDecimal::compareTo).orElse(ZERO);

        // === Risk-Adjusted Metrics ===
        BigDecimal recoveryFactor = maxDD.signum() == 0 ? ZERO
                : totalRealized.divide(maxDD, SCALE, RoundingMode.HALF_UP);

        // Annualized return (assumes 365 days)
        BigDecimal annualReturn = nDays == 0 ? ZERO
                : totalRealized.multiply(BigDecimal.valueOf(365.0 / nDays), MC);

        BigDecimal calmarRatio = maxDD.signum() == 0 ? ZERO
                : annualReturn.divide(maxDD, SCALE, RoundingMode.HALF_UP);

        // Sortino (downside deviation)
        double sortinoRatio = calculateSortinoRatio(dailyVals, dailyMean);

        // === Monthly Stats ===
        Map<String, BigDecimal> monthlyPnl = aggregateMonthlyPnl(daily);
        double monthlyWinRate = calculateMonthlyWinRate(monthlyPnl);
        double stdDevMonthlyReturns = calculateMonthlyStdDev(monthlyPnl);

        // === Activity Metrics ===
        double tradesPerDay = nDays == 0 ? 0.0 : (double) totalTrades / nDays;
        
        BigDecimal avgTradeSize = totalTrades == 0 ? ZERO
                : totalVol.divide(BigDecimal.valueOf(totalTrades), SCALE, RoundingMode.HALF_UP);

        BigDecimal medianTradeSize = calculateMedianTradeSize(r);

        // === Recent Performance ===
        LocalDate today = daily.isEmpty() ? LocalDate.now() : daily.get(daily.size() - 1).getKey();
        LocalDate sevenDaysAgo = today.minusDays(7);
        LocalDate thirtyDaysAgo = today.minusDays(30);

        BigDecimal last7DaysPnl = calculateRecentPnl(daily, sevenDaysAgo);
        BigDecimal last30DaysPnl = calculateRecentPnl(daily, thirtyDaysAgo);

        double last7DaysWinRate = calculateRecentWinRate(daily, sevenDaysAgo);
        double last30DaysWinRate = calculateRecentWinRate(daily, thirtyDaysAgo);

        int last30DaysTrades = calculateRecentTrades(daily, thirtyDaysAgo, totalTrades, nDays);

        // === Position Management ===
        double avgHoldingHours = r.positions.values().stream()
                .mapToDouble(PnlEngine::avgHoldingHours)
                .average()
                .orElse(0.0);

        // For now, use same value for winners/losers (can be enhanced later)
        double avgHoldingHoursWinners = avgHoldingHours;
        double avgHoldingHoursLosers = avgHoldingHours;

        return new WalletMetrics(
                // Original metrics
                totalVol,
                totalRealized,
                totalFees,
                pnlPct,
                profitFactor,
                feeRatio,
                maxDD,
                sharpeLikeDaily,
                avgHoldingHours,
                nDays,
                dailyWinRatePct,
                
                // Risk metrics
                recoveryFactor,
                maxConsecutiveLosses,
                maxConsecutiveWins,
                avgWinningTrade,
                avgLosingTrade,
                winLossRatio,
                calmarRatio,
                sortinoRatio,
                largestWin,
                largestLoss,
                
                // Consistency metrics
                monthlyWinRate,
                longestDrawdownDays,
                dailyStd,
                stdDevMonthlyReturns,
                totalTrades,
                numWinningTrades,
                numLosingTrades,
                tradeWinRate,
                
                // Activity metrics
                tradesPerDay,
                avgTradeSize,
                medianTradeSize,
                
                // Recent performance
                last7DaysPnl,
                last30DaysPnl,
                last7DaysWinRate,
                last30DaysWinRate,
                last30DaysTrades,
                
                // Position management
                avgHoldingHoursWinners,
                avgHoldingHoursLosers,
                grossProfit,
                grossLoss
        );
    }

    private static WalletMetrics createEmptyMetrics() {
        return new WalletMetrics(
                ZERO, ZERO, ZERO, ZERO, ZERO, ZERO, ZERO, 0.0, 0.0, 0, 0.0,
                ZERO, 0, 0, ZERO, ZERO, ZERO, ZERO, 0.0, ZERO, ZERO,
                0.0, 0, 0.0, 0.0, 0, 0, 0, 0.0,
                0.0, ZERO, ZERO,
                ZERO, ZERO, 0.0, 0.0, 0,
                0.0, 0.0, ZERO, ZERO
        );
    }

    // ---- helpers ----

    private static double mean(double[] a) {
        if (a.length == 0) return 0.0;
        double s = 0.0;
        for (double v : a) s += v;
        return s / a.length;
    }

    private static double stddev(double[] a, double mean) {
        if (a.length == 0) return 0.0;
        double s2 = 0.0;
        for (double v : a) {
            double d = v - mean;
            s2 += d * d;
        }
        return Math.sqrt(s2 / a.length); // population std
    }

    /** Max drawdown on cumulative daily PnL. Input: sorted by date ascending. */
    private static BigDecimal maxDrawdownUsd(List<Map.Entry<LocalDate, BigDecimal>> daily) {
        BigDecimal peak = BigDecimal.ZERO;
        BigDecimal equity = BigDecimal.ZERO;
        BigDecimal maxDD = BigDecimal.ZERO;

        for (var e : daily) {
            equity = equity.add(e.getValue(), MC);
            if (equity.compareTo(peak) > 0) {
                peak = equity;
            }
            BigDecimal dd = peak.subtract(equity, MC); // drawdown (USD)
            if (dd.compareTo(maxDD) > 0) maxDD = dd;
        }
        return maxDD;
    }

    /** Calculate longest drawdown period in days */
    private static int calculateLongestDrawdownDays(List<Map.Entry<LocalDate, BigDecimal>> daily) {
        if (daily.isEmpty()) return 0;
        
        BigDecimal peak = BigDecimal.ZERO;
        BigDecimal equity = BigDecimal.ZERO;
        int longestDD = 0;
        int currentDD = 0;
        
        for (var e : daily) {
            equity = equity.add(e.getValue(), MC);
            if (equity.compareTo(peak) >= 0) {
                peak = equity;
                if (currentDD > longestDD) longestDD = currentDD;
                currentDD = 0;
            } else {
                currentDD++;
            }
        }
        
        // Check final streak
        if (currentDD > longestDD) longestDD = currentDD;
        return longestDD;
    }

    /** Calculate max consecutive wins and losses. Returns [maxWins, maxLosses] */
    private static int[] calculateStreaks(List<BigDecimal> tradePnls) {
        int maxWins = 0, maxLosses = 0;
        int currentWins = 0, currentLosses = 0;
        
        for (BigDecimal pnl : tradePnls) {
            if (pnl.compareTo(ZERO) > 0) {
                currentWins++;
                if (currentLosses > maxLosses) maxLosses = currentLosses;
                currentLosses = 0;
            } else if (pnl.compareTo(ZERO) < 0) {
                currentLosses++;
                if (currentWins > maxWins) maxWins = currentWins;
                currentWins = 0;
            }
        }
        
        // Check final streaks
        if (currentWins > maxWins) maxWins = currentWins;
        if (currentLosses > maxLosses) maxLosses = currentLosses;
        
        return new int[]{maxWins, maxLosses};
    }

    /** Calculate Sortino ratio using downside deviation */
    private static double calculateSortinoRatio(double[] dailyVals, double meanReturn) {
        if (dailyVals.length == 0) return 0.0;
        
        // Calculate downside deviation (only negative returns)
        double sumSquaredDownside = 0.0;
        int countDownside = 0;
        
        for (double val : dailyVals) {
            if (val < 0) {
                sumSquaredDownside += val * val;
                countDownside++;
            }
        }
        
        if (countDownside == 0 || sumSquaredDownside == 0.0) return 0.0;
        
        double downsideDeviation = Math.sqrt(sumSquaredDownside / countDownside);
        return downsideDeviation == 0.0 ? 0.0 : meanReturn / downsideDeviation;
    }

    /** Aggregate daily PnL into monthly buckets. Returns map of "YYYY-MM" -> totalPnl */
    private static Map<String, BigDecimal> aggregateMonthlyPnl(List<Map.Entry<LocalDate, BigDecimal>> daily) {
        Map<String, BigDecimal> monthlyPnl = new HashMap<>();
        
        for (var e : daily) {
            String monthKey = e.getKey().format(DateTimeFormatter.ofPattern("yyyy-MM"));
            monthlyPnl.merge(monthKey, e.getValue(), BigDecimal::add);
        }
        
        return monthlyPnl;
    }

    /** Calculate percentage of months with positive PnL */
    private static double calculateMonthlyWinRate(Map<String, BigDecimal> monthlyPnl) {
        if (monthlyPnl.isEmpty()) return 0.0;
        
        long winningMonths = monthlyPnl.values().stream()
                .filter(pnl -> pnl.compareTo(ZERO) > 0)
                .count();
        
        return 100.0 * winningMonths / monthlyPnl.size();
    }

    /** Calculate standard deviation of monthly returns */
    private static double calculateMonthlyStdDev(Map<String, BigDecimal> monthlyPnl) {
        if (monthlyPnl.isEmpty()) return 0.0;
        
        double[] monthlyVals = monthlyPnl.values().stream()
                .mapToDouble(BigDecimal::doubleValue)
                .toArray();
        
        double mean = mean(monthlyVals);
        return stddev(monthlyVals, mean);
    }

    /** Calculate median trade size from position volumes */
    private static BigDecimal calculateMedianTradeSize(PnlEngine.Result r) {
        if (r.positions.isEmpty()) return ZERO;
        
        List<BigDecimal> volumes = r.positions.values().stream()
                .map(p -> p.volumeUsd == null ? ZERO : p.volumeUsd)
                .sorted()
                .collect(Collectors.toList());
        
        int size = volumes.size();
        if (size == 0) return ZERO;
        
        if (size % 2 == 0) {
            return volumes.get(size / 2 - 1)
                    .add(volumes.get(size / 2))
                    .divide(BigDecimal.valueOf(2), SCALE, RoundingMode.HALF_UP);
        } else {
            return volumes.get(size / 2);
        }
    }

    /** Calculate total PnL from a specific date onwards */
    private static BigDecimal calculateRecentPnl(List<Map.Entry<LocalDate, BigDecimal>> daily, LocalDate fromDate) {
        return daily.stream()
                .filter(e -> !e.getKey().isBefore(fromDate))
                .map(Map.Entry::getValue)
                .reduce(ZERO, BigDecimal::add);
    }

    /** Calculate win rate (% positive days) from a specific date onwards */
    private static double calculateRecentWinRate(List<Map.Entry<LocalDate, BigDecimal>> daily, LocalDate fromDate) {
        List<BigDecimal> recentPnls = daily.stream()
                .filter(e -> !e.getKey().isBefore(fromDate))
                .map(Map.Entry::getValue)
                .collect(Collectors.toList());
        
        if (recentPnls.isEmpty()) return 0.0;
        
        long winDays = recentPnls.stream().filter(pnl -> pnl.compareTo(ZERO) > 0).count();
        return 100.0 * winDays / recentPnls.size();
    }

    /** Estimate number of trades in recent period (proportional to days) */
    private static int calculateRecentTrades(List<Map.Entry<LocalDate, BigDecimal>> daily, 
                                            LocalDate fromDate, int totalTrades, int totalDays) {
        if (totalDays == 0) return 0;
        
        long recentDays = daily.stream()
                .filter(e -> !e.getKey().isBefore(fromDate))
                .count();
        
        return (int) Math.round((double) totalTrades * recentDays / totalDays);
    }
}
