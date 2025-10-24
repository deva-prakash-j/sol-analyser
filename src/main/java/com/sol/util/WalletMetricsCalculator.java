package com.sol.util;

import com.sol.service.PnlEngine;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;

public class WalletMetricsCalculator {

    public record WalletMetrics(
            BigDecimal totalVolumeUsd,
            BigDecimal totalRealizedUsd,
            BigDecimal totalFeesUsd,
            BigDecimal pnlPct,          // totalRealized / totalVolume
            BigDecimal profitFactor,    // grossProfit / grossLoss
            BigDecimal feeRatio,        // totalFees / totalVolume
            BigDecimal maxDrawdownUsd,  // from cumulative daily PnL
            double sharpeLikeDaily,     // mean(daily pnl) / std(daily pnl)
            double avgHoldingHoursWeighted, // qty-weighted across positions
            int daysCount,
            double dailyWinRatePct      // % of days with pnl > 0
    ) {}

    private static final MathContext MC = new MathContext(18, RoundingMode.HALF_UP);
    private static final int SCALE = 8;
    private static final BigDecimal ZERO = BigDecimal.ZERO;

    public static WalletMetrics fromResult(PnlEngine.Result r) {
        if (r == null) {
            return new WalletMetrics(ZERO, ZERO, ZERO, ZERO, ZERO, ZERO, ZERO, 0.0, 0.0, 0, 0.0);
        }

        // === Totals across positions ===
        BigDecimal totalVol = r.positions.values().stream()
                .map(p -> p.volumeUsd == null ? ZERO : p.volumeUsd)
                .reduce(ZERO, BigDecimal::add);

        BigDecimal totalFees = r.totalFees == null ? ZERO : r.totalFees;
        BigDecimal totalRealized = r.totalRealized == null ? ZERO : r.totalRealized;

        BigDecimal pnlPct = totalVol.signum()==0 ? ZERO
                : totalRealized.divide(totalVol, SCALE, RoundingMode.HALF_UP);

        BigDecimal grossProfit = r.positions.values().stream()
                .map(p -> p.grossProfitUsd == null ? ZERO : p.grossProfitUsd)
                .reduce(ZERO, BigDecimal::add);

        BigDecimal grossLoss = r.positions.values().stream()
                .map(p -> p.grossLossUsd == null ? ZERO : p.grossLossUsd)
                .reduce(ZERO, BigDecimal::add);

        BigDecimal profitFactor = grossLoss.signum()==0
                ? (grossProfit.signum()==0 ? ZERO : new BigDecimal("10")) // cap when no losses
                : grossProfit.divide(grossLoss, SCALE, RoundingMode.HALF_UP);

        BigDecimal feeRatio = totalVol.signum()==0 ? ZERO
                : totalFees.divide(totalVol, SCALE, RoundingMode.HALF_UP);

        // === Daily series stats (from realizedPnlByDay) ===
        var daily = new ArrayList<>(r.realizedPnlByDay.entrySet());
        daily.sort(Map.Entry.comparingByKey());
        int nDays = daily.size();

        // daily mean & stddev (population)
        double[] vals = daily.stream().mapToDouble(e -> e.getValue().doubleValue()).toArray();
        double mean = mean(vals);
        double std = stddev(vals, mean); // population std
        double sharpeLikeDaily = (std == 0.0) ? 0.0 : (mean / std);

        // daily win rate (% days with positive pnl)
        long winDays = Arrays.stream(vals).filter(v -> v > 0.0).count();
        double dailyWinRatePct = (nDays == 0) ? 0.0 : (100.0 * winDays / nDays);

        // max drawdown on cumulative daily pnl
        BigDecimal maxDD = maxDrawdownUsd(daily);

        // avg holding hours (qty-weighted over realized qty) across positions
        double avgHoldingHours =
                r.positions.values().stream()
                        .mapToDouble(PnlEngine::avgHoldingHours)
                        .average()
                        .orElse(0.0);

        return new WalletMetrics(
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
                dailyWinRatePct
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
}
