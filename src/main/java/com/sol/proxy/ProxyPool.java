package com.sol.proxy;

import org.springframework.stereotype.Component;

import com.sol.bean.ProxyIdentity;
import com.sol.bean.ProxyPoolProperties;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.springframework.web.reactive.function.client.WebClient;

@Component
public class ProxyPool {

    private final List<WebClient> clients;
    private final AtomicInteger globalCursor = new AtomicInteger(0);
    private final Map<String, AtomicInteger> cursorByBase = new ConcurrentHashMap<>();

    public ProxyPool(ProxyPoolProperties props) {
        List<WebClient> tmp = new ArrayList<>();
        for (int i = 0; i < props.getCount(); i++) {
            String user = props.getUsernamePrefix() + (props.getStartIndex() + i);
            ProxyIdentity id = new ProxyIdentity(user, props.getPassword(), props.getHost(), props.getPort());
            tmp.add(ProxyWebClientFactory.build(id, props));
        }
        this.clients = List.copyOf(tmp);
    }

    // next client for single-shot; rotates globally
    public WebClient next() {
        int idx = Math.floorMod(globalCursor.getAndIncrement(), clients.size());
        return clients.get(idx);
    }

    // base URL–scoped rotation (keeps independent “lanes” per base URL)
    public WebClient next(String baseUrl) {
        AtomicInteger c = cursorByBase.computeIfAbsent(norm(baseUrl), k -> new AtomicInteger(0));
        int idx = Math.floorMod(c.getAndIncrement(), clients.size());
        return clients.get(idx);
    }

    // get N distinct clients for a batch (base URL–scoped)
    public List<WebClient> slice(String baseUrl, int size) {
        AtomicInteger c = cursorByBase.computeIfAbsent(norm(baseUrl), k -> new AtomicInteger(0));
        int start = c.getAndAccumulate(size, (cur, add) -> cur + add);
        return IntStream.range(0, size)
                .mapToObj(i -> clients.get(Math.floorMod(start + i, clients.size())))
                .toList();
    }

    private String norm(String baseUrl) {
        if (baseUrl == null) return "_";
        return baseUrl.replaceAll("/+$", "").toLowerCase(Locale.ROOT);
    }

    public int size() { return clients.size(); }
}

