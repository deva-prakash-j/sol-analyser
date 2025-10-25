package com.sol.service;

import com.sol.dto.Transaction;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Analyzes priority fees to detect MEV bots and priority access
 * MEV bots pay 10-100x normal fees to guarantee first execution
 */
@Service
@Slf4j
public class PriorityFeeAnalysisService {
    
    // Priority fee thresholds (in lamports)
    private static final long NORMAL_FEE = 5_000L;        // ~$0.0007 typical Solana fee
    private static final long ELEVATED_FEE = 50_000L;     // 10x normal (suspicious)
    private static final long MEV_FEE = 100_000L;         // 20x normal (MEV activity)
    private static final long EXTREME_FEE = 500_000L;     // 100x normal (definite MEV bot)
    
    // Percentage thresholds
    private static final double ELEVATED_THRESHOLD = 10.0;  // >10% elevated = warning
    private static final double MEV_THRESHOLD = 20.0;       // >20% MEV-level = reject
    private static final double EXTREME_THRESHOLD = 5.0;    // >5% extreme = definite MEV bot
    
    public record PriorityFeeAnalysis(
        boolean hasElevatedFees,
        boolean likelyMevBot,
        long avgFee,
        long medianFee,
        long maxFee,
        double percentageElevatedFees,
        List<String> patterns,
        Map<String, Object> diagnostics
    ) {}
    
