package com.sol.service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.sol.dto.WalletCard;
import com.sol.util.NormalizeTransaction;
import static com.sol.util.NormalizeTransaction.Side;

@Service
public class WalletCardService {
    
    private static final MathContext MC = new MathContext(18, RoundingMode.HALF_UP);
    private static final BigDecimal ZERO = BigDecimal.ZERO;
    
    /**
     * Internal class to track token lots for FIFO P&L calculation
     */
    private static class TokenLot {
        public BigDecimal quantity;           // remaining quantity
        public BigDecimal costPerUnitUsd;     // USD cost per token
        public BigDecimal costPerUnitSol;     // SOL cost per token
        @SuppressWarnings("unused") // Used for tracking purchase date context
        public LocalDate purchaseDate;        // day when purchased
        
        public TokenLot(BigDecimal quantity, BigDecimal costPerUnitUsd, BigDecimal costPerUnitSol, LocalDate purchaseDate) {
            this.quantity = quantity;
            this.costPerUnitUsd = costPerUnitUsd;
            this.costPerUnitSol = costPerUnitSol;
            this.purchaseDate = purchaseDate;
        }
    }
    
    /**
     * Calculates day-wise realized P&L for the last 7 days from transaction data.
     * Uses FIFO (First-In-First-Out) methodology to match sells with buys.
     * If a token sold on day N was bought on day N+1 (8th day), it's included in day N's P&L.
     * 
     * @param transactions List of normalized transaction rows (should include 8 days of data)
     * @return List of 7 daily P&L objects (index 0 = most recent day, index 6 = 7th day ago)
     */
    public List<WalletCard.PnL> calculateDayWisePnL(List<NormalizeTransaction.Row> transactions) {
        if (transactions == null || transactions.isEmpty()) {
            return createEmptyPnLList();
        }
        
        // Sort transactions by time ascending (oldest first)
        List<NormalizeTransaction.Row> sortedTxs = transactions.stream()
            .filter(row -> row.blockTime() != null && row.side() != null && row.baseMint() != null)
            .sorted(Comparator.comparingLong(NormalizeTransaction.Row::blockTime))
            .collect(Collectors.toList());
        
        if (sortedTxs.isEmpty()) {
            return createEmptyPnLList();
        }
        
        // Get date range for last 7 days
        LocalDate latestDate = Instant.ofEpochSecond(sortedTxs.get(sortedTxs.size() - 1).blockTime())
            .atOffset(ZoneOffset.UTC).toLocalDate();
        List<LocalDate> last7Days = new ArrayList<>();
        for (int i = 6; i >= 0; i--) {
            last7Days.add(latestDate.minusDays(i));
        }
        
        // Track positions per token (mint -> lots queue)
        Map<String, Queue<TokenLot>> tokenPositions = new HashMap<>();
        
        // Track daily P&L
        Map<LocalDate, BigDecimal> dailyPnlUsd = new HashMap<>();
        Map<LocalDate, BigDecimal> dailyPnlSol = new HashMap<>();
        
        // Initialize daily P&L for last 7 days
        for (LocalDate date : last7Days) {
            dailyPnlUsd.put(date, ZERO);
            dailyPnlSol.put(date, ZERO);
        }
        
        // Process each transaction
        for (NormalizeTransaction.Row row : sortedTxs) {
            LocalDate txDate = Instant.ofEpochSecond(row.blockTime()).atOffset(ZoneOffset.UTC).toLocalDate();
            String mint = row.baseMint();
            
            // Initialize position for this token if needed
            tokenPositions.computeIfAbsent(mint, k -> new LinkedList<>());
            Queue<TokenLot> lots = tokenPositions.get(mint);
            
            if (row.side() == Side.BUY) {
                // Add to position
                BigDecimal costPerUnitUsd = row.priceUsdPerBase() != null ? row.priceUsdPerBase() : ZERO;
                BigDecimal costPerUnitSol = row.priceQuotePerBase() != null ? row.priceQuotePerBase() : ZERO;
                
                // Add fees to cost basis
                if (row.baseAmount() != null && row.baseAmount().compareTo(ZERO) > 0) {
                    if (row.feeUsd() != null) {
                        BigDecimal feePerUnitUsd = row.feeUsd().divide(row.baseAmount(), MC);
                        costPerUnitUsd = costPerUnitUsd.add(feePerUnitUsd, MC);
                    }
                    if (row.feeSol() != null) {
                        BigDecimal feePerUnitSol = row.feeSol().divide(row.baseAmount(), MC);
                        costPerUnitSol = costPerUnitSol.add(feePerUnitSol, MC);
                    }
                }
                
                lots.offer(new TokenLot(row.baseAmount(), costPerUnitUsd, costPerUnitSol, txDate));
                
            } else if (row.side() == Side.SELL) {
                // Sell - match against existing lots using FIFO
                BigDecimal remainingToSell = row.baseAmount();
                BigDecimal sellPriceUsd = row.priceUsdPerBase() != null ? row.priceUsdPerBase() : ZERO;
                BigDecimal sellPriceSol = row.priceQuotePerBase() != null ? row.priceQuotePerBase() : ZERO;
                
                BigDecimal totalPnlUsd = ZERO;
                BigDecimal totalPnlSol = ZERO;
                
                // Match against lots (FIFO)
                while (remainingToSell.compareTo(new BigDecimal("1e-15")) > 0 && !lots.isEmpty()) {
                    TokenLot lot = lots.peek();
                    BigDecimal sellQty = remainingToSell.min(lot.quantity);
                    
                    // Calculate P&L for this portion
                    BigDecimal proceedsUsd = sellQty.multiply(sellPriceUsd, MC);
                    BigDecimal proceedsSol = sellQty.multiply(sellPriceSol, MC);
                    BigDecimal costUsd = sellQty.multiply(lot.costPerUnitUsd, MC);
                    BigDecimal costSol = sellQty.multiply(lot.costPerUnitSol, MC);
                    
                    BigDecimal pnlUsd = proceedsUsd.subtract(costUsd, MC);
                    BigDecimal pnlSol = proceedsSol.subtract(costSol, MC);
                    
                    // Subtract sell fees
                    if (row.feeUsd() != null && row.baseAmount().compareTo(ZERO) > 0) {
                        BigDecimal feePortionUsd = row.feeUsd().multiply(sellQty, MC).divide(row.baseAmount(), MC);
                        pnlUsd = pnlUsd.subtract(feePortionUsd, MC);
                    }
                    if (row.feeSol() != null && row.baseAmount().compareTo(ZERO) > 0) {
                        BigDecimal feePortionSol = row.feeSol().multiply(sellQty, MC).divide(row.baseAmount(), MC);
                        pnlSol = pnlSol.subtract(feePortionSol, MC);
                    }
                    
                    totalPnlUsd = totalPnlUsd.add(pnlUsd, MC);
                    totalPnlSol = totalPnlSol.add(pnlSol, MC);
                    
                    // Update lot quantity
                    lot.quantity = lot.quantity.subtract(sellQty, MC);
                    remainingToSell = remainingToSell.subtract(sellQty, MC);
                    
                    // Remove lot if fully used
                    if (lot.quantity.compareTo(new BigDecimal("1e-15")) <= 0) {
                        lots.poll();
                    }
                }
                
                // Add P&L to the sell date (if it's in our 7-day window)
                if (last7Days.contains(txDate)) {
                    dailyPnlUsd.put(txDate, dailyPnlUsd.get(txDate).add(totalPnlUsd, MC));
                    dailyPnlSol.put(txDate, dailyPnlSol.get(txDate).add(totalPnlSol, MC));
                }
            }
        }
        
        // Convert to result list (index 0 = most recent day)
        List<WalletCard.PnL> result = new ArrayList<>();
        for (int i = 6; i >= 0; i--) {
            LocalDate date = last7Days.get(i);
            WalletCard.PnL dayPnl = new WalletCard.PnL();
            dayPnl.setPnlUsd(dailyPnlUsd.getOrDefault(date, ZERO));
            dayPnl.setPnlSol(dailyPnlSol.getOrDefault(date, ZERO));
            result.add(dayPnl);
        }
        
        return result;
    }
    
    /**
     * Creates an empty P&L list for 7 days (all zeros)
     */
    private List<WalletCard.PnL> createEmptyPnLList() {
        List<WalletCard.PnL> result = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            WalletCard.PnL pnl = new WalletCard.PnL();
            pnl.setPnlUsd(ZERO);
            pnl.setPnlSol(ZERO);
            result.add(pnl);
        }
        return result;
    }
    
    public void createWalletCard(List<NormalizeTransaction.Row> normalizedTransactions, String wallet) {
        WalletCard walletCard = new WalletCard();
        walletCard.setWalletAddress(wallet);
        
        // Calculate 7-day P&L
        List<WalletCard.PnL> sevenDaysPnL = calculateDayWisePnL(normalizedTransactions);
        walletCard.setSevenDaysPnL(sevenDaysPnL);

        System.out.println(sevenDaysPnL);
    }}
