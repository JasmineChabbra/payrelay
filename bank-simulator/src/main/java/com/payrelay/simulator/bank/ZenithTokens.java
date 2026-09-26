package com.payrelay.simulator.bank;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Zenith's OAuth2 token store. Tokens can be revoked on demand to simulate early expiry or key rotation. */
@Component
public class ZenithTokens {

    private final Map<String, Instant> tokens = new ConcurrentHashMap<>();
    private final AtomicLong issued = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();
    private final Clock clock = Clock.systemUTC();

    public String issue(long ttlSeconds) {
        String token = "zt_" + UUID.randomUUID().toString().replace("-", "");
        tokens.put(token, clock.instant().plusSeconds(ttlSeconds));
        issued.incrementAndGet();
        return token;
    }

    public boolean isValid(String token) {
        Instant expiry = token == null ? null : tokens.get(token);
        boolean ok = expiry != null && expiry.isAfter(clock.instant());
        if (!ok) {
            rejected.incrementAndGet();
        }
        return ok;
    }

    public int revokeAll() {
        int n = tokens.size();
        tokens.clear();
        return n;
    }

    public Map<String, Long> stats() {
        return Map.of("issued", issued.get(), "rejected", rejected.get(), "active", (long) tokens.size());
    }

    public void reset() {
        tokens.clear();
        issued.set(0);
        rejected.set(0);
    }
}
