package com.sol.dto;

import lombok.Data;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class TransactionSignaturesResponse {
    private List<Result> result;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Result {
        private long blockTime;
        private String confirmationStatus;
        private Object err;
        private String memo;
        private String signature;
        private long slot;
    }
}
