package com.sol.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * Global exception handler for REST API endpoints
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(SolanaRpcException.class)
    public ResponseEntity<Map<String, Object>> handleSolanaRpcException(SolanaRpcException ex) {
        log.error("Solana RPC error: {}", ex.getMessage(), ex);
        
        Map<String, Object> body = new HashMap<>();
        body.put("timestamp", LocalDateTime.now());
        body.put("error", "Solana RPC Error");
        body.put("message", ex.getMessage());
        body.put("method", ex.getMethod());
        body.put("statusCode", ex.getStatusCode());
        
        return new ResponseEntity<>(body, HttpStatus.BAD_GATEWAY);
    }

    @ExceptionHandler(PriceDataNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handlePriceDataNotFoundException(PriceDataNotFoundException ex) {
        log.error("Price data not found: {}", ex.getMessage(), ex);
        
        Map<String, Object> body = new HashMap<>();
        body.put("timestamp", LocalDateTime.now());
        body.put("error", "Price Data Not Found");
        body.put("message", ex.getMessage());
        body.put("tokenAddress", ex.getTokenAddress());
        body.put("blockTime", ex.getBlockTime());
        
        return new ResponseEntity<>(body, HttpStatus.NOT_FOUND);
    }

    @ExceptionHandler(TransactionNormalizationException.class)
    public ResponseEntity<Map<String, Object>> handleTransactionNormalizationException(TransactionNormalizationException ex) {
        log.error("Transaction normalization error: {}", ex.getMessage(), ex);
        
        Map<String, Object> body = new HashMap<>();
        body.put("timestamp", LocalDateTime.now());
        body.put("error", "Transaction Normalization Error");
        body.put("message", ex.getMessage());
        body.put("signature", ex.getSignature());
        
        return new ResponseEntity<>(body, HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @ExceptionHandler(ProxyPoolExhaustedException.class)
    public ResponseEntity<Map<String, Object>> handleProxyPoolExhaustedException(ProxyPoolExhaustedException ex) {
        log.error("Proxy pool exhausted: {}", ex.getMessage(), ex);
        
        Map<String, Object> body = new HashMap<>();
        body.put("timestamp", LocalDateTime.now());
        body.put("error", "Proxy Pool Exhausted");
        body.put("message", ex.getMessage());
        body.put("poolSize", ex.getPoolSize());
        body.put("attemptCount", ex.getAttemptCount());
        
        return new ResponseEntity<>(body, HttpStatus.SERVICE_UNAVAILABLE);
    }

    @ExceptionHandler(WalletProcessingException.class)
    public ResponseEntity<Map<String, Object>> handleWalletProcessingException(WalletProcessingException ex) {
        log.error("Wallet processing error: {}", ex.getMessage(), ex);
        
        Map<String, Object> body = new HashMap<>();
        body.put("timestamp", LocalDateTime.now());
        body.put("error", "Wallet Processing Error");
        body.put("message", ex.getMessage());
        body.put("walletAddress", ex.getWalletAddress());
        body.put("stage", ex.getStage());
        
        return new ResponseEntity<>(body, HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @ExceptionHandler(WebClientResponseException.class)
    public ResponseEntity<Map<String, Object>> handleWebClientResponseException(WebClientResponseException ex) {
        log.error("WebClient error: {} - {}", ex.getStatusCode(), ex.getMessage(), ex);
        
        Map<String, Object> body = new HashMap<>();
        body.put("timestamp", LocalDateTime.now());
        body.put("error", "HTTP Client Error");
        body.put("message", ex.getMessage());
        body.put("statusCode", ex.getStatusCode().value());
        body.put("responseBody", ex.getResponseBodyAsString());
        
        return new ResponseEntity<>(body, ex.getStatusCode());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgumentException(IllegalArgumentException ex) {
        log.error("Invalid argument: {}", ex.getMessage(), ex);
        
        Map<String, Object> body = new HashMap<>();
        body.put("timestamp", LocalDateTime.now());
        body.put("error", "Invalid Argument");
        body.put("message", ex.getMessage());
        
        return new ResponseEntity<>(body, HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGenericException(Exception ex) {
        log.error("Unexpected error: {}", ex.getMessage(), ex);
        
        Map<String, Object> body = new HashMap<>();
        body.put("timestamp", LocalDateTime.now());
        body.put("error", "Internal Server Error");
        body.put("message", "An unexpected error occurred");
        
        return new ResponseEntity<>(body, HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
