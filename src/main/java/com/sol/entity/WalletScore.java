package com.sol.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Main wallet scoring results - stores composite scores and tier classification
 */
@Entity
@Table(name = "wallet_scores", 
    indexes = {
        @Index(name = "idx_wallet_score", columnList = "wallet_address, scored_at DESC"),
        @Index(name = "idx_tier_score", columnList = "tier, composite_score DESC"),
        @Index(name = "idx_copy_candidate", columnList = "is_copy_candidate, composite_score DESC")
    },
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_wallet_address", columnNames = {"wallet_address"})
    }
)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WalletScore {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(name = "wallet_address", nullable = false, length = 44)
    private String walletAddress;
    
    @Column(name = "scored_at", nullable = false)
    private LocalDateTime scoredAt;
    
    // Scoring Results
    @Column(name = "composite_score", nullable = false)
    private Integer compositeScore;
    
    @Column(name = "tier", nullable = false, length = 10)
    private String tier;
    
    @Column(name = "tier_description", length = 255)
    private String tierDescription;
    
    @Column(name = "is_copy_candidate", nullable = false)
    private Boolean isCopyCandidate;
    
    @Column(name = "is_elite_candidate", nullable = false)
    private Boolean isEliteCandidate;
    
    @Column(name = "passed_hard_filters", nullable = false)
    private Boolean passedHardFilters;
    
    // Category Scores (0-100 each)
    @Column(name = "score_profitability", precision = 5, scale = 2)
    private BigDecimal scoreProfitability;
    
    @Column(name = "score_consistency", precision = 5, scale = 2)
    private BigDecimal scoreConsistency;
    
    @Column(name = "score_risk_management", precision = 5, scale = 2)
    private BigDecimal scoreRiskManagement;
    
    @Column(name = "score_recent_performance", precision = 5, scale = 2)
    private BigDecimal scoreRecentPerformance;
    
    @Column(name = "score_trade_execution", precision = 5, scale = 2)
    private BigDecimal scoreTradeExecution;
    
    @Column(name = "score_activity_level", precision = 5, scale = 2)
    private BigDecimal scoreActivityLevel;
    
    // Red Flags
    @Column(name = "red_flags_count")
    private Integer redFlagsCount;
    
    @Column(name = "red_flags_json", columnDefinition = "TEXT")
    private String redFlagsJson; // JSON: {"flag_key": "description"}
    
    @Column(name = "failed_filters_json", columnDefinition = "TEXT")
    private String failedFiltersJson; // JSON: ["filter1", "filter2"]
    
    @Column(name = "recommendation", columnDefinition = "TEXT")
    private String recommendation;
    
    // Relationship to metrics
    @OneToOne(mappedBy = "walletScore", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private WalletMetrics metrics;
}
