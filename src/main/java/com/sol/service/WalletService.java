package com.sol.service;

import com.sol.dto.TokenAccountByOwnerResponse;
import com.sol.dto.Transaction;
import com.sol.dto.TransactionSignaturesResponse;
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

    public void processWallet(String wallet) {
        long limitUnix = CommonUtil.getDateBeforeDays(120);
        TokenAccountByOwnerResponse accountByOwnerResponse = solanaService.getTokenAccountsByOwner(wallet);
        if (accountByOwnerResponse != null && accountByOwnerResponse.getResult() != null
                && !ObjectUtils.isEmpty(accountByOwnerResponse.getResult().getValue())) {
            List<String> accounts = accountByOwnerResponse.getResult().getValue().stream()
                    .filter(acc -> QUOTE_MINTS.contains(acc.getAccount().getData().getParsed().getInfo().getMint()))
                    .map(TokenAccountByOwnerResponse.Value::getPubkey).toList();
            log.info("Found {} token accounts for wallet {}", accounts.size(), wallet);
            List<String> signatures = fetchSignatureForAllAccounts(accounts, limitUnix, wallet).stream().distinct().toList();
            log.info("Fetched {} signatures for wallet: {}", signatures.size(), wallet);
            List<Transaction> transactions = solanaService.getTransactionBulk(signatures, wallet);
            log.info("Fetched {} transactions for wallet: {}", transactions.size(), wallet);
            normalizeTransaction.process(transactions, wallet);
        } else {
            log.warn("No Account Found: {}", accountByOwnerResponse);
        }
    }

    public List<String> fetchSignatureForAllAccounts(List<String> accounts, long limitUnix, String wallet) {
        List<TransactionSignaturesResponse> signaturesObj = solanaService.getSignaturesBulk(accounts, wallet);

        return signaturesObj.stream()
                .map(TransactionSignaturesResponse::getResult)
                .filter(Objects::nonNull)
                .flatMap(Collection::stream)
                .filter(r -> r.getBlockTime() > limitUnix && r.getErr() == null)
                .map(TransactionSignaturesResponse.Result::getSignature)
                .filter(Objects::nonNull)
                .toList();
    }
}
