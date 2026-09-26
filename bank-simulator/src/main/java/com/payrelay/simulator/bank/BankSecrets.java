package com.payrelay.simulator.bank;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Credentials each simulated bank expects from its client (PayRelay). */
@ConfigurationProperties(prefix = "simulator.bank-secrets")
public record BankSecrets(
        String novaApiKey,
        String zenithClientId,
        String zenithClientSecret,
        long zenithTokenTtlSeconds,
        String orbitKeyId,
        String orbitHmacSecret,
        String kestrelApiKey) {
}
