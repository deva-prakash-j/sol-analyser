package com.sol.consumer;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class WalletQueueConsumer {

    @RabbitListener(queues = "wallets")
    public void consumeWalletMessage(String message) {
        log.info("========================================");
        log.info("Received message from 'wallets' queue:");
        log.info("Message: {}", message);
        log.info("========================================");
        
       
    }
}
