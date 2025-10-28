package com.sol.proxy;

import com.sol.repository.ProxyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Database-based proxy provider that fetches proxy sessions from stored database records
 * Replaces the Oculus API integration with database-driven session management
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DatabaseProxyProvider {

    private final ProxyRepository proxyRepository;
    private Long lastFetchedId = 0L;

    /**
     * Fetch proxy sessions from database for small batches
     * @param count Number of sessions to fetch (should be <= 1000)
     * @return List of proxy session strings
     */
    public List<String> fetchProxySessions(int count) {
        if (count > 1000) {
            log.warn("Requested {} sessions, limiting to 1000", count);
            count = 1000;
        }
        
        log.info("Fetching {} proxy sessions from database", count);
        
        try {
            // Check if we have any proxy records at all
            long totalProxies = proxyRepository.count();
            if (totalProxies == 0) {
                log.error("No proxy records found in database! Please ensure Proxies table is populated.");
                throw new RuntimeException("No proxy records available in database");
            }
            
            log.debug("Total proxies available in database: {}", totalProxies);
            
            Pageable pageable = PageRequest.of(0, count);
            List<String> sessions = proxyRepository.findSessionsWithLimit(pageable);
            
            log.info("Successfully fetched {} proxy sessions from database", sessions.size());
            return sessions;
        } catch (Exception e) {
            log.error("Failed to fetch proxy sessions from database: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to fetch proxy sessions", e);
        }
    }

    /**
     * Fetch next batch of proxy sessions for large operations
     * Maintains state to fetch different sessions for each batch
     * @param count Number of sessions to fetch (typically 1000)
     * @return List of proxy session strings
     */
    public List<String> fetchNextBatchSessions(int count) {
        if (count > 1000) {
            log.warn("Requested {} sessions, limiting to 1000", count);
            count = 1000;
        }
        
        log.info("Fetching next batch of {} proxy sessions from database (after ID: {})", count, lastFetchedId);
        
        try {
            // Check if we have any proxy records at all
            long totalProxies = proxyRepository.count();
            if (totalProxies == 0) {
                log.error("No proxy records found in database! Please ensure Proxies table is populated.");
                throw new RuntimeException("No proxy records available in database");
            }
            
            log.debug("Total proxies available in database: {}", totalProxies);
            
            Pageable pageable = PageRequest.of(0, count);
            List<String> sessions;
            
            if (lastFetchedId == 0L) {
                // First batch - start from beginning
                sessions = proxyRepository.findSessionsWithLimit(pageable);
                if (!sessions.isEmpty()) {
                    // Update lastFetchedId based on the number of sessions fetched
                    lastFetchedId = (long) sessions.size();
                }
            } else {
                // Subsequent batches - start after last fetched ID
                log.debug("Fetching {} proxy sessions after ID: {}", count, lastFetchedId);
                sessions = proxyRepository.findSessionsAfterIdWithLimit(lastFetchedId, pageable);
                if (!sessions.isEmpty()) {
                    lastFetchedId += sessions.size();
                }
            }
            
            // If we've reached the end, reset to beginning for cycling
            if (sessions.isEmpty() || sessions.size() < count) {
                log.info("Reached end of proxy sessions, cycling back to beginning");
                lastFetchedId = 0L;
                if (sessions.isEmpty()) {
                    // Fetch from beginning if no sessions were returned
                    sessions = proxyRepository.findSessionsWithLimit(pageable);
                    if (!sessions.isEmpty()) {
                        lastFetchedId = (long) sessions.size();
                    }
                }
            }
            
            log.info("Successfully fetched {} proxy sessions from database (next batch will start after ID: {})", 
                    sessions.size(), lastFetchedId);
            return sessions;
        } catch (Exception e) {
            log.error("Failed to fetch next batch of proxy sessions from database: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to fetch proxy sessions", e);
        }
    }

    /**
     * Alias for fetchNextBatchSessions to maintain compatibility with existing code
     * @param targetSessions Number of sessions to fetch
     * @return List of proxy session strings
     */
    public List<String> fetchSessionsByRegion(int targetSessions) {
        return fetchNextBatchSessions(targetSessions);
    }

    /**
     * Parse proxy string in format "host:port:username:password"
     * @param proxyString Proxy string to parse
     * @return ProxyCredentials object
     */
    public ProxyCredentials parseProxyString(String proxyString) {
        String[] parts = proxyString.split(":");
        if (parts.length != 4) {
            throw new IllegalArgumentException("Invalid proxy format: " + proxyString + ". Expected format: host:port:username:password");
        }

        return ProxyCredentials.builder()
                .host(parts[0])
                .port(Integer.parseInt(parts[1]))
                .username(parts[2])
                .password(parts[3])
                .build();
    }

    /**
     * Reset the batch tracking for new large operation
     */
    public void resetBatchTracking() {
        log.info("Resetting batch tracking for new large operation");
        lastFetchedId = 0L;
    }

    /**
     * Get total count of available proxy sessions
     */
    public long getTotalSessionCount() {
        try {
            long count = proxyRepository.count();
            log.debug("Total proxy sessions available in database: {}", count);
            return count;
        } catch (Exception e) {
            log.error("Failed to get total session count: {}", e.getMessage(), e);
            return 0L;
        }
    }
}