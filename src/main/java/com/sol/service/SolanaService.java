package com.sol.service;

import com.sol.cache.annotation.Cached;
import com.sol.dto.SolanaRequest;
import com.sol.dto.TokenAccountByOwnerResponse;
import com.sol.dto.Transaction;
import com.sol.dto.TransactionSignaturesResponse;
import com.sol.proxy.BatchProcessor;
import com.sol.proxy.HttpOps;
import com.sol.util.CommonUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

@Service
@RequiredArgsConstructor
public class SolanaService {

    private final HttpOps httpOps;
    private final BatchProcessor batchProcessor;

    private static final String SOLANA_RPC_URL = "https://api.mainnet-beta.solana.com";

    @Cached(cacheName = "solanaService:getTokenAccountsByOwner", key = "#wallet", activeProfile = "local")
    public TokenAccountByOwnerResponse getTokenAccountsByOwner(String wallet) {
        SolanaRequest blockRequest = SolanaRequest.builder().method("getTokenAccountsByOwner")
                .params(List.of(wallet, Map.of("programId", "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA"),
                        Map.of("commitment", "confirmed", "encoding", "jsonParsed")))
                .build();

        return httpOps.postJsonOnce(SOLANA_RPC_URL, "", blockRequest, TokenAccountByOwnerResponse.class, Map.of()).block();
    }

    public Mono<TransactionSignaturesResponse> getSignaturesForAddress(
            WebClient client,
            String account
    ) {
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

            return httpOps.postJsonOnce(SOLANA_RPC_URL, "", req, TransactionSignaturesResponse.class, Map.of());
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
                });
    }

    public Mono<Transaction> getTransaction(WebClient client, String signature) {
        Map<String, Object> params = new HashMap<>();
        params.put("commitment", "confirmed");
        params.put("encoding", "json"); // smaller payload than jsonParsed
        params.put("maxSupportedTransactionVersion", 0);
        params.put("rewards", false);
        SolanaRequest blockRequest = SolanaRequest.builder().method("getTransaction").params(List.of(signature, params)).build();
        return httpOps.postJsonOnce(client, SOLANA_RPC_URL, "", blockRequest, Transaction.class, Map.of());
    }

    @Cached(cacheName = "solanaService:getSignaturesBulk", key = "#wallet", activeProfile = "local")
    public List<TransactionSignaturesResponse> getSignaturesBulk(List<String> accounts, String wallet) {
        return batchProcessor.processInLanes(SOLANA_RPC_URL, accounts, 40, this::getSignaturesForAddress).collectList().block();
    }

    @Cached(cacheName = "solanaService:getTransactionBulk", key = "#wallet", activeProfile = "local")
    public List<Transaction> getTransactionBulk(List<String> signatures, String wallet) {
        return batchProcessor.processInLanes(SOLANA_RPC_URL, signatures, 40, this::getTransaction).collectList().block();
    }

}
