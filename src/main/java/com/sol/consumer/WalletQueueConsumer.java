package com.sol.consumer;

import com.sol.service.WalletService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Component
@Slf4j
@RequiredArgsConstructor
public class WalletQueueConsumer {

    private final WalletService walletService;

    @RabbitListener(queues = "wallets", concurrency = "1")
    public void consumeWalletMessage(Message message) {
        try {
            String wallet = new String(message.getBody(), StandardCharsets.UTF_8);
            log.info("========================================");
            log.info("Received wallet from 'wallets' queue: {}", wallet);
            log.info("========================================");
            
            // Process wallet synchronously
            walletService.processWalletSafely(wallet);
            
            log.info("Successfully processed wallet: {}", wallet);
        } catch (Exception e) {
            log.error("Error processing wallet message: {}", e.getMessage(), e);
        }
    }
}
