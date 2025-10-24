package com.sol.dto;


import lombok.Data;

import java.math.BigInteger;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class TokenAccountByOwnerResponse {
    private String jsonrpc;
    private int id;
    private Result result;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Result {
        private Context context;
        private List<Value> value;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Context {
        private String apiVersion;
        private long slot;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Value {
        private String pubkey;
        private Account account;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Account {
        private AccountData data;
        private boolean executable;
        private BigInteger lamports;
        private String owner;
        private BigInteger rentEpoch;
        private int space;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AccountData {
        private String program;
        private Parsed parsed;
        private int space;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Parsed {
        private Info info;
        private String type;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Info {
        private boolean isNative;
        private String mint;
        private String owner;
        private String state;
        private TokenAmount tokenAmount;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TokenAmount {
        private String amount;
        private int decimals;
        private Double uiAmount;
        private String uiAmountString;
    }
}

