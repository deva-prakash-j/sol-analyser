package com.sol.repository;

import com.sol.entity.WalletMetrics;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;

@Repository
public interface WalletMetricsRepository extends JpaRepository<WalletMetrics, Long> {
    
    /**
     * Find metrics by wallet score id
     */
    WalletMetrics findByWalletScoreId(Long walletScoreId);
    
    /**
     * Find wallets with high profit factor
     */
    @Query("SELECT wm FROM WalletMetrics wm WHERE wm.profitFactor >= :minProfitFactor ORDER BY wm.profitFactor DESC")
    List<WalletMetrics> findByProfitFactorGreaterThan(@Param("minProfitFactor") BigDecimal minProfitFactor);
    
    /**
     * Find wallets with high win rate
     */
    @Query("SELECT wm FROM WalletMetrics wm WHERE wm.tradeWinRate >= :minWinRate ORDER BY wm.tradeWinRate DESC")
    List<WalletMetrics> findByTradeWinRateGreaterThan(@Param("minWinRate") Double minWinRate);
    
    /**
     * Find wallets with recent positive performance
     */
    @Query("SELECT wm FROM WalletMetrics wm WHERE wm.last30DaysPnl > 0 ORDER BY wm.last30DaysPnl DESC")
    List<WalletMetrics> findWithPositiveLast30Days();
}
