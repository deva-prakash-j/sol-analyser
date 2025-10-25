package com.sol.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Service for calculating unrealized PnL on open positions
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UnrealizedPnlService {
    
    private final JupiterService jupiterService;
    
    /**
     * Unrealized PnL result for a single position
     */
    public record PositionUnrealizedPnl(
        String mint,
        BigDecimal quantity,
        BigDecimal avgBoughtPrice,
        BigDecimal currentPrice,
        BigDecimal costBasis,
        BigDecimal currentValue,
        BigDecimal unrealizedPnl,
        BigDecimal unrealizedPnlPct,
        boolean priceFound
    ) {}
    
    /**
     * Summary of all unrealized PnL
     */
    public record UnrealizedPnlSummary(
        List<PositionUnrealizedPnl> positions,
        BigDecimal totalCostBasis,
        BigDecimal totalCurrentValue,
        BigDecimal totalUnrealizedPnl,
        BigDecimal totalUnrealizedPnlPct,
        int positionsCount,
        int pricesFound,
        int pricesMissing
    ) {}
    
    /**
     * Calculate unrealized PnL for all open positions
     */
    public UnrealizedPnlSummary calculateUnrealizedPnl(List<PnlEngine.OpenPosition> openPositions) {
        if (openPositions == null || openPositions.isEmpty()) {
            return new UnrealizedPnlSummary(
                List.of(), 
                BigDecimal.ZERO, 
                BigDecimal.ZERO, 
                BigDecimal.ZERO, 
                BigDecimal.ZERO,
                0, 0, 0
            );
        }
        
        // Extract all mints
        List<String> mints = openPositions.stream()
                .map(pos -> pos.mint)
                .collect(Collectors.toList());
        
        // Fetch current prices from Jupiter (batched, using proxies)
        Map<String, JupiterService.TokenPrice> currentPrices = jupiterService.getCurrentPrices(mints);
        
        // Calculate unrealized PnL for each position
        List<PositionUnrealizedPnl> positionPnls = openPositions.stream()
                .map(pos -> calculatePositionPnl(pos, currentPrices.get(pos.mint)))
                .collect(Collectors.toList());
        
        // Calculate totals
        BigDecimal totalCostBasis = positionPnls.stream()
                .map(PositionUnrealizedPnl::costBasis)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        
        BigDecimal totalCurrentValue = positionPnls.stream()
                .map(PositionUnrealizedPnl::currentValue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        
        BigDecimal totalUnrealizedPnl = positionPnls.stream()
                .map(PositionUnrealizedPnl::unrealizedPnl)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        
        BigDecimal totalUnrealizedPnlPct = totalCostBasis.compareTo(BigDecimal.ZERO) > 0
                ? totalUnrealizedPnl.divide(totalCostBasis, 4, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100))
                : BigDecimal.ZERO;
        
        int pricesFound = (int) positionPnls.stream().filter(PositionUnrealizedPnl::priceFound).count();
        int pricesMissing = positionPnls.size() - pricesFound;
        
        return new UnrealizedPnlSummary(
            positionPnls,
            totalCostBasis,
            totalCurrentValue,
            totalUnrealizedPnl,
            totalUnrealizedPnlPct,
            positionPnls.size(),
            pricesFound,
            pricesMissing
        );
    }
    
    /**
     * Calculate unrealized PnL for a single position
     */
    private PositionUnrealizedPnl calculatePositionPnl(
            PnlEngine.OpenPosition position, 
            JupiterService.TokenPrice currentPriceData) {
        
        BigDecimal currentPrice = (currentPriceData != null && currentPriceData.priceFound()) 
                ? currentPriceData.usdPrice() 
                : BigDecimal.ZERO;
        
        boolean priceFound = currentPriceData != null && currentPriceData.priceFound();
        
        // Cost basis = quantity × avg bought price
        BigDecimal costBasis = position.costBasis;
        
        // Current value = quantity × current price
        BigDecimal currentValue = position.quantity.multiply(currentPrice);
        
        // Unrealized PnL = current value - cost basis
        BigDecimal unrealizedPnl = currentValue.subtract(costBasis);
        
        // Unrealized PnL % = (unrealized PnL / cost basis) × 100
        BigDecimal unrealizedPnlPct = costBasis.compareTo(BigDecimal.ZERO) > 0
                ? unrealizedPnl.divide(costBasis, 4, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100))
                : BigDecimal.ZERO;
        
        return new PositionUnrealizedPnl(
            position.mint,
            position.quantity,
            position.avgBoughtPrice,
            currentPrice,
            costBasis,
            currentValue,
            unrealizedPnl,
            unrealizedPnlPct,
            priceFound
        );
    }
}
