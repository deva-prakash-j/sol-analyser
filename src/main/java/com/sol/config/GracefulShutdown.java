package com.sol.config;

import com.sol.proxy.DynamicProxyClientFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import jakarta.annotation.PreDestroy;

/**
 * Handles graceful shutdown of application resources
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class GracefulShutdown {
    
    private final DynamicProxyClientFactory clientFactory;
    
    @PreDestroy
    public void shutdown() {
        log.info("Starting graceful shutdown...");
        
        try {
            // Shutdown WebClient pool and connection providers
            clientFactory.getWebClientPool().shutdown();
            log.info("WebClient pool shutdown completed");
            
            // Allow time for current requests to complete
            Thread.sleep(2000);
            
            log.info("Graceful shutdown completed successfully");
        } catch (Exception e) {
            log.error("Error during graceful shutdown", e);
        }
    }
}