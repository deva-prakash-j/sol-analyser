package com.sol.repository;

import com.sol.entity.WalletScore;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface WalletScoreRepository extends JpaRepository<WalletScore, Long> {
    
    /**
     * Find latest score for a wallet
     */
    Optional<WalletScore> findFirstByWalletAddressOrderByScoredAtDesc(String walletAddress);
    
    /**
     * Find all copy trading candidates (B tier or above)
     */
    List<WalletScore> findByIsCopyCandidateTrueOrderByCompositeScoreDesc();
    
    /**
     * Find elite candidates (S+ tier)
     */
    List<WalletScore> findByIsEliteCandidateTrueOrderByCompositeScoreDesc();
    
    /**
     * Find by tier
     */
    List<WalletScore> findByTierOrderByCompositeScoreDesc(String tier);
    
    /**
     * Find wallets scored after a specific time
     */
    List<WalletScore> findByScoredAtAfterOrderByScoredAtDesc(LocalDateTime after);
    
    /**
     * Find top N wallets by composite score
     */
    @Query("SELECT ws FROM WalletScore ws WHERE ws.passedHardFilters = true ORDER BY ws.compositeScore DESC")
    List<WalletScore> findTopScoringWallets();
    
    /**
     * Find wallets with score above threshold
     */
    @Query("SELECT ws FROM WalletScore ws WHERE ws.compositeScore >= :minScore AND ws.passedHardFilters = true ORDER BY ws.compositeScore DESC")
    List<WalletScore> findWalletsAboveScore(@Param("minScore") Integer minScore);
    
    /**
     * Count wallets by tier
     */
    Long countByTier(String tier);
    
    /**
     * Check if wallet has been scored recently (within hours)
     */
    @Query("SELECT COUNT(ws) > 0 FROM WalletScore ws WHERE ws.walletAddress = :address AND ws.scoredAt > :since")
    boolean hasRecentScore(@Param("address") String walletAddress, @Param("since") LocalDateTime since);
}
