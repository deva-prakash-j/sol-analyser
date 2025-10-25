package com.sol.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sol.entity.WalletScore;
import com.sol.repository.WalletScoreRepository;
import com.sol.service.WalletScoringEngine.ScoringResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Service for persisting wallet scoring results to database
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WalletScorePersistenceService {
    
    private final WalletScoreRepository scoreRepository;
    private final ObjectMapper objectMapper;
    
    /**
     * Save scoring result to database
     */
    @Transactional
    public WalletScore saveScore(ScoringResult result) {
        try {
            // Check if wallet already has a score - update instead of insert
            WalletScore entity = scoreRepository.findFirstByWalletAddressOrderByScoredAtDesc(result.walletAddress())
                .map(existing -> {
                    log.debug("Updating existing score for wallet {}", result.walletAddress().substring(0, 8));
                    return updateEntity(existing, result);
                })
                .orElseGet(() -> {
                    log.debug("Creating new score for wallet {}", result.walletAddress().substring(0, 8));
                    try {
                        return convertToEntity(result);
                    } catch (JsonProcessingException e) {
                        throw new RuntimeException("Failed to convert to entity", e);
                    }
                });
            
            WalletScore saved = scoreRepository.save(entity);
            log.debug("Saved score for wallet {}: Tier={}, Score={}", 
                result.walletAddress().substring(0, 8), 
                result.tier().label, 
                result.compositeScore());
            return saved;
        } catch (Exception e) {
            log.error("Failed to save score for wallet {}: {}", 
                result.walletAddress(), e.getMessage(), e);
            return null; // Don't throw - persistence failure shouldn't stop processing
        }
    }
    
    /**
     * Convert ScoringResult to database entity
     */
    private WalletScore convertToEntity(ScoringResult result) throws JsonProcessingException {
        var m = result.metrics();
        
        // Convert maps to JSON
        String redFlagsJson = objectMapper.writeValueAsString(result.redFlags());
        String failedFiltersJson = objectMapper.writeValueAsString(result.failedFilters());
        
        // Create metrics entity
        var metricsEntity = com.sol.entity.WalletMetrics.builder()
            .totalVolumeUsd(m.totalVolumeUsd())
            .totalRealizedUsd(m.totalRealizedUsd())
            .totalFeesUsd(m.totalFeesUsd())
            .pnlPct(m.pnlPct())
            .profitFactor(m.profitFactor())
            .feeRatio(m.feeRatio())
            .maxDrawdownUsd(m.maxDrawdownUsd())
            .sharpeLikeDaily(m.sharpeLikeDaily())
            .avgHoldingHoursWeighted(m.avgHoldingHoursWeighted())
            .daysCount(m.daysCount())
            .dailyWinRatePct(m.dailyWinRatePct())
            .recoveryFactor(m.recoveryFactor())
            .maxConsecutiveLosses(m.maxConsecutiveLosses())
            .maxConsecutiveWins(m.maxConsecutiveWins())
            .avgWinningTrade(m.avgWinningTrade())
            .avgLosingTrade(m.avgLosingTrade())
            .winLossRatio(m.winLossRatio())
            .calmarRatio(m.calmarRatio())
            .sortinoRatio(m.sortinoRatio())
            .largestWinUsd(m.largestWinUsd())
            .largestLossUsd(m.largestLossUsd())
            .monthlyWinRate(m.monthlyWinRate())
            .longestDrawdownDays(m.longestDrawdownDays())
            .stdDevDailyReturns(m.stdDevDailyReturns())
            .stdDevMonthlyReturns(m.stdDevMonthlyReturns())
            .totalTrades(m.totalTrades())
            .winningTrades(m.winningTrades())
            .losingTrades(m.losingTrades())
            .tradeWinRate(m.tradeWinRate())
            .tradesPerDay(m.tradesPerDay())
            .avgTradeSize(m.avgTradeSize())
            .medianTradeSize(m.medianTradeSize())
            .last7DaysPnl(m.last7DaysPnl())
            .last30DaysPnl(m.last30DaysPnl())
            .last7DaysWinRate(m.last7DaysWinRate())
            .last30DaysWinRate(m.last30DaysWinRate())
            .last30DaysTrades(m.last30DaysTrades())
            .avgHoldingHoursWinners(m.avgHoldingHoursWinners())
            .avgHoldingHoursLosers(m.avgHoldingHoursLosers())
            .grossProfitUsd(m.grossProfitUsd())
            .grossLossUsd(m.grossLossUsd())
            .build();
        
        // Create score entity
        var scoreEntity = WalletScore.builder()
            .walletAddress(result.walletAddress())
            .scoredAt(LocalDateTime.now())
            .compositeScore(result.compositeScore())
            .tier(result.tier().label)
            .tierDescription(result.tier().description)
            .isCopyCandidate(result.isCopyTradingCandidate())
            .isEliteCandidate(result.isEliteCandidate())
            .passedHardFilters(result.passedHardFilters())
            .scoreProfitability(BigDecimal.valueOf(result.categoryScores().getOrDefault("profitability", 0.0)))
            .scoreConsistency(BigDecimal.valueOf(result.categoryScores().getOrDefault("consistency", 0.0)))
            .scoreRiskManagement(BigDecimal.valueOf(result.categoryScores().getOrDefault("riskManagement", 0.0)))
            .scoreRecentPerformance(BigDecimal.valueOf(result.categoryScores().getOrDefault("recentPerformance", 0.0)))
            .scoreTradeExecution(BigDecimal.valueOf(result.categoryScores().getOrDefault("tradeExecution", 0.0)))
            .scoreActivityLevel(BigDecimal.valueOf(result.categoryScores().getOrDefault("activityLevel", 0.0)))
            .redFlagsCount(result.redFlags().size())
            .redFlagsJson(redFlagsJson)
            .failedFiltersJson(failedFiltersJson)
            .recommendation(result.recommendation())
            .metrics(metricsEntity)
            .build();
        
        // Set bidirectional relationship
        metricsEntity.setWalletScore(scoreEntity);
        
        return scoreEntity;
    }
    
    /**
     * Update existing entity with new scoring results
     */
    private WalletScore updateEntity(WalletScore existing, ScoringResult result) {
        try {
            var m = result.metrics();
            
            // Update score fields
            existing.setScoredAt(LocalDateTime.now());
            existing.setCompositeScore(result.compositeScore());
            existing.setTier(result.tier().label);
            existing.setTierDescription(result.tier().description);
            existing.setIsCopyCandidate(result.isCopyTradingCandidate());
            existing.setIsEliteCandidate(result.isEliteCandidate());
            existing.setPassedHardFilters(result.passedHardFilters());
            existing.setScoreProfitability(BigDecimal.valueOf(result.categoryScores().getOrDefault("profitability", 0.0)));
            existing.setScoreConsistency(BigDecimal.valueOf(result.categoryScores().getOrDefault("consistency", 0.0)));
            existing.setScoreRiskManagement(BigDecimal.valueOf(result.categoryScores().getOrDefault("riskManagement", 0.0)));
            existing.setScoreRecentPerformance(BigDecimal.valueOf(result.categoryScores().getOrDefault("recentPerformance", 0.0)));
            existing.setScoreTradeExecution(BigDecimal.valueOf(result.categoryScores().getOrDefault("tradeExecution", 0.0)));
            existing.setScoreActivityLevel(BigDecimal.valueOf(result.categoryScores().getOrDefault("activityLevel", 0.0)));
            existing.setRedFlagsCount(result.redFlags().size());
            existing.setRedFlagsJson(objectMapper.writeValueAsString(result.redFlags()));
            existing.setFailedFiltersJson(objectMapper.writeValueAsString(result.failedFilters()));
            existing.setRecommendation(result.recommendation());
            
            // Update metrics
            var metrics = existing.getMetrics();
            metrics.setTotalVolumeUsd(m.totalVolumeUsd());
            metrics.setTotalRealizedUsd(m.totalRealizedUsd());
            metrics.setTotalFeesUsd(m.totalFeesUsd());
            metrics.setPnlPct(m.pnlPct());
            metrics.setProfitFactor(m.profitFactor());
            metrics.setFeeRatio(m.feeRatio());
            metrics.setMaxDrawdownUsd(m.maxDrawdownUsd());
            metrics.setSharpeLikeDaily(m.sharpeLikeDaily());
            metrics.setAvgHoldingHoursWeighted(m.avgHoldingHoursWeighted());
            metrics.setDaysCount(m.daysCount());
            metrics.setDailyWinRatePct(m.dailyWinRatePct());
            metrics.setRecoveryFactor(m.recoveryFactor());
            metrics.setMaxConsecutiveLosses(m.maxConsecutiveLosses());
            metrics.setMaxConsecutiveWins(m.maxConsecutiveWins());
            metrics.setAvgWinningTrade(m.avgWinningTrade());
            metrics.setAvgLosingTrade(m.avgLosingTrade());
            metrics.setWinLossRatio(m.winLossRatio());
            metrics.setCalmarRatio(m.calmarRatio());
            metrics.setSortinoRatio(m.sortinoRatio());
            metrics.setLargestWinUsd(m.largestWinUsd());
            metrics.setLargestLossUsd(m.largestLossUsd());
            metrics.setMonthlyWinRate(m.monthlyWinRate());
            metrics.setLongestDrawdownDays(m.longestDrawdownDays());
            metrics.setStdDevDailyReturns(m.stdDevDailyReturns());
            metrics.setStdDevMonthlyReturns(m.stdDevMonthlyReturns());
            metrics.setTotalTrades(m.totalTrades());
            metrics.setWinningTrades(m.winningTrades());
            metrics.setLosingTrades(m.losingTrades());
            metrics.setTradeWinRate(m.tradeWinRate());
            metrics.setTradesPerDay(m.tradesPerDay());
            metrics.setAvgTradeSize(m.avgTradeSize());
            metrics.setMedianTradeSize(m.medianTradeSize());
            metrics.setLast7DaysPnl(m.last7DaysPnl());
            metrics.setLast30DaysPnl(m.last30DaysPnl());
            metrics.setLast7DaysWinRate(m.last7DaysWinRate());
            metrics.setLast30DaysWinRate(m.last30DaysWinRate());
            metrics.setLast30DaysTrades(m.last30DaysTrades());
            metrics.setAvgHoldingHoursWinners(m.avgHoldingHoursWinners());
            metrics.setAvgHoldingHoursLosers(m.avgHoldingHoursLosers());
            metrics.setGrossProfitUsd(m.grossProfitUsd());
            metrics.setGrossLossUsd(m.grossLossUsd());
            
            return existing;
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize JSON fields", e);
        }
    }
    
    /**
     * Get latest score for a wallet
     */
    public WalletScore getLatestScore(String walletAddress) {
        return scoreRepository.findFirstByWalletAddressOrderByScoredAtDesc(walletAddress)
            .orElse(null);
    }
    
    /**
     * Get all copy trading candidates
     */
    public List<WalletScore> getCopyTradingCandidates() {
        return scoreRepository.findByIsCopyCandidateTrueOrderByCompositeScoreDesc();
    }
    
    /**
     * Get elite candidates (S+ tier)
     */
    public List<WalletScore> getEliteCandidates() {
        return scoreRepository.findByIsEliteCandidateTrueOrderByCompositeScoreDesc();
    }
    
    /**
     * Get wallets by tier
     */
    public List<WalletScore> getWalletsByTier(String tier) {
        return scoreRepository.findByTierOrderByCompositeScoreDesc(tier);
    }
    
    /**
     * Get top N scoring wallets
     */
    public List<WalletScore> getTopWallets(int limit) {
        return scoreRepository.findTopScoringWallets().stream()
            .limit(limit)
            .toList();
    }
}
