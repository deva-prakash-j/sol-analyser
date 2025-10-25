package com.sol.consumer;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Component
@Slf4j
public class WalletQueueConsumer {

    @RabbitListener(queues = "wallets")
    public void consumeWalletMessage(Message message) {
        try {
            String body = new String(message.getBody(), StandardCharsets.UTF_8);
            log.info("========================================");
            log.info("Received message from 'wallets' queue:");
            log.info("Message: {}", body);
            log.info("Message Properties: {}", message.getMessageProperties());
            log.info("========================================");
        } catch (Exception e) {
            log.error("Error processing message: {}", e.getMessage(), e);
        }
    }
}
