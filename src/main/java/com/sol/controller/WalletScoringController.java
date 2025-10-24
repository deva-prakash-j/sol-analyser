package com.sol.controller;

import com.sol.entity.WalletScore;
import com.sol.service.WalletScorePersistenceService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST API for querying wallet scoring results
 */
@RestController
@RequestMapping("/api/wallets")
@RequiredArgsConstructor
public class WalletScoringController {
    
    private final WalletScorePersistenceService persistenceService;
    
    /**
     * Get latest score for a specific wallet
     * GET /api/wallets/{address}
     */
    @GetMapping("/{address}")
    public ResponseEntity<WalletScore> getWalletScore(@PathVariable String address) {
        WalletScore score = persistenceService.getLatestScore(address);
        return score != null ? ResponseEntity.ok(score) : ResponseEntity.notFound().build();
    }
    
    /**
     * Get all copy trading candidates (B tier or above)
     * GET /api/wallets/candidates
     */
    @GetMapping("/candidates")
    public ResponseEntity<List<WalletScore>> getCopyTradingCandidates() {
        return ResponseEntity.ok(persistenceService.getCopyTradingCandidates());
    }
    
    /**
     * Get elite candidates (S+ tier only)
     * GET /api/wallets/elite
     */
    @GetMapping("/elite")
    public ResponseEntity<List<WalletScore>> getEliteCandidates() {
        return ResponseEntity.ok(persistenceService.getEliteCandidates());
    }
    
    /**
     * Get wallets by tier
     * GET /api/wallets/tier/{tier}
     * Tiers: S+, S, A, B, C, D, F
     */
    @GetMapping("/tier/{tier}")
    public ResponseEntity<List<WalletScore>> getWalletsByTier(@PathVariable String tier) {
        return ResponseEntity.ok(persistenceService.getWalletsByTier(tier));
    }
    
    /**
     * Get top N scoring wallets
     * GET /api/wallets/top?limit=10
     */
    @GetMapping("/top")
    public ResponseEntity<List<WalletScore>> getTopWallets(
            @RequestParam(defaultValue = "10") int limit) {
        return ResponseEntity.ok(persistenceService.getTopWallets(limit));
    }
}
