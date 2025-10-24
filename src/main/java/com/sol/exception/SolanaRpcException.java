package com.sol.exception;

/**
 * Exception thrown when Solana RPC calls fail
 */
public class SolanaRpcException extends RuntimeException {
    
    private final String method;
    private final Integer statusCode;
    
    public SolanaRpcException(String message) {
        super(message);
        this.method = null;
        this.statusCode = null;
    }
    
    public SolanaRpcException(String message, Throwable cause) {
        super(message, cause);
        this.method = null;
        this.statusCode = null;
    }
    
    public SolanaRpcException(String method, String message, Throwable cause) {
        super(String.format("RPC method '%s' failed: %s", method, message), cause);
        this.method = method;
        this.statusCode = null;
    }
    
    public SolanaRpcException(String method, Integer statusCode, String message, Throwable cause) {
        super(String.format("RPC method '%s' failed with status %d: %s", method, statusCode, message), cause);
        this.method = method;
        this.statusCode = statusCode;
    }
    
    public String getMethod() {
        return method;
    }
    
    public Integer getStatusCode() {
        return statusCode;
    }
}
