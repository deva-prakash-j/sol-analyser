package com.sol.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/**
 * RestTemplate configuration - Currently unused since all services use proxy-enabled DynamicHttpOps
 * Kept for potential future use cases that don't require proxy support
 */
// @Configuration - Disabled: All HTTP calls now use DynamicHttpOps with proxy support
public class RestTemplateConfig {

    // @Bean - Disabled: RestTemplate no longer needed
    public RestTemplate restTemplate() {
        RestTemplate restTemplate = new RestTemplate();
        
        // Configure timeouts
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(30000); // 30 seconds
        factory.setReadTimeout(30000); // 30 seconds
        
        restTemplate.setRequestFactory(factory);
        return restTemplate;
    }
}