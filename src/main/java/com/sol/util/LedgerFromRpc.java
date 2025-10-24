package com.sol.util;

// (optional) package com.yourorg.ledger;

import com.sol.dto.Transaction;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class LedgerFromRpc {

    /** Quote mints per your CTE. */
    private static final Set<String> QUOTE_MINTS = Set.of(
            "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v", // USDC
            "Es9vMFrzaCERmJfrF4H2FYD4KCoNkY11McCe8BenwNYB", // USDT
            "So11111111111111111111111111111111111111112",   // wSOL
            "2b1kV6DkPAnxd5ixfnxCpjxmKwqjjaYmCZfHsFu24GXo", // PyUSD
            "2u1tszSeqZ3qBWF3uNGPFc8TzMk2tdiwknnRMWGWjGWH", // USDG
            "USDH1SM1ojwWUga67PGrgFWUHibbjqMvuMaDkRJTgkX",  // USDH
            "7kbnvuGBxxj8AG9qp8Scn56muWGaRaFqxg1FsRp3PaFT"  // UXD
    );

    private static final BigDecimal EPS = new BigDecimal("1e-9");

    /** Output = your final_rows CTE. */
    public record FinalRow(
            String signature,
            String wallet,
            String baseMint,
            BigDecimal baseAmount,
            String side,           // BUY / SELL / UNKNOWN
            Long block_time,       // epoch seconds
            Long block_slot
    ) {}

    /** Internal row like wm CTE. */
    private record WmRow(
            String signature,
            Long blockTime,
            Long blockSlot,
            Long feeLamports,
            String wallet,
            String mint,
            BigDecimal delta // +received, -sent
    ) {}

    /** Public API: build final_rows for a given wallet from many RPC results. */
    public static List<FinalRow> buildFinalRowsForWallet(
            List<Transaction> rpcList,
            String walletBase58
    ) {
        long now = Instant.now().getEpochSecond();

        // tx (filter: success && block_time >= now-1d)
        List<Transaction.Result> results = rpcList.stream()
                .map(Transaction::getResult)
                .filter(Objects::nonNull)
                .filter(res -> isSuccess(res) && res.getBlockTime() != null)
                .toList();

        // wm: per-tx, per mint, post - pre (only rows where owner == wallet)
        List<WmRow> wm = results.stream()
                .flatMap(res -> computeWmRows(res, walletBase58).stream())
                .filter(r -> r.delta.abs().compareTo(EPS) > 0)
                .toList();
        System.out.println(wm.size());

        // deduped / tx_1d: keep latest block_time per (signature, wallet)
        Map<List<String>, WmRow> latestBySigWallet = wm.stream()
                .collect(Collectors.toMap(
                        r -> List.of(r.signature, r.wallet),
                        Function.identity(),
                        (a, b) -> (a.blockTime >= b.blockTime) ? a : b
                ));
        System.out.println(latestBySigWallet.size());
        Set<List<String>> keepKeys = latestBySigWallet.keySet();
        List<WmRow> wmDeduped = wm.stream()
                .filter(r -> keepKeys.contains(List.of(r.signature, r.wallet)))
                .toList();

        // paired: pick the pos/neg mint with max |delta| per (signature, wallet)
        record Paired(String signature, String wallet, Long blockTime, Long blockSlot, Long feeLamports,
                      String posMint, BigDecimal posDelta, String negMint, BigDecimal negDelta) {}
        Map<List<String>, Paired> paired = new HashMap<>();

        wmDeduped.stream()
                .collect(Collectors.groupingBy(r -> List.of(r.signature, r.wallet)))
                .forEach((k, rows) -> {
                    WmRow pos = rows.stream().filter(r -> r.delta.signum() > 0)
                            .max(Comparator.comparing(r -> r.delta.abs()))
                            .orElse(null);
                    WmRow neg = rows.stream().filter(r -> r.delta.signum() < 0)
                            .max(Comparator.comparing(r -> r.delta.abs()))
                            .orElse(null);

                    if (pos != null && neg != null) {
                        // Use max block_time/slot/fee like SQL
                        long bt = rows.stream().map(r -> r.blockTime).max(Long::compare).orElse(0L);
                        long bs = rows.stream().map(r -> r.blockSlot).max(Long::compare).orElse(0L);
                        long fee = rows.stream().map(r -> r.feeLamports).max(Long::compare).orElse(0L);
                        paired.put(k, new Paired(
                                rows.getFirst().signature,
                                rows.getFirst().wallet,
                                bt, bs, fee,
                                pos.mint, pos.delta,
                                neg.mint, neg.delta
                        ));
                    }
                });

        // ledger → final_rows
        List<FinalRow> out = new ArrayList<>();
        for (Paired p : paired.values()) {
            boolean posIsQuote = QUOTE_MINTS.contains(p.posMint);
            boolean negIsQuote = QUOTE_MINTS.contains(p.negMint);

            String baseMint, quoteMint, side;
            BigDecimal baseAmount, quoteAmount;

            if (posIsQuote) {
                quoteMint  = p.posMint;
                baseMint   = p.negMint;
                baseAmount = p.negDelta.abs();
                quoteAmount= p.posDelta.abs();
                side       = "SELL";  // giving quote, receiving base? (matches your CASE)
            } else if (negIsQuote) {
                quoteMint  = p.negMint;
                baseMint   = p.posMint;
                baseAmount = p.posDelta.abs();
                quoteAmount= p.negDelta.abs();
                side       = "BUY";
            } else {
                // default (no quote mint detected): mimic your ELSE branch
                quoteMint  = p.negMint;
                baseMint   = p.posMint;
                baseAmount = p.posDelta.abs();
                quoteAmount= p.negDelta.abs();
                side       = "UNKNOWN";
            }

            // price_quote_per_base could be quoteAmount/baseAmount if you need it:
            // BigDecimal price = (baseAmount.signum() == 0) ? null
            //        : quoteAmount.divide(baseAmount, 18, RoundingMode.HALF_UP);

            out.add(new FinalRow(
                    p.signature,
                    p.wallet,
                    baseMint,
                    baseAmount,
                    side,
                    p.blockTime,
                    p.blockSlot
            ));
        }
        return out;
    }

    /* ------------------------------- helpers ------------------------------- */

    private static boolean isSuccess(Transaction.Result res) {
        if (res.getMeta() == null) return false;
        // Treat success like your SQL: success = TRUE when no error in meta
        if (res.getMeta().getErr() != null) return false;
        // Also accept status.Ok present
        var s = res.getMeta().getStatus();
        if (s == null) return true;
        return s.getErr() == null; // Ok or null
    }

    /** Build wm rows (per-mint deltas) for this tx + wallet. */
    private static List<WmRow> computeWmRows(Transaction.Result res, String wallet) {
        var meta = res.getMeta();
        if (meta == null) return List.of();

        Map<String, BigDecimal> postByMint = safe(meta.getPostTokenBalances()).stream()
                .filter(tb -> wallet.equalsIgnoreCase(nullToEmpty(tb.getOwner())))
                .collect(Collectors.groupingBy(
                        Transaction.TokenBalance::getMint,
                        Collectors.mapping(LedgerFromRpc::toUiAmount,
                                Collectors.reducing(BigDecimal.ZERO, BigDecimal::add))
                ));

        Map<String, BigDecimal> preByMint = safe(meta.getPreTokenBalances()).stream()
                .filter(tb -> wallet.equalsIgnoreCase(nullToEmpty(tb.getOwner())))
                .collect(Collectors.groupingBy(
                        Transaction.TokenBalance::getMint,
                        Collectors.mapping(LedgerFromRpc::toUiAmount,
                                Collectors.reducing(BigDecimal.ZERO, BigDecimal::add))
                ));

        Set<String> mints = new HashSet<>(postByMint.keySet());
        mints.addAll(preByMint.keySet());

        String sig = firstOrNull(res.getTransaction() != null ? res.getTransaction().getSignatures() : null);
        Long bt = res.getBlockTime();
        Long slot = res.getSlot();
        Long feeLamports = meta.getFee();

        List<WmRow> out = new ArrayList<>();
        for (String mint : mints) {
            BigDecimal delta = postByMint.getOrDefault(mint, BigDecimal.ZERO)
                    .subtract(preByMint.getOrDefault(mint, BigDecimal.ZERO));
            out.add(new WmRow(sig, bt, slot, feeLamports, wallet, mint, delta));
        }
        return out;
    }

    private static BigDecimal toUiAmount(Transaction.TokenBalance tb) {
        if (tb == null || tb.getUiTokenAmount() == null) return BigDecimal.ZERO;
        var ui = tb.getUiTokenAmount();

        if (ui.getUiAmount() != null) return ui.getUiAmount();

        String raw = ui.getAmount();
        Integer dec = ui.getDecimals();
        if (raw == null || dec == null) return BigDecimal.ZERO;

        var num = new BigDecimal(raw);
        if (dec == 0) return num;
        return num.divide(BigDecimal.TEN.pow(dec), dec, RoundingMode.DOWN);
    }

    private static <T> List<T> safe(List<T> xs) { return xs == null ? List.of() : xs; }
    private static String nullToEmpty(String s) { return s == null ? "" : s; }
    private static String firstOrNull(List<String> xs) { return (xs == null || xs.isEmpty()) ? null : xs.get(0); }

    private LedgerFromRpc() {}
}
