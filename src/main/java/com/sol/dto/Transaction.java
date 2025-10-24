package com.sol.dto;

import java.math.BigDecimal;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Transaction {
    private String jsonrpc;
    private Result result;
    private Object id; // "1" or numeric; keep flexible

    /* ------------------------------ Result ------------------------------ */

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Result {
        private Long blockTime;
        private Meta meta;
        private Long slot;
        private Tx transaction;
        private String version; // e.g., "legacy"
    }

    /* ------------------------------ Meta ------------------------------ */

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Meta {
        private Long computeUnitsConsumed;
        private Object err; // null or object
        private Long fee;
        private List<InnerInstruction> innerInstructions;
        private LoadedAddresses loadedAddresses;
        private List<String> logMessages;
        private List<Long> postBalances;
        private List<TokenBalance> postTokenBalances;
        private List<Long> preBalances;
        private List<TokenBalance> preTokenBalances;
        private List<Object> rewards;
        private Status status;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class LoadedAddresses {
        private List<String> readonly;
        private List<String> writable;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class InnerInstruction {
        private Integer index;
        private List<CompiledInstruction> instructions;
    }

    /* ------------------------------ Transaction ------------------------------ */

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Tx {
        private Message message;
        private List<String> signatures;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Message {
        private List<String> accountKeys;
        private Header header;
        private List<CompiledInstruction> instructions;
        private String recentBlockhash;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Header {
        @JsonProperty("numReadonlySignedAccounts")
        private Integer numReadonlySignedAccounts;

        @JsonProperty("numReadonlyUnsignedAccounts")
        private Integer numReadonlyUnsignedAccounts;

        @JsonProperty("numRequiredSignatures")
        private Integer numRequiredSignatures;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class CompiledInstruction {
        private List<Integer> accounts;
        private String data;
        private Integer programIdIndex;
        private Integer stackHeight; // may be null
    }

    /* ------------------------------ Token Balance ------------------------------ */

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class TokenBalance {
        private Integer accountIndex;
        private String mint;
        private String owner;
        private UiTokenAmount uiTokenAmount;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class UiTokenAmount {
        private String amount;
        private Integer decimals;
        private BigDecimal uiAmount; // may be null
        private String uiAmountString;
    }

    /* ------------------------------ Status ------------------------------ */

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Status {
        // For "status": {"Ok": null} or {"Err": {...}}
        @JsonProperty("Ok")
        private Object ok;

        @JsonProperty("Err")
        private Object err;
    }
}
