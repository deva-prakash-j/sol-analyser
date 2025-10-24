package com.sol.util;

import com.sol.dto.Transaction;
import com.sol.service.PnlEngine;
import com.sol.service.PriceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.*;
import java.util.stream.Collectors;

import static com.sol.util.Constant.QUOTE_MINTS;

@Service
@RequiredArgsConstructor
@Slf4j
public class NormalizeTransaction {

    private final PriceService priceService;

    private final String solanaMint = "So11111111111111111111111111111111111111112";

    private final BigDecimal LAMPORTS_PER_SOL = new BigDecimal("1000000000");

    private static final MathContext MC = new MathContext(18, RoundingMode.HALF_UP);
    private static BigDecimal bd(Double d) { return d == null ? BigDecimal.ZERO : BigDecimal.valueOf(d); }

    /** output row matching the final SELECT */
    public record Row(
            String signature,
            String wallet,
            Long blockTime,
            Long blockSlot,
            String quoteMint,
            String baseMint,
            BigDecimal baseAmount,
            BigDecimal quoteAmount,
            Side side,                 // BUY / SELL / UNKNOWN
            BigDecimal priceQuotePerBase,
            BigDecimal priceUsdPerBase,
            BigDecimal notionalUsd,
            BigDecimal feeSol,
            BigDecimal feeUsd
    ) {}

    public enum Side { BUY, SELL, UNKNOWN }

    /** prints rows; keep signature unchanged */
    public void process(List<Transaction> transactions, String wallet) {
        List<Row> rows = processToList(dedupeLatestBySlot(transactions), wallet);
        rows = rows.stream().filter(r -> List.of(Side.BUY, Side.SELL).contains(r.side))
                .sorted(Comparator.comparing(Row::blockTime)).collect(Collectors.toList());
        PnlEngine.Result result = PnlEngine.run(rows);
        var metrics = WalletMetricsCalculator.fromResult(result);

        log.info("Completed");
    }

    public List<Transaction> dedupeLatestBySlot(List<Transaction> txs) {
        Map<String, Transaction> bySig = new HashMap<>();
        for (var t : txs) {
            var sigOpt = primarySignature(t);
            if (sigOpt.isEmpty()) continue;
            var sig = sigOpt.get();
            bySig.merge(sig, t, (a, b) -> {
                Long sa = Optional.ofNullable(a.getResult()).map(Transaction.Result::getSlot).orElse(0L);
                Long sb = Optional.ofNullable(b.getResult()).map(Transaction.Result::getSlot).orElse(0L);
                return (sb != null && sb > sa) ? b : a;
            });
        }
        // Optional: stable order by slot desc, then signature
        return bySig.values().stream()
                .sorted(Comparator
                        .comparing((Transaction x) -> Optional.ofNullable(x.getResult()).map(Transaction.Result::getSlot).orElse(0L))
                        .reversed()
                        .thenComparing(x -> primarySignature(x).orElse("")))
                .toList();
    }

    Optional<String> primarySignature(Transaction t) {
        return Optional.ofNullable(t)
                .map(Transaction::getResult)
                .map(Transaction.Result::getTransaction)
                .map(Transaction.Tx::getSignatures)
                .filter(list -> !list.isEmpty())
                .map(list -> list.getFirst());
    }

