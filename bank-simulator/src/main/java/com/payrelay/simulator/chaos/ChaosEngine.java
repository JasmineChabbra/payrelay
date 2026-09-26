package com.payrelay.simulator.chaos;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * Wraps every simulated operation with (1) injected chaos and (2) idempotency, the way a real
 * bank API behaves: the same Idempotency-Key always returns the original result, and the
 * operation behind it runs at most once.
 */
@Component
public class ChaosEngine {

    public static final String KYC = "KYC";
    public static final String RISK = "RISK";

    /**
     * What a simulated operation produced. {@code amountMoved} is set only for successful transfers.
     * {@code rejected} marks a business rejection, which some banks report with a 200 status.
     */
    public record SimResponse(HttpStatus status, Map<String, Object> body, double amountMoved, boolean rejected) {
        public SimResponse(HttpStatus status, Map<String, Object> body, double amountMoved) {
            this(status, body, amountMoved, status.is4xxClientError());
        }

        public static SimResponse ok(Map<String, Object> body) {
            return new SimResponse(HttpStatus.OK, body, 0);
        }

        public static SimResponse rejection(HttpStatus status, Map<String, Object> body) {
            return new SimResponse(status, body, 0, true);
        }
    }

    /** A response plus whether it was replayed from the idempotency store. */
    public record Outcome(SimResponse response, boolean replayed) {
    }

    private final List<String> targets;
    private final Map<String, ChaosConfig> configs = new ConcurrentHashMap<>();
    private final Map<String, TargetStats> stats = new ConcurrentHashMap<>();
    private final Map<String, SimResponse> idempotencyStore = new ConcurrentHashMap<>();

    public ChaosEngine(@Value("${simulator.banks}") List<String> banks) {
        List<String> all = new ArrayList<>(List.of(KYC, RISK));
        all.addAll(banks);
        this.targets = List.copyOf(all);
        reset();
    }

    public Outcome run(String target, String idempotencyKey, Supplier<SimResponse> operation) {
        TargetStats s = stats.get(target);
        s.requests.incrementAndGet();
        ChaosConfig c = configs.get(target);

        sleep(c.latencyMs());
        if (roll(c.failureRate())) {
            s.injectedFailures.incrementAndGet();
            throw new ChaosException(HttpStatus.SERVICE_UNAVAILABLE, target + " is temporarily unavailable (injected)");
        }

        // computeIfAbsent is atomic per key: two concurrent requests with the same key run the operation once.
        boolean[] ranNow = {false};
        SimResponse response = idempotencyStore.computeIfAbsent(target + ":" + idempotencyKey, k -> {
            ranNow[0] = true;
            SimResponse r = operation.get();
            s.uniqueOperations.incrementAndGet();
            if (r.rejected()) {
                s.businessRejections.incrementAndGet();
            }
            s.amountMoved.add(r.amountMoved());
            return r;
        });
        if (!ranNow[0]) {
            s.idempotentReplays.incrementAndGet();
        }

        if (roll(c.lostResponseRate())) {
            // The work above is done and stored, but the caller never hears about it.
            s.lostResponses.incrementAndGet();
            throw new ChaosException(HttpStatus.GATEWAY_TIMEOUT, target + " processed the request but the response was lost (injected)");
        }
        return new Outcome(response, !ranNow[0]);
    }

    // ---- configuration ----

    public List<String> targets() {
        return targets;
    }

    public boolean isTarget(String target) {
        return configs.containsKey(target);
    }

    public Map<String, ChaosConfig> configs() {
        Map<String, ChaosConfig> ordered = new LinkedHashMap<>();
        targets.forEach(t -> ordered.put(t, configs.get(t)));
        return ordered;
    }

    public void configure(String target, ChaosConfig config) {
        if (!isTarget(target)) {
            throw new IllegalArgumentException("Unknown target " + target);
        }
        configs.put(target, config);
    }

    public void applyPreset(String preset) {
        ChaosConfig config = switch (preset.toLowerCase(Locale.ROOT)) {
            case "calm" -> ChaosConfig.CALM;
            case "flaky" -> new ChaosConfig(0.25, 0.10, 150);
            case "slow" -> new ChaosConfig(0.05, 0.0, 2500);
            case "meltdown" -> new ChaosConfig(0.6, 0.2, 400);
            default -> throw new IllegalArgumentException("Unknown preset " + preset + ". Use calm, flaky, slow or meltdown.");
        };
        targets.forEach(t -> configs.put(t, config));
    }

    public void reset() {
        targets.forEach(t -> {
            configs.put(t, ChaosConfig.CALM);
            stats.put(t, new TargetStats());
        });
        idempotencyStore.clear();
    }

    public Map<String, TargetStats.Snapshot> stats() {
        Map<String, TargetStats.Snapshot> ordered = new LinkedHashMap<>();
        targets.forEach(t -> ordered.put(t, stats.get(t).snapshot()));
        return ordered;
    }

    private static boolean roll(double probability) {
        return probability > 0 && ThreadLocalRandom.current().nextDouble() < probability;
    }

    private static void sleep(int latencyMs) {
        if (latencyMs <= 0) {
            return;
        }
        // ±20% jitter so latency looks like a real network, not a metronome.
        long jittered = Math.round(latencyMs * (0.8 + 0.4 * ThreadLocalRandom.current().nextDouble()));
        try {
            Thread.sleep(jittered);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
