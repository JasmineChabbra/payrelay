package com.payrelay.simulator.api;

import com.payrelay.simulator.chaos.ChaosEngine;
import com.payrelay.simulator.chaos.ChaosEngine.Outcome;
import com.payrelay.simulator.chaos.ChaosEngine.SimResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/**
 * The fake KYC registry and risk engine (banks live in {@code bank/}). Business rules use documented "magic values" so demos can trigger
 * rejections on purpose:
 * <ul>
 *   <li>KYC: a PAN starting with {@code ZZZ} is rejected.</li>
 *   <li>Risk: a score is derived from the customer ID; low scores, or low-ish scores on big amounts, are declined.</li>
 * </ul>
 */
@RestController
public class SimulatedSystemsController {

    private final ChaosEngine chaos;

    public SimulatedSystemsController(ChaosEngine chaos) {
        this.chaos = chaos;
    }

    @PostMapping("/kyc/verify")
    public ResponseEntity<Map<String, Object>> verifyKyc(@RequestHeader("Idempotency-Key") String key,
                                                         @RequestBody Map<String, Object> body) {
        return respond(chaos.run(ChaosEngine.KYC, key, () -> {
            String pan = String.valueOf(body.get("pan"));
            if (pan.startsWith("ZZZ")) {
                return SimResponse.ok(Map.of("status", "REJECTED", "reason", "PAN not found in registry"));
            }
            return SimResponse.ok(Map.of("status", "VERIFIED", "kycReference", "KYC-" + shortId()));
        }));
    }

    @PostMapping("/risk/score")
    public ResponseEntity<Map<String, Object>> scoreRisk(@RequestHeader("Idempotency-Key") String key,
                                                         @RequestBody Map<String, Object> body) {
        return respond(chaos.run(ChaosEngine.RISK, key, () -> {
            String customerId = String.valueOf(body.get("customerId"));
            double amount = new BigDecimal(String.valueOf(body.get("amount"))).doubleValue();
            int score = 300 + Math.floorMod(customerId.hashCode(), 601); // 300..900, stable per customer
            boolean decline = score < 330 || (amount > 700_000 && score < 450);
            return SimResponse.ok(Map.of("score", score, "decision", decline ? "DECLINE" : "APPROVE"));
        }));
    }

    public static ResponseEntity<Map<String, Object>> respond(Outcome outcome) {
        return ResponseEntity.status(outcome.response().status())
                .header("Idempotent-Replayed", String.valueOf(outcome.replayed()))
                .body(outcome.response().body());
    }

    public static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }
}
