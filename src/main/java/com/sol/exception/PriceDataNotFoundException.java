package com.sol.exception;

/**
 * Exception thrown when price data is not found for a given token and timestamp
 */
public class PriceDataNotFoundException extends RuntimeException {
    
    private final String tokenAddress;
    private final Long blockTime;
    
    public PriceDataNotFoundException(String tokenAddress, Long blockTime) {
        super(String.format("No price data found for token %s at block time %d", tokenAddress, blockTime));
        this.tokenAddress = tokenAddress;
        this.blockTime = blockTime;
    }
    
    public PriceDataNotFoundException(String tokenAddress, Long blockTime, Throwable cause) {
        super(String.format("No price data found for token %s at block time %d", tokenAddress, blockTime), cause);
        this.tokenAddress = tokenAddress;
        this.blockTime = blockTime;
    }
    
    public String getTokenAddress() {
        return tokenAddress;
    }
    
    public Long getBlockTime() {
        return blockTime;
    }
}
