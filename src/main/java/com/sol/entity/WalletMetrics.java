package com.sol.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Detailed wallet metrics - comprehensive trading performance data
 */
@Entity
@Table(name = "wallet_metrics", indexes = {
    @Index(name = "idx_metrics_wallet", columnList = "wallet_score_id")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WalletMetrics {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @OneToOne
    @JoinColumn(name = "wallet_score_id", nullable = false)
    private WalletScore walletScore;
    
    // === Core Metrics ===
    @Column(name = "total_volume_usd", precision = 20, scale = 8)
    private BigDecimal totalVolumeUsd;
    
    @Column(name = "total_realized_usd", precision = 20, scale = 8)
    private BigDecimal totalRealizedUsd;
    
    @Column(name = "total_fees_usd", precision = 20, scale = 8)
    private BigDecimal totalFeesUsd;
    
    @Column(name = "pnl_pct", precision = 15, scale = 8)
    private BigDecimal pnlPct;
    
    @Column(name = "profit_factor", precision = 15, scale = 8)
    private BigDecimal profitFactor;
    
    @Column(name = "fee_ratio", precision = 10, scale = 8)
    private BigDecimal feeRatio;
    
    @Column(name = "max_drawdown_usd", precision = 20, scale = 8)
    private BigDecimal maxDrawdownUsd;
    
    @Column(name = "sharpe_like_daily")
    private Double sharpeLikeDaily;
    
    @Column(name = "avg_holding_hours_weighted")
    private Double avgHoldingHoursWeighted;
    
    @Column(name = "days_count")
    private Integer daysCount;
    
    @Column(name = "daily_win_rate_pct")
    private Double dailyWinRatePct;
    
    // === Risk Metrics ===
    @Column(name = "recovery_factor", precision = 15, scale = 8)
    private BigDecimal recoveryFactor;
    
    @Column(name = "max_consecutive_losses")
    private Integer maxConsecutiveLosses;
    
    @Column(name = "max_consecutive_wins")
    private Integer maxConsecutiveWins;
    
    @Column(name = "avg_winning_trade", precision = 20, scale = 8)
    private BigDecimal avgWinningTrade;
    
    @Column(name = "avg_losing_trade", precision = 20, scale = 8)
    private BigDecimal avgLosingTrade;
    
    @Column(name = "win_loss_ratio", precision = 15, scale = 8)
    private BigDecimal winLossRatio;
    
    @Column(name = "calmar_ratio", precision = 15, scale = 8)
    private BigDecimal calmarRatio;
    
    @Column(name = "sortino_ratio")
    private Double sortinoRatio;
    
    @Column(name = "largest_win_usd", precision = 20, scale = 8)
    private BigDecimal largestWinUsd;
    
    @Column(name = "largest_loss_usd", precision = 20, scale = 8)
    private BigDecimal largestLossUsd;
    
    // === Consistency Metrics ===
    @Column(name = "monthly_win_rate")
    private Double monthlyWinRate;
    
    @Column(name = "longest_drawdown_days")
    private Integer longestDrawdownDays;
    
    @Column(name = "std_dev_daily_returns")
    private Double stdDevDailyReturns;
    
    @Column(name = "std_dev_monthly_returns")
    private Double stdDevMonthlyReturns;
    
    @Column(name = "total_trades")
    private Integer totalTrades;
    
    @Column(name = "winning_trades")
    private Integer winningTrades;
    
    @Column(name = "losing_trades")
    private Integer losingTrades;
    
    @Column(name = "trade_win_rate")
    private Double tradeWinRate;
    
    // === Activity Metrics ===
    @Column(name = "trades_per_day")
    private Double tradesPerDay;
    
    @Column(name = "avg_trade_size", precision = 20, scale = 8)
    private BigDecimal avgTradeSize;
    
    @Column(name = "median_trade_size", precision = 20, scale = 8)
    private BigDecimal medianTradeSize;
    
    // === Recent Performance ===
    @Column(name = "last_7_days_pnl", precision = 20, scale = 8)
    private BigDecimal last7DaysPnl;
    
    @Column(name = "last_30_days_pnl", precision = 20, scale = 8)
    private BigDecimal last30DaysPnl;
    
    @Column(name = "last_7_days_win_rate")
    private Double last7DaysWinRate;
    
    @Column(name = "last_30_days_win_rate")
    private Double last30DaysWinRate;
    
    @Column(name = "last_30_days_trades")
    private Integer last30DaysTrades;
    
    // === Position Management ===
    @Column(name = "avg_holding_hours_winners")
    private Double avgHoldingHoursWinners;
    
    @Column(name = "avg_holding_hours_losers")
    private Double avgHoldingHoursLosers;
    
    @Column(name = "gross_profit_usd", precision = 20, scale = 8)
    private BigDecimal grossProfitUsd;
    
    @Column(name = "gross_loss_usd", precision = 20, scale = 8)
    private BigDecimal grossLossUsd;
}
