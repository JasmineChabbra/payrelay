package com.payrelay.api.idempotency;

import java.time.Duration;

/**
 * A short-lived lock per idempotency key. It stops two identical requests that arrive at the same
 * moment from both creating a payment. The database's unique constraint on the key is the final
 * safety net; this lock just makes the common race cheap and gives the caller a clear 409.
 */
public interface IdempotencyLock {

    /** @return true if this caller now holds the lock for the key. */
    boolean tryAcquire(String key, Duration ttl);

    void release(String key);
}
