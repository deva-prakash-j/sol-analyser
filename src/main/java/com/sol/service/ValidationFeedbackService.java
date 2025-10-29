package com.sol.service;

import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Validation feedback service for continuous improvement of HFT detection
 * Tracks false positives/negatives and adjusts thresholds accordingly
 */
@Service
@RequiredArgsConstructor
public class ValidationFeedbackService {
    private static final Logger log = LoggerFactory.getLogger(ValidationFeedbackService.class);
    
    // In-memory storage (would be replaced with database in production)
    private final Map<String, ValidationOutcome> validationHistory = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> falsePositiveCounters = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> falseNegativeCounters = new ConcurrentHashMap<>();
    
    @Builder
    @Data
    public static class ValidationOutcome {
        private String walletAddress;
        private boolean wasRejected;
        private String rejectionReason;
        private DynamicThresholdService.TraderProfile detectedProfile;
        private double legitimacyScore;
        private LocalDateTime validationTime;
        
        // Actual performance metrics (collected later)
        private ActualPerformance actualPerformance;
        private boolean feedbackProvided;
    }
    
    @Builder
    @Data
    public static class ActualPerformance {
        private boolean wasActuallyGood;
        private double actualWinRate;
        private double actualSharpeRatio;
        private double actualReturnPercentage;
        private boolean confirmedAsBot;
        private boolean confirmedAsLegitimateTrader;
        private String performanceNotes;
        private LocalDateTime performanceTime;
    }
    
    @Builder
    @Data
    public static class FeedbackSummary {
        private long totalValidations;
        private long falsePositives;
        private long falseNegatives;
        private double falsePositiveRate;
        private double falseNegativeRate;
        private double accuracy;
        private Map<DynamicThresholdService.TraderProfile, Long> profileBreakdown;
        private Map<String, Object> thresholdAdjustmentRecommendations;
    }
    
    /**
     * Record validation outcome for a wallet
     */
    public void recordValidationOutcome(String walletAddress, 
                                      WalletValidationService.ValidationResult result,
                                      DynamicThresholdService.TraderProfile profile,
                                      double legitimacyScore) {
        
        ValidationOutcome outcome = ValidationOutcome.builder()
            .walletAddress(walletAddress)
            .wasRejected(!result.passed())
            .rejectionReason(String.join("; ", result.failures()))
            .detectedProfile(profile)
            .legitimacyScore(legitimacyScore)
            .validationTime(LocalDateTime.now())
            .feedbackProvided(false)
            .build();
        
        validationHistory.put(walletAddress, outcome);
        
        log.debug("Recorded validation outcome for wallet {}: rejected={}, profile={}, legitimacy={}", 
            walletAddress, outcome.isWasRejected(), profile, legitimacyScore);
    }
    
    /**
     * Provide feedback on actual performance
     */
    public void provideFeedback(String walletAddress, ActualPerformance actualPerformance) {
        ValidationOutcome outcome = validationHistory.get(walletAddress);
        if (outcome == null) {
            log.warn("No validation outcome found for wallet {}", walletAddress);
            return;
        }
        
        outcome.setActualPerformance(actualPerformance);
        outcome.setFeedbackProvided(true);
        
        // Analyze for false positive/negative
        analyzeFeedback(outcome);
        
        log.info("Feedback provided for wallet {}: actuallyGood={}, wasRejected={}", 
            walletAddress, actualPerformance.isWasActuallyGood(), outcome.isWasRejected());
    }
    
    private void analyzeFeedback(ValidationOutcome outcome) {
        String profileKey = outcome.getDetectedProfile().toString();
        ActualPerformance actual = outcome.getActualPerformance();
        
        // Check for false positive (rejected but was actually good)
        if (outcome.isWasRejected() && actual.isWasActuallyGood()) {
            recordFalsePositive(outcome);
        }
        
        // Check for false negative (accepted but was actually bad)
        if (!outcome.isWasRejected() && !actual.isWasActuallyGood()) {
            recordFalseNegative(outcome);
        }
        
        // Update counters
        falsePositiveCounters.computeIfAbsent(profileKey, k -> new AtomicLong(0));
        falseNegativeCounters.computeIfAbsent(profileKey, k -> new AtomicLong(0));
    }
    
    private void recordFalsePositive(ValidationOutcome outcome) {
        String profileKey = outcome.getDetectedProfile().toString();
        falsePositiveCounters.computeIfAbsent(profileKey, k -> new AtomicLong(0)).incrementAndGet();
        
        log.warn("FALSE POSITIVE detected for wallet {}: " +
                "Profile: {}, Legitimacy: {}, Reason: {}, Actual performance: {}", 
            outcome.getWalletAddress(),
            outcome.getDetectedProfile(),
            outcome.getLegitimacyScore(),
            outcome.getRejectionReason(),
            outcome.getActualPerformance().getPerformanceNotes());
        
        // Suggest threshold adjustments
        suggestThresholdAdjustments(outcome, true);
    }
    
    private void recordFalseNegative(ValidationOutcome outcome) {
        String profileKey = outcome.getDetectedProfile().toString();
        falseNegativeCounters.computeIfAbsent(profileKey, k -> new AtomicLong(0)).incrementAndGet();
        
        log.warn("FALSE NEGATIVE detected for wallet {}: " +
                "Profile: {}, Legitimacy: {}, Should have been rejected", 
            outcome.getWalletAddress(),
            outcome.getDetectedProfile(),
            outcome.getLegitimacyScore());
        
        // Suggest threshold adjustments
        suggestThresholdAdjustments(outcome, false);
    }
    
