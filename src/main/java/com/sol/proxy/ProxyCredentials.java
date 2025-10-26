package com.sol.proxy;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ProxyCredentials {
    private String host;
    private int port;
    private String username;
    private String password;
}
