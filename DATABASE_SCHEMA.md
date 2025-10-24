# Wallet Scoring Database Schema

## Overview
Database schema for storing wallet performance metrics and scoring results for Solana copy trading analysis.

## Tables

### 1. `wallet_scores`
Main table storing composite scores and tier classifications.

| Column | Type | Description |
|--------|------|-------------|
| id | BIGSERIAL | Primary key |
| wallet_address | VARCHAR(44) | Solana wallet address |
| scored_at | TIMESTAMP | When the score was calculated |
| composite_score | INTEGER | Overall score (0-100) |
| tier | VARCHAR(10) | Classification: S+, S, A, B, C, D, F |
| tier_description | VARCHAR(255) | Tier description |
| is_copy_candidate | BOOLEAN | Suitable for copy trading (score >= 60) |
| is_elite_candidate | BOOLEAN | Elite tier (S+) |
| passed_hard_filters | BOOLEAN | Passed minimum requirements |
| score_profitability | NUMERIC(5,2) | Profitability category score |
| score_consistency | NUMERIC(5,2) | Consistency category score |
| score_risk_management | NUMERIC(5,2) | Risk management category score |
| score_recent_performance | NUMERIC(5,2) | Recent performance category score |
| score_trade_execution | NUMERIC(5,2) | Trade execution category score |
| score_activity_level | NUMERIC(5,2) | Activity level category score |
| red_flags_count | INTEGER | Number of red flags detected |
| red_flags_json | TEXT | JSON map of red flags |
| failed_filters_json | TEXT | JSON array of failed filters |
| recommendation | TEXT | AI-generated recommendation |

**Indexes:**
- `idx_wallet_score` - Fast lookup by wallet + date
- `idx_tier_score` - Query by tier and score
- `idx_copy_candidate` - Find copy candidates
- `idx_elite_candidate` - Find elite wallets
- `idx_scored_at` - Time-based queries

### 2. `wallet_metrics`
Detailed trading performance metrics (42 fields).

| Category | Fields |
|----------|--------|
| **Core Metrics** | total_volume_usd, total_realized_usd, total_fees_usd, pnl_pct, profit_factor, fee_ratio, max_drawdown_usd, sharpe_like_daily, avg_holding_hours_weighted, days_count, daily_win_rate_pct |
| **Risk Metrics** | recovery_factor, max_consecutive_losses, max_consecutive_wins, avg_winning_trade, avg_losing_trade, win_loss_ratio, calmar_ratio, sortino_ratio, largest_win_usd, largest_loss_usd |
| **Consistency** | monthly_win_rate, longest_drawdown_days, std_dev_daily_returns, std_dev_monthly_returns, total_trades, winning_trades, losing_trades, trade_win_rate |
| **Activity** | trades_per_day, avg_trade_size, median_trade_size |
| **Recent Performance** | last_7_days_pnl, last_30_days_pnl, last_7_days_win_rate, last_30_days_win_rate, last_30_days_trades |
| **Position Management** | avg_holding_hours_winners, avg_holding_hours_losers, gross_profit_usd, gross_loss_usd |

**Foreign Key:** `wallet_score_id` → `wallet_scores.id` (CASCADE DELETE)

## Views

### `v_top_wallets`
Top scoring wallets with key metrics (passed hard filters only).

### `v_copy_candidates`
Wallets suitable for copy trading (B tier or above).

### `v_elite_wallets`
Elite wallets (S+ tier) with full metrics.

## API Endpoints

### REST API (`/api/wallets`)

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/wallets/{address}` | GET | Get latest score for specific wallet |
| `/api/wallets/candidates` | GET | Get all copy trading candidates |
| `/api/wallets/elite` | GET | Get elite candidates (S+ tier) |
| `/api/wallets/tier/{tier}` | GET | Get wallets by tier (S+, S, A, B, C, D, F) |
| `/api/wallets/top?limit=10` | GET | Get top N scoring wallets |

## Usage Examples

### Query Top 10 Elite Wallets
```sql
SELECT * FROM v_elite_wallets LIMIT 10;
```

### Find Wallets with High Recent Performance
```sql
SELECT 
    ws.wallet_address,
    ws.tier,
    ws.composite_score,
    wm.last_30_days_pnl,
    wm.last_30_days_win_rate
FROM wallet_scores ws
JOIN wallet_metrics wm ON ws.id = wm.wallet_score_id
WHERE wm.last_30_days_pnl > 1000
  AND wm.last_30_days_win_rate > 70
  AND ws.is_copy_candidate = TRUE
ORDER BY ws.composite_score DESC;
```

### Check for Red Flags
```sql
SELECT 
    wallet_address,
    tier,
    composite_score,
    red_flags_count,
    red_flags_json
FROM wallet_scores
WHERE red_flags_count > 0
  AND is_copy_candidate = TRUE
ORDER BY composite_score DESC;
```

### Track Wallet Score History
```sql
SELECT 
    scored_at,
    composite_score,
    tier,
    is_copy_candidate
FROM wallet_scores
WHERE wallet_address = '<wallet_address>'
ORDER BY scored_at DESC;
```

## Migration

The schema is managed by Flyway migrations:
- **V2__create_wallet_scoring_tables.sql** - Creates all tables, indexes, views, and triggers

To apply migrations:
1. Ensure database credentials are set in environment variables
2. Run the Spring Boot application - Flyway will auto-migrate on startup
3. Check migration status: `SELECT * FROM flyway_schema_history;`

## Performance Notes

- All foreign keys have indexes for fast joins
- Composite indexes on commonly queried columns (tier + score)
- Updated_at triggers for audit tracking
- Materialized views can be added later for heavy queries

## Data Retention

Consider implementing:
- Archive old scores (keep last 30 days in main table)
- Partition by `scored_at` for large datasets
- Cleanup jobs for failed/low-tier wallets after X days