    private void suggestThresholdAdjustments(ValidationOutcome outcome, boolean isFalsePositive) {
        if (isFalsePositive) {
            // For false positives, suggest more lenient thresholds
            if (outcome.getDetectedProfile() == DynamicThresholdService.TraderProfile.LEGITIMATE_HFT) {
                log.info("Suggestion: Increase bot detection threshold for LEGITIMATE_HFT profile " +
                        "(current legitimacy score was {})", outcome.getLegitimacyScore());
            }
        } else {
            // For false negatives, suggest stricter thresholds
            log.info("Suggestion: Decrease thresholds for profile {} " +
                    "(legitimacy score was {})", outcome.getDetectedProfile(), outcome.getLegitimacyScore());
        }
    }
    
    /**
     * Get feedback summary for monitoring and optimization
     */
    public FeedbackSummary getFeedbackSummary() {
        long totalValidations = validationHistory.size();
        long totalFalsePositives = falsePositiveCounters.values().stream()
            .mapToLong(AtomicLong::get)
            .sum();
        long totalFalseNegatives = falseNegativeCounters.values().stream()
            .mapToLong(AtomicLong::get)
            .sum();
        
        long totalWithFeedback = validationHistory.values().stream()
            .mapToLong(outcome -> outcome.isFeedbackProvided() ? 1 : 0)
            .sum();
        
        double falsePositiveRate = totalWithFeedback > 0 ? (double) totalFalsePositives / totalWithFeedback : 0.0;
        double falseNegativeRate = totalWithFeedback > 0 ? (double) totalFalseNegatives / totalWithFeedback : 0.0;
        double accuracy = totalWithFeedback > 0 ? 
            1.0 - ((double) (totalFalsePositives + totalFalseNegatives) / totalWithFeedback) : 0.0;
        
        // Profile breakdown
        Map<DynamicThresholdService.TraderProfile, Long> profileBreakdown = new HashMap<>();
        for (ValidationOutcome outcome : validationHistory.values()) {
            profileBreakdown.merge(outcome.getDetectedProfile(), 1L, Long::sum);
        }
        
        // Threshold adjustment recommendations
        Map<String, Object> recommendations = generateThresholdRecommendations();
        
        return FeedbackSummary.builder()
            .totalValidations(totalValidations)
            .falsePositives(totalFalsePositives)
            .falseNegatives(totalFalseNegatives)
            .falsePositiveRate(falsePositiveRate)
            .falseNegativeRate(falseNegativeRate)
            .accuracy(accuracy)
            .profileBreakdown(profileBreakdown)
            .thresholdAdjustmentRecommendations(recommendations)
            .build();
    }
    
    private Map<String, Object> generateThresholdRecommendations() {
        Map<String, Object> recommendations = new HashMap<>();
        
        // Analyze false positive rates by profile
        for (DynamicThresholdService.TraderProfile profile : DynamicThresholdService.TraderProfile.values()) {
            String profileKey = profile.toString();
            long falsePositives = falsePositiveCounters.getOrDefault(profileKey, new AtomicLong(0)).get();
            long totalForProfile = validationHistory.values().stream()
                .mapToLong(outcome -> outcome.getDetectedProfile() == profile && outcome.isFeedbackProvided() ? 1 : 0)
                .sum();
            
            if (totalForProfile > 10) { // Only recommend if we have enough data
                double fpRate = (double) falsePositives / totalForProfile;
                
                if (fpRate > 0.2) { // >20% false positive rate
                    recommendations.put(profileKey + "_threshold_adjustment", 
                        String.format("Increase bot detection threshold by %.1f%% (FP rate: %.1f%%)", 
                            fpRate * 50, fpRate * 100));
                }
            }
        }
        
        return recommendations;
    }
    
    /**
     * Get detailed feedback for a specific wallet
     */
    public ValidationOutcome getValidationOutcome(String walletAddress) {
        return validationHistory.get(walletAddress);
    }
    
    /**
     * Clear feedback history (for testing or reset)
     */
    public void clearFeedbackHistory() {
        validationHistory.clear();
        falsePositiveCounters.clear();
        falseNegativeCounters.clear();
        log.info("Feedback history cleared");
    }
    
    /**
     * Export feedback data for machine learning model training
     */
    public Map<String, Object> exportFeedbackData() {
        Map<String, Object> exportData = new HashMap<>();
        
        // Export validation outcomes
        exportData.put("validationOutcomes", validationHistory.values());
        
        // Export aggregated statistics
        exportData.put("feedbackSummary", getFeedbackSummary());
        
        // Export threshold performance
        Map<String, Double> thresholdPerformance = new HashMap<>();
        for (DynamicThresholdService.TraderProfile profile : DynamicThresholdService.TraderProfile.values()) {
            String profileKey = profile.toString();
            long total = validationHistory.values().stream()
                .mapToLong(outcome -> outcome.getDetectedProfile() == profile && outcome.isFeedbackProvided() ? 1 : 0)
                .sum();
            long errors = falsePositiveCounters.getOrDefault(profileKey, new AtomicLong(0)).get() +
                         falseNegativeCounters.getOrDefault(profileKey, new AtomicLong(0)).get();
            
            double accuracy = total > 0 ? 1.0 - ((double) errors / total) : 0.0;
            thresholdPerformance.put(profileKey, accuracy);
        }
        exportData.put("thresholdPerformance", thresholdPerformance);
        
        return exportData;
    }
}