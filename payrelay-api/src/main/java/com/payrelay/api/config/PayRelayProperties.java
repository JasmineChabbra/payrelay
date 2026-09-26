package com.payrelay.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "payrelay")
public record PayRelayProperties(
        Simulator simulator,
        Idempotency idempotency,
        Retry retry,
        Worker worker) {

    public record Simulator(String baseUrl, String apiKey, Duration connectTimeout, Duration readTimeout) {
    }

    public record Idempotency(String store, Duration lockTtl) {
    }

    public record Retry(int maxAttemptsPerStep, Duration baseDelay, Duration maxDelay) {
    }

    public record Worker(long pollIntervalMs, int batchSize, Duration lease, int threads) {
    }
}
