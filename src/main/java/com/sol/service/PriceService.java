package com.sol.service;

import com.sol.cache.annotation.Cached;
import com.sol.entity.Price;
import com.sol.repository.PriceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PriceService {

    private final PriceRepository priceRepository;

    private Map<String, List<Price>> priceMap = new HashMap<>();
    private Map<String, LocalDateTime> refreshMap = new HashMap<>();

    private List<Price> fetchPrice(String address) {
        if(refreshMap.containsKey(address) && Duration.between(refreshMap.get(address), LocalDateTime.now()).toMinutes() < 60) {
            return priceMap.get(address);
        }
        List<Price> price = priceRepository.findAllByAddressOrderByHourAsc(address);
        priceMap.put(address, price);
        refreshMap.put(address, LocalDateTime.now());
       return price;
    }

    public BigDecimal getPrice(String address, Long blockTime) {
        var series = fetchPrice(address);
        if (series.isEmpty() || blockTime == null) return null;

        Instant ts = Instant.ofEpochSecond(blockTime);
        // convert to UTC LocalDateTime because your entity uses LocalDateTime
        LocalDateTime target = LocalDateTime.ofInstant(ts, ZoneOffset.UTC);

        int idx = binarySearchByHour(series, target);
        if (idx >= 0) {
            return series.get(idx).getPrice(); // exact match
        } else {
            // insertion point
            int ip = -idx - 1;
            if (ip == 0) {
                return series.getFirst().getPrice(); // before first
            } else if (ip >= series.size()) {
                return series.getLast().getPrice();  // after last
            } else {
                // choose closer of neighbors; tie -> lower
                var lower = series.get(ip - 1);
                var upper = series.get(ip);

                long dl = Math.abs(Duration.between(
                        lower.getHour().atOffset(ZoneOffset.UTC), target.atOffset(ZoneOffset.UTC)).toSeconds());
                long du = Math.abs(Duration.between(
                        upper.getHour().atOffset(ZoneOffset.UTC), target.atOffset(ZoneOffset.UTC)).toSeconds());

                return (dl <= du) ? lower.getPrice() : upper.getPrice();
            }
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
