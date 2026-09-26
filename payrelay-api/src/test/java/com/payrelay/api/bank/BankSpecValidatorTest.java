package com.payrelay.api.bank;

import com.payrelay.api.bank.BankSpec.*;
import com.payrelay.api.bank.auth.SecretResolver;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BankSpecValidatorTest {

    private final BankSpecValidator validator = new BankSpecValidator(
            new SecretResolver(new MockEnvironment().withProperty("payrelay.secrets.key", "s3cret")));

    private static final Response RESPONSE = new Response("status", List.of("OK"), List.of("NO"), "ref", "reason");
    private static final Auth API_KEY = new Auth(AuthType.API_KEY, "key", "X-Key", null, null, null, null, null, null, null, null);

    private static FieldRule rule(String target, String source) {
        return new FieldRule(target, source, null, null, null, null);
    }

    private static BankSpec spec(Auth auth, List<FieldRule> headers, List<FieldRule> body) {
        return new BankSpec(new Endpoint("/pay"), auth, new Request(headers, body), RESPONSE, Map.of());
    }

    @Test
    void acceptsAValidSpec() {
        assertThat(validator.validate(spec(API_KEY, List.of(rule("Idem", "transferKey")), List.of(rule("amount", "amount"))))).isEmpty();
    }

    @Test
    void refusesASpecThatNeverSendsTheTransferKey() {
        assertThat(validator.validate(spec(API_KEY, List.of(), List.of(rule("amount", "amount")))))
                .anyMatch(e -> e.contains("transferKey") && e.contains("pay twice"));
    }

    @Test
    void reportsUnknownSourcesTransformsLookupsAndDuplicates() {
        var errors = validator.validate(spec(API_KEY, List.of(rule("Idem", "transferKey")), List.of(
                rule("a", "nope"),
                new FieldRule("b", "amount", null, List.of("SHOUT"), null, null),
                new FieldRule("c", "purpose", null, List.of("LOOKUP:missing"), null, null),
                new FieldRule("d", "amount", null, List.of("TRUNCATE:zero"), null, null),
                rule("a", "amount"))));
        assertThat(errors).anyMatch(e -> e.contains("unknown source 'nope'"))
                .anyMatch(e -> e.contains("unknown transform 'SHOUT'"))
                .anyMatch(e -> e.contains("lookup table 'missing'"))
                .anyMatch(e -> e.contains("positive whole number"))
                .anyMatch(e -> e.contains("defined twice"));
    }

    @Test
    void reportsNestingConflicts() {
        assertThat(validator.validate(spec(API_KEY, List.of(rule("Idem", "transferKey")),
                List.of(rule("a", "amount"), rule("a.b", "currency")))))
                .anyMatch(e -> e.contains("nested under it"));
    }

    @Test
    void requiresAConfiguredSecretAndTypeSpecificFields() {
        Auth oauth = new Auth(AuthType.OAUTH2_CLIENT_CREDENTIALS, "not-set", null, null, "token", null, null, null, null, null, null);
        assertThat(validator.validate(spec(oauth, List.of(rule("Idem", "transferKey")), List.of(rule("a", "amount")))))
                .anyMatch(e -> e.contains("'not-set' is not configured"))
                .anyMatch(e -> e.contains("token path"))
                .anyMatch(e -> e.contains("client ID"));
    }

    @Test
    void refusesAStatusThatIsBothSuccessAndRejected() {
        var r = new Response("status", List.of("OK"), List.of("OK"), "ref", null);
        var s = new BankSpec(new Endpoint("/pay"), API_KEY, new Request(List.of(rule("Idem", "transferKey")), List.of(rule("a", "amount"))), r, Map.of());
        assertThat(validator.validate(s)).anyMatch(e -> e.contains("both success and rejected"));
    }
}
