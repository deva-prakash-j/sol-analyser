package com.sol.bean;

import lombok.Data;

// @Configuration - Removed: No longer used with DatabaseProxyProvider
// @ConfigurationProperties(prefix = "proxy-pool")
@Data
public class ProxyPoolProperties {
    private String host;
    private int port;
    private String usernamePrefix;
    private int startIndex;
    private int count;
    private String password;
    private int perProxyMaxConnections = 1;
    private int connectTimeoutMs = 10_000;
    private int responseTimeoutMs = 30_000;
    private int readTimeoutSec = 30;
    private int writeTimeoutSec = 30;
    private boolean enableMetrics = true;
}
