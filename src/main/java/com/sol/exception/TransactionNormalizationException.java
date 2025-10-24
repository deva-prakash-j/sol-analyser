package com.sol.exception;

/**
 * Exception thrown when transaction normalization fails
 */
public class TransactionNormalizationException extends RuntimeException {
    
    private final String signature;
    
    public TransactionNormalizationException(String message) {
        super(message);
        this.signature = null;
    }
    
    public TransactionNormalizationException(String signature, String message) {
        super(String.format("Failed to normalize transaction %s: %s", signature, message));
        this.signature = signature;
    }
    
    public TransactionNormalizationException(String signature, String message, Throwable cause) {
        super(String.format("Failed to normalize transaction %s: %s", signature, message), cause);
        this.signature = signature;
    }
    
    public String getSignature() {
        return signature;
    }
}
