package com.payrelay.api.bank;

import com.payrelay.api.bank.BankSpec.AuthType;
import com.payrelay.api.bank.BankSpec.FieldRule;
import com.payrelay.api.bank.auth.SecretResolver;
import com.payrelay.api.bank.mapping.MappingSources;
import com.payrelay.api.bank.mapping.Transform;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Stream;

/** Checks a bank spec before it is saved, so a bad config never reaches a live payment. */
@Component
public class BankSpecValidator {

    private final SecretResolver secrets;

    public BankSpecValidator(SecretResolver secrets) {
        this.secrets = secrets;
    }

    public List<String> validate(BankSpec spec) {
        List<String> errors = new ArrayList<>();
        if (spec == null) {
            return List.of("The spec is empty.");
        }

        if (spec.endpoint() == null || blank(spec.endpoint().path()) || !spec.endpoint().path().startsWith("/")) {
            errors.add("Endpoint path is required and must start with '/'.");
        }

        validateAuth(spec.auth(), errors);

        var lookups = spec.lookupsOrEmpty();
        lookups.forEach((name, table) -> {
            if (blank(name)) errors.add("A lookup table has no name.");
            if (table == null || table.isEmpty()) errors.add("Lookup table '" + name + "' has no entries.");
        });

        if (spec.request() == null || spec.request().bodyOrEmpty().isEmpty()) {
            errors.add("Add at least one body field.");
        } else {
            validateRules(spec.request().headersOrEmpty(), "Header", lookups, errors);
            validateRules(spec.request().bodyOrEmpty(), "Body field", lookups, errors);
            validateStructure(spec.request().bodyOrEmpty(), errors);

            // Without the transfer key, a retry after a lost response looks like a brand-new payment to the bank.
            boolean sendsKey = Stream.concat(spec.request().headersOrEmpty().stream(), spec.request().bodyOrEmpty().stream())
                    .anyMatch(r -> "transferKey".equals(r.source()));
            if (!sendsKey) {
                errors.add("No header or body field sends 'transferKey'. Without it, a retry after a lost response could pay twice.");
            }
        }

        var r = spec.response();
        if (r == null || blank(r.statusPath())) errors.add("Response status path is required.");
        if (r == null || r.successValues() == null || r.successValues().isEmpty()) errors.add("List at least one success status value.");
        if (r == null || blank(r.referencePath())) errors.add("Response reference path is required.");
        if (r != null && r.successValues() != null && r.rejectedValues() != null) {
            r.successValues().stream().filter(r.rejectedValues()::contains)
                    .forEach(v -> errors.add("Status '" + v + "' is listed as both success and rejected."));
        }
        return errors;
    }

    private void validateAuth(BankSpec.Auth auth, List<String> errors) {
        if (auth == null || auth.type() == null) {
            errors.add("Choose an authentication type.");
            return;
        }
        if (blank(auth.secretRef())) {
            errors.add("Authentication needs a secret reference.");
        } else if (secrets.resolve(auth.secretRef()).isEmpty()) {
            errors.add("Secret '" + auth.secretRef() + "' is not configured. Set payrelay.secrets." + auth.secretRef() + " in the environment.");
        }
        switch (auth.type()) {
            case API_KEY -> {
                if (blank(auth.header())) errors.add("API key authentication needs a header name.");
            }
            case OAUTH2_CLIENT_CREDENTIALS -> {
                if (blank(auth.tokenPath()) || !auth.tokenPath().startsWith("/")) errors.add("OAuth2 needs a token path starting with '/'.");
                if (blank(auth.clientId())) errors.add("OAuth2 needs a client ID.");
            }
            case HMAC_SHA256 -> {
                if (blank(auth.keyId())) errors.add("HMAC signing needs a key ID.");
                if (blank(auth.signatureHeader())) errors.add("HMAC signing needs a signature header name.");
                if (blank(auth.timestampHeader())) errors.add("HMAC signing needs a timestamp header name.");
            }
        }
    }

    private void validateRules(List<FieldRule> rules, String kind, Map<String, Map<String, String>> lookups, List<String> errors) {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < rules.size(); i++) {
            FieldRule rule = rules.get(i);
            String label = kind + " " + (i + 1) + (blank(rule.target()) ? "" : " ('" + rule.target() + "')");
            if (blank(rule.target())) {
                errors.add(label + " needs a target name.");
            } else if (!seen.add(rule.target())) {
                errors.add(label + " is defined twice.");
            }
            boolean hasSource = !blank(rule.source());
            boolean hasConstant = rule.constant() != null;
            if (hasSource == hasConstant) {
                errors.add(label + " needs either a source field or a fixed value, not both.");
            } else if (hasSource && !MappingSources.isKnown(rule.source())) {
                errors.add(label + " reads unknown source '" + rule.source() + "'.");
            }
            for (String t : rule.transformsOrEmpty()) {
                try {
                    Transform.Step step = Transform.Step.parse(t);
                    if (step.transform() == Transform.LOOKUP && !lookups.containsKey(step.argument())) {
                        errors.add(label + " uses lookup table '" + step.argument() + "', which doesn't exist.");
                    }
                } catch (IllegalArgumentException e) {
                    errors.add(label + ": " + e.getMessage() + ".");
                }
            }
        }
    }

    /** Catches rules like "a" and "a.b" that can't both exist in one JSON object. */
    private void validateStructure(List<FieldRule> rules, List<String> errors) {
        List<String> targets = rules.stream().map(FieldRule::target).filter(t -> !blank(t)).toList();
        for (String a : targets) {
            if (a.startsWith(".") || a.endsWith(".") || a.contains("..")) {
                errors.add("Body field '" + a + "' is not a valid path.");
            }
            for (String b : targets) {
                if (b.startsWith(a + ".")) {
                    errors.add("Body field '" + a + "' can't be a value because '" + b + "' is nested under it.");
                }
            }
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
