package com.sol.repository;

import com.sol.entity.Price;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PriceRepository extends JpaRepository<Price, String> {

    public List<Price> findAllByAddressOrderByHourAsc(String address);
}
