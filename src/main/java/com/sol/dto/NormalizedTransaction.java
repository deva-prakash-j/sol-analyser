package com.sol.dto;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class NormalizedTransaction {
    private String signature;
    private Long blockTime;
    private Long blockSlot;
    private String baseMint;
    private BigDecimal baseAmount;
    private String side;
}
