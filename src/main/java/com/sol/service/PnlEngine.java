package com.sol.service;

import lombok.ToString;

import static com.sol.util.NormalizeTransaction.Row;
import static com.sol.util.NormalizeTransaction.Side;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;

public class PnlEngine {

    @ToString
    static class Lot {
        public BigDecimal qty;             // remaining qty
        public BigDecimal costPerUnitUsd;  // USD per token
        public long ts;
        Lot(BigDecimal qty, BigDecimal cpu, long ts){ this.qty=qty; this.costPerUnitUsd=cpu; this.ts=ts; }
    }

    /** Per-mint position */
    @ToString
    public static class Position {
        public String mint;
        public Deque<Lot> fifo = new ArrayDeque<>();
        public BigDecimal realizedPnlUsd = BigDecimal.ZERO;
        public BigDecimal grossProfitUsd = BigDecimal.ZERO;
        public BigDecimal grossLossUsd   = BigDecimal.ZERO;
        public BigDecimal volumeUsd      = BigDecimal.ZERO;
        public BigDecimal feesUsd        = BigDecimal.ZERO;
        public long vwapHoldingSecondsNumer = 0L;  // Σ(holding_seconds * qtySold)
        public BigDecimal qtySoldForHP = BigDecimal.ZERO;

        Position(String mint){ this.mint = mint; }
    }

    @ToString
    public static class Result {
        public Map<String, Position> positions = new HashMap<>();  // mint -> position
        public Map<LocalDate, BigDecimal> realizedPnlByDay = new TreeMap<>();
        public Map<LocalDate, BigDecimal> volumeByDay = new TreeMap<>();
        public BigDecimal totalRealized = BigDecimal.ZERO;
        public BigDecimal totalFees = BigDecimal.ZERO;
        public List<BigDecimal> tradePnls = new ArrayList<>();
    }

    private static final MathContext MC = new MathContext(18, RoundingMode.HALF_UP);
    private static final BigDecimal ZERO = BigDecimal.ZERO;

    public static Result run(List<Row> rows) {
        // sort by time asc
        rows.sort(Comparator.comparingLong(Row::blockTime));

        Result res = new Result();

        for (Row r : rows) {
            if (r.side() == null || r.baseMint() == null) continue;
            Position p = res.positions.computeIfAbsent(r.baseMint(), Position::new);
            LocalDate day = Instant.ofEpochSecond(r.blockTime()).atOffset(ZoneOffset.UTC).toLocalDate();

            if (r.side() == Side.BUY) {
                // Option A: add fee to cost basis (buy side)
                BigDecimal feePerUnit = r.baseAmount().compareTo(ZERO) == 0 ? ZERO
                        : r.feeUsd().divide(r.baseAmount(), MC);
                BigDecimal cpu = r.priceUsdPerBase().add(feePerUnit, MC);
                p.fifo.addLast(new Lot(r.baseAmount(), cpu, r.blockTime()));
                p.volumeUsd = p.volumeUsd.add(r.notionalUsd(), MC);
                p.feesUsd   = p.feesUsd.add(r.feeUsd() == null ? ZERO : r.feeUsd(), MC);

            } else { // SELL
                BigDecimal remaining = r.baseAmount();
                BigDecimal proceeds = r.notionalUsd(); // USD out
                BigDecimal costOut  = ZERO;
                long weightedSecSum = 0L;
                BigDecimal soldForHP = ZERO;

                while (remaining.compareTo(new BigDecimal("1e-15")) > 0 && !p.fifo.isEmpty()) {
                    Lot lot = p.fifo.peekFirst();
                    BigDecimal take = remaining.min(lot.qty);
                    costOut = costOut.add(take.multiply(lot.costPerUnitUsd, MC), MC);

                    // holding period contribution
                    long sec = Math.max(0L, r.blockTime() - lot.ts);
                    weightedSecSum += (long) (sec * take.doubleValue());
                    soldForHP = soldForHP.add(take, MC);

                    lot.qty = lot.qty.subtract(take, MC);
                    remaining = remaining.subtract(take, MC);
                    if (lot.qty.compareTo(new BigDecimal("1e-15")) <= 0) p.fifo.removeFirst();
                }

                // If remaining > 0, we sold more than we had → treat remainder as zero-cost (or flag)
                if (remaining.compareTo(new BigDecimal("1e-15")) > 0) {
                    // Option: count as zero-cost (conservative)
                    // costOut stays as-is; you may want to log this anomaly.
                }

                BigDecimal pnl = proceeds.subtract(costOut, MC);

                // Option B: subtract fee from sell proceeds (sell side)
                if (r.feeUsd() != null) {
                    pnl = pnl.subtract(r.feeUsd(), MC);
                    p.feesUsd = p.feesUsd.add(r.feeUsd(), MC);
                    res.tradePnls.add(pnl);
                }

                p.realizedPnlUsd = p.realizedPnlUsd.add(pnl, MC);
                if (pnl.signum() >= 0) p.grossProfitUsd = p.grossProfitUsd.add(pnl, MC);
                else                   p.grossLossUsd   = p.grossLossUsd.add(pnl.abs(MC), MC);
                p.volumeUsd = p.volumeUsd.add(proceeds, MC);

                p.vwapHoldingSecondsNumer += weightedSecSum;
                p.qtySoldForHP = p.qtySoldForHP.add(soldForHP, MC);

                // daily
                res.realizedPnlByDay.merge(day, pnl, BigDecimal::add);
                res.volumeByDay.merge(day, proceeds, BigDecimal::add);
            }
        }

        // totals
        res.totalRealized = res.positions.values().stream()
                .map(pos -> pos.realizedPnlUsd).reduce(ZERO, BigDecimal::add);
        res.totalFees = res.positions.values().stream()
                .map(pos -> pos.feesUsd).reduce(ZERO, BigDecimal::add);
        return res;
    }

    /** Helper: average holding period in hours for a position (only over realized qty) */
    public static double avgHoldingHours(Position p) {
        if (p.qtySoldForHP.compareTo(ZERO) == 0) return 0.0;
        double seconds = p.vwapHoldingSecondsNumer / p.qtySoldForHP.doubleValue();
        return seconds / 3600.0;
    }

}
