package com.sol.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CompletableFuture;

@Service
@Profile("!prod")
@RequiredArgsConstructor
@Slf4j
public class StartupService {

    private final WalletService walletService;

    /**
     * Async processing ensures application starts successfully even if wallet processing fails.
     * Changed from @PostConstruct to @EventListener to decouple from bean initialization.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Async
    public void startup() {
        log.info("=== Application Ready - Starting Async Wallet Processing ===");
        
        CompletableFuture.runAsync(() -> {
            try {
                processAllWallets();
            } catch (Exception e) {
                log.error("CRITICAL: Top-level wallet processing thread failed: {}", e.getMessage(), e);
                // Swallow exception - don't crash the application
            }
        }).exceptionally(ex -> {
            log.error("CRITICAL: Wallet processing future failed: {}", ex.getMessage(), ex);
            return null;
        });
    }
    
    private void processAllWallets() {
        List<String> wallets = List.of(
                "FiAPe3q5TFYfYu4fP2ySMLRE9qfkAEYLsk2JaPaavvuW",
                "A7V5qDASoWNkB1uCXgtUs9MCxWPbwZEVRJciPZsPjKdR",
                "63XhSCjVUxb6R8xLWJ3EAGS8aKRbwNGnSEtCByQ4Asd2"
        );
        
        int successCount = 0;
        int failureCount = 0;
        
        for (int i = 0; i < wallets.size(); i++) {
            String wallet = wallets.get(i);
            try {
                log.info("=== Processing wallet {}/{}: {} ===", i + 1, wallets.size(), wallet);
                walletService.processWalletSafely(wallet);
                successCount++;
                log.info("✓ Successfully completed wallet {}/{}", i + 1, wallets.size());
            } catch (Exception e) {
                failureCount++;
                log.error("✗ Failed wallet {}/{}: {}", i + 1, wallets.size(), e.getMessage(), e);
                // Continue with next wallet - one failure doesn't stop others
            }
        }
        
        log.info("=== Wallet Processing Summary: {} succeeded, {} failed, {} total ===", 
                successCount, failureCount, wallets.size());
    }
}
