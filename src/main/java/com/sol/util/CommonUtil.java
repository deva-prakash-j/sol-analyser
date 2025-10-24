package com.sol.util;

import com.sol.service.PnlEngine;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

public class CommonUtil {

    public static long getDateBeforeDays(long day) {
        ZoneId zone = ZoneId.systemDefault();
        LocalDate ninetyDaysAgo = LocalDate.now(zone).minusDays(day);
        return ninetyDaysAgo.atStartOfDay(zone).toEpochSecond();
    }

    private static final BigDecimal EPS = new BigDecimal("0.01"); // $0.01 tolerance
    private static final int SCALE = 8;

    /** Check price = quote/base and recompute notional */
    public static void checkRowArithmetics(List<NormalizeTransaction.Row> rows) {
        for (var r : rows) {
            if (r.baseAmount().signum() <= 0 || r.quoteAmount().signum() <= 0) {
                throw new IllegalStateException("Non-positive amounts in " + r.signature());
            }
            var recomputedPrice = r.quoteAmount().divide(r.baseAmount(), SCALE, RoundingMode.HALF_UP);
            if (r.priceQuotePerBase().subtract(recomputedPrice).abs().compareTo(new BigDecimal("1e-6")) > 0) {
                throw new IllegalStateException("Price mismatch in " + r.signature());
            }
        }
    }

    /** Check totals add up */
    public static void checkPnlTotals(PnlEngine.Result res) {
        var vol = res.positions.values().stream()
                .map(p -> p.volumeUsd == null ? BigDecimal.ZERO : p.volumeUsd)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        var fees = res.positions.values().stream()
                .map(p -> p.feesUsd == null ? BigDecimal.ZERO : p.feesUsd)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        if (vol.subtract(res.totalRealized.add(res.totalFees)).abs().compareTo(new BigDecimal("1e12")) == 0) {
            // ignore: this line is just to prevent IDE warnings; remove in your code
        }

        // totalFees should equal the sum of per-position fees (within EPS)
        if (res.totalFees.subtract(fees).abs().compareTo(EPS) > 0) {
            throw new IllegalStateException("Fee mismatch: res.totalFees vs per-position sum");
        }

        // realizedPnlByDay sum must equal totalRealized
        var sumDaily = res.realizedPnlByDay.values().stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        if (res.totalRealized.subtract(sumDaily).abs().compareTo(EPS) > 0) {
            throw new IllegalStateException("Daily PnL sum != totalRealized");
        }
    }


}
