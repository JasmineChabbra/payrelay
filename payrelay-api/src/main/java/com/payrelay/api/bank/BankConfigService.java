package com.payrelay.api.bank;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.payrelay.api.bank.mapping.MappingEngine;
import com.payrelay.api.bank.mapping.MappingException;
import com.payrelay.api.bank.mapping.MappingSources;
import com.payrelay.api.bank.mapping.Transform;
import com.payrelay.api.domain.Payment;
import com.payrelay.api.repository.PaymentRepository;
import com.payrelay.api.service.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class BankConfigService {

    /** A bank config ready for use by the transfer step. */
    public record LoadedBank(String code, String displayName, boolean enabled, long version, BankSpec spec) {
    }

    public record BankSummary(String code, String displayName, boolean enabled, BankSpec.AuthType authType,
                              int bodyFields, long version, Instant updatedAt) {
    }

    public record BankView(String code, String displayName, boolean enabled, long version, Instant updatedAt, BankSpec spec) {
    }

    public record UpdateRequest(String displayName, boolean enabled, long version, BankSpec spec) {
    }

    public record Preview(String method, String path, Map<String, String> headers, Object body, List<String> errors,
                          Map<String, Object> sample) {
    }

    public record HistoryEntry(long version, String displayName, boolean enabled, Instant changedAt, BankSpec spec) {
    }

    private final BankConfigRepository configs;
    private final BankConfigHistoryRepository history;
    private final PaymentRepository payments;
    private final BankSpecValidator validator;
    private final MappingEngine mapping;
    private final ObjectMapper mapper;
    private final TransactionTemplate tx;
    private final Clock clock;
    /** Parsed specs by "code:version". A new version gets a new key, so there's nothing to invalidate. */
    private final Map<String, BankSpec> parsed = new ConcurrentHashMap<>();

    public BankConfigService(BankConfigRepository configs, BankConfigHistoryRepository history, PaymentRepository payments,
                             BankSpecValidator validator, MappingEngine mapping, ObjectMapper mapper,
                             TransactionTemplate tx, Clock clock) {
        this.configs = configs;
        this.history = history;
        this.payments = payments;
        this.validator = validator;
        this.mapping = mapping;
        this.mapper = mapper;
        this.tx = tx;
        this.clock = clock;
    }

    // ---- used by payments ----

    public LoadedBank load(String code) {
        BankConfig c = configs.findById(code).orElseThrow(() -> ApiException.notFound("Bank " + code + " not found"));
        BankSpec spec = parsed.computeIfAbsent(code + ":" + c.getVersion(), k -> parse(c.getSpec()));
        return new LoadedBank(c.getBankCode(), c.getDisplayName(), c.isEnabled(), c.getVersion(), spec);
    }

    public boolean exists(String code) {
        return code != null && configs.existsById(code);
    }

    public List<String> codes() {
        return configs.findAllByOrderByBankCodeAsc().stream().map(BankConfig::getBankCode).toList();
    }

    // ---- console API ----

    public List<BankSummary> list() {
        return configs.findAllByOrderByBankCodeAsc().stream().map(c -> {
            BankSpec s = parse(c.getSpec());
            return new BankSummary(c.getBankCode(), c.getDisplayName(), c.isEnabled(),
                    s.auth() == null ? null : s.auth().type(),
                    s.request() == null ? 0 : s.request().bodyOrEmpty().size(), c.getVersion(), c.getUpdatedAt());
        }).toList();
    }

    public BankView get(String code) {
        LoadedBank b = load(code);
        BankConfig c = configs.findById(code).orElseThrow();
        return new BankView(b.code(), b.displayName(), b.enabled(), b.version(), c.getUpdatedAt(), b.spec());
    }

    /**
     * Saves a new version. The caller sends the version it started editing from; if someone saved in
     * between, the save is refused (409) rather than silently overwriting their change.
     */
    public BankView update(String code, UpdateRequest req) {
        if (req.displayName() == null || req.displayName().isBlank()) {
            throw new SpecValidationException(List.of("Display name is required."));
        }
        List<String> errors = validator.validate(req.spec());
        if (!errors.isEmpty()) {
            throw new SpecValidationException(errors);
        }
        String json = write(req.spec());
        tx.executeWithoutResult(s -> {
            BankConfig c = configs.findById(code).orElseThrow(() -> ApiException.notFound("Bank " + code + " not found"));
            if (c.getVersion() != req.version()) {
                throw ApiException.conflict("This bank was changed by someone else (now version " + c.getVersion()
                        + ", you edited version " + req.version() + "). Reload to see their changes.");
            }
            c.update(req.displayName().trim(), req.enabled(), json, clock.instant());
            BankConfig saved = configs.saveAndFlush(c);
            history.save(BankConfigHistory.of(saved));
        });
        return get(code);
    }

    /**
     * Renders exactly what the bank would receive for a sample (or real) payment under the given spec,
     * which doesn't need to be saved yet. OAuth tokens aren't fetched for a preview.
     */
    public Preview preview(String code, BankSpec spec, UUID paymentId) {
        Payment payment = paymentId == null
                ? MappingSources.sample(code)
                : payments.findById(paymentId).orElseThrow(() -> ApiException.notFound("Payment " + paymentId + " not found"));
        Map<String, Object> sample = MappingSources.from(payment);
        List<String> errors = new ArrayList<>(validator.validate(spec));
        String path = spec != null && spec.endpoint() != null ? spec.endpoint().path() : null;

        Map<String, String> headers = new LinkedHashMap<>();
        Object body = null;
        if (spec != null && spec.request() != null) {
            try {
                var mapped = mapping.map(spec, sample);
                body = mapped.body();
                headers.putAll(mapped.headers());
                if (spec.auth() != null && spec.auth().type() != null) {
                    headers.putAll(previewAuthHeaders(spec.auth()));
                }
            } catch (MappingException e) {
                e.problems().stream().filter(p -> !errors.contains(p)).forEach(errors::add);
            }
        }
        return new Preview("POST", path, headers, body, errors, sample);
    }

    private Map<String, String> previewAuthHeaders(BankSpec.Auth auth) {
        return switch (auth.type()) {
            case API_KEY -> Map.of(nz(auth.header()), nz(auth.prefix()) + "{secret " + auth.secretRef() + "}");
            case OAUTH2_CLIENT_CREDENTIALS -> Map.of("Authorization", "Bearer {token from " + auth.tokenPath() + "}");
            case HMAC_SHA256 -> {
                // Signed with a placeholder so the preview shows the real header layout without the real secret.
                String ts = String.valueOf(clock.instant().getEpochSecond());
                Map<String, String> h = new LinkedHashMap<>();
                h.put(nz(auth.timestampHeader()), ts);
                h.put(nz(auth.signatureHeader()), "{HMAC-SHA256 of timestamp, method, path, body hash}");
                if (auth.keyIdHeader() != null && !auth.keyIdHeader().isBlank()) h.put(auth.keyIdHeader(), nz(auth.keyId()));
                yield h;
            }
        };
    }

    public List<HistoryEntry> history(String code) {
        return history.findTop50ByBankCodeOrderByVersionDesc(code).stream()
                .map(h -> new HistoryEntry(h.getVersion(), h.getDisplayName(), h.isEnabled(), h.getChangedAt(), parse(h.getSpec())))
                .toList();
    }

    /** What the console needs to build the editor: source fields, transforms and auth types. */
    public Map<String, Object> meta() {
        return Map.of(
                "sources", MappingSources.ALL.stream().map(s -> Map.of("name", s.name(), "description", s.description())).toList(),
                "transforms", Arrays.stream(Transform.values())
                        .map(t -> Map.of("name", t.name(), "description", t.description, "takesArgument", t.takesArgument)).toList(),
                "authTypes", Arrays.stream(BankSpec.AuthType.values()).map(Enum::name).toList());
    }

    private BankSpec parse(String json) {
        try {
            return mapper.readValue(json, BankSpec.class);
        } catch (Exception e) {
            throw new IllegalStateException("Stored bank spec is not valid JSON", e);
        }
    }

    private String write(BankSpec spec) {
        try {
            return mapper.writeValueAsString(spec);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /** A spec failed validation; carries every problem found. */
    public static class SpecValidationException extends ApiException {
        private final List<String> errors;

        public SpecValidationException(List<String> errors) {
            super(org.springframework.http.HttpStatus.BAD_REQUEST, "Invalid bank configuration",
                    errors.size() == 1 ? errors.getFirst() : errors.size() + " problems need fixing before this can be saved.");
            this.errors = errors;
        }

        public List<String> errors() {
            return errors;
        }
    }
}
