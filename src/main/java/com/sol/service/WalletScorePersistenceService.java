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
            WalletScore entity = convertToEntity(result);
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
