package com.sol.repository;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.sol.entity.Proxies;
import java.util.List;

@Repository
public interface ProxyRepository extends JpaRepository<Proxies, Long> {
    
    @Query("SELECT p.session FROM Proxies p ORDER BY p.id")
    List<String> findSessionsWithLimit(Pageable pageable);
    
    @Query("SELECT p.session FROM Proxies p WHERE p.id > :lastId ORDER BY p.id")
    List<String> findSessionsAfterIdWithLimit(@Param("lastId") Long lastId, Pageable pageable);
    
}
