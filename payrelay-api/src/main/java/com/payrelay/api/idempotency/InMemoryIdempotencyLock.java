package com.payrelay.api.idempotency;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/** Single-instance stand-in for Redis, used by the "dev" profile and in tests. */
@Component
@ConditionalOnProperty(name = "payrelay.idempotency.store", havingValue = "memory")
public class InMemoryIdempotencyLock implements IdempotencyLock {

    private final ConcurrentHashMap<String, Instant> locks = new ConcurrentHashMap<>();
    private final Clock clock;

    public InMemoryIdempotencyLock(Clock clock) {
        this.clock = clock;
    }

    @Override
    public boolean tryAcquire(String key, Duration ttl) {
        Instant now = clock.instant();
        Instant expiry = now.plus(ttl);
        // compute() is atomic per key: take the lock if it's free or expired.
        Instant result = locks.compute(key, (k, existing) ->
                existing == null || existing.isBefore(now) ? expiry : existing);
        return result == expiry;
    }

    @Override
    public void release(String key) {
        locks.remove(key);
    }
}
