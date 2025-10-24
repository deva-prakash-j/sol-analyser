package com.sol.service;

import com.sol.cache.annotation.Cached;
import com.sol.entity.Price;
import com.sol.repository.PriceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class PriceService {

    private final PriceRepository priceRepository;

    private Map<String, List<Price>> priceMap = new HashMap<>();
    private Map<String, LocalDateTime> refreshMap = new HashMap<>();
    
    private static final int CACHE_DURATION_MINUTES = 60;

    private List<Price> fetchPrice(String address) {
        if (address == null || address.isBlank()) {
            log.warn("Attempted to fetch price for null or empty address");
            return List.of();
        }
        
        try {
            if (refreshMap.containsKey(address) 
                    && Duration.between(refreshMap.get(address), LocalDateTime.now()).toMinutes() < CACHE_DURATION_MINUTES) {
                log.debug("Returning cached price data for address: {}", address);
                return priceMap.get(address);
            }
            
            log.debug("Fetching price data from repository for address: {}", address);
            List<Price> prices = priceRepository.findAllByAddressOrderByHourAsc(address);
            
            if (prices == null) {
                prices = List.of();
            }
            
            priceMap.put(address, prices);
            refreshMap.put(address, LocalDateTime.now());
            
            log.debug("Cached {} price points for address: {}", prices.size(), address);
            return prices;
            
        } catch (Exception e) {
            log.error("Failed to fetch price data for address {}: {}", address, e.getMessage(), e);
            // Return cached data if available, otherwise empty list
            return priceMap.getOrDefault(address, List.of());
        }
    }

    public BigDecimal getPrice(String address, Long blockTime) {
        if (address == null || address.isBlank()) {
            log.warn("Attempted to get price for null or empty address");
            return null;
        }
        
        if (blockTime == null) {
            log.warn("Attempted to get price for address {} with null blockTime", address);
            return null;
        }
        
        try {
            var series = fetchPrice(address);
            
            if (series.isEmpty()) {
                log.warn("No price data available for address: {}", address);
                return null;
            }

            Instant ts = Instant.ofEpochSecond(blockTime);
            // convert to UTC LocalDateTime because your entity uses LocalDateTime
            LocalDateTime target = LocalDateTime.ofInstant(ts, ZoneOffset.UTC);

            int idx = binarySearchByHour(series, target);
            if (idx >= 0) {
                BigDecimal price = series.get(idx).getPrice();
                log.debug("Found exact price match for address {} at {}: {}", address, target, price);
                return price;
            } else {
                // insertion point
                int ip = -idx - 1;
                BigDecimal price;
                
                if (ip == 0) {
                    price = series.get(0).getPrice();
                    log.debug("Using first available price for address {} (before first datapoint): {}", address, price);
                } else if (ip >= series.size()) {
                    price = series.get(series.size() - 1).getPrice();
                    log.debug("Using last available price for address {} (after last datapoint): {}", address, price);
                } else {
                    // choose closer of neighbors; tie -> lower
                    var lower = series.get(ip - 1);
                    var upper = series.get(ip);

                    long dl = Math.abs(Duration.between(
                            lower.getHour().atOffset(ZoneOffset.UTC), target.atOffset(ZoneOffset.UTC)).toSeconds());
                    long du = Math.abs(Duration.between(
                            upper.getHour().atOffset(ZoneOffset.UTC), target.atOffset(ZoneOffset.UTC)).toSeconds());

                    price = (dl <= du) ? lower.getPrice() : upper.getPrice();
                    log.debug("Interpolated price for address {} at {}: {} (distance lower={}, upper={})", 
                            address, target, price, dl, du);
                }
                
                return price;
            }
        } catch (Exception e) {
            log.error("Failed to get price for address {} at blockTime {}: {}", 
                    address, blockTime, e.getMessage(), e);
            return null;
        }
    }

    private static int binarySearchByHour(List<Price> series, LocalDateTime targetUtc) {
        int lo = 0, hi = series.size() - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            LocalDateTime midUtc = series.get(mid).getHour(); // stored as LocalDateTime
            int cmp = midUtc.compareTo(targetUtc);
            if (cmp < 0) lo = mid + 1;
            else if (cmp > 0) hi = mid - 1;
            else return mid;
        }
        return -(lo + 1);
    }
}
