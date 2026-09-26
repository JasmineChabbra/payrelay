package com.payrelay.api.bank.auth;

import com.payrelay.api.bank.BankSpec;
import com.payrelay.api.client.ExternalCallResult;
import com.payrelay.api.client.ExternalSystemClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * OAuth2 client-credentials tokens, cached per bank.
 *
 * <ul>
 *   <li>Tokens are reused until 30 seconds before they expire, so a token never expires mid-request.</li>
 *   <li>Only one thread fetches a token for a given bank at a time; the others wait and reuse it,
 *       instead of 16 workers stampeding the token endpoint together.</li>
 *   <li>When a bank rejects a token early (revoked, rotated), {@link #invalidate} drops exactly that
 *       token, and the next call fetches a fresh one.</li>
 * </ul>
 */
@Component
public class OAuthTokenService {

    private static final Logger log = LoggerFactory.getLogger(OAuthTokenService.class);
    private static final Duration EXPIRY_SKEW = Duration.ofSeconds(30);
    private static final long DEFAULT_TTL_SECONDS = 300;

    record CachedToken(String value, Instant expiresAt) {
    }

    /** Fetches a token from the bank's token endpoint. Swappable in tests. */
    interface TokenFetcher {
        CachedToken fetch(BankSpec.Auth auth, String secret, String correlationId);
    }

    private final Map<String, CachedToken> cache = new ConcurrentHashMap<>();
    private final Map<String, Object> locks = new ConcurrentHashMap<>();
    private final SecretResolver secrets;
    private final TokenFetcher fetcher;
    private final Clock clock;

    @Autowired
    public OAuthTokenService(SecretResolver secrets, ExternalSystemClient client, Clock clock) {
        this(secrets, httpFetcher(client, clock), clock);
    }

    OAuthTokenService(SecretResolver secrets, TokenFetcher fetcher, Clock clock) {
        this.secrets = secrets;
        this.fetcher = fetcher;
        this.clock = clock;
    }

    public String token(String bankCode, BankSpec.Auth auth, String correlationId) {
        String key = key(bankCode, auth);
        CachedToken cached = cache.get(key);
        if (isFresh(cached)) {
            return cached.value();
        }
        synchronized (locks.computeIfAbsent(key, k -> new Object())) {
            cached = cache.get(key);
            if (isFresh(cached)) {
                return cached.value(); // another thread refreshed it while we waited
            }
            String secret = secrets.resolve(auth.secretRef())
                    .orElseThrow(() -> new BankAuthException(false, "Secret '" + auth.secretRef() + "' is not configured"));
            CachedToken fresh = fetcher.fetch(auth, secret, correlationId);
            cache.put(key, fresh);
            log.info("bank={} fetched new access token, expires {}", bankCode, fresh.expiresAt());
            return fresh.value();
        }
    }

    /** Drops the token only if it's still the one the bank rejected, so a newer token isn't thrown away. */
    public void invalidate(String bankCode, BankSpec.Auth auth, String rejectedToken) {
        cache.computeIfPresent(key(bankCode, auth), (k, v) -> v.value().equals(rejectedToken) ? null : v);
    }

    private boolean isFresh(CachedToken t) {
        return t != null && t.expiresAt().minus(EXPIRY_SKEW).isAfter(clock.instant());
    }

    private static String key(String bankCode, BankSpec.Auth auth) {
        return bankCode + "|" + auth.tokenPath() + "|" + auth.clientId() + "|" + auth.scope();
    }

    private static TokenFetcher httpFetcher(ExternalSystemClient client, Clock clock) {
        return (auth, secret, cid) -> {
            Map<String, String> form = new LinkedHashMap<>();
            form.put("grant_type", "client_credentials");
            form.put("client_id", auth.clientId());
            form.put("client_secret", secret);
            if (auth.scope() != null && !auth.scope().isBlank()) {
                form.put("scope", auth.scope());
            }
            ExternalCallResult res = client.postForm(auth.tokenPath(), form, cid, List.of(secret));
            switch (res.kind()) {
                case TRANSIENT_ERROR -> throw new BankAuthException(true, "Token endpoint unavailable: " + describe(res));
                case CLIENT_ERROR -> throw new BankAuthException(false, "Token request rejected: " + describe(res));
                default -> { }
            }
            String token = res.str("access_token");
            if (token == null) {
                throw new BankAuthException(true, "Token response had no access_token");
            }
            Integer ttl = res.integer("expires_in");
            return new CachedToken(token, clock.instant().plusSeconds(ttl != null ? ttl : DEFAULT_TTL_SECONDS));
        };
    }

    private static String describe(ExternalCallResult res) {
        if (res.error() != null) {
            return res.error();
        }
        String err = res.str("error");
        return "HTTP " + res.httpStatus() + (err != null ? " (" + err + ")" : "");
    }
}
