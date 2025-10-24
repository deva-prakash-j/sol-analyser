package com.sol.service;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class StartupService {

    private final WalletService walletService;

    @EventListener(ApplicationReadyEvent.class)
    public void startup() {
        List<String> wallets = List.of("2e7jTCKTakatd1f3zabgm9aDujRgoyxbnG2VtZ9yjy3c",
                "DULT2ijn8FaUagyqTCbuidDiADUxXK9aJV1Ghi6EfhE5");
        walletService.processWallet(wallets.get(1));
    }
}
