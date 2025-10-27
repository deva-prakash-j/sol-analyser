package com.sol.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sol.entity.Proxies;
import com.sol.repository.ProxyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * REST API for managing proxy imports
 */
@RestController
@RequestMapping("/api/proxies")
@RequiredArgsConstructor
@Slf4j
public class ProxyController {
    
    private final ProxyRepository proxyRepository;
    private final ResourceLoader resourceLoader;
    private final ObjectMapper objectMapper;
    
    /**
     * Import proxies from resources/static/proxy.json and save to database
     * POST /api/proxies/import
     */
    @PostMapping("/import")
    //@Transactional
    public ResponseEntity<Map<String, Object>> importProxies() {
        try {
            // Load JSON file from classpath
            Resource resource = resourceLoader.getResource("classpath:static/proxy.json");
            
            if (!resource.exists()) {
                log.error("proxy.json file not found in resources/static/");
                return ResponseEntity.badRequest()
                    .body(Map.of("error", "proxy.json file not found in resources/static/"));
            }
            
            // Parse JSON array to list of strings (proxy connection strings)
            List<String> proxySessions = objectMapper.readValue(
                resource.getInputStream(),
                new TypeReference<List<String>>() {}
            );
            
            log.info("Loaded {} proxy sessions from JSON, starting batch import", proxySessions.size());
            
            // Convert to Proxies entities
            List<Proxies> allProxies = proxySessions.stream()
                .filter(session -> session != null && !session.trim().isEmpty())
                .map(session -> {
                    Proxies proxy = new Proxies();
                    proxy.setSession(session);
                    return proxy;
                })
                .collect(Collectors.toList());
            
            // Save in batches of 500 to avoid memory issues
            int batchSize = 500;
            int totalSaved = 0;
            int totalBatches = (int) Math.ceil((double) allProxies.size() / batchSize);
            
            for (int i = 0; i < allProxies.size(); i += batchSize) {
                int end = Math.min(i + batchSize, allProxies.size());
                List<Proxies> batch = allProxies.subList(i, end);
                
                proxyRepository.saveAll(batch);
                proxyRepository.flush(); // Force write to database
                
                totalSaved += batch.size();
                int currentBatch = (i / batchSize) + 1;
                log.info("Saved batch {}/{}: {} proxies (total: {})", 
                    currentBatch, totalBatches, batch.size(), totalSaved);
            }
            
            log.info("Successfully imported {} proxies in {} batches", totalSaved, totalBatches);
            
            return ResponseEntity.ok(Map.of(
                "success", true,
                "count", totalSaved,
                "batches", totalBatches,
                "message", "Successfully imported " + totalSaved + " proxies in " + totalBatches + " batches"
            ));
            
        } catch (IOException e) {
            log.error("Failed to read or parse proxy.json", e);
            return ResponseEntity.internalServerError()
                .body(Map.of("error", "Failed to read or parse proxy.json: " + e.getMessage()));
        } catch (Exception e) {
            log.error("Failed to import proxies", e);
            return ResponseEntity.internalServerError()
                .body(Map.of("error", "Failed to import proxies: " + e.getMessage()));
        }
    }
    
    /**
     * Get count of proxies in database
     * GET /api/proxies/count
     */
    @GetMapping("/count")
    public ResponseEntity<Map<String, Object>> getProxyCount() {
        long count = proxyRepository.count();
        return ResponseEntity.ok(Map.of("count", count));
    }
    
    /**
     * Delete all proxies from database
     * DELETE /api/proxies
     */
    @DeleteMapping
    @Transactional
    public ResponseEntity<Map<String, Object>> deleteAllProxies() {
        proxyRepository.deleteAll();
        log.info("Deleted all proxies from database");
        return ResponseEntity.ok(Map.of(
            "success", true,
            "message", "All proxies deleted"
        ));
    }
}
