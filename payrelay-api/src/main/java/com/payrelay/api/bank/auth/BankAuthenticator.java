package com.payrelay.api.bank.auth;

import com.payrelay.api.bank.BankSpec;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.*;

/**
 * Adds a bank's authentication to a request, for each supported scheme.
 *
 * <p>HMAC signing: the signature covers {@code timestamp \n method \n path \n sha256(body)}. Because the
 * body hash is included, a tampered body fails verification; because the timestamp is included (and the
 * bank rejects old timestamps), a captured request can't be replayed later.
 */
@Component
public class BankAuthenticator {

    /** Headers to add, plus values that must be hidden in stored snapshots. */
    public record AuthResult(Map<String, String> headers, List<String> secretsToHide, String bearerToken) {
    }

    private final SecretResolver secrets;
    private final OAuthTokenService tokens;
    private final Clock clock;

    public BankAuthenticator(SecretResolver secrets, OAuthTokenService tokens, Clock clock) {
        this.secrets = secrets;
        this.tokens = tokens;
        this.clock = clock;
    }

    public AuthResult authenticate(String bankCode, BankSpec.Auth auth, String path, String bodyJson, String correlationId) {
        return switch (auth.type()) {
            case API_KEY -> {
                String key = secret(auth);
                String prefix = auth.prefix() == null ? "" : auth.prefix();
                yield new AuthResult(Map.of(auth.header(), prefix + key), List.of(key), null);
            }
            case OAUTH2_CLIENT_CREDENTIALS -> {
                String token = tokens.token(bankCode, auth, correlationId);
                yield new AuthResult(Map.of("Authorization", "Bearer " + token), List.of(token), token);
            }
            case HMAC_SHA256 -> {
                String secret = secret(auth);
                String timestamp = String.valueOf(clock.instant().getEpochSecond());
                Map<String, String> headers = new LinkedHashMap<>();
                headers.put(auth.timestampHeader(), timestamp);
                headers.put(auth.signatureHeader(), sign(secret, timestamp, "POST", path, bodyJson));
                if (auth.keyIdHeader() != null && !auth.keyIdHeader().isBlank()) {
                    headers.put(auth.keyIdHeader(), auth.keyId());
                }
                yield new AuthResult(headers, List.of(secret), null);
            }
        };
    }

    public static String canonical(String timestamp, String method, String path, String body) {
        return timestamp + "\n" + method + "\n" + path + "\n" + sha256Hex(body);
    }

    public static String sign(String secret, String timestamp, String method, String path, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(canonical(timestamp, method, path, body).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    static String sha256Hex(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private String secret(BankSpec.Auth auth) {
        return secrets.resolve(auth.secretRef())
                .orElseThrow(() -> new BankAuthException(false, "Secret '" + auth.secretRef() + "' is not configured"));
    }
}
