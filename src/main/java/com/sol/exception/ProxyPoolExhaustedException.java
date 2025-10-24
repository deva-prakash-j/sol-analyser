package com.sol.exception;

/**
 * Exception thrown when all proxies in the pool have failed
 */
public class ProxyPoolExhaustedException extends RuntimeException {
    
    private final int poolSize;
    private final int attemptCount;
    
    public ProxyPoolExhaustedException(int poolSize, int attemptCount) {
        super(String.format("All %d proxies exhausted after %d attempts", poolSize, attemptCount));
        this.poolSize = poolSize;
        this.attemptCount = attemptCount;
    }
    
    public ProxyPoolExhaustedException(int poolSize, int attemptCount, Throwable cause) {
        super(String.format("All %d proxies exhausted after %d attempts", poolSize, attemptCount), cause);
        this.poolSize = poolSize;
        this.attemptCount = attemptCount;
    }
    
    public int getPoolSize() {
        return poolSize;
    }
    
    public int getAttemptCount() {
        return attemptCount;
    }
}
