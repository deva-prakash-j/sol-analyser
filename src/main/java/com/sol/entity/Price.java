package com.sol.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "token_prices_hourly")
@Data
public class Price {

    @Id
    @Column(name = "contract_address", columnDefinition = "text")
    private String address;
    private LocalDateTime hour;
    @Column(name = "price", columnDefinition = "double")
    private BigDecimal price;
}
