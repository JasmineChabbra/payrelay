package com.payrelay.api.bank.auth;

import com.payrelay.api.bank.BankSpec;
import com.payrelay.api.bank.BankSpec.AuthType;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BankAuthTest {

    private final SecretResolver secrets = new SecretResolver(new MockEnvironment()
            .withProperty("payrelay.secrets.hmac", "orbit-demo-hmac-secret")
            .withProperty("payrelay.secrets.zen", "zenith-demo-secret"));

    private static final BankSpec.Auth OAUTH = new BankSpec.Auth(AuthType.OAUTH2_CLIENT_CREDENTIALS, "zen",
            null, null, "/token", "payrelay", "scope", null, null, null, null);

    @Test
    void hmacSignatureCoversTimestampPathAndBody() {
        String sig = BankAuthenticator.sign("secret", "1700000000", "POST", "/pay", "{\"a\":1}");
        assertThat(sig).matches("[0-9a-f]{64}");
        assertThat(BankAuthenticator.sign("secret", "1700000000", "POST", "/pay", "{\"a\":1}")).isEqualTo(sig);
        assertThat(BankAuthenticator.sign("secret", "1700000001", "POST", "/pay", "{\"a\":1}")).isNotEqualTo(sig);
        assertThat(BankAuthenticator.sign("secret", "1700000000", "POST", "/pay", "{\"a\":2}")).isNotEqualTo(sig);
        assertThat(BankAuthenticator.sign("secret", "1700000000", "POST", "/other", "{\"a\":1}")).isNotEqualTo(sig);
    }

    @Test
    void hmacHeadersFollowTheConfig() {
        var auth = new BankSpec.Auth(AuthType.HMAC_SHA256, "hmac", null, null, null, null, null,
                "key-1", "X-Sig", "X-Ts", "X-Key-Id");
        var clock = Clock.fixed(Instant.ofEpochSecond(1_700_000_000), ZoneOffset.UTC);
        var result = new BankAuthenticator(secrets, null, clock).authenticate("ORBIT", auth, "/pay", "{}", "cid");

        assertThat(result.headers()).containsEntry("X-Ts", "1700000000").containsEntry("X-Key-Id", "key-1");
        assertThat(result.headers().get("X-Sig")).isEqualTo(BankAuthenticator.sign("orbit-demo-hmac-secret", "1700000000", "POST", "/pay", "{}"));
        assertThat(result.secretsToHide()).contains("orbit-demo-hmac-secret");
    }

    @Test
    void tokenIsCachedUntilShortlyBeforeExpiry() {
        var clock = new MutableClock(Instant.ofEpochSecond(0));
        AtomicInteger fetches = new AtomicInteger();
        var service = new OAuthTokenService(secrets, (auth, secret, cid) ->
                new OAuthTokenService.CachedToken("t" + fetches.incrementAndGet(), clock.instant().plusSeconds(300)), clock);

        assertThat(service.token("ZENITH", OAUTH, "c")).isEqualTo("t1");
        clock.now = Instant.ofEpochSecond(260);
        assertThat(service.token("ZENITH", OAUTH, "c")).isEqualTo("t1");
        clock.now = Instant.ofEpochSecond(271); // inside the 30s safety window
        assertThat(service.token("ZENITH", OAUTH, "c")).isEqualTo("t2");
    }

    @Test
    void invalidateOnlyDropsTheRejectedToken() {
        var clock = new MutableClock(Instant.ofEpochSecond(0));
        AtomicInteger fetches = new AtomicInteger();
        var service = new OAuthTokenService(secrets, (auth, secret, cid) ->
                new OAuthTokenService.CachedToken("t" + fetches.incrementAndGet(), clock.instant().plusSeconds(300)), clock);

        service.token("ZENITH", OAUTH, "c");
        service.invalidate("ZENITH", OAUTH, "some-older-token");
        assertThat(service.token("ZENITH", OAUTH, "c")).isEqualTo("t1"); // kept
        service.invalidate("ZENITH", OAUTH, "t1");
        assertThat(service.token("ZENITH", OAUTH, "c")).isEqualTo("t2"); // refreshed
    }

    @Test
    void concurrentCallersShareOneTokenFetch() throws Exception {
        var clock = new MutableClock(Instant.ofEpochSecond(0));
        AtomicInteger fetches = new AtomicInteger();
        var service = new OAuthTokenService(secrets, (auth, secret, cid) -> {
            fetches.incrementAndGet();
            sleep(50);
            return new OAuthTokenService.CachedToken("t", clock.instant().plusSeconds(300));
        }, clock);

        ExecutorService pool = Executors.newFixedThreadPool(16);
        List<Future<String>> results = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            results.add(pool.submit(() -> service.token("ZENITH", OAUTH, "c")));
        }
        for (Future<String> f : results) {
            assertThat(f.get(5, TimeUnit.SECONDS)).isEqualTo("t");
        }
        pool.shutdown();
        assertThat(fetches.get()).isEqualTo(1);
    }

    @Test
    void missingSecretIsAConfigurationErrorNotARetry() {
        var auth = new BankSpec.Auth(AuthType.API_KEY, "absent", "X-Key", null, null, null, null, null, null, null, null);
        assertThatThrownBy(() -> new BankAuthenticator(secrets, null, Clock.systemUTC()).authenticate("NOVA", auth, "/p", "{}", "c"))
                .isInstanceOf(BankAuthException.class)
                .satisfies(e -> assertThat(((BankAuthException) e).retryable()).isFalse());
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static class MutableClock extends Clock {
        volatile Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
