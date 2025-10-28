package com.sol.proxy;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

// @Component - Removed: No longer used, replaced by DatabaseProxyProvider
@Slf4j
public class OculusProxyProvider {

    private static final String OCULUS_API_URL = "https://api.oculusproxies.com/v1/configure/proxy/getProxies";
    private static final int MAX_SESSIONS = 1000;
    private static final long SESSION_COOLDOWN_MS = 60_000; // 1 minute
    
    // High-quality countries with good proxy diversity
    private static final List<String> COUNTRY_ROTATION = List.of(
        "us", "gb", "de", "fr", "ca", 
        "nl", "au", "se", "ch", "be",
        "es", "it", "pl", "jp", "br"
    );
    private int countryIndex = 0;

    @Value("${oculus.auth-token}")
    private String authToken;

    @Value("${oculus.order-token}")
    private String orderToken;

    @Value("${oculus.country:de}")
    private String country;

    @Value("${oculus.plan-type:SHARED_DC}")
    private String planType;

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final Map<String, Long> usedSessions = new ConcurrentHashMap<>();
    private String cachedPublicIp;

    public OculusProxyProvider() {
        this.webClient = WebClient.builder().build();
        this.objectMapper = new ObjectMapper();
        this.cachedPublicIp = fetchPublicIp();
    }

