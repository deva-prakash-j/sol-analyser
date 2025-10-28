package com.sol.service;

import com.sol.cache.annotation.Cached;
import com.sol.dto.SolanaRequest;
import com.sol.dto.TokenAccountByOwnerResponse;
import com.sol.dto.Transaction;
import com.sol.dto.TransactionSignaturesResponse;
import com.sol.exception.SolanaRpcException;
import com.sol.proxy.DynamicHttpOps;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class SolanaService {

    private final DynamicHttpOps dynamicHttpOps;

    private static final String SOLANA_RPC_URL = "https://api.mainnet-beta.solana.com";

    @Cached(cacheName = "solanaService:getTokenAccountsByOwner", key = "#wallet", activeProfile = "local")
    public TokenAccountByOwnerResponse getTokenAccountsByOwner(String wallet) {
        if (wallet == null || wallet.isBlank()) {
            throw new IllegalArgumentException("Wallet address cannot be null or empty");
        }
        
        log.debug("Fetching token accounts for wallet: {}", wallet);
        
        try {
            SolanaRequest blockRequest = SolanaRequest.builder().method("getTokenAccountsByOwner")
                    .params(List.of(wallet, Map.of("programId", "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA"),
                            Map.of("commitment", "confirmed", "encoding", "jsonParsed")))
                    .build();

            List<TokenAccountByOwnerResponse> responses = dynamicHttpOps.postJsonBatch(
                    SOLANA_RPC_URL, "", List.of(blockRequest), TokenAccountByOwnerResponse.class, Map.of());
            
            if (responses == null || responses.isEmpty()) {
                throw new SolanaRpcException("getTokenAccountsByOwner", "Received null response from RPC", null);
            }
            
            log.debug("Successfully fetched token accounts for wallet: {}", wallet);
            return responses.get(0);
        } catch (Exception e) {
            log.error("Failed to fetch token accounts for wallet {}: {}", wallet, e.getMessage(), e);
            throw new SolanaRpcException("getTokenAccountsByOwner", 
                    "Failed to fetch token accounts for wallet " + wallet, e);
        }
    }

    @Cached(cacheName = "solanaService:getSignaturesBulk", key = "#wallet", activeProfile = "local")
    public List<TransactionSignaturesResponse> getSignaturesBulk(List<String> accounts, String wallet) {
        if (accounts == null || accounts.isEmpty()) {
            log.warn("No accounts provided for signature bulk fetch");
            return List.of();
        }
        
        log.info("Fetching signatures for {} accounts (wallet: {}) with distributed proxy rotation", accounts.size(), wallet);
        
        try {
            List<TransactionSignaturesResponse> allResponses = new java.util.ArrayList<>();
            
            // Process each account with pagination using different proxy clients per page
            for (String account : accounts) {
                List<TransactionSignaturesResponse.Result> accountSignatures = new java.util.ArrayList<>();
                String beforeSignature = null;
                String previousBeforeSignature = null;
                boolean hasMore = true;
                int pageCount = 0;
                int maxPages = 100; // Safety limit to prevent infinite loops
                
                while (hasMore && pageCount < maxPages) {
                    pageCount++;
                    log.info("Fetching page {} for account {} (wallet: {}), total {}", pageCount, account, wallet, accountSignatures.size());
                    
                    // Create request with optional 'before' parameter for pagination
                    Map<String, Object> params = new HashMap<>();
                    params.put("commitment", "confirmed");
                    params.put("limit", 1000);
                    if (beforeSignature != null) {
                        params.put("before", beforeSignature);
                    }
                    
                    SolanaRequest request = SolanaRequest.builder()
                            .method("getSignaturesForAddress")
                            .params(List.of(account, params))
                            .build();
                    
                    // Use different proxy client for each page request
                    // This distributes the load and reduces risk of rate limiting on any single proxy
                    List<TransactionSignaturesResponse> pageResponses = dynamicHttpOps.postJsonBatch(
                            SOLANA_RPC_URL, "", 
                            List.of(request), 
                            TransactionSignaturesResponse.class, 
                            Map.of(),
                            false); // Request fresh proxy for each page
                    
                    if (pageResponses == null || pageResponses.isEmpty() || pageResponses.get(0) == null || pageResponses.get(0).getResult() == null || pageResponses.get(0).getResult().size() == 0) {
                        log.warn("Received null/empty response for account {} page {} (wallet: {})", account, pageCount, wallet);
                        break;
                    }
                    
                    TransactionSignaturesResponse pageResponse = pageResponses.get(0);
                    List<TransactionSignaturesResponse.Result> pageResults = pageResponse.getResult();
                    log.debug("Page {} for account {}: fetched {} signatures using fresh proxy", pageCount, account, pageResults.size());
                    
                    if (pageResults == null || pageResults.isEmpty()) {
                        log.debug("No more signatures for account {} (wallet: {})", account, wallet);
                        break;
                    }
                    
                    // Add results to accumulated list
                    accountSignatures.addAll(pageResults);
                    log.debug("Page {} for account {}: {} signatures (total: {})", 
                            pageCount, account, pageResults.size(), accountSignatures.size());
                    
                    // Check if we need to fetch more (result size == 1000 indicates possible more data)
                    if (pageResults.size() == 1000) {
                        // Get last signature for next page
                        String lastSignature = pageResults.get(pageResults.size() - 1).getSignature();
                        // long firstTimestamp = pageResults.get(0).getBlockTime();
                        // long lastTimeStamp = pageResults.get(pageResults.size() - 1).getBlockTime();

                        //log.info("FirstTimeStamp: {}, LastTimeStamp: {}", firstTimestamp, lastTimeStamp);
                        
                        // Check if we're stuck (same before signature as last iteration)
                        if (lastSignature.equals(previousBeforeSignature)) {
                            log.warn("Pagination detected duplicate before signature - stopping to prevent infinite loop for account {}", account);
                            break;
                        }
                        
                        previousBeforeSignature = beforeSignature;
                        beforeSignature = lastSignature;
                        //log.info("fetching next page with before={} using next proxy client", beforeSignature);
                    } else {
                        hasMore = false;
                    }
                }
                
                if (pageCount >= maxPages) {
                    log.warn("Reached maximum page limit ({}) for account {} (wallet: {})", maxPages, account, wallet);
                }
                
                // Create response object with all accumulated signatures for this account
                TransactionSignaturesResponse accountResponse = new TransactionSignaturesResponse();
                accountResponse.setResult(accountSignatures);
                allResponses.add(accountResponse);
                
                log.info("Fetched total {} signatures across {} pages for account {} (wallet: {}) with distributed proxy usage", 
                        accountSignatures.size(), pageCount, account, wallet);
            }
            
            log.info("Successfully fetched signatures for {} accounts (wallet: {}) using distributed proxy rotation", allResponses.size(), wallet);
            return allResponses;
        } catch (Exception e) {
            log.error("Failed to fetch signatures bulk for wallet {}: {}", wallet, e.getMessage(), e);
            return List.of();
        }
    }

    @Cached(cacheName = "solanaService:getTransactionBulk", key = "#wallet", activeProfile = "local")
    public List<Transaction> getTransactionBulk(List<String> signatures, String wallet) {
        if (signatures == null || signatures.isEmpty()) {
            log.warn("No signatures provided for transaction bulk fetch");
            return List.of();
        }
        
        log.info("Fetching {} transactions for wallet: {} (region-based session pooling with reuse)", 
                signatures.size(), wallet);
        
        try {
            // Create requests for each signature
            List<SolanaRequest> requests = signatures.stream()
                    .map(signature -> {
                        Map<String, Object> params = new HashMap<>();
                        params.put("commitment", "confirmed");
                        params.put("encoding", "json");
                        params.put("maxSupportedTransactionVersion", 0);
                        params.put("rewards", false);
                        
                        return SolanaRequest.builder()
                                .method("getTransaction")
                                .params(List.of(signature, params))
                                .build();
                    })
                    .collect(Collectors.toList());
            
            // Use region-based session pooling with reuse for large batches
            // Fetches up to 1000 unique IPs across regions, then reuses for all batches
            // Batch size: 1000 (or available sessions), 2s delay between batches
            int batchSize = 1000;
            int delaySeconds = 2;
            
            List<Transaction> transactions = dynamicHttpOps.postJsonBatchWithSessionReuse(
                    SOLANA_RPC_URL, "", 
                    requests.stream().map(r -> (Object) r).collect(Collectors.toList()), 
                    Transaction.class, 
                    Map.of(),
                    batchSize,
                    delaySeconds);
            
            if (transactions == null) {
                log.warn("Received null response for wallet {}", wallet);
                return List.of();
            }
            
            // Filter out nulls from failed fetches
            List<Transaction> validTransactions = transactions.stream()
                    .filter(tx -> tx != null)
                    .collect(Collectors.toList());
            
            log.info("Successfully fetched {}/{} transactions for wallet: {}", 
                    validTransactions.size(), signatures.size(), wallet);
            
            return validTransactions;
        } catch (Exception e) {
            log.error("Failed to fetch transactions bulk for wallet {}: {}", wallet, e.getMessage(), e);
            return List.of();
        }
    }

}
