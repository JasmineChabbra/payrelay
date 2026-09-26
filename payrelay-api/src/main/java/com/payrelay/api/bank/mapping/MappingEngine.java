package com.payrelay.api.bank.mapping;

import com.payrelay.api.bank.BankSpec;
import com.payrelay.api.bank.BankSpec.FieldRule;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Turns a payment into a bank-specific request by applying a bank's field rules.
 *
 * <p>Adding a bank, or changing what a bank expects, is a config change reviewed in the console,
 * not a new adapter class and a deploy. All problems are collected and reported together, so a
 * broken config is fixed in one pass.
 */
@Component
public class MappingEngine {

    /** Headers and body for one bank request, before authentication is added. */
    public record MappedRequest(Map<String, String> headers, Map<String, Object> body) {
    }

    public MappedRequest map(BankSpec spec, Map<String, Object> sources) {
        List<String> problems = new ArrayList<>();
        var lookups = spec.lookupsOrEmpty();

        Map<String, String> headers = new LinkedHashMap<>();
        for (FieldRule rule : spec.request().headersOrEmpty()) {
            resolve(rule, sources, lookups, problems, "header").ifPresent(v -> headers.put(rule.target(), String.valueOf(v)));
        }

        Map<String, Object> body = new LinkedHashMap<>();
        for (FieldRule rule : spec.request().bodyOrEmpty()) {
            resolve(rule, sources, lookups, problems, "body field").ifPresent(v -> {
                String error = put(body, rule.target(), v);
                if (error != null) {
                    problems.add(error);
                }
            });
        }

        if (!problems.isEmpty()) {
            throw new MappingException(problems);
        }
        return new MappedRequest(headers, body);
    }

    private Optional<Object> resolve(FieldRule rule, Map<String, Object> sources,
                                     Map<String, Map<String, String>> lookups, List<String> problems, String kind) {
        Object value = rule.constant() != null ? rule.constant() : sources.get(rule.source());
        if (value instanceof String s && s.isBlank()) {
            value = null;
        }
        if (value == null && rule.defaultValue() != null) {
            value = rule.defaultValue();
        }
        if (value == null) {
            if (rule.isRequired()) {
                problems.add(kind + " '" + rule.target() + "': source '" + rule.source() + "' is empty and no default is set");
            }
            return Optional.empty();
        }
        try {
            for (Transform.Step step : Transform.parseAll(rule.transformsOrEmpty())) {
                value = step.apply(value, lookups);
            }
            return Optional.of(value);
        } catch (IllegalArgumentException e) {
            problems.add(kind + " '" + rule.target() + "': " + e.getMessage());
            return Optional.empty();
        }
    }

    /** Sets a dotted path like "beneficiary.account.number", creating nested objects as needed. */
    @SuppressWarnings("unchecked")
    static String put(Map<String, Object> root, String path, Object value) {
        String[] keys = path.split("\\.");
        Map<String, Object> node = root;
        for (int i = 0; i < keys.length - 1; i++) {
            Object child = node.computeIfAbsent(keys[i], k -> new LinkedHashMap<String, Object>());
            if (!(child instanceof Map)) {
                return "body field '" + path + "' conflicts with '" + String.join(".", Arrays.copyOf(keys, i + 1)) + "', which is already a value";
            }
            node = (Map<String, Object>) child;
        }
        String last = keys[keys.length - 1];
        if (node.get(last) instanceof Map) {
            return "body field '" + path + "' conflicts with fields nested under it";
        }
        node.put(last, value);
        return null;
    }
}
