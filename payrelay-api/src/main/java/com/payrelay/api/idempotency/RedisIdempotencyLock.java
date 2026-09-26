package com.payrelay.api.idempotency;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** Redis SET NX with a TTL: atomic, shared across all API instances, and self-expiring if an instance dies. */
@Component
@ConditionalOnProperty(name = "payrelay.idempotency.store", havingValue = "redis", matchIfMissing = true)
public class RedisIdempotencyLock implements IdempotencyLock {

    private static final String PREFIX = "payrelay:idem:";

    private final StringRedisTemplate redis;

    public RedisIdempotencyLock(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public boolean tryAcquire(String key, Duration ttl) {
        Boolean acquired = redis.opsForValue().setIfAbsent(PREFIX + key, "locked", ttl);
        return Boolean.TRUE.equals(acquired);
    }

    @Override
    public void release(String key) {
        redis.delete(PREFIX + key);
    }
}
