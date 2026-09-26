package com.payrelay.simulator.chaos;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * How badly a simulated system should behave.
 *
 * @param failureRate      chance a request fails with 503 before doing anything
 * @param lostResponseRate chance the work is done but the response is "lost" (504). The nastiest case:
 *                         the caller can't tell whether it happened, so only idempotency keeps it safe.
 * @param latencyMs        added delay per request. Above the caller's read timeout (3s) this becomes a timeout.
 */
public record ChaosConfig(
        @DecimalMin("0.0") @DecimalMax("1.0") double failureRate,
        @DecimalMin("0.0") @DecimalMax("1.0") double lostResponseRate,
        @Min(0) @Max(10_000) int latencyMs) {

    public static final ChaosConfig CALM = new ChaosConfig(0, 0, 40);
}
