package com.sol.service;

import com.sol.cache.annotation.Cached;
import com.sol.dto.SolanaRequest;
import com.sol.dto.TokenAccountByOwnerResponse;
import com.sol.dto.Transaction;
import com.sol.dto.TransactionSignaturesResponse;
import com.sol.exception.SolanaRpcException;
import com.sol.proxy.BatchProcessor;
import com.sol.proxy.HttpOps;
import com.sol.util.CommonUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

@Service
@RequiredArgsConstructor
@Slf4j
public class SolanaService {

    private final HttpOps httpOps;
    private final BatchProcessor batchProcessor;

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

            TokenAccountByOwnerResponse response = httpOps.postJsonOnce(SOLANA_RPC_URL, "", blockRequest, 
                    TokenAccountByOwnerResponse.class, Map.of()).block();
            
            if (response == null) {
                throw new SolanaRpcException("getTokenAccountsByOwner", "Received null response from RPC", null);
            }
            
            log.debug("Successfully fetched token accounts for wallet: {}", wallet);
            return response;
        } catch (Exception e) {
            log.error("Failed to fetch token accounts for wallet {}: {}", wallet, e.getMessage(), e);
            throw new SolanaRpcException("getTokenAccountsByOwner", 
                    "Failed to fetch token accounts for wallet " + wallet, e);
        }
    }

    public Mono<TransactionSignaturesResponse> getSignaturesForAddress(
            WebClient client,
            String account
    ) {
        if (account == null || account.isBlank()) {
            return Mono.error(new IllegalArgumentException("Account address cannot be null or empty"));
        }
        
        final int PAGE_SIZE = 1000;
        long limitUnix = CommonUtil.getDateBeforeDays(120);

        // inline "one page" fetch
        Function<String, Mono<TransactionSignaturesResponse>> fetchPage = (String before) -> {
            Map<String, Object> params = new HashMap<>();
            params.put("commitment", "confirmed");
            params.put("limit", PAGE_SIZE);
            if (before != null && !before.isBlank()) params.put("before", before);

            SolanaRequest req = SolanaRequest.builder()
                    .method("getSignaturesForAddress")
                    .params(List.of(account, params))
                    .build();

            return httpOps.postJsonOnce(SOLANA_RPC_URL, "", req, TransactionSignaturesResponse.class, Map.of())
                    .doOnError(error -> log.error("Failed to fetch signatures for account {}: {}", 
                            account, error.getMessage()))
                    .onErrorResume(error -> {
                        log.warn("Recovering from signature fetch error for account {}, returning empty response", account);
                        TransactionSignaturesResponse empty = new TransactionSignaturesResponse();
                        empty.setResult(List.of());
                        return Mono.just(empty);
                    });
        };

        return fetchPage.apply(null)
                .expand(resp -> {
                    List<TransactionSignaturesResponse.Result> page =
                            resp.getResult() == null ? List.of() : resp.getResult();

                    if (page.isEmpty() || page.size() < PAGE_SIZE) return Mono.empty(); // done
                    if( page.get(page.size() - 1).getBlockTime() < limitUnix) return Mono.empty();
                    String nextBefore = page.get(page.size() - 1).getSignature();
                    return fetchPage.apply(nextBefore);
                })
                .flatMapIterable(TransactionSignaturesResponse::getResult)
                .collectList()
                .map(all -> {
                    TransactionSignaturesResponse agg = new TransactionSignaturesResponse();
                    agg.setResult(all);
                    return agg;
                })
                .doOnSuccess(result -> log.debug("Fetched {} signatures for account {}", 
                        result.getResult() != null ? result.getResult().size() : 0, account));
    }

    public Mono<Transaction> getTransaction(WebClient client, String signature) {
        if (signature == null || signature.isBlank()) {
            return Mono.error(new IllegalArgumentException("Transaction signature cannot be null or empty"));
        }
        
        Map<String, Object> params = new HashMap<>();
        params.put("commitment", "confirmed");
        params.put("encoding", "json"); // smaller payload than jsonParsed
        params.put("maxSupportedTransactionVersion", 0);
        params.put("rewards", false);
        SolanaRequest blockRequest = SolanaRequest.builder().method("getTransaction").params(List.of(signature, params)).build();
        return httpOps.postJsonOnce(client, SOLANA_RPC_URL, "", blockRequest, Transaction.class, Map.of())
                .doOnError(error -> log.error("Failed to fetch transaction {}: {}", signature, error.getMessage()))
                .onErrorResume(error -> {
                    log.warn("Recovering from transaction fetch error for signature {}, returning null", signature);
                    return Mono.empty();
                });
    }

    @Cached(cacheName = "solanaService:getSignaturesBulk", key = "#wallet", activeProfile = "local")
    public List<TransactionSignaturesResponse> getSignaturesBulk(List<String> accounts, String wallet) {
        if (accounts == null || accounts.isEmpty()) {
            log.warn("No accounts provided for signature bulk fetch");
            return List.of();
        }
        
        log.info("Fetching signatures for {} accounts (wallet: {})", accounts.size(), wallet);
        
        try {
            List<TransactionSignaturesResponse> responses = batchProcessor
                    .processInLanes(SOLANA_RPC_URL, accounts, 40, this::getSignaturesForAddress)
                    .collectList()
                    .block();
            
            if (responses == null) {
                log.warn("Received null response from batch processor for wallet {}", wallet);
                return List.of();
            }
            
            log.info("Successfully fetched signatures for {} accounts (wallet: {})", responses.size(), wallet);
            return responses;
        } catch (Exception e) {
            log.error("Failed to fetch signatures bulk for wallet {}: {}", wallet, e.getMessage(), e);
            throw new SolanaRpcException("getSignaturesBulk", 
                    "Failed to fetch signatures for wallet " + wallet, e);
        }
    }

    @Cached(cacheName = "solanaService:getTransactionBulk", key = "#wallet", activeProfile = "local")
    public List<Transaction> getTransactionBulk(List<String> signatures, String wallet) {
        if (signatures == null || signatures.isEmpty()) {
            log.warn("No signatures provided for transaction bulk fetch");
            return List.of();
        }
        
        log.info("Fetching {} transactions for wallet: {}", signatures.size(), wallet);
        
        try {
            List<Transaction> transactions = batchProcessor
                    .processInLanes(SOLANA_RPC_URL, signatures, 40, this::getTransaction)
                    .collectList()
                    .block();
            
            if (transactions == null) {
                log.warn("Received null response from batch processor for wallet {}", wallet);
                return List.of();
            }
            
            // Filter out nulls from failed individual fetches
            List<Transaction> validTransactions = transactions.stream()
                    .filter(tx -> tx != null)
                    .toList();
            
            log.info("Successfully fetched {}/{} transactions for wallet: {}", 
                    validTransactions.size(), signatures.size(), wallet);
            
            if (validTransactions.size() < signatures.size()) {
                log.warn("Failed to fetch {} transactions for wallet: {}", 
                        signatures.size() - validTransactions.size(), wallet);
            }
            
            return validTransactions;
        } catch (Exception e) {
            log.error("Failed to fetch transactions bulk for wallet {}: {}", wallet, e.getMessage(), e);
            throw new SolanaRpcException("getTransactionBulk", 
                    "Failed to fetch transactions for wallet " + wallet, e);
        }
    }

}
