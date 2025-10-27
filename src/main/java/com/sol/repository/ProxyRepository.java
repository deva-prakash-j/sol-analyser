package com.sol.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.sol.entity.Proxies;

@Repository
public interface ProxyRepository extends JpaRepository<Proxies, Long> {
    
}
