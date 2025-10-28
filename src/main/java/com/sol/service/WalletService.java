package com.sol.service;

import com.sol.dto.TokenAccountByOwnerResponse;
import com.sol.dto.Transaction;
import com.sol.dto.TransactionSignaturesResponse;
import com.sol.exception.WalletProcessingException;
import com.sol.util.CommonUtil;
import com.sol.util.NormalizeTransaction;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.ObjectUtils;

import java.util.*;
import static com.sol.util.Constant.QUOTE_MINTS;

@Service
@RequiredArgsConstructor
@Slf4j
public class WalletService {

    private final SolanaService solanaService;
    private final NormalizeTransaction normalizeTransaction;

    /**
     * Crash-proof wrapper - guarantees no exceptions escape to caller
     */
    public void processWalletSafely(String wallet) {
        try {
            processWallet(wallet);
        } catch (Throwable t) {
            log.error("FATAL: Unhandled error in processWallet for {}: {}", wallet, t.getMessage(), t);
            // Swallow all exceptions and errors - absolutely nothing escapes
        }
    }

    /**
     * Main processing logic - can throw exceptions but they're caught by processWalletSafely
     */
    public void processWallet(String wallet) {
        if (wallet == null || wallet.isBlank()) {
            log.error("Invalid wallet address: null or empty - Skipping");
            return; // Don't throw, just return
        }
        
        log.info("Starting wallet processing for: {}", wallet);
        String currentStage = "initialization";
        
        try {
            // Stage 1: Fetch token accounts
            currentStage = "fetch_accounts";
            log.info("[Stage 1/5] Fetching token accounts for wallet: {}", wallet);
            TokenAccountByOwnerResponse accountByOwnerResponse = fetchTokenAccounts(wallet);
            
            if (accountByOwnerResponse == null || accountByOwnerResponse.getResult() == null
                    || ObjectUtils.isEmpty(accountByOwnerResponse.getResult().getValue())) {
                log.warn("[Stage 1/5] No token accounts found for wallet: {} - Stopping", wallet);
                return;
            }
            
            // Stage 2: Filter quote mint accounts
            currentStage = "filter_accounts";
            log.info("[Stage 2/5] Filtering quote mint accounts");
            List<String> accounts = accountByOwnerResponse.getResult().getValue().stream()
                    .filter(acc -> acc != null && acc.getAccount() != null 
                            && acc.getAccount().getData() != null
                            && acc.getAccount().getData().getParsed() != null
                            && acc.getAccount().getData().getParsed().getInfo() != null)
                    .filter(acc -> QUOTE_MINTS.contains(acc.getAccount().getData().getParsed().getInfo().getMint()))
                    .map(TokenAccountByOwnerResponse.Value::getPubkey)
                    .filter(Objects::nonNull)
                    .toList();
            
            if (accounts.isEmpty()) {
                log.warn("[Stage 2/5] No quote mint token accounts found for wallet: {} - Stopping", wallet);
                return;
            }
            
            log.info("[Stage 2/5] Found {} quote mint token accounts", accounts.size());
            
            // Stage 3: Fetch signatures
            currentStage = "fetch_signatures";
            log.info("[Stage 3/5] Fetching transaction signatures (last 120 days)");
            long limitUnix = CommonUtil.getDateBeforeDays(120);
            List<String> signatures = fetchSignatureForAllAccounts(accounts, limitUnix, wallet);
            
            if (signatures.isEmpty()) {
                log.warn("[Stage 3/5] No transaction signatures found for wallet: {} - Stopping", wallet);
                return;
            }

            // Stage 4: Fetch transactions
            currentStage = "fetch_transactions";
            log.info("[Stage 4/5] Fetching full transaction details");
            List<Transaction> transactions = fetchTransactions(signatures, wallet);
            if (transactions.isEmpty()) {
                log.warn("[Stage 4/5] No transactions fetched for wallet: {} - Stopping", wallet);
                return;
            }
            
            log.info("[Stage 4/5] Fetched {} transactions", transactions.size());
            
            // Stage 5: Normalize and calculate PnL
            currentStage = "normalize_and_calculate";
            log.info("[Stage 5/5] Normalizing transactions and calculating PnL metrics");
            normalizeTransaction.process(transactions, wallet);
            
            log.info("✓ Successfully completed all 5 stages for wallet: {}", wallet);
            
        } catch (WalletProcessingException e) {
            log.error("Wallet processing failed at stage '{}' for wallet {}: {}", 
                    currentStage, wallet, e.getMessage());
            // Don't re-throw - just log
        } catch (Exception e) {
            log.error("Unexpected error at stage '{}' for wallet {}: {}", 
                    currentStage, wallet, e.getMessage(), e);
            // Don't re-throw - just log
        }
    }
    
    private TokenAccountByOwnerResponse fetchTokenAccounts(String wallet) {
        try {
            TokenAccountByOwnerResponse response = solanaService.getTokenAccountsByOwner(wallet);
            if (response == null) {
                log.warn("Received null response from getTokenAccountsByOwner for wallet: {}", wallet);
            }
            return response;
        } catch (Exception e) {
            log.error("Failed to fetch token accounts for wallet {}: {}", wallet, e.getMessage(), e);
            return null; // Return null instead of throwing - let caller handle
        }
    }
    
    private List<Transaction> fetchTransactions(List<String> signatures, String wallet) {
        try {
            List<Transaction> transactions = solanaService.getTransactionBulk(signatures, wallet);
            return transactions != null ? transactions : List.of();
        } catch (Exception e) {
            log.error("Failed to fetch transactions for wallet {}: {}", wallet, e.getMessage(), e);
            return List.of(); // Return empty list instead of throwing
        }
    }

    public List<String> fetchSignatureForAllAccounts(List<String> accounts, long limitUnix, String wallet) {
        if (accounts == null || accounts.isEmpty()) {
            log.warn("No accounts provided for signature fetching");
            return List.of();
        }
        
        try {
            log.debug("Fetching signatures for {} accounts (limit: {})", accounts.size(), limitUnix);
            
            List<TransactionSignaturesResponse> signaturesObj = solanaService.getSignaturesBulk(accounts, wallet);

            if (signaturesObj == null || signaturesObj.isEmpty()) {
                log.warn("No signature responses received");
                return List.of();
            }

            List<String> signatures = signaturesObj.stream()
                    .filter(Objects::nonNull)
                    .map(TransactionSignaturesResponse::getResult)
                    .filter(Objects::nonNull)
                    .flatMap(Collection::stream)
                    .filter(Objects::nonNull)
                    .filter(r -> r.getBlockTime() > 0)
                    .filter(r -> r.getBlockTime() > limitUnix && r.getErr() == null)
                    .map(TransactionSignaturesResponse.Result::getSignature)
                    .filter(Objects::nonNull)
                    .distinct()
                    .toList();
            
            log.debug("Filtered to {} unique signatures", signatures.size());
            return signatures;
            
        } catch (Exception e) {
            log.error("Failed to fetch signatures for wallet {}: {}", wallet, e.getMessage(), e);
            return List.of(); // Return empty list instead of throwing
        }
    }
}