    /**
     * Analyze priority fees from transaction history
     */
    public PriorityFeeAnalysis analyzePriorityFees(List<Transaction> transactions) {
        if (transactions == null || transactions.isEmpty()) {
            return new PriorityFeeAnalysis(false, false, 0L, 0L, 0L, 0.0, List.of(), Map.of());
        }
        
        List<String> patterns = new ArrayList<>();
        Map<String, Object> diagnostics = new HashMap<>();
        
        // Extract fees from all transactions
        List<Long> fees = transactions.stream()
            .map(tx -> {
                var result = tx.getResult();
                if (result == null) return 0L;
                var meta = result.getMeta();
                return meta != null ? meta.getFee() : 0L;
            })
            .filter(fee -> fee > 0)
            .collect(Collectors.toList());
        
        if (fees.isEmpty()) {
            return new PriorityFeeAnalysis(false, false, 0L, 0L, 0L, 0.0, List.of(), Map.of());
        }
        
        // Calculate statistics
        long totalFees = fees.stream().mapToLong(Long::longValue).sum();
        long avgFee = totalFees / fees.size();
        
        List<Long> sortedFees = fees.stream().sorted().collect(Collectors.toList());
        long medianFee = sortedFees.get(sortedFees.size() / 2);
        long maxFee = sortedFees.get(sortedFees.size() - 1);
        
        diagnostics.put("avgFee", avgFee);
        diagnostics.put("medianFee", medianFee);
        diagnostics.put("maxFee", maxFee);
        diagnostics.put("totalTransactions", fees.size());
        
        // Count elevated fees
        long elevatedFeeTxs = fees.stream()
            .filter(fee -> fee > ELEVATED_FEE)
            .count();
        
        long mevFeeTxs = fees.stream()
            .filter(fee -> fee > MEV_FEE)
            .count();
        
        long extremeFeeTxs = fees.stream()
            .filter(fee -> fee > EXTREME_FEE)
            .count();
        
        double percentageElevated = (elevatedFeeTxs * 100.0) / fees.size();
        double percentageMev = (mevFeeTxs * 100.0) / fees.size();
        double percentageExtreme = (extremeFeeTxs * 100.0) / fees.size();
        
        diagnostics.put("elevatedFeeTxs", elevatedFeeTxs);
        diagnostics.put("mevFeeTxs", mevFeeTxs);
        diagnostics.put("extremeFeeTxs", extremeFeeTxs);
        diagnostics.put("percentageElevated", String.format("%.1f%%", percentageElevated));
        diagnostics.put("percentageMev", String.format("%.1f%%", percentageMev));
        diagnostics.put("percentageExtreme", String.format("%.1f%%", percentageExtreme));
        
        // Pattern detection
        boolean hasElevatedFees = percentageElevated > ELEVATED_THRESHOLD;
        boolean likelyMevBot = percentageMev > MEV_THRESHOLD || percentageExtreme > EXTREME_THRESHOLD;
        
        // Pattern 1: Consistently high fees
        if (avgFee > ELEVATED_FEE) {
            patterns.add(String.format(
                "Consistently high fees: avg %,d lamports (%.1fx normal)",
                avgFee, avgFee / (double) NORMAL_FEE
            ));
        }
        
        // Pattern 2: Many extreme priority transactions (definite MEV bot)
        if (percentageExtreme > EXTREME_THRESHOLD) {
            patterns.add(String.format(
                "Extreme priority usage: %d txs (%.1f%%) paid >500k lamports (MEV bot)",
                extremeFeeTxs, percentageExtreme
            ));
        }
        
        // Pattern 3: Frequent MEV-level fees
        if (percentageMev > MEV_THRESHOLD) {
            patterns.add(String.format(
                "MEV-level fees: %d txs (%.1f%%) paid >100k lamports (priority access)",
                mevFeeTxs, percentageMev
            ));
        }
        
        // Pattern 4: Very high maximum fee
        if (maxFee > EXTREME_FEE) {
            patterns.add(String.format(
                "Extreme max fee: %,d lamports (%.0fx normal) - MEV infrastructure",
                maxFee, maxFee / (double) NORMAL_FEE
            ));
        }
        
        // Pattern 5: Wide fee variance (sometimes pays premium)
        if (fees.size() > 10) {
            double variance = calculateVariance(fees);
            double stdDev = Math.sqrt(variance);
            
            // High variance indicates selective priority fee usage
            if (stdDev > avgFee * 0.5) { // Standard deviation > 50% of mean
                patterns.add(String.format(
                    "Variable priority usage: high fee variance (%.0f lamports stddev)",
                    stdDev
                ));
                diagnostics.put("feeStdDev", String.format("%.0f", stdDev));
            }
        }
        
        // Pattern 6: Fee distribution analysis
        Map<String, Long> feeDistribution = new HashMap<>();
        feeDistribution.put("normal", fees.stream().filter(f -> f <= ELEVATED_FEE).count());
        feeDistribution.put("elevated", fees.stream().filter(f -> f > ELEVATED_FEE && f <= MEV_FEE).count());
        feeDistribution.put("mev", fees.stream().filter(f -> f > MEV_FEE && f <= EXTREME_FEE).count());
        feeDistribution.put("extreme", extremeFeeTxs);
        
        diagnostics.put("feeDistribution", feeDistribution);
        
        if (likelyMevBot) {
            log.debug("MEV infrastructure detected - Avg fee: {}, Max fee: {}, Elevated: {}%", 
                avgFee, maxFee, String.format("%.1f", percentageElevated));
        }
        
        return new PriorityFeeAnalysis(
            hasElevatedFees,
            likelyMevBot,
            avgFee,
            medianFee,
            maxFee,
            percentageElevated,
            patterns,
            diagnostics
        );
    }
    
    /**
     * Calculate variance of fee distribution
     */
    private double calculateVariance(List<Long> fees) {
        double mean = fees.stream().mapToLong(Long::longValue).average().orElse(0);
        double sumSquaredDiff = fees.stream()
            .mapToDouble(fee -> Math.pow(fee - mean, 2))
            .sum();
        return sumSquaredDiff / fees.size();
    }
    
    /**
     * Categorize transaction fee level
     */
    public String categorizeFeeLevel(long fee) {
        if (fee > EXTREME_FEE) return "EXTREME (MEV bot)";
        if (fee > MEV_FEE) return "MEV-level";
        if (fee > ELEVATED_FEE) return "Elevated";
        return "Normal";
    }
}