    public List<Row> processToList(List<Transaction> transactions, String wallet) {
        if (transactions == null || transactions.isEmpty()) return List.of();

        // ---- tuning constants for multi-hop guard ----
        final BigDecimal EPS = new BigDecimal("0.0000001");       // ignore tiny token dust
        final BigDecimal MIN_COVERAGE = new BigDecimal("0.80");   // dominant legs explain ≥80% of abs flow
        final BigDecimal MAX_SIDE_LEAK = new BigDecimal("0.20");  // other legs per side ≤20%
        final int SCALE = 18;

        // tx-level accumulator: key = (signature, wallet) → mint → delta
        record TxKey(String signature, String wallet) {}
        Map<TxKey, Map<String, BigDecimal>> deltaByTxMint = new HashMap<>();

        // keep tx meta needed for the final projection
        record MetaBits(Long blockTime, Long slot, BigDecimal feeSol) {}
        Map<String, MetaBits> metaBySig = new HashMap<>();

        for (var t : transactions) {
            if (t == null || t.getResult() == null || t.getResult().getTransaction() == null) continue;

            var res = t.getResult();
            var tx = res.getTransaction();
            var sig = firstOrNull(tx.getSignatures());
            if (sig == null) continue;

            // success filter (status.err == null)
            var meta = res.getMeta();
            if (meta == null) continue;
            var status = meta.getStatus();
            if (status == null || status.getErr() != null) continue;

            // cache meta bits per signature
            BigDecimal feeLamports = BigDecimal.valueOf(
                    Optional.ofNullable(meta.getFee()).orElse(0L)
            );
            BigDecimal feeSol = feeLamports.divide(LAMPORTS_PER_SOL, SCALE, RoundingMode.HALF_UP);
            metaBySig.put(sig, new MetaBits(res.getBlockTime(), res.getSlot(), feeSol));

            var key = new TxKey(sig, wallet);
            var mintToDelta = deltaByTxMint.computeIfAbsent(key, k -> new HashMap<>());

            // + post balances for this wallet
            var posts = meta.getPostTokenBalances();
            if (posts != null) {
                for (var b : posts) {
                    if (b != null && Objects.equals(wallet, b.getOwner())) {
                        mintToDelta.merge(b.getMint(), amountOf(b), BigDecimal::add);
                    }
                }
            }

            // - pre balances for this wallet
            var pres = meta.getPreTokenBalances();
            if (pres != null) {
                for (var b : pres) {
                    if (b != null && Objects.equals(wallet, b.getOwner())) {
                        mintToDelta.merge(b.getMint(), amountOf(b).negate(), BigDecimal::add);
                    }
                }
            }
        }

        // convert each tx to a paired row with multi-hop guard
        List<Row> out = new ArrayList<>();

        for (var e : deltaByTxMint.entrySet()) {
            var key = e.getKey();
            var mintDelta = e.getValue();

            // prune dust
            Map<String, BigDecimal> nz = mintDelta.entrySet().stream()
                    .filter(x -> x.getValue() != null && x.getValue().abs().compareTo(EPS) >= 0)
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
            if (nz.size() < 2) continue;

            // split by sign
            List<Map.Entry<String, BigDecimal>> positives = nz.entrySet().stream()
                    .filter(x -> x.getValue().compareTo(BigDecimal.ZERO) > 0).toList();
            List<Map.Entry<String, BigDecimal>> negatives = nz.entrySet().stream()
                    .filter(x -> x.getValue().compareTo(BigDecimal.ZERO) < 0).toList();
            if (positives.isEmpty() || negatives.isEmpty()) continue;

            // dominant per side
            var domPos = positives.stream().max(Comparator.comparing(x -> x.getValue().abs())).orElse(null);
            var domNeg = negatives.stream().max(Comparator.comparing(x -> x.getValue().abs())).orElse(null);
            if (domPos == null || domNeg == null) continue;

            String posMint = domPos.getKey();
            String negMint = domNeg.getKey();
            BigDecimal posAbs = domPos.getValue().abs();
            BigDecimal negAbs = domNeg.getValue().abs();

            // coverage test
            BigDecimal totalAbs = nz.values().stream().map(BigDecimal::abs)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (totalAbs.signum() == 0) continue;

            BigDecimal coverage = posAbs.add(negAbs).divide(totalAbs, 6, RoundingMode.HALF_UP);
            if (coverage.compareTo(MIN_COVERAGE) < 0) continue; // likely multi-hop

            // per-side leakage test
            BigDecimal otherPos = positives.stream().filter(x -> !x.getKey().equals(posMint))
                    .map(x -> x.getValue().abs()).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal otherNeg = negatives.stream().filter(x -> !x.getKey().equals(negMint))
                    .map(x -> x.getValue().abs()).reduce(BigDecimal.ZERO, BigDecimal::add);

            BigDecimal posLeak = (posAbs.add(otherPos).signum() == 0) ? BigDecimal.ZERO
                    : otherPos.divide(posAbs.add(otherPos), 6, RoundingMode.HALF_UP);
            BigDecimal negLeak = (negAbs.add(otherNeg).signum() == 0) ? BigDecimal.ZERO
                    : otherNeg.divide(negAbs.add(otherNeg), 6, RoundingMode.HALF_UP);

            if (posLeak.compareTo(MAX_SIDE_LEAK) > 0 || negLeak.compareTo(MAX_SIDE_LEAK) > 0) continue; // likely multi-hop

            // enforce quote/base identification
            boolean posIsQuote = QUOTE_MINTS.contains(posMint);
            boolean negIsQuote = QUOTE_MINTS.contains(negMint);

            String quoteMint, baseMint;
            Side side;
            BigDecimal baseAmtAbs, quoteAmtAbs;

            if (posIsQuote && !negIsQuote) {
                quoteMint = posMint; baseMint = negMint;
                baseAmtAbs = negAbs; quoteAmtAbs = posAbs;
                side = Side.SELL;
            } else if (negIsQuote && !posIsQuote) {
                quoteMint = negMint; baseMint = posMint;
                baseAmtAbs = posAbs; quoteAmtAbs = negAbs;
                side = Side.BUY;
            } else {
                // neither or both look like quote → ambiguous/LP → skip
                continue;
            }

            // price (quote/base)
            BigDecimal priceQuotePerBase = BigDecimal.ZERO;
            if (baseAmtAbs.compareTo(BigDecimal.ZERO) != 0) {
                priceQuotePerBase = quoteAmtAbs.divide(baseAmtAbs, SCALE, RoundingMode.HALF_UP);
            }

            var meta = metaBySig.getOrDefault(key.signature, new MetaBits(null, null, BigDecimal.ZERO));

            // USD conversions
            BigDecimal usdPerSol = Optional.ofNullable(priceService.getPrice(solanaMint, meta.blockTime()))
                    .orElse(BigDecimal.ZERO);

            BigDecimal usdPerQuote = quoteMint.equalsIgnoreCase(solanaMint)
                    ? usdPerSol
                    : Optional.ofNullable(priceService.getPrice(quoteMint, meta.blockTime()))
                    .orElse(BigDecimal.ZERO);

            BigDecimal priceUsdPerBase = priceQuotePerBase.multiply(usdPerQuote, MC);
            BigDecimal notionalUsd = baseAmtAbs.multiply(priceUsdPerBase, MC);
            BigDecimal feeUsd = meta.feeSol().multiply(usdPerSol, MC);

            out.add(new Row(
                    key.signature,
                    key.wallet,
                    meta.blockTime(),
                    meta.slot(),
                    quoteMint,
                    baseMint,
                    baseAmtAbs,
                    quoteAmtAbs,
                    side,
                    priceQuotePerBase,
                    priceUsdPerBase,
                    notionalUsd,
                    meta.feeSol(),
                    feeUsd
            ));
        }

        // stable, deterministic order (by block_time desc, then signature)
        out.sort(Comparator
                .comparing(Row::blockTime, Comparator.nullsLast(Comparator.naturalOrder())).reversed()
                .thenComparing(Row::signature));

        return out;
    }

    /* ------------------------------ helpers ------------------------------ */

    private String firstOrNull(List<String> list) {
        return (list == null || list.isEmpty()) ? null : list.getFirst();
    }

    private Optional<Long> optLong(Long v) { return Optional.ofNullable(v); }

    /** choose uiAmountString when present; else amount / 10^decimals */
    private BigDecimal amountOf(Transaction.TokenBalance b) {
        if (b == null || b.getUiTokenAmount() == null) return BigDecimal.ZERO;
        var ui = b.getUiTokenAmount();

        // Prefer exact decimal string if provided
        if (ui.getUiAmountString() != null && !ui.getUiAmountString().isEmpty()) {
            try { return new BigDecimal(ui.getUiAmountString()); }
            catch (NumberFormatException ignored) {}
        }

        // Fallback: amount (raw) / 10^decimals
        try {
            var raw = new BigDecimal(ui.getAmount() == null ? "0" : ui.getAmount());
            var scale = Optional.ofNullable(ui.getDecimals()).orElse(0);
            var denom = BigDecimal.TEN.pow(scale);
            return raw.divide(denom, MC);
        } catch (Exception e) {
            return Optional.ofNullable(ui.getUiAmount()).orElse(BigDecimal.ZERO);
        }
    }


}

