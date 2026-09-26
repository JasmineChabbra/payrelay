package com.payrelay.api.bank.auth;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Resolves a spec's {@code secretRef} to its value from configuration ({@code payrelay.secrets.<ref>},
 * normally set from environment variables). Secret values never enter the database or the API.
 * Swapping this for AWS Secrets Manager or Vault would only touch this class.
 */
@Component
public class SecretResolver {

    private final Environment env;

    public SecretResolver(Environment env) {
        this.env = env;
    }

    public Optional<String> resolve(String ref) {
        if (ref == null || ref.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(env.getProperty("payrelay.secrets." + ref)).filter(s -> !s.isBlank());
    }
}
