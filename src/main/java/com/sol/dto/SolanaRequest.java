package com.sol.dto;

import java.util.ArrayList;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

@AllArgsConstructor
@Builder
@Data
public class SolanaRequest {

    @Builder.Default
    private String jsonrpc = "2.0";

    @Builder.Default
    private Integer id = 1;

    private String method;

    @Builder.Default
    private List<Object> params = new ArrayList<>(5);
}