    /**
     * Fetch current machine's public IP address
     */
    private String fetchPublicIp() {
        try {
            String ip = webClient.get()
                    .uri("https://api.ipify.org")
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(60)) 
                    .block();
            
            log.info("Detected public IP: {}", ip);
            return ip;
        } catch (Exception e) {
            log.error("Failed to fetch public IP, using fallback: {}", e.getMessage());
            return "0.0.0.0"; // Fallback
        }
    }

    /**
     * Fetch fresh proxy sessions from Oculus API with guaranteed unique IPs
     * Always fetches 1000 proxies and deduplicates IPs automatically
     * @param count Number of unique sessions needed (max 1000)
     * @return List of proxy strings in format "host:port:username:password" with verified unique IPs
     */
    public List<String> fetchProxySessions(int count) {
        if (count > MAX_SESSIONS) {
            throw new IllegalArgumentException("Cannot fetch more than " + MAX_SESSIONS + " sessions at once");
        }

        List<String> uniqueProxies = new ArrayList<>();
        Set<String> verifiedIPs = new HashSet<>();
        int attempts = 0;
        int maxAttempts = 3;
        
        log.info("Starting proxy fetch: need {} sessions with unique IPs", count);

        while (uniqueProxies.size() < count && attempts < maxAttempts) {
            attempts++;
            
            // Request only the needed count (not always 1000) - adds 20% buffer for deduplication
            int remaining = count - uniqueProxies.size();
            int requestCount = Math.min(MAX_SESSIONS, (int)(remaining * 1.2));
            
            log.info("Attempt {}/{}: Requesting {} sessions (have {} unique, need {} more)", 
                    attempts, maxAttempts, requestCount, uniqueProxies.size(), remaining);

            try {
                List<String> newProxies = fetchProxyBatch(requestCount);
                
                // Filter out recently used sessions
                List<String> availableProxies = newProxies.stream()
                        .filter(this::isSessionAvailable)
                        .collect(Collectors.toList());

                log.info("Got {} available proxies after session filtering", availableProxies.size());

                // Verify IPs in parallel and add unique ones
                List<String> newUniqueProxies = verifyUniqueProxyIPsParallel(availableProxies, verifiedIPs);
                
                uniqueProxies.addAll(newUniqueProxies);
                
                log.info("After IP verification: {} unique proxies total ({} still needed)", 
                        uniqueProxies.size(), Math.max(0, count - uniqueProxies.size()));

                if (uniqueProxies.size() >= count) {
                    break;
                }
                
                // If we got very few unique IPs, wait before retry
                if (newUniqueProxies.size() < 50) {
                    log.warn("Low IP diversity: only {} unique IPs from {} sessions - waiting 3s before retry", 
                            newUniqueProxies.size(), availableProxies.size());
                    Thread.sleep(3000);
                }

            } catch (Exception e) {
                log.error("Error in fetch attempt {}: {}", attempts, e.getMessage());
                if (attempts >= maxAttempts) {
                    break;
                }
            }
        }

        // Mark these sessions as used
        long now = System.currentTimeMillis();
        uniqueProxies.forEach(proxy -> {
            String sessionId = extractSessionId(proxy);
            usedSessions.put(sessionId, now);
        });

        // Cleanup old session records
        cleanupOldSessions();

        log.info("✓ Final result: {} unique proxy sessions delivered (requested: {}, attempts: {})", 
                uniqueProxies.size(), count, attempts);

        // Return up to the requested count
        return uniqueProxies.subList(0, Math.min(count, uniqueProxies.size()));
    }
    
    /**
     * Fetch a single batch of proxies from Oculus API
     * Rotates through different countries to maximize IP diversity
     */
    private List<String> fetchProxyBatch(int count) {
        try {
            // Rotate to next country for better IP diversity
            String selectedCountry = getNextCountry();
            
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("orderToken", orderToken);
            requestBody.put("country", selectedCountry);
            requestBody.put("numberOfProxies", count);
            requestBody.put("enableSock5", false);
            requestBody.put("whiteListIP", List.of(cachedPublicIp));
            requestBody.put("planType", planType);
            System.out.println(requestBody);
            log.info("Requesting {} proxies from {} (country rotation)", count, selectedCountry.toUpperCase());

            String response = webClient.post()
                    .uri(OCULUS_API_URL)
                    .header("authToken", authToken)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            List<String> proxies = objectMapper.readValue(response, new TypeReference<List<String>>() {});
            log.debug("Received {} proxies from {}", proxies.size(), selectedCountry.toUpperCase());
            
            return proxies;

        } catch (Exception e) {
            log.error("Failed to fetch proxy batch from Oculus: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to fetch proxy batch", e);
        }
    }
    
    /**
     * Get next country in rotation sequence
     * This ensures each attempt uses a different country for maximum IP diversity
     */
    private synchronized String getNextCountry() {
        String selectedCountry = COUNTRY_ROTATION.get(countryIndex % COUNTRY_ROTATION.size());
        countryIndex++;
        return selectedCountry;
    }
    
    /**
     * Verify unique IPs in parallel, adding only new unique IPs not in the verifiedIPs set
     * This allows incremental verification across multiple fetch attempts
     * 
     * @param proxySessions List of proxy sessions to verify
     * @param verifiedIPs Set of already verified IPs (will be updated)
     * @return List of proxies with unique IPs
     */
    private List<String> verifyUniqueProxyIPsParallel(List<String> proxySessions, Set<String> verifiedIPs) {
        if (proxySessions.isEmpty()) {
            return new ArrayList<>();
        }
        
        log.info("Verifying {} proxy sessions in parallel...", proxySessions.size());
        
        // Parallel verification - all proxies checked concurrently
        List<Map.Entry<String, String>> verifiedProxies = proxySessions.parallelStream()
                .map(proxyString -> {
                    try {
                        ProxyCredentials creds = parseProxyString(proxyString);
                        String publicIP = getProxyPublicIP(creds);
                        
                        if (publicIP != null && !publicIP.isEmpty()) {
                            return Map.entry(proxyString, publicIP);
                        }
                    } catch (Exception e) {
                        log.debug("Failed to verify IP for proxy: {}", e.getMessage());
                    }
                    return null;
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        
        // Filter to keep only unique IPs (thread-safe)
        List<String> uniqueProxies = Collections.synchronizedList(new ArrayList<>());
        int duplicates = 0;
        int failed = proxySessions.size() - verifiedProxies.size();
        
        for (Map.Entry<String, String> entry : verifiedProxies) {
            String proxyString = entry.getKey();
            String ip = entry.getValue();
            
            // Thread-safe add to set
            synchronized (verifiedIPs) {
                if (verifiedIPs.add(ip)) {
                    uniqueProxies.add(proxyString);
                    log.debug("✓ Proxy {} → unique IP: {}", extractSessionId(proxyString), ip);
                } else {
                    duplicates++;
                    log.debug("✗ Duplicate IP: {} (session: {})", ip, extractSessionId(proxyString));
                }
            }
        }
        
        log.info("IP Verification complete: {} verified → {} unique, {} duplicates, {} failed", 
                proxySessions.size(), uniqueProxies.size(), duplicates, failed);
        
        return uniqueProxies;
    }
    
    /**
     * Get the public IP address that a proxy presents to the outside world
     * Uses increased timeouts to handle slow proxy connections
     */
    private String getProxyPublicIP(ProxyCredentials creds) {
        try {
            // Create a WebClient configured with this specific proxy and generous timeouts
            WebClient proxyClient = WebClient.builder()
                    .clientConnector(new org.springframework.http.client.reactive.ReactorClientHttpConnector(
                        reactor.netty.http.client.HttpClient.create()
                            .proxy(proxy -> proxy.type(reactor.netty.transport.ProxyProvider.Proxy.HTTP)
                                .host(creds.getHost())
                                .port(creds.getPort())
                                .username(creds.getUsername())
                                .password(u -> creds.getPassword())
                                .connectTimeoutMillis(15000))  // 15 second connect timeout
                            .responseTimeout(Duration.ofSeconds(15))  // 15 second response timeout
                    ))
                    .build();
            
            String ip = proxyClient.get()
                    .uri("https://api.ipify.org")
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(15))  // Overall 15 second timeout
                    .block();
            
            return ip;
        } catch (Exception e) {
            // Log only at debug level to avoid spam - many proxies may fail
            log.debug("Failed to get public IP for proxy {}:{} - {}", 
                    creds.getHost(), creds.getPort(), e.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * Check if a session is available (not used in last minute)
     */
    private boolean isSessionAvailable(String proxyString) {
        String sessionId = extractSessionId(proxyString);
        Long lastUsed = usedSessions.get(sessionId);
        
        if (lastUsed == null) {
            return true;
        }

        long age = System.currentTimeMillis() - lastUsed;
        return age > SESSION_COOLDOWN_MS;
    }

    /**
     * Extract session ID from proxy string
     * Format: "proxy.oculus-proxy.com:31111:oc-xxx-session-363f7:password"
     */
    private String extractSessionId(String proxyString) {
        String[] parts = proxyString.split(":");
        if (parts.length >= 3) {
            String username = parts[2];
            // Extract session part from username
            int sessionIndex = username.indexOf("-session-");
            if (sessionIndex != -1) {
                return username.substring(sessionIndex + 9); // +9 for "-session-"
            }
        }
        return proxyString; // fallback to full string
    }

    /**
     * Remove session records older than cooldown period
     */
    private void cleanupOldSessions() {
        long cutoff = System.currentTimeMillis() - SESSION_COOLDOWN_MS;
        usedSessions.entrySet().removeIf(entry -> entry.getValue() < cutoff);
    }

    /**
     * Parse proxy string into ProxyCredentials
     * Format: "host:port:username:password"
     */
    public ProxyCredentials parseProxyString(String proxyString) {
        String[] parts = proxyString.split(":");
        if (parts.length != 4) {
            throw new IllegalArgumentException("Invalid proxy format: " + proxyString);
        }

        return ProxyCredentials.builder()
                .host(parts[0])
                .port(Integer.parseInt(parts[1]))
                .username(parts[2])
                .password(parts[3])
                .build();
    }

    /**
     * Fetch proxy sessions region by region until we get target unique IPs or exhaust all regions
     * This is optimized for large batch operations like getTransaction
     * 
     * @param targetUniqueIPs Target number of unique IPs (max 1000)
     * @return List of proxy strings with verified unique IPs
     */
    public List<String> fetchSessionsByRegion(int targetUniqueIPs) {
        if (targetUniqueIPs > MAX_SESSIONS) {
            throw new IllegalArgumentException("Cannot fetch more than " + MAX_SESSIONS + " unique IPs");
        }

        List<String> uniqueProxies = new ArrayList<>();
        Set<String> verifiedIPs = new HashSet<>();
        
        log.info("Starting region-based proxy fetch: target {} unique IPs across {} regions", 
                targetUniqueIPs, COUNTRY_ROTATION.size());

        // Try each region until we have enough unique IPs
        for (int regionIdx = 0; regionIdx < COUNTRY_ROTATION.size(); regionIdx++) {
            String region = COUNTRY_ROTATION.get(regionIdx);
            
            log.info("Region {}/{}: Fetching from {} (have {} unique, need {} more)", 
                    regionIdx + 1, COUNTRY_ROTATION.size(), region.toUpperCase(), 
                    uniqueProxies.size(), targetUniqueIPs - uniqueProxies.size());

            try {
                // Fetch 1000 sessions from this region
                List<String> regionProxies = fetchProxyBatchFromRegion(region, MAX_SESSIONS);
                
                // Filter out recently used sessions
                List<String> availableProxies = regionProxies.stream()
                        .filter(this::isSessionAvailable)
                        .collect(Collectors.toList());

                log.info("Got {} available proxies from {} after session filtering", 
                        availableProxies.size(), region.toUpperCase());

                // Verify IPs in parallel and add unique ones
                List<String> newUniqueProxies = verifyUniqueProxyIPsParallel(availableProxies, verifiedIPs);
                uniqueProxies.addAll(newUniqueProxies);
                
                log.info("After IP verification: {} unique proxies total from {} ({} new unique IPs)", 
                        uniqueProxies.size(), region.toUpperCase(), newUniqueProxies.size());

                // Stop if we have enough unique IPs
                if (uniqueProxies.size() >= targetUniqueIPs) {
                    log.info("✓ Target reached: {} unique IPs from {} regions", 
                            uniqueProxies.size(), regionIdx + 1);
                    break;
                }

            } catch (Exception e) {
                log.error("Error fetching from region {}: {}", region.toUpperCase(), e.getMessage());
            }
        }

        // Mark these sessions as used
        long now = System.currentTimeMillis();
        uniqueProxies.forEach(proxy -> {
            String sessionId = extractSessionId(proxy);
            usedSessions.put(sessionId, now);
        });

        // Cleanup old session records
        cleanupOldSessions();

        int finalCount = Math.min(targetUniqueIPs, uniqueProxies.size());
        log.info("✓ Region-based fetch complete: {} unique proxy sessions delivered (target: {})", 
                finalCount, targetUniqueIPs);

        return uniqueProxies.subList(0, finalCount);
    }

    /**
     * Fetch proxies from a specific region
     */
    private List<String> fetchProxyBatchFromRegion(String region, int count) {
        try {
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("orderToken", orderToken);
            requestBody.put("country", region);
            requestBody.put("numberOfProxies", count);
            requestBody.put("enableSock5", false);
            requestBody.put("whiteListIP", List.of(cachedPublicIp));
            requestBody.put("planType", planType);

            String response = webClient.post()
                    .uri(OCULUS_API_URL)
                    .header("authToken", authToken)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            List<String> proxies = objectMapper.readValue(response, new TypeReference<List<String>>() {});
            log.debug("Received {} proxies from {}", proxies.size(), region.toUpperCase());
            
            return proxies;

        } catch (Exception e) {
            log.error("Failed to fetch proxy batch from region {}: {}", region.toUpperCase(), e.getMessage());
            throw new RuntimeException("Failed to fetch proxy batch from " + region, e);
        }
    }

    /**
     * Get statistics about session usage
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("activeSessions", usedSessions.size());
        stats.put("maxSessions", MAX_SESSIONS);
        stats.put("cooldownMs", SESSION_COOLDOWN_MS);
        return stats;
    }
}
