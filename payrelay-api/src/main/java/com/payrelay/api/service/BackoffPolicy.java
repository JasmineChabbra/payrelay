package com.payrelay.api.service;

import com.payrelay.api.config.PayRelayProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;

/**
 * Exponential backoff with jitter: 1s, 2s, 4s, 8s... capped at max-delay, each randomised to between
 * 50% and 100% of that value. Jitter stops a burst of payments that failed together from all retrying
 * at the same instant and hammering a bank that is just recovering.
 */
@Component
public class BackoffPolicy {

    private final Duration baseDelay;
    private final Duration maxDelay;
    private final DoubleSupplier random;

    @Autowired
    public BackoffPolicy(PayRelayProperties props) {
        this(props.retry().baseDelay(), props.retry().maxDelay(), () -> ThreadLocalRandom.current().nextDouble());
    }

    BackoffPolicy(Duration baseDelay, Duration maxDelay, DoubleSupplier random) {
        this.baseDelay = baseDelay;
        this.maxDelay = maxDelay;
        this.random = random;
    }

    /** @param attempt the attempt that just failed, starting at 1 */
    public Duration delayAfter(int attempt) {
        int exponent = Math.min(Math.max(attempt - 1, 0), 30);
        long uncapped = baseDelay.toMillis() * (1L << exponent);
        long capped = Math.min(uncapped, maxDelay.toMillis());
        long jittered = (long) (capped * (0.5 + 0.5 * random.getAsDouble()));
        return Duration.ofMillis(jittered);
    }
}
