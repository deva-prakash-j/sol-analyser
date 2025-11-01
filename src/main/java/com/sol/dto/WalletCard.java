package com.sol.dto;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import lombok.Data;

@Data
public class WalletCard {

    private String walletAddress;
    private List<PnL> sevenDaysPnL = new ArrayList<>(7);
    
    @Data
    public static class PnL {
        private BigDecimal pnlUsd;
        private BigDecimal pnlSol;
    }
}
