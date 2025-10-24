package com.sol.exception;

/**
 * Exception thrown when wallet processing fails
 */
public class WalletProcessingException extends RuntimeException {
    
    private final String walletAddress;
    private final String stage;
    
    public WalletProcessingException(String walletAddress, String stage, String message) {
        super(String.format("Failed to process wallet %s at stage '%s': %s", walletAddress, stage, message));
        this.walletAddress = walletAddress;
        this.stage = stage;
    }
    
    public WalletProcessingException(String walletAddress, String stage, String message, Throwable cause) {
        super(String.format("Failed to process wallet %s at stage '%s': %s", walletAddress, stage, message), cause);
        this.walletAddress = walletAddress;
        this.stage = stage;
    }
    
    public String getWalletAddress() {
        return walletAddress;
    }
    
    public String getStage() {
        return stage;
    }
}
