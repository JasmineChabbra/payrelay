package com.payrelay.simulator.api;

import com.payrelay.simulator.chaos.ChaosConfig;
import com.payrelay.simulator.chaos.ChaosEngine;
import com.payrelay.simulator.chaos.TargetStats;
import com.payrelay.simulator.bank.ZenithTokens;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** Control panel for the simulator. Open (no API key) so the console can drive it. */
@RestController
@RequestMapping("/chaos")
public class ChaosController {

    private final ChaosEngine chaos;
    private final ZenithTokens zenithTokens;

    public ChaosController(ChaosEngine chaos, ZenithTokens zenithTokens) {
        this.chaos = chaos;
        this.zenithTokens = zenithTokens;
    }

    @GetMapping
    public Map<String, ChaosConfig> get() {
        return chaos.configs();
    }

    @PutMapping("/{target}")
    public Map<String, ChaosConfig> set(@PathVariable String target, @Valid @RequestBody ChaosConfig config) {
        chaos.configure(target, config);
        return chaos.configs();
    }

    @PostMapping("/presets/{preset}")
    public Map<String, ChaosConfig> preset(@PathVariable String preset) {
        chaos.applyPreset(preset);
        return chaos.configs();
    }

    /** Resets chaos to calm and clears all counters and stored idempotent responses. */
    @PostMapping("/reset")
    public Map<String, ChaosConfig> reset() {
        chaos.reset();
        zenithTokens.reset();
        return chaos.configs();
    }

    /** Revokes every Zenith access token, as if the bank rotated its keys. PayRelay should refresh transparently. */
    @PostMapping("/banks/zenith/revoke-tokens")
    public Map<String, Object> revokeZenithTokens() {
        return Map.of("revoked", zenithTokens.revokeAll());
    }

    @GetMapping("/banks/zenith/tokens")
    public Map<String, Long> zenithTokenStats() {
        return zenithTokens.stats();
    }

    @GetMapping("/stats")
    public Map<String, TargetStats.Snapshot> stats() {
        return chaos.stats();
    }
}
